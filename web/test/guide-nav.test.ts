/// <reference types="node" />
// The held guide's Left / Right paging, and the guide parked with its window paged ahead (Swift AppModel+Guide and
// AppModel.guidePark, which no Swift or Kotlin unit test can reach: their models need the app). The demo lineup at a
// fixed clock (npm test pins Los Angeles time).
import assert from 'node:assert/strict'
import { after, test } from 'node:test'
import { DemoMediaServer } from '../src/core/DemoServer'
import { ChannelBuilder } from '../src/core/GuideGenerator'
import { AppModel, ProgramKey } from '../src/app/AppModel'
import '../src/app/AppModelGuide'
import '../src/app/AppModelWatch'

const realNow = Date.now
const t = new Date(2026, 9, 28, 22, 40).getTime() / 1000   // 10:40 PM: window 0 is 10:30 PM to 12:00 AM
Date.now = () => t * 1000
after(() => { Date.now = realNow })
const lineup = ChannelBuilder.channelsOf(DemoMediaServer.items)

/** The guide held on channel `i`'s program on now, at window 0, as a tap on its cell leaves it. */
function held(i: number): AppModel {
  const m = new AppModel()
  m.later = () => {}   // no idle or attract timers
  m.channels = lineup
  m.screen = 'guide'
  const ch = m.visibleChannels[i]
  m.remoteHeld = true; m.guideScroll.row = i; m.guideScroll.focusTime = t
  m.focused = m.cellAt(ch, t, m.windowStart(t, 0))
  return m
}

const span = (m: AppModel) => m.program(m.focused!)!.slot

test('Left pages back to where the program starts: back on the program on now, the window is the live one again', () => {
  let paged = 0
  for (let i = 0; i < lineup.length; i++) {
    const m = held(i), start = m.focused!
    let moves = 0
    for (let r = 0; r < 8; r++) {
      const was = m.focused
      m.press('right')
      if (!ProgramKey.eq(was, m.focused)) moves++
      // Right shows the start of the program it focuses
      assert.ok(span(m).start < m.windowStart(t) + AppModel.windowSpan, `${lineup[i].name}: start in view`)
    }
    if (m.windowOffset === 0) continue
    paged++
    for (let l = 0; l < moves; l++) m.press('left')
    assert.equal(m.overlay, null, `${lineup[i].name}: Left opened Options`)
    assert.ok(ProgramKey.eq(m.focused, start), `${lineup[i].name}: back on the program on now`)
    assert.equal(m.windowOffset, 0, `${lineup[i].name}: back in the live window`)
  }
  assert.ok(paged > 0)
})

test('leaving a guide paged ahead brings its focus into the first window, as letting go does', () => {
  let far = 0
  for (const leave of [(m: AppModel) => m.go('settings'), (m: AppModel) => m.guideRelease()]) {
    for (let i = 0; i < lineup.length; i++) {
      const m = held(i)
      for (let r = 0; r < 8 && span(m).start < m.windowStart(t, 0) + AppModel.windowSpan; r++) m.press('right')
      if (span(m).start < m.windowStart(t, 0) + AppModel.windowSpan) continue
      far++
      leave(m)
      const ws0 = m.windowStart(t, 0), s = span(m)
      assert.equal(m.remoteHeld, false)
      assert.equal(m.windowOffset, 0)
      assert.ok(s.start < ws0 + AppModel.windowSpan && s.end > ws0, `${lineup[i].name}: focus in the first window`)
      assert.ok(ProgramKey.eq(m.guideKey(t), m.cellAt(lineup[i], t, ws0)), `${lineup[i].name}: what's on now on that channel`)
    }
  }
  assert.ok(far > 0)
})

test('Up onto the program on now in a window paged ahead: Left brings back the live window, the next Left opens Options', () => {
  const m = held(lineup.findIndex(c => c.number === 18))
  for (const c of ['right', 'up', 'left', 'up'] as const) m.press(c)   // ends on Len Okonkwo's 8 PM film, window at 11:00 PM
  assert.ok(m.windowOffset > 0 && span(m).start <= m.windowStart(t, 0), 'on now, paged ahead')
  m.press('left')
  const first = { overlay: m.overlay, offset: m.windowOffset }
  m.press('left')
  assert.deepEqual(first, { overlay: null, offset: 0 }, 'the first Left brings back the live window')
  assert.equal(m.overlay?.kind, 'options')
})

test('Right pages up to 12 hours ahead and no further', () => {
  let reached = 0
  for (let i = 0; i < lineup.length; i++) {
    const m = held(i)
    for (let r = 0; r < 100; r++) m.press('right')
    assert.ok(m.windowOffset <= AppModel.maxWindowOffset)
    assert.ok(span(m).start < m.windowStart(t, 0) + 12 * 3600, `${lineup[i].name}: starts within 12 hours`)
    if (m.windowOffset > 5) reached++   // past the old 4-hour reach
  }
  assert.ok(reached > 0)
})
