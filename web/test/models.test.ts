/// <reference types="node" />
// core/Models.ts: the Swift parity values it owns (FNV-1a), the persisted JSON shapes, Swift string order, and
// TrackSelectionTests.kt.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import http from 'node:http'
import { AddressInfo } from 'node:net'
import { test } from 'node:test'
import {
  Channel, ChannelDefinition, LibrarySection, MediaItem, MediaTrack, PlayOrder, RuleChip, Slot, StableHash, TrackSelection,
  isO3Language, swiftCompare,
} from '../src/core/Models'
import { HTTP, URLRequest, percentEncode, queryString } from '../src/core/MediaServer'

// Read in place, never copied (README: android/core's ParityTest uses the same file). Same depth from test/ and .test-build/.
const fixture = JSON.parse(readFileSync(new URL('../../android/core/src/test/resources/parity-fixture.json', import.meta.url), 'utf8'))

test('FNV-1a matches the Swift fixture', () => {
  const cases: { s: string; h: string }[] = fixture.fnv1a
  assert.equal(cases.length, 5)
  for (const c of cases) assert.equal(StableHash.fnv1a(c.s).toString(), c.h, JSON.stringify(c.s))
  const def = new ChannelDefinition({ id: 'genre:Comedy:tv', number: 2, name: 'N', tag: 'T', rule: [] })
  assert.equal(def.hue, Number(BigInt(cases[2].h) % BigInt(360)))
})

test('swiftCompare is NFC, then code point order', () => {
  // U+FFFD < U+1F600 by code point; JS `<` compares UTF-16 units and says the opposite (0xD83D < 0xFFFD).
  assert.equal(swiftCompare('�', '\u{1F600}'), -1)
  assert.ok('\u{1F600}' < '�')
  assert.equal(swiftCompare('\u{1F600}', '�'), 1)
  assert.equal(swiftCompare('é', 'é'), 0)
  assert.equal(swiftCompare('a', 'ab'), -1)
  assert.equal(swiftCompare('B', 'a'), -1)
  assert.equal(swiftCompare('same', 'same'), 0)
})

test('persisted JSON round trips, missing keys take the defaults', () => {
  const def = new ChannelDefinition({
    id: 'c', number: 7, name: 'N', tag: 'T', order: 'newestFirst', trailerBreaks: false, isCustom: true, tuneMode: 'start',
    rule: [RuleChip.genre('G'), RuleChip.years(1900, 1969), RuleChip.director('D'), RuleChip.actor('A'), RuleChip.series('s', 'S'),
      RuleChip.year(1985), RuleChip.movies, RuleChip.tv, RuleChip.addedWithin(60), RuleChip.anyGenre(['x', 'y']), RuleChip.keywords(['k'])],
  })
  const json = JSON.stringify(def.toJson())
  assert.deepEqual(ChannelDefinition.fromJson(JSON.parse(json)), def)
  assert.equal(json, '{"id":"c","number":7,"name":"N","tag":"T","rule":[{"genre":{"_0":"G"}},{"years":{"_0":1900,"_1":1969}},' +
    '{"director":{"_0":"D"}},{"actor":{"_0":"A"}},{"series":{"id":"s","title":"S"}},{"year":{"_0":1985}},{"movies":{}},{"tv":{}},' +
    '{"addedWithin":{"days":60}},{"anyGenre":{"_0":["x","y"]}},{"keywords":{"_0":["k"]}}],"order":"Newest first",' +
    '"trailerBreaks":false,"isCustom":true,"tuneMode":"start"}')
  assert.deepEqual(ChannelDefinition.fromJson({ id: 'c' }), new ChannelDefinition({ id: 'c', number: 0, name: '', tag: '', rule: [] }))
  assert.throws(() => RuleChip.fromJson({ genre: { _0: 'a' }, tv: {} }))

  const full = new MediaItem({
    id: 'e1', kind: 'episode', title: 'Pilot', seriesTitle: 'Show', seriesID: 's1', season: 1, episode: 2, year: 1986,
    genres: ['Comedy'], runtime: 1320.5, artworkPath: '/a', backdropPath: '/b', overview: 'O', contentRating: 'TV-PG',
    directors: ['D'], actors: ['A', 'B'], libraryID: 'l', addedAt: 1792897200.25,
  })
  for (const m of [full, ...Channel.scrambled.items]) assert.deepEqual(MediaItem.fromJson(JSON.parse(JSON.stringify(m.toJson()))), m)
  assert.equal(JSON.stringify(Channel.scrambled.items[0].toJson()), '{"id":"scrambled-0","kind":"movie","title":"Late Night Feature",' +
    '"year":1986,"genres":[],"runtime":3600,"overview":"Nothing comes in clearly on 99.","contentRating":"NR",' +
    '"directors":[],"actors":[],"watched":false}')

  const t = new TrackSelection('2', TrackSelection.Subtitle.track('5'))
  assert.deepEqual(TrackSelection.fromJson(JSON.parse(JSON.stringify(t.toJson()))), t)
  assert.equal(JSON.stringify(new TrackSelection().toJson()), '{"subtitle":"auto"}')
  assert.deepEqual(TrackSelection.fromJson(new TrackSelection().toJson()), new TrackSelection())
  assert.ok(new TrackSelection().isDefault)

  const s: LibrarySection = { id: '1', title: 'Movies', kind: 'movies', itemCount: 3 }
  assert.deepEqual(LibrarySection.fromJson(JSON.parse(JSON.stringify(LibrarySection.toJson(s)))), s)
})

