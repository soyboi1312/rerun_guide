/// <reference types="node" />
// GuideGeneratorTests.kt. The calendar is Los Angeles (npm test sets TZ); the Cairo case switches process.env.TZ, which
// Node re-reads, and puts it back.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { DemoMediaServer } from '../src/core/DemoServer'
import { ChannelBuilder, GuideGenerator, SeededGenerator } from '../src/core/GuideGenerator'
import { JSONException, stableSort } from '../src/core/Lang'
import { Channel, ChannelDefinition, MediaItem, PlayOrder, RuleChip, Slot, swiftCompare } from '../src/core/Models'
import * as Time from '../src/core/Time'

process.env.TZ = 'America/Los_Angeles'
const block = GuideGenerator.blockLength
const demo = DemoMediaServer.items

/** `ZonedDateTime.of(y, m, d, h, min, 0, 0, zone)` in the current TZ (no test time falls in a DST gap). */
const date = (y: number, m: number, d: number, h = 0, min = 0): number => new Date(y, m - 1, d, h, min).getTime() / 1000
const channel = (id: string, ...minutes: number[]) => Channel.make(id, 2, id, minutes.map((m, i) =>
  new MediaItem({ id: `${id}-${i}`, kind: 'movie', title: `${m}m`, runtime: m * 60 })))
const lineup = (): Channel[] => ChannelBuilder.channelsOf(demo)
const comedy = (): Channel => lineup().find(it => it.id === 'genre:Comedy:tv')!
const zipWithNext = <T>(a: readonly T[]): [T, T][] => a.slice(1).map((b, i) => [a[i], b] as [T, T])
const film = (id: string, title: string, year: number, runtime: number) => new MediaItem({ id, kind: 'movie', title, year, runtime })

function assertTiles(slots: readonly Slot[], day: number) {
  const start = Time.startOfDay(day)
  const end = Time.addDays(start, 1)
  assert.equal(slots[0].start, start)
  assert.equal(slots[slots.length - 1].end, end)
  for (const [a, b] of zipWithNext(slots)) assert.equal(a.end, b.start)
  // On the half-hour grid, except short programs sharing a half hour: those stay inside it.
  const offset = (t: number) => t - start
  for (const s of slots.slice(0, -1)) {
    assert.ok(s.duration > 0)
    const half = Math.floor(offset(s.start) / block)
    if (offset(s.start) % block !== 0 || offset(s.end) % block !== 0) {
      assert.ok(offset(s.end) <= (half + 1) * block, `${s.item.title} crosses a half hour`)
    }
  }
  assert.ok(slots[slots.length - 1].duration > 0)
}

// MARK: Scheduler

test('deterministic per channel and day', () => {
  const c0 = comedy()
  assert.ok(c0.items.length >= 8)
  const a = GuideGenerator.schedule(c0, date(2026, 1, 5))
  const b = GuideGenerator.schedule(c0, date(2026, 1, 5, 13, 37))
  assert.deepEqual(a, b)
  const c = GuideGenerator.schedule(c0, date(2026, 1, 6))
  assert.notDeepEqual(a.map(it => it.item.id), c.map(it => it.item.id))
})

test('day tiling', () => {
  const chans = lineup()
  for (const ch of chans) for (let d = 0; d < 5; d++) assertTiles(GuideGenerator.schedule(ch, date(2026, 1, 1 + d)), date(2026, 1, 1 + d))
  const single = GuideGenerator.schedule(channel('x', 140), date(2026, 1, 1))
  assertTiles(single, date(2026, 1, 1))
  assert.equal(single.length, 9)   // 8 × 2.5h, then the 9th film runs to midnight with a 1.5h longer break
  assert.equal(single[single.length - 1].duration, 8 * block)
  // No program is ever cut short: every slot holds its whole item (the leftover before midnight is a break).
  for (const ch of chans) for (let d = 0; d < 14; d++) {
    for (const s of GuideGenerator.schedule(ch, date(2026, 1, 1 + d))) assert.ok(s.duration >= s.item.runtime, `${ch.id} cut ${s.item.title} short`)
  }
  // The window is filled with the longest item that fits: a 2h and a 3.5h film, 30 min left after 7 × 3.5h.
  const mixed = Channel.make('m', 2, 'm', [
    new MediaItem({ id: 'long', kind: 'movie', title: 'Long', runtime: 205 * 60 }),   // 3.5h slot
    new MediaItem({ id: 'short', kind: 'movie', title: 'Short', runtime: 100 * 60 }), // 2h slot
  ], undefined, 'inOrder')
  for (let d = 0; d < 7; d++) {
    const day = GuideGenerator.schedule(mixed, date(2026, 2, 1 + d))
    assertTiles(day, date(2026, 2, 1 + d))
    assert.ok(day.every(it => it.duration >= it.item.runtime))
  }
})

/**
 * Nothing airs twice in a row: not where the day's last slot is re-picked to run to midnight (say, a 181-minute western
 * at 5:00 and again at 8:30 PM as the one 3.5-hour film that fills 8:30 to midnight), not a second copy or cut of the
 * same film, and not across midnight.
 */
