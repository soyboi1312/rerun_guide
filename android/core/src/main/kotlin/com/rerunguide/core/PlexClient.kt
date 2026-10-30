package com.rerunguide.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import kotlinx.coroutines.channels.Channel as AnswerChannel

data class PlexPIN(
    val id: Int,
    /** 4-char code the user types at https://plex.tv/link */
    val code: String,
)

data class PlexServer(
    val id: String,
    val name: String,
    /** Best reachable connection (local > remote > relay). */
    val baseURL: String,
    /** Per-server access token (differs from the account token for shared servers). */
    val accessToken: String,
    /**
     * Every address plex.tv lists for it, best first, so a device that moves between networks can pick again later
     * (`AppModel.pickPlexAddress`, which off TV drops the plain-http ones away from the server's network). Empty for a
     * server found by LAN discovery, which has no token either.
     */
    val addresses: List<PlexAddress> = emptyList(),
)

/** One of a server's addresses, and whether plex.tv says it is on the server's own network. */
data class PlexAddress(val url: String, val local: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("url", url).put("local", local)

    companion object {
        fun fromJson(o: JSONObject) = PlexAddress(o.getString("url"), o.optBoolean("local", false))
    }
}

// MARK: - Shared request plumbing

/** Read per request, so the device name the app sets at startup is always the one sent. */
private fun plexIdentity() = linkedMapOf(
    "X-Plex-Product" to "Rerun Guide", "X-Plex-Version" to "1.0", "X-Plex-Platform" to ClientDevice.platform,
    "X-Plex-Device" to ClientDevice.device, "X-Plex-Device-Name" to "Rerun Guide",
)

/** Every plex.tv and PMS request goes through here so the identity headers are always identical. */
private fun plexRequest(url: String, token: String?, clientID: String, method: String = "GET", timeout: Double = 30.0): URLRequest {
    val r = URLRequest(url, timeoutInterval = timeout)
    r.httpMethod = method
    r.setValue("application/json", forHTTPHeaderField = "Accept")
    r.setValue(clientID, forHTTPHeaderField = "X-Plex-Client-Identifier")
    if (token != null) r.setValue(token, forHTTPHeaderField = "X-Plex-Token")
    for ((k, v) in plexIdentity()) r.setValue(v, forHTTPHeaderField = k)
    return r
}

/** `base` + `path` (+ query), tolerating a base that carries a path prefix or trailing slash. */
private fun plexURL(base: String, path: String, query: List<Pair<String, String>> = emptyList()): String {
    val u = URI(base)
    val basePath = u.rawPath ?: ""
    val authority = u.rawAuthority?.let { "//$it" } ?: ""
    return "${u.scheme}:$authority${if (basePath.endsWith("/")) basePath.dropLast(1) else basePath}$path${queryString(query)}"
}

// MARK: - plex.tv

