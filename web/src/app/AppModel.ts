// Port of android/app AppModel.kt: the persisted types, the model's shared core and LibraryCache. Screen handlers live in
// AppModel*.ts, which add their methods to AppModel.prototype (web/README.md, "The model"). This file never imports one
// of those at runtime: they run `AppModel.prototype.x = …` when loaded, so they must load after this class exists.
// No window, localStorage or indexedDB access at module load either: the Node tests import this file.
import { createContext } from 'preact'
import { useContext } from 'preact/hooks'
import {
  JSONException, JSONObject, asObject, boolOrNull, getJSONArray, getJSONObject, getString, intOrNull, objects,
  optJSONArray, optJSONObject, optString, putOpt, stableSort, stringOrNull, strings, toIntOrNull, uuid,
} from '../core/Lang'
import { MediaServer, MediaServerError, isCancellation, runCatchingNonCancel } from '../core/MediaServer'
import {
  Channel, ChannelDefinition, LibrarySection, MediaItem, MediaTrack, PlayOrder, ProgramStatus, ServerKind, Slot,
  StableHash, TrackSelection, TuneMode, isO3Language, swiftCompare,
} from '../core/Models'
import * as Time from '../core/Time'
import { Instant } from '../core/Time'
import { ChannelBuilder, GuideGenerator } from '../core/GuideGenerator'
import { TitleSearch } from '../core/TitleSearch'
import { PlexAddress, PlexAuth, PlexClient, PlexServer } from '../core/PlexClient'
import { JellyfinClient } from '../core/JellyfinClient'
import { DemoMediaServer } from '../core/DemoServer'
import { GenreIDMigration } from '../core/GenreIDMigration'
import { Theme, ThemeID } from './Theme'
import { Keychain } from './Keychain'
import { cacheStore, defaults } from './Persistence'
import type { TrackMenu } from './AppModelWatch'
import type { RemoteCommand } from './Remote'

/** The app's one model for every component (the Swift `@Environment(AppModel.self) var model`). RootView provides it. */
export const LocalModel = createContext<AppModel>(null as unknown as AppModel)

export function useModel(): AppModel {
  return useContext(LocalModel)
}

// MARK: - Persisted types

export type Screen = 'welcome' | 'address' | 'link' | 'login' | 'libraries' | 'building' | 'profiles' | 'guide' | 'watch' | 'channels' | 'settings' | 'search'

/** `makeChannel`: Search's page for a result that isn't on in the next 24 hours, or a film's people to make a channel of. */
export type Overlay = { readonly kind: 'options' } | { readonly kind: 'info'; readonly key: ProgramKey } | { readonly kind: 'newChannel' }
  | { readonly kind: 'makeChannel' }
export const Overlay = {
  options: { kind: 'options' } as Overlay,
  info: (key: ProgramKey): Overlay => ({ kind: 'info', key }),
  newChannel: { kind: 'newChannel' } as Overlay,
  makeChannel: { kind: 'makeChannel' } as Overlay,
}

/** Identifies one guide cell: a channel and the slot's exact start. Build it as a literal: `{ channelID, start }`. */
export interface ProgramKey {
  readonly channelID: string
  readonly start: Instant
}
export const ProgramKey = {
  /** Kotlin's data-class `==`. */
  eq(a: ProgramKey | null | undefined, b: ProgramKey | null | undefined): boolean {
    return a == null || b == null ? a == b : a.channelID === b.channelID && a.start === b.start
  },
  /** A string with the same equality, for effect keys and Map keys ("" for none). */
  id(k: ProgramKey | null | undefined): string {
    return k == null ? '' : `${k.channelID}|${k.start}`
  },
}

export class Profile {
  constructor(
    readonly id: string,
    readonly name: string,
    readonly colorHex: string,
    readonly theme: ThemeID,
    readonly hiddenChannelIDs: ReadonlySet<string>,
  ) {}

  /** `name.prefix(1)`: the first character. ponytail: a code point, not a grapheme (Intl.Segmenter is Chromium 87+), so a
   *  flag, toned or ZWJ emoji, or a decomposed accent, shows only its first part. */
  get initial(): string {
    return Array.from(this.name)[0] ?? ''
  }

  copy(changes: Partial<Pick<Profile, 'id' | 'name' | 'colorHex' | 'theme' | 'hiddenChannelIDs'>>): Profile {
    const p = { ...this, ...changes }
    return new Profile(p.id, p.name, p.colorHex, p.theme, p.hiddenChannelIDs)
  }

  toJson(): JSONObject {
    return { id: this.id, name: this.name, colorHex: this.colorHex, theme: ThemeID.raw(this.theme), hiddenChannelIDs: Array.from(this.hiddenChannelIDs) }
  }

  /** Synthesized-Decodable in Swift: a missing key throws (and Persisted.load starts over). */
  static fromJson(o: JSONObject): Profile {
    return new Profile(getString(o, 'id'), getString(o, 'name'), getString(o, 'colorHex'), ThemeID.from(optString(o, 'theme')),
      new Set(strings(getJSONArray(o, 'hiddenChannelIDs'))))
  }
}

/** Which channels the guide shows. */
export type GuideFilter = 'all' | 'movies' | 'tv'
export const GuideFilter = {
  entries: ['all', 'movies', 'tv'] as readonly GuideFilter[],
  from(raw: string | null | undefined): GuideFilter | null {
    return GuideFilter.entries.indexOf(raw as GuideFilter) >= 0 ? raw as GuideFilter : null
  },
}

/** Kotlin `Enum.valueOf`: an unknown name throws, and the whole Persisted starts over (Persisted.load). */
function valueOf<T>(v: T | null, what: string, raw: string): T {
  if (v == null) throw new JSONException(`${what} ${raw}`)
  return v
}

/** Hand-written decoders here and on [Persisted]: a build that adds a property must not reset every profile, edit and
 *  the server link. Missing keys keep the defaults in `Settings.make`. */
export interface Settings {
  readonly crtLines: boolean
  readonly autoScroll: boolean
  /** 5 / 8 / 15 / 30 */
  readonly idleSeconds: number
  readonly tuneMode: TuneMode
  readonly trailerBreaks: boolean
  /** Streaming quality cap in kbit/s; 0 = original. Applied by the clients (Plex maxVideoBitrate, Jellyfin MaxStreamingBitrate). */
  readonly maxBitrateKbps: number
  /** Preferred audio language (ISO 639-2, "spa"); null = whatever the server marks default. */
  readonly audioLanguage: string | null
  /** Station idents: the 1.5 s channel card + sting on channel change and before trailer breaks. */
  readonly stationIdents: boolean
  /** Attract mode: after this many minutes idle on the drifting guide the screen becomes a slow storefront wall; 0 = off. */
  readonly attractMinutes: number
  /** Subtitles by default: "off" (none), "forced" (only the program's forced track), "on". */
  readonly subtitleMode: string
  /** Which language to show when subtitles are on; null = the device language, else the first subtitle track. */
  readonly subtitleLanguage: string | null
  /** What attract mode shows: "wall" (the storefront wall), "logo" (the bouncing Rerun Guide logo) or "soyboi" (the mascot). */
  readonly screensaver: string
  /** One trailer break in 15 opens with the bundled Soyboi spot. */
  readonly houseSpots: boolean
}
export const Settings = {
  /** Kotlin `Settings(…)` with its defaults: `Settings.make({ crtLines: false })`. */
  make(init: Partial<Settings> = {}): Settings {
    return {
      crtLines: true, autoScroll: true, idleSeconds: 8, tuneMode: 'live', trailerBreaks: true, maxBitrateKbps: 0,
      audioLanguage: null, stationIdents: true, attractMinutes: 3, subtitleMode: 'forced', subtitleLanguage: null,
      screensaver: 'wall', houseSpots: true, ...init,
    }
  },

  toJson(s: Settings): JSONObject {
    const o: JSONObject = { crtLines: s.crtLines, autoScroll: s.autoScroll, idleSeconds: s.idleSeconds, tuneMode: s.tuneMode,
      trailerBreaks: s.trailerBreaks, maxBitrateKbps: s.maxBitrateKbps }
    putOpt(o, 'audioLanguage', s.audioLanguage)
    o.stationIdents = s.stationIdents
    o.attractMinutes = s.attractMinutes
    o.subtitleMode = s.subtitleMode
    putOpt(o, 'subtitleLanguage', s.subtitleLanguage)
    o.screensaver = s.screensaver
    o.houseSpots = s.houseSpots
    return o
  },

  fromJson(o: JSONObject): Settings {
    const d = Settings.make()
    const mode = stringOrNull(o, 'subtitleMode') ?? d.subtitleMode
    const tune = stringOrNull(o, 'tuneMode')
    return {
      crtLines: boolOrNull(o, 'crtLines') ?? d.crtLines,
      autoScroll: boolOrNull(o, 'autoScroll') ?? d.autoScroll,
      idleSeconds: intOrNull(o, 'idleSeconds') ?? d.idleSeconds,
      tuneMode: tune == null ? d.tuneMode : valueOf(TuneMode.from(tune), 'tuneMode', tune),
      trailerBreaks: boolOrNull(o, 'trailerBreaks') ?? d.trailerBreaks,
      maxBitrateKbps: intOrNull(o, 'maxBitrateKbps') ?? d.maxBitrateKbps,
      audioLanguage: stringOrNull(o, 'audioLanguage'),
      stationIdents: boolOrNull(o, 'stationIdents') ?? d.stationIdents,
      attractMinutes: intOrNull(o, 'attractMinutes') ?? d.attractMinutes,
      subtitleMode: mode === 'auto' ? 'forced' : mode,   // earlier builds stored the mode as "auto"
      subtitleLanguage: stringOrNull(o, 'subtitleLanguage'),
      screensaver: stringOrNull(o, 'screensaver') ?? d.screensaver,
      houseSpots: boolOrNull(o, 'houseSpots') ?? d.houseSpots,
    }
  },
}

