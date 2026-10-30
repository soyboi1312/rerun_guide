import XCTest
@testable import RerunGuideCore

final class ScrambledTests: XCTestCase {
    /// Automatic numbering steps over 99, so the scrambled channel always has its number to itself.
    func testNinetyNineIsReserved() {
        let numbers = ChannelBuilder.automaticChannels(from: DemoMediaServer.items).map(\.number)
        XCTAssertFalse(numbers.contains(Channel.scrambledNumber))
        XCTAssertEqual(numbers, numbers.sorted(), "numbers stay increasing around the gap")
        XCTAssertEqual(Set(numbers).count, numbers.count)
    }

    /// Hour-long slots with no trailer break, a few programs to rotate, and nothing the scheduler can't tile.
    func testScrambledChannelShape() {
        let ch = Channel.scrambled
        XCTAssertEqual(ch.number, 99)
        XCTAssertTrue(ch.isScrambled)
        XCTAssertFalse(ch.definition.trailerBreaks)
        XCTAssertGreaterThanOrEqual(ch.items.count, 4)
        for s in GuideGenerator.schedule(for: ch, day: Date(timeIntervalSince1970: 1_800_000_000)) {
            XCTAssertEqual(s.duration, 3600, "\(s.item.title) should fill exactly one hour")
            XCTAssertEqual(s.duration, s.item.runtime, "no pad, so never a break on channel 99")
        }
    }
}
