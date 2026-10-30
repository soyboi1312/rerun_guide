import XCTest
@testable import RerunGuideCore

final class BouncerTests: XCTestCase {
    /// Twenty simulated minutes at 60 Hz from several starts and speed ratios, including ones a plain bounce would
    /// never corner on: the logo stays on the stage and never goes 120 s without a corner.
    func testCornerAtLeastEveryTwoMinutes() {
        let stage = (w: 1920.0, h: 1080.0), logo = (w: 420.0, h: 300.0)
        let cases: [(speed: (x: Double, y: Double), start: (x: Double, y: Double))] = [
            ((110, 80), (0, 0)), ((110, 80), (700, 333)), ((100, 100), (10, 500)),      // equal speeds on a 1500×780 box: no natural corners
            ((150, 61), (1200, 90)), ((90, 130), (333, 777)), ((110, 80), (1500, 780)),
        ]
        for c in cases {
            var b = Bouncer(stage: stage, logo: logo, speed: c.speed, start: c.start)
            var worstGap = 0.0, last = 0.0
            for _ in 0..<(20 * 60 * 60) {
                b.step(1.0 / 60)
                XCTAssertTrue(b.x >= 0 && b.x <= stage.w - logo.w && b.y >= 0 && b.y <= stage.h - logo.h, "off the stage at t=\(b.time)")
                if b.hitCorner { worstGap = max(worstGap, b.time - last); last = b.time }
            }
            worstGap = max(worstGap, b.time - last)
            XCTAssertLessThanOrEqual(worstGap, 120, "speed \(c.speed) start \(c.start): \(worstGap)s between corners")
            XCTAssertGreaterThanOrEqual(b.corners, 10, "speed \(c.speed) start \(c.start): only \(b.corners) corners in 20 min")
        }
    }

    /// Cruising speed is restored after a steered approach, so the logo doesn't stay slowed for good.
    func testSpeedRestoredAfterCorner() {
        var b = Bouncer(stage: (1920, 1080), logo: (420, 300), speed: (100, 100), start: (0, 0))   // equal speeds: no natural corners within the deadline
        var sawSteering = false
        for _ in 0..<(10 * 60 * 60) {
            b.step(1.0 / 60)
            if abs(b.vy) < 99 { sawSteering = true }
            if b.hitCorner { XCTAssertEqual(abs(b.vy), 100, accuracy: 0.001); XCTAssertEqual(abs(b.vx), 100, accuracy: 0.001) }
        }
        XCTAssertTrue(sawSteering, "the deadline never had to steer: the test speeds corner naturally, pick others")
    }
}
