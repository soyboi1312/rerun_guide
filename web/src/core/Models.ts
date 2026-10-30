// Port of android/core Models.kt (and Scrambled.kt, which builds `Channel.scrambled`: kept here so Models doesn't import a
// module that imports Models). Enums are string unions spelled like the Kotlin constants, with their members on a
// same-named object; sealed interfaces are unions on `kind`; data classes with getters are classes with `copy()`.
import {
  JSONException, JSONObject, asObject, doubleOrNull, getBoolean, getDouble, getInt, getJSONObject, getString, intOrNull,
  objects, optBoolean, optInt, optJSONArray, optString, putOpt, stringOrNull, strings,
} from './Lang'
import { Instant } from './Time'

export type ServerKind = 'plex' | 'emby' | 'jellyfin' | 'demo'
export const ServerKind = {
  entries: ['plex', 'emby', 'jellyfin', 'demo'] as readonly ServerKind[],
  displayName(k: ServerKind): string {
    return k === 'plex' ? 'Plex' : k === 'emby' ? 'Emby' : k === 'jellyfin' ? 'Jellyfin' : 'Demo'
  },
  from(rawValue: string | null | undefined): ServerKind | null {
    return ServerKind.entries.indexOf(rawValue as ServerKind) >= 0 ? rawValue as ServerKind : null
  },
}

/** A library/section on the server (Plex section, Jellyfin/Emby view). Users pick which ones go on the air. */
export interface LibrarySection {
  readonly id: string
  readonly title: string
  readonly kind: LibrarySection.Kind
  readonly itemCount?: number | null
}
export namespace LibrarySection {
  export type Kind = 'movies' | 'shows' | 'other'
  export const Kind = {
    from(rawValue: string | null | undefined): Kind | null {
      return rawValue === 'movies' || rawValue === 'shows' || rawValue === 'other' ? rawValue : null
    },
  }
  export function toJson(s: LibrarySection): JSONObject {
    return putOpt({ id: s.id, title: s.title, kind: s.kind }, 'itemCount', s.itemCount)
  }
  export function fromJson(o: JSONObject): LibrarySection {
    const kind = Kind.from(getString(o, 'kind'))
    if (kind == null) throw new JSONException('kind')
    return { id: getString(o, 'id'), title: getString(o, 'title'), kind, itemCount: intOrNull(o, 'itemCount') }
  }
}

export interface MediaItemInit {
  id: string
  kind: MediaItem.Kind
  title: string
  seriesTitle?: string | null
  seriesID?: string | null
  season?: number | null
  episode?: number | null
  year?: number | null
  genres?: readonly string[]
  /** Seconds. */
  runtime: number
  artworkPath?: string | null
  backdropPath?: string | null
  overview?: string | null
  contentRating?: string | null
  directors?: readonly string[]
  actors?: readonly string[]
  libraryID?: string | null
  addedAt?: Instant | null
  watched?: boolean
}

/** One playable thing from the library. Server-agnostic. */
export class MediaItem {
  readonly id: string
  readonly kind: MediaItem.Kind
  readonly title: string
  readonly seriesTitle: string | null
  readonly seriesID: string | null
  readonly season: number | null
  readonly episode: number | null
  readonly year: number | null
  readonly genres: readonly string[]
  /** Seconds. */
  readonly runtime: number
  /** Server-relative poster/thumb path. Resolve with `MediaServer.artworkURL(path)`. */
  readonly artworkPath: string | null
  /** Server-relative wide art (backdrop/fanart) path, used by the guide's preview window. */
  readonly backdropPath: string | null
  readonly overview: string | null
  /** "PG", "R", "TV-14"… as the server reports it. */
  readonly contentRating: string | null
  readonly directors: readonly string[]
  /** Top-billed cast, in billing order (first ~6 is plenty). */
  readonly actors: readonly string[]
  /** Which `LibrarySection` it came from, when known. */
  readonly libraryID: string | null
  /** When the server added it to the library (Plex addedAt, Emby/Jellyfin DateCreated). Drives "Just Added". */
  readonly addedAt: Instant | null
  /**
   * The signed-in user has watched it, as the server says (Plex viewCount, Emby/Jellyfin UserData.Played). Drives
   * "Unwatched". Required in the JSON, so a library cache written before it is unreadable and refetched.
   */
  readonly watched: boolean

