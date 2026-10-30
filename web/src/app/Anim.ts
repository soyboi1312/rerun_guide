// Web-only UI helpers with no Kotlin counterpart: what Compose's AnimatedVisibility/AnimatedContent, produceState clocks,
// Arrangement.spacedBy and BasicText's natural line height give the Kotlin screens for free.
import type { ComponentChildren, JSX } from 'preact'
import { h, toChildArray } from 'preact'
import { useEffect, useRef, useState } from 'preact/hooks'
import { now } from '../core/Time'
import type { TextStyle } from './Theme'
import { useTheme } from './Theme'
import { lineEm } from './Text'

// The Kotlin screens' enter/exit motions as keyframes (styles.css has fadeIn/fadeOut and spin):
// rgEnterX: Options' `enter(dx = -40)`; rgUp: `enter(dy = 14)`, `appear()` and the toast's entrance (a fade up from 14 below);
// rgDown: the toast's exit (a fade down by 14); rgPulse: the Link screen's status dot (alpha 1 ↔ 0.25, alternate).
addCSS(`
@keyframes rgEnterX { from { opacity: 0; transform: translateX(-40px); } }
@keyframes rgUp { from { opacity: 0; transform: translateY(14px); } }
@keyframes rgDown { to { opacity: 0; transform: translateY(14px); } }
@keyframes rgPulse { to { opacity: 0.25; } }
`)

/**
 * Exit animations: keeps the last non-null `value` mounted for `ms` after it turns null, with `leaving` true meanwhile
 * (give the element an exit animation of `ms` then). A new non-null value cancels the exit.
 */
export function useExit<T>(value: T | null, ms: number): { value: T | null; leaving: boolean } {
  const last = useRef<T | null>(value)
  const [, gone] = useState(0)
  if (value != null) last.current = value
  const leaving = value == null && last.current != null
  useEffect(() => {
    if (!leaving) return
    const id = setTimeout(() => { last.current = null; gone(n => n + 1) }, ms)
    return () => clearTimeout(id)
  }, [leaving ? 1 : 0, ms])
  return { value: value ?? last.current, leaving }
}

/**
 * `produceState(Instant.now()) { while (true) { delay(period); value = Instant.now() } }`: epoch seconds, re-rendering only
 * the calling component every `periodMs` (on the period's boundary when `align`, like a clock), never the whole app.
 */
export function useNow(periodMs: number, align = false): number {
  const [t, setT] = useState(now)
  useEffect(() => {
    let id = 0
    const wait = () => align ? periodMs - (Date.now() % periodMs) : periodMs
    const tick = () => { setT(now()); id = window.setTimeout(tick, wait()) }
    id = window.setTimeout(tick, wait())
    return () => clearTimeout(id)
  }, [periodMs, align ? 1 : 0])
  return t
}

/** Adds rules (keyframes, mostly) to the page; call once at module level. styles.css holds the shared ones. */
export function addCSS(css: string): void {
  const s = document.createElement('style')
  s.textContent = css
  document.head.appendChild(s)
}

/** A text style with its explicit natural line height (BasicText's): `line-height: normal` varies by platform. */
export function natural<S extends TextStyle>(style: S): S & { lineHeight: number } {
  return { ...style, lineHeight: lineEm(style) }
}

type Box = Omit<JSX.HTMLAttributes<HTMLDivElement>, 'style'> & { gap: number; style?: JSX.CSSProperties; children?: ComponentChildren }

/** Spacers between the (non-null) children: flexbox `gap` is Chromium 84+, and margins would fight the children's own. */
function spaced(children: ComponentChildren, gap: number, vertical: boolean) {
  const size = vertical ? { height: gap } : { width: gap }
  const out: ComponentChildren[] = []
  toChildArray(children).forEach((c, i) => {
    if (i > 0) out.push(h('div', { key: `gap${i}`, style: { ...size, flex: 'none' } }))
    out.push(c)
  })
  return out
}

/** Compose `Column(verticalArrangement = spacedBy(gap))`, children start-aligned (not stretched) as in Compose. */
export function Column({ gap, style, children, ...rest }: Box) {
  return h('div', { ...rest, style: { display: 'flex', flexDirection: 'column', alignItems: 'flex-start', ...style } }, spaced(children, gap, true))
}

/** Compose `Row(horizontalArrangement = spacedBy(gap))`, children top-aligned as in Compose. */
export function Row({ gap, style, children, ...rest }: Box) {
  return h('div', { ...rest, style: { display: 'flex', alignItems: 'flex-start', ...style } }, spaced(children, gap, false))
}

type LiftProps = Omit<JSX.HTMLAttributes<HTMLDivElement>, 'style'> & {
  on: boolean; radius: number; bg: string; ms: number; style?: JSX.CSSProperties; children?: ComponentChildren
}

/**
 * The focus lift of Overlays.kt (150 ms) and OnboardingScreens.kt (180 ms): scale to `t.focusScale` and the shadow
 * `0 18px 44px rgba(0,0,0,.55)` (SwiftUI radius 22 = sigma 22) when the theme has one, eased with the fill and text colour
 * (their `fvAnimated` / `fade`). The shadow is a pre-drawn child whose opacity fades: never a box-shadow transition. Raised
 * above its siblings while on (their `zIndex(on ? 1 : 0)`). SwiftUI's shadow on the texts inside (`liftTextShadow`) is
 * left out: a blurred text shadow is costly on TV GPUs and barely shows.
 */
export function Lift({ on, radius, bg, ms, style, children, ...rest }: LiftProps) {
  const t = useTheme()
  const ease = `${ms}ms ease-in-out`
  const shadow = t.focusShadow ? h('div', { style: {
    position: 'absolute', left: 0, top: 0, width: '100%', height: '100%', borderRadius: radius, pointerEvents: 'none',
    boxShadow: '0 18px 44px rgba(0,0,0,0.55)', opacity: on ? 1 : 0, transition: `opacity ${ease}`,
  } }) : null
  return h('div', { ...rest, style: {
    position: 'relative', zIndex: on ? 1 : 0, transform: `scale(${on ? t.focusScale : 1})`, background: bg, borderRadius: radius,
    transition: `transform ${ease}, background-color ${ease}, color ${ease}`, ...style,
  } }, shadow, children)
}
