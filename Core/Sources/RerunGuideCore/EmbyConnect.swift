import Foundation

/// Emby Connect's PIN sign-in, the TV flow Emby's own apps use and the Emby side of plex.tv/link: show a PIN, the viewer
/// types it at emby.media/pin while signed in to their Emby Connect account, then this device lists that account's servers
/// and trades a server's Connect key for an ordinary sign-in on it (`JellyfinClient.connectExchange`).
/// Emby doesn't document the PIN calls; these are the ones Emby.ApiClient.Java's ConnectService makes. A server only shows
/// up once its owner has linked an Emby Connect account to one of its users (Emby dashboard › Users › Emby Connect).
public enum EmbyConnect {
    public struct Pin: Sendable, Equatable {
        public let code: String
        public let deviceID: String
        public init(code: String, deviceID: String) { self.code = code; self.deviceID = deviceID }
    }

    /// The Emby Connect account a confirmed PIN signed in.
    public struct Account: Sendable, Equatable {
        public let userID: String
        public let token: String
        public init(userID: String, token: String) { self.userID = userID; self.token = token }
    }

    /// A 5-digit code to show; it lasts a few minutes (`pollPIN` throws `notFound` once it has expired).
    public static func requestPIN(deviceID: String) async throws -> Pin {
        struct Created: Decodable { let Pin: String }
        let c: Created = try await HTTP.json(request("pin", query: ["deviceId": deviceID], form: ["deviceId": deviceID]))
        return Pin(code: c.Pin, deviceID: deviceID)
    }

    /// nil while the viewer hasn't typed the code yet; the account once they have. Throws `notFound` when it expired.
    public static func pollPIN(_ pin: Pin) async throws -> Account? {
        struct Status: Decodable { let IsConfirmed: Bool; let IsExpired: Bool }
        let s: Status
        do { s = try await HTTP.json(request("pin", query: ["deviceId": pin.deviceID, "pin": pin.code])) }
        catch MediaServerError.http(404, _) { throw MediaServerError.notFound("Emby PIN (expired)") }
        if s.IsExpired { throw MediaServerError.notFound("Emby PIN (expired)") }
        guard s.IsConfirmed else { return nil }
        struct Exchanged: Decodable { let UserId: String; let AccessToken: String }
        let e: Exchanged = try await HTTP.json(request("pin/authenticate", form: ["deviceId": pin.deviceID, "pin": pin.code]))
        return Account(userID: e.UserId, token: e.AccessToken)
    }

    /// The servers linked to `account`, as rows for the server list: `accessToken` holds the server's Connect access key
    /// (traded for a real sign-in by `JellyfinClient.connectExchange`), `addresses` its LAN address first, then its remote one.
    public static func servers(_ account: Account) async throws -> [PlexServer] {
        var r = request("servers", query: ["userId": account.userID])
        r.setValue(account.token, forHTTPHeaderField: "X-Connect-UserToken")
        return rows(try await HTTP.json(r))
    }

    struct Server: Decodable { let Name: String?; let Url: String?; let LocalAddress: String?; let AccessKey: String?; let SystemId: String? }

    static func rows(_ servers: [Server]) -> [PlexServer] {
        servers.compactMap { s in
            guard let key = s.AccessKey, !key.isEmpty, let id = s.SystemId else { return nil }
            let addresses = [s.LocalAddress.flatMap(url).map { PlexAddress(url: $0, local: true) },
                             s.Url.flatMap(url).map { PlexAddress(url: $0, local: false) }].compactMap { $0 }
            guard let first = addresses.first else { return nil }
            return PlexServer(id: id, name: s.Name ?? first.url.host ?? "Emby", baseURL: first.url, accessToken: key, addresses: addresses)
        }
    }

    private static func url(_ s: String) -> URL? {
        var a = s.trimmingCharacters(in: .whitespaces)
        while a.hasSuffix("/") { a.removeLast() }
        guard let u = URL(string: a), u.host != nil else { return nil }
        return u
    }

    /// connect.emby.media/service/<path>; `form` makes it a form-encoded POST, as Emby's clients send these.
    private static func request(_ path: String, query: [String: String] = [:], form: [String: String]? = nil) -> URLRequest {
        var c = URLComponents(string: "https://connect.emby.media/service/" + path)!
        if !query.isEmpty { c.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) } }
        var r = URLRequest(url: c.url!, timeoutInterval: 20)
        r.setValue("RerunGuide/1.0", forHTTPHeaderField: "X-Application")
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        if let form {
            var body = URLComponents()
            body.queryItems = form.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
            r.httpMethod = "POST"
            r.httpBody = Data((body.percentEncodedQuery ?? "").utf8)
            r.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        }
        return r
    }
}