test('no back-to-back repeats', () => {
  const key = GuideGenerator.repeatKey
  const repeats: string[] = []
  const check = (ch: Channel, days: number, start: number) => {
    let previous: Slot | null = null
    for (let d = 0; d < days; d++) {
      const day = Time.addDays(start, d)
      const slots = GuideGenerator.schedule(ch, day)
      assertTiles(slots, day)
      assert.ok(slots.every(it => it.duration >= it.item.runtime))
      for (const [a, b] of zipWithNext(slots)) if (key(a.item) === key(b.item)) repeats.push(`${ch.id} day ${d} ${a.item.id}/${b.item.id}`)
      // Across midnight only on a show played in order, or when nothing else fits the last slot: nothing that repeats
      // neither the slot before it nor what opens the next day.
      const last: Slot | null = previous
      if (last != null && key(last.item) === key(slots[0].item) && ch.order !== 'inOrder') {
        const yesterday = GuideGenerator.schedule(ch, Time.addDays(start, d - 1))
        const beforeSlot = yesterday.slice(0, -1).pop()
        const before = beforeSlot ? key(beforeSlot.item) : null
        const window = last.end - last.start
        if (ch.items.some(it => key(it) !== before && key(it) !== key(last.item) && GuideGenerator.slotLength(it) <= window)) {
          repeats.push(`${ch.id} midnight before day ${d} ${last.item.id}/${slots[0].item.id}`)
        }
      }
      previous = slots[slots.length - 1]
    }
  }
  for (let n = 2; n <= 6; n++) {
    for (let seed = 0; seed < 40; seed++) {
      const rng = new SeededGenerator(`repeat|${n}|${seed}`)
      const items = Array.from({ length: n }, (_, i) => film(`f${i}`, `F${i}`, 1990, (80 + Number(rng.next() % BigInt(130))) * 60))
      if (seed % 2 === 1) items.push(film('f0-cut', 'F0', 1990, items[0].runtime + 1800))   // a second copy of F0: another item, same title and year
      check(Channel.make(`r${n}-${seed}`, 2, 'r', items, undefined, seed % 3 === 0 ? 'inOrder' : 'shuffle'), 4, date(2026, 3, 1))
    }
  }
  for (const ch of lineup().filter(it => it.items.length > 1)) check(ch, 14, date(2026, 1, 1))
  assert.deepEqual(repeats, [])

  // A channel holding nothing but copies of one film still fills the day.
  const cuts = Channel.make('cuts', 2, 'cuts', [film('a', 'Alien', 1979, 116 * 60), film('b', 'Alien', 1979, 115 * 60)])
  const day = GuideGenerator.schedule(cuts, date(2026, 3, 1))
  assertTiles(day, date(2026, 3, 1))
  assert.equal(day.length, 12)   // 2h slots back to back
  // ...even next to an item too long to ever air, instead of one slot with a 22-hour break.
  const stuck = Channel.make('stuck', 2, 'stuck', [cuts.items[0], film('huge', 'Huge', 1990, 30 * 3600)])
  for (let d = 0; d < 4; d++) {
    const slots = GuideGenerator.schedule(stuck, date(2026, 1, 1 + d))
    assertTiles(slots, date(2026, 1, 1 + d))
    assert.equal(slots.length, 12)
  }
  // Where a DST change skips midnight (Cairo, 2024-04-26), one day ends exactly where the next begins.
  const six = lineup().slice(0, 6)
  const la = process.env.TZ
  process.env.TZ = 'Africa/Cairo'
  try {
    assert.equal(new Date(2024, 3, 26, 0, 30).getHours(), 1, 'this Node has no 2024 Cairo DST: the case below proves nothing')
    for (const ch of six) {
      const [a, b, c] = [25, 26, 27].map(d => GuideGenerator.schedule(ch, new Date(2024, 3, d, 12).getTime() / 1000))
      assert.equal(a[a.length - 1].end, b[0].start, ch.id)
      assert.equal(b[b.length - 1].end, c[0].start, ch.id)
      assert.equal(new Date(b[0].start * 1000).getHours(), 1, ch.id)   // the 26th starts at 1:00
    }
  } finally {
    process.env.TZ = la
  }
})

/**
 * A short program doesn't leave most of its half hour empty: the next ones in order that fit play right after it
 * (8-minute episodes: three to a half hour, the last keeping the rest), and nothing crosses the half hour.
 */
test('short programs share their half hour', () => {
  const eps = Array.from({ length: 12 }, (_, k) => new MediaItem({
    id: `e${k + 1}`, kind: 'episode', title: `E${k + 1}`, seriesTitle: 'Toons', seriesID: 'toons', season: 1, episode: k + 1, runtime: 8 * 60 + 0.4,
  }))
  const toons = Channel.make('toons', 2, 'Toons', eps, undefined, 'inOrder')
  const slots = GuideGenerator.schedule(toons, date(2026, 3, 1))
  assertTiles(slots, date(2026, 3, 1))
  assert.deepEqual(slots.slice(0, 3).map(it => it.duration), [480, 480, 840])
  assert.equal(slots.length, 48 * 3)
  const order = eps.map(it => it.id)
  for (const [a, b] of zipWithNext(slots)) {   // still in order, episode after episode
    assert.equal(order.indexOf(b.item.id), (order.indexOf(a.item.id) + 1) % order.length)
  }
  assert.ok(slots.every(it => it.duration >= Math.floor(it.item.runtime)))
  // Up to half its half hour (15 minutes, the pad not counted) shares it: 13-minute episodes go two to a half hour.
  const thirteen = Channel.make('thirteen', 2, 'Thirteen', Array.from({ length: 4 }, (_, k) => new MediaItem({
    id: `t${k + 1}`, kind: 'episode', title: `T${k + 1}`, seriesTitle: 'Thirteen', seriesID: 'thirteen', season: 1, episode: k + 1, runtime: 13 * 60,
  })), undefined, 'inOrder')
  assert.deepEqual(GuideGenerator.schedule(thirteen, date(2026, 3, 1)).slice(0, 2).map(it => it.duration), [780, 1020])
  // 16 minutes is more than half its half hour: it keeps the slot to itself.
  const longer = Channel.make('longer', 2, 'Longer', Array.from({ length: 4 }, (_, k) => new MediaItem({
    id: `l${k + 1}`, kind: 'episode', title: `L${k + 1}`, seriesTitle: 'Longer', seriesID: 'longer', season: 1, episode: k + 1, runtime: 16 * 60,
  })), undefined, 'inOrder')
  assert.ok(GuideGenerator.schedule(longer, date(2026, 3, 1)).every(it => it.duration === block))
})

