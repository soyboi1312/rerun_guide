// Port of android/core JellyfinClient.kt. Web change: the device profile asks for no subtitle renditions, so subtitles burn
// in and `subtitleInPlaylist` is always false (no textTracks code on the TVs; see `deviceProfile`).
import {
  JSONException, JSONObject, arrayOrNull, asArray, asObject, boolOrNull, getBoolean, getInt, getJSONArray, getJSONObject, getString,
  has, intOrNull, longOrNull, objectOrNull, objects, objectsOrNull, replaceAll, stableSort, stringOrNull, strings, toIntOrNull,
} from './Lang'
import {
  ClientDevice, HTTP, MediaServer, MediaServerError, URLRequest, isCancellation, queryString, runCatchingNonCancel,
} from './MediaServer'
import { LibrarySection, MediaItem, MediaTrack, PlaybackSource, ServerKind, TrackSelection, swiftCompare } from './Models'
import { Instant } from './Time'
import { PlexAddress, PlexServer } from './PlexClient'

const SERIES_FIELDS = 'Genres,People,OfficialRating'
const FETCHES: readonly (readonly [string, string])[] = [
  ['Movie', 'Genres,People,Overview,ProductionYear,OfficialRating,DateCreated,Path'],
  ['Episode', 'Overview,SeriesPrimaryImage,ProductionYear,OfficialRating,DateCreated'],
]

type Query = { [name: string]: string }

/** Auth result. */
interface Auth {
  readonly AccessToken: string
  readonly UserId: string
}
function auth(o: unknown): Auth {
  const a = asObject(o)
  return { AccessToken: getString(a, 'AccessToken'), UserId: getString(getJSONObject(a, 'User'), 'Id') }
}

/** Wire shape of a BaseItemDto; property names mirror the JSON. */
class Item {
  readonly Id: string
  readonly Name: string | null
  readonly Type: string
  readonly SeriesName: string | null
  readonly SeriesId: string | null
  readonly ParentIndexNumber: number | null
  readonly IndexNumber: number | null
  readonly ProductionYear: number | null
  readonly RunTimeTicks: number | null
  readonly Genres: string[] | null
  readonly ImageTags: JSONObject | null
  readonly SeriesPrimaryImageTag: string | null
  readonly BackdropImageTags: string[] | null
  readonly ParentBackdropImageTags: string[] | null
  readonly ParentBackdropItemId: string | null
  readonly Overview: string | null
  readonly OfficialRating: string | null
  /** (Name, Type) pairs. */
  readonly People: [string | null, string | null][] | null
  readonly DateCreated: string | null
  /** The file, for a film's cut ({edition-…}); movies only. */
  readonly Path: string | null
  /** This user's state. Both servers send it on /Users/{id}/Items unless EnableUserData=false (verified on Emby 4.10). */
  readonly UserData: { readonly Played: boolean | null } | null

  constructor(o: JSONObject) {
    this.Id = getString(o, 'Id')
    this.Name = stringOrNull(o, 'Name')
    this.Type = getString(o, 'Type')
    this.SeriesName = stringOrNull(o, 'SeriesName')
    this.SeriesId = stringOrNull(o, 'SeriesId')
    this.ParentIndexNumber = intOrNull(o, 'ParentIndexNumber')
    this.IndexNumber = intOrNull(o, 'IndexNumber')
    this.ProductionYear = intOrNull(o, 'ProductionYear')
    this.RunTimeTicks = longOrNull(o, 'RunTimeTicks')
    this.Genres = arrayOrNull(o, 'Genres') ? strings(o, 'Genres') : null
    this.ImageTags = objectOrNull(o, 'ImageTags')
    this.SeriesPrimaryImageTag = stringOrNull(o, 'SeriesPrimaryImageTag')
    this.BackdropImageTags = arrayOrNull(o, 'BackdropImageTags') ? strings(o, 'BackdropImageTags') : null
    this.ParentBackdropImageTags = arrayOrNull(o, 'ParentBackdropImageTags') ? strings(o, 'ParentBackdropImageTags') : null
    this.ParentBackdropItemId = stringOrNull(o, 'ParentBackdropItemId')
    this.Overview = stringOrNull(o, 'Overview')
    this.OfficialRating = stringOrNull(o, 'OfficialRating')
    this.People = objectsOrNull(o, 'People')?.map((p): [string | null, string | null] => [stringOrNull(p, 'Name'), stringOrNull(p, 'Type')]) ?? null
    this.DateCreated = stringOrNull(o, 'DateCreated')
    this.Path = stringOrNull(o, 'Path')
    const userData = objectOrNull(o, 'UserData')
    this.UserData = userData == null ? null : { Played: boolOrNull(userData, 'Played') }
  }

