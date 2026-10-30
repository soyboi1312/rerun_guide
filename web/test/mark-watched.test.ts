/// <reference types="node" />
// MarkWatchedTests (Swift and Kotlin): the one request each server's own "mark as watched" makes, for the episode itself
// (not its show), as a node:http stand-in on 127.0.0.1 receives it. Then the rule in `watchTick` (Swift AppModel+Watch,
// which no Swift or Kotlin unit test can reach: their models need the app): a program started from its beginning is
// marked once at 90%, and nothing else ever is. The demo lineup at a fixed clock, as in guide-nav.test.ts.
import assert from 'node:assert/strict'
import http from 'node:http'
import { AddressInfo } from 'node:net'
import { after, test } from 'node:test'
import { DemoMediaServer } from '../src/core/DemoServer'
import { ChannelBuilder } from '../src/core/GuideGenerator'
import { JellyfinClient } from '../src/core/JellyfinClient'
import { MediaItem, ServerKind } from '../src/core/Models'
import { PlexClient } from '../src/core/PlexClient'
import { AppModel } from '../src/app/AppModel'
import '../src/app/AppModelWatch'

const episode = new MediaItem({ id: 'ep7', kind: 'episode', title: 'Pilot', seriesTitle: 'Show', seriesID: 'show3', runtime: 1320 })

/** The requests a stand-in answering 200 `{}` received while `block` ran against its address. */
async function received(block: (base: string) => Promise<void>): Promise<http.IncomingMessage[]> {
  const got: http.IncomingMessage[] = []
  const s = http.createServer((req, res) => { got.push(req); res.end('{}') })
  await new Promise<void>(resolve => s.listen(0, '127.0.0.1', resolve))
  try {
    await block(`http://127.0.0.1:${(s.address() as AddressInfo).port}`)
  } finally {
    s.close(); s.closeAllConnections()
  }
  return got
}

/** How many times `block` (and anything it left running) called fetch. */
async function fetches(block: () => Promise<void>): Promise<number> {
  const real = globalThis.fetch
  let n = 0
  globalThis.fetch = (input, init) => { n++; return real(input, init) }
  try {
    await block()
    await new Promise(resolve => setTimeout(resolve, 0))
  } finally {
    globalThis.fetch = real
  }
  return n
}

test('Plex scrobbles the rating key', async () => {
  const got = await received(base => new PlexClient(base, 'tok', 'dev').markWatched(episode))
  assert.equal(got.length, 1)
  const r = got[0]
  assert.equal(r.method, 'PUT')
  assert.equal(r.url, '/:/scrobble?identifier=com.plexapp.plugins.library&key=ep7')
  assert.equal(r.headers['x-plex-token'], 'tok')
  assert.equal(r.headers['x-plex-client-identifier'], 'dev')
})

test('Jellyfin and Emby post the played item', async () => {
  for (const kind of ['jellyfin', 'emby'] as const) {
    const got = await received(base => new JellyfinClient(base, 'tok', 'u1', 'dev', kind).markWatched(episode))
    assert.equal(got.length, 1, kind)
    const r = got[0]
    assert.equal(r.method, 'POST', kind)
    assert.equal(r.url, '/Users/u1/PlayedItems/ep7', kind)
    assert.equal(r.headers['x-emby-token'], 'tok', kind)
    for (const h of ['authorization', 'x-emby-authorization']) assert.ok(String(r.headers[h]).indexOf('Token="tok"') >= 0, `${kind} ${h}`)
  }
})

test('the demo asks nothing', async () => {
  assert.equal(await fetches(() => new DemoMediaServer().markWatched(episode)), 0)
})

// MARK: - The rule

const realNow = Date.now
after(() => { Date.now = realNow })
/** tune, skip and togglePause read the clock (epoch seconds); watchTick is handed it. */
const setClock = (at: number) => { Date.now = () => at * 1000 }
const lineup = ChannelBuilder.channelsOf(DemoMediaServer.items)
const T = new Date(2026, 9, 28, 22, 40).getTime() / 1000

/** A Plex server as far as the model can tell, that records what it is asked to mark. */
class Spy extends DemoMediaServer {
  readonly kind: ServerKind = 'plex'
  readonly marked: string[] = []
  async markWatched(item: MediaItem): Promise<void> { this.marked.push(item.id) }
}

/** A model on the demo lineup with a spy for its server, and the program `s` on air at T on a channel that isn't 99.
 *  `t`, a minute into it, is when the tests start it over. */
function setup() {
  const m = new AppModel()
  m.later = () => {}   // no banner, toast or seek timers
  m.channels = lineup
  const spy = new Spy()
  m.server = spy
  const ch = lineup.find(c => !c.isScrambled)!
  const s = m.slotOnAir(ch, T)!
  return { m, spy, ch, s, r: s.item.runtime, t: s.start + 60 }
}

test('from the beginning: marked once at 90%, not before', () => {
  const { m, spy, ch, s, r, t } = setup()
  setClock(t); m.tune(ch.id, 'start', s.start)
  const at = t + Math.ceil(0.9 * r)
  m.watchTick(at - 1)
  assert.deepEqual(spy.marked, [])
  m.watchTick(at)
  assert.deepEqual(spy.marked, [s.item.id])
  m.watchTick(at + 5)
  assert.deepEqual(spy.marked, [s.item.id])
})

