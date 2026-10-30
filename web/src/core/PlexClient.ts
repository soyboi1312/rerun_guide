// Port of android/core PlexClient.kt. Web changes: the token-less /identity probes have a CORS fallback (see
// `firstAnswering` and `PlexAuth.server`), and `bestAddress` always takes the TV path (ClientDevice.device is never "Android…").
import {
  JSONObject, asArray, asObject, boolOrNull, getBoolean, getInt, getJSONObject, getString, intOrNull, longOrNull, objects,
  objectsOrNull, optBoolean, stableSort, stringOrNull,
} from './Lang'
import {
  ClientDevice, HTTP, MediaServer, MediaServerError, URLRequest, isCancellation, percentEncode, queryString, runCatchingNonCancel,
} from './MediaServer'
import { LibrarySection, MediaItem, MediaTrack, PlaybackSource, ServerKind, TrackSelection, swiftCompare } from './Models'

export interface PlexPIN {
  readonly id: number
  /** 4-char code the user types at https://plex.tv/link */
  readonly code: string
}

export interface PlexServer {
  readonly id: string
  readonly name: string
  /** Best reachable connection (local > remote > relay). */
  readonly baseURL: string
  /** Per-server access token (differs from the account token for shared servers). */
  readonly accessToken: string
  /**
   * Every address plex.tv lists for it, best first, so a device that moves between networks can pick again later
   * (`PlexAuth.bestAddress`). Missing or empty for a server with no plex.tv listing. Kotlin's default is emptyList().
   */
  readonly addresses?: readonly PlexAddress[]
}

/** One of a server's addresses, and whether plex.tv says it is on the server's own network. */
export interface PlexAddress {
  readonly url: string
  readonly local: boolean
}
export namespace PlexAddress {
  export function toJson(a: PlexAddress): JSONObject {
    return { url: a.url, local: a.local }
  }
  export function fromJson(o: JSONObject): PlexAddress {
    return { url: getString(o, 'url'), local: optBoolean(o, 'local', false) }
  }
}

// MARK: - Shared request plumbing

/** Read per request, so the device name the app sets at startup is always the one sent. */
function plexIdentity() {
  return {
    'X-Plex-Product': 'Rerun Guide', 'X-Plex-Version': '1.0', 'X-Plex-Platform': ClientDevice.platform,
    'X-Plex-Device': ClientDevice.device, 'X-Plex-Device-Name': 'Rerun Guide',
  }
}

/** Every plex.tv and PMS request goes through here so the identity headers are always identical. */
function plexRequest(url: string, token: string | null, clientID: string, method = 'GET', timeout = 30): URLRequest {
  const r = new URLRequest(url, timeout)
  r.httpMethod = method
  r.setValue('application/json', 'Accept')
  r.setValue(clientID, 'X-Plex-Client-Identifier')
  if (token != null) r.setValue(token, 'X-Plex-Token')
  const identity = plexIdentity()
  for (const k of Object.keys(identity) as (keyof typeof identity)[]) r.setValue(identity[k], k)
  return r
}

/**
 * `base` + `path` (+ query), tolerating a base that carries a path prefix or trailing slash. Split by hand like java.net.URI's
 * raw parts: WHATWG `URL` would lowercase the host, drop default ports and add a "/" path.
 */
