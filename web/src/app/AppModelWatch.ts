// Port of android/app AppModelWatch.kt. Watching: banner, channel up/down, last channel, pause/time-shift (the
// prototype's k_watch/wInfo/flip/togglePause/watchActs), the trip to the TV's Home screen and back, and the house spot.
import { asObject, toIntOrNull } from '../core/Lang'
import { Channel, MediaTrack, Slot, TrackSelection } from '../core/Models'
import { SpotRotation } from '../core/SpotRotation'
import { Instant, now } from '../core/Time'
import { AppModel, Playback } from './AppModel'
import type { RemoteCommand } from './Remote'

/** What the picture is showing right now (the prototype's wInfo). */
export class WatchInfo {
  constructor(
    readonly channel: Channel,
    readonly slot: Slot,
    /** Seconds into the slot. `runtime ≤ position < duration` is the break after the program. */
    readonly position: number,
    readonly live: boolean,
    /** What the channel's current schedule airs at `slot.end` (what live rolls into). */
    readonly next: Slot | null,
  ) {}
  get isBreak(): boolean { return this.position >= this.slot.item.runtime }
}

/** One banner button: the Swift `(label: String, run: () -> Void)` tuple. */
export class WatchAction {
  constructor(readonly label: string, readonly run: () => void) {}
}

/** A row of the banner's Audio or Subtitles list. */
export class TrackChoice {
  constructor(readonly label: string, readonly on: boolean, readonly apply: (s: TrackSelection) => TrackSelection) {}
}

/** The banner's open Audio or Subtitles list: which, and the row the remote is on. */
export interface TrackMenu { readonly kind: MediaTrack.Kind; readonly focus: number }

/**
 * The house spot on screen: WatchScreen holds its stream; the remote and the screen reader need when it started and whether
 * it can be skipped yet. Its own object since an extension can't add stored properties to AppModel. It isn't behind the
 * model's Proxy, so it changes only in `spotChanged` (and its timer), which schedule the render themselves (`redraw`).
 */
export const SpotOnAir = {
  started: null as Instant | null,
  /** The 5 s countdown is done: OK / Right (a click with the pointer) end the spot. */
  skippable: false,
  end: (() => {}) as () => void,
}

/** Schedules a render the way every model change does: an assignment through the model's Proxy (main.tsx `observable`). */
function redraw(m: AppModel) {
  m.bannerFocus = m.bannerFocus
}

declare module './AppModel' {
  namespace AppModel {
    /** The bundled spots (Android's res/raw beacon_*.mp4, copied to media/beacon-*.mp4 by build.mjs), in the order they
     *  take turns. Each is 30 s, 1080p H.264 + AAC. */
    const spotURLs: readonly string[]
    const spotLength: number
    /** Seconds into the spot before it can be skipped. */
    const spotSkipDelay: number
    /** "English"; the server's own title ("Commentary", "English (AC3 5.1)") when the language is unknown or shared by another track. */
    function trackName(t: MediaTrack, among: readonly MediaTrack[]): string
  }
  interface AppModel {
    watchInfo(at?: Instant): WatchInfo | null
    pressWatch(cmd: RemoteCommand): void
    /** In a break: the program that just ended, from its beginning (Play/Pause, or the break card's pill). */
    restartFromBreak(): void
    /** In a break: the next program, from its beginning, without waiting for its slot (Select, or the break card's pill). */
    startNextFromBreak(): void
    /** Hide the banner 6 s from now. */
    bannerLater(): void
    hideBanner(): void
    /** Channel up (+1) / down (−1) through the visible channels, wrapping. */
    flip(d: number): void
    /** The channel numbered `n` in this profile's lineup: a hidden one counts as missing. Kotlin's `channel(numbered)`. */
    channelNumbered(n: number): Channel | null
    /** The channel the digits typed so far name. */
    readonly entryChannel: Channel | null
    /** A number key on the guide or the player with nothing over them: the box in the top-right corner collects up to
     *  three digits and tunes 2 s after the last one, at the third, or on OK. Digits do nothing anywhere else. */
    pressDigit(d: number): void
    /** While the box collects digits, OK tunes now and Back cancels; any other press closes it and does what it does.
     *  True when the box took the press. */
    pressEntry(cmd: RemoteCommand): boolean
    /** Put the box away without tuning: a click, a channel change or a screen went elsewhere. */
    closeEntry(): void
    /** Tune to the typed number like a channel change (the tune-mode setting applies). The channel already on screen
     *  just closes the box; a number with no channel says so for 2 s and changes nothing. */
    commitEntry(): void
    /** Settles once the server has been asked to stop both streams (Samsung's exit waits for that). */
    enteredBackground(): Promise<void>
    returnedToForeground(): void
    dueSpot(ch: Channel, slot: Slot, at?: Instant): string | null
    spotChanged(end: (() => void) | null): void
    skipSpot(): void
    goLast(): void
    skip(seconds: number): void
    togglePause(): void
    watchActions(): WatchAction[]
    /** The rows of the banner's Audio or Subtitles list, the one playing marked `on`. */
    trackChoices(kind: MediaTrack.Kind): TrackChoice[]
    /** The banner's Audio or Subtitles button: its list opens on the choice that's on, and holds the banner up. */
    openTrackMenu(kind: MediaTrack.Kind): void
    pressTrackMenu(cmd: RemoteCommand): void
    dropSubtitles(itemID: string): void
    watchTick(at?: Instant): void
  }
}

