package com.rerunguide.app

import com.rerunguide.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URL

/* First launch: welcome → (address) → link/login → libraries → building (port of the design prototype's k_welcome…k_building, startLink, startBuild),
 * wired to the real servers: Plex PIN / plex.tv password, Jellyfin Quick Connect / password, Emby PIN (emby.media/pin) /
 * password, demo library.
 * The PIN / Quick Connect poll loop is `AppModel.linkTask` (the Swift `static var linkTask`). */

/** Focus index of the Continue button on the address screen (input, found servers…, Continue). */
private val AppModel.continueIndex: Int get() = foundServers.size + 1
/** Plex 4-char / Emby 5-digit / Jellyfin 6-digit code, as boxes. */
val AppModel.linkCodeChars: List<String>
    get() = (linkCode ?: " ".repeat(when (serviceKind) { ServerKind.plex -> 4; ServerKind.emby -> 5; else -> 6 })).map { it.toString() }
/** Plex and Emby link an account (plex.tv/link, emby.media/pin) and then pick one of its servers; Jellyfin's Quick
 *  Connect signs in to the one server whose address was typed. */
private val AppModel.linksAccount: Boolean get() = serviceKind != ServerKind.jellyfin
val AppModel.buildDone: Boolean get() = !libraryLoading && libraryError == null && builtCount >= channels.size

/** `URL(string:)?.host`: null when the address doesn't parse or names no host. */
private fun hostOf(address: String): String? = runCatching { URL(address).host }.getOrNull()?.takeIf { it.isNotEmpty() }
/** Swift's `error.localizedDescription`: the transport failures read as URLError's text, not the JVM exception's
 *  ("Failed to connect to /192.168.1.20:8096"); MediaServerError's message is already its errorDescription. */
internal fun errorText(e: Throwable): String = when (e) {
    is java.net.ConnectException, is java.net.NoRouteToHostException -> "Could not connect to the server."
    is java.net.UnknownHostException -> "A server with the specified hostname could not be found."
    is java.net.SocketTimeoutException -> "The request timed out."
    is java.net.SocketException -> "The network connection was lost."
    is javax.net.ssl.SSLException -> "An SSL error has occurred and a secure connection to the server cannot be made."
    is org.json.JSONException -> "The data couldn’t be read because it isn’t in the correct format."
    else -> e.localizedMessage ?: "The operation couldn’t be completed."
}

// MARK: Welcome

/** The Welcome cards, name and line: Plex, Jellyfin, Emby, then the sample library. The screen and TalkBack read these. */
val AppModel.Companion.welcomeCards: List<Pair<String, String>>
    get() = listOf(
        "Plex" to "Link this ${Platform.device} to your Plex account",
        "Jellyfin" to "Point it at your Jellyfin server",
        "Emby" to "Link this ${Platform.device} to your Emby account",
        "Sample station" to "Take a look with a made-up library",
    )

fun AppModel.pressWelcome(cmd: RemoteCommand) {
    // Plex / Jellyfin / Emby are one row (k_welcome's step(a, 3, true)); the demo card sits above it, over Emby.
    if (cmd == RemoteCommand.up && focusIndex < 3) { focusIndex = 3; return }
    if (cmd == RemoteCommand.down && focusIndex == 3) { focusIndex = 2; return }
    if (focusIndex < 3 && step(cmd, count = 3, horizontal = true)) return
    if (cmd != RemoteCommand.select) return
    authError = null
    foundServers = emptyList(); pendingToken = null   // Plex PIN results never leak onto another service's address screen
    val picked = listOf(ServerKind.plex, ServerKind.jellyfin, ServerKind.emby, ServerKind.demo)[minOf(focusIndex, 3)]
    if (picked != serviceKind) address = ""   // a Plex address (port 32400) must not pre-fill the Emby/Jellyfin form
    when (focusIndex) {
        0 -> { serviceKind = ServerKind.plex; go(Screen.link) }
        1 -> { serviceKind = ServerKind.jellyfin; go(Screen.address) }
        2 -> { serviceKind = ServerKind.emby; go(Screen.link) }
        else -> {
            connect(DemoMediaServer(), ServerConfig(ServerKind.demo, "demo://local", "Demo library"), "")
            go(Screen.libraries)
        }
    }
}

// MARK: Address