  constructor(p: MediaItemInit) {
    this.id = p.id
    this.kind = p.kind
    this.title = p.title
    this.seriesTitle = p.seriesTitle ?? null
    this.seriesID = p.seriesID ?? null
    this.season = p.season ?? null
    this.episode = p.episode ?? null
    this.year = p.year ?? null
    this.genres = p.genres ?? []
    this.runtime = p.runtime
    this.artworkPath = p.artworkPath ?? null
    this.backdropPath = p.backdropPath ?? null
    this.overview = p.overview ?? null
    this.contentRating = p.contentRating ?? null
    this.directors = p.directors ?? []
    this.actors = p.actors ?? []
    this.libraryID = p.libraryID ?? null
    this.addedAt = p.addedAt ?? null
    this.watched = p.watched ?? false
  }

  copy(changes: Partial<MediaItemInit>): MediaItem {
    return new MediaItem({ ...this, ...changes })
  }

  /** Guide-cell headline: series name for episodes, title for movies. */
  get displayTitle(): string {
    return this.kind === 'episode' ? (this.seriesTitle ?? this.title) : this.title
  }

  /** "S5 E9" or "". */
  get seasonEpisode(): string {
    const parts: string[] = []
    if (this.season != null) parts.push(`S${this.season}`)
    if (this.episode != null) parts.push(`E${this.episode}`)
    return parts.join(' ')
  }

  /** Guide-cell second line: episode title for episodes, year for movies. */
  get subtitle(): string {
    return this.kind === 'episode' ? this.title : this.year != null ? String(this.year) : ''
  }

  /** Info-panel sub line. Episodes: “Episode” · S5 E9 · 1986. Movies: 1981 · PG · 115 min. */
  get infoLine(): string {
    const parts: (string | null)[] = this.kind === 'episode'
      ? [`“${this.title}”`, this.seasonEpisode || null, this.year != null ? String(this.year) : null]
      : [this.year != null ? String(this.year) : null, this.contentRating, `${Math.trunc(this.runtime / 60)} min`]
    return parts.filter(p => p != null).join(' · ')
  }

  /** Keys as Swift's synthesized Codable; dates as epoch seconds; nulls omitted. */
  toJson(): JSONObject {
    const o: JSONObject = { id: this.id, kind: this.kind, title: this.title }
    putOpt(o, 'seriesTitle', this.seriesTitle)
    putOpt(o, 'seriesID', this.seriesID)
    putOpt(o, 'season', this.season)
    putOpt(o, 'episode', this.episode)
    putOpt(o, 'year', this.year)
    o.genres = this.genres.slice()
    o.runtime = this.runtime
    putOpt(o, 'artworkPath', this.artworkPath)
    putOpt(o, 'backdropPath', this.backdropPath)
    putOpt(o, 'overview', this.overview)
    putOpt(o, 'contentRating', this.contentRating)
    o.directors = this.directors.slice()
    o.actors = this.actors.slice()
    putOpt(o, 'libraryID', this.libraryID)
    putOpt(o, 'addedAt', this.addedAt)
    o.watched = this.watched
    return o
  }

  /**
   * A film's title with its cut, "Watchmen (Directors Cut)": two cuts are two films, on screen and to the scheduler
   * (`GuideGenerator.repeatKey`), while two plain copies stay one.
   */
  static title(title: string, edition: string | null | undefined): string {
    const e = edition?.trim()
    return !e ? title : `${title} (${e})`
  }

  /** The cut named in a file path, Plex style: "Watchmen (2009) {edition-Directors Cut}.mkv". */
  static edition(inPath: string | null | undefined): string | null {
    if (inPath == null) return null
    const m = /\{edition-([^}]+)\}/.exec(inPath)
    return m ? m[1] : null
  }

  static fromJson(o: JSONObject): MediaItem {
    const kind = MediaItem.Kind.from(getString(o, 'kind'))
    if (kind == null) throw new JSONException('kind')
    return new MediaItem({
      id: getString(o, 'id'), kind, title: getString(o, 'title'), seriesTitle: stringOrNull(o, 'seriesTitle'),
      seriesID: stringOrNull(o, 'seriesID'), season: intOrNull(o, 'season'), episode: intOrNull(o, 'episode'),
      year: intOrNull(o, 'year'), genres: strings(o, 'genres'), runtime: getDouble(o, 'runtime'),
      artworkPath: stringOrNull(o, 'artworkPath'), backdropPath: stringOrNull(o, 'backdropPath'),
      overview: stringOrNull(o, 'overview'), contentRating: stringOrNull(o, 'contentRating'),
      directors: strings(o, 'directors'), actors: strings(o, 'actors'), libraryID: stringOrNull(o, 'libraryID'),
      addedAt: doubleOrNull(o, 'addedAt'), watched: getBoolean(o, 'watched'),
    })
  }

  static readonly Kind = {
    from(rawValue: string | null | undefined): MediaItem.Kind | null {
      return rawValue === 'movie' || rawValue === 'episode' ? rawValue : null
    },
  }
}
export namespace MediaItem {
  export type Kind = 'movie' | 'episode'
}