function plexURL(base: string, path: string, query: readonly (readonly [string, string])[] = []): string {
  const m = /^([A-Za-z][A-Za-z0-9+.-]*):(\/\/[^/?#]*)?([^?#]*)/.exec(base)
  if (!m) throw new TypeError(`Invalid URL: ${base}`)
  const basePath = m[3].endsWith('/') ? m[3].slice(0, -1) : m[3]
  return `${m[1]}:${m[2] ?? ''}${basePath}${path}${queryString(query)}`
}

function parses(url: string): boolean {
  try { new URL(url); return true } catch { return false }
}

const machineIdentifier = (o: unknown) => stringOrNull(getJSONObject(asObject(o), 'MediaContainer'), 'machineIdentifier')

/** The machineIdentifier `base`'s /identity reports. */
async function identity(base: string, clientID: string, token: string | null, timeout: number, signal: AbortSignal | null): Promise<string | null> {
  const req = plexRequest(plexURL(base, '/identity'), token, clientID, 'GET', timeout)
  req.signal = signal
  return HTTP.json(req, machineIdentifier)
}

// MARK: - plex.tv

interface Connection {
  readonly uri: string
  readonly protocol: string
  readonly local: boolean
  readonly relay: boolean
  readonly address: string | null
  readonly port: number | null
}
/** `httpsRequired`: the server's "Secure connections: Required", so no plain-http address for it. */
interface Resource {
  readonly name: string
  readonly clientIdentifier: string
  readonly provides: string
  readonly accessToken: string | null
  readonly connections: readonly Connection[] | null
  readonly httpsRequired: boolean | null
}

const isServer = (r: Resource) => r.provides.split(',').indexOf('server') >= 0

function pin(o: unknown) {
  const p = asObject(o)
  return { id: getInt(p, 'id'), code: getString(p, 'code'), authToken: stringOrNull(p, 'authToken') }
}

async function resources(token: string, clientID: string): Promise<Resource[]> {
  return HTTP.json(plexRequest(PlexAuth.resourcesURL, token, clientID), a => objects(asArray(a)).map((r): Resource => ({
    name: getString(r, 'name'), clientIdentifier: getString(r, 'clientIdentifier'), provides: getString(r, 'provides'),
    accessToken: stringOrNull(r, 'accessToken'),
    connections: objectsOrNull(r, 'connections')?.map(c => ({
      uri: getString(c, 'uri'), protocol: getString(c, 'protocol'), local: getBoolean(c, 'local'), relay: getBoolean(c, 'relay'),
      address: stringOrNull(c, 'address'), port: intOrNull(c, 'port'),
    })) ?? null,
    httpsRequired: boolOrNull(r, 'httpsRequired'),
  })))
}

/**
 * plex.tv's addresses for the server, best first. A LAN connection also gets its plain http://address:port, unless the
 * server requires secure connections: where the router blocks DNS answers that point at private IPs (DNS rebinding
 * protection), the plex.direct name never resolves and that is the only way in at home. It ranks after the https ones,
 * and like every address it must answer as this machine before a token goes to it.
 */
function ranked(r: Resource): PlexAddress[] {
  const all: [number, PlexAddress][] = []
  for (const c of r.connections ?? []) {
    if (parses(c.uri)) all.push([priority(c) * 1000 + all.length, { url: c.uri, local: c.local }])
    const a = c.address, p = c.port
    if (c.local && !c.relay && r.httpsRequired !== true && a != null && p != null && parses(`http://${a}:${p}`)) {
      all.push([1 * 1000 + all.length, { url: `http://${a}:${p}`, local: true }])   // priority of a local http connection
    }
  }
  const seen = new Set<string>()
  return stableSort(all, (x, y) => x[0] - y[0]).map(x => x[1]).filter(a => {
    if (seen.has(a.url)) return false
    seen.add(a.url)
    return true
  })
}

function priority(c: Connection): number {
  return (c.local ? 0 : c.relay ? 2 : 1) * 2 + (c.protocol === 'https' ? 0 : 1)
}

/** "host:port" with the scheme's default port filled in, for comparing addresses; null when `url` doesn't parse. */
function hostPort(url: string): string | null {
  try {
    const u = new URL(url)
    return `${u.hostname}:${u.port || (u.protocol === 'https:' ? '443' : '80')}`
  } catch { return null }
}

/** Whether plex.tv lists `at`'s host and port among `r`'s connections (by URI or by LAN address:port). */
function lists(r: Resource, at: string): boolean {
  const typed = hostPort(at)
  return typed != null && (r.connections ?? []).some(c =>
    hostPort(c.uri) === typed || (c.address != null && c.port != null && hostPort(`http://${c.address}:${c.port}`) === typed))
}

/** Whether anything answers HTTP at `url`, readable or not: a `no-cors` fetch resolves with an opaque answer. */
async function answers(url: string, timeout: number): Promise<boolean> {
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), timeout * 1000)
  try {
    await fetch(url, { mode: 'no-cors', credentials: 'omit', signal: ctrl.signal })
    return true
  } catch {
    return false
  } finally {
    clearTimeout(timer)
  }
}

const delay = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms))

/**
 * Probes every address at once; the best-ranked one whose /identity reports `machineID` wins (a stranger's LAN at the same
 * IP can't impersonate the server). The losers are aborted.
 */
async function firstAnswering(ranked: readonly PlexAddress[], machineID: string, clientID: string, token: string | null): Promise<string | null> {
  const ctrl = new AbortController()
  let best: number | null = null
  await new Promise<void>(resolve => {
    let left = ranked.length
    if (left === 0) return resolve()
    ranked.forEach((a, i) => {
      const timeout = a.local ? 2 : 4   // a LAN server answers in ms
      identity(a.url, clientID, null, timeout, ctrl.signal)
        .catch(e => {
          // ponytail: CORS fallback, unverified on a TV. From a packaged app's null/file:// origin, PMS answers a token-less
          // request with ACAO https://app.plex.tv, so the browser hides every token-less /identity (a TypeError, like a
          // network failure; an HTTP status or bad JSON is not one). This address is one plex.tv lists for this very server,
          // so ask once more WITH its token, https only: there the certificate proves the host. A plain-http one is just an
          // IP:port, and a LAN device sitting there could fail the token-less probe on purpose to collect the token from the
          // retry, so http gets no retry (Kotlin sends no token here at all). Cost: a TV whose only working address is the
          // plain LAN one (plex.direct blocked by the router) can't link while answers are hidden. On-device check: does a
          // token-less fetch of PLEX_URL/identity resolve, readable, from the packaged app (file://)? If it does, delete this retry.
          if (!(e instanceof TypeError) || token == null || !/^https:/i.test(a.url)) return null
          return identity(a.url, clientID, token, timeout, ctrl.signal)
        })
        .catch(() => null)
        .then(id => {
          if (id === machineID && i < (best ?? Infinity)) best = i
          // Nothing can beat the top candidate; stop waiting on the rest.
          if (best === 0 || --left === 0) resolve()
        })
    })
  })
  ctrl.abort()
  return best == null ? null : ranked[best].url
}

