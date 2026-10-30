import XCTest
@testable import RerunGuideCore

final class LocalDiscoveryTests: XCTestCase {
    func testJellyfinReply() {
        let s = LocalDiscovery.jellyfin(Data(#"{"Address":"http://192.168.1.5:8096","Id":"abc","Name":"Den","EndpointAddress":null}"#.utf8), ip: "192.168.1.5")
        XCTAssertEqual(s?.id, "abc")
        XCTAssertEqual(s?.name, "Den")
        XCTAssertEqual(s?.baseURL.absoluteString, "http://192.168.1.5:8096")
        XCTAssertEqual(s?.accessToken, "")
        XCTAssertNil(LocalDiscovery.jellyfin(Data("nope".utf8), ip: "192.168.1.5"))
        // Only the sender counts: a reply naming another host is reached at the IP it came from (a forged reply can't
        // redirect the password; a Docker server naming its container address is found at the host).
        func at(_ address: String, _ ip: String) -> String? {
            LocalDiscovery.jellyfin(Data(#"{"Address":"\#(address)","Id":"x","Name":"X"}"#.utf8), ip: ip)?.baseURL.absoluteString
        }
        XCTAssertEqual(at("https://evil.example.net:8920", "192.168.1.9"), "http://192.168.1.9:8096")
        XCTAssertEqual(at("http://172.17.0.5:8096", "192.168.1.5"), "http://192.168.1.5:8096")
        XCTAssertEqual(at("https://10.0.0.2:8920", "192.168.1.5"), "https://192.168.1.5:8920")
        XCTAssertEqual(at("http://jellyfin.local:8096/jellyfin", "192.168.1.5"), "http://192.168.1.5:8096/jellyfin")
        XCTAssertEqual(at("http://jellyfin.local", "192.168.1.5"), "http://192.168.1.5:8096")
        XCTAssertEqual(at("https://jellyfin.example.com", "192.168.1.5"), "http://192.168.1.5:8096")   // a reverse proxy's URL
        XCTAssertEqual(at("HTTP://192.168.1.5:8096", "192.168.1.5"), "http://192.168.1.5:8096")
        XCTAssertEqual(at("http://u:p@192.168.1.5:8096/p?q#f", "192.168.1.5"), "http://192.168.1.5:8096/p")
        XCTAssertNil(at("file:///etc/passwd", "192.168.1.5"))
        XCTAssertNil(at("http:foo", "192.168.1.5"))
        XCTAssertEqual(at("http://jellyfin.local:8097/jf", "192.168.1.5"), "http://192.168.1.5:8097/jf")
        XCTAssertEqual(at("http://media.4k:8097", "192.168.1.5"), "http://192.168.1.5:8097")
        XCTAssertEqual(at("https://[::1]:8920/jf", "192.168.1.5"), "https://192.168.1.5:8920/jf")
        XCTAssertEqual(at("http://192.168.1.5:8096/a%20b", "192.168.1.5"), "http://192.168.1.5:8096/a%20b")
        XCTAssertNil(at("http://:8096", "192.168.1.5"))
        XCTAssertNil(at("http://host:abc", "192.168.1.5"))
    }

    func testPlexGDMReply() {
        let reply = "HTTP/1.0 200 OK\r\nContent-Type: plex/media-server\r\nName: Living Room\r\nPort: 32401\r\nResource-Identifier: r1\r\nVersion: 1.40\r\n"
        let s = LocalDiscovery.plex(Data(reply.utf8), ip: "10.0.0.9")
        XCTAssertEqual(s?.id, "r1")
        XCTAssertEqual(s?.name, "Living Room")
        XCTAssertEqual(s?.baseURL.absoluteString, "http://10.0.0.9:32401")
        XCTAssertEqual(LocalDiscovery.plex(Data("HTTP/1.0 200 OK\r\nResource-Identifier: r2\r\n".utf8), ip: "10.0.0.9")?.baseURL.port, 32400)
        XCTAssertNil(LocalDiscovery.plex(Data("HTTP/1.0 404 Not Found\r\n".utf8), ip: "10.0.0.9"))
        XCTAssertNil(LocalDiscovery.plex(Data("HTTP/1.0 200 OK\r\nPort: 1@evil.com\r\nResource-Identifier: r3\r\n".utf8), ip: "10.0.0.9"))   // a Port can't move the host
    }
}