/** What "tune in" means: join the program in progress (real TV) or start it from the beginning. */
export type TuneMode = 'live' | 'start'
export const TuneMode = {
  entries: ['live', 'start'] as readonly TuneMode[],
  from(rawValue: string | null | undefined): TuneMode | null {
    return rawValue === 'live' || rawValue === 'start' ? rawValue : null
  },
}

export type PlayOrder = 'shuffle' | 'inOrder' | 'newestFirst'
export const PlayOrder = {
  entries: ['shuffle', 'inOrder', 'newestFirst'] as readonly PlayOrder[],
  rawValue(o: PlayOrder): string {
    return o === 'shuffle' ? 'Shuffle' : o === 'inOrder' ? 'In order' : 'Newest first'
  },
  next(o: PlayOrder): PlayOrder {
    return o === 'shuffle' ? 'inOrder' : o === 'inOrder' ? 'newestFirst' : 'shuffle'
  },
  from(rawValue: string | null | undefined): PlayOrder | null {
    for (const o of PlayOrder.entries) if (PlayOrder.rawValue(o) === rawValue) return o
    return null
  },
}

/** One criterion of a channel's rule. A channel's items are the library items matching EVERY chip. */
export type RuleChip =
  | { readonly kind: 'genre'; readonly value: string }
  | { readonly kind: 'years'; readonly from: number; readonly to: number }
  | { readonly kind: 'director'; readonly name: string }
  | { readonly kind: 'actor'; readonly name: string }
  | { readonly kind: 'series'; readonly id: string; readonly title: string }
  | { readonly kind: 'year'; readonly value: number }
  | { readonly kind: 'movies' }
  | { readonly kind: 'tv' }
  /** Added to the server within the last N days (relative to when the channels are rebuilt). */
  | { readonly kind: 'addedWithin'; readonly days: number }
  /** Any of these genres. One chip, so it ANDs with the rest of the rule like every other chip. */
  | { readonly kind: 'anyGenre'; readonly genres: readonly string[] }
  /** Title or overview mentions any of these words: case-insensitive, matched at a word start ("zombie" takes "zombies"). */
  | { readonly kind: 'keywords'; readonly words: readonly string[] }
  /** Not watched by the signed-in user (`MediaItem.watched`). */
  | { readonly kind: 'unwatched' }

