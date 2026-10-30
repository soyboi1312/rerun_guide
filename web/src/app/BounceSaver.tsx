// Port of android/app BounceSaver.kt.
import type { JSX } from 'preact'
import { useEffect, useRef } from 'preact/hooks'
import { Bouncer } from '../core/Bouncer'
import { natural } from './Anim'
import { at } from './Stage'
import { useTheme } from './Theme'

/**
 * Attract mode's other faces: a logo drifting and bouncing the way the DVD logo used to, on the theme's background.
 * Either the Rerun Guide TV with its wordmark, or Soyboi's mascot over "soyboi.tech". `Bouncer` (core) owns the path and
 * its promise of a corner at least every two minutes; a corner gets a glow. The first press wakes the guide
 * (AppModelGuide swallows it), so there is nothing to focus here. The frame loop writes the face's transform straight to
 * the DOM: nothing re-renders while it bounces.
 */
export function BounceSaver(props: { face: BounceSaver.Face }) {
  const t = useTheme()
  const [w, h] = BounceSaver.box(props.face)
  const b = useRef<Bouncer | null>(null)
  if (b.current == null) b.current = new Bouncer([1920, 1080], [w, h], undefined, [300, 200])   // Swift @State: first value kept
  const face = useRef<HTMLDivElement>(null)
  const glow = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const bouncer = b.current!
    // Swift steps 1/60 s per 60 Hz timer tick; fixed steps keep that path on any refresh rate. A stall (the app away from
    // the screen) resumes rather than fast-forwarding, as the timer does.
    let last = -1
    let acc = 0
    let id = 0
    const frame = (now: number) => {
      if (last >= 0) acc = Math.min(acc + (now - last) / 1000, 0.25)
      last = now
      let hit = false
      while (acc >= 1 / 60) { bouncer.step(1 / 60); acc -= 1 / 60; if (bouncer.hitCorner) hit = true }
      if (face.current) face.current.style.transform = `translate3d(${bouncer.x}px, ${bouncer.y}px, 0)`
      // `.shadow(color: accent × glow × 0.9, radius: 48 × glow)`, glow snapping to 1 and easing to 0 over 1.5 s: the halo
      // fades and draws in towards the face (the blur shrinking), opacity and scale only, on the compositor.
      if (hit) glow.current?.animate(
        [{ opacity: 0.9, transform: 'scale(1)' }, { opacity: 0, transform: `scale(${w / (w + 320)}, ${h / (h + 320)})` }],
        { duration: 1500, easing: 'ease-out' })
      id = requestAnimationFrame(frame)
    }
    id = requestAnimationFrame(frame)
    return () => cancelAnimationFrame(id)
  }, [])
  const pos = b.current
  return (
    <div style={at(0, 0, 1920, 1080)}>{/* on RootView's t.bg */}
      <div ref={face} style={{ ...at(0, 0, w, h), transform: `translate3d(${pos.x}px, ${pos.y}px, 0)`, willChange: 'transform' }}>
        {/* The glow, 160 bigger each side so it isn't cut at the box. Kotlin blurs an accent copy of the face; TV GPUs
            can't afford a blur, so this is a soft accent ellipse under it. ponytail: an ellipse, not the face's silhouette;
            a pre-blurred silhouette image per face if the difference shows. */}
        <div ref={glow} style={{ ...at(-160, -160, w + 320, h + 320), opacity: 0,
          background: `radial-gradient(closest-side, ${t.accent} 30%, transparent 82%)` }} />
        <FaceArt face={props.face} w={w} h={h} />
      </div>
    </div>
  )
}

export namespace BounceSaver {
  /** Names are the `Settings.screensaver` strings; "wall" (the storefront wall) has no face here. */
  export type Face = 'logo' | 'soyboi'
  export const Face = {
    entries: ['logo', 'soyboi'] as readonly Face[],
  }

  /** The bouncing box hugs the artwork plus its line of text, so corner hits look like corner hits. */
  export function box(face: Face): [number, number] {
    return face === 'logo'
      ? [400, 400]    // the 360-wide logo image (753×693 source) plus the wordmark under it
      : [320, 372]    // the 300-square mascot plus "soyboi.tech" under it
  }
}

/** The artwork over its line of text, centered in the bouncing box. Sized up front (the source's aspect), so the box
 *  doesn't jump when the image arrives. */
function FaceArt(props: { face: BounceSaver.Face; w: number; h: number }) {
  const t = useTheme()
  const col: JSX.CSSProperties = {
    ...at(0, 0, props.w, props.h), display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
  }
  if (props.face === 'logo') {
    const s = t.heading(40, 600)
    return (
      <div style={col}>
        <img src="images/bounce_logo.png" alt="" style={{ display: 'block', width: 360, height: 360 * 693 / 753 }} />
        <div style={{ display: 'flex', alignItems: 'center', marginTop: 12 }}>
          <div style={{ width: 14, height: 14, borderRadius: 999, background: t.accent }} />
          <div style={{ ...natural(s), letterSpacing: t.tracking(t.headingTracking), color: t.ink, marginLeft: 14 }}>
            {t.heading('Rerun Guide')}
          </div>
        </div>
      </div>
    )
  }
  return (
    <div style={col}>
      <img src="images/soyboi_mascot.png" alt="" style={{ display: 'block', width: 300, height: 300 }} />
      {/* a domain: never uppercased */}
      <div style={{ ...natural(t.body(40, 600)), color: t.ink, marginTop: 12 }}>soyboi.tech</div>
    </div>
  )
}
