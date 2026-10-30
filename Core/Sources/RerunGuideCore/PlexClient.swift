import AVFoundation
import Foundation

public struct PlexPIN: Sendable {
    public let id: Int
    /// 4-char code the user types at https://plex.tv/link
    public let code: String
    public init(id: Int, code: String) { self.id = id; self.code = code }
}

public struct PlexServer: Identifiable, Sendable {
    public let id: String
    public let name: String
    /// Best reachable connection (local > remote > relay).
    public let baseURL: URL
    /// Per-server access token (differs from the account token for shared servers).
    public let accessToken: String
    /// Every address plex.tv lists for it, best first, so a device that moves between networks can pick again later
    /// (`AppModel.pickPlexAddress`, which off TV drops the plain-http ones away from the server's network). Empty for a
    /// server found by LAN discovery, which has no token either.
    public let addresses: [PlexAddress]
    public init(id: String, name: String, baseURL: URL, accessToken: String, addresses: [PlexAddress] = []) {
        self.id = id; self.name = name; self.baseURL = baseURL; self.accessToken = accessToken; self.addresses = addresses
    }
}

/// One of a server's addresses, and whether plex.tv says it is on the server's own network.
public struct PlexAddress: Codable, Sendable, Hashable {
    public let url: URL
    public let local: Bool
    public init(url: URL, local: Bool) { self.url = url; self.local = local }
}

// MARK: - Shared request plumbing

private let plexIdentity = [
    "X-Plex-Product": "Rerun Guide", "X-Plex-Version": "1.0", "X-Plex-Platform": clientDevice.platform,
    "X-Plex-Device": clientDevice.device, "X-Plex-Device-Name": "Rerun Guide",
]

/// Every plex.tv and PMS request goes through here so the identity headers are always identical.
private func plexRequest(_ url: URL, token: String?, clientID: String, method: String = "GET",
                         timeout: TimeInterval = 30) -> URLRequest {
    var r = URLRequest(url: url, timeoutInterval: timeout)
    r.httpMethod = method
    r.setValue("application/json", forHTTPHeaderField: "Accept")
    r.setValue(clientID, forHTTPHeaderField: "X-Plex-Client-Identifier")
    if let token { r.setValue(token, forHTTPHeaderField: "X-Plex-Token") }
    for (k, v) in plexIdentity { r.setValue(v, forHTTPHeaderField: k) }
    return r
}

/// `base` + `path` (+ query), tolerating a base that carries a path prefix or trailing slash.
private func plexURL(_ base: URL, _ path: String, query: [URLQueryItem] = []) -> URL {
    var c = URLComponents(url: base, resolvingAgainstBaseURL: false)!
    c.path = (c.path.hasSuffix("/") ? String(c.path.dropLast()) : c.path) + path
    c.queryItems = query.isEmpty ? nil : query
    return c.url!
}

// MARK: - plex.tv