/** Two cuts of a film are two films (titled apart, free to follow each other); two plain copies are one. */
test('editions are different films', () => {
  assert.equal(MediaItem.title('Watchmen', 'Directors Cut'), 'Watchmen (Directors Cut)')
  assert.equal(MediaItem.title('Watchmen', ' '), 'Watchmen')
  assert.equal(MediaItem.title('Watchmen', null), 'Watchmen')
  assert.equal(MediaItem.edition('/films/Watchmen (2009) - {imdb-tt0409459}{edition-Ultimate Version}[Bluray-1080p].mkv'), 'Ultimate Version')
  assert.equal(MediaItem.edition('/films/Watchmen (2009) - {imdb-tt0409459}[Bluray-1080p].mkv'), null)
  assert.equal(MediaItem.edition(null), null)
  const watchmen = (id: string, edition: string | null) => film(id, MediaItem.title('Watchmen', edition), 2009, 186 * 60)
  assert.notEqual(GuideGenerator.repeatKey(watchmen('a', null)), GuideGenerator.repeatKey(watchmen('b', 'Directors Cut')))
  assert.equal(GuideGenerator.repeatKey(watchmen('a', null)), GuideGenerator.repeatKey(watchmen('c', null)))   // a 4K copy and a 1080p copy
})

test('block rounding', () => {
  const first = (ch: Channel) => GuideGenerator.schedule(ch, date(2026, 1, 1))[0].duration
  assert.equal(first(channel('a', 22)), block)
  assert.equal(first(channel('b', 95)), 4 * block)
  // 30 min + the 3-minute pad spills into a second block (a real guide never jams a 30-min show into one 30-min slot).
  assert.equal(first(channel('c', 30)), 2 * block)
  assert.equal(first(channel('d', 27)), block)
})

test('DST days tile midnight to midnight', () => {
  for (const [day, hours] of [[date(2026, 3, 8), 23], [date(2026, 11, 1), 25]]) {
    const slots = GuideGenerator.schedule(comedy(), day)
    assertTiles(slots, day)
    assert.equal(slots.reduce((t, s) => t + s.duration, 0), hours * 3600)
  }
})

test('window spanning midnight', () => {
  const c = comedy()
  const from = date(2026, 1, 5, 23)
  const to = date(2026, 1, 6, 1)
  const slots = GuideGenerator.scheduleRange(c, from, to)
  assert.ok(slots.length > 0)
  assert.ok(slots.every(it => it.end > from && it.start < to))
  assert.ok(slots.some(it => it.start < date(2026, 1, 6)))
  assert.ok(slots.some(it => it.start >= date(2026, 1, 6)))
  assert.ok(slots[0].start <= from)
  assert.ok(slots[slots.length - 1].end >= to)
  for (const [a, b] of zipWithNext(slots)) assert.equal(a.end, b.start)
  const full = GuideGenerator.schedule(c, from).concat(GuideGenerator.schedule(c, to))
  assert.deepEqual(slots, full.filter(it => it.end > from && it.start < to))
})

test('status', () => {
  const slot = GuideGenerator.schedule(channel('a', 22), date(2026, 1, 1))[0]
  assert.equal(slot.duration, block)
  assert.equal(GuideGenerator.status(slot, date(2025, 12, 31, 23, 59)), 'later')
  assert.equal(GuideGenerator.status(slot, slot.start), 'live')
  assert.equal(GuideGenerator.status(slot, date(2026, 1, 1, 0, 21)), 'live')
  assert.equal(GuideGenerator.status(slot, date(2026, 1, 1, 0, 22)), 'break')
  assert.equal(GuideGenerator.status(slot, date(2026, 1, 1, 0, 25)), 'break')
  assert.equal(GuideGenerator.status(slot, slot.end), 'past')
  assert.equal(GuideGenerator.status(slot, date(2026, 1, 1, 9)), 'past')
})