/** plex.tv account flow: request PIN → user enters it at plex.tv/link → poll → token → list servers. */
object PlexAuth {
    /**
     * plex.tv username/password sign-in (the "Type a server address instead" path). Returns the account token.
     * Plex 2FA: user appends the 6-digit code to the password (official Plex guidance for apps using this endpoint); the UI says so.
     */
    suspend fun signIn(username: String, password: String, clientID: String): String {
        val req = plexRequest("https://plex.tv/api/v2/users/signin", token = null, clientID = clientID, method = "POST")
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField = "Content-Type")
        req.httpBody = listOf("login" to username, "password" to password)
            .joinToString("&") { (k, v) -> "$k=${percentEncode(v)}" }.toByteArray(Charsets.UTF_8)
        // HTTP.data already maps 401 (bad credentials / missing 2FA code) to unauthorized.
        return HTTP.json(req) { (it as JSONObject).getString("authToken") }
    }

    private class PINResponse(val id: Int, val code: String, val authToken: String?)
    private fun pin(o: Any) = (o as JSONObject).let { PINResponse(it.getInt("id"), it.getString("code"), it.stringOrNull("authToken")) }

    /** `strong=true` yields the long OAuth code; omitting it yields the 4-char code for plex.tv/link. */
    suspend fun requestPIN(clientID: String): PlexPIN {
        val req = plexRequest("https://plex.tv/api/v2/pins", token = null, clientID = clientID, method = "POST")
        val p = HTTP.json(req, ::pin)
        return PlexPIN(id = p.id, code = p.code)
    }

    /** null until the user has claimed the PIN. */
    suspend fun pollPIN(pin: PlexPIN, clientID: String): String? {
        val req = plexRequest("https://plex.tv/api/v2/pins/${pin.id}", token = null, clientID = clientID)
        return try {
            HTTP.json(req, ::pin).authToken
        } catch (e: MediaServerError.http) {
            if (e.code == 404) throw MediaServerError.notFound("PIN expired. Request a new code") else throw e
        }
    }

    private class Connection(val uri: String, val protocol: String, val local: Boolean, val relay: Boolean, val address: String?, val port: Int?)
    /** `httpsRequired`: the server's "Secure connections: Required", so no plain-http address for it.
     *  `publicAddressMatches`: plex.tv sees this device at the server's public IP, i.e. on the server's network. */
    private class Resource(val name: String, val clientIdentifier: String, val provides: String, val accessToken: String?,
                           val connections: List<Connection>?, val httpsRequired: Boolean?, val publicAddressMatches: Boolean?)

    /** plex.tv's server list; a var only so PlexAddressTests can point it at a local stand-in. */
    internal var resourcesURL = "https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1&includeIPv6=0"

    private suspend fun resources(token: String, clientID: String, timeout: Double = 30.0): List<Resource> {
        return HTTP.json(plexRequest(resourcesURL, token = token, clientID = clientID, timeout = timeout)) { a ->
            (a as JSONArray).objects().map { r ->
                Resource(r.getString("name"), r.getString("clientIdentifier"), r.getString("provides"), r.stringOrNull("accessToken"),
                    r.objectsOrNull("connections")?.map {
                        Connection(it.getString("uri"), it.getString("protocol"), it.getBoolean("local"), it.getBoolean("relay"),
                                   it.stringOrNull("address"), it.intOrNull("port"))
                    }, r.boolOrNull("httpsRequired"), r.boolOrNull("publicAddressMatches"))
            }
        }
    }

    /**
     * Whether plex.tv sees this device on `machineID`'s network (`publicAddressMatches`), asked with the account
     * `token`. Phones and tablets trust a plain-http address only then. No token, an error or no answer: false.
     * 8 s, not 30: a plain-http link waits on this at launch. ponytail: it means "same public IP", which a shared exit
     * (carrier-grade NAT, one VPN) also gives off the server's network; plex.tv offers nothing finer.
     */
    suspend fun onServerNetwork(machineID: String, token: String?, clientID: String): Boolean {
        if (token == null) return false
        return runCatchingNonCancel { resources(token, clientID, timeout = 8.0).firstOrNull { it.clientIdentifier == machineID }?.publicAddressMatches } == true
    }

    /**
     * plex.tv's addresses for the server, best first. A LAN connection also gets its plain http://address:port, unless
     * the server requires secure connections: where the router blocks DNS answers that point at private IPs (DNS
     * rebinding protection), the plex.direct name never resolves and that is the only way in at home. It ranks after
     * the https ones, and like every address it must answer as this machine before a token goes to it (`trusted`).
     */
    private fun ranked(r: Resource): List<PlexAddress> {
        val all = mutableListOf<Pair<Int, PlexAddress>>()
        for (c in r.connections ?: emptyList()) {
            if (runCatching { URI(c.uri) }.isSuccess) all.add(priority(c) * 1000 + all.size to PlexAddress(url = c.uri, local = c.local))
            val a = c.address; val p = c.port
            if (c.local && !c.relay && r.httpsRequired != true && a != null && p != null && runCatching { URI("http://$a:$p") }.isSuccess) {
                all.add(1 * 1000 + all.size to PlexAddress(url = "http://$a:$p", local = true))   // priority of a local http connection
            }
        }
        val seen = HashSet<String>()
        return all.sortedBy { it.first }.map { it.second }.filter { seen.add(it.url) }
    }

    /**
     * The ones of `ranked` worth probing from here. Phones and tablets take a plain-http one (plex.tv's or the LAN one
     * above) only when plex.tv says they are on the server's network: elsewhere (a café, a hotel) whoever answers there
     * can pose as the server and read the token in the clear. The whole list is still saved, so
     * `AppModel.pickPlexAddress`, which filters the same way, finds the LAN one again at home. A TV doesn't move networks.
     */
    private fun trusted(ranked: List<PlexAddress>, r: Resource): List<PlexAddress> =
        if (!ClientDevice.device.startsWith("Android") || r.publicAddressMatches == true) ranked
        else ranked.filter { !it.url.startsWith("http://") }

    /**
     * Why `server(at)` turned an address down: nothing Plex answered there, it's not a server on this account, or it is
     * but none of the addresses plex.tv lists for it answered.
     */
    sealed class AddressError : Exception() {
        class noServer : AddressError(); class notOnAccount : AddressError(); class unreachable : AddressError()
    }

    /**
     * The account's server that `at` (an address the viewer typed or picked from a LAN reply) says it is. The address
     * only names the server: its /identity, asked without a token, gives the machine; plex.tv must list that machine on
     * this account; and the link then goes to whichever address plex.tv lists for it answers as that machine (on the LAN,
     * usually the plex.direct one, whose certificate is the server's own). No token is sent to the typed host unless
     * plex.tv lists it, so a typo, or a LAN device claiming to be the server, gets nothing.
     */
    suspend fun server(at: String, token: String, clientID: String): PlexServer {
        val probe = plexRequest(plexURL(at, "/identity"), token = null, clientID = clientID, timeout = 8.0)
        val id = runCatchingNonCancel {
            HTTP.json(probe) { (it as JSONObject).getJSONObject("MediaContainer").stringOrNull("machineIdentifier") }
        } ?: throw AddressError.noServer()
        val r = resources(token, clientID).firstOrNull { it.clientIdentifier == id && it.provides.split(",").contains("server") }
            ?: throw AddressError.notOnAccount()
        val serverToken = r.accessToken ?: throw AddressError.notOnAccount()   // never the account token
        val known = ranked(r)
        val best = bestAddress(trusted(known, r), machineID = id, clientID = clientID, waitForLAN = true) ?: throw AddressError.unreachable()
        return PlexServer(id = id, name = r.name, baseURL = best, accessToken = serverToken, addresses = known)
    }

    /** Every server on the account that answers, or with `only`, just that machineIdentifier (finding a moved server). */
    suspend fun servers(token: String, clientID: String, only: String? = null): List<PlexServer> {
        val servers = mutableListOf<PlexServer>()
        for (r in resources(token, clientID)) {
            if (!(r.provides.split(",").contains("server") && (only == null || r.clientIdentifier == only))) continue
            val serverToken = r.accessToken ?: continue   // the account token would give its owner the whole account
            // Probe LAN addresses even for shared servers: a family member's TV usually sits on the server's LAN, and
            // going out via the public address hairpins through the router (verified: /identity answers, big pages time out).
            // Only an address whose /identity reports this server's machineIdentifier counts (firstAnswering).
            val ranked = ranked(r)
            val uri = bestAddress(trusted(ranked, r), machineID = r.clientIdentifier, clientID = clientID, waitForLAN = true) ?: continue
            servers.add(PlexServer(id = r.clientIdentifier, name = r.name, baseURL = uri, accessToken = serverToken, addresses = ranked))
        }
        return servers
    }

    /**
     * The first of `ranked` that answers as `machineID` (all probed at once, GET /identity; 2 s for LAN, 4 s otherwise).
     * `waitForLAN`, phones / tablets only (Swift: everything but tvOS; here: `ClientDevice.device` is "Android …", not a TV's):
     * when only a non-LAN address answered, try the LAN ones twice more, 2 s apart (at most ~10 s more away from home).
     * The first LAN request can fail while the system asks for local-network permission, and settling for the public
     * address hairpins every request through the router (big library pages time out).
     */
    suspend fun bestAddress(ranked: List<PlexAddress>, machineID: String, clientID: String, waitForLAN: Boolean = false): String? {
        val found = firstAnswering(ranked, machineID, clientID)
        if (ClientDevice.device.startsWith("Android")) {
            val lan = ranked.filter { it.local }
            if (waitForLAN && lan.isNotEmpty() && lan.none { it.url == found }) {
                repeat(2) {
                    delay(2_000)
                    firstAnswering(lan, machineID, clientID)?.let { return it }
                }
            }
        }
        return found
    }

    /**
     * Probes every address at once; the best-ranked one whose /identity reports `machineID` wins (a stranger's LAN at
     * the same IP can't impersonate the server).
     */
    private suspend fun firstAnswering(ranked: List<PlexAddress>, machineID: String, clientID: String): String? = coroutineScope {
        val answers = AnswerChannel<Int?>(ranked.size)
        val probes = ranked.mapIndexed { i, a ->
            launch {
                val req = plexRequest(plexURL(a.url, "/identity"), token = null, clientID = clientID, timeout = if (a.local) 2.0 else 4.0)   // a LAN server answers in ms
                val id = runCatchingNonCancel { HTTP.json(req) { (it as JSONObject).getJSONObject("MediaContainer").stringOrNull("machineIdentifier") } }
                answers.send(if (id == machineID) i else null)
            }
        }
        var best: Int? = null
        for (n in ranked.indices) {
            val i = answers.receive() ?: continue
            if (i >= (best ?: Int.MAX_VALUE)) continue
            best = i
            if (i == 0) break   // nothing can beat the top candidate; stop waiting on the rest
        }
        probes.forEach { it.cancel() }
        best?.let { ranked[it].url }
    }

    private fun priority(c: Connection): Int =
        (if (c.local) 0 else if (c.relay) 2 else 1) * 2 + (if (c.protocol == "https") 0 else 1)
}