Object.assign(AppModel, {
  spotURLs: ['media/beacon-they-live.mp4', 'media/beacon-rental.mp4', 'media/beacon-trail.mp4', 'media/beacon-cafe.mp4'],
  spotLength: 30,
  spotSkipDelay: 5,
  trackName(t: MediaTrack, among: readonly MediaTrack[]): string {
    const lang = MediaTrack.languageName(t.language)
    const shared = among.filter(x => x.language === t.language).length > 1
    return (lang == null || shared) && t.title !== '' ? t.title : lang ?? `Track ${t.id}`
  },
})

AppModel.prototype.watchInfo = function (this: AppModel, at: Instant = now()): WatchInfo | null {
  const pb = this.playback
  if (pb == null) return null
  const ch = this.channel(pb.channelID) ?? this.visibleChannels[0]
  if (ch == null) return null
  // The slot tune() snapshotted, unless its channel is gone: a library refresh reshuffles every lineup and the program
  // must not change under the viewer. Live rolls into the current schedule once the snapshot ends.
  const kept = ch.id === pb.channelID ? pb.slot ?? null : null
  if (pb.mode === 'live') {
    const s = (kept != null && at < kept.end ? kept : null) ?? this.slotOnAir(ch, at)
    if (s == null) return null
    return new WatchInfo(ch, s, at - s.start, true, this.slotOnAir(ch, s.end))
  }
  const s = kept ?? this.slot(ch, pb.programStart) ?? this.slotOnAir(ch, at)
  if (s == null) return null
  return new WatchInfo(ch, s, pb.offset + (pb.paused ? 0 : at - pb.startedAt), false, this.slotOnAir(ch, s.end))
}

AppModel.prototype.pressWatch = function (this: AppModel, cmd: RemoteCommand) {
  const pb = this.playback
  if (pb == null) return
  if (this.trackMenu != null && cmd !== 'play') { this.pressTrackMenu(cmd); return }
  switch (cmd) {
    // From the banner's buttons Up goes to its progress bar, and Down comes back; otherwise they change channels.
    case 'up':
      if (this.bannerVisible && this.bannerFocus >= 0) { this.barFocused = true; this.bannerLater() } else this.flip(1)
      break
    case 'down':
      if (this.bannerVisible && this.bannerFocus >= 0 && this.barFocused) { this.barFocused = false; this.bannerLater() } else this.flip(-1)
      break
    case 'back':   // the info banner alone never holds Back
      if (this.bannerVisible && this.bannerFocus >= 0) this.hideBanner(); else this.toGuide(pb.channelID)
      break
    case 'play': if (this.watchInfo()?.isBreak === true) this.restartFromBreak(); else this.togglePause(); break   // pausing a break holds nothing
    case 'left': case 'right': case 'select': {
      if (this.bannerVisible && this.bannerFocus >= 0 && this.barFocused) {   // the progress bar: Left / Right skip, OK is Play/Pause
        if (cmd !== 'select') this.skip(cmd === 'left' ? -10 : 10)
        else if (this.watchInfo()?.isBreak !== true) this.togglePause()
        this.bannerLater(); return
      }
      if (this.bannerVisible && this.bannerFocus >= 0) {
        const acts = this.watchActions()
        if (acts.length === 0) { this.hideBanner(); return }   // no watchInfo (channels gone mid-program)
        if (cmd === 'left') this.bannerFocus = Math.max(0, this.bannerFocus - 1)
        else if (cmd === 'right') this.bannerFocus = Math.min(acts.length - 1, this.bannerFocus + 1)
        else acts[Math.min(this.bannerFocus, acts.length - 1)].run()
        this.bannerLater(); return
      }
      // The spot: Select / Right skip it once its countdown is done, and do nothing before that.
      if (cmd !== 'left' && SpotOnAir.started != null) { this.skipSpot(); return }
      const info = this.watchInfo()
      if (cmd === 'select' && info != null && info.isBreak && info.next != null) { this.startNextFromBreak(); return }   // the break card's OK
      if (cmd === 'left') { this.skip(-10); return }
      if (cmd === 'right') { this.skip(10); return }
      this.bannerVisible = true; this.bannerFocus = 0; this.barFocused = false
      this.bannerLater()
    }
  }
}