/** Why `server(at)` turned an address down (Kotlin's sealed AddressError): `e instanceof PlexAuth.AddressError && e.kind === 'noServer'`. */
class AddressError extends Error {
  private constructor(
    /** noServer: nothing Plex answered there; notOnAccount: it's not a server on this account; unreachable: it is, but none
     *  of the addresses plex.tv lists for it answered. */
    readonly kind: 'noServer' | 'notOnAccount' | 'unreachable',
  ) {
    super(kind)
    this.name = 'AddressError'
  }
  static noServer() { return new AddressError('noServer') }
  static notOnAccount() { return new AddressError('notOnAccount') }
  static unreachable() { return new AddressError('unreachable') }
}

/** plex.tv account flow: request PIN → user enters it at plex.tv/link → poll → token → list servers. */
export const PlexAuth = {
  AddressError,

  /** plex.tv's server list; settable only so the address tests can point it at a local stand-in. */
  resourcesURL: 'https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1&includeIPv6=0',

  /**
   * plex.tv username/password sign-in (the "Type a server address instead" path). Returns the account token.
   * Plex 2FA: user appends the 6-digit code to the password (official Plex guidance for apps using this endpoint); the UI says so.
   */
  async signIn(username: string, password: string, clientID: string): Promise<string> {
    const req = plexRequest('https://plex.tv/api/v2/users/signin', null, clientID, 'POST')
    req.setValue('application/x-www-form-urlencoded', 'Content-Type')
    req.httpBody = [['login', username], ['password', password]].map(([k, v]) => `${k}=${percentEncode(v)}`).join('&')
    // HTTP.data already maps 401 (bad credentials / missing 2FA code) to unauthorized.
    return HTTP.json(req, o => getString(asObject(o), 'authToken'))
  },

  /** `strong=true` yields the long OAuth code; omitting it yields the 4-char code for plex.tv/link. */
  async requestPIN(clientID: string): Promise<PlexPIN> {
    const p = await HTTP.json(plexRequest('https://plex.tv/api/v2/pins', null, clientID, 'POST'), pin)
    return { id: p.id, code: p.code }
  },

  /** null until the user has claimed the PIN. */
  async pollPIN(p: PlexPIN, clientID: string): Promise<string | null> {
    try {
      return (await HTTP.json(plexRequest(`https://plex.tv/api/v2/pins/${p.id}`, null, clientID), pin)).authToken
    } catch (e) {
      if (e instanceof MediaServerError && e.kind === 'http' && e.code === 404) throw MediaServerError.notFound('PIN expired. Request a new code')
      throw e
    }
  },

  /**
   * The account's server that `at` (an address the viewer typed) says it is. The address only names the server: its
   * /identity, asked without a token, gives the machine; plex.tv must list that machine on this account; and the link then
   * goes to whichever address plex.tv lists for it answers as that machine (on the LAN, usually the plex.direct one, whose
   * certificate is the server's own). No token is sent to the typed host unless plex.tv lists it, so a typo, or a LAN
   * device claiming to be the server, gets nothing.
   */
  async server(at: string, token: string, clientID: string): Promise<PlexServer> {
    const url = plexURL(at, '/identity')
    let id: string | null = null
    let hidden = false
    try {
      id = await HTTP.json(plexRequest(url, null, clientID, 'GET', 8), machineIdentifier)
    } catch (e) {
      if (isCancellation(e)) throw e
      // ponytail: CORS fallback, unverified on a TV. A PMS hides its token-less answer from a packaged app (file:// origin):
      // a TypeError although something answered. Then the address can only name the server by being one plex.tv lists, so
      // match its host:port against the account's connections; nothing is sent to it. On-device check: does a token-less
      // fetch of PLEX_URL/identity resolve, readable, from the packaged app (file://)? If it does, delete this fallback.
      hidden = e instanceof TypeError && await answers(url, 8)
    }
    if (id == null && !hidden) throw AddressError.noServer()
    const r = (await resources(token, clientID)).find(r => isServer(r) && (id != null ? r.clientIdentifier === id : lists(r, at)))
    if (!r) throw id != null ? AddressError.notOnAccount() : AddressError.noServer()
    const serverToken = r.accessToken
    if (serverToken == null) throw AddressError.notOnAccount()   // never the account token
    const known = ranked(r)
    const best = await PlexAuth.bestAddress(known, r.clientIdentifier, clientID, true, serverToken)
    if (best == null) throw AddressError.unreachable()
    return { id: r.clientIdentifier, name: r.name, baseURL: best, accessToken: serverToken, addresses: known }
  },

  /** Every server on the account that answers, or with `only`, just that machineIdentifier (finding a moved server). */
  async servers(token: string, clientID: string, only: string | null = null): Promise<PlexServer[]> {
    const servers: PlexServer[] = []
    for (const r of await resources(token, clientID)) {
      if (!(isServer(r) && (only == null || r.clientIdentifier === only))) continue
      const serverToken = r.accessToken
      if (serverToken == null) continue   // the account token would give its owner the whole account
      // Probe LAN addresses even for shared servers: a family member's TV usually sits on the server's LAN, and going out
      // via the public address hairpins through the router (verified: /identity answers, big pages time out).
      // Only an address whose /identity reports this server's machineIdentifier counts (firstAnswering).
      const addresses = ranked(r)
      const uri = await PlexAuth.bestAddress(addresses, r.clientIdentifier, clientID, true, serverToken)
      if (uri == null) continue
      servers.push({ id: r.clientIdentifier, name: r.name, baseURL: uri, accessToken: serverToken, addresses })
    }
    return servers
  },

  /**
   * The first of `ranked` that answers as `machineID` (all probed at once, GET /identity; 2 s for LAN, 4 s otherwise).
   * `waitForLAN`, phones / tablets only (`ClientDevice.device` "Android …"; never on the web, so LG and Samsung take the TV
   * path): when only a non-LAN address answered, try the LAN ones twice more, 2 s apart.
   * `token` (web only): the server's own access token, for the CORS fallback in `firstAnswering`. Pass the link's token.
   */
  async bestAddress(ranked: readonly PlexAddress[], machineID: string, clientID: string, waitForLAN = false,
                    token: string | null = null): Promise<string | null> {
    const found = await firstAnswering(ranked, machineID, clientID, token)
    if (ClientDevice.device.startsWith('Android')) {
      const lan = ranked.filter(a => a.local)
      if (waitForLAN && lan.length > 0 && !lan.some(a => a.url === found)) {
        for (let n = 0; n < 2; n++) {
          await delay(2000)
          const again = await firstAnswering(lan, machineID, clientID, token)
          if (again != null) return again
        }
      }
    }
    return found
  },
}

