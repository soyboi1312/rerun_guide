package com.rerunguide.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/**
 * The Plex and Emby clients against real servers. Skipped unless the env vars are set (README › Android, Google TV and Fire TV):
 *   set -a; . path/to/test.env; set +a; ./gradlew :core:test --tests '*LiveServersTest*' --rerun -i
 * Prints a redacted summary (no tokens, no query strings), one `P|` line per check. LIVE_DUMP_DIR, when set, gets one
 * canonical line per item for a field-level diff.
 */
class LiveServersTest {
    private val env = System.getenv()
    private val secrets = listOf("PLEX_TOKEN", "EMBY_TOKEN", "EMBY_USER_ID").mapNotNull { env[it] }.filter { it.length > 3 }
    private fun say(s: String) = println(secrets.fold(s) { a, t -> a.replace(t, "***") })
    private val deviceID = "rerunguide-parity-kt"

    @Test fun plex() {
        val url = env["PLEX_URL"]; val token = env["PLEX_TOKEN"]
        assumeTrue("PLEX_URL / PLEX_TOKEN not set", !url.isNullOrEmpty() && !token.isNullOrEmpty())
        check("Plex", PlexClient(url!!, token!!, clientID = deviceID, serverName = "plex-test")) {
            // PMS lists its transcode sessions; the key carries our session id ("<clientID>-parity").
            val r = get("${url.trimEnd('/')}/transcode/sessions", mapOf("X-Plex-Token" to token, "Accept" to "application/json"))
            if (r.code != 200) null else JSONObject(r.text).getJSONObject("MediaContainer").optJSONArray("TranscodeSession")
                ?.let { a -> (0 until a.length()).count { a.getJSONObject(it).optString("key").contains(deviceID) } } ?: 0
        }
    }

    @Test fun emby() {
        val url = env["EMBY_URL"]; val token = env["EMBY_TOKEN"]; val user = env["EMBY_USER_ID"]
        assumeTrue("EMBY_URL / EMBY_TOKEN / EMBY_USER_ID not set", !url.isNullOrEmpty() && !token.isNullOrEmpty() && !user.isNullOrEmpty())
        check("Emby", JellyfinClient(url!!, token!!, userID = user!!, deviceID = deviceID, kind = ServerKind.emby, serverName = "emby-test")) {
            // Emby (4.10) leaves a killed job's TranscodingInfo on the session at 100 % with no position (the Swift client
            // gets the same); only one still short of 100 % is running.
            val r = get("${url.trimEnd('/')}/Sessions?DeviceId=$deviceID", mapOf("X-Emby-Token" to token, "Accept" to "application/json"))
            if (r.code != 200) null else JSONArray(r.text).let { a ->
                (0 until a.length()).count { a.getJSONObject(it).optJSONObject("TranscodingInfo")?.optDouble("CompletionPercentage", 0.0)?.let { p -> p < 100 } == true }
            }
        }
    }

    /** `running`: this client's live transcodes on the server, null when the server won't say. */
    private fun check(tag: String, server: MediaServer, running: () -> Int?) = runBlocking {
        say("\n==================== $tag")
        var playing: Int? = null
        var after: Int? = null
        try {
            val libs = server.libraries()
            for (l in libs) say("P|$tag lib ${l.id} \"${l.title}\" ${l.kind} n=${l.itemCount}")
            val all = server.libraryItems(null)
            say("P|$tag all ${summary(all)}")
            dump("$tag-all", all)
            val video = libs.filter { it.kind != LibrarySection.Kind.other }
            val bySection = server.libraryItems(video)
            say("P|$tag sections ${summary(bySection)}")
            dump("$tag-sections", bySection)
            for (l in video) say("P|$tag items lib=${l.id} ${summary(bySection.filter { it.libraryID == l.id })}")

            val movies = all.filter { it.kind == MediaItem.Kind.movie }
            val movie = movies.first()
            val episode = all.first { it.kind == MediaItem.Kind.episode }
            say("P|$tag picks movie=${movie.id} episode=${episode.id}")
            for (m in listOf(movie, episode)) say("P|$tag tracks ${m.id}: " + server.tracks(m).joinToString(" ", transform = ::track))

            for (m in listOf(movie, episode)) {
                val src = server.playbackSource(m, startingAt = 600.0, session = "parity")
                say("P|$tag playback ${m.id}: ${shape(src.url)} startsAtOffset=${src.startsAtOffset} subtitleInPlaylist=${src.subtitleInPlaylist}")
                stream(tag, src.url)
                if (m == movie) playing = running()   // a transcode is certainly live here (a trailer at the end may already be done)
            }

            var fetched = false
            val trailers = movies.take(5).map { m ->
                val t = server.trailer(m, session = "parity")
                if (t != null && !fetched) { say("  trailer ${m.id}: ${shape(t.url)}"); stream(tag, t.url); fetched = true }
                "${m.id}=${if (t != null) "yes" else "no"}"
            }
            say("P|$tag trailers ${trailers.joinToString(" ")} -> ${trailers.count { it.endsWith("yes") }}/5")

            for ((what, u) in listOf("poster movie" to server.artworkURL(movie.artworkPath), "backdrop movie" to server.backdropURL(movie),
                                     "poster episode" to server.artworkURL(episode.artworkPath), "backdrop episode" to server.backdropURL(episode))) {
                if (u == null) { say("  $what: none"); continue }
                val r = get(u)
                say("  $what ${URI(u).rawPath}: HTTP ${r.code} ${r.type} ${r.bytes.size} bytes")
                assertEquals(200, r.code)
                assertTrue(r.type.startsWith("image/"))
            }
        } finally {
            server.stopPlayback("parity")
            delay(2_000)   // let the server tear the transcode down before the leftover check
            after = running()
            say("  $tag transcodes for this client: while playing ${playing ?: "n/a"}, after stopPlayback ${after ?: "n/a"}")
        }
        if (playing != null) assertTrue("the check should see the transcode we started", playing!! >= 1)
        if (after != null) assertEquals(0, after)
    }

