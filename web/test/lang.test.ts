/// <reference types="node" />
// core/Lang.ts and core/Time.ts, plus Text.tsx's Clock (it sits on the same Date and Intl ground). `npm test` runs this
// with TZ=America/Los_Angeles and an English locale.
import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  JSONException, compareValues, getInt, getString, intOrNull, boolOrNull, objects, optInt, optString, putOpt, replaceAll,
  stableSort, stringOrNull, strings, toIntOrNull, toUShortOrNull, uuid,
} from '../src/core/Lang'
import { addDays, localYMD, localYear, startOfDay } from '../src/core/Time'
import { Clock } from '../src/app/Text'

test('stableSort keeps equal elements in order and leaves the input alone', () => {
  const xs = Array.from({ length: 1000 }, (_, i) => ({ key: (i * 7919) % 3, i }))
  const sorted = stableSort(xs, (a, b) => a.key - b.key)
  assert.equal(sorted.length, xs.length)
  for (let n = 1; n < sorted.length; n++) {
    const a = sorted[n - 1], b = sorted[n]
    assert.ok(a.key < b.key || (a.key === b.key && a.i < b.i), `position ${n}`)
  }
  assert.equal(xs[0].i, 0)
  assert.deepEqual(stableSort([3, null, 1], compareValues), [null, 1, 3])
})

test('replaceAll replaces every occurrence, like Kotlin String.replace', () => {
  assert.equal(replaceAll('a+b+c', '+', '%20'), 'a%20b%20c')
  assert.equal(replaceAll('SegmentContainer=mp4&x=mp4', 'mp4', 'ts'), 'SegmentContainer=ts&x=ts')
  assert.equal(replaceAll('abc', 'z', 'y'), 'abc')
  assert.equal(replaceAll('ab', '', '-'), '-a-b-')
  assert.equal(replaceAll('', '', '-'), '-')
})

test('strict integer parsing', () => {
  assert.equal(toIntOrNull('12'), 12)
  assert.equal(toIntOrNull('-5'), -5)
  assert.equal(toIntOrNull('+7'), 7)
  assert.equal(toIntOrNull('2147483647'), 2147483647)
  for (const bad of ['12abc', '', ' 1', '1.0', '1e3', '0x10', '2147483648', '-', null]) assert.equal(toIntOrNull(bad), null, String(bad))
  assert.equal(toUShortOrNull('32400'), 32400)
  assert.equal(toUShortOrNull('+1'), 1)
  for (const bad of ['65536', '-1', 'port']) assert.equal(toUShortOrNull(bad), null, bad)
})

test('uuid is an uppercase v4', () => {
  const a = uuid()
  assert.match(a, /^[0-9A-F]{8}-[0-9A-F]{4}-4[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}$/)
  assert.notEqual(a, uuid())
})

test('JSON helpers are as lenient as Android org.json', () => {
  const o = JSON.parse('{"n":"5","f":5.9,"s":5,"b":"TRUE","nul":null,"arr":["a",1],"objs":[{}],"str":"x"}')
  assert.equal(intOrNull(o, 'n'), 5)
  assert.equal(intOrNull(o, 'f'), 5)
  assert.equal(stringOrNull(o, 's'), '5')
  assert.equal(boolOrNull(o, 'b'), true)
  assert.equal(stringOrNull(o, 'nul'), null)
  assert.equal(stringOrNull(o, 'missing'), null)
  assert.equal(stringOrNull(o, 'constructor'), null)   // never Object.prototype
  assert.deepEqual(strings(o, 'arr'), ['a', '1'])
  assert.deepEqual(strings(o, 'missing'), [])
  assert.equal(objects(o.objs).length, 1)
  assert.throws(() => getString(o, 'nul'), JSONException)
  assert.throws(() => getInt(o, 'str'), JSONException)
  assert.throws(() => intOrNull(o, 'arr'), JSONException)
  assert.equal(optInt(o, 'str', 3), 3)
  assert.equal(optString(o, 'nul', 'd'), 'd')
  assert.equal(JSON.stringify(putOpt(putOpt({ a: 1 }, 'b', null), 'c', 0)), '{"a":1,"c":0}')
})

const at = (iso: string) => Date.parse(iso) / 1000

test('local days in Los Angeles: 23 and 25 hours across DST', () => {
  assert.equal(new Intl.DateTimeFormat().resolvedOptions().timeZone, 'America/Los_Angeles')
  const fixtureNow = 1792897200   // parity-fixture.json "now": 2026-10-24 20:00 PDT
  const day = startOfDay(fixtureNow)
  assert.equal(day, at('2026-10-24T07:00:00Z'))
  assert.deepEqual(localYMD(day), { y: 2026, m: 10, d: 24 })
  assert.equal(localYear(fixtureNow), 2026)
  assert.equal(addDays(day, 1) - day, 86400)
  const spring = startOfDay(at('2026-03-08T12:00:00Z'))
  assert.equal(addDays(spring, 1) - spring, 23 * 3600)
  const fall = startOfDay(at('2026-11-01T12:00:00Z'))
  assert.equal(addDays(fall, 1) - fall, 25 * 3600)
  assert.deepEqual(localYMD(addDays(at('2026-12-31T12:00:00Z'), 1)), { y: 2027, m: 1, d: 1 })
  assert.equal(addDays(addDays(day, 3), -3), day)
})

test('a day whose midnight DST skips starts at 01:00 (Cairo)', () => {
  const zone = process.env.TZ
  process.env.TZ = 'Africa/Cairo'
  try {
    // 2024-04-26: clocks go from 00:00 straight to 01:00 EEST (UTC+3), so the day starts at 22:00 UTC the evening before.
    const gapDay = at('2024-04-25T22:00:00Z')
    assert.equal(startOfDay(at('2024-04-26T10:00:00Z')), gapDay)
    assert.equal(addDays(startOfDay(at('2024-04-25T10:00:00Z')), 1), gapDay)
    assert.deepEqual(localYMD(gapDay), { y: 2024, m: 4, d: 26 })
    assert.equal(addDays(gapDay, 1) - gapDay, 23 * 3600)
  } finally {
    process.env.TZ = zone
  }
})

test('Clock reads like the Kotlin patterns', () => {
  const t = 1792897200 + 30 * 60   // Sat 2026-10-24 20:30 PDT
  assert.equal(Clock.time(t), '8:30')
  assert.equal(Clock.ap(t), 'PM')
  assert.equal(Clock.date(t), 'SAT OCT 24')
  assert.equal(Clock.time(at('2026-10-24T07:05:00Z')), '12:05')
  assert.equal(Clock.range(at('2026-10-24T18:30:00Z'), at('2026-10-24T19:00:00Z')), '11:30 AM – 12:00 PM')
  assert.equal(Clock.range(t, t + 1800), '8:30 – 9:00 PM')
})