/** `ProgramKey` stores a slot's start; the generator must hand the same slot back from it, on every channel and across midnight. */
test('slot start round trip', () => {
  for (const ch of lineup()) {
    const slots = GuideGenerator.scheduleRange(ch, date(2026, 1, 5, 22), date(2026, 1, 6, 3))
    assert.ok(slots.length > 2)
    for (const s of slots) {
      assert.deepEqual(GuideGenerator.slotStartingAt(ch, s.start), s)
      assert.deepEqual(GuideGenerator.slot(ch, s.start), s)
    }
  }
  const c = comedy()
  const s = GuideGenerator.schedule(c, date(2026, 1, 5))[3]
  assert.deepEqual(GuideGenerator.slotStartingAt(c, s.start + 0.25), s)   // survived JSON
  assert.equal(GuideGenerator.slotStartingAt(c, s.start + 60), null)
})

test('in order plays in sequence and rotates daily', () => {
  const smh = lineup().find(it => it.id === 'series:smh')!
  assert.equal(smh.order, 'inOrder')
  const order = smh.items.map(it => it.id)
  assert.deepEqual(smh.items.map(it => `${it.season}-${it.episode}`), ['1-1', '1-2', '1-3', '1-4', '2-1', '2-2', '2-3', '2-4'])
  const firsts = new Set<string>()
  for (let d = 0; d < 6; d++) {
    const ids = GuideGenerator.schedule(smh, date(2026, 2, 1 + d)).map(it => it.item.id)
    assert.equal(ids.length, 48)                           // 22-min episodes → 48 half-hour slots
    for (const [a, b] of zipWithNext(ids)) {               // every slot is the next episode, wrapping
      assert.equal(order.indexOf(b), (order.indexOf(a) + 1) % order.length)
    }
    firsts.add(ids[0])
  }
  assert.ok(firsts.size > 1)                               // not every day starts on the same episode
})

// MARK: ChannelBuilder

test('automatic lineup', () => {
  const defs = ChannelBuilder.automaticChannels(demo)
  assert.deepEqual(ChannelBuilder.automaticChannels(demo), defs)
  assert.deepEqual(defs.map(it => it.id), lineup().map(it => it.id))   // every automatic channel has something to air

  // Numbers: start at 2, strictly increasing, with gaps like cable channel numbers.
  assert.equal(defs[0].number, 2)
  const nums = defs.map(it => it.number)
  assert.deepEqual(stableSort(nums, (a, b) => a - b), nums)
  assert.equal(new Set(nums).size, nums.length)
  assert.ok(zipWithNext(nums).every(([a, b]) => b - a >= 1 && b - a <= 3))
  assert.ok(zipWithNext(nums).some(([a, b]) => b - a > 1))

  // Lead channels first (the demo library is 1978–1999 with movies added over the last ~120 days, none watched →
  // only Just Added and Unwatched).
  assert.equal(defs[0].id, 'lead:just-added')
  assert.equal(defs[0].name, 'Just Added')
  assert.equal(defs[1].id, 'lead:unwatched')
  assert.ok(!defs.some(it => it.id === 'lead:new-movies'))
  // The rest is scattered, not alphabetical, but identical on every call.
  const restNames = defs.slice(1).map(it => it.name)
  assert.notDeepEqual(stableSort(restNames, swiftCompare), restNames)
  assert.deepEqual(ChannelBuilder.automaticChannels(demo).slice(1).map(it => it.name), restNames)

  const def = (id: string): ChannelDefinition => {
    const d = defs.find(it => it.id === id)
    if (!d) throw new assert.AssertionError({ message: `missing ${id}` })
    return d
  }
  // Split genre with retro names: lots of comedy films and lots of comedy episodes.
  const cm = def('genre:Comedy:movies')
  const ct = def('genre:Comedy:tv')
  assert.deepEqual([cm.name, cm.tag], ['Funny Business', 'Comedy films, 80s–90s'])
  assert.deepEqual(cm.rule, [RuleChip.genre('Comedy'), RuleChip.years(1980, 1999), RuleChip.movies])
  assert.deepEqual([ct.name, ct.tag], ['Studio Audience', 'Sitcoms, 80s–90s'])
  assert.deepEqual(ct.rule, [RuleChip.genre('Comedy'), RuleChip.years(1980, 1994), RuleChip.tv])   // decade floor to the latest year
  assert.ok(!defs.some(it => it.id === 'genre:Comedy'))
  // Never mixed: Sci-Fi is films only (a films channel); Crime is episodes plus two films, too few for a films channel.
  const sf = def('genre:Sci-Fi:movies')
  const crime = def('genre:Crime:tv')
  assert.deepEqual([sf.name, sf.tag], ['Final Frontier', 'Sci-fi films, 80s–90s'])
  assert.deepEqual(sf.rule, [RuleChip.genre('Sci-Fi'), RuleChip.years(1980, 1997), RuleChip.movies])
  assert.equal(crime.name, 'Gumshoe')
  assert.ok(!defs.some(it => ['genre:Crime', 'genre:Crime:movies', 'genre:Sci-Fi', 'genre:Sci-Fi:tv'].indexOf(it.id) >= 0))
  assert.ok(ChannelBuilder.resolve(crime, demo).every(it => it.kind === 'episode'))
  for (const d of defs) assert.equal(new Set(ChannelBuilder.resolve(d, demo).map(it => it.kind)).size, 1, d.id)   // no automatic channel airs films and TV together
  // Marathons, directors, actors, years.
  const smh = def('series:smh')
  assert.deepEqual([smh.name, smh.tag], ['Strip Mall Heroes', 'Marathon'])
  assert.equal(smh.order, 'inOrder')
  assert.deepEqual(smh.rule, [RuleChip.series('smh', 'Strip Mall Heroes')])
  assert.equal(defs.filter(it => it.id.startsWith('series:')).length, 3)
  assert.deepEqual(new Set(defs.filter(it => it.id.startsWith('director:')).map(it => it.name)), new Set(['Dusty Calhoun', 'Len Okonkwo', 'Marguerite Vole']))
  assert.deepEqual(def('director:Marguerite Vole').rule, [RuleChip.director('Marguerite Vole'), RuleChip.movies])
  assert.equal(def('director:Marguerite Vole').tag, 'Director')
  assert.deepEqual(new Set(defs.filter(it => it.id.startsWith('actor:')).map(it => it.name)), new Set(['Dolores Finch', 'Kip Navarro', 'Rex Tallow']))
  assert.deepEqual(def('actor:Rex Tallow').rule, [RuleChip.actor('Rex Tallow'), RuleChip.movies])
  assert.equal(def('actor:Rex Tallow').tag, 'Actor')
  assert.deepEqual(defs.filter(it => it.id.startsWith('year:')).map(it => [it.id, it.name, it.tag]), [['year:1985', 'Class of ’85', 'Films from 1985']])
  assert.deepEqual(def('year:1985').rule, [RuleChip.year(1985), RuleChip.movies])
  assert.ok(defs.every(it => !it.isCustom && it.trailerBreaks))

  // Thresholds and edge cases.
  assert.deepEqual(ChannelBuilder.channelsOf(demo, 10).map(it => it.id).filter(it => !it.startsWith('genre:')), ['lead:unwatched'])   // all 20 films
  assert.ok(ChannelBuilder.channelsOf(demo, 10).some(it => it.id === 'genre:Comedy:tv'))   // too few films for a films channel: TV only
  assert.deepEqual(ChannelBuilder.channelsOf(demo, 1000), [])
  assert.deepEqual(ChannelBuilder.channelsOf([]), [])
  const noID = Array.from({ length: 4 }, (_, k) => new MediaItem({ id: `n${k + 1}`, kind: 'episode', title: `e${k + 1}`, seriesTitle: 'Orphan Show', episode: k + 1, runtime: 1 }))
  assert.deepEqual(ChannelBuilder.channelsOf(noID).map(it => it.id), ['series:Orphan Show'])
  assert.equal(ChannelBuilder.channelsOf(noID)[0].number, 2)
})

