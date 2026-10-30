package com.rerunguide.core

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections

/**
 * `PlexAuth.server(at)`: a typed Plex address only names a server on the account; the link goes to an address plex.tv
 * lists for it, and no token goes anywhere but plex.tv while that is checked. plex.tv and the servers are local stand-ins.
 */
class PlexAddressTests {
    private val started = mutableListOf<HttpServer>()
    /** (host:port + path, X-Plex-Token) of every request any stand-in received. */
    private val seen = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
    private val plexTV = PlexAuth.resourcesURL

    private fun serve(routes: Map<String, () -> String>): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${s.address.port}"
        s.createContext("/") { ex ->
            seen.add("${s.address.port}${ex.requestURI.path}" to ex.requestHeaders.getFirst("X-Plex-Token"))
            val body = routes[ex.requestURI.path]?.invoke()
            if (body == null) { ex.sendResponseHeaders(404, -1); ex.close() }
            else { val b = body.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        }
        s.start(); started.add(s)
        return base
    }
    private fun identity(id: String) = mapOf("/identity" to { """{"MediaContainer":{"machineIdentifier":"$id"}}""" })

    private val den = serve(identity("m1"))
    private val claimant = serve(identity("m1"))                 // says it's Den; plex.tv doesn't list it
    private val stranger = serve(identity("m2"))                 // a Plex server, not this account's
    private val cabin = serve(identity("m3"))                    // Cabin, whose listed address is down
    private val attic = serve(identity("m4"))                    // Attic: its plex.direct name won't resolve
    private val vault = serve(identity("m5"))                    // Vault: the same, but secure connections only
    private val loft = serve(identity("m6"))                     // Loft: like Attic, but plex.tv says this device is elsewhere
    private val shed = serve(identity("m7"))                     // Shed: a listed plain-http address; plex.tv doesn't say where
    private val router = serve(mapOf("/identity" to { "<html>router</html>" }))   // not a Plex server
    private val dead = serve(emptyMap()).also { started.removeAt(started.size - 1).stop(0) }   // nothing listening
    private val account = serve(mapOf("/resources" to {
        """[{"name":"Den","clientIdentifier":"m1","provides":"server","accessToken":"server-token","owned":true,"publicAddressMatches":true,
              "connections":[{"uri":"$den","protocol":"http","local":true,"relay":false},
                             {"uri":"https://1-2-3-4.abc.plex.direct:32400","protocol":"https","local":false,"relay":false}]},
            {"name":"Cabin","clientIdentifier":"m3","provides":"server","accessToken":"cabin-token","owned":true,"publicAddressMatches":true,
              "connections":[{"uri":"$dead","protocol":"http","local":false,"relay":false}]},
            {"name":"Attic","clientIdentifier":"m4","provides":"server","accessToken":"attic-token","owned":true,"publicAddressMatches":true,
              "connections":[{"uri":"https://attic.invalid:32400","protocol":"https","local":true,"relay":false,"address":"127.0.0.1","port":${attic.substringAfterLast(":")}}]},
            {"name":"Vault","clientIdentifier":"m5","provides":"server","accessToken":"vault-token","owned":true,"httpsRequired":true,"publicAddressMatches":true,
              "connections":[{"uri":"https://vault.invalid:32400","protocol":"https","local":true,"relay":false,"address":"127.0.0.1","port":${vault.substringAfterLast(":")}}]},
            {"name":"Loft","clientIdentifier":"m6","provides":"server","accessToken":"loft-token","owned":true,"publicAddressMatches":false,
              "connections":[{"uri":"https://loft.invalid:32400","protocol":"https","local":true,"relay":false,"address":"127.0.0.1","port":${loft.substringAfterLast(":")}}]},
            {"name":"Shed","clientIdentifier":"m7","provides":"server","accessToken":"shed-token","owned":true,
              "connections":[{"uri":"https://shed.invalid:32400","protocol":"https","local":false,"relay":false},
                             {"uri":"$shed","protocol":"http","local":false,"relay":false}]},
            {"name":"Phone","clientIdentifier":"p1","provides":"client,player","accessToken":"x","owned":true,"connections":[]}]"""
    })).also { PlexAuth.resourcesURL = "$it/resources" }

