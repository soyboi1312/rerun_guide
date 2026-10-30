/// <reference types="node" />
// ParityTest.kt: the Swift Core's output for the demo library (parity-fixture.json, written by tools/fixturegen against
// RerunGuideCore). The web port must agree value for value, so a channel airs the same thing at the same minute on an
// Apple TV, a Google TV and an LG or Samsung TV.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { Bouncer } from '../src/core/Bouncer'
import { DemoMediaServer } from '../src/core/DemoServer'
import { ChannelBuilder, GuideGenerator, SeededGenerator, shuffled } from '../src/core/GuideGenerator'
import { toIntOrNull } from '../src/core/Lang'
import { Channel, ChannelDefinition, LibrarySection, MediaItem, PlayOrder, RuleChip, StableHash, TrackSelection } from '../src/core/Models'
import { SpotRotation } from '../src/core/SpotRotation'
import * as Time from '../src/core/Time'

// Read in place, never copied. Same depth from test/ and .test-build/.
const f = JSON.parse(readFileSync(new URL('../../android/core/src/test/resources/parity-fixture.json', import.meta.url), 'utf8'))
process.env.TZ = f.timeZone   // npm test already runs in Los Angeles; this keeps a bare `node --test` honest
const now: number = f.now

/**
 * The demo's addedAt is relative to the wall clock when the library loads; the fixture ran at `now` (give or take
 * minutes), so pin it there or "Just Added" drifts as the days pass. Every third film is watched (m3, m6, ...), as in
 * fixturegen, so Unwatched is checked too.
 */
const lib = DemoMediaServer.items.map(m => {
  if (m.kind !== 'movie') return m
  const n = toIntOrNull(m.id.slice(1))!
  return m.copy({ addedAt: now - n * 6 * 86_400, watched: n % 3 === 0 })
})

type Row = { [k: string]: any }
const slotRows = (slots: readonly { item: MediaItem; start: number; end: number }[]) => slots.map(s => [s.item.id, s.start, s.end])

test('seeded generator: SplitMix64, Lemire, shuffled', () => {
  const g = new SeededGenerator('parity|2026-10-24')
  assert.deepEqual(Array.from({ length: 8 }, () => g.next().toString()), f.seeded)
  const g2 = new SeededGenerator('shuffle-check')
  assert.deepEqual(shuffled(Array.from({ length: 20 }, (_, i) => i), g2), f.shuffle20)
  const g3 = new SeededGenerator('bounded')
  assert.deepEqual(Array.from({ length: 12 }, (_, i) => g3.nextInt((i + 3) * 7)), f.bounded)
})

test('FNV-1a', () => {
  assert.equal(f.fnv1a.length, 5)
  for (const c of f.fnv1a as Row[]) assert.equal(StableHash.fnv1a(c.s).toString(), c.h, c.s)
})

test('demo items', () => {
  const want: Row[] = f.demoItems
  assert.equal(lib.length, want.length)
  want.forEach((w, i) => {
    const m = lib[i]
    assert.deepEqual([m.id, m.kind, m.title, m.runtime, m.year, m.genres, m.seriesID, m.watched],
      [w.id, w.kind, w.title, w.runtime, w.year ?? null, w.genres, w.seriesID ?? null, w.watched])
  })
})

test('automatic channels', () => {
  const want: Row[] = f.automatic
  const defs = ChannelBuilder.automaticChannels(lib, undefined, undefined, undefined, now)
  assert.deepEqual(defs.map(d => d.id), want.map(w => w.id))
  want.forEach((w, i) => {
    const d = defs[i]
    assert.deepEqual([d.number, d.name, d.tag, d.rule.map(RuleChip.label), PlayOrder.rawValue(d.order)],
      [w.number, w.name, w.tag, w.rule, w.order], w.id)
  })
})

