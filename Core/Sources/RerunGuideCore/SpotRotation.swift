import Foundation

/// The house-spot rotation: every `every`th trailer break opens with one of Soyboi's spots, and the spots take turns. A break is counted once, by id, so
/// tuning away and back to the same break neither counts it twice nor replays the spot. The app persists it, so the
/// count carries across launches.
public struct SpotRotation: Codable, Equatable, Sendable {
    public static let every = 15
    public private(set) var breaks = 0
    public private(set) var lastBreak: String?

    public init() {}

    /// Count a break that is starting; true when it's this break's turn for the spot.
    public mutating func next(_ breakID: String) -> Bool {
        guard breakID != lastBreak else { return false }
        lastBreak = breakID; breaks += 1
        return breaks % Self.every == 0
    }

    /// Which of `count` spots the break `next` just said yes to plays: the first, then the second, … then round again.
    public func pick(of count: Int) -> Int { count > 0 ? max(0, breaks / Self.every - 1) % count : 0 }
}
