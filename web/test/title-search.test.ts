/// <reference types="node" />
// TitleSearchTests.kt. The calendar is Los Angeles (npm test sets TZ) where the Swift test's is UTC: the same wall-clock
// times, and no DST change between the 27th and the 28th.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { Channel, MediaItem, Slot } from '../src/core/Models'
import { TitleSearch } from '../src/core/TitleSearch'

process.env.TZ = 'America/Los_Angeles'

const movie = (id: string, title: string, year: number | null, minutes = 100) =>
  new MediaItem({ id, kind: 'movie', title, year, runtime: minutes * 60 })
const episode = (id: string, show: string | null, title: string, year: number) => new MediaItem({
  id, kind: 'episode', title, seriesTitle: show, seriesID: show == null ? null : `s-${show}`, season: 1, episode: 1, year, runtime: 22 * 60,
})

const library = [
  movie('m1', 'Amélie', 2001),
  movie('m2', 'The Godfather', 1972, 175),
  movie('m3', 'The Godfather Part II', 1974),
  movie('m4', 'Godzilla', 2014),
  movie('m5', 'Godzilla', 1954),
  movie('m6', 'GODZILLA', 1954),            // a second copy of m5: one result
  movie('m7', 'Godzilla', null),            // no year: counts as 0
  movie('m8', 'Spider-Man', 2002),
  movie('m9', 'Schindler’s List', 1993),
  movie('m10', 'Office Space', 1999, 89),
  movie('m11', 'A Bug\'s Life', 1998),
  movie('m12', 'Fargo', 1996, 98),
  movie('m13', 'Man on Fire', 2004),
  movie('m14', 'Batman', 1989),
  episode('e1', 'The Office', 'Pilot', 2006),
  episode('e2', 'The Office', 'Diversity Day', 2005),
  episode('e3', 'Fargo', 'The Crocodile\'s Dilemma', 2014),
  episode('e4', null, 'Godzilla Returns', 2000),   // no series title: not searchable
]
const index = TitleSearch.index(library)

const titles = (q: string) => TitleSearch.search(q, index).map(it => it.title)
const found = (q: string) => TitleSearch.search(q, index).map(it => `${it.title} ${it.year ?? '-'}`)

test('capitals, accents and punctuation', () => {
  assert.deepEqual(titles('OFFICE SPACE'), ['Office Space'])
  assert.deepEqual(titles('amelie'), ['Amélie'])
  assert.deepEqual(titles('AMÉLIE'), ['Amélie'])
  for (const q of ['spider-man', 'spiderman', 'spider man', 'SPIDER MAN!']) assert.deepEqual(titles(q), ['Spider-Man'], q)
  assert.deepEqual(titles('schindlers list'), ['Schindler’s List'])
  assert.deepEqual(titles('schindler\'s'), ['Schindler’s List'])
})

/** A word-final sigma is ς typed and Σ in capitals: both match σ, so capitals don't matter in Greek either. */
test('final sigma', () => {
  const greek = TitleSearch.index([movie('g1', 'ΖΟΡΜΠΑΣ', 1964), movie('g2', 'Ζορμπάς', 1965)])
  for (const q of ['Ζορμπάς', 'ΖΟΡΜΠΑΣ', 'ζορμπασ']) assert.deepEqual(TitleSearch.search(q, greek).map(it => it.title), ['ΖΟΡΜΠΑΣ', 'Ζορμπάς'], q)
})

/** "office" is The Office exactly (its article needn't be typed), so it comes before Office Space, which only starts with
 *  it; typing the article works too. */
test('leading article', () => {
  assert.deepEqual(titles('office'), ['The Office', 'Office Space'])
  assert.deepEqual(titles('the office'), ['The Office'])
  assert.deepEqual(titles('bugs life'), ['A Bug\'s Life'])
  assert.deepEqual(titles('a bug'), ['A Bug\'s Life'])
  assert.deepEqual(titles('godfather'), ['The Godfather', 'The Godfather Part II'])
})

test('partial words', () => {
  assert.deepEqual(titles('godf'), ['The Godfather', 'The Godfather Part II'])
  assert.deepEqual(titles('father'), ['The Godfather', 'The Godfather Part II'])
  assert.deepEqual(titles('spiderm'), ['Spider-Man'])
})

/** Exact titles, then titles that start with the query, then any other match, each by title then year (none = 0). */
test('order and ties', () => {
  assert.deepEqual(found('god'), ['The Godfather 1972', 'The Godfather Part II 1974', 'Godzilla -', 'Godzilla 1954', 'Godzilla 2014'])
  assert.deepEqual(found('man'), ['Man on Fire 2004', 'Batman 1989', 'Spider-Man 2002'])
  assert.deepEqual(found('godzilla'), ['Godzilla -', 'Godzilla 1954', 'Godzilla 2014'])
  assert.deepEqual(found('fargo'), ['Fargo 1996', 'Fargo 2014'])
})

/** Films by title, shows by series title once each (their year is the earliest episode's); episode titles never match. */
test('movies and shows', () => {
  const office = TitleSearch.search('the office', index)
  assert.deepEqual(office.map(it => it.isShow), [true])
  assert.equal(office[0]?.year, 2005)
  assert.deepEqual(TitleSearch.search('fargo', index).map(it => it.isShow), [false, true])
  assert.deepEqual(titles('pilot'), [])
  assert.deepEqual(titles('diversity'), [])
  assert.deepEqual(titles('returns'), [])
})

test('cap and no results', () => {
  const many = TitleSearch.index(Array.from({ length: 60 }, (_, i) => movie(`n${i + 1}`, `Night ${i + 1}`, 2000)))
  const r = TitleSearch.search('night', many)
  assert.equal(r.length, TitleSearch.limit)
  assert.equal(TitleSearch.limit, 50)
  assert.deepEqual(r.slice(0, 3).map(it => it.title), ['Night 1', 'Night 10', 'Night 11'])   // by title key, as text
  assert.deepEqual(titles('zzz'), [])
  assert.deepEqual(titles(''), [])
  assert.deepEqual(titles('!!'), [])
})