/// plex.tv account flow: request PIN → user enters it at plex.tv/link → poll → token → list servers.
public enum PlexAuth {
    /// plex.tv username/password sign-in (the "Type a server address instead" path). Returns the account token.
    /// Plex 2FA: user appends the 6-digit code to the password (official Plex guidance for apps using this endpoint); the UI says so.
    public static func signIn(username: String, password: String, clientID: String) async throws -> String {
        var req = plexRequest(URL(string: "https://plex.tv/api/v2/users/signin")!, token: nil, clientID: clientID, method: "POST")
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        let unreserved = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))
        req.httpBody = Data([("login", username), ("password", password)]
            .map { "\($0)=\($1.addingPercentEncoding(withAllowedCharacters: unreserved) ?? $1)" }
            .joined(separator: "&").utf8)
        // HTTP.data already maps 401 (bad credentials / missing 2FA code) to .unauthorized.
        return try await HTTP.json(req, as: SignInResponse.self).authToken
    }

    private struct SignInResponse: Decodable { let authToken: String }

    private struct PINResponse: Decodable { let id: Int; let code: String; let authToken: String? }

    private struct Resource: Decodable {
        struct Connection: Decodable { let uri: String; let `protocol`: String; let local: Bool; let relay: Bool; let address: String?; let port: Int? }
        let name: String
        let clientIdentifier: String
        /// Comma-separated, e.g. "server" or "client,player".
        let provides: String
        let accessToken: String?
        let connections: [Connection]?
        /// The server's "Secure connections: Required": no plain-http address for it.
        let httpsRequired: Bool?
        /// plex.tv sees this device and the server at the same public IP: the device is on the server's network.
        let publicAddressMatches: Bool?
    }
    private struct Identity: Decodable { struct Inner: Decodable { let machineIdentifier: String? }; let MediaContainer: Inner }

    /// `strong=true` yields the long OAuth code; omitting it yields the 4-char code for plex.tv/link.
    public static func requestPIN(clientID: String) async throws -> PlexPIN {
        let req = plexRequest(URL(string: "https://plex.tv/api/v2/pins")!, token: nil, clientID: clientID, method: "POST")
        let pin: PINResponse = try await HTTP.json(req)
        return PlexPIN(id: pin.id, code: pin.code)
    }

    /// nil until the user has claimed the PIN.
    public static func pollPIN(_ pin: PlexPIN, clientID: String) async throws -> String? {
        let req = plexRequest(URL(string: "https://plex.tv/api/v2/pins/\(pin.id)")!, token: nil, clientID: clientID)
        do { return try await HTTP.json(req, as: PINResponse.self).authToken }
        catch MediaServerError.http(404, _) { throw MediaServerError.notFound("PIN expired. Request a new code") }
    }

    private static let resourcesURL = URL(string: "https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1&includeIPv6=0")!

    /// Why `server(at:)` turned an address down: nothing Plex answered there, it's not a server on this account, or it
    /// is but none of the addresses plex.tv lists for it answered.
    public enum AddressError: Error, Sendable { case noServer, notOnAccount, unreachable }

    /// The account's server that `url` (an address the viewer typed or picked from a LAN reply) says it is. The address
    /// only names the server: its /identity, asked without a token, gives the machine; plex.tv must list that machine on
    /// this account; and the connection then goes to whichever address plex.tv lists for it answers as that machine (on the LAN,
    /// usually the plex.direct one, whose certificate is the server's own). No token is sent to the typed host unless
    /// plex.tv lists it, so a typo, or a LAN device claiming to be the server, gets nothing.
    public static func server(at url: URL, token: String, clientID: String) async throws -> PlexServer {
        let probe = plexRequest(plexURL(url, "/identity"), token: nil, clientID: clientID, timeout: 8)
        guard let id = (try? await HTTP.json(probe, as: Identity.self))?.MediaContainer.machineIdentifier else { throw AddressError.noServer }
        let resources: [Resource] = try await HTTP.json(plexRequest(resourcesURL, token: token, clientID: clientID))
        guard let r = resources.first(where: { $0.clientIdentifier == id && $0.provides.split(separator: ",").contains("server") }),
              let serverToken = r.accessToken else { throw AddressError.notOnAccount }   // no token of its own: never send it the account's
        let known = ranked(r)
        guard let best = await bestAddress(trusted(known, r), machineID: id, clientID: clientID, waitForLAN: true) else { throw AddressError.unreachable }
        return PlexServer(id: id, name: r.name, baseURL: best, accessToken: serverToken, addresses: known)
    }

    /// plex.tv's addresses for the server, best first. A LAN connection also gets its plain http://address:port, unless
    /// the server requires secure connections: where the router blocks DNS answers that point at private IPs (DNS
    /// rebinding protection), the plex.direct name never resolves and that is the only way in at home. It ranks after
    /// the https ones, and like every address it must answer as this machine before a token goes to it (`trusted`).
    private static func ranked(_ r: Resource) -> [PlexAddress] {
        var all: [(rank: Int, address: PlexAddress)] = []
        for c in r.connections ?? [] {
            if let u = URL(string: c.uri) { all.append((priority(c) * 1000 + all.count, PlexAddress(url: u, local: c.local))) }
            if c.local, !c.relay, r.httpsRequired != true, let a = c.address, let p = c.port, let u = URL(string: "http://\(a):\(p)") {
                all.append((1 * 1000 + all.count, PlexAddress(url: u, local: true)))   // priority of a local http connection
            }
        }
        var seen = Set<URL>()
        return all.sorted { $0.rank < $1.rank }.map(\.address).filter { seen.insert($0.url).inserted }
    }

    /// The ones of `ranked` worth probing from here. iPhone, iPad and Mac take a plain-http one (plex.tv's or the LAN
    /// one above) only when plex.tv says they are on the server's network: elsewhere (a café, a hotel) whoever answers
    /// there can pose as the server and read the token in the clear. The whole list is still saved, so
    /// `AppModel.pickPlexAddress`, which filters the same way, finds the LAN one again at home. A TV doesn't move networks.
    private static func trusted(_ ranked: [PlexAddress], _ r: Resource) -> [PlexAddress] {
        #if os(tvOS)
        return ranked
        #else
        return r.publicAddressMatches == true ? ranked : ranked.filter { $0.url.scheme != "http" }
        #endif
    }

    /// Whether plex.tv sees this device on `machineID`'s network (`publicAddressMatches`), asked with the account
    /// `token`. iPhone, iPad and Mac trust a plain-http address only then. No token, an error or no answer: false.
    /// 8 s, not 30: a plain-http link waits on this at launch. ponytail: it means "same public IP", which a shared exit
    /// (carrier-grade NAT, one VPN) also gives off the server's network; plex.tv offers nothing finer.
    public static func onServerNetwork(machineID: String, token: String?, clientID: String) async -> Bool {
        guard let token, let resources = try? await HTTP.json(plexRequest(resourcesURL, token: token, clientID: clientID, timeout: 8),
                                                              as: [Resource].self) else { return false }
        return resources.first { $0.clientIdentifier == machineID }?.publicAddressMatches == true
    }

    /// Every server on the account that answers, or with `only`, just that machineIdentifier (finding a moved server).
    public static func servers(token: String, clientID: String, only: String? = nil) async throws -> [PlexServer] {
        let resources: [Resource] = try await HTTP.json(plexRequest(resourcesURL, token: token, clientID: clientID))
        var servers: [PlexServer] = []
        for r in resources where r.provides.split(separator: ",").contains("server") && (only == nil || r.clientIdentifier == only) {
            // Probe LAN addresses even for shared servers: a family member's Apple TV usually sits on the server's LAN, and
            // going out via the public address hairpins through the router (verified: /identity answers, big pages time out).
            // Only an address whose /identity reports this server's machineIdentifier counts (firstAnswering).
            let ranked = Self.ranked(r)
            guard let serverToken = r.accessToken,   // no token of its own: never send it the account's
                  let uri = await bestAddress(trusted(ranked, r), machineID: r.clientIdentifier, clientID: clientID, waitForLAN: true) else { continue }
            servers.append(PlexServer(id: r.clientIdentifier, name: r.name, baseURL: uri, accessToken: serverToken, addresses: ranked))
        }
        return servers
    }

    /// The first of `ranked` that answers as `machineID` (all probed at once, GET /identity; 2 s for LAN, 4 s otherwise).
    /// `waitForLAN`, iPhone / iPad / Mac only: when only a non-LAN address answered, try the LAN ones twice more, 2 s
    /// apart (at most ~10 s more away from home). The first LAN request fails while the system asks for local-network permission, and settling for the
    /// public address hairpins every request through the router (big library pages time out). tvOS has no such prompt.
    public static func bestAddress(_ ranked: [PlexAddress], machineID: String, clientID: String, waitForLAN: Bool = false) async -> URL? {
        let found = await firstAnswering(ranked, machineID: machineID, clientID: clientID)
        #if !os(tvOS)
        let lan = ranked.filter(\.local)
        if waitForLAN, !lan.isEmpty, !lan.contains(where: { $0.url == found }) {
            for _ in 0..<2 {
                try? await Task.sleep(for: .seconds(2))
                if let url = await firstAnswering(lan, machineID: machineID, clientID: clientID) { return url }
            }
        }
        #endif
        return found
    }

    /// Probes every address at once; the best-ranked one whose /identity reports `machineID` wins (a stranger's LAN at
    /// the same IP can't impersonate the server).
    private static func firstAnswering(_ ranked: [PlexAddress], machineID: String, clientID: String) async -> URL? {
        await withTaskGroup(of: Int?.self) { group in
            for (i, a) in ranked.enumerated() {
                group.addTask {
                    let req = plexRequest(plexURL(a.url, "/identity"), token: nil, clientID: clientID, timeout: a.local ? 2 : 4)   // a LAN server answers in ms
                    return (try? await HTTP.json(req, as: Identity.self))?.MediaContainer.machineIdentifier == machineID ? i : nil
                }
            }
            var best: Int?
            for await i in group {
                guard let i, i < best ?? .max else { continue }
                best = i
                if i == 0 { group.cancelAll(); break }  // nothing can beat the top candidate; stop waiting on the rest
            }
            return best.map { ranked[$0].url }
        }
    }

    private static func priority(_ c: Resource.Connection) -> Int {
        (c.local ? 0 : c.relay ? 2 : 1) * 2 + (c.protocol == "https" ? 0 : 1)
    }
}

