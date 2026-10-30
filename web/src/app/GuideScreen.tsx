// Port of android/app GuideScreen.kt: the guide. Preview window (top-left), info panel (top-right), time header and the
// looping listings grid (bottom), absolute on the 1920×1080 stage straight from the handoff's CSS.
import type { ComponentChildren, JSX, VNode } from 'preact'
import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks'
import { GuideGenerator } from '../core/GuideGenerator'
import { runCatchingNonCancel } from '../core/MediaServer'
import type { Channel, MediaItem, ProgramStatus, Slot } from '../core/Models'
import { Instant, now as instantNow } from '../core/Time'
import { Column, Row, natural, useNow } from './Anim'
import { AppModel, ChannelSlot, ProgramKey, useModel } from './AppModel'
import { AttractPanel } from './AttractMode'
import { BounceSaver } from './BounceSaver'
import { Platform, backgroundTap, onPoint, onTouch } from './Platform'
import type { RemoteCommand } from './Remote'
import { RemoteImage } from './RemoteImage'
import { RerunLoader } from './RerunLoader'
import { ScrambleView } from './Scramble'
import { at } from './Stage'
import { Clock, Para, lineSpacing } from './Text'
import { Fonts, TextStyle, Theme, channelColor, colorHex, hex, useTheme } from './Theme'

/** The guide. Shows the bouncing face instead while attract mode runs with one picked. */
export function GuideScreen() {
  const model = useModel()
  const t = useTheme()
  // On the first render, not in an effect: the grid reads the drift offset as it first renders, and an effect runs only
  // after that frame has painted, so the rows would show one frame at the stale drift (time since the guide last showed).
  const appeared = useRef(false)
  if (!appeared.current) { appeared.current = true; model.guideAppeared() }
  useEffect(() => () => model.attractReset(), [])   // off the guide: wake, stop the countdown
  // An overlay wakes it; closing one re-arms it (on a change only, not on the first render).
  const bare = model.overlay == null ? 1 : 0
  const seen = useRef(bare)
  useEffect(() => {
    if (seen.current === bare) return
    seen.current = bare
    model.attractReset()
  }, [bare])
  const face = BounceSaver.Face.entries.find(f => f === model.settings.screensaver)
  let content: ComponentChildren
  if (model.visibleChannels.length === 0) {
    const err = model.libraryError
    // A quick cache read at launch doesn't show it.
    content = model.libraryLoading ? <RerunLoader delay={1} style={{ ...at(0, 0, 1920, 1080), justifyContent: 'center' }} /> : (
      <Column gap={18} style={{ ...at(0, 0, 1920, 1080), alignItems: 'center', justifyContent: 'center' }}>
        <Para text={err ?? 'Nothing to schedule yet'}
          style={{ ...natural(t.body(32)), textAlign: 'center', color: err == null ? t.dim : t.accent, maxWidth: 1200 }} />
        <Txt s="Open Options › Settings › Rebuild to try again" style={t.body(26)} color={t.dim} />
      </Column>
    )
  } else if (model.attracting && face != null) {
    // ponytail: Swift's `.transition(.opacity)` here runs without an animation transaction, so the swap is instant; so is this.
    content = <BounceSaver face={face} />
  } else {
    content = <Periodic seconds={10}>{now => <GuideContent now={now} />}</Periodic>
  }
  // No background: RootView's is the same t.bg (one full-screen fill fewer per frame on a TV's GPU).
  return (
    <div style={at(0, 0, 1920, 1080)} {...backgroundTap(() => { if (model.attracting) model.attractReset() })}>
      {/* the bouncing logo has nothing else to tap */}
      {t.id === 'LATE' && (
        // Clipped to where the glow isn't transparent yet (0.7 of the 1000×640 radii around 380,300): the same pixels, a
        // third of the screen's fill instead of all of it.
        <div style={{ ...at(0, 0, 1080, 748), background: `radial-gradient(1000px 640px at 380px 300px, ${t.glow}, transparent 70%)` }} />
      )}
      {content}
      {t.id === 'MIDNIGHT' && model.settings.crtLines && (
        <div style={{ ...at(0, 0, 1920, 1080), background: crt(0.09, [0.62, 0.38]), pointerEvents: 'none' }} />
      )}
    </div>
  )
}

/** The attract crossfade (Swift `.animation(.easeInOut(duration: 0.8), value: attracting)`). Hidden once fully faded,
 *  where Kotlin unmounts: its clock never paints and its pills take no clicks. */
function crossfade(o: number): JSX.CSSProperties {
  return { opacity: o, visibility: o > 0 ? 'visible' : 'hidden', transition: 'opacity .8s ease-in-out, visibility .8s' }
}

function GuideContent(props: { now: Instant }) {
  const model = useModel()
  const gi = GuideInfo.make(model, props.now)
  if (gi == null) return null
  const a = model.attracting
  return (
    <>
      <InfoPanel gi={gi} style={crossfade(a ? 0 : 1)} />
      <AttractPanel ch={gi.ch} title={gi.title} style={crossfade(a ? 1 : 0)} />
      {/* The grid dims while attracting. A box with no size, so it never takes the info panel's clicks. */}
      <div style={{ position: 'absolute', left: 0, top: 0, opacity: a ? 0.7 : 1, transition: 'opacity .8s ease-in-out' }}>
        <TimeHeader now={props.now} ws={gi.ws} />
        <GuideGrid now={props.now} ws={gi.ws} gf={gi.key} />
      </div>
      <PreviewWindow gi={gi} />{/* last: the enlarged attract tube floats over the dimmed grid */}
    </>
  )
}

// MARK: - View model (port of the prototype vGuide's `gi`)

class GuideInfo {
  readonly ch: Channel
  readonly slot: Slot
  readonly st: ProgramStatus
  readonly cur: Slot
  readonly brk: boolean
  readonly bOn: boolean
  readonly trailer: MediaItem | null
  readonly next: Slot | null
  readonly ws: Instant
  readonly held: boolean
  /** `model.foregroundSerial`: back from the Home screen, the preview's stream must be fetched again. */
  readonly resume: number

  constructor(model: AppModel, readonly key: ProgramKey, pr: ChannelSlot, readonly now: Instant) {
    this.ch = pr.channel
    this.slot = pr.slot
    this.st = model.status(this.slot, now)
    this.cur = model.slotOnAir(this.ch, now) ?? this.slot
    this.brk = model.status(this.cur, now) === 'break'
    this.bOn = model.settings.trailerBreaks && this.ch.trailerBreaks
    this.trailer = this.brk && this.bOn ? model.breakTrailerCandidate(this.ch, this.cur) : null
    this.next = model.slotOnAir(this.ch, this.cur.end)
    this.ws = model.windowStart(now)
    this.held = model.remoteHeld
    this.resume = model.foregroundSerial
  }

