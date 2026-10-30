// Port of App/RerunLoader.swift, from the design handoff's RerunLoader.tsx: the logo in SVG, posed by a
// requestAnimationFrame loop that writes attributes directly (only the ones that changed), so nothing re-renders.
import type { JSX } from 'preact'
import { useEffect, useRef } from 'preact/hooks'
import { natural } from './Anim'
import { useTheme } from './Theme'

const CAPTIONS = ['Tuning in…', 'Buffering…']

type Ear = { bx: number; by: number; th: number; k: number }   // base point, heading (radians), curvature (1/radius)
type Pose = { sy: number; arrow: number; a: Ear; b: Ear }       // TV squash (anchored bottom center), arrow degrees

const D = Math.PI / 180, LEN = 120, RING_R = LEN / Math.PI, CX = 320, CY = -40
const eo = (x: number) => 1 - (1 - x) ** 3, ei = (x: number) => x * x * x
const eio = (x: number) => (x < 0.5 ? 4 * x * x * x : 1 - (-2 * x + 2) ** 3 / 2)
const back = (x: number) => 1 + 2.70158 * (x - 1) ** 3 + 1.70158 * (x - 1) ** 2

/** One ring loop, t in 0…1: 0–.14 crouch · .08–.30 ears pop and curl into a ring (overshoot) · .30–.72 two turns ·
 *  .72–.88 straighten and drop back on · .88–.98 landing squash. The arrow stays still. */
function ring(t: number): Pose {
  const seg = (a: number, b: number) => Math.min(1, Math.max(0, (t - a) / (b - a)))
  let sy = 1
  if (t < 0.06) sy = 1 - 0.03 * eo(seg(0, 0.06))
  else if (t < 0.14) sy = 0.97 + 0.03 * eo(seg(0.06, 0.14))
  else if (t >= 0.88 && t < 0.92) sy = 1 - 0.025 * eo(seg(0.88, 0.92))
  else if (t >= 0.92 && t < 0.98) sy = 0.975 + 0.025 * eo(seg(0.92, 0.98))
  let f = 0, kf = 0, phi = 0
  if (t >= 0.08 && t < 0.3) { f = back(seg(0.08, 0.3)); kf = eo(seg(0.08, 0.3)) }
  else if (t >= 0.3 && t < 0.72) { f = 1; kf = 1; phi = -720 * eio(seg(0.3, 0.72)) }
  else if (t >= 0.72 && t < 0.88) { const q = seg(0.72, 0.88); f = 1 - ei(q); kf = 1 - eio(q) }
  const lerp = (a: number, b: number) => a + (b - a) * f, drop = (476 - 84) * (1 - sy), k = (Math.PI / LEN) * kf
  return {
    sy, arrow: 0,
    a: { bx: lerp(320, CX + RING_R * Math.cos((90 + phi) * D)), by: lerp(84 + drop, CY + RING_R * Math.sin((90 + phi) * D)), th: lerp(-122, -180 + phi) * D, k },
    b: { bx: lerp(320, CX + RING_R * Math.cos((-90 + phi) * D)), by: lerp(84 + drop, CY + RING_R * Math.sin((-90 + phi) * D)), th: lerp(-58, phi) * D, k },
  }
}

/** A 120-long stem that bends into an arc of curvature k, with a knob at the tip. */
function ear(e: Ear) {
  if (Math.abs(e.k) < 1e-4) {
    const ex = e.bx + LEN * Math.cos(e.th), ey = e.by + LEN * Math.sin(e.th)
    return { d: `M${e.bx} ${e.by}L${ex} ${ey}`, ex, ey }
  }
  const R = 1 / e.k, pt = (s: number) => [e.bx + R * (Math.sin(e.th + e.k * s) - Math.sin(e.th)), e.by - R * (Math.cos(e.th + e.k * s) - Math.cos(e.th))]
  const m = pt(LEN / 2), end = pt(LEN)
  return { d: `M${e.bx} ${e.by}A${R} ${R} 0 0 1 ${m[0]} ${m[1]}A${R} ${R} 0 0 1 ${end[0]} ${end[1]}`, ex: end[0], ey: end[1] }
}

let uid = 0

/**
 * Rerun Guide's loading animation, the design handoff's "2a, ears ring": the app-icon TV, whose rabbit ears pop off,
 * curl into a ring, spin twice and snap back on (a 2.4 s loop). The caption alternates "Tuning in…" and "Buffering…"
 * every two loops with a 0.22 s fade; a single caption stays put. It fades in once `delay` seconds have
 * passed, so a wait shorter than that never shows it. Reduce Motion: the ears stay put, the arrow turns once every 3 s
 * and captions change every 6 s. `width` is the logo's (384 is the design's size on the stage).
 */
