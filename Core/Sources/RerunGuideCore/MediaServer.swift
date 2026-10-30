import Foundation

/// What the servers' device lists call this client: X-Plex-Platform / X-Plex-Device, the MediaBrowser `Device`.
/// iPhone vs iPad comes from the hardware model (no UIKit here); the simulator reports "arm64" and passes as an iPhone.
let clientDevice: (platform: String, device: String) = {
    #if os(tvOS)
    return ("tvOS", "Apple TV")
    #elseif targetEnvironment(macCatalyst) || os(macOS)
    return ("iOS", "Mac")   // Plex has no profile for "macOS" (start.m3u8 → 400); transcodes now send "Generic" (PlexClient.transcodeSource)
    #else
    var u = utsname(); uname(&u)
    let machine = withUnsafeBytes(of: &u.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
    return ("iOS", machine.hasPrefix("iPad") ? "iPad" : "iPhone")
    #endif
}()

/// Every backend (Plex, Emby, Jellyfin, demo) implements this. The guide and player only ever see this protocol.
public protocol MediaServer: Sendable {
    var kind: ServerKind { get }
    /// Human name shown in Settings ("Living Room", "Basement").
    var serverName: String { get }
    /// Libraries/sections the user can put on the air.
    func libraries() async throws -> [LibrarySection]
    /// Every movie and episode in `sections` (nil = every library). Called at launch once the cache is six hours old, and
    /// on "Rebuild"; the app caches it.
    func libraryItems(in sections: [LibrarySection]?) async throws -> [MediaItem]
    /// The newest items in `sections` (nil = every library) by date added: at most `limit` movies and `limit` episodes per
    /// library, cheap enough to ask every time the app opens. Feeds the "what's new" channels between full loads.
    func recentlyAdded(in sections: [LibrarySection]?, limit: Int) async throws -> [MediaItem]
    /// Stream URL for `item`, starting `offset` seconds in (the guide joins shows mid-stream).
    /// `session` is a caller-chosen label ("preview", "watch") so a backend keeps one server-side stream per purpose.
    func playbackSource(for item: MediaItem, startingAt offset: TimeInterval, session: String, tracks: TrackSelection) async throws -> PlaybackSource
    /// Audio and subtitle tracks of `item`, for the picker. Empty when the backend can't say.
    func tracks(for item: MediaItem) async throws -> [MediaTrack]
    /// A trailer for `item` (local extra or remote trailer the server can stream), or nil when it has none.
    func trailer(for item: MediaItem, session: String) async throws -> PlaybackSource?
    /// Best-effort release of the server-side stream for `session`; no-op for backends without one.
    func stopPlayback(session: String) async
    /// Marks `item` (an episode itself, not its show) watched for the signed-in user, as the server's own "mark as
    /// watched" does. No-op for the demo.
    func markWatched(_ item: MediaItem) async throws
    /// Absolute, authenticated URL for a server-relative artwork path, or nil.
    func artworkURL(path: String?) -> URL?
}

public extension MediaServer {
    var serverName: String { kind.displayName }
    func libraries() async throws -> [LibrarySection] { [] }
    func recentlyAdded(in sections: [LibrarySection]?, limit: Int) async throws -> [MediaItem] { [] }
    func trailer(for item: MediaItem, session: String) async throws -> PlaybackSource? { nil }
    func tracks(for item: MediaItem) async throws -> [MediaTrack] { [] }
    /// Server-default tracks (the guide preview, trailers).
    func playbackSource(for item: MediaItem, startingAt offset: TimeInterval, session: String) async throws -> PlaybackSource {
        try await playbackSource(for: item, startingAt: offset, session: session, tracks: TrackSelection())
    }
    func stopPlayback(session: String) async {}
    func markWatched(_ item: MediaItem) async throws {}
    /// Wide art for the preview window, falling back to the poster.
    func backdropURL(for item: MediaItem) -> URL? { artworkURL(path: item.backdropPath ?? item.artworkPath) }
}

public enum MediaServerError: Error, LocalizedError, Sendable {
    case unauthorized
    case http(Int, String)
    case invalidResponse(String)
    case notFound(String)

    public var errorDescription: String? {
        switch self {
        case .unauthorized: return "Not authorized. Sign in again."
        case .http(let code, _): return "Server returned \(code)"
        case .invalidResponse(let why): return "Unexpected response: \(why)"
        case .notFound(let what): return "Not found: \(what)"
        }
    }
}

/// Minimal shared HTTP helper. Throws `MediaServerError` on non-2xx, which includes an https → http redirect it refused.
public enum HTTP {
    public static func data(_ request: URLRequest) async throws -> Data {
        let (data, response) = try await URLSession.shared.data(for: request, delegate: NoDowngrade.shared)
        guard let http = response as? HTTPURLResponse else { throw MediaServerError.invalidResponse("not HTTP") }
        guard (200..<300).contains(http.statusCode) else {
            if http.statusCode == 401 || http.statusCode == 403 { throw MediaServerError.unauthorized }
            throw MediaServerError.http(http.statusCode, String(decoding: data, as: UTF8.self))
        }
        return data
    }

    public static func json<T: Decodable>(_ request: URLRequest, as type: T.Type = T.self) async throws -> T {
        let data = try await data(request)
        do { return try JSONDecoder().decode(T.self, from: data) }
        catch { throw MediaServerError.invalidResponse("\(error)") }
    }
}

/// Stops an https → http redirect: the token header would follow it in the clear. Stopped, the 3xx itself comes back.
private final class NoDowngrade: NSObject, URLSessionTaskDelegate, Sendable {
    static let shared = NoDowngrade()
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest) async -> URLRequest? {
        let https = { (r: URLRequest?) in r?.url?.scheme?.lowercased() == "https" }
        return https(task.currentRequest) && !https(request) ? nil : request
    }
}