// MARK: Airs next

/** `ZonedDateTime.of(2026, 10, d, h, m, 0, 0, zone)`. */
const at = (d: number, h: number, m = 0) => new Date(2026, 9, d, h, m).getTime() / 1000
/** "h:mm a" (en_US_POSIX). */
function clock(t: number): string {
  const d = new Date(t * 1000)
  return `${d.getHours() % 12 || 12}:${String(d.getMinutes()).padStart(2, '0')} ${d.getHours() < 12 ? 'AM' : 'PM'}`
}
const item = (id: string) => library.find(it => it.id === id)!
const slot = (id: string, start: number, end: number) => new Slot(item(id), start, end)

/** Now is 7:20 PM on the 27th. Each entry's earliest airing in the next 24 hours, as the result rows say it. */
test('airs next', () => {
  const now = at(27, 19, 20)
  const c5 = Channel.make('c5', 5, 'Fright Night', [])
  const c9 = Channel.make('c9', 9, 'Studio Audience', [])
  const c22 = Channel.make('c22', 22, 'Gumshoe', [])
  const lineups = [
    { channel: c22, slots: [slot('m10', at(27, 17, 30), at(27, 19, 30)),   // Office Space: in its break now (ended 6:59)
      slot('m12', at(27, 17, 30), at(27, 19, 30)),   // Fargo: in its break, and not on again
      slot('m1', at(27, 20), at(27, 22)),
      slot('m9', at(27, 21), at(27, 23)),
      slot('m8', at(27, 22), at(28, 0)),
      slot('m2', at(27, 23), at(28, 2))] },
    { channel: c5, slots: [slot('m2', at(27, 18, 40), at(27, 21, 30)),     // The Godfather: on now, 40 min in
      slot('m9', at(27, 21), at(27, 23)),             // same start as on 22: the lower number wins
      slot('m1', at(27, 23, 30), at(28, 1, 30)),      // later than on 22: 22 wins
      slot('m10', at(28, 14), at(28, 16)),
      slot('m3', at(28, 19, 30), at(28, 21, 30))] },  // past the 24 hours
    { channel: c9, slots: [slot('e1', at(27, 23), at(27, 23, 30)),
      slot('e2', at(27, 20, 30), at(27, 21))] },      // the show's next episode
  ]
  const airs = TitleSearch.nextAirings(lineups, index, now)
  const text = (q: string) => TitleSearch.airsText(airs.get(TitleSearch.search(q, index)[0].id), now, clock)
  assert.equal(text('the godfather'), 'On now · Ch 5 Fright Night, 40 min in')
  assert.equal(text('office space'), 'Tomorrow 2:00 PM · Ch 5 Fright Night')
  assert.equal(text('fargo'), 'Not scheduled in the next 24 hours')
  assert.equal(text('amelie'), 'Tonight 8:00 PM · Ch 22 Gumshoe')
  assert.equal(text('schindlers list'), 'Tonight 9:00 PM · Ch 5 Fright Night')
  assert.equal(text('spider-man'), 'Tonight 10:00 PM · Ch 22 Gumshoe')
  assert.equal(text('the office'), 'Tonight 8:30 PM · Ch 9 Studio Audience')
  assert.equal(text('godfather part ii'), 'Not scheduled in the next 24 hours')
  assert.equal(airs.get(TitleSearch.search('godfather', index)[0].id)?.channelID, 'c5')
})

/** Later today is "Today" before 6 PM and "Tonight" from then; the next calendar day is "Tomorrow". */
test('airs text days', () => {
  const text = (start: number, now: number) =>
    TitleSearch.airsText({ channelID: 'c22', number: 22, name: 'Gumshoe', start }, now, clock)
  assert.equal(text(at(27, 15), at(27, 9)), 'Today 3:00 PM · Ch 22 Gumshoe')
  assert.equal(text(at(27, 17, 30), at(27, 9)), 'Today 5:30 PM · Ch 22 Gumshoe')
  assert.equal(text(at(27, 18), at(27, 9)), 'Tonight 6:00 PM · Ch 22 Gumshoe')
  assert.equal(text(at(28, 2), at(27, 19, 20)), 'Tomorrow 2:00 AM · Ch 22 Gumshoe')
  assert.equal(text(at(27, 9), at(27, 9)), 'On now · Ch 22 Gumshoe, 0 min in')
  assert.equal(TitleSearch.airsText(null, at(27, 9), clock), 'Not scheduled in the next 24 hours')
})

/** Saturday 11:30 PM before a spring-forward Sunday (23 hours) in Los Angeles: 24 hours on is 12:30 AM Monday, which
 *  "Tomorrow" would say as Sunday's. The window stops at the end of tomorrow. */
test('spring forward night', () => {
  const march = (d: number, h: number, m = 0) => new Date(2027, 2, d, h, m).getTime() / 1000
  const now = march(13, 23, 30)
  const c22 = Channel.make('c22', 22, 'Gumshoe', [])
  const airs = TitleSearch.nextAirings([{ channel: c22, slots: [slot('m8', march(14, 23), march(15, 0)), slot('m1', march(15, 0), march(15, 2))] }],
    index, now)
  const text = (q: string) => TitleSearch.airsText(airs.get(TitleSearch.search(q, index)[0].id), now, clock)
  assert.equal(text('spider-man'), 'Tomorrow 11:00 PM · Ch 22 Gumshoe')
  assert.equal(text('amelie'), 'Not scheduled in the next 24 hours')
})
