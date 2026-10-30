// Port of android/app AppModelGuide.kt: guide remote handling + drift bookkeeping (the prototype's k_guide/gGrab/gNav/
// gScrollTo/gRelease…). Row positions are "raw" track offsets: `guideScroll.row` and `.pos` grow without bound and the view
// wraps them modulo the half-track height, so no ±half normalisation is needed. `guideScroll` is mutated in place on purpose:
// it changes every frame while drifting and is never observed.
import type { Channel } from '../core/Models'
import { Instant, now } from '../core/Time'
import { AppModel } from './AppModel'
import { ProgramKey } from './AppModel'
import type { RemoteCommand } from './Remote'

declare module './AppModel' {
  interface AppModel {
    /** Height of one pass through the visible channels; every guide offset is taken modulo this. */
    readonly half: number
    wrap(x: number): number
    /** The channel on raw row `row` (any integer; the lineup wraps). */
    rowChannel(row: number): Channel | null
    pressGuide(cmd: RemoteCommand): void
    tapGuide(row: number, key: ProgramKey): void
    /** The LG pointer over a cell: focus it as a tap would, without selecting it (the click that follows does). */
    pointGuide(row: number, key: ProgramKey): void
    guideHint(cmd: RemoteCommand): void
    guideBump(): void
    guideRelease(): void
    guideDriftOffset(at?: Instant): number
    guideAppeared(): void
    holdForLaunch(): void
    attractReset(): void
  }
}

Object.defineProperty(AppModel.prototype, 'half', {
  get(this: AppModel) { return Math.max(1, this.visibleChannels.length * this.rowPitch) },
})

AppModel.prototype.wrap = function (this: AppModel, x: number): number {
  const h = this.half
  return ((x % h) + h) % h
}

/** Kotlin's `floor(x + 0.5f)`: JS `Math.round` rounds halves the same way. */
function jsRound(x: number): number {
  return Math.floor(x + 0.5)
}

AppModel.prototype.rowChannel = function (this: AppModel, row: number): Channel | null {
  const list = this.visibleChannels
  const n = list.length
  return n > 0 ? list[((row % n) + n) % n] : null
}

AppModel.prototype.pressGuide = function (this: AppModel, cmd: RemoteCommand) {
  if (this.attracting) { this.attractReset(); return }   // the first press only wakes the screen, like a screensaver
  if (cmd === 'back') { if (this.remoteHeld) this.guideRelease(); else this.openOptions(); return }
  if (!this.remoteHeld) {
    // Left is the drifting guide's way into Options: Back exits to Home here, and the airing program is the earliest in the window.
    if (cmd === 'left') { this.openOptions(); return }
    if (!guideGrab(this)) return
    this.guideScroll.pos = (this.guideScroll.row - 1) * this.rowPitch
    this.guideBump(); return
  }
  this.guideBump()
  if (cmd === 'select') guideSelect(this)
  else if (cmd === 'play') startFocused(this)
  else guideNav(this, cmd)
}

/** Play/Pause on a cell: that program from its beginning. */
function startFocused(m: AppModel) {
  const k = m.guideKey()
  const pr = k != null ? m.program(k) : null
  if (pr != null) m.tune(pr.channel.id, 'start', pr.slot.start)
}

/** First press takes the remote: keep the focus if its cell is on screen, else focus the 2nd visible row at the current time. */
function guideGrab(m: AppModel): boolean {
  const list = m.visibleChannels, n = list.length, p = m.rowPitch, t = now()
  if (n <= 0) return false
  let gf = m.guideKey(t)
  if (gf == null) return false
  const pr = m.program(gf)
  if (pr == null) return false
  const o = m.guideDriftOffset(t)
  m.guideScroll.pos = o
  const ws = m.windowStart(t), we = ws + AppModel.windowSpan
  const r = Math.max(0, list.findIndex(c => c.id === pr.channel.id))
  let v = r + n * jsRound((o + p - r * p) / m.half)
  const seen = pr.slot.start < we && pr.slot.end > ws && v * p >= o - p / 2 && v * p <= o + m.gridHeight - p / 2
  if (seen) m.guideScroll.focusTime = Math.max(pr.slot.start, ws)
  else {
    v = jsRound(o / p) + 1
    const ch = m.rowChannel(v)
    const k = ch != null ? m.cellAt(ch, Math.max(t, ws), ws) : null
    const q = k != null ? m.program(k) : null
    if (k != null && q != null) { gf = k; m.guideScroll.focusTime = Math.max(q.slot.start, ws) }
  }
  m.guideScroll.row = v
  m.remoteHeld = true; m.focused = gf
  return true
}

function guideNav(m: AppModel, cmd: RemoteCommand) {
  const t = now()
  if (cmd === 'up' || cmd === 'down') {
    m.guideScroll.row += cmd === 'down' ? 1 : -1
    const ws = m.windowStart(t)
    const ch = m.rowChannel(m.guideScroll.row)
    if (ch != null) m.focused = m.cellAt(ch, Math.max(m.guideScroll.focusTime, ws), ws)
    m.guideScroll.pos = (m.guideScroll.row - 1) * m.rowPitch
    return
  }
  const k = m.guideKey(t)
  if (k == null) return
  const pr = m.program(k)
  if (pr == null) return
  const ch = pr.channel, p = pr.slot
  const q = m.slotOnAir(ch, cmd === 'right' ? p.end : p.start - 1)
  if (q == null) { if (cmd === 'left') m.openOptions(); return }
  // Page only as far as it takes to show the program's start (or to the first window: the on-now one brings back now).
  let w = m.windowOffset
  if (cmd === 'right') { while (q.start >= m.windowStart(t, w) + AppModel.windowSpan && w < AppModel.maxWindowOffset) w += 1 }
  else { while (q.start < m.windowStart(t, w) && w > 0) w -= 1 }
  const ws = m.windowStart(t, w)
  if (q.start >= ws + AppModel.windowSpan) return
  if (q.end <= ws) { if (cmd === 'left') { if (m.windowOffset > 0) { m.windowOffset = 0; m.guideScroll.focusTime = Math.max(p.start, ws) } else m.openOptions() }; return }   // paged ahead (Up/Down or a tap got here): back to now first
  m.guideScroll.focusTime = Math.max(q.start, ws)
  m.windowOffset = w; m.focused = m.key(ch, q)
}

