// Port of android/app WatchScreen.kt. Captions and the in-playlist subtitle pick (`selectLegible`, the captions TextView)
// are gone: Emby/Jellyfin subtitles burn in on the web, like Plex's (web/README.md, "Video"), so the player never sees a
// text track. Audio focus is gone too (no web equivalent).
import type { ComponentChildren, JSX } from 'preact'
import { h } from 'preact'
import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks'
import { runCatchingNonCancel } from '../core/MediaServer'
import type { MediaItem, Slot } from '../core/Models'
import { Instant, now as instantNow } from '../core/Time'
import { TrailerTries } from '../core/TrailerTries'
import { Cue, cueText, parseWebVTT } from '../core/WebVTT'
import { addCSS, Column, natural, Row, useExit, useNow } from './Anim'
import { AppModel, useModel } from './AppModel'
import { SpotOnAir, WatchInfo } from './AppModelWatch'
import { attachStream, bevelDark, bevelLight, breakBadge, KeyHint, lineHeightTrim } from './GuideScreen'
import { Checkmark } from './OnboardingScreens'
import { backgroundTap, onPoint, onTouch, Platform, useSticky } from './Platform'
import { RemoteImage } from './RemoteImage'
import { RerunLoader } from './RerunLoader'
import { ScrambleView } from './Scramble'
import { at } from './Stage'
import { StationIdentCard, StationSting } from './StationIdent'
import { touchChromeShows } from './TouchViews'
import { Clock, lineSpacing, Para } from './Text'
import { channelColor, hex, Theme, useTheme } from './Theme'

// Enter/exit animations (Compose's AnimatedContent and graphicsLayer fades): transform and opacity only.
addCSS(`
@keyframes watchBannerIn { from { opacity: 0; transform: translateY(14px); } }
@keyframes watchBannerOut { to { opacity: 0; transform: translateY(14px); } }
@keyframes watchIdentIn { from { opacity: 0; transform: scale(0.96); } }
@keyframes watchFadeIn { from { opacity: 0; } }
@keyframes watchFadeOut { to { opacity: 0; } }
@keyframes watchStatic { from { opacity: 1; } to { opacity: 0; visibility: hidden; } }
.watch-banner-in { animation: watchBannerIn 250ms ease-out both; }
.watch-banner-out { animation: watchBannerOut 250ms ease-out forwards; }
.watch-ident-in { animation: watchIdentIn 250ms ease-out both; }
.watch-ident-out { animation: watchFadeOut 400ms ease-out forwards; }
.watch-static { animation: watchStatic 500ms linear forwards; background-size: 220px 220px; image-rendering: pixelated; }
@keyframes watchRewindRoll { from { transform: translateY(1080px); } to { transform: translateY(-90px); } }
@keyframes watchRewindShake {
  0% { transform: translateX(-12px); background-position: 0 0; }
  25% { transform: translateX(-47px); background-position: 113px 61px; }
  50% { transform: translateX(-3px); background-position: 37px 170px; }
  75% { transform: translateX(-31px); background-position: 181px 97px; }
}
.watch-rewind { pointer-events: none; overflow: hidden; }
.watch-rewind-roll { position: absolute; left: 0; top: 0; width: 1920px; height: 90px; animation: watchRewindRoll 700ms linear infinite; }
.watch-rewind-band {
  width: 1980px; height: 90px; opacity: 0.85; background-size: 220px 220px; image-rendering: pixelated;
  animation: watchRewindShake 240ms steps(1) infinite;
}
@media (prefers-reduced-motion: reduce) { .watch-ident-in { animation-name: watchFadeIn; } .watch-rewind-roll { display: none; } }
`)

/**
 * Now playing: full-screen `<video>`, break trailers / station card, channel banner, static flash.
 * The clock is the source of truth (prototype `wInfo`): the player loads the current program at the clock's offset and
 * is reloaded when the (channel, slot, tune, break) key changes; banner/focus/pause changes never touch it.
 */
export function WatchScreen() {
  const model = useModel()
  const loaded = useMutableState<Loaded | null>(null)
  /** The station card on screen (tune-in / break), cleared 1.5 s after it was set. */
  const [ident, setIdent] = useState<Ident | null>(null)
  /** The key effect's job: the break's hand-off (the spot over or failed, a trailer failed) ends with its key, or with the screen. */
  const keyJob = useRef<Job>({ active: true })
  // TimelineView(.periodic(from: .timelineOrigin, by: 1)): ticks on whole seconds; `onChange(of: now)` → watchTick.
  // Only this screen re-renders per second, never the whole app.
  const now = Math.floor(useNow(1000, true))
  useEffect(() => { model.watchTick(now) }, [now])
  const info = model.watchInfo(now)
  const key = info != null ? LoadKey(info.channel.id, info.slot, model.tunes, info.isBreak) : null
  const current = loaded.value != null && same(loaded.value.key, key) ? loaded.value : null

  function showIdent(kicker: string, sub: string, sting: StationSting.Kind) {
    if (!model.settings.stationIdents) return
    setIdent({ kicker, sub, n: model.identSerial })
    StationSting.shared.play(sting)
  }

  const keyID = key?.id ?? ''
  useEffect(() => {   // LaunchedEffect(key): a new key cancels the last load and its hand-off
    const job: Job = { active: true }
    keyJob.current = job
    void load(model, loaded, key, false, job)
    return () => { job.active = false }
  }, [keyID])
  const identKey = ident != null ? `${ident.n}|${ident.kicker}|${ident.sub}` : ''
  useEffect(() => {
    if (ident == null) return
    const id = setTimeout(() => setIdent(null), 1500)   // fades out over 0.4 s (the exit animation)
    return () => clearTimeout(id)
  }, [identKey])
  useEffect(() => {   // initial too: tune() from the guide bumps it before this screen exists
    if (info == null || model.rewind != null) return   // a start-over from the guide rewinds instead
    showIdent('YOU’RE WATCHING', info.isBreak ? `Back at ${Clock.time(info.slot.end)}` : info.slot.item.displayTitle, 'tune')
  }, [model.identSerial])
  const oldKey = useRef(key)
  useEffect(() => {   // the program rolled into its break under the same tune
    if (!same(loaded.value?.key, key)) model.spotChanged(null)   // a channel change mid-spot: the spot is gone now
    const old = oldKey.current
    oldKey.current = key
    const k = key
    if (old == null || k == null || info == null || same(old, k) || !k.isBreak || old.isBreak || k.tune !== old.tune ||
      !(model.settings.trailerBreaks && info.channel.trailerBreaks) || info.channel.isScrambled) return
    showIdent('WE’LL BE RIGHT BACK', `${info.channel.name} · back at ${Clock.time(k.slot.end)}`, 'brk')
  }, [keyID])
  useEffect(() => {   // the spot on screen or gone: the remote can skip it
    const l = loaded.value
    // Ended or skipped: off the air now (station / title card), not frozen on its last frame while the next one loads.
    if (l?.spot === true) model.spotChanged(() => {
      if (!same(loaded.value?.key, l.key)) return   // a stale end after a channel change must not clear the new one
      loaded.value = Loaded(l.key, null, false, 0, null, false)
      void load(model, loaded, l.key, true, keyJob.current)
    })
    else model.spotChanged(null)
  }, [loaded.n])
  // Samsung: the screensaver must not come on over channel 99 either (the GIF isn't video; the player does the rest).
  const scrambled = info?.channel.isScrambled === true ? 1 : 0
  useEffect(() => {
    if (!scrambled) return
    Platform.setScreenSaver(false)
    return () => Platform.setScreenSaver(true)
  }, [scrambled])
  useEffect(() => () => {
    model.cancelTimer('banner'); model.spotChanged(null); loaded.value = null
    model.server?.stopPlayback('watch').catch(() => {})
  }, [])

  const handOff = (k: LoadKey, tries: TrailerTries) => { void load(model, loaded, k, true, keyJob.current, tries) }
  return (
    <div style={{ ...at(0, 0, 1920, 1080), background: '#000' }} {...backgroundTap(() => model.watchTap())}>
      {info != null && h(stage, { info, current, now, loaded, ident, handOff })}
    </div>
  )
}

interface StageProps {
  info: WatchInfo
  current: Loaded | null
  now: Instant
  loaded: MutableState<Loaded | null>
  ident: Ident | null
  handOff: (k: LoadKey, tries: TrailerTries) => void
}