// MARK: - Plex Media Server

public final class PlexClient: MediaServer, Sendable {
    public let kind: ServerKind = .plex
    public let baseURL: URL
    public let token: String
    public let clientID: String

    public let serverName: String

    /// Streaming cap in kbit/s, 0 = original. Applied to transcode/direct-stream requests.
    public let maxBitrateKbps: Int

    public init(baseURL: URL, token: String, clientID: String, serverName: String = "Plex", maxBitrateKbps: Int = 0) {
        self.serverName = serverName; self.maxBitrateKbps = maxBitrateKbps
        self.baseURL = baseURL; self.token = token; self.clientID = clientID
    }

    // Plex omits any field it has no value for, so everything but keys/titles is optional.
    private struct Container<T: Decodable>: Decodable {
        struct Inner: Decodable { let totalSize: Int?; let Directory: [T]?; let Metadata: [T]? }
        let MediaContainer: Inner
    }
    private struct Directory: Decodable { let key: String; let type: String; let title: String? }
    private struct Metadata: Decodable {
        struct Tag: Decodable { let tag: String }
        let ratingKey: String
        let title: String?
        /// A film's cut ("Directors Cut"), from the file name's {edition-…}.
        let editionTitle: String?
        let year: Int?
        /// Unix seconds.
        let addedAt: Int?
        /// Milliseconds.
        let duration: Int?
        /// Times this account watched it (or marked it watched); absent = never. Stopping partway only sets viewOffset.
        let viewCount: Int?
        let thumb: String?
        let art: String?
        let summary: String?
        let contentRating: String?
        /// Extras only: "trailer", "behindTheScenes", …
        let subtype: String?
        let index: Int?
        let parentIndex: Int?
        let grandparentTitle: String?
        let grandparentRatingKey: String?
        let grandparentThumb: String?
        let grandparentArt: String?
        let Genre: [Tag]?
        let Director: [Tag]?
        let Role: [Tag]?
        var genres: [String] { Genre?.map(\.tag) ?? [] }
        var directors: [String] { Director?.map(\.tag) ?? [] }
        var actors: [String] { (Role ?? []).prefix(6).map(\.tag) }
        var runtime: TimeInterval? { duration.flatMap { $0 > 0 ? TimeInterval($0) / 1000 : nil } }
        var watched: Bool { (viewCount ?? 0) > 0 }
    }
    /// `/library/metadata/{key}` → Media[0].Part[0].Stream[]; streamType 1 video, 2 audio, 3 subtitle.
    private struct Stream: Decodable {
        let id: Int; let streamType: Int; let codec: String?
        /// "English" / "eng" (ISO 639-2/B).
        let language: String?; let languageCode: String?
        /// title is a custom name ("Commentary"); displayTitle is Plex's summary ("English (AAC Stereo)").
        let title: String?; let displayTitle: String?
        let `default`: Bool?; let forced: Bool?
    }
    private struct StreamMetadata: Decodable {
        struct Media: Decodable { struct Part: Decodable { let id: Int; let Stream: [Stream]? }; let Part: [Part]? }
        let Media: [Media]?
    }

