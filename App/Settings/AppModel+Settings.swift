import Foundation
import RerunGuideCore

/// One entry of the Settings list (port of the prototype's setRows). `key == nil` is a section header.
struct SettingsRow: Identifiable {
    enum Key: String { case theme, crt, auto, idle, attract, tune, breaks, idents, quality, audio, subs, subLang, profile, server, rebuild, signout, about, saver, spots }
    let key: Key?
    let label: String
    var value = ""
    var desc = ""
    var id: String { key?.rawValue ?? label }
    static func head(_ label: String) -> SettingsRow { SettingsRow(key: nil, label: label) }
}

/// The channel editor's detail fields, in order (the prototype's F array).
enum ChannelField: Int, CaseIterable { case name, number, order, breaks, tune, show, delete }

/// Profiles, Settings and the Channel editor (port of the prototype's k_profiles, k_settings/setRows, k_channels/commitNum/chIdx).
extension AppModel {
    /// "Join in progress" / "From the beginning", or "Default · …" when the channel follows the app setting.
    static func tuneLabel(_ mode: TuneMode?, default d: TuneMode) -> String {
        let name = { (m: TuneMode) in m == .live ? "Join in progress" : "From the beginning" }
        return mode.map(name) ?? "Default · \(name(d))"
    }


    /// Settings › About. The beacon is Soyboi's other project (soyboi.tech). Ten lines at most: the pane is 600 high.
    @MainActor static let aboutText = """
    From Soyboi LLC in San Diego, makers of the beacon: a pocket counter-surveillance device that spots license-plate \
    readers, body cams and drones. soyboi.tech
    Rerun Guide talks to your media server, Plex or Emby if you sign in with them, and Apple for the Sample station. \
    No analytics, no tracking, and nothing about you is sent to us.
    Privacy: rerunguide.tv/privacy · Support: rerunguide.tv/support
    """
    static let version: String = {
        let d = Bundle.main.infoDictionary
        let v = d?["CFBundleShortVersionString"] as? String ?? "", b = d?["CFBundleVersion"] as? String ?? ""
        return b.isEmpty ? v : "\(v) (\(b))"
    }()

    // MARK: Profiles

    /// Avatar colors for new profiles, rotating by profile count (the prototype's seed colors + its "Guest" purple).
    static let profilePalette = ["#f0a33a", "#7d97ff", "#7fd39a", "#c9a0ff", "#ff8a80"]

    /// Actions in the row that Play/Pause opens under a tile: Rename, and Delete while another profile remains.
    var profileActions: [String] { persisted.profiles.count > 1 ? ["Rename", "Delete"] : ["Rename"] }

    func pressProfiles(_ cmd: RemoteCommand) {
        let profiles = persisted.profiles
        if let m = profileMenu {
            switch cmd {
            case .left, .right: profileMenuIndex = max(0, min(profileActions.count - 1, profileMenuIndex + (cmd == .right ? 1 : -1)))
            case .back: profileMenu = nil
            case .select:
                profileMenu = nil
                if profileMenuIndex == 0 { editingField = "profileName"; return }
                let p = profiles[m]
                persisted.profiles.remove(at: m)
                if p.id == persisted.currentProfileID { switchProfile(persisted.profiles[0].id); guideReset() } else { save() }
                focusIndex = min(focusIndex, persisted.profiles.count)
                toast("\(p.name) is gone.")
            default: break
            }
            return
        }
        if step(cmd, count: profiles.count + (profiles.count < 5 ? 1 : 0), horizontal: true) { return }
        if cmd == .back { go(.guide); return }
        if cmd == .play, focusIndex < profiles.count { profileMenu = focusIndex; profileMenuIndex = 0; return }
        guard cmd == .select else { return }
        guard focusIndex < profiles.count else {
            persisted.profiles.append(Profile(id: UUID().uuidString, name: "Profile \(profiles.count + 1)",
                                              colorHex: Self.profilePalette[profiles.count % Self.profilePalette.count],
                                              theme: .late, hiddenChannelIDs: []))
            save(); editingField = "profileName"; return   // focusIndex already points at the new tile
        }
        let p = profiles[focusIndex]
        // Switching to the profile already in use would do nothing, so Select there offers Rename / Delete (as Play/Pause does).
        if p.id == persisted.currentProfileID { profileMenu = focusIndex; profileMenuIndex = 0; return }
        switchProfile(p.id); guideReset(); go(.guide)
        toast("Hi, \(p.name).")
    }