// MARK: - Plex Media Server

// Plex omits any field it has no value for, so everything but keys/titles is optional.
interface Container<T> {
  readonly totalSize: number | null
  readonly Directory: T[] | null
  readonly Metadata: T[] | null
}
function container<T>(o: unknown, item: (o: JSONObject) => T): Container<T> {
  const c = getJSONObject(asObject(o), 'MediaContainer')
  return { totalSize: intOrNull(c, 'totalSize'), Directory: objectsOrNull(c, 'Directory')?.map(item) ?? null, Metadata: objectsOrNull(c, 'Metadata')?.map(item) ?? null }
}

interface Directory {
  readonly key: string
  readonly type: string
  readonly title: string | null
}
const directory = (o: JSONObject): Directory => ({ key: getString(o, 'key'), type: getString(o, 'type'), title: stringOrNull(o, 'title') })

function tags(o: JSONObject, key: string): string[] | null {
  return objectsOrNull(o, key)?.map(t => getString(t, 'tag')) ?? null
}

class Metadata {
  readonly ratingKey: string
  readonly title: string | null
  /** A film's cut ("Directors Cut"), from the file name's {edition-…}. */
  readonly editionTitle: string | null
  readonly year: number | null
  /** Unix seconds. */
  readonly addedAt: number | null
  /** Milliseconds. */
  readonly duration: number | null
  /** Times this account watched it (or marked it watched); absent = never. Stopping partway only sets viewOffset. */
  readonly viewCount: number | null
  readonly thumb: string | null
  readonly art: string | null
  readonly summary: string | null
  readonly contentRating: string | null
  /** Extras only: "trailer", "behindTheScenes", … */
  readonly subtype: string | null
  readonly index: number | null
  readonly parentIndex: number | null
  readonly grandparentTitle: string | null
  readonly grandparentRatingKey: string | null
  readonly grandparentThumb: string | null
  readonly grandparentArt: string | null
  readonly Genre: string[] | null
  readonly Director: string[] | null
  readonly Role: string[] | null

