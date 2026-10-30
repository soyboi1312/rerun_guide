import Foundation
import Observation
import UIKit
import RerunGuideCore

/// What the picture is showing right now (port of the prototype's wInfo).
struct WatchInfo {
    let channel: Channel
    let slot: Slot
    /// Seconds into the slot. `runtime ≤ position < duration` is the break after the program.
    let position: TimeInterval
    let live: Bool
    /// What the channel's current schedule airs at `slot.end` (what live rolls into).
    let next: Slot?
    var isBreak: Bool { position >= slot.item.runtime }
}

/// Watching: banner, channel up/down, last channel, pause/time-shift (port of k_watch/wInfo/flip/togglePause/watchActs).
extension AppModel {
    func watchInfo(at now: Date = .now) -> WatchInfo? {
        guard let pb = playback, let ch = channel(pb.channelID) ?? visibleChannels.first else { return nil }
        // The slot tune() snapshotted, unless its channel is gone: a library refresh reshuffles every lineup and the
        // program must not change under the viewer. Live rolls into the current schedule once the snapshot ends.
        let kept = ch.id == pb.channelID ? pb.slot : nil
        if pb.mode == .live {
            guard let s = kept.flatMap({ now < $0.end ? $0 : nil }) ?? slotOnAir(ch, at: now) else { return nil }
            return WatchInfo(channel: ch, slot: s, position: now.timeIntervalSince(s.start), live: true, next: slotOnAir(ch, at: s.end))
        }
        guard let s = kept ?? slot(ch, startingAt: pb.programStart) ?? slotOnAir(ch, at: now) else { return nil }
        return WatchInfo(channel: ch, slot: s, position: pb.offset + (pb.paused ? 0 : now.timeIntervalSince(pb.startedAt)), live: false, next: slotOnAir(ch, at: s.end))
    }

    func pressWatch(_ cmd: RemoteCommand) {
        guard let pb = playback else { return }
        if trackMenu != nil, cmd != .play { pressTrackMenu(cmd); return }
        switch cmd {
        // From the banner's buttons Up goes to its progress bar, and Down comes back; otherwise they change channels.
        case .up: if bannerVisible && bannerFocus >= 0 { barFocused = true; bannerLater() } else { flip(1) }
        case .down: if bannerVisible && bannerFocus >= 0 && barFocused { barFocused = false; bannerLater() } else { flip(-1) }
        case .back:   // the info banner alone never holds Back; full screen goes back to portrait Now playing
            if bannerVisible && bannerFocus >= 0 { hideBanner() } else if fullScreen { fullScreen = false } else { toGuide(from: pb.channelID) }
        case .play: if watchInfo()?.isBreak == true { restartFromBreak() } else { togglePause() }   // pausing a break holds nothing
        case .left, .right, .select:
            if bannerVisible && bannerFocus >= 0 && barFocused {   // the progress bar: Left / Right skip, OK is Play/Pause
                if cmd != .select { skip(cmd == .left ? -10 : 10) } else if watchInfo()?.isBreak != true { togglePause() }
                bannerLater(); return
            }
            if bannerVisible && bannerFocus >= 0 {
                let acts = watchActions()
                guard !acts.isEmpty else { hideBanner(); return }   // no watchInfo (channels gone mid-program)
                switch cmd {
                case .left: bannerFocus = max(0, bannerFocus - 1)
                case .right: bannerFocus = min(acts.count - 1, bannerFocus + 1)
                default: acts[min(bannerFocus, acts.count - 1)].run()
                }
                bannerLater(); return
            }
            // The spot: Select / Right skip it once its countdown is done, and do nothing before that.
            if cmd != .left, SpotOnAir.shared.started != nil { skipSpot(); return }
            if cmd == .select, let info = watchInfo(), info.isBreak, info.next != nil { startNextFromBreak(); return }   // the break card's OK
            if cmd == .left { skip(-10); return }
            if cmd == .right { skip(10); return }
            bannerVisible = true; bannerFocus = 0; barFocused = false
            bannerLater()
        }
    }

