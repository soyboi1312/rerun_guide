import SwiftUI
import RerunGuideCore

/// First launch: welcome → (address) → link/login → libraries → building (port of the design prototype's k_welcome…k_building, startLink, startBuild),
/// wired to the real servers: Plex PIN / plex.tv password, Jellyfin Quick Connect / password, Emby PIN (emby.media/pin) /
/// password, demo library.
extension AppModel {
    /// The PIN / Quick Connect poll loop. One model per app, so a static is enough; cancelled on Back and on restart.
    private static var linkTask: Task<Void, Never>?

    /// Focus index of the Continue button on the address screen (input, found servers…, Continue).
    private var continueIndex: Int { foundServers.count + 1 }
    /// Plex 4-char / Emby 5-digit / Jellyfin 6-digit code, as boxes.
    var linkCodeChars: [String] {
        (linkCode ?? String(repeating: " ", count: serviceKind == .plex ? 4 : serviceKind == .emby ? 5 : 6)).map(String.init)
    }
    /// Plex and Emby link an account (plex.tv/link, emby.media/pin) and then pick one of its servers; Jellyfin's Quick
    /// Connect signs in to the one server whose address was typed.
    private var linksAccount: Bool { serviceKind != .jellyfin }
    var buildDone: Bool { !libraryLoading && libraryError == nil && builtCount >= channels.count }

    // MARK: Welcome

    /// The Welcome cards: Plex, Jellyfin, Emby, then the sample station. The screen and VoiceOver both read these.
    static var welcomeCards: [(name: String, desc: String)] {
        [("Plex", "Link this \(Platform.device) to your Plex account"), ("Jellyfin", "Point it at your Jellyfin server"),
         ("Emby", "Link this \(Platform.device) to your Emby account"), ("Sample station", "Take a look with a made-up library")]
    }

    func pressWelcome(_ cmd: RemoteCommand) {
        // Plex / Jellyfin / Emby are one row (k_welcome's step(a, 3, true)); the demo card sits above it, over Emby.
        if cmd == .up, focusIndex < 3 { focusIndex = 3; return }
        if cmd == .down, focusIndex == 3 { focusIndex = 2; return }
        if focusIndex < 3, step(cmd, count: 3, horizontal: true) { return }
        guard cmd == .select else { return }
        authError = nil
        foundServers = []; pendingToken = nil   // Plex PIN results never leak onto another service's address screen
        let picked: ServerKind = [.plex, .jellyfin, .emby, .demo][min(focusIndex, 3)]
        if picked != serviceKind { address = "" }   // a Plex address (port 32400) must not pre-fill the Emby/Jellyfin form
        switch focusIndex {
        case 0: serviceKind = .plex; go(.link)
        case 1: serviceKind = .jellyfin; go(.address)
        case 2: serviceKind = .emby; go(.link)
        default:
            connect(DemoMediaServer(), config: ServerConfig(kind: .demo, baseURL: URL(string: "demo://local")!, name: "Demo library"), token: "")
            go(.libraries)
        }
    }

    // MARK: Address

    func pressAddress(_ cmd: RemoteCommand) {
        if step(cmd, count: continueIndex + 1) { return }
        if cmd == .back { go(.welcome); focusIndex = [ServerKind.plex, .jellyfin, .emby].firstIndex(of: serviceKind) ?? 0; return }
        guard cmd == .select else { return }
        let i = focusIndex
        if i == 0 { editingField = "addr" }
        else if i < continueIndex {
            let s = foundServers[i - 1]
            // A server from the Plex or Emby account carries its access token, so Select connects. One found on the network still needs sign-in.
            if serviceKind == .plex, !s.accessToken.isEmpty { connectPlex(s); return }
            if serviceKind == .emby, !s.accessToken.isEmpty, let user = pendingToken {
                address = s.baseURL.absoluteString   // the check mark follows the pick while it signs in
                connectEmby(s, connectUserID: user); return
            }
            address = s.baseURL.absoluteString; focusIndex = continueIndex
        }
        else { continueFromAddress() }
    }

