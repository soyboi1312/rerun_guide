package com.rerunguide.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI

/**
 * LAN server discovery over UDP broadcast, each server's own protocol (none advertises mDNS reliably):
 * Jellyfin/Emby answer "who is JellyfinServer?" / "who is EmbyServer?" on 7359 with JSON {Address, Id, Name};
 * Plex GDM answers "M-SEARCH * HTTP/1.1" on 32414 with an HTTP-style header block (Name, Port, Resource-Identifier).
 * Never throws: no permission, no reply, or no network → []. Jellyfin only answers the broadcast address.
 */
object LocalDiscovery {
    /** Listens 1.5 s for replies. */
    suspend fun find(kind: ServerKind): List<PlexServer> {
        val (port, probe) = when (kind) {
            ServerKind.jellyfin -> 7359 to "who is JellyfinServer?"
            ServerKind.emby -> 7359 to "who is EmbyServer?"
            ServerKind.plex -> 32414 to "M-SEARCH * HTTP/1.1\r\n\r\n"
            ServerKind.demo -> return emptyList()
        }
        // Blocking socket calls on an IO thread.
        return runInterruptible(Dispatchers.IO) { scan(kind, port, probe, 1500) }
    }

    private fun scan(kind: ServerKind, port: Int, probe: String, timeoutMs: Long): List<PlexServer> = try {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val bytes = probe.toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), port))
            val found = LinkedHashMap<String, PlexServer>()
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val ms = deadline - System.currentTimeMillis()
                if (ms <= 0) break
                socket.soTimeout = ms.toInt()
                val packet = DatagramPacket(buf, buf.size)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { break }
                if (packet.length <= 0) continue
                val data = buf.copyOf(packet.length)
                val ip = packet.address.hostAddress ?: continue
                val s = if (kind == ServerKind.plex) plex(data, ip) else jellyfin(data, ip)
                if (s != null) found[s.id] = s
            }
            found.values.toList()
        }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * The URL always points at the host the reply came from, never at an address the reply names (as the Plex path does),
     * so a reply can't send the sign-in to some other server; any LAN device can still pose as one. A reply naming an IP
     * keeps its scheme, port and path (a server in Docker names its container IP); one naming a DNS name, a reverse
     * proxy's published URL, falls back to plain HTTP at the sender on the named HTTP port, or the default 8096.
     */
    internal fun jellyfin(data: ByteArray, ip: String): PlexServer? = try {
        val r = JSONObject(String(data, Charsets.UTF_8))
        val named = URI(r.getString("Address"))
        val scheme = named.scheme?.lowercase()
        val hp = hostPort(named)
        if (hp == null || hp.first.isEmpty() || (scheme != "http" && scheme != "https")) null
        else {
            val (host, namedPort) = hp
            val namesIP = host.contains(':') || Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(host)
            val port = if (namesIP) namedPort.takeIf { it >= 0 } else if (scheme == "http" && namedPort >= 0) namedPort else 8096
            val url = "${if (namesIP) scheme else "http"}://$ip${port?.let { ":$it" } ?: ""}${named.rawPath ?: ""}"
            URI(url)   // must parse, as Swift's URLComponents.url must
            PlexServer(id = r.getString("Id"), name = r.getString("Name"), baseURL = url, accessToken = "")
        }
    } catch (_: Throwable) {   // StackOverflowError too: Android's org.json recurses with no depth limit
        null
    }

    /**
     * Host and port (-1: none) of `u`. java.net.URI reads some names Swift's URLComponents accepts (e.g. "media.4k:8097")
     * as a registry authority with no host, so those come from the raw authority. null: no host, or a port that isn't a number.
     */
    private fun hostPort(u: URI): Pair<String, Int>? {
        u.host?.let { return it to u.port }
        val a = u.rawAuthority?.substringAfterLast('@') ?: return null
        val i = a.lastIndexOf(':')
        if (i < 0) return a to -1
        val p = a.substring(i + 1)
        return a.substring(0, i) to (if (p.isEmpty()) -1 else p.toIntOrNull() ?: return null)
    }

    internal fun plex(data: ByteArray, ip: String): PlexServer? {
        val lines = String(data, Charsets.UTF_8).lines().filter { it.isNotEmpty() }
        if (lines.firstOrNull()?.contains("200 OK") != true) return null
        val h = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val i = line.indexOf(':')
            if (i < 0) continue
            h[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        val id = h["Resource-Identifier"] ?: return null
        val port = (h["Port"] ?: "32400").toUShortOrNull() ?: return null   // a number, so the URL stays on the sender
        val url = "http://$ip:$port"
        if (runCatching { URI(url) }.isFailure) return null
        return PlexServer(id = id, name = h["Name"] ?: ip, baseURL = url, accessToken = "")
    }
}
