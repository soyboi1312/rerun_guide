// Cross-platform parity fixture: what the Swift Core produces for the demo library, so the Kotlin port can be checked
// value for value (the schedule must agree on every device, Apple TV or Google TV).
import Foundation
@testable import RerunGuideCore

var tz = TimeZone(identifier: "America/Los_Angeles")!
var cal = Calendar(identifier: .gregorian); cal.timeZone = tz
let now = cal.date(from: DateComponents(year: 2026, month: 10, day: 24, hour: 20))!

var out: [String: Any] = [:]
out["timeZone"] = tz.identifier
out["now"] = now.timeIntervalSince1970

var g = SeededGenerator(seed: "parity|2026-10-24")
out["seeded"] = (0..<8).map { _ in String(g.next()) }
var g2 = SeededGenerator(seed: "shuffle-check")
out["shuffle20"] = Array(0..<20).shuffled(using: &g2)
var g3 = SeededGenerator(seed: "bounded")
out["bounded"] = (0..<12).map { i in Int.random(in: 0..<(i + 3) * 7, using: &g3) }
out["fnv1a"] = ["", "a", "genre:Comedy:tv", "retro-binge-dial", "Rerun Guide ✓"].map { ["s": $0, "h": String(StableHash.fnv1a($0))] }

// Films: addedAt pinned to `now` as the ports pin it (the demo's follows the wall clock), and every third one watched
// (m3, m6, ...) so Unwatched and its rule are checked too. The demo server itself has none watched.
let lib = DemoMediaServer.items.map { m in
    guard m.kind == .movie, let n = Int(m.id.dropFirst()) else { return m }
    return MediaItem(id: m.id, kind: m.kind, title: m.title, seriesTitle: m.seriesTitle, seriesID: m.seriesID, season: m.season,
                     episode: m.episode, year: m.year, genres: m.genres, runtime: m.runtime, artworkPath: m.artworkPath,
                     backdropPath: m.backdropPath, overview: m.overview, contentRating: m.contentRating, directors: m.directors,
                     actors: m.actors, libraryID: m.libraryID, addedAt: now.addingTimeInterval(-Double(n * 6) * 86_400), watched: n % 3 == 0)
}
out["demoItems"] = lib.map { ["id": $0.id, "kind": $0.kind == .movie ? "movie" : "episode", "title": $0.title, "runtime": $0.runtime,
                              "year": $0.year as Any, "genres": $0.genres, "seriesID": $0.seriesID as Any, "watched": $0.watched] }
let defs = ChannelBuilder.automaticChannels(from: lib, now: now)
out["automatic"] = defs.map { ["id": $0.id, "number": $0.number, "name": $0.name, "tag": $0.tag, "rule": $0.rule.map(\.label), "order": $0.order.rawValue] }
let chans = ChannelBuilder.channels(defs, in: lib, now: now) + [Channel.scrambled]
out["channels"] = chans.map { ch -> [String: Any] in
    var days: [[String: Any]] = []
    for d in 0..<3 {
        let day = cal.date(byAdding: .day, value: d, to: cal.startOfDay(for: now))!
        let slots = GuideGenerator.schedule(for: ch, day: day, calendar: cal)
        days.append(["day": day.timeIntervalSince1970, "slots": slots.map { ["item": $0.item.id, "start": $0.start.timeIntervalSince1970, "end": $0.end.timeIntervalSince1970] }])
    }
    return ["id": ch.id, "items": ch.items.map(\.id), "days": days]
}
// Short programs sharing a half hour, with fractional runtimes as servers report them: in order and shuffled.
let shorts = [130.7, 245.2, 480.4, 540.9, 701.0, 150.0, 453.3, 720.0, 780.0, 1320.0].enumerated().map { i, r in
    MediaItem(id: "s\(i)", kind: .episode, title: "S\(i)", seriesTitle: "Shorts", seriesID: "shorts", season: 1, episode: i + 1, runtime: r)
}
out["packed"] = [Channel(id: "shorts", number: 2, name: "Shorts", items: shorts, order: .inOrder),
                 Channel(id: "shorts-mix", number: 3, name: "Mix", items: shorts, order: .shuffle)].map { ch in
    (0..<3).map { d in
        GuideGenerator.schedule(for: ch, day: cal.date(byAdding: .day, value: d, to: cal.startOfDay(for: now))!, calendar: cal)
            .map { [$0.item.id, $0.start.timeIntervalSince1970, $0.end.timeIntervalSince1970] as [Any] }
    }
}
var b = Bouncer(stage: (1920, 1080), logo: (400, 400), start: (300, 200))
var bounce: [[Double]] = []
for i in 1...(60 * 60 * 3) { b.step(1.0 / 60); if i % 600 == 0 { bounce.append([b.time, b.x, b.y, b.vx, b.vy, Double(b.corners)]) } }
out["bouncer"] = bounce
var rot = SpotRotation(); out["spotRotation"] = (["a", "b", "a", "c", "d", "d"] + (1...30).map { "n\($0)" }).map { rot.next($0) }

let data = try JSONSerialization.data(withJSONObject: out, options: [.prettyPrinted, .sortedKeys])
try data.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
print("wrote", data.count, "bytes;", chans.count, "channels")