  get live(): boolean { return this.st === 'live' || this.st === 'break' }
  get title(): string { return this.slot.item.displayTitle }
  get timeRange(): string { return Clock.range(this.slot.start, this.slot.end) }
  get elapsed(): number { return this.now - this.slot.start }
  get progress(): number { return this.live ? Math.max(0, Math.min(1, this.elapsed / this.slot.duration)) : 0 }
  get desc(): string { return this.slot.item.overview ?? '' }
  get statusText(): string {
    switch (this.st) {
      case 'live': return `${Math.trunc(this.elapsed / 60)} min in`
      case 'break': return (this.bOn ? 'Trailers until ' : 'Station break until ') + Clock.time(this.slot.end)
      case 'later': return `Starts in ${Math.ceil(-this.elapsed / 60)} min`
      case 'past': return 'Aired earlier'
    }
  }
  get statusLabel(): string {
    switch (this.st) {
      case 'live': return 'ON NOW'
      case 'break': return 'BREAK'
      case 'later': return 'LATER'
      case 'past': return 'ENDED'
    }
  }
  get pvKind(): string { return this.brk ? 'BREAK' : 'LIVE' }
  get pvKicker(): string { return this.brk ? (this.bOn ? 'Coming attractions' : 'Station break') : 'Now playing' }
  get pvTitle(): string {
    return this.brk ? (this.bOn ? this.trailer?.title ?? this.ch.name : this.ch.name) : this.cur.item.displayTitle
  }
  get pvSub(): string {
    if (this.brk) {
      const year = this.trailer?.year
      const what = this.bOn ? 'Trailer' + (year != null ? `, ${year}` : '') : `Channel ${this.ch.number}`
      return what + (this.next != null ? ` · ${this.next.item.displayTitle} at ${Clock.time(this.next.start)}` : '')
    }
    return this.cur.item.kind === 'movie' ? (this.cur.item.year != null ? String(this.cur.item.year) : '') : `“${this.cur.item.title}”`
  }
  get selLabel(): string { return this.live ? 'Watch live' : 'Details' }
  /** Play/Pause: a program that hasn't started yet has no beginning to go back to. */
  get playLabel(): string { return this.st === 'later' ? 'Watch now' : 'Start from the beginning' }
  get backLabel(): string { return this.held ? 'Let it drift' : 'Options' }
  /** Held: the Back button lets go. Drifting: Back exits to Home, so the D-pad's left is the way into Options. */
  get backKey(): string { return this.held ? 'chevron.backward' : 'circle.grid.cross.left.filled' }
  /** Changes when the preview picture should reload (focused channel, program or break state). */
  get previewKey(): string { return `${this.ch.id}|${this.cur.id}|${this.brk}|${this.resume}` }

  static make(model: AppModel, now: Instant): GuideInfo | null {
    const key = model.guideKey(now)
    if (key == null) return null
    const pr = model.program(key)
    if (pr == null) return null
    return new GuideInfo(model, key, pr, now)
  }

  static fit(n: number, a: number, b: number, c: number): number { return n <= 16 ? a : n <= 26 ? b : c }
}

// Design handoff literals that are not theme tokens (handoff README §1). Midnight Movie cells have no live fill: its
// liveCell/focusFill equal the base colors, so the shared fill degenerates to the flat cell the prototype shows.
const PV_TEXT = hex('#e6d8c4'); const LIVE_CELL = hex('#28211c'); const FOCUS_FILL = hex('#e2d2bc')
const RING = hex('#2e2722'); const BEVEL_LIGHT = hex('#6e3434'); const BEVEL_DARK = hex('#050303')
const BOX = hex('#070505'); const SLASH = hex('#7a4040'); const WATERMARK = hex('#d8c2bd')
const LIVE_CHIP = hex('#d7261e'); const BREAK_CHIP = hex('#c2560c'); const BREAK_BADGE = hex('#ffa94d')
const cellBg = (t: Theme) => t.id === 'MIDNIGHT' ? t.panel2 : t.panel
const liveCell = (t: Theme) => t.id === 'MIDNIGHT' ? t.panel2 : LIVE_CELL
const focusFill = (t: Theme) => t.id === 'MIDNIGHT' ? t.focusBg : FOCUS_FILL
/** Kotlin's `Theme.bevelLight` / `bevelDark` / `breakBadge` (theme-independent literals), shared with WatchScreen. */
export const bevelLight = BEVEL_LIGHT
export const bevelDark = BEVEL_DARK
export const breakBadge = BREAK_BADGE

// MARK: - Preview window

/** Android's "Remove animations" as the web says it (Chromium 74+; earlier TVs never reduce). */
const reduceMotion = typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches

const FILL: JSX.CSSProperties = { position: 'absolute', left: 0, top: 0, right: 0, bottom: 0 }
/** Legibility over real art/video only; the bare hue gradient renders as the design mockup. */
const SCRIM = 'linear-gradient(transparent 45%, rgba(0,0,0,0.6))'

function PreviewWindow(props: { gi: GuideInfo }) {
  const { gi } = props
  const model = useModel()
  const t = useTheme()
  const mm = t.id === 'MIDNIGHT'
  const a = model.attracting
  // ponytail: the attract tube is hard-coded (Late Show 640×480 at 80,72, ending above the time header at y 566;
  // Midnight Movie 824×464 at 80,56, ending at x 904 / y 520 before its time bar); no handoff spec for it.
  const w = mm ? (a ? 824 : 800) : (a ? 640 : 600)
  const h = mm ? (a ? 464 : 450) : (a ? 480 : 450)
  // A theme switch resizes the tube at once; only attract mode grows it (0.8 s, like the crossfade).
  const lastMm = useRef(mm)
  const switched = lastMm.current !== mm
  lastMm.current = mm

  // One muted <video> for the window's life (the Swift AVPlayer), released before every reload and with the window: a TV
  // decodes one video at a time, and the watch player needs it next.
  const video = useRef<HTMLVideoElement>(null)
  const detach = useRef<(() => void) | null>(null)
  const [ready, setReady] = useState(false)   // AVPlayer timeControlStatus == .playing
  const notReady = () => setReady(false)
  // tvOS: only a video that is actually playing holds off the screensaver, so a BREAK card, channel 99 or a stalled load
  // lets the TV dim and sleep (Samsung; LG has no API). The player screen holds it the same way (WatchScreen).
  useEffect(() => {
    if (!ready) return
    Platform.setScreenSaver(false)
    return () => Platform.setScreenSaver(true)
  }, [ready])
  const release = () => {
    detach.current?.()
    detach.current = null
    const v = video.current
    if (v) { v.pause(); v.removeAttribute('src'); v.load() }
  }
  useEffect(() => {
    // AVPlayer pauses itself when the app leaves for the Home screen; a <video> has to be told. Coming back bumps
    // foregroundSerial, which reloads the stream (the model stopped the server's session).
    const away = () => { if (document.hidden) video.current?.pause() }
    document.addEventListener('visibilitychange', away)
    return () => {
      document.removeEventListener('visibilitychange', away)
      release()
      model.server?.stopPlayback('preview').catch(() => {})
    }
  }, [])
  useEffect(() => {   // a new key cancels the previous load (Swift `load?.cancel()`)
    if (gi.ch.isScrambled) { release(); setReady(false); return }   // nothing to stream
    let active = true
    const load = async () => {
      // A TV suspends the app at its Home screen, so this never gets further there; foregroundSerial reloads on return.
      if (model.backgrounded) return
      setReady(false)
      release()
      const now = instantNow()
      if (gi.brk) return
      const server = model.server
      if (server == null) return
      const air = model.slotOnAir(gi.ch, now)
      if (air == null) return
      const offset = Math.min(now - air.start, air.item.runtime)
      const src = await runCatchingNonCancel(() => server.playbackSource(air.item, offset, 'preview'))
      const v = video.current
      if (src == null || !active || model.backgrounded || v == null) return
      const stream = attachStream(v, src.url)
      // The server started a transcode at the offset itself, or the stream starts at 0 and the player seeks.
      const seek = () => { v.currentTime = offset }
      if (!src.startsAtOffset) v.addEventListener('loadedmetadata', seek)
      detach.current = () => { v.removeEventListener('loadedmetadata', seek); stream() }
      v.play().catch(() => {})   // interrupted by the next load or the release: nothing to do
    }
    const dwell = setTimeout(() => { void load() }, 1500)   // the dwell (a debounce): each load starts a server transcode
    return () => { active = false; clearTimeout(dwell) }
  }, [gi.previewKey])

  // Shown with `visibility` once it plays, never opacity: a TV draws video on its own plane, under the page.
  // `object-fit: cover` is AVPlayerLayer's `.resizeAspectFill` where the TV honours it (else the picture letterboxes).
  const player = (
    <video ref={video} class="rg-preview" muted playsInline onPlaying={() => setReady(true)} onWaiting={notReady} onPause={notReady}
      onEmptied={notReady} onEnded={notReady} onError={notReady}
      style={{ position: 'absolute', left: 0, top: 0, width: '100%', height: '100%', objectFit: 'cover', visibility: ready ? 'visible' : 'hidden' }} />
  )
  // Late Show: rounded, ringed and glowing. The shadows are the box's own (the video is a layer of its own, so its frames
  // never re-raster them); the ring is the 2 px just outside the edge. Midnight Movie: a 6 px bevel, light on top/left,
  // dark on bottom/right, mitred, the picture inside it.
  const frame: JSX.CSSProperties = mm
    ? { borderStyle: 'solid', borderWidth: 6, borderColor: `${BEVEL_LIGHT} ${BEVEL_DARK} ${BEVEL_DARK} ${BEVEL_LIGHT}` }
    : { borderRadius: 44, background: '#000', boxShadow: tubeShadow(t) }
  return (
    // The picture: watch it (or its details, like the Select pill). The video keeps its place in this tree whatever the
    // theme, so a theme switch doesn't drop the stream.
    <div style={{ ...at(80, mm ? 56 : 72, w, h), ...frame, transition: reduceMotion || switched ? 'none' : 'width .8s ease-in-out, height .8s ease-in-out' }}
      class="hov" {...onTouch(() => model.guideHint('select'))}>
      <Picture gi={gi} player={player} ready={ready} w={w} h={h} />
      {mm ? <MidnightTube gi={gi} w={w - 12} /> : <LateTube gi={gi} w={w} />}
    </div>
  )
}