    /// In a break: the program that just ended, from its beginning (Play/Pause, or the break card's pill).
    func restartFromBreak() {
        guard let info = watchInfo() else { return }
        tune(info.channel.id, mode: .start, programStart: info.slot.start)
    }

    /// In a break: the next program, from its beginning, without waiting for its slot (Select, or the break card's pill).
    func startNextFromBreak() {
        guard let info = watchInfo(), let next = info.next else { return }
        tune(info.channel.id, mode: .start, programStart: next.start)
    }

    /// Hide the banner 6 s from now.
    func bannerLater() { later("banner", seconds: 6) { [weak self] in guard let self, overlay == nil, trackMenu == nil else { return }; bannerVisible = false; bannerFocus = -1 } }
    func hideBanner() { cancelTimer("banner"); bannerVisible = false; bannerFocus = -1; trackMenu = nil }

    /// Channel up (+1) / down (−1) through the visible channels, wrapping.
    func flip(_ d: Int) {
        let list = visibleChannels
        guard !list.isEmpty, let pb = playback else { return }
        let i = list.firstIndex { $0.id == pb.channelID } ?? 0
        tune(list[((i + d) % list.count + list.count) % list.count].id, mode: .live)
    }

    // MARK: Number keys

    /// The channel numbered `n` in this profile's lineup: a hidden one counts as missing.
    func channel(numbered n: Int) -> Channel? { channels.first { $0.number == n && !isHidden($0.id) } }
    /// The channel the digits typed so far name.
    var entryChannel: Channel? { channelEntry.flatMap { Int($0.digits) }.flatMap(channel(numbered:)) }

    /// A number key on the guide or the player with nothing over them: the box in the top-right corner collects up to
    /// three digits and tunes 2 s after the last one, at the third, or on OK. Digits do nothing anywhere else.
    func pressDigit(_ d: Int) {
        guard overlay == nil, editingField == nil, trackMenu == nil, screen == .guide || screen == .watch else { return }
        if attracting { attractReset() }   // the box wakes the screen instead of sitting on the screensaver
        let digits = (channelEntry.flatMap { $0.missing ? nil : $0.digits } ?? "") + String(d)
        channelEntry = ChannelEntry(digits: digits); lastEntry = channelEntry
        if digits.count == 3 { commitEntry() } else { later("entry", seconds: 2) { [weak self] in self?.commitEntry() } }
    }

    /// While the box collects digits, OK tunes now and Back cancels; any other press closes it and does what it does.
    /// True when the box took the press.
    func pressEntry(_ cmd: RemoteCommand) -> Bool {
        guard channelEntry?.missing == false else { return false }
        if cmd == .select { commitEntry(); return true }
        closeEntry()
        return cmd == .back
    }

    /// Put the box away without tuning: a tap, a channel change or a screen went elsewhere.
    func closeEntry() { cancelTimer("entry"); channelEntry = nil }

    /// Tune to the typed number like a channel change (the tune-mode setting applies). The channel already on screen
    /// just closes the box; a number with no channel says so for 2 s and changes nothing.
    func commitEntry() {
        guard let e = channelEntry, !e.missing, let n = Int(e.digits) else { return }
        closeEntry()
        guard overlay == nil, editingField == nil, !pip, screen == .guide || screen == .watch else { return }   // moved on, or away in picture in picture
        guard let ch = channel(numbered: n) else {
            channelEntry = ChannelEntry(digits: e.digits, missing: true); lastEntry = channelEntry
            later("entry", seconds: 2) { [weak self] in self?.channelEntry = nil }
            return
        }
        if screen == .watch, playback?.channelID == ch.id { return }
        tune(ch.id, mode: .live)
    }

    // MARK: Background

