// Port of android/app StationIdent.kt: the station card and its sting.
import type { JSX } from 'preact'
import { useLayoutEffect, useRef } from 'preact/hooks'
import { Column, natural, Row } from './Anim'
import { at } from './Stage'
import { useTheme } from './Theme'

/**
 * The station ident: a full-screen channel card WatchScreen shows for 1.5 s on tune-in ("YOU'RE WATCHING") and before a
 * trailer break ("WE'LL BE RIGHT BACK"). WatchScreen decides when; this is the picture (the sting is `StationSting`).
 * Fixed 1920×1080 stage points, like the Swift frame.
 */
export function StationIdentCard(props: { number: number; name: string; tag: string; kicker: string; sub: string; style?: JSX.CSSProperties }) {
  const t = useTheme()
  const num = useRef<HTMLDivElement>(null)
  // minimumScaleFactor(0.6): shrink to fit the 292-pt inside, never below 60 %. One layout read per number.
  useLayoutEffect(() => {
    const el = num.current
    if (!el) return
    el.style.fontSize = '160px'
    const wide = el.scrollWidth
    if (wide > 292) el.style.fontSize = `${160 * Math.max(0.6, 292 / wide)}px`
  }, [props.number, t.id])
  const line: JSX.CSSProperties = { maxWidth: '100%' }   // with .ellipsis: one line, cut (maxLines 1, softWrap false)
  return (
    <div style={{
      ...at(0, 0, 1920, 1080), ...props.style, overflow: 'hidden', display: 'flex', alignItems: 'center', justifyContent: 'center',
      // The onboarding Stage's radial-gradient(1100px 700px …) glow, centered behind the card.
      backgroundColor: t.bg, backgroundImage: `radial-gradient(1100px 700px at 50% 50%, ${t.glow}, transparent 70%)`,
    }}>
      <Row gap={64} style={{ alignItems: 'center' }}>
        <div style={{
          width: 340, height: 340, flex: 'none', padding: 24, background: t.accent, borderRadius: t.id === 'MIDNIGHT' ? 0 : '50%',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
          <div ref={num} class="ellipsis" style={{ ...natural(t.heading(160)), color: t.accentInk, maxWidth: 292 }}>{props.number}</div>
        </div>
        <Column gap={12} style={{ maxWidth: 1100, minWidth: 0 }}>
          <div style={{ ...natural(t.body(28, 700)), color: t.accent, letterSpacing: '0.18em', maxWidth: '100%' }}>{props.kicker}</div>
          <div class="ellipsis" style={{ ...natural(t.heading(72)), color: t.ink, letterSpacing: t.tracking(t.headingTracking), ...line }}>{t.heading(props.name)}</div>
          <div class="ellipsis" style={{ ...natural(t.body(30)), color: t.dim, ...line }}>{props.tag}</div>
          <div class="ellipsis" style={{ ...natural(t.body(32)), color: t.mute, paddingTop: 12, ...line }}>{props.sub}</div>
        </Column>
      </Row>
    </div>
  )
}

/**
 * The sting under the card: a two-note "bing-bong" on tune-in, a softer three-note descending figure before a break.
 * Sines with a 30 ms attack and an exponential release, rendered once into an AudioBuffer and mixed over the program
 * audio (no ducking). The AudioContext is made on the first sting, which always follows a remote press, so autoplay rules
 * (Chromium 71+) let it run. Stopped when the app is hidden: Web Audio isn't suspended with the page on webOS. Failures
 * are ignored: the card still shows. ponytail: synthesized stand-in for a real sting asset.
 */
export class StationSting {
  static readonly shared = new StationSting()

  private ctx: AudioContext | null = null
  private buffers: { tune: AudioBuffer; brk: AudioBuffer } | null = null
  private playing: AudioBufferSourceNode | null = null

  private constructor() {}

  play(kind: StationSting.Kind) {
    try {
      if (!this.ctx) {
        this.ctx = new AudioContext()
        document.addEventListener('visibilitychange', () => { if (document.hidden) this.stop() })
      }
      const ctx = this.ctx
      if (ctx.state === 'suspended') ctx.resume().catch(() => {})
      if (!this.buffers) this.buffers = {
        tune: render(ctx, [[880, 0.12], [660, 0.22]], 0.2),               // −14 dBFS
        brk: render(ctx, [[660, 0.14], [554, 0.14], [440, 0.28]], 0.11),  // −19 dBFS
      }
      this.stop()
      const s = ctx.createBufferSource()
      s.buffer = this.buffers[kind]
      s.connect(ctx.destination)
      s.onended = () => { if (this.playing === s) this.playing = null }
      s.start()
      this.playing = s
    } catch (e) {
      console.warn('sting', e)
    }
  }

  private stop() {
    const p = this.playing
    this.playing = null
    try { p?.stop() } catch { /* already ended */ }
  }
}

export namespace StationSting {
  export type Kind = 'tune' | 'brk'
}

/** (frequency Hz, seconds) notes back to back, each with a 30 ms attack and a release that lands at −43 dB. Time-based,
 *  so the context's own rate (48 kHz on most TVs) needs no resampling. */
function render(ctx: AudioContext, notes: [number, number][], gain: number): AudioBuffer {
  const sr = ctx.sampleRate
  const b = ctx.createBuffer(1, notes.reduce((n, [, dur]) => n + Math.floor(dur * sr), 0), sr)
  const out = b.getChannelData(0)
  let i = 0
  for (const [hz, dur] of notes) {
    for (let k = 0, n = Math.floor(dur * sr); k < n; k++) {
      const t = k / sr
      out[i++] = Math.sin(2 * Math.PI * hz * t) * Math.min(1, t / 0.03) * Math.exp(-5 * t / dur) * gain
    }
  }
  return b
}
