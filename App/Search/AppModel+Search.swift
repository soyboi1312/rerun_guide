import Foundation
import RerunGuideCore

/// Options › Search: find a film or a show, see when it's on next, open it, or make a channel of it.
extension AppModel {
    /// The library's titles keyed for matching, built on first use after the library changed.
    var titleIndex: TitleSearch.Index {
        if let i = searchIndex { return i }
        let i = TitleSearch.index(library + freshItems)
        searchIndex = i
        return i
    }

    /// The result the page's focus is on: what Program info or the Make a channel page over it is about.
    var searchSelection: TitleSearch.Entry? { searchResults.indices.contains(searchFocus - 1) ? searchResults[searchFocus - 1] : nil }

    /// Options › Search: an empty page with its text entry open.
    func openSearch() {
        go(.search)
        refreshAirings()
        searchQuery = ""; searchFocus = 0
        editingField = "search"
    }

    /// When everything airs in the next 24 hours, across the channels this profile sees: as the page opens, on its 10 s
    /// clock (a program ended, the lineup was rebuilt, the app came back) and before OK opens a result. Never per keystroke.
    func refreshAirings(at now: Date = .now) {
        let end = now.addingTimeInterval(TitleSearch.window)
        searchAirings = TitleSearch.nextAirings(channels.filter { !isHidden($0.id) }.map { ($0, slots($0, from: now, to: end)) }, in: titleIndex, now: now)
    }

    /// "Tonight 10:00 PM · Ch 5 Fright Night", in Clock's time.
    func airsText(_ e: TitleSearch.Entry, at now: Date = .now) -> String {
        TitleSearch.airsText(searchAirings[e.id], now: now) { "\(Clock.time($0)) \(Clock.ap($0))" }
    }

    /// Up / Down move between the field and the results, Select types in the field or opens a result, Back goes to the guide.
    func pressSearch(_ cmd: RemoteCommand) {
        switch cmd {
        case .up, .down: searchFocus = max(0, min(searchResults.count, searchFocus + (cmd == .down ? 1 : -1)))
        case .back: go(.guide)
        case .select:
            guard let e = searchSelection else { editingField = "search"; return }
            makePeople = nil; refreshAirings()
            // On in the next 24 hours: its Program info, which adds Make a channel over this page. Otherwise just that.
            if let a = searchAirings[e.id] { openInfo(ProgramKey(channelID: a.channelID, start: a.start)) } else { overlay = .makeChannel; focusIndex = 0 }
        default: break
        }
    }

    /// How many of a film's billed actors count as its leads.
    static let leadActors = 3

    /// Make a channel of a result, as Channels › New channel does: a show's marathon in episode order at once; for a film,
    /// the choice of its director(s) and lead actors with enough films for a channel. Says so when nothing qualifies.
    func makeChannel(_ e: TitleSearch.Entry) {
        if e.isShow {
            guard let c = ChannelBuilder.candidates(.series, in: library).first(where: { $0.name == e.title }) else {
                toast("\(e.title) doesn’t have enough episodes for a channel."); return
            }
            return made(.series, c)
        }
        let m = e.item
        let directors = ChannelBuilder.candidates(.director, in: library).filter { m.directors.contains($0.name) }
        let actors = ChannelBuilder.candidates(.actor, in: library).filter { m.actors.prefix(Self.leadActors).contains($0.name) }
        let people = directors.map { (kind: ChannelBuilder.CandidateKind.director, candidate: $0) }
            + actors.map { (kind: ChannelBuilder.CandidateKind.actor, candidate: $0) }
        guard !people.isEmpty else { toast("No one from \(e.title) has enough films for a channel."); return }
        makePeople = people; overlay = .makeChannel; focusIndex = 0
    }

    /// The new channel lands in the channel editor, as after Channels › New channel.
    private func made(_ kind: ChannelBuilder.CandidateKind, _ c: ChannelBuilder.Candidate) {
        createChannel(kind, c); makePeople = nil; go(.channels)
    }

    /// The Make a channel page's buttons: Make a channel, then a film's people to pick from.
    var makeActions: [InfoAction] {
        guard let e = searchSelection else { return [] }
        guard let people = makePeople else { return [InfoAction(label: "Make a channel") { [self] in makeChannel(e) }] }
        return people.map { p in InfoAction(label: "\(p.candidate.name) · \(p.kind.rawValue)") { [self] in made(p.kind, p.candidate) } }
    }

    func pressMakeChannel(_ cmd: RemoteCommand) {
        let acts = makeActions
        if step(cmd, count: acts.count, horizontal: true) { return }
        if cmd == .select, !acts.isEmpty { acts[min(focusIndex, acts.count - 1)].run() }
        else if cmd == .back { makePeople = nil; closeOverlay() }
    }
}
