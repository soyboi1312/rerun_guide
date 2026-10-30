/// <reference types="node" />
// EmbyConnectTests.kt. The exchange test runs two node:http stand-ins on 127.0.0.1.
import assert from 'node:assert/strict'
import http from 'node:http'
import { AddressInfo } from 'node:net'
import { test } from 'node:test'
import { EmbyConnect } from '../src/core/EmbyConnect'
import { JellyfinClient } from '../src/core/JellyfinClient'
import { PlexServer } from '../src/core/PlexClient'

/** Connect's server list → server rows: LAN address first, then remote; the access key rides in `accessToken`; servers
 *  without a key, an id or any usable address are dropped. */
test('server rows', () => {
  const json = `
  [{"Name":"Basement","Url":"https://emby.example.net:8920/","LocalAddress":"http://192.0.2.10:8096","AccessKey":"k1","SystemId":"s1"},
   {"Name":"Remote only","Url":"https://far.example.net","LocalAddress":"","AccessKey":"k2","SystemId":"s2"},
   {"Name":"No key","Url":"https://x.example.net","AccessKey":"","SystemId":"s3"},
   {"Name":"No address","Url":"","LocalAddress":null,"AccessKey":"k4","SystemId":"s4"}]`
  const rows = EmbyConnect.rows(JSON.parse(json))
  assert.deepEqual(rows.map(r => r.id), ['s1', 's2'])
  assert.equal(rows[0].baseURL, 'http://192.0.2.10:8096')
  assert.deepEqual(rows[0].addresses!.map(a => a.url), ['http://192.0.2.10:8096', 'https://emby.example.net:8920'])
  assert.deepEqual(rows[0].addresses!.map(a => a.local), [true, false])
  assert.equal(rows[0].accessToken, 'k1')
  assert.equal(rows[1].baseURL, 'https://far.example.net')
  assert.equal(rows[1].name, 'Remote only')
})

/** A LAN address that isn't this server (another box at that IP on someone else's network) never gets the key. */
test('exchange skips a stranger on the LAN', async () => {
  const keys = new Map<number, string | undefined>()
  const serve = (id: string) => new Promise<http.Server>(resolve => {
    const s = http.createServer((req, res) => {
      const path = new URL(req.url!, 'http://x').pathname
      const port = (s.address() as AddressInfo).port
      if (path === '/Connect/Exchange') keys.set(port, req.headers['x-emby-token'] as string | undefined)
      res.end(path === '/System/Info/Public' ? `{"Id":"${id}"}` : '{"LocalUserId":"u","AccessToken":"t"}')
    })
    s.listen(0, '127.0.0.1', () => resolve(s))
  })
  const stranger = await serve('other')
  const home = await serve('s1')
  try {
    const urls = [stranger, home].map(s => `http://127.0.0.1:${(s.address() as AddressInfo).port}`)
    const row: PlexServer = { id: 's1', name: 'Basement', baseURL: urls[0], accessToken: 'key', addresses: urls.map(url => ({ url, local: true })) }
    assert.equal((await JellyfinClient.connectExchange(row, 'cu', 'dev')).baseURL, urls[1])
    assert.equal(keys.has((stranger.address() as AddressInfo).port), false)
    assert.equal(keys.get((home.address() as AddressInfo).port), 'key')
  } finally {
    for (const s of [stranger, home]) { s.close(); s.closeAllConnections() }
  }
})
