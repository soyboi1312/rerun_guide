/// <reference types="node" />
// TrailerTriesTests.kt.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { MediaItem } from '../src/core/Models'
import { TrailerTries } from '../src/core/TrailerTries'

const films = ['a', 'b', 'c'].map(id => new MediaItem({ id, kind: 'movie', title: id, runtime: 90 * 60 }))
const t0 = 1_800_000_000

/** Each candidate once, in order, then none: the station card. */
test('each candidate once', () => {
  let t = new TrailerTries()
  const order: string[] = []
  for (;;) {
    const m = t.next(films, t0)
    if (m == null) break
    order.push(m.id); t = t.adding(m, t0)
  }
  assert.deepEqual(order, ['a', 'b', 'c'])
  assert.deepEqual(t.tried, ['a', 'b', 'c'])
  assert.equal(new TrailerTries().next([], t0), null)
})

/** The clock starts at the first try: a failure 19 s in still gets the next trailer, one 20 s in gets none. */
test('budget', () => {
  const t = new TrailerTries().adding(films[0], t0)
  assert.equal(t.since, t0)
  assert.equal(t.next(films, t0 + 19)?.id, 'b')
  const t2 = t.adding(films[1], t0 + 19)
  assert.equal(t2.since, t0)   // later tries keep the first one's clock
  assert.equal(t2.next(films, t0 + TrailerTries.budget), null)
  assert.equal(t2.restarted().next(films, t0 + 90)?.id, 'c')   // one that had played: a new clock, the same tries
  assert.equal(TrailerTries.startTimeout, 10)
  assert.equal(TrailerTries.budget, 20)
})