  constructor(o: JSONObject) {
    this.ratingKey = getString(o, 'ratingKey')
    this.title = stringOrNull(o, 'title')
    this.editionTitle = stringOrNull(o, 'editionTitle')
    this.year = intOrNull(o, 'year')
    this.addedAt = longOrNull(o, 'addedAt')
    this.duration = longOrNull(o, 'duration')
    this.viewCount = intOrNull(o, 'viewCount')
    this.thumb = stringOrNull(o, 'thumb')
    this.art = stringOrNull(o, 'art')
    this.summary = stringOrNull(o, 'summary')
    this.contentRating = stringOrNull(o, 'contentRating')
    this.subtype = stringOrNull(o, 'subtype')
    this.index = intOrNull(o, 'index')
    this.parentIndex = intOrNull(o, 'parentIndex')
    this.grandparentTitle = stringOrNull(o, 'grandparentTitle')
    this.grandparentRatingKey = stringOrNull(o, 'grandparentRatingKey')
    this.grandparentThumb = stringOrNull(o, 'grandparentThumb')
    this.grandparentArt = stringOrNull(o, 'grandparentArt')
    this.Genre = tags(o, 'Genre')
    this.Director = tags(o, 'Director')
    this.Role = tags(o, 'Role')
  }

  get genres(): string[] { return this.Genre ?? [] }
  get directors(): string[] { return this.Director ?? [] }
  get actors(): string[] { return (this.Role ?? []).slice(0, 6) }
  get runtime(): number | null { return this.duration != null && this.duration > 0 ? this.duration / 1000 : null }
  get watched(): boolean { return (this.viewCount ?? 0) > 0 }
}
const metadata = (o: JSONObject) => new Metadata(o)
const showOf = (e: Metadata, showByKey: Map<string, Metadata>) =>
  e.grandparentRatingKey != null ? showByKey.get(e.grandparentRatingKey) ?? null : null
/** Kotlin `mapNotNull`'s filter half. */
const notNull = <T>(x: T | null): x is T => x != null

/** `/library/metadata/{key}` → Media[0].Part[0].Stream[]; streamType 1 video, 2 audio, 3 subtitle. */
interface Stream {
  readonly id: number
  readonly streamType: number
  readonly codec: string | null
  /** "English" / "eng" (ISO 639-2/B). */
  readonly language: string | null
  readonly languageCode: string | null
  /** title is a custom name ("Commentary"); displayTitle is Plex's summary ("English (AAC Stereo)"). */
  readonly title: string | null
  readonly displayTitle: string | null
  readonly default: boolean | null
  readonly forced: boolean | null
}
const stream = (o: JSONObject): Stream => ({
  id: getInt(o, 'id'), streamType: getInt(o, 'streamType'), codec: stringOrNull(o, 'codec'), language: stringOrNull(o, 'language'),
  languageCode: stringOrNull(o, 'languageCode'), title: stringOrNull(o, 'title'), displayTitle: stringOrNull(o, 'displayTitle'),
  default: boolOrNull(o, 'default'), forced: boolOrNull(o, 'forced'),
})
interface Part {
  readonly id: number
  readonly Stream: Stream[] | null
}

/** Runs `f` over `xs` with at most `slots` in flight; results in input order. The first failure stops new starts. */
async function inPool<T, R>(xs: readonly T[], slots: number, f: (x: T) => Promise<R>): Promise<R[]> {
  const out: R[] = new Array(xs.length)
  let next = 0
  const worker = async () => {
    while (next < xs.length) {
      const i = next++
      try { out[i] = await f(xs[i]) } catch (e) { next = xs.length; throw e }
    }
  }
  const workers: Promise<void>[] = []
  for (let n = 0; n < Math.min(slots, xs.length); n++) workers.push(worker())
  await Promise.all(workers)
  return out
}

export class PlexClient extends MediaServer {
  readonly kind: ServerKind = 'plex'

  constructor(
    readonly baseURL: string,
    readonly token: string,
    readonly clientID: string,
    readonly serverName: string = 'Plex',
    /** Streaming cap in kbit/s, 0 = original. Applied to transcode/direct-stream requests. */
    readonly maxBitrateKbps: number = 0,
  ) {
    super()
  }

  private request(path: string, query: readonly (readonly [string, string])[] = []): URLRequest {
    return plexRequest(plexURL(this.baseURL, path, query), this.token, this.clientID)
  }

  private async sections(): Promise<Directory[]> {
    return (await HTTP.json(this.request('/library/sections'), o => container(o, directory))).Directory ?? []
  }

  async libraries(): Promise<LibrarySection[]> {
    const dirs = await this.sections()
    // Counts come from a zero-size page's totalSize, all sections at once; a failed count is just null.
    const counts = await Promise.all(dirs.map(d => {
      // totalSize is only reported when paging via the container HEADERS (verified on PMS 1.43); the query form returns every item.
      const req = this.request(`/library/sections/${d.key}/all`)
      req.setValue('0', 'X-Plex-Container-Start')
      req.setValue('0', 'X-Plex-Container-Size')
      return runCatchingNonCancel(() => HTTP.json(req, o => container(o, metadata).totalSize))
    }))
    return dirs.map((d, i): LibrarySection => ({
      id: d.key, title: d.title ?? d.key,
      kind: d.type === 'movie' ? 'movies' : d.type === 'show' ? 'shows' : 'other',
      itemCount: counts[i],
    }))
  }

