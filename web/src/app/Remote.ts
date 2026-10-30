// Port of android/app Remote.kt: the remote's keys (LG, Samsung, a keyboard) as the app's seven commands and its number
// keys, and the wheel.
// The TalkBack side (`Modifier.remoteCapture`) is `accessibilityDescription` in an aria-live region (RootView); the
// touch drags of `Modifier.remoteDrags` are left out: no TV here has a touchscreen.
import { Platform, remoteFocus } from './Platform'

/** The seven things a remote can say. D-pad presses, keyboard arrows and the wheel all become up/down/left/right. */
export type RemoteCommand = 'up' | 'down' | 'left' | 'right' | 'select' | 'play' | 'back'

/** `e.keyCode`s (`e.key` names vary across TVs). Samsung delivers the media, channel and number keys only once registered
 *  (`Platform.registerKeys`); LG's Magic Remote has no media keys, a conventional LG remote or HDMI-CEC sends them. */
const Key = {
  enter: 13, space: 32, backspace: 8, escape: 27,
  left: 37, up: 38, right: 39, down: 40,
  lgBack: 461, samsungBack: 10009,
  play: 415, pause: 19, playPause: 10252,
  fastForward: 417, rewind: 412,
  channelUp: 427, channelDown: 428,
  digit0: 48, digit9: 57, numpad0: 96, numpad9: 105,
}

/** The remote's buttons (and a keyboard: arrows, Enter = Select, Space = Play/Pause, Esc or Backspace = Back).
 *  Shortcuts with Ctrl, Alt or Meta stay the browser's. Null for every other key. `inPlayer`: the channel and fast-forward /
 *  rewind keys mean what the player's Up / Down (channel) and Right / Left (10 s) already do; off the player they stay
 *  unmapped (on the guide CH+ would point the wrong way). */
export function remoteCommand(e: KeyboardEvent, inPlayer = false): RemoteCommand | null {
  if (e.ctrlKey || e.altKey || e.metaKey) return null
  switch (e.keyCode) {
    case Key.up: return 'up'
    case Key.down: return 'down'
    case Key.left: return 'left'
    case Key.right: return 'right'
    case Key.enter: return 'select'
    case Key.playPause: case Key.play: case Key.pause: case Key.space: return 'play'
    case Key.lgBack: case Key.samsungBack: case Key.escape: case Key.backspace: return 'back'
    case Key.channelUp: return inPlayer ? 'up' : null
    case Key.channelDown: return inPlayer ? 'down' : null
    case Key.fastForward: return inPlayer ? 'right' : null
    case Key.rewind: return inPlayer ? 'left' : null
    default: return null
  }
}

/** A remote's number keys, 0–9 (a keyboard's number row and keypad too, Shift or not): channel numbers on the guide and
 *  the player. Null for every other key and with Ctrl, Alt or Meta. */
function remoteDigit(e: KeyboardEvent): number | null {
  if (e.ctrlKey || e.altKey || e.metaKey) return null
  const k = e.keyCode
  return k >= Key.digit0 && k <= Key.digit9 ? k - Key.digit0 : k >= Key.numpad0 && k <= Key.numpad9 ? k - Key.numpad0 : null
}

/**
 * The key side of RemoteCaptureUIView: owns the remote while `isActive` (the text-entry panel takes the keys meanwhile).
 * One command per press, sent on key-down, exactly like UIKit's pressesBegan: a held key never repeats and a long press
 * is just a press (its repeats are swallowed until its key-up; TVs don't always flag them `repeat`). `passBack` hands Back
 * to `exitApp` at the app's top level. Keys that are not a command keep their default.
 * Number keys go to `onDigit` instead.
 * main.tsx: `new RemoteCapture(() => model.editingField == null, () => model.backExitsApp, c => model.press(c), …).install()`.
 */
export class RemoteCapture {
  /** Keys whose key-down was taken here, with the time of their latest key-down; their repeats are taken too. */
  private readonly held = new Map<number, number>()

