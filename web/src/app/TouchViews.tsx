// Port of android/app TouchViews.kt for TVs: TouchChrome (for the LG Magic Remote's pointer) and TextEntry. MenuHint is
// phone-only (an edge-swipe hint) and isn't ported.
import type { JSX } from 'preact'
import { useEffect, useRef, useState } from 'preact/hooks'
import { natural, useExit } from './Anim'
import { AppModel, useModel } from './AppModel'
import { Platform, onTouch } from './Platform'
import { remoteCommand } from './Remote'
import { useTheme } from './Theme'

/** Kotlin's 44 dp phone button, scaled up to a TV pointer target: LG's QA asks for 75 × 75 px or more at 1080p. */
const S = 76 / 44

/** The chrome is up (its bottom edge is at (16 + 44) × S ≈ 104 on the stage): the pointer is (LG), or always in a desktop
 *  browser, on a screen with an on-screen Back. */
export function touchChromeShows(model: AppModel): boolean {
  return (model.pointerVisible || Platform.kind === 'browser') && model.showsTouchBack
}

/**
 * Back, and Play/Pause on the player (no Menu button: the Options hint pill and the profile chip are there). Shown while
 * the LG Magic Remote's pointer is (it has no Play/Pause key, so this is how a pointer pauses) and always in a desktop
 * browser. Window space, fading 200 ms: Back in the top-left corner (Kotlin's 44 at (9, 16), scaled by `S`); Play/Pause
 * low and centred where a TV player's controls sit, above the banner while it's up, and gone while the banner's buttons
 * (which have a Pause of their own) or a track list are.
 */
export function TouchChrome() {
  const model = useModel()
  const paused = model.playback?.paused === true
  const { value: shown, leaving } = useExit(touchChromeShows(model) ? true : null, 200)
  if (!shown) return null
  const fade = leaving ? 'fadeOut 200ms ease-out forwards' : 'fadeIn 200ms ease-out backwards'
  return (
    <>
      <div style={{ position: 'fixed', top: 16 * S, left: 9 * S, display: 'flex', animation: fade }}>
        <TouchButton glyph={backGlyph} label="Back" action={() => model.touchBack()} />
      </div>
      {model.screen === 'watch' && model.trackMenu == null && model.bannerFocus < 0 && (   // the banner's buttons have their own Pause
        <div style={{ position: 'fixed', left: '50%', bottom: model.bannerVisible ? '39vh' : '9vh', marginLeft: -22 * S, animation: fade }}>
          <TouchButton glyph={paused ? playGlyph : pauseGlyph} label={paused ? 'Play' : 'Pause'} action={() => model.press('play')} />
        </div>
      )}
    </>
  )
}

/** Under the pointer it takes the theme's focus colours: LG's QA wants a visible selection on every pointer target. */
function TouchButton(props: { glyph: JSX.Element; label: string; action: () => void; style?: JSX.CSSProperties }) {
  const t = useModel().theme
  const [hover, setHover] = useState(false)
  const n = parseInt(t.panel.slice(1), 16)   // t.panel at 90 %
  return (
    <div role="button" aria-label={props.label} {...onTouch(props.action)} onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)} style={{
      width: 44 * S, height: 44 * S, borderRadius: t.radius === 0 ? 0 : '50%',
      background: hover ? t.focusBg : `rgba(${n >> 16},${(n >> 8) & 255},${n & 255},0.9)`, color: hover ? t.focusInk : t.ink, ...props.style,
    }}>
      <svg width={44 * S} height={44 * S} viewBox="-22 -22 44 44" style={{ display: 'block' }}>{props.glyph}</svg>
    </div>
  )
}

// SF Symbols at 18 pt bold, drawn around the button's center.
const backGlyph = (   // chevron.backward
  <path d="M3 -8L-5 0L3 8" fill="none" stroke="currentColor" stroke-width={2.6} stroke-linecap="round" stroke-linejoin="round" />
)
const playGlyph = (   // play.fill, stroked too for the symbol's rounded corners
  <path d="M-5 -8L8.5 0L-5 8Z" fill="currentColor" stroke="currentColor" stroke-width={2} stroke-linejoin="round" />
)
const pauseGlyph = (   // pause.fill
  <g fill="currentColor"><rect x={-6} y={-8} width={4.5} height={16} rx={1.2} /><rect x={1.5} y={-8} width={4.5} height={16} rx={1.2} /></g>
)

/**
 * Text input: the field the remote would have opened inline (`editingField`) as a panel in the top third of the stage with
 * a real <input>, focused on open so the TV's own keyboard comes up (webOS wants the lower two thirds free for it). Enter
 * (and Samsung's IME Done) commits the way the tvOS fields' submit does; Back or Esc leaves the value as it was. Clicks
 * around it do nothing, as an alert ignores taps outside it. Search's results follow the typing behind the panel.
 */
export function TextEntry() {
  const field = useModel().editingField
  return field == null ? null : <TextEntryPanel key={field} field={field} />
}