  /** Emby writes 7 fractional digits ("2026-02-12T08:00:00.0000000Z"); keep the whole seconds, as the Swift client does. */
  get added(): Instant | null {
    const s = this.DateCreated?.slice(0, 19)
    if (s == null || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/.test(s)) return null   // Instant.parse's strict shape
    const ms = Date.parse(s + 'Z')
    return isFinite(ms) ? ms / 1000 : null
  }

  get hasBackdrop(): boolean { return (this.BackdropImageTags ?? []).length > 0 }
  people(type: string): string[] {
    return (this.People ?? []).filter(p => p[1] === type).map(p => p[0]).filter((n): n is string => n != null)
  }
}
interface Page {
  readonly Items: Item[]
  readonly TotalRecordCount: number
}
function page(o: unknown): Page {
  const p = asObject(o)
  return { Items: objects(getJSONArray(p, 'Items')).map(i => new Item(i)), TotalRecordCount: getInt(p, 'TotalRecordCount') }
}

interface Stream {
  readonly Index: number
  readonly Type: string
  readonly Codec: string | null
  readonly Language: string | null
  readonly Title: string | null
  readonly DisplayTitle: string | null
  readonly DisplayLanguage: string | null
  readonly IsDefault: boolean | null
  readonly IsForced: boolean | null
  /** A text subtitle (SubRip, ASS, WebVTT…): the TV apps draw it themselves instead of having it burned in. */
  readonly IsTextSubtitleStream: boolean | null
}
const stream = (o: JSONObject): Stream => ({
  Index: getInt(o, 'Index'), Type: getString(o, 'Type'), Codec: stringOrNull(o, 'Codec'), Language: stringOrNull(o, 'Language'),
  Title: stringOrNull(o, 'Title'), DisplayTitle: stringOrNull(o, 'DisplayTitle'), DisplayLanguage: stringOrNull(o, 'DisplayLanguage'),
  IsDefault: boolOrNull(o, 'IsDefault'), IsForced: boolOrNull(o, 'IsForced'), IsTextSubtitleStream: boolOrNull(o, 'IsTextSubtitleStream'),
})

/** java.net.URL(u).host without IPv6 brackets, or null. (WHATWG lowercases it; it only names a server.) */
function hostOf(u: string): string | null {
  try { return new URL(u).hostname.replace(/^\[(.*)\]$/, '$1') || null } catch { return null }
}

/** Emby and Jellyfin share one client; `kind` selects the few places they differ. */
export class JellyfinClient extends MediaServer {
  readonly baseURL: string
  readonly serverName: string

  /** `session` label → PlaySessionId of the transcode last started for it, so the next load (or stopPlayback) can kill it. */
  private readonly sessions = new Map<string, string>()
  /**
   * MediaSources[0].Id per item, filled by `tracks(item)`. Emby (verified on 4.10) only honours AudioStreamIndex /
   * SubtitleStreamIndex when the PlaybackInfo query also names the MediaSourceId; Jellyfin doesn't mind it.
   */
  private readonly sourceIDs = new Map<string, string>()
  /** The text subtitle stream indexes per item, filled by `tracks(item)`: these are drawn by the app, never burned in. */
  private readonly textSubtitles = new Map<string, Set<number>>()
  readonly drawsSubtitles = true

  drawnSubtitleURL(itemID: string, trackID: string): string | null {
    const index = toIntOrNull(trackID), sid = this.sourceIDs.get(itemID)
    if (index == null || sid == null || this.textSubtitles.get(itemID)?.has(index) !== true) return null
    return this.url(`/Videos/${itemID}/${sid}/Subtitles/${index}/Stream.vtt`, { api_key: this.token })
  }

  constructor(
    baseURL: string,
    readonly token: string,
    readonly userID: string,
    readonly deviceID: string,
    readonly kind: ServerKind,
    serverName = '',
    /** Streaming cap in kbit/s, 0 = original. Sent as MaxStreamingBitrate in PlaybackInfo and its DeviceProfile. */
    readonly maxBitrateKbps = 0,
  ) {
    super()
    if (kind !== 'emby' && kind !== 'jellyfin') throw new Error('Failed requirement.')
    this.baseURL = baseURL.endsWith('/') ? baseURL.slice(0, -1) : baseURL
    this.serverName = serverName || hostOf(baseURL) || ServerKind.displayName(kind)
  }

