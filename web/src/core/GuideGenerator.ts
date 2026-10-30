// Port of android/core GuideGenerator.kt: ChannelBuilder, SeededGenerator, `shuffled(using:)` and GuideGenerator.
// Kotlin overloads get the README's names: `scheduleRange`, `channelsFrom` / `channelsOf`, `nextBelow`. The calendar is the
// device's time zone (core/Time.ts), so Kotlin's `calendar: ZoneId` parameters are gone.
import { compareValues, stableSort } from './Lang'
import {
  Channel, ChannelDefinition, MediaItem, PlayOrder, ProgramStatus, RuleChip, Slot, StableHash, swiftCompare,
} from './Models'
import * as Time from './Time'
import { Instant } from './Time'

// ---- ChannelBuilder: turns a flat library into channel definitions and resolves definitions back into items ----

type Names = { readonly movies: readonly [string, string]; readonly tv: readonly [string, string]; readonly mixed: readonly [string, string] }
const n = (mn: string, md: string, tn: string, td: string, xn: string, xd: string): Names =>
  ({ movies: [mn, md], tv: [tn, td], mixed: [xn, xd] })

const retroTable = new Map<string, Names>([
  ['action', n('Adrenaline', 'Action films', 'Stunt Hour', 'Action TV', 'Adrenaline', 'Action')],
  ['action & adventure', n('Thrill Ride', 'Action & adventure films', 'Thrill Ride', 'Action & adventure TV', 'Thrill Ride', 'Action & adventure')],
  ['adventure', n('The Matinee', 'Adventure films', 'Wide World', 'Adventure TV', 'The Matinee', 'Adventure')],
  ['animation', n('The Big Cartoon', 'Animated films', 'Toon Block', 'Cartoons', 'Toon Block', 'Animation')],
  ['anime', n('Anime Features', 'Anime films', 'Anime Block', 'Anime', 'Anime Block', 'Anime')],
  ['biography', n('Life Stories', 'Biopics', 'Portraits', 'Biographies', 'Life Stories', 'Biographies')],
  ['children', n('Kids’ Matinee', 'Kids’ films', 'After School', 'Kids’ TV', 'After School', 'Kids')],
  ['kids', n('Kids’ Matinee', 'Kids’ films', 'After School', 'Kids’ TV', 'After School', 'Kids')],
  ['comedy', n('Funny Business', 'Comedy films', 'Studio Audience', 'Sitcoms', 'Studio Audience', 'Comedy')],
  ['crime', n('Mean Streets', 'Crime films', 'Gumshoe', 'Detectives', 'Gumshoe', 'Crime')],
  ['documentary', n('True Stories', 'Documentaries', 'Field Notes', 'Documentary TV', 'True Stories', 'Documentaries')],
  ['drama', n('The Picture Show', 'Drama films', 'Prime Time', 'Drama series', 'Prime Time', 'Drama')],
  ['family', n('Family Hour', 'Family films', 'Family Room', 'Family TV', 'Family Hour', 'Family')],
  ['fantasy', n('Once Upon', 'Fantasy films', 'The Realm', 'Fantasy TV', 'Once Upon', 'Fantasy')],
  ['food', n('Dinner Bell', 'Food', 'Dinner Bell', 'Food TV', 'Dinner Bell', 'Food')],
  ['game show', n('Game Night', 'Game shows', 'Game Night', 'Game shows', 'Game Night', 'Game shows')],
  ['history', n('Days Gone By', 'History films', 'The Archive', 'History TV', 'Days Gone By', 'History')],
  ['holiday', n('Yule Log', 'Holiday films', 'Tinsel Time', 'Holiday TV', 'Yule Log', 'Holiday')],
  ['home and garden', n('Open House', 'Home & garden', 'Open House', 'Home & garden', 'Open House', 'Home & garden')],
  ['horror', n('Fright Night', 'Horror films', 'Chiller', 'Horror TV', 'Fright Night', 'Horror')],
  ['martial arts', n('Kung Fu Theater', 'Martial arts', 'Kung Fu Theater', 'Martial arts', 'Kung Fu Theater', 'Martial arts')],
  ['mini-series', n('The Miniseries', 'Mini-series', 'The Miniseries', 'Mini-series', 'The Miniseries', 'Mini-series')],
  ['music', n('The Jukebox', 'Music films', 'Video Hour', 'Music TV', 'The Jukebox', 'Music')],
  ['musical', n('Showtunes', 'Musicals', 'Showtunes', 'Musicals', 'Showtunes', 'Musicals')],
  ['mystery', n('Whodunit', 'Mystery films', 'Case Files', 'Mystery TV', 'Whodunit', 'Mysteries')],
  ['news', n('Newsroom', 'News', 'Newsroom', 'News', 'Newsroom', 'News')],
  ['reality', n('Real Life', 'Reality TV', 'Real Life', 'Reality TV', 'Real Life', 'Reality TV')],
  ['romance', n('Date Night', 'Romance films', 'Love Lines', 'Romance TV', 'Date Night', 'Romance')],
  ['sci-fi', n('Final Frontier', 'Sci-fi films', 'Space Age', 'Sci-fi TV', 'Space Age', 'Sci-fi')],
  ['science fiction', n('Final Frontier', 'Sci-fi films', 'Space Age', 'Sci-fi TV', 'Space Age', 'Sci-fi')],
  ['soap', n('Daytime', 'Soaps', 'Daytime', 'Soaps', 'Daytime', 'Soaps')],
  ['sport', n('Game Day', 'Sports films', 'Sports Desk', 'Sports TV', 'Game Day', 'Sports')],
  ['sports', n('Game Day', 'Sports films', 'Sports Desk', 'Sports TV', 'Game Day', 'Sports')],
  ['superhero', n('Capes & Cowls', 'Superhero films', 'Secret Identity', 'Superhero TV', 'Capes & Cowls', 'Superheroes')],
  ['suspense', n('Nail Biter', 'Suspense films', 'Held Breath', 'Suspense TV', 'Nail Biter', 'Suspense')],
  ['talk show', n('Late Night', 'Talk shows', 'Late Night', 'Talk shows', 'Late Night', 'Talk shows')],
  ['thriller', n('Edge of Your Seat', 'Thrillers', 'Cliffhanger', 'Thriller TV', 'Edge of Your Seat', 'Thrillers')],
  ['travel', n('Passport', 'Travel films', 'Road Trip', 'Travel TV', 'Passport', 'Travel')],
  ['war', n('The Front', 'War films', 'War Room', 'War TV', 'The Front', 'War')],
  ['western', n('Dusty Trails', 'Westerns', 'The Frontier', 'Western TV', 'Dusty Trails', 'Westerns')],
])