    private func continueFromAddress() {
        address = Self.normalized(address)
        guard let url = URL(string: address), url.host != nil else { authError = "Enter an address like 192.168.1.20:32400"; return }
        authError = nil
        switch serviceKind {
        case .plex:
            // A GDM-found server carries no token; it goes through the pendingToken / password path like a typed address.
            if let s = foundServers.first(where: { $0.baseURL.absoluteString == address && !$0.accessToken.isEmpty }) { connectPlex(s); return }
            guard let token = pendingToken else { go(.login); return }
            guard !authBusy else { return }
            authBusy = true; authSerial += 1
            let serial = authSerial
            Task { [self] in
                defer { if authSerial == serial { authBusy = false } }
                if let s = await plexServer(at: url, account: token, serial: serial) { connectPlex(s, account: token) }
            }
        case .jellyfin: go(.link)
        case .emby:
            if let s = foundServers.first(where: { $0.baseURL.absoluteString == address && !$0.accessToken.isEmpty }), let user = pendingToken {
                connectEmby(s, connectUserID: user)
            } else { go(.login) }
        default: go(.login)
        }
    }

    /// Text-field exit (submit, Menu): normalise the address and hand the remote back.
    func endEditing() {
        if editingField == "addr" { address = Self.normalized(address) }
        editingField = nil
    }

    /// UDP-broadcast for servers of `serviceKind` while the address screen has nothing to list (go(.address) calls this).
    func startDiscovery() {
        discovering = true
        Task { [self] in
            let found = await LocalDiscovery.find(kind: serviceKind)
            discovering = false
            guard screen == .address, foundServers.isEmpty else { return }
            foundServers = found
            if focusIndex > 0 { focusIndex = continueIndex }   // already on Continue → stay on Continue as rows appear
        }
    }

    /// Trim, prepend http:// when there is no scheme, drop a trailing slash.
    static func normalized(_ s: String) -> String {
        var a = s.trimmingCharacters(in: .whitespacesAndNewlines)
        if !a.isEmpty && !a.contains("://") { a = "http://" + a }
        while a.hasSuffix("/") { a.removeLast() }
        return a
    }

    /// The account's server a typed (or LAN-spotted) Plex address names, or nil with `authError` set. No token goes to
    /// the address itself: `PlexAuth.server(at:)` finds the server on the account and links to an address plex.tv lists.
    /// A result that lands after the viewer moved on (`authSerial`, or another service or sign-in) is dropped.
    private func plexServer(at url: URL, account: String, serial: Int) async -> PlexServer? {
        func current() -> Bool { !Task.isCancelled && authSerial == serial && serviceKind == .plex && pendingToken == account }
        do {
            let s = try await PlexAuth.server(at: url, token: account, clientID: Self.deviceID)
            return current() ? s : nil
        } catch {
            guard current() else { return nil }
            let host = url.host ?? "that address"
            switch error {
            case PlexAuth.AddressError.noServer: authError = "No Plex server answered at \(host). Check the address."
            case PlexAuth.AddressError.notOnAccount: authError = "\(host) is a Plex server, but not one on this Plex account."
            case PlexAuth.AddressError.unreachable:
                authError = "That’s your server, but no address Plex lists for it answered. On a VPN or proxy? Add it to the server’s Custom server access URLs."
            case MediaServerError.unauthorized: authError = "Plex didn’t accept this sign-in. Sign in again."; pendingToken = nil
            default: authError = error.localizedDescription
            }
            return nil
        }
    }

    /// `account`: the plex.tv account token, kept so a server that moves (new LAN or public address) can be found again.
    private func connectPlex(_ s: PlexServer, account: String? = nil) {
        if let a = account ?? pendingToken { Keychain.set(a, for: "plexAccount") }
        connect(PlexClient(baseURL: s.baseURL, token: s.accessToken, clientID: Self.deviceID, serverName: s.name),
                config: ServerConfig(kind: .plex, baseURL: s.baseURL, name: s.name,
                                     addresses: s.addresses.isEmpty ? nil : s.addresses, machineID: s.id), token: s.accessToken)
        go(.libraries)
    }