/** The Late Show tube's text: LIVE/BREAK pill top-left, kicker, title and sub bottom-left. */
function LateTube(props: { gi: GuideInfo; w: number }) {
  const { gi } = props
  const t = useTheme()
  const pvSize = GuideInfo.fit(gi.pvTitle.length, 60, 50, 40)
  return (
    <div style={{ ...FILL, borderRadius: 44, overflow: 'hidden' }}>
      <div style={{
        position: 'absolute', left: 28, top: 26, ...natural(t.body(24, 700)), letterSpacing: '0.1em', color: t.accentInk,
        background: gi.brk ? t.focusBg : t.accent, borderRadius: 999, padding: '6px 14px',
      }}>{gi.pvKind}</div>
      <div style={{ position: 'absolute', left: 36, right: 36, bottom: 34, display: 'flex', flexDirection: 'column' }}>
        <Txt s={gi.pvKicker.toUpperCase()} style={{ ...t.body(24, 600), letterSpacing: '0.14em' }} color={PV_TEXT} />
        {/* 8 apart (the title's padding: its margins are the trim) */}
        <Para text={gi.pvTitle} style={{
          ...t.heading(pvSize), ...lineHeightTrim(pvSize), letterSpacing: '-0.02em', color: t.ink, paddingTop: 8, maxWidth: props.w - 72,
        }} />
        <Para text={gi.pvSub} style={{ ...natural(t.body(26)), color: PV_TEXT, marginTop: 8, maxWidth: props.w - 72 }} />
      </div>
    </div>
  )
}

/** The Midnight Movie set's text: channel + chip along the top, title centered, watermark. `iw`: inside the bevel. */
function MidnightTube(props: { gi: GuideInfo; w: number }) {
  const { gi } = props
  const t = useTheme()
  const pvSize = GuideInfo.fit(gi.pvTitle.length, 84, 64, 50)
  return (
    <div style={{ ...FILL, overflow: 'hidden' }}>
      <Row gap={12} style={{ position: 'absolute', left: 24, top: 22, alignItems: 'center' }}>
        {/* frameHeight(34): a 34 tall line, the text's natural line centered in it */}
        <div style={{ ...t.body(34, 800), lineHeight: '34px', height: 46, padding: '6px 12px', color: t.accentInk, background: t.accent }}>
          {gi.ch.number}
        </div>
        <Txt s={gi.ch.name.toUpperCase()} style={{ ...t.body(30, 700), letterSpacing: '0.06em', textShadow: '0 2px 0 rgba(0,0,0,0.5)' }} color={t.ink} />
      </Row>
      <div style={{
        position: 'absolute', right: 24, top: 24, ...t.body(26, 800), letterSpacing: '0.12em', lineHeight: '26px', height: 38,
        padding: '6px 14px', color: '#fff', background: gi.brk ? BREAK_CHIP : LIVE_CHIP,
      }}>{gi.pvKind}</div>
      <Column gap={12} style={{
        position: 'absolute', left: 48, top: 80, right: 48, bottom: 70, alignItems: 'center', justifyContent: 'center', textAlign: 'center',
      }}>
        <Txt s={gi.pvKicker.toUpperCase()} style={{ ...t.body(26, 700), letterSpacing: '0.22em' }} color={t.accent} />
        <Para text={gi.pvTitle.toUpperCase()} style={{
          ...t.heading(pvSize), ...lineHeightTrim(pvSize, 0.95), color: t.ink, textShadow: '0 4px 0 rgba(0,0,0,0.45)', maxWidth: props.w - 96,
        }} />
        <Para text={gi.pvSub} style={{ ...natural(t.body(30)), color: t.mute, maxWidth: props.w - 96 }} />
      </Column>
      <div style={{
        position: 'absolute', right: 24, bottom: 18, fontFamily: Fonts.barlow, fontWeight: 800, fontStyle: 'italic', fontSize: 28,
        lineHeight: 1.2, letterSpacing: '0.04em', color: withAlpha(WATERMARK, 0.85),
      }}>RERUN GUIDE</div>
    </div>
  )
}

/**
 * Hue gradient → backdrop art (or channel 99's scramble) → muted live video, in that order as each becomes available,
 * then the CRT lines. `player` is the window's <video>, always at the same place here, hidden until it plays.
 */
function Picture(props: { gi: GuideInfo; player: VNode; ready: boolean; w: number; h: number }) {
  const { gi, ready } = props
  const model = useModel()
  const t = useTheme()
  const mm = t.id === 'MIDNIGHT'
  const r = mm ? 0 : 44
  const hue = gi.ch.hue
  const gradient = `radial-gradient(100% 100% at 25% 15%, ${channelColor(hue, 0.5, 0.1)}, ${channelColor(hue, 0.24, 0.05)} 55%, ${channelColor(hue, 0.13, 0.02)})`
  const url = !gi.ch.isScrambled && !gi.brk ? model.server?.backdropURL(gi.cur.item) : null
  return (
    <>
      {/* Clipped (nothing but art in here: a TV's video plane ignores clips, so the video stays outside). */}
      <div style={{ ...FILL, borderRadius: r, overflow: 'hidden', background: gradient }}>
        {gi.ch.isScrambled
          ? <ScrambleView seed={Math.floor(gi.cur.start / 60)} style={FILL} />
          : url != null && <RemoteImage url={url} style={FILL} overlay={SCRIM} />}
      </div>
      {props.player}
      {ready && <div style={{ ...FILL, borderRadius: r, background: SCRIM }} />}
      {ready && !mm && corners(t, props.w, props.h)}
      {model.settings.crtLines && (
        <div style={{ ...FILL, borderRadius: r, background: mm ? crt(0.24, null) : crt(0.16, [0.55, 0.5]) }} />
      )}
    </>
  )
}

