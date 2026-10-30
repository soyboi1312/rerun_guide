package com.rerunguide.core

import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections

/** `markWatched`: the one request each server's own "mark as watched" makes, for the episode itself (not its show). The
 *  servers are one local stand-in that records every request. */
class MarkWatchedTests {
    private class Seen(val method: String, val path: String, val query: String?, val headers: Headers)
    private val seen = Collections.synchronizedList(mutableListOf<Seen>())
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            seen.add(Seen(ex.requestMethod, ex.requestURI.path, ex.requestURI.rawQuery, ex.requestHeaders))
            val b = "{}".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val episode = MediaItem(id = "ep7", kind = MediaItem.Kind.episode, title = "Pilot", seriesTitle = "Show", seriesID = "show3", runtime = 1320.0)

    @After fun stop() { server.stop(0) }

    @Test fun testPlexScrobblesTheRatingKey() {
        runBlocking { PlexClient(base, "tok", "dev").markWatched(episode) }
        assertEquals(1, seen.size)
        val r = seen[0]
        assertEquals("PUT", r.method)
        assertEquals("/:/scrobble", r.path)
        assertEquals("identifier=com.plexapp.plugins.library&key=ep7", r.query)
        assertEquals("tok", r.headers.getFirst("X-Plex-Token"))
        assertEquals("dev", r.headers.getFirst("X-Plex-Client-Identifier"))
    }

    @Test fun testJellyfinAndEmbyPostPlayedItem() {
        for (kind in listOf(ServerKind.jellyfin, ServerKind.emby)) {
            seen.clear()
            runBlocking { JellyfinClient(base, "tok", "u1", "dev", kind).markWatched(episode) }
            assertEquals("$kind", 1, seen.size)
            val r = seen[0]
            assertEquals("$kind", "POST", r.method)
            assertEquals("$kind", "/Users/u1/PlayedItems/ep7", r.path)
            assertNull("$kind", r.query)
            assertEquals("$kind", "tok", r.headers.getFirst("X-Emby-Token"))
            for (h in listOf("Authorization", "X-Emby-Authorization")) {
                assertTrue("$kind $h", r.headers.getFirst(h)?.contains("Token=\"tok\"") == true)
            }
        }
    }

    @Test fun testDemoAsksNothing() {
        runBlocking { DemoMediaServer().markWatched(episode) }
        assertTrue(seen.isEmpty())
    }
}