    /// An Emby Connect server (`EmbyConnect.servers`): trade its access key for a sign-in, then pick libraries.
    private func connectEmby(_ s: PlexServer, connectUserID: String) {
        guard !authBusy else { return }
        authBusy = true; authError = nil
        Task { [self] in
            defer { authBusy = false }
            do {
                let c = try await JellyfinClient.connectExchange(s, connectUserID: connectUserID, deviceID: Self.deviceID)
                guard screen == .address || screen == .link else { return }
                connect(c, config: ServerConfig(kind: .emby, baseURL: c.baseURL, name: c.serverName, userID: c.userID), token: c.token)
                go(.libraries)
            } catch {
                guard screen == .address || screen == .link else { return }
                authError = "Couldn’t sign in to \(s.name): \(error.localizedDescription)"
                if screen == .link { linked = false }
            }
        }
    }

    // MARK: Link (Plex PIN / Emby PIN / Jellyfin Quick Connect)

    func stopLink() { Self.linkTask?.cancel() }

    func startLink() {
        Self.linkTask?.cancel()
        linked = false; linkCode = nil; authError = nil
        Self.linkTask = Task { [self] in await runLink() }
    }

    private func runLink() async {
        let id = Self.deviceID
        do {
            if serviceKind == .plex {
                let pin = try await PlexAuth.requestPIN(clientID: id)
                linkCode = pin.code
                var token: String? = nil
                while token == nil {
                    try await Task.sleep(for: .seconds(3))
                    token = try await PlexAuth.pollPIN(pin, clientID: id)
                }
                linked = true
                let servers = try await PlexAuth.servers(token: token!, clientID: id)
                try Task.checkCancellation()
                switch servers.count {
                case 0: linked = false; authError = "No Plex Media Server is linked to this account."
                case 1: connectPlex(servers[0], account: token)
                default: foundServers = servers; pendingToken = token; address = servers[0].baseURL.absoluteString; go(.address); focusIndex = 1   // land on the first server, not the address field
                }
            } else if serviceKind == .emby {
                var account: EmbyConnect.Account? = nil
                var pins = 0
                while account == nil {   // an expired PIN is replaced by a fresh one; the viewer just reads the new code
                    pins += 1
                    guard pins <= 10 else { authError = "The code expired. Go back and try again."; return }
                    let pin = try await EmbyConnect.requestPIN(deviceID: id)
                    linkCode = pin.code
                    do {
                        while account == nil {
                            try await Task.sleep(for: .seconds(3))
                            account = try await EmbyConnect.pollPIN(pin)
                        }
                    } catch MediaServerError.notFound(_) { continue }
                }
                linked = true
                let servers = try await EmbyConnect.servers(account!)
                try Task.checkCancellation()
                switch servers.count {
                case 0: linked = false; authError = "No server is linked to that Emby Connect account. In your server’s dashboard, add your Emby Connect email to a user, then try again."
                case 1: connectEmby(servers[0], connectUserID: account!.userID)
                default: foundServers = servers; pendingToken = account!.userID; address = servers[0].baseURL.absoluteString; go(.address); focusIndex = 1
                }
            } else {
                guard let url = URL(string: address), url.host != nil else { throw MediaServerError.invalidResponse("bad server address") }
                let qc = try await JellyfinClient.quickConnectStart(baseURL: url, deviceID: id)
                linkCode = qc.code
                while try await !JellyfinClient.quickConnectIsApproved(baseURL: url, secret: qc.secret, deviceID: id) {
                    try await Task.sleep(for: .seconds(3))
                }
                linked = true
                let client = try await JellyfinClient.loginWithQuickConnect(baseURL: url, secret: qc.secret, deviceID: id)
                try Task.checkCancellation()
                connect(client, config: ServerConfig(kind: .jellyfin, baseURL: client.baseURL, name: client.serverName, userID: client.userID), token: client.token)
                go(.libraries)
            }
        } catch {
            guard !Task.isCancelled, screen == .link else { return }
            authError = error.localizedDescription
        }
    }

