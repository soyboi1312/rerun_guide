// Six first-launch screens, ported from android/app OnboardingScreens.kt (the prototype's "First launch" / "Sign in"
// (isForm) / "Sign in with a code" / "Building lineup" blocks; App/Onboarding/OnboardingScreens.swift). Absolute layout on
// the 1920×1080 stage; every color/font comes from the theme tokens. Text entry is always TextEntry's panel, so the fields
// only ever draw their resting look.
import type { ComponentChildren, JSX } from 'preact'
import { useEffect, useMemo } from 'preact/hooks'
import { replaceAll } from '../core/Lang'
import { Channel, LibrarySection, ServerKind } from '../core/Models'
import { Column, Lift, Row, natural } from './Anim'
import { AppModel, useModel } from './AppModel'
import { tapSelects } from './AppModelTouch'
import { Platform, useSticky } from './Platform'
import { Spinner } from './Spinner'
import { at } from './Stage'
import { Para, lineSpacing } from './Text'
import { TextStyle, hex, useTheme } from './Theme'

type Tap = { onClick: (e: MouseEvent) => void }

// MARK: - Welcome

export function WelcomeScreen() {
  const model = useModel()
  const t = useTheme()
  const cards = AppModel.welcomeCards
  return (
    <OnboardingStage>
      <Column gap={28} style={{ position: 'absolute', left: 140, top: 120, width: 1640 }}>
        <Brand size={34} dot={16} />
        <div style={{ ...natural(t.heading(128)), color: t.ink, letterSpacing: t.tracking(t.headingTracking), maxWidth: 1400 }}>{t.heading('Tonight’s lineup.')}</div>
        <Para text="The movies and shows on your server, running to a schedule the way TV used to be. Pick a channel, catch whatever’s playing, change your mind whenever. Nothing to set up on the server side."
          style={{ ...lineSpacing(t.body(34), 7), color: t.mute, maxWidth: 1180 }} />
      </Column>

      <div style={{ ...at(140, 651, 900, 31), display: 'flex', alignItems: 'center' }}>
        <Kicker text="Where do your shows live?" color={t.dim} />
      </div>
      <Row gap={32} style={{ ...at(140, 710, 1640, 240), alignItems: 'stretch' }}>
        {[0, 1, 2].map(i => (
          <ServiceCard key={i} name={cards[i][0]} desc={cards[i][1]} focused={model.focusIndex === i}
            style={{ flex: '1 1 0', minWidth: 0 }} attrs={tapSelects(model, () => { model.focusIndex = i })} />
        ))}
      </Row>
      {/* A 4th, smaller card so the app can be tried without a server; sits at the right edge above the row. Its 122 is
          too short for name + description: SwiftUI cuts the description to one line ("…") and the card grows down past 122
          to fit it (about 135), as the content refuses to be smaller than one line of each. */}
      <ServiceCard name={cards[3][0]} desc={cards[3][1]} focused={model.focusIndex === 3}
        style={{ position: 'absolute', left: 1360, top: 560, width: 420, minHeight: 122 }} attrs={tapSelects(model, () => { model.focusIndex = 3 })}
        nameSize={40} descSize={24} pad={28} oneLine />
    </OnboardingStage>
  )
}

function ServiceCard(props: {
  name: string; desc: string; focused: boolean; style: JSX.CSSProperties; attrs: Tap
  nameSize?: number; descSize?: number; pad?: number; oneLine?: boolean
}) {
  const t = useTheme()
  const { focused, nameSize = 68, descSize = 28, pad = 40 } = props
  const fade = 'color 180ms ease-in-out'
  const descStyle = { ...lineSpacing(t.body(descSize), descSize * 0.15), color: focused ? t.focusDim : t.dim, transition: fade }
  return (
    <Lift on={focused} radius={t.radius} bg={focused ? t.focusBg : t.panel} ms={180} {...props.attrs}
      style={{ display: 'flex', flexDirection: 'column', padding: `${pad}px ${pad + 4}px`, ...props.style }}>
      <div style={{ ...natural(t.heading(nameSize)), color: focused ? t.focusInk : t.ink, transition: fade }}>{t.heading(props.name)}</div>
      <div style={{ flex: 1 }} />
      {/* Unbounded: like the SwiftUI VStack, a description taller than what's left runs into the padding, never clipped. */}
      {props.oneLine
        ? <div class="ellipsis" style={descStyle}>{props.desc}</div>
        : <Para text={props.desc} style={{ ...descStyle, maxWidth: (1640 - 2 * 32) / 3 - 2 * (pad + 4) }} />}
    </Lift>
  )
}