test('channels and three days of slots', () => {
  const want: Row[] = f.channels
  const chans = ChannelBuilder.channelsFrom(ChannelBuilder.automaticChannels(lib, undefined, undefined, undefined, now), lib, now)
    .concat(Channel.scrambled)
  assert.deepEqual(chans.map(c => c.id), want.map(w => w.id))
  const today = Time.startOfDay(now)
  let slotCount = 0
  want.forEach((w, c) => {
    const ch = chans[c]
    assert.deepEqual(ch.items.map(it => it.id), w.items, ch.id)
    assert.equal(w.days.length, 3)
    ;(w.days as Row[]).forEach((wd, d) => {
      const day = Time.addDays(today, d)
      assert.equal(day, wd.day)
      const got = GuideGenerator.schedule(ch, day)
      assert.deepEqual(slotRows(got), (wd.slots as Row[]).map(s => [s.item, s.start, s.end]), `${ch.id} day ${d}`)
      slotCount += got.length
    })
  })
  assert.ok(slotCount > 1000)
})

test('packed short programs, in order and shuffled', () => {
  // Short programs sharing a half hour, with fractional runtimes as servers report them.
  const shorts = [130.7, 245.2, 480.4, 540.9, 701.0, 150.0, 453.3, 720.0, 780.0, 1320.0].map((r, i) => new MediaItem({
    id: `s${i}`, kind: 'episode', title: `S${i}`, seriesTitle: 'Shorts', seriesID: 'shorts', season: 1, episode: i + 1, runtime: r,
  }))
  const chans = [Channel.make('shorts', 2, 'Shorts', shorts, undefined, 'inOrder'), Channel.make('shorts-mix', 3, 'Mix', shorts, undefined, 'shuffle')]
  const today = Time.startOfDay(now)
  chans.forEach((ch, c) => {
    for (let d = 0; d < 3; d++) {
      const got = GuideGenerator.schedule(ch, Time.addDays(today, d))
      assert.deepEqual(slotRows(got), f.packed[c][d], `${ch.id} day ${d}`)
    }
  })
})

test('bouncer, exact doubles', () => {
  const want: number[][] = f.bouncer
  const b = new Bouncer([1920, 1080], [400, 400], undefined, [300, 200])
  const got: number[][] = []
  for (let i = 1; i <= 60 * 60 * 3; i++) {
    b.step(1 / 60)
    if (i % 600 === 0) got.push([b.time, b.x, b.y, b.vx, b.vy, b.corners])
  }
  assert.equal(got.length, want.length)
  got.forEach((g, i) => { for (let k = 0; k < 6; k++) assert.ok(g[k] === want[i][k], `sample ${i} field ${k}: ${g[k]} vs ${want[i][k]}`) })
})

test('spot rotation', () => {
  const rot = new SpotRotation()
  assert.deepEqual([...['a', 'b', 'a', 'c', 'd', 'd'], ...Array.from({ length: 30 }, (_, i) => `n${i + 1}`)].map(id => rot.next(id)), f.spotRotation)
})

/** Not from the fixture: what the app persists comes back equal (missing keys → defaults). PlexAddress is PlexClient's test. */
test('persisted JSON round trip', () => {
  const rt = (o: object) => JSON.parse(JSON.stringify(o))
  const def = new ChannelDefinition({
    id: 'c', number: 7, name: 'N', tag: 'T', order: 'newestFirst', trailerBreaks: false, isCustom: true, tuneMode: 'start',
    rule: [RuleChip.genre('G'), RuleChip.years(1900, 1969), RuleChip.director('D'), RuleChip.actor('A'), RuleChip.series('s', 'S'),
      RuleChip.year(1985), RuleChip.movies, RuleChip.tv, RuleChip.addedWithin(60), RuleChip.anyGenre(['x', 'y']), RuleChip.keywords(['k'])],
  })
  assert.deepEqual(ChannelDefinition.fromJson(rt(def.toJson())), def)
  assert.deepEqual(ChannelDefinition.fromJson({ id: 'c' }), new ChannelDefinition({ id: 'c', number: 0, name: '', tag: '', rule: [] }))
  for (const m of lib.concat(Channel.scrambled.items)) assert.deepEqual(MediaItem.fromJson(rt(m.toJson())), m)
  const t = new TrackSelection('2', TrackSelection.Subtitle.track('5'))
  assert.deepEqual(TrackSelection.fromJson(rt(t.toJson())), t)
  assert.deepEqual(TrackSelection.fromJson(rt(new TrackSelection().toJson())), new TrackSelection())
  const s: LibrarySection = { id: '1', title: 'Movies', kind: 'movies', itemCount: 3 }
  assert.deepEqual(LibrarySection.fromJson(rt(LibrarySection.toJson(s))), s)
})
