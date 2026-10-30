/// <reference types="node" />
// SpotRotationTests.kt.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { SpotRotation } from '../src/core/SpotRotation'

const n = SpotRotation.every
const range = (from: number, to: number) => Array.from({ length: to - from + 1 }, (_, i) => from + i)

/**
 * Breaks 15, 30 and 45 get the spot; seeing the same break again doesn't count, and the count survives the round trip
 * through the persisted JSON.
 */
test('one break in 15', () => {
  assert.equal(n, 15)
  const r = new SpotRotation()
  const hits: number[] = []
  for (const i of range(1, 3 * n)) {
    if (r.next(`ch|${i}`)) hits.push(i)
    assert.equal(r.next(`ch|${i}`), false, `a retune into break ${i} must not count or replay`)
  }
  assert.deepEqual(hits, [n, 2 * n, 3 * n])
  const json = JSON.stringify(r.toJson())
  assert.equal(json, `{"breaks":${3 * n},"lastBreak":"ch|${3 * n}"}`)
  const restored = SpotRotation.fromJson(JSON.parse(json))
  assert.deepEqual(restored, r)
  assert.deepEqual(range(3 * n + 1, 4 * n).filter(i => restored.next(`ch|${i}`)), [4 * n])
  assert.deepEqual(SpotRotation.fromJson({}), new SpotRotation())
})

/** Two spots take turns, one per spot break: 15 → first, 30 → second, 45 → first. */
test('spots take turns', () => {
  const r = new SpotRotation()
  const played: number[] = []
  for (const i of range(1, 3 * n)) if (r.next(`ch|${i}`)) played.push(r.pick(2))
  assert.deepEqual(played, [0, 1, 0])
  assert.equal(new SpotRotation().pick(2), 0)   // a forced spot (the Apple app's -spotOverride debug flag), before any break counted
})