test('MediaItem text, Slot and channel 99', () => {
  const ep = new MediaItem({ id: 'e', kind: 'episode', title: 'Pilot', seriesTitle: 'Show', season: 5, episode: 9, year: 1986, runtime: 1500 })
  assert.equal(ep.displayTitle, 'Show')
  assert.equal(ep.infoLine, '“Pilot” · S5 E9 · 1986')
  const film = new MediaItem({ id: 'm', kind: 'movie', title: 'Film', year: 1981, contentRating: 'PG', runtime: 6959 })
  assert.equal(film.infoLine, '1981 · PG · 115 min')
  assert.equal(film.copy({ year: null }).subtitle, '')
  assert.equal(MediaItem.title('Watchmen', ' Directors Cut '), 'Watchmen (Directors Cut)')
  assert.equal(MediaItem.edition('Watchmen (2009) {edition-Directors Cut}.mkv'), 'Directors Cut')
  const slot = new Slot(film, 1800.9, 9000)
  assert.equal(slot.id, 'm@1800')
  assert.equal(slot.itemEnd, 1800.9 + 6959)
  const ch = Channel.scrambled
  assert.equal(ch, Channel.scrambled)
  assert.deepEqual([ch.id, ch.number, ch.name, ch.tag, ch.trailerBreaks, ch.order, ch.isMovies, ch.isScrambled, ch.items.length],
    ['scrambled', 99, 'Channel 99', 'Scrambled', false, 'shuffle', true, true, 6])
  assert.equal(PlayOrder.from('In order'), 'inOrder')
  assert.equal(RuleChip.label(RuleChip.years(1900, 1969)), 'Before 1970')
  assert.equal(RuleChip.label(RuleChip.keywords(['a', 'b', 'c', 'd', 'e'])), 'Mentions: a, b, c, d…')
})

test('language names and codes', () => {
  assert.equal(MediaTrack.languageName('spa'), 'Spanish')
  assert.equal(MediaTrack.languageName('fre'), 'French')
  assert.equal(MediaTrack.languageName('NOB'), 'Norwegian')
  assert.equal(MediaTrack.languageName('und'), null)
  assert.equal(MediaTrack.languageName('xyz'), 'xyz')
  assert.equal(isO3Language('en-US'), 'eng')
  assert.equal(isO3Language('nb'), 'nob')
})

test('percent-encoding as Kotlin URLEncoder + fixes', () => {
  assert.equal(percentEncode("a b+c*~'é"), 'a%20b%2Bc%2A~%27%C3%A9')
  assert.equal(percentEncode('x\ud800'), 'x%3F')
  assert.equal(queryString([['k', 'v w'], ['X-Plex-Token', 'a/b']]), '?k=v%20w&X-Plex-Token=a%2Fb')
  assert.equal(queryString([]), '')
})