export const RuleChip = {
  genre: (value: string): RuleChip => ({ kind: 'genre', value }),
  years: (from: number, to: number): RuleChip => ({ kind: 'years', from, to }),
  director: (name: string): RuleChip => ({ kind: 'director', name }),
  actor: (name: string): RuleChip => ({ kind: 'actor', name }),
  series: (id: string, title: string): RuleChip => ({ kind: 'series', id, title }),
  year: (value: number): RuleChip => ({ kind: 'year', value }),
  movies: { kind: 'movies' } as RuleChip,
  tv: { kind: 'tv' } as RuleChip,
  addedWithin: (days: number): RuleChip => ({ kind: 'addedWithin', days }),
  anyGenre: (genres: readonly string[]): RuleChip => ({ kind: 'anyGenre', genres }),
  keywords: (words: readonly string[]): RuleChip => ({ kind: 'keywords', words }),
  unwatched: { kind: 'unwatched' } as RuleChip,

  /** Chip text as shown in the channel editor: "Genre: Sitcom", "1975–1999", "Director: Steven Spielberg", "TV". */
  label(c: RuleChip): string {
    switch (c.kind) {
      case 'genre': return `Genre: ${c.value}`
      case 'years': return c.from <= 1900 ? `Before ${c.to + 1}` : `${c.from}–${c.to}`
      case 'director': return `Director: ${c.name}`
      case 'actor': return `Actor: ${c.name}`
      case 'series': return `Show: ${c.title}`
      case 'year': return `Year: ${c.value}`
      case 'movies': return 'Movies'
      case 'tv': return 'TV'
      case 'addedWithin': return `Added in the last ${c.days} days`
      case 'anyGenre': return 'Genre: ' + c.genres.join(' / ')
      case 'keywords': return 'Mentions: ' + c.words.slice(0, 4).join(', ') + (c.words.length > 4 ? '…' : '')
      case 'unwatched': return 'Unwatched'
    }
  },

  /** Swift's synthesized enum Codable shape: {"genre":{"_0":"Comedy"}}, {"series":{"id":…,"title":…}}, {"movies":{}}. */
  toJson(c: RuleChip): JSONObject {
    const wrap = (v: JSONObject): JSONObject => ({ [c.kind]: v })
    switch (c.kind) {
      case 'genre': return wrap({ _0: c.value })
      case 'years': return wrap({ _0: c.from, _1: c.to })
      case 'director': return wrap({ _0: c.name })
      case 'actor': return wrap({ _0: c.name })
      case 'series': return wrap({ id: c.id, title: c.title })
      case 'year': return wrap({ _0: c.value })
      case 'movies': return wrap({})
      case 'tv': return wrap({})
      case 'addedWithin': return wrap({ days: c.days })
      case 'anyGenre': return wrap({ _0: c.genres.slice() })
      case 'keywords': return wrap({ _0: c.words.slice() })
      case 'unwatched': return wrap({})
    }
  },

  fromJson(o: JSONObject): RuleChip {
    const keys = Object.keys(o)
    if (keys.length !== 1) throw new JSONException('RuleChip: one key expected')
    const k = keys[0]
    const v = getJSONObject(o, k)
    switch (k) {
      case 'genre': return RuleChip.genre(getString(v, '_0'))
      case 'years': return RuleChip.years(getInt(v, '_0'), getInt(v, '_1'))
      case 'director': return RuleChip.director(getString(v, '_0'))
      case 'actor': return RuleChip.actor(getString(v, '_0'))
      case 'series': return RuleChip.series(getString(v, 'id'), getString(v, 'title'))
      case 'year': return RuleChip.year(getInt(v, '_0'))
      case 'movies': return RuleChip.movies
      case 'tv': return RuleChip.tv
      case 'addedWithin': return RuleChip.addedWithin(getInt(v, 'days'))
      case 'anyGenre': return RuleChip.anyGenre(strings(v, '_0'))
      case 'keywords': return RuleChip.keywords(strings(v, '_0'))
      case 'unwatched': return RuleChip.unwatched
      default: throw new JSONException(`RuleChip: unknown case ${k}`)
    }
  },
}

export interface ChannelDefinitionInit {
  id: string
  number: number
  name: string
  /** Short descriptor under the name: "Sitcoms, 70s–90s", "Director", "Marathon", "Year". */
  tag: string
  rule: readonly RuleChip[]
  order?: PlayOrder
  trailerBreaks?: boolean
  isCustom?: boolean
  /** Per-channel tune-in behaviour; null = follow the app setting. */
  tuneMode?: TuneMode | null
}

/**
 * What a channel IS, independent of the library contents. Automatic ones are rebuilt from the library each launch;
 * custom ones and per-channel edits are persisted by the app.
 */
export class ChannelDefinition {
  readonly id: string
  readonly number: number
  readonly name: string
  readonly tag: string
  readonly rule: readonly RuleChip[]
  readonly order: PlayOrder
  readonly trailerBreaks: boolean
  readonly isCustom: boolean
  readonly tuneMode: TuneMode | null

  constructor(p: ChannelDefinitionInit) {
    this.id = p.id
    this.number = p.number
    this.name = p.name
    this.tag = p.tag
    this.rule = p.rule
    this.order = p.order ?? 'shuffle'
    this.trailerBreaks = p.trailerBreaks ?? true
    this.isCustom = p.isCustom ?? false
    this.tuneMode = p.tuneMode ?? null
  }

  copy(changes: Partial<ChannelDefinitionInit>): ChannelDefinition {
    return new ChannelDefinition({ ...this, ...changes })
  }

  /** Stable 0..<360 hue for gradients, derived from the id (never from a per-process hash). */
  get hue(): number {
    return Number(StableHash.fnv1a(this.id) % BigInt(360))
  }

