import XCTest
@testable import RerunGuideCore

/// `PlexAuth.server(at:)`: a typed Plex address only names a server on the account; the connection goes to an address plex.tv
/// lists for it, and no token goes anywhere but plex.tv while that is checked. plex.tv and the servers are stubbed.
final class PlexAddressTests: XCTestCase {
    /// Canned answers by "host/path"; every request is recorded.
    final class Stub: URLProtocol {
        nonisolated(unsafe) static var routes: [String: String] = [:]
        nonisolated(unsafe) static var seen: [URLRequest] = []
        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func stopLoading() {}
        override func startLoading() {
            Self.seen.append(request)
            guard let url = request.url, let body = Self.routes["\(url.host ?? "")\(url.path)"] else {
                client?.urlProtocol(self, didFailWithError: URLError(.cannotConnectToHost)); return
            }
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil)!,
                                cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(body.utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    override func setUp() {
        URLProtocol.registerClass(Stub.self)
        Stub.seen = []
        Stub.routes = [
            "plex.tv/api/v2/resources": #"""
                [{"name":"Den","clientIdentifier":"m1","provides":"server","accessToken":"server-token","owned":true,"publicAddressMatches":true,
                  "connections":[{"uri":"http://192.168.1.5:32400","protocol":"http","local":true,"relay":false},
                                 {"uri":"https://1-2-3-4.abc.plex.direct:32400","protocol":"https","local":false,"relay":false}]},
                 {"name":"Cabin","clientIdentifier":"m3","provides":"server","accessToken":"cabin-token","owned":true,
                  "connections":[{"uri":"https://5-6-7-8.def.plex.direct:32400","protocol":"https","local":false,"relay":false}]},
                 {"name":"Attic","clientIdentifier":"m4","provides":"server","accessToken":"attic-token","owned":true,"publicAddressMatches":true,
                  "connections":[{"uri":"https://attic.invalid:32400","protocol":"https","local":true,"relay":false,"address":"192.168.1.4","port":32400}]},
                 {"name":"Vault","clientIdentifier":"m5","provides":"server","accessToken":"vault-token","owned":true,"httpsRequired":true,"publicAddressMatches":true,
                  "connections":[{"uri":"https://vault.invalid:32400","protocol":"https","local":true,"relay":false,"address":"192.168.1.6","port":32400}]},
                 {"name":"Loft","clientIdentifier":"m6","provides":"server","accessToken":"loft-token","owned":true,"publicAddressMatches":false,
                  "connections":[{"uri":"https://loft.invalid:32400","protocol":"https","local":true,"relay":false,"address":"192.168.1.16","port":32400}]},
                 {"name":"Shed","clientIdentifier":"m7","provides":"server","accessToken":"shed-token","owned":true,
                  "connections":[{"uri":"https://shed.invalid:32400","protocol":"https","local":false,"relay":false},
                                 {"uri":"http://203.0.113.17:32400","protocol":"http","local":false,"relay":false}]},
                 {"name":"Phone","clientIdentifier":"p1","provides":"client,player","accessToken":"x","owned":true,"connections":[]}]
                """#,
            "192.168.1.4/identity": #"{"MediaContainer":{"machineIdentifier":"m4"}}"#,   // Attic: its plex.direct name won't resolve
            "192.168.1.6/identity": #"{"MediaContainer":{"machineIdentifier":"m5"}}"#,   // Vault: the same, but secure connections only
            "192.168.1.5/identity": #"{"MediaContainer":{"machineIdentifier":"m1"}}"#,
            "10.8.0.2/identity": #"{"MediaContainer":{"machineIdentifier":"m1"}}"#,      // says it's Den; plex.tv doesn't list it
            "192.168.1.9/identity": #"{"MediaContainer":{"machineIdentifier":"m2"}}"#,   // a Plex server, not this account's
            "192.168.1.3/identity": #"{"MediaContainer":{"machineIdentifier":"m3"}}"#,   // Cabin, whose listed address is down
            "192.168.1.7/identity": #"<html>router</html>"#,                              // not a Plex server
            "loft.invalid/identity": #"{"MediaContainer":{"machineIdentifier":"m6"}}"#,   // Loft and Shed answer at both
            "192.168.1.16/identity": #"{"MediaContainer":{"machineIdentifier":"m6"}}"#,
            "shed.invalid/identity": #"{"MediaContainer":{"machineIdentifier":"m7"}}"#,
            "203.0.113.17/identity": #"{"MediaContainer":{"machineIdentifier":"m7"}}"#,   // Shed's custom http access URL
        ]
    }
    override func tearDown() { URLProtocol.unregisterClass(Stub.self) }

    func server(_ address: String) async throws -> PlexServer {
        try await PlexAuth.server(at: URL(string: address)!, token: "account-token", clientID: "test")
    }

    func testAddressNamesTheServerPlexTVListsTheWay() async throws {
        let s = try await server("http://192.168.1.5:32400")
        XCTAssertEqual(s.id, "m1"); XCTAssertEqual(s.name, "Den")
        XCTAssertEqual(s.accessToken, "server-token")
        XCTAssertEqual(s.baseURL.absoluteString, "http://192.168.1.5:32400")
        XCTAssertEqual(s.addresses.map(\.url.absoluteString), ["http://192.168.1.5:32400", "https://1-2-3-4.abc.plex.direct:32400"])

        // A host plex.tv doesn't list that claims to be Den only names it: the connection goes to Den's listed address.
        let claimed = try await server("http://10.8.0.2:32400")
        XCTAssertEqual(claimed.baseURL.absoluteString, "http://192.168.1.5:32400")
        XCTAssertEqual(claimed.addresses, s.addresses)

        // A router that blocks DNS answers pointing at private IPs: the LAN plex.direct name fails, its plain address works.
        let attic = try await server("http://192.168.1.4:32400")
        XCTAssertEqual(attic.baseURL.absoluteString, "http://192.168.1.4:32400")
        XCTAssertEqual(attic.addresses.map(\.url.absoluteString), ["https://attic.invalid:32400", "http://192.168.1.4:32400"])
    }

    func testOtherAddressesAreTurnedDown() async {
        for (address, want) in [("http://192.168.1.9:32400", PlexAuth.AddressError.notOnAccount),
                                ("http://192.168.1.3:32400", .unreachable), ("http://192.168.1.6:32400", .unreachable),
                                ("http://192.168.1.7", .noServer), ("http://192.168.1.250:32400", .noServer)] {
            do { _ = try await server(address); XCTFail("\(address) accepted") }
            catch { XCTAssertEqual(error as? PlexAuth.AddressError, want, address) }
        }
    }

    /// No token at all goes anywhere but plex.tv while the address is checked: the account token is plex.tv's, and the
    /// server's token is only used later, by the connection, at an address plex.tv listed.
    func testTokensOnlyGoToPlexTV() async {
        for a in ["http://192.168.1.5:32400", "http://10.8.0.2:32400", "http://192.168.1.9:32400", "http://192.168.1.3:32400"] {
            _ = try? await server(a)
        }
        XCTAssertFalse(Stub.seen.isEmpty)
        for r in Stub.seen where r.url?.host != "plex.tv" {
            XCTAssertNil(r.value(forHTTPHeaderField: "X-Plex-Token"), r.url!.absoluteString)
            XCTAssertFalse(r.url!.absoluteString.contains("token"), r.url!.absoluteString)
        }
        XCTAssertTrue(Stub.seen.contains { $0.url?.host == "plex.tv" && $0.value(forHTTPHeaderField: "X-Plex-Token") == "account-token" })
    }

    /// Off TV (these tests run on macOS) a plain-http address, the LAN one or one plex.tv lists, is probed only when plex.tv
    /// says this device is on the server's network: elsewhere whoever answers there would get the token in the clear. It is
    /// still saved, so the device finds it again at home.
    func testPlainHTTPOnlyOnTheServersNetwork() async throws {
        let attic = try await PlexAuth.servers(token: "account-token", clientID: "test", only: "m4")   // publicAddressMatches true
        XCTAssertEqual(attic.first?.baseURL.absoluteString, "http://192.168.1.4:32400")
        for (id, https, http) in [("m6", "https://loft.invalid:32400", "http://192.168.1.16:32400"),     // false: the LAN one
                                  ("m7", "https://shed.invalid:32400", "http://203.0.113.17:32400")] {   // missing: a listed one
            Stub.seen = []
            let s = try await PlexAuth.servers(token: "account-token", clientID: "test", only: id).first
            XCTAssertEqual(s?.baseURL.absoluteString, https, id)
            XCTAssertEqual(s?.addresses.map(\.url.absoluteString), [https, http], id)
            XCTAssertFalse(Stub.seen.contains { $0.url?.scheme == "http" }, id)   // never asked
        }
        // A typed plain-http address that answers as Loft only names it: the link goes to Loft's https address.
        let loft = try await server("http://192.168.1.16:32400")
        XCTAssertEqual(loft.baseURL.absoluteString, "https://loft.invalid:32400")
        XCTAssertEqual(loft.addresses.map(\.url.absoluteString), ["https://loft.invalid:32400", "http://192.168.1.16:32400"])
    }

    /// `onServerNetwork` is true only when plex.tv says so for that machine; anything else is false.
    func testOnServerNetworkFailsClosed() async {
        func ask(_ id: String, token: String? = "account-token") async -> Bool {
            await PlexAuth.onServerNetwork(machineID: id, token: token, clientID: "test")
        }
        let noToken = await ask("m4", token: nil)
        XCTAssertFalse(noToken); XCTAssertTrue(Stub.seen.isEmpty)
        let attic = await ask("m4"), loft = await ask("m6"), shed = await ask("m7"), unknown = await ask("m2")
        XCTAssertTrue(attic); XCTAssertFalse(loft); XCTAssertFalse(shed); XCTAssertFalse(unknown)
        Stub.routes["plex.tv/api/v2/resources"] = "<html>maintenance</html>"
        let garbled = await ask("m4")
        XCTAssertFalse(garbled)
        Stub.routes["plex.tv/api/v2/resources"] = nil   // plex.tv unreachable
        let offline = await ask("m4")
        XCTAssertFalse(offline)
    }
}