const min = (a: readonly number[]): number => a.reduce((x, y) => Math.min(x, y))
const max = (a: readonly number[]): number => a.reduce((x, y) => Math.max(x, y))
const yearsOf = (items: readonly MediaItem[]): number[] => {
  const out: number[] = []
  for (const i of items) if (i.year != null) out.push(i.year)
  return out
}
const zeroPad = (v: number, width: number): string => String(v).padStart(width, '0')

/**
 * The automatic channel lineup. Up front, when the library has enough for them: New Releases (films from this year and last),
 * New This Season (TV from this year and last), Just Added (anything added in the last 60 days), Unwatched (films the
 * signed-in user hasn't seen), then the classic channels (TV and films from before 1970). After that every genre, theme,
 * marathon, director, actor and year channel, scattered with a fixed seed so the lineup feels like cable, not an
 * alphabetical list, yet is identical on every device.
 * Genre channels get retro names ("Studio Audience", "Gumshoe", "Toon Block"); unknown genres keep their own name.
 * No channel mixes films and TV: a genre gets a films channel with ≥ `minItems` films and a TV channel with
 * ≥ `minItems` episodes, and director, actor and year channels are films. Only the biggest groups make the lineup
 * (`maxSeries` marathons, `maxPeople` directors and actors, 3 years).
 */