test('year channels capped at the three biggest', () => {
  const lib: MediaItem[] = []
  for (const [y, n] of [[1979, 4], [1985, 6], [1988, 5], [1992, 4], [2005, 7]]) {
    for (let i = 0; i < n; i++) lib.push(film(`${y}-${i}`, `${y} ${i}`, y, 5400))
  }
  const years = ChannelBuilder.automaticChannels(lib).filter(it => it.id.startsWith('year:'))
  assert.deepEqual(new Set(years.map(it => it.id)), new Set(['year:1985', 'year:1988', 'year:2005']))
  assert.deepEqual(new Set(years.map(it => it.name)), new Set(['Class of ’05', 'Class of ’85', 'Class of ’88']))   // scattered, so compare as a set
})

test('resolve honours every chip', () => {
  const items = (rule: RuleChip[], order: PlayOrder = 'shuffle') =>
    ChannelBuilder.resolve(new ChannelDefinition({ id: 't', number: 1, name: 't', tag: '', rule, order }), demo)
  const ids = (rule: RuleChip[]) => new Set(items(rule).map(it => it.id))
  assert.deepEqual(ids([RuleChip.genre('Horror')]), new Set(['m1', 'm6', 'm11', 'm15', 'm19']))
  const nineties = ['m5', 'm7', 'm8', 'm12', 'm17', 'm18']
  const ninetiesEps = demo.filter(it => it.seriesID === 'smh' || it.seriesID === 'p9').map(it => it.id)
  assert.deepEqual(ids([RuleChip.years(1990, 1995)]), new Set(nineties.concat(ninetiesEps)))
  assert.deepEqual(ids([RuleChip.director('Len Okonkwo')]), new Set(['m3', 'm9', 'm14', 'm20']))
  assert.deepEqual(items([RuleChip.actor('Bea Saltonstall')]).map(it => it.id), ['m2', 'm5', 'm9'])
  assert.equal(items([RuleChip.series('p9', 'Precinct 9 at Nine')]).length, 8)
  assert.equal(items([RuleChip.series('nope', 'Precinct 9 at Nine')]).length, 0)       // id wins when the item has one
  assert.deepEqual(items([RuleChip.year(1985)]).map(it => it.id), ['m1', 'm4', 'm10', 'm15'])
  assert.equal(items([RuleChip.movies]).length, 20)
  assert.equal(items([RuleChip.tv]).length, 24)
  assert.equal(items([RuleChip.genre('Comedy'), RuleChip.tv, RuleChip.years(1990, 1999)]).length, 8)   // every chip must match
  assert.deepEqual(items([RuleChip.genre('Comedy'), RuleChip.movies], 'newestFirst').map(it => it.year), [1999, 1995, 1992, 1991, 1985, 1985, 1985])
  assert.deepEqual(items([RuleChip.series('smh', '')], 'inOrder').map(it => it.seasonEpisode),
    ['S1 E1', 'S1 E2', 'S1 E3', 'S1 E4', 'S2 E1', 'S2 E2', 'S2 E3', 'S2 E4'])
  assert.deepEqual(ChannelBuilder.channelsFrom([new ChannelDefinition({ id: 'x', number: 1, name: 'x', tag: '', rule: [RuleChip.year(1900)] })], demo), [])
})