    /// Commit the rename typed on the Profiles screen; an empty name keeps the old one.
    func commitProfileName(_ index: Int, _ name: String) {
        defer { editingField = nil }
        let n = name.trimmingCharacters(in: .whitespaces)
        guard persisted.profiles.indices.contains(index), !n.isEmpty else { return }
        persisted.profiles[index].name = n; save()
    }

    // MARK: Settings

    var settingsRows: [SettingsRow] {
        let on = { (b: Bool) in b ? "On" : "Off" }
        let s = settings, p = profile
        let films = library.count { $0.kind == .movie }
        let shows = Set(library.compactMap { $0.kind == .episode ? ($0.seriesID ?? $0.seriesTitle ?? $0.title) : nil }).count
        let service = server?.kind.displayName ?? "your server"
        let anyInput = Platform.isTV ? "Any button" : "Any \(Platform.verb.lowercased())"
        return [
            .head("Appearance"),
            SettingsRow(key: .theme, label: "Theme", value: p.theme.displayName, desc: "Late Show is warm and rounded. Midnight Movie is near-black with neon red, in a condensed face. Each profile keeps its own."),
            SettingsRow(key: .crt, label: "Old-TV screen lines", value: on(s.crtLines), desc: "A faint CRT texture over the guide’s preview window. The show itself is never touched."),
            .head("Guide"),
            SettingsRow(key: .auto, label: "Scroll listings on their own", value: on(s.autoScroll), desc: Platform.isTV ? "When nobody’s touching the remote, the guide drifts through the channels like the old preview channel." : "When you leave it alone, the guide drifts through the channels like the old preview channel."),
            SettingsRow(key: .idle, label: "Start drifting again after", value: "\(s.idleSeconds) seconds", desc: Platform.isTV ? "How long the guide waits after your last button press before it starts scrolling again." : "How long the guide waits after you stop browsing before it starts scrolling again."),
            SettingsRow(key: .attract, label: "Attract mode after", value: s.attractMinutes == 0 ? "Off" : "\(s.attractMinutes) min", desc: "Leave the guide alone this long and it settles into a slow storefront wall: a bigger preview, a big clock, listings drifting by. \(anyInput) wakes it."),
            SettingsRow(key: .saver, label: "Screensaver", value: ["logo": "Bouncing logo", "soyboi": "Bouncing Soyboi"][s.screensaver] ?? "Storefront wall", desc: "What attract mode shows. The storefront wall keeps the listings drifting under a big clock. The bouncing logo is the Rerun Guide logo drifting around the screen the way the DVD logo used to; Bouncing Soyboi does the same with the soyboi.tech mascot. \(anyInput) wakes the guide."),
            .head("Playback"),
            SettingsRow(key: .tune, label: "When you tune in", value: s.tuneMode == .live ? "Join in progress" : "Start from the beginning", desc: Platform.isTV ? "Join in progress keeps everyone on the same schedule. Press Play/Pause on any program to start it over either way." : "Join in progress keeps everyone on the same schedule. Pick Start from the beginning on any program to start it over either way."),
            SettingsRow(key: .breaks, label: "Trailers between programs", value: on(s.trailerBreaks), desc: "Fills the gap between programs with trailers from your library. Off shows a station card instead."),
            SettingsRow(key: .spots, label: "Messages from Soyboi", value: on(s.houseSpots), desc: "One in 15 trailer breaks opens with a 30-second spot for the beacon, Soyboi’s other project. It’s how Rerun Guide stays free. Turn it off and the trailers start right away. To report a spot, write to support@soyboi.tech."),
            SettingsRow(key: .idents, label: "Station idents", value: on(s.stationIdents), desc: "A quick channel card and a two-note sting when you change channels, and a ‘We’ll be right back’ card before trailer breaks."),
            SettingsRow(key: .quality, label: "Streaming quality", value: s.maxBitrateKbps == 0 ? "Original" : "\(s.maxBitrateKbps / 1000) Mbps", desc: "Caps the stream the server sends. Lower it for remote servers or a slow connection."),
            SettingsRow(key: .audio, label: "Audio language", value: MediaTrack.languageName(s.audioLanguage) ?? "Default", desc: "Picks this language’s audio track when a program has one. Otherwise the server’s default plays. Takes effect on the next program."),
            SettingsRow(key: .subs, label: "Subtitles", value: ["off": "Off", "on": "On"][s.subtitleMode] ?? "Forced only", desc: "On shows subtitles for every program that has them, in the language below. Forced only shows just the foreign-dialogue subtitles a program marks as needed, like broadcast TV. Off hides them all."),
            SettingsRow(key: .subLang, label: "Subtitle language", value: MediaTrack.languageName(s.subtitleLanguage) ?? "Device language (\(MediaTrack.languageName(Self.deviceLanguage) ?? "English"))", desc: "Which subtitles to show when they’re on. If a program doesn’t have this language, the first full subtitle track plays instead."),
            .head("Profiles"),
            SettingsRow(key: .profile, label: "Who’s watching", value: p.name, desc: "Each profile keeps its own lineup of channels."),
            .head("Library"),
            SettingsRow(key: .server, label: "Server", value: serverLabel, desc: "Choose which libraries feed the schedule. \(films.formatted()) films and \(shows.formatted()) shows on the schedule."),
            SettingsRow(key: .rebuild, label: "Rebuild automatic channels", desc: "Reads your library again and refreshes the automatic channels. Your edits and custom channels stay put."),
            SettingsRow(key: .signout, label: "Sign out", value: signOutArmed ? "\(Platform.verb) again to sign out" : "", desc: "Disconnects this \(Platform.device) from \(service). Your channels are kept for when you sign back in."),
            .head("About"),
            SettingsRow(key: .about, label: "About Rerun Guide", value: Self.version, desc: Self.aboutText),
        ]
    }

