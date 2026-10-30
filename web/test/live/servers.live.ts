/// <reference types="node" />
// Manual smoke test of the Plex and Emby clients against real servers (LiveServersTest.kt's little brother). Not part of
// `npm test` (not *.test.ts). Run from web/:
//   set -a; . ~/.config/retrobinge/test.env; set +a
//   npx esbuild test/live/servers.live.ts --bundle --platform=node --format=esm --outfile=.test-build/servers.live.mjs
//   node .test-build/servers.live.mjs
// Prints statuses and playlist first lines only: tokens and user ids are redacted, URLs are shown without their query.
// Also asks Plex for the HLS transcode under each candidate X-Plex-Platform, to pick ClientDevice.platform for the TVs.
import { ClientDevice, MediaServer } from '../../src/core/MediaServer'
import { JellyfinClient } from '../../src/core/JellyfinClient'
import { MediaItem, TrackSelection } from '../../src/core/Models'
import { PlexClient } from '../../src/core/PlexClient'

const env = process.env
const secrets = ['PLEX_TOKEN', 'EMBY_TOKEN', 'EMBY_USER_ID'].map(k => env[k] ?? '').filter(t => t.length > 3)
function say(s: string) {
  for (const t of secrets) s = s.split(t).join('***')
  console.log(s)
}
const shape = (url: string) => url.replace(/\?.*$/, '')
function expect(ok: boolean, what: string) {
  if (!ok) { say(`  FAIL ${what}`); process.exitCode = 1 }
}

// What Platform.init() sets on a TV. X-Plex-Platform "Generic": PMS answers "webOS" and "Tizen" with 400, and its
// "Web"/"Chrome"/"Android" profiles ignore the Profile-Extra (TS, H.264 + AAC only); Generic follows it (fMP4, HEVC/EAC3 copy).
ClientDevice.device = 'LG TV'
ClientDevice.platform = 'Generic'
ClientDevice.videoCodecs = 'h264,hevc'
ClientDevice.audioCodecs = 'aac,ac3,eac3'
ClientDevice.directPlayAudioCodecs = ClientDevice.audioCodecs
const deviceID = 'rerunguide-web-live'

/** GET a playback URL: a master playlist's status and first line, then its first variant's status; or a media file's type. */
async function stream(tag: string, url: string): Promise<number> {
  const r = await fetch(url)
  const text = (r.headers.get('content-type') ?? '').indexOf('mpegurl') >= 0 || url.indexOf('.m3u8') >= 0 ? await r.text() : ''
  say(`  ${tag} GET ${shape(url)}: HTTP ${r.status} ${r.headers.get('content-type')} first line ${JSON.stringify(text.split('\n')[0])}`)
  if (!text.startsWith('#EXTM3U')) { r.body?.cancel().catch(() => {}); return r.status }
  const variant = text.split('\n').map(l => l.trim()).find(l => l !== '' && !l.startsWith('#'))
  if (variant) {
    const v = await fetch(new URL(variant, url))
    const body = await v.text()
    say(`  ${tag}   variant ${shape(new URL(variant, url).pathname)}: HTTP ${v.status}, ${body.split('\n').filter(l => l !== '' && !l.startsWith('#')).length} segments listed`)
  }
  return r.status
}

async function basics(tag: string, server: MediaServer): Promise<MediaItem | null> {
  const libs = await server.libraries()
  for (const l of libs) say(`  ${tag} lib ${l.id} "${l.title}" ${l.kind} n=${l.itemCount}`)
  const section = libs.find(l => l.kind === 'movies')
  if (!section) { expect(false, `${tag}: no movie library`); return null }
  const t0 = Date.now()
  const items = await server.libraryItems([section])
  say(`  ${tag} items in "${section.title}": ${items.length} (${Date.now() - t0} ms; server says ${section.itemCount})`)
  expect(items.length > 0, `${tag} items`)
  const recent = await server.recentlyAdded(null, 5)
  say(`  ${tag} recentlyAdded(null, 5): ${recent.length} items`)
  return items[0] ?? null
}