  // MARK: - Sign-in

  /**
   * Emby (verified on 4.10) restarts the encoder of an fMP4 HLS stream mid-play with segments whose tfdt starts over at 0 and
   * no EXT-X-DISCONTINUITY; players freeze on the jump back. Its TS segments keep the source timestamps across restarts,
   * and the TVs decode HEVC in TS, so VideoCodec stays as asked. A WebVTT rendition goes TS on Jellyfin too: both servers
   * stamp every subtitle segment X-TIMESTAMP-MAP=MPEGTS:900000, which matches TS video and puts cues 10 s late over fMP4.
   * ponytail: a Jellyfin HEVC source keeps fMP4 and late cues, as in the Swift client.
   * Only a selected subtitle the server lists as an HLS rendition (SubtitleMethod=Hls) is in the playlist; a burn-in
   * (Encode) is in the picture. Returns the rewritten TranscodingUrl path and whether the pick is a playlist rendition.
   * Web: the device profile offers no renditions, so the server always burns in and the second value is false.
   */
  static segments(path: string, kind: ServerKind, picked: boolean, hevc: boolean): [string, boolean] {
    const hlsSubs = picked && path.indexOf('SubtitleMethod=Hls') >= 0
    const ts = kind === 'emby' || (hlsSubs && !hevc)
    return [ts ? replaceAll(path, 'SegmentContainer=mp4', 'SegmentContainer=ts') : path, hlsSubs]
  }

  /** Username/password sign-in. Returns a ready client; persist `token` and `userID`. */
  static async login(baseURL: string, username: string, password: string, deviceID: string, kind: ServerKind): Promise<JellyfinClient> {
    const anon = new JellyfinClient(baseURL, '', '', deviceID, kind)
    const body = JSON.stringify({ Username: username, Pw: password })
    return anon.signedIn(await HTTP.json(anon.request('/Users/AuthenticateByName', {}, 'POST', body), auth))
  }

  static async quickConnectStart(baseURL: string, deviceID: string): Promise<JellyfinClient.QuickConnectSession> {
    const anon = JellyfinClient.anonymous(baseURL, deviceID)
    const enabled = await HTTP.json(anon.request('/QuickConnect/Enabled'), v => {
      if (typeof v !== 'boolean') throw new JSONException('not a boolean')
      return v
    })
    if (!enabled) throw MediaServerError.notFound('Quick Connect')
    const start = (o: unknown): JellyfinClient.QuickConnectSession => {
      const s = asObject(o)
      return { code: getString(s, 'Code'), secret: getString(s, 'Secret') }
    }
    try {
      return await HTTP.json(anon.request('/QuickConnect/Initiate', {}, 'POST'), start)
    } catch (e) {
      if (!(e instanceof MediaServerError && e.kind === 'http' && e.code === 405)) throw e
      return HTTP.json(anon.request('/QuickConnect/Initiate'), start)   // 10.8 only routes GET
    }
  }

  /** true once the user has approved the code. Throws `notFound` when the code has expired. */
  static async quickConnectIsApproved(baseURL: string, secret: string, deviceID: string): Promise<boolean> {
    const anon = JellyfinClient.anonymous(baseURL, deviceID)
    try {
      return await HTTP.json(anon.request('/QuickConnect/Connect', { secret }), o => getBoolean(asObject(o), 'Authenticated'))
    } catch (e) {
      if (e instanceof MediaServerError && e.kind === 'http' && e.code === 404) throw MediaServerError.notFound('Quick Connect code (expired)')
      throw e
    }
  }

  static async loginWithQuickConnect(baseURL: string, secret: string, deviceID: string): Promise<JellyfinClient> {
    const anon = JellyfinClient.anonymous(baseURL, deviceID)
    const body = JSON.stringify({ Secret: secret })
    return anon.signedIn(await HTTP.json(anon.request('/Users/AuthenticateWithQuickConnect', {}, 'POST', body), auth))
  }