  /**
   * Sections load in parallel and join in section order, not finish order: shuffle channels keep library order, so a race
   * here would give every device (and every refresh) a different lineup.
   */
  async libraryItems(sections: readonly LibrarySection[] | null): Promise<MediaItem[]> {
    const dirs = await this.directories(sections)
    const lists = await Promise.all(dirs.map(d => this.items(d)))
    return ([] as MediaItem[]).concat(...lists)
  }

  /**
   * The newest `limit` movies or episodes of each section, by date added: one small page per section (plus one request for
   * the episodes' shows), so the app can run it every time it opens.
   */
  async recentlyAdded(sections: readonly LibrarySection[] | null, limit: number): Promise<MediaItem[]> {
    const out: MediaItem[] = []
    for (const d of await this.directories(sections)) {
      if (d.type === 'movie') {
        out.push(...((await this.page(d.key, 1, 0, limit, 'addedAt:desc')).Metadata ?? []).map(m => this.movie(m, d.key)).filter(notNull))
        continue
      }
      const episodes = (await this.page(d.key, 4, 0, limit, 'addedAt:desc')).Metadata ?? []
      const keys = stableSort(Array.from(new Set(episodes.map(e => e.grandparentRatingKey).filter(notNull))), swiftCompare)
      const shows = keys.length === 0 ? []
        : (await HTTP.json(this.request(`/library/metadata/${keys.join(',')}`), o => container(o, metadata))).Metadata ?? []
      const showByKey = new Map<string, Metadata>()
      for (const sh of shows) if (!showByKey.has(sh.ratingKey)) showByKey.set(sh.ratingKey, sh)
      out.push(...episodes.map(e => this.episode(e, showOf(e, showByKey), d.key)).filter(notNull))
    }
    return out
  }

  /** The movie and TV sections to load: the chosen ones, or every one the server has. */
  private async directories(sections: readonly LibrarySection[] | null): Promise<Directory[]> {
    if (sections == null) return (await this.sections()).filter(d => d.type === 'movie' || d.type === 'show')
    const out: Directory[] = []
    for (const s of sections) {
      if (s.kind === 'movies') out.push({ key: s.id, type: 'movie', title: s.title })
      else if (s.kind === 'shows') out.push({ key: s.id, type: 'show', title: s.title })
    }
    return out
  }

  private async items(section: Directory): Promise<MediaItem[]> {
    if (section.type === 'movie') return (await this.all(section.key, 1)).map(m => this.movie(m, section.key)).filter(notNull)
    // Genres, cast, rating and the blurb live on the show (type=2), not the episode (type=4); join on grandparentRatingKey.
    const [shows, episodes] = await Promise.all([this.all(section.key, 2), this.all(section.key, 4)])
    const showByKey = new Map<string, Metadata>()
    for (const s of shows) if (!showByKey.has(s.ratingKey)) showByKey.set(s.ratingKey, s)
    return episodes.map(e => this.episode(e, showOf(e, showByKey), section.key)).filter(notNull)
  }

  private movie(m: Metadata, section: string): MediaItem | null {
    const runtime = m.runtime
    if (runtime == null) return null
    return new MediaItem({
      id: m.ratingKey, kind: 'movie', title: MediaItem.title(m.title ?? 'Untitled', m.editionTitle), year: m.year, genres: m.genres,
      runtime, artworkPath: m.thumb, backdropPath: m.art, overview: m.summary, contentRating: m.contentRating,
      directors: m.directors, actors: m.actors, libraryID: section, addedAt: m.addedAt, watched: m.watched,
    })
  }

  private episode(e: Metadata, show: Metadata | null, section: string): MediaItem | null {
    const runtime = e.runtime
    if (runtime == null) return null
    return new MediaItem({
      id: e.ratingKey, kind: 'episode', title: e.title ?? 'Untitled', seriesTitle: e.grandparentTitle,
      seriesID: e.grandparentRatingKey, season: e.parentIndex, episode: e.index, year: e.year,
      genres: show?.genres ?? [], runtime, artworkPath: e.grandparentThumb ?? e.thumb,
      backdropPath: e.grandparentArt ?? e.art, overview: e.summary, contentRating: show?.contentRating,
      directors: show?.directors ?? [], actors: show?.actors ?? [], libraryID: section, addedAt: e.addedAt, watched: e.watched,
    })
  }

  /**
   * `/library/sections/{key}/all?type=` 500 at a time: page 0 learns `totalSize`, the rest load 4 at a time, joined in page
   * order. `excludeElements=Media` drops the Media/Part/Stream subtree (the bulk of an episode's JSON); everything `Metadata`
   * decodes, `duration` included, is an attribute or tag of the item itself.
   */
  private async all(key: string, type: number): Promise<Metadata[]> {
    const first = await this.page(key, type, 0)
    const out = first.Metadata ?? []
    // ponytail: a server's totalSize is capped at 2M items so a bad one can't allocate pages without end; raise it if a library is bigger.
    const starts: number[] = []
    for (let s = 500; s < Math.min(first.totalSize ?? 0, 2_000_000); s += 500) starts.push(s)
    if (out.length === 0 || starts.length === 0) return out
    // ponytail: 4 in flight per call; movies, shows and episodes fetch in parallel, so the browser's 6-per-host limit is the real ceiling.
    const pages = await inPool(starts, 4, async s => (await this.page(key, type, s)).Metadata ?? [])
    return out.concat(...pages)
  }

