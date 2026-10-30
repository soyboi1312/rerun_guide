// Port of android/app Overlays.kt: Options, Program info, New channel, Make a channel, the number box and the toast. Each
// scrim and its sheet are DOM siblings, so a click on the sheet never reaches the scrim's backgroundTap (Kotlin's `blocksTaps`).
import type { ComponentChildren, JSX } from 'preact'
import { useEffect, useLayoutEffect, useRef } from 'preact/hooks'
import { ChannelBuilder } from '../core/GuideGenerator'
import { stableSort, toIntOrNull } from '../core/Lang'
import { Channel, ProgramStatus, Slot } from '../core/Models'
import { Instant } from '../core/Time'
import { Column, Lift, Row, natural, useExit, useNow } from './Anim'
import { AppModel, ProgramKey, useModel } from './AppModel'
import { tapSelects } from './AppModelTouch'
import { backgroundTap, onPoint, onTouch, useSticky } from './Platform'
import { RemoteImage } from './RemoteImage'
import { STAGE_H, STAGE_W, at } from './Stage'
import { Clock, Para, lineSpacing } from './Text'
import { Theme, ThemeID, channelColor, colorHex, useTheme } from './Theme'

const stage = at(0, 0, STAGE_W, STAGE_H)
const centered: JSX.CSSProperties = { ...stage, display: 'flex', alignItems: 'center', justifyContent: 'center' }

// MARK: - Options menu (template "Options menu")

export function OptionsOverlay() {
  const model = useModel()
  const t = useTheme()
  return (
    <div style={stage}>
      <div style={{ ...stage, background: t.scrim }} {...backgroundTap(() => model.press('back'))} />
      <div style={{ ...at(0, 0, 620, 1080), animation: 'rgEnterX 220ms ease-out backwards' }}>
        <SheetShadow radius={0} shadow="30px 0 80px rgba(0,0,0,0.5)" />
        <Column gap={10} style={{ ...at(0, 0, 620, 1080), background: t.panel, padding: '72px 48px' }}>
          <Row gap={14} style={{ alignItems: 'center', padding: '0 24px 28px' }}>
            <div style={{ width: 14, height: 14, borderRadius: '50%', background: t.accent }} />
            <div style={{ ...natural(t.heading(32, 600)), color: t.ink }}>{t.heading('Rerun Guide')}</div>
          </Row>
          {AppModel.OptionRow.entries.map((row, i) => {
            const top: JSX.CSSProperties = { marginTop: row === 'guide' || row === 'theme' ? 24 : 0 }
            // The Theme row's chips are its buttons; one around the row would swallow their clicks.
            return <OptionRowView key={row} row={row} focused={i === model.focusIndex}
              attrs={row === 'theme' ? { style: top } : { style: top, ...tapSelects(model, () => { model.focusIndex = i }) }} />
          })}
          <div style={{ flex: 1 }} />
          <Para text={`${model.serverLabel} · connected`} style={{ ...lineSpacing(t.body(24), 5), color: t.dim, padding: '0 24px', width: 524 }} />
        </Column>
      </div>
    </div>
  )
}