test('joining live never marks', () => {
  const { m, spy, ch, s, r } = setup()
  setClock(s.start + 0.5 * r); m.tune(ch.id, 'live')
  m.watchTick(s.start + 0.95 * r)
  assert.deepEqual(spy.marked, [])
  m.playback = { ...m.playback!, fromStart: true }   // control: only the flag held it back
  m.watchTick(s.start + 0.95 * r)
  assert.deepEqual(spy.marked, [s.item.id])
})

test('a paused live program (time-shifted, not from the beginning) never marks', () => {
  const { m, spy, ch, s, r } = setup()
  setClock(s.start + 0.5 * r); m.tune(ch.id, 'live'); m.togglePause()
  setClock(s.start + 0.6 * r); m.togglePause()
  const at = s.start + 1.05 * r   // 0.95 r into the program
  m.watchTick(at)
  assert.deepEqual(spy.marked, [])
  m.playback = { ...m.playback!, fromStart: true }   // control: only the flag held it back
  m.watchTick(at)
  assert.deepEqual(spy.marked, [s.item.id])
})

test('the break never marks', () => {
  const { m, spy, ch, s, r, t } = setup()
  setClock(t); m.tune(ch.id, 'start', s.start)
  assert.ok(m.watchInfo(t + r + 1)!.isBreak)
  m.watchTick(t + r + 1)
  assert.deepEqual(spy.marked, [])
})

test('no server at 90%: marked on a later tick once it is back', () => {
  const { m, spy, ch, s, r, t } = setup()
  setClock(t); m.tune(ch.id, 'start', s.start)
  m.server = null
  m.watchTick(t + 0.92 * r)
  assert.deepEqual(spy.marked, [])
  m.server = spy
  m.watchTick(t + 0.93 * r)
  assert.deepEqual(spy.marked, [s.item.id])
})

test('the demo (Sample station) never calls out', async () => {
  const { m, ch, s, r, t } = setup()
  m.server = new DemoMediaServer()
  setClock(t); m.tune(ch.id, 'start', s.start)
  assert.equal(await fetches(async () => m.watchTick(t + 0.95 * r)), 0)
})

test('the 10 s skips keep it: still marked once', () => {
  const { m, spy, ch, s, r, t } = setup()
  setClock(t); m.tune(ch.id, 'start', s.start); m.skip(10)
  const at = t + Math.ceil(0.9 * r) - 10
  m.watchTick(at); m.watchTick(at + 1)
  assert.deepEqual(spy.marked, [s.item.id])
})

test('no server across 90%, back in the break of the same playback: marked once', () => {
  const { m, spy, ch, s, r, t } = setup()
  setClock(t); m.tune(ch.id, 'start', s.start)
  m.server = null
  m.watchTick(t + 0.95 * r)
  m.server = spy
  assert.ok(m.watchInfo(t + r + 60)!.isBreak)
  m.watchTick(t + r + 60); m.watchTick(t + r + 61)
  assert.deepEqual(spy.marked, [s.item.id])
})

test('started over, then Home: held, and still marked at 90% after the way back', async () => {
  const { m, spy, ch, s, r, t } = setup()
  setClock(t); m.tune(ch.id, 'start', s.start)
  setClock(t + 60); await m.enteredBackground()
  setClock(t + 600); m.returnedToForeground()
  m.watchTick(t + 600 - 60 + Math.ceil(0.9 * r))
  assert.deepEqual(spy.marked, [s.item.id])
})

/** `setup`, then `s` started over a minute in and Right pressed until caught up: live, still from the beginning. */
function caughtUp() {
  const x = setup()
  setClock(x.t); x.m.tune(x.ch.id, 'start', x.s.start)
  for (let i = 0; i < 10 && x.m.playback!.mode === 'start'; i++) x.m.skip(10)   // a minute behind: six presses
  assert.equal(x.m.playback!.mode, 'live'); assert.ok(x.m.playback!.fromStart)
  return x
}

test('caught up to live, then away past its end: the next program, joined midway, never marks', () => {
  for (const back of [(m: AppModel) => { m.togglePause(); m.togglePause() }, (m: AppModel) => m.skip(-10)]) {
    const { m, spy, ch, s } = caughtUp()
    const b = m.slotOnAir(ch, s.end)!, rb = b.item.runtime
    const at = b.start + 0.3 * rb   // no tick ran since: back 30% into the next program
    setClock(at); back(m)
    m.watchTick(at + 0.65 * rb)   // 95% into it (10 s less after the skip back)
    assert.deepEqual(spy.marked, [])
    m.playback = { ...m.playback!, fromStart: true }   // control: only the flag held it back
    m.watchTick(at + 0.65 * rb)
    assert.deepEqual(spy.marked, [b.item.id])
  }
})

test('caught up to live, then Home: never marked, hidden or back', async () => {
  const { m, spy, s, r } = caughtUp()
  await m.enteredBackground()
  const at = s.start + Math.ceil(0.9 * r)
  m.watchTick(at)   // a ticker that runs while hidden
  m.returnedToForeground(); m.watchTick(at + 1)
  assert.deepEqual(spy.marked, [])
})

test('caught up to live, no server across 90%, then Home: marked once back in its break', async () => {
  const { m, spy, s, r } = caughtUp()
  m.server = null
  m.watchTick(s.start + 0.95 * r)   // seen to 90% (picture in picture on a plain-http link), not reported
  setClock(s.start + r + 30); await m.enteredBackground()   // the window closes at the break, the app still away
  m.server = spy; m.returnedToForeground()
  m.watchTick(s.start + r + 31)
  assert.deepEqual(spy.marked, [s.item.id])
})