  private async page(key: string, type: number, start: number, size = 500, sort: string | null = null): Promise<Container<Metadata>> {
    const query: [string, string][] = [['type', `${type}`], ['excludeElements', 'Media']]
    if (sort != null) query.push(['sort', sort])
    const req = this.request(`/library/sections/${key}/all`, query)
    req.setValue(`${start}`, 'X-Plex-Container-Start')
    req.setValue(`${size}`, 'X-Plex-Container-Size')
    return HTTP.json(req, o => container(o, metadata))
  }

  /** First part's id and streams of `/library/metadata/{key}`; throws when the item has no playable part. */
  private async streams(key: string): Promise<Part> {
    const part = await HTTP.json(this.request(`/library/metadata/${key}`), o => {
      const parts = container(o, (m): Part | null => {
        const media = objectsOrNull(m, 'Media')?.[0]
        const p = media ? objectsOrNull(media, 'Part')?.[0] : undefined
        return p ? { id: getInt(p, 'id'), Stream: objectsOrNull(p, 'Stream')?.map(stream) ?? null } : null
      }).Metadata
      return parts?.[0] ?? null
    })
    if (part == null) throw MediaServerError.notFound(`media part for ${key}`)
    return part
  }

  async tracks(item: MediaItem): Promise<MediaTrack[]> {
    const out: MediaTrack[] = []
    for (const s of (await this.streams(item.id)).Stream ?? []) {
      const kind: MediaTrack.Kind | null = s.streamType === 2 ? 'audio' : s.streamType === 3 ? 'subtitle' : null
      if (kind == null) continue
      let title = s.displayTitle ?? s.title ?? s.language ?? s.codec ?? `Track ${s.id}`
      const t = s.title
      const d = s.displayTitle
      // "Commentary · English (AAC Stereo)", but the title alone once it names the summary or the language ("English
      // (SDH)" over "English", "Spanish (Castilian)" over "Español"), and the summary alone if it holds the title.
      if (t && d) {
        const has = (a: string, b: string) => a.toLowerCase().indexOf(b.toLowerCase()) >= 0
        const lang = MediaTrack.languageName(s.languageCode)
        title = has(t, d) || (lang != null && has(t, lang)) ? t : has(d, t) ? d : `${t} · ${d}`
      }
      out.push({ id: `${s.id}`, kind, language: s.languageCode, title, isDefault: s.default ?? false, isForced: s.forced ?? false })
    }
    return out
  }

  async playbackSource(item: MediaItem, startingAt: number, session: string, tracks: TrackSelection = new TrackSelection()): Promise<PlaybackSource> {
    // Server defaults (guide preview, trailers): no metadata round trip.
    if (!tracks.isDefault) {
      // The transcoder follows the part's *selected* streams, and the only way to pick them is the PUT every official
      // client makes (python-plexapi setSelectedAudioStream/setSelectedSubtitleStream). Side effect: it changes the part's
      // default audio/subtitle for this account server-wide, exactly as choosing a track in Plex Web does.
      // subtitleStreamID=0 clears the subtitle. A failed PUT just means server defaults; playback still starts.
      const partID = (await this.streams(item.id)).id
      const query: [string, string][] = [['allParts', '1']]
      if (tracks.audioID != null) query.push(['audioStreamID', tracks.audioID])
      const s = tracks.subtitle
      if (s.kind === 'track') query.push(['subtitleStreamID', s.id])
      else if (s.kind === 'off') query.push(['subtitleStreamID', '0'])
      const put = this.request(`/library/parts/${partID}`, query)
      put.httpMethod = 'PUT'
      try {
        await HTTP.data(put)
      } catch (e) {
        if (isCancellation(e)) throw e
        console.log(`PlexClient.playbackSource: select streams on part ${partID}: ${e}`)
      }
    }
    return this.transcodeSource(item.id, session, tracks.subtitle)
  }

  /** Best-effort `/video/:/transcode/universal/stop` for this device's `session`; PMS prunes stragglers itself. */
  async stopPlayback(session: string): Promise<void> {
    await runCatchingNonCancel(() => HTTP.data(this.request('/video/:/transcode/universal/stop', [['session', `${this.clientID}-${session}`]])))
  }

  /** Plex's own "mark as played": PUT /:/scrobble, keyed by the item's ratingKey. */
  async markWatched(item: MediaItem): Promise<void> {
    const put = this.request('/:/scrobble', [['identifier', 'com.plexapp.plugins.library'], ['key', item.id]])
    put.httpMethod = 'PUT'
    await HTTP.data(put)
  }