    /** GET the playback URL: an HLS master → its first variant → that variant's init map and the segment playback starts with; or media bytes. */
    private fun stream(tag: String, url: String) {
        val r = get(url)
        say("  $tag GET ${URI(url).rawPath}: HTTP ${r.code} ${r.type} ${r.bytes.size} bytes" + if (r.code != 200) " ${r.text.take(300)}" else "")
        assertEquals(200, r.code)
        if (!r.text.startsWith("#EXTM3U")) {
            assertTrue("media, got ${r.type}", r.type.startsWith("video/") || r.type.startsWith("audio/") || r.type == "application/octet-stream")
            return
        }
        var playlist = URI(url) to r.text
        if (r.text.contains("#EXT-X-STREAM-INF")) {
            val variant = playlist.first.resolve(firstURI(r.text))
            val v = get(variant.toString())
            say("  $tag variant ${variant.rawPath}: HTTP ${v.code} ${v.type} ${v.text.lines().count { it.startsWith("#EXTINF") }} segments")
            assertEquals(200, v.code)
            assertTrue(v.text.startsWith("#EXTM3U"))
            playlist = variant to v.text
        }
        Regex("#EXT-X-MAP:URI=\"([^\"]+)\"").find(playlist.second)?.let { m ->
            val map = playlist.first.resolve(m.groupValues[1])
            val g = get(map.toString())
            say("  $tag init map ${map.rawPath}: HTTP ${g.code} ${g.type} ${g.bytes.size} bytes")
            assertEquals(200, g.code)
        }
        val seg = playlist.first.resolve(startSegment(playlist.second))
        val s = get(seg.toString())
        val magic = s.bytes.take(8).let { b -> if (b.firstOrNull() == 0x47.toByte()) "mpegts" else String(b.drop(4).toByteArray(), Charsets.ISO_8859_1) }
        say("  $tag segment ${seg.rawPath}: HTTP ${s.code} ${s.type} ${s.bytes.size} bytes ($magic)")
        assertEquals(200, s.code)
        assertTrue("real media, not a 188-byte placeholder", s.bytes.size > 188)
    }

    /**
     * The segment playback begins with. Plex's offset playlist covers the whole item from 0 with
     * `#EXT-X-START:TIME-OFFSET=<offset>`, and the segments before it are 188-byte placeholders; elsewhere the first one.
     */
    private fun startSegment(playlist: String): String {
        val start = Regex("#EXT-X-START:TIME-OFFSET=([0-9.]+)").find(playlist)?.groupValues?.get(1)?.toDouble() ?: 0.0
        var t = 0.0
        var duration = 0.0
        for (l in playlist.lines()) {
            if (l.startsWith("#EXTINF:")) duration = l.removePrefix("#EXTINF:").substringBefore(",").toDouble()
            else if (l.isNotBlank() && !l.startsWith("#")) { if (t + duration > start) return l.trim(); t += duration }
        }
        return firstURI(playlist)
    }

    private fun firstURI(playlist: String) = playlist.lines().first { it.isNotBlank() && !it.startsWith("#") }.trim()

    private class Response(val code: Int, val type: String, val bytes: ByteArray) { val text get() = String(bytes, Charsets.UTF_8) }

    /** Plain GET reading at most `limit` bytes (a direct-play movie is gigabytes), then dropping the connection. */
    private fun get(url: String, headers: Map<String, String> = emptyMap(), limit: Int = 1024 * 1024): Response {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 90_000
        headers.forEach(c::setRequestProperty)
        try {
            val code = c.responseCode
            val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readNBytes(limit) } ?: ByteArray(0)
            return Response(code, c.contentType ?: "", body)
        } finally { c.disconnect() }
    }

    /** Path and query keys only; the values carry tokens and per-call session ids. */
    private fun shape(url: String): String = URI(url).let { u -> "${u.rawPath} keys=${u.rawQuery?.split("&")?.map { it.substringBefore("=") } ?: emptyList()}" }

    private fun track(t: MediaTrack) = "${t.kind}:${t.id}:${t.language ?: "~"}:${t.title}:${if (t.isDefault) "D" else "-"}${if (t.isForced) "F" else "-"}"

    private fun summary(items: List<MediaItem>) =
        "n=${items.size} movies=${items.count { it.kind == MediaItem.Kind.movie }} episodes=${items.count { it.kind == MediaItem.Kind.episode }} " +
            "digest=${digest(items)} first5=${items.take(5).map { it.id }}"

    /** Every field the clients fill, one stable line per item, so dumps from two clients can be diffed. */
    private fun line(m: MediaItem) = listOf(m.id, m.kind.name, m.title, m.seriesTitle, m.seriesID, m.season, m.episode, m.year,
        m.genres.joinToString(","), Math.round(m.runtime * 1000), m.artworkPath, m.backdropPath, m.overview, m.contentRating,
        m.directors.joinToString(","), m.actors.joinToString(","), m.libraryID, m.addedAt?.epochSecond)
        .joinToString("|") { it?.toString() ?: "~" }

    private fun digest(items: List<MediaItem>) = MessageDigest.getInstance("SHA-256")
        .digest(items.joinToString("\n", transform = ::line).toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }

    private fun dump(name: String, items: List<MediaItem>) {
        val dir = env["LIVE_DUMP_DIR"] ?: return
        File(dir, "kt-$name.txt").writeText(items.joinToString("\n", transform = ::line) + "\n")
    }
}
