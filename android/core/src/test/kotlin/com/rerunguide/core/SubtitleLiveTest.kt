package com.rerunguide.core

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Subtitle delivery against a real Emby server, with the selection AppModel.trackSelection makes ("Forced only"
 * and "on"). Skipped unless the env vars are set:
 *   set -a; . path/to/test.env; set +a; ./gradlew :core:test --tests '*SubtitleLiveTest*' --rerun -i
 * Prints statuses, timings and counts only (no tokens, no query values, no subtitle text). Stops every transcode it starts.
 * Asserts only what must hold for any movie: a text-sub pick is an Hls rendition that plays; no subs = direct/remux 200.
 */
class SubtitleLiveTest {
    private val env = System.getenv()
    private val secrets = listOf("EMBY_TOKEN", "EMBY_USER_ID", "EMBY_URL").mapNotNull { env[it] }.filter { it.length > 3 }
    private fun say(s: String) = println(secrets.fold(s) { a, t -> a.replace(t, "***") })
    private val session = "subcheck"

    @Test fun emby() = runBlocking {
        val url = env["EMBY_URL"]; val token = env["EMBY_TOKEN"]; val user = env["EMBY_USER_ID"]
        assumeTrue("EMBY_URL / EMBY_TOKEN / EMBY_USER_ID not set", !url.isNullOrEmpty() && !token.isNullOrEmpty() && !user.isNullOrEmpty())
        val base = url!!.trimEnd('/')
        val c = JellyfinClient(base, token!!, userID = user!!, deviceID = "rerunguide-subcheck-kt", kind = ServerKind.emby, serverName = "emby-test")
        // One list with streams to find candidates.
        val list = JSONObject(get("$base/Users/$user/Items?Recursive=true&IncludeItemTypes=Movie&Fields=MediaStreams,Container",
            mapOf("X-Emby-Token" to token, "Accept" to "application/json"), limit = 256 * 1024 * 1024).text).getJSONArray("Items")
        class Cand(val id: String, val container: String, val subs: List<JSONObject>)
        val cands = (0 until list.length()).map { list.getJSONObject(it) }.map { o ->
            val ms = o.optJSONArray("MediaStreams")
            Cand(o.getString("Id"), o.optString("Container"), (0 until (ms?.length() ?: 0)).map { ms!!.getJSONObject(it) }.filter { it.optString("Type") == "Subtitle" })
        }
        fun JSONObject.codec() = optString("Codec").lowercase()
        val forcedSrt = cands.filter { c -> c.subs.any { it.codec() == "subrip" && it.optBoolean("IsDefault") && it.optBoolean("IsForced") } &&
                                             c.subs.any { it.codec() == "subrip" && !it.optBoolean("IsForced") } }.takeLast(3)
        val pgs = cands.filter { c -> c.subs.isNotEmpty() && c.subs.all { it.codec() == "pgssub" } }.takeLast(1)
        val plain = cands.filter { it.subs.isEmpty() }.let { p -> listOfNotNull(p.lastOrNull { "mp4" in it.container }, p.lastOrNull { it.container == "mkv" }) }
        say("candidates: forcedSrt=${forcedSrt.size} pgs=${pgs.size} plain=${plain.size}")
        val byID = c.libraryItems(null).associateBy { it.id }
        try {
            for ((label, group, modes) in listOf(Triple("SRT", forcedSrt, listOf("forced", "on")), Triple("PGS", pgs, listOf("forced", "on")), Triple("PLAIN", plain, listOf("forced"))))
                for ((n, cand) in group.withIndex()) {
                    val m = byID[cand.id] ?: continue
                    for (mode in modes) run(c, "$label${n + 1} ${m.title}", m, mode, expectRendition = label == "SRT", plain = label == "PLAIN")
                }
        } finally { c.stopPlayback(session) }
    }