    func pressLink(_ cmd: RemoteCommand) {
        guard cmd == .back || cmd == .select else { return }
        Self.linkTask?.cancel()
        if cmd == .back { go(linksAccount ? .welcome : .address); focusIndex = linksAccount ? (serviceKind == .plex ? 0 : 2) : continueIndex }
        else { go(linksAccount ? .address : .login) }
    }

    // MARK: Login (plex.tv / Jellyfin / Emby password)

    func pressLogin(_ cmd: RemoteCommand) {
        if step(cmd, count: 3) { return }
        if cmd == .back { go(.address); focusIndex = continueIndex; return }
        guard cmd == .select, !authBusy else { return }
        switch focusIndex {
        case 0: editingField = "user"
        case 1: editingField = "pass"
        default: signIn()
        }
    }

    private func signIn() {
        authBusy = true; authError = nil; authSerial += 1
        let serial = authSerial, kind = serviceKind
        Task { [self] in
            defer { if authSerial == serial { authBusy = false } }
            do {
                if kind == .plex {
                    let token: String
                    do { token = try await PlexAuth.signIn(username: username, password: password, clientID: Self.deviceID) }
                    catch MediaServerError.unauthorized {
                        // plex.tv answers 401 both for bad credentials and for a missing 2FA code.
                        guard authSerial == serial else { return }
                        authError = "Wrong email or password. Two-factor on? Type your password followed by the 6-digit code."; return
                    }
                    guard authSerial == serial else { return }   // moved on while plex.tv answered
                    pendingToken = token
                    guard let url = URL(string: address), url.host != nil else { go(.address); return }
                    if let s = await plexServer(at: url, account: token, serial: serial) { connectPlex(s, account: token) }
                } else {
                    guard let url = URL(string: address), url.host != nil else { throw MediaServerError.invalidResponse("bad server address") }
                    let c = try await JellyfinClient.login(baseURL: url, username: username, password: password, deviceID: Self.deviceID, kind: kind)
                    guard authSerial == serial else { return }   // moved on while the server answered
                    connect(c, config: ServerConfig(kind: kind, baseURL: c.baseURL, name: c.serverName, userID: c.userID), token: c.token)
                    go(.libraries)
                }
            } catch { if authSerial == serial { authError = error.localizedDescription } }
        }
    }

    // MARK: Libraries

    /// Fetch the server's libraries into `librarySections`; the saved choice is preselected, else movies/shows default to
    /// On (a re-pick from Settings with nothing saved means "every library"). The view calls this on appear.
    /// What the Libraries rows show for `sections` before the viewer changes any: the saved choice, else movies and shows
    /// (every library on a Settings re-pick, where nothing saved means every library).
    private func libraryPicks(_ sections: [LibrarySection]) -> Set<String> {
        persisted.selectedLibraryIDs.map(Set.init) ?? Set(sections.filter { librariesOrigin == .settings || $0.kind != .other }.map(\.id))
    }

    func fetchLibraries() async {
        authBusy = true; authError = nil
        // The rows show what Continue builds until the list lands (a failed load keeps the saved choice; nothing saved: all).
        librarySelection = libraryPicks(librarySections)
        let serial = authSerial   // a refetch can run outside the screen's own task: what lands after the viewer left is dropped
        defer { if authSerial == serial { authBusy = false } }
        #if !os(tvOS)
        // The LAN address may answer now that local-network access was allowed during sign-in. The pick outlives a Back: it
        // may be the one that revives a plain-http link's client, and a cancelled one would read as unreachable.
        _ = await Task { await pickPlexAddress() }.value
        #endif
        guard authSerial == serial else { return }
        // Dropped (away during the pick, or no address checks out here): say so, not "No libraries reported".
        guard let server else { authError = "Could not connect to the server."; return }
        do {
            let sections = try await server.libraries().filter { $0.itemCount != 0 }   // empty (Emby's Collections, Playlists): nothing to schedule; unknown counts stay
            guard authSerial == serial else { return }
            librarySections = sections
            librarySelection = libraryPicks(sections)
        } catch { if !Task.isCancelled, authSerial == serial { authError = error.localizedDescription } }
    }

