// Port of android/core MediaServer.kt. `fetch` replaces HttpURLConnection: it follows redirects itself (301/302/303 turn
// a POST into a GET, 307/308 keep it, as URLSession does), so Kotlin's redirect loop is gone.
import { MediaItem, LibrarySection, MediaTrack, PlaybackSource, ServerKind, TrackSelection } from './Models'

/**
 * What the servers' device lists call this client: X-Plex-Platform / X-Plex-Device, the MediaBrowser `Device`, and
 * which codecs to ask for. `Platform.init()` sets them per TV (MainActivity does it on Android).
 */
export const ClientDevice = {
  platform: 'Android',
  device: 'Android',
  /** What this device's decoders play: the servers transcode everything else. */
  videoCodecs: 'h264,hevc',
  audioCodecs: 'aac,ac3,eac3',
  /** Direct play may also carry lossless audio in mp4. */
  directPlayAudioCodecs: 'aac,ac3,eac3,alac,flac',
}

/**
 * Every backend (Plex, Emby, Jellyfin, demo) implements this. The guide and player only ever see this interface.
 * A Kotlin interface with default methods, so an abstract class here: backends `extends MediaServer`.
 */
export abstract class MediaServer {
  abstract readonly kind: ServerKind
  /** Human name shown in Settings ("Living Room", "Basement"); Kotlin's default is `kind.displayName`, every backend sets its own. */
  abstract readonly serverName: string
  /** Libraries/sections the user can put on the air. */
  async libraries(): Promise<LibrarySection[]> { return [] }
  /** Every movie and episode in `sections` (null = every library). Called at launch and on "Rebuild"; the app caches it. */
  abstract libraryItems(sections: readonly LibrarySection[] | null): Promise<MediaItem[]>
  /**
   * The newest items in `sections` (null = every library) by date added: at most `limit` movies and `limit` episodes per
   * library, cheap enough to ask every time the app opens. Feeds the "what's new" channels between full loads.
   */
  async recentlyAdded(sections: readonly LibrarySection[] | null, limit: number): Promise<MediaItem[]> { return [] }
  /**
   * Stream URL for `item`, starting `startingAt` seconds in (the guide joins shows mid-stream).
   * `session` is a caller-chosen label ("preview", "watch") so a backend keeps one server-side stream per purpose.
   * Default `tracks` = server-default tracks (the guide preview, trailers).
   */
  abstract playbackSource(item: MediaItem, startingAt: number, session: string, tracks?: TrackSelection): Promise<PlaybackSource>
  /** Audio and subtitle tracks of `item`, for the picker. Empty when the backend can't say. */
  async tracks(item: MediaItem): Promise<MediaTrack[]> { return [] }
  /** A trailer for `item` (local extra or remote trailer the server can stream), or null when it has none. */
  async trailer(item: MediaItem, session: string): Promise<PlaybackSource | null> { return null }
  /** Best-effort release of the server-side stream for `session`; no-op for backends without one. */
  async stopPlayback(session: string): Promise<void> {}
  /** Marks `item` (an episode itself, not its show) watched for the signed-in user, as the server's own "mark as
   *  watched" does. No-op for the demo. */
  async markWatched(item: MediaItem): Promise<void> {}
  /** Absolute, authenticated URL for a server-relative artwork path, or null. */
  abstract artworkURL(path: string | null | undefined): string | null
  /** Wide art for the preview window, falling back to the poster. */
  backdropURL(item: MediaItem): string | null { return this.artworkURL(item.backdropPath ?? item.artworkPath) }
  /**
   * Web: the WebVTT of subtitle track `trackID` of `itemID` when the app draws it over the picture itself (a text subtitle
   * on a server that serves one), else null and the stream burns it in. Answers after `tracks(item)`.
   */
  drawnSubtitleURL(itemID: string, trackID: string): string | null { return null }
  /** Web: a drawn subtitle or none leaves the stream as it is (Jellyfin/Emby ask for subtitle -1 either way), so switching
   *  between them needs no reload. */
  readonly drawsSubtitles: boolean = false
}

/** Kotlin's sealed MediaServerError: `throw MediaServerError.http(code, body)`, `e instanceof MediaServerError && e.kind === 'http'`. */
export class MediaServerError extends Error {
  private constructor(
    readonly kind: 'unauthorized' | 'http' | 'invalidResponse' | 'notFound',
    readonly errorDescription: string,
    /** http */ readonly code = 0,
    /** http */ readonly body = '',
    /** invalidResponse */ readonly why = '',
    /** notFound */ readonly what = '',
  ) {
    super(errorDescription)
    this.name = 'MediaServerError'
  }
  static unauthorized() { return new MediaServerError('unauthorized', 'Not authorized. Sign in again.') }
  static http(code: number, body: string) { return new MediaServerError('http', `Server returned ${code}`, code, body) }
  static invalidResponse(why: string) { return new MediaServerError('invalidResponse', `Unexpected response: ${why}`, 0, '', why) }
  static notFound(what: string) { return new MediaServerError('notFound', `Not found: ${what}`, 0, '', '', what) }
}