    private suspend fun run(c: JellyfinClient, label: String, m: MediaItem, mode: String, expectRendition: Boolean, plain: Boolean) {
        val tracks = c.tracks(m)
        val sel = TrackSelection.preferred(tracks, ServerKind.emby, audioLanguage = null, subtitleMode = mode, subtitleLanguage = "eng")
        val picked = (sel.subtitle as? TrackSelection.Subtitle.track)?.let { s -> tracks.firstOrNull { it.id == s.id } }
        val selDesc = picked?.let { "track ${it.id} ${it.title} ${if (it.isForced) "F" else "-"}" } ?: sel.subtitle.toString()
        val t0 = System.nanoTime()
        val src = c.playbackSource(m, startingAt = 600.0, session = session, tracks = sel)
        val info = secs(t0)
        val u = URI(src.url)
        fun q(k: String) = u.rawQuery?.split("&")?.firstOrNull { it.startsWith("$k=") }?.substringAfter("=") ?: "-"
        val direct = u.rawPath.endsWith("/stream")
        var row = "ROW|$label|$mode|$selDesc|info $info ${if (direct) "direct" else "hls"} method=${q("SubtitleMethod")} reasons=${q("TranscodeReasons")} " +
            "seg=${q("SegmentContainer")} vcodec=${q("VideoCodec")} inPlaylist=${src.subtitleInPlaylist}"
        try {
            if (direct) {
                val t = System.nanoTime(); val r = get(src.url, mapOf("Range" to "bytes=0-65535"), limit = 65536)
                say("$row|direct GET ${r.code} ${r.bytes.size}B ${secs(t)}"); assertEquals(true, r.code in 200..206); return
            }
            var t = System.nanoTime(); val master = get(src.url)
            val media = master.text.lines().filter { it.startsWith("#EXT-X-MEDIA:") && "TYPE=SUBTITLES" in it }
            row += "|master ${master.code} ${secs(t)} subRenditions=${media.size} " + media.joinToString("") { "[${attr(it, "NAME")} lang=${attr(it, "LANGUAGE")} forced=${attr(it, "FORCED")} default=${attr(it, "DEFAULT")}]" }
            val variant = u.resolve(uris(master.text).first())
            val seg = variant.resolve(uris(get(variant.toString()).text).first())
            t = System.nanoTime(); val s = get(seg.toString())
            row += "|video seg0 ${s.code} ${s.bytes.size / 1024}KB ${secs(t)} ${if (s.bytes.firstOrNull() == 0x47.toByte()) "ts" else "mp4?"}"
            val line = media.firstOrNull { attr(it, "NAME") == picked?.title } ?: media.firstOrNull()
            if (line != null) {
                val sp = u.resolve(attr(line, "URI"))
                val segs = uris(get(sp.toString()).text)
                t = System.nanoTime(); val s0 = get(sp.resolve(segs.first()).toString())
                val map = s0.text.lines().firstOrNull { it.startsWith("X-TIMESTAMP-MAP") }?.substringBefore(",") ?: "nomap"
                val step = maxOf(1, segs.size / 40)
                val sampled = segs.indices.step(step).sumOf { get(sp.resolve(segs[it]).toString()).text.split("-->").size - 1 }
                row += "|subs(${attr(line, "NAME")}) segs=${segs.size} seg0 ${s0.code} ${secs(t)} cues=${s0.text.split("-->").size - 1} $map sampledCues=$sampled/${(segs.size + step - 1) / step}segs"
                assertEquals(200, s0.code)
            }
            say(row)
            if (plain) assertEquals(200, s.code)
            if (expectRendition) {   // a text-sub pick is delivered as a WebVTT rendition and its video plays
                assertEquals("SubtitleMethod for $label $mode", "Hls", q("SubtitleMethod"))
                assertEquals(true, media.isNotEmpty())
                assertEquals(200, s.code)
            }
        } finally { c.stopPlayback(session) }
    }

    private fun secs(t0: Long) = "%.2fs".format((System.nanoTime() - t0) / 1e9)
    private fun attr(line: String, k: String) = Regex("$k=(\"[^\"]*\"|[^,]*)").find(line)?.groupValues?.get(1)?.trim('"')
    private fun uris(p: String) = p.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.trim() }

    private class Response(val code: Int, val bytes: ByteArray) { val text get() = String(bytes, Charsets.UTF_8) }
    private fun get(url: String, headers: Map<String, String> = emptyMap(), limit: Int = 8 * 1024 * 1024): Response {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        headers.forEach(c::setRequestProperty)
        try {
            val code = c.responseCode
            return Response(code, (if (code < 400) c.inputStream else c.errorStream)?.use { it.readNBytes(limit) } ?: ByteArray(0))
        } catch (e: java.io.IOException) { return Response(-1, ByteArray(0)) } finally { c.disconnect() }
    }
}