fun AppModel.pressAddress(cmd: RemoteCommand) {
    if (step(cmd, count = continueIndex + 1)) return
    if (cmd == RemoteCommand.back) {
        go(Screen.welcome)
        focusIndex = listOf(ServerKind.plex, ServerKind.jellyfin, ServerKind.emby).indexOf(serviceKind).let { if (it < 0) 0 else it }
        return
    }
    if (cmd != RemoteCommand.select) return
    val i = focusIndex
    if (i == 0) editingField = "addr"
    else if (i < continueIndex) {
        val s = foundServers[i - 1]
        // A server from the Plex or Emby account carries its access token, so Select connects. One found on the network still needs sign-in.
        if (serviceKind == ServerKind.plex && s.accessToken.isNotEmpty()) { connectPlex(s); return }
        val user = pendingToken
        if (serviceKind == ServerKind.emby && s.accessToken.isNotEmpty() && user != null) {
            address = s.baseURL   // the check mark follows the pick while it signs in
            connectEmby(s, user); return
        }
        address = s.baseURL; focusIndex = continueIndex
    }
    else continueFromAddress()
}

private fun AppModel.continueFromAddress() {
    address = AppModel.normalized(address)
    val host = hostOf(address)
    if (host == null) { authError = "Enter an address like 192.168.1.20:32400"; return }
    authError = null
    when (serviceKind) {
        ServerKind.plex -> {
            // A GDM-found server carries no token; it goes through the pendingToken / password path like a typed address.
            foundServers.firstOrNull { it.baseURL == address && it.accessToken.isNotEmpty() }?.let { connectPlex(it); return }
            val token = pendingToken ?: run { go(Screen.login); return }
            if (authBusy) return
            authBusy = true; authSerial += 1
            val url = address; val serial = authSerial
            scope.launch {
                try { plexServer(url, token, serial)?.let { connectPlex(it, account = token) } }
                finally { if (authSerial == serial) authBusy = false }
            }
        }
        ServerKind.jellyfin -> go(Screen.link)
        ServerKind.emby -> {
            val s = foundServers.firstOrNull { it.baseURL == address && it.accessToken.isNotEmpty() }
            val user = pendingToken
            if (s != null && user != null) connectEmby(s, user) else go(Screen.login)
        }
        else -> go(Screen.login)
    }
}

/** UDP-broadcast for servers of `serviceKind` while the address screen has nothing to list (go(Screen.address) calls this). */
fun AppModel.startDiscovery() {
    discovering = true
    scope.launch {
        val found = LocalDiscovery.find(serviceKind)
        discovering = false
        if (screen != Screen.address || foundServers.isNotEmpty()) return@launch
        foundServers = found
        if (focusIndex > 0) focusIndex = continueIndex   // already on Continue → stay on Continue as rows appear
    }
}

/** Trim, prepend http:// when there is no scheme, drop a trailing slash. */
fun AppModel.Companion.normalized(s: String): String {
    var a = s.trim()
    if (a.isNotEmpty() && !a.contains("://")) a = "http://$a"
    while (a.endsWith("/")) a = a.dropLast(1)
    return a
}

/**
 * The account's server a typed (or LAN-spotted) Plex address names, or null with `authError` set. No token goes to the
 * address itself: `PlexAuth.server(at)` finds the server on the account and links to an address plex.tv lists. A result
 * that lands after the viewer moved on (`authSerial`, or another service or sign-in) is dropped.
 */
private suspend fun AppModel.plexServer(url: String, account: String, serial: Int): PlexServer? {
    fun current() = authSerial == serial && serviceKind == ServerKind.plex && pendingToken == account
    return try {
        PlexAuth.server(url, account, AppModel.deviceID).takeIf { current() }
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) {
        if (!current()) return null
        val host = hostOf(url) ?: "that address"
        authError = when (e) {
            is PlexAuth.AddressError.noServer -> "No Plex server answered at $host. Check the address."
            is PlexAuth.AddressError.notOnAccount -> "$host is a Plex server, but not one on this Plex account."
            is PlexAuth.AddressError.unreachable ->
                "That’s your server, but no address Plex lists for it answered. On a VPN or proxy? Add it to the server’s Custom server access URLs."
            is MediaServerError.unauthorized -> { pendingToken = null; "Plex didn’t accept this sign-in. Sign in again." }
            else -> errorText(e)
        }
        null
    }
}

