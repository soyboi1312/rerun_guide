package com.rerunguide.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Emby Connect's PIN sign-in, the TV flow Emby's own apps use and the Emby side of plex.tv/link: show a PIN, the viewer
 * types it at emby.media/pin while signed in to their Emby Connect account, then this device lists that account's servers
 * and trades a server's Connect key for an ordinary sign-in on it (`JellyfinClient.connectExchange`).
 * Emby doesn't document the PIN calls; these are the ones Emby.ApiClient.Java's ConnectService makes. A server only shows
 * up once its owner has linked an Emby Connect account to one of its users (Emby dashboard › Users › Emby Connect).
 */
object EmbyConnect {
    data class Pin(val code: String, val deviceID: String)

    /** The Emby Connect account a confirmed PIN signed in. */
    data class Account(val userID: String, val token: String)

    /** A 5-digit code to show; it lasts a few minutes (`pollPIN` throws `notFound` once it has expired). */
    suspend fun requestPIN(deviceID: String): Pin {
        val code = HTTP.json(request("pin", query = listOf("deviceId" to deviceID), form = listOf("deviceId" to deviceID))) {
            (it as JSONObject).getString("Pin")
        }
        return Pin(code, deviceID)
    }

    /** null while the viewer hasn't typed the code yet; the account once they have. Throws `notFound` when it expired. */
    suspend fun pollPIN(pin: Pin): Account? {
        val (confirmed, expired) = try {
            HTTP.json(request("pin", query = listOf("deviceId" to pin.deviceID, "pin" to pin.code))) {
                (it as JSONObject).let { o -> o.getBoolean("IsConfirmed") to o.getBoolean("IsExpired") }
            }
        } catch (e: MediaServerError.http) {
            if (e.code == 404) throw MediaServerError.notFound("Emby PIN (expired)") else throw e
        }
        if (expired) throw MediaServerError.notFound("Emby PIN (expired)")
        if (!confirmed) return null
        return HTTP.json(request("pin/authenticate", form = listOf("deviceId" to pin.deviceID, "pin" to pin.code))) {
            (it as JSONObject).let { o -> Account(o.getString("UserId"), o.getString("AccessToken")) }
        }
    }

    /**
     * The servers linked to `account`, as rows for the server list: `accessToken` holds the server's Connect access key
     * (traded for a real sign-in by `JellyfinClient.connectExchange`), `addresses` its LAN address first, then its remote one.
     */
    suspend fun servers(account: Account): List<PlexServer> {
        val r = request("servers", query = listOf("userId" to account.userID))
        r.setValue(account.token, forHTTPHeaderField = "X-Connect-UserToken")
        return rows(HTTP.json(r) { it as JSONArray })
    }

    internal fun rows(servers: JSONArray): List<PlexServer> = servers.objects().mapNotNull { s ->
        val key = s.stringOrNull("AccessKey")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val id = s.stringOrNull("SystemId") ?: return@mapNotNull null
        val addresses = listOfNotNull(
            s.stringOrNull("LocalAddress")?.let(::url)?.let { PlexAddress(it, local = true) },
            s.stringOrNull("Url")?.let(::url)?.let { PlexAddress(it, local = false) },
        )
        val first = addresses.firstOrNull() ?: return@mapNotNull null
        PlexServer(id = id, name = s.stringOrNull("Name") ?: java.net.URL(first.url).host ?: "Emby", baseURL = first.url,
                   accessToken = key, addresses = addresses)
    }

    private fun url(s: String): String? {
        val a = s.trim().trimEnd('/')
        return a.takeIf { runCatching { java.net.URL(it).host }.getOrNull()?.isNotEmpty() == true }   // like URL(string:)?.host
    }

    /** connect.emby.media/service/<path>; `form` makes it a form-encoded POST, as Emby's clients send these. */
    private fun request(path: String, query: List<Pair<String, String>> = emptyList(), form: List<Pair<String, String>>? = null): URLRequest {
        val r = URLRequest("https://connect.emby.media/service/$path" + queryString(query.sortedBy { it.first }), timeoutInterval = 20.0)
        r.setValue("RerunGuide/1.0", forHTTPHeaderField = "X-Application")
        r.setValue("application/json", forHTTPHeaderField = "Accept")
        if (form != null) {
            r.httpMethod = "POST"
            r.httpBody = queryString(form.sortedBy { it.first }).removePrefix("?").toByteArray(Charsets.UTF_8)
            r.setValue("application/x-www-form-urlencoded", forHTTPHeaderField = "Content-Type")
        }
        return r
    }
}
