// Port of android/app AttractMode.kt.
import type { JSX } from 'preact'
import type { Channel } from '../core/Models'
import type { Instant } from '../core/Time'
import { natural } from './Anim'
import { ChannelLine, EverySecond, lineHeightTrim } from './GuideScreen'
import { at } from './Stage'
import { Para } from './Text'
import { useTheme } from './Theme'

let timeF: Intl.DateTimeFormat | null = null

/** `.dateTime.hour().minute()`: the device's own clock style. A web app can't read the TV's 12/24-hour setting, so this
 *  is the locale's ("8:30 PM" in en-US, "20:30" in en-GB). */
function deviceTime(d: Instant): string {
  timeF = timeF ?? new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' })
  return timeF.format(new Date(d * 1000))
}

/**
 * Attract mode's right-hand panel: the storefront wall's big clock over the channel line and title of whatever the drift
 * is passing. Stands in for InfoPanel while `model.attracting`; the hint pills and blurb are left out. `style` goes after
 * the placement (the guide's crossfade).
 */
export function AttractPanel(props: { ch: Channel; title: string; style?: JSX.CSSProperties }) {
  const t = useTheme()
  const mm = t.id === 'MIDNIGHT'
  // ponytail: hard-coded beside PreviewWindow's attract tube (Late Show 640×480, Midnight Movie 824×464 ending at x 904)
  // and above the time header / bar; no handoff spec for this layout. Midnight Movie sits where its info panel's content does.
  const w = mm ? 860 : 936
  return (
    <div style={{ ...at(mm ? 940 : 904, mm ? 84 : 90, w, mm ? 396 : 460), display: 'flex', flexDirection: 'column', ...props.style }}>
      <EverySecond>{d => (
        <div style={{ ...natural(t.heading(96)), color: t.accent, fontFeatureSettings: '"tnum"', textAlign: 'right' }}>{deviceTime(d)}</div>
      )}</EverySecond>
      <div style={{ flex: 1 }} />
      <ChannelLine ch={props.ch} />
      {/* Mixed case in both themes, like the info panel's title it crossfades with. */}
      <Para text={props.title} maxLines={2} style={{
        ...t.heading(80), ...lineHeightTrim(80), color: t.ink, letterSpacing: mm ? 0 : '-0.025em', paddingTop: 12, maxWidth: w,
      }} />
    </div>
  )
}