    /// Back: Settings re-pick → Settings (saved choice untouched); a Plex or Emby account link with other servers still in memory
    /// → the server list (no new PIN); otherwise Welcome is the signed-out state, as in the prototype's k_libraries.
    func pressLibraries(_ cmd: RemoteCommand) {
        let n = librarySections.count
        if step(cmd, count: n + 1) { return }
        if cmd == .back {
            if librariesOrigin == .settings { librariesOrigin = nil; go(.settings) }
            else if linksAccount, let token = pendingToken, !foundServers.isEmpty {   // back to the account's server list
                let rows = foundServers
                signOut(); pendingToken = token; foundServers = rows; go(.address); focusIndex = continueIndex
            }
            else { signOut() }
            return
        }
        // Its list and picks aren't in yet: nothing to save. No client (dropped, or unchecked on this network): Select checks
        // again (the spinner, then the list or the line). A list that failed to load keeps the saved choice: Continue builds
        // that, rows don't toggle.
        guard cmd == .select, !authBusy else { return }
        if server == nil {
            // Its list never landed: fetch it again. A landed list lacks only the client (dropped on the way out, or by a
            // network change): check the address, busy meanwhile, keeping the list and its unsaved picks. Busy at once: one
            // check; a viewer gone before it starts finds go() cleared busy.
            authBusy = true
            let serial = authSerial, landed = authError == nil
            Task {
                guard authSerial == serial else { return }
                guard landed else { await fetchLibraries(); return }
                let mine = pickGen + 1   // pickPlexAddress bumps it before its first suspension
                await pickPlexAddress()
                guard authSerial == serial else { return }
                authBusy = false
                // Nothing checks out here: say so, rows showing what Continue builds. Not when a newer pick took over or the
                // app left meanwhile (refused): that pick, or the way back's, decides.
                if server == nil, pickGen <= mine, !backgrounded { librarySelection = libraryPicks(librarySections); authError = "Could not connect to the server." }
            }
            return
        }
        if authError != nil { if focusIndex >= n { go(.building) }; return }
        if focusIndex < n {
            let id = librarySections[focusIndex].id
            if librarySelection.contains(id) { librarySelection.remove(id) } else { librarySelection.insert(id) }
        } else {
            persisted.selectedLibraryIDs = librarySelection.count == n ? nil : Array(librarySelection)
            save(); go(.building)
        }
    }

    // MARK: Building

    /// Kept in `loadTask` so Back can cancel the server round trips. An empty result is not an error: the view reads
    /// `buildDone && channels.isEmpty` and offers "Pick other libraries"; `libraryError` means the load threw (Retry).
    func startBuild() {
        builtCount = 0; libraryError = nil; libraryLoading = true
        loadTask?.cancel()
        loadTask = Task { [self] in
            await loadLibrary(force: true)   // a changed selection must be fetched, however fresh the cache is
            if Task.isCancelled { libraryError = nil; return }   // Back: the cancelled request is not a server error
            guard screen == .building else { return }
            reveal()
        }
    }

    /// One tile every 170 ms until every channel is on the board.
    private func reveal() {
        guard screen == .building, builtCount < channels.count else { return }
        withAnimation(.easeOut(duration: 0.3)) { builtCount += max(1, channels.count / 24) }   // ≤ ~4 s however big the lineup
        later("build", seconds: 0.17) { [weak self] in self?.reveal() }
    }

    func pressBuilding(_ cmd: RemoteCommand) {
        if cmd == .back {
            loadTask?.cancel(); cancelTimer("build")
            if librariesOrigin == .settings { librariesOrigin = nil; go(.settings) } else { go(.libraries) }
            return
        }
        guard cmd == .select else { return }
        if libraryError != nil { startBuild(); return }
        guard buildDone else { return }
        if channels.isEmpty { go(.libraries); return }
        librariesOrigin = nil
        guideReset(); go(.guide)
        toast(Platform.isTV ? "We’re live. Press any arrow to start flipping." : "We’re live. \(Platform.verb) a program to start flipping.")
    }
}