    private func request(_ path: String, query: [URLQueryItem] = []) -> URLRequest {
        plexRequest(plexURL(baseURL, path, query: query), token: token, clientID: clientID)
    }

    private func sections() async throws -> [Directory] {
        try await HTTP.json(request("/library/sections"), as: Container<Directory>.self).MediaContainer.Directory ?? []
    }

    public func libraries() async throws -> [LibrarySection] {
        let dirs = try await sections()
        // Counts come from a zero-size page's totalSize, all sections at once; a failed count is just nil.
        let counts = await withTaskGroup(of: (Int, Int?).self) { group in
            for (i, d) in dirs.enumerated() {
                group.addTask {
                    // totalSize is only reported when paging via the container HEADERS (verified on PMS 1.43); the query form returns every item.
                    var req = self.request("/library/sections/\(d.key)/all")
                    req.setValue("0", forHTTPHeaderField: "X-Plex-Container-Start")
                    req.setValue("0", forHTTPHeaderField: "X-Plex-Container-Size")
                    return (i, (try? await HTTP.json(req, as: Container<Metadata>.self))?.MediaContainer.totalSize)
                }
            }
            return await group.reduce(into: [Int: Int]()) { if let n = $1.1 { $0[$1.0] = n } }
        }
        return dirs.enumerated().map { i, d in
            LibrarySection(id: d.key, title: d.title ?? d.key,
                           kind: d.type == "movie" ? .movies : d.type == "show" ? .shows : .other, itemCount: counts[i])
        }
    }

