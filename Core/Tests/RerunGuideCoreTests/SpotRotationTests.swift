import XCTest
@testable import RerunGuideCore

final class SpotRotationTests: XCTestCase {
    /// Breaks 15, 30 and 45 get the spot; seeing the same break again doesn't count, and the count survives the round
    /// trip through UserDefaults' JSON.
    func testOneBreakIn15() throws {
        let n = SpotRotation.every
        XCTAssertEqual(n, 15)
        var r = SpotRotation()
        var hits: [Int] = []
        for i in 1...(3 * n) {
            if r.next("ch|\(i)") { hits.append(i) }
            XCTAssertFalse(r.next("ch|\(i)"), "a retune into break \(i) must not count or replay")
        }
        XCTAssertEqual(hits, [n, 2 * n, 3 * n])
        var restored = try JSONDecoder().decode(SpotRotation.self, from: JSONEncoder().encode(r))
        XCTAssertEqual(restored, r)
        XCTAssertEqual((3 * n + 1...4 * n).filter { restored.next("ch|\($0)") }, [4 * n])
    }

    /// Two spots take turns, one per spot break: 15 → first, 30 → second, 45 → first.
    func testSpotsTakeTurns() {
        var r = SpotRotation()
        var played: [Int] = []
        for i in 1...(3 * SpotRotation.every) where r.next("ch|\(i)") { played.append(r.pick(of: 2)) }
        XCTAssertEqual(played, [0, 1, 0])
        XCTAssertEqual(SpotRotation().pick(of: 2), 0)   // the -spotOverride path, before any break counted
    }
}
