package com.rerunguide.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * What the servers' device lists call this client: X-Plex-Platform / X-Plex-Device, the MediaBrowser `Device`.
 * The app sets `device` at startup ("Google TV", "Fire TV", "Android phone", "Android tablet"). Plex transcodes send
 * "Generic" instead (PlexClient.transcodeSource says why).
 */
object ClientDevice {
    @Volatile var platform = "Android"
    @Volatile var device = "Android"
    /** What this device's decoders play (the app fills these from MediaCodecList at launch): the servers transcode
     *  everything else. Many phones have no AC-3/E-AC-3 decoder and Android before 12 none for ALAC. */
    @Volatile var videoCodecs = "h264,hevc"
    @Volatile var audioCodecs = "aac,ac3,eac3"
    /** Direct play may also carry lossless audio in mp4. */
    @Volatile var directPlayAudioCodecs = "aac,ac3,eac3,alac,flac"
    /** The screen shows HDR (Swift: AVPlayer.eligibleForHDRPlayback). Without it Plex tone-maps HDR sources. */
    @Volatile var hdr = true
}

/** Every backend (Plex, Emby, Jellyfin, demo) implements this. The guide and player only ever see this interface. */
interface MediaServer {
    val kind: ServerKind
    /** Human name shown in Settings ("Living Room", "Basement"). */
    val serverName: String get() = kind.displayName
    /** Libraries/sections the user can put on the air. */
    suspend fun libraries(): List<LibrarySection> = emptyList()
    /** Every movie and episode in `sections` (null = every library). Called at launch and on "Rebuild"; the app caches it. */
    suspend fun libraryItems(sections: List<LibrarySection>?): List<MediaItem>
    /**
     * The newest items in `sections` (null = every library) by date added: at most `limit` movies and `limit` episodes per
     * library, cheap enough to ask every time the app opens. Feeds the "what's new" channels between full loads.
     */
    suspend fun recentlyAdded(sections: List<LibrarySection>?, limit: Int): List<MediaItem> = emptyList()
    /**
     * Stream URL for `item`, starting `startingAt` seconds in (the guide joins shows mid-stream).
     * `session` is a caller-chosen label ("preview", "watch") so a backend keeps one server-side stream per purpose.
     * Default `tracks` = server-default tracks (the guide preview, trailers).
     */
    suspend fun playbackSource(item: MediaItem, startingAt: Double, session: String, tracks: TrackSelection = TrackSelection()): PlaybackSource
    /** Audio and subtitle tracks of `item`, for the picker. Empty when the backend can't say. */
    suspend fun tracks(item: MediaItem): List<MediaTrack> = emptyList()
    /** A trailer for `item` (local extra or remote trailer the server can stream), or null when it has none. */
    suspend fun trailer(item: MediaItem, session: String): PlaybackSource? = null
    /** Best-effort release of the server-side stream for `session`; no-op for backends without one. */
    suspend fun stopPlayback(session: String) {}
    /** Marks `item` (an episode itself, not its show) watched for the signed-in user, as the server's own "mark as
     *  watched" does. No-op for the demo. */
    suspend fun markWatched(item: MediaItem) {}
    /** Absolute, authenticated URL for a server-relative artwork path, or null. */
    fun artworkURL(path: String?): String?
    /** Wide art for the preview window, falling back to the poster. */
    fun backdropURL(item: MediaItem): String? = artworkURL(item.backdropPath ?: item.artworkPath)
}

sealed class MediaServerError(val errorDescription: String) : Exception(errorDescription) {
    class unauthorized : MediaServerError("Not authorized. Sign in again.")
    class http(val code: Int, val body: String) : MediaServerError("Server returned $code")
    class invalidResponse(val why: String) : MediaServerError("Unexpected response: $why")
    class notFound(val what: String) : MediaServerError("Not found: $what")
}

/** `URLRequest`, as much of it as the clients use. */
class URLRequest(var url: String, var timeoutInterval: Double = 60.0) {
    var httpMethod = "GET"
    var httpBody: ByteArray? = null
    val headers = LinkedHashMap<String, String>()
    fun setValue(value: String?, forHTTPHeaderField: String) {
        if (value == null) headers.remove(forHTTPHeaderField) else headers[forHTTPHeaderField] = value
    }
}

