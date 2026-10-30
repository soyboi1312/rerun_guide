// Port of android/core TitleSearch.kt: Options › Search. Films by title and shows by series title (one entry per show;
// episode titles aren't searched), and when each airs next. Matching ignores case, accents and punctuation, and a leading
// "The", "A" or "An" needn't be typed. The calendar is the device's time zone (core/Time.ts), so `airsText` has no
// `calendar` parameter.
import { stableSort } from './Lang'
import { Channel, MediaItem, Slot, swiftCompare } from './Models'
import * as Time from './Time'
import { Instant } from './Time'

/** Every film and show keyed for matching, sorted the way equal matches are listed, and the entry each item airs as. */
function index(library: readonly MediaItem[]): TitleSearch.Index {
  const entries: TitleSearch.Entry[] = []
  const at = new Map<string, number>()
  const entryOf = new Map<string, string>()
  for (const item of library) {
    const show = item.kind === 'episode'
    const title = show ? item.seriesTitle : item.title
    if (title == null) continue
    let key: Keys | null = null
    let id = 'show:' + title
    if (!show) { key = keys(title); id = `movie:${key.full}|${item.year ?? 0}` }
    entryOf.set(item.id, id)
    const i = at.get(id)
    if (i != null) {
      const e = entries[i], y = item.year
      if (show && y != null && (e.year == null || y < e.year)) entries[i] = { ...e, year: y }
      continue
    }
    const k = key ?? keys(title)
    at.set(id, entries.length)
    entries.push({ id, title, year: item.year, isShow: show, item, full: k.full, bare: k.bare })
  }
  return { entries: stableSort(entries, byKeys), entryOf }
}

/** By `bare`, then year (none as 0), films before shows, then `full` and the raw title: one order on every platform. */
function byKeys(a: TitleSearch.Entry, b: TitleSearch.Entry): number {
  return swiftCompare(a.bare, b.bare) || (a.year ?? 0) - (b.year ?? 0) || Number(a.isShow) - Number(b.isShow)
    || swiftCompare(a.full, b.full) || swiftCompare(a.title, b.title)
}

/** Up to `limit` entries: exact titles first, then titles that start with the query, then any other match; ties by
 *  title (its `bare` key), then year. A query without letters or digits finds nothing. */
function search(query: string, index: TitleSearch.Index, limit = TitleSearch.limit): TitleSearch.Entry[] {
  const q = keys(query).full
  if (q === '') return []
  const exact: TitleSearch.Entry[] = [], prefix: TitleSearch.Entry[] = [], other: TitleSearch.Entry[] = []
  for (const e of index.entries) {   // already in tie order, so each list stays sorted
    if (e.full === q || e.bare === q) exact.push(e)
    else if (e.full.startsWith(q) || e.bare.startsWith(q)) { if (prefix.length < limit) prefix.push(e) }
    else if (other.length < limit && e.full.indexOf(q) >= 0) other.push(e)
  }
  return exact.concat(prefix, other).slice(0, limit)
}

interface Keys { readonly full: string; readonly bare: string }

/** "The Schindler’s List" → ("theschindlerslist", "schindlerslist"): decomposed, lowercased, combining marks dropped,
 *  split into words at anything but a letter or a digit, joined without spaces; `bare` drops a leading article when more
 *  words follow. An ASCII title (most are) skips the normalizer and the Unicode classes: a 2020 TV keys 20,000 of them.
 *  ς is read as σ: toLowerCase() turns a word-final Σ into ς, where Swift and Kotlin give σ. */
function keys(s: string): Keys {
  const parts = /[^\u0000-\u007f]/.test(s)
    ? s.normalize('NFD').toLowerCase().replace(/\u03c2/g, '\u03c3').replace(/\p{Mn}/gu, '').split(/[^\p{L}\p{N}]+/u)
    : s.toLowerCase().split(/[^a-z0-9]+/)
  const words = parts.filter(w => w !== '')
  const full = words.join('')
  const article = words[0] === 'the' || words[0] === 'a' || words[0] === 'an'
  return { full, bare: words.length > 1 && article ? words.slice(1).join('') : full }
}

/** The earliest airing of each entry across `lineups` (each channel with its slots) from `now` to `window` on, but not
 *  past tomorrow. A program counts while it plays and when it starts inside the window, never in its break (it's over by
 *  then). The earliest start wins, ties to the lower channel number. */
function nextAirings(lineups: readonly { readonly channel: Channel; readonly slots: readonly Slot[] }[], index: TitleSearch.Index,
                     now: Instant, window = TitleSearch.window): Map<string, TitleSearch.Airing> {
  // No further than tomorrow, so "Tomorrow" stays true: a spring-forward day is 23 hours.
  const end = Math.min(now + window, Time.addDays(Time.startOfDay(now), 2))
  const best = new Map<string, TitleSearch.Airing>()
  for (const { channel: ch, slots } of lineups) {
    for (const s of slots) {
      if (s.start >= end || s.itemEnd <= now) continue
      const id = index.entryOf.get(s.item.id)
      if (id == null) continue
      const b = best.get(id)
      if (b != null && (b.start < s.start || (b.start === s.start && b.number <= ch.number))) continue
      best.set(id, { channelID: ch.id, number: ch.number, name: ch.name, start: s.start })
    }
  }
  return best
}

/** "On now · Ch 5 Fright Night, 40 min in", "Today 3:00 PM · Ch 5 Fright Night", "Tonight 10:00 PM · …" (later today
 *  from 6 PM), "Tomorrow 2:00 PM · Ch 22 Gumshoe", or "Not scheduled in the next 24 hours". `time` is the app's clock
 *  ("10:00 PM"). */
function airsText(a: TitleSearch.Airing | null | undefined, now: Instant, time: (t: Instant) => string): string {
  if (a == null) return 'Not scheduled in the next 24 hours'
  const ch = `Ch ${a.number} ${a.name}`
  if (a.start <= now) return `On now · ${ch}, ${Math.trunc((now - a.start) / 60)} min in`
  const day = Time.startOfDay(a.start) !== Time.startOfDay(now) ? 'Tomorrow' : Time.localHour(a.start) >= 18 ? 'Tonight' : 'Today'
  return `${day} ${time(a.start)} · ${ch}`
}

export const TitleSearch = {
  /** The most results a search returns. */
  limit: 50,
  /** How far ahead "airs next" looks: 24 hours, in seconds. */
  window: 24 * 3600,
  index, search, nextAirings, airsText,
}

export namespace TitleSearch {
  /** One searchable title: a film (copies with the same title and year are one) or a show. */
  export interface Entry {
    /** "movie:<full key>|<year>" or "show:<series title>". */
    readonly id: string
    readonly title: string
    /** A show's: its earliest episode's. */
    readonly year: number | null
    readonly isShow: boolean
    /** The film (its first copy), or the show's first episode in the library. */
    readonly item: MediaItem
    /** The title's letters and digits ("theoffice"), and the same without a leading article ("office"). */
    readonly full: string
    readonly bare: string
  }

  /** Every entry with its keys, in tie order, and the entry (id) each item airs as. */
  export interface Index {
    readonly entries: readonly Entry[]
    readonly entryOf: ReadonlyMap<string, string>
  }

  /** Where an entry airs next. */
  export interface Airing {
    readonly channelID: string
    readonly number: number
    readonly name: string
    readonly start: Instant
  }
}