test('candidates', () => {
  const directors = ChannelBuilder.candidates('director', demo)
  assert.deepEqual(directors.map(it => `${it.name} ${it.count}`), ['Marguerite Vole 7', 'Dusty Calhoun 6', 'Len Okonkwo 4', 'Priya Marsh 3'])
  const actors = ChannelBuilder.candidates('actor', demo)
  assert.deepEqual(actors.slice(0, 3).map(it => `${it.name} ${it.count}`), ['Dolores Finch 9', 'Kip Navarro 8', 'Rex Tallow 8'])
  assert.deepEqual(actors.slice(3).map(it => it.count), [3, 3, 3, 3, 3])
  assert.equal(ChannelBuilder.candidates('actor', demo, 4).length, 3)
  const series = ChannelBuilder.candidates('series', demo)
  assert.deepEqual(series.map(it => `${it.name} ${it.count}`), ['Cul-de-Sac Confidential 8', 'Precinct 9 at Nine 8', 'Strip Mall Heroes 8'])
  assert.deepEqual(series.map(it => it.id), ['cds', 'p9', 'smh'])
  assert.ok(directors.every(it => it.id == null))
  assert.deepEqual(ChannelBuilder.CandidateKind.entries.map(ChannelBuilder.CandidateKind.rawValue), ['Director', 'Actor', 'Show marathon'])
})

// MARK: Demo server

test('demo library filters by section', async () => {
  const server = new DemoMediaServer()
  const sections = await server.libraries()
  assert.deepEqual(sections.map(it => it.id), ['demo-movies', 'demo-tv'])
  const all = await server.libraryItems(null)
  assert.equal(all.length, 44)
  const movies = await server.libraryItems([sections[0]])
  assert.equal(movies.length, 20)
  assert.ok(movies.every(it => it.kind === 'movie' && it.libraryID === 'demo-movies'))
  const tv = await server.libraryItems([sections[1]])
  assert.equal(tv.length, 24)
  assert.ok(tv.every(it => it.kind === 'episode' && it.libraryID === 'demo-tv'))
  assert.ok(all.every(it => it.overview != null && it.overview !== '' && it.contentRating != null && it.genres.length > 0))
  assert.ok(movies.every(it => it.directors.length === 1 && it.actors.length === 2))
  assert.equal(await server.trailer(all[0], 'test'), null)
  assert.equal((await server.playbackSource(all[0], 0, 'watch')).startsAtOffset, true)
  assert.equal(server.artworkURL('/x'), null)
})

test('decade tags past 1999', () => {
  assert.equal(ChannelBuilder.decadeTag('Action', [1955, 2024]), 'Action, 50s–2020s')
  assert.equal(ChannelBuilder.decadeTag('Drama', [2001, 2009]), 'Drama, 2000s')
  assert.equal(ChannelBuilder.decadeTag('Comedy', [1983, 1991]), 'Comedy, 80s–90s')
})

test('people and series channels are capped', () => {
  // 30 directors with 4 movies each, 30 series with 4 episodes each.
  const lib: MediaItem[] = []
  for (let d = 0; d < 30; d++) {
    for (let m = 0; m < 4; m++) lib.push(new MediaItem({ id: `d${d}m${m}`, kind: 'movie', title: `M${d}-${m}`, year: 1990, runtime: 5400, directors: [`Dir ${d}`] }))
    for (let e = 0; e < 4; e++) lib.push(new MediaItem({ id: `s${d}e${e}`, kind: 'episode', title: `E${e}`, seriesTitle: `Show ${d}`, seriesID: `s${d}`, season: 1, episode: e + 1, runtime: 1320 }))
  }
  const defs = ChannelBuilder.automaticChannels(lib, undefined, 5, 3)
  assert.equal(defs.filter(it => it.tag === 'Marathon').length, 5)
  assert.equal(defs.filter(it => it.tag === 'Director').length, 3)
  assert.equal(ChannelBuilder.automaticChannels(lib).filter(it => it.tag === 'Marathon').length, 24)
})

test('lead channels and the relative chip', () => {
  const now = 1_800_000_000   // 2027-01-15
  const y = Time.localYear(now)
  const lib: MediaItem[] = []
  for (let i = 0; i < 5; i++) lib.push(new MediaItem({ id: `nm${i}`, kind: 'movie', title: `New ${i}`, year: y - (i % 2), runtime: 5400 }))
  for (let i = 0; i < 5; i++) lib.push(new MediaItem({ id: `nt${i}`, kind: 'episode', title: `Ep ${i}`, seriesTitle: 'Fresh', seriesID: 'fresh', season: 1, episode: i, year: y, runtime: 1320 }))
  for (let i = 0; i < 5; i++) lib.push(new MediaItem({ id: `old${i}`, kind: 'episode', title: `Old ${i}`, seriesTitle: 'Vintage', seriesID: 'vin', year: 1962, runtime: 1500, addedAt: now - i * 86_400 }))
  const ids = ChannelBuilder.automaticChannels(lib, undefined, undefined, undefined, now).map(it => it.id)
  assert.deepEqual(ids.slice(0, 5), ['lead:new-movies', 'lead:new-tv', 'lead:unwatched', 'lead:just-added-tv', 'lead:classic-tv'])   // only episodes were added
  const added = (days: number) =>
    ChannelBuilder.resolve(new ChannelDefinition({ id: 'x', number: 0, name: '', tag: '', rule: [RuleChip.addedWithin(days)] }), lib, now).length
  assert.equal(added(60), 5)
  assert.equal(added(2), 3)   // today, yesterday, two days ago
  assert.equal(RuleChip.label(RuleChip.years(1900, 1969)), 'Before 1970')
})

