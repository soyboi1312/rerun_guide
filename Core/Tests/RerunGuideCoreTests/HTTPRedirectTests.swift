import XCTest
@testable import RerunGuideCore

/// `HTTP.data` refuses an https → http redirect (the token header would follow it in the clear) and follows any other.
final class HTTPRedirectTests: XCTestCase {
    /// https://a.test/up → https://b.test/ok, https://a.test/down → http://b.test/ok; b.test/ok answers "ok". Records what was asked.
    final class Hops: URLProtocol {
        nonisolated(unsafe) static var seen: [URL] = []
        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func stopLoading() {}
        override func startLoading() {
            let url = request.url!
            Self.seen.append(url)
            let hop = ["/up": "https://b.test/ok", "/down": "http://b.test/ok"][url.path]
            let response = HTTPURLResponse(url: url, statusCode: hop == nil ? 200 : 302, httpVersion: nil,
                                           headerFields: hop.map { ["Location": $0] })!
            if let hop { client?.urlProtocol(self, wasRedirectedTo: URLRequest(url: URL(string: hop)!), redirectResponse: response) }
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data((hop == nil ? "ok" : "moved").utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    override func setUp() { URLProtocol.registerClass(Hops.self); Hops.seen = [] }
    override func tearDown() { URLProtocol.unregisterClass(Hops.self) }

    func get(_ url: String) async throws -> String {
        var r = URLRequest(url: URL(string: url)!)
        r.setValue("secret", forHTTPHeaderField: "X-Plex-Token")
        return String(decoding: try await HTTP.data(r), as: UTF8.self)
    }

    func testHttpsToHttpRedirectIsRefused() async throws {
        do { _ = try await get("https://a.test/down"); XCTFail("followed https → http") }
        catch MediaServerError.http(let code, _) { XCTAssertEqual(code, 302) }
        XCTAssertEqual(Hops.seen.map(\.absoluteString), ["https://a.test/down"])
    }

    func testOtherRedirectsAreFollowed() async throws {
        let body = try await get("https://a.test/up")
        XCTAssertEqual(body, "ok")
        XCTAssertEqual(Hops.seen.map(\.absoluteString), ["https://a.test/up", "https://b.test/ok"])
    }
}