  /**
   * Emby Connect (Emby only): trade a server's Connect access key (`EmbyConnect.servers`) for a local sign-in, trying its
   * addresses in order, LAN first, with short timeouts so a viewer away from home doesn't sit on the LAN one.
   * A LAN address gets the key only if its /System/Info/Public, asked without a token, names this server.
   */
  static async connectExchange(server: PlexServer, connectUserID: string, deviceID: string): Promise<JellyfinClient> {
    let last: unknown = MediaServerError.notFound(`an address for ${server.name}`)
    let answered: unknown = null   // a server's own refusal says more than another address timing out
    const addresses: readonly PlexAddress[] = server.addresses?.length ? server.addresses : [{ url: server.baseURL, local: false }]
    for (const a of addresses) {
      if (a.local) {
        const probe = new JellyfinClient(a.url, '', '', deviceID, 'emby').request('/System/Info/Public')
        probe.timeoutInterval = 4
        if (await runCatchingNonCancel(() => HTTP.json(probe, o => stringOrNull(asObject(o), 'Id'))) !== server.id) continue
      }
      const keyed = new JellyfinClient(a.url, server.accessToken, '', deviceID, 'emby', server.name)
      const r = keyed.request('/Connect/Exchange', { format: 'json', ConnectUserId: connectUserID })
      r.timeoutInterval = a.local ? 4 : 10
      try {
        const [user, token] = await HTTP.json(r, o => {
          const x = asObject(o)
          return [getString(x, 'LocalUserId'), getString(x, 'AccessToken')]
        })
        return new JellyfinClient(a.url, token, user, deviceID, 'emby', server.name)
      } catch (e) {
        if (isCancellation(e)) throw e
        if (e instanceof MediaServerError) answered = answered ?? e
        else last = e
      }
    }
    throw answered ?? last
  }

  private static anonymous(baseURL: string, deviceID: string): JellyfinClient {
    return new JellyfinClient(baseURL, '', '', deviceID, 'jellyfin')
  }

  /**
   * TV-player-shaped profile: mp4/mov direct play only (no mkv); everything else becomes HLS, fMP4 first (HEVC is only legal
   * in fMP4 per Apple HLS spec 1.5), ts h264-only as the fallback for servers without fMP4 HLS. The HLS playlist is
   * full-length VOD starting at 0 (StartTimeTicks is ignored for hls); the player seeks to the offset.
   * Web: no subtitle renditions. The Kotlin profile has "ManifestSubtitles":"vtt" on both HLS profiles and a vtt/Hls
   * SubtitleProfile so text subs ride along as WebVTT renditions; the TVs' native HLS players don't reliably expose those as
   * textTracks, so this one has neither, and the servers burn a picked subtitle into the picture (SubtitleMethod=Encode: a
   * full re-encode). ponytail: upgrade path is {"Format":"vtt","Method":"External"} and drawing the cues ourselves.
   */
  private static deviceProfile(maxBitrate: number): JSONObject {
    const hls = (container: string, videoCodec: string) => ({
      Type: 'Video', Container: container, Protocol: 'hls', VideoCodec: videoCodec, AudioCodec: ClientDevice.audioCodecs,
      Context: 'Streaming', MinSegments: 2, BreakOnNonKeyFrames: true, MaxAudioChannels: '6',
    })
    return {
      MaxStreamingBitrate: maxBitrate,
      DirectPlayProfiles: [{ Type: 'Video', Container: 'mp4,m4v,mov', VideoCodec: ClientDevice.videoCodecs, AudioCodec: ClientDevice.directPlayAudioCodecs }],
      TranscodingProfiles: [hls('mp4', ClientDevice.videoCodecs), hls('ts', 'h264')],
      CodecProfiles: [], SubtitleProfiles: [],
    }
  }

  /** Auth result → ready client, named after the server when it will say (best-effort; host otherwise). */
  private async signedIn(a: Auth): Promise<JellyfinClient> {
    const name = await runCatchingNonCancel(() => HTTP.json(this.request('/System/Info/Public'), o => stringOrNull(asObject(o), 'ServerName'))) ?? ''
    return new JellyfinClient(this.baseURL, a.AccessToken, a.UserId, this.deviceID, this.kind, name)
  }

  // MARK: - Library