  toJson(): JSONObject {
    return putOpt({
      id: this.id, number: this.number, name: this.name, tag: this.tag, rule: this.rule.map(RuleChip.toJson),
      order: PlayOrder.rawValue(this.order), trailerBreaks: this.trailerBreaks, isCustom: this.isCustom,
    }, 'tuneMode', this.tuneMode)
  }

  static fromJson(o: JSONObject): ChannelDefinition {
    const rule = optJSONArray(o, 'rule')
    return new ChannelDefinition({
      id: getString(o, 'id'), number: optInt(o, 'number', 0), name: optString(o, 'name', ''), tag: optString(o, 'tag', ''),
      rule: rule == null ? [] : objects(rule).map(RuleChip.fromJson),
      order: PlayOrder.from(stringOrNull(o, 'order')) ?? 'shuffle',
      trailerBreaks: optBoolean(o, 'trailerBreaks', true), isCustom: optBoolean(o, 'isCustom', false),
      tuneMode: TuneMode.from(stringOrNull(o, 'tuneMode')),
    })
  }
}

let scrambledChannel: Channel | null = null

/** A guide row: a definition plus the library items it resolved to (already in `order`-appropriate sequence). */
export class Channel {
  /** Mostly films (vs. episodes); decides whether the channel belongs to the Movies or the TV guide. */
  readonly isMovies: boolean

  constructor(readonly definition: ChannelDefinition, readonly items: readonly MediaItem[]) {
    this.isMovies = items.filter(it => it.kind === 'movie').length * 2 >= items.length
  }

  /** Kotlin's convenience constructor, for tests and channel 99: a plain genre-style channel. */
  static make(id: string, number: number, name: string, items: readonly MediaItem[], tag = '', order: PlayOrder = 'shuffle'): Channel {
    return new Channel(new ChannelDefinition({ id, number, name, tag, rule: [], order }), items)
  }

  copy(changes: { definition?: ChannelDefinition; items?: readonly MediaItem[] }): Channel {
    return new Channel(changes.definition ?? this.definition, changes.items ?? this.items)
  }

  get id(): string { return this.definition.id }
  get number(): number { return this.definition.number }
  get name(): string { return this.definition.name }
  get tag(): string { return this.definition.tag }
  get order(): PlayOrder { return this.definition.order }
  get trailerBreaks(): boolean { return this.definition.trailerBreaks }
  get isCustom(): boolean { return this.definition.isCustom }
  get hue(): number { return this.definition.hue }
  /** Channel 99. */
  get isScrambled(): boolean { return this.id === Channel.scrambledID }

  static readonly scrambledID = 'scrambled'
  static readonly scrambledNumber = 99

  /**
   * Channel 99 (Scrambled.kt): the one every cable box had that nobody could quite make out. Its "programs" are stand-ins
   * the app never asks the server for; the picture is the scramble itself.
   */
  static get scrambled(): Channel {
    if (scrambledChannel == null) {
      const titles = ['Late Night Feature', 'Mystery Matinee', 'After Hours Special', 'Double Feature', 'Feature Presentation', 'Encore Showing']
      // Exactly an hour: the scheduler skips its pad for these, so the picture never drops into a station break.
      const items = titles.map((t, i) => new MediaItem({
        id: `scrambled-${i}`, kind: 'movie', title: t, year: 1986 + i, runtime: 3600,
        overview: 'Nothing comes in clearly on 99.', contentRating: 'NR',
      }))
      const ch = Channel.make(Channel.scrambledID, Channel.scrambledNumber, 'Channel 99', items, 'Scrambled', 'shuffle')
      scrambledChannel = ch.copy({ definition: ch.definition.copy({ trailerBreaks: false }) })
    }
    return scrambledChannel
  }
}

/**
 * One cell in the guide: an item airing on a channel between `start` and `end`.
 * `end - start` is the runtime (+3 min pad) rounded UP to the guide block (30 min), like a paper TV guide.
 * Time after the item ends but before `end` is the "break" (trailers / station card).
 */
export class Slot {
  constructor(readonly item: MediaItem, readonly start: Instant, readonly end: Instant) {}
  get id(): string { return `${this.item.id}@${Math.trunc(this.start)}` }
  get duration(): number { return this.end - this.start }
  /** When the item itself stops playing and the break begins. */
  get itemEnd(): Instant { return Math.min(this.end, this.start + this.item.runtime) }
}

export type ProgramStatus = 'later' | 'live' | 'break' | 'past'