/** Per-channel overrides the viewer made to an automatic channel. Custom channels store everything in their definition. */
export interface ChannelEdit {
  readonly name: string | null
  readonly number: number | null
  readonly order: PlayOrder | null
  readonly tuneMode: TuneMode | null
  readonly trailerBreaks: boolean | null
}
export const ChannelEdit = {
  /** Kotlin `ChannelEdit(…)`: every field null unless given. */
  make(init: Partial<ChannelEdit> = {}): ChannelEdit {
    return { name: null, number: null, order: null, tuneMode: null, trailerBreaks: null, ...init }
  },

  toJson(e: ChannelEdit): JSONObject {
    const o: JSONObject = {}
    putOpt(o, 'name', e.name)
    putOpt(o, 'number', e.number)
    putOpt(o, 'order', e.order == null ? null : PlayOrder.rawValue(e.order))
    putOpt(o, 'tuneMode', e.tuneMode)
    return putOpt(o, 'trailerBreaks', e.trailerBreaks)
  },

  fromJson(o: JSONObject): ChannelEdit {
    const order = stringOrNull(o, 'order')
    const tune = stringOrNull(o, 'tuneMode')
    return {
      name: stringOrNull(o, 'name'),
      number: intOrNull(o, 'number'),
      order: order == null ? null : valueOf(PlayOrder.from(order), 'order', order),
      tuneMode: tune == null ? null : valueOf(TuneMode.from(tune), 'tuneMode', tune),
      trailerBreaks: boolOrNull(o, 'trailerBreaks'),
    }
  },
}

export class ServerConfig {
  constructor(
    readonly kind: ServerKind,
    readonly baseURL: string,
    readonly name: string,
    /** Emby/Jellyfin */
    readonly userID: string | null = null,
    /** Plex: every address plex.tv listed (a typed one first) and the server's machineIdentifier, so phones and tablets
     *  can switch between the LAN and public addresses as they move (`AppModel.pickPlexAddress`). null for older links. */
    readonly addresses: readonly PlexAddress[] | null = null,
    readonly machineID: string | null = null,
  ) {}

  /** Which server and account a cached library belongs to (`LibraryCache`): the Plex machine, or the address, plus the user. */
  get link(): string {
    return `${this.kind}|${this.machineID ?? this.baseURL}|${this.userID ?? ''}`
  }

  copy(changes: Partial<Pick<ServerConfig, 'kind' | 'baseURL' | 'name' | 'userID' | 'addresses' | 'machineID'>>): ServerConfig {
    const c = { ...this, ...changes }
    return new ServerConfig(c.kind, c.baseURL, c.name, c.userID, c.addresses, c.machineID)
  }

  makeServer(token: string, deviceID: string, maxBitrateKbps = 0): MediaServer {
    switch (this.kind) {
      case 'plex': return new PlexClient(this.baseURL, token, deviceID, this.name, maxBitrateKbps)
      case 'emby':
      case 'jellyfin': return new JellyfinClient(this.baseURL, token, this.userID ?? '', deviceID, this.kind, this.name, maxBitrateKbps)
      case 'demo': return new DemoMediaServer()
    }
  }

  toJson(): JSONObject {
    const o: JSONObject = { kind: this.kind, baseURL: this.baseURL, name: this.name }
    putOpt(o, 'userID', this.userID)
    putOpt(o, 'addresses', this.addresses?.map(a => PlexAddress.toJson(a)))
    return putOpt(o, 'machineID', this.machineID)
  }

  static fromJson(o: JSONObject): ServerConfig {
    const kind = getString(o, 'kind')
    const addresses = optJSONArray(o, 'addresses')
    return new ServerConfig(valueOf(ServerKind.from(kind), 'kind', kind), getString(o, 'baseURL'), getString(o, 'name'),
      stringOrNull(o, 'userID'), addresses == null ? null : objects(addresses).map(a => PlexAddress.fromJson(a)),
      stringOrNull(o, 'machineID'))
  }
}

/** Any property added to the types inside (Profile, ServerConfig, ChannelDefinition) must be nullable, or get the same
 *  missing-key treatment. Edit with a spread: `{ ...persisted, lastChannelID: id }`. */
export interface Persisted {
  readonly profiles: readonly Profile[]
  readonly currentProfileID: string
  readonly settings: Settings
  readonly channelEdits: ReadonlyMap<string, ChannelEdit>
  readonly customChannels: readonly ChannelDefinition[]
  /** null = every library. */
  readonly selectedLibraryIDs: readonly string[] | null
  readonly serverConfig: ServerConfig | null
  readonly lastChannelID: string | null
  readonly guideFilter: GuideFilter
}
export const Persisted = {
  /** Kotlin `Persisted(…)` with its defaults: `Persisted.make({ guideFilter: 'movies' })`. */
  make(init: Partial<Persisted> = {}): Persisted {
    return {
      profiles: [new Profile('p1', 'You', '#f0a33a', 'LATE', new Set())], currentProfileID: 'p1', settings: Settings.make(),
      channelEdits: new Map(), customChannels: [], selectedLibraryIDs: null, serverConfig: null, lastChannelID: null,
      guideFilter: 'all', ...init,
    }
  },

  toJson(p: Persisted): JSONObject {
    const edits: JSONObject = {}
    p.channelEdits.forEach((v, k) => { edits[k] = ChannelEdit.toJson(v) })
    const o: JSONObject = {
      profiles: p.profiles.map(it => it.toJson()), currentProfileID: p.currentProfileID, settings: Settings.toJson(p.settings),
      channelEdits: edits, customChannels: p.customChannels.map(it => it.toJson()),
    }
    putOpt(o, 'selectedLibraryIDs', p.selectedLibraryIDs?.slice())
    putOpt(o, 'serverConfig', p.serverConfig?.toJson())
    putOpt(o, 'lastChannelID', p.lastChannelID)
    o.guideFilter = p.guideFilter
    return o
  },

  fromJson(o: JSONObject): Persisted {
    const d = Persisted.make()
    const profiles = optJSONArray(o, 'profiles')
    const settings = optJSONObject(o, 'settings')
    const edits = optJSONObject(o, 'channelEdits')
    const custom = optJSONArray(o, 'customChannels')
    const libraries = optJSONArray(o, 'selectedLibraryIDs')
    const server = optJSONObject(o, 'serverConfig')
    const filter = stringOrNull(o, 'guideFilter')
    return {
      profiles: profiles == null ? d.profiles : objects(profiles).map(it => Profile.fromJson(it)),
      currentProfileID: stringOrNull(o, 'currentProfileID') ?? d.currentProfileID,
      settings: settings == null ? d.settings : Settings.fromJson(settings),
      channelEdits: edits == null ? d.channelEdits : new Map(Object.keys(edits).map(k => [k, ChannelEdit.fromJson(getJSONObject(edits, k))] as [string, ChannelEdit])),
      customChannels: custom == null ? d.customChannels : objects(custom).map(it => ChannelDefinition.fromJson(it)),
      selectedLibraryIDs: libraries == null ? null : strings(libraries),
      serverConfig: server == null ? null : ServerConfig.fromJson(server),
      lastChannelID: stringOrNull(o, 'lastChannelID'),
      guideFilter: filter == null ? d.guideFilter : valueOf(GuideFilter.from(filter), 'guideFilter', filter),
    }
  },

  load(): Persisted {
    const s = defaults.get('persisted')
    if (s == null) return Persisted.make()
    try { return Persisted.fromJson(asObject(JSON.parse(s))) }
    catch (e) { console.warn('Persisted load', e); return Persisted.make() }   // genuine corruption, not a missing key
  },

  save(p: Persisted): void {
    defaults.set('persisted', JSON.stringify(Persisted.toJson(p)))
  },
}

