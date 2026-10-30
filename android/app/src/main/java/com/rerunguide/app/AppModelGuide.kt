package com.rerunguide.app

import com.rerunguide.core.*
import java.time.Instant
import kotlin.math.floor
import kotlin.math.max

/* Guide remote handling + drift bookkeeping (port of the prototype's k_guide/gGrab/gNav/gScrollTo/gRelease…).
 * Row positions are "raw" track offsets: `guideScroll.row` and `.pos` grow without bound and the view wraps them
 * modulo the half-track height, so no ±half normalisation is needed. */

/** Height of one pass through the visible channels; every guide offset is taken modulo this. */
val AppModel.half: Float get() = max(1f, visibleChannels.size * rowPitch)
fun AppModel.wrap(x: Float): Float { val h = half; return ((x % h) + h) % h }
private fun jsRound(x: Float): Int = floor(x + 0.5f).toInt()
/** The channel on raw row `row` (any integer; the lineup wraps). */
fun AppModel.rowChannel(row: Int): Channel? { val list = visibleChannels; val n = list.size; return if (n > 0) list[((row % n) + n) % n] else null }

fun AppModel.pressGuide(cmd: RemoteCommand) {
    if (attracting) { attractReset(); return }   // the first press only wakes the screen, like a screensaver
    if (cmd == RemoteCommand.back) { if (remoteHeld) guideRelease() else openOptions(); return }
    if (!remoteHeld) {
        if (cmd == RemoteCommand.left) { openOptions(); return }   // Left is the drifting guide's way into Options: Back exits to Home here, and the airing program is the earliest in the window
        if (!guideGrab()) return
        guideScroll.pos = (guideScroll.row - 1) * rowPitch
        guideBump(); return
    }
    guideBump()
    when (cmd) {
        RemoteCommand.select -> guideSelect()
        RemoteCommand.play -> guideKey()?.let { program(it) }?.let { pr -> tune(pr.channel.id, Playback.Mode.start, programStart = pr.slot.start) }
        else -> guideNav(cmd)
    }
}

/** First press takes the remote: keep the focus if its cell is on screen, else focus the 2nd visible row at the current time. */
private fun AppModel.guideGrab(): Boolean {
    val list = visibleChannels; val n = list.size; val p = rowPitch; val now = Instant.now()
    if (n <= 0) return false
    var gf = guideKey(now) ?: return false
    val pr = program(gf) ?: return false
    val o = guideDriftOffset(now)
    guideScroll.pos = o
    val ws = windowStart(now); val we = ws.plusSeconds(AppModel.windowSpan)
    val r = list.indexOfFirst { it.id == pr.channel.id }.let { if (it < 0) 0 else it }
    var v = r + n * jsRound((o + p - r * p) / half)
    val seen = pr.slot.start < we && pr.slot.end > ws && v * p >= o - p / 2 && v * p <= o + gridHeight - p / 2
    if (seen) guideScroll.focusTime = maxOf(pr.slot.start, ws)
    else {
        v = jsRound(o / p) + 1
        val ch = rowChannel(v)
        val k = ch?.let { cellAt(it, maxOf(now, ws), ws) }
        val q = k?.let { program(it) }
        if (k != null && q != null) { gf = k; guideScroll.focusTime = maxOf(q.slot.start, ws) }
    }
    guideScroll.row = v
    remoteHeld = true; focused = gf
    return true
}

private fun AppModel.guideNav(cmd: RemoteCommand) {
    val now = Instant.now()
    if (cmd == RemoteCommand.up || cmd == RemoteCommand.down) {
        guideScroll.row += if (cmd == RemoteCommand.down) 1 else -1
        val ws = windowStart(now)
        rowChannel(guideScroll.row)?.let { focused = cellAt(it, maxOf(guideScroll.focusTime, ws), ws) }
        guideScroll.pos = (guideScroll.row - 1) * rowPitch
        return
    }
    val k = guideKey(now) ?: return
    val pr = program(k) ?: return
    val ch = pr.channel; val p = pr.slot
    val q = slotOnAir(ch, if (cmd == RemoteCommand.right) p.end else p.start.minusSeconds(1))
    if (q == null) { if (cmd == RemoteCommand.left) openOptions(); return }
    // Page only as far as it takes to show the program's start (or to the first window: the on-now one brings back now).
    var w = windowOffset
    if (cmd == RemoteCommand.right) { while (q.start >= windowStart(now, offset = w).plusSeconds(AppModel.windowSpan) && w < AppModel.maxWindowOffset) w += 1 }
    else { while (q.start < windowStart(now, offset = w) && w > 0) w -= 1 }
    val ws = windowStart(now, offset = w)
    if (q.start >= ws.plusSeconds(AppModel.windowSpan)) return
    if (q.end <= ws) { if (cmd == RemoteCommand.left) { if (windowOffset > 0) { windowOffset = 0; guideScroll.focusTime = maxOf(p.start, ws) } else openOptions() }; return }   // paged ahead (Up/Down or a tap got here): back to now first
    guideScroll.focusTime = maxOf(q.start, ws)
    windowOffset = w; focused = key(ch, q)
}

