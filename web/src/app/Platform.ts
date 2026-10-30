// Port of android/app Platform.kt: everything LG-, Samsung- or browser-specific, and the only file that touches
// webOSSystem, PalmSystem, tizen or webapis. Every TV call is guarded: the same bundle runs in a desktop browser.
import { useRef } from 'preact/hooks'
import { ClientDevice } from '../core/MediaServer'

interface WebOSSystem {
  platformBack(): void
  cursor?: { visibility?: boolean }
}
interface Tizen {
  tvinputdevice: { registerKeyBatch(keys: string[], ok?: (() => void) | null, err?: (e: unknown) => void): void }
  application: { getCurrentApplication(): { exit(): void } }
}
interface WebAPIs {
  appcommon: {
    setScreenSaver(state: number, ok?: (() => void) | null, err?: (e: unknown) => void): void
    AppCommonScreenSaverState: { SCREEN_SAVER_OFF: number; SCREEN_SAVER_ON: number }
  }
}

declare global {
  interface Window {
    webOSSystem?: WebOSSystem
    PalmSystem?: WebOSSystem
    tizen?: Tizen
    /** Samsung's webapis.js, loaded by the Tizen index.html. */
    webapis?: WebAPIs
  }
  /** true in the browser build only (build.mjs `define`): debug launch parameters, seed.json, hls.js. */
  const __DEV__: boolean
}

export type PlatformKind = 'webos' | 'tizen' | 'browser'

const lg: WebOSSystem | undefined = window.webOSSystem || window.PalmSystem   // webOSSystem from webOS 5, PalmSystem before
const kind: PlatformKind = lg ? 'webos' : window.tizen ? 'tizen' : 'browser'

let pointer = false
let screenSaverOn = true

function attempt(f: () => void) {
  try { f() } catch (e) { console.warn(e) }
}

/** LG, Samsung and a desktop browser from one bundle. Call `init()` once before the first render. */
export const Platform = {
  kind,
  /** Always a TV on the web: the TV copy and layout everywhere. */
  isTV: true as const,
  /** What the copy calls this device; also the server's device name (ClientDevice.device). */
  device: kind === 'webos' ? 'LG TV' : kind === 'tizen' ? 'Samsung TV' : 'TV',
  /** How the copy says "act on it". */
  verb: 'Press',
  /** The remote's select button as the copy names it. */
  select: kind === 'webos' ? 'OK' : 'Select',

  /** Set by main.tsx: the LG Magic Remote pointer appeared or went away (`pointerVisible()` already reflects it). */
  onPointerChange: null as ((visible: boolean) => void) | null,

  init() {
    // MainActivity's ClientDevice setup. Codecs are fixed per platform: canPlayType is unreliable on TVs, and every 2020+
    // set decodes HEVC Main10 and Dolby Digital (Plus). The browser build is for development: H.264 and AAC only.
    // X-Plex-Platform: Plex answers 400 to "webOS" and "Tizen". "Generic" keeps HEVC and E-AC3 copied into fMP4 HLS (the
    // TVs' native player); "Web" would re-encode them to H.264/AAC in TS (the fallback if fMP4 HEVC fails on a TV).
    ClientDevice.platform = kind === 'browser' ? 'Web' : 'Generic'
    ClientDevice.device = Platform.device
    ClientDevice.videoCodecs = kind === 'browser' ? 'h264' : 'h264,hevc'
    ClientDevice.audioCodecs = kind === 'browser' ? 'aac' : 'aac,ac3,eac3'
    ClientDevice.directPlayAudioCodecs = ClientDevice.audioCodecs
    if (kind === 'tizen') Platform.registerKeys()
    if (lg) {
      pointer = !!(lg.cursor && lg.cursor.visibility)
      document.body.classList.toggle('lg-pointer', pointer)
      document.addEventListener('cursorStateChange', (e) => {
        pointer = !!(e as CustomEvent<{ visibility?: boolean }>).detail?.visibility
        document.body.classList.toggle('lg-pointer', pointer)   // turns on the .hov outline (index.html's CSS)
        if (!pointer) { pointed = null; pointedAt = '' }
        Platform.onPointerChange?.(pointer)
      })
    }
  },

  /** Samsung delivers arrows, OK and Back by itself; media, channel and number keys only once registered. Never Back (its
   *  long press is the system's Exit), Exit or volume. The number keys ("0"…"9", the Smart Remote's on-screen 123 pad too)
   *  are a batch of their own, so a set that refuses one keeps the media keys. */
  registerKeys() {
    const register = (keys: string[]) =>
      attempt(() => window.tizen?.tvinputdevice.registerKeyBatch(keys, null, (e) => console.warn('registerKeyBatch', e)))
    register(['MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaFastForward', 'MediaRewind', 'ChannelUp', 'ChannelDown'])
    register(['0', '1', '2', '3', '4', '5', '6', '7', '8', '9'])
  },

  /** Back at the top level. LG: the system's exit popup (webOS 6+) or the launcher (5). Samsung: quits at once (no exit
   *  confirmation yet: one would go in front of this, as RemoteCapture's `exitApp`). Browser: nothing. */
  exitApp() {
    if (lg) attempt(() => lg.platformBack())
    else if (window.tizen) attempt(() => window.tizen!.application.getCurrentApplication().exit())
  },

  /** Samsung: the screensaver must stay off while video plays (and while channel 99 shows). LG has no API: only
   *  full-screen video holds its screensaver off. */
  setScreenSaver(on: boolean) {
    const ac = window.webapis?.appcommon
    if (!ac || on === screenSaverOn) return
    screenSaverOn = on
    const s = ac.AppCommonScreenSaverState
    attempt(() => ac.setScreenSaver(on ? s.SCREEN_SAVER_ON : s.SCREEN_SAVER_OFF, null, (e) => console.warn('setScreenSaver', e)))
  },

  /** LG: the Magic Remote's pointer is showing. While it is, OK arrives as the pointer's click (Remote ignores key 13). */
  pointerVisible(): boolean {
    return pointer
  },
}