/** What's on the screen while watching. Mirrors the prototype's `w`. Make one with `Playback(…)`, change it with a spread. */
export interface Playback {
  readonly channelID: string
  readonly mode: Playback.Mode
  /** For `start`: the slot being replayed and the player position bookkeeping. */
  readonly programStart: Instant
  /** seconds into the program at `startedAt` */
  readonly offset: number
  readonly startedAt: Instant
  readonly paused: boolean
  /** The slot that was on when we tuned, kept until it ends so a library refresh (which reshuffles lineups) can't
   *  swap the program under the viewer. null = derive from the schedule. */
  readonly slot: Slot | null
  /** Per-program track override from the banner picker (or the subtitle fallback), for `itemID` only; null or another
   *  item = resolve from Settings. Kept by a re-tune to the same program (Start over, Back to live); any other tune drops it. */
  readonly tracks: Playback.TrackOverride | null
  /** `slot` was started from its beginning (`tune` in 'start'), so at 90% `watchTick` marks it watched on the server
   *  and clears this. Play/Pause and the 10 s skips keep it on the same slot, Home from live drops it (unless `markDue`),
   *  any tune makes a new playback. */
  readonly fromStart: boolean
  /** `watchTick` saw 90% with no server: this slot's break may still report it once it's back. */
  readonly markDue: boolean
}
/** Kotlin's constructor and its defaults: `Playback(id, 'start', start, { offset, paused: true, slot })`. */
export function Playback(channelID: string, mode: Playback.Mode, programStart: Instant,
                         p: { offset?: number; startedAt?: Instant; paused?: boolean; slot?: Slot | null; tracks?: Playback.TrackOverride | null; fromStart?: boolean } = {}): Playback {
  return { channelID, mode, programStart, offset: p.offset ?? 0, startedAt: p.startedAt ?? Time.now(), paused: p.paused ?? false,
    slot: p.slot ?? null, tracks: p.tracks ?? null, fromStart: p.fromStart ?? false, markDue: false }
}
export namespace Playback {
  export type Mode = 'live' | 'start'
  export interface TrackOverride { readonly itemID: string; readonly selection: TrackSelection }
}

export interface Toast {
  readonly message: string
  readonly n: number
}

/** Scroll/focus bookkeeping for the guide's drifting grid. Not observed: it changes every frame while drifting, so its
 *  fields are mutated in place (never through the model) and it is only replaced whole by `guideReset`. */
export class GuideScroll {
  /** current translateY of the row track, in points (remote held) */
  pos = 0
  /** focused row index on the doubled track */
  row = 0
  /** "focus time": start of the focused cell clamped to the window */
  focusTime: Instant = Time.now()
  /** drift offset to resume from, 0 ..< half-track height */
  resumeOffset = 0
  /** when the current drift began (offset = resumeOffset + elapsed × speed, mod half) */
  driftStart: Instant = Time.now()
  /** pt/s, the design handoff's default drift speed */
  speed = 22
}

/** A guide cell resolved: the Swift `(channel: Channel, slot: Slot)` tuple. `const { channel: ch, slot } = …`. */
export interface ChannelSlot {
  readonly channel: Channel
  readonly slot: Slot
}

/** `AppModel.resolve`'s `(auto:, channels:)` tuple. */
export interface Resolved {
  readonly auto: readonly ChannelDefinition[]
  readonly channels: readonly Channel[]
}

/** `($0.number, $0.id) < ($1.number, $1.id)`, ids in Swift's string order (core `swiftCompare`). */
function byNumber(a: Channel, b: Channel): number {
  return a.number !== b.number ? a.number - b.number : swiftCompare(a.id, b.id)
}

/** Kotlin's CancellationException: Back on the Building screen cancelled the load. */
function cancelled(): Error {
  const e = new Error('The operation was aborted.')
  e.name = 'AbortError'
  return e
}

function ensureActive(signal: AbortSignal | null): void {
  if (signal?.aborted) throw cancelled()
}

/** A suspension point Kotlin cancels at once (its request disconnects): `p`'s result, or the AbortError as soon as
 *  `signal` aborts. The request itself runs on unused, so a load cancelled mid-fetch can't land later on a newer one's state. */
function cancellable<T>(signal: AbortSignal | null, p: Promise<T>): Promise<T> {
  if (signal == null) return p
  return new Promise<T>((resolve, reject) => {
    const abort = () => reject(cancelled())
    if (signal.aborted) return abort()
    signal.addEventListener('abort', abort)
    p.then(resolve, reject).then(() => signal.removeEventListener('abort', abort))
  })
}

let deviceIDValue: string | null = null

// MARK: - Model

/**
 * One object holds the whole app state; screens are pure functions of it and press handlers mutate it.
 * This file holds the shared core (ported from the design prototype's Component). Screen-specific handlers live in
 * `AppModel*.ts`. The constructor only sets fields; main.tsx wraps the model in `observable` and then calls `start()`.
 * [launchArgs]: the page's query parameters in the browser build (the Swift launch arguments), else null.
 */
export class AppModel {
  // navigation
  screen: Screen = 'welcome'
  overlay: Overlay | null = null
  /** Generic focus index for list-like screens (the prototype's `fi`). */
  focusIndex = 0

  // server + library
  server: MediaServer | null = null
  persisted: Persisted
  private libraryState: readonly MediaItem[] = []
  /** The library's films, kept with it: trailer breaks on a channel with none of its own pick from these. */
  private movies: readonly MediaItem[] = []
  librarySections: readonly LibrarySection[] = []
  private channelsState: readonly Channel[] = []
  /** Day schedules per (channel, day): the generator shuffles the whole pool per call, so the guide must not recompute
   *  it per row per tick. Filled during render (in place, so it never schedules one); replaced whenever `channels` changes. */
  dayCache = new Map<string, readonly Slot[]>()
  /** The automatic definitions from the last rebuild, so "Reset to automatic" needs no second library pass. */
  autoDefinitions: readonly ChannelDefinition[] = []
  /** Candidates for the New-channel picker, computed once when the kind is chosen (otherwise a 20k-item scan would run per press). */
  candidateCache: readonly ChannelBuilder.Candidate[] = []
  private libraryGen = 0
  private freshState: readonly MediaItem[] = []
  /** Items the open-the-app check (`checkRecentlyAdded`) found since the last full load. Only the "what's new" channels
   *  see them (`ChannelBuilder.isFresh`); the next full load folds them into `library`. */
  get freshItems(): readonly MediaItem[] { return this.freshState }
  set freshItems(v: readonly MediaItem[]) { this.freshState = v; this.searchIndex = null }
  private checkingRecent = false
  private rebuildGen = 0
  /** When the app last went looking for a Plex server that stopped answering (at most once a minute). */
  private lastPlexRecovery: Instant | null = null
  libraryLoading = false
  libraryError: string | null = null

  // onboarding
  serviceKind: ServerKind = 'plex'
  address = ''
  username = ''
  password = ''
  linkCode: string | null = null
  linked = false
  authError: string | null = null
  authBusy = false
  /** Servers returned by a Plex PIN (with the server's token) or an Emby PIN (with the server's Emby Connect access key).
   *  No LAN discovery on the web: apps can't send UDP. */
  foundServers: readonly PlexServer[] = []
  discovering = false
  /** The account the link signed in, while its servers are listed: the plex.tv token, or the Emby Connect user id. */
  pendingToken: string | null = null
  /** Bumped by go() and by each sign-in step: a step still in flight when the viewer leaves (even for the same screen
   *  again) is dropped, result, error and busy flag alike. */
  authSerial = 0
  librarySelection: ReadonlySet<string> = new Set()
  builtCount = 0
  /** The PIN / Quick Connect poll loop (AppModel+Onboarding's `static var linkTask`); cancelled on Back and on restart. */
  linkTask: AppModel.Job | null = null

  // guide
  focused: ProgramKey | null = null
  remoteHeld = false
  /** 0...maxWindowOffset, 30-min pages ahead */
  windowOffset = 0
  guideScroll = new GuideScroll()
  /** The launch's first guide with channels held still for `launchHold` (see `holdForLaunch`). */
  launchHeld = false

  // watch
  playback: Playback | null = null
  bannerVisible = false
  bannerFocus = -1
  /** The banner's progress bar has the remote (Up from its buttons, while `bannerFocus` ≥ 0): Left / Right skip. */
  barFocused = false
  /** The banner's ⏪ (−1) or ⏩ (+1) above the playhead for a second after a skip; 0 when none. */
  skipCue = 0
  /** The banner's Audio or Subtitles list, open over its button. */
  trackMenu: TrackMenu | null = null
  /** Start over: the VHS rewind on screen, from where the program was (see `tune()`). */
  rewind: { readonly from: number; readonly started: Instant } | null = null
  /** Attract mode is on screen (the guide has been drifting untouched for `settings.attractMinutes`). */
  attracting = false
  /** Bumped by `tune()` on every channel change, together with `flips`; the ident card keys off it. A start-over from the
   *  guide rewinds instead: no bump, and `flips` goes to 0 so the new watch screen has no static to show. */
  identSerial = 0
  /** Bumped on the way back from the Home screen: the guide's preview reloads on it (its stream was stopped). */
  foregroundSerial = 0
  backgrounded = false
  /** A started-over program that was playing when the app left: it was held at its position and plays on return. */
  resumeAfterBackground = false
  /** Tracks of the program on screen, loaded by WatchScreen; drives the banner's Audio / Subtitles actions. */
  playbackTracks: readonly MediaTrack[] = []
  flips = 0
  /** Bumped by every `tune()`: the player reloads on it, so Play/Pause (which only changes `playback.mode`) does not. */
  tunes = 0