AppModel.prototype.restartFromBreak = function (this: AppModel) {
  const info = this.watchInfo()
  if (info == null) return
  this.tune(info.channel.id, 'start', info.slot.start)
}

AppModel.prototype.startNextFromBreak = function (this: AppModel) {
  const info = this.watchInfo()
  if (info == null || info.next == null) return
  this.tune(info.channel.id, 'start', info.next.start)
}

AppModel.prototype.bannerLater = function (this: AppModel) {
  this.later('banner', 6, () => { if (this.overlay == null && this.trackMenu == null) { this.bannerVisible = false; this.bannerFocus = -1 } })
}

AppModel.prototype.hideBanner = function (this: AppModel) {
  this.cancelTimer('banner'); this.bannerVisible = false; this.bannerFocus = -1; this.trackMenu = null
}

AppModel.prototype.flip = function (this: AppModel, d: number) {
  const list = this.visibleChannels
  const pb = this.playback
  if (list.length === 0 || pb == null) return
  const i = Math.max(0, list.findIndex(c => c.id === pb.channelID))
  this.tune(list[((i + d) % list.length + list.length) % list.length].id, 'live')
}

// MARK: Number keys

AppModel.prototype.channelNumbered = function (this: AppModel, n: number): Channel | null {
  return this.channels.find(c => c.number === n && !this.isHidden(c.id)) ?? null
}

Object.defineProperty(AppModel.prototype, 'entryChannel', {
  get(this: AppModel): Channel | null {
    const n = this.channelEntry == null ? null : toIntOrNull(this.channelEntry.digits)
    return n == null ? null : this.channelNumbered(n)
  },
})

AppModel.prototype.pressDigit = function (this: AppModel, d: number) {
  if (this.overlay != null || this.editingField != null || this.trackMenu != null || (this.screen !== 'guide' && this.screen !== 'watch')) return
  if (this.attracting) this.attractReset()   // the box wakes the screen instead of sitting on the screensaver
  const e = this.channelEntry
  const digits = (e == null || e.missing ? '' : e.digits) + String(d)
  this.channelEntry = this.lastEntry = { digits, missing: false }
  if (digits.length === 3) this.commitEntry(); else this.later('entry', 2, () => this.commitEntry())
}

AppModel.prototype.pressEntry = function (this: AppModel, cmd: RemoteCommand): boolean {
  if (this.channelEntry?.missing !== false) return false
  if (cmd === 'select') { this.commitEntry(); return true }
  this.closeEntry()
  return cmd === 'back'
}

AppModel.prototype.closeEntry = function (this: AppModel) {
  this.cancelTimer('entry'); this.channelEntry = null
}

AppModel.prototype.commitEntry = function (this: AppModel) {
  const e = this.channelEntry
  const n = e == null || e.missing ? null : toIntOrNull(e.digits)
  if (e == null || n == null) return
  this.closeEntry()
  if (this.overlay != null || this.editingField != null || (this.screen !== 'guide' && this.screen !== 'watch')) return   // moved on meanwhile
  const ch = this.channelNumbered(n)
  if (ch == null) {
    this.channelEntry = this.lastEntry = { digits: e.digits, missing: true }
    this.later('entry', 2, () => { this.channelEntry = null })
    return
  }
  if (this.screen === 'watch' && this.playback?.channelID === ch.id) return
  this.tune(ch.id, 'live')
}

// MARK: Background (visibilitychange)

/** Leaving for the TV's Home screen (Back from the guide, or the Home button): ask the server to stop both streams now,
 *  instead of leaving it transcoding until it notices nobody is pulling, and hold a started-over program's place.
 *  A live program needs nothing held: it rejoins live on the way back, like changing back to a channel. */
