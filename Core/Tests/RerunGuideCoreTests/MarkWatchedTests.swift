import XCTest
@testable import RerunGuideCore

/// `markWatched`: the one request each server's own "mark as watched" makes, for the episode itself (not its show). The
/// servers are stubbed with `PlexAddressTests.Stub`.
final class MarkWatchedTests: XCTestCase {
    typealias Stub = PlexAddressTests.Stub
    let episode = MediaItem(id: "ep7", kind: .episode, title: "Pilot", seriesTitle: "Show", seriesID: "show3", runtime: 1320)

    override func setUp() {
        URLProtocol.registerClass(Stub.self)
        Stub.seen = []
        Stub.routes = ["plex.test/:/scrobble": "", "jf.test/Users/u1/PlayedItems/ep7": "{}"]
    }
    override func tearDown() { URLProtocol.unregisterClass(Stub.self) }

    func testPlexScrobblesTheRatingKey() async throws {
        try await PlexClient(baseURL: URL(string: "https://plex.test:32400")!, token: "tok", clientID: "dev").markWatched(episode)
        XCTAssertEqual(Stub.seen.count, 1)
        let r = try XCTUnwrap(Stub.seen.first), url = try XCTUnwrap(r.url)
        XCTAssertEqual(r.httpMethod, "PUT")
        XCTAssertEqual(url.path, "/:/scrobble")
        XCTAssertEqual(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
                       [URLQueryItem(name: "identifier", value: "com.plexapp.plugins.library"), URLQueryItem(name: "key", value: "ep7")])
        XCTAssertEqual(r.value(forHTTPHeaderField: "X-Plex-Token"), "tok")
        XCTAssertEqual(r.value(forHTTPHeaderField: "X-Plex-Client-Identifier"), "dev")
    }

    func testJellyfinAndEmbyPostPlayedItem() async throws {
        for kind in [ServerKind.jellyfin, .emby] {
            Stub.seen = []
            try await JellyfinClient(baseURL: URL(string: "https://jf.test")!, token: "tok", userID: "u1", deviceID: "dev", kind: kind)
                .markWatched(episode)
            XCTAssertEqual(Stub.seen.count, 1, "\(kind)")
            let r = try XCTUnwrap(Stub.seen.first), url = try XCTUnwrap(r.url)
            XCTAssertEqual(r.httpMethod, "POST", "\(kind)")
            XCTAssertEqual(url.path, "/Users/u1/PlayedItems/ep7", "\(kind)")
            XCTAssertEqual(url.query ?? "", "", "\(kind)")
            XCTAssertEqual(r.value(forHTTPHeaderField: "X-Emby-Token"), "tok", "\(kind)")
            for h in ["Authorization", "X-Emby-Authorization"] {
                XCTAssertTrue(r.value(forHTTPHeaderField: h)?.contains(#"Token="tok""#) == true, "\(kind) \(h)")
            }
        }
    }

    func testDemoAsksNothing() async throws {
        try await DemoMediaServer().markWatched(episode)
        XCTAssertTrue(Stub.seen.isEmpty)
    }
}
