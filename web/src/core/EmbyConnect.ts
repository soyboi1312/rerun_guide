// Port of android/core EmbyConnect.kt. connect.emby.media answers CORS with ACAO * and a fixed allow-list of request headers
// (X-Application, X-Connect-UserToken, Content-Type, Range): send nothing else, or the preflight fails.
import { JSONArray, asArray, asObject, getBoolean, getString, objects, stableSort, stringOrNull } from './Lang'
import { HTTP, MediaServerError, URLRequest, queryString } from './MediaServer'
import { swiftCompare } from './Models'
import { PlexAddress, PlexServer } from './PlexClient'

/**
 * Emby Connect's PIN sign-in, the TV flow Emby's own apps use and the Emby side of plex.tv/link: show a PIN, the viewer
 * types it at emby.media/pin while signed in to their Emby Connect account, then this device lists that account's servers
 * and trades a server's Connect key for an ordinary sign-in on it (`JellyfinClient.connectExchange`).
 * Emby doesn't document the PIN calls; these are the ones Emby.ApiClient.Java's ConnectService makes. A server only shows up
 * once its owner has linked an Emby Connect account to one of its users (Emby dashboard › Users › Emby Connect).
 */
export namespace EmbyConnect {
  export interface Pin {
    readonly code: string
    readonly deviceID: string
  }

  /** The Emby Connect account a confirmed PIN signed in. */
  export interface Account {
    readonly userID: string
    readonly token: string
  }

  /** A 5-digit code to show; it lasts a few minutes (`pollPIN` throws `notFound` once it has expired). */
  export async function requestPIN(deviceID: string): Promise<Pin> {
    const code = await HTTP.json(request('pin', [['deviceId', deviceID]], [['deviceId', deviceID]]), o => getString(asObject(o), 'Pin'))
    return { code, deviceID }
  }

  /** null while the viewer hasn't typed the code yet; the account once they have. Throws `notFound` when it expired. */
  export async function pollPIN(pin: Pin): Promise<Account | null> {
    let confirmed: boolean, expired: boolean
    try {
      [confirmed, expired] = await HTTP.json(request('pin', [['deviceId', pin.deviceID], ['pin', pin.code]]), o => {
        const p = asObject(o)
        return [getBoolean(p, 'IsConfirmed'), getBoolean(p, 'IsExpired')]
      })
    } catch (e) {
      if (e instanceof MediaServerError && e.kind === 'http' && e.code === 404) throw MediaServerError.notFound('Emby PIN (expired)')
      throw e
    }
    if (expired) throw MediaServerError.notFound('Emby PIN (expired)')
    if (!confirmed) return null
    return HTTP.json(request('pin/authenticate', [], [['deviceId', pin.deviceID], ['pin', pin.code]]), o => {
      const a = asObject(o)
      return { userID: getString(a, 'UserId'), token: getString(a, 'AccessToken') }
    })
  }

  /**
   * The servers linked to `account`, as rows for the server list: `accessToken` holds the server's Connect access key
   * (traded for a real sign-in by `JellyfinClient.connectExchange`), `addresses` its LAN address first, then its remote one.
   */
  export async function servers(account: Account): Promise<PlexServer[]> {
    const r = request('servers', [['userId', account.userID]])
    r.setValue(account.token, 'X-Connect-UserToken')
    return rows(await HTTP.json(r, asArray))
  }

  /** Kotlin `internal`: exported for the tests. */
  export function rows(servers: JSONArray): PlexServer[] {
    const out: PlexServer[] = []
    for (const s of objects(servers)) {
      const key = stringOrNull(s, 'AccessKey')
      const id = stringOrNull(s, 'SystemId')
      if (!key || id == null) continue
      const addresses: PlexAddress[] = []
      const lan = url(stringOrNull(s, 'LocalAddress'))
      if (lan != null) addresses.push({ url: lan, local: true })
      const remote = url(stringOrNull(s, 'Url'))
      if (remote != null) addresses.push({ url: remote, local: false })
      const first = addresses[0]
      if (!first) continue
      out.push({ id, name: stringOrNull(s, 'Name') ?? hostOf(first.url) ?? 'Emby', baseURL: first.url, accessToken: key, addresses })
    }
    return out
  }
}

/** java.net.URL(u).host, or null. */
function hostOf(u: string): string | null {
  try { return new URL(u).hostname || null } catch { return null }
}

/** Trimmed, without trailing slashes, when it has a host (like URL(string:)?.host); the string itself, never `URL.href`. */
function url(s: string | null): string | null {
  if (s == null) return null
  const a = s.trim().replace(/\/+$/, '')
  return hostOf(a) ? a : null
}

/** connect.emby.media/service/<path>; `form` makes it a form-encoded POST, as Emby's clients send these. */
function request(path: string, query: [string, string][] = [], form: [string, string][] | null = null): URLRequest {
  const byName = (x: [string, string], y: [string, string]) => swiftCompare(x[0], y[0])
  const r = new URLRequest(`https://connect.emby.media/service/${path}` + queryString(stableSort(query, byName)), 20)
  r.setValue('RerunGuide/1.0', 'X-Application')
  r.setValue('application/json', 'Accept')
  if (form != null) {
    r.httpMethod = 'POST'
    r.httpBody = queryString(stableSort(form, byName)).slice(1)
    r.setValue('application/x-www-form-urlencoded', 'Content-Type')
  }
  return r
}