    func pressSettings(_ cmd: RemoteCommand) {
        let keys = settingsRows.compactMap(\.key)
        if cmd != .select { signOutArmed = false }   // any focus move (or Back) disarms Sign out
        if step(cmd, count: keys.count) { return }
        if cmd == .back { go(.guide); return }
        guard cmd == .select, keys.indices.contains(focusIndex) else { return }
        switch keys[focusIndex] {
        case .theme: toggleTheme()
        case .crt: settings.crtLines.toggle()
        case .auto: settings.autoScroll.toggle()
        case .breaks: settings.trailerBreaks.toggle()
        case .spots: settings.houseSpots.toggle()
        case .idents: settings.stationIdents.toggle()
        case .idle: settings.idleSeconds = [5: 8, 8: 15, 15: 30, 30: 5][settings.idleSeconds] ?? 8
        case .saver: settings.screensaver = Self.next(in: ["wall", "logo", "soyboi"], after: settings.screensaver)
        case .attract: settings.attractMinutes = [0: 1, 1: 3, 3: 5, 5: 10][settings.attractMinutes] ?? 0
        case .tune: settings.tuneMode = settings.tuneMode == .live ? .start : .live
        case .quality:   // 2 Mbps exists because Plex Relay caps there. The clients read it at init; a playing stream keeps its quality until the next load.
            settings.maxBitrateKbps = [0: 20000, 20000: 8000, 8000: 4000, 4000: 2000][settings.maxBitrateKbps] ?? 0
            if server != nil { rebuildServer() }   // a dropped one (pickPlexAddress) waits for a pick that verifies it
        case .audio: settings.audioLanguage = Self.next(in: [nil] + Self.languages, after: settings.audioLanguage)   // `settings` saves on set
        case .subs: settings.subtitleMode = Self.next(in: ["forced", "on", "off"], after: settings.subtitleMode)
        case .subLang: settings.subtitleLanguage = Self.next(in: [nil] + Self.languages, after: settings.subtitleLanguage)
        case .profile:
            let i = persisted.profiles.firstIndex { $0.id == persisted.currentProfileID } ?? 0
            go(.profiles); focusIndex = i
        case .about: break   // read-only: the credit sits in the description pane
        case .server: librariesOrigin = .settings; go(.libraries)
        case .rebuild:
            Task { await loadLibrary(force: true); toast(libraryError ?? "Channels rebuilt. Your edits and custom channels stayed put.") }
        case .signout:
            guard signOutArmed else {
                signOutArmed = true
                later("signout", seconds: 5) { [weak self] in self?.signOutArmed = false }
                return
            }
            signOutArmed = false; signOut()
        }
    }

