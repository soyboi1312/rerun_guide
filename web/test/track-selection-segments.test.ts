/// <reference types="node" />
// TrackSelectionTests.kt's JellyfinClient.segments cases (the TrackSelection ones are in models.test.ts).
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { JellyfinClient } from '../src/core/JellyfinClient'

test('only an HLS rendition rides in the playlist', () => {
  const path = (c: string, m: string) => `/videos/1/master.m3u8?VideoCodec=h264,hevc&SegmentContainer=${c}&SubtitleMethod=${m}`
  assert.equal(JellyfinClient.segments(path('mp4', 'Hls'), 'emby', true, false)[1], true)
  assert.equal(JellyfinClient.segments(path('mp4', 'Encode'), 'emby', true, false)[1], false)   // burned in
  assert.equal(JellyfinClient.segments(path('mp4', 'Hls'), 'emby', false, false)[1], false)
  // Emby: always TS; the TVs play HEVC in TS, so VideoCodec stays. Jellyfin: TS for a rendition, unless HEVC.
  assert.equal(JellyfinClient.segments(path('mp4', 'Hls'), 'emby', true, true)[0], path('ts', 'Hls'))
  assert.equal(JellyfinClient.segments(path('mp4', 'Hls'), 'jellyfin', true, false)[0], path('ts', 'Hls'))
  assert.equal(JellyfinClient.segments(path('mp4', 'Hls'), 'jellyfin', true, true)[0], path('mp4', 'Hls'))
  assert.equal(JellyfinClient.segments(path('mp4', 'Encode'), 'jellyfin', true, false)[0], path('mp4', 'Encode'))
})
