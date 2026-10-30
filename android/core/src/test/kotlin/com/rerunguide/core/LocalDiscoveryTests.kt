package com.rerunguide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URI

class LocalDiscoveryTests {
    @Test fun testJellyfinReply() {
        val s = LocalDiscovery.jellyfin("""{"Address":"http://192.168.1.5:8096","Id":"abc","Name":"Den","EndpointAddress":null}""".toByteArray(), "192.168.1.5")
        assertEquals("abc", s?.id)
        assertEquals("Den", s?.name)
        assertEquals("http://192.168.1.5:8096", s?.baseURL)
        assertEquals("", s?.accessToken)
        assertNull(LocalDiscovery.jellyfin("nope".toByteArray(), "192.168.1.5"))
        // Only the sender counts: a reply naming another host is reached at the IP it came from (a forged reply can't
        // redirect the password; a Docker server naming its container address is found at the host).
        fun at(address: String, ip: String) = LocalDiscovery.jellyfin("""{"Address":"$address","Id":"x","Name":"X"}""".toByteArray(), ip)?.baseURL
        assertEquals("http://192.168.1.9:8096", at("https://evil.example.net:8920", "192.168.1.9"))
        assertEquals("http://192.168.1.5:8096", at("http://172.17.0.5:8096", "192.168.1.5"))
        assertEquals("https://192.168.1.5:8920", at("https://10.0.0.2:8920", "192.168.1.5"))
        assertEquals("http://192.168.1.5:8096/jellyfin", at("http://jellyfin.local:8096/jellyfin", "192.168.1.5"))
        assertEquals("http://192.168.1.5:8096", at("http://jellyfin.local", "192.168.1.5"))
        assertEquals("http://192.168.1.5:8096", at("https://jellyfin.example.com", "192.168.1.5"))   // a reverse proxy's URL
        assertEquals("http://192.168.1.5:8096", at("HTTP://192.168.1.5:8096", "192.168.1.5"))
        assertEquals("http://192.168.1.5:8096/p", at("http://u:p@192.168.1.5:8096/p?q#f", "192.168.1.5"))
        assertNull(at("file:///etc/passwd", "192.168.1.5"))
        assertNull(at("http:foo", "192.168.1.5"))
        assertEquals("http://192.168.1.5:8097/jf", at("http://jellyfin.local:8097/jf", "192.168.1.5"))
        assertEquals("http://192.168.1.5:8097", at("http://media.4k:8097", "192.168.1.5"))
        assertEquals("https://192.168.1.5:8920/jf", at("https://[::1]:8920/jf", "192.168.1.5"))
        assertEquals("http://192.168.1.5:8096/a%20b", at("http://192.168.1.5:8096/a%20b", "192.168.1.5"))
        assertNull(at("http://:8096", "192.168.1.5"))
        assertNull(at("http://host:abc", "192.168.1.5"))
    }

    @Test fun testPlexGDMReply() {
        val reply = "HTTP/1.0 200 OK\r\nContent-Type: plex/media-server\r\nName: Living Room\r\nPort: 32401\r\nResource-Identifier: r1\r\nVersion: 1.40\r\n"
        val s = LocalDiscovery.plex(reply.toByteArray(), ip = "10.0.0.9")
        assertEquals("r1", s?.id)
        assertEquals("Living Room", s?.name)
        assertEquals("http://10.0.0.9:32401", s?.baseURL)
        assertEquals(32400, URI(LocalDiscovery.plex("HTTP/1.0 200 OK\r\nResource-Identifier: r2\r\n".toByteArray(), ip = "10.0.0.9")!!.baseURL).port)
        assertNull(LocalDiscovery.plex("HTTP/1.0 200 OK\r\nPort: 1@evil.com\r\nResource-Identifier: r3\r\n".toByteArray(), ip = "10.0.0.9"))   // a Port can't move the host
        assertNull(LocalDiscovery.plex("HTTP/1.0 404 Not Found\r\n".toByteArray(), ip = "10.0.0.9"))
    }
}