    public func libraryItems(in sections: [LibrarySection]?) async throws -> [MediaItem] {
        let dirs = try await directories(sections)
        // Joined in section order, not finish order: shuffle channels keep library order, so a race here would give
        // every device (and every refresh) a different lineup.
        return try await withThrowingTaskGroup(of: (Int, [MediaItem]).self) { group in
            for (i, d) in dirs.enumerated() { group.addTask { (i, try await self.items(in: d)) } }
            var parts = [[MediaItem]](repeating: [], count: dirs.count)
            for try await (i, items) in group { parts[i] = items }
            return parts.flatMap { $0 }
        }
    }

    /// The newest `limit` movies or episodes of each section, by date added: one small page per section (plus one request
    /// for the episodes' shows), so the app can run it every time it opens.
    public func recentlyAdded(in sections: [LibrarySection]?, limit: Int) async throws -> [MediaItem] {
        var out: [MediaItem] = []
        for d in try await directories(sections) {
            if d.type == "movie" {
                out += (try await page(section: d.key, type: 1, start: 0, size: limit, sort: "addedAt:desc").Metadata ?? [])
                    .compactMap { movie($0, section: d.key) }
                continue
            }
            let episodes = try await page(section: d.key, type: 4, start: 0, size: limit, sort: "addedAt:desc").Metadata ?? []
            let keys = Array(Set(episodes.compactMap(\.grandparentRatingKey))).sorted()
            let shows = keys.isEmpty ? [] : try await HTTP.json(request("/library/metadata/\(keys.joined(separator: ","))"),
                                                                as: Container<Metadata>.self).MediaContainer.Metadata ?? []
            let showByKey = Dictionary(shows.map { ($0.ratingKey, $0) }, uniquingKeysWith: { a, _ in a })
            out += episodes.compactMap { e in episode(e, show: e.grandparentRatingKey.flatMap { showByKey[$0] }, section: d.key) }
        }
        return out
    }

    /// The movie and TV sections to load: the chosen ones, or every one the server has.
    private func directories(_ sections: [LibrarySection]?) async throws -> [Directory] {
        guard let sections else { return try await self.sections().filter { $0.type == "movie" || $0.type == "show" } }
        return sections.compactMap { s in
            switch s.kind {
            case .movies: Directory(key: s.id, type: "movie", title: s.title)
            case .shows: Directory(key: s.id, type: "show", title: s.title)
            case .other: nil
            }
        }
    }

    private func items(in section: Directory) async throws -> [MediaItem] {
        if section.type == "movie" {
            return try await all(section: section.key, type: 1).compactMap { movie($0, section: section.key) }
        }
        // Genres, cast, rating and the blurb live on the show (type=2), not the episode (type=4); join on grandparentRatingKey.
        async let shows = all(section: section.key, type: 2)
        async let episodes = all(section: section.key, type: 4)
        let showByKey = Dictionary(try await shows.map { ($0.ratingKey, $0) }, uniquingKeysWith: { a, _ in a })
        return try await episodes.compactMap { e in
            episode(e, show: e.grandparentRatingKey.flatMap { showByKey[$0] }, section: section.key)
        }
    }

    private func movie(_ m: Metadata, section: String) -> MediaItem? {
        guard let runtime = m.runtime else { return nil }
        let added = m.addedAt.map { Date(timeIntervalSince1970: TimeInterval($0)) }
        return MediaItem(id: m.ratingKey, kind: .movie, title: MediaItem.title(m.title ?? "Untitled", edition: m.editionTitle), year: m.year, genres: m.genres,
                         runtime: runtime, artworkPath: m.thumb, backdropPath: m.art, overview: m.summary,
                         contentRating: m.contentRating, directors: m.directors, actors: m.actors,
                         libraryID: section, addedAt: added, watched: m.watched)
    }