function OptionRowView({ row, focused, attrs }: { row: AppModel.OptionRow; focused: boolean; attrs: { style: JSX.CSSProperties; onClick?: (e: MouseEvent) => void; onMouseMove?: (e: MouseEvent) => void } }) {
  const model = useModel()
  const t = useTheme()
  const f = fv(t, focused)
  // The guide row that matches the current filter is tinted so you can see which guide you're on.
  const isActiveGuide = row === 'guide' ? model.guideFilter === 'all'
    : row === 'movies' ? model.guideFilter === 'movies'
    : row === 'tv' ? model.guideFilter === 'tv' : false
  const label = row === 'profile' ? model.profile.name : row === 'guide' ? 'Guide' : row === 'movies' ? 'Movies' : row === 'tv' ? 'TV Shows'
    : row === 'search' ? 'Search' : row === 'channels' ? 'Channels' : row === 'settings' ? 'Settings' : 'Theme'
  return (
    // 84 high, not 88: eight rows fit above the footer
    <Row gap={20} onClick={attrs.onClick} onMouseMove={attrs.onMouseMove}
      style={{ ...attrs.style, alignSelf: 'stretch', alignItems: 'center', minHeight: 84, background: f.bg, borderRadius: t.radius, padding: '0 24px' }}>
      {row === 'profile' && (
        <div style={{ width: 56, height: 56, flex: 'none', borderRadius: '50%', background: colorHex(model.profile.colorHex), display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <div style={{ ...natural(t.body(28, 700)), color: t.accentInk, whiteSpace: 'nowrap' }}>{model.profile.initial}</div>
        </div>
      )}
      <Column gap={2} style={{ flex: 1, minWidth: 0 }}>
        <div class={row === 'theme' ? 'ellipsis' : undefined}
          style={{ ...natural(t.body(34, 600)), maxWidth: '100%', color: isActiveGuide && !focused ? t.accent : f.fg }}>{t.heading(label)}</div>
        {row === 'profile' && <div style={{ ...natural(t.body(24)), color: f.sc }}>Switch profile</div>}
      </Column>
      {row === 'theme' && (
        <Row gap={6}>
          {ThemeID.entries.map(id => {
            const on = id === t.id
            return (
              // the remote's Left / Right on this row
              <div key={id} class="hov" {...onTouch(() => model.setTheme(id))} {...onPoint(() => { model.focusIndex = AppModel.OptionRow.entries.indexOf('theme') })} style={{
                ...natural(t.body(24, 700)), whiteSpace: 'nowrap', padding: '8px 16px', borderRadius: t.pill,
                background: on ? (focused ? t.focusInk : t.accent) : 'transparent',
                color: on ? (focused ? t.focusBg : t.accentInk) : (focused ? t.focusInk : t.dim),
              }}>{ThemeID.displayName(id)}</div>
            )
          })}
        </Row>
      )}
    </Row>
  )
}

// MARK: - Program info (template "Program info")

export function InfoOverlay({ k }: { k: ProgramKey }) {
  const model = useModel()
  const t = useTheme()
  const p = model.program(k)
  const now = useNow(10_000)   // TimelineView(.periodic(by: 10))
  const missing = p == null
  useEffect(() => { if (missing) model.closeOverlay() }, [missing ? 1 : 0])
  return (
    <div style={centered}>
      <div style={{ ...stage, background: t.scrim }} {...backgroundTap(() => model.press('back'))} />
      {p != null && <InfoSheet ch={p.channel} slot={p.slot} now={now} k={k} />}
    </div>
  )
}

/** The text column's width: the sheet's 1480 less its padding, the picture and the gap. */
const INFO_TEXT_W = 1480 - 2 * 56 - 520 - 56

function InfoSheet({ ch, slot, now, k }: { ch: Channel; slot: Slot; now: Instant; k: ProgramKey }) {
  const model = useModel()
  const t = useTheme()
  const st = model.status(slot, now)
  const kind = st === 'live' || st === 'break' ? 'On now' : st === 'later' ? 'Later' : 'Earlier'
  const acts = model.infoActions(k)
  return (
    <div style={{ position: 'relative', width: 1480, animation: 'rgUp 220ms ease-out backwards' }}>
      <SheetShadow radius={t.radius} shadow="0 40px 120px rgba(0,0,0,0.6)" />
      <Row gap={56} style={{ position: 'relative', background: t.panel, borderRadius: t.radius, padding: 56 }}>
        <div style={{ position: 'relative', width: 520, height: 390, flex: 'none', borderRadius: t.radius, overflow: 'hidden' }}>
          <HueGradient hue={ch.hue} />
          <RemoteImage url={model.server?.backdropURL(slot.item) ?? null} style={at(0, 0, 520, 390)} />
          <div style={{
            position: 'absolute', left: 24, top: 22, padding: '6px 14px', borderRadius: t.pill, background: t.accent,
            ...natural(t.body(24, 700)), color: t.accentInk, letterSpacing: t.tracking(0.1), whiteSpace: 'nowrap',
          }}>{kind.toUpperCase()}</div>
          <Para text={t.heading(slot.item.displayTitle)}
            style={{ position: 'absolute', left: 28, bottom: 26, width: 520 - 56, ...natural(t.heading(46)), color: '#ffffff' }} />
        </div>
        {/* VStack(spacing: 14) { texts; Spacer(minLength: 0); Flow.padding(.top, 20) }.frame(minHeight: 390): the buttons sit
            at the bottom of 390 points, or 14 + 14 + 20 below the texts when they run longer. */}
        <div style={{ flex: 1, minWidth: 0, minHeight: 390, display: 'flex', flexDirection: 'column' }}>
          <Column gap={14} style={{ marginBottom: 48 }}>
            <div style={{ ...natural(t.body(26, 600)), color: t.accent, letterSpacing: t.tracking(0.1) }}>
              {`Channel ${ch.number} · ${ch.name}`.toUpperCase()}
            </div>
            <Para text={t.heading(slot.item.displayTitle)}
              style={{ ...natural(t.heading(76)), color: t.ink, letterSpacing: t.tracking(t.headingTracking), maxWidth: INFO_TEXT_W }} />
            <Para text={slot.item.infoLine} style={{ ...natural(t.body(32)), color: t.mute, maxWidth: INFO_TEXT_W }} />
            <div style={{ ...natural(t.body(28)), color: t.dim }}>{`${Clock.range(slot.start, slot.end)} · ${statusText(slot, st, now)}`}</div>
            {slot.item.overview != null && <Para text={slot.item.overview} style={{ ...lineSpacing(t.body(30), 8), color: t.mute, maxWidth: INFO_TEXT_W }} />}
          </Column>
          <ActionPills labels={acts.map(a => a.label)} style={{ marginTop: 'auto' }} />
        </div>
      </Row>
    </div>
  )
}

// MARK: - New channel (template "New channel")

export function NewChannelOverlay() {
  const model = useModel()
  const t = useTheme()
  const cards = newChannelCards(model)
  const rows = Math.trunc((cards.length + 2) / 3)
  // ponytail: 2-row window around the focus (4 rows of 240 don't fit the stage); upgrade: animated row scroll.
  const first = useSticky(Math.max(0, Math.min(Math.trunc(model.focusIndex / 3) - 1, rows - 2)) * 3)
  const kind = model.newChannelType
  const title = model.newChannelStep !== 1 || kind == null ? 'Build it from…'
    : kind === 'director' ? 'Pick a director' : kind === 'actor' ? 'Pick an actor' : 'Pick a show'
  const textW = 1440 - 2 * 64
  return (
    <div style={centered}>
      <div style={{ ...stage, background: t.scrim }} {...backgroundTap(() => model.press('back'))} />
      <div style={{ position: 'relative', width: 1440, animation: 'rgUp 220ms ease-out backwards' }}>
        <SheetShadow radius={t.radius} shadow="0 40px 120px rgba(0,0,0,0.6)" />
        <Column gap={18} style={{ position: 'relative', background: t.panel, borderRadius: t.radius, padding: 64 }}>
          <div style={{ ...natural(t.body(26, 700)), color: t.accent, letterSpacing: t.tracking(0.14) }}>NEW CHANNEL</div>
          <Para text={t.heading(title)} style={{ ...natural(t.heading(76)), color: t.ink, letterSpacing: t.tracking(t.headingTracking), maxWidth: textW }} />
          <Para text={model.newChannelStep === 1 ? 'Only names with enough in your library to fill an evening are listed.'
            : 'Rerun Guide finds everything that matches and puts it on a schedule.'}
            style={{ ...natural(t.body(30)), color: t.mute, maxWidth: textW }} />
          <NewChannelRows first={first} count={cards.length} style={{ marginTop: 28, alignSelf: 'stretch' }} row={r => (
            <Row gap={24} style={{ alignItems: 'stretch' }}>
              {/* a missing card's space is padded like a card: flex shares out what padding leaves */}
              {[r, r + 1, r + 2].map(i => i < cards.length
                ? <NewChannelCard key={i} c={cards[i]} focused={i === model.focusIndex} attrs={tapSelects(model, () => { model.focusIndex = i })} />
                : <div key={i} style={{ flex: '1 1 0', padding: '0 40px' }} />)}
            </Row>
          )} />
        </Column>
      </div>
    </div>
  )
}

/**
 * `VStack(spacing: 24) { ForEach(rows from first, id: \.self) }.animation(.easeInOut(duration: 0.15), value: focusIndex)`:
 * when the 2-row window moves, the row that stays slides to its new place while the leaving row fades out where it was
 * and the entering one fades in where it lands (SwiftUI's default insert/remove transition). The rows' heights are
 * measured after each render and placed by direct style writes (transitions do the easing), as Kotlin's custom Layout does.
 */
function NewChannelRows({ first, count, style, row }: { first: number; count: number; style: JSX.CSSProperties; row: (r: number) => ComponentChildren }) {
  const rows = (f: number) => { const rs: number[] = []; for (let r = f; r < Math.min(f + 6, count); r += 3) rs.push(r); return rs }
  const win = useRef([first, first])   // the window moved from, the window now
  const moved = useRef(false)
  if (win.current[1] !== first) { win.current = [win.current[1], first]; moved.current = true }
  const from = rows(win.current[0])
  const to = rows(win.current[1])
  const all = stableSort(from.concat(to.filter(r => from.indexOf(r) < 0)), (a, b) => a - b)
  const box = useRef<HTMLDivElement>(null)
  useLayoutEffect(() => {
    const el = box.current
    if (!el) return
    const kids = el.children as HTMLCollectionOf<HTMLElement>
    const gap = 24
    const hs = all.map((_, i) => kids[i].offsetHeight)
    const tops = (rs: number[]) => { const m = new Map<number, number>(); let y = 0; for (const r of rs) { m.set(r, y); y += hs[all.indexOf(r)] + gap } return m }
    const height = (rs: number[]) => rs.reduce((s, r) => s + hs[all.indexOf(r)], 0) + gap * Math.max(0, rs.length - 1)
    const a = tops(from)
    const b = tops(to)
    const place = (p: number) => {
      el.style.height = `${height(from) + (height(to) - height(from)) * p}px`
      all.forEach((r, i) => {
        const ar = a.get(r)
        const br = b.get(r)
        const y = ar != null && br != null ? ar + (br - ar) * p : br ?? ar ?? 0
        kids[i].style.transform = `translateY(${y}px)`
        kids[i].style.opacity = String(ar != null && br != null ? 1 : br != null ? p : 1 - p)
        kids[i].style.pointerEvents = br != null ? '' : 'none'   // a faded-out row takes no clicks
      })
    }
    const ease = (on: boolean) => {
      el.style.transition = on ? 'height 150ms ease-in-out' : 'none'
      for (let i = 0; i < kids.length; i++) kids[i].style.transition = on ? 'transform 150ms ease-in-out, opacity 150ms ease-in-out' : 'none'
    }
    if (moved.current) {
      moved.current = false
      ease(false)
      place(0)
      void el.offsetHeight   // commit the starting place before easing to the new one
      ease(true)
    }
    place(1)   // re-measured every render: a card that grows (candidates arriving) moves the rows below it
  })
  return (
    <div ref={box} style={{ position: 'relative', ...style }}>
      {all.map(r => <div key={r} style={{ position: 'absolute', left: 0, top: 0, width: '100%' }}>{row(r)}</div>)}
    </div>
  )
}

function newChannelCards(model: AppModel): [string, string][] {
  if (model.newChannelStep === 0) {
    const desc = ['One filmmaker, back to back.', 'Follow one face through your library.', 'One series, every episode in order.']
    return ChannelBuilder.CandidateKind.entries.map((k, i): [string, string] => [ChannelBuilder.CandidateKind.rawValue(k), desc[i]])
  }
  const unit = model.newChannelType === 'series' ? 'episodes' : 'films'
  return model.candidateCache.map((c): [string, string] => [c.name, `${c.count} ${unit} in your library`])
}

function NewChannelCard({ c, focused, attrs }: { c: [string, string]; focused: boolean; attrs: { onClick: (e: MouseEvent) => void } }) {
  const t = useTheme()
  const f = fv(t, focused)
  const textW = (1440 - 2 * 64 - 2 * 24) / 3 - 2 * 40
  const color = 'color 150ms ease-in-out'
  return (
    <Lift on={focused} radius={t.radius} bg={f.bg} ms={150} {...attrs}
      style={{ flex: '1 1 0', minWidth: 0, minHeight: 240, padding: '36px 40px', display: 'flex', flexDirection: 'column' }}>
      <Column gap={16} style={{ flex: 1 }}>
        <Para text={t.heading(c[0])} style={{ ...natural(t.heading(50)), color: f.fg, transition: color, maxWidth: textW }} />
        <div style={{ flex: 1 }} />
        <Para text={c[1]} style={{ ...lineSpacing(t.body(28), 4), color: f.sc, transition: color, maxWidth: textW }} />
      </Column>
    </Lift>
  )
}

// MARK: - Make a channel (Search)

/** Search's page for a result that isn't on in the next 24 hours (just Make a channel), and a film's people to make a
 *  channel of. Back returns to the Search page. */
export function MakeChannelOverlay() {
  const model = useModel()
  const t = useTheme()
  const e = model.searchSelection
  useEffect(() => { if (e == null) model.closeOverlay() }, [e == null ? 1 : 0])
  const textW = 1100 - 2 * 56
  return (
    <div style={centered}>
      <div style={{ ...stage, background: t.scrim }} {...backgroundTap(() => model.press('back'))} />
      {e != null && (
        <div style={{ position: 'relative', width: 1100, animation: 'rgUp 220ms ease-out backwards' }}>
          <SheetShadow radius={t.radius} shadow="0 40px 120px rgba(0,0,0,0.6)" />
          <Column gap={14} style={{ position: 'relative', background: t.panel, borderRadius: t.radius, padding: 56 }}>
            <div style={{ ...natural(t.body(26, 600)), color: t.accent, letterSpacing: t.tracking(0.1) }}>
              {((e.isShow ? 'Show' : 'Movie') + (e.year == null ? '' : ` · ${e.year}`)).toUpperCase()}
            </div>
            <Para text={t.heading(e.title)} style={{ ...natural(t.heading(76)), color: t.ink, letterSpacing: t.tracking(t.headingTracking), maxWidth: textW }} />
            <Para text={model.makePeople == null ? model.airsText(e) : 'Pick who the channel follows. Only names with enough in your library to fill an evening are listed.'}
              style={{ ...lineSpacing(t.body(30), 6), color: t.mute, maxWidth: textW }} />
            <ActionPills labels={model.makeActions.map(a => a.label)} style={{ marginTop: 20, alignSelf: 'stretch' }} />
          </Column>
        </div>
      )}
    </div>
  )
}

// MARK: - Number keys (top-right box)

/** The channel number being typed on the guide or the player, "2 2 _", with the channel once the digits are one; or that
 *  there's no such channel. Fades in and out over 200 ms, its lines too as they come, and takes no clicks. */
export function ChannelEntryView() {
  const model = useModel()
  const t = useTheme()
  const { value, leaving } = useExit(model.channelEntry, 200)
  // Leaving, the box fades out showing what it last said: useExit keeps what it last rendered, never a third digit's
  // entry (that tunes in the same press).
  const e = leaving ? model.lastEntry : value
  if (e == null) return null
  const n = toIntOrNull(e.digits)
  const ch = e.missing || n == null ? null : model.channelNumbered(n)   // from the shown digits: still there while it fades
  const line: JSX.CSSProperties = { whiteSpace: 'nowrap', color: t.ink, animation: 'fadeIn 200ms ease-out backwards' }
  return (
    <Column gap={6} style={{
      position: 'absolute', top: 56, right: 72, minWidth: 260, alignItems: 'flex-end', padding: '20px 34px 22px', borderRadius: t.radius,
      background: t.panel2, boxShadow: '0 20px 60px rgba(0,0,0,0.5)', pointerEvents: 'none',
      animation: leaving ? 'fadeOut 200ms ease-out forwards' : 'fadeIn 200ms ease-out backwards',
    }}>
      {e.missing && <div key="missing" style={{ ...natural(t.body(32, 600)), ...line }}>{`No channel ${n ?? 0}`}</div>}
      {!e.missing && (
        <div key="digits" style={{ ...natural(t.heading(72)), ...line, color: t.accent, fontFeatureSettings: '"tnum"' }}>
          {(e.digits + '_'.repeat(Math.max(0, 3 - e.digits.length))).split('').join(' ')}
        </div>
      )}
      {ch != null && <div key="name" style={{ ...natural(t.body(30, 600)), ...line }}>{t.heading(`${ch.number} · ${ch.name}`)}</div>}
    </Column>
  )
}

// MARK: - Toast (template toastOn/toastMsg: top-right card)

/**
 * `.transition(.opacity.combined(with: .offset(y: 14)))`, `.animation(.easeOut(duration: 0.25))`: in from 14 below, out
 * the same way. ponytail: a toast replacing a showing one swaps in place (the new one fades in, the old one doesn't fade
 * out under it as AnimatedContent's crossfade does); keep both mounted if that ever reads as a jump.
 */
export function ToastView() {
  const model = useModel()
  const t = useTheme()
  const { value: toast, leaving } = useExit(model.currentToast, 250)
  if (toast == null) return null
  return (
    <Para key={toast.n} text={toast.message} style={{
      position: 'absolute', top: 56, right: 72, maxWidth: 820, padding: '20px 30px', borderRadius: t.radius, background: t.panel2,
      boxShadow: '0 20px 60px rgba(0,0,0,0.5)', ...natural(t.body(28, 600)), color: t.ink,
      pointerEvents: 'none',   // as in Compose, the card takes no clicks: they reach the screen under it
      animation: leaving ? 'rgDown 250ms ease-out forwards' : 'rgUp 250ms ease-out backwards',
    }} />
  )
}

// MARK: - Shared bits

/** Program info's buttons (the Make a channel page's too): pills that wrap, the focused one lifted. */
function ActionPills({ labels, style }: { labels: readonly string[]; style: JSX.CSSProperties }) {
  const model = useModel()
  const t = useTheme()
  return (
    <Flow spacing={18} style={style}>
      {labels.map((label, i) => {
        const on = i === model.focusIndex
        const f = fv(t, on)
        return (
          <Lift key={label} on={on} radius={t.pill} bg={f.bg} ms={150} {...tapSelects(model, () => { model.focusIndex = i })}
            style={{ margin: 9, padding: '18px 34px', ...natural(t.body(30, 600)), color: f.fg, whiteSpace: 'nowrap' }}>{label}</Lift>
        )
      })}
    </Flow>
  )
}

interface FocusColors { bg: string; fg: string; sc: string }

/** The prototype's fv(): focused → focus bg/ink/sub, else panel/ink/dim. */
function fv(t: Theme, focused: boolean): FocusColors {
  return focused ? { bg: t.focusBg, fg: t.focusInk, sc: t.focusDim } : { bg: t.panel, fg: t.ink, sc: t.dim }
}

/**
 * `fillWithShadow`'s shadow (SwiftUI radius = sigma, so CSS blur = 2 × radius), drawn by a static sibling under the sheet
 * so the sheet's content changes never re-raster the big blur.
 */
function SheetShadow({ radius, shadow }: { radius: number; shadow: string }) {
  return <div style={{ position: 'absolute', left: 0, top: 0, width: '100%', height: '100%', borderRadius: radius, boxShadow: shadow }} />
}

/**
 * Channel-hue preview background (the prototype's pvBg), filling the 520×390 picture: Midnight Movie a linear ramp from
 * (0.33w, 0.03h) to (0.67w, 0.97h), which on this box is 154.3° with its stops at 14.7% and 85.3% of the CSS gradient
 * line; Late Show a radial one. ponytail: the circle stands in for the CSS 130%×100% ellipse at 25% 15%.
 */
function HueGradient({ hue }: { hue: number }) {
  const t = useTheme()
  const background = t.id === 'MIDNIGHT'
    ? `linear-gradient(154.3deg, ${channelColor(hue, 0.46, 0.14)} 14.7%, ${channelColor(hue, 0.2, 0.08)} 85.3%)`
    : `radial-gradient(circle 600px at 25% 15%, ${channelColor(hue, 0.5, 0.1)} 0%, ${channelColor(hue, 0.24, 0.05)} 55%, ${channelColor(hue, 0.13, 0.02)} 100%)`
  return <div style={{ ...at(0, 0, 520, 390), background }} />
}

/** CSS `display:flex; flex-wrap:wrap; gap:` for the info sheet's buttons. Flex gap is Chromium 84+: each child carries
 *  half the spacing as its margin, which the inner box's negative margin takes back at the edges. */
function Flow({ spacing, style, children }: { spacing: number; style: JSX.CSSProperties; children: ComponentChildren }) {
  return (
    <div style={style}>
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'flex-start', margin: -spacing / 2 }}>{children}</div>
    </div>
  )
}

function statusText(slot: Slot, st: ProgramStatus, now: Instant): string {
  switch (st) {
    case 'live': return `On now, ${Math.trunc((now - slot.start) / 60)} min in`
    case 'break': return 'On now, between programs'
    case 'later': return `Starts in ${Math.ceil((slot.start - now) / 60)} min`
    case 'past': return 'Aired earlier tonight'
  }
}