    @After fun stop() { started.forEach { it.stop(0) }; PlexAuth.resourcesURL = plexTV }

    private fun server(address: String) = runBlocking { PlexAuth.server(address, "account-token", "test") }

    @Test fun testAddressNamesTheServerPlexTVListsTheWay() {
        val s = server(den)
        assertEquals("m1", s.id); assertEquals("Den", s.name)
        assertEquals("server-token", s.accessToken)
        assertEquals(den, s.baseURL)
        assertEquals(listOf(den, "https://1-2-3-4.abc.plex.direct:32400"), s.addresses.map { it.url })

        // A host plex.tv doesn't list that claims to be Den only names it: the link goes to Den's listed address.
        val claimed = server(claimant)
        assertEquals(den, claimed.baseURL)
        assertEquals(s.addresses, claimed.addresses)

        // A router that blocks DNS answers pointing at private IPs: the LAN plex.direct name fails, its plain address works.
        val a = server(attic)
        assertEquals(attic, a.baseURL)
        assertEquals(listOf("https://attic.invalid:32400", attic), a.addresses.map { it.url })
    }

    @Test fun testOtherAddressesAreTurnedDown() {
        for ((address, want) in listOf(stranger to PlexAuth.AddressError.notOnAccount::class, cabin to PlexAuth.AddressError.unreachable::class, vault to PlexAuth.AddressError.unreachable::class,
                                       loft to PlexAuth.AddressError.unreachable::class, shed to PlexAuth.AddressError.unreachable::class,
                                       router to PlexAuth.AddressError.noServer::class, dead to PlexAuth.AddressError.noServer::class)) {
            try { server(address); fail("$address accepted") }
            catch (e: PlexAuth.AddressError) { assertEquals(address, want, e::class) }
        }
    }

    /** A TV doesn't move between networks, so it keeps plain-http addresses (Loft's LAN one, Shed's listed one) whatever
     *  plex.tv says; a phone turns both down (testOtherAddressesAreTurnedDown). */
    @Test fun testTVsKeepThePlainLANAddress() {
        val phone = ClientDevice.device
        ClientDevice.device = "Google TV"
        try {
            assertEquals(loft, server(loft).baseURL)
            assertEquals(shed, server(shed).baseURL)
        } finally { ClientDevice.device = phone }
    }

    /** Only a plain yes from plex.tv counts; no, no answer, no token or an error is "not on its network". */
    @Test fun testOnServerNetworkFailsClosed() = runBlocking {
        assertTrue(PlexAuth.onServerNetwork("m4", "account-token", "test"))
        for (id in listOf("m6", "m7", "unknown")) assertFalse(id, PlexAuth.onServerNetwork(id, "account-token", "test"))
        assertFalse(PlexAuth.onServerNetwork("m4", null, "test"))
        for (broken in listOf("$dead/resources", "$router/identity")) {
            PlexAuth.resourcesURL = broken
            assertFalse(broken, PlexAuth.onServerNetwork("m4", "account-token", "test"))
        }
    }

    /** No token at all goes anywhere but plex.tv while the address is checked: the account token is plex.tv's, and the
     *  server's token is only used later, by the link, at an address plex.tv listed. */
    @Test fun testTokensOnlyGoToPlexTV() {
        for (a in listOf(den, claimant, stranger, cabin)) runCatching { server(a) }
        assertFalse(seen.isEmpty())
        val plexTVPort = account.substringAfterLast(":")
        for ((path, token) in seen.filter { !it.first.startsWith(plexTVPort) }) assertNull(path, token)
        assertTrue(seen.any { it.first == "$plexTVPort/resources" && it.second == "account-token" })
    }
}