    /// Leaving for the Home screen (Back from the guide, or the TV button): ask the server to stop both streams now,
    /// instead of leaving it transcoding until it notices nobody is pulling, and hold a started-over program's place.
    /// A live program needs nothing held: it rejoins live on the way back, like changing back to a channel.
    func enteredBackground() {
        guard !backgrounded, !pip else { return }   // picture in picture plays on: `pipChanged` comes back here when it closes
        backgrounded = true
        if var pb = playback, pb.mode == .start, !pb.paused, let info = watchInfo() {
            pb.offset = info.position; pb.paused = true; playback = pb; resumeAfterBackground = true
        } else if playback?.mode == .live, playback?.markDue == false { playback?.fromStart = false }   // a caught-up start-over rejoins live on the way back: no longer from the beginning
        cancelTimer("seek"); closeEntry()   // a typed number doesn't tune behind the Home screen
        guard let server else { return }
        let task = BackgroundTask.begin("stop-streams")   // the app is suspended in seconds; finish the two requests first
        Task { await server.stopPlayback(session: "watch"); await server.stopPlayback(session: "preview"); task.end() }
        // A plain-http Plex link may come back on another network, where the guide's artwork and preview would carry the
        // token before the return's pick finishes. Dropped now, that pick rebuilds it once its address checks out.
        if plainHTTPLink { self.server = nil }
    }

    /// A plain-http Plex link off TV (a LAN address, where the router hides plex.direct): dropped while the app is away
    /// and checked again on the way back before the token goes to it.
    var plainHTTPLink: Bool {
        !Platform.isTV && persisted.serverConfig?.addresses != nil && persisted.serverConfig?.baseURL.scheme == "http"
    }

    /// The player went into picture in picture (iPhone, iPad: Home while it plays) or out of it. The app is away meanwhile
    /// (and may sleep through a network change while the window is paused): a plain-http link's client goes, as in
    /// `enteredBackground` (the window plays on: past start.m3u8 its requests carry no token), and address picks wait.
    /// Closed while the app is still away, its stream stops as on any other way out (a plain-http link's server can't be
    /// asked to: it ends the stream once nothing pulls it). Brought back paused (the window's own
    /// pause, behind the clock) or on a plain-http link, it comes back as from Home: on the clock, the banner up, the link
    /// checked before anything reloads. Brought back playing otherwise, it just carries on.
    func pipChanged(_ on: Bool, paused: Bool = false) {
        let was = pip
        pip = on
        if on { if plainHTTPLink { server = nil }; return }
        guard was else { return }
        if UIApplication.shared.applicationState == .background { enteredBackground() }
        else if paused || plainHTTPLink { backgrounded = true; returnedToForeground() }   // its stream isn't stopped: the reload replaces it
    }

    /// Back from the Home screen: reload whatever is on screen (its stream was stopped), resume a held program where it
    /// was, and show the banner so the viewer sees where they landed.
    func returnedToForeground() {
        guard backgrounded else { return }
        backgrounded = false
        if resumeAfterBackground, var pb = playback { pb.paused = false; pb.startedAt = .now; playback = pb }
        resumeAfterBackground = false
        if screen == .watch, watchInfo()?.isBreak != true { bannerVisible = true; bannerFocus = -1; trackMenu = nil; bannerLater() }   // a break's card says it all
        // A plain-http Plex link (dropped on the way out) picks its address again before anything reloads: the reload
        // carries the token there. With nothing verified to move to, it stays dropped and the reload finds none.
        let pickFirst = plainHTTPLink
        let reload = { [self] in foregroundSerial += 1; if screen == .watch { tunes += 1 } }
        if !pickFirst { reload() }
        Task {
            #if !os(tvOS)
            // The device may have left (or come home to) the server's network: an https link reloaded already and reloads
            // again if its address moves (a movie started on the old one); a dropped http link reloads below once checked.
            if pickFirst { await pickPlexAddress() } else { await networkChanged() }
            #endif
            if pickFirst, !backgrounded, !pip { reload() }   // away again meanwhile: that way back picks and reloads
            await checkRecentlyAdded()   // opening the app brings the "what's new" channels up to date
        }
    }

    // MARK: Soyboi spot

    /// The bundled spots, in the order they take turns. Each is 30 s, 1080p H.264 + AAC.
    static let spotURLs = ["beacon-they-live", "beacon-rental", "beacon-trail", "beacon-cafe"].compactMap { Bundle.main.url(forResource: $0, withExtension: "mp4") }
    static let spotLength: TimeInterval = 30