    private func episode(_ e: Metadata, show: Metadata?, section: String) -> MediaItem? {
        guard let runtime = e.runtime else { return nil }
        let added = e.addedAt.map { Date(timeIntervalSince1970: TimeInterval($0)) }
        return MediaItem(id: e.ratingKey, kind: .episode, title: e.title ?? "Untitled", seriesTitle: e.grandparentTitle,
                         seriesID: e.grandparentRatingKey, season: e.parentIndex, episode: e.index, year: e.year,
                         genres: show?.genres ?? [], runtime: runtime, artworkPath: e.grandparentThumb ?? e.thumb,
                         backdropPath: e.grandparentArt ?? e.art, overview: e.summary,
                         contentRating: show?.contentRating, directors: show?.directors ?? [],
                         actors: show?.actors ?? [], libraryID: section, addedAt: added, watched: e.watched)
    }

    /// `/library/sections/{key}/all?type=` 500 at a time: page 0 learns `totalSize`, the rest load 4 at a time, joined in page order.
    /// `excludeElements=Media` drops the Media/Part/Stream subtree (the bulk of an episode's JSON); everything `Metadata`
    /// decodes, `duration` included, is an attribute or tag of the item itself.
    private func all(section key: String, type: Int) async throws -> [Metadata] {
        let first = try await page(section: key, type: type, start: 0)
        var out = first.Metadata ?? []
        // ponytail: totalSize is the server's word; capped at 2M items so a bogus one can't allocate forever.
        let starts = Array(stride(from: 500, to: min(first.totalSize ?? 0, 2_000_000), by: 500))
        guard !out.isEmpty, !starts.isEmpty else { return out }
        // ponytail: 4 in flight per call; movies, shows and episodes fetch in parallel, so URLSession's 6-per-host cap is the real ceiling.
        let pages = try await withThrowingTaskGroup(of: (Int, [Metadata]).self) { group -> [Int: [Metadata]] in
            var pages: [Int: [Metadata]] = [:]
            var i = 0
            while i < starts.count || !group.isEmpty {
                if i < starts.count, i - pages.count < 4 {
                    let s = starts[i]; i += 1
                    group.addTask { (s, try await self.page(section: key, type: type, start: s).Metadata ?? []) }
                } else if let (s, items) = try await group.next() {
                    pages[s] = items
                }
            }
            return pages
        }
        for s in starts { out += pages[s] ?? [] }
        return out
    }

    private func page(section key: String, type: Int, start: Int, size: Int = 500, sort: String? = nil) async throws -> Container<Metadata>.Inner {
        var query = [URLQueryItem(name: "type", value: "\(type)"), URLQueryItem(name: "excludeElements", value: "Media")]
        if let sort { query.append(URLQueryItem(name: "sort", value: sort)) }
        var req = request("/library/sections/\(key)/all", query: query)
        req.setValue("\(start)", forHTTPHeaderField: "X-Plex-Container-Start")
        req.setValue("\(size)", forHTTPHeaderField: "X-Plex-Container-Size")
        return try await HTTP.json(req, as: Container<Metadata>.self).MediaContainer
    }

    /// First part's id and streams of `/library/metadata/{key}`; throws when the item has no playable part.
    private func streams(for key: String) async throws -> (partID: Int, streams: [Stream]) {
        let part = try await HTTP.json(request("/library/metadata/\(key)"), as: Container<StreamMetadata>.self)
            .MediaContainer.Metadata?.first?.Media?.first?.Part?.first
        guard let part else { throw MediaServerError.notFound("media part for \(key)") }
        return (part.id, part.Stream ?? [])
    }

    public func tracks(for item: MediaItem) async throws -> [MediaTrack] {
        try await streams(for: item.id).streams.compactMap { s in
            guard let kind: MediaTrack.Kind = s.streamType == 2 ? .audio : s.streamType == 3 ? .subtitle : nil else { return nil }
            var title = s.displayTitle ?? s.title ?? s.language ?? s.codec ?? "Track \(s.id)"
            // "Commentary · English (AAC Stereo)", but the title alone once it names the summary or the language ("English
            // (SDH)" over "English", "Spanish (Castilian)" over "Español"), and the summary alone if it holds the title.
            if let t = s.title, !t.isEmpty, let d = s.displayTitle, !d.isEmpty {
                let names = [d] + [MediaTrack.languageName(s.languageCode)].compactMap { $0 }
                title = names.contains { t.localizedCaseInsensitiveContains($0) } ? t : d.localizedCaseInsensitiveContains(t) ? d : "\(t) · \(d)"
            }
            return MediaTrack(id: String(s.id), kind: kind, language: s.languageCode, title: title,
                              isDefault: s.default ?? false, isForced: s.forced ?? false)
        }
    }