  /**
   * One request to `/library/metadata/{key}/extras`; prefers a trailer, falls back to any extra, null when there are none.
   * Episodes have no extras of their own, so look at the show.
   */
  async trailer(item: MediaItem, session: string): Promise<PlaybackSource | null> {
    const key = item.kind === 'episode' ? item.seriesID ?? item.id : item.id
    const extras = (await HTTP.json(this.request(`/library/metadata/${key}/extras`), o => container(o, metadata))).Metadata ?? []
    const extra = extras.find(e => e.subtype === 'trailer') ?? extras[0]
    if (!extra) return null
    return this.transcodeSource(extra.ratingKey, session)
  }

  /**
   * Universal-transcoder HLS start for any rating key (items and extras alike). Web: the transcode always starts at 0 and
   * the player seeks (`startsAtOffset` false). With `offset`, Plex's playlist still lists the whole file from segment 0 and
   * marks the offset only with EXT-X-START, which AVPlayer and ExoPlayer follow but Samsung's player ignores: it starts at
   * segment 0, which a transcode begun at the offset leaves empty, and stays black. A seek far ahead makes PMS restart the
   * transcode there (about 0.5 s, measured on the user's PMS 1.43).
   * The session id is stable per device + purpose, so a reload replaces the old transcode instead of adding one.
   * Subtitles: `track` → `subtitles=burn` (the HLS transcoder burns the part's selected text/PGS sub into the picture, so
   * `subtitleInPlaylist` is always false); `off` → `skipSubtitles=1`; `auto` → nothing. Both values are what
   * plex-for-kodi's buildTranscodeHls/buildTranscodeMkv send; `subtitles=none` is not seen anywhere, so it isn't used.
   */
  private async transcodeSource(ratingKey: string, session: string,
                                subtitle: TrackSelection.Subtitle = TrackSelection.Subtitle.auto): Promise<PlaybackSource> {
    // fMP4 HLS (container=mp4) with hevc in the target, so HEVC sources direct-stream instead of transcoding; HEVC over HLS
    // needs fMP4 per Apple's spec. Profile-Extra grammar as in Plex's own clients.
    const profile = 'add-transcode-target(type=videoProfile&context=streaming&protocol=hls&container=mp4' +
      `&videoCodec=${ClientDevice.videoCodecs}&audioCodec=${ClientDevice.audioCodecs})`
    // The guide preview is a muted ~800 px window: cap it at 720p / 4 Mbps (or lower if Settings says so) so browsing the
    // guide never spins up a full-rate transcode. Otherwise the Settings cap, 0 = original. directStream=1 stays, so only
    // sources above the cap transcode. ponytail: an h264 source above the cap transcodes rather than direct-streams, as intended.
    let cap = this.maxBitrateKbps
    if (session === 'preview') cap = cap > 0 ? Math.min(cap, 4000) : 4000
    const identity = plexIdentity()
    const query: [string, string][] = [
      ['path', `/library/metadata/${ratingKey}`], ['mediaIndex', '0'], ['partIndex', '0'],
      ['protocol', 'hls'], ['directPlay', '0'], ['directStream', '1'], ['fastSeek', '1'],
      ['offset', '0'], ['session', `${this.clientID}-${session}`],
      ['X-Plex-Client-Identifier', this.clientID], ['X-Plex-Token', this.token],
      ['X-Plex-Platform', identity['X-Plex-Platform']], ['X-Plex-Product', identity['X-Plex-Product']],
      ['X-Plex-Version', identity['X-Plex-Version']], ['X-Plex-Client-Profile-Extra', profile],
    ]
    if (cap > 0) query.push(['maxVideoBitrate', `${cap}`], ['videoResolution', cap <= 4000 ? '1280x720' : '1920x1080'])
    if (subtitle.kind === 'track') query.push(['subtitles', 'burn'])
    else if (subtitle.kind === 'off') query.push(['skipSubtitles', '1'])
    // PMS 1.43 can answer start.m3u8 with 400 unless this client first asked for a decision on the same item, as every
    // official client does: a best-effort GET of the same query on /decision. If it fails, start still gets its try.
    // Web: no `no-cors` needed. It's a simple GET (no custom headers, token in the query), so PMS acts on it whether or not
    // the browser may read the answer, and nothing reads it.
    await runCatchingNonCancel(() => HTTP.data(new URLRequest(plexURL(this.baseURL, '/video/:/transcode/universal/decision', query), 10)))
    return {
      url: plexURL(this.baseURL, '/video/:/transcode/universal/start.m3u8', query),
      startsAtOffset: false, subtitleInPlaylist: false,
    }
  }

  artworkURL(path: string | null | undefined): string | null {
    if (path == null) return null
    return plexURL(this.baseURL, '/photo/:/transcode', [
      ['width', '400'], ['height', '600'], ['minSize', '1'], ['url', path], ['X-Plex-Token', this.token],
    ])
  }
}
