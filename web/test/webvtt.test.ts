/// <reference types="node" />
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { cueText, parseWebVTT } from '../src/core/WebVTT'

test('WebVTT cues: times, text without tags, header and notes skipped', () => {
  const vtt = 'WEBVTT\r\n\r\nNOTE made by Emby\r\n\r\n1\r\n00:00:01.500 --> 00:00:04.000 line:90%\r\n<i>Hello</i> &amp; welcome\r\n{\\an8}back\r\n\r\n' +
    '01:02.25 --> 01:03.000\nShort &lt;form&gt;\n\n00:00:09.000 --> 00:00:08.000\nbackwards, dropped\n\n00:00:10,000 --> 00:00:12,000\nSRT comma\n'
  const cues = parseWebVTT(vtt)
  assert.deepEqual(cues, [
    { start: 1.5, end: 4, text: 'Hello & welcome\nback' },
    { start: 62.25, end: 63, text: 'Short <form>' },
    { start: 10, end: 12, text: 'SRT comma' },
  ])
  assert.equal(cueText(cues, 0), '')
  assert.equal(cueText(cues, 1.5), 'Hello & welcome\nback')
  assert.equal(cueText(cues, 4), '')   // the end is exclusive
  assert.equal(cueText(cues, 62.5), 'Short <form>')
})