function TextEntryPanel({ field }: { field: string }) {
  const model = useModel()
  const t = useTheme()
  const input = useRef<HTMLInputElement>(null)
  const original = useRef('')

  /** Never reads `editingField` after open: the field is the one this panel was opened for. */
  const close = (save: boolean) => {
    const el = input.current
    if (!el) return
    el.blur()   // hides Samsung's IME
    if (save) commit(model, field, el.value)
    else cancel(model, original.current)
    window.scrollTo(0, 0)   // webOS may have slid the page up for its keyboard
  }

  useEffect(() => {
    const el = input.current!
    el.value = original.current = initial(model, field)   // the caret goes to the end (no setSelectionRange: number inputs throw)
    el.focus()
    // Keys that reach the window while the panel is up come from outside the field (its keyboard was put away some other
    // way, or a click blurred it); the remote's handler is off meanwhile. OK brings the keyboard back, Back cancels.
    const stray = (e: KeyboardEvent) => {
      const c = remoteCommand(e)
      if (c === 'select') el.focus()
      else if (c === 'back') close(false)
    }
    window.addEventListener('keydown', stray)
    return () => {   // OK / Back closed it, or the model closed the field itself
      window.removeEventListener('keydown', stray)
      el.blur()
      window.scrollTo(0, 0)
    }
  }, [])

  // Never preventDefault in here (Samsung's IME relies on the defaults). Every key stops here: a commit's Enter must not
  // reach the remote's handler as Select (it would open the field again).
  const onKeyDown = (e: KeyboardEvent) => {
    e.stopPropagation()
    const k = e.keyCode
    // LG: OK with the pointer up arrives as its click too (Remote.ts's rule), which does the job: Cancel cancels, a click
    // on the field brings the keyboard back. Committing on its 13 would save what Cancel was meant to drop.
    if (k === 13 && Platform.pointerVisible()) return
    if (k === 13 || k === 65376) close(true)   // Enter (the webOS keyboard's too), Samsung IME Done
    else if (k === 461 || k === 10009 || k === 27) close(false)   // LG Back, Samsung Back, Esc
    // Samsung IME Cancel (65385) is ignored: it comes for Back while the IME is up and also for Up past the IME's top row
    // (2020+ sets), like Google TV's Done. The IME hides itself and the field stays open; a later Back cancels.
  }

  const onInput = field === 'search' ? () => { model.searchQuery = input.current?.value ?? '' } : undefined
  // A click anywhere but the field keeps the field focused (and the keyboard up).
  const keepFocus = (e: MouseEvent) => { if (e.target !== input.current) e.preventDefault() }
  // 76 high: an LG pointer target (75 × 75 or more).
  const pill: JSX.CSSProperties = { height: 76, padding: '0 36px', borderRadius: t.pill, display: 'flex', alignItems: 'center', ...natural(t.body(28, 700)) }
  const type = field === 'pass' ? 'password' : field === 'addr' ? 'url' : field === 'chNum' ? 'number' : 'text'
  return (
    <div style={{ position: 'absolute', left: 0, top: 0, width: 1920, height: 1080, background: t.scrim }} onMouseDown={keepFocus}>
      <div style={{ position: 'absolute', left: 460, top: 120, width: 1000, padding: 48, background: t.panel, borderRadius: t.radius, boxShadow: '0 40px 120px rgba(0,0,0,0.6)' }}>
        <div style={{ ...natural(t.heading(46, 600)), color: t.ink, letterSpacing: t.tracking(t.headingTracking) }}>{t.heading(title(field))}</div>
        <input ref={input} type={type} placeholder={title(field)} onKeyDown={onKeyDown} onInput={onInput}
          autocomplete="off" autocorrect="off" spellcheck={false} autocapitalize={field === 'profileName' || field === 'chName' ? 'words' : 'off'}
          style={{
            display: 'block', width: '100%', height: 88, marginTop: 28, padding: '0 32px', border: `3px solid ${t.accent}`, borderRadius: t.radius,
            outline: 'none', background: t.panel2, color: t.ink, caretColor: t.accent, ...natural(t.body(34, 500)),
          }} />
        <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 28 }}>
          <div role="button" class="hov" {...onTouch(() => close(false))} style={{ ...pill, background: t.panel2, color: t.ink }}>Cancel</div>
          <div role="button" class="hov" {...onTouch(() => close(true))} style={{ ...pill, marginLeft: 16, background: t.accent, color: t.accentInk }}>OK</div>
        </div>
      </div>
    </div>
  )
}

function title(f: string): string {
  switch (f) {
    case 'addr': return 'Server address'
    case 'user': return 'Username'
    case 'pass': return 'Password'
    case 'profileName': return 'Profile name'
    case 'chName': return 'Channel name'
    case 'chNum': return 'Channel number'
    case 'search': return 'Search'
    default: return ''
  }
}

function initial(model: AppModel, f: string): string {
  const profiles = model.persisted.profiles
  switch (f) {
    case 'addr': return model.address
    case 'user': return model.username
    case 'pass': return model.password
    case 'profileName': return profiles[model.focusIndex]?.name ?? ''
    case 'chName': return model.selectedChannel?.name ?? ''
    case 'chNum': return model.numberDraft ?? ''
    case 'search': return model.searchQuery
    default: return ''
  }
}

/** Never reads `editingField`: the field is the one this panel was opened for. */
function commit(model: AppModel, f: string, draft: string) {
  switch (f) {
    case 'addr': model.address = AppModel.normalized(draft); break
    case 'user': model.username = draft; break
    case 'pass': model.password = draft; break
    case 'profileName': model.commitProfileName(model.focusIndex, draft); break
    case 'chName': { const id = model.channelSelection; if (id != null) model.updateChannel(id, d => d.copy({ name: draft })) } break
    case 'chNum': model.numberDraft = draft.replace(/[^0-9]/g, '').slice(0, 3); model.commitNumber(); break
    case 'search': model.searchQuery = draft; break
  }
  model.editingField = null
}

/** `original`: the value the panel opened with (Search's results moved with the typing). */
function cancel(model: AppModel, original: string) {
  if (model.editingField == null) return
  if (model.editingField === 'chNum') model.numberDraft = null
  if (model.editingField === 'search') model.searchQuery = original
  model.editingField = null
}