/** One audio or subtitle track of an item, as the server reports it. */
export interface MediaTrack {
  /** Plex stream id / Jellyfin-Emby stream index, as a string. */
  readonly id: string
  readonly kind: MediaTrack.Kind
  /** ISO 639-2 code ("eng", "spa") when the server knows it. */
  readonly language: string | null
  /** "English (AAC Stereo)", "Spanish (SUBRIP)", "Commentary"… */
  readonly title: string
  readonly isDefault?: boolean
  readonly isForced?: boolean
}

// ISO 639-2/T code, 639-1 code, English name. Java names languages in the device's language; Intl.DisplayNames would
// too, but it is Chromium 81+. ponytail: English names for the common languages; anything else shows its code.
const languageTable: readonly [string, string, string][] = [
  ['afr', 'af', 'Afrikaans'], ['ara', 'ar', 'Arabic'], ['aze', 'az', 'Azerbaijani'], ['bel', 'be', 'Belarusian'],
  ['ben', 'bn', 'Bangla'], ['bod', 'bo', 'Tibetan'], ['bos', 'bs', 'Bosnian'], ['bul', 'bg', 'Bulgarian'],
  ['cat', 'ca', 'Catalan'], ['ces', 'cs', 'Czech'], ['cym', 'cy', 'Welsh'], ['dan', 'da', 'Danish'], ['deu', 'de', 'German'],
  ['ell', 'el', 'Greek'], ['eng', 'en', 'English'], ['est', 'et', 'Estonian'], ['eus', 'eu', 'Basque'], ['fas', 'fa', 'Persian'],
  ['fil', '', 'Filipino'], ['fin', 'fi', 'Finnish'], ['fra', 'fr', 'French'], ['gle', 'ga', 'Irish'], ['glg', 'gl', 'Galician'],
  ['guj', 'gu', 'Gujarati'], ['heb', 'he', 'Hebrew'], ['hin', 'hi', 'Hindi'], ['hrv', 'hr', 'Croatian'], ['hun', 'hu', 'Hungarian'],
  ['hye', 'hy', 'Armenian'], ['ind', 'id', 'Indonesian'], ['isl', 'is', 'Icelandic'], ['ita', 'it', 'Italian'],
  ['jpn', 'ja', 'Japanese'], ['kan', 'kn', 'Kannada'], ['kat', 'ka', 'Georgian'], ['kaz', 'kk', 'Kazakh'], ['khm', 'km', 'Khmer'],
  ['kor', 'ko', 'Korean'], ['lao', 'lo', 'Lao'], ['lat', 'la', 'Latin'], ['lav', 'lv', 'Latvian'], ['lit', 'lt', 'Lithuanian'],
  ['mal', 'ml', 'Malayalam'], ['mar', 'mr', 'Marathi'], ['mkd', 'mk', 'Macedonian'], ['mon', 'mn', 'Mongolian'],
  ['mri', 'mi', 'Māori'], ['msa', 'ms', 'Malay'], ['mya', 'my', 'Burmese'], ['nep', 'ne', 'Nepali'], ['nld', 'nl', 'Dutch'],
  ['nor', 'no', 'Norwegian'], ['pan', 'pa', 'Punjabi'], ['pol', 'pl', 'Polish'], ['por', 'pt', 'Portuguese'],
  ['ron', 'ro', 'Romanian'], ['rus', 'ru', 'Russian'], ['sin', 'si', 'Sinhala'], ['slk', 'sk', 'Slovak'], ['slv', 'sl', 'Slovenian'],
  ['spa', 'es', 'Spanish'], ['sqi', 'sq', 'Albanian'], ['srp', 'sr', 'Serbian'], ['swa', 'sw', 'Swahili'], ['swe', 'sv', 'Swedish'],
  ['tam', 'ta', 'Tamil'], ['tel', 'te', 'Telugu'], ['tgl', 'tl', 'Tagalog'], ['tha', 'th', 'Thai'], ['tur', 'tr', 'Turkish'],
  ['ukr', 'uk', 'Ukrainian'], ['urd', 'ur', 'Urdu'], ['uzb', 'uz', 'Uzbek'], ['vie', 'vi', 'Vietnamese'], ['yue', '', 'Cantonese'],
  ['zho', 'zh', 'Chinese'], ['zul', 'zu', 'Zulu'],
]
const languageNames = new Map<string, string>()
const threeLetter = new Map<string, string>()
for (const [t, two, name] of languageTable) {
  languageNames.set(t, name)
  if (two) { languageNames.set(two, name); threeLetter.set(two, t) }
}
threeLetter.set('nb', 'nob').set('nn', 'nno')

