// Port of android/app MainActivity.kt (RerunGuideApp + the RemoteCaptureView wiring): platform setup, the model, the
// remote, the app's background/foreground transitions and the render loop.
import { render } from 'preact'
import { toIntOrNull } from './core/Lang'
import { AppModel, LibraryCache } from './app/AppModel'
// The AppModel*.ts extension files add their methods to AppModel.prototype when imported (AppModel first: they patch it).
import './app/AppModelGuide'
import './app/AppModelOnboarding'
import './app/AppModelOverlays'
import './app/AppModelSettings'
import './app/AppModelWatch'
import './app/AppModelTouch'
import './app/AppModelSearch'
import './app/Accessibility'
import { Keychain } from './app/Keychain'
import { defaults } from './app/Persistence'
import { Platform } from './app/Platform'
import { RemoteCapture, RemoteCommand, remoteDrags } from './app/Remote'
import { RootView } from './app/RootView'

/**
 * The model behind a Proxy whose `set` trap schedules a render: every field assignment (`this.x = …` inside a method,
 * which runs with `this` = the proxy) redraws, even of an equal value (the spot's redraw relies on that). `receiver`
 * keeps setters running against the proxy, so their own assignments are seen too.
 */
export function observable<T extends object>(model: T, scheduleRender: () => void): T {
  return new Proxy(model, {
    set(target, key, value, receiver) {
      const ok = Reflect.set(target, key, value, receiver)
      scheduleRender()
      return ok
    },
  })
}

const root = document.getElementById('root')!
let queued = false
let model: AppModel

/** At most one render per animation frame, however many fields change. Component-local state (clocks, the drift) keeps
 *  Preact's own debounce: only the model goes through here. */
function scheduleRender() {
  if (queued) return
  queued = true
  requestAnimationFrame(() => { queued = false; draw() })
}

function draw() {
  render(<RootView model={model} />, root)
}

/** Browser build only: `seed.json` next to the page ({"persisted": {…}, "token": "…"}, the Android format) signs this
 *  browser in to a test server without typing a password. Imported once per distinct file (a served file can't be
 *  deleted, as Android deletes its copy), then the library cache goes too: it belongs to whatever server was there. */
async function importTestSeed(): Promise<void> {
  let text: string
  try {
    const r = await fetch('seed.json', { cache: 'no-store' })
    if (!r.ok) return
    text = await r.text()
  } catch {
    return
  }
  if (defaults.get('seedImported') === text) return
  try {
    const o = JSON.parse(text)
    if (typeof o.token === 'string' && o.token !== '') Keychain.set(o.token, 'token')
    if (o.persisted != null && typeof o.persisted === 'object') defaults.set('persisted', JSON.stringify(o.persisted))
  } catch (e) {
    console.warn('seed.json', e)
  }
  defaults.set('seedImported', text)
  LibraryCache.clear()
}

/** Browser build only: `?keys=down,down,select,wait:1500,right` presses those remote commands after launch, 400 ms
 *  apart (`wait:N` waits N ms more), so headless screenshots can walk the app. */
function replayKeys(spec: string) {
  const commands: readonly string[] = ['up', 'down', 'left', 'right', 'select', 'play', 'back']
  const steps = spec.split(',').map(s => s.trim()).filter(s => s !== '')
  let i = 0
  const next = () => {
    if (i >= steps.length) return
    const s = steps[i++]
    if (s.indexOf('wait:') === 0) { setTimeout(next, toIntOrNull(s.slice(5)) ?? 0); return }
    if (commands.indexOf(s) >= 0) model.press(s as RemoteCommand)
    else console.warn('keys: unknown command', s)
    setTimeout(next, 400)
  }
  setTimeout(next, 400)
}

/** Back at the top level. Samsung's exit() ends the app before a request could leave, so the server's streams are
 *  stopped first, as the Home screen does (ponytail: waiting 1 s at most). LG's Back only leaves for the launcher or its
 *  exit popup, where visibilitychange stops them (a cancelled popup must not stop the preview). */
function exitApp() {
  if (Platform.kind !== 'tizen') { Platform.exitApp(); return }
  void Promise.race([model.enteredBackground(), new Promise(r => setTimeout(r, 1000))]).then(() => Platform.exitApp())
}

function main() {
  Platform.init()
  const args = __DEV__ ? new URLSearchParams(location.search) : null
  model = observable(new AppModel(args), scheduleRender)
  model.start()

  // The remote while no text field is up (the text-entry panel takes the keys meanwhile); Back at the top level leaves
  // the app (LG: the launcher or its exit popup; Samsung: quits), as tvOS does with Menu. Number keys type a channel number.
  const remote = new RemoteCapture(
    () => model.editingField == null,
    () => model.backExitsApp,
    c => model.press(c),
    d => model.pressDigit(d),
    () => (model.screen === 'watch' && model.overlay == null ? model.playback?.paused === true : null),
    exitApp)
  remote.install()
  // A click while the number box collects digits (the Kotlin taps call closeEntry): the LG pointer's click is the OK
  // button, so it tunes the number and clicks nothing; any other click puts the box away and goes on to what it hit.
  window.addEventListener('click', e => {
    if (model.channelEntry?.missing !== false) return
    if (Platform.pointerVisible()) { e.stopPropagation(); model.commitEntry() } else model.closeEntry()
  }, true)
  // The wheel (Magic Remote, mouse) is Up / Down; the LG pointer brings up the touch chrome.
  remoteDrags(() => model.editingField == null, c => model.scroll(c))
  model.pointerVisible = Platform.pointerVisible()
  Platform.onPointerChange = v => { model.pointerVisible = v }

  // The Swift scenePhase .background / .active (Android onStop / onStart): the TV's Home screen or another app hides the page.
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) model.enteredBackground()
    else model.returnedToForeground()
  })

  draw()
  const keys = args && args.get('keys')
  if (__DEV__ && keys) replayKeys(keys)
}

if (__DEV__) void importTestSeed().then(main)
else main()