  async libraries(): Promise<LibrarySection[]> {
    const views = await HTTP.json(this.request(`/Users/${this.userID}/Views`), o =>
      objects(getJSONArray(asObject(o), 'Items')).map(v => ({ Id: getString(v, 'Id'), Name: stringOrNull(v, 'Name'), CollectionType: stringOrNull(v, 'CollectionType') })))
    // Counts are a nicety: one request per view, in parallel, null when a view won't count.
    const counts = await Promise.all(views.map(v => runCatchingNonCancel(() => this.count(v.Id))))
    return views.map((v, i): LibrarySection => ({
      id: v.Id, title: v.Name ?? '',
      kind: v.CollectionType === 'movies' ? 'movies' : v.CollectionType === 'tvshows' ? 'shows' : 'other',
      itemCount: counts[i],
    }))
  }

  private async count(viewID: string): Promise<number> {
    return (await HTTP.json(this.request(`/Users/${this.userID}/Items`, {
      ParentId: viewID, Recursive: 'true', IncludeItemTypes: 'Movie,Series', IsMissing: 'false', Limit: '1',
    }), page)).TotalRecordCount
  }

  async libraryItems(sections: readonly LibrarySection[] | null): Promise<MediaItem[]> {
    // Episodes usually carry no Genres/People/rating of their own; inherit from the series. Episodes are also not asked for
    // Genres/People: a 10k-episode library's per-episode cast list is tens of MB of JSON on every library load.
    const series = new Map<string, Item>()
    for (const s of await this.items('Series', SERIES_FIELDS)) if (!series.has(s.Id)) series.set(s.Id, s)
    const out: MediaItem[] = []
    // ponytail: sections fetched one after another; parallel if many large views make launch slow
    for (const parent of sections?.map(s => s.id) ?? [null]) {
      for (const [types, fields] of FETCHES) {
        for (const i of await this.items(types, fields, parent)) {
          const m = this.mediaItem(i, series, parent)
          if (m) out.push(m)
        }
      }
    }
    return out
  }

  /**
   * The newest `limit` movies and `limit` episodes of each library, by date added, plus their series: a few small requests,
   * so the app can run it every time it opens.
   */
  async recentlyAdded(sections: readonly LibrarySection[] | null, limit: number): Promise<MediaItem[]> {
    const found: [Item, string | null][] = []
    for (const parent of sections?.map(s => s.id) ?? [null]) {
      for (const [types, fields] of FETCHES) {
        for (const i of await this.items(types, fields, parent, limit)) found.push([i, parent])
      }
    }
    const ids = stableSort(Array.from(new Set(found.map(f => f[0].SeriesId).filter((id): id is string => id != null))), swiftCompare)
    const series = new Map<string, Item>()
    if (ids.length > 0) for (const s of await this.items('Series', SERIES_FIELDS, null, null, ids)) if (!series.has(s.Id)) series.set(s.Id, s)
    const out: MediaItem[] = []
    for (const [i, parent] of found) {
      const m = this.mediaItem(i, series, parent)
      if (m) out.push(m)
    }
    return out
  }

  private mediaItem(i: Item, series: ReadonlyMap<string, Item>, parent: string | null): MediaItem | null {
    const or = <C extends { length: number }>(a: C | null | undefined, b: C | null | undefined): C | null | undefined => a != null && a.length > 0 ? a : b
    const backdrop = (id: string) => `/Items/${id}/Images/Backdrop/0`
    const ticks = i.RunTimeTicks
    if (ticks == null || ticks <= 0) return null
    const isEpisode = i.Type === 'Episode'
    const show = isEpisode && i.SeriesId != null ? series.get(i.SeriesId) ?? null : null
    const own = i.ImageTags != null && has(i.ImageTags, 'Primary') ? `/Items/${i.Id}/Images/Primary` : null
    const art = isEpisode && i.SeriesPrimaryImageTag != null && i.SeriesId != null ? `/Items/${i.SeriesId}/Images/Primary` : own
    const parentBackdrop = i.ParentBackdropItemId ?? i.SeriesId
    const wide = i.hasBackdrop ? backdrop(i.Id)
      : (i.ParentBackdropImageTags ?? []).length > 0 && parentBackdrop != null ? backdrop(parentBackdrop)
      : show != null && show.hasBackdrop ? backdrop(show.Id)
      : null
    return new MediaItem({
      id: i.Id, kind: isEpisode ? 'episode' : 'movie',
      title: isEpisode ? i.Name ?? '' : MediaItem.title(i.Name ?? '', MediaItem.edition(i.Path)),
      seriesTitle: i.SeriesName, seriesID: i.SeriesId, season: i.ParentIndexNumber, episode: i.IndexNumber,
      year: i.ProductionYear, genres: or(show?.Genres, i.Genres) ?? [],
      runtime: ticks / 10_000_000, artworkPath: art, backdropPath: wide,
      overview: or(i.Overview, null), contentRating: or(i.OfficialRating, show?.OfficialRating),
      directors: or(i.people('Director'), show?.people('Director')) ?? [],
      actors: (or(i.people('Actor'), show?.people('Actor')) ?? []).slice(0, 6),
      libraryID: parent, addedAt: i.added, watched: i.UserData?.Played === true,
    })
  }

