// Port of android/app Text.kt.
import type { JSX } from 'preact'
import { Instant } from '../core/Time'
import { Fonts, TextStyle } from './Theme'

/**
 * A wrapping paragraph with SwiftUI's line breaking (TextKit's standard strategy pushes out orphans): when the last line
 * would be one short lone word, the word before it moves down with it ("…says yes, we / move on."). A long one stays.
 * The Kotlin version measures the laid-out lines; this one joins the last two words with a no-break space when the last
 * word is short, so the browser does the same without a layout read.
 * ponytail: "short" = under a quarter of the box width (`style.width` or `style.maxWidth`), estimated at 0.5 em per
 * character; measure with a canvas if a real orphan or a wrongly moved word shows up.
 */
export function Para(props: { text: string; style: JSX.CSSProperties & TextStyle; maxLines?: number; class?: string }) {
  const { text, style, maxLines } = props
  const box = typeof style.width === 'number' ? style.width : typeof style.maxWidth === 'number' ? style.maxWidth : null
  let shown = text
  const lastBreak = text.lastIndexOf('\n')
  const space = text.lastIndexOf(' ')
  // Only within the last paragraph: a last line after a hard break is a paragraph of its own.
  if (box != null && space > lastBreak && text.slice(space + 1).length * 0.5 * style.fontSize < box / 4) {
    shown = text.slice(0, space) + ' ' + text.slice(space + 1)
  }
  const clamp: JSX.CSSProperties = maxLines == null ? {}
    : { display: '-webkit-box', WebkitBoxOrient: 'vertical', WebkitLineClamp: maxLines, overflow: 'hidden' }
  return <div class={props.class} style={{ whiteSpace: 'pre-line', ...clamp, ...style }}>{shown}</div>
}

/** Natural line height in em, from the bundled TTFs' hhea metrics (no line gap): Hanken Grotesk 1.303, Bricolage
 *  Grotesque and Barlow Condensed 1.2. Always give text an explicit line-height: `normal` varies by platform. */
export function lineEm(style: TextStyle): number {
  return style.fontFamily === Fonts.hanken ? 1.303 : 1.2
}

/** SwiftUI `.lineSpacing(n)`: n points between wrapped lines on top of the face's natural line, none above the first or
 *  below the last (the negative margin trims the half-gaps CSS puts there). One line keeps its natural height. */
export function lineSpacing<S extends TextStyle>(style: S, n: number): S & { lineHeight: string; marginTop: string; marginBottom: string } {
  return { ...style, lineHeight: `${style.fontSize * lineEm(style) + n}px`, marginTop: `${-n / 2}px`, marginBottom: `${-n / 2}px` }
}

let dayPeriodF: Intl.DateTimeFormat | null = null
let dateF: Intl.DateTimeFormat | null = null

function part(f: Intl.DateTimeFormat, d: Date, type: string): string | null {
  for (const p of f.formatToParts(d)) if (p.type.toLowerCase() === type) return p.value   // Chromium < 71 says "dayperiod"
  return null
}

/** 12-hour clock text in the device's locale (the prototype's fmt and range), shared by the guide, the player and the info sheet. */
export const Clock = {
  /** "8:30": the pattern h:mm. */
  time(t: Instant): string {
    const d = new Date(t * 1000)
    return `${d.getHours() % 12 || 12}:${String(d.getMinutes()).padStart(2, '0')}`
  },
  /** "PM": the pattern a, in the device's language. */
  ap(t: Instant): string {
    const d = new Date(t * 1000)
    dayPeriodF = dayPeriodF ?? new Intl.DateTimeFormat(undefined, { hour: 'numeric', hour12: true })
    return part(dayPeriodF, d, 'dayperiod') ?? (d.getHours() < 12 ? 'AM' : 'PM')
  },
  /** "WED OCT 22" (the Midnight Movie time bar): the pattern EEE MMM d, uppercased. */
  date(t: Instant): string {
    const d = new Date(t * 1000)
    dateF = dateF ?? new Intl.DateTimeFormat(undefined, { weekday: 'short', month: 'short', day: 'numeric' })
    return `${part(dateF, d, 'weekday')} ${part(dateF, d, 'month')} ${d.getDate()}`.toUpperCase()
  },
  /** The first AM/PM only when it differs from the second. */
  range(a: Instant, b: Instant): string {
    const x = Clock.ap(a)
    const y = Clock.ap(b)
    return Clock.time(a) + (x !== y ? ` ${x}` : '') + ' – ' + Clock.time(b) + ' ' + y
  },
}
