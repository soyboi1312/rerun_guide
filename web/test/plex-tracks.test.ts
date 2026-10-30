/// <reference types="node" />
// Web only: the names PlexClient.tracks gives a part's streams (Swift and Kotlin share the rule). A node:http stand-in on
// 127.0.0.1 answers with streams shaped like The Replacements' (40 subtitle tracks, custom titles over Plex's summaries).
import assert from 'node:assert/strict'
import http from 'node:http'
import { AddressInfo } from 'node:net'
import { test } from 'node:test'
import { PlexClient } from '../src/core/PlexClient'
import type { MediaItem } from '../src/core/Models'

test('a custom title, without the summary or language it already names', async () => {
  const streams = [
    { id: 1, streamType: 2, languageCode: 'eng', title: 'Commentary', displayTitle: 'English (AAC Stereo)' },
    { id: 2, streamType: 3, languageCode: 'eng', title: 'English (SDH)', displayTitle: 'English' },
    { id: 3, streamType: 3, languageCode: 'spa', title: 'Spanish (Castilian) | SRT', displayTitle: 'Español' },
    { id: 4, streamType: 3, languageCode: 'eng', title: 'SDH', displayTitle: 'English (SDH SRT)' },
    { id: 5, streamType: 3, languageCode: 'fra', displayTitle: 'Français' },
    { id: 6, streamType: 1, languageCode: 'eng', displayTitle: '1080p (H.264)' },
  ]
  const body = JSON.stringify({ MediaContainer: { Metadata: [{ Media: [{ Part: [{ id: 9, Stream: streams }] }] }] } })
  const s = http.createServer((_, res) => { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(body) })
  await new Promise<void>(resolve => s.listen(0, '127.0.0.1', resolve))
  try {
    const plex = new PlexClient(`http://127.0.0.1:${(s.address() as AddressInfo).port}`, 'token', 'client')
    const tracks = await plex.tracks({ id: '42' } as MediaItem)
    assert.deepEqual(tracks.map(t => t.title),
      ['Commentary · English (AAC Stereo)', 'English (SDH)', 'Spanish (Castilian) | SRT', 'English (SDH SRT)', 'Français'])
  } finally {
    s.close(); s.closeAllConnections()
  }
})
