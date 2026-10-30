import Foundation
import RerunGuideCore

/// Options menu, Program info sheet, New-channel picker (port of the prototype's k_options, k_info/infoActs, k_newch/createCh).
extension AppModel {
    // MARK: Options (k_options)

    enum OptionRow: CaseIterable { case profile, guide, movies, tv, search, channels, settings, theme }

    func pressOptions(_ cmd: RemoteCommand) {
        let rows = OptionRow.allCases
        if step(cmd, count: rows.count) { return }
        let row = rows[min(focusIndex, rows.count - 1)]
        if row == .theme, cmd == .left || cmd == .right { setTheme(cmd == .left ? .late : .midnight); return }
        if cmd == .back || cmd == .right { closeOverlay(); return }
        guard cmd == .select else { return }
        switch row {
        case .profile:
            go(.profiles)
            focusIndex = persisted.profiles.firstIndex { $0.id == persisted.currentProfileID } ?? 0
        case .guide: setGuideFilter(.all)
        case .movies: setGuideFilter(.movies)
        case .tv: setGuideFilter(.tv)
        case .search: openSearch()
        case .channels: go(.channels)
        case .settings: go(.settings)
        case .theme: closeOverlay()   // Left/Right pick the theme; the center button confirms by closing the menu, never flips it again
        }
    }

    // MARK: Program info (infoActs / k_info)

    struct InfoAction { let label: String; let run: @MainActor () -> Void }

    func infoActions(_ key: ProgramKey) -> [InfoAction] {
        guard let (ch, slot) = program(key) else { return [] }
        let st = status(slot, at: .now)
        let fromStart = InfoAction(label: st == .later ? "Watch now" : "Start from the beginning") { [self] in tune(ch.id, mode: .start, programStart: slot.start) }
        var acts: [InfoAction] = st == .live || st == .break
            ? [InfoAction(label: "Watch live") { [self] in tune(ch.id, mode: .live, force: true) }, fromStart]
            : [fromStart, InfoAction(label: "Watch channel \(ch.number) now") { [self] in tune(ch.id, mode: .live) }]
        acts.append(InfoAction(label: "Edit channel") { [self] in
            go(.channels); channelSelection = ch.id; channelColumn = 1; channelFieldIndex = 0
        })
        // Opened from Search (over its page): the result can also become a channel.
        if screen == .search, let e = searchSelection { acts.append(InfoAction(label: "Make a channel") { [self] in makeChannel(e) }) }
        return acts
    }

    func pressInfo(_ cmd: RemoteCommand) {
        guard case .info(let key) = overlay else { return }
        let acts = infoActions(key)
        if step(cmd, count: acts.count, horizontal: true) { return }
        if cmd == .select, !acts.isEmpty { acts[min(focusIndex, acts.count - 1)].run() }
        else if cmd == .back { closeOverlay(); if screen == .watch { bannerLater() } }
    }

    // MARK: New channel (k_newch / createCh)

    func pressNewChannel(_ cmd: RemoteCommand) {
        let kinds = ChannelBuilder.CandidateKind.allCases
        let items = candidateCache
        if newChannelStep == 1 && (cmd == .up || cmd == .down) {   // 3 per row
            let j = focusIndex + (cmd == .down ? 3 : -3)
            if items.indices.contains(j) { focusIndex = j }
            return
        }
        if step(cmd, count: newChannelStep == 0 ? kinds.count : items.count, horizontal: true) { return }
        if cmd == .back {
            if newChannelStep == 1 { newChannelStep = 0; focusIndex = newChannelType.flatMap { kinds.firstIndex(of: $0) } ?? 0 }
            else { candidateCache = []; closeOverlay() }
            return
        }
        guard cmd == .select else { return }
        if newChannelStep == 0 {
            let kind = kinds[min(focusIndex, kinds.count - 1)]
            newChannelType = kind; candidateCache = ChannelBuilder.candidates(kind, in: library); newChannelStep = 1; focusIndex = 0
        }
        else if let kind = newChannelType, items.indices.contains(focusIndex) { createChannel(kind, items[focusIndex]) }
    }

    func createChannel(_ kind: ChannelBuilder.CandidateKind, _ c: ChannelBuilder.Candidate) {
        let n = nextFreeNumber
        let chip: RuleChip = switch kind {
        case .director: .director(c.name)
        case .actor: .actor(c.name)
        case .series: .series(id: c.id ?? c.name, title: c.name)
        }
        let d = ChannelDefinition(id: "custom:" + UUID().uuidString, number: n, name: c.name,
                                  tag: kind == .series ? "Marathon" : kind.rawValue, rule: [chip],
                                  order: kind == .series ? .inOrder : .shuffle, trailerBreaks: true, isCustom: true)
        addCustomChannel(d)
        overlay = nil; focusIndex = 0   // the candidates stay: the portrait sheet still shows them as it slides away
        channelSelection = d.id; channelColumn = 1; channelFieldIndex = 0
        toast("Channel \(n), \(c.name), just signed on.")
    }
}