/** `URLRequest`, as much of it as the clients use. */
export class URLRequest {
  httpMethod = 'GET'
  httpBody: string | null = null
  readonly headers: { [field: string]: string } = {}
  /** Web: cancels the request, as cancelling the coroutine does in Kotlin (`runCatchingNonCancel` rethrows it). */
  signal: AbortSignal | null = null

  constructor(public url: string, public timeoutInterval = 60) {}

  setValue(value: string | null, forHTTPHeaderField: string) {
    if (value == null) delete this.headers[forHTTPHeaderField]
    else this.headers[forHTTPHeaderField] = value
  }
}

function timeoutError(): Error {
  const e = new Error('The request timed out.')
  e.name = 'TimeoutError'   // an IOException in Kotlin: `runCatchingNonCancel` swallows it, unlike a cancel
  return e
}

/** Minimal shared HTTP helper. Throws `MediaServerError` on non-2xx. */
export const HTTP = {
  /**
   * The response body as text. `timeoutInterval` is the longest wait for the answer or its next bytes (HttpURLConnection's
   * read timeout, URLRequest's), so a big library page on a slow link keeps loading while bytes arrive. Cookies are never sent.
   */
  async data(request: URLRequest): Promise<string> {
    const outer = request.signal
    if (outer?.aborted) throw abortError()
    const ctrl = new AbortController()
    const cancel = () => ctrl.abort()
    outer?.addEventListener('abort', cancel)
    let timedOut = false
    let timer: ReturnType<typeof setTimeout> | undefined
    const arm = () => {
      clearTimeout(timer)
      timer = setTimeout(() => { timedOut = true; ctrl.abort() }, request.timeoutInterval * 1000)
    }
    arm()
    try {
      const r = await fetch(request.url, {
        method: request.httpMethod, headers: request.headers, body: request.httpBody ?? undefined,
        credentials: 'omit', redirect: 'follow', signal: ctrl.signal,
      })
      arm()
      const body = await readText(r, arm)
      if (!r.ok) {
        if (r.status === 401 || r.status === 403) throw MediaServerError.unauthorized()
        throw MediaServerError.http(r.status, body)
      }
      return body
    } catch (e) {
      throw timedOut ? timeoutError() : e
    } finally {
      clearTimeout(timer)
      outer?.removeEventListener('abort', cancel)
    }
  },

  /**
   * The response parsed with `JSON.parse`, handed to `decode`. A parse failure or a decode failure (missing key, wrong
   * type) is `invalidResponse`, as Swift's Decodable failures are; the message never quotes the body.
   */
  async json<T>(request: URLRequest, decode: (json: unknown) => T): Promise<T> {
    let text = await HTTP.data(request)
    if (text.charCodeAt(0) === 0xfeff) text = text.slice(1)
    try {
      return decode(JSON.parse(text))
    } catch (e) {
      if (e instanceof MediaServerError || isCancellation(e)) throw e
      throw MediaServerError.invalidResponse(e instanceof Error ? e.name : 'Error')
    }
  },
}

/** `r.text()` (UTF-8, a leading BOM dropped), calling `progress` as each chunk arrives. */
async function readText(r: Response, progress: () => void): Promise<string> {
  const reader = r.body?.getReader()
  if (!reader) return r.text()
  const utf8 = new TextDecoder()
  let s = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) return s + utf8.decode()
    progress()
    s += utf8.decode(value, { stream: true })
  }
}

function abortError(): Error {
  const e = new Error('The operation was aborted.')
  e.name = 'AbortError'
  return e
}

/** An aborted `fetch` (our `URLRequest.signal`): Kotlin's CancellationException. */
export function isCancellation(e: unknown): boolean {
  return typeof e === 'object' && e !== null && (e as { name?: unknown }).name === 'AbortError'
}

/** Swift `try?` for async calls that must still honour cancellation. */
export async function runCatchingNonCancel<T>(block: () => Promise<T>): Promise<T | null> {
  try {
    return await block()
  } catch (e) {
    if (isCancellation(e)) throw e
    return null
  }
}

/** `URLComponents.queryItems` → "?a=1&b=2", every reserved character percent-encoded (servers decode either way). */
export function queryString(query: readonly (readonly [string, string])[]): string {
  return query.length === 0 ? '' : '?' + query.map(([k, v]) => `${percentEncode(k)}=${percentEncode(v)}`).join('&')
}

/**
 * RFC 3986 unreserved characters stay; everything else is %XX of its UTF-8 bytes, uppercase. A lone surrogate becomes
 * "%3F" ("?"), as Java's URLEncoder writes it, instead of `encodeURIComponent`'s URIError.
 */
export function percentEncode(s: string): string {
  return encodeURIComponent(s.replace(/[\ud800-\udbff](?![\udc00-\udfff])|(?<![\ud800-\udbff])[\udc00-\udfff]/g, '?'))
    .replace(/[!'()*]/g, c => '%' + c.charCodeAt(0).toString(16).toUpperCase())
}
