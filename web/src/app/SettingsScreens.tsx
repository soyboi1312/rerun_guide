// Port of android/app SettingsScreens.kt (App/Settings/SettingsScreens.swift). The tvOS inline text fields are not ported:
// text entry is always TextEntry's panel.
import type { ComponentChildren, JSX } from 'preact'
import { useRef } from 'preact/hooks'
import { Channel, PlayOrder, RuleChip } from '../core/Models'
import { Instant } from '../core/Time'
import { Column, Row, natural, useNow } from './Anim'
import { AppModel, Profile, useModel } from './AppModel'
import { ChannelField, SettingsRow } from './AppModelSettings'
import { tapSelects } from './AppModelTouch'
import { onTouch, useSticky } from './Platform'
import { at } from './Stage'
import { Clock, Para, lineEm, lineSpacing } from './Text'
import { Fonts, TextStyle, ThemeID, colorHex, useTheme } from './Theme'

// MARK: - Profiles ("Who’s watching?")

export function ProfilesScreen() {
  const model = useModel()
  const t = useTheme()
  const profiles = model.persisted.profiles
  const tiles = profiles.length + (profiles.length < 5 ? 1 : 0)
  const left = (1920 - (tiles * 250 + (tiles - 1) * 64)) / 2
  const hint = natural(t.body(28))
  const tileViews: JSX.Element[] = []
  for (let i = 0; i < tiles; i++) {
    const p: Profile | undefined = profiles[i]
    const x = left + i * 314
    tileViews.push(
      <ProfileTile key={p?.id ?? '+'} initial={p?.initial ?? '+'} name={p?.name ?? 'Add profile'} sub={p ? sub(model, p) : ''}
        color={p ? colorHex(p.colorHex) : t.panel2} ink={p == null ? t.ink : t.accentInk} focused={i === model.focusIndex}
        style={at(x, 382, 250, 378)} attrs={tapSelects(model, () => { model.profileMenu = null; model.focusIndex = i }, () => { if (model.profileMenu == null) model.focusIndex = i })} />,
    )
    if (model.profileMenu === i) {
      // Rename / Delete row, segmented-pill style.
      tileViews.push(
        <div key="menu" style={{ ...at(x, 776, 250, 56), display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Row gap={6} style={{ flex: 'none', background: t.panel, borderRadius: t.pill, padding: 6 }}>
            {model.profileActions.map((label, j) => {
              const on = j === model.profileMenuIndex
              return (
                <div key={label} {...tapSelects(model, () => { model.profileMenuIndex = j })} style={{
                  ...natural(t.body(24, 700)), minHeight: swiftHeight(t.body(24)), whiteSpace: 'nowrap', padding: '8px 16px', borderRadius: t.pill,
                  background: on ? t.accent : 'transparent', color: on ? t.accentInk : t.dim,
                }}>{label}</div>
              )
            })}
          </Row>
        </div>,
      )
    }
  }
  return (
    // radial-gradient(1100px 700px at 50% 20%, glow, transparent 70%): an ellipse of radii 770 × 490 at (960, 216)
    <div style={{ ...at(0, 0, 1920, 1080), background: `radial-gradient(770px 490px at 960px 216px, ${t.glow}, transparent)` }}>
      <TextAt text={t.heading('Who’s watching?')} style={{ ...t.heading(96), color: t.ink, letterSpacing: t.tracking(t.headingTracking) }}
        x={0} y={206} w={1920} h={96} align="center" />
      {tileViews}
      <div style={{ ...at(0, 840, 1920, 34), display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <GlyphText before="Each profile keeps its own theme and lineup. Select yours, or press " glyph="playPause"
          after=" on any, to rename or delete it." style={{ ...hint, color: t.mute }} onGlyph={() => model.press('play')} />
      </div>
    </div>
  )
}

function sub(model: AppModel, p: Profile): string {
  const hidden = model.channels.filter(c => p.hiddenChannelIDs.has(c.id)).length
  return `${ThemeID.displayName(p.theme)}\n${model.channels.length - hidden} channels`   // two lines: one ran past the 250-wide tile
}

function ProfileTile(props: {
  initial: string; name: string; sub: string; color: string; ink: string; focused: boolean; style: JSX.CSSProperties
  attrs: { onClick: (e: MouseEvent) => void }
}) {
  const t = useTheme()
  const { focused } = props
  // .animation(.easeInOut(duration: 0.18), value: focused)
  const ease = '180ms ease-in-out'
  return (
    <div {...props.attrs} style={props.style}>
      <div style={{
        ...at(15, 0, 220, 220), borderRadius: '50%', background: props.color, display: 'flex', alignItems: 'center', justifyContent: 'center',
        transform: `scale(${focused ? 1.06 : 1})`, transition: `transform ${ease}`,
      }}>
        {/* box-shadow 0 0 0 8px ink: an 8-wide ring just outside the circle, faded in */}
        <div style={{ ...at(-8, -8, 236, 236), borderRadius: '50%', border: `8px solid ${t.ink}`, opacity: focused ? 1 : 0, transition: `opacity ${ease}` }} />
        <div style={{ ...natural(t.heading(100, 700)), color: props.ink, whiteSpace: 'nowrap', position: 'relative' }}>{props.initial}</div>
      </div>
      <TextAt text={t.heading(props.name)} style={{ ...t.body(38, 600), color: focused ? t.ink : t.dim, transition: `color ${ease}` }}
        x={0} y={254} w={250} h={46} align="center" maxLines={1} />
      <TextAt text={props.sub} style={{ ...t.body(24), color: t.dim, textAlign: 'center' }} x={0} y={320} w={250} h={64} align="topCenter" maxLines={2} />
    </div>
  )
}

// MARK: - Settings

/** `index`: the row's focus index (keyed rows only; headers have none). */
interface SettingsPlaced { readonly row: SettingsRow; readonly y: number; readonly index: number | null }

export function SettingsScreen() {
  const model = useModel()
  const t = useTheme()
  // Row positions and the translate that keeps the focused row centered (the prototype's `ty` math).
  const rows: SettingsPlaced[] = []
  let y = 0
  let fy = 0
  let k = 0
  let focused: SettingsRow | null = null
  for (const r of model.settingsRows) {
    rows.push({ row: r, y, index: r.key == null ? null : k })
    if (r.key == null) y += 72
    else { if (k === model.focusIndex) { fy = y; focused = r } y += 92; k += 1 }
  }
  const ty = useSticky(Math.max(0, Math.min(y - 904, fy - 904 / 2 + 46)))
  const p = model.profile
  const colors = useScrollSpec(ty)
  const label = t.heading(46, 600)
  const descStyle = t.body(30)
  // SwiftUI squeezes the label and description into the 600-high frame, ellipsizing the last line that fits.
  // ponytail: assumes a one-line label (every Settings label is); measure it if a two-line label ever appears.
  const descLines = Math.max(1, Math.floor((600 - swiftHeight(label) - 24 + 7.5) / (descStyle.fontSize * lineEm(descStyle) + 7.5)))
  return (
    <div style={at(0, 0, 1920, 1080)}>
      <TextAt text={t.heading('Settings')} style={{ ...t.heading(84), color: t.ink, letterSpacing: t.tracking(t.headingTracking) }}
        x={96} y={96} w={620} h={84} align="centerStart" />
      <div style={{ ...at(96, 204, 40, 40), borderRadius: '50%', background: colorHex(p.colorHex), display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <div style={{ ...natural(t.body(22, 700)), color: t.accentInk, whiteSpace: 'nowrap' }}>{p.initial}</div>
      </div>
      <TextAt text={`Watching as ${p.name}`} style={{ ...t.body(28), color: t.mute }} x={150} y={204} w={566} h={40} align="centerStart" />
      <div style={{ ...at(96, 388, 620, 600), display: 'flex', flexDirection: 'column', alignItems: 'flex-start' }}>
        <div style={{ ...lineSpacing(label, 4.6), color: t.ink, letterSpacing: t.tracking(t.headingTracking), maxWidth: '100%' }}>
          {t.heading(focused?.label ?? '')}
        </div>
        <Para text={focused?.desc ?? ''} maxLines={descLines}
          style={{ ...lineSpacing(descStyle, 7.5), marginTop: `${24 - 7.5 / 2}px`, color: t.mute, maxWidth: 620 }} />
      </div>

      <div style={{ ...at(800, 88, 1024, 904), overflow: 'hidden' }}>{/* rows scrolled out of view take no clicks */}
        <div style={{ ...at(0, 0, 1024, 904), transform: `translateY(${-ty}px)`, transition: 'transform 220ms ease-in-out' }}>
          {rows.map(pl => {
            if (pl.row.key == null) {
              return (
                <div key={pl.row.id} style={{
                  ...at(0, pl.y, 1024, 72), padding: '30px 0 0 28px', ...natural(t.body(24, 700)), color: t.dim, letterSpacing: t.tracking(0.16),
                }}>{pl.row.label.toUpperCase()}</div>
              )
            }
            const f = pl.row.id === focused?.id
            return (
              <ListRow key={pl.row.id} bg={f ? t.focusBg : t.panel} height={84} inset={32} style={{ ...at(0, pl.y, 1024, 84), transition: colors }}
                attrs={tapSelects(model, () => {
                  // moving off Sign out disarms it, as the remote's Up / Down do
                  if (model.focusIndex !== pl.index) model.signOutArmed = false
                  model.focusIndex = pl.index ?? 0
                })}>
                <div style={{ ...natural(t.body(32, 600)), color: f ? t.focusInk : t.ink, transition: colors }}>{pl.row.label}</div>
                <div style={{ flex: 1, minWidth: 24 }} />
                <div style={{ ...natural(t.body(30)), minHeight: swiftHeight(t.body(30)), color: f ? t.focusDim : t.dim, transition: colors }}>{pl.row.value}</div>
              </ListRow>
            )
          })}
        </div>
      </div>
    </div>
  )
}

/** A list/field row: rounded panel, horizontal padding, content vertically centered. The caller colors its texts. Search's
 *  rows too. */
export function ListRow(props: { bg: string; height: number; inset: number; style?: JSX.CSSProperties; attrs: { onClick: (e: MouseEvent) => void }; children: ComponentChildren }) {
  const t = useTheme()
  return (
    <div {...props.attrs} style={{
      alignSelf: 'stretch', display: 'flex', alignItems: 'center', height: props.height, background: props.bg, borderRadius: t.radius,
      padding: `0 ${props.inset}px`, ...props.style,
    }}>{props.children}</div>
  )
}

// MARK: - Channel editor

export function ChannelsScreen() {
  const model = useModel()
  const t = useTheme()
  const list = model.channels
  const idx = model.channelIndex
  const p = model.profile
  const hidden = list.filter(c => model.isHidden(c.id)).length
  const count = `${list.length} channels` + (hidden > 0 ? ` · ${hidden} hidden for ${p.name}` : '')
  const total = (list.length + 1) * 96 - 8
  const ty = useSticky(Math.max(0, Math.min(total - 784, idx * 96 - 784 / 2 + 44)))
  const rowState = (i: number): ChannelRowState => i !== idx ? 'plain' : model.channelColumn === 0 ? 'focused' : 'selected'
  const colors = useScrollSpec(ty)
  const ch = model.selectedChannel
  return (
    <div style={at(0, 0, 1920, 1080)}>
      <TextAt text={t.heading('Channels')} style={{ ...t.heading(84), color: t.ink, letterSpacing: t.tracking(t.headingTracking) }}
        x={96} y={64} w={1000} h={84} align="centerStart" />
      <TextAt text="Drawn from your library. Rename, renumber, hide, or start one from scratch." style={{ ...t.body(30), color: t.mute }}
        x={96} y={158} w={1200} h={36} align="centerStart" />
      <TextAt text={count} style={{ ...t.body(26), color: t.dim }} x={1324} y={157} w={500} h={31} align="centerEnd" />

      <div style={{ ...at(96, 240, 760, 784), overflow: 'hidden' }}>{/* rows scrolled out of view take no clicks */}
        <div style={{ ...at(0, 0, 760, 784), transform: `translateY(${-ty}px)`, transition: 'transform 220ms ease-in-out' }}>
          <ChannelRow key="+" n="+" name="New channel" tag="From a director, an actor, or one show" badge="" state={rowState(0)} colors={colors}
            style={at(0, 0, 760, 88)} attrs={tapSelects(model, () => { model.channelSelection = null; model.channelColumn = 0 })} />
          {list.map((c, i) => (
            <ChannelRow key={c.id} n={String(c.number)} name={c.name || 'Untitled'} tag={c.definition.rule.map(RuleChip.label).join(' · ')}
              badge={model.isHidden(c.id) ? 'Hidden' : c.isCustom ? 'Custom' : 'Auto'} state={rowState(i + 1)} colors={colors}
              style={at(0, (i + 1) * 96, 760, 88)} attrs={tapSelects(model, () => { model.channelSelection = c.id; model.channelColumn = 0 })} />
          ))}
        </div>
      </div>

      {ch != null ? <ChannelDetail channel={ch} /> : <NewChannelIntro />}
    </div>
  )
}

type ChannelRowState = 'plain' | 'selected' | 'focused'

function ChannelRow(props: {
  n: string; name: string; tag: string; badge: string; state: ChannelRowState; colors: string; style: JSX.CSSProperties
  attrs: { onClick: (e: MouseEvent) => void }
}) {
  const t = useTheme()
  const { state, colors } = props
  const f = state === 'focused'
  const sc = f ? t.focusDim : state === 'selected' ? t.mute : t.dim
  const bg = f ? t.focusBg : state === 'selected' ? t.panel2 : t.panel
  const fg = f ? t.focusInk : t.ink
  const nc = f ? t.focusInk : t.accent
  const line: JSX.CSSProperties = { whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: '100%', transition: colors }
  return (
    <ListRow bg={bg} height={88} inset={28} style={{ ...props.style, transition: colors }} attrs={props.attrs}>
      <div style={{ ...natural(t.body(34, 700)), width: 64, flex: 'none', textAlign: 'right', fontFeatureSettings: '"tnum"', color: nc, ...line }}>{props.n}</div>
      <Column gap={2} style={{ flex: 1, minWidth: 0, paddingLeft: 22 }}>
        <div style={{ ...natural(t.body(32, 600)), minHeight: swiftHeight(t.body(32)), color: fg, ...line }}>{t.heading(props.name)}</div>
        {/* tvOS lays out an empty Text("") 14 pt tall, not a full 24 pt line: a tagless row (a new channel with no rule yet)
            has its title 9 pt lower than a tagged one (measured on an Apple TV screenshot). */}
        <div style={{ ...natural(t.body(24)), color: sc, ...line, ...(props.tag === '' ? { height: 14 } : { minHeight: swiftHeight(t.body(24)) }) }}>{props.tag}</div>
      </Column>
      <div style={{ ...natural(t.body(24, 600)), minHeight: swiftHeight(t.body(24)), paddingLeft: 22, whiteSpace: 'nowrap', color: sc, transition: colors }}>{props.badge}</div>
    </ListRow>
  )
}

function NewChannelIntro() {
  const model = useModel()
  const t = useTheme()
  return (
    <Column gap={22} style={at(920, 252, 904, 772)}>
      <div style={{ height: 72, display: 'flex', alignItems: 'center' }}>
        <div style={{ ...natural(t.heading(72)), color: t.ink, letterSpacing: t.tracking(t.headingTracking) }}>{t.heading('Start a channel')}</div>
      </div>
      <Para text="Name a director, an actor, or a single show. Rerun Guide rounds up everything that matches in your library and gives it the next free number."
        style={{ ...lineSpacing(t.body(32), 8), color: t.mute, maxWidth: 820 }} />
      <GlyphText before="Press " glyph="ok" after=" to start." style={{ ...natural(t.body(28)), color: t.dim, paddingTop: 12 }}
        onGlyph={() => model.press('select')} />
    </Column>
  )
}

function ChannelDetail({ channel }: { channel: Channel }) {
  const model = useModel()
  const t = useTheme()
  const ch = channel
  const p = model.profile
  const hidden = model.isHidden(ch.id)
  const now = useNow(10_000)   // TimelineView(.periodic(from: .now, by: 10))
  const one: JSX.CSSProperties = { whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: '100%' }
  // The column (~830 high) is taller than its 784 frame, as in SwiftUI: it hangs off the bottom instead of squeezing the rows.
  return (
    <Column gap={14} style={{ position: 'absolute', left: 920, top: 240, width: 904 }}>
      <div style={{ height: 96, alignSelf: 'stretch', display: 'flex', alignItems: 'center' }}>
        <div style={{ display: 'flex', alignItems: 'baseline', minWidth: 0 }}>
          <div style={{ ...natural(t.heading(96)), minHeight: swiftHeight(t.heading(96)), flex: 'none', marginRight: 24, color: t.accent, fontFeatureSettings: '"tnum"' }}>{ch.number}</div>
          <div style={{ ...natural(t.heading(72)), minWidth: 0, color: t.ink, letterSpacing: t.tracking(t.headingTracking), ...one }}>{t.heading(ch.name || 'Untitled')}</div>
        </div>
      </div>
      {/* ponytail: chips never wrap; wrap them if a rule ever has more than ~3 chips. */}
      <Row gap={10}>
        {ch.definition.rule.map((c, i) => (
          <div key={i} style={{
            ...natural(t.body(24, 600)), minHeight: swiftHeight(t.body(24)), whiteSpace: 'nowrap', padding: '6px 16px', borderRadius: t.pill,
            background: t.panel2, color: t.ink,
          }}>{RuleChip.label(c)}</div>
        ))}
      </Row>
      {/* One line, as SwiftUI lays it out: the overfull VStack takes its only give from this text. */}
      <div style={{ ...natural(t.body(26)), minHeight: swiftHeight(t.body(26)), color: t.dim, ...one }}>
        {ch.isCustom ? 'A channel you made. It keeps its schedule until you delete it.' : 'Built automatically from your library’s tags. Your edits stay when it rebuilds.'}
      </div>
      <div style={{ ...natural(t.body(28)), minHeight: swiftHeight(t.body(28)), color: t.mute, ...one }}>{onNow(model, ch, now)}</div>
      <Column gap={8} style={{ alignSelf: 'stretch', paddingTop: 12 }}>
        {ChannelField.entries.map((field, ordinal) => {
          const f = model.channelColumn === 1 && ordinal === model.channelFieldIndex
          const isName = field === 'title'   // Swift's `.name` case (Kotlin can't call an enum entry `name`)
          const label = field === 'number' ? 'Channel number' : field === 'order' ? 'Order' : field === 'breaks' ? 'Trailer breaks'
            : field === 'tune' ? 'When you tune in' : field === 'show' ? `Show in ${p.name}’s guide`
            : field === 'delete' ? (ch.isCustom ? 'Delete channel' : 'Reset to automatic') : 'Name'
          const value = field === 'number' ? model.numberDraft ?? String(ch.number) : field === 'order' ? PlayOrder.rawValue(ch.order)
            : field === 'breaks' ? (ch.trailerBreaks ? 'On' : 'Off') : field === 'tune' ? AppModel.tuneLabel(ch.definition.tuneMode, model.settings.tuneMode)
            : field === 'show' ? (hidden ? 'Off' : 'On') : field === 'delete' ? '' : ch.name
          const strong = isName || field === 'number'
          return (
            <ListRow key={field} bg={f ? t.focusBg : t.panel} height={72} inset={28}
              attrs={tapSelects(model, () => { model.channelColumn = 1; model.channelFieldIndex = ordinal })}>
              <div style={{ ...natural(t.body(30, 600)), minHeight: swiftHeight(t.body(30)), flex: 1, minWidth: 0, color: f ? t.focusInk : t.ink }}>{label}</div>
              {/* SwiftUI's HStack offers the plain value Text half the row before the maxWidth: .infinity label takes the rest:
                  a long value wraps (spilling past the 72-high row) instead of squeezing the label. */}
              <div style={{
                ...natural(t.body(30, 500)), minHeight: swiftHeight(t.body(30)), maxWidth: (904 - 56) / 2, paddingLeft: 24, flex: 'none',
                color: strong ? (f ? t.focusInk : t.ink) : (f ? t.focusDim : t.dim),
              }}>{value}</div>
            </ListRow>
          )
        })}
      </Column>
    </Column>
  )
}

/** "On now: X  ·  Next at 9:30: Y". The time is `.hour(.defaultDigits(amPM: .omitted)).minute()`: "9:30" on a 12-hour
 *  clock. ponytail: always 12-hour; the TVs' 24-hour setting isn't readable from a web app without platform calls. */
function onNow(model: AppModel, channel: Channel, now: Instant): string {
  const cur = model.slotOnAir(channel, now)
  if (cur == null) return ''
  let s = `On now: ${cur.item.displayTitle}`
  const next = model.slotOnAir(channel, cur.end)
  if (next != null) s += `  ·  Next at ${Clock.time(cur.end)}: ${next.item.displayTitle}`
  return s
}

// MARK: - Helpers

/** SwiftUI ceils a text's height to whole points (24 pt Hanken: 31.27 → 32); a `minHeight` for the text's box. */
export function swiftHeight(style: TextStyle): number {
  return Math.ceil(style.fontSize * lineEm(style))
}

type Align = 'center' | 'centerStart' | 'centerEnd' | 'topCenter'

/** `Text(s).at(x, y, w:, h:, alignment:)`. The text keeps its natural height: a line taller than its frame spills out
 *  evenly above and below, as SwiftUI draws it, instead of being clipped at the bottom. */
export function TextAt(props: { text: string; style: JSX.CSSProperties & TextStyle; x: number; y: number; w: number; h: number; align: Align; maxLines?: number }) {
  const { style, align, maxLines } = props
  const clamp: JSX.CSSProperties = maxLines === 1 ? { whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }
    : maxLines != null ? { display: '-webkit-box', WebkitBoxOrient: 'vertical', WebkitLineClamp: maxLines, overflow: 'hidden', whiteSpace: 'pre-line' }
    : { whiteSpace: 'pre-line' }   // a "\n" breaks the line, as in Compose
  return (
    <div style={{
      ...at(props.x, props.y, props.w, props.h), display: 'flex', alignItems: align === 'topCenter' ? 'flex-start' : 'center',
      justifyContent: align === 'centerStart' ? 'flex-start' : align === 'centerEnd' ? 'flex-end' : 'center',
    }}>
      <div style={{ ...natural(style), minHeight: swiftHeight(style), minWidth: 0, maxWidth: '100%', ...clamp }}>{props.text}</div>
    </div>
  )
}

/**
 * SwiftUI's `.animation(.easeInOut(duration: 0.22), value: ty)` on a scrolling list eases every change that lands in the
 * same update as the scroll (the rows' focus colors too); changes that leave the list where it is snap. Returns the colour
 * `transition` for this render. Kept for the ease's 220 ms after a scroll, so a re-render mid-ease doesn't cut it short.
 */
export function useScrollSpec(ty: number): string {
  const last = useRef({ ty, at: 0 })
  if (last.current.ty !== ty) last.current = { ty, at: Date.now() }
  const scrolling = Date.now() - last.current.at < 220
  return scrolling ? 'background-color 220ms ease-in-out, color 220ms ease-in-out' : 'none'
}

/** SF Symbols in the hint copy, drawn in the text's color: `playpause.fill` (Play/Pause) and `button.programmable` (OK,
 *  the clickpad center). */
type SettingsGlyph = 'playPause' | 'ok'

/**
 * Text with an inline glyph. The placeholder sizes are the Kotlin ones (advances measured from Apple TV screenshots: the SF
 * Symbols carry ~4 pt (playpause) and ~2.5 pt side bearings). The glyph names a remote button; clicking it (the Magic
 * Remote's pointer, which has no Play/Pause key) presses that button.
 */
function GlyphText(props: { before: string; glyph: SettingsGlyph; after: string; style: JSX.CSSProperties & TextStyle; onGlyph: () => void }) {
  const { style } = props
  // playPause sits on the baseline (AboveBaseline); ok is centered on the text (TextCenter): the middle of the face's
  // ascent (1 em) and descent (Hanken 0.303 em, Barlow 0.2 em) above the baseline, less half its 1.1 em.
  const descent = style.fontFamily === Fonts.hanken ? 0.303 : 0.2
  const svg: JSX.CSSProperties = props.glyph === 'playPause'
    ? { width: '1.68em', height: '0.72em', verticalAlign: 'baseline', overflow: 'visible' }
    : { width: '1.18em', height: '1.1em', verticalAlign: `${(1 - descent) / 2 - 0.55}em`, overflow: 'visible' }
  return (
    <div style={style}>
      {props.before}
      <span class="hov" {...onTouch(props.onGlyph)}>
        {props.glyph === 'playPause' ? (
          // 1.39 em wide, 0.75 em tall, sitting 0.04 em below the placeholder's top (a point under the baseline)
          <svg viewBox="0 0 1.68 0.72" style={svg} fill="currentColor">
            <path d="M0.143 0.04L0.8256 0.415L0.143 0.79Z" />
            <rect x={0.143 + 1.393 * 0.6} y={0.04} width={1.393 * 0.16} height={0.75} rx={0.04} />
            <rect x={0.143 + 1.393 * 0.84} y={0.04} width={1.393 * 0.16} height={0.75} rx={0.04} />
          </svg>
        ) : (
          // 1 em across: a 0.1 em ring around a 0.71 em disc
          <svg viewBox="0 0 1.18 1.1" style={svg} fill="currentColor">
            <circle cx={0.59} cy={0.55} r={0.45} fill="none" stroke="currentColor" stroke-width={0.1} />
            <circle cx={0.59} cy={0.55} r={0.357} />
          </svg>
        )}
      </span>
      {props.after}
    </div>
  )
}