  // channel editor
  /** null = "New channel" row */
  channelSelection: string | null = null
  channelColumn = 0
  channelFieldIndex = 0
  numberDraft: string | null = null
  newChannelStep = 0
  newChannelType: ChannelBuilder.CandidateKind | null = null
  /** Non-null while a text field owns the remote (server address, username, channel name…). */
  editingField: string | null = null

  /** Number keys on the guide or the player: the digits typed so far (AppModelWatch). */
  channelEntry: AppModel.ChannelEntry | null = null
  /** What the box last said, which it fades out showing: a third digit tunes (and so closes it) in the same press. */
  lastEntry: AppModel.ChannelEntry | null = null

  // search (Options › Search, AppModelSearch)
  private searchQueryState = ''
  /** Assigning searches (the Swift didSet). */
  get searchQuery(): string { return this.searchQueryState }
  set searchQuery(v: string) { this.searchQueryState = v; this.searchResults = TitleSearch.search(v, this.titleIndex) }
  searchResults: readonly TitleSearch.Entry[] = []
  /** The page's row: 0 the text field, i the result `searchResults[i - 1]`. Its own index, kept while an overlay over the
   *  page uses `focusIndex`. */
  searchFocus = 0
  /** When each entry airs next, worked out as the page opens and again on its clock (`refreshAirings`). */
  searchAirings: ReadonlyMap<string, TitleSearch.Airing> = new Map()
  /** Make a channel from a film: its director(s) and lead actors with enough films, once asked for. */
  makePeople: readonly { readonly kind: ChannelBuilder.CandidateKind; readonly candidate: ChannelBuilder.Candidate }[] | null = null
  /** The library's titles keyed for matching (`titleIndex`); dropped whenever the library changes. */
  searchIndex: TitleSearch.Index | null = null

  /** Kotlin's `toast` property. Renamed: a TS class can't have a field and a method (`toast(message)`) of one name. */
  currentToast: Toast | null = null

  // Navigation state for confirmations, return paths and cancellable loads (owned by the screen handlers that use it).
  /** Settings › Sign out needs a second press within a few seconds. */
  signOutArmed = false
  /** Where the Libraries screen returns to: null = first-run onboarding (welcome), 'settings' = re-pick from Settings. */
  librariesOrigin: Screen | null = null
  /** Profiles screen: index of the tile whose Rename / Delete row is open, with `profileMenuIndex` as the focused action. */
  profileMenu: number | null = null
  profileMenuIndex = 0
  /** The in-flight library load started by the Building screen, so Back can cancel it. */
  loadTask: AppModel.Job | null = null

  private readonly timers = new Map<string, ReturnType<typeof setTimeout>>()

  /** Debug-only launch hook; cleared once used. */
  tuneOverride: number | null = null
  /** Debug-only `spotOverride`: the next load opens with the spot, break or not. */
  spotOverride = false
  /** Debug-only `attractMinutesOverride`: the attract delay for this run, never saved into settings. */
  attractOverride: number | null = null

  /** Web only: the LG Magic Remote's pointer is showing (main.tsx sets it from `Platform.onPointerChange`); the touch
   *  chrome shows while it is. */
  pointerVisible = false

  private readonly launchArgs: URLSearchParams | null

  /** UserDefaults.standard: localStorage (Persistence.ts). */
  static readonly defaults = defaults
  static readonly windowSpan = 90 * 60
  /** How many 30-min pages Right can move the window ahead: the last one ends 12 hours past the current half hour. */
  static readonly maxWindowOffset = 21
  /** How long the rewind plays: its counter runs back for 1.1 s, holds, and fades out by then. */
  static readonly rewindLength = 1.4
  /** How long the first guide of a launch waits before it starts to drift, so the first channels can be read. */
  static readonly launchHold = 4

  /** A per-install client id, in localStorage. Android keeps it out of backups; TVs have no backup or transfer. */
  static get deviceID(): string {
    if (deviceIDValue == null) {
      let id = defaults.get('deviceID')
      if (!id) { id = uuid(); defaults.set('deviceID', id) }
      deviceIDValue = id
    }
    return deviceIDValue
  }

  /** The device's language as the ISO 639-2 code servers use ("eng", "spa"). */
  static get deviceLanguage(): string {
    return isO3Language(navigator.language) || 'eng'
  }

  /** ponytail: on the main thread (Kotlin uses Dispatchers.Default: a library refresh evaluates every rule chip against
   *  thousands of items); a Web Worker if a big library stalls the TV. */
  static async resolveChannels(library: readonly MediaItem[], fresh: readonly MediaItem[], edits: ReadonlyMap<string, ChannelEdit>,
                               custom: readonly ChannelDefinition[]): Promise<Resolved> {
    return AppModel.resolve(library, fresh, edits, custom)
  }

  /** The lineup (which automatic channels exist, and their numbers) comes from `library` alone, so newly found items
   *  never renumber it; `fresh` only adds to the "what's new" channels. */
  static resolve(library: readonly MediaItem[], fresh: readonly MediaItem[], edits: ReadonlyMap<string, ChannelEdit>,
                 custom: readonly ChannelDefinition[]): Resolved {
    const auto = ChannelBuilder.automaticChannels(library)
    const defs = auto.map(d => {
      const e = edits.get(d.id)
      if (e == null) return d
      return d.copy({
        name: e.name ?? d.name, number: e.number ?? d.number, order: e.order ?? d.order,
        trailerBreaks: e.trailerBreaks ?? d.trailerBreaks, tuneMode: e.tuneMode ?? d.tuneMode,
      })
    }).concat(custom)
    let resolved: readonly Channel[] = ChannelBuilder.channels(defs, library, fresh, Time.now())
    if (library.length > 0) {   // channel 99 rides along with any real lineup; the editor's rename/renumber still apply
      let scr = Channel.scrambled
      const e = edits.get(scr.id)
      if (e != null) {
        scr = scr.copy({ definition: scr.definition.copy({
          name: e.name ?? scr.definition.name, number: e.number ?? scr.definition.number,
          tuneMode: e.tuneMode ?? scr.definition.tuneMode }) })
      }
      resolved = resolved.concat(scr)
    }
    return { auto, channels: stableSort(resolved, byNumber) }
  }

  /** A request that never reached the server (as opposed to one the server refused): `fetch` rejects with a TypeError
   *  for every transport failure, and HTTP.data turns its own timeout into a TimeoutError. */
  static isUnreachable(error: unknown): boolean {
    return error instanceof TypeError || (error instanceof Error && error.name === 'TimeoutError')
  }

  constructor(launchArgs: URLSearchParams | null = null) {
    this.launchArgs = launchArgs
    this.persisted = Persisted.load()
  }

  /** The rest of Kotlin's `init`: call once, on the observable model. */
  start(): void {
    if (!this.applyLaunchArgs(this.launchArgs)) this.startFromSavedLink()
  }

  /** Test hooks, browser build only (`?tuneOverride=N`), the Swift launch arguments: `attractMinutesOverride N` shortens
   *  the attract-mode delay for a run (never persisted); `tuneOverride N` tunes to channel N as soon as the lineup is
   *  built; `spotOverride 1` opens with the spot; `themeOverride midnight` starts every profile in that theme (persisted
   *  on the next save); `serversOverride N` opens the Plex server list with N stand-in servers (never persisted).
   *  Returns true when it did, so the saved link is not started. */
  private applyLaunchArgs(args: URLSearchParams | null): boolean {
    if (!__DEV__ || args == null) return false
    const int = (k: string) => toIntOrNull(args.get(k)) ?? 0   // Bundle.getInt: 0 when it isn't an int
    if (args.has('attractMinutesOverride')) this.attractOverride = int('attractMinutesOverride')
    if (args.has('tuneOverride')) this.tuneOverride = int('tuneOverride')
    this.spotOverride = args.get('spotOverride') === 'true' || args.get('spotOverride') === '1'
    const raw = args.get('themeOverride')
    const id = ThemeID.entries.find(t => ThemeID.raw(t) === raw)
    if (id) this.persisted = { ...this.persisted, profiles: this.persisted.profiles.map(p => p.copy({ theme: id })) }
    const standIns = int('serversOverride')
    if (standIns <= 0) return false
    this.serviceKind = 'plex'; this.pendingToken = 'ui-test'
    const servers: PlexServer[] = []
    for (let i = 1; i <= standIns; i++) {
      servers.push({ id: `sim-${i}`, name: `Server ${i}`, baseURL: `http://192.168.1.${i}:32400`, accessToken: 'ui-test', addresses: [] })
    }
    this.foundServers = servers
    this.address = servers[0].baseURL
    this.screen = 'address'; this.focusIndex = 1
    return true
  }

