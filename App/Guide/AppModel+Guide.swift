import Foundation
import RerunGuideCore

/// Guide remote handling + drift bookkeeping (port of the prototype's k_guide/gGrab/gNav/gScrollTo/gRelease…).
/// Row positions are "raw" track offsets: `guideScroll.row` and `.pos` grow without bound and the view wraps them
/// modulo the half-track height, so no ±half normalisation is needed.
extension AppModel {
    private var span: TimeInterval { Self.windowSpan }
    /// Height of one pass through the visible channels; every guide offset is taken modulo this.
    var half: CGFloat { max(1, CGFloat(visibleChannels.count) * rowPitch) }
    func wrap(_ x: CGFloat) -> CGFloat { let h = half; return (x.truncatingRemainder(dividingBy: h) + h).truncatingRemainder(dividingBy: h) }
    private func jsRound(_ x: CGFloat) -> Int { Int((x + 0.5).rounded(.down)) }
    /// The channel on raw row `row` (any integer; the lineup wraps).
    func rowChannel(_ row: Int) -> Channel? { let n = visibleChannels.count; return n > 0 ? visibleChannels[((row % n) + n) % n] : nil }

    func pressGuide(_ cmd: RemoteCommand) {
        if attracting { attractReset(); return }   // the first press only wakes the screen, like a screensaver
        if cmd == .back { if remoteHeld { guideRelease() } else { openOptions() }; return }
        if !remoteHeld {
            if cmd == .left { openOptions(); return }   // the drifting guide's way in: Menu exits to Home here, and the airing program is the earliest in the window
            guard guideGrab() else { return }
            guideScroll.pos = CGFloat(guideScroll.row - 1) * rowPitch
            guideBump(); return
        }
        guideBump()
        switch cmd {
        case .select: guideSelect()
        case .play: if let k = guideKey(), let pr = program(k) { tune(pr.channel.id, mode: .start, programStart: pr.slot.start) }
        default: guideNav(cmd)
        }
    }

    /// First press takes the remote: keep the focus if its cell is on screen, else focus the 2nd visible row at the current time.
    private func guideGrab() -> Bool {
        let list = visibleChannels, n = list.count, p = rowPitch, now = Date.now
        guard n > 0, var gf = guideKey(at: now), let pr = program(gf) else { return false }
        let o = guideDriftOffset(at: now)
        guideScroll.pos = o
        let ws = windowStart(at: now), we = ws + span
        let r = list.firstIndex { $0.id == pr.channel.id } ?? 0
        var v = r + n * jsRound((o + p - CGFloat(r) * p) / half)
        let seen = pr.slot.start < we && pr.slot.end > ws && CGFloat(v) * p >= o - p / 2 && CGFloat(v) * p <= o + gridHeight - p / 2
        if seen { guideScroll.focusTime = max(pr.slot.start, ws) }
        else {
            v = jsRound(o / p) + 1
            if let ch = rowChannel(v), let k = cellAt(ch, time: max(now, ws), windowStart: ws), let q = program(k) {
                gf = k; guideScroll.focusTime = max(q.slot.start, ws)
            }
        }
        guideScroll.row = v
        remoteHeld = true; focused = gf
        return true
    }

    private func guideNav(_ cmd: RemoteCommand) {
        let now = Date.now
        if cmd == .up || cmd == .down {
            guideScroll.row += cmd == .down ? 1 : -1
            let ws = windowStart(at: now)
            if let ch = rowChannel(guideScroll.row) { focused = cellAt(ch, time: max(guideScroll.focusTime, ws), windowStart: ws) }
            guideScroll.pos = CGFloat(guideScroll.row - 1) * rowPitch
            return
        }
        guard let k = guideKey(at: now), let pr = program(k) else { return }
        let ch = pr.channel, p = pr.slot
        guard let q = slotOnAir(ch, at: cmd == .right ? p.end : p.start - 1) else { if cmd == .left { openOptions() }; return }
        // Page only as far as it takes to show the program's start (or to the first window: the on-now one brings back now).
        var w = windowOffset
        if cmd == .right { while q.start >= windowStart(at: now, offset: w) + span && w < Self.maxWindowOffset { w += 1 } }
        else { while q.start < windowStart(at: now, offset: w) && w > 0 { w -= 1 } }
        let ws = windowStart(at: now, offset: w)
        if q.start >= ws + span { return }
        if q.end <= ws { if cmd == .left { if windowOffset > 0 { windowOffset = 0; guideScroll.focusTime = max(p.start, ws) } else { openOptions() } }; return }   // paged ahead (Up/Down or a tap got here): back to now first
        guideScroll.focusTime = max(q.start, ws)
        windowOffset = w; focused = key(ch, q)
    }