AppModel.prototype.enteredBackground = function (this: AppModel) {
  if (this.backgrounded) return Promise.resolve()
  this.backgrounded = true
  const pb = this.playback
  const info = this.watchInfo()
  if (pb != null && pb.mode === 'start' && !pb.paused && info != null) {
    this.playback = { ...pb, offset: info.position, paused: true }; this.resumeAfterBackground = true
  } else if (pb != null && pb.mode === 'live' && !pb.markDue) this.playback = { ...pb, fromStart: false }   // a caught-up start-over rejoins live on the way back: no longer from the beginning
  this.cancelTimer('seek'); this.closeEntry()   // a typed number doesn't tune behind the Home screen
  if (this.screen === 'link') this.linkTask?.cancel()   // the PIN poll would keep running behind the Home screen
  const server = this.server
  if (server == null) return Promise.resolve()
  return Promise.all([server.stopPlayback('watch'), server.stopPlayback('preview')]).then(() => {}, () => {})
}

/** Back from the Home screen: reload whatever is on screen (its stream was stopped), resume a held program where it was,
 *  and show the banner so the viewer sees where they landed. A TV never re-picks a Plex address (phones only). */
AppModel.prototype.returnedToForeground = function (this: AppModel) {
  if (!this.backgrounded) return
  this.backgrounded = false
  const pb = this.playback
  if (this.resumeAfterBackground && pb != null) this.playback = { ...pb, paused: false, startedAt: now() }
  this.resumeAfterBackground = false
  this.foregroundSerial += 1   // the reload
  if (this.screen === 'watch') {
    this.tunes += 1
    if (this.watchInfo()?.isBreak !== true) { this.bannerVisible = true; this.bannerFocus = -1; this.trackMenu = null; this.bannerLater() }   // a break's card says it all
  }
  if (this.screen === 'link') this.startLink()   // a fresh code: the poll stopped in the background
  void this.checkRecentlyAdded()   // opening the app brings the "what's new" channels up to date
}

// MARK: Soyboi spot

/** The spot this break opens with, or null. One trailer break in 15 with room for a whole spot (counted once per
 *  break, across launches), the spots taking turns; never on channel 99, never with the setting or this channel's
 *  trailers off. */
AppModel.prototype.dueSpot = function (this: AppModel, ch: Channel, slot: Slot, at: Instant = now()): string | null {
  const spots = AppModel.spotURLs
  const first = spots[0]
  if (first == null) return null
  if (this.spotOverride) { this.spotOverride = false; return first }
  if (!this.settings.houseSpots || !this.settings.trailerBreaks || !ch.trailerBreaks || ch.isScrambled ||
    slot.end - at < AppModel.spotLength + 2) return null
  let r = new SpotRotation()
  const d = AppModel.defaults
  const saved = d.get('spotRotation')
  if (saved != null) try { r = SpotRotation.fromJson(asObject(JSON.parse(saved))) } catch { /* unreadable: start over */ }
  const due = r.next(`${ch.id}|${Math.floor(slot.start)}`)
  d.set('spotRotation', JSON.stringify(r.toJson()))
  return due ? spots[r.pick(spots.length)] : null
}

/** WatchScreen: the spot started playing (`end` hands over to the trailers, like the spot's own end), or, with null, it's gone. */
AppModel.prototype.spotChanged = function (this: AppModel, end: (() => void) | null) {
  this.cancelTimer('spot')
  SpotOnAir.started = end == null ? null : now(); SpotOnAir.skippable = false; SpotOnAir.end = end ?? (() => {})
  if (end != null) this.later('spot', AppModel.spotSkipDelay, () => { SpotOnAir.skippable = true; redraw(this) })
  redraw(this)
}

/** OK / Right, or a click on the Skip chip, during the spot: once the countdown is done, end it like its own end. */
AppModel.prototype.skipSpot = function (this: AppModel) {
  if (SpotOnAir.started == null || !SpotOnAir.skippable) return
  const end = SpotOnAir.end
  this.spotChanged(null); end()
}

AppModel.prototype.goLast = function (this: AppModel) {
  const c = this.lastChannel
  if (c != null) this.tune(c.id, 'live'); else this.toast('No last channel yet. Change channels first.')
}

/** Left / Right while watching: 10 s back or ahead per press. Presses within 0.6 s combine into one move, then the player
 *  reloads at the new spot (the server transcodes from an offset, so a seek is a reload). Rewinding a live program
 *  time-shifts it; fast-forwarding a shifted one can catch up to live. */