    /// The spot this break opens with, or nil. One trailer break in 15 with room for a whole spot (counted once per
    /// break, across launches), the spots taking turns; never on channel 99, never with the setting or this channel's
    /// trailers off.
    func dueSpot(_ ch: Channel, slot: Slot, at now: Date = .now) -> URL? {
        guard let first = Self.spotURLs.first else { return nil }
        if spotOverride { spotOverride = false; return first }
        guard settings.houseSpots, settings.trailerBreaks, ch.trailerBreaks, !ch.isScrambled,
              slot.end.timeIntervalSince(now) >= Self.spotLength + 2 else { return nil }
        let d = UserDefaults.standard
        var r = d.data(forKey: "spotRotation").flatMap { try? JSONDecoder().decode(SpotRotation.self, from: $0) } ?? SpotRotation()
        let due = r.next("\(ch.id)|\(Int(slot.start.timeIntervalSince1970))")
        d.set(try? JSONEncoder().encode(r), forKey: "spotRotation")
        return due ? Self.spotURLs[r.pick(of: Self.spotURLs.count)] : nil
    }

    /// Seconds into the spot before it can be skipped.
    static let spotSkipDelay: TimeInterval = 5

    /// WatchScreen: the spot started playing (`end` hands over to the trailers, like the spot's own end), or, with nil, it's gone.
    func spotChanged(end: (@MainActor () -> Void)?) {
        let s = SpotOnAir.shared
        cancelTimer("spot")
        s.started = end == nil ? nil : .now; s.skippable = false; s.end = end ?? {}
        if end != nil { later("spot", seconds: Self.spotSkipDelay) { s.skippable = true } }
    }

    /// Select / Right, or a tap on the Skip chip, during the spot: once the countdown is done, end it like its own end.
    func skipSpot() {
        let s = SpotOnAir.shared
        guard s.started != nil, s.skippable else { return }
        let end = s.end
        spotChanged(end: nil); end()
    }

    func goLast() {
        if let c = lastChannel { tune(c.id, mode: .live) } else { toast("No last channel yet. Change channels first.") }
    }

    /// Left / Right while watching: 10 s back or ahead per press, the step Apple's own player uses. Presses within
    /// 0.6 s pool into one move, then the player reloads at the new spot (the server transcodes from an offset, so a
    /// seek is a reload). Rewinding a live program time-shifts it; fast-forwarding a shifted one can catch up to live.
    func skip(_ seconds: TimeInterval) {
        guard var pb = playback, let info = watchInfo() else { return }
        bannerVisible = true; bannerLater()
        let runtime = info.slot.item.runtime, sinceStart = Date.now.timeIntervalSince(info.slot.start)
        guard info.position < runtime else { return }   // a trailer break: nothing to scrub
        // A program started before it airs has no live point to catch up to yet: it stays time-shifted.
        let liveEdge = sinceStart > 0 ? sinceStart : .infinity
        let target = max(0, min(info.position + seconds, runtime - 1, liveEdge))
        let fromStart = pb.fromStart && info.slot == pb.slot   // not onto the next program, joined midway once the kept slot ended
        if target >= liveEdge - 1 {   // caught up, or never left live
            guard pb.mode != .live else { return }
            pb = Playback(channelID: pb.channelID, mode: .live, programStart: info.slot.start, slot: info.slot, tracks: pb.tracks, fromStart: fromStart)
        } else {
            pb = Playback(channelID: pb.channelID, mode: .start, programStart: info.slot.start, offset: target, paused: pb.paused, slot: info.slot, tracks: pb.tracks, fromStart: fromStart)
        }
        playback = pb
        skipCue = seconds < 0 ? -1 : 1; later("skip-cue", seconds: 1) { [weak self] in self?.skipCue = 0 }
        later("seek", seconds: 0.6) { [weak self] in self?.tunes += 1 }
    }