/**
 * Unwatched airs the films this account hasn't seen, never a watched one or an episode, right after Just Added, once
 * there are `minItems` of them.
 */
test('unwatched channel', () => {
  const now = 1_800_000_000
  const movie = (id: string, watched: boolean, added: number | null = null) =>
    new MediaItem({ id, kind: 'movie', title: id, year: 1990, runtime: 5400, addedAt: added, watched })
  const seen = Array.from({ length: 5 }, (_, i) => movie(`seen${i}`, true))
  const unseen = Array.from({ length: 4 }, (_, i) => movie(`new${i}`, false))
  const eps = Array.from({ length: 6 }, (_, i) =>
    new MediaItem({ id: `e${i}`, kind: 'episode', title: `E${i}`, seriesTitle: 'Show', seriesID: 'show', season: 1, episode: i, runtime: 1320 }))
  assert.equal(RuleChip.label(RuleChip.unwatched), 'Unwatched')
  assert.ok(ChannelBuilder.matches(RuleChip.unwatched, unseen[0])); assert.ok(!ChannelBuilder.matches(RuleChip.unwatched, seen[0]))
  const ch = ChannelBuilder.channelsOf([...seen, ...unseen, ...eps]).find(it => it.id === 'lead:unwatched')!
  assert.deepEqual([ch.name, ch.tag], ['Unwatched', 'Films you haven’t seen'])
  assert.deepEqual(ch.definition.rule, [RuleChip.unwatched, RuleChip.movies]); assert.equal(ch.order, 'shuffle')
  assert.deepEqual(ch.items.map(it => it.id), unseen.map(it => it.id))
  const day = GuideGenerator.schedule(ch, now)
  assert.ok(day.length > 0); assert.ok(day.every(s => s.item.kind === 'movie' && !s.item.watched))
  assert.ok(!ChannelBuilder.channelsOf([...seen, ...unseen.slice(0, -1), ...eps]).some(it => it.id === 'lead:unwatched'))   // 3 is too few
  const added = Array.from({ length: 4 }, (_, i) => movie(`add${i}`, true, now))
  const ids = ChannelBuilder.automaticChannels([...seen, ...unseen, ...added], undefined, undefined, undefined, now).map(it => it.id)
  assert.deepEqual(ids.slice(0, 2), ['lead:just-added', 'lead:unwatched'])
})

/**
 * `watched` is required when reading, so a library cache written before it existed is unreadable (and refetched)
 * instead of reading as all unwatched. The chip saves as {"unwatched":{}}, like the Swift and Kotlin ones.
 */
test('watched and the unwatched chip in JSON', () => {
  const item = new MediaItem({ id: 'm', kind: 'movie', title: 'M', runtime: 5400, watched: true })
  const o = JSON.parse(JSON.stringify(item.toJson()))
  assert.deepEqual(MediaItem.fromJson(o), item)
  delete o.watched
  assert.throws(() => MediaItem.fromJson(o), JSONException)
  const chips = JSON.stringify([RuleChip.unwatched, RuleChip.movies].map(RuleChip.toJson))
  assert.equal(chips, '[{"unwatched":{}},{"movies":{}}]')
  assert.deepEqual(JSON.parse(chips).map(RuleChip.fromJson), [RuleChip.unwatched, RuleChip.movies])
})

/**
 * Horror and thriller channels stay grown-up: a kids' title tagged Horror (the animated Gremlins series, Gravity Falls) is
 * left off; an adult animated one (Love, Death & Robots) and an unrated live-action one stay. Other channels keep them.
 */
test('kids titles stay off horror', () => {
  const ep = (id: string, genres: string[], rating: string | null) => new MediaItem({
    id, kind: 'episode', title: id, seriesTitle: id, seriesID: id, season: 1, episode: 1, year: 2023, genres, runtime: 1320, contentRating: rating,
  })
  const gremlins = ep('gremlins', ['Horror', 'Animation', 'Comedy'], 'TV-PG')
  const lib = [gremlins, ep('falls', ['Horror', 'Animation', 'Family'], 'TV-Y7'), ep('ldr', ['Horror', 'Animation'], 'TV-MA'), ep('live', ['Horror', 'Thriller'], null)]
  assert.deepEqual(lib.filter(it => ChannelBuilder.matches(RuleChip.genre('Horror'), it)).map(it => it.id), ['ldr', 'live'])
  assert.deepEqual(lib.filter(it => ChannelBuilder.matches(RuleChip.genre('Thriller'), it)).map(it => it.id), ['live'])
  assert.ok(ChannelBuilder.matches(RuleChip.genre('Comedy'), gremlins))
})

/**
 * "Creature Feature" is a keyword search inside Horror / Sci-Fi / Action / Adventure: the right word in the wrong genre,
 * or inside another word, stays out.
 */
