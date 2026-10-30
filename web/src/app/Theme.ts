// Port of android/app Theme.kt. Colors are CSS color strings for inline styles; text styles are style objects to spread:
// `style={{ ...at(80, 72, 600, 60), ...t.heading(46), color: t.ink }}`.
import { createContext } from 'preact'
import { useContext } from 'preact/hooks'

/** Profiles persist the raw value; an unknown value from an older or newer build maps to LATE (App/Theme.swift ThemeID). */
export type ThemeID = 'LATE' | 'MIDNIGHT'
export const ThemeID = {
  entries: ['LATE', 'MIDNIGHT'] as readonly ThemeID[],
  raw(id: ThemeID): string { return id === 'LATE' ? 'late' : 'midnight' },
  displayName(id: ThemeID): string { return id === 'LATE' ? 'Late Show' : 'Midnight Movie' },
  theme(id: ThemeID): Theme { return id === 'LATE' ? Theme.late : Theme.midnight },
  from(raw: string | null | undefined): ThemeID { return raw === 'midnight' ? 'MIDNIGHT' : 'LATE' },
}

/**
 * Android `Color.parseColor`: "#rrggbb" or "#aarrggbb" (alpha FIRST, unlike CSS's #rrggbbaa) → a CSS color. Throws on
 * anything else, as parseColor does.
 */
export function hex(s: string): string {
  const m = /^#([0-9a-f]{2})?([0-9a-f]{6})$/i.exec(s)
  if (!m) throw new Error(`Unknown color ${s}`)
  if (m[1] == null) return '#' + m[2].toLowerCase()
  const n = parseInt(m[2], 16)
  return `rgba(${n >> 16},${(n >> 8) & 255},${n & 255},${+(parseInt(m[1], 16) / 255).toFixed(3)})`
}

/** The three bundled Google Fonts families (OFL), declared in styles.css with these names. */
export const Fonts = {
  bricolage: "'Bricolage Grotesque', sans-serif",
  hanken: "'Hanken Grotesk', sans-serif",
  barlow: "'Barlow Condensed', sans-serif",
}

/** What `Theme.heading` / `Theme.body` return: spread into a `style`. */
export interface TextStyle {
  fontFamily: string
  fontWeight: number
  fontSize: number
}

/** The design tokens, one to one with App/Theme.swift. Every screen reads colors and fonts from here. */
export class Theme {
  constructor(
    readonly id: ThemeID,
    readonly bg: string, readonly panel: string, readonly panel2: string, readonly line: string,
    readonly ink: string, readonly mute: string, readonly dim: string,
    readonly accent: string, readonly accentInk: string,
    readonly focusBg: string, readonly focusInk: string, readonly focusDim: string,
    readonly scrim: string, readonly glow: string,
    readonly radius: number, readonly cellRadius: number, readonly pill: number,
    readonly focusScale: number, readonly focusShadow: boolean, readonly uppercase: boolean,
    /** em */
    readonly headingTracking: number,
    readonly headingWeight: number,
    readonly headingFamily: string, readonly bodyFamily: string,
  ) {}

  /** Heading text style (Bricolage Grotesque / Barlow Condensed): the Swift `t.heading(size, weight:)`. */
  heading(size: number, weight?: number): TextStyle
  /** Headings in caps when the theme says so (Late Show doesn't). */
  heading(text: string): string
  heading(x: number | string, weight?: number): TextStyle | string {
    if (typeof x === 'string') return this.uppercase ? x.toUpperCase() : x
    return { fontFamily: this.headingFamily, fontWeight: this.clamp(weight ?? this.headingWeight), fontSize: x }
  }

  /** Body text style (Hanken Grotesk / Barlow Condensed): the Swift `t.body(size, weight:)`. */
  body(size: number, weight = 400): TextStyle {
    return { fontFamily: this.bodyFamily, fontWeight: this.clamp(weight), fontSize: size }
  }

  /** Letter-spacing in em, like the handoff's CSS: `tracking(-0.03)` → "-0.03em". */
  tracking(em: number): string {
    return `${em}em`
  }

  /** Barlow Condensed ships 500–800 only, so its "regular" is Medium. */
  private clamp(w: number): number {
    return this.bodyFamily === Fonts.barlow && this.headingFamily === Fonts.barlow && w === 400 ? 500 : w
  }

  static readonly late = new Theme(
    'LATE',
    hex('#14110f'), hex('#1e1a17'), hex('#2a231e'), hex('#3b3129'),
    hex('#f3ebe0'), hex('#c9bdae'), hex('#a89c8e'),
    hex('#f0a33a'), hex('#14110f'),
    hex('#f3ebe0'), hex('#16120f'), hex('#5a4e42'),
    'rgba(10,8,7,0.72)', 'rgba(240,163,58,0.10)',
    24, 16, 999, 1.03, true, false,
    -0.03, 700,
    Fonts.bricolage, Fonts.hanken,
  )

  /** Near-black and neon red, everything in Barlow Condensed, square corners, no focus lift. */
  static readonly midnight = new Theme(
    'MIDNIGHT',
    hex('#0c0909'), hex('#241414'), hex('#2e1919'), hex('#4a2424'),
    hex('#ffffff'), hex('#eadcd8'), hex('#c4a9a4'),
    hex('#ff4a3a'), hex('#0c0909'),
    hex('#ff4a3a'), hex('#0c0909'), hex('#3a0c09'),
    'rgba(8,4,4,0.82)', 'rgba(255,74,58,0.12)',
    0, 0, 0, 1, false, true,
    0.01, 800,
    Fonts.barlow, Fonts.barlow,
  )
}

/** The handoff's oklch(L C H) gradients, approximated: HSB with hue H, used for channel-colored previews. */
export function channelColor(hue: number, lightness: number, chroma: number): string {
  const h = ((hue % 360) + 360) % 360
  const s = Math.min(1, chroma * 4)
  const v = Math.min(1, lightness * 1.15)
  // Compose's Color.hsv
  const f = (n: number) => {
    const k = (n + h / 60) % 6
    return Math.round((v - v * s * Math.max(0, Math.min(k, 4 - k, 1))) * 255)
  }
  return `rgb(${f(5)},${f(3)},${f(1)})`
}

/** `Color(hex: "#f0a33a")` for strings stored in profiles: black when it doesn't parse. */
export function colorHex(s: string): string {
  try {
    return hex(s.charAt(0) === '#' ? s : '#' + s)
  } catch {
    return '#000000'
  }
}

/** The current profile's theme for every component (the Swift `@Environment(\.theme)`). RootView provides it. */
export const LocalTheme = createContext(Theme.late)

export function useTheme(): Theme {
  return useContext(LocalTheme)
}