AppModel.prototype.skip = function (this: AppModel, seconds: number) {
  let pb = this.playback
  if (pb == null) return
  const info = this.watchInfo()
  if (info == null) return
  this.bannerVisible = true; this.bannerLater()
  const runtime = info.slot.item.runtime
  const sinceStart = now() - info.slot.start
  if (info.position >= runtime) return   // a trailer break: nothing to scrub
  // A program started before it airs has no live point to catch up to yet: it stays time-shifted.
  const liveEdge = sinceStart > 0 ? sinceStart : Infinity
  const target = Math.max(0, Math.min(info.position + seconds, runtime - 1, liveEdge))
  const fromStart = pb.fromStart && info.slot.id === pb.slot?.id   // not onto the next program, joined midway once the kept slot ended
  if (target >= liveEdge - 1) {   // caught up, or never left live
    if (pb.mode === 'live') return
    pb = Playback(pb.channelID, 'live', info.slot.start, { slot: info.slot, tracks: pb.tracks, fromStart })
  } else {
    pb = Playback(pb.channelID, 'start', info.slot.start, { offset: target, paused: pb.paused, slot: info.slot, tracks: pb.tracks, fromStart })
  }
  this.playback = pb
  this.skipCue = seconds < 0 ? -1 : 1; this.later('skip-cue', 1, () => { this.skipCue = 0 })
  this.later('seek', 0.6, () => { this.tunes += 1 })
}

/** Play/Pause: live becomes time-shifted and paused; paused resumes; playing pauses. */
AppModel.prototype.togglePause = function (this: AppModel) {
  const pb = this.playback
  if (pb == null) return
  const info = this.watchInfo()
  if (info == null) return
  this.playback = pb.mode === 'live'
    ? Playback(pb.channelID, 'start', info.slot.start, { offset: info.position, paused: true, slot: info.slot, tracks: pb.tracks, fromStart: pb.fromStart && info.slot.id === pb.slot?.id })   // as in `skip`
    : pb.paused ? { ...pb, startedAt: now(), paused: false }
    : { ...pb, offset: info.position, paused: true }
  this.bannerVisible = true; this.bannerLater()
}

/** Banner buttons: [Pause | Play] (not in a break), [Start over | Back to live], Program info, Audio, Subtitles,
 *  Last channel · N. Audio and Subtitles open a list of the program's tracks over the button, the one on checked. */
AppModel.prototype.watchActions = function (this: AppModel): WatchAction[] {
  const info = this.watchInfo()
  if (info == null) return []
  const ch = info.channel, slot = info.slot
  const a: WatchAction[] = []
  if (!info.isBreak) a.push(new WatchAction(this.playback?.paused === true ? 'Play' : 'Pause', () => this.togglePause()))
  if (info.live) a.push(new WatchAction('Start over', () => this.tune(ch.id, 'start', slot.start)))
  else a.push(new WatchAction('Back to live', () => this.tune(ch.id, 'live', null, true)))
  a.push(new WatchAction('Program info', () => this.openInfo(this.key(ch, slot))))
  for (const [kind, name] of [['audio', 'Audio'], ['subtitle', 'Subtitles']] as const) {
    if (this.trackChoices(kind).length >= 2) a.push(new WatchAction(name, () => this.openTrackMenu(kind)))   // one audio track: nothing to pick
  }
  const last = this.lastChannel
  if (last != null) a.push(new WatchAction(`Last channel · ${last.number}`, () => this.goLast()))
  return a
}

/** Subtitles: Off, Forced only (only while it's the choice: the server's pick, which the list can't offer back once
 *  left), then each track. */
AppModel.prototype.trackChoices = function (this: AppModel, kind: MediaTrack.Kind): TrackChoice[] {
  const item = this.watchInfo()?.slot.item
  if (item == null) return []
  const tracks = this.playbackTracks.filter(t => t.kind === kind), sel = this.trackSelection(this.playbackTracks, item.id)
  if (kind === 'audio') {
    let on = tracks.findIndex(t => t.id === sel.audioID)
    if (on < 0) on = Math.max(0, tracks.findIndex(t => t.isDefault === true))
    return tracks.map((t, i) => new TrackChoice(AppModel.trackName(t, tracks), i === on, s => s.copy({ audioID: t.id })))
  }
  if (tracks.length === 0) return []
  const cur = sel.subtitle
  const rows = [new TrackChoice('Off', cur.kind === 'off', s => s.copy({ subtitle: TrackSelection.Subtitle.off }))]
  if (cur.kind === 'auto') rows.push(new TrackChoice('Forced only', true, s => s.copy({ subtitle: TrackSelection.Subtitle.auto })))
  for (const t of tracks) {
    rows.push(new TrackChoice(AppModel.trackName(t, tracks), cur.kind === 'track' && cur.id === t.id, s => s.copy({ subtitle: TrackSelection.Subtitle.track(t.id) })))
  }
  return rows
}