/** The Late Show tube's shadows (SwiftUI radius = sigma: CSS blur 2σ) and its ring, the 2 px just outside its edge. */
function tubeShadow(t: Theme): string {
  return `0 0 0 2px ${RING}, 0 40px 90px rgba(0,0,0,0.55), 0 0 160px ${withAlpha(t.accent, 0.16)}`
}

/**
 * The Late Show tube's rounded corners over its video: a TV draws video on its own plane, where neither a rounded clip nor
 * opacity reaches, so the four corners outside the 44 radius are painted over with what is behind them there: the
 * background lit by the screen's glow (flat per corner: it changes by under a level across 44 px), then a copy of the
 * tube's shadows and ring, clipped to the corner (a box-shadow paints only outside its rounded box, so exactly there).
 * `w`, `h`: the tube's size; it sits at (80, 72).
 */
function corners(t: Theme, w: number, h: number): VNode[] {
  const r = 44
  const c = (sx: 'left' | 'right', sy: 'top' | 'bottom', center: string) => {
    const x = 80 + (sx === 'left' ? r / 2 : w - r / 2)
    const y = 72 + (sy === 'top' ? r / 2 : h - r / 2)
    return (
      <div key={sx + sy} style={{ position: 'absolute', [sx]: 0, [sy]: 0, width: r, height: r, overflow: 'hidden' }}>
        <div style={{ ...FILL, background: `radial-gradient(circle at ${center}, transparent ${r - 0.5}px, ${glowAt(t, x, y)} ${r + 0.5}px)` }} />
        <div style={{ position: 'absolute', [sx]: 0, [sy]: 0, width: w, height: h, borderRadius: r, boxShadow: tubeShadow(t) }} />
      </div>
    )
  }
  return [c('left', 'top', '100% 100%'), c('right', 'top', '0 100%'), c('left', 'bottom', '100% 0'), c('right', 'bottom', '0 0')]
}

/** `t.bg` under GuideScreen's Late Show glow (radial-gradient(1000px 640px at 380px 300px, glow, transparent 70%)) at
 *  stage point (x, y). */
function glowAt(t: Theme, x: number, y: number): string {
  const [gr, gg, gb, ga] = (t.glow.match(/[\d.]+/g) ?? []).map(Number)
  const k = ga * Math.max(0, 1 - Math.sqrt(Math.pow((x - 380) / 1000, 2) + Math.pow((y - 300) / 640, 2)) / 0.7)
  const n = parseInt(t.bg.slice(1), 16)
  const mix = (c: number, g: number) => Math.round(c * (1 - k) + g * k)
  return `rgb(${mix(n >> 16, gr)},${mix((n >> 8) & 255, gg)},${mix(n & 255, gb)})`
}

/**
 * Scanlines (2 px on, 2 px off at alpha `lines`) under an optional vignette ([start fraction, alpha]: SwiftUI's
 * EllipticalGradient with end radius 0.71 of each side), as one CSS background: drawn once, a static layer.
 */
function crt(lines: number, vignette: [number, number] | null): string {
  const c = `rgba(0,0,0,${lines})`
  const scan = `repeating-linear-gradient(${c} 0, ${c} 2px, transparent 2px, transparent 4px)`
  if (vignette == null) return scan
  return `radial-gradient(71% 71% at 50% 50%, rgba(0,0,0,0) ${vignette[0] * 100}%, rgba(0,0,0,${vignette[1]})), ${scan}`
}

/** Kotlin `Color.copy(alpha = a)` for a "#rrggbb" color. */
function withAlpha(c: string, a: number): string {
  const n = parseInt(c.slice(1), 16)
  return `rgba(${n >> 16},${(n >> 8) & 255},${n & 255},${a})`
}

interface HlsPlayer { loadSource(url: string): void; attachMedia(media: HTMLMediaElement): void; destroy(): void }
declare global {
  interface Window {
    /** hls.js, which only the browser build's page loads (development). */
    Hls?: { new (): HlsPlayer; isSupported(): boolean }
  }
}

/**
 * Plays `url` in `video`; returns what detaches it (run before `pause(); removeAttribute('src'); load()`). TVs play HLS
 * natively. The browser build (development only) hands an HLS stream to hls.js wherever it runs: recent desktop Chrome
 * answers canPlayType 'maybe' for HLS and then fails the servers' streams (MEDIA_ERR_SRC_NOT_SUPPORTED), so its answer
 * can't decide. Shared with WatchScreen.
 */
export function attachStream(video: HTMLVideoElement, url: string): () => void {
  const gap = jumpStartGap(video)
  const Hls = __DEV__ ? window.Hls : undefined
  if (Hls != null && /\.m3u8(\?|$)/i.test(url) && Hls.isSupported()) {
    const hls = new Hls()
    hls.loadSource(url)
    hls.attachMedia(video)
    return () => { gap(); hls.destroy() }
  }
  video.src = url
  return gap
}

/**
 * Until the video can play, jumps a hole at the start of what it buffered: Emby/Jellyfin's HLS begins at the first
 * keyframe after the offset the player seeks to (seen up to 8 s on), which hls.js skips only up to 2 s and a TV's own
 * player maybe not at all, so the video would wait at HAVE_METADATA for good. ExoPlayer and AVPlayer start at that
 * keyframe by themselves. Returns what stops it.
 */
function jumpStartGap(video: HTMLVideoElement): () => void {
  const id = setInterval(() => {
    if (video.readyState >= 3) { clearInterval(id); return }   // HAVE_FUTURE_DATA: it plays from here on
    const b = video.buffered
    const hole = b.length > 0 ? b.start(0) - video.currentTime : 0
    if (hole > 0 && hole < 15) video.currentTime = b.start(0) + 0.1
  }, 500)
  return () => clearInterval(id)
}

// MARK: - Info panel

function InfoPanel(props: { gi: GuideInfo; style: JSX.CSSProperties }) {
  return useTheme().id === 'MIDNIGHT' ? <InfoMidnight {...props} /> : <InfoLate {...props} />
}