function automaticChannels(from: readonly MediaItem[], minItems = 4, maxSeries = 24, maxPeople = 12,
                           now: Instant = Time.now()): ChannelDefinition[] {
  const library = from
  const def = (id: string, name: string, tag: string, rule: readonly RuleChip[], order: PlayOrder = 'shuffle') =>
    new ChannelDefinition({ id, number: 0, name, tag, rule, order })
  const span = (items: readonly MediaItem[]): RuleChip[] => {
    const years = yearsOf(items)
    return years.length === 0 ? [] : [RuleChip.years(Math.trunc(min(years) / 10) * 10, max(years))]
  }
  const count = (rule: readonly RuleChip[]): number => library.filter(i => rule.every(c => matches(c, i, now))).length

  // Lead channels.
  const year = Time.localYear(now)
  const lead: ChannelDefinition[] = []
  const leads: [string, string, string, RuleChip[], PlayOrder][] = [
    ['lead:new-movies', 'New Releases', `Films from ${year - 1}–${year}`, [RuleChip.years(year - 1, year), RuleChip.movies], 'newestFirst'],
    ['lead:new-tv', 'New This Season', `TV from ${year - 1}–${year}`, [RuleChip.years(year - 1, year), RuleChip.tv], 'newestFirst'],
    ['lead:just-added', 'Just Added', 'Films new on the server', [RuleChip.addedWithin(60), RuleChip.movies], 'shuffle'],
    ['lead:unwatched', 'Unwatched', 'Films you haven’t seen', [RuleChip.unwatched, RuleChip.movies], 'shuffle'],
    ['lead:just-added-tv', 'Fresh Episodes', 'TV new on the server', [RuleChip.addedWithin(60), RuleChip.tv], 'shuffle'],
    ['lead:classic-tv', 'Front Porch', 'Classic TV, before 1970', [RuleChip.years(1900, 1969), RuleChip.tv], 'shuffle'],
    ['lead:classic-movies', 'The Vault', 'Classic films, before 1970', [RuleChip.years(1900, 1969), RuleChip.movies], 'shuffle'],
  ]
  for (const [id, name, tag, rule, order] of leads) if (count(rule) >= minItems) lead.push(def(id, name, tag, rule, order))

  // Map, never {}: genre/person names like "constructor" and numeric Plex ids would break a plain object.
  const byGenre = new Map<string, MediaItem[]>()
  const bySeries = new Map<string, [string, MediaItem[]]>()
  const byDirector = new Map<string, number>()
  const byActor = new Map<string, number>()
  const byYear = new Map<number, number>()
  for (const item of library) {
    for (const g of item.genres) {
      const list = byGenre.get(g)
      if (list) list.push(item)
      else byGenre.set(g, [item])
    }
    if (item.kind === 'episode') {
      const key = item.seriesID ?? item.seriesTitle
      if (key == null) continue
      const entry = bySeries.get(key)
      if (entry) entry[1].push(item)
      else bySeries.set(key, [item.seriesTitle ?? key, [item]])
    } else {
      for (const d of item.directors) byDirector.set(d, (byDirector.get(d) ?? 0) + 1)
      for (const a of item.actors) byActor.set(a, (byActor.get(a) ?? 0) + 1)
      if (item.year != null) byYear.set(item.year, (byYear.get(item.year) ?? 0) + 1)
    }
  }

  let genres: ChannelDefinition[] = []
  byGenre.forEach((all, g) => {
    if (all.length < minItems) return
    const items = all.filter(it => matches(RuleChip.genre(g), it, now))   // what the channel will really air (kids' titles leave horror)
    const movies = items.filter(it => it.kind === 'movie')
    const eps = items.filter(it => it.kind === 'episode')
    if (movies.length >= minItems) {
      const [mn, md] = retroName(g, 'movies')
      genres.push(def(`genre:${g}:movies`, mn, decadeTag(md, yearsOf(movies)), [RuleChip.genre(g), ...span(movies), RuleChip.movies]))
    }
    if (eps.length >= minItems) {
      const [tn, td] = retroName(g, 'tv')
      genres.push(def(`genre:${g}:tv`, tn, decadeTag(td, yearsOf(eps)), [RuleChip.genre(g), ...span(eps), RuleChip.tv]))
    }
  })
  // Two channels with the same retro name (two genres mapping to one, or a genre missing from the table) get told apart by what's on them.
  const names = new Map<string, number>()
  for (const gd of genres) names.set(gd.name, (names.get(gd.name) ?? 0) + 1)
  genres = genres.map(gd => {
    if ((names.get(gd.name) ?? 0) < 2) return gd
    const kind = gd.rule.some(c => c.kind === 'tv') ? 'TV' : gd.rule.some(c => c.kind === 'movies') ? 'Films'
      : gd.id.split(':').filter(s => s !== '').slice(1)[0] ?? ''
    return gd.copy({ name: gd.name + ` (${kind})` })
  })
  // Themed channels: a keyword search within a few related genres, for what no genre tag covers.
  const themes = [
    def('theme:monsters', 'Creature Feature', 'Monster movies',
      [RuleChip.movies, RuleChip.anyGenre(monsterGenres), RuleChip.keywords(monsterWords)]),
  ].filter(t => count(t.rule) >= minItems)
  const seriesEntries: [string, [string, MediaItem[]]][] = []
  bySeries.forEach((v, k) => { if (v[1].length >= minItems) seriesEntries.push([k, v]) })
  const series = stableSort(seriesEntries, (a, b) => a[1][1].length !== b[1][1].length ? b[1][1].length - a[1][1].length : swiftCompare(a[0], b[0]))
    .slice(0, maxSeries)
    .map(([key, [title]]) => def(`series:${key}`, title, 'Marathon', [RuleChip.series(key, title)], 'inOrder'))
  const top = (counts: Map<string, number>): string[] => {
    const entries: [string, number][] = []
    counts.forEach((c, k) => { if (c >= minItems) entries.push([k, c]) })
    return stableSort(entries, (a, b) => a[1] !== b[1] ? b[1] - a[1] : swiftCompare(a[0], b[0])).slice(0, maxPeople).map(e => e[0])
  }
  const directors = top(byDirector).map(it => def(`director:${it}`, it, 'Director', [RuleChip.director(it), RuleChip.movies]))
  const actors = top(byActor).map(it => def(`actor:${it}`, it, 'Actor', [RuleChip.actor(it), RuleChip.movies]))
  const yearEntries: [number, number][] = []
  byYear.forEach((c, y) => { if (c >= minItems) yearEntries.push([y, c]) })
  const years = stableSort(yearEntries, (a, b) => a[1] !== b[1] ? b[1] - a[1] : compareValues(a[0], b[0])).slice(0, 3)
    .map(([y]) => def(`year:${y}`, 'Class of ’' + zeroPad(y % 100, 2), `Films from ${y}`, [RuleChip.year(y), RuleChip.movies]))

  // Scatter the rest with a fixed seed: same channels → same order on every device and launch.
  const rng = new SeededGenerator('retro-binge-dial')
  const rest = shuffled(stableSort([...genres, ...themes, ...series, ...directors, ...actors, ...years], (a, b) => swiftCompare(a.id, b.id)), rng)
  const all = [...lead, ...rest]
  const three = BigInt(3)
  let num = 0
  for (let i = 0; i < all.length; i++) {
    num = i === 0 ? 2 : num + 1 + Number(StableHash.fnv1a(all[i].id) % three)
    if (num === Channel.scrambledNumber) num += 1   // 99 belongs to the scrambled channel
    all[i] = all[i].copy({ number: num })
  }
  return all
}