    /// Play/Pause: live becomes time-shifted and paused; paused resumes; playing pauses.
    func togglePause() {
        guard var pb = playback, let info = watchInfo() else { return }
        if pb.mode == .live { pb = Playback(channelID: pb.channelID, mode: .start, programStart: info.slot.start, offset: info.position, paused: true, slot: info.slot, tracks: pb.tracks, fromStart: pb.fromStart && info.slot == pb.slot) }   // as in `skip`
        else if pb.paused { pb.startedAt = .now; pb.paused = false }
        else { pb.offset = info.position; pb.paused = true }
        playback = pb
        bannerVisible = true; bannerLater()
    }

    /// Banner buttons: [Pause | Play] (not in a break), [Start over | Back to live], Program info, Audio, Subtitles,
    /// Last channel · N. Audio and Subtitles open a list of the program's tracks over the button, the one on checked.
    func watchActions() -> [(label: String, run: @MainActor () -> Void)] {
        guard let info = watchInfo() else { return [] }
        let ch = info.channel, slot = info.slot
        var a: [(label: String, run: @MainActor () -> Void)] = []
        if !info.isBreak { a.append((playback?.paused == true ? "Play" : "Pause", { [weak self] in self?.togglePause() })) }
        if info.live { a.append(("Start over", { [weak self] in self?.tune(ch.id, mode: .start, programStart: slot.start) })) }
        else { a.append(("Back to live", { [weak self] in self?.tune(ch.id, mode: .live, force: true) })) }
        a.append(("Program info", { [weak self] in self?.openInfo(ProgramKey(channelID: ch.id, start: slot.start)) }))
        for (kind, name) in [(MediaTrack.Kind.audio, "Audio"), (.subtitle, "Subtitles")] where trackChoices(kind).count >= 2 {   // one audio track: nothing to pick
            a.append((name, { [weak self] in self?.openTrackMenu(kind) }))
        }
        if let last = lastChannel { a.append(("Last channel · \(last.number)", { [weak self] in self?.goLast() })) }
        return a
    }

    /// The rows of the banner's Audio or Subtitles list, the one playing marked `on`. Subtitles: Off, Forced only (only
    /// while it's the choice: the server's pick, which the list can't offer back once left), then each track.
    func trackChoices(_ kind: MediaTrack.Kind) -> [(label: String, on: Bool, apply: (inout TrackSelection) -> Void)] {
        guard let item = watchInfo()?.slot.item else { return [] }
        let tracks = playbackTracks.filter { $0.kind == kind }, sel = trackSelection(for: playbackTracks, itemID: item.id)
        if kind == .audio {
            let on = tracks.firstIndex { $0.id == sel.audioID } ?? tracks.firstIndex(where: \.isDefault) ?? 0
            return tracks.enumerated().map { i, t in (Self.trackName(t, among: tracks), i == on, { $0.audioID = t.id }) }
        }
        guard !tracks.isEmpty else { return [] }
        var rows: [(label: String, on: Bool, apply: (inout TrackSelection) -> Void)] = [("Off", sel.subtitle == .off, { $0.subtitle = .off })]
        if sel.subtitle == .auto { rows.append(("Forced only", true, { $0.subtitle = .auto })) }
        rows += tracks.map { t in (Self.trackName(t, among: tracks), sel.subtitle == .track(t.id), { $0.subtitle = .track(t.id) }) }
        return rows
    }

    /// The banner's Audio or Subtitles button: its list opens on the choice that's on, and holds the banner up.
    func openTrackMenu(_ kind: MediaTrack.Kind) {
        cancelTimer("banner")
        trackMenu = TrackMenu(kind: kind, focus: trackChoices(kind).firstIndex(where: \.on) ?? 0)
    }

    /// Up / Down move through the list (a drag or the scroll wheel too), Select plays the choice (a reload, unless it's
    /// the one on), Back puts the list away. Left / Right do nothing while it's open.
    func pressTrackMenu(_ cmd: RemoteCommand) {
        guard var m = trackMenu else { return }
        let c = trackChoices(m.kind)
        switch cmd {
        case .up, .down:
            m.focus = max(0, min(c.count - 1, m.focus + (cmd == .down ? 1 : -1))); trackMenu = m
        case .select:
            trackMenu = nil; bannerLater()
            if c.indices.contains(m.focus), !c[m.focus].on { setTracks(c[m.focus].apply) }
        case .back: trackMenu = nil; bannerLater()
        default: break
        }
    }