// The touch layer of Platform.kt, always on here: the Magic Remote's pointer is a mouse, and a div with only an onClick
// never takes focus away from the remote. Spread into an element: `<div {...onTouch(() => model.openOptions())}>`.

/** A click on this element runs `action`, and only this: the topmost element wins, as with taps in Compose and SwiftUI. */
export function onTouch(action: () => void): { onClick: (e: MouseEvent) => void } {
  return { onClick: (e) => { e.stopPropagation(); remoteFocus(); action() } }
}

let pointed: EventTarget | null = null   // the target the pointer last moved the focus to
let pointedAt = ''                       // where the pointer was then
let pointerFocus = false                 // the focus was last moved by the pointer (not a key, the wheel or a click)

/**
 * A focusable target: the LG pointer moving onto it moves the remote's focus there (`focus`), the mouse-over state LG's
 * QA checks on every pointer target. Only a real move counts: a list sliding under a still pointer moves nothing.
 * Spread next to the click: `<div {...onTouch(act)} {...onPoint(() => { model.focusIndex = i })}>`.
 */
export function onPoint(focus: () => void): { onMouseMove: (e: MouseEvent) => void } {
  return {
    onMouseMove: (e) => {
      if (!pointer || e.currentTarget === pointed) return
      const at = `${e.clientX},${e.clientY}`
      if (at === pointedAt) return
      pointed = e.currentTarget; pointedAt = at; pointerFocus = true
      focus()
    },
  }
}

/** A key, the wheel or a click: the remote's focus rules again (Remote.ts, onTouch). */
export function remoteFocus(): void { pointerFocus = false; pointed = null }

/** A list's scroll position (or first shown row) that holds still while the pointer moves the focus, so the row under
 *  the pointer doesn't slide away from it; the next key, wheel turn or click puts the list back to following the focus. */
export function useSticky(value: number): number {
  const last = useRef(value)
  if (!pointerFocus) last.current = value
  return last.current
}

/** A click on empty space (a scrim, the picture). Elements on top keep their own clicks (`onTouch`). */
export function backgroundTap(action: () => void): { onClick: () => void } {
  return { onClick: () => action() }
}
