import XCTest
@testable import RerunGuideCore

final class EmbyConnectTests: XCTestCase {
    /// Connect's server list → server rows: LAN address first, then remote; the access key rides in `accessToken`; servers
    /// without a key, an id or any usable address are dropped.
    func testServerRows() throws {
        let json = """
        [{"Name":"Basement","Url":"https://emby.example.net:8920/","LocalAddress":"http://192.0.2.10:8096","AccessKey":"k1","SystemId":"s1"},
         {"Name":"Remote only","Url":"https://far.example.net","LocalAddress":"","AccessKey":"k2","SystemId":"s2"},
         {"Name":"No key","Url":"https://x.example.net","AccessKey":"","SystemId":"s3"},
         {"Name":"No address","Url":"","LocalAddress":null,"AccessKey":"k4","SystemId":"s4"}]
        """
        let rows = EmbyConnect.rows(try JSONDecoder().decode([EmbyConnect.Server].self, from: Data(json.utf8)))
        XCTAssertEqual(rows.map(\.id), ["s1", "s2"])
        XCTAssertEqual(rows[0].baseURL.absoluteString, "http://192.0.2.10:8096")
        XCTAssertEqual(rows[0].addresses.map(\.url.absoluteString), ["http://192.0.2.10:8096", "https://emby.example.net:8920"])
        XCTAssertEqual(rows[0].addresses.map(\.local), [true, false])
        XCTAssertEqual(rows[0].accessToken, "k1")
        XCTAssertEqual(rows[1].baseURL.absoluteString, "https://far.example.net")
        XCTAssertEqual(rows[1].name, "Remote only")
    }
}