/**
 * Retro channel names for the common genres ("Studio Audience", "Gumshoe"). Returns (name, descriptor);
 * the descriptor becomes the tag with the decade span appended. Unknown genres fall back to the genre itself.
 */
function retroName(genre: string, kind: ChannelBuilder.ShelfKind): readonly [string, string] {
  const e = retroTable.get(genre.toLowerCase())
  if (e != null) return e[kind]
  switch (kind) {
    case 'movies': return [`${genre} Movies`, `${genre} films`]
    case 'tv': return [`${genre} TV`, `${genre} TV`]
    case 'mixed': return [genre, genre]
  }
}

/**
 * Items matching every chip of the rule, in the sequence the channel's `order` wants
 * (`inOrder`: season/episode then year; `newestFirst`: year descending; `shuffle`: library order, shuffled per day by the scheduler).
 */
function resolve(definition: ChannelDefinition, library: readonly MediaItem[], now: Instant = Time.now()): MediaItem[] {
  const items = library.filter(item => definition.rule.every(c => matches(c, item, now)))
  switch (definition.order) {
    case 'shuffle': return items
    case 'inOrder': return stableSort(items, (a, b) =>
      compareValues(a.season ?? 0, b.season ?? 0) || compareValues(a.episode ?? 0, b.episode ?? 0) ||
      compareValues(a.year ?? 0, b.year ?? 0) || swiftCompare(a.title, b.title))
    case 'newestFirst': return stableSort(items, (a, b) => compareValues(b.year ?? 0, a.year ?? 0) || swiftCompare(b.title, a.title))
  }
}

function matches(chip: RuleChip, item: MediaItem, now: Instant = Time.now()): boolean {
  switch (chip.kind) {
    case 'addedWithin': return item.addedAt != null && item.addedAt >= now - chip.days * 86_400
    case 'genre': return item.genres.indexOf(chip.value) >= 0 && !(grownUpGenres.indexOf(chip.value.toLowerCase()) >= 0 && isForKids(item))
    case 'years': return item.year != null && item.year >= chip.from && item.year <= chip.to
    case 'director': return item.directors.indexOf(chip.name) >= 0
    case 'actor': return item.actors.indexOf(chip.name) >= 0
    case 'series': return item.seriesID === chip.id || (item.seriesID == null && item.seriesTitle === chip.title)
    case 'year': return item.year === chip.value
    case 'movies': return item.kind === 'movie'
    case 'tv': return item.kind === 'episode'
    case 'anyGenre': return item.genres.some(g => chip.genres.indexOf(g) >= 0)
    case 'keywords': return mentions(item, chip.words)
    case 'unwatched': return !item.watched
  }
}

const letter = /\p{L}/u

/** Genres whose channels stay grown-up: a kids' title tagged with one (the animated Gremlins series is "Horror") is left off. */
const grownUpGenres: readonly string[] = ['horror', 'thriller']