/** Minimal shared HTTP helper. Throws `MediaServerError` on non-2xx. */
object HTTP {
    /** Blocking HttpURLConnection on Dispatchers.IO; cancelling the coroutine disconnects, like a URLSession task. */
    suspend fun data(request: URLRequest): ByteArray = suspendCancellableCoroutine { cont ->
        val conn = AtomicReference<HttpURLConnection?>(null)   // the hop in flight
        cont.invokeOnCancellation { runCatching { conn.get()?.disconnect() } }
        Dispatchers.IO.asExecutor().execute {
            try { cont.resume(perform(request, conn)) } catch (e: Throwable) { cont.resumeWithException(e) }
        }
    }

    /** URLSession follows redirects itself (http → https too, which HttpURLConnection won't): 301/302/303 turn a
     *  POST into a GET without its body, 307/308 keep the method and body. Never https → http: the token header would
     *  follow it in the clear, so that fails like any network error. */
    private fun perform(request: URLRequest, current: AtomicReference<HttpURLConnection?>): ByteArray {
        var url = URL(request.url)
        var method = request.httpMethod
        var body = request.httpBody
        repeat(16) {
            val conn = url.openConnection() as HttpURLConnection
            current.set(conn)
            val timeout = (request.timeoutInterval * 1000).toInt()
            conn.connectTimeout = timeout   // URLRequest's one timeoutInterval covers the connect too
            conn.readTimeout = timeout
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            for ((k, v) in request.headers) conn.setRequestProperty(k, v)
            if (body != null || method == "POST" || method == "PUT") {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body?.size ?: 0)
                conn.outputStream.use { if (body != null) it.write(body) }
            }
            // Reading the stream to the end (no disconnect) hands the socket back to the keep-alive pool.
            val code = conn.responseCode
            val location = conn.getHeaderField("Location")
            if (code in setOf(301, 302, 303, 307, 308) && location != null) {
                runCatching { conn.inputStream.use { it.readBytes() } }
                val next = URL(url, location)
                if (url.protocol == "https" && next.protocol != "https") throw java.net.ProtocolException("insecure redirect")
                url = next
                if (code == 303 || (code != 307 && code != 308 && method == "POST")) { method = "GET"; body = null }
                return@repeat
            }
            val data = (if (code in 200 until 300) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200 until 300) {
                if (code == 401 || code == 403) throw MediaServerError.unauthorized()
                throw MediaServerError.http(code, String(data, Charsets.UTF_8))
            }
            return data
        }
        throw java.net.ProtocolException("Too many redirects")
    }

    /**
     * The response parsed with org.json (JSONObject / JSONArray / Boolean / Number / String), handed to `decode`.
     * A parse failure or a decode failure (missing key, wrong type) is `invalidResponse`, as Swift's Decodable failures are.
     * Parsing runs off the main thread, like Swift's nonisolated async helpers.
     */
    suspend fun <T> json(request: URLRequest, decode: (Any) -> T): T = withContext(Dispatchers.Default) {
        val data = data(request)
        try {
            decode(JSONTokener(String(data, Charsets.UTF_8)).nextValue())
        } catch (e: Exception) {
            if (e is MediaServerError) throw e
            throw MediaServerError.invalidResponse(e.javaClass.simpleName)   // org.json's message can quote the body
        } catch (e: StackOverflowError) {   // Android's org.json recurses with no depth limit
            throw MediaServerError.invalidResponse("nested too deep")
        }
    }
}

/** Swift `try?` for suspend calls that must still honour cancellation. */
suspend fun <T> runCatchingNonCancel(block: suspend () -> T): T? =
    try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }

/** `URLComponents.queryItems` → "?a=1&b=2", every reserved character percent-encoded (servers decode either way). */
internal fun queryString(query: List<Pair<String, String>>): String =
    if (query.isEmpty()) "" else "?" + query.joinToString("&") { (k, v) -> "${percentEncode(k)}=${percentEncode(v)}" }

/** RFC 3986 unreserved characters stay; everything else is %XX of its UTF-8 bytes. */
internal fun percentEncode(s: String): String =
    java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%7E", "~").replace("*", "%2A")
