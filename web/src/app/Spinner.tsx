// Port of android/app Spinner.kt.
import type { JSX } from 'preact'

/**
 * The system spinner as tvOS draws it: 64 pt (times `scale`), tinted, centered in whatever box `style` gives. A 270° arc
 * with round caps, a tenth of its size thick, turning once a second (styles.css `.spin`: a CSS animation, so it turns on
 * the compositor and never re-renders anything).
 */
export function Spinner(props: { tint: string; style?: JSX.CSSProperties; scale?: number }) {
  const s = 64 * (props.scale ?? 1)
  const w = s * 0.1
  const r = (s - w) / 2
  const c = 2 * Math.PI * r
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', ...props.style }}>
      <svg class="spin" width={s} height={s} style={{ flex: 'none', display: 'block' }}>
        {/* A circle's stroke starts at 3 o'clock and runs clockwise: Compose's drawArc(startAngle = 0, sweepAngle = 270). */}
        <circle cx={s / 2} cy={s / 2} r={r} fill="none" stroke={props.tint} stroke-width={w} stroke-linecap="round"
          stroke-dasharray={`${c * 0.75} ${c}`} />
      </svg>
    </div>
  )
}