/**
 * Made for children: tagged Children, Kids or Family, rated for kids, or animated without a rating for older viewers
 * (an adult animated horror like Love, Death & Robots is TV-MA and stays).
 * ponytail: US ratings only; another country's rating on an animated title counts as not older.
 */
function isForKids(item: MediaItem): boolean {
  const genres = item.genres.map(g => g.toLowerCase())
  const rating = item.contentRating?.toUpperCase() ?? null
  if (genres.some(g => g === 'children' || g === 'kids' || g === 'family')) return true
  if (rating != null && ['TV-Y', 'TV-Y7', 'TV-Y7-FV', 'TV-G', 'G'].indexOf(rating) >= 0) return true
  return genres.indexOf('animation') >= 0 && !(rating != null && ['TV-14', 'TV-MA', 'PG-13', 'R', 'NC-17'].indexOf(rating) >= 0)
}

/** Word-start, case-insensitive search of the title and overview: "creature" takes "creatures", not "procreature". */
function mentions(item: MediaItem, any: readonly string[]): boolean {
  const text = (item.title + ' ' + (item.overview ?? '')).toLowerCase()
  return any.some(w => {
    if (w === '') return false   // Foundation finds no range for an empty needle (JS indexOf('') is 0: every item would match)
    let from = 0
    for (;;) {
      const r = text.indexOf(w, from)
      if (r < 0) break
      if (r === 0 || !letter.test(String.fromCodePoint(codePointBefore(text, r)))) return true
      from = r + w.length
    }
    return false
  })
}

/** Java `String.codePointBefore`: the code point ending at `i`, stepping back over a surrogate pair. */
function codePointBefore(s: string, i: number): number {
  const pair = i >= 2 ? s.codePointAt(i - 2)! : 0
  return pair > 0xffff ? pair : s.charCodeAt(i - 1)
}

/**
 * The keywords for "Creature Feature": lowercase, word-start prefixes ("werewol" takes werewolf and werewolves).
 * "shark" alone matches loan sharks and shark-infested waters; "great white" is the one that eats people.
 */
const monsterWords: readonly string[] = ['monster', 'kaiju', 'godzilla', 'king kong', 'kong:', 'creature', 'werewol', 'vampire', 'zombie',
  'frankenstein', 'dracula', 'mummy', 'dinosaur', 'great white', 'megalodon', 'gremlin', 'xenomorph',
  'kraken', 'leviathan', 'cryptid', 'sasquatch', 'bigfoot', 'swamp thing']
/** The genres searched for those keywords: Fantasy would add Terabithia and Spirited Away; Action/Adventure adds Jurassic Park and Kong. */
const monsterGenres: readonly string[] = ['Horror', 'Science Fiction', 'Sci-Fi', 'Action', 'Adventure']

const freshIDs = ['lead:new-movies', 'lead:new-tv', 'lead:just-added', 'lead:just-added-tv']

/**
 * The "what's new" channels: New Releases, New This Season, Just Added, and any channel with an "Added in the last N
 * days" chip. They also see the items `MediaServer.recentlyAdded` found since the last full library load.
 */
function isFresh(d: ChannelDefinition): boolean {
  return freshIDs.indexOf(d.id) >= 0 || d.rule.some(c => c.kind === 'addedWithin')
}

/**
 * `channelsFrom(definitions, library, now)`, with `fresh` (newly added items not yet in `library`) given to the `isFresh`
 * channels only: every other channel keeps resolving from `library`, so its lineup for the day holds still until the next
 * full load.
 */
function channels(definitions: readonly ChannelDefinition[], library: readonly MediaItem[], fresh: readonly MediaItem[],
                  now: Instant = Time.now()): Channel[] {
  if (fresh.length === 0) return channelsFrom(definitions, library, now)
  return channelsFrom(definitions.filter(d => !isFresh(d)), library, now)
    .concat(channelsFrom(definitions.filter(isFresh), library.concat(fresh), now))
}

/** Kotlin `channels(definitions, library, now)`: resolve definitions against the library, dropping channels with nothing to air. */
function channelsFrom(definitions: readonly ChannelDefinition[], library: readonly MediaItem[], now: Instant = Time.now()): Channel[] {
  const out: Channel[] = []
  for (const d of definitions) {
    const items = resolve(d, library, now)
    if (items.length > 0) out.push(new Channel(d, items))
  }
  return out
}

/** Kotlin `channels(from, minItems)`. Convenience: automatic channels resolved. Used by tests. */
function channelsOf(from: readonly MediaItem[], minItems = 4): Channel[] {
  return channelsFrom(automaticChannels(from, minItems), from)
}