  private startFromSavedLink(): void {
    if (this.persisted.serverConfig == null) return
    this.rebuildServer()
    if (this.server == null) return
    this.screen = 'guide'
    this.libraryLoading = true   // the cache decodes asynchronously; no "Nothing to schedule" flash before it lands
    // Android picks a Plex address first off TV; the web is always a TV, which keeps the linked address.
    void (async () => {
      await this.loadLibrary()
      await this.checkRecentlyAdded()   // a fresh cache skipped the full load; the new channels still catch up
    })()
  }

  // MARK: Derived

  get profile(): Profile {
    const p = this.persisted
    return p.profiles.find(it => it.id === p.currentProfileID) ?? p.profiles[0]
  }
  get theme(): Theme { return ThemeID.theme(this.profile.theme) }

  /** Options and Settings both offer the theme; it lives on the profile, so each viewer keeps their own. */
  setTheme(id: ThemeID): void {
    if (id === this.profile.theme) return
    this.updateProfile(p => p.copy({ theme: id }))
    if (this.screen === 'guide') this.guideReset()   // the row pitch changes with the theme, so the drift offset is re-based
  }
  toggleTheme(): void { this.setTheme(this.profile.theme === 'LATE' ? 'MIDNIGHT' : 'LATE') }

  /** Assigning saves (the Swift `settings { set }`). Edit one field with `settings = { ...settings, … }` or [updateSettings]. */
  get settings(): Settings { return this.persisted.settings }
  set settings(v: Settings) { this.persisted = { ...this.persisted, settings: v }; this.save() }
  updateSettings(change: (s: Settings) => Settings): void { this.settings = change(this.settings) }

  get serverLabel(): string {
    return `${this.server?.serverName ?? ''} · ${this.server ? ServerKind.displayName(this.server.kind) : ''}`
  }

  isHidden(channelID: string): boolean { return this.profile.hiddenChannelIDs.has(channelID) }
  channel(id: string): Channel | null { return this.channels.find(c => c.id === id) ?? null }
  /** Channels in the lineup: not hidden for this profile, and in the current guide (All / Movies / TV Shows). */
  get visibleChannels(): Channel[] {
    const hidden = this.profile.hiddenChannelIDs
    const f = this.guideFilter
    return this.channels.filter(c => !hidden.has(c.id) && (f === 'all' || (f === 'movies') === c.isMovies))
  }
  get guideFilter(): GuideFilter { return this.persisted.guideFilter }
  /** Switch guides (Options › Guide / Movies / TV Shows): restarts the drift from the top of the new lineup. */
  setGuideFilter(f: GuideFilter): void {
    this.persisted = { ...this.persisted, guideFilter: f }; this.save()
    this.guideReset()
    this.overlay = null; this.focusIndex = 0
    if (this.screen !== 'guide') this.go('guide')
  }

  get library(): readonly MediaItem[] { return this.libraryState }
  set library(v: readonly MediaItem[]) { this.libraryState = v; this.movies = v.filter(it => it.kind === 'movie'); this.searchIndex = null }
  /** Every channel, sorted by number, hidden ones included. */
  get channels(): readonly Channel[] { return this.channelsState }
  set channels(v: readonly Channel[]) { this.channelsState = v; this.dayCache = new Map() }

  /** Guide row pitch and viewport: the design handoff's PITCH / VIEW per theme (Late Show 100 / 424, Midnight Movie 108 / 432). */
  get rowPitch(): number { return this.theme.id === 'MIDNIGHT' ? 108 : 100 }
  get gridHeight(): number { return this.theme.id === 'MIDNIGHT' ? 432 : 424 }

  /** Guide window start: now floored to the half hour, plus `offset` pages. */
  windowStart(at: Instant, offset: number | null = null): Instant {
    const block = GuideGenerator.blockLength
    const floored = Math.floor(at / block) * block   // the epoch is on a half-hour boundary, like the reference date
    return floored + (offset ?? this.windowOffset) * block
  }

  /** One channel's lineup for the day containing `day`, memoized. */
  daySlots(channel: Channel, day: Instant): readonly Slot[] {
    const d = Time.startOfDay(day)
    const k = `${channel.id}|${d}`
    const hit = this.dayCache.get(k)
    if (hit) return hit
    const s = GuideGenerator.schedule(channel, d)
    this.dayCache.set(k, s)
    return s
  }
  /** Slots intersecting [from, to], spanning days; memoized per day. */
  slots(channel: Channel, from: Instant, to: Instant): Slot[] {
    let out: Slot[] = []
    for (let day = Time.startOfDay(from); day < to; day = Time.addDays(day, 1)) out = out.concat(this.daySlots(channel, day))
    return out.filter(s => s.end > from && s.start < to)
  }
  slotOnAir(channel: Channel, at: Instant): Slot | null {
    return this.daySlots(channel, at).find(s => s.start <= at && at < s.end) ?? null
  }
  slot(channel: Channel, startingAt: Instant): Slot | null {
    return this.daySlots(channel, startingAt).find(s => Math.abs(s.start - startingAt) < 1) ?? null
  }
  status(slot: Slot, at: Instant): ProgramStatus { return GuideGenerator.status(slot, at) }
  program(key: ProgramKey): ChannelSlot | null {
    const ch = this.channel(key.channelID)
    const slot = ch && this.slot(ch, key.start)
    return ch && slot ? { channel: ch, slot } : null
  }
  key(channel: Channel, slot: Slot): ProgramKey { return { channelID: channel.id, start: slot.start } }

  // MARK: Timers / toast

  /** Run `action` in `seconds`; one timer per key, so re-arming replaces the pending one. */
  later(key: string, seconds: number, action: () => void): void {
    const old = this.timers.get(key)
    if (old != null) clearTimeout(old)
    this.timers.set(key, setTimeout(action, seconds * 1000))
  }
  cancelTimer(key: string): void {
    const t = this.timers.get(key)
    if (t != null) { clearTimeout(t); this.timers.delete(key) }
  }

  toast(message: string): void {
    this.currentToast = { message, n: (this.currentToast?.n ?? 0) + 1 }
    this.later('toast', 3.2, () => { this.currentToast = null })
  }

  // MARK: Navigation

  go(to: Screen): void {
    if (this.screen === 'guide' && to !== 'guide') this.guidePark()
    if (this.screen === 'link' && to !== 'link') this.linkTask?.cancel()   // a PIN / Quick Connect poll never outlives its screen
    if (this.screen === 'login' && to !== 'login') this.password = ''    // typed passwords don't wait around in memory
    this.authSerial += 1                                                   // a sign-in step in flight belongs to the screen being left
    if (this.screen === 'address' || this.screen === 'login') this.authBusy = false
    this.cancelTimer('banner'); this.closeEntry()
    this.screen = to; this.overlay = null; this.focusIndex = 0; this.editingField = null
    if (to === 'link') this.startLink()
    if (to === 'building') this.startBuild()
    if (to === 'address' && this.foundServers.length === 0) this.startDiscovery()
  }

  closeOverlay(): void {
    this.overlay = null; this.focusIndex = 0
    if (this.screen === 'guide' && this.remoteHeld) this.guideBump()
  }

  openOptions(): void { this.cancelTimer('idle'); this.closeEntry(); this.overlay = Overlay.options; this.focusIndex = 1 }
  openInfo(key: ProgramKey): void { this.cancelTimer('idle'); this.cancelTimer('banner'); this.closeEntry(); this.overlay = Overlay.info(key); this.focusIndex = 0 }

  /** Dispatch a remote press to whichever overlay/screen owns the remote right now. */
  press(cmd: RemoteCommand): void {
    if (this.overlay == null && this.pressEntry(cmd)) return
    switch (this.overlay?.kind) {
      case 'options': return this.pressOptions(cmd)
      case 'info': return this.pressInfo(cmd)
      case 'newChannel': return this.pressNewChannel(cmd)
      case 'makeChannel': return this.pressMakeChannel(cmd)
    }
    switch (this.screen) {
      case 'welcome': return this.pressWelcome(cmd)
      case 'address': return this.pressAddress(cmd)
      case 'link': return this.pressLink(cmd)
      case 'login': return this.pressLogin(cmd)
      case 'libraries': return this.pressLibraries(cmd)
      case 'building': return this.pressBuilding(cmd)
      case 'profiles': return this.pressProfiles(cmd)
      case 'guide': return this.pressGuide(cmd)
      case 'watch': return this.pressWatch(cmd)
      case 'channels': return this.pressChannels(cmd)
      case 'settings': return this.pressSettings(cmd)
      case 'search': return this.pressSearch(cmd)
    }
  }