export function RerunLoader(props: { width?: number; captions?: string[]; delay?: number; style?: JSX.CSSProperties }) {
  const t = useTheme()
  const w = props.width ?? 384, k = w / 640
  const caps = props.captions ?? CAPTIONS
  const capsNow = useRef(caps)
  capsNow.current = caps   // a new caption keeps the loop (and the delay already served) going
  const id = useRef(`rl${++uid}`).current
  const root = useRef<HTMLDivElement>(null), cap = useRef<HTMLDivElement>(null)
  const earA = useRef<SVGPathElement>(null), earB = useRef<SVGPathElement>(null)
  const ballA = useRef<SVGCircleElement>(null), ballB = useRef<SVGCircleElement>(null)
  const tv = useRef<SVGGElement>(null), arrow = useRef<SVGGElement>(null)

  useEffect(() => {
    const reduce = typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches
    const t0 = performance.now() + (props.delay ?? 0) * 1000
    const per = reduce ? 6000 : 4800   // two ring loops per caption
    const last = new Map<string, string>()
    // An unchanged attribute isn't written again: the TV's shadow filter then repaints only while the TV squashes.
    const put = (key: string, v: string, set: (v: string) => void) => { if (last.get(key) !== v) { last.set(key, v); set(v) } }
    let raf = 0
    const tick = (now: number) => {
      raf = requestAnimationFrame(tick)
      const ms = now - t0
      put('o', String(Math.min(1, Math.max(0, ms / 250))), v => { if (root.current != null) root.current.style.opacity = v })
      if (ms < 0) return
      const p = reduce ? { ...ring(0), arrow: -360 * ((ms % 3000) / 3000) } : ring((ms % 2400) / 2400), a = ear(p.a), b = ear(p.b)
      put('ea', a.d, v => earA.current?.setAttribute('d', v))
      put('eb', b.d, v => earB.current?.setAttribute('d', v))
      put('ba', `${a.ex} ${a.ey}`, () => { ballA.current?.setAttribute('cx', String(a.ex)); ballA.current?.setAttribute('cy', String(a.ey)) })
      put('bb', `${b.ex} ${b.ey}`, () => { ballB.current?.setAttribute('cx', String(b.ex)); ballB.current?.setAttribute('cy', String(b.ey)) })
      put('tv', `translate(320 476) scale(1 ${p.sy}) translate(-320 -476)`, v => tv.current?.setAttribute('transform', v))
      put('ar', `rotate(${p.arrow} 320 285)`, v => arrow.current?.setAttribute('transform', v))
      const c = cap.current?.firstChild, cs = capsNow.current
      if (c != null && cs.length > 0) {
        const i = Math.floor(ms / per) % cs.length, q = ms % per
        if (c.nodeValue !== cs[i]) c.nodeValue = cs[i]   // the text node Preact made, so its own updates still land
        put('co', String(cs.length === 1 ? 1 : Math.min(1, q / 220, (per - q) / 220)), v => { cap.current!.style.opacity = v })
      }
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [])

  return (
    <div ref={root} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', opacity: 0, ...props.style }}>
      <svg width={w} height={620 * k} viewBox="0 -100 640 620" style={{ flex: 'none', display: 'block', overflow: 'visible' }}>
        <defs>
          <radialGradient id={`${id}s`} gradientUnits="userSpaceOnUse" cx="240.8" cy="192.6" r="520">
            <stop offset="0" stop-color="#3d3027" /><stop offset=".58" stop-color="#1d1713" /><stop offset="1" stop-color="#110d0b" />
          </radialGradient>
          <radialGradient id={`${id}g`} gradientUnits="userSpaceOnUse" cx="320" cy="285" r="240">
            <stop offset="0" stop-color="#f0a33a" stop-opacity=".18" /><stop offset="1" stop-color="#f0a33a" stop-opacity="0" />
          </radialGradient>
          <pattern id={`${id}l`} patternUnits="userSpaceOnUse" width="640" height="7" y="1">
            <rect width="640" height="3" fill="rgba(0,0,0,.22)" />
          </pattern>
          <clipPath id={`${id}c`}><rect x="100" y="120" width="440" height="330" rx="34" ry="38" /></clipPath>
          <filter id={`${id}sh`} x="-20%" y="-20%" width="140%" height="160%">
            <feDropShadow dx="0" dy="30" stdDeviation="30" flood-color="#000" flood-opacity=".55" />
          </filter>
          <filter id={`${id}gl`} x="-30%" y="-30%" width="160%" height="160%">
            <feDropShadow dx="0" dy="0" stdDeviation="8" flood-color="#f0a33a" flood-opacity=".75" />
          </filter>
        </defs>
        <path ref={earA} d="M320 84L256.4 -17.8" fill="none" stroke="#c9bdae" stroke-width="8" stroke-linecap="round" />
        <circle ref={ballA} cx="256.4" cy="-17.8" r="10" fill="#f3ebe0" />
        <path ref={earB} d="M320 84L383.6 -17.8" fill="none" stroke="#c9bdae" stroke-width="8" stroke-linecap="round" />
        <circle ref={ballB} cx="383.6" cy="-17.8" r="10" fill="#f3ebe0" />
        <g ref={tv}>
          <path d="M286 102A30 30 0 0 1 316 72L324 72A30 30 0 0 1 354 102Z" fill="#f3ebe0" />
          <rect x="74" y="94" width="492" height="382" rx="58" ry="62" fill="#f3ebe0" filter={`url(#${id}sh)`} />
          <g clip-path={`url(#${id}c)`}>
            <rect x="100" y="120" width="440" height="330" fill={`url(#${id}s)`} />
            <rect x="100" y="120" width="440" height="330" fill={`url(#${id}g)`} />
            <rect x="100" y="120" width="440" height="330" fill={`url(#${id}l)`} />
          </g>
          <g filter={`url(#${id}gl)`}>
            <g ref={arrow}>
              <path d="M320 197.5A87.5 87.5 0 1 1 232.5 285" fill="none" stroke="#f0a33a" stroke-width="37" />
              <path d="M256 198L324 154L324 242Z" fill="#f0a33a" />
            </g>
          </g>
          <path d="M294 248L358 285L294 322Z" fill="#f3ebe0" />
        </g>
      </svg>
      {caps.length > 0 && (
        <div ref={cap} style={{ ...natural(t.body(50 * k, 500)), marginTop: 20 * k, letterSpacing: '.02em', color: t.dim, whiteSpace: 'nowrap',
          textShadow: '0 0 10px rgba(0,0,0,0.8)' }}>{/* readable over a bright frame while buffering; a margin, not flex gap (old TV browsers) */}
          {caps[0]}
        </div>
      )}
    </div>
  )
}