/**
 * Java `Locale.isO3Language` for a BCP 47 tag ("en-US" → "eng", "nb" → "nob"): what `AppModel.deviceLanguage` reads from
 * `navigator.language`. null when the table doesn't know the language.
 */
export function isO3Language(tag: string | null | undefined): string | null {
  const two = (tag ?? '').split('-')[0].toLowerCase()
  return threeLetter.get(two) ?? (two.length === 3 ? two : null)
}

// ISO 639-2/B → /T. A Norwegian device says Bokmål/Nynorsk, servers tag "nor".
const bibliographic = new Map<string, string>([
  ['fre', 'fra'], ['ger', 'deu'], ['chi', 'zho'], ['dut', 'nld'], ['gre', 'ell'], ['cze', 'ces'], ['rum', 'ron'], ['may', 'msa'],
  ['baq', 'eus'], ['per', 'fas'], ['ice', 'isl'], ['mac', 'mkd'], ['slo', 'slk'], ['wel', 'cym'], ['arm', 'hye'], ['geo', 'kat'],
  ['bur', 'mya'], ['tib', 'bod'], ['alb', 'sqi'], ['mao', 'mri'], ['nob', 'nor'], ['nno', 'nor'],
])

export const MediaTrack = {
  /** "Spanish" for "spa"; falls back to the code. Handles the bibliographic ISO 639-2/B codes Emby and Plex emit. */
  languageName(code: string | null | undefined): string | null {
    if (!code || code === 'und') return null
    const t = MediaTrack.terminology(code) ?? code
    return languageNames.get(t) ?? code
  },

  /** The ISO 639-2/T code for `code` ("fre" → "fra", "ger" → "deu"; others lowercased as is). null for none / "und". */
  terminology(code: string | null | undefined): string | null {
    const c = code?.toLowerCase()
    if (!c || c === 'und') return null
    return bibliographic.get(c) ?? c
  },

  /** Same language, /B and /T codes alike; an unknown language matches nothing. */
  sameLanguage(a: string | null | undefined, b: string | null | undefined): boolean {
    const t = MediaTrack.terminology(a)
    return t != null && t === MediaTrack.terminology(b)
  },
}
export namespace MediaTrack {
  export type Kind = 'audio' | 'subtitle'
}

/** What the viewer asked for. null / auto = the server's default choice. */
export class TrackSelection {
  constructor(readonly audioID: string | null = null, readonly subtitle: TrackSelection.Subtitle = TrackSelection.Subtitle.auto) {}

  copy(changes: { audioID?: string | null; subtitle?: TrackSelection.Subtitle }): TrackSelection {
    return new TrackSelection(changes.audioID !== undefined ? changes.audioID : this.audioID, changes.subtitle ?? this.subtitle)
  }

  get isDefault(): boolean { return this.audioID == null && this.subtitle.kind === 'auto' }

  /** {"audioID": "2", "subtitle": "auto" | "off" | {"track": "5"}}. */
  toJson(): JSONObject {
    const s = this.subtitle
    const o = putOpt({}, 'audioID', this.audioID)
    o.subtitle = s.kind === 'track' ? { track: s.id } : s.kind
    return o
  }

  static readonly Subtitle = {
    auto: { kind: 'auto' } as TrackSelection.Subtitle,
    off: { kind: 'off' } as TrackSelection.Subtitle,
    track: (id: string): TrackSelection.Subtitle => ({ kind: 'track', id }),
  }

