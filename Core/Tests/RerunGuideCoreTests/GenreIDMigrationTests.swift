import XCTest
@testable import RerunGuideCore

final class GenreIDMigrationTests: XCTestCase {
    /// A mixed shelf splits: the Kids profile's hide covers both halves, the rename goes to the bigger half, the stale
    /// id is gone, and a second run changes nothing.
    func testSplitCarriesHideAndEdit() {
        var hidden: [Set<String>] = [["genre:Horror", "genre:Comedy"], []]
        var edits: [String: String] = ["genre:Horror": "Creep Show"]
        let ids = ["genre:Horror:movies", "genre:Horror:tv", "genre:Comedy", "lead:new-movies"]
        let r = GenreIDMigration.migrate(currentIDs: ids, sizes: ["genre:Horror:movies": 40, "genre:Horror:tv": 12],
                                         hidden: &hidden, edits: &edits)
        XCTAssertTrue(r.changed); XCTAssertTrue(r.editMoved)
        XCTAssertEqual(hidden[0], ["genre:Horror:movies", "genre:Horror:tv", "genre:Comedy"])
        XCTAssertEqual(hidden[1], [])
        XCTAssertEqual(edits, ["genre:Horror:movies": "Creep Show"])
        let again = GenreIDMigration.migrate(currentIDs: ids, sizes: [:], hidden: &hidden, edits: &edits)
        XCTAssertFalse(again.changed); XCTAssertFalse(again.editMoved)
    }

    /// Halves merge back: hidden if either half was, the first stale edit wins, and an edit already on the merged
    /// channel is kept rather than overwritten.
    func testMergeKeepsHideAndExistingEdit() {
        var hidden: [Set<String>] = [["genre:Drama:tv"]]
        var edits: [String: String] = ["genre:Drama:movies": "Old", "genre:Drama": "Kept"]
        let r = GenreIDMigration.migrate(currentIDs: ["genre:Drama"], sizes: ["genre:Drama": 9], hidden: &hidden, edits: &edits)
        XCTAssertTrue(r.changed); XCTAssertFalse(r.editMoved)
        XCTAssertEqual(hidden[0], ["genre:Drama"])
        XCTAssertEqual(edits, ["genre:Drama": "Kept"])
    }
}