test('theme chips and Creature Feature', () => {
  const movie = (i: number, title: string, genres: string[], overview: string | null = null) =>
    new MediaItem({ id: `m${i}`, kind: 'movie', title, year: 1990, genres, runtime: 5400, overview })
  const lib = [
    movie(1, 'Gorgo', ['Horror'], 'A sea monster is captured off the Irish coast.'),
    movie(2, 'Them!', ['Science Fiction'], 'Giant ants, the first of the atomic creatures.'),
    movie(3, 'Monsters, Inc.', ['Animation', 'Comedy'], 'Scaring is their business.'),   // right word, wrong genre
    movie(4, 'Monster\'s Ball', ['Drama']),                                                // title only, wrong genre
    movie(5, 'Procreature', ['Horror']),                                                  // not at a word start
    movie(6, 'Bloodlines', ['Horror'], 'The last vampires of Sarajevo.'),
    movie(7, 'Kong: Skull Island', ['Adventure']),
    movie(9, 'Kubo', ['Fantasy'], 'A boy faces a monster.'),                               // Fantasy isn't searched
    movie(8, 'Quiet', ['Horror'], 'Nothing to see.'),
  ]
  const creature = ChannelBuilder.channelsOf(lib).find(it => it.id === 'theme:monsters')
  assert.equal(creature?.name, 'Creature Feature')
  assert.deepEqual(stableSort(creature!.items.map(it => it.title), swiftCompare), ['Bloodlines', 'Gorgo', 'Kong: Skull Island', 'Them!'])
  assert.equal(RuleChip.label(RuleChip.anyGenre(['Horror', 'Fantasy'])), 'Genre: Horror / Fantasy')
  assert.equal(RuleChip.label(RuleChip.keywords(['monster', 'kaiju', 'godzilla', 'creature', 'zombie'])), 'Mentions: monster, kaiju, godzilla, creature…')
})

/** Web only: the word-start test steps back over a surrogate pair, and an empty word matches nothing (JS indexOf('') is 0). */
test('mentions: astral letters and the empty word', () => {
  const item = (title: string) => new MediaItem({ id: 'x', kind: 'movie', title, runtime: 60 })
  assert.ok(!ChannelBuilder.mentions(item('𝐀monster'), ['monster']))   // U+1D400 is a letter: not a word start
  assert.ok(ChannelBuilder.mentions(item('😀monster'), ['monster']))    // an emoji isn't
  assert.ok(ChannelBuilder.mentions(item('Monster'), ['monster']))
  assert.ok(!ChannelBuilder.mentions(item('Monster'), ['']))
})

/** Series chips match by id or by title, years ranges, mixed chips, and an empty rule takes the whole library. */
test('resolve rules', () => {
  const now = 1_800_000_000
  const noID = Array.from({ length: 4 }, (_, k) => new MediaItem({ id: `n${k + 1}`, kind: 'episode', title: `e${k + 1}`, seriesTitle: 'Orphan Show', episode: k + 1, runtime: 1, addedAt: now }))
  const lib = demo.concat(noID)
  const def = (id: string, rule: RuleChip[], order: PlayOrder = 'shuffle') => new ChannelDefinition({ id, number: 0, name: id, tag: '', rule, order })
  const defs = ChannelBuilder.automaticChannels(lib, undefined, undefined, undefined, now).concat([
    def('orphan', [RuleChip.series('Orphan Show', 'Orphan Show')], 'inOrder'),
    def('mixed-series', [RuleChip.series('smh', 'Orphan Show')]),
    def('span', [RuleChip.years(1980, 1989)], 'newestFirst'),
    def('classic', [RuleChip.years(1900, 1969), RuleChip.tv]),
    def('added', [RuleChip.addedWithin(365)]),
    def('added-genre', [RuleChip.addedWithin(365), RuleChip.genre('Comedy'), RuleChip.movies]),
    def('theme', [RuleChip.anyGenre(['Comedy', 'Drama']), RuleChip.keywords(['the', 'a'])]),
    def('nothing', [RuleChip.genre('Nope')]),
    def('empty', []),
  ])
  const brute: Channel[] = []
  for (const d of defs) {
    const items = ChannelBuilder.resolve(d, lib, now)
    if (items.length > 0) brute.push(new Channel(d, items))
  }
  assert.ok(brute.some(it => it.id === 'orphan' && it.items.length === 4))
  assert.ok(brute.some(it => it.id === 'mixed-series' && it.items.length === 12))
  assert.ok(!brute.some(it => it.id === 'nothing'))
  assert.ok(brute.some(it => it.id === 'empty' && it.items.length === lib.length))
})

/** Web only: how long the demo lineup takes to schedule three days, so BigInt's cost is on record (printed, not asserted). */
test('timing: three days of the demo lineup', () => {
  const chans = lineup().concat(Channel.scrambled)
  const day = date(2026, 1, 5)
  const t0 = process.hrtime.bigint()
  let slots = 0
  for (const ch of chans) for (let d = 0; d < 3; d++) slots += GuideGenerator.schedule(ch, Time.addDays(day, d)).length
  const ms = Number(process.hrtime.bigint() - t0) / 1e6
  console.log(`# ${chans.length} channels × 3 days = ${slots} slots in ${ms.toFixed(1)} ms`)
  assert.ok(slots > 1000)
})
