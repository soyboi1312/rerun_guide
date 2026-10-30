import Foundation

/// A trailer break's tries: its candidates in order, each tried at most once, and none once `budget` seconds have passed
/// since the first, so a server whose trailers all fail gives the station card the rest of the break.
public struct TrailerTries: Equatable, Sendable {
    /// A trailer that shows no first frame within this many seconds is given up for the next one.
    public static let startTimeout: TimeInterval = 10
    /// No new try starts this many seconds after the first one.
    /// ponytail: a try started just inside it still gets its whole start timeout (30 s in all at worst); clamp the
    /// player's start timeout to what is left of the budget if that shows.
    public static let budget: TimeInterval = 20
    public private(set) var tried: [String] = []
    public private(set) var since: Date?

    public init() {}

    /// The first of `candidates` not tried yet; nil once each was, or once the budget is spent.
    public func next(_ candidates: [MediaItem], at now: Date = .now) -> MediaItem? {
        if let since, now.timeIntervalSince(since) >= Self.budget { return nil }
        return candidates.first { !tried.contains($0.id) }
    }

    /// These tries with `item` tried at `now` (the first one starts the clock).
    public func adding(_ item: MediaItem, at now: Date = .now) -> TrailerTries {
        var t = self
        t.tried.append(item.id); t.since = since ?? now
        return t
    }

    /// These tries with the clock stopped, for a trailer that had started before it failed: the time it played was no
    /// failed attempt, so the next try starts the clock again. Each candidate is still tried once.
    /// ponytail: the stall before that failure isn't counted either; still bounded, by the candidates.
    public func restarted() -> TrailerTries { var t = self; t.since = nil; return t }
}