AppModel.prototype.openTrackMenu = function (this: AppModel, kind: MediaTrack.Kind) {
  this.cancelTimer('banner')
  this.trackMenu = { kind, focus: Math.max(0, this.trackChoices(kind).findIndex(c => c.on)) }
}

/** Up / Down move through the list (the wheel too), OK plays the choice (a reload, unless it's the one on, or a drawn
 *  subtitle), Back puts the list away. Left / Right do nothing while it's open. */
AppModel.prototype.pressTrackMenu = function (this: AppModel, cmd: RemoteCommand) {
  const m = this.trackMenu
  if (m == null) return
  const c = this.trackChoices(m.kind)
  if (cmd === 'up' || cmd === 'down') {
    this.trackMenu = { ...m, focus: Math.max(0, Math.min(c.length - 1, m.focus + (cmd === 'down' ? 1 : -1))) }
  } else if (cmd === 'select') {
    this.trackMenu = null; this.bannerLater()
    const pick = c[m.focus]
    if (pick != null && !pick.on) setTracks(this, null, pick.apply)
  } else if (cmd === 'back') {
    this.trackMenu = null; this.bannerLater()
  }
}

/** Per-program override from the banner: the player reloads at the current position with the new tracks. The override
 *  holds for that program only (`itemID`, null = the one on now). */
function setTracks(m: AppModel, itemID: string | null, change: (s: TrackSelection) => TrackSelection) {
  const pb = m.playback
  if (pb == null) return
  const id = itemID ?? m.watchInfo()?.slot.item.id
  if (id == null) return
  const old = m.trackSelection(m.playbackTracks, id), next = change(old)
  m.playback = { ...pb, tracks: { itemID: id, selection: next } }
  // Web: a subtitle the app draws, or none, is the same stream on Jellyfin/Emby (subtitle -1), so only an audio change or a
  // burned-in subtitle (before or after) reloads it; the drawn one just switches over the picture.
  const burns = (s: TrackSelection) => s.subtitle.kind === 'track' && m.server?.drawnSubtitleURL(id, s.subtitle.id) == null
  if (m.server?.drawsSubtitles !== true || next.audioID !== old.audioID || burns(old) || burns(next)) m.tunes += 1
}

/** Program `itemID`, asked for with a subtitle, didn't start: reload it at the same position with subtitles off. */
AppModel.prototype.dropSubtitles = function (this: AppModel, itemID: string) {
  setTracks(this, itemID, it => it.copy({ subtitle: TrackSelection.Subtitle.off }))
  this.toast('Couldn’t show subtitles for this one.')
}

/** The prototype's onTick: a started-over program whose slot has run out goes back to live. Also marks a program
 *  started from its beginning watched on the server once it has played to 90%. */
AppModel.prototype.watchTick = function (this: AppModel, at: Instant = now()) {
  const pb = this.playback
  if (pb == null || this.screen !== 'watch') return
  const info = this.watchInfo(at)
  if (info == null) return
  // Only the program tuned counts, not its break (trailers, the spot) nor channel 99. Once per playback, no toast,
  // a failure only logged. No server (a plain-http link dropped while away): a later tick of this slot reports it
  // once it's back, its break too.
  // The library's own `watched` stays as it is, so the lineup doesn't change under the viewer: the Unwatched
  // channel drops the program at the next library refresh.
  const server = this.server
  if (pb.fromStart && info.slot.id === pb.slot?.id && (!info.isBreak || pb.markDue) && !info.channel.isScrambled &&
      info.position >= 0.9 * info.slot.item.runtime) {
    if (server == null) { if (!pb.markDue) this.playback = { ...pb, markDue: true } }
    else {
      this.playback = { ...pb, fromStart: false }
      server.markWatched(info.slot.item).catch(e => console.log(`markWatched: ${e}`))
    }
  }
  if (pb.mode !== 'start' || pb.paused || info.position < info.slot.duration) return
  const on = this.slotOnAir(info.channel, at)
  this.playback = Playback(pb.channelID, 'live', on?.start ?? at, { slot: on })
  this.toast('Caught up. Back to live.')
}