  constructor(
    private readonly isActive: () => boolean,
    private readonly passBack: () => boolean,
    private readonly onCommand: (cmd: RemoteCommand) => void,
    /** 0–9: channel numbers on the guide and the player (`model.pressDigit`). */
    private readonly onDigit: (d: number) => void,
    /** null off the player; on it, whether playback is paused (for the separate Play / Pause keys below). */
    private readonly playerPaused: () => boolean | null = () => null,
    /** Back at the top level. main.tsx can put Samsung's exit confirmation in front of `Platform.exitApp`. */
    private readonly exitApp: () => void = Platform.exitApp,
  ) {}

  /** True when the event was taken (and its default prevented). */
  dispatchKeyEvent(e: KeyboardEvent): boolean {
    if (e.type === 'keyup') return this.held.delete(e.keyCode)
    if (e.type !== 'keydown') return false
    const t = Date.now()
    const last = this.held.get(e.keyCode)
    if (last != null) {
      // A repeat is swallowed (TVs don't always flag them, but send them back to back). A key-down after a pause is a new
      // press whose key-up went elsewhere (a keyboard or popup took it): it acts, as Kotlin's repeatCount 0 always does.
      if (e.repeat || t - last < repeatGap) { this.held.set(e.keyCode, t); e.preventDefault(); return true }
      this.held.delete(e.keyCode)
    }
    // While a text field owns the keys nothing here calls preventDefault (Samsung's IME rule).
    if (e.repeat || !this.isActive()) return false
    // LG: OK while the Magic Remote's pointer shows arrives as the pointer's click too; the click does it.
    if (e.keyCode === Key.enter && Platform.pointerVisible()) return false
    const d = remoteDigit(e)
    if (d != null) { e.preventDefault(); this.held.set(e.keyCode, t); this.onDigit(d); return true }
    const paused = this.playerPaused()
    const cmd = remoteCommand(e, paused != null)
    if (cmd == null) return false
    e.preventDefault()
    this.held.set(e.keyCode, t)   // Back that leaves too: its unflagged repeats mustn't call exitApp again
    if (cmd === 'back' && this.passBack()) { this.exitApp(); return true }
    // Remotes with separate Play and Pause keys (a conventional remote, HDMI-CEC) get only what the key names: Play while
    // playing and Pause while paused (or off the player) are taken and dropped, not toggled.
    const noop = (e.keyCode === Key.play && paused === false) || (e.keyCode === Key.pause && paused !== false)
    remoteFocus()
    if (!noop) this.onCommand(cmd)
    return true
  }

  /** A key-up lost while the app was hidden or unfocused must not eat that key's next press. */
  reset(): void {
    this.held.clear()
  }

  /** Listen on the window (bubble phase, so the text-entry input's own handler stops its keys first). */
  install(): void {
    window.addEventListener('keydown', e => this.dispatchKeyEvent(e))
    window.addEventListener('keyup', e => this.dispatchKeyEvent(e))
    window.addEventListener('blur', () => this.reset())
    document.addEventListener('visibilitychange', () => this.reset())
  }
}

/** ponytail: longer than a TV remote's first auto-repeat delay (~0.5 s), the knob if a long press ever acts twice. */
const repeatGap = 1000

/** ponytail: one press per 100 px of wheel (a desktop notch; Swift uses 80 stage pt). The calibration knob if the Magic
 *  Remote's wheel turns out to step differently. */
const wheelStep = 100

/**
 * The wheel half of Kotlin's `Modifier.remoteDrags`: the Magic Remote's wheel (and a mouse's) anywhere in the window is
 * Up / Down, about one per row (scrolling the content up presses Down), sent to `onScroll` (`model.scroll`). Ignored
 * while `isActive` is false.
 */
export function remoteDrags(isActive: () => boolean, onScroll: (cmd: RemoteCommand) => void): void {
  let wheel = 0
  window.addEventListener('wheel', (e: WheelEvent) => {
    if (!isActive()) return
    wheel += e.deltaMode === 1 ? e.deltaY * 40 : e.deltaY   // DOM_DELTA_LINE: ~40 px a line
    if (Math.abs(wheel) >= wheelStep) remoteFocus()
    while (wheel >= wheelStep) { onScroll('down'); wheel -= wheelStep }
    while (wheel <= -wheelStep) { onScroll('up'); wheel += wheelStep }
  }, { passive: true })
}