test('the timeout is a wait for the next bytes, not for the whole body (HttpURLConnection read timeout)', async () => {
  // Sends "[1,2,3,4]" a byte every 100 ms after a 100 ms wait, or stalls 1.5 s before its fourth.
  const s = http.createServer((req, res) => {
    const gaps = req.url === '/stall' ? [100, 100, 100, 1500] : [100]
    const body = '[1,2,3,4]'.split('')
    let n = 0
    const send = () => n < body.length ? setTimeout(() => { res.write(body[n++]); send() }, gaps[Math.min(n, gaps.length - 1)]) : res.end()
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.flushHeaders()
    send()
  })
  await new Promise<void>(resolve => s.listen(0, '127.0.0.1', resolve))
  const base = `http://127.0.0.1:${(s.address() as AddressInfo).port}`
  try {
    // ~1 s in all, never 500 ms without a byte.
    assert.deepEqual(await HTTP.json(new URLRequest(`${base}/trickle`, 0.5), o => o), [1, 2, 3, 4])
    await assert.rejects(HTTP.data(new URLRequest(`${base}/stall`, 0.5)), (e: Error) => e.name === 'TimeoutError')
  } finally {
    s.closeAllConnections()
    s.close()
  }
})

// TrackSelectionTests.kt (all but testOnlyAnHlsRenditionRidesInThePlaylist, which is JellyfinClient's).
const audio = (id: string, language: string | null, title: string): MediaTrack => ({ id, kind: 'audio', language, title })
const sub = (id: string, language: string | null, title: string, isDefault = false, isForced = false): MediaTrack =>
  ({ id, kind: 'subtitle', language, title, isDefault, isForced })
const tracks = [
  audio('1', 'eng', 'English'),
  sub('2', 'fre', 'French (Forced SUBRIP)', false, true),
  sub('3', 'eng', 'English (Default Forced SUBRIP)', true, true),
  sub('4', 'eng', 'English (SUBRIP)'),
]
const pick = (t: MediaTrack[], server: 'plex' | 'emby' | 'jellyfin' | null, mode = 'forced', lang = 'eng') =>
  TrackSelection.preferred(t, server, null, mode, lang).subtitle
const track = (id: string) => TrackSelection.Subtitle.track(id)
const off = TrackSelection.Subtitle.off
const auto = TrackSelection.Subtitle.auto

test('forced only picks the forced track on Emby and Jellyfin', () => {
  assert.deepEqual(pick(tracks, 'emby'), track('3'))
  assert.deepEqual(pick(tracks, 'jellyfin'), track('3'))
  assert.deepEqual(pick(tracks, 'emby', 'forced', 'spa'), track('3'))   // English audio
  assert.deepEqual(TrackSelection.preferred(tracks, 'emby', null, 'forced', 'spa', 'fra').subtitle, track('2'))
  assert.deepEqual(pick([tracks[1], tracks[2]], 'emby', 'forced', 'spa'), off)   // no audio track to go by
  assert.deepEqual(pick([audio('1', 'spa', 'Spanish'), ...tracks.slice(1)], 'emby', 'forced', 'spa'), off)
  assert.deepEqual(pick([tracks[0], tracks[3]], 'emby'), off)   // no forced track: none
})

test('bibliographic and terminology codes match', () => {
  assert.deepEqual(pick(tracks, 'emby', 'forced', 'fra'), track('2'))
  const ger = [audio('1', 'ger', 'Deutsch'), sub('2', 'ger', 'Deutsch'), sub('3', 'eng', 'English')]
  assert.deepEqual(pick(ger, 'emby', 'on', 'deu'), track('2'))
  assert.equal(TrackSelection.preferred(ger, 'emby', 'deu', 'off', 'eng').audioID, '1')
  assert.ok(MediaTrack.sameLanguage('CHI', 'zho'))
  assert.ok(MediaTrack.sameLanguage('nob', 'nor'))
  assert.ok(MediaTrack.sameLanguage('NNO', 'nor'))
  assert.ok(!MediaTrack.sameLanguage(null, null))
  assert.ok(!MediaTrack.sameLanguage('und', 'und'))
})

test('a forced track without a language never counts', () => {
  assert.deepEqual(pick([tracks[0], sub('2', null, 'Forced', false, true)], 'emby'), off)
})

test('forced only stays with the server on Plex', () => {
  assert.deepEqual(pick(tracks, 'plex'), auto)
  assert.deepEqual(pick(tracks, null), auto)
})

test('on and off', () => {
  assert.deepEqual(pick(tracks, 'emby', 'on'), track('4'))
  assert.deepEqual(pick(tracks, 'plex', 'on'), track('4'))
  assert.deepEqual(pick(tracks, 'emby', 'off'), off)
  assert.deepEqual(pick(tracks.slice(0, 1), 'emby', 'on'), auto)
  assert.equal(TrackSelection.preferred(tracks, 'emby', 'eng', 'off', 'eng').audioID, '1')
})