    public func playbackSource(for item: MediaItem, startingAt offset: TimeInterval, session: String, tracks: TrackSelection) async throws -> PlaybackSource {
        // Server defaults (guide preview, trailers) need no metadata round trip.
        if !tracks.isDefault {
            // The transcoder follows the part's *selected* streams, and the only way to pick them is the PUT every official
            // client makes (python-plexapi setSelectedAudioStream/setSelectedSubtitleStream). Side effect: it changes the
            // part's default audio/subtitle for this account server-wide, exactly as choosing a track in Plex Web does.
            // subtitleStreamID=0 clears the subtitle. A failed PUT just means server defaults; playback still starts.
            let partID = try await streams(for: item.id).partID
            var query = [URLQueryItem(name: "allParts", value: "1")]
            if let a = tracks.audioID { query.append(URLQueryItem(name: "audioStreamID", value: a)) }
            switch tracks.subtitle {
            case .track(let id): query.append(URLQueryItem(name: "subtitleStreamID", value: id))
            case .off: query.append(URLQueryItem(name: "subtitleStreamID", value: "0"))
            case .auto: break
            }
            var put = request("/library/parts/\(partID)", query: query)
            put.httpMethod = "PUT"
            do { _ = try await HTTP.data(put) } catch { print("PlexClient.playbackSource: select streams on part \(partID):", error) }
        }
        return await transcodeSource(ratingKey: item.id, offset: offset, session: session, subtitle: tracks.subtitle)
    }

    /// Best-effort `/video/:/transcode/universal/stop` for this device's `session`; PMS prunes stragglers itself.
    public func stopPlayback(session: String) async {
        _ = try? await HTTP.data(request("/video/:/transcode/universal/stop",
                                         query: [URLQueryItem(name: "session", value: "\(clientID)-\(session)")]))
    }

    /// Plex's own "mark as played": PUT /:/scrobble, keyed by the item's ratingKey.
    public func markWatched(_ item: MediaItem) async throws {
        var put = request("/:/scrobble", query: [URLQueryItem(name: "identifier", value: "com.plexapp.plugins.library"),
                                                 URLQueryItem(name: "key", value: item.id)])
        put.httpMethod = "PUT"
        _ = try await HTTP.data(put)
    }

    /// One request to `/library/metadata/{key}/extras`; prefers a trailer, falls back to any extra, nil when there are none.
    /// Episodes have no extras of their own, so look at the show.
    public func trailer(for item: MediaItem, session: String) async throws -> PlaybackSource? {
        let key = item.kind == .episode ? item.seriesID ?? item.id : item.id
        let extras = try await HTTP.json(request("/library/metadata/\(key)/extras"), as: Container<Metadata>.self).MediaContainer.Metadata ?? []
        guard let extra = extras.first(where: { $0.subtype == "trailer" }) ?? extras.first else { return nil }
        return await transcodeSource(ratingKey: extra.ratingKey, offset: 0, session: session)
    }