// MARK: - Address

export function AddressScreen() {
  const model = useModel()
  const t = useTheme()
  const n = model.foundServers.length
  // At most three server rows fit above Continue; the window slides with focus so Continue never leaves the screen.
  const cap = 3
  const near = Math.min(Math.max(model.focusIndex - 1, 0), Math.max(n - 1, 0))
  const start = useSticky(Math.min(Math.max(near - 1, 0), Math.max(n - cap, 0)))
  const end = Math.min(start + cap, n)
  const name = ServerKind.displayName(model.serviceKind)
  const account = model.foundServers.some(s => s.accessToken !== '')   // rows from the account
  // A web app can't search the network (no UDP), so the only rows are the servers on the linked Plex or Emby account:
  // no "Spotted on your network" section and no "Looking around" line.
  const source = `On your ${name} account`
  const rows: JSX.Element[] = []
  for (let i = start; i < end; i++) {
    const s = model.foundServers[i]
    const f = model.focusIndex === i + 1
    rows.push(
      <FormRow key={s.id} label={s.name} focused={f} attrs={tapSelects(model, () => { model.focusIndex = i + 1 })}>
        <Detail text={replaceAll(s.baseURL, 'http://', '')} focused={f} />
        {s.baseURL === model.address && <Checkmark color={f ? t.focusInk : t.accent} />}{/* the pick: what the address field holds */}
      </FormRow>,
    )
  }
  return (
    <FormScreen kicker={name} title="Which server?"
      sub={account ? `Pick the server your lineup comes from. ${Platform.select} connects.` : 'Type the address you use on other devices.'}>
      <FormRow label="Server address" focused={model.focusIndex === 0} attrs={tapSelects(model, () => { model.focusIndex = 0 })}>
        <ValueText value={model.address} placeholder="http://" focused={model.focusIndex === 0} />
      </FormRow>
      {n > 0 && <SectionLabel text={n > cap ? `${source} · ${start + 1}–${end} of ${n}` : source} />}
      {rows}
      <PillButton label="Continue" focused={model.focusIndex === n + 1} style={{ marginTop: 24 }}
        attrs={tapSelects(model, () => { model.focusIndex = n + 1 })} busy={model.authBusy} />
      <ErrorLine />
    </FormScreen>
  )
}

// MARK: - Link (Plex PIN / Emby PIN / Jellyfin Quick Connect)

