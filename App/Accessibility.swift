import Foundation
import RerunGuideCore

/// Back pass-through and the one VoiceOver string. RemoteCaptureUIView is the app's single accessibility element
/// (screens are pure renderings with nothing focusable), so `accessibilityDescription` is what VoiceOver reads after
/// every press. Plain strings that mirror what each screen draws for the focused thing.
extension AppModel {
    /// HIG: Back at the app's top level returns to the Home screen. Every other screen has an in-app Back.
    /// Attract mode keeps Menu: the first press of any button wakes the guide, Menu included. So does the number box.
    var backExitsApp: Bool { overlay == nil && channelEntry?.missing != false && ((screen == .guide && !remoteHeld && !attracting) || screen == .welcome) }

    /// The number box while it's up (it has the remote while digits are being typed), else what's focused.
    var accessibilityDescription: String {
        guard overlay == nil, let e = channelEntry else { return focusDescription }
        if e.missing { return "No channel \(Int(e.digits) ?? 0). " + focusDescription }
        return "Channel number \(e.digits)" + (entryChannel.map { ". Channel \($0.number) \($0.name)" } ?? "")
    }

    private var focusDescription: String {
        switch overlay {
        case .options:
            let rows = OptionRow.allCases
            let label: String = switch rows[min(focusIndex, rows.count - 1)] {
            case .profile: "\(profile.name), Switch profile"
            case .guide: "Guide, all channels"
            case .movies: "Movies guide"
            case .tv: "TV Shows guide"
            case .search: "Search"
            case .channels: "Channels"
            case .settings: "Settings"
            case .theme: "Theme, \(profile.theme.displayName). Left and Right switch, Select closes the menu"
            }
            return "Options. \(label)"
        case .info(let key):
            guard let (ch, slot) = program(key) else { return "Program info" }
            let acts = infoActions(key)
            return describe(ch, slot) + (acts.indices.contains(focusIndex) ? ". \(acts[focusIndex].label)" : "")
        case .newChannel:
            if newChannelStep == 0 {
                let kinds = ChannelBuilder.CandidateKind.allCases
                return "New channel. \(kinds[min(focusIndex, kinds.count - 1)].rawValue)"
            }
            let items = candidateCache
            guard items.indices.contains(focusIndex) else { return "New channel. Nothing to pick" }
            return "New channel. \(items[focusIndex].name), \(items[focusIndex].count) \(newChannelType == .series ? "episodes" : "films")"
        case .makeChannel:
            guard let e = searchSelection else { return "Make a channel" }
            let acts = makeActions
            return "\(e.title). \(makePeople == nil ? airsText(e) : "Pick who the channel follows")" + (acts.indices.contains(focusIndex) ? ". \(acts[focusIndex].label)" : "")
        case nil: break
        }
        switch screen {
        case .welcome:
            let c = Self.welcomeCards[min(focusIndex, Self.welcomeCards.count - 1)]
            return "\(c.name). \(c.desc)"
        case .address:
            let row = focusIndex == 0 ? "Server address, \(address.isEmpty ? "empty" : address)"
                : focusIndex <= foundServers.count ? "\(foundServers[focusIndex - 1].name), \(foundServers[focusIndex - 1].baseURL.absoluteString)"
                : "Continue"
            return row + errorLine
        case .link:
            let code = linkCode.map { "Code \($0.map(String.init).joined(separator: " "))" } ?? "Waiting for the code"
            let button = serviceKind == .jellyfin ? "Sign in with a password instead" : "Type a server address instead"
            return "Sign in to \(serviceKind.displayName). \(code). \(button)" + errorLine
        case .login:
            let row = focusIndex == 0 ? "Username, \(username.isEmpty ? "empty" : username)"
                : focusIndex == 1 ? "Password, \(password.isEmpty ? "empty" : "entered")" : "Sign in"
            return row + errorLine
        case .libraries:
            guard librarySections.indices.contains(focusIndex) else { return "Continue" + errorLine }
            let s = librarySections[focusIndex]
            return "\(s.title), \(librarySelection.contains(s.id) ? "On" : "Off")"
        case .building:
            if let e = libraryError { return "\(e). Retry" }
            return buildDone ? "Sign-on complete. Start flipping" : "Configuring channels"
        case .profiles:
            let profiles = persisted.profiles
            guard profiles.indices.contains(focusIndex) else { return "Add profile" }
            let p = profiles[focusIndex]
            if let m = profileMenu, profiles.indices.contains(m) { return "\(profileActions[min(profileMenuIndex, profileActions.count - 1)]), for \(profiles[m].name)" }
            let current = p.id == persisted.currentProfileID ? ", watching now, Select to rename" : ""
            return "\(p.name), \(channels.filter { !p.hiddenChannelIDs.contains($0.id) }.count) channels\(current)"
        case .guide:
            guard let k = guideKey(), let (ch, slot) = program(k) else { return libraryLoading ? "Loading your library" : "Nothing to schedule yet" }
            return describe(ch, slot)
        case .watch:
            guard let info = watchInfo() else { return "Watching" }
            let spot = SpotOnAir.shared
            if spot.started != nil {   // the house spot, labeled "Ad" on screen
                return "Ad, Message from Soyboi. " + (!spot.skippable ? "You can skip it in a few seconds"
                    : Platform.isTV ? "Select to skip" : "\(Platform.verb) Skip to skip it")
            }
            var s = describe(info.channel, info.slot)
            if playback?.paused == true { s += ". Paused" }
            if info.isBreak, Platform.isTV {   // touch: the card's own buttons
                if let next = info.next { s += ". Select watches \(next.item.displayTitle) now" }
                s += ". Play Pause watches \(info.slot.item.displayTitle) again"
            }
            let acts = watchActions()
            if bannerVisible, acts.indices.contains(bannerFocus) { s += barFocused ? ". Progress, Left and Right skip 10 seconds" : ". \(acts[bannerFocus].label)" }
            return s
        case .channels:
            guard let ch = selectedChannel else { return "New channel, From a director, an actor, or one show" }
            let head = "Channel \(ch.number) \(ch.name)"
            guard channelColumn == 1, let field = ChannelField(rawValue: channelFieldIndex) else {
                return "\(head), \(isHidden(ch.id) ? "Hidden" : ch.isCustom ? "Custom" : "Auto")"
            }
            let row: String = switch field {
            case .name: "Name, \(ch.name)"
            case .number: "Channel number, \(numberDraft ?? String(ch.number))"
            case .order: "Order, \(ch.order.rawValue)"
            case .breaks: "Trailer breaks, \(ch.trailerBreaks ? "On" : "Off")"
            case .tune: "When you tune in, \(AppModel.tuneLabel(ch.definition.tuneMode, default: settings.tuneMode))"
            case .show: "Show in \(profile.name)’s guide, \(isHidden(ch.id) ? "Off" : "On")"
            case .delete: ch.isCustom ? "Delete channel" : "Reset to automatic"
            }
            return "\(head). \(row)"
        case .search:
            guard let e = searchSelection else {
                let found = searchQuery.isEmpty ? "" : searchResults.isEmpty ? ". Nothing matches" : ". \(searchResults.count) found"
                return "Search, \(searchQuery.isEmpty ? "empty" : searchQuery)\(found)"
            }
            return "\(e.title), \([e.year.map(String.init), e.isShow ? "Show" : "Movie"].compactMap { $0 }.joined(separator: ", ")). \(airsText(e))"
        case .settings:
            let rows = settingsRows.filter { $0.key != nil }
            guard rows.indices.contains(focusIndex) else { return "Settings" }
            let r = rows[focusIndex]
            return [r.label, r.value].filter { !$0.isEmpty }.joined(separator: ", ") + ". " + r.desc
        }
    }

    private var errorLine: String { authError.map { ". \($0)" } ?? "" }

    /// "Channel 4 Gumshoe. Columbo, Any Old Port in a Storm. On now, 23 minutes in."
    private func describe(_ ch: Channel, _ slot: Slot) -> String {
        let now = Date.now
        let when: String = switch status(slot, at: now) {
        case .live: "On now, \(Int(now.timeIntervalSince(slot.start) / 60)) minutes in"
        case .break: "On now, between programs"
        case .later: "Starts in \(Int((slot.start.timeIntervalSince(now) / 60).rounded(.up))) minutes"
        case .past: "Aired earlier"
        }
        let what = [slot.item.displayTitle, slot.item.subtitle].filter { !$0.isEmpty }.joined(separator: ", ")
        return "Channel \(ch.number) \(ch.name). \(what). \(when)"
    }
}