/** Names with enough in the library to fill an evening, for the "New channel" picker. Sorted by count desc, then name. */
function candidates(kind: ChannelBuilder.CandidateKind, library: readonly MediaItem[], minItems = 3): ChannelBuilder.Candidate[] {
  const counts = new Map<string, [string | null, number]>()
  const bump = (key: string, id: string | null) => {
    const c = counts.get(key)
    counts.set(key, [c?.[0] ?? id, (c?.[1] ?? 0) + 1])
  }
  for (const item of library) {
    switch (kind) {
      case 'director': if (item.kind === 'movie') for (const d of item.directors) bump(d, null); break
      case 'actor': if (item.kind === 'movie') for (const a of item.actors) bump(a, null); break
      case 'series': { const t = item.seriesTitle; if (item.kind === 'episode' && t != null) bump(t, item.seriesID) }
    }
  }
  const out: ChannelBuilder.Candidate[] = []
  counts.forEach(([id, count], name) => { if (count >= minItems) out.push({ name, id, count }) })
  return stableSort(out, (a, b) => a.count !== b.count ? b.count - a.count : swiftCompare(a.name, b.name))
}

function decadeTag(genre: string, years: readonly number[]): string {
  if (years.length === 0) return genre
  const lo = min(years)
  const hi = max(years)
  // "80s" for the 1900s, "2010s" from 2000 on, so "50s–2020s" can't be read as the 1920s.
  const d = (y: number) => y < 2000 ? `${Math.trunc(y % 100 / 10) * 10}s` : `${Math.trunc(y / 10) * 10}s`
  return `${genre}, ` + (Math.trunc(lo / 10) === Math.trunc(hi / 10) ? d(lo) : `${d(lo)}–${d(hi)}`)
}

export const ChannelBuilder = {
  automaticChannels, retroName, resolve, matches, mentions, monsterWords, monsterGenres, isFresh,
  channels, channelsFrom, channelsOf, candidates, decadeTag,
  CandidateKind: {
    entries: ['director', 'actor', 'series'] as readonly ChannelBuilder.CandidateKind[],
    rawValue(k: ChannelBuilder.CandidateKind): string {
      return k === 'director' ? 'Director' : k === 'actor' ? 'Actor' : 'Show marathon'
    },
  },
}
export namespace ChannelBuilder {
  export type ShelfKind = 'movies' | 'tv' | 'mixed'
  export type CandidateKind = 'director' | 'actor' | 'series'
  export interface Candidate {
    readonly name: string
    readonly id: string | null
    readonly count: number
  }
}

// ---- SeededGenerator: FNV-1a 64 of a string seeding SplitMix64. Stable across processes and devices ----
// 64-bit wrapping maths (Swift &+ / &*, Kotlin ULong) in BigInt, cut back to 64 bits after every add and multiply.

const u64 = (x: bigint): bigint => BigInt.asUintN(64, x)
const zero = BigInt(0)
const golden = BigInt('0x9e3779b97f4a7c15')
const mix1 = BigInt('0xbf58476d1ce4e5b9')
const mix2 = BigInt('0x94d049bb133111eb')
const s27 = BigInt(27)
const s30 = BigInt(30)
const s31 = BigInt(31)
const s64 = BigInt(64)

export class SeededGenerator {
  private state: bigint

  constructor(seed: string) {
    this.state = StableHash.fnv1a(seed)
  }

  next(): bigint {
    this.state = u64(this.state + golden)
    let z = this.state
    z = u64((z ^ (z >> s30)) * mix1)
    z = u64((z ^ (z >> s27)) * mix2)
    return z ^ (z >> s31)
  }

  /** Kotlin `next(upperBound)`, Swift's `RandomNumberGenerator.next(upperBound:)`: Lemire's nearly divisionless method, same draws, same result. */
  nextBelow(upperBound: bigint): bigint {
    if (upperBound === zero) throw new Error('upperBound cannot be zero.')
    let random = this.next()
    let low = u64(random * upperBound)
    if (low < upperBound) {
      const t = u64(zero - upperBound) % upperBound
      while (low < t) {
        random = this.next()
        low = u64(random * upperBound)
      }
    }
    return (random * upperBound) >> s64   // Kotlin's multiplyHighUnsigned: BigInt holds the whole 128-bit product
  }

  /** Swift's `Int.random(in: 0..<until, using:)`. */
  nextInt(until: number): number {
    if (!(until > 0)) throw new Error('Can\'t get random value with an empty range')
    return Number(this.nextBelow(BigInt(until)))
  }
}