  /**
   * Pages through /Users/{id}/Items, optionally under one view. Jellyfin always sends OfficialRating/ProductionYear; Emby
   * (verified on 4.10) omits them unless they are listed in Fields, and both ignore Fields values they don't know.
   * `newest`: one page of that many, newest first by date added. `ids`: only those items.
   */
  private async items(types: string, fields: string, parent: string | null = null, newest: number | null = null,
                      ids: readonly string[] | null = null): Promise<Item[]> {
    let all: Item[] = []
    for (;;) {
      const query: Query = {
        Recursive: 'true', IncludeItemTypes: types, IsMissing: 'false', SortBy: 'SortName',
        Fields: fields, StartIndex: `${all.length}`, Limit: '500',
      }
      if (parent != null) query.ParentId = parent
      if (newest != null) { query.SortBy = 'DateCreated'; query.SortOrder = 'Descending'; query.Limit = `${newest}` }
      if (ids != null) query.Ids = ids.join(',')
      const p = await HTTP.json(this.request(`/Users/${this.userID}/Items`, query), page)
      all = all.concat(p.Items)
      if (newest != null || p.Items.length === 0 || all.length >= p.TotalRecordCount) return all
    }
  }

  // MARK: - Playback

  async playbackSource(item: MediaItem, startingAt: number, session: string, tracks: TrackSelection = new TrackSelection()): Promise<PlaybackSource> {
    return this.playbackSourceOf(item.id, startingAt, session, tracks)
  }

  /** Audio/subtitle streams of the item's first MediaSource (same DTO on Emby and Jellyfin); id = the stream Index. */
  async tracks(item: MediaItem): Promise<MediaTrack[]> {
    const sources = await HTTP.json(this.request(`/Users/${this.userID}/Items/${item.id}`, { Fields: 'MediaStreams' }), o =>
      objectsOrNull(asObject(o), 'MediaSources')?.map(s => ({ Id: stringOrNull(s, 'Id'), MediaStreams: objectsOrNull(s, 'MediaStreams')?.map(stream) ?? null })) ?? null)
    const first = sources?.[0]
    if (first?.Id != null) this.sourceIDs.set(item.id, first.Id)
    this.textSubtitles.set(item.id, new Set((first?.MediaStreams ?? []).filter(s => s.Type === 'Subtitle' && s.IsTextSubtitleStream === true).map(s => s.Index)))
    const out: MediaTrack[] = []
    for (const s of first?.MediaStreams ?? []) {
      const kind: MediaTrack.Kind | null = s.Type === 'Audio' ? 'audio' : s.Type === 'Subtitle' ? 'subtitle' : null
      if (kind == null) continue
      out.push({
        id: `${s.Index}`, kind, language: s.Language, title: s.DisplayTitle ?? s.Title ?? s.DisplayLanguage ?? s.Codec ?? '',
        isDefault: s.IsDefault ?? false, isForced: s.IsForced ?? false,
      })
    }
    return out
  }

  /**
   * Local trailer extra of the movie (or, for an episode, its series) via the same PlaybackInfo flow; null when none.
   * RemoteTrailers are YouTube links the player can't play, so they are ignored.
   */
  async trailer(item: MediaItem, session: string): Promise<PlaybackSource | null> {
    const owner = item.kind === 'episode' ? item.seriesID ?? item.id : item.id
    const extras = await HTTP.json(this.request(`/Users/${this.userID}/Items/${owner}/LocalTrailers`), o => objects(asArray(o)).map(e => getString(e, 'Id')))
    const id = extras[0]
    if (id == null) return null
    return this.playbackSourceOf(id, 0, session)
  }

  /**
   * Best-effort kill of the transcode last started for `session` (DELETE /Videos/ActiveEncodings; Jellyfin requires the
   * PlaySessionId, Emby accepts it). A direct-play source stores an id too, which makes the DELETE a harmless no-op.
   */
  async stopPlayback(session: string): Promise<void> {
    const id = this.sessions.get(session)
    if (id == null) return
    this.sessions.delete(session)
    await runCatchingNonCancel(() => HTTP.data(this.request('/Videos/ActiveEncodings', { DeviceId: this.deviceID, PlaySessionId: id }, 'DELETE')))
  }

