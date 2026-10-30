package com.rerunguide.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress

class EmbyConnectTests {
    /** Connect's server list → server rows: LAN address first, then remote; the access key rides in `accessToken`; servers
     *  without a key, an id or any usable address are dropped. */
    @Test fun testServerRows() {
        val json = """
        [{"Name":"Basement","Url":"https://emby.example.net:8920/","LocalAddress":"http://192.0.2.10:8096","AccessKey":"k1","SystemId":"s1"},
         {"Name":"Remote only","Url":"https://far.example.net","LocalAddress":"","AccessKey":"k2","SystemId":"s2"},
         {"Name":"No key","Url":"https://x.example.net","AccessKey":"","SystemId":"s3"},
         {"Name":"No address","Url":"","LocalAddress":null,"AccessKey":"k4","SystemId":"s4"}]
        """
        val rows = EmbyConnect.rows(JSONArray(json))
        assertEquals(listOf("s1", "s2"), rows.map { it.id })
        assertEquals("http://192.0.2.10:8096", rows[0].baseURL)
        assertEquals(listOf("http://192.0.2.10:8096", "https://emby.example.net:8920"), rows[0].addresses.map { it.url })
        assertEquals(listOf(true, false), rows[0].addresses.map { it.local })
        assertEquals("k1", rows[0].accessToken)
        assertEquals("https://far.example.net", rows[1].baseURL)
        assertEquals("Remote only", rows[1].name)
    }

    /** A LAN address that isn't this server (another box at that IP on someone else's network) never gets the key. */
    @Test fun testExchangeSkipsAStrangerOnTheLAN() {
        val keys = mutableMapOf<Int, String?>()
        fun serve(id: String) = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                if (ex.requestURI.path == "/Connect/Exchange") keys[address.port] = ex.requestHeaders.getFirst("X-Emby-Token")
                val b = (if (ex.requestURI.path == "/System/Info/Public") """{"Id":"$id"}""" else """{"LocalUserId":"u","AccessToken":"t"}""").toByteArray()
                ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
            start()
        }
        val stranger = serve("other"); val home = serve("s1")
        try {
            val urls = listOf(stranger, home).map { "http://127.0.0.1:${it.address.port}" }
            val row = PlexServer("s1", "Basement", urls[0], "key", urls.map { PlexAddress(it, local = true) })
            assertEquals(urls[1], runBlocking { JellyfinClient.connectExchange(row, "cu", "dev") }.baseURL)
            assertNull(keys[stranger.address.port])
            assertEquals("key", keys[home.address.port])
        } finally { stranger.stop(0); home.stop(0) }
    }
}
