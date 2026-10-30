// Port of android/app SearchScreen.kt (App/Search/SearchScreen.swift). The tvOS inline text field is not ported: the text
// is typed in TextEntry's panel, and the results follow the typing behind it.
import type { JSX } from 'preact'
import { useEffect } from 'preact/hooks'
import { TitleSearch } from '../core/TitleSearch'
import { Column, natural, useNow } from './Anim'
import { useModel } from './AppModel'
import { tapSelects } from './AppModelTouch'
import { useSticky } from './Platform'
import { ListRow, TextAt, swiftHeight, useScrollSpec } from './SettingsScreens'
import { at } from './Stage'
import { useTheme } from './Theme'

/** Options › Search: the text field, then up to 50 films and shows that match, each with when it's on next. The list
 *  keeps the focused row in view like the channel editor's. */
export function SearchScreen() {
  const model = useModel()
  const t = useTheme()
  const now = useNow(10_000)   // TimelineView(.periodic(by: 10))
  useEffect(() => { model.refreshAirings(now) }, [now])
  const results = model.searchResults, focus = model.searchFocus, query = model.searchQuery
  const listH = 688, total = results.length * 96 - 8
  const ty = useSticky(Math.max(0, Math.min(total - listH, (focus - 1) * 96 - listH / 2 + 44)))   // holds still while the LG pointer moves the focus
  const colors = useScrollSpec(ty)
  const f = focus === 0
  return (
    <div style={at(0, 0, 1920, 1080)}>
      <TextAt text={t.heading('Search')} style={{ ...t.heading(84), color: t.ink, letterSpacing: t.tracking(t.headingTracking) }}
        x={96} y={64} w={1000} h={84} align="centerStart" />
      <TextAt text="Find a film or a show in your library, see when it’s on next, or make a channel of it." style={{ ...t.body(30), color: t.mute }}
        x={96} y={158} w={1500} h={36} align="centerStart" />

      <ListRow bg={f ? t.focusBg : t.panel} height={88} inset={32} style={at(96, 232, 1728, 88)} attrs={tapSelects(model, () => { model.searchFocus = 0 })}>
        <SearchGlyph color={f ? t.focusDim : t.dim} />
        <div style={{
          ...natural(t.body(32, 500)), minHeight: swiftHeight(t.body(32)), flex: 1, minWidth: 0, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
          color: query === '' ? (f ? t.focusDim : t.dim) : (f ? t.focusInk : t.ink),
        }}>{query === '' ? 'Type a title' : query}</div>
      </ListRow>

      {results.length === 0 && (
        <TextAt text={query === '' ? 'Films by title, shows by name.' : `Nothing in your library matches “${query}”.`}
          style={{ ...t.body(30), color: t.dim }} x={128} y={356} w={1600} h={36} align="centerStart" />
      )}
      <div style={{ ...at(96, 344, 1728, listH), overflow: 'hidden' }}>{/* rows scrolled out of view take no clicks */}
        <div style={{ ...at(0, 0, 1728, listH), transform: `translateY(${-ty}px)`, transition: 'transform 220ms ease-in-out' }}>
          {results.map((e, i) => {
            const a = model.searchAirings.get(e.id)
            return (
              <ResultRow key={e.id} entry={e} airs={model.airsText(e, now)} onNow={a != null && a.start <= now} focused={focus === i + 1}
                colors={colors} style={at(0, i * 96, 1728, 88)} attrs={tapSelects(model, () => { model.searchFocus = i + 1 })} />
            )
          })}
        </div>
      </div>
    </div>
  )
}

/** Title, then year · Movie or Show; on the right when it's on next (the accent while it's on now). */
function ResultRow(props: {
  entry: TitleSearch.Entry; airs: string; onNow: boolean; focused: boolean; colors: string; style: JSX.CSSProperties
  attrs: { onClick: (e: MouseEvent) => void }
}) {
  const t = useTheme()
  const { entry, focused, colors } = props
  const sc = focused ? t.focusDim : t.dim
  const kind = (entry.year == null ? '' : `${entry.year} · `) + (entry.isShow ? 'Show' : 'Movie')
  const line: JSX.CSSProperties = { whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: '100%', transition: colors }
  return (
    <ListRow bg={focused ? t.focusBg : t.panel} height={88} inset={28} style={{ ...props.style, transition: colors }} attrs={props.attrs}>
      <Column gap={2} style={{ flex: 1, minWidth: 0 }}>
        <div style={{ ...natural(t.body(32, 600)), minHeight: swiftHeight(t.body(32)), color: focused ? t.focusInk : t.ink, ...line }}>{entry.title}</div>
        <div style={{ ...natural(t.body(24)), minHeight: swiftHeight(t.body(24)), color: sc, ...line }}>{kind}</div>
      </Column>
      <div style={{
        ...natural(t.body(28, 500)), minHeight: swiftHeight(t.body(28)), flex: 'none', paddingLeft: 32, whiteSpace: 'nowrap', transition: colors,
        color: focused ? t.focusInk : props.onNow ? t.accent : sc,
      }}>{props.airs}</div>
    </ListRow>
  )
}

/** SF Symbols' `magnifyingglass` at 30 pt semibold (measured on an Apple TV screenshot), in its 40-wide slot. */
function SearchGlyph({ color }: { color: string }) {
  return (
    <svg width={40} height={40} viewBox="0 0 40 40" style={{ flex: 'none', marginRight: 20 }}>
      <g fill="none" stroke={color} stroke-linecap="round">
        <circle cx={17} cy={17.5} r={10} stroke-width={3.8} />
        <path d="M24 24.5L31 31.5" stroke-width={5} />
      </g>
    </svg>
  )
}