export function LinkScreen() {
  const model = useModel()
  const t = useTheme()
  const kind = model.serviceKind
  const name = ServerKind.displayName(kind)
  const plex = kind !== 'jellyfin'   // Plex and Emby link an account; Jellyfin's Quick Connect is per server
  const host = replaceAll(model.address, 'http://', '')
  const steps: [string, string][] = kind === 'plex'
    ? [['On your phone or computer, open', 'plex.tv/link'], ['Type in this code. The moment Plex says yes, we move on.', '']]
    : kind === 'emby'
      ? [['On your phone or computer, open', 'emby.media/pin'], ['Sign in with Emby Connect and type in this code. Once Emby says yes, we move on.', '']]
      : [['Open Jellyfin on your phone or computer and sign in.', ''], ['Head to', 'Settings › Quick Connect'], ['Type in this code. Once it’s approved, we move on.', '']]
  const status = model.authError ?? (model.linked
    ? (plex ? 'Linked. Looking for your server, which takes 10–15 seconds…' : 'Approved. Reading your libraries…')
    : 'Waiting for you to type the code…')
  const chars = model.linkCodeChars
  const stepW = 1640 - 160 - (chars.length * 134 - 14) - 56 - 26   // the steps column: what the code boxes leave
  const stepStyle = { ...lineSpacing(t.body(36), 5), color: t.ink, marginTop: `${4 - 5 / 2}px` }
  return (
    <OnboardingStage>
      <Header kicker={plex ? name : `Jellyfin · ${host}`} title={plex ? `Link your ${name} account` : 'Link with Quick Connect'} sub={null} width={1640} />
      <div style={{ position: 'absolute', left: 140, top: 430, width: 1640, display: 'flex', alignItems: 'flex-start' }}>
        <Column gap={30} style={{ flex: 1, minWidth: 0 }}>
          {steps.map(([text, em], i) => (
            <Row key={i} gap={26}>
              <div style={{ width: 56, height: 56, flex: 'none', borderRadius: '50%', background: t.panel2, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                <div style={{ ...natural(t.body(28, 700)), color: t.ink }}>{i + 1}</div>
              </div>
              {em === ''
                ? <Para text={text} style={{ ...stepStyle, maxWidth: stepW }} />
                : <div style={stepStyle}>{text + ' '}<span style={{ color: t.accent, fontWeight: 700 }}>{em}</span></div>}
            </Row>
          ))}
          <Row gap={16} style={{ alignItems: 'center', marginTop: 16 }}>
            <div style={{
              width: 14, height: 14, flex: 'none', borderRadius: '50%', background: model.linked ? hex('#7fd39a') : t.accent,
              animation: model.linked || model.authError != null ? 'none' : 'rgPulse 700ms ease-in-out infinite alternate',
            }} />
            <div style={{ ...natural(t.body(30)), color: model.authError == null ? t.mute : t.accent }}>{status}</div>
          </Row>
        </Column>
        <div style={{ width: 160, flex: 'none' }} />{/* the Swift HStack's spacing 80 on both sides of its Spacer(minLength: 0) */}
        <Row gap={14} style={{ flex: 'none' }}>
          {chars.map((ch, i) => (
            <div key={i} style={{
              width: 120, height: 170, background: t.panel, border: `2px solid ${t.line}`, borderRadius: t.radius,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}>
              <div style={{ ...natural(t.heading(96, 700)), color: t.ink, whiteSpace: 'pre' }}>{ch}</div>
            </div>
          ))}
        </Row>
      </div>
      <PillButton label={plex ? 'Type a server address instead' : 'Sign in with a password instead'} focused
        style={{ position: 'absolute', left: 140, top: 1080 - 120 - 84 }} attrs={tapSelects(model)} height={84} padX={44} size={30} />
    </OnboardingStage>
  )
}

// MARK: - Login

export function LoginScreen() {
  const model = useModel()
  const name = ServerKind.displayName(model.serviceKind)
  return (
    <FormScreen kicker={`${name} · ${replaceAll(model.address, 'http://', '')}`} title={`Sign in to ${name}`}
      sub={model.serviceKind === 'plex' ? 'Two-factor enabled? Enter your password followed by the 6-digit code.'
        : `Use the account you already use for ${name} on your other devices.`}>
      <FormRow label="Username" focused={model.focusIndex === 0} attrs={tapSelects(model, () => { model.focusIndex = 0 })}>
        <ValueText value={model.username} placeholder="dana" focused={model.focusIndex === 0} />
      </FormRow>
      <FormRow label="Password" focused={model.focusIndex === 1} attrs={tapSelects(model, () => { model.focusIndex = 1 })}>
        <ValueText value={'•'.repeat(model.password.length)} placeholder="••••••••" focused={model.focusIndex === 1} />
      </FormRow>
      <PillButton label="Sign in" focused={model.focusIndex === 2} style={{ marginTop: 24 }}
        attrs={tapSelects(model, () => { model.focusIndex = 2 })} busy={model.authBusy} />
      <ErrorLine />
    </FormScreen>
  )
}

// MARK: - Libraries

export function LibrariesScreen() {
  const model = useModel()
  const t = useTheme()
  const n = model.librarySections.length
  useEffect(() => {   // LaunchedEffect(Unit): leaving the screen cancels the fetch
    const job = new AbortController()
    void model.fetchLibraries(job.signal)
    return () => job.abort()
  }, [])
  return (
    <FormScreen kicker={`Connected to ${model.server?.serverName ?? ''}`} title="What goes in the lineup?"
      sub="Pick the libraries Rerun Guide may schedule. You can change this in the Settings any time.">
      {model.librarySections.map((s, i) => {
        const f = model.focusIndex === i
        const on = model.librarySelection.has(s.id)
        const ink = on ? (f ? t.focusBg : t.accentInk) : (f ? t.focusInk : t.ink)
        return (
          <FormRow key={s.id} label={s.title} focused={f} attrs={tapSelects(model, () => { model.focusIndex = i })}>
            <Detail text={detail(s)} focused={f} />
            {/* CSS content-box min-width 84 + 6/16 padding + 3 border (the border drawn inside, as Compose's is) */}
            <div style={{
              flex: 'none', minWidth: 84 + 2 * 19, padding: '9px 19px', borderRadius: t.pill, boxShadow: `inset 0 0 0 3px ${ink}`,
              background: on ? (f ? t.focusInk : t.accent) : 'transparent', textAlign: 'center', ...natural(t.body(24, 700)), color: ink,
            }}>{on ? 'On' : 'Off'}</div>
          </FormRow>
        )
      })}
      {n === 0 && !model.authBusy && model.authError == null && (
        <div style={{ ...natural(t.body(28)), color: t.dim }}>No libraries reported, so everything on the server will be scheduled.</div>
      )}
      {model.authBusy && <Spinner tint={t.dim} scale={0.7} style={{ width: 64, height: 88 }} />}{/* tvOS lays the spinner out 64 wide whatever its scale */}
      <PillButton label="Continue" focused={model.focusIndex === n} style={{ marginTop: 24 }} attrs={tapSelects(model, () => { model.focusIndex = n })} />
      <ErrorLine />
    </FormScreen>
  )
}

function detail(s: LibrarySection): string {
  const c = s.itemCount
  if (c == null) return ''
  const unit = s.kind === 'movies' ? 'film' : s.kind === 'shows' ? 'show' : 'title'
  return `${c.toLocaleString()} ${unit}${c === 1 ? '' : 's'}`   // "%,d": the device locale's grouping
}

// MARK: - Building

export function BuildingScreen() {
  const model = useModel()
  const t = useTheme()
  const done = model.buildDone
  const server = model.server?.serverName ?? ''
  const empty = done && model.channels.length === 0
  const failed = model.libraryError != null || empty
  const sub = model.libraryError ?? (empty
    ? `Nothing to schedule. Titles without a runtime are skipped. If ${server} is still scanning, try again in a few minutes; otherwise pick other libraries.`
    : done ? `${model.channels.length} channels, all from ${server}. Rename, renumber or retire any of them under Options › Channels.`
      : `Sorting ${server} by genre, decade, director and cast…`)
  const built = model.channels.slice(0, model.builtCount)
  const board: Channel[][] = []
  for (let i = 0; i < built.length; i += 4) board.push(built.slice(i, i + 4))
  // ponytail: more than 16 channels scrolls the board up; no manual scrolling. Eased like the Swift reveal's withAnimation.
  const scroll = -Math.max(0, board.length - 4) * 120
  return (
    <OnboardingStage>
      <Header kicker={null} title={done && !empty ? 'Sign-on complete' : 'Configuring channels'} sub={sub} width={1640} subColor={failed ? t.accent : t.mute} />
      <div style={{ position: 'absolute', left: 140, top: 380, width: 1640, height: 464, overflow: 'hidden' }}>
        <Column gap={16} style={{ alignItems: 'stretch', transform: `translateY(${scroll}px)`, transition: 'transform 300ms ease-out' }}>
          {board.map((row, r) => (
            <Row key={r} gap={16}>
              {row.map(ch => <ChannelTile key={ch.id} ch={ch} />)}
              {/* padded like a tile: flex shares out what padding leaves */}
              {[0, 1, 2].slice(0, 4 - row.length).map(k => <div key={`fill${k}`} style={{ flex: '1 1 0', padding: '0 24px' }} />)}
            </Row>
          ))}
        </Column>
      </div>
      {(done || model.libraryError != null) && (
        // Swift animates this in only when the last reveal step finishes the board; an error or an empty result just appears.
        <div style={{
          position: 'absolute', left: 140, top: 1080 - 96 - 88,
          animation: model.libraryError == null && model.channels.length > 0 ? 'rgUp 300ms ease-out backwards' : undefined,
        }}>
          <PillButton label={model.libraryError != null ? 'Retry' : empty ? 'Pick other libraries' : 'Start flipping'} focused attrs={tapSelects(model)} />
        </div>
      )}
    </OnboardingStage>
  )
}

/** Appears (fade up from 14 below, 300 ms ease-out) as the reveal adds it. */
function ChannelTile({ ch }: { ch: Channel }) {
  const t = useTheme()
  const one: JSX.CSSProperties = { whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: '100%' }
  return (
    <Row gap={18} style={{
      flex: '1 1 0', minWidth: 0, height: 104, alignItems: 'center', padding: '0 24px', background: t.panel, borderRadius: t.radius,
      animation: 'rgUp 300ms ease-out backwards',
    }}>
      {/* 3 digits past channel 99: never wraps */}
      <div style={{ minWidth: 52, flex: 'none', textAlign: 'right', whiteSpace: 'nowrap', ...natural(t.body(34, 700)), color: t.accent, fontFeatureSettings: '"tnum"' }}>{ch.number}</div>
      <Column gap={2} style={{ flex: '0 1 auto', minWidth: 0 }}>
        <div style={{ ...natural(t.body(30, 600)), color: t.ink, ...one }}>{t.heading(ch.name)}</div>
        <div style={{ ...natural(t.body(24)), color: t.dim, ...one }}>{ch.tag}</div>
      </Column>
    </Row>
  )
}

// MARK: - Shared pieces (the template's isForm / welcome / link / building CSS)

/** The 1920×1080 stage with the theme's background glow: radial-gradient(1100px 700px at 20% 10%, glow, transparent 70%),
 *  an ellipse of radii 770 × 490 at (384, 108). */
function OnboardingStage({ children }: { children: ComponentChildren }) {
  const t = useTheme()
  return <div style={{ ...at(0, 0, 1920, 1080), background: `radial-gradient(770px 490px at 384px 108px, ${t.glow}, transparent)` }}>{children}</div>
}

/** Header column at (140, 110): brand, kicker (margin-top 20), 88px title, sub. */
function Header({ kicker, title, sub, width = 1300, subColor }: { kicker: string | null; title: string; sub: string | null; width?: number; subColor?: string }) {
  const t = useTheme()
  return (
    <Column gap={18} style={{ position: 'absolute', left: 140, top: 110, width }}>
      <Brand />
      {kicker != null && <Kicker text={kicker} color={t.accent} style={{ marginTop: 20 }} />}
      <div style={{ ...natural(t.heading(88)), color: t.ink, letterSpacing: t.tracking(t.headingTracking), marginTop: kicker == null ? 20 : 0 }}>{t.heading(title)}</div>
      {sub != null && <Para text={sub} style={{ ...natural(t.body(32)), color: subColor ?? t.mute, maxWidth: width }} />}
    </Column>
  )
}

/** The "Sign in" template: header + a 1100-wide column of rows at (140, 450), gap 14. */
function FormScreen({ kicker, title, sub, children }: { kicker: string; title: string; sub: string; children: ComponentChildren }) {
  return (
    <OnboardingStage>
      <Header kicker={kicker} title={title} sub={sub} />
      <Column gap={14} style={{ position: 'absolute', left: 140, top: 450, width: 1100 }}>{children}</Column>
    </OnboardingStage>
  )
}

function Brand({ size = 30, dot = 14 }: { size?: number; dot?: number }) {
  const t = useTheme()
  return (
    <Row gap={14} style={{ alignItems: 'center' }}>
      <div style={{ width: dot, height: dot, borderRadius: '50%', background: t.accent }} />
      <div style={{ ...natural(t.heading(size, 600)), color: t.ink }}>{t.heading('Rerun Guide')}</div>
    </Row>
  )
}

/** 26px 700 .14em uppercase. */
function Kicker({ text, color, style }: { text: string; color: string; style?: JSX.CSSProperties }) {
  const t = useTheme()
  return <div style={{ ...natural(t.body(26, 700)), color, letterSpacing: t.tracking(0.14), ...style }}>{text.toUpperCase()}</div>
}

/** Form section label: 24px 700 .14em uppercase dim, margin-top 14. */
function SectionLabel({ text }: { text: string }) {
  const t = useTheme()
  return <div style={{ ...natural(t.body(24, 700)), color: t.dim, letterSpacing: t.tracking(0.14), marginTop: 14 }}>{text.toUpperCase()}</div>
}

/** 88-high row: 300-wide label + content, padding 0 36, gap 24. */
function FormRow({ label, focused, attrs, children }: { label: string; focused: boolean; attrs: Tap; children: ComponentChildren }) {
  const t = useTheme()
  return (
    <Row gap={24} {...attrs} style={{
      flex: 'none', width: 1100, height: 88, alignItems: 'center', padding: '0 36px', borderRadius: t.radius, background: focused ? t.focusBg : t.panel,
    }}>
      <div style={{ width: 300, flex: 'none', ...natural(t.body(32, 600)), color: focused ? t.focusInk : t.ink }}>{label}</div>
      {children}
    </Row>
  )
}

/** What a FormRow leaves its content: 1100 less the padding, the label and the gap. */
const FORM_VALUE_W = 1100 - 2 * 36 - 300 - 24

/** The input's resting look: its value, or the placeholder in the row's secondary color. */
function ValueText({ value, placeholder, focused }: { value: string; placeholder: string; focused: boolean }) {
  const t = useTheme()
  const color = value === '' ? (focused ? t.focusDim : t.dim) : (focused ? t.focusInk : t.ink)
  return <MiddleTruncatedText text={value || placeholder} style={{ ...t.body(32, 500), color }} />
}

/** Row detail: 30px in the row's secondary color, one line, taking the row's remaining width. */
function Detail({ text, focused }: { text: string; focused: boolean }) {
  const t = useTheme()
  return <div class="ellipsis" style={{ flex: 1, minWidth: 0, ...natural(t.body(30)), color: focused ? t.focusDim : t.dim }}>{text}</div>
}

/** Inline auth error under the rows, in the accent. */
function ErrorLine() {
  const model = useModel()
  const t = useTheme()
  return model.authError == null ? null : <Para text={model.authError} style={{ ...natural(t.body(28)), color: t.accent, marginTop: 10, maxWidth: 1100 }} />
}

/** The template's pill button (isButton / ln.alt / bd.onGo): height 88, padding 0 52, 32px 700, focus colors + lift. */
function PillButton(props: {
  label: string; focused: boolean; attrs: Tap; style?: JSX.CSSProperties; height?: number; padX?: number; size?: number; busy?: boolean
}) {
  const t = useTheme()
  const { focused, height = 88, padX = 52, size = 32 } = props
  return (
    <Lift on={focused} radius={t.pill} bg={focused ? t.focusBg : t.panel} ms={180} {...props.attrs}
      style={{ display: 'flex', alignItems: 'center', flex: 'none', height, padding: `0 ${padX}px`, ...props.style }}>
      <div style={{ ...natural(t.body(size, 700)), whiteSpace: 'nowrap', color: focused ? t.focusInk : t.ink, transition: 'color 180ms ease-in-out' }}>{t.heading(props.label)}</div>
      {props.busy && <Spinner tint={focused ? t.focusInk : t.ink} scale={0.6} style={{ width: 40, height: 40, marginLeft: 20 }} />}
    </Lift>
  )
}

/** SF Symbol "checkmark", 28 pt bold (or smaller, `w` × `h`), drawn. */
export function Checkmark({ color, w = 29, h = 27 }: { color: string; w?: number; h?: number }) {
  return (
    <svg width={w} height={h} viewBox="0 0 29 27" style={{ display: 'block', flex: 'none', overflow: 'visible' }}>
      <path d={`M${29 * 0.1} ${27 * 0.56}L${29 * 0.37} ${27 * 0.88}L${29 * 0.9} ${27 * 0.1}`} fill="none" stroke={color} stroke-width={3.6}
        stroke-linecap="round" stroke-linejoin="round" />
    </svg>
  )
}

let measureCtx: CanvasRenderingContext2D | null = null

/** One line, truncated in the middle (SwiftUI `.truncationMode(.middle)`; CSS has only end ellipses), in FormRow's
 *  value width: a binary search on the characters kept around the "…", measured with a canvas. */
function MiddleTruncatedText({ text, style }: { text: string; style: JSX.CSSProperties & TextStyle }) {
  const font = `${style.fontWeight} ${style.fontSize}px ${style.fontFamily}`
  const shown = useMemo(() => {
    measureCtx = measureCtx ?? document.createElement('canvas').getContext('2d')
    const ctx = measureCtx
    if (ctx == null) return text
    ctx.font = font
    const fits = (s: string) => ctx.measureText(s).width <= FORM_VALUE_W
    const cut = (keep: number) => text.slice(0, Math.trunc((keep + 1) / 2)) + '…' + text.slice(text.length - Math.trunc(keep / 2))
    if (fits(text)) return text
    let lo = 0
    let hi = text.length - 1   // characters kept around the ellipsis
    while (lo < hi) {
      const mid = Math.trunc((lo + hi + 1) / 2)
      if (fits(cut(mid))) lo = mid
      else hi = mid - 1
    }
    return cut(lo)
  }, [text, font, document.fonts.status])   // measured again once the bundled fonts have loaded
  return <div style={{ flex: 1, minWidth: 0, whiteSpace: 'pre', overflow: 'hidden', ...natural(style) }}>{shown}</div>
}