private fun AppModel.guideSelect() {
    val k = guideKey() ?: return
    val pr = program(k) ?: return
    when (status(pr.slot, Instant.now())) {
        ProgramStatus.live, ProgramStatus.`break` -> tune(pr.channel.id, Playback.Mode.live)
        ProgramStatus.later, ProgramStatus.past -> openInfo(k)
    }
}

// MARK: Touch / mouse

/** A tap on a guide cell on raw row `row`: the first takes the remote there (the grid stays put, no grab jump), a tap on
 *  the focused cell is Select. */
fun AppModel.tapGuide(row: Int, key: ProgramKey) {
    closeEntry()   // a tap moves the focus as the remote's arrows would
    if (attracting) { attractReset(); return }
    val pr = program(key) ?: return
    val now = Instant.now(); val again = guideKey(now) == key
    if (!remoteHeld) { guideScroll.pos = guideDriftOffset(now); remoteHeld = true }   // read before holding: the drift stops here
    guideScroll.row = row
    guideScroll.focusTime = maxOf(pr.slot.start, windowStart(now))
    focused = key
    guideBump()
    if (again) guideSelect()
}

/** The info panel's hint pills and preview window: act on the program the panel shows, without the grab a remote
 *  press needs first (a grab may move the focus to another row). */
fun AppModel.guideHint(cmd: RemoteCommand) {
    if (attracting) { attractReset(); return }
    when (cmd) {
        RemoteCommand.select -> guideSelect()
        RemoteCommand.play -> guideKey()?.let { program(it) }?.let { pr -> tune(pr.channel.id, Playback.Mode.start, programStart = pr.slot.start) }
        else -> press(cmd)
    }
}

/** Restart the idle timer; when it fires the guide releases back to drifting. */
fun AppModel.guideBump() = later("idle", seconds = settings.idleSeconds.toDouble()) { guideRelease() }

/** Let go of the remote: focus snaps to what's on now on the focused channel, window resets, drift resumes from here. */
fun AppModel.guideRelease() {
    cancelTimer("idle")
    if (!remoteHeld || screen != Screen.guide || overlay != null) return
    guidePark(); guideScroll.driftStart = Instant.now()
    attractReset()
}

/** Current drift translate offset (0 ..< half-track height) while the guide is drifting on its own. */
fun AppModel.guideDriftOffset(at: Instant = Instant.now()): Float {
    if (remoteHeld || !settings.autoScroll) return wrap(guideScroll.resumeOffset)
    val elapsed = max(0.0, at.secondsSince(guideScroll.driftStart))
    // Wrapped in Double before narrowing (Swift sums in CGFloat): after a day or so of drifting, a Float sum would step
    // by about a frame's motion and the glide would judder.
    val h = half.toDouble(); val x = (guideScroll.resumeOffset + elapsed * guideScroll.speed) % h
    return ((x + h) % h).toFloat()
}

/** The guide came on screen: the drift resumes from `resumeOffset` now. */
fun AppModel.guideAppeared() { guideScroll.driftStart = Instant.now(); holdForLaunch(); attractReset() }

/** How long the first guide of a launch waits before it starts to drift, so the first channels can be read. */
const val LAUNCH_HOLD_SECONDS = 4L

/** The first time a launch's guide is on screen with channels, it holds still [LAUNCH_HOLD_SECONDS] before the drift
 *  starts (the offset doesn't move before `driftStart`), so the first channels can be read. */
fun AppModel.holdForLaunch() {
    if (launchHeld || channels.isEmpty()) return
    launchHeld = true; guideScroll.driftStart = Instant.now().plusSeconds(LAUNCH_HOLD_SECONDS)
}

// MARK: Attract mode

/** Wake the screen, then start the attract countdown again if the guide is drifting untouched (no overlay, remote
 *  not held). Everything that changes that (a press, an overlay, letting go, leaving the guide) routes through here. */
fun AppModel.attractReset() {
    cancelTimer("attract")
    setAttracting(false)
    val minutes = attractOverride ?: settings.attractMinutes
    // Portrait has no attract mode: the phone layout has no storefront wall.
    if (screen != Screen.guide || overlay != null || remoteHeld || portrait || !settings.autoScroll || minutes <= 0) return
    later("attract", seconds = (minutes * 60).toDouble()) {
        if (screen == Screen.guide && overlay == null && !remoteHeld && !portrait) setAttracting(true)
    }
}

/** The drift slows to half while attracting; re-based first so the speed change doesn't jump the grid. */
private fun AppModel.setAttracting(on: Boolean) {
    if (attracting == on) return
    guideScroll.resumeOffset = guideDriftOffset(); guideScroll.driftStart = Instant.now()
    guideScroll.speed = if (on) 11f else 22f
    attracting = on
}