    /// Universal-transcoder HLS start for any rating key (items and extras alike); the server starts at `offset`.
    /// The session id is stable per device + purpose, so a reload replaces the old transcode instead of adding one.
    /// Subtitles: `.track` → `subtitles=burn` (the HLS transcoder burns the part's selected text/PGS sub into the picture, so
    /// `subtitleInPlaylist` is always false); `.off` → `skipSubtitles=1`; `.auto` → nothing. Both values are what
    /// plex-for-kodi's buildTranscodeHls/buildTranscodeMkv send; `subtitles=none` is not seen anywhere, so it isn't used.
    private func transcodeSource(ratingKey: String, offset: TimeInterval, session: String,
                                 subtitle: TrackSelection.Subtitle = .auto) async -> PlaybackSource {
        // fMP4 HLS (container=mp4) with hevc in the target, so HEVC sources direct-stream instead of transcoding; HEVC over
        // HLS needs fMP4 per Apple's spec. Profile-Extra grammar as in Plex's own clients.
        // The target only counts under X-Plex-Platform "Generic" (measured on PMS 1.43.5, 2026-10-26): "tvOS", "iOS" and
        // "Android" pick built-in profiles that answer MPEG-TS and re-encode HEVC to H.264 (iOS/Android also E-AC3 to AAC)
        // whatever the target says, and "macOS" is a 400. "Generic" copies HEVC (hvc1 init) and E-AC3 into fMP4 and
        // transcodes only codecs the target leaves out. So transcodes say "Generic"; every other request keeps the real platform.
        var profile = "add-transcode-target(type=videoProfile&context=streaming&protocol=hls&container=mp4"
            + "&videoCodec=h264,hevc&audioCodec=aac,ac3,eac3)"
        // Copied HDR10 / Dolby Vision 8 HEVC is a lone VIDEO-RANGE=PQ variant, which AVPlayer refuses on a screen that can't
        // show HDR ("Cannot open", CoreMedia -17223; SDR TVs, the simulator). There Plex must tone-map: HEVC whose transfer
        // is tagged anything but SDR transcodes to BT.709 (measured: a bare notMatch on PQ transcodes but stays PQ), while
        // untagged files, most SDR rips, still copy.
        if !AVPlayer.eligibleForHDRPlayback {
            profile += "+add-limitation(scope=videoCodec&scopeName=hevc&type=match&name=video.colorTrc&list=bt709|smpte170m|bt470bg&isRequired=false)"
        }
        // The guide preview is a muted ~800 px window: cap it at 720p / 4 Mbps (or lower if Settings says so) so browsing the
        // guide never spins up a full-rate transcode. Otherwise the Settings cap, 0 = original. directStream=1 stays, so only
        // sources above the cap transcode. ponytail: an h264 source above the cap transcodes rather than direct-streams, which is intended.
        var cap = maxBitrateKbps
        if session == "preview" { cap = cap > 0 ? min(cap, 4000) : 4000 }
        var query = [
            ("path", "/library/metadata/\(ratingKey)"), ("mediaIndex", "0"), ("partIndex", "0"),
            ("protocol", "hls"), ("directPlay", "0"), ("directStream", "1"), ("fastSeek", "1"),
            ("offset", "\(Int(offset))"), ("session", "\(clientID)-\(session)"),
            ("X-Plex-Client-Identifier", clientID), ("X-Plex-Token", token),
            ("X-Plex-Platform", "Generic"), ("X-Plex-Product", plexIdentity["X-Plex-Product"]!),
            ("X-Plex-Version", plexIdentity["X-Plex-Version"]!), ("X-Plex-Client-Profile-Extra", profile),
        ].map { URLQueryItem(name: $0, value: $1) }
        if cap > 0 {
            query += [("maxVideoBitrate", "\(cap)"), ("videoResolution", cap <= 4000 ? "1280x720" : "1920x1080")]
                .map { URLQueryItem(name: $0, value: $1) }
        }
        switch subtitle {
        case .track: query.append(URLQueryItem(name: "subtitles", value: "burn"))
        case .off: query.append(URLQueryItem(name: "skipSubtitles", value: "1"))
        case .auto: break
        }
        // PMS 1.43 can answer start.m3u8 with 400 unless this client first asked for a decision on the same item, as every
        // official client does: a best-effort GET of the same query on /decision. If it fails, start still gets its try.
        var decision = URLRequest(url: plexURL(baseURL, "/video/:/transcode/universal/decision", query: query))
        decision.timeoutInterval = 10
        _ = try? await HTTP.data(decision)
        return PlaybackSource(url: plexURL(baseURL, "/video/:/transcode/universal/start.m3u8", query: query),
                              startsAtOffset: true, subtitleInPlaylist: false)
    }

    public func artworkURL(path: String?) -> URL? {
        guard let path else { return nil }
        return plexURL(baseURL, "/photo/:/transcode", query: [
            URLQueryItem(name: "width", value: "400"), URLQueryItem(name: "height", value: "600"),
            URLQueryItem(name: "minSize", value: "1"), URLQueryItem(name: "url", value: path),
            URLQueryItem(name: "X-Plex-Token", value: token),
        ])
    }
}