    private func guideSelect() {
        guard let k = guideKey(), let pr = program(k) else { return }
        switch status(pr.slot, at: .now) {
        case .live, .break: tune(pr.channel.id, mode: .live)
        case .later, .past: openInfo(k)
        }
    }

    // MARK: Touch / mouse

    /// A tap on a guide cell on raw row `row`: the first takes the remote there (the grid stays put, no grab jump), a tap on
    /// the focused cell is Select.
    func tapGuide(row: Int, key k: ProgramKey) {
        closeEntry()   // a tap moves the focus as the remote's arrows would
        if attracting { attractReset(); return }
        guard let pr = program(k) else { return }
        let now = Date.now, again = guideKey(at: now) == k
        if !remoteHeld { guideScroll.pos = guideDriftOffset(at: now); remoteHeld = true }   // read before holding: the drift stops here
        guideScroll.row = row
        guideScroll.focusTime = max(pr.slot.start, windowStart(at: now))
        focused = k
        guideBump()
        if again { guideSelect() }
    }

    /// The info panel's hint pills and preview window: act on the program the panel shows, without the grab a remote
    /// press needs first (a grab may move the focus to another row).
    func guideHint(_ cmd: RemoteCommand) {
        if attracting { attractReset(); return }
        switch cmd {
        case .select: guideSelect()
        case .play: if let k = guideKey(), let pr = program(k) { tune(pr.channel.id, mode: .start, programStart: pr.slot.start) }
        default: press(cmd)
        }
    }

    /// Restart the idle timer; when it fires the guide releases back to drifting.
    func guideBump() { later("idle", seconds: Double(settings.idleSeconds)) { [weak self] in self?.guideRelease() } }

    /// Let go of the remote: focus snaps to what's on now on the focused channel, window resets, drift resumes from here.
    func guideRelease() {
        cancelTimer("idle")
        guard remoteHeld, screen == .guide, overlay == nil else { return }
        guidePark(); guideScroll.driftStart = .now
        attractReset()
    }

    /// Current drift translate offset (0 ..< half-track height) while the guide is drifting on its own.
    func guideDriftOffset(at now: Date = .now) -> CGFloat {
        guard !remoteHeld, settings.autoScroll else { return wrap(guideScroll.resumeOffset) }
        return wrap(guideScroll.resumeOffset + CGFloat(max(0, now.timeIntervalSince(guideScroll.driftStart))) * guideScroll.speed)
    }

    /// The guide came on screen: the drift resumes from `resumeOffset` now.
    func guideAppeared() { guideScroll.driftStart = .now; holdForLaunch(); attractReset() }

    /// The first time a launch's guide is on screen with channels, it holds still `launchHold` seconds before the drift
    /// starts (the offset doesn't move before `driftStart`), so the first channels can be read.
    func holdForLaunch() {
        guard !launchHeld, !channels.isEmpty else { return }
        launchHeld = true; guideScroll.driftStart = .now.addingTimeInterval(Self.launchHold)
    }

    // MARK: Attract mode

    /// Wake the screen, then start the attract countdown again if the guide is drifting untouched (no overlay, remote
    /// not held). Everything that changes that (a press, an overlay, letting go, leaving the guide) routes through here.
    func attractReset() {
        cancelTimer("attract")
        setAttracting(false)
        var mins = settings.attractMinutes
        #if DEBUG
        // Debug-build launch argument `-attractMinutesOverride N` (UI tests): this run only, never written to settings.
        if UserDefaults.standard.object(forKey: "attractMinutesOverride") != nil { mins = UserDefaults.standard.integer(forKey: "attractMinutesOverride") }
        #endif
        // Portrait has no attract mode: the phone layout has no storefront wall.
        guard screen == .guide, overlay == nil, !remoteHeld, !portrait, settings.autoScroll, mins > 0 else { return }
        later("attract", seconds: Double(mins * 60)) { [weak self] in
            guard let self, screen == .guide, overlay == nil, !remoteHeld, !portrait else { return }
            setAttracting(true)
        }
    }

    /// The drift slows to half while attracting; re-based first so the speed change doesn't jump the grid.
    private func setAttracting(_ on: Bool) {
        guard attracting != on else { return }
        guideScroll.resumeOffset = guideDriftOffset(); guideScroll.driftStart = .now
        guideScroll.speed = on ? 11 : 22
        attracting = on
    }
}