function guideSelect(m: AppModel) {
  const k = m.guideKey()
  if (k == null) return
  const pr = m.program(k)
  if (pr == null) return
  switch (m.status(pr.slot, now())) {
    case 'live': case 'break': m.tune(pr.channel.id, 'live'); break
    case 'later': case 'past': m.openInfo(k); break
  }
}

// MARK: Touch / mouse (the Magic Remote's pointer)

/** A click on a guide cell on raw row `row`: the first takes the remote there (the grid stays put, no grab jump), a click
 *  on the focused cell is Select. */
AppModel.prototype.tapGuide = function (this: AppModel, row: number, key: ProgramKey) {
  if (this.attracting) { this.attractReset(); return }
  if (focusCell(this, row, key)) guideSelect(this)
}

AppModel.prototype.pointGuide = function (this: AppModel, row: number, key: ProgramKey) {
  if (!this.attracting) focusCell(this, row, key)
}

/** Take the remote (stopping the drift where it is) and focus that cell; true if it already had the focus. */
function focusCell(m: AppModel, row: number, key: ProgramKey): boolean {
  const pr = m.program(key)
  if (pr == null) return false
  const t = now(), again = ProgramKey.eq(m.guideKey(t), key)
  if (!m.remoteHeld) { m.guideScroll.pos = m.guideDriftOffset(t); m.remoteHeld = true }   // read before holding: the drift stops here
  m.guideScroll.row = row
  m.guideScroll.focusTime = Math.max(pr.slot.start, m.windowStart(t))
  m.focused = key
  m.guideBump()
  return again
}

/** The info panel's hint pills and preview window: act on the program the panel shows, without the grab a remote press
 *  needs first (a grab may move the focus to another row). */
AppModel.prototype.guideHint = function (this: AppModel, cmd: RemoteCommand) {
  if (this.attracting) { this.attractReset(); return }
  if (cmd === 'select') guideSelect(this)
  else if (cmd === 'play') startFocused(this)
  else this.press(cmd)
}

/** Restart the idle timer; when it fires the guide releases back to drifting. */
AppModel.prototype.guideBump = function (this: AppModel) {
  this.later('idle', this.settings.idleSeconds, () => this.guideRelease())
}

/** Let go of the remote: focus snaps to what's on now on the focused channel, window resets, drift resumes from here. */
AppModel.prototype.guideRelease = function (this: AppModel) {
  this.cancelTimer('idle')
  if (!this.remoteHeld || this.screen !== 'guide' || this.overlay != null) return
  this.guidePark(); this.guideScroll.driftStart = now()
  this.attractReset()
}

/** Current drift translate offset (0 ..< half-track height) while the guide is drifting on its own. */
AppModel.prototype.guideDriftOffset = function (this: AppModel, at: Instant = now()): number {
  if (this.remoteHeld || !this.settings.autoScroll) return this.wrap(this.guideScroll.resumeOffset)
  const elapsed = Math.max(0, at - this.guideScroll.driftStart)
  const h = this.half, x = (this.guideScroll.resumeOffset + elapsed * this.guideScroll.speed) % h
  return (x + h) % h
}

/** The guide came on screen: the drift resumes from `resumeOffset` now. */
AppModel.prototype.guideAppeared = function (this: AppModel) {
  this.guideScroll.driftStart = now()
  this.holdForLaunch()
  this.attractReset()
}

/** The first time a launch's guide is on screen with channels, it holds still `launchHold` seconds before the drift starts
 *  (the offset doesn't move before `driftStart`), so the first channels can be read. */
AppModel.prototype.holdForLaunch = function (this: AppModel) {
  if (this.launchHeld || this.channels.length === 0) return
  this.launchHeld = true; this.guideScroll.driftStart = now() + AppModel.launchHold
}

// MARK: Attract mode

/** Wake the screen, then start the attract countdown again if the guide is drifting untouched (no overlay, remote not
 *  held). Everything that changes that (a press, an overlay, letting go, leaving the guide) routes through here. */
AppModel.prototype.attractReset = function (this: AppModel) {
  this.cancelTimer('attract')
  setAttracting(this, false)
  const minutes = this.attractOverride ?? this.settings.attractMinutes
  if (this.screen !== 'guide' || this.overlay != null || this.remoteHeld || !this.settings.autoScroll || minutes <= 0) return
  this.later('attract', minutes * 60, () => {
    if (this.screen === 'guide' && this.overlay == null && !this.remoteHeld) setAttracting(this, true)
  })
}

/** The drift slows to half while attracting; re-based first so the speed change doesn't jump the grid. */
function setAttracting(m: AppModel, on: boolean) {
  if (m.attracting === on) return
  m.guideScroll.resumeOffset = m.guideDriftOffset(); m.guideScroll.driftStart = now()
  m.guideScroll.speed = on ? 11 : 22
  m.attracting = on
}