/** `account`: the plex.tv account token, kept so a server that moves (new LAN or public address) can be found again. */
private fun AppModel.connectPlex(s: PlexServer, account: String? = null) {
    (account ?: pendingToken)?.let { Keychain.set(it, "plexAccount") }
    connect(PlexClient(s.baseURL, s.accessToken, clientID = AppModel.deviceID, serverName = s.name),
        ServerConfig(ServerKind.plex, s.baseURL, s.name, addresses = s.addresses.ifEmpty { null }, machineID = s.id), s.accessToken)
    go(Screen.libraries)
}

/** An Emby Connect server (`EmbyConnect.servers`): trade its access key for a sign-in, then pick libraries. */
private fun AppModel.connectEmby(s: PlexServer, connectUserID: String) {
    if (authBusy) return
    authBusy = true; authError = null
    scope.launch {
        try {
            val c = JellyfinClient.connectExchange(s, connectUserID, AppModel.deviceID)
            if (screen != Screen.address && screen != Screen.link) return@launch
            connect(c, ServerConfig(ServerKind.emby, c.baseURL, c.serverName, userID = c.userID), c.token)
            go(Screen.libraries)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (screen != Screen.address && screen != Screen.link) return@launch
            authError = "Couldn’t sign in to ${s.name}: ${errorText(e)}"
            if (screen == Screen.link) linked = false
        } finally {
            authBusy = false
        }
    }
}

// MARK: Link (Plex PIN / Emby PIN / Jellyfin Quick Connect)

fun AppModel.startLink() {
    linkTask?.cancel()
    linked = false; linkCode = null; authError = null
    linkTask = scope.launch { runLink() }
}

private suspend fun AppModel.runLink() {
    val id = AppModel.deviceID
    try {
        if (serviceKind == ServerKind.plex) {
            val pin = PlexAuth.requestPIN(id)
            linkCode = pin.code
            var token: String? = null
            while (token == null) {
                delay(3000)
                token = PlexAuth.pollPIN(pin, id)
            }
            linked = true
            val servers = PlexAuth.servers(token, id)
            currentCoroutineContext().ensureActive()
            when (servers.size) {
                0 -> { linked = false; authError = "No Plex Media Server is linked to this account." }
                1 -> connectPlex(servers[0], account = token)
                else -> { foundServers = servers; pendingToken = token; address = servers[0].baseURL; go(Screen.address); focusIndex = 1 }   // land on the first server, not the address field
            }
        } else if (serviceKind == ServerKind.emby) {
            var account: EmbyConnect.Account? = null
            var pins = 0
            while (account == null) {   // an expired PIN is replaced by a fresh one; the viewer just reads the new code
                pins += 1
                if (pins > 10) { authError = "The code expired. Go back and try again."; return }
                val pin = EmbyConnect.requestPIN(id)
                linkCode = pin.code
                try {
                    while (account == null) {
                        delay(3000)
                        account = EmbyConnect.pollPIN(pin)
                    }
                } catch (e: MediaServerError.notFound) { continue }
            }
            linked = true
            val servers = EmbyConnect.servers(account)
            currentCoroutineContext().ensureActive()
            when (servers.size) {
                0 -> { linked = false; authError = "No server is linked to that Emby Connect account. In your server’s dashboard, add your Emby Connect email to a user, then try again." }
                1 -> connectEmby(servers[0], account.userID)
                else -> { foundServers = servers; pendingToken = account.userID; address = servers[0].baseURL; go(Screen.address); focusIndex = 1 }
            }
        } else {
            val url = address
            if (hostOf(url) == null) throw MediaServerError.invalidResponse("bad server address")
            val qc = JellyfinClient.quickConnectStart(url, id)
            linkCode = qc.code
            while (!JellyfinClient.quickConnectIsApproved(url, qc.secret, id)) delay(3000)
            linked = true
            val client = JellyfinClient.loginWithQuickConnect(url, qc.secret, id)
            currentCoroutineContext().ensureActive()
            connect(client, ServerConfig(ServerKind.jellyfin, client.baseURL, client.serverName, userID = client.userID), client.token)
            go(Screen.libraries)
        }
    } catch (e: Exception) {
        if (e is CancellationException || !currentCoroutineContext().isActive || screen != Screen.link) return
        authError = errorText(e)
    }
}

fun AppModel.pressLink(cmd: RemoteCommand) {
    if (cmd != RemoteCommand.back && cmd != RemoteCommand.select) return
    linkTask?.cancel()
    if (cmd == RemoteCommand.back) {
        go(if (linksAccount) Screen.welcome else Screen.address)
        focusIndex = if (linksAccount) (if (serviceKind == ServerKind.plex) 0 else 2) else continueIndex
    }
    else go(if (linksAccount) Screen.address else Screen.login)
}

