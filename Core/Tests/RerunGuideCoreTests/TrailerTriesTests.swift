import XCTest
@testable import RerunGuideCore

final class TrailerTriesTests: XCTestCase {
    private let films = ["a", "b", "c"].map { MediaItem(id: $0, kind: .movie, title: $0, runtime: 90 * 60) }
    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)

    /// Each candidate once, in order, then none: the station card.
    func testEachCandidateOnce() {
        var t = TrailerTries()
        var order: [String] = []
        while let m = t.next(films, at: t0) { order.append(m.id); t = t.adding(m, at: t0) }
        XCTAssertEqual(order, ["a", "b", "c"])
        XCTAssertEqual(t.tried, ["a", "b", "c"])
        XCTAssertNil(TrailerTries().next([], at: t0))
    }

    /// The clock starts at the first try: a failure 19 s in still gets the next trailer, one 20 s in gets none.
    func testBudget() {
        let t = TrailerTries().adding(films[0], at: t0)
        XCTAssertEqual(t.since, t0)
        XCTAssertEqual(t.next(films, at: t0 + 19)?.id, "b")
        let t2 = t.adding(films[1], at: t0 + 19)
        XCTAssertEqual(t2.since, t0)   // later tries keep the first one's clock
        XCTAssertNil(t2.next(films, at: t0 + TrailerTries.budget))
        XCTAssertEqual(t2.restarted().next(films, at: t0 + 90)?.id, "c")   // one that had played: a new clock, the same tries
        XCTAssertEqual(TrailerTries.startTimeout, 10)
        XCTAssertEqual(TrailerTries.budget, 20)
    }
}