  /**
   * The Settings preferences matched against an item's tracks. `subtitleMode` "off" / "on" / anything else = "Forced
   * only": Plex leaves that to the server (an explicit Plex pick changes the account-wide choice), Emby/Jellyfin get
   * the forced track asked for by index, or none, so the server account's subtitle preference never decides. A forced
   * track counts only in `subtitleLanguage`, else `deviceLanguage`, else the language of the audio that will play
   * (picked, else the item's default); a foreign forced track is never shown. An unmatched audio language, or "on"
   * without subtitle tracks, = server default.
   */
  static preferred(tracks: readonly MediaTrack[], server: ServerKind | null, audioLanguage: string | null,
                   subtitleMode: string, subtitleLanguage: string, deviceLanguage: string | null = null): TrackSelection {
    const audio = tracks.filter(t => t.kind === 'audio')
    const audioID = audio.find(t => MediaTrack.sameLanguage(t.language, audioLanguage))?.id ?? null
    const subs = tracks.filter(t => t.kind === 'subtitle')
    let subtitle: TrackSelection.Subtitle
    if (subtitleMode === 'off') subtitle = TrackSelection.Subtitle.off
    else if (subtitleMode === 'on') {
      // The chosen language if the program has it, else any full subtitle track; forced-only tracks last.
      const pick = subs.find(t => MediaTrack.sameLanguage(t.language, subtitleLanguage) && !t.isForced)
        ?? subs.find(t => !t.isForced) ?? subs[0]
      subtitle = pick ? TrackSelection.Subtitle.track(pick.id) : TrackSelection.Subtitle.auto
    } else if (server !== 'emby' && server !== 'jellyfin') subtitle = TrackSelection.Subtitle.auto
    else {
      const forced = subs.filter(t => t.isForced)
      const playing = audio.find(t => t.id === audioID) ?? audio.find(t => t.isDefault) ?? audio[0]
      let pick: MediaTrack | undefined
      for (const lang of [subtitleLanguage, deviceLanguage, playing?.language]) {
        pick = forced.find(t => MediaTrack.sameLanguage(t.language, lang))
        if (pick) break
      }
      subtitle = pick ? TrackSelection.Subtitle.track(pick.id) : TrackSelection.Subtitle.off
    }
    return new TrackSelection(audioID, subtitle)
  }

  static fromJson(o: JSONObject): TrackSelection {
    const s = o.subtitle
    const sub = s === 'off' ? TrackSelection.Subtitle.off
      : typeof s === 'object' && s !== null && !Array.isArray(s) && Object.prototype.hasOwnProperty.call(s, 'track')
        ? TrackSelection.Subtitle.track(getString(asObject(s), 'track'))
        : TrackSelection.Subtitle.auto
    return new TrackSelection(stringOrNull(o, 'audioID'), sub)
  }
}
export namespace TrackSelection {
  export type Subtitle = { readonly kind: 'auto' } | { readonly kind: 'off' } | { readonly kind: 'track'; readonly id: string }
}

/** What the player should load. */
export interface PlaybackSource {
  readonly url: string
  /**
   * true: the server already started the stream at the requested offset (Plex HLS transcode), so do NOT seek.
   * false: the stream starts at 0 (direct play, Jellyfin/Emby HLS); the player must seek to the offset itself.
   */
  readonly startsAtOffset: boolean
  /**
   * true when a chosen subtitle rides in the HLS playlist as a text rendition the player must select (Jellyfin/Emby);
   * false when it is burned into the picture (Plex) or no subtitle was requested.
   */
  readonly subtitleInPlaylist?: boolean
}

const fnvOffset = BigInt('0xcbf29ce484222325')
const fnvPrime = BigInt('0x100000001b3')
const utf8 = new TextEncoder()

/** Process-independent hashing. Never feed a per-process hash into anything deterministic. */
export const StableHash = {
  /** 64-bit FNV-1a over the UTF-8 bytes, as an unsigned BigInt (compare 64-bit values as BigInt or decimal strings). */
  fnv1a(s: string): bigint {
    let h = fnvOffset
    for (const b of utf8.encode(s)) h = BigInt.asUintN(64, (h ^ BigInt(b)) * fnvPrime)
    return h
  },
}

/**
 * Swift's `String <`: Unicode scalar order of the NFC forms. JS `<` is UTF-16 order and not normalized; every sort that
 * decides the channel lineup or a day's schedule goes through this so all platforms order identically. -1, 0 or 1.
 */
export function swiftCompare(a: string, b: string): number {
  const x = nfc(a)
  const y = nfc(b)
  let i = 0
  let j = 0
  while (i < x.length && j < y.length) {
    const c = x.codePointAt(i)!
    const d = y.codePointAt(j)!
    if (c !== d) return c < d ? -1 : 1
    i += c > 0xffff ? 2 : 1
    j += d > 0xffff ? 2 : 1
  }
  const p = x.length - i
  const q = y.length - j
  return p < q ? -1 : p > q ? 1 : 0
}

/** Everything below U+0300 is already NFC; skip the normalizer for the common case. */
function nfc(s: string): string {
  return /[^\u0000-\u02ff]/.test(s) ? s.normalize('NFC') : s
}