  /** Move `focusIndex` within 0 until count for up/down (or left/right when horizontal). Returns true if consumed. */
  step(cmd: RemoteCommand, count: number, horizontal = false): boolean {
    const dec: RemoteCommand = horizontal ? 'left' : 'up'
    const inc: RemoteCommand = horizontal ? 'right' : 'down'
    if (cmd !== dec && cmd !== inc) return false
    this.focusIndex = Math.max(0, Math.min(count - 1, this.focusIndex + (cmd === inc ? 1 : -1)))
    return true
  }

  /** Edit the current profile: `updateProfile(p => p.copy({ name: n }))` (the Swift `{ $0.name = n }`). Saves. */
  updateProfile(change: (p: Profile) => Profile): void {
    const i = this.persisted.profiles.findIndex(p => p.id === this.persisted.currentProfileID)
    if (i < 0) return
    const profiles = this.persisted.profiles.slice()
    profiles[i] = change(profiles[i])
    this.persisted = { ...this.persisted, profiles }; this.save()
  }

  switchProfile(id: string): void {
    if (this.screen === 'guide') this.guideReset()
    this.persisted = { ...this.persisted, currentProfileID: id }; this.save()
  }

  // MARK: Guide core (shared with watch/overlays; the drift itself lives in AppModelGuide)

  /** Leaving the guide (or letting go of the remote, `guideRelease`): remember where the drift was so it resumes from
   *  there. A held focus snaps to what's on now on its channel unless it's in the first window, the one the guide comes
   *  back to (a focus paged ahead would describe a program hours away, with no cell lit). */
  guidePark(): void {
    this.cancelTimer('idle')
    this.guideScroll.resumeOffset = this.remoteHeld ? this.wrap(this.guideScroll.pos) : this.guideDriftOffset()
    const t = Time.now(), ws0 = this.windowStart(t, 0), k = this.remoteHeld ? this.guideKey(t) : null
    const pr = k != null ? this.program(k) : null
    if (k != null && pr != null) this.focused = pr.slot.start < ws0 + AppModel.windowSpan && pr.slot.end > ws0 ? k : this.cellAt(pr.channel, t, ws0)
    this.remoteHeld = false; this.windowOffset = 0
  }

  /** Profile change: start the guide over from the top. */
  guideReset(): void {
    this.cancelTimer('idle')
    this.guideScroll = new GuideScroll()
    this.remoteHeld = false; this.windowOffset = 0; this.focused = null
  }

  /** The cell on `channel` nearest `time` inside the window starting at `windowStart`. */
  cellAt(channel: Channel, time: Instant, windowStart: Instant): ProgramKey | null {
    const vis = this.slots(channel, windowStart, windowStart + AppModel.windowSpan)
    let pick: Slot | null = vis.find(s => s.start <= time && time < s.end) ?? null
    if (pick == null) {
      for (const s of vis) if (pick == null || Math.abs(s.start - time) < Math.abs(pick.start - time)) pick = s
    }
    if (pick == null) pick = this.slotOnAir(channel, time)
    return pick == null ? null : this.key(channel, pick)
  }

  /** The focused cell, falling back to what's airing now on the first visible channel. */
  guideKey(at: Instant = Time.now()): ProgramKey | null {
    const k = this.focused
    const p = k == null ? null : this.program(k)
    if (k != null && p != null && !this.isHidden(p.channel.id)) {
      if (this.remoteHeld || p.slot.end > at) return k
      // Drifting and the focused program is over: follow the channel to what's on now (no mutation: this runs in view bodies).
      const now = this.cellAt(p.channel, at, this.windowStart(at, 0))
      if (now != null) return now
    }
    const ch = this.visibleChannels[0]
    return ch == null ? null : this.cellAt(ch, at, this.windowStart(at, 0))
  }

  // MARK: Tune / watch core

  /** Start watching `channelID`. 'live' joins in progress (unless the setting says start over and `force` is false);
   *  'start' plays the slot beginning at `programStart` from its beginning. */
  tune(channelID: string, mode: Playback.Mode, programStart: Instant | null = null, force = false): void {
    const ch = this.channel(channelID)
    if (ch == null) return
    const t = Time.now()
    const onAir = this.slotOnAir(ch, t)
    let pb: Playback
    if (mode === 'start' && programStart != null) {
      pb = Playback(channelID, 'start', programStart, { slot: this.slot(ch, programStart) })
    } else if ((ch.definition.tuneMode ?? this.settings.tuneMode) === 'start' && !force && onAir != null) {
      pb = Playback(channelID, 'start', onAir.start, { slot: onAir })
    } else {
      pb = Playback(channelID, 'live', onAir?.start ?? t, { slot: onAir })
    }
    const kept = this.playback?.tracks
    pb = { ...pb, tracks: kept != null && kept.itemID === pb.slot?.item.id ? kept : null }   // Start over / Back to live keep it; another program drops it
    pb = { ...pb, fromStart: pb.mode === 'start' }   // both 'start' cases above play from the slot's beginning
    if (this.screen === 'guide') this.guidePark()
    this.cancelTimer('seek')   // a pending debounced skip must not reload the channel we just left
    this.closeEntry()          // nor a typed number tune over a click's channel
    const same = this.screen === 'watch' && this.playback?.channelID === channelID
    // Start over (the banner's, a break's "Watch again", Program info's, the guide's Play): the tape rewinds instead of
    // the static and the ident card, from where the program on screen was, or from the guide, from where its broadcast
    // is (none before it airs).
    const was = same && mode === 'start' ? this.watchInfo() : null
    const aired = !same && mode === 'start' && programStart != null ? t - programStart : 0
    if (was != null && was.slot.start === programStart && was.position >= 1) {
      this.rewind = { from: Math.min(was.position, was.slot.item.runtime), started: t }
    } else if (aired >= 1 && pb.slot != null) {
      this.rewind = { from: Math.min(aired, pb.slot.item.runtime), started: t }
    } else this.rewind = null
    if (this.rewind != null) this.later('rewind', AppModel.rewindLength, () => { this.rewind = null })
    const prev = this.playback?.channelID
    if (prev != null && prev !== channelID) { this.persisted = { ...this.persisted, lastChannelID: prev }; this.save() }
    this.screen = 'watch'; this.overlay = null; this.focusIndex = 0
    this.playback = pb
    this.tunes += 1
    this.bannerVisible = this.watchInfo()?.isBreak !== true; this.bannerFocus = -1; this.trackMenu = null   // in a break the card says it all; the banner would cover it
    if (!same) { if (this.rewind == null) { this.flips += 1; this.identSerial += 1 } else this.flips = 0 }
    this.bannerLater()
  }

  /** The selection to request for item `itemID`: the per-program override if the banner set one for that item, else
   *  the Settings preferences matched against the item's tracks (see `TrackSelection.preferred`). A later program on
   *  the same channel never inherits the override (its stream indices belong to another file). */
  trackSelection(tracks: readonly MediaTrack[], itemID: string): TrackSelection {
    const o = this.playback?.tracks
    if (o != null && o.itemID === itemID) return o.selection
    const s = this.settings
    return TrackSelection.preferred(tracks, this.server?.kind ?? null, s.audioLanguage, s.subtitleMode,
      s.subtitleLanguage ?? AppModel.deviceLanguage, AppModel.deviceLanguage)
  }

  get lastChannel(): Channel | null {
    const id = this.persisted.lastChannelID
    const ch = id == null ? null : this.channel(id)
    return ch == null || this.isHidden(ch.id) ? null : ch
  }

  /** Back to the guide, drifting from the channel that was on. */
  toGuide(from: string): void {
    const list = this.visibleChannels
    const i = list.findIndex(c => c.id === from)
    if (i >= 0) {
      this.guideScroll.resumeOffset = this.wrap((i - 1) * this.rowPitch)
      const t = Time.now()
      this.focused = this.cellAt(list[i], t, this.windowStart(t, 0))
    } else this.focused = null
    this.cancelTimer('banner'); this.closeEntry()
    this.screen = 'guide'; this.overlay = null; this.focusIndex = 0; this.editingField = null
    this.windowOffset = 0; this.remoteHeld = false; this.bannerVisible = false; this.bannerFocus = -1; this.trackMenu = null; this.rewind = null
  }

  // MARK: Channels

  applyTuneOverride(): void {
    const n = this.tuneOverride
    if (n == null || this.screen !== 'guide') return
    const ch = this.channels.find(c => c.number === n)
    if (ch == null) return
    this.tuneOverride = null; this.tune(ch.id, 'live')
  }

