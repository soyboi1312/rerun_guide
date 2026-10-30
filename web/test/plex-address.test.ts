/// <reference types="node" />
// PlexAddressTests.kt: `PlexAuth.server(at)`, a typed Plex address only names a server on the account; the link goes to an
// address plex.tv lists for it, and no token goes anywhere but plex.tv while that is checked. plex.tv and the servers are
// node:http stand-ins on 127.0.0.1 (`PlexAuth.resourcesURL` points at one). Every Kotlin case runs: Den's plex.direct
// address needs no answer (Den's own address wins first), and the *.invalid names fail at DNS, as they should.
// ClientDevice.device stays "Android" as in the Kotlin tests, so Vault also runs the phone path's two LAN retries (~4 s).
import assert from 'node:assert/strict'
import http from 'node:http'
import { AddressInfo } from 'node:net'
import { after, before, test } from 'node:test'
import { PlexAddress, PlexAuth } from '../src/core/PlexClient'

const started: http.Server[] = []
/** (port + path, X-Plex-Token) of every request any stand-in received. */
const seen: [string, string | undefined][] = []
const plexTV = PlexAuth.resourcesURL

function serve(routes: { [path: string]: () => string }): Promise<string> {
  return new Promise(resolve => {
    const s = http.createServer((req, res) => {
      const path = new URL(req.url!, 'http://x').pathname
      seen.push([`${(s.address() as AddressInfo).port}${path}`, req.headers['x-plex-token'] as string | undefined])
      const route = Object.prototype.hasOwnProperty.call(routes, path) ? routes[path] : null
      if (route == null) { res.writeHead(404); res.end() } else res.end(route())
    })
    started.push(s)
    s.listen(0, '127.0.0.1', () => resolve(`http://127.0.0.1:${(s.address() as AddressInfo).port}`))
  })
}
const identity = (id: string) => ({ '/identity': () => `{"MediaContainer":{"machineIdentifier":"${id}"}}` })
const portOf = (url: string) => url.slice(url.lastIndexOf(':') + 1)

let den: string, claimant: string, stranger: string, cabin: string, attic: string, vault: string, router: string, dead: string, account: string

before(async () => {
  den = await serve(identity('m1'))
  claimant = await serve(identity('m1'))                 // says it's Den; plex.tv doesn't list it
  stranger = await serve(identity('m2'))                 // a Plex server, not this account's
  cabin = await serve(identity('m3'))                    // Cabin, whose listed address is down
  attic = await serve(identity('m4'))                    // Attic: its plex.direct name won't resolve
  vault = await serve(identity('m5'))                    // Vault: the same, but secure connections only
  router = await serve({ '/identity': () => '<html>router</html>' })   // not a Plex server
  dead = await serve({})                                 // nothing listening
  await new Promise(resolve => started.pop()!.close(resolve))
  account = await serve({ '/resources': () => `
    [{"name":"Den","clientIdentifier":"m1","provides":"server","accessToken":"server-token","owned":true,
       "connections":[{"uri":"${den}","protocol":"http","local":true,"relay":false},
                      {"uri":"https://1-2-3-4.abc.plex.direct:32400","protocol":"https","local":false,"relay":false}]},
     {"name":"Cabin","clientIdentifier":"m3","provides":"server","accessToken":"cabin-token","owned":true,
       "connections":[{"uri":"${dead}","protocol":"http","local":false,"relay":false}]},
     {"name":"Attic","clientIdentifier":"m4","provides":"server","accessToken":"attic-token","owned":true,
       "connections":[{"uri":"https://attic.invalid:32400","protocol":"https","local":true,"relay":false,"address":"127.0.0.1","port":${portOf(attic)}}]},
     {"name":"Vault","clientIdentifier":"m5","provides":"server","accessToken":"vault-token","owned":true,"httpsRequired":true,
       "connections":[{"uri":"https://vault.invalid:32400","protocol":"https","local":true,"relay":false,"address":"127.0.0.1","port":${portOf(vault)}}]},
     {"name":"Phone","clientIdentifier":"p1","provides":"client,player","accessToken":"x","owned":true,"connections":[]}]` })
  PlexAuth.resourcesURL = `${account}/resources`
})

after(() => {
  for (const s of started) { s.close(); s.closeAllConnections() }
  PlexAuth.resourcesURL = plexTV
})

const server = (address: string) => PlexAuth.server(address, 'account-token', 'test')