function InfoLate(props: { gi: GuideInfo; style: JSX.CSSProperties }) {
  const { gi } = props
  const model = useModel()
  const t = useTheme()
  const titleSize = GuideInfo.fit(gi.title.length, 92, 76, 60)
  const f = model.guideFilter
  const profile = model.profile
  return (
    <div style={{ ...at(744, 64, 1096, 470), display: 'flex', flexDirection: 'column', ...props.style }}>
      <div style={{ display: 'flex', alignItems: 'center' }}>
        <Row gap={12} style={{ alignItems: 'center' }}>
          <div style={{ width: 14, height: 14, borderRadius: 999, background: t.accent }} />
          <Txt s={f === 'all' ? 'Rerun Guide' : `Rerun Guide · ${f === 'movies' ? 'Movies' : 'TV Shows'}`} style={t.heading(30, 600)} color={t.ink} />
        </Row>
        <div style={{ flex: 1 }} />
        <Row gap={28} style={{ alignItems: 'center' }}>
          {/* The profile chip opens the menu (it leads with Switch profile). */}
          <Row gap={12} class="hov" style={{ alignItems: 'center' }} {...onTouch(() => model.openOptions())}>
            <div style={{ width: 38, height: 38, borderRadius: 999, background: colorHex(profile.colorHex), display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <Txt s={profile.initial} style={t.body(20, 700)} color={t.accentInk} />
            </div>
            <Txt s={profile.name} style={t.body(26)} color={t.mute} />
          </Row>
          <EverySecond>{d => (
            <Row gap={8} style={{ alignItems: 'center' }}>
              <Txt s={Clock.time(d)} style={t.heading(30, 500)} color={t.ink} />
              <Txt s={Clock.ap(d)} style={t.heading(30, 500)} color={t.dim} />
            </Row>
          )}</EverySecond>
        </Row>
      </div>
      <div style={{ flex: 1 }} />
      <ChannelLine ch={gi.ch} />
      <Para text={gi.title} style={{
        ...t.heading(titleSize), ...lineHeightTrim(titleSize), letterSpacing: '-0.025em', color: t.ink, paddingTop: 12, maxWidth: 1096,
      }} />
      <Txt s={gi.slot.item.infoLine} style={{ ...t.body(32), marginTop: 12 }} color={t.mute} />
      <Row gap={20} style={{ alignItems: 'center', marginTop: 20 }}>
        <div style={{ width: 300, height: 6, borderRadius: 999, background: t.line }}>
          <div style={{ width: 300 * gi.progress, height: 6, borderRadius: 999, background: t.accent }} />
        </div>
        <Txt s={gi.timeRange} style={t.body(26, 600)} color={t.ink} />
        <Txt s={gi.statusText} style={t.body(26)} color={t.dim} />
      </Row>
      <Para text={gi.desc} maxLines={2} style={{ ...lineSpacing(t.body(29), 6), color: t.mute, paddingTop: 16, maxWidth: 1020 }} />
      {/* A legend of remote buttons, not focusable controls: the grid is the only thing focus moves through. */}
      <Row gap={30} style={{ marginTop: 22 }}>{hints(gi)}</Row>
    </div>
  )
}

/** The boxed Midnight Movie panel: channel line + profile chip, title, status badge, square progress bar, boxed-key hints. */
function InfoMidnight(props: { gi: GuideInfo; style: JSX.CSSProperties }) {
  const { gi } = props
  const model = useModel()
  const t = useTheme()
  const titleSize = GuideInfo.fit(gi.title.length, 70, 58, 48)
  const badge = gi.st === 'live' ? t.accent : gi.st === 'break' ? BREAK_BADGE : t.dim
  const s = t.body(30, 600)
  return (
    <div style={{
      ...at(904, 56, 936, 450), display: 'flex', flexDirection: 'column', ...bleedBackground(BOX, 0, 56, 80),
      border: `3px solid ${t.line}`, padding: '28px 36px 26px', ...props.style,   // 3px border + 28/36/26
    }}>
      <div style={{ display: 'flex', alignItems: 'center' }}>
        <ChannelLine ch={gi.ch} />
        <div style={{ flex: 1 }} />
        <Row gap={10} class="hov" style={{ alignItems: 'center' }} {...onTouch(() => model.openOptions())}>{/* as in Late Show */}
          <div style={{ width: 14, height: 14, background: colorHex(model.profile.colorHex) }} />
          <Txt s={model.profile.name.toUpperCase()} style={{ ...t.body(28, 600), letterSpacing: '0.08em' }} color={t.dim} />
        </Row>
      </div>
      <Para text={gi.title} style={{ ...t.heading(titleSize, 700), ...lineHeightTrim(titleSize), color: t.ink, paddingTop: 14, maxWidth: 858 }} />
      <Txt s={gi.slot.item.infoLine} style={{ ...t.body(32, 600), marginTop: 10 }} color={t.accent} />
      <Row gap={18} style={{ alignItems: 'center', marginTop: 20 }}>
        <Txt s={gi.timeRange} style={s} color={t.ink} />
        <Txt s={gi.statusLabel} style={{ ...t.body(24, 800), letterSpacing: '0.1em', background: badge, padding: '4px 12px 3px' }} color={t.accentInk} />
        <Txt s={gi.statusText} style={s} color={t.dim} />
      </Row>
      <div style={{ marginTop: 14, height: 10, flex: 'none', background: t.panel2 }}>
        <div style={{ height: '100%', width: `${gi.progress * 100}%`, background: t.accent }} />
      </div>
      <Para text={gi.desc} maxLines={2} style={{ ...lineSpacing(t.body(29), 3), color: t.mute, paddingTop: 18, maxWidth: 858 }} />
      <div style={{ flex: 1 }} />
      <Row gap={28}>{hints(gi)}</Row>
    </div>
  )
}

function hints(gi: GuideInfo): VNode[] {
  return [
    <Hint key="ok" symbol="button.programmable" label={gi.selLabel} cmd="select" />,
    <Hint key="play" symbol="playpause.fill" label={gi.playLabel} cmd="play" />,
    <Hint key="back" symbol={gi.backKey} label={gi.backLabel} cmd="back" />,
  ]
}

function Hint(props: { symbol: string; label: string; cmd: RemoteCommand }) {
  const model = useModel()
  return <KeyHint symbol={props.symbol} label={props.label} action={() => model.guideHint(props.cmd)} />
}

/**
 * A remote key cap (the button's glyph in a box) and what it does here. Glyphs, not names. A click (the LG Magic Remote's
 * pointer) runs `action`, what the button does. LG's remote has no Play/Pause key, so there that pill is a plain button
 * for the pointer, with no key cap to look for on the remote. The guide's info panel (for the program the panel shows) and
 * the watch screen's break card.
 */
export function KeyHint(props: { symbol: string; label: string; action: () => void }) {
  const t = useTheme()
  const mm = t.id === 'MIDNIGHT'
  if (Platform.kind === 'webos' && props.symbol === 'playpause.fill') return (   // the key cap's box (height, border), stretched round the label
    <div style={{
      height: mm ? 38 : 34, margin: -1, padding: '0 12px', border: `2px solid ${mm ? t.dim : t.line}`, borderRadius: mm ? 0 : 7,
      display: 'flex', alignItems: 'center',
    }} class="hov" {...onTouch(props.action)}>
      <Txt s={props.label} style={t.body(26, mm ? 600 : 500)} color={mm ? t.dim : t.mute} />
    </div>
  )
  return (
    <Row gap={mm ? 10 : 12} class="hov" style={{ alignItems: 'center' }} {...onTouch(props.action)}>
      {/* Compose strokes the 46×32 (36) box on its edge, half outside: a 2 px border on a box 1 px bigger each side. */}
      <div style={{
        width: 48, height: mm ? 38 : 34, margin: -1, border: `2px solid ${mm ? t.dim : t.line}`, borderRadius: mm ? 0 : 7,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}>
        <Glyph name={props.symbol} s={mm ? 24 : 20} color={mm ? t.ink : t.dim} />
      </div>
      <Txt s={props.label} style={t.body(26, mm ? 600 : 500)} color={mm ? t.dim : t.mute} />
    </Row>
  )
}

/**
 * The hint caps' symbols, drawn at about an SF Symbol's size for font size `s` (bold), (1.5·s)×s, in a unit square
 * centered in it (the viewBox): the TV remote's OK (the ring and its center button), Play/Pause, Back, and the D-pad's
 * left. Measured off Apple TV screenshots (SF Symbols, bold, 20 and 24 pt).
 */
function Glyph(props: { name: string; s: number; color: string }) {
  let body: VNode | null = null
  switch (props.name) {
    case 'button.programmable':
      body = <>
        <circle cx={0.5} cy={0.5} r={0.45} fill="none" stroke="currentColor" stroke-width={0.12} />
        <circle cx={0.5} cy={0.5} r={0.31} fill="currentColor" />
      </>
      break
    case 'playpause.fill':
      body = <>
        {/* filled, then stroked: rounded corners */}
        <path d="M-.24 .12L-.24 .88L.43 .5Z" fill="currentColor" stroke="currentColor" stroke-width={0.06} stroke-linejoin="round" />
        <rect x={0.58} y={0.09} width={0.29} height={0.82} rx={0.05} fill="currentColor" />
        <rect x={1} y={0.09} width={0.29} height={0.82} rx={0.05} fill="currentColor" />
      </>
      break
    case 'chevron.backward':
      body = <path d="M.61 .16L.25 .51L.61 .86" fill="none" stroke="currentColor" stroke-width={0.16} stroke-linecap="round" stroke-linejoin="round" />
      break
    case 'circle.grid.cross.left.filled':
      body = <>
        <circle cx={0.55} cy={0.1} r={0.175} fill="none" stroke="currentColor" stroke-width={0.1} />
        <circle cx={0.9} cy={0.48} r={0.175} fill="none" stroke="currentColor" stroke-width={0.1} />
        <circle cx={0.55} cy={0.875} r={0.175} fill="none" stroke="currentColor" stroke-width={0.1} />
        <circle cx={0.2} cy={0.5} r={0.225} fill="currentColor" />
      </>
      break
  }
  // Drawn past the box where the symbol is (Play/Pause's bars), as the Compose Canvas does.
  return (
    <svg width={props.s * 1.5} height={props.s} viewBox="-0.25 0 1.5 1" style={{ display: 'block', overflow: 'visible', color: props.color }}>
      {body}
    </svg>
  )
}

/** The number pill, name and tag of a channel: the info panel's channel line, reused by the attract panel. */
export function ChannelLine(props: { ch: Channel }) {
  const t = useTheme()
  const ch = props.ch
  if (t.id === 'MIDNIGHT') {
    const s = { ...t.body(28, 600), letterSpacing: '0.08em' }
    return (
      <Row gap={14} style={{ alignItems: 'center' }}>
        <Txt s={String(ch.number)} style={{ ...t.body(28, 800), letterSpacing: '0.08em' }} color={t.accent} />
        <Txt s={ch.name.toUpperCase()} style={s} color={t.ink} />
        <Txt s="/" style={s} color={SLASH} />
        <Txt s={ch.tag.toUpperCase()} style={s} color={t.dim} />
      </Row>
    )
  }
  return (
    <Row gap={14} style={{ alignItems: 'center' }}>
      <Txt s={String(ch.number)} style={{
        ...t.body(28, 700), fontFeatureSettings: '"tnum"', background: t.accent, borderRadius: 999, padding: '3px 14px',
      }} color={t.accentInk} />
      <Txt s={ch.name} style={t.body(28, 600)} color={t.ink} />
      <Txt s={'· ' + ch.tag} style={t.body(28)} color={t.mute} />
    </Row>
  )
}

/**
 * Re-renders its content once every `seconds`, on wall-clock boundaries, with the boundary's time (the Swift
 * `TimelineView(.periodic(from: .timelineOrigin, by:))`). Only this component re-renders on a tick.
 */
export function Periodic(props: { seconds: number; children: (now: Instant) => ComponentChildren }) {
  const s = props.seconds
  const now = useNow(s * 1000, true)
  return <>{props.children(Math.floor(now / s) * s)}</>
}

/** Re-renders its content once a second (the clock). */
export function EverySecond(props: { children: (now: Instant) => ComponentChildren }) {
  return <Periodic seconds={1}>{props.children}</Periodic>
}

// MARK: - Time header

function TimeHeader(props: { now: Instant; ws: Instant }) {
  const labels = [0, 1, 2].map(i => props.ws + i * GuideGenerator.blockLength)
  return useTheme().id === 'MIDNIGHT' ? <TimeBar labels={labels} /> : <LateHeader now={props.now} ws={props.ws} labels={labels} />
}

/** Midnight Movie: a solid bar, date + clock in the channel column, one labelled cell per half hour; no now marker. */
function TimeBar(props: { labels: Instant[] }) {
  const t = useTheme()
  return (
    <Row gap={4} style={at(80, 528, 1760, 60)}>
      <div style={{ width: 300, height: 60, ...bleedBackground(t.accent, 80), display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <EverySecond>{d => (
          <Row gap={10} style={{ alignItems: 'center' }}>
            <Txt s={Clock.date(d)} style={{ ...t.body(26, 800), letterSpacing: '0.06em' }} color={t.accentInk} />
            <Txt s={Clock.time(d)} style={t.body(34, 800)} color={t.accentInk} />
            <Txt s={Clock.ap(d)} style={t.body(24, 800)} color={t.accentInk} />
          </Row>
        )}</EverySecond>
      </div>
      <div style={{ width: 1456, height: 60, ...bleedBackground(BOX, 0, 0, 80), display: 'flex' }}>
        {props.labels.map(d => (
          <div style={{ flex: 1, position: 'relative' }}>
            <Row gap={8} style={{ height: '100%', paddingLeft: 26, alignItems: 'center' }}>
              <Txt s={Clock.time(d)} style={t.body(32, 700)} color={t.accent} />
              <Txt s={Clock.ap(d)} style={t.body(24, 700)} color={t.accent} />
            </Row>
            <div style={{ position: 'absolute', left: 0, top: 0, width: 4, height: '100%', background: t.bg }} />
          </div>
        ))}
      </div>
    </Row>
  )
}

function LateHeader(props: { now: Instant; ws: Instant; labels: Instant[] }) {
  const model = useModel()
  const t = useTheme()
  const track = 1420
  const nowPct = (props.now - props.ws) / AppModel.windowSpan
  const live = model.windowOffset === 0
  const s = t.body(26, 600)
  return (
    <>
      <div style={at(420, 566, track, 40)}>
        {/* Keyed by time (Swift `ForEach(labels, id: \.self)`): a label keeps its fade as the window shifts, and a new one
            appears at its final opacity. */}
        {props.labels.map((d, i) => {
          // A column label the now pill would sit on fades out instead of colliding: from ~6 minutes before its time
          // until the pill (85 wide at most) clears the label's right edge (117 past its time), ~10 minutes after.
          const x = i / 3 * track
          const nearNow = live && nowPct * track - x >= -95 && nowPct * track - x < 170
          return (
            <Row key={d} gap={6} style={{
              position: 'absolute', left: x + 6, top: 4, alignItems: 'center', opacity: nearNow ? 0 : 1, transition: 'opacity .4s ease-in-out',
            }}>
              <Txt s={Clock.time(d)} style={s} color={t.dim} />
              <Txt s={Clock.ap(d)} style={s} color={t.dim} />
            </Row>
          )
        })}
        {live && (
          <div style={{ position: 'absolute', left: nowPct * track - 100, top: 0, width: 200, display: 'flex', justifyContent: 'center' }}>
            <EverySecond>{d => (
              <Txt s={Clock.time(d)} style={{ ...t.body(24, 700), background: t.accent, borderRadius: 999, padding: '5px 14px' }} color={t.accentInk} />
            )}</EverySecond>
          </div>
        )}
      </div>
      {live && <div style={{ ...at(420 + nowPct * track - 1, 612, 2, 428), background: `linear-gradient(${t.accent}, transparent)` }} />}
    </>
  )
}

// MARK: - Grid

/** The rows in view and where they sit: the window starts one row above the row `raw` lands in. */
interface RowWindow { readonly first: number; readonly rowBase: number; readonly top: number }
const RowWindow = {
  of(raw: number, half: number, pitch: number): RowWindow {
    // raw = base + m with m in 0 ..< half.
    const base = Math.floor(raw / half) * half
    const first = Math.trunc((raw - base) / pitch) - 1
    return { first, rowBase: Math.round(base / pitch), top: first * pitch + base }
  },
}

/** Kotlin `CubicBezierEasing(.39, .575, .565, 1)` (ease-out sine): the time fraction → progress (Newton's method on the curve's x). */
function heldEasing(x: number): number {
  const x1 = 0.39; const y1 = 0.575; const x2 = 0.565; const y2 = 1
  const bez = (t: number, a: number, b: number) => 3 * a * t * (1 - t) * (1 - t) + 3 * b * t * t * (1 - t) + t * t * t
  let t = x
  for (let i = 0; i < 8; i++) {
    const d = 3 * x1 * (1 - t) * (1 - t) + 6 * (x2 - x1) * t * (1 - t) + 3 * (1 - x2) * t * t
    if (d < 1e-6) break
    t -= (bez(t, x1, x2) - x) / d
  }
  return bez(t, y1, y2)
}

/**
 * Drifting, a frame loop moves `raw` and writes the rows' transform straight to the DOM (the rows re-render only when the
 * window steps a row). Held, `raw` eases to the remote's position over 0.2 s (a press glides a row, no kick) while the
 * window is already the target's (Swift: only `.offset(y: -raw)` animates). Paused (auto-scroll off), it sits at the parked drift offset.
 */
function GuideGrid(props: { now: Instant; ws: Instant; gf: ProgramKey }) {
  const { now, ws, gf } = props
  const model = useModel()
  const t = useTheme()
  const mm = t.id === 'MIDNIGHT'
  const list = model.visibleChannels
  const n = list.length
  const pitch = model.rowPitch
  const height = model.gridHeight
  const half = model.half
  const count = Math.ceil(height / pitch) + 2   // one row of slack above and below the viewport
  const held = model.remoteHeld
  const width = 1760
  // Held: the remote's position. Paused (auto-scroll off): the parked drift offset. Drifting: null, the frame loop's.
  const target = held ? model.guideScroll.pos : !model.settings.autoScroll ? model.guideDriftOffset() : null
  const raw = useRef(NaN)   // what is on screen
  if (isNaN(raw.current)) raw.current = target ?? model.guideDriftOffset()
  const [, step] = useState(0)
  const win = RowWindow.of(target ?? raw.current, half, pitch)
  const still = !held ? target : null   // paused: drawn at the target straight away
  const track = useRef<HTMLDivElement>(null)
  const shown = useRef({ win, still, ty: NaN })   // the window the DOM's rows are, and the transform written
  // translateY(top − raw): the window's first row sits at raw track offset `top`, so the picture is the same as if the
  // whole tiled track were translated by −raw. `top` is fixed for the rows shown, which keeps the slide continuous when
  // raw crosses a multiple of the track height. In whole pixels: the 22 pt/s drift then repaints only when the rows have
  // moved a pixel (about 22 times a second instead of every vsync, and no text re-rasterized at fractional offsets).
  const place = () => {
    const s = shown.current
    const ty = Math.round(s.win.top - (s.still ?? raw.current))
    if (ty !== s.ty && track.current != null) { s.ty = ty; track.current.style.transform = `translateY(${ty}px)` }
  }
  useLayoutEffect(() => { shown.current.win = win; shown.current.still = still; place() })   // new rows: placed before paint
  useEffect(() => {
    let id = 0
    if (target == null) {
      const frame = () => {
        // Held or paused since this frame was asked for (this effect restarts for the new mode): `raw` stays where the
        // drift left it, so the held ease starts from what is on screen (the offset now returns the parked value).
        if (model.remoteHeld || !model.settings.autoScroll) return
        raw.current = model.guideDriftOffset()
        const w = RowWindow.of(raw.current, half, pitch)
        const s = shown.current.win
        // A row stepped in or out: re-render the rows (Preact renders in a microtask, before this frame paints, and the
        // layout effect places them). Otherwise only the transform moves.
        if (w.first !== s.first || w.rowBase !== s.rowBase) step(x => x + 1)
        else place()
        id = requestAnimationFrame(frame)
      }
      id = requestAnimationFrame(frame)
    } else if (held) {
      const from = raw.current
      const start = performance.now()
      const frame = () => {
        const p = Math.min(1, (performance.now() - start) / 200)
        raw.current = from + (target - from) * heldEasing(p)
        place()
        if (p < 1) id = requestAnimationFrame(frame)
      }
      id = requestAnimationFrame(frame)
    } else {
      raw.current = target
    }
    return () => cancelAnimationFrame(id)
  }, [`${held}|${target}|${half}|${pitch}`])

  const rows: VNode[] = []
  // Row identity is the raw row (`row + rowBase`), also where the window crosses the lineup's wrap point: the same
  // channel stays on the same row as the window slides, so focus transitions don't restart.
  if (n > 0) {
    for (let row = win.first; row < win.first + count; row++) {
      const ch = list[((row % n) + n) % n]
      rows.push(<GuideRow key={row + win.rowBase} ch={ch} row={row + win.rowBase} ws={ws} now={now} gf={gf} width={width} z={gf.channelID === ch.id ? 1 : 0} />)
    }
  }
  return (
    // Clicks only inside the viewport (the clip): the slack rows sit over the header and hint pills.
    <div style={{ ...at(80, mm ? 596 : 616, width, height), overflow: 'hidden' }}>
      <div ref={track} style={{ position: 'absolute', left: 0, top: 0, width, willChange: 'transform' }}>{rows}</div>
      {/* The rows fade into the background: the background color faded in over them (the CSS mask's picture, since the
          bottom fifth has nothing behind it but t.bg), without an offscreen mask every frame. */}
      {!mm && <div style={{ ...at(0, 0, width, height), background: `linear-gradient(transparent 78%, ${t.bg})`, pointerEvents: 'none' }} />}
    </div>
  )
}

function GuideRow(props: { ch: Channel; row: number; ws: Instant; now: Instant; gf: ProgramKey; width: number; z: number }) {
  const { ch, row, ws, now, gf, width } = props
  const model = useModel()
  const t = useTheme()
  const mm = t.id === 'MIDNIGHT'
  const span = AppModel.windowSpan
  const we = ws + span
  // Late Show: 340 column, cells inset 4 each side. Midnight Movie: 300 column, 4 gap, each cell 4 short on the right.
  const pitch = model.rowPitch
  const cellH = mm ? pitch - 4 : 88
  const colW = mm ? 300 : 340
  const gap = mm ? 4 : 0
  const trackW = width - colW - gap
  const inset = mm ? 0 : 4
  const shrink = mm ? 4 : 8
  const slots = model.slots(ch, ws, we)
  return (
    <div style={{ position: 'relative', width, height: pitch, zIndex: props.z }}>
      <ChannelCell ch={ch} style={at(0, 0, colW, cellH)} />
      <div style={at(colW + gap, 0, trackW, cellH)}>
        {slots.map(s => {
          const l = Math.max(s.start, ws)
          const r = Math.min(s.end, we)
          const left = (l - ws) / span * trackW
          const cw = (r - l) / span * trackW
          const k: ProgramKey = { channelID: ch.id, start: s.start }
          const focused = ProgramKey.eq(gf, k)
          return (
            <GuideCell key={s.id} slot={s} l={l} r={r} contL={s.start < ws} contR={s.end > we} now={now} focused={focused}
              style={{ ...at(left + inset, 0, Math.max(0, cw - shrink), cellH), zIndex: focused ? 3 : 1 }}
              onTap={() => model.tapGuide(row, k)} onPoint={() => model.pointGuide(row, k)} />
          )
        })}
      </div>
    </div>
  )
}

function ChannelCell(props: { ch: Channel; style: JSX.CSSProperties }) {
  const t = useTheme()
  const ch = props.ch
  if (t.id === 'MIDNIGHT') {
    return (
      <Row gap={18} style={{ ...props.style, alignItems: 'center', background: t.panel, padding: '0 20px' }}>
        <Txt s={String(ch.number)} style={{ ...t.body(46, 800), textAlign: 'right', minWidth: 60 }} color={t.accent} />
        <Txt s={ch.name.toUpperCase()} lines={2} style={{ ...t.heading(30, 700), letterSpacing: '0.05em', minWidth: 0 }} color={t.ink} />
      </Row>
    )
  }
  return (
    <Row gap={18} style={{ ...props.style, alignItems: 'center' }}>
      <Txt s={String(ch.number)} style={{ ...t.body(26, 600), fontFeatureSettings: '"tnum"', textAlign: 'right', width: 48, flex: 'none' }} color={t.accent} />
      <div style={{ minWidth: 0 }}>
        <Txt s={ch.name} lines={1} style={t.heading(32, 600)} color={t.ink} />
        <Txt s={ch.tag} lines={1} style={{ ...t.body(24), marginTop: 2 }} color={t.dim} />
      </div>
    </Row>
  )
}

function GuideCell(props: {
  slot: Slot; l: Instant; r: Instant; contL: boolean; contR: boolean; now: Instant; focused: boolean
  style: JSX.CSSProperties; onTap: () => void; onPoint: () => void
}) {
  const { slot, l, r, contL, contR, now, focused } = props
  const t = useTheme()
  const item = slot.item
  const st = GuideGenerator.status(slot, now)
  const live = st === 'live' || st === 'break'
  const span = r - l
  const fill = live && span > 0 ? Math.max(0, Math.min(1, (now - l) / span)) : 0
  const mm = t.id === 'MIDNIGHT'
  // Everything the focus changes eases together, in step with the row slide (heldEasing, 0.2 s; Swift `.animation(.glide,
  // value: focused)`), so on Up/Down the highlight holds its place while the rows pass under it: transitions on the colors,
  // the scale and the shadow's opacity (a pre-drawn shadow, never a box-shadow transition).
  const ease = '.2s cubic-bezier(0.39, 0.575, 0.565, 1)'
  const ink = focused ? t.focusInk : t.ink
  const sc = focused ? (mm ? t.focusInk : t.focusDim) : t.dim   // the prototype's focused MM sub is the ink color
  // The clipped-edge triangle: Late Show 12×18 in the sub color, Midnight Movie 16×24 in the title color.
  const arrow = mm ? ink : sc
  const aw = mm ? 16 : 12
  const ah = mm ? 24 : 18
  const sidePad = mm ? 50 : 44
  const base = focused ? t.focusBg : live ? liveCell(t) : cellBg(t)
  const fillColor = focused ? focusFill(t) : mm ? liveCell(t) : t.line   // Midnight Movie cells are flat
  const radius = t.cellRadius
  const arrowAt = (right: boolean) => (
    <svg width={aw} height={ah} style={{
      position: 'absolute', [right ? 'right' : 'left']: 16, top: '50%', marginTop: -ah / 2, color: arrow, transition: `color ${ease}`,
    }}>
      <path d={right ? `M0 0L${aw} ${ah / 2}L0 ${ah}Z` : `M${aw} 0L0 ${ah / 2}L${aw} ${ah}Z`} fill="currentColor" />
    </svg>
  )
  const title = t.body(mm ? 34 : 30, mm ? 700 : 600)
  const sub = t.body(mm ? 26 : 24, 500)
  return (
    <div style={{ ...props.style, transform: !mm && focused ? 'scale(1.04)' : 'none', transition: `transform ${ease}` }} {...onTouch(props.onTap)} {...onPoint(props.onPoint)}>
      {t.focusShadow && (
        // SwiftUI radius 22 is the blur's sigma: CSS blur 44.
        <div style={{ ...FILL, borderRadius: radius, boxShadow: '0 18px 44px rgba(0,0,0,0.6)', opacity: focused ? 1 : 0, transition: `opacity ${ease}` }} />
      )}
      <div style={{
        ...FILL, borderRadius: radius, overflow: 'hidden', backgroundColor: base, transition: `background-color ${ease}`,
        display: 'flex', flexDirection: 'column', justifyContent: 'center',
        paddingLeft: contL ? sidePad : 24, paddingRight: contR ? sidePad : 24,
      }}>
        {live && (
          <div style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: `${fill * 100}%`, backgroundColor: fillColor, transition: `background-color ${ease}` }} />
        )}
        {contL && arrowAt(false)}
        {contR && arrowAt(true)}
        <div class="ellipsis" style={{ ...natural(title), position: 'relative', color: ink, transition: `color ${ease}` }}>{item.displayTitle}</div>
        <div class="ellipsis" style={{ ...natural(sub), position: 'relative', marginTop: 2, color: sc, transition: `color ${ease}` }}>
          {guideCellSub(item, mm)}
        </div>
      </div>
    </div>
  )
}

/** Late Show: "1981 · 115 min" for a movie, the episode title for an episode. Midnight Movie: "MOVIE · 1981 · PG", "“Episode”". */
function guideCellSub(item: MediaItem, mm: boolean): string {
  if (item.kind === 'movie' && mm) return ['MOVIE', item.year != null ? String(item.year) : null, item.contentRating].filter(x => x != null).join(' · ')
  if (item.kind === 'movie') return [item.year != null ? String(item.year) : null, `${Math.trunc(item.runtime / 60)} min`].filter(x => x != null).join(' · ')
  return mm ? `“${item.title}”` : item.title
}

// MARK: - Helpers

/** `Text(s).font(style).foregroundStyle(color)` at the face's natural line height; `lines` 1 or 2 truncate with an
 *  ellipsis like SwiftUI. */
function Txt(props: { s: string; style: TextStyle | (TextStyle & JSX.CSSProperties); color: string; lines?: 1 | 2 }) {
  return (
    <div class={props.lines === 1 ? 'ellipsis' : props.lines === 2 ? 'clamp2' : undefined}
      style={{ ...natural(props.style), color: props.color }}>{props.s}</div>
  )
}

/**
 * The Swift `.padding(.vertical, t.lineHeightTrim(size, mult:))` on heading type: the face's natural lines (1.2 em for
 * both heading faces) with (1.2·size − size·mult)/2 cut from the outer edges only, so a wrapped title keeps its natural
 * line spacing, as in SwiftUI. Spread into the text's style; spacing above it goes in `paddingTop` (its margins are the trim).
 */
export function lineHeightTrim(size: number, mult = 1): { lineHeight: number; marginTop: number; marginBottom: number } {
  const trim = Math.round((1.2 - mult) * size / 2)
  return { lineHeight: 1.2, marginTop: -trim, marginBottom: -trim }
}

/**
 * SwiftUI `.background(color)` on tvOS: a color (a ShapeStyle, not a view) runs on into the screen's safe-area margins
 * (60 top and bottom, 80 each side) on every edge where the view reaches them. The overhang is copies of the box drawn as
 * blur-free shadows: no extra element, not laid out.
 */
function bleedBackground(color: string, left = 0, top = 0, right = 0): JSX.CSSProperties {
  const s: string[] = []
  if (left) s.push(`${-left}px 0 0 ${color}`)
  if (right) s.push(`${right}px 0 0 ${color}`)
  if (top) s.push(`0 ${-top}px 0 ${color}`)
  if (top && left) s.push(`${-left}px ${-top}px 0 ${color}`)
  if (top && right) s.push(`${right}px ${-top}px 0 ${color}`)
  return { background: color, boxShadow: s.join(', ') }
}