function stage({ info, current, now, loaded, ident, handOff }: StageProps) {
  const model = useModel()
  const t = useTheme()
  const ch = info.channel
  const item = info.slot.item
  const trailerOn = current?.trailer != null || (current == null && model.settings.trailerBreaks && ch.trailerBreaks)
  const tv = item.kind === 'episode' && !info.isBreak
  const station = info.isBreak && current != null && current.url == null
  const next = info.next
  const tr = current?.trailer ?? (info.isBreak && trailerOn ? model.breakTrailerCandidate(ch, info.slot) : null)
  const fx = tv ? 240 : 0
  const fw = tv ? 1440 : 1920
  const paused = model.playback?.paused === true
  const clock = useRef<() => number>(() => -1)   // the player's time, for the subtitles
  // The subtitle drawn over the picture follows the current pick, not the last load: switching it never reloads the stream.
  const pick = model.trackSelection(model.playbackTracks, item.id).subtitle
  const drawn = current?.url != null && current.trailer == null && !current.spot && !info.isBreak && pick.kind === 'track'
    ? model.server?.drawnSubtitleURL(item.id, pick.id) ?? null : null
  // .transition(.opacity.combined(with: .offset(y: 14))). SwiftUI fades the removed banner out as it last looked
  // (hideBanner clears the focus at the same time): useExit keeps the last BannerData.
  const fade = t.id === 'MIDNIGHT' ? 200 : 250
  const banner = useExit(model.bannerVisible ? new BannerData(info, model, t, now, trailerOn) : null, fade)
  // AnimatedContent keyed on `ident != null`: one card to the next just changes its text.
  const shownIdent = useExit(ident, 400)
  const spotStarted = SpotOnAir.started
  /** What the player last said about its load: first frame shown, stalled (the loader's cue). */
  const [status, setStatus] = useState<PlayerStatus>(noStatus)

  const onFail = (err: string, started: boolean, k: LoadKey | null, u: string | null) => {
    // Same key, url null: no reload; the title card / station card returns and `sync` stops calling play().
    // Only for what is loaded now: a failure of the last item can land after `loaded` moved on, before `sync`.
    const l = loaded.value != null && same(loaded.value.key, k) && loaded.value.url === u ? loaded.value : null
    if (l == null || l.url == null) return
    if (l.spot) {   // the trailers take the break
      model.spotChanged(null); handOff(l.key, new TrailerTries())
    } else if (l.trailer != null) {
      // A trailer that failed or never started: quietly the next one, or the station card. Never a toast for a trailer.
      loaded.value = Loaded(l.key, null, false, 0, null, false)
      handOff(l.key, started ? l.tries.restarted() : l.tries)
    } else if (!started && l.subtitleRisk && l.trailer == null) {
      // A program with a subtitle that never showed a first frame: once more without (the retry asks for off, so it
      // can't come back here). Once the picture has started, or with no subtitle to blame, it's a plain failure.
      loaded.value = Loaded(l.key, null, false, 0, null, false)
      model.dropSubtitles(l.key.slot.item.id)
    } else {
      const msg = `Couldn’t play this stream: ${err}`
      loaded.value = Loaded(l.key, null, false, 0, null, false, { error: msg })
      model.toast(msg)
      // The player never says whether the server was unreachable: look for it anyway (at most once a minute), and
      // reload there if it moved, as serverFailed does for the app's own requests.
      void model.recoverPlexAddress().then(moved => { if (moved) { model.tunes += 1; model.foregroundSerial += 1 } })
    }
  }

  const chip: JSX.CSSProperties = { background: 'rgba(0,0,0,0.6)', borderRadius: t.radius }
  return (
    <>
      {ch.isScrambled ? <ScrambleView seed={Math.trunc(info.slot.start / 60)} style={at(0, 0, 1920, 1080)} audio /> : <>
        <Picture hue={ch.hue} art={model.server?.backdropURL(tr ?? item) ?? null} style={at(fx, 0, fw, 1080)} />
        <PlayerView loaded={current} paused={paused} style={at(0, 0, 1920, 1080)} onFail={onFail} clock={clock} onStatus={setStatus}
          onEnd={() => model.skipSpot()} />{/* the spot is over (skippable by then): as a skip, the trailers (or the program) take the rest */}
        {drawn != null && <Subtitles url={drawn} time={() => clock.current()} lifted={model.bannerVisible} />}
        {/* The design mockup's stand-in for the picture: only while no stream is attached, never over live video. */}
        {current?.url == null && (!info.isBreak || trailerOn) && (
          <div style={{ ...at(fx + 80, 0, fw - 160, 1080), display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Column gap={14} style={{ alignItems: 'center' }}>
              <div style={{ ...natural(t.body(24, 600)), color: 'rgba(255,255,255,0.78)', letterSpacing: '0.2em' }}>
                {(info.isBreak ? 'Trailer' : `Playing from ${model.server?.serverName ?? ''}`).toUpperCase()}
              </div>
              <Para text={info.isBreak ? `${tr?.title ?? ''} (${tr?.year ?? ''})`
                : item.displayTitle + (item.kind === 'movie' ? ` (${item.year ?? ''})` : `: “${item.title}”`)}
                style={{ ...lineSpacing(t.heading(56, 600), 4), color: '#fff', textAlign: 'center', maxWidth: 1200 }} />
              {current?.error != null &&
                <Para text={current.error} style={{ ...natural(t.body(28)), color: t.accent, textAlign: 'center', maxWidth: 1200 }} />}
            </Column>
          </div>
        )}
        {station && (
          <div style={{ ...at(0, 0, 1920, 1080), background: t.bg, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Column gap={20} style={{ alignItems: 'center' }}>
              <div style={{ ...t.heading(220), ...lineBox(220), color: t.accent }}>{ch.number}</div>{/* CSS line-height: 1 */}
              <div style={{ ...t.heading(80), ...lineBox(80), color: t.ink, letterSpacing: t.tracking(t.headingTracking) }}>{t.heading(ch.name)}</div>
              {/* What just ended and what's next, so a channel between programs doesn't look broken. */}
              <Column gap={8} style={{ alignItems: 'center', paddingTop: 16 }}>
                <Para text={item.displayTitle + (item.kind === 'movie' ? '' : `: “${item.title}”`)
                  + (info.live ? ` ended at ${Clock.time(info.slot.start + item.runtime)}` : ' just ended')}
                  style={{ ...natural(t.body(36)), color: t.ink, textAlign: 'center', maxWidth: 1400 }} />
                <div style={{ ...natural(t.body(36)), color: t.mute }}>
                  {next != null ? `Next: ${next.item.displayTitle} at ${Clock.time(next.start)}` : 'More after this'}
                </div>
              </Column>
              <Row gap={48} style={{ alignItems: 'center', paddingTop: 24 }}>
                {next != null &&
                  <KeyHint symbol="button.programmable" label={`Watch ${next.item.displayTitle} now`} action={() => model.startNextFromBreak()} />}
                <KeyHint symbol="playpause.fill" label={`Watch ${item.displayTitle} again`} action={() => model.restartFromBreak()} />
              </Row>
            </Column>
          </div>
        )}
        {info.isBreak && current?.trailer != null && next != null && (   // trailers only: the spot draws its own UI in that corner
          <Column gap={6} style={{ position: 'absolute', right: 72, top: 64, alignItems: 'flex-end', ...chip, padding: '18px 26px' }}>
            <div style={{ ...natural(t.body(24, 700)), color: t.accent, letterSpacing: '0.16em' }}>{'Coming attractions'.toUpperCase()}</div>
            <div style={{ ...natural(t.body(30)), color: '#fff' }}>{`Back at ${Clock.time(next.start)} with ${next.item.displayTitle}`}</div>
          </Column>
        )}
        {current?.spot === true && spotStarted != null && <SpotChips started={spotStarted} now={now} />}
        {ident == null && loader(status, current, paused)}
      </>}
      {model.rewind != null && <RewindEffect key={model.rewind.started} r={model.rewind} />}
      {paused && (
        <Row gap={22} style={{ position: 'absolute', left: 80, top: chipTop(model), alignItems: 'center', ...chip, padding: '18px 28px' }}>
          <Row gap={8}>
            <div style={{ width: 10, height: 36, background: '#fff' }} />
            <div style={{ width: 10, height: 36, background: '#fff' }} />
          </Row>
          <div style={{ ...natural(t.body(32, 600)), color: '#fff' }}>Paused</div>
        </Row>
      )}
      {/* The fade's layer is the whole banner with its overflow, so the Late Show shadow and the Midnight bleed fade with
          the panel instead of being clipped to it. */}
      {banner.value != null && (
        <div class={banner.leaving ? 'watch-banner-out' : 'watch-banner-in'}
          style={{ position: 'absolute', left: 72, right: 72, bottom: 56, animationDuration: `${fade}ms`, willChange: 'transform' }}>
          <Banner d={banner.value} />
        </div>
      )}
      {shownIdent.value != null && (
        <div class={shownIdent.leaving ? 'watch-ident-out' : 'watch-ident-in'} style={at(0, 0, 1920, 1080)}>
          <StationIdentCard number={ch.number} name={ch.name} tag={ch.tag} kicker={shownIdent.value.kicker} sub={shownIdent.value.sub} />
        </div>
      )}
      {model.flips > 0 && <StaticFlash key={model.flips} />}
    </>
  )
}

/** A coroutine's `isActive`: false once what started the load is gone (the key moved on, the screen closed). */
interface Job { active: boolean }

/**
 * Fetch the stream for `key`: the program at the clock's offset, or for a break the first trailer the server has
 * (deterministic candidate, then up to 3 other movies on the channel); none → station card (url null). A trailer that
 * fails carries on here with its `tries`: the ones already tried are skipped, and the budget ends them. Its result is
 * dropped once `job` is inactive (the Kotlin coroutine's cancellation).
 */
async function load(model: AppModel, loaded: MutableState<Loaded | null>, key: LoadKey | null, skipSpot: boolean, job: Job,
                    tries: TrailerTries = new TrailerTries()): Promise<void> {
  if (key == null) return
  const server = model.server
  if (server == null) return
  const ch = model.channel(key.channelID)
  if (ch == null) return
  let result = Loaded(key, null, false, 0, null, false)
  if (ch.isScrambled) { model.playbackTracks = []; loaded.value = result; return }   // channel 99 draws itself
  const spot = !skipSpot && (key.isBreak || model.spotOverride) ? model.dueSpot(ch, key.slot) : null
  if (spot != null) {
    model.playbackTracks = []
    loaded.value = Loaded(key, spot, false, 0, null, false, { spot: true })
    return
  }
  if (key.isBreak) {
    model.playbackTracks = []
    if (model.settings.trailerBreaks && ch.trailerBreaks) {
      const first = model.breakTrailerCandidate(ch, key.slot)
      const others = ch.items.filter(it => it.kind === 'movie' && it.id !== key.slot.item.id && it.id !== first?.id).slice(0, 3)
      const candidates = (first != null ? [first] : []).concat(others)
      let t = tries
      for (;;) {
        const m = t.next(candidates)
        if (m == null) break
        t = t.adding(m)
        try {
          const src = await server.trailer(m, 'watch')
          if (src != null) { result = Loaded(key, src.url, false, 0, m, true, { tries: t }); break }
        } catch (e) {
          if (!job.active) return   // Kotlin's cancelled request throws CancellationException: serverFailed never sees it
          model.serverFailed(e)   // a sign-out or a server to look for, then the others: no toast for a trailer
        }
        if (!job.active || model.server == null) return
      }
    }
  } else {
    const offset = Math.max(0, Math.min(model.watchInfo(instantNow())?.position ?? 0, key.slot.item.runtime))
    // Another item: the banner's Audio/Subtitles must not build its pick from the last one's stream indices meanwhile.
    if (loaded.value?.key.slot.item.id !== key.slot.item.id) model.playbackTracks = []
    const tracks = (await runCatchingNonCancel(() => server.tracks(key.slot.item))) ?? []
    if (!job.active) return
    model.playbackTracks = tracks
    const sel = model.trackSelection(tracks, key.slot.item.id)
    try {
      const src = await server.playbackSource(key.slot.item, offset, 'watch', sel)
      // The demo's fixed sample is shorter than any slot, so it loops instead of seeking.
      // Only a burned-in subtitle can stop the picture from starting; a drawn one can't.
      const burned = sel.subtitle.kind === 'track' && server.drawnSubtitleURL(key.slot.item.id, sel.subtitle.id) == null
      result = Loaded(key, src.url, src.startsAtOffset, offset, null, server.kind === 'demo', { subtitleRisk: burned })
    } catch (e) {
      if (!job.active) return
      result = { ...result, error: model.serverFailed(e) }
    }
  }
  if (!job.active) return
  if (skipSpot && !same(loaded.value?.key, key)) return   // the hand-off finished after the viewer had already moved on
  loaded.value = result
  const e = result.error
  if (e != null && model.server != null) model.toast(e)   // a 401 already toasted on its way to Welcome
}

/** Kotlin's data class; `id` stands in for its equality (and is the effect key: Preact compares keys with Object.is). */
interface LoadKey {
  readonly channelID: string
  readonly slot: Slot
  readonly tune: number
  readonly isBreak: boolean
  readonly id: string
}
function LoadKey(channelID: string, slot: Slot, tune: number, isBreak: boolean): LoadKey {
  return { channelID, slot, tune, isBreak, id: `${channelID}|${slot.id}|${slot.end}|${tune}|${isBreak}` }
}
function same(a: LoadKey | null | undefined, b: LoadKey | null | undefined): boolean {
  return (a?.id ?? null) === (b?.id ?? null)
}

interface PlayerStatus { readonly key: LoadKey | null; readonly url: string | null; readonly started: boolean; readonly stalled: boolean }
const noStatus: PlayerStatus = { key: null, url: null, started: false, stalled: false }

/** The loader: a stream attached with no first frame yet (the title card covers the fetch before it), or one that
 *  stalled. A reload of the program that was on (a skip, Start over, a track change) is buffering, not tuning in. Never
 *  while paused (the Paused chip says it), nor under the station ident (the caller's check). */
function loader(status: PlayerStatus, current: Loaded | null, paused: boolean) {
  const s = same(status.key, current?.key) && status.url === (current?.url ?? null) ? status : noStatus
  const tuning = current?.url != null && !s.started
  if (paused || (!tuning && !s.stalled)) return null
  const again = status.key?.channelID === current?.key.channelID && status.key?.slot.id === current?.key.slot.id
  return <RerunLoader width={320} captions={[tuning && !again ? 'Tuning in…' : 'Buffering…']} delay={1}
    style={{ position: 'absolute', left: 960 - 300, top: 400 - 250, width: 600, height: 500, justifyContent: 'center' }} />   // centred at 960, 400: clear of the banner
}

interface Ident { readonly kicker: string; readonly sub: string; readonly n: number }

interface Loaded {
  readonly key: LoadKey
  readonly url: string | null
  readonly startsAtOffset: boolean
  readonly offset: number
  readonly trailer: MediaItem | null
  readonly loop: boolean
  /** A subtitle track was picked: the one thing a failure before the first frame is blamed on. Not Plex's auto: the
   *  fallback's off would make PlexClient change the account-wide subtitle choice. */
  readonly subtitleRisk: boolean
  readonly error: string | null
  /** The bundled Soyboi spot: plays once, then `onEnd` loads what the key really calls for. */
  readonly spot: boolean
  /** In a break, the trailers tried up to this one: its failure carries on from them. */
  readonly tries: TrailerTries
}
function Loaded(key: LoadKey, url: string | null, startsAtOffset: boolean, offset: number, trailer: MediaItem | null, loop: boolean,
                p: { subtitleRisk?: boolean; error?: string | null; spot?: boolean; tries?: TrailerTries } = {}): Loaded {
  return { key, url, startsAtOffset, offset, trailer, loop, subtitleRisk: p.subtitleRisk ?? false, error: p.error ?? null, spot: p.spot ?? false,
    tries: p.tries ?? new TrailerTries() }
}

/** Compose's `mutableStateOf` held by the screen: read and written by async code (`load`) and callbacks, a write
 *  re-renders. `n` counts writes: the effect key for "loaded changed". */
interface MutableState<T> { value: T; readonly n: number }
function useMutableState<T>(initial: T): MutableState<T> {
  const [, bump] = useState(0)
  const box = useRef<MutableState<T> | null>(null)
  if (box.current == null) {
    let v = initial
    let n = 0
    box.current = {
      get value() { return v },
      set value(x: T) { v = x; n += 1; bump(c => c + 1) },
      get n() { return n },
    }
  }
  return box.current
}

// MARK: - Player

/** One `<video>` (WatchPlayerUIView) behind the overlays; `sync` runs after every render, as AndroidView's `update`. */
function PlayerView(props: {
  loaded: Loaded | null
  paused: boolean
  style: JSX.CSSProperties
  onFail: (text: string, started: boolean, key: LoadKey | null, url: string | null) => void
  onEnd: () => void
  /** The first frame showed, or the stream stalled or got going again. */
  onStatus: (s: PlayerStatus) => void
  /** Set to the player's `time()` while it exists (the subtitles' clock). */
  clock: { current: () => number }
}) {
  const host = useRef<HTMLDivElement>(null)
  const view = useRef<WatchPlayerUIView | null>(null)
  useEffect(() => {
    const v = new WatchPlayerUIView(host.current!)
    view.current = v
    props.clock.current = () => v.time()
    return () => { v.stop(); view.current = null; props.clock.current = () => -1 }
  }, [])
  useEffect(() => {
    const v = view.current
    if (v == null) return
    v.onFail = props.onFail
    v.onEnd = props.onEnd
    v.onStatus = props.onStatus
    v.sync(props.loaded, props.paused)
  })
  return <div ref={host} style={props.style} />
}

/**
 * The screen's one `<video>`, reused for every program, trailer, spot and channel flip (the Kotlin ExoPlayer). Both TVs
 * draw it on a hardware plane under the page, so it is never faded or clipped: it stays `visibility: hidden` from a
 * reload until its first frame, while the host's black covers the gap (the Kotlin shutter). Trailers and the demo sample
 * loop; programs seek to the offset unless the server already started the stream there. Clicks on it reach WatchScreen.
 */
class WatchPlayerUIView {
  private readonly video = document.createElement('video')
  private key: LoadKey | null = null
  private url: string | null = null
  /** The spot is playing: its end (and only its end) calls `onEnd`. */
  private spot = false
  /** The current item has shown its first frame. */
  private started = false
  /** Between the page going hidden and visible again: the stream was released and `sync` must not restart it. */
  private stopped = false
  /** Where to seek once the metadata is in; 0 = none. */
  private seekTo = 0
  /** ExoPlayer's playWhenReady: play() was asked for this load, so an ended stream isn't restarted by the next sync. */
  private playWhenReady = false
  /** No first frame by then counts as a failure: 20 s after a program with a subtitle risk loads (a burn-in that never
   *  starts), `TrailerTries.startTimeout` after a trailer does (the next one takes over). */
  private watchdog = 0
  /** Bumped per load: a listener of an older load ignores what reaches it. */
  private loadSerial = 0
  private detach: (() => void) | null = null
  /** A failure's text, whether the first frame had shown, and the key and url it was loaded for. */
  onFail: ((text: string, started: boolean, key: LoadKey | null, url: string | null) => void) | null = null
  onEnd: (() => void) | null = null
  onStatus: ((s: PlayerStatus) => void) | null = null
  /** Waiting for data since a `waiting` event; a stall once it has played (a `playing` event) since this load. */
  private waiting = false
  private played = false

  /** Off to the Home screen: the server's stream is stopped (AppModel.enteredBackground), so release the video too and
   *  forget what it had; back in front, `returnedToForeground` bumps `tunes` and the next `sync` loads afresh. */
  private readonly lifecycle = () => {
    if (document.hidden) { this.stopped = true; this.key = null; this.url = null; this.started = false; this.release() }
    else this.stopped = false
  }

  constructor(private readonly host: HTMLElement) {
    const v = this.video
    v.setAttribute('playsinline', '')
    v.preload = 'auto'   // a load while paused still buffers to its first frame (`canplay`)
    v.style.cssText = 'position:absolute;left:0;top:0;width:100%;height:100%;visibility:hidden'
    host.appendChild(v)
    document.addEventListener('visibilitychange', this.lifecycle)
  }

  sync(l: Loaded | null, paused: boolean) {
    if (this.stopped) return
    if (!same(l?.key, this.key) || (l?.url ?? null) !== this.url) {   // same key, new url: the spot handing over, or a failed stream cleared
      this.key = l?.key ?? null
      this.url = l?.url ?? null
      this.started = false
      this.release()
      if (l != null && l.url != null) this.prepare(l, l.url)
    }
    // Black letterbox/pillarbox bars once a stream is attached; the Picture backdrop shows through until then.
    const attached = l?.url != null
    this.host.style.background = attached ? '#000' : 'transparent'
    if (!attached) return
    const v = this.video
    if (paused) { v.pause(); this.playWhenReady = false }
    else if (!this.playWhenReady) { this.playWhenReady = true; v.play().catch(() => {}) }   // failures arrive as `error`
    // ponytail: no drift correction after a stall; the picture may lag the banner's clock. Add a periodic resync if it shows.
  }

  /** setMediaItem + prepare. Listeners are per load and check the serial; `load()` in `release` drops the old source's
   *  queued events. */
  private prepare(l: Loaded, u: string) {
    const v = this.video
    const serial = ++this.loadSerial
    const offs: (() => void)[] = []
    const on = (type: string, f: () => void) => {
      const g = () => { if (serial === this.loadSerial) f() }
      v.addEventListener(type, g)
      offs.push(() => v.removeEventListener(type, g))
    }
    this.spot = !l.loop && l.spot
    this.seekTo = !l.loop && !l.startsAtOffset && l.offset > 0 ? l.offset : 0
    v.loop = l.loop
    on('loadedmetadata', () => { if (this.seekTo > 0) v.currentTime = this.seekTo })
    on('canplay', () => {
      // A TV pipeline can drop a seek made before it could play: check it took, once. Later than asked is attachStream
      // jumping the hole before the first keyframe (Emby/Jellyfin), not a dropped seek.
      const s = this.seekTo
      this.seekTo = 0
      if (s > 0 && v.currentTime < s - 1) { v.currentTime = s; return }
      if (v.paused && !v.seeking) this.firstFrame()   // loaded paused: the frame at the offset is up (playing waits for `playing`)
    })
    // tvOS: only a player that is actually playing holds off the screensaver (not a paused, stalled or failed one).
    on('playing', () => { this.waiting = false; this.played = true; this.firstFrame(); Platform.setScreenSaver(false) })
    on('pause', () => Platform.setScreenSaver(true))
    on('waiting', () => { this.waiting = true; this.report(); Platform.setScreenSaver(true) })
    on('ended', () => {
      Platform.setScreenSaver(true)
      if (this.spot) { this.spot = false; this.onEnd?.() }
      // Native HLS `loop` doesn't always hold, and a trailer's transcode may be gone by its end: load it again.
      else if (l.loop) { this.release(); this.prepare(l, u); this.playWhenReady = true; v.play().catch(() => {}) }
    })
    on('error', () => {
      Platform.setScreenSaver(true)
      const e = v.error
      if (e != null && e.code !== 1) this.onFail?.(avText(e.code), this.started, this.key, this.url)   // 1: aborted by us
    })
    const detachStream = attachStream(v, u)   // `src`, or hls.js in a development browser without native HLS
    this.detach = () => { offs.forEach(f => f()); detachStream() }
    if (l.trailer != null || (!l.loop && !l.spot && l.subtitleRisk)) this.watchdog = window.setTimeout(() => {
      // Not once started: a loop's reload at 'ended' is no first start (Swift's looper and Kotlin's repeat never reload).
      if (serial === this.loadSerial && !this.started) this.onFail?.('The stream didn’t start.', false, this.key, this.url)
    }, (l.trailer != null ? TrailerTries.startTimeout : 20) * 1000)
  }

  /** Where the picture is, in media seconds (the servers' HLS keeps the file's timeline); -1 before the first frame. */
  time(): number {
    return this.started ? this.video.currentTime : -1
  }

  private firstFrame() {
    this.video.style.visibility = 'visible'
    clearTimeout(this.watchdog)
    this.started = true
    this.report()
  }

  private report() {
    if (this.started) this.onStatus?.({ key: this.key, url: this.url, started: true, stalled: this.played && this.waiting })
  }

  /** ExoPlayer's stop() + clearMediaItems(): frees the decoder (the TVs have one, and the guide's preview needs it next). */
  private release() {
    clearTimeout(this.watchdog)
    // Nothing attached (released already, or never loaded): the video is empty, so a reload releases it exactly once.
    if (this.detach == null) return
    this.detach()
    this.detach = null
    this.spot = false
    this.seekTo = 0
    this.playWhenReady = false
    this.waiting = false
    this.played = false
    const v = this.video
    v.style.visibility = 'hidden'
    v.loop = false
    v.pause()
    v.removeAttribute('src')
    v.load()
    Platform.setScreenSaver(true)
  }

  stop() {
    document.removeEventListener('visibilitychange', this.lifecycle)
    this.release()
  }
}

/** What AVPlayerItem.error.localizedDescription says for the same failure, from the MediaError code. Native HLS reports
 *  no HTTP status, so Kotlin's "Server returned N" and its network wordings collapse into these. */
function avText(code: number): string {
  switch (code) {
    case 2: return 'The network connection was lost.'   // MEDIA_ERR_NETWORK
    case 3: return 'Cannot Decode'                        // MEDIA_ERR_DECODE
    case 4: return 'Cannot Open'                          // MEDIA_ERR_SRC_NOT_SUPPORTED
    default: return 'The operation could not be completed'
  }
}

// MARK: - Subtitles

/**
 * A text subtitle drawn over the picture. Web only: the TVs' players don't show the servers' subtitle renditions, and a
 * burn-in re-encodes the whole picture. Fetches the WebVTT once per url and shows the cues covering the player's time,
 * checked five times a second; above the banner while it's up. A server pulling a subtitle out of a big file can take
 * seconds the first time, so a load still going after half a second says so, and a failed one says that for a while.
 */
function Subtitles(props: { url: string; time: () => number; lifted: boolean }) {
  const t = useTheme()
  const [cues, setCues] = useState<readonly Cue[]>([])
  const [text, setText] = useState('')
  const [notice, setNotice] = useState<string | null>(null)
  useEffect(() => {
    let on = true
    setCues([]); setText(''); setNotice(null)
    const slow = setTimeout(() => { if (on) setNotice('Loading subtitles…') }, 500)
    let clear = 0
    fetch(props.url)
      .then(r => r.ok ? r.text() : Promise.reject(new Error(`HTTP ${r.status}`)))
      .then(s => { if (on) { setCues(parseWebVTT(s)); setNotice(null) } })
      .catch(e => {
        console.warn('subtitles', e)
        if (on) { setNotice('Subtitles didn’t load'); clear = window.setTimeout(() => { if (on) setNotice(null) }, 5000) }
      })
      .then(() => clearTimeout(slow))
    return () => { on = false; clearTimeout(slow); clearTimeout(clear) }
  }, [props.url])
  useEffect(() => {
    if (cues.length === 0) return
    const id = setInterval(() => setText(cueText(cues, props.time())), 200)
    return () => clearInterval(id)
  }, [cues])
  if (text === '' && notice == null) return null
  return (
    <div style={{ position: 'absolute', left: 160, right: 160, bottom: props.lifted ? 430 : 90, textAlign: 'center', pointerEvents: 'none' }}>
      {text !== ''
        ? <span style={{
            ...t.body(46, 600), color: '#fff', whiteSpace: 'pre-line', lineHeight: 1.35, background: 'rgba(0,0,0,0.55)',
            padding: '4px 18px', borderRadius: 8, textShadow: '0 0 4px #000', WebkitBoxDecorationBreak: 'clone',
          }}>{text}</span>
        : <span style={{ ...t.body(30, 500), color: 'rgba(255,255,255,0.8)', background: 'rgba(0,0,0,0.45)', padding: '4px 14px', borderRadius: 8 }}>{notice}</span>}
    </div>
  )
}

// MARK: - Picture backdrop

const fillMaxSize: JSX.CSSProperties = { position: 'absolute', left: 0, top: 0, width: '100%', height: '100%' }

/** Hue gradient (the prototype's `wv.bg`) with the item's backdrop on top while the stream loads or is absent. */
function Picture({ hue, art, style }: { hue: number; art: string | null; style: JSX.CSSProperties }) {
  return (
    <div style={{
      ...style, overflow: 'hidden',
      background: `radial-gradient(circle 1700px at 30% 25%, ${channelColor(hue, 0.42, 0.09)}, ${channelColor(hue, 0.2, 0.05)} 55%, ${channelColor(hue, 0.1, 0.02)})`,
    }}>
      <RemoteImage url={art} style={fillMaxSize} />
      <div style={{ ...fillMaxSize, background: 'radial-gradient(circle 1100px at 50% 50%, rgba(0,0,0,0) 45%, rgba(0,0,0,0.5))' }} />
    </div>
  )
}

// MARK: - Static

let noiseAdded = false

/** One 220×220 grey noise tile (the prototype's feTurbulence tile, grayscale, contrast 1.6), built once into the
 *  `.watch-static` rule, so a flip only mounts a div. */
function noiseTile() {
  if (noiseAdded) return
  noiseAdded = true
  const n = 220
  const c = document.createElement('canvas')
  c.width = n
  c.height = n
  const g = c.getContext('2d')
  if (g == null) return
  const img = g.createImageData(n, n)
  for (let i = 0; i < n * n; i++) {
    const v = 13 + Math.floor(Math.random() * 179)   // 13..191
    img.data[4 * i] = v; img.data[4 * i + 1] = v; img.data[4 * i + 2] = v; img.data[4 * i + 3] = 255
  }
  g.putImageData(img, 0, 0)
  addCSS(`.watch-static, .watch-rewind-band { background-image: url(${c.toDataURL()}); }`)
}

/** ~0.5 s of noise fading out on channel change (the prototype's rbStatic), re-created per `flips` (its `key`).
 *  ponytail: one linear fade instead of the prototype's 5 stepped grain shifts; add keyframes if the steps matter. */
function StaticFlash() {
  noiseTile()
  return <div class="watch-static" style={at(0, 0, 1920, 1080)} />
}

// MARK: - Rewind

/** SF Symbol backward.fill at 56 pt, 64 × 40. */
const rewindGlyph = 'M32 0L0 20L32 40ZM64 0L32 20L64 40Z'

/** Start over on the program on screen, as a VCR rewinding its tape: two bands of static rolling up the picture (CSS;
 *  none with reduced motion), and the OSD's ◀◀ REW over a counter running back to 0:00:00 (eased, 1.1 s), then a fade.
 *  Both are redrawn every frame from `r.started`, as in Swift and Kotlin, so the fade ends with the model's timer even
 *  when the effect mounts late (from the guide, with a new watch screen). Re-created per rewind (its `key`). */
function RewindEffect({ r }: { r: { readonly from: number; readonly started: Instant } }) {
  noiseTile()
  const [, frame] = useState(0)
  useEffect(() => {
    let id = requestAnimationFrame(function tick() { frame(n => n + 1); id = requestAnimationFrame(tick) })
    return () => cancelAnimationFrame(id)
  }, [])
  const elapsed = instantNow() - r.started
  const left = 1 - Math.pow(1 - Math.min(1, elapsed / 1.1), 3)   // ease-out
  const s = Math.round(r.from * (1 - left))
  const osd: JSX.CSSProperties = { font: 'bold 56px monospace', lineHeight: '64px', color: '#fff', textShadow: '3px 3px 0 rgba(0,0,0,0.7)' }
  return (
    <div class="watch-rewind" style={{ ...at(0, 0, 1920, 1080), opacity: Math.max(0, Math.min(1, (AppModel.rewindLength - elapsed) / 0.25)) }}>
      {[0, 1].map(k => <div key={k} class="watch-rewind-roll" style={{ animationDelay: `${-k * 385}ms` }}><div class="watch-rewind-band" /></div>)}
      <Column gap={6} style={{ position: 'absolute', right: 96, top: 72, alignItems: 'flex-end' }}>
        <Row gap={16} style={{ alignItems: 'center' }}>
          <svg width={64} height={40} viewBox="0 0 64 40" style={{ display: 'block', overflow: 'visible' }}>
            <path d={rewindGlyph} fill="rgba(0,0,0,0.7)" transform="translate(3 3)" />
            <path d={rewindGlyph} fill="#fff" />
          </svg>
          <div style={osd}>REW</div>
        </Row>
        <div style={osd}>{`${Math.floor(s / 3600)}:${String(Math.floor(s / 60) % 60).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`}</div>
      </Column>
    </div>
  )
}

// MARK: - Banner

interface Entry { readonly time: string; readonly title: string; readonly isNow: boolean }

/** Midnight Movie's one-off colors (the prototype's bCable block); Late Show never reads them. */
const startOverBadge = hex('#7fd39a')
const numberBevelLight = hex('#ff9a8f')
const numberBevelDark = hex('#a8231a')

/** Port of the prototype's vWatch: every string/color the banner shows. */
class BannerData {
  readonly number: number
  readonly name: string
  readonly tag: string
  readonly title: string
  readonly sub: string
  readonly startT: string
  readonly endT: string
  readonly timeRange: string
  readonly progress: number
  /** Midnight Movie's status badge (LIVE / BREAK / START OVER / PAUSED) and its color. */
  readonly badge: string
  readonly badgeBg: string
  readonly status: string
  readonly statusColor: string
  readonly nextTitle: string
  readonly nextTime: string
  readonly actions: string[]
  readonly focus: number
  /** The progress bar has the remote's focus (no button does then). */
  readonly bar: boolean
  readonly skipCue: number
  /** The open Audio or Subtitles list: the button it's over, its rows, the one on and the one focused. */
  readonly menu: TrackListData | null
  /** The lineup strip: this slot plus the next three, "9:30 PM" then "10:00" until the meridiem changes. */
  readonly tonight: Entry[]

  constructor(info: WatchInfo, model: AppModel, t: Theme, now: Instant, trailerOn: boolean) {
    const ch = info.channel
    const s = info.slot
    const item = s.item
    const pos = info.position
    const paused = model.playback?.paused === true
    const behind = !info.live && now >= s.start && now < s.end ? now - s.start - pos : 0
    const minsIn = Math.trunc(pos / 60)
    this.number = ch.number
    this.name = ch.name
    this.tag = ch.tag
    this.title = item.displayTitle
    this.sub = item.kind === 'movie' ? [item.year != null ? String(item.year) : null, item.contentRating].filter(x => x != null).join(' · ')
      : [`“${item.title}”`, item.seasonEpisode || null].filter(x => x != null).join(' · ')
    this.startT = Clock.time(s.start)
    this.endT = Clock.time(s.end)
    this.timeRange = Clock.range(s.start, s.end)
    this.progress = Math.max(0, Math.min(1, pos / s.duration))
    this.badge = paused ? 'PAUSED' : info.live ? (info.isBreak ? 'BREAK' : 'LIVE') : 'START OVER'
    this.badgeBg = paused ? t.dim : info.live ? (info.isBreak ? breakBadge : t.accent) : startOverBadge
    this.status = paused ? 'Paused'
      : info.live ? (info.isBreak ? `${trailerOn ? 'Trailers' : 'Station break'} · back at ${Clock.time(s.end)}` : `Live · ${minsIn} min in`)
      : behind >= 60 ? `${Math.round(behind / 60)} min behind live`
      : behind >= 3 ? `${Math.round(behind)} s behind live`
      : `From the beginning · ${minsIn} min in`
    this.statusColor = !paused && info.live && !info.isBreak ? t.accent : t.mute
    this.nextTitle = info.next?.item.displayTitle ?? 'More after this'
    this.nextTime = info.next != null ? `${Clock.time(info.next.start)} ${Clock.ap(info.next.start)}` : ''
    this.actions = model.watchActions().map(a => a.label)
    this.focus = model.bannerFocus
    this.bar = model.barFocused && model.bannerFocus >= 0
    this.skipCue = model.skipCue
    const m = model.trackMenu, c = m != null ? model.trackChoices(m.kind) : []
    this.menu = m == null || c.length === 0 ? null
      : { anchor: model.bannerFocus, rows: c.map(x => x.label), on: c.findIndex(x => x.on), focus: Math.min(m.focus, c.length - 1) }
    let prevAP = ''
    this.tonight = [s].concat(model.slots(ch, s.start, s.start + 4 * 3600).filter(x => x.start >= s.end).slice(0, 3)).map(x => {
      const ap = Clock.ap(x.start)
      const e = { time: Clock.time(x.start) + (ap === prevAP ? '' : ` ${ap}`), title: x.item.displayTitle, isNow: x.start === s.start }
      prevAP = ap
      return e
    })
  }
}

function Banner({ d }: { d: BannerData }) {
  const t = useTheme()
  const model = useModel()
  /** The remote's way to run a banner button: focus it, then Select. */
  const run = (i: number) => { model.bannerFocus = i; model.barFocused = false; model.press('select') }
  /** The LG pointer on a button focuses it (and keeps the banner up), except under an open track list. */
  const point = (i: number) => { if (model.trackMenu == null) { model.bannerFocus = i; model.barFocused = false; model.bannerLater() } }
  return t.id === 'MIDNIGHT' ? <Midnight d={d} t={t} run={run} point={point} /> : <Late d={d} t={t} run={run} point={point} />
}

function Late({ d, t, run, point }: { d: BannerData; t: Theme; run: (i: number) => void; point: (i: number) => void }) {
  const row = { ...natural(t.body(26, 600)), color: t.ink }
  return (
    <div style={{ position: 'relative' }}>
      {/* .background(t.bg.opacity(0.92), in: RoundedRectangle(32)).shadow(black 0.5, radius 40, y 30): SwiftUI's radius is
          the blur's sigma, CSS's blur is 2σ. A layer of its own, so the text ticking every second never re-rasters it. */}
      <div style={{ ...fillMaxSize, borderRadius: 32, background: withAlpha(t.bg, 0.92), boxShadow: '0 30px 80px rgba(0,0,0,0.5)', willChange: 'transform' }} />
      <Column gap={22} style={{ position: 'relative', alignItems: 'stretch', padding: '36px 44px 32px' }}>
        <Row gap={48} style={{ alignItems: 'flex-end' }}>
          <Column gap={10} style={{ flex: '1 1 0', minWidth: 0 }}>
            <Row gap={14} style={{ alignItems: 'center' }}>
              <div style={{ ...natural(t.body(28, 700)), color: t.accentInk, background: t.accent, borderRadius: 999, padding: '3px 16px' }}>{d.number}</div>
              <div style={{ ...natural(t.body(28, 600)), color: t.ink }}>{d.name}</div>
              <div style={{ ...natural(t.body(28)), color: t.mute }}>{`· ${d.tag}`}</div>
            </Row>
            <div class="ellipsis" style={{ ...natural(t.heading(72, 700)), color: t.ink, letterSpacing: '-0.025em', maxWidth: '100%' }}>{d.title}</div>
            <Para text={d.sub} style={{ ...natural(t.body(32)), color: t.mute }} />
          </Column>
          <Column gap={8} style={{ width: 460, flex: 'none', alignItems: 'flex-end' }}>
            <div style={{ ...natural(t.body(24, 600)), color: t.dim, letterSpacing: '0.14em', textAlign: 'right', maxWidth: '100%' }}>
              {`Up next · ${d.nextTime}`.toUpperCase()}
            </div>
            <div class="ellipsis" style={{ ...natural(t.body(34, 600)), color: t.ink, maxWidth: '100%' }}>{d.nextTitle}</div>
          </Column>
        </Row>
        <Row gap={24} style={{ alignItems: 'center' }}>
          <div style={row}>{d.startT}</div>
          <Bar progress={d.progress} height={8} radius={4} track={t.line} fill={t.accent} cue={d.skipCue} focused={d.bar} style={{ flex: '1 1 0', minWidth: 0 }} />
          <div style={row}>{d.endT}</div>
          <StatusText status={d.status} statusColor={d.statusColor} focus={d.focus} style={{ ...row, textAlign: 'right', minWidth: 340 }}
            hint={{ ...natural(t.body(24)), color: t.dim }} />
        </Row>
        <div style={{ minHeight: 62, display: 'flex', alignItems: 'center' }}>
          {d.focus >= 0 ? (
            <Row gap={16} style={{ alignItems: 'center' }}>
              {d.actions.map((label, i) => {
                const f = i === d.focus && !d.bar
                return (
                  <div key={i} style={chipBox}>
                    <div {...onTouch(() => run(i))} {...onPoint(() => point(i))} style={{
                      ...natural(t.body(28, 600)), color: f ? t.focusInk : t.ink, background: f ? t.focusBg : t.panel2, borderRadius: 999,
                      padding: '14px 30px', transform: `scale(${f ? 1.05 : 1})`, transition: 'transform 150ms ease-out',
                    }}>{label}</div>
                    {d.menu?.anchor === i && <TrackList m={d.menu} />}
                  </div>
                )
              })}
            </Row>
          ) : (
            <ShareRow spacing={16}>
              <div style={{ ...natural(t.body(22, 600)), color: t.dim, letterSpacing: '0.14em', flex: 'none' }}>{`Tonight on ${d.name}`.toUpperCase()}</div>
              {d.tonight.map((x, i) => (
                <Row key={i} gap={10} style={{
                  alignItems: 'center', flex: '0 1 auto', minWidth: 0, background: x.isNow ? t.panel2 : 'transparent', borderRadius: 999,
                  padding: `10px ${x.isNow ? 20 : 0}px`,
                }}>
                  {x.isNow && <div style={{ width: 8, height: 8, borderRadius: '50%', background: t.accent, flex: 'none' }} />}
                  <div style={{ ...natural(t.body(24, 600)), color: t.accent, flex: 'none' }}>{x.time}</div>
                  <div class="ellipsis" style={{ ...natural(t.body(26)), color: t.ink, minWidth: 0 }}>{x.title}</div>
                </Row>
              ))}
            </ShareRow>
          )}
        </div>
      </Column>
    </div>
  )
}

/**
 * The prototype's bCable block: a 280px channel-number block beside a bevelled info box, everything square and in caps.
 * The number block is as tall as the info box (flex stretch). On tvOS both `.background(fill)`s sit inside the safe area
 * (80 pt sides, 60 pt bottom), so SwiftUI runs them out to the screen's edges: the number block left and down, the info
 * box right and down (the 72/56 pt banner margins).
 */
function Midnight({ d, t, run, point }: { d: BannerData; t: Theme; run: (i: number) => void; point: (i: number) => void }) {
  const side = 72
  const below = 56
  const row = natural(t.body(30, 600))
  const strip = { ...natural(t.body(24, 600)), letterSpacing: '0.04em' }
  return (
    <div style={{ display: 'flex', alignItems: 'stretch' }}>
      {bevelled(t.accent, numberBevelLight, numberBevelDark, { start: side, bottom: below }, { width: 280, flex: 'none' },
        <Column gap={8} style={{ height: '100%', alignItems: 'center', justifyContent: 'center', padding: '20px 14px' }}>
          <div style={{ ...t.heading(132), ...lineBox(132 * 0.9), color: t.accentInk }}>{d.number}</div>{/* CSS line-height: .9 */}
          <div class="clamp2" style={{ ...t.heading(30), ...lineHeightTrim(30), color: t.accentInk, letterSpacing: '0.06em', textAlign: 'center' }}>
            {t.heading(d.name)}
          </div>
        </Column>)}
      <div style={{ width: 6, flex: 'none' }} />
      {bevelled(t.bg, bevelLight, bevelDark, { end: side, bottom: below }, { flex: '1 1 0', minWidth: 0 },
        <Column gap={14} style={{ alignItems: 'stretch', padding: '24px 36px' }}>
          <Row gap={32} style={{ alignItems: 'baseline' }}>
            <div class="ellipsis" style={{ ...t.heading(68), ...lineBox(68), color: t.ink, flex: '1 1 0', minWidth: 0 }}>{t.heading(d.title)}</div>
            <div style={{ ...natural(t.body(32, 700)), color: t.accent, flex: 'none' }}>{d.timeRange}</div>
          </Row>
          <Row gap={22} style={{ alignItems: 'center' }}>
            <Para text={d.sub} style={{ ...row, color: t.mute, flex: '0 1 auto', minWidth: 0 }} />
            <div style={{ ...natural(t.heading(24)), color: t.accentInk, letterSpacing: '0.1em', background: d.badgeBg, padding: '3px 12px 2px', flex: 'none' }}>{d.badge}</div>
            <StatusText status={d.status} statusColor={t.dim} focus={d.focus} style={{ ...row, color: t.dim, flex: 'none' }}
              hint={{ ...natural(t.body(24, 600)), color: t.dim }} />
          </Row>
          <Bar progress={d.progress} height={12} radius={0} track={t.panel2} fill={t.accent} cue={d.skipCue} focused={d.bar} />
          <Row gap={24} style={{ alignItems: 'center', minHeight: 52 }}>
            {d.focus >= 0 ? (
              // The chips take the whole row: five of them at this size leave no room for the Next line.
              <Row gap={8} style={{ flex: '1 1 0', minWidth: 0 }}>
                {d.actions.map((label, i) => {
                  const f = i === d.focus && !d.bar
                  return (
                    <div key={i} style={chipBox}>
                      <div class="ellipsis" {...onTouch(() => run(i))} {...onPoint(() => point(i))} style={{
                        ...natural(t.heading(26)), color: f ? t.focusInk : t.ink, letterSpacing: '0.04em', background: f ? t.focusBg : t.panel2,
                        padding: '8px 18px', minWidth: 0,
                      }}>{label.toUpperCase()}</div>
                      {d.menu?.anchor === i && <TrackList m={d.menu} />}
                    </div>
                  )
                })}
              </Row>
            ) : d.tonight.length === 0 ? (
              // The prototype's Next line; the "Tonight on" strip says the same and more, so it takes the row when it has entries.
              <div class="ellipsis" style={{ ...natural(t.heading(28, 700)), color: t.dim, letterSpacing: '0.04em', flex: '1 1 0', minWidth: 0 }}>
                {`Next${d.nextTime === '' ? '' : ' ' + d.nextTime}: `.toUpperCase()}<span style={{ color: t.ink }}>{d.nextTitle.toUpperCase()}</span>
              </div>
            ) : (
              <ShareRow spacing={28}>
                <div style={{ ...strip, color: t.dim, flex: 'none' }}>{`Tonight on ${d.name}`.toUpperCase()}</div>
                {d.tonight.map((x, i) => (
                  <Row key={i} gap={10} style={{ flex: '0 1 auto', minWidth: 0, background: x.isNow ? t.panel2 : 'transparent', padding: `6px ${x.isNow ? 14 : 0}px` }}>
                    <div style={{ ...strip, color: t.accent, flex: 'none' }}>{x.time}</div>
                    <div class="ellipsis" style={{ ...strip, color: t.ink, minWidth: 0 }}>{x.title.toUpperCase()}</div>
                  </Row>
                ))}
              </ShareRow>
            )}
          </Row>
        </Column>)}
    </div>
  )
}

/** The house spot is an ad (Play's ads policy, App Store 2.5.18): an "Ad" label top left (below a Paused chip), and a
 *  Skip chip bottom right (above the banner) counting down 5 s, then naming the press that skips it; a click on the chip
 *  (the Magic Remote's pointer) skips it too. */
function SpotChips({ started, now }: { started: Instant; now: Instant }) {
  const model = useModel()
  const t = useTheme()
  const mm = t.id === 'MIDNIGHT'
  const chip: JSX.CSSProperties = { position: 'absolute', background: 'rgba(0,0,0,0.6)', borderRadius: t.radius, padding: '18px 28px' }
  const text = { ...natural(t.body(32, 600)), color: '#fff' }
  const skippable = SpotOnAir.skippable
  const left = Math.min(Math.max(Math.ceil(AppModel.spotSkipDelay - (now - started)), 1), Math.trunc(AppModel.spotSkipDelay))
  return (
    <>
      <Row gap={14} style={{ ...chip, left: 80, top: chipTop(model) + (model.playback?.paused === true ? 88 : 0), alignItems: 'center' }}>
        <div style={{
          ...natural(mm ? t.heading(24) : t.body(24, 700)), color: t.accentInk, letterSpacing: '0.1em', background: t.accent,
          borderRadius: mm ? 0 : 8, padding: '3px 12px 2px',
        }}>{'Ad'.toUpperCase()}</div>
        <div style={text}>Message from Soyboi</div>
      </Row>
      <div class="hov" {...onTouch(() => model.skipSpot())} style={{
        ...chip, ...text, right: 72, bottom: model.bannerVisible ? 480 : 64, whiteSpace: 'pre', color: skippable ? '#fff' : 'rgba(255,255,255,0.72)',
      }}>
        {skippable ? <>{'Press '}<OkGlyph />{' to skip  ›'}</> : `Skip in ${left}`}
      </div>
    </>
  )
}

/** The top-left chips (Paused, Ad) sit at y 72, or below the pointer's Back and Play/Pause (bottom edge ≈ 104) while
 *  those show: the Kotlin phone's chrome is small enough beside the scaled-down stage, the web's 76 px buttons aren't. */
function chipTop(model: AppModel): number {
  return touchChromeShows(model) ? 120 : 72
}

/** The remote's OK (the Siri Remote's button.programmable): a ring and a dot, 1 em, centred on the text. */
function OkGlyph() {
  return (
    <svg width="1em" height="1em" viewBox="0 0 20 20" style={{ verticalAlign: '-0.15em' }}>
      <circle cx="10" cy="10" r="9" fill="none" stroke="currentColor" stroke-width="2" />
      <circle cx="10" cy="10" r="7.2" fill="currentColor" />
    </svg>
  )
}

/** Status line plus " · Press ◉ for options" while the banner shows only the program. The hint is a button for the
 *  Magic Remote's pointer: a click is that press. */
function StatusText({ status, statusColor, focus, style, hint }: {
  status: string; statusColor: string; focus: number; style: JSX.CSSProperties; hint: JSX.CSSProperties
}) {
  const model = useModel()
  return (
    <div style={style}>
      <span style={{ color: statusColor }}>{status}</span>
      {focus < 0 && <span class="hov" {...onTouch(() => model.press('select'))} style={hint}>{' · Press '}<OkGlyph />{' for options'}</span>}
    </div>
  )
}

/** A banner button and, over it, its track list when that's open. */
const chipBox: JSX.CSSProperties = { position: 'relative', display: 'flex', minWidth: 0 }

interface TrackListData { readonly anchor: number; readonly rows: string[]; readonly on: number; readonly focus: number }

/** The banner's Audio or Subtitles list, its bottom 16 above its button and its left edge on the button's: a check by the
 *  choice that's on, the remote's focus, six rows at most (the list slides to keep the focus in view). A click plays a
 *  row, as the remote's OK does. */
function TrackList({ m }: { m: TrackListData }) {
  const t = useTheme()
  const model = useModel()
  const rowH = 64, rowW = 560, n = m.rows.length
  const h = Math.min(n, 7) * rowH, travel = n * rowH - h   // an odd count, so the middle row is whole and so are the rows around it
  const ty = useSticky(Math.max(0, Math.min(travel, m.focus * rowH - (h - rowH) / 2)))
  const thumb = h * h / (n * rowH)
  const square = t.radius === 0
  const pick = (i: number) => { if (model.trackMenu != null) model.trackMenu = { ...model.trackMenu, focus: i }; model.press('select') }
  return (
    <div style={{
      position: 'absolute', left: 0, bottom: 'calc(100% + 16px)', padding: 10, background: t.panel, borderRadius: square ? 0 : 24,
      boxShadow: '0 16px 60px rgba(0,0,0,0.5)',
    }}>
      {/* Rows out of view take no clicks. */}
      <div style={{ width: rowW, height: h, overflow: 'hidden' }}>
        <div style={{ transform: `translateY(${-ty}px)`, transition: 'transform 220ms ease-in-out' }}>
          {m.rows.map((label, i) => {
            const f = i === m.focus, ink = f ? t.focusInk : t.ink
            return (
              <Row key={i} gap={14} {...onTouch(() => pick(i))} {...onPoint(() => { if (model.trackMenu != null) model.trackMenu = { ...model.trackMenu, focus: i } })} style={{
                alignItems: 'center', height: rowH, padding: '0 22px', borderRadius: square ? 0 : 14, background: f ? t.focusBg : 'transparent',
              }}>
                <div style={{ opacity: i === m.on ? 1 : 0 }}><Checkmark color={ink} w={23} h={21} /></div>
                <div class="ellipsis" style={{ ...natural(t.body(28, 600)), color: ink, minWidth: 0 }}>{label}</div>
              </Row>
            )
          })}
        </div>
      </div>
      {travel > 0 && <div style={{   // the scroll bar, in the right padding
        position: 'absolute', top: 10, right: 3, width: 4, height: thumb, borderRadius: 2, background: t.mute,
        transform: `translateY(${(h - thumb) * ty / travel}px)`, transition: 'transform 220ms ease-in-out',
      }} />}
    </div>
  )
}

/** The progress bar, with a ⏪ / ⏩ tag over the playhead while `cue` is −1 / +1 (a skip back or ahead), and a 24-pt knob
 *  on the playhead while it has the remote's focus. */
function Bar({ progress, height, radius, track, fill, cue = 0, focused = false, style }: {
  progress: number; height: number; radius: number; track: string; fill: string; cue?: number; focused?: boolean; style?: JSX.CSSProperties
}) {
  const theme = useTheme(), ink = theme.accentInk
  const bar = useRef<HTMLDivElement>(null)
  const [w, setW] = useState(0)   // the bar's width, read once it's laid out (before the tag's first paint)
  useLayoutEffect(() => { setW(bar.current!.offsetWidth) })
  const { value: dir, leaving } = useExit(cue !== 0 ? cue : null, 200)
  // SwiftUI's backward.fill / forward.fill at 22 pt bold on a 60 × 36 tag, its center 24 above the bar's top, kept
  // inside the bar's ends.
  const x = Math.min(Math.max(w * progress, 30), w - 30)
  return (
    <div ref={bar} style={{ position: 'relative', height, borderRadius: radius, background: track, ...style }}>
      <div style={{ width: `${progress * 100}%`, height: '100%', borderRadius: radius, background: fill }} />
      {focused && <div style={{   // the remote's focus: a 24-pt knob on the playhead
        position: 'absolute', left: `${progress * 100}%`, top: height / 2 - 12, width: 24, height: 24, marginLeft: -12,
        borderRadius: radius > 0 ? 12 : 0, background: theme.ink, boxShadow: '0 2px 6px rgba(0,0,0,0.5)',
      }} />}
      {dir != null && (
        <svg width={60} height={36} viewBox="0 0 60 36" style={{
          position: 'absolute', left: x - 30, top: -42, display: 'block',
          animation: leaving ? 'fadeOut 200ms ease-out forwards' : 'fadeIn 200ms ease-out backwards',
        }}>
          <rect width={60} height={36} rx={radius > 0 ? 18 : 0} fill={fill} />
          <path d={dir < 0 ? 'M30 9L15 18L30 27ZM45 9L30 18L45 27Z' : 'M15 9L30 18L15 27ZM30 9L45 18L30 27Z'} fill={ink} />
        </svg>
      )}
    </div>
  )
}

/** SwiftUI's HStack for the "Tonight on" strip, as tvOS runs it: the kicker (first child, `flex: none`) at its full
 *  width, the entries (`flex: 0 1 auto`) sharing what's left, each title cut with an ellipsis.
 *  ponytail: CSS flex shrinks the entries in proportion to their widths, not tvOS's equal shares least-flexible first, and
 *  skips its second-pass truncation quirk; measure the titles with a canvas if the difference shows. */
function ShareRow({ spacing, children }: { spacing: number; children: ComponentChildren }) {
  return <Row gap={spacing} style={{ alignItems: 'center', minWidth: 0, maxWidth: '100%' }}>{children}</Row>
}

/** `.frame(height: h)` on one line of display type: an `h`-tall box with the text centred in it, overflowing unclipped
 *  (CSS centres the glyphs in a line box shorter than the face's). */
function lineBox(h: number): JSX.CSSProperties {
  return { height: h, lineHeight: `${h}px`, whiteSpace: 'nowrap' }
}

/** `color.copy(alpha = a)` for a theme's #rrggbb token. */
function withAlpha(c: string, a: number): string {
  const n = parseInt(c.slice(1), 16)
  return `rgba(${n >> 16},${(n >> 8) & 255},${n & 255},${a})`
}

/** CSS `border: 6px solid` with mitred light top/left and dark bottom/right edges around a solid fill. The fill (only)
 *  runs `bleed…` points past the box, SwiftUI's background extending into the safe area. `style` sizes the box; the
 *  content sits inside the 6 px bevel. */
function bevelled(fill: string, light: string, dark: string, bleed: { start?: number; end?: number; bottom?: number },
                  style: JSX.CSSProperties, content: ComponentChildren) {
  return (
    <div style={{ position: 'relative', display: 'flex', ...style }}>
      <div style={{ position: 'absolute', left: -(bleed.start ?? 0), top: 0, right: -(bleed.end ?? 0), bottom: -(bleed.bottom ?? 0), background: fill }} />
      <div style={{ position: 'absolute', left: 0, top: 0, right: 0, bottom: 0, border: '6px solid', borderColor: `${light} ${dark} ${dark} ${light}` }} />
      <div style={{ position: 'relative', flex: '1 1 auto', minWidth: 0, padding: 6 }}>{content}</div>
    </div>
  )
}