  /** Automatic channels from the library + user edits + custom channels, resolved and sorted by number. */
  async rebuildChannelsAsync(): Promise<void> {
    const gen = ++this.rebuildGen   // a newer rebuild (a full load landing mid-way) must not be overwritten
    let r = await AppModel.resolveChannels(this.library, this.freshItems, this.persisted.channelEdits, this.persisted.customChannels)
    if (gen !== this.rebuildGen) return
    if (this.migrateGenreIDs(r)) r = await AppModel.resolveChannels(this.library, this.freshItems, this.persisted.channelEdits, this.persisted.customChannels)
    if (gen !== this.rebuildGen) return
    this.autoDefinitions = r.auto; this.channels = r.channels
    if (this.screen === 'guide') this.holdForLaunch()   // the lineup came in under the guide's loader
  }

  /** Carry hidden flags and edits across a genre shelf's split into films + TV or merge back (GenreIDMigration).
   *  Returns true when an edit moved, so the lineup must re-resolve. */
  migrateGenreIDs(r: Resolved): boolean {
    const hidden = this.persisted.profiles.map(p => new Set(p.hiddenChannelIDs))   // updated in place (Swift inout)
    const edits = new Map(this.persisted.channelEdits)
    const sizes = new Map<string, number>()
    for (const c of r.channels) sizes.set(c.id, Math.max(sizes.get(c.id) ?? 0, c.items.length))
    const m = GenreIDMigration.migrate(r.auto.map(d => d.id), sizes, hidden, edits)
    if (!m.changed) return false
    this.persisted = {
      ...this.persisted,
      profiles: this.persisted.profiles.map((p, i) => p.copy({ hiddenChannelIDs: hidden[i] })),
      channelEdits: edits,
    }
    this.save()
    return m.editMoved
  }

  /** Replace (or insert) one channel from its definition: one library filter instead of one per definition. */
  private splice(d: ChannelDefinition): void {
    const others = this.channels.filter(c => c.id !== d.id)
    this.channels = stableSort(others.concat(ChannelBuilder.channels([d], this.library, this.freshItems, Time.now())), byNumber)
  }

  /** Edit one field of a channel (automatic → recorded as an edit; custom → written into its definition).
   *  `updateChannel(id, d => d.copy({ name: n }))` (the Swift `{ $0.name = n }`). */
  updateChannel(id: string, change: (d: ChannelDefinition) => ChannelDefinition): void {
    const i = this.persisted.customChannels.findIndex(d => d.id === id)
    if (i >= 0) {
      const custom = this.persisted.customChannels.slice()
      custom[i] = change(custom[i])
      this.persisted = { ...this.persisted, customChannels: custom }
    } else {
      const ch = this.channel(id)
      if (ch != null) {
        const d = change(ch.definition)
        const e: ChannelEdit = { ...(this.persisted.channelEdits.get(id) ?? ChannelEdit.make()),
          name: d.name, number: d.number, order: d.order, trailerBreaks: d.trailerBreaks, tuneMode: d.tuneMode }
        this.persisted = { ...this.persisted, channelEdits: new Map(this.persisted.channelEdits).set(id, e) }
        this.save(); this.splice(d); return
      }
    }
    this.save()
    const d = this.persisted.customChannels.find(c => c.id === id)
    if (d) this.splice(d)
  }

  resetChannel(id: string): void {
    const edits = new Map(this.persisted.channelEdits)
    edits.delete(id)
    this.persisted = { ...this.persisted, channelEdits: edits }; this.save()
    const d = this.autoDefinitions.find(it => it.id === id)
    if (d) this.splice(d)
  }
  deleteCustomChannel(id: string): void {
    this.persisted = { ...this.persisted, customChannels: this.persisted.customChannels.filter(d => d.id !== id) }; this.save()
    this.channels = this.channels.filter(c => c.id !== id)
  }
  addCustomChannel(d: ChannelDefinition): void {
    this.persisted = { ...this.persisted, customChannels: this.persisted.customChannels.concat(d) }; this.save(); this.splice(d)
  }
  setHidden(id: string, hidden: boolean): void {
    this.updateProfile(p => {
      const ids = new Set(p.hiddenChannelIDs)
      if (hidden) ids.add(id); else ids.delete(id)
      return p.copy({ hiddenChannelIDs: ids })
    })
  }
  /** The movie whose trailer fills a break on `channel` after `slot`. Deterministic (channel + slot start) so the guide's
   *  preview window and the player agree. Whether the server actually has a trailer for it is only known at playback. */
  breakTrailerCandidate(channel: Channel, slot: Slot): MediaItem | null {
    let pool = channel.items.filter(it => it.kind === 'movie' && it.id !== slot.item.id)
    if (pool.length === 0) pool = this.movies.filter(it => it.id !== slot.item.id)
    if (pool.length === 0) return null
    const h = StableHash.fnv1a(`${channel.id}|${Math.floor(slot.start)}`)
    return pool[Number(h % BigInt(pool.length))]
  }

  /** Lowest unused channel number from 50 up (the prototype's rule for custom channels). */
  get nextFreeNumber(): number {
    const used = new Set(this.channels.map(c => c.number))
    let n = 50
    while (used.has(n)) n += 1
    return n
  }

  // MARK: Server / library

  connect(server: MediaServer, config: ServerConfig, token: string): void {
    this.username = ''; this.password = ''   // a shared TV mustn't offer the next person this sign-in
    Keychain.set(token, 'token')
    this.persisted = { ...this.persisted, serverConfig: config }; this.save()
    this.libraryGen += 1; this.freshItems = []
    this.server = server
  }

  /** Re-create the server client from the saved config (after a settings change the clients read, e.g. quality). */
  rebuildServer(): void {
    const cfg = this.persisted.serverConfig
    if (cfg == null) return
    const token = Keychain.get('token') ?? (cfg.kind === 'demo' ? '' : null)
    if (token == null) return
    this.server = cfg.makeServer(token, AppModel.deviceID, this.settings.maxBitrateKbps)
  }

  /** The saved Plex address stopped answering: a new DHCP lease, a moved box, a new public IP. Try the other
   *  addresses plex.tv listed at sign-in, then ask plex.tv afresh with the account token, and switch to whichever answers
   *  as this server (its /identity must match the machineIdentifier). Returns true when the address changed, so the caller
   *  can retry once. At most one search a minute, so a server that is simply off isn't hammered. */
  async recoverPlexAddress(): Promise<boolean> {
    const cfg = this.persisted.serverConfig
    const id = cfg?.machineID
    if (cfg == null || cfg.kind !== 'plex' || id == null) return false
    if (this.lastPlexRecovery != null && Time.now() - this.lastPlexRecovery <= 60) return false
    this.lastPlexRecovery = Time.now()
    let url: string | null = null
    let addresses = cfg.addresses
    let token: string | null = null
    const a = cfg.addresses
    // The link's own token: the CORS fallback retries a plex.tv-listed https address with it (PlexClient.ts, firstAnswering).
    if (a != null && a.length > 1) url = await PlexAuth.bestAddress(a, id, AppModel.deviceID, false, Keychain.get('token'))
    if (url == null || url === cfg.baseURL) {
      const account = Keychain.get('plexAccount')
      const s = account == null ? null : await runCatchingNonCancel(async () => (await PlexAuth.servers(account, AppModel.deviceID, id))[0] ?? null)
      if (s != null) {
        url = s.baseURL; addresses = s.addresses == null || s.addresses.length === 0 ? addresses : s.addresses; token = s.accessToken
      }
    }
    const current = this.persisted.serverConfig
    if (url == null || url === cfg.baseURL || current?.machineID !== id) return false   // still the same link
    this.persisted = { ...this.persisted, serverConfig: current.copy({ baseURL: url, addresses }) }; this.save()
    if (token != null) Keychain.set(token, 'token')
    this.rebuildServer()
    return true
  }

  /** Phones and tablets move between networks: use whichever of the Plex server's addresses answers best right now.
   *  Off TV only on Android, so nothing calls it on the web (always a TV, which keeps the linked address). */
  async pickPlexAddress(): Promise<void> {
    const cfg = this.persisted.serverConfig
    const addresses = cfg?.addresses
    const id = cfg?.machineID
    if (addresses == null || id == null || addresses.length <= 1) return
    const url = await PlexAuth.bestAddress(addresses, id, AppModel.deviceID)
    const current = this.persisted.serverConfig
    if (url == null || url === current?.baseURL || current?.machineID !== id) return   // still the same link
    this.persisted = { ...this.persisted, serverConfig: current.copy({ baseURL: url }) }; this.save()
    this.rebuildServer()
  }

  signOut(): void {
    this.libraryGen += 1
    Keychain.delete('token'); Keychain.delete('plexAccount')
    this.persisted = { ...this.persisted, serverConfig: null, selectedLibraryIDs: null }; this.save()
    this.server = null; this.library = []; this.channels = []; this.librarySections = []; this.freshItems = []
    this.username = ''; this.password = ''; this.pendingToken = null; this.foundServers = []   // the plex.tv token and server tokens too
    LibraryCache.clear()
    // Android also empties its decoded-artwork cache here; the browser's image cache is keyed by URL, and every artwork
    // URL carries the old token, so nothing is ever shown from it again.
    this.go('welcome')
  }