/** Swift's `shuffled(using:)`: Fisher–Yates front to back, each swap partner from `Int.random(in: 0..<remaining)`. */
export function shuffled<T>(list: readonly T[], using: SeededGenerator): T[] {
  const result = list.slice()
  let amount = result.length
  let i = 0
  while (amount > 1) {
    const r = using.nextInt(amount)
    amount -= 1
    const t = result[i]
    result[i] = result[i + r]
    result[i + r] = t
    i += 1
  }
  return result
}

// ---- GuideGenerator: deterministic scheduler. Same (channel, day) → identical lineup on every device, every launch ----

/** Guide grid resolution. Slots are (runtime + `pad`) rounded UP to a multiple of this. */
const blockLength = 30 * 60
/** Breathing room so a 28-minute episode isn't jammed into one block: ceil((runtime + 3 min) / 30 min) blocks. */
const pad = 3 * 60

/**
 * Lineup tiling `day` from local midnight to the next local midnight.
 * Pool order follows `channel.order`: shuffle = seeded shuffle (seed = channel.id + yyyy-MM-dd); inOrder/newestFirst =
 * the channel's item order rotated by a seeded offset so consecutive days don't all start at episode 1.
 * Repeat the pool if it runs short. If the next item doesn't fit before midnight, take the next one that does.
 * If none fits, no program starts that can't finish: the day's last slot runs on to midnight instead, re-picked to
 * the longest item that fits that window, and the time left over becomes its break. Only an item longer than the
 * whole day is still cut at midnight. A short program shares its half hour with the next ones that fit (`tile`).
 * Nothing airs twice in a row (`repeatKey`) unless the channel has nothing else; across midnight, unless nothing else
 * fits the last slot (or the channel plays in order).
 */
function schedule(channel: Channel, day: Instant): Slot[] {
  if (channel.items.length === 0) return []
  const start = Time.startOfDay(day)
  // Tile by time: a DST day is 23 or 25 hours, never assume 48 blocks.
  // (Start of the next day, not 24h on: where a DST change skips midnight, a day starts at 1:00.)
  const next = Time.addDays(start, 1)
  // A day always opens with its pool's first item, so tonight's last slot can steer clear of tomorrow's opener
  // without looking at any other day's lineup. Not on a show played in order: skipping an episode at 11:30 PM
  // would break the run, and tomorrow starts somewhere else in it anyway.
  const opens = channel.order === 'inOrder' ? null : repeatKey(pool(channel, next)[0])
  return tile(pool(channel, start), start, next, opens)
}

/**
 * What mustn't air twice in a row: the item, or for a film any copy of it (same title and year; a library often
 * holds a 4K copy as a second item). A cut is part of the title, so two cuts are two films. Episodes go by item, so a
 * show's next episode, or the next segment sharing its episode number, still follows.
 */
function repeatKey(item: MediaItem): string {
  return item.kind === 'movie' ? `film:${item.title}|${item.year ?? 0}` : `item:${item.id}`
}

/** The day's play order, seeded by channel and date. `day` is a local midnight (`Time.startOfDay`). */
function pool(channel: Channel, day: Instant): MediaItem[] {
  const { y, m, d } = Time.localYMD(day)
  const rng = new SeededGenerator(channel.id + `|${zeroPad(y, 4)}-${zeroPad(m, 2)}-${zeroPad(d, 2)}`)
  switch (channel.order) {
    case 'shuffle': return shuffled(channel.items, rng)
    case 'inOrder':
    case 'newestFirst': {
      const r = Number(rng.next() % BigInt(channel.items.length))   // plain modulo, not Lemire: as Swift and Kotlin
      return channel.items.slice(r).concat(channel.items.slice(0, r))
    }
  }
}

/**
 * Tiles [start, end) from `pool` as `schedule` describes, opening with `pool[0]`. `opens`: the `repeatKey` of what
 * airs right after `end`, which the last slot avoids when something else fits.
 */