test('an address names the server; plex.tv lists the way', async () => {
  const s = await server(den)
  assert.equal(s.id, 'm1')
  assert.equal(s.name, 'Den')
  assert.equal(s.accessToken, 'server-token')
  assert.equal(s.baseURL, den)
  assert.deepEqual(s.addresses!.map(a => a.url), [den, 'https://1-2-3-4.abc.plex.direct:32400'])

  // A host plex.tv doesn't list that claims to be Den only names it: the link goes to Den's listed address.
  const claimed = await server(claimant)
  assert.equal(claimed.baseURL, den)
  assert.deepEqual(claimed.addresses, s.addresses)

  // A router that blocks DNS answers pointing at private IPs: the LAN plex.direct name fails, its plain address works.
  const a = await server(attic)
  assert.equal(a.baseURL, attic)
  assert.deepEqual(a.addresses!.map(x => x.url), ['https://attic.invalid:32400', attic])
})

test('other addresses are turned down', async () => {
  for (const [address, want] of [[stranger, 'notOnAccount'], [cabin, 'unreachable'], [vault, 'unreachable'], [router, 'noServer'], [dead, 'noServer']]) {
    await assert.rejects(server(address), (e: unknown) => {
      assert.ok(e instanceof PlexAuth.AddressError, `${address}: ${e}`)
      assert.equal(e.kind, want, address)
      return true
    })
  }
})

/** No token at all goes anywhere but plex.tv while the address is checked: the account token is plex.tv's, and the
 *  server's token is only used later, by the link, at an address plex.tv listed. */
test('tokens only go to plex.tv', async () => {
  seen.length = 0
  for (const a of [den, claimant, stranger, cabin]) await server(a).catch(() => null)
  assert.ok(seen.length > 0)
  const plexTVPort = portOf(account)
  for (const [path, token] of seen.filter(x => !x[0].startsWith(plexTVPort))) assert.equal(token, undefined, path)
  assert.ok(seen.some(x => x[0] === `${plexTVPort}/resources` && x[1] === 'account-token'))
})

/**
 * Web only: the CORS fallback. From a packaged TV app (file:// origin) PMS hides its token-less answers (ACAO
 * https://app.plex.tv), so fetch throws a TypeError although the request arrived. A typed address plex.tv lists still names
 * its server (so Den is unreachable, not noServer), but only an https address may be asked again with the token: Den's
 * plain-http one gets none, and its plex.direct one can't answer here. One plex.tv doesn't list gets nothing.
 * ponytail: no https stand-in (it would need a certificate), so the https retry that still links is untested.
 */
test('a hidden token-less answer names the server by plex.tv listing; http gets no token', async () => {
  const real = globalThis.fetch
  globalThis.fetch = (input, init) => {
    const headers = (init?.headers ?? {}) as { [k: string]: string }
    if (init?.mode === 'no-cors' || headers['X-Plex-Token'] != null || String(input).startsWith(account)) return real(input, init)
    return real(input, init).then(() => { throw new TypeError('Failed to fetch') })
  }
  try {
    seen.length = 0
    await assert.rejects(server(den), (e: unknown) => e instanceof PlexAuth.AddressError && e.kind === 'unreachable')
    await assert.rejects(server(claimant), (e: unknown) => e instanceof PlexAuth.AddressError && e.kind === 'noServer')
    await assert.rejects(server(dead), (e: unknown) => e instanceof PlexAuth.AddressError && e.kind === 'noServer')
    const plexTVPort = portOf(account)
    for (const [path, token] of seen.filter(x => !x[0].startsWith(plexTVPort))) assert.equal(token, undefined, path)
    assert.ok(seen.some(x => x[0] === `${portOf(den)}/identity`), 'Den was probed')
  } finally {
    globalThis.fetch = real
  }
})

/** Web only: a LAN device at a plex.tv-listed http IP:port that fails the token-less probe on purpose (here it drops the
 *  connection: fetch's TypeError, as a hidden CORS answer looks) must not get the token from a retry. */
test('a failed plain-http probe is never retried with the token', async () => {
  let probed = false, gotToken = false
  const s = http.createServer((req, res) => {
    if (req.headers['x-plex-token'] == null) { probed = true; req.socket.destroy(); return }
    gotToken = true
    res.end(identity('m1')['/identity']())
  })
  started.push(s)
  await new Promise<void>(resolve => s.listen(0, '127.0.0.1', resolve))
  const url = `http://127.0.0.1:${(s.address() as AddressInfo).port}`
  assert.equal(await PlexAuth.bestAddress([{ url, local: true }], 'm1', 'test', false, 'server-token'), null)
  assert.ok(probed, 'the token-less probe arrived')
  assert.equal(gotToken, false, 'a token-bearing request arrived')
})

// The PlexAddress part of ParityTest.testPersistedJsonRoundTrip.
test('PlexAddress JSON round trip', () => {
  const a: PlexAddress = { url: 'http://10.0.0.2:32400', local: true }
  assert.deepEqual(PlexAddress.fromJson(JSON.parse(JSON.stringify(PlexAddress.toJson(a)))), a)
})