  /** Load the library: cached index first (instant launch), then refresh from the server unless the cache is under
   *  six hours old (`force` skips that gate: Settings › Rebuild, a changed library selection). A newer load, sign-out
   *  or connect supersedes this one at every await (generation counter), so a slow refresh can't write stale data.
   *  `signal` (web only) is the Building screen's `loadTask`: aborting it is Kotlin's job cancel. */
  async loadLibrary(refresh = true, force = false, retried = false, signal: AbortSignal | null = null): Promise<void> {
    try { await this.loadLibraryOnce(refresh, force, retried, signal) }
    catch (e) { if (isCancellation(e)) this.libraryLoading = false; throw e }   // Back on the Building screen: not a server error
  }

  private async loadLibraryOnce(refresh: boolean, force: boolean, retried: boolean, signal: AbortSignal | null): Promise<void> {
    const server = this.server
    if (server == null) return
    const gen = ++this.libraryGen
    const link = this.persisted.serverConfig?.link ?? ''
    if (this.library.length === 0) {
      const cached = await cancellable(signal, LibraryCache.load(link))
      if (cached != null) {
        if (gen !== this.libraryGen) return
        this.library = cached; await this.rebuildChannelsAsync(); this.applyTuneOverride()
        ensureActive(signal)
        if (gen !== this.libraryGen) return
      }
    }
    if (!refresh) return
    const age = LibraryCache.age
    if (!force && this.library.length > 0 && age != null && age < 6 * 3600) { this.libraryLoading = false; return }   // start() set it
    this.libraryLoading = true; this.libraryError = null
    try {
      const sections = await cancellable(signal, server.libraries())
      if (gen !== this.libraryGen) return
      this.librarySections = sections.filter(s => s.itemCount !== 0)   // as fetchLibraries lists them: the Libraries screen may be up
      const ids = this.persisted.selectedLibraryIDs
      const chosen = ids == null ? null : sections.filter(s => ids.indexOf(s.id) >= 0)
      const items = await cancellable(signal, server.libraryItems(chosen))
      if (gen !== this.libraryGen) return
      this.library = items; this.freshItems = []   // a full load has everything the open-the-app check found
      // Kotlin's NonCancellable block: no abort checks from here on. The save is awaited, not detached, to keep its order
      // with signOut() → clear(); the channels are rebuilt in the same stretch, so the new library never sits beside the
      // old lineup.
      await LibraryCache.save(this.library, link)
      if (gen !== this.libraryGen) {
        // Signed out or switched servers while it saved: that entry belongs to no current link, so it goes.
        if (this.persisted.serverConfig?.link !== link) LibraryCache.clear()
        return
      }
      await this.rebuildChannelsAsync(); this.applyTuneOverride()
    } catch (e) {
      if (isCancellation(e)) throw e
      if (gen !== this.libraryGen) return
      if (!retried && AppModel.isUnreachable(e) && await this.recoverPlexAddress()) {   // the server moved: try once at its new address
        await this.loadLibrary(true, true, true, signal); return
      }
      this.libraryLoading = false   // before serverFailed: a dead token signs out and lands on Welcome
      this.libraryError = this.serverFailed(e)
      const err = this.libraryError
      if (this.channels.length > 0 && err != null) this.toast(err)   // the cache is on screen, so the guide's empty-state line never shows
    }
    this.libraryLoading = false
  }

  /** Opening the app (launch, or back from the Home screen): ask the server for what's been added since the last full
   *  load, so Just Added, New Releases and New This Season are current. Every other channel keeps today's lineup until
   *  the next full load. Best effort and silent: on any error the guide keeps what it has. */
  async checkRecentlyAdded(): Promise<void> {
    const server = this.server
    if (server == null || this.library.length === 0 || this.libraryLoading || this.checkingRecent) return
    this.checkingRecent = true
    const gen = this.libraryGen
    try {
      const ids = this.persisted.selectedLibraryIDs
      const chosen = ids == null ? null
        : (this.librarySections.length > 0 ? this.librarySections : await server.libraries()).filter(s => ids.indexOf(s.id) >= 0)
      // ponytail: the newest 50 movies and 50 episodes per library; a bigger import waits for the next full load.
      const recent = await server.recentlyAdded(chosen, 50)
      if (gen !== this.libraryGen || this.libraryLoading) return
      const known = new Set(this.library.map(it => it.id))
      const fresh = recent.filter(it => !known.has(it.id))
      if (fresh.map(it => it.id).join('\n') === this.freshItems.map(it => it.id).join('\n')) return
      this.freshItems = fresh
      await this.rebuildChannelsAsync()
    } catch (e) {
      if (isCancellation(e)) throw e
    } finally {
      this.checkingRecent = false
    }
  }

  /** Every server catch routes here: a dead token signs the device out; anything else becomes one short user-safe line. */
  serverFailed(error: unknown): string {
    if (AppModel.isUnreachable(error)) {   // a stream or preview that can't reach the server: look for it, then reload what's on screen
      void this.recoverPlexAddress().then(moved => { if (moved) { this.tunes += 1; this.foregroundSerial += 1 } })
    }
    if (error instanceof MediaServerError && error.kind === 'unauthorized') {
      const msg = `${this.server ? ServerKind.displayName(this.server.kind) : 'The server'} signed this device out. Sign in again.`
      this.toast(msg); this.signOut()
      return msg
    }
    const text = errorText(error)
    const cut = text.search(/[\n<{]/)
    return (cut < 0 ? text : text.slice(0, cut)).slice(0, 120).replace(/^[ :]+|[ :]+$/g, '')
  }

  save(): void { Persisted.save(this.persisted) }
}

export namespace AppModel {
  /** Kotlin's coroutine `Job`, as much of it as the model uses: an async task with an AbortController behind it. */
  export interface Job { cancel(): void }

  /** Options menu rows (AppModel+Overlays' `OptionRow`; nested here because Kotlin extensions can't add types). */
  export type OptionRow = 'profile' | 'guide' | 'movies' | 'tv' | 'search' | 'channels' | 'settings' | 'theme'
  export const OptionRow = {
    entries: ['profile', 'guide', 'movies', 'tv', 'search', 'channels', 'settings', 'theme'] as readonly OptionRow[],
  }

  /** The number box's digits (up to three). `missing`: the number had no channel, and the box says so for a moment. */
  export interface ChannelEntry { readonly digits: string; readonly missing: boolean }

  /** One Program-info action (AppModel+Overlays' `InfoAction`). */
  export class InfoAction {
    constructor(readonly label: string, readonly run: () => void) {}
  }
}

/**
 * Swift's `error.localizedDescription` (Kotlin's `errorText`, in AppModelOnboarding.kt; here because AppModel can't import
 * an AppModel*.ts file): the transport failures read as URLError's text; MediaServerError's message is already its
 * errorDescription. `fetch` can't tell a refused connection from an unknown host, so both read as "Could not connect".
 */
export function errorText(e: unknown): string {
  if (e instanceof TypeError) return 'Could not connect to the server.'
  if (e instanceof Error && e.name === 'TimeoutError') return 'The request timed out.'
  if (e instanceof SyntaxError || e instanceof JSONException) return 'The data couldn’t be read because it isn’t in the correct format.'
  return (e instanceof Error && e.message) || 'The operation couldn’t be completed.'
}

/** JSON cache of the library index so the guide is up before the server answers. In IndexedDB (purgeable, like the
 *  Swift Caches): several MB, past localStorage's quota. */
export const LibraryCache = {
  /** The entry holds the items and the link they came from (`ServerConfig.link`): a save that lands after a sign-out or
   *  a switch to another server is never read back under the new one. */
  async load(link: string): Promise<MediaItem[] | null> {
    try {
      const s = await cacheStore.get('library')
      if (typeof s !== 'string') return null
      const o = asObject(JSON.parse(s))
      return getString(o, 'link') !== link ? null : objects(getJSONArray(o, 'items')).map(it => MediaItem.fromJson(it))
    } catch (e) {
      return null   // no cache yet, an older schema, or another link's: the refresh replaces it
    }
  },

  /** One JSON string, like the Android file: a single clone of one string beats one per item. */
  async save(items: readonly MediaItem[], link: string): Promise<void> {
    try {
      await cacheStore.set('library', JSON.stringify({ link, items: items.map(it => it.toJson()) }))
      defaults.set('libraryCachedAt', String(Date.now()))
    } catch (e) {
      console.warn('LibraryCache save', e)
    }
  },

  clear(): void {
    cacheStore.delete('library').catch(() => {})
    defaults.remove('libraryCachedAt')
  },

  /** Seconds since the cache was written (tracked in `defaults`, so it reads synchronously). */
  get age(): number | null {
    const at = Number(defaults.get('libraryCachedAt') ?? NaN)
    return isFinite(at) ? (Date.now() - at) / 1000 : null
  },
}