function tile(pool: readonly MediaItem[], start: Instant, end: Instant, opens: string | null): Slot[] {
  const slots: Slot[] = []
  let cursor = start
  let next = 0
  while (cursor < end) {
    const previous = slots.length > 0 ? repeatKey(slots[slots.length - 1].item) : null
    let pick: number | null = null
    let again: number | null = null
    for (let k = 0; k < pool.length; k++) {
      const i = (next + k) % pool.length
      const slotEnd = cursor + slotLength(pool[i])
      if (slotEnd > end) continue
      const key = repeatKey(pool[i])
      if (slots.length === 0 || key !== previous && (slotEnd < end || key !== opens)) { pick = i; break }
      if (again == null) again = i
    }
    // Only the same thing again fits: fine on a channel with nothing else that could air today (one film, or
    // copies of it). Otherwise it's the end of the day, below.
    const day = end - start
    if (pick == null && again != null && !pool.some(it => repeatKey(it) !== previous && slotLength(it) <= day)) pick = again
    if (pick == null) {
      let last = slots.pop() ?? null
      // A half hour of short programs goes as a whole, so the re-picked slot starts on the half-hour grid.
      while (last != null && (last.start - start) % blockLength !== 0) {
        const before = slots.pop()
        if (before == null) break
        last = before
      }
      if (last != null) {
        const window = end - last.start
        let best = last.item   // fits by construction; a longer one that also fits leaves a shorter break
        const before = slots.length > 0 ? repeatKey(slots[slots.length - 1].item) : null
        if (before != null) {   // (the day's opener itself never changes)
          // The longest that fits and repeats neither the slot before nor tomorrow's opener.
          const ok = (it: MediaItem) => repeatKey(it) !== before && repeatKey(it) !== opens
          let found: MediaItem | null = ok(last.item) ? last.item : null
          for (let k = 0; k < pool.length; k++) {
            const it = pool[(next + k) % pool.length]
            if (slotLength(it) <= window && slotLength(it) > (found != null ? slotLength(found) : 0) && ok(it)) found = it
          }
          best = found ?? last.item
        }
        slots.push(new Slot(best, last.start, end))
      } else {
        slots.push(new Slot(pool[next], cursor, end))   // longer than the whole day
      }
      break
    }
    const slotEnd = cursor + slotLength(pool[pick])
    // A short program (15 minutes or less: half its half hour, the pad not counted) doesn't leave the rest of it empty: the next ones in order that
    // fit play right after it, each in its own slot, the way cartoon blocks did. Nothing crosses the half hour.
    const parts = [pool[pick]]
    let used = partLength(pool[pick])
    next = (pick + 1) % pool.length
    if (pool[pick].runtime <= (slotEnd - cursor) / 2) {
      while (pool.length > 1) {
        const it = pool[next]
        if (used + partLength(it) > slotEnd - cursor || parts.some(p => p.id === it.id) ||
          repeatKey(it) === repeatKey(parts[parts.length - 1]) || !(slotEnd < end || repeatKey(it) !== opens)) break
        parts.push(it); used += partLength(it); next = (next + 1) % pool.length
      }
    }
    for (let n = 0; n < parts.length; n++) {   // the last one keeps whatever is left of the half hour as its break
      const partEnd = n === parts.length - 1 ? slotEnd : cursor + partLength(parts[n])
      slots.push(new Slot(parts[n], cursor, partEnd))
      cursor = partEnd
    }
  }
  return slots
}

/** A packed program's slot: its runtime in whole seconds, so the next one starts as it ends (no break in between). */
function partLength(item: MediaItem): number {
  return Math.max(1, Math.floor(item.runtime))
}

/** Kotlin `schedule(channel, from, to)`: slots on `channel` intersecting [from, to]. Spans days as needed. */
function scheduleRange(channel: Channel, from: Instant, to: Instant): Slot[] {
  const slots: Slot[] = []
  for (let day = Time.startOfDay(from); day < to; day = Time.addDays(day, 1)) slots.push(...schedule(channel, day))
  return slots.filter(it => it.end > from && it.start < to)
}

/** The slot containing `at` on `channel`. */
function slot(on: Channel, at: Instant): Slot | null {
  return schedule(on, at).find(it => it.start <= at && at < it.end) ?? null
}

/**
 * Swift `slot(on:startingAt:)`. The slot by its exact start (what `ProgramKey` stores), if the generator still produces it.
 * Sub-second tolerance so a start that went through JSON (persisted key) still matches.
 */
function slotStartingAt(on: Channel, startingAt: Instant): Slot | null {
  return schedule(on, startingAt).find(it => Math.abs(it.start - startingAt) < 1) ?? null
}

/** later: before start · live: item playing · break: item over, slot not · past: slot over. */
function status(of: Slot, at: Instant): ProgramStatus {
  if (at < of.start) return 'later'
  if (at >= of.end) return 'past'
  return at >= of.itemEnd ? 'break' : 'live'
}

function slotLength(item: MediaItem): number {
  // Channel 99's programs fill their slots exactly: no pad, so the scramble never drops into a station break.
  const padded = item.id.startsWith(Channel.scrambledID) ? item.runtime : item.runtime + pad
  return Math.max(1, Math.ceil(padded / blockLength)) * blockLength
}

export const GuideGenerator = {
  blockLength, pad,
  schedule, repeatKey, pool, tile, partLength, scheduleRange, slot, slotStartingAt, status, slotLength,
}
