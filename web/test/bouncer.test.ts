/// <reference types="node" />
// BouncerTests.kt.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { Bouncer } from '../src/core/Bouncer'

/**
 * Twenty simulated minutes at 60 Hz from several starts and speed ratios, including ones a plain bounce would never
 * corner on: the logo stays on the stage and never goes 120 s without a corner.
 */
test('a corner at least every two minutes', () => {
  const stage: [number, number] = [1920, 1080]
  const logo: [number, number] = [420, 300]
  const cases: [[number, number], [number, number]][] = [
    [[110, 80], [0, 0]], [[110, 80], [700, 333]], [[100, 100], [10, 500]],   // equal speeds on a 1500×780 box: no natural corners
    [[150, 61], [1200, 90]], [[90, 130], [333, 777]], [[110, 80], [1500, 780]],
  ]
  for (const [speed, start] of cases) {
    const b = new Bouncer(stage, logo, speed, start)
    let worstGap = 0
    let last = 0
    for (let i = 0; i < 20 * 60 * 60; i++) {
      b.step(1 / 60)
      assert.ok(b.x >= 0 && b.x <= stage[0] - logo[0] && b.y >= 0 && b.y <= stage[1] - logo[1], `off the stage at t=${b.time}`)
      if (b.hitCorner) { worstGap = Math.max(worstGap, b.time - last); last = b.time }
    }
    worstGap = Math.max(worstGap, b.time - last)
    assert.ok(worstGap <= 120, `speed ${speed} start ${start}: ${worstGap}s between corners`)
    assert.ok(b.corners >= 10, `speed ${speed} start ${start}: only ${b.corners} corners in 20 min`)
  }
})

/** Cruising speed is restored after a steered approach, so the logo doesn't stay slowed for good. */
test('speed restored after a corner', () => {
  const b = new Bouncer([1920, 1080], [420, 300], [100, 100], [0, 0])   // equal speeds: no natural corners within the deadline
  let sawSteering = false
  for (let i = 0; i < 10 * 60 * 60; i++) {
    b.step(1 / 60)
    if (Math.abs(b.vy) < 99) sawSteering = true
    if (b.hitCorner) {
      assert.ok(Math.abs(Math.abs(b.vy) - 100) <= 0.001)
      assert.ok(Math.abs(Math.abs(b.vx) - 100) <= 0.001)
    }
  }
  assert.ok(sawSteering, 'the deadline never had to steer: the test speeds corner naturally, pick others')
})