// MARK: Login (plex.tv / Jellyfin / Emby password)

fun AppModel.pressLogin(cmd: RemoteCommand) {
    if (step(cmd, count = 3)) return
    if (cmd == RemoteCommand.back) { go(Screen.address); focusIndex = continueIndex; return }
    if (cmd != RemoteCommand.select || authBusy) return
    when (focusIndex) {
        0 -> editingField = "user"
        1 -> editingField = "pass"
        else -> signIn()
    }
}

private fun AppModel.signIn() {
    authBusy = true; authError = null; authSerial += 1
    val serial = authSerial; val kind = serviceKind
    scope.launch {
        try {
            if (kind == ServerKind.plex) {
                val token = try { PlexAuth.signIn(username, password, AppModel.deviceID) }
                catch (e: MediaServerError.unauthorized) {
                    // plex.tv answers 401 both for bad credentials and for a missing 2FA code.
                    if (authSerial != serial) return@launch
                    authError = "Wrong email or password. Two-factor on? Type your password followed by the 6-digit code."; return@launch
                }
                if (authSerial != serial) return@launch   // moved on while plex.tv answered
                pendingToken = token
                if (hostOf(address) == null) { go(Screen.address); return@launch }
                plexServer(address, token, serial)?.let { connectPlex(it, account = token) }
            } else {
                if (hostOf(address) == null) throw MediaServerError.invalidResponse("bad server address")
                val c = JellyfinClient.login(address, username, password, AppModel.deviceID, kind)
                if (authSerial != serial) return@launch   // moved on while the server answered
                connect(c, ServerConfig(kind, c.baseURL, c.serverName, userID = c.userID), c.token)
                go(Screen.libraries)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (authSerial == serial) authError = errorText(e) }
        finally { if (authSerial == serial) authBusy = false }
    }
}

// MARK: Libraries

/** Fetch the server's libraries into `librarySections`; the saved choice is preselected, else movies/shows default to
 *  On (a re-pick from Settings with nothing saved means "every library"). The view calls this on appear
 *  (`LaunchedEffect(Unit) { model.fetchLibraries() }`). */
/** What the Libraries rows show for `sections` before the viewer changes any: the saved choice, else movies and shows
 *  (every library on a Settings re-pick, where nothing saved means every library). */
private fun AppModel.libraryPicks(sections: List<LibrarySection>): Set<String> = persisted.selectedLibraryIDs?.toSet()
    ?: sections.filter { librariesOrigin == Screen.settings || it.kind != LibrarySection.Kind.other }.map { it.id }.toSet()

suspend fun AppModel.fetchLibraries() {
    authBusy = true; authError = null
    // The rows show what Continue builds until the list lands (a failed load keeps the saved choice; nothing saved: all).
    librarySelection = libraryPicks(librarySections)
    val serial = authSerial   // a refetch can run outside the screen's own effect: what lands after the viewer left is dropped
    try {   // finally: leaving the screen cancels this, and a stuck authBusy would block sign-in
        // The LAN address may answer now that the device is on the server's network. The pick outlives a Back: it may be
        // the one that revives a plain-http link's client (and runs the launch's held loads).
        if (!Platform.isTV) scope.launch { pickPlexAddress() }.join()
        if (authSerial != serial) return
        // Dropped (away during the pick, or no address checks out here): say so, not "No libraries reported".
        val server = server ?: run { authError = "Could not connect to the server."; return }
        val sections = server.libraries().filter { it.itemCount != 0 }   // empty (Emby's Collections, Playlists): nothing to schedule; unknown counts stay
        if (authSerial != serial) return
        librarySections = sections
        librarySelection = libraryPicks(sections)
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { if (authSerial == serial) authError = errorText(e) }
    finally { if (authSerial == serial) authBusy = false }
}

/** Back: Settings re-pick → Settings (saved choice untouched); a Plex or Emby account link with other servers still in memory
 *  → the server list (no new PIN); otherwise Welcome is the signed-out state, as in the prototype's k_libraries. */
fun AppModel.pressLibraries(cmd: RemoteCommand) {
    val n = librarySections.size
    if (step(cmd, count = n + 1)) return
    if (cmd == RemoteCommand.back) {
        if (librariesOrigin == Screen.settings) { librariesOrigin = null; go(Screen.settings) }
        else if (linksAccount && pendingToken != null && foundServers.isNotEmpty()) {   // back to the account's server list
            val token = pendingToken; val rows = foundServers
            signOut(); pendingToken = token; foundServers = rows; go(Screen.address); focusIndex = continueIndex
        }
        else signOut()
        return
    }
    // Its list and picks aren't in yet: nothing to save. No client (dropped, or unchecked on this network): Select checks
    // again (the spinner, then the list or the line). A list that failed to load keeps the saved choice: Continue builds
    // that, rows don't toggle.
    if (cmd != RemoteCommand.select || authBusy) return
    if (server == null) {
        // Its list never landed: fetch it again (busy at once: one fetch). A landed list lacks only the client (dropped on
        // the way out, or by a network change): check the address, busy meanwhile, keeping the list and its unsaved picks.
        if (authError != null) scope.launch(start = CoroutineStart.UNDISPATCHED) { fetchLibraries() }
        else {
            authBusy = true; val serial = authSerial
            scope.launch {
                val mine = pickGen + 1   // pickPlexAddress bumps it before its first suspension
                try { pickPlexAddress() } finally { if (authSerial == serial) authBusy = false }
                // Nothing checks out here: say so, rows showing what Continue builds. Not when a newer pick took over or the
                // app left meanwhile (refused): that pick, or the way back's, decides.
                if (authSerial == serial && server == null && pickGen <= mine && !backgrounded) {
                    librarySelection = libraryPicks(librarySections); authError = "Could not connect to the server."
                }
            }
        }
        return
    }
    if (authError != null) { if (focusIndex >= n) go(Screen.building); return }
    if (focusIndex < n) {
        val id = librarySections[focusIndex].id
        librarySelection = if (id in librarySelection) librarySelection - id else librarySelection + id
    } else {
        persisted = persisted.copy(selectedLibraryIDs = if (librarySelection.size == n) null else librarySelection.toList()); save()
        go(Screen.building)
    }
}

// MARK: Building

/** Kept in `loadTask` so Back can cancel the server round trips. An empty result is not an error: the view reads
 *  `buildDone && channels.isEmpty()` and offers "Pick other libraries"; `libraryError` means the load threw (Retry). */
fun AppModel.startBuild() {
    builtCount = 0; libraryError = null; libraryLoading = true
    loadTask?.cancel()
    loadTask = scope.launch {
        try { loadLibrary(force = true) }   // a changed selection must be fetched, however fresh the cache is
        catch (e: CancellationException) { libraryError = null; throw e }   // Back: the cancelled request is not a server error
        if (screen != Screen.building) return@launch
        reveal()
    }
}

/** One tile every 170 ms until every channel is on the board (the screen animates each step in, easeOut 0.3 s).
 *  Paced by the clock from the first step, not by counting ticks: a slow device whose frames stretch each `delay`
 *  (the TV emulator ran ~0.6 s a tile) adds the steps it missed, so the board still finishes when tvOS's does. */
private fun AppModel.reveal(start: Long = System.nanoTime()) {
    if (screen != Screen.building || builtCount >= channels.size) return
    val per = maxOf(1, channels.size / 24)   // ≤ ~4 s however big the lineup
    val due = ((System.nanoTime() - start) / 170_000_000L).toInt() + 1
    // Catch-up never passes the count tvOS's += per ends on, or the board scrolls (rows) past its last tile.
    builtCount = minOf((channels.size + per - 1) / per * per, maxOf(builtCount + per, due * per))
    later("build", seconds = 0.17) { reveal(start) }
}

fun AppModel.pressBuilding(cmd: RemoteCommand) {
    if (cmd == RemoteCommand.back) {
        loadTask?.cancel(); cancelTimer("build")
        if (librariesOrigin == Screen.settings) { librariesOrigin = null; go(Screen.settings) } else go(Screen.libraries)
        return
    }
    if (cmd != RemoteCommand.select) return
    if (libraryError != null) { startBuild(); return }
    if (!buildDone) return
    if (channels.isEmpty()) { go(Screen.libraries); return }
    librariesOrigin = null
    guideReset(); go(Screen.guide)
    toast(if (Platform.isTV) "We’re live. Press any arrow to start flipping." else "We’re live. ${Platform.verb} a program to start flipping.")
}