async function plex() {
  const url = env.PLEX_URL, token = env.PLEX_TOKEN
  if (!url || !token) { say('Plex: PLEX_URL / PLEX_TOKEN not set, skipped'); return }
  say('==== Plex')
  const server = new PlexClient(url, token, deviceID, 'plex-test')
  const movie = await basics('Plex', server)
  if (!movie) return
  say(`  Plex movie ${movie.id}: tracks ${(await server.tracks(movie)).map(t => `${t.kind}:${t.id}`).join(' ')}`)
  try {
    const src = await server.playbackSource(movie, 600, 'live')
    say(`  Plex playback ${shape(src.url)} startsAtOffset=${src.startsAtOffset} subtitleInPlaylist=${src.subtitleInPlaylist}`)
    expect(await stream('Plex', src.url) === 200, 'Plex start.m3u8')
  } finally {
    await server.stopPlayback('live')
  }

  // Which X-Plex-Platform the universal transcoder accepts, and what it decides for this movie under each.
  say('---- X-Plex-Platform for the HLS transcode')
  for (const platform of ['Generic', 'webOS', 'Tizen', 'Web', 'Chrome', 'Android']) {
    ClientDevice.platform = platform
    const session = `platform-${platform}`
    try {
      const src = await server.playbackSource(movie, 600, session)   // runs /decision, then hands back start.m3u8
      const d = await fetch(src.url.replace('/start.m3u8', '/decision'), { headers: { Accept: 'application/json' } })
      const dtext = await d.text()
      let decided = ''
      try {
        const mc = JSON.parse(dtext).MediaContainer
        const streams: { streamType: number; codec: string; decision?: string }[] = mc.Metadata?.[0]?.Media?.[0]?.Part?.[0]?.Stream ?? []
        decided = `general=${mc.generalDecisionCode} "${mc.generalDecisionText}" container=${mc.Metadata?.[0]?.Media?.[0]?.container} ` +
          streams.filter(s => s.streamType === 1 || s.streamType === 2).map(s => `${s.streamType === 1 ? 'video' : 'audio'}=${s.decision}(${s.codec})`).join(' ')
      } catch { decided = dtext.slice(0, 120) }
      say(`  ${platform}: decision HTTP ${d.status} ${decided}`)
      const status = await stream(platform, src.url)
      say(`  ${platform}: start.m3u8 HTTP ${status}`)
    } catch (e) {
      say(`  ${platform}: ${e}`)
    } finally {
      await server.stopPlayback(session)
    }
  }
  ClientDevice.platform = 'Generic'
}

async function emby() {
  const url = env.EMBY_URL, token = env.EMBY_TOKEN, user = env.EMBY_USER_ID
  if (!url || !token || !user) { say('Emby: EMBY_URL / EMBY_TOKEN / EMBY_USER_ID not set, skipped'); return }
  say('==== Emby')
  const server = new JellyfinClient(url, token, user, deviceID, 'emby', 'emby-test')
  const movie = await basics('Emby', server)
  if (!movie) return
  try {
    const src = await server.playbackSource(movie, 600, 'live')
    say(`  Emby playback ${shape(src.url)} startsAtOffset=${src.startsAtOffset} subtitleInPlaylist=${src.subtitleInPlaylist}`)
    expect(await stream('Emby', src.url) === 200, 'Emby stream')
  } finally {
    await server.stopPlayback('live')
  }
  // The web profile has no subtitle renditions: a picked subtitle must come back burned in (SubtitleMethod=Encode).
  for (const m of (await server.libraryItems(null)).filter(i => i.kind === 'movie').slice(0, 20)) {
    const sub = (await server.tracks(m)).find(t => t.kind === 'subtitle')
    if (!sub) continue
    try {
      const src = await server.playbackSource(m, 600, 'live', new TrackSelection(null, TrackSelection.Subtitle.track(sub.id)))
      const method = /SubtitleMethod=(\w+)/.exec(src.url)?.[1] ?? 'none'
      say(`  Emby subtitle pick ${m.id}/${sub.id}: SubtitleMethod=${method} subtitleInPlaylist=${src.subtitleInPlaylist}`)
      expect(src.subtitleInPlaylist !== true, 'Emby subtitle not in playlist')
      expect(await stream('Emby sub', src.url) === 200, 'Emby subtitle stream')
    } finally {
      await server.stopPlayback('live')
    }
    break
  }
}

await plex()
await emby()