// MARK: - Plex Media Server

class PlexClient(
    val baseURL: String,
    val token: String,
    val clientID: String,
    override val serverName: String = "Plex",
    /** Streaming cap in kbit/s, 0 = original. Applied to transcode/direct-stream requests. */
    val maxBitrateKbps: Int = 0,
) : MediaServer {
    override val kind: ServerKind = ServerKind.plex

    // Plex omits any field it has no value for, so everything but keys/titles is optional.
    private class Container<T>(val totalSize: Int?, val Directory: List<T>?, val Metadata: List<T>?)
    private fun <T> container(o: Any, item: (JSONObject) -> T): Container<T> {
        val c = (o as JSONObject).getJSONObject("MediaContainer")
        return Container(c.intOrNull("totalSize"), c.objectsOrNull("Directory")?.map(item), c.objectsOrNull("Metadata")?.map(item))
    }
    private class Directory(val key: String, val type: String, val title: String?)
    private fun directory(o: JSONObject) = Directory(o.getString("key"), o.getString("type"), o.stringOrNull("title"))
    private class Metadata(o: JSONObject) {
        val ratingKey: String = o.getString("ratingKey")
        val title: String? = o.stringOrNull("title")
        /** A film's cut ("Directors Cut"), from the file name's {edition-…}. */
        val editionTitle: String? = o.stringOrNull("editionTitle")
        val year: Int? = o.intOrNull("year")
        /** Unix seconds. */
        val addedAt: Long? = o.longOrNull("addedAt")
        /** Milliseconds. */
        val duration: Long? = o.longOrNull("duration")
        /** Times this account watched it (or marked it watched); absent = never. Stopping partway only sets viewOffset. */
        val viewCount: Int? = o.intOrNull("viewCount")
        val thumb: String? = o.stringOrNull("thumb")
        val art: String? = o.stringOrNull("art")
        val summary: String? = o.stringOrNull("summary")
        val contentRating: String? = o.stringOrNull("contentRating")
        /** Extras only: "trailer", "behindTheScenes", … */
        val subtype: String? = o.stringOrNull("subtype")
        val index: Int? = o.intOrNull("index")
        val parentIndex: Int? = o.intOrNull("parentIndex")
        val grandparentTitle: String? = o.stringOrNull("grandparentTitle")
        val grandparentRatingKey: String? = o.stringOrNull("grandparentRatingKey")
        val grandparentThumb: String? = o.stringOrNull("grandparentThumb")
        val grandparentArt: String? = o.stringOrNull("grandparentArt")
        private fun tags(o: JSONObject, key: String) = o.objectsOrNull(key)?.map { it.getString("tag") }
        val Genre: List<String>? = tags(o, "Genre")
        val Director: List<String>? = tags(o, "Director")
        val Role: List<String>? = tags(o, "Role")
        val genres: List<String> get() = Genre ?: emptyList()
        val directors: List<String> get() = Director ?: emptyList()
        val actors: List<String> get() = (Role ?: emptyList()).take(6)
        val runtime: Double? get() = duration?.let { if (it > 0) it.toDouble() / 1000 else null }
        val watched: Boolean get() = (viewCount ?: 0) > 0
    }
    /** `/library/metadata/{key}` → Media[0].Part[0].Stream[]; streamType 1 video, 2 audio, 3 subtitle. */
    private class Stream(o: JSONObject) {
        val id: Int = o.getInt("id")
        val streamType: Int = o.getInt("streamType")
        val codec: String? = o.stringOrNull("codec")
        /** "English" / "eng" (ISO 639-2/B). */
        val language: String? = o.stringOrNull("language")
        val languageCode: String? = o.stringOrNull("languageCode")
        /** title is a custom name ("Commentary"); displayTitle is Plex's summary ("English (AAC Stereo)"). */
        val title: String? = o.stringOrNull("title")
        val displayTitle: String? = o.stringOrNull("displayTitle")
        val default: Boolean? = o.boolOrNull("default")
        val forced: Boolean? = o.boolOrNull("forced")
    }
    private class Part(val id: Int, val Stream: List<Stream>?)

    private fun request(path: String, query: List<Pair<String, String>> = emptyList()): URLRequest =
        plexRequest(plexURL(baseURL, path, query), token = token, clientID = clientID)

    private suspend fun sections(): List<Directory> =
        HTTP.json(request("/library/sections")) { container(it, ::directory) }.Directory ?: emptyList()

    override suspend fun libraries(): List<LibrarySection> {
        val dirs = sections()
        // Counts come from a zero-size page's totalSize, all sections at once; a failed count is just null.
        val counts = coroutineScope {
            dirs.map { d ->
                async {
                    // totalSize is only reported when paging via the container HEADERS (verified on PMS 1.43); the query form returns every item.
                    val req = request("/library/sections/${d.key}/all")
                    req.setValue("0", forHTTPHeaderField = "X-Plex-Container-Start")
                    req.setValue("0", forHTTPHeaderField = "X-Plex-Container-Size")
                    runCatchingNonCancel { HTTP.json(req) { container(it, ::Metadata).totalSize } }
                }
            }.awaitAll()
        }
        return dirs.mapIndexed { i, d ->
            LibrarySection(id = d.key, title = d.title ?: d.key,
                kind = if (d.type == "movie") LibrarySection.Kind.movies else if (d.type == "show") LibrarySection.Kind.shows else LibrarySection.Kind.other,
                itemCount = counts[i])
        }
    }

    /**
     * Sections load in parallel and join in section order, not finish order: shuffle channels keep library order, so a
     * race here would give every device (and every refresh) a different lineup.
     */
    override suspend fun libraryItems(sections: List<LibrarySection>?): List<MediaItem> = withContext(Dispatchers.Default) {   // mapping off main, as in Swift
        val dirs = directories(sections)
        coroutineScope { dirs.map { d -> async { items(d) } }.awaitAll().flatten() }
    }

    /**
     * The newest `limit` movies or episodes of each section, by date added: one small page per section (plus one request
     * for the episodes' shows), so the app can run it every time it opens.
     */
    override suspend fun recentlyAdded(sections: List<LibrarySection>?, limit: Int): List<MediaItem> = withContext(Dispatchers.Default) {
        val out = mutableListOf<MediaItem>()
        for (d in directories(sections)) {
            if (d.type == "movie") {
                out += (page(d.key, type = 1, start = 0, size = limit, sort = "addedAt:desc").Metadata ?: emptyList())
                    .mapNotNull { movie(it, d.key) }
                continue
            }
            val episodes = page(d.key, type = 4, start = 0, size = limit, sort = "addedAt:desc").Metadata ?: emptyList()
            val keys = episodes.mapNotNull { it.grandparentRatingKey }.toSortedSet().toList()
            val shows = if (keys.isEmpty()) emptyList()
                else HTTP.json(request("/library/metadata/${keys.joinToString(",")}")) { container(it, ::Metadata) }.Metadata ?: emptyList()
            val showByKey = LinkedHashMap<String, Metadata>()
            for (sh in shows) showByKey.putIfAbsent(sh.ratingKey, sh)
            out += episodes.mapNotNull { e -> episode(e, e.grandparentRatingKey?.let { showByKey[it] }, d.key) }
        }
        out
    }

    /** The movie and TV sections to load: the chosen ones, or every one the server has. */
    private suspend fun directories(sections: List<LibrarySection>?): List<Directory> =
        sections?.mapNotNull { s ->
            when (s.kind) {
                LibrarySection.Kind.movies -> Directory(key = s.id, type = "movie", title = s.title)
                LibrarySection.Kind.shows -> Directory(key = s.id, type = "show", title = s.title)
                LibrarySection.Kind.other -> null
            }
        } ?: this.sections().filter { it.type == "movie" || it.type == "show" }

    private suspend fun items(section: Directory): List<MediaItem> {
        if (section.type == "movie") return all(section.key, type = 1).mapNotNull { movie(it, section.key) }
        // Genres, cast, rating and the blurb live on the show (type=2), not the episode (type=4); join on grandparentRatingKey.
        return coroutineScope {
            val shows = async { all(section.key, type = 2) }
            val episodes = async { all(section.key, type = 4) }
            val showByKey = LinkedHashMap<String, Metadata>()
            for (s in shows.await()) showByKey.putIfAbsent(s.ratingKey, s)
            episodes.await().mapNotNull { e -> episode(e, e.grandparentRatingKey?.let { showByKey[it] }, section.key) }
        }
    }

    private fun movie(m: Metadata, section: String): MediaItem? {
        val runtime = m.runtime ?: return null
        val added = m.addedAt?.let { instantOf(it.toDouble()) }
        return MediaItem(id = m.ratingKey, kind = MediaItem.Kind.movie, title = MediaItem.title(m.title ?: "Untitled", m.editionTitle), year = m.year, genres = m.genres,
                         runtime = runtime, artworkPath = m.thumb, backdropPath = m.art, overview = m.summary,
                         contentRating = m.contentRating, directors = m.directors, actors = m.actors,
                         libraryID = section, addedAt = added, watched = m.watched)
    }

    private fun episode(e: Metadata, show: Metadata?, section: String): MediaItem? {
        val runtime = e.runtime ?: return null
        val added = e.addedAt?.let { instantOf(it.toDouble()) }
        return MediaItem(id = e.ratingKey, kind = MediaItem.Kind.episode, title = e.title ?: "Untitled", seriesTitle = e.grandparentTitle,
                         seriesID = e.grandparentRatingKey, season = e.parentIndex, episode = e.index, year = e.year,
                         genres = show?.genres ?: emptyList(), runtime = runtime, artworkPath = e.grandparentThumb ?: e.thumb,
                         backdropPath = e.grandparentArt ?: e.art, overview = e.summary,
                         contentRating = show?.contentRating, directors = show?.directors ?: emptyList(),
                         actors = show?.actors ?: emptyList(), libraryID = section, addedAt = added, watched = e.watched)
    }

    /**
     * `/library/sections/{key}/all?type=` 500 at a time: page 0 learns `totalSize`, the rest load 4 at a time, joined in page order.
     * `excludeElements=Media` drops the Media/Part/Stream subtree (the bulk of an episode's JSON); everything `Metadata`
     * decodes, `duration` included, is an attribute or tag of the item itself.
     */
    private suspend fun all(key: String, type: Int): List<Metadata> {
        val first = page(key, type, start = 0)
        val out = (first.Metadata ?: emptyList()).toMutableList()
        // ponytail: a server's totalSize is capped at 2M items so a bad one can't allocate pages without end; raise it if a library is bigger.
        val starts = (500 until minOf(first.totalSize ?: 0, 2_000_000) step 500).toList()
        if (out.isEmpty() || starts.isEmpty()) return out
        // ponytail: 4 in flight per call; movies, shows and episodes fetch in parallel, so the per-host connection pool is the real ceiling.
        val inFlight = Semaphore(4)
        val pages = coroutineScope {
            starts.map { s -> async { inFlight.withPermit { page(key, type, start = s).Metadata ?: emptyList() } } }.awaitAll()
        }
        for (p in pages) out += p
        return out
    }

    private suspend fun page(key: String, type: Int, start: Int, size: Int = 500, sort: String? = null): Container<Metadata> {
        val query = mutableListOf("type" to "$type", "excludeElements" to "Media")
        if (sort != null) query += "sort" to sort
        val req = request("/library/sections/$key/all", query)
        req.setValue("$start", forHTTPHeaderField = "X-Plex-Container-Start")
        req.setValue("$size", forHTTPHeaderField = "X-Plex-Container-Size")
        return HTTP.json(req) { container(it, ::Metadata) }
    }

    /** First part's id and streams of `/library/metadata/{key}`; throws when the item has no playable part. */
    private suspend fun streams(key: String): Part {
        val part = HTTP.json(request("/library/metadata/$key")) { o ->
            container(o) { m ->
                m.objectsOrNull("Media")?.firstOrNull()?.objectsOrNull("Part")?.firstOrNull()
                    ?.let { p -> Part(p.getInt("id"), p.objectsOrNull("Stream")?.map(::Stream)) }
            }.Metadata?.firstOrNull()
        }
        return part ?: throw MediaServerError.notFound("media part for $key")
    }

    override suspend fun tracks(item: MediaItem): List<MediaTrack> =
        (streams(item.id).Stream ?: emptyList()).mapNotNull { s ->
            val kind = when (s.streamType) { 2 -> MediaTrack.Kind.audio; 3 -> MediaTrack.Kind.subtitle; else -> return@mapNotNull null }
            var title = s.displayTitle ?: s.title ?: s.language ?: s.codec ?: "Track ${s.id}"
            val t = s.title
            val d = s.displayTitle
            // "Commentary · English (AAC Stereo)", but the title alone once it names the summary or the language ("English
            // (SDH)" over "English", "Spanish (Castilian)" over "Español"), and the summary alone if it holds the title.
            if (!t.isNullOrEmpty() && !d.isNullOrEmpty()) {
                val names = listOfNotNull(d, MediaTrack.languageName(s.languageCode))
                title = if (names.any { t.contains(it, ignoreCase = true) }) t else if (d.contains(t, ignoreCase = true)) d else "$t · $d"
            }
            MediaTrack(id = s.id.toString(), kind = kind, language = s.languageCode, title = title,
                       isDefault = s.default ?: false, isForced = s.forced ?: false)
        }

    override suspend fun playbackSource(item: MediaItem, startingAt: Double, session: String, tracks: TrackSelection): PlaybackSource {
        // Server defaults (guide preview, trailers): no metadata round trip.
        if (!tracks.isDefault) {
            // The transcoder follows the part's *selected* streams, and the only way to pick them is the PUT every official
            // client makes (python-plexapi setSelectedAudioStream/setSelectedSubtitleStream). Side effect: it changes the
            // part's default audio/subtitle for this account server-wide, exactly as choosing a track in Plex Web does.
            // subtitleStreamID=0 clears the subtitle. A failed PUT just means server defaults; playback still starts.
            val partID = streams(item.id).id
            val query = mutableListOf("allParts" to "1")
            tracks.audioID?.let { query.add("audioStreamID" to it) }
            when (val s = tracks.subtitle) {
                is TrackSelection.Subtitle.track -> query.add("subtitleStreamID" to s.id)
                TrackSelection.Subtitle.off -> query.add("subtitleStreamID" to "0")
                TrackSelection.Subtitle.auto -> {}
            }
            val put = request("/library/parts/$partID", query)
            put.httpMethod = "PUT"
            try { HTTP.data(put) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                println("PlexClient.playbackSource: select streams on part $partID: $e")
            }
        }
        return transcodeSource(ratingKey = item.id, offset = startingAt, session = session, subtitle = tracks.subtitle)
    }

    /** Best-effort `/video/:/transcode/universal/stop` for this device's `session`; PMS prunes stragglers itself. */
    override suspend fun stopPlayback(session: String) {
        runCatchingNonCancel { HTTP.data(request("/video/:/transcode/universal/stop", listOf("session" to "$clientID-$session"))) }
    }

    /** Plex's own "mark as played": PUT /:/scrobble, keyed by the item's ratingKey. */
    override suspend fun markWatched(item: MediaItem) {
        val put = request("/:/scrobble", listOf("identifier" to "com.plexapp.plugins.library", "key" to item.id))
        put.httpMethod = "PUT"
        HTTP.data(put)
    }

    /**
     * One request to `/library/metadata/{key}/extras`; prefers a trailer, falls back to any extra, null when there are none.
     * Episodes have no extras of their own, so look at the show.
     */
    override suspend fun trailer(item: MediaItem, session: String): PlaybackSource? {
        val key = if (item.kind == MediaItem.Kind.episode) item.seriesID ?: item.id else item.id
        val extras = HTTP.json(request("/library/metadata/$key/extras")) { container(it, ::Metadata) }.Metadata ?: emptyList()
        val extra = extras.firstOrNull { it.subtype == "trailer" } ?: extras.firstOrNull() ?: return null
        return transcodeSource(ratingKey = extra.ratingKey, offset = 0.0, session = session)
    }

    /**
     * Universal-transcoder HLS start for any rating key (items and extras alike); the server starts at `offset`.
     * The session id is stable per device + purpose, so a reload replaces the old transcode instead of adding one.
     * Subtitles: `track` → `subtitles=burn` (the HLS transcoder burns the part's selected text/PGS sub into the picture, so
     * `subtitleInPlaylist` is always false); `off` → `skipSubtitles=1`; `auto` → nothing. Both values are what
     * plex-for-kodi's buildTranscodeHls/buildTranscodeMkv send; `subtitles=none` is not seen anywhere, so it isn't used.
     */
    private suspend fun transcodeSource(ratingKey: String, offset: Double, session: String,
                                subtitle: TrackSelection.Subtitle = TrackSelection.Subtitle.auto): PlaybackSource {
        // fMP4 HLS (container=mp4) with hevc in the target, so HEVC sources direct-stream instead of transcoding; HEVC over
        // HLS needs fMP4 per Apple's spec. Profile-Extra grammar as in Plex's own clients.
        // The target only counts under X-Plex-Platform "Generic" (measured on PMS 1.43.5, 2026-10-26): "tvOS", "iOS" and
        // "Android" pick built-in profiles that answer MPEG-TS and re-encode HEVC to H.264 (iOS/Android also E-AC3 to AAC)
        // whatever the target says, and "macOS" is a 400. "Generic" copies HEVC (hvc1 init) and E-AC3 into fMP4 and
        // transcodes only codecs the target leaves out. So transcodes say "Generic"; every other request keeps the real platform.
        var profile = "add-transcode-target(type=videoProfile&context=streaming&protocol=hls&container=mp4" +
            "&videoCodec=${ClientDevice.videoCodecs}&audioCodec=${ClientDevice.audioCodecs})"
        // Copied HDR10 / Dolby Vision 8 HEVC is a lone VIDEO-RANGE=PQ variant, which AVPlayer refuses on a screen that can't
        // show HDR ("Cannot open", CoreMedia -17223; SDR TVs, the simulator) and ExoPlayer shows washed out. There Plex must
        // tone-map: HEVC whose transfer is tagged anything but SDR transcodes to BT.709 (measured: a bare notMatch on PQ
        // transcodes but stays PQ), while untagged files, most SDR rips, still copy.
        if (!ClientDevice.hdr) {
            profile += "+add-limitation(scope=videoCodec&scopeName=hevc&type=match&name=video.colorTrc&list=bt709|smpte170m|bt470bg&isRequired=false)"
        }
        // The guide preview is a muted ~800 px window: cap it at 720p / 4 Mbps (or lower if Settings says so) so browsing the
        // guide never spins up a full-rate transcode. Otherwise the Settings cap, 0 = original. directStream=1 stays, so only
        // sources above the cap transcode. ponytail: an h264 source above the cap transcodes rather than direct-streams, as intended.
        var cap = maxBitrateKbps
        if (session == "preview") cap = if (cap > 0) minOf(cap, 4000) else 4000
        val identity = plexIdentity()
        val query = mutableListOf(
            "path" to "/library/metadata/$ratingKey", "mediaIndex" to "0", "partIndex" to "0",
            "protocol" to "hls", "directPlay" to "0", "directStream" to "1", "fastSeek" to "1",
            "offset" to "${offset.toLong()}", "session" to "$clientID-$session",
            "X-Plex-Client-Identifier" to clientID, "X-Plex-Token" to token,
            "X-Plex-Platform" to "Generic", "X-Plex-Product" to identity.getValue("X-Plex-Product"),
            "X-Plex-Version" to identity.getValue("X-Plex-Version"), "X-Plex-Client-Profile-Extra" to profile,
        )
        if (cap > 0) {
            query += listOf("maxVideoBitrate" to "$cap", "videoResolution" to if (cap <= 4000) "1280x720" else "1920x1080")
        }
        when (subtitle) {
            is TrackSelection.Subtitle.track -> query.add("subtitles" to "burn")
            TrackSelection.Subtitle.off -> query.add("skipSubtitles" to "1")
            TrackSelection.Subtitle.auto -> {}
        }
        // PMS 1.43 can answer start.m3u8 with 400 unless this client first asked for a decision on the same item, as every
        // official client does: a best-effort GET of the same query on /decision. If it fails, start still gets its try.
        runCatchingNonCancel { HTTP.data(URLRequest(plexURL(baseURL, "/video/:/transcode/universal/decision", query), timeoutInterval = 10.0)) }
        return PlaybackSource(url = plexURL(baseURL, "/video/:/transcode/universal/start.m3u8", query),
                              startsAtOffset = true, subtitleInPlaylist = false)
    }

    override fun artworkURL(path: String?): String? {
        if (path == null) return null
        return plexURL(baseURL, "/photo/:/transcode", listOf(
            "width" to "400", "height" to "600", "minSize" to "1", "url" to path, "X-Plex-Token" to token,
        ))
    }
}