  /** Both servers' "mark played" for this user: POST /Users/{id}/PlayedItems/{item}. */
  async markWatched(item: MediaItem): Promise<void> {
    await HTTP.data(this.request(`/Users/${this.userID}/PlayedItems/${item.id}`, {}, 'POST'))
  }

  /** Kotlin's private `playbackSource(itemID, …)` overload. */
  private async playbackSourceOf(itemID: string, startingAt: number, session: string, tracks: TrackSelection = new TrackSelection()): Promise<PlaybackSource> {
    await this.stopPlayback(session)   // a reload replaces this session's transcode instead of adding one
    // 0 = original, sent as 120 Mbps (no real cap). ponytail: the muted guide preview is capped at 4 Mbps regardless, so
    // sources above that transcode instead of remuxing; drop the cap if a software-only server can't keep up.
    let kbps = this.maxBitrateKbps > 0 ? this.maxBitrateKbps : 120_000
    if (session === 'preview') kbps = Math.min(kbps, 4000)
    // Chosen streams by index (PlaybackInfoDto.AudioStreamIndex / SubtitleStreamIndex, same names on Emby). -1 = no
    // subtitle: the server looks up stream index -1, finds none, and the manifest carries no sub. A chosen audio track, or a
    // subtitle to burn in, turns direct play off so the server muxes that audio (or burns that subtitle) into HLS.
    // Web: a picked text subtitle is drawn by the app from a WebVTT file (`drawnSubtitleURL`); only an image subtitle (PGS, DVD)
    // is burned in. Every other case asks for -1, never for nothing: with no index the server applies the account's
    // remembered subtitle, which the TVs could only get burned in (a full re-encode: slow, and failing on some servers).
    const sub = tracks.subtitle
    const picked = sub.kind === 'track' ? toIntOrNull(sub.id) : null
    const drawn = picked != null && this.textSubtitles.get(itemID)?.has(picked) === true
    const subtitleIndex: number = picked != null && !drawn ? picked : -1
    const burned = subtitleIndex >= 0
    const direct = tracks.audioID == null && !burned
    const audioIndex = toIntOrNull(tracks.audioID)
    const ticks = Math.trunc(startingAt * 10_000_000)
    const body: JSONObject = {
      UserId: this.userID, DeviceProfile: JellyfinClient.deviceProfile(kbps * 1000), StartTimeTicks: ticks,
      MaxStreamingBitrate: kbps * 1000, EnableDirectPlay: direct, EnableDirectStream: true, EnableTranscoding: true,
      AutoOpenLiveStream: true, AllowVideoStreamCopy: true, AllowAudioStreamCopy: true,
    }
    if (audioIndex != null) body.AudioStreamIndex = audioIndex
    body.SubtitleStreamIndex = subtitleIndex
    // Emby (verified on 4.10) reads the stream indices and the direct-play switch from the QUERY STRING only (its own web
    // client sends them there), while Jellyfin reads the body. Send both so one request shape works on either server.
    const query: Query = {
      UserId: this.userID, StartTimeTicks: `${ticks}`, MaxStreamingBitrate: `${kbps * 1000}`,
      EnableDirectPlay: `${direct}`, EnableDirectStream: 'true', EnableTranscoding: 'true',
    }
    // Always: the subtitle index is always sent, and Emby only honours it beside the MediaSourceId.
    let sid = this.sourceIDs.get(itemID) ?? null
    if (sid == null) {   // the watch player lists tracks first; this is the fallback for the guide preview and trailers
      sid = await runCatchingNonCancel(() => HTTP.json(this.request(`/Users/${this.userID}/Items/${itemID}`, { Fields: 'MediaSources' }), o => {
        const s = objectsOrNull(asObject(o), 'MediaSources')?.[0]
        return s ? stringOrNull(s, 'Id') : null
      }))
    }
    if (sid != null) query.MediaSourceId = sid
    if (audioIndex != null) query.AudioStreamIndex = `${audioIndex}`
    query.SubtitleStreamIndex = `${subtitleIndex}`
    const info = await HTTP.json(this.request(`/Items/${itemID}/PlaybackInfo`, query, 'POST', JSON.stringify(body)), o => {
      const i = asObject(o)
      return {
        MediaSources: objects(getJSONArray(i, 'MediaSources')).map(s => ({
          Id: getString(s, 'Id'), SupportsDirectPlay: boolOrNull(s, 'SupportsDirectPlay'), TranscodingUrl: stringOrNull(s, 'TranscodingUrl'),
          hevc: (objectsOrNull(s, 'MediaStreams') ?? []).some(m => {
            const codec = stringOrNull(m, 'Codec')?.toLowerCase()
            return stringOrNull(m, 'Type') === 'Video' && (codec === 'hevc' || codec === 'h265')
          }),
          SupportsDirectStream: boolOrNull(s, 'SupportsDirectStream'),
        })),
        PlaySessionId: stringOrNull(i, 'PlaySessionId'),
      }
    })
    const play = info.PlaySessionId
    if (play != null) this.sessions.set(session, play)
    else this.sessions.delete(session)
    const src = info.MediaSources[0]
    if (!src) throw MediaServerError.invalidResponse(`no MediaSources for ${itemID}`)
    // An audio pick turns direct play off, and for a file that needs nothing done (an mp4 with its one AAC track) Emby then
    // answers "direct stream" with no TranscodingUrl: play the file as it is.
    // ponytail: a non-default audio pick on such a file plays its default audio; send it to HLS if that ever matters.
    if (src.SupportsDirectPlay === true || (src.TranscodingUrl == null && src.SupportsDirectStream === true)) {
      return {
        url: this.url(`/Videos/${itemID}/stream`, { Static: 'true', MediaSourceId: src.Id, DeviceId: this.deviceID, api_key: this.token }),
        startsAtOffset: false,
      }
    }
    let path = src.TranscodingUrl
    if (path == null) throw MediaServerError.invalidResponse(`no playable source for ${itemID}`)
    if (!path.startsWith('/')) path = '/' + path
    const [p, subInPlaylist] = JellyfinClient.segments(path, this.kind, burned, src.hevc)
    path = p
    // Emby omits the token from TranscodingUrl; Jellyfin includes it (api_key= or, 10.9+, ApiKey=).
    const lower = path.toLowerCase()
    if (lower.indexOf('api_key=') < 0 && lower.indexOf('apikey=') < 0) path += (path.indexOf('?') >= 0 ? '&' : '?') + 'api_key=' + this.token
    const url = this.baseURL + path
    try { new URL(url) } catch { throw MediaServerError.invalidResponse(`bad TranscodingUrl ${path}`) }
    return { url, startsAtOffset: false, subtitleInPlaylist: subInPlaylist }
  }

