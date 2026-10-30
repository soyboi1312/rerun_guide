package com.rerunguide.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private const val SERIES_FIELDS = "Genres,People,OfficialRating"
private val FETCHES = listOf("Movie" to "Genres,People,Overview,ProductionYear,OfficialRating,DateCreated,Path",
                             "Episode" to "Overview,SeriesPrimaryImage,ProductionYear,OfficialRating,DateCreated")

/** Emby and Jellyfin share one client; `kind` selects the few places they differ. */
class JellyfinClient(
    baseURL: String,
    val token: String,
    val userID: String,
    val deviceID: String,
    override val kind: ServerKind,
    serverName: String = "",
    /** Streaming cap in kbit/s, 0 = original. Sent as MaxStreamingBitrate in PlaybackInfo and its DeviceProfile. */
    val maxBitrateKbps: Int = 0,
) : MediaServer {
    val baseURL: String = if (baseURL.endsWith("/")) baseURL.dropLast(1) else baseURL
    override val serverName: String =
        serverName.ifEmpty { runCatching { java.net.URL(baseURL).host.removeSurrounding("[", "]") }.getOrNull()?.takeIf { it.isNotEmpty() } ?: kind.displayName }

    /** `session` label → PlaySessionId of the transcode last started for it, so the next load (or stopPlayback) can kill it. */
    private val sessions = ConcurrentHashMap<String, String>()
    /**
     * MediaSources[0].Id per item, filled by `tracks(item)`. Emby (verified on 4.10) only honours AudioStreamIndex /
     * SubtitleStreamIndex when the PlaybackInfo query also names the MediaSourceId; Jellyfin doesn't mind it.
     */
    private val sourceIDs = ConcurrentHashMap<String, String>()

    init {
        require(kind == ServerKind.emby || kind == ServerKind.jellyfin)
    }

    // MARK: - Sign-in

    private class Auth(val AccessToken: String, val UserId: String)
    private fun auth(o: Any) = (o as JSONObject).let { Auth(it.getString("AccessToken"), it.getJSONObject("User").getString("Id")) }

    /** Jellyfin Quick Connect (Jellyfin only): show `code`, user approves it in Settings › Quick Connect, poll, then exchange.
     *  Every call is unauthenticated but carries the MediaBrowser header so the server records the device. */
    data class QuickConnectSession(val code: String, val secret: String)

    companion object {
        /**
         * Emby (verified on 4.10) restarts the encoder of an fMP4 HLS stream mid-play with segments whose tfdt starts over at 0
         * and no EXT-X-DISCONTINUITY; ExoPlayer (and AVPlayer) freeze on the jump back, burn-in or remux alike. Its TS segments
         * keep the source timestamps across restarts, and ExoPlayer plays HEVC in TS, so VideoCodec stays as asked (the Swift
         * client switches fewer streams to TS and limits every TS one to H.264: AVPlayer has no HEVC in TS). A WebVTT rendition goes
         * TS on Jellyfin too: both servers stamp every subtitle segment X-TIMESTAMP-MAP=MPEGTS:900000 (Emby 4.10 ignores
         * VttTimestampMap; Jellyfin releases before PR #17299), which matches TS video (PTS from 10 s) and puts cues 10 s late
         * over fMP4 (tfdt from 0). ponytail: a Jellyfin HEVC source keeps fMP4 and late cues, as in the Swift client; extend
         * once it's checked on a Jellyfin server.
         * Only a selected subtitle the server lists as an HLS rendition (SubtitleMethod=Hls) is in the playlist; a burn-in
         * (Encode) is in the picture. Returns the rewritten TranscodingUrl path and whether the pick is a playlist rendition.
         */
        fun segments(path: String, kind: ServerKind, picked: Boolean, hevc: Boolean): Pair<String, Boolean> {
            val hlsSubs = picked && path.contains("SubtitleMethod=Hls")
            val ts = kind == ServerKind.emby || (hlsSubs && !hevc)
            return (if (ts) path.replace("SegmentContainer=mp4", "SegmentContainer=ts") else path) to hlsSubs
        }

        /** Username/password sign-in. Returns a ready client; persist `token` and `userID`. */
        suspend fun login(baseURL: String, username: String, password: String, deviceID: String, kind: ServerKind): JellyfinClient {
            val anon = JellyfinClient(baseURL = baseURL, token = "", userID = "", deviceID = deviceID, kind = kind)
            val body = JSONObject().put("Username", username).put("Pw", password).toString().toByteArray(Charsets.UTF_8)
            return anon.signedIn(HTTP.json(anon.request("/Users/AuthenticateByName", method = "POST", body = body), anon::auth))
        }

        suspend fun quickConnectStart(baseURL: String, deviceID: String): QuickConnectSession {
            val anon = anonymous(baseURL, deviceID)
            if (!HTTP.json(anon.request("/QuickConnect/Enabled")) { it as Boolean }) throw MediaServerError.notFound("Quick Connect")
            val start = { o: Any -> (o as JSONObject).let { QuickConnectSession(code = it.getString("Code"), secret = it.getString("Secret")) } }
            return try {
                HTTP.json(anon.request("/QuickConnect/Initiate", method = "POST"), start)
            } catch (e: MediaServerError.http) {
                if (e.code != 405) throw e
                HTTP.json(anon.request("/QuickConnect/Initiate"), start)   // 10.8 only routes GET
            }
        }

        /** true once the user has approved the code. Throws `notFound` when the code has expired. */
        suspend fun quickConnectIsApproved(baseURL: String, secret: String, deviceID: String): Boolean {
            val anon = anonymous(baseURL, deviceID)
            return try {
                HTTP.json(anon.request("/QuickConnect/Connect", query = mapOf("secret" to secret))) { (it as JSONObject).getBoolean("Authenticated") }
            } catch (e: MediaServerError.http) {
                if (e.code == 404) throw MediaServerError.notFound("Quick Connect code (expired)") else throw e
            }
        }

        suspend fun loginWithQuickConnect(baseURL: String, secret: String, deviceID: String): JellyfinClient {
            val anon = anonymous(baseURL, deviceID)
            val body = JSONObject().put("Secret", secret).toString().toByteArray(Charsets.UTF_8)
            return anon.signedIn(HTTP.json(anon.request("/Users/AuthenticateWithQuickConnect", method = "POST", body = body), anon::auth))
        }

        /**
         * Emby Connect (Emby only): trade a server's Connect access key (`EmbyConnect.servers`) for a local sign-in, trying its
         * addresses in order, LAN first, with short timeouts so a viewer away from home doesn't sit on the LAN one.
         * A LAN address gets the key only if its /System/Info/Public, asked without a token, names this server.
         */
        suspend fun connectExchange(server: PlexServer, connectUserID: String, deviceID: String): JellyfinClient {
            var last: Exception = MediaServerError.notFound("an address for ${server.name}")
            var answered: Exception? = null   // a server's own refusal says more than another address timing out
            for (a in server.addresses.ifEmpty { listOf(PlexAddress(server.baseURL, local = false)) }) {
                if (a.local) {
                    val probe = JellyfinClient(a.url, token = "", userID = "", deviceID = deviceID, kind = ServerKind.emby).request("/System/Info/Public")
                    probe.timeoutInterval = 4.0
                    if (runCatchingNonCancel { HTTP.json(probe) { (it as JSONObject).stringOrNull("Id") } } != server.id) continue
                }
                val keyed = JellyfinClient(baseURL = a.url, token = server.accessToken, userID = "", deviceID = deviceID, kind = ServerKind.emby, serverName = server.name)
                val r = keyed.request("/Connect/Exchange", query = mapOf("format" to "json", "ConnectUserId" to connectUserID))
                r.timeoutInterval = if (a.local) 4.0 else 10.0
                try {
                    val (user, token) = HTTP.json(r) { (it as JSONObject).let { o -> o.getString("LocalUserId") to o.getString("AccessToken") } }
                    return JellyfinClient(baseURL = a.url, token = token, userID = user, deviceID = deviceID, kind = ServerKind.emby, serverName = server.name)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: MediaServerError) {
                    answered = answered ?: e
                } catch (e: Exception) { last = e }
            }
            throw answered ?: last
        }

        private fun anonymous(baseURL: String, deviceID: String) =
            JellyfinClient(baseURL = baseURL, token = "", userID = "", deviceID = deviceID, kind = ServerKind.jellyfin)

        /**
         * Phone/TV-player-shaped profile: mp4/mov direct play only (no mkv); everything else becomes HLS, fMP4 first (HEVC is
         * only legal in fMP4 per Apple HLS spec 1.5), ts h264-only as the fallback for servers without fMP4 HLS.
         * The HLS playlist is full-length VOD starting at 0 (StartTimeTicks is ignored for hls); the player seeks to the offset.
         * Text subtitles (subrip/srt, mov_text, vtt, external .srt) ride along as WebVTT HLS renditions, so a sub track doesn't
         * force a burn-in transcode: Jellyfin picks that from the vtt/Hls SubtitleProfile alone; Emby (verified on 4.10) also
         * needs "ManifestSubtitles":"vtt" on the HLS TranscodingProfiles (Jellyfin ignores the unknown field), and without the
         * SubtitleProfile it burns in. ponytail: on Emby ASS and PGS/VobSub still burn in (full encode; ASS is never offered as
         * a rendition), and an mp4 with a selected sub goes HLS instead of direct play.
         */
        private fun deviceProfile(maxBitrate: Int): String = """
            {"MaxStreamingBitrate":$maxBitrate,
             "DirectPlayProfiles":[{"Type":"Video","Container":"mp4,m4v,mov","VideoCodec":"${ClientDevice.videoCodecs}","AudioCodec":"${ClientDevice.directPlayAudioCodecs}"}],
             "TranscodingProfiles":[
               {"Type":"Video","Container":"mp4","Protocol":"hls","VideoCodec":"${ClientDevice.videoCodecs}","AudioCodec":"${ClientDevice.audioCodecs}",
                "Context":"Streaming","MinSegments":2,"BreakOnNonKeyFrames":true,"MaxAudioChannels":"6","ManifestSubtitles":"vtt"},
               {"Type":"Video","Container":"ts","Protocol":"hls","VideoCodec":"h264","AudioCodec":"${ClientDevice.audioCodecs}",
                "Context":"Streaming","MinSegments":2,"BreakOnNonKeyFrames":true,"MaxAudioChannels":"6","ManifestSubtitles":"vtt"}],
             "CodecProfiles":[],"SubtitleProfiles":[{"Format":"vtt","Method":"Hls"}]}
            """.trimIndent()
    }

    /** Auth result → ready client, named after the server when it will say (best-effort; host otherwise). */
    private suspend fun signedIn(auth: Auth): JellyfinClient {
        val name = runCatchingNonCancel { HTTP.json(request("/System/Info/Public")) { (it as JSONObject).stringOrNull("ServerName") } } ?: ""
        return JellyfinClient(baseURL = baseURL, token = auth.AccessToken, userID = auth.UserId, deviceID = deviceID, kind = kind, serverName = name)
    }

    // MARK: - Library

    /** Wire shape of a BaseItemDto; property names mirror the JSON. */
    private class Item(o: JSONObject) {
        val Id: String = o.getString("Id")
        val Name: String? = o.stringOrNull("Name")
        val Type: String = o.getString("Type")
        val SeriesName: String? = o.stringOrNull("SeriesName")
        val SeriesId: String? = o.stringOrNull("SeriesId")
        val ParentIndexNumber: Int? = o.intOrNull("ParentIndexNumber")
        val IndexNumber: Int? = o.intOrNull("IndexNumber")
        val ProductionYear: Int? = o.intOrNull("ProductionYear")
        val RunTimeTicks: Long? = o.longOrNull("RunTimeTicks")
        val Genres: List<String>? = o.arrayOrNull("Genres")?.let { o.strings("Genres") }
        val ImageTags: JSONObject? = o.objectOrNull("ImageTags")
        val SeriesPrimaryImageTag: String? = o.stringOrNull("SeriesPrimaryImageTag")
        val BackdropImageTags: List<String>? = o.arrayOrNull("BackdropImageTags")?.let { o.strings("BackdropImageTags") }
        val ParentBackdropImageTags: List<String>? = o.arrayOrNull("ParentBackdropImageTags")?.let { o.strings("ParentBackdropImageTags") }
        val ParentBackdropItemId: String? = o.stringOrNull("ParentBackdropItemId")
        val Overview: String? = o.stringOrNull("Overview")
        val OfficialRating: String? = o.stringOrNull("OfficialRating")
        /** (Name, Type) pairs. */
        val People: List<Pair<String?, String?>>? = o.objectsOrNull("People")?.map { it.stringOrNull("Name") to it.stringOrNull("Type") }
        val DateCreated: String? = o.stringOrNull("DateCreated")
        /** The file, for a film's cut ({edition-…}); movies only. */
        val Path: String? = o.stringOrNull("Path")
        /** UserData.Played, this user's state. Both servers send it on /Users/{id}/Items unless EnableUserData=false (verified on Emby 4.10). */
        val Played: Boolean? = o.objectOrNull("UserData")?.boolOrNull("Played")
        /** Emby writes 7 fractional digits ("2026-02-12T08:00:00.0000000Z"); keep the whole seconds, as the Swift client does. */
        val added: Instant? get() = DateCreated?.let { runCatching { Instant.parse(it.take(19) + "Z") }.getOrNull() }

        val hasBackdrop: Boolean get() = !(BackdropImageTags ?: emptyList()).isEmpty()
        fun people(type: String): List<String> = (People ?: emptyList()).filter { it.second == type }.mapNotNull { it.first }
    }
    private class Page(val Items: List<Item>, val TotalRecordCount: Int)
    private fun page(o: Any) = (o as JSONObject).let { Page(it.getJSONArray("Items").objects().map(::Item), it.getInt("TotalRecordCount")) }

    override suspend fun libraries(): List<LibrarySection> {
        class View(val Id: String, val Name: String?, val CollectionType: String?)
        val views = HTTP.json(request("/Users/$userID/Views")) { o ->
            (o as JSONObject).getJSONArray("Items").objects().map { View(it.getString("Id"), it.stringOrNull("Name"), it.stringOrNull("CollectionType")) }
        }
        // Counts are a nicety: one request per view, in parallel, null when a view won't count.
        val counts = coroutineScope {
            views.map { v -> async { runCatchingNonCancel { count(v.Id) } } }.awaitAll()
        }
        return views.zip(counts).map { (v, n) ->
            val kind = when (v.CollectionType) { "movies" -> LibrarySection.Kind.movies; "tvshows" -> LibrarySection.Kind.shows; else -> LibrarySection.Kind.other }
            LibrarySection(id = v.Id, title = v.Name ?: "", kind = kind, itemCount = n)
        }
    }

    private suspend fun count(viewID: String): Int =
        HTTP.json(request("/Users/$userID/Items", query = mapOf(
            "ParentId" to viewID, "Recursive" to "true", "IncludeItemTypes" to "Movie,Series", "IsMissing" to "false", "Limit" to "1")), ::page).TotalRecordCount

    override suspend fun libraryItems(sections: List<LibrarySection>?): List<MediaItem> = withContext(Dispatchers.Default) {   // mapping off main, as in Swift
        // Episodes usually carry no Genres/People/rating of their own; inherit from the series. Episodes are also not asked
        // for Genres/People: a 10k-episode library's per-episode cast list is tens of MB of JSON on every library load.
        val series = LinkedHashMap<String, Item>()
        for (s in items(types = "Series", fields = SERIES_FIELDS)) series.putIfAbsent(s.Id, s)
        val out = mutableListOf<MediaItem>()
        for (parent in sections?.map { it.id } ?: listOf(null)) { // ponytail: sections fetched one after another; parallel if many large views make launch slow
            for ((types, fields) in FETCHES) {
                out += items(types = types, fields = fields, parent = parent).mapNotNull { mediaItem(it, series, parent) }
            }
        }
        out
    }

    /**
     * The newest `limit` movies and `limit` episodes of each library, by date added, plus their series: a few small requests,
     * so the app can run it every time it opens.
     */
    override suspend fun recentlyAdded(sections: List<LibrarySection>?, limit: Int): List<MediaItem> = withContext(Dispatchers.Default) {
        val found = mutableListOf<Pair<Item, String?>>()
        for (parent in sections?.map { it.id } ?: listOf(null)) {
            for ((types, fields) in FETCHES) {
                found += items(types = types, fields = fields, parent = parent, newest = limit).map { it to parent }
            }
        }
        val ids = found.mapNotNull { it.first.SeriesId }.toSortedSet().toList()
        val series = LinkedHashMap<String, Item>()
        if (ids.isNotEmpty()) for (s in items(types = "Series", fields = SERIES_FIELDS, ids = ids)) series.putIfAbsent(s.Id, s)
        found.mapNotNull { (i, parent) -> mediaItem(i, series, parent) }
    }

    private fun mediaItem(i: Item, series: Map<String, Item>, parent: String?): MediaItem? {
        fun <C : Collection<*>> or(a: C?, b: C?): C? = if (a?.isEmpty() == false) a else b
        fun or(a: String?, b: String?): String? = if (a?.isEmpty() == false) a else b
        fun backdrop(id: String) = "/Items/$id/Images/Backdrop/0"
        val ticks = i.RunTimeTicks
        if (ticks == null || ticks <= 0) return null
        val isEpisode = i.Type == "Episode"
        val show = if (isEpisode) i.SeriesId?.let { series[it] } else null
        val own = if (i.ImageTags?.has("Primary") == true) "/Items/${i.Id}/Images/Primary" else null
        val art = if (isEpisode && i.SeriesPrimaryImageTag != null && i.SeriesId != null) "/Items/${i.SeriesId}/Images/Primary" else own
        val parentBackdrop = i.ParentBackdropItemId ?: i.SeriesId
        val wide: String? = if (i.hasBackdrop) backdrop(i.Id)
            else if (!(i.ParentBackdropImageTags ?: emptyList()).isEmpty() && parentBackdrop != null) backdrop(parentBackdrop)
            else if (show != null && show.hasBackdrop) backdrop(show.Id)
            else null
        return MediaItem(id = i.Id, kind = if (isEpisode) MediaItem.Kind.episode else MediaItem.Kind.movie,
                         title = if (isEpisode) i.Name ?: "" else MediaItem.title(i.Name ?: "", MediaItem.edition(i.Path)),
                         seriesTitle = i.SeriesName, seriesID = i.SeriesId, season = i.ParentIndexNumber, episode = i.IndexNumber,
                         year = i.ProductionYear, genres = or(show?.Genres, i.Genres) ?: emptyList(),
                         runtime = ticks.toDouble() / 10_000_000, artworkPath = art, backdropPath = wide,
                         overview = or(i.Overview, null), contentRating = or(i.OfficialRating, show?.OfficialRating),
                         directors = or(i.people("Director"), show?.people("Director")) ?: emptyList(),
                         actors = (or(i.people("Actor"), show?.people("Actor")) ?: emptyList()).take(6),
                         libraryID = parent, addedAt = i.added, watched = i.Played == true)
    }

    /**
     * Pages through /Users/{id}/Items, optionally under one view. Jellyfin always sends OfficialRating/ProductionYear;
     * Emby (verified on 4.10) omits them unless they are listed in Fields, and both ignore Fields values they don't know.
     * `newest`: one page of that many, newest first by date added. `ids`: only those items.
     */
    private suspend fun items(types: String, fields: String, parent: String? = null, newest: Int? = null, ids: List<String>? = null): List<Item> {
        val all = mutableListOf<Item>()
        while (true) {
            val query = mutableMapOf("Recursive" to "true", "IncludeItemTypes" to types, "IsMissing" to "false", "SortBy" to "SortName",
                                     "Fields" to fields, "StartIndex" to "${all.size}", "Limit" to "500")
            if (parent != null) query["ParentId"] = parent
            if (newest != null) { query["SortBy"] = "DateCreated"; query["SortOrder"] = "Descending"; query["Limit"] = "$newest" }
            if (ids != null) query["Ids"] = ids.joinToString(",")
            val page = HTTP.json(request("/Users/$userID/Items", query = query), ::page)
            all += page.Items
            if (newest != null || page.Items.isEmpty() || all.size >= page.TotalRecordCount) return all
        }
    }

    // MARK: - Playback

    override suspend fun playbackSource(item: MediaItem, startingAt: Double, session: String, tracks: TrackSelection): PlaybackSource =
        playbackSource(itemID = item.id, startingAt = startingAt, session = session, tracks = tracks)

    private class Stream(o: JSONObject) {
        val Index: Int = o.getInt("Index")
        val Type: String = o.getString("Type")
        val Codec: String? = o.stringOrNull("Codec")
        val Language: String? = o.stringOrNull("Language")
        val Title: String? = o.stringOrNull("Title")
        val DisplayTitle: String? = o.stringOrNull("DisplayTitle")
        val DisplayLanguage: String? = o.stringOrNull("DisplayLanguage")
        val IsDefault: Boolean? = o.boolOrNull("IsDefault")
        val IsForced: Boolean? = o.boolOrNull("IsForced")
    }
    private class StreamSource(val Id: String?, val MediaStreams: List<Stream>?)

    /** Audio/subtitle streams of the item's first MediaSource (same DTO on Emby and Jellyfin); id = the stream Index. */
    override suspend fun tracks(item: MediaItem): List<MediaTrack> {
        val sources = HTTP.json(request("/Users/$userID/Items/${item.id}", query = mapOf("Fields" to "MediaStreams"))) { o ->
            (o as JSONObject).objectsOrNull("MediaSources")?.map { StreamSource(it.stringOrNull("Id"), it.objectsOrNull("MediaStreams")?.map(::Stream)) }
        }
        sources?.firstOrNull()?.Id?.let { sourceIDs[item.id] = it }
        return (sources?.firstOrNull()?.MediaStreams ?: emptyList()).mapNotNull { s ->
            val kind = when (s.Type) { "Audio" -> MediaTrack.Kind.audio; "Subtitle" -> MediaTrack.Kind.subtitle; else -> return@mapNotNull null }
            MediaTrack(id = s.Index.toString(), kind = kind, language = s.Language,
                       title = s.DisplayTitle ?: s.Title ?: s.DisplayLanguage ?: s.Codec ?: "",
                       isDefault = s.IsDefault ?: false, isForced = s.IsForced ?: false)
        }
    }

    /**
     * Local trailer extra of the movie (or, for an episode, its series) via the same PlaybackInfo flow; null when none.
     * RemoteTrailers are YouTube links the player can't play, so they are ignored.
     */
    override suspend fun trailer(item: MediaItem, session: String): PlaybackSource? {
        val owner = if (item.kind == MediaItem.Kind.episode) (item.seriesID ?: item.id) else item.id
        val extras = HTTP.json(request("/Users/$userID/Items/$owner/LocalTrailers")) { o -> (o as JSONArray).objects().map { it.getString("Id") } }
        val id = extras.firstOrNull() ?: return null
        return playbackSource(itemID = id, startingAt = 0.0, session = session)
    }

    /**
     * Best-effort kill of the transcode last started for `session` (DELETE /Videos/ActiveEncodings; Jellyfin requires the
     * PlaySessionId, Emby accepts it). A direct-play source stores an id too, which makes the DELETE a harmless no-op.
     */
    override suspend fun stopPlayback(session: String) {
        val id = sessions.remove(session) ?: return
        runCatchingNonCancel { HTTP.data(request("/Videos/ActiveEncodings", query = mapOf("DeviceId" to deviceID, "PlaySessionId" to id), method = "DELETE")) }
    }

    /** Both servers' "mark played" for this user: POST /Users/{id}/PlayedItems/{item}. */
    override suspend fun markWatched(item: MediaItem) {
        HTTP.data(request("/Users/$userID/PlayedItems/${item.id}", method = "POST"))
    }

    private suspend fun playbackSource(itemID: String, startingAt: Double, session: String, tracks: TrackSelection = TrackSelection()): PlaybackSource {
        class Source(val Id: String, val SupportsDirectPlay: Boolean?, val TranscodingUrl: String?, val hevc: Boolean, val SupportsDirectStream: Boolean? = null)
        class Info(val MediaSources: List<Source>, val PlaySessionId: String?)
        stopPlayback(session)   // a reload replaces this session's transcode instead of adding one
        // 0 = original, sent as 120 Mbps (no real cap). ponytail: the muted 788-px guide preview is capped at 4 Mbps
        // regardless, so sources above that transcode instead of remuxing; drop the cap if a software-only server can't keep up.
        var kbps = if (maxBitrateKbps > 0) maxBitrateKbps else 120_000
        if (session == "preview") kbps = minOf(kbps, 4000)
        // Chosen streams by index (PlaybackInfoDto.AudioStreamIndex / SubtitleStreamIndex, same names on Emby). -1 = no
        // subtitle: the server looks up stream index -1, finds none, and the manifest carries no sub. A chosen audio or
        // subtitle track turns direct play off so the server muxes that audio into HLS and lists text subs as renditions
        // (Jellyfin marks the chosen one DEFAULT=YES, Emby none; the player selects it); a PGS/VobSub pick is burned in.
        val direct = tracks.audioID == null && tracks.subtitle !is TrackSelection.Subtitle.track
        val audioIndex = tracks.audioID?.toIntOrNull()
        var picks = audioIndex?.let { ",\"AudioStreamIndex\":$it" } ?: ""
        when (val s = tracks.subtitle) {
            is TrackSelection.Subtitle.track -> picks += s.id.toIntOrNull()?.let { ",\"SubtitleStreamIndex\":$it" } ?: ""
            TrackSelection.Subtitle.off -> picks += ",\"SubtitleStreamIndex\":-1"
            TrackSelection.Subtitle.auto -> {}
        }
        val ticks = (startingAt * 10_000_000).toLong()
        val body = """
            {"UserId":"$userID","DeviceProfile":${deviceProfile(maxBitrate = kbps * 1000)},"StartTimeTicks":$ticks,
             "MaxStreamingBitrate":${kbps * 1000},"EnableDirectPlay":$direct,"EnableDirectStream":true,"EnableTranscoding":true,
             "AutoOpenLiveStream":true,"AllowVideoStreamCopy":true,"AllowAudioStreamCopy":true$picks}
            """.trimIndent()
        // Emby (verified on 4.10) reads the stream indices and the direct-play switch from the QUERY STRING only (its own web
        // client sends them there), while Jellyfin reads the body. Send both so one request shape works on either server.
        val query = mutableMapOf("UserId" to userID, "StartTimeTicks" to "$ticks", "MaxStreamingBitrate" to "${kbps * 1000}",
                                 "EnableDirectPlay" to "$direct", "EnableDirectStream" to "true", "EnableTranscoding" to "true")
        if (!tracks.isDefault) {
            var sid = sourceIDs[itemID]
            if (sid == null) {   // the picker always lists tracks first; this is the fallback for a direct call
                sid = runCatchingNonCancel {
                    HTTP.json(request("/Users/$userID/Items/$itemID", query = mapOf("Fields" to "MediaSources"))) {
                        (it as JSONObject).objectsOrNull("MediaSources")?.firstOrNull()?.stringOrNull("Id")
                    }
                }
            }
            sid?.let { query["MediaSourceId"] = it }
        }
        if (audioIndex != null) query["AudioStreamIndex"] = "$audioIndex"
        when (val s = tracks.subtitle) {
            is TrackSelection.Subtitle.track -> s.id.toIntOrNull()?.let { query["SubtitleStreamIndex"] = "$it" }
            TrackSelection.Subtitle.off -> query["SubtitleStreamIndex"] = "-1"
            TrackSelection.Subtitle.auto -> {}
        }
        val info = HTTP.json(request("/Items/$itemID/PlaybackInfo", query = query, method = "POST", body = body.toByteArray(Charsets.UTF_8))) { o ->
            (o as JSONObject).let { i ->
                Info(i.getJSONArray("MediaSources").objects().map { Source(it.getString("Id"), it.boolOrNull("SupportsDirectPlay"), it.stringOrNull("TranscodingUrl"),
                    it.objectsOrNull("MediaStreams").orEmpty().any { m -> m.stringOrNull("Type") == "Video" && m.stringOrNull("Codec")?.lowercase() in setOf("hevc", "h265") },
                    it.boolOrNull("SupportsDirectStream")) },
                     i.stringOrNull("PlaySessionId"))
            }
        }
        val play = info.PlaySessionId
        if (play != null) sessions[session] = play else sessions.remove(session)
        val src = info.MediaSources.firstOrNull() ?: throw MediaServerError.invalidResponse("no MediaSources for $itemID")
        // An audio pick turns direct play off, and for a file that needs nothing done (an mp4 with its one AAC track) Emby
        // then answers "direct stream" with no TranscodingUrl: play the file as it is.
        // ponytail: a non-default audio pick on such a file plays its default audio; send it to HLS if that ever matters.
        if (src.SupportsDirectPlay == true || (src.TranscodingUrl == null && src.SupportsDirectStream == true)) {
            return PlaybackSource(url = url("/Videos/$itemID/stream", query = mapOf(
                "Static" to "true", "MediaSourceId" to src.Id, "DeviceId" to deviceID, "api_key" to token)), startsAtOffset = false)
        }
        var path = src.TranscodingUrl ?: throw MediaServerError.invalidResponse("no playable source for $itemID")
        if (!path.startsWith("/")) path = "/$path"
        val (p, subInPlaylist) = segments(path, kind, tracks.subtitle is TrackSelection.Subtitle.track, src.hevc)
        path = p
        // Emby omits the token from TranscodingUrl; Jellyfin includes it (api_key= or, 10.9+, ApiKey=).
        if (!path.lowercase().contains("api_key=") && !path.lowercase().contains("apikey=")) {
            path += (if (path.contains("?")) "&" else "?") + "api_key=" + token
        }
        val url = baseURL + path
        if (runCatching { URI(url) }.isFailure) throw MediaServerError.invalidResponse("bad TranscodingUrl $path")
        return PlaybackSource(url = url, startsAtOffset = false, subtitleInPlaylist = subInPlaylist)
    }

    override fun artworkURL(path: String?): String? =
        path?.let { url(it, query = mapOf("maxWidth" to "600", "api_key" to token)) }

    // MARK: - HTTP

    private fun url(path: String, query: Map<String, String>): String =
        baseURL + path + queryString(query.entries.sortedWith { a, b -> swiftCompare(a.key, b.key) }.map { it.key to it.value })

    /**
     * MediaBrowser auth on every request. Jellyfin reads `Authorization`, Emby reads `X-Emby-Authorization` /
     * `X-Emby-Token`; each ignores the other's header, so send all. Emby rejects the scheme without a DeviceId.
     */
    private fun request(path: String, query: Map<String, String> = emptyMap(), method: String = "GET", body: ByteArray? = null): URLRequest {
        val r = URLRequest(url(path, query), timeoutInterval = 30.0)
        r.httpMethod = method
        r.httpBody = body
        var auth = "MediaBrowser Client=\"Rerun Guide\", Device=\"${ClientDevice.device}\", DeviceId=\"$deviceID\", Version=\"1.0\""
        if (token.isNotEmpty()) { auth += ", Token=\"$token\""; r.setValue(token, forHTTPHeaderField = "X-Emby-Token") }
        r.setValue(auth, forHTTPHeaderField = "Authorization")
        r.setValue(auth, forHTTPHeaderField = "X-Emby-Authorization")
        r.setValue("application/json", forHTTPHeaderField = "Accept")
        if (body != null) r.setValue("application/json", forHTTPHeaderField = "Content-Type")
        return r
    }
}