    /// The languages the Playback rows cycle through (ISO 639-2/B, as Plex and Emby report them).
    static let languages = ["eng", "spa", "fre", "ger", "ita", "por", "jpn", "kor", "chi"]
    private static func next<T: Equatable>(in list: [T], after v: T) -> T { list[((list.firstIndex(of: v) ?? -1) + 1) % list.count] }

    // MARK: Channel editor

    /// Position in the editor list: 0 = "New channel", i + 1 = channels[i] (the prototype's chIdx).
    var channelIndex: Int { channelSelection.flatMap { id in channels.firstIndex { $0.id == id } }.map { $0 + 1 } ?? 0 }
    var selectedChannel: Channel? { channelSelection.flatMap(channel) }

    func pressChannels(_ cmd: RemoteCommand) {
        let list = channels, idx = channelIndex
        if channelColumn == 0 {
            switch cmd {
            case .up, .down:
                let j = max(0, min(list.count, idx + (cmd == .down ? 1 : -1)))
                channelSelection = j == 0 ? nil : list[j - 1].id
            case .back: go(.guide)
            case .right, .select:
                if idx == 0 { overlay = .newChannel; newChannelStep = 0; newChannelType = nil; focusIndex = 0 }
                else { channelColumn = 1; channelFieldIndex = 0 }
            default: break
            }
            return
        }
        switch cmd {
        case .up, .down:
            channelFieldIndex = max(0, min(ChannelField.allCases.count - 1, channelFieldIndex + (cmd == .down ? 1 : -1)))
            return
        case .left, .back: channelColumn = 0; return
        case .select: break
        default: return
        }
        guard let ch = selectedChannel, let field = ChannelField(rawValue: channelFieldIndex) else { return }
        switch field {
        case .name: editingField = "chName"
        case .number: numberDraft = String(ch.number); editingField = "chNum"
        case .order: updateChannel(ch.id) { $0.order = $0.order.next }
        case .breaks: updateChannel(ch.id) { $0.trailerBreaks.toggle() }
        case .tune: updateChannel(ch.id) { $0.tuneMode = $0.tuneMode == nil ? .live : $0.tuneMode == .live ? .start : nil }
        case .show: setHidden(ch.id, !isHidden(ch.id))
        case .delete:
            if ch.isCustom {
                let next = idx < list.count ? list[idx] : (idx >= 2 ? list[idx - 2] : nil)
                deleteCustomChannel(ch.id); channelColumn = 0; channelSelection = next?.id
                toast("Channel \(ch.number) is off the air.")
            } else {
                resetChannel(ch.id)
                // The automatic number may have been given to another channel meanwhile: keep the edited one then.
                if let auto = channel(ch.id), channels.contains(where: { $0.number == auto.number && $0.id != ch.id }) {
                    updateChannel(ch.id) { $0.number = ch.number }
                }
                toast("Channel \(channel(ch.id)?.number ?? ch.number) is back to automatic.")
            }
        }
    }

    /// A channel editor row's label and value (the TV's detail pane and the portrait editor).
    func fieldText(_ field: ChannelField, _ ch: Channel) -> (label: String, value: String) {
        switch field {
        case .name: ("Name", ch.name)
        case .number: ("Channel number", numberDraft ?? String(ch.number))
        case .order: ("Order", ch.order.rawValue)
        case .breaks: ("Trailer breaks", ch.trailerBreaks ? "On" : "Off")
        case .tune: ("When you tune in", Self.tuneLabel(ch.definition.tuneMode, default: settings.tuneMode))
        case .show: ("Show in \(profile.name)’s guide", isHidden(ch.id) ? "Off" : "On")
        case .delete: (ch.isCustom ? "Delete channel" : "Reset to automatic", "")
        }
    }

    /// Validate and apply the channel-number draft (the prototype's commitNum).
    func commitNumber() {
        defer { numberDraft = nil }
        guard let ch = selectedChannel, let v = numberDraft.flatMap({ Int($0) }), v != ch.number else { return }
        guard (1...999).contains(v) else { toast("Pick a number from 1 to 999."); return }
        guard !channels.contains(where: { $0.number == v && $0.id != ch.id }) else { toast("Channel \(v) is taken."); return }
        updateChannel(ch.id) { $0.number = v }
    }
}
