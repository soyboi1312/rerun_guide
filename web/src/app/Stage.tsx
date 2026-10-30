// Port of android/app Stage.kt for TVs. Everything is laid out on the handoff's 1920×1080 artboard: one CSS pixel is one
// artboard point, so every number from the Swift views carries over (`at(80, 72, 600, 450)` means what `.at` means there).
// Both TVs give a 1920×1080 viewport, so the stage is drawn 1:1; only a desktop browser of another width scales it.
import type { ComponentChildren, JSX } from 'preact'
import { useEffect, useState } from 'preact/hooks'

export const STAGE_W = 1920
export const STAGE_H = 1080

/** Absolute placement on the stage (or the positioned parent), top-left (x, y) with size (w, h): the Swift `.at(_:_:w:h:)`. */
export function at(x: number, y: number, w: number, h: number): JSX.CSSProperties {
  return { position: 'absolute', left: x, top: y, width: w, height: h }
}

/** Window px per stage point: 1 on a TV; letterboxed to fit in a browser window that isn't 1920 wide. */
function fit(): number {
  const w = window.innerWidth
  return w === STAGE_W ? 1 : Math.max(0.01, Math.min(w / STAGE_W, window.innerHeight / STAGE_H))
}

/** The 1920×1080 stage, centered in the window; `background` paints the margins around it in a browser. */
export function Stage(props: { background: string; children?: ComponentChildren }) {
  const [scale, setScale] = useState(fit)
  useEffect(() => {
    const resize = () => setScale(fit())
    window.addEventListener('resize', resize)
    return () => window.removeEventListener('resize', resize)
  }, [])
  const stage: JSX.CSSProperties = { ...at(0, 0, STAGE_W, STAGE_H), overflow: 'hidden' }
  if (scale !== 1) {
    stage.left = (window.innerWidth - STAGE_W * scale) / 2
    stage.top = (window.innerHeight - STAGE_H * scale) / 2
    stage.transform = `scale(${scale})`
    stage.transformOrigin = '0 0'
  }
  return (
    <div style={{ position: 'fixed', left: 0, top: 0, right: 0, bottom: 0, overflow: 'hidden', background: props.background }}>
      <div id="stage" style={stage}>{props.children}</div>
    </div>
  )
}