  artworkURL(path: string | null | undefined): string | null {
    return path == null ? null : this.url(path, { maxWidth: '600', api_key: this.token })
  }

  // MARK: - HTTP

  /** Query keys in Swift order (uppercase before lowercase), so every platform sends the same URL. */
  private url(path: string, query: Query): string {
    return this.baseURL + path + queryString(stableSort(Object.keys(query), swiftCompare).map((k): [string, string] => [k, query[k]]))
  }

  /**
   * MediaBrowser auth on every request. Jellyfin reads `Authorization`, Emby reads `X-Emby-Authorization` / `X-Emby-Token`;
   * each ignores the other's header, so send all. Emby rejects the scheme without a DeviceId.
   */
  private request(path: string, query: Query = {}, method = 'GET', body: string | null = null): URLRequest {
    const r = new URLRequest(this.url(path, query), 30)
    r.httpMethod = method
    r.httpBody = body
    let auth = `MediaBrowser Client="Rerun Guide", Device="${ClientDevice.device}", DeviceId="${this.deviceID}", Version="1.0"`
    if (this.token !== '') {
      auth += `, Token="${this.token}"`
      r.setValue(this.token, 'X-Emby-Token')
    }
    r.setValue(auth, 'Authorization')
    r.setValue(auth, 'X-Emby-Authorization')
    r.setValue('application/json', 'Accept')
    if (body != null) r.setValue('application/json', 'Content-Type')
    return r
  }
}
export namespace JellyfinClient {
  /** Jellyfin Quick Connect (Jellyfin only): show `code`, user approves it in Settings › Quick Connect, poll, then exchange.
   *  Every call is unauthenticated but carries the MediaBrowser header so the server records the device. */
  export interface QuickConnectSession {
    readonly code: string
    readonly secret: string
  }
}