    /// "English"; the server's own title ("Commentary", "English (AC3 5.1)") when the language is unknown or shared by another track.
    static func trackName(_ t: MediaTrack, among: [MediaTrack]) -> String {
        let lang = MediaTrack.languageName(t.language)
        let shared = among.filter { $0.language == t.language }.count > 1
        return (lang == nil || shared) && !t.title.isEmpty ? t.title : lang ?? "Track \(t.id)"
    }

    /// Per-program override from the banner: the player reloads at the current position with the new tracks.
    /// The override holds for that program only (`itemID`, default the one on now).
    private func setTracks(itemID: String? = nil, _ change: (inout TrackSelection) -> Void) {
        guard playback != nil, let id = itemID ?? watchInfo()?.slot.item.id else { return }
        var sel = trackSelection(for: playbackTracks, itemID: id); change(&sel)
        playback?.tracks = .init(itemID: id, selection: sel); tunes += 1
    }

    /// Program `itemID`, asked for with a subtitle, didn't start: reload it at the same position with subtitles off.
    func dropSubtitles(itemID: String) {
        setTracks(itemID: itemID) { $0.subtitle = .off }
        toast("Couldn’t show subtitles for this one.")
    }

    /// The prototype's onTick: a started-over program whose slot has run out goes back to live. Also marks a program
    /// started from its beginning watched on the server once it has played to 90%.
    func watchTick(at now: Date = .now) {
        guard screen == .watch, let pb = playback, let info = watchInfo(at: now) else { return }
        // Only the program tuned counts, not its break (trailers, the spot) nor channel 99. Once per playback, no toast,
        // a failure only logged. No server (a plain-http link dropped while away): a later tick of this slot reports it
        // once it's back, its break too.
        // The library's own `watched` stays as it is, so the lineup doesn't change under the viewer: the Unwatched
        // channel drops the program at the next library refresh.
        if pb.fromStart, info.slot == pb.slot, !info.isBreak || pb.markDue, !info.channel.isScrambled,
           info.position >= 0.9 * info.slot.item.runtime {
            if let server {
                playback?.fromStart = false
                let item = info.slot.item
                Task { do { try await server.markWatched(item) } catch { print("markWatched:", error) } }
            } else if !pb.markDue { playback?.markDue = true }
        }
        guard pb.mode == .start, !pb.paused, info.position >= info.slot.duration else { return }
        let on = slotOnAir(info.channel, at: now)
        playback = Playback(channelID: pb.channelID, mode: .live, programStart: on?.start ?? now, slot: on)
        toast("Caught up. Back to live.")
    }
}

/// The banner's open Audio or Subtitles list: which, and the row the remote is on.
struct TrackMenu: Equatable { let kind: MediaTrack.Kind; var focus: Int }

/// The house spot on screen: WatchScreen holds its stream, the remote and VoiceOver need when it started and whether it
/// can be skipped yet. Its own object since an extension can't add stored properties to AppModel; observed like it.
@MainActor @Observable final class SpotOnAir {
    static let shared = SpotOnAir()
    var started: Date? = nil
    /// The 5 s countdown is done: Select / Right (a tap on touch) end the spot.
    var skippable = false
    @ObservationIgnored var end: @MainActor () -> Void = {}
}

/// A UIKit background task around a short async job. `end` is safe to call twice: the expiration handler may beat it.
@MainActor private final class BackgroundTask {
    private var id = UIBackgroundTaskIdentifier.invalid
    static func begin(_ name: String) -> BackgroundTask {
        let t = BackgroundTask()
        t.id = UIApplication.shared.beginBackgroundTask(withName: name) { t.end() }
        return t
    }
    func end() {
        guard id != .invalid else { return }
        UIApplication.shared.endBackgroundTask(id); id = .invalid
    }
}
