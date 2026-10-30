import XCTest
@testable import RerunGuideCore

final class GuideGeneratorTests: XCTestCase {
    let cal: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "America/Los_Angeles")!
        return c
    }()
    let block = GuideGenerator.blockLength
    let demo = DemoMediaServer.items

    func date(_ y: Int, _ m: Int, _ d: Int, _ h: Int = 0, _ min: Int = 0) -> Date {
        cal.date(from: DateComponents(year: y, month: m, day: d, hour: h, minute: min))!
    }
    func channel(_ id: String, minutes: Int...) -> Channel {
        Channel(id: id, number: 2, name: id, items: minutes.enumerated().map {
            MediaItem(id: "\(id)-\($0.offset)", kind: .movie, title: "\($0.element)m", runtime: TimeInterval($0.element * 60))
        })
    }
    var lineup: [Channel] { ChannelBuilder.channels(from: demo) }
    var comedy: Channel { lineup.first { $0.id == "genre:Comedy:tv" }! }

    func assertTiles(_ slots: [Slot], _ day: Date, file: StaticString = #filePath, line: UInt = #line) {
        let start = cal.startOfDay(for: day), end = cal.startOfDay(for: cal.date(byAdding: .day, value: 1, to: start)!)
        XCTAssertEqual(slots.first?.start, start, file: file, line: line)
        XCTAssertEqual(slots.last?.end, end, file: file, line: line)
        for (a, b) in zip(slots, slots.dropFirst()) { XCTAssertEqual(a.end, b.start, file: file, line: line) }
        // On the half-hour grid, except short programs sharing a half hour: those stay inside it.
        func offset(_ d: Date) -> TimeInterval { d.timeIntervalSince(start) }
        for s in slots.dropLast() {
            XCTAssertGreaterThan(s.duration, 0, file: file, line: line)
            let half = (offset(s.start) / block).rounded(.down)
            if offset(s.start).truncatingRemainder(dividingBy: block) != 0 || offset(s.end).truncatingRemainder(dividingBy: block) != 0 {
                XCTAssertLessThanOrEqual(offset(s.end), (half + 1) * block, "\(s.item.title) crosses a half hour", file: file, line: line)
            }
        }
        XCTAssertGreaterThan(slots.last!.duration, 0, file: file, line: line)
    }

    // MARK: Scheduler

    func testDeterministicPerChannelDay() {
        XCTAssertGreaterThanOrEqual(comedy.items.count, 8)
        let a = GuideGenerator.schedule(for: comedy, day: date(2026, 1, 5), calendar: cal)
        let b = GuideGenerator.schedule(for: comedy, day: date(2026, 1, 5, 13, 37), calendar: cal)
        XCTAssertEqual(a, b)
        let c = GuideGenerator.schedule(for: comedy, day: date(2026, 1, 6), calendar: cal)
        XCTAssertNotEqual(a.map(\.item.id), c.map(\.item.id))
    }

    func testDayTiling() {
        for ch in lineup {
            for d in 0..<5 { assertTiles(GuideGenerator.schedule(for: ch, day: date(2026, 1, 1 + d), calendar: cal), date(2026, 1, 1 + d)) }
        }
        let single = GuideGenerator.schedule(for: channel("x", minutes: 140), day: date(2026, 1, 1), calendar: cal)
        assertTiles(single, date(2026, 1, 1))
        XCTAssertEqual(single.count, 9)                  // 8 × 2.5h, then the 9th film runs to midnight with a 1.5h longer break
        XCTAssertEqual(single.last!.duration, 8 * block)
        // No program is ever cut short: every slot holds its whole item (the leftover before midnight is a break).
        for ch in lineup {
            for d in 0..<14 {
                for s in GuideGenerator.schedule(for: ch, day: date(2026, 1, 1 + d), calendar: cal) {
                    XCTAssertGreaterThanOrEqual(s.duration, s.item.runtime, "\(ch.id) cut \(s.item.title) short")
                }
            }
        }
        // The window is filled with the longest item that fits: a 2h and a 3.5h film, 30 min left after 7 × 3.5h.
        let mixed = Channel(id: "m", number: 2, name: "m", items: [
            MediaItem(id: "long", kind: .movie, title: "Long", runtime: 205 * 60),   // 3.5h slot
            MediaItem(id: "short", kind: .movie, title: "Short", runtime: 100 * 60), // 2h slot
        ], order: .inOrder)
        for d in 0..<7 {
            let day = GuideGenerator.schedule(for: mixed, day: date(2026, 2, 1 + d), calendar: cal)
            assertTiles(day, date(2026, 2, 1 + d))
            XCTAssertTrue(day.allSatisfy { $0.duration >= $0.item.runtime })
        }
    }

    /// Nothing airs twice in a row: not where the day's last slot is re-picked to run to midnight (say, a 3-hour film at
    /// 5:00 PM picked again at 8:30 PM because it alone fills 8:30 to midnight), not a second copy or cut of the same
    /// film, and not across midnight.
    func testNoBackToBackRepeats() {
        let key = GuideGenerator.repeatKey
        var repeats: [String] = []
        func check(_ ch: Channel, days: Int, from start: Date) {
            var previous: Slot?
            for d in 0..<days {
                let day = cal.date(byAdding: .day, value: d, to: start)!
                let slots = GuideGenerator.schedule(for: ch, day: day, calendar: cal)
                assertTiles(slots, day)
                XCTAssertTrue(slots.allSatisfy { $0.duration >= $0.item.runtime })
                for (a, b) in zip(slots, slots.dropFirst()) where key(a.item) == key(b.item) {
                    repeats.append("\(ch.id) day \(d) \(a.item.id)/\(b.item.id)")
                }
                // Across midnight only on a show played in order, or when nothing else fits the last slot: nothing
                // that repeats neither the slot before it nor what opens the next day.
                if let last = previous, key(last.item) == key(slots[0].item), ch.order != .inOrder {
                    let yesterday = GuideGenerator.schedule(for: ch, day: cal.date(byAdding: .day, value: d - 1, to: start)!, calendar: cal)
                    let before = yesterday.dropLast().last.map { key($0.item) }
                    let window = last.end.timeIntervalSince(last.start)
                    if ch.items.contains(where: { key($0) != before && key($0) != key(last.item) && GuideGenerator.slotLength($0) <= window }) {
                        repeats.append("\(ch.id) midnight before day \(d) \(last.item.id)/\(slots[0].item.id)")
                    }
                }
                previous = slots.last
            }
        }
        for n in 2...6 {
            for seed in 0..<40 {
                var rng = SeededGenerator(seed: "repeat|\(n)|\(seed)")
                var items = (0..<n).map { i in
                    MediaItem(id: "f\(i)", kind: .movie, title: "F\(i)", year: 1990, runtime: TimeInterval(80 + Int(rng.next() % 130)) * 60)
                }
                if seed % 2 == 1 {   // a second copy of F0: another item, same title and year
                    items.append(MediaItem(id: "f0-cut", kind: .movie, title: "F0", year: 1990, runtime: items[0].runtime + 1800))
                }
                check(Channel(id: "r\(n)-\(seed)", number: 2, name: "r", items: items, order: seed % 3 == 0 ? .inOrder : .shuffle),
                      days: 4, from: date(2026, 3, 1))
            }
        }
        for ch in lineup where ch.items.count > 1 { check(ch, days: 14, from: date(2026, 1, 1)) }
        XCTAssertEqual(repeats, [])

        // A channel holding nothing but copies of one film still fills the day.
        let cuts = Channel(id: "cuts", number: 2, name: "cuts", items: [
            MediaItem(id: "a", kind: .movie, title: "Alien", year: 1979, runtime: 116 * 60),
            MediaItem(id: "b", kind: .movie, title: "Alien", year: 1979, runtime: 115 * 60),
        ])
        let day = GuideGenerator.schedule(for: cuts, day: date(2026, 3, 1), calendar: cal)
        assertTiles(day, date(2026, 3, 1))
        XCTAssertEqual(day.count, 12)   // 2h slots back to back
        // ...even next to an item too long to ever air, instead of one slot with a 22-hour break.
        let stuck = Channel(id: "stuck", number: 2, name: "stuck", items: [cuts.items[0],
            MediaItem(id: "huge", kind: .movie, title: "Huge", year: 1990, runtime: 30 * 3600)])
        for d in 0..<4 {
            let slots = GuideGenerator.schedule(for: stuck, day: date(2026, 1, 1 + d), calendar: cal)
            assertTiles(slots, date(2026, 1, 1 + d))
            XCTAssertEqual(slots.count, 12)
        }
        // Where a DST change skips midnight (Cairo, 2024-04-26), one day ends exactly where the next begins.
        var cairo = Calendar(identifier: .gregorian); cairo.timeZone = TimeZone(identifier: "Africa/Cairo")!
        for ch in lineup.prefix(6) {
            let a = GuideGenerator.schedule(for: ch, day: cairo.date(from: DateComponents(year: 2024, month: 4, day: 25, hour: 12))!, calendar: cairo)
            let b = GuideGenerator.schedule(for: ch, day: cairo.date(from: DateComponents(year: 2024, month: 4, day: 26, hour: 12))!, calendar: cairo)
            let c = GuideGenerator.schedule(for: ch, day: cairo.date(from: DateComponents(year: 2024, month: 4, day: 27, hour: 12))!, calendar: cairo)
            XCTAssertEqual(a.last?.end, b.first?.start, ch.id)
            XCTAssertEqual(b.last?.end, c.first?.start, ch.id)
        }
    }

    /// A short program doesn't leave most of its half hour empty: the next ones in order that fit play right after it
    /// (8-minute episodes: three to a half hour, the last keeping the rest), and nothing crosses the half hour.
    func testShortProgramsShareTheirHalfHour() {
        let eps = (1...12).map { MediaItem(id: "e\($0)", kind: .episode, title: "E\($0)", seriesTitle: "Toons", seriesID: "toons",
                                           season: 1, episode: $0, runtime: 8 * 60 + 0.4) }
        let toons = Channel(id: "toons", number: 2, name: "Toons", items: eps, order: .inOrder)
        let slots = GuideGenerator.schedule(for: toons, day: date(2026, 3, 1), calendar: cal)
        assertTiles(slots, date(2026, 3, 1))
        XCTAssertEqual(slots.prefix(3).map(\.duration), [480, 480, 840])
        XCTAssertEqual(slots.count, 48 * 3)
        let order = eps.map(\.id)
        for (a, b) in zip(slots, slots.dropFirst()) {   // still in order, episode after episode
            XCTAssertEqual(order.firstIndex(of: b.item.id), (order.firstIndex(of: a.item.id)! + 1) % order.count)
        }
        XCTAssertTrue(slots.allSatisfy { $0.duration >= $0.item.runtime.rounded(.down) })
        // Up to half its half hour (15 minutes, the pad not counted) shares it: 13-minute episodes go two to a half hour.
        let thirteen = Channel(id: "thirteen", number: 2, name: "Thirteen", items: (1...4).map {
            MediaItem(id: "t\($0)", kind: .episode, title: "T\($0)", seriesTitle: "Thirteen", seriesID: "thirteen", season: 1, episode: $0, runtime: 13 * 60)
        }, order: .inOrder)
        XCTAssertEqual(GuideGenerator.schedule(for: thirteen, day: date(2026, 3, 1), calendar: cal).prefix(2).map(\.duration), [780, 1020])
        // 16 minutes is more than half its half hour: it keeps the slot to itself.
        let longer = Channel(id: "longer", number: 2, name: "Longer", items: (1...4).map {
            MediaItem(id: "l\($0)", kind: .episode, title: "L\($0)", seriesTitle: "Longer", seriesID: "longer", season: 1, episode: $0, runtime: 16 * 60)
        }, order: .inOrder)
        XCTAssertTrue(GuideGenerator.schedule(for: longer, day: date(2026, 3, 1), calendar: cal).allSatisfy { $0.duration == block })
    }

    /// Two cuts of a film are two films (titled apart, free to follow each other); two plain copies are one.
    func testEditionsAreDifferentFilms() {
        XCTAssertEqual(MediaItem.title("Watchmen", edition: "Directors Cut"), "Watchmen (Directors Cut)")
        XCTAssertEqual(MediaItem.title("Watchmen", edition: " "), "Watchmen")
        XCTAssertEqual(MediaItem.title("Watchmen", edition: nil), "Watchmen")
        XCTAssertEqual(MediaItem.edition(inPath: "/films/Watchmen (2009) - {imdb-tt0409459}{edition-Ultimate Version}[Bluray-1080p].mkv"), "Ultimate Version")
        XCTAssertNil(MediaItem.edition(inPath: "/films/Watchmen (2009) - {imdb-tt0409459}[Bluray-1080p].mkv"))
        XCTAssertNil(MediaItem.edition(inPath: nil))
        func film(_ id: String, _ edition: String?) -> MediaItem {
            MediaItem(id: id, kind: .movie, title: MediaItem.title("Watchmen", edition: edition), year: 2009, runtime: 186 * 60)
        }
        let key = GuideGenerator.repeatKey
        XCTAssertNotEqual(key(film("a", nil)), key(film("b", "Directors Cut")))
        XCTAssertEqual(key(film("a", nil)), key(film("c", nil)))   // a 4K copy and a 1080p copy
    }

    func testBlockRounding() {
        XCTAssertEqual(GuideGenerator.schedule(for: channel("a", minutes: 22), day: date(2026, 1, 1), calendar: cal).first?.duration, block)
        XCTAssertEqual(GuideGenerator.schedule(for: channel("b", minutes: 95), day: date(2026, 1, 1), calendar: cal).first?.duration, 4 * block)
        // 30 min + the 3-minute pad spills into a second block (a real guide never jams a 30-min show into one 30-min slot).
        XCTAssertEqual(GuideGenerator.schedule(for: channel("c", minutes: 30), day: date(2026, 1, 1), calendar: cal).first?.duration, 2 * block)
        XCTAssertEqual(GuideGenerator.schedule(for: channel("d", minutes: 27), day: date(2026, 1, 1), calendar: cal).first?.duration, block)
    }

    func testDSTDaysTileMidnightToMidnight() {
        for (day, hours) in [(date(2026, 3, 8), 23.0), (date(2026, 11, 1), 25.0)] {
            let slots = GuideGenerator.schedule(for: comedy, day: day, calendar: cal)
            assertTiles(slots, day)
            XCTAssertEqual(slots.reduce(0) { $0 + $1.duration }, hours * 3600)
        }
    }

    func testWindowSpanningMidnight() {
        let from = date(2026, 1, 5, 23), to = date(2026, 1, 6, 1)
        let slots = GuideGenerator.schedule(for: comedy, from: from, to: to, calendar: cal)
        XCTAssertFalse(slots.isEmpty)
        XCTAssertTrue(slots.allSatisfy { $0.end > from && $0.start < to })
        XCTAssertTrue(slots.contains { $0.start < date(2026, 1, 6) })
        XCTAssertTrue(slots.contains { $0.start >= date(2026, 1, 6) })
        XCTAssertLessThanOrEqual(slots.first!.start, from)
        XCTAssertGreaterThanOrEqual(slots.last!.end, to)
        for (a, b) in zip(slots, slots.dropFirst()) { XCTAssertEqual(a.end, b.start) }
        let full = GuideGenerator.schedule(for: comedy, day: from, calendar: cal) + GuideGenerator.schedule(for: comedy, day: to, calendar: cal)
        XCTAssertEqual(slots, full.filter { $0.end > from && $0.start < to })
    }

    func testStatus() {
        let slot = GuideGenerator.schedule(for: channel("a", minutes: 22), day: date(2026, 1, 1), calendar: cal)[0]
        XCTAssertEqual(slot.duration, block)
        XCTAssertEqual(GuideGenerator.status(of: slot, at: date(2025, 12, 31, 23, 59)), .later)
        XCTAssertEqual(GuideGenerator.status(of: slot, at: slot.start), .live)
        XCTAssertEqual(GuideGenerator.status(of: slot, at: date(2026, 1, 1, 0, 21)), .live)
        XCTAssertEqual(GuideGenerator.status(of: slot, at: date(2026, 1, 1, 0, 22)), .break)
        XCTAssertEqual(GuideGenerator.status(of: slot, at: date(2026, 1, 1, 0, 25)), .break)
        XCTAssertEqual(GuideGenerator.status(of: slot, at: slot.end), .past)
        XCTAssertEqual(GuideGenerator.status(of: slot, at: date(2026, 1, 1, 9)), .past)
    }

    /// `ProgramKey` stores a slot's start; the generator must hand the same slot back from it, on every channel and across midnight.
    func testSlotStartRoundTrip() {
        for ch in lineup {
            let from = date(2026, 1, 5, 22), to = date(2026, 1, 6, 3)
            let slots = GuideGenerator.schedule(for: ch, from: from, to: to, calendar: cal)
            XCTAssertGreaterThan(slots.count, 2)
            for s in slots {
                XCTAssertEqual(GuideGenerator.slot(on: ch, startingAt: s.start, calendar: cal), s)
                XCTAssertEqual(GuideGenerator.slot(on: ch, at: s.start, calendar: cal), s)
            }
        }
        let s = GuideGenerator.schedule(for: comedy, day: date(2026, 1, 5), calendar: cal)[3]
        XCTAssertEqual(GuideGenerator.slot(on: comedy, startingAt: s.start.addingTimeInterval(0.25), calendar: cal), s) // survived JSON
        XCTAssertNil(GuideGenerator.slot(on: comedy, startingAt: s.start.addingTimeInterval(60), calendar: cal))
    }

    func testInOrderPlaysInSequenceAndRotatesDaily() {
        let smh = lineup.first { $0.id == "series:smh" }!
        XCTAssertEqual(smh.order, .inOrder)
        let order = smh.items.map(\.id)
        XCTAssertEqual(smh.items.map { "\($0.season!)-\($0.episode!)" }, ["1-1", "1-2", "1-3", "1-4", "2-1", "2-2", "2-3", "2-4"])
        var firsts = Set<String>()
        for d in 0..<6 {
            let ids = GuideGenerator.schedule(for: smh, day: date(2026, 2, 1 + d), calendar: cal).map(\.item.id)
            XCTAssertEqual(ids.count, 48)                            // 22-min episodes → 48 half-hour slots
            for (a, b) in zip(ids, ids.dropFirst()) {               // every slot is the next episode, wrapping
                XCTAssertEqual(order.firstIndex(of: b), (order.firstIndex(of: a)! + 1) % order.count)
            }
            firsts.insert(ids[0])
        }
        XCTAssertGreaterThan(firsts.count, 1)                       // not every day starts on the same episode
    }

    // MARK: ChannelBuilder

    func testAutomaticLineup() {
        let defs = ChannelBuilder.automaticChannels(from: demo)
        XCTAssertEqual(defs, ChannelBuilder.automaticChannels(from: demo))
        XCTAssertEqual(defs.map(\.id), lineup.map(\.id))            // every automatic channel has something to air

        // Numbers: start at 2, strictly increasing, skipping numbers like a cable lineup.
        XCTAssertEqual(defs.first?.number, 2)
        let nums = defs.map(\.number)
        XCTAssertEqual(nums, nums.sorted()); XCTAssertEqual(Set(nums).count, nums.count)
        XCTAssertTrue(zip(nums, nums.dropFirst()).allSatisfy { (1...3).contains($1 - $0) })
        XCTAssertTrue(zip(nums, nums.dropFirst()).contains { $1 - $0 > 1 })

        // Lead channels first (the demo library is 1978–1999 with movies added over the last ~120 days, none watched →
        // only Just Added and Unwatched).
        XCTAssertEqual(defs.first?.id, "lead:just-added")
        XCTAssertEqual(defs.first?.name, "Just Added")
        XCTAssertEqual(defs[1].id, "lead:unwatched")
        XCTAssertFalse(defs.contains { $0.id == "lead:new-movies" })
        // The rest is scattered, not alphabetical, but identical on every call.
        let restNames = defs.dropFirst().map(\.name)
        XCTAssertNotEqual(restNames, restNames.sorted())
        XCTAssertEqual(restNames, ChannelBuilder.automaticChannels(from: demo).dropFirst().map(\.name))

        func def(_ id: String) -> ChannelDefinition { defs.first { $0.id == id } ?? { XCTFail("missing \(id)"); return defs[0] }() }
        // Split genre with retro names: lots of comedy films and lots of comedy episodes.
        let cm = def("genre:Comedy:movies"), ct = def("genre:Comedy:tv")
        XCTAssertEqual([cm.name, cm.tag], ["Funny Business", "Comedy films, 80s–90s"])
        XCTAssertEqual(cm.rule, [.genre("Comedy"), .years(1980, 1999), .movies])
        XCTAssertEqual([ct.name, ct.tag], ["Studio Audience", "Sitcoms, 80s–90s"])
        XCTAssertEqual(ct.rule, [.genre("Comedy"), .years(1980, 1994), .tv])   // decade floor to the latest year
        XCTAssertFalse(defs.contains { $0.id == "genre:Comedy" })
        // Never mixed: Sci-Fi is films only (a films shelf); Crime is episodes plus two films, too few for a films shelf.
        let sf = def("genre:Sci-Fi:movies"), crime = def("genre:Crime:tv")
        XCTAssertEqual([sf.name, sf.tag], ["Final Frontier", "Sci-fi films, 80s–90s"])
        XCTAssertEqual(sf.rule, [.genre("Sci-Fi"), .years(1980, 1997), .movies])
        XCTAssertEqual(crime.name, "Gumshoe")
        XCTAssertFalse(defs.contains { ["genre:Crime", "genre:Crime:movies", "genre:Sci-Fi", "genre:Sci-Fi:tv"].contains($0.id) })
        XCTAssertTrue(ChannelBuilder.resolve(crime, in: demo).allSatisfy { $0.kind == .episode })
        for d in defs {   // no automatic channel airs films and TV together
            let kinds = Set(ChannelBuilder.resolve(d, in: demo).map(\.kind))
            XCTAssertEqual(kinds.count, 1, d.id)
        }
        // Marathons, directors, actors, years.
        let smh = def("series:smh")
        XCTAssertEqual([smh.name, smh.tag], ["Strip Mall Heroes", "Marathon"]); XCTAssertEqual(smh.order, .inOrder)
        XCTAssertEqual(smh.rule, [.series(id: "smh", title: "Strip Mall Heroes")])
        XCTAssertEqual(defs.filter { $0.id.hasPrefix("series:") }.count, 3)
        XCTAssertEqual(Set(defs.filter { $0.id.hasPrefix("director:") }.map(\.name)), ["Dusty Calhoun", "Len Okonkwo", "Marguerite Vole"])
        XCTAssertEqual(def("director:Marguerite Vole").rule, [.director("Marguerite Vole"), .movies]); XCTAssertEqual(def("director:Marguerite Vole").tag, "Director")
        XCTAssertEqual(Set(defs.filter { $0.id.hasPrefix("actor:") }.map(\.name)), ["Dolores Finch", "Kip Navarro", "Rex Tallow"])
        XCTAssertEqual(def("actor:Rex Tallow").rule, [.actor("Rex Tallow"), .movies]); XCTAssertEqual(def("actor:Rex Tallow").tag, "Actor")
        XCTAssertEqual(defs.filter { $0.id.hasPrefix("year:") }.map { [$0.id, $0.name, $0.tag] }, [["year:1985", "Class of ’85", "Films from 1985"]])
        XCTAssertEqual(def("year:1985").rule, [.year(1985), .movies])
        XCTAssertTrue(defs.allSatisfy { !$0.isCustom && $0.trailerBreaks })

        // Thresholds and edge cases.
        XCTAssertEqual(ChannelBuilder.channels(from: demo, minItems: 10).map(\.id).filter { !$0.hasPrefix("genre:") }, ["lead:unwatched"])   // all 20 films
        XCTAssertTrue(ChannelBuilder.channels(from: demo, minItems: 10).contains { $0.id == "genre:Comedy:tv" })   // too few films for a films shelf: TV only
        XCTAssertEqual(ChannelBuilder.channels(from: demo, minItems: 1000), [])
        XCTAssertEqual(ChannelBuilder.channels(from: []), [])
        let noID = (1...4).map { (i: Int) in MediaItem(id: "n\(i)", kind: .episode, title: "e\(i)", seriesTitle: "Orphan Show", episode: i, runtime: 1) }
        XCTAssertEqual(ChannelBuilder.channels(from: noID).map(\.id), ["series:Orphan Show"])
        XCTAssertEqual(ChannelBuilder.channels(from: noID).first?.number, 2)
    }

    func testYearChannelsCappedAtThreeBiggest() {
        var lib: [MediaItem] = []
        for (y, n) in [(1979, 4), (1985, 6), (1988, 5), (1992, 4), (2005, 7)] {
            for i in 0..<n { lib.append(MediaItem(id: "\(y)-\(i)", kind: .movie, title: "\(y) \(i)", year: y, runtime: 5400)) }
        }
        let years = ChannelBuilder.automaticChannels(from: lib).filter { $0.id.hasPrefix("year:") }
        XCTAssertEqual(Set(years.map(\.id)), ["year:1985", "year:1988", "year:2005"])
        XCTAssertEqual(Set(years.map(\.name)), ["Class of ’05", "Class of ’85", "Class of ’88"])   // scattered, so compare as a set
    }

    func testResolveHonoursEveryChip() {
        func items(_ rule: [RuleChip], order: PlayOrder = .shuffle) -> [MediaItem] {
            ChannelBuilder.resolve(ChannelDefinition(id: "t", number: 1, name: "t", tag: "", rule: rule, order: order), in: demo)
        }
        XCTAssertEqual(Set(items([.genre("Horror")]).map(\.id)), ["m1", "m6", "m11", "m15", "m19"])
        let nineties: Set<String> = ["m5", "m7", "m8", "m12", "m17", "m18"]
        let ninetiesEps = Set(demo.filter { $0.seriesID == "smh" || $0.seriesID == "p9" }.map(\.id))
        XCTAssertEqual(Set(items([.years(1990, 1995)]).map(\.id)), nineties.union(ninetiesEps))
        XCTAssertEqual(Set(items([.director("Len Okonkwo")]).map(\.id)), ["m3", "m9", "m14", "m20"])
        XCTAssertEqual(items([.actor("Bea Saltonstall")]).map(\.id), ["m2", "m5", "m9"])
        XCTAssertEqual(items([.series(id: "p9", title: "Precinct 9 at Nine")]).count, 8)
        XCTAssertEqual(items([.series(id: "nope", title: "Precinct 9 at Nine")]).count, 0)       // id wins when the item has one
        XCTAssertEqual(items([.year(1985)]).map(\.id), ["m1", "m4", "m10", "m15"])
        XCTAssertEqual(items([.movies]).count, 20); XCTAssertEqual(items([.tv]).count, 24)
        XCTAssertEqual(items([.genre("Comedy"), .tv, .years(1990, 1999)]).count, 8)             // every chip must match
        XCTAssertEqual(items([.genre("Comedy"), .movies], order: .newestFirst).map(\.year), [1999, 1995, 1992, 1991, 1985, 1985, 1985])
        XCTAssertEqual(items([.series(id: "smh", title: "")], order: .inOrder).map(\.seasonEpisode), ["S1 E1", "S1 E2", "S1 E3", "S1 E4", "S2 E1", "S2 E2", "S2 E3", "S2 E4"])
        XCTAssertEqual(ChannelBuilder.channels([ChannelDefinition(id: "x", number: 1, name: "x", tag: "", rule: [.year(1900)])], in: demo), [])
    }

    func testCandidates() {
        let directors = ChannelBuilder.candidates(.director, in: demo)
        XCTAssertEqual(directors.map { "\($0.name) \($0.count)" }, ["Marguerite Vole 7", "Dusty Calhoun 6", "Len Okonkwo 4", "Priya Marsh 3"])
        let actors = ChannelBuilder.candidates(.actor, in: demo)
        XCTAssertEqual(actors.prefix(3).map { "\($0.name) \($0.count)" }, ["Dolores Finch 9", "Kip Navarro 8", "Rex Tallow 8"])
        XCTAssertEqual(actors.dropFirst(3).map(\.count), Array(repeating: 3, count: 5))
        XCTAssertEqual(ChannelBuilder.candidates(.actor, in: demo, minItems: 4).count, 3)
        let series = ChannelBuilder.candidates(.series, in: demo)
        XCTAssertEqual(series.map { "\($0.name) \($0.count)" }, ["Cul-de-Sac Confidential 8", "Precinct 9 at Nine 8", "Strip Mall Heroes 8"])
        XCTAssertEqual(series.map(\.id), ["cds", "p9", "smh"])
        XCTAssertTrue(directors.allSatisfy { $0.id == nil })
    }

    // MARK: Demo server

    func testDemoLibraryFiltersBySection() async throws {
        let server = DemoMediaServer()
        let sections = try await server.libraries()
        XCTAssertEqual(sections.map(\.id), ["demo-movies", "demo-tv"])
        let all = try await server.libraryItems(in: nil)
        XCTAssertEqual(all.count, 44)
        let movies = try await server.libraryItems(in: [sections[0]])
        XCTAssertEqual(movies.count, 20); XCTAssertTrue(movies.allSatisfy { $0.kind == .movie && $0.libraryID == "demo-movies" })
        let tv = try await server.libraryItems(in: [sections[1]])
        XCTAssertEqual(tv.count, 24); XCTAssertTrue(tv.allSatisfy { $0.kind == .episode && $0.libraryID == "demo-tv" })
        XCTAssertTrue(all.allSatisfy { $0.overview?.isEmpty == false && $0.contentRating != nil && !$0.genres.isEmpty })
        XCTAssertTrue(movies.allSatisfy { $0.directors.count == 1 && $0.actors.count == 2 })
        let trailer = try await server.trailer(for: all[0], session: "test")
        XCTAssertNil(trailer)
    }
}

extension GuideGeneratorTests {
    func testDecadeTagsPast1999() {
        XCTAssertEqual(ChannelBuilder.decadeTag("Action", years: [1955, 2024]), "Action, 50s–2020s")
        XCTAssertEqual(ChannelBuilder.decadeTag("Drama", years: [2001, 2009]), "Drama, 2000s")
        XCTAssertEqual(ChannelBuilder.decadeTag("Comedy", years: [1983, 1991]), "Comedy, 80s–90s")
    }

    func testPeopleAndSeriesChannelsAreCapped() {
        // 30 directors with 4 movies each, 30 series with 4 episodes each.
        var lib: [MediaItem] = []
        for d in 0..<30 {
            for m in 0..<4 { lib.append(MediaItem(id: "d\(d)m\(m)", kind: .movie, title: "M\(d)-\(m)", year: 1990, runtime: 5400, directors: ["Dir \(d)"])) }
            for e in 0..<4 { lib.append(MediaItem(id: "s\(d)e\(e)", kind: .episode, title: "E\(e)", seriesTitle: "Show \(d)", seriesID: "s\(d)", season: 1, episode: e + 1, runtime: 1320)) }
        }
        let defs = ChannelBuilder.automaticChannels(from: lib, maxSeries: 5, maxPeople: 3)
        XCTAssertEqual(defs.filter { $0.tag == "Marathon" }.count, 5)
        XCTAssertEqual(defs.filter { $0.tag == "Director" }.count, 3)
        XCTAssertEqual(ChannelBuilder.automaticChannels(from: lib).filter { $0.tag == "Marathon" }.count, 24)
    }

    func testLeadChannelsAndRelativeChip() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)   // 2027-01-15
        let y = Calendar.current.component(.year, from: now)
        var lib: [MediaItem] = []
        for i in 0..<5 { lib.append(MediaItem(id: "nm\(i)", kind: .movie, title: "New \(i)", year: y - (i % 2), runtime: 5400)) }
        for i in 0..<5 { lib.append(MediaItem(id: "nt\(i)", kind: .episode, title: "Ep \(i)", seriesTitle: "Fresh", seriesID: "fresh", season: 1, episode: i, year: y, runtime: 1320)) }
        for i in 0..<5 { lib.append(MediaItem(id: "old\(i)", kind: .episode, title: "Old \(i)", seriesTitle: "Vintage", seriesID: "vin", year: 1962, runtime: 1500, addedAt: now.addingTimeInterval(-Double(i) * 86_400)) ) }
        let ids = ChannelBuilder.automaticChannels(from: lib, now: now).map(\.id)
        XCTAssertEqual(Array(ids.prefix(5)), ["lead:new-movies", "lead:new-tv", "lead:unwatched", "lead:just-added-tv", "lead:classic-tv"])   // only episodes were added
        XCTAssertEqual(ChannelBuilder.resolve(ChannelDefinition(id: "x", number: 0, name: "", tag: "", rule: [.addedWithin(days: 60)]), in: lib, now: now).count, 5)
        XCTAssertEqual(ChannelBuilder.resolve(ChannelDefinition(id: "x", number: 0, name: "", tag: "", rule: [.addedWithin(days: 2)]), in: lib, now: now).count, 3)   // today, yesterday, two days ago
        XCTAssertEqual(RuleChip.years(1900, 1969).label, "Before 1970")
    }

    /// Unwatched airs the films this account hasn't seen, never a watched one or an episode, right after Just Added,
    /// once there are `minItems` of them.
    func testUnwatchedChannel() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        func film(_ id: String, watched: Bool, added: Date? = nil) -> MediaItem {
            MediaItem(id: id, kind: .movie, title: id, year: 1990, runtime: 5400, addedAt: added, watched: watched)
        }
        let seen = (0..<5).map { film("seen\($0)", watched: true) }, unseen = (0..<4).map { film("new\($0)", watched: false) }
        let eps = (0..<6).map { MediaItem(id: "e\($0)", kind: .episode, title: "E\($0)", seriesTitle: "Show", seriesID: "show", season: 1, episode: $0, runtime: 1320) }
        XCTAssertEqual(RuleChip.unwatched.label, "Unwatched")
        XCTAssertTrue(ChannelBuilder.matches(.unwatched, unseen[0])); XCTAssertFalse(ChannelBuilder.matches(.unwatched, seen[0]))
        let ch = ChannelBuilder.channels(from: seen + unseen + eps).first { $0.id == "lead:unwatched" }
        XCTAssertEqual([ch?.name, ch?.tag], ["Unwatched", "Films you haven’t seen"])
        XCTAssertEqual(ch?.definition.rule, [.unwatched, .movies]); XCTAssertEqual(ch?.order, .shuffle)
        XCTAssertEqual(ch?.items.map(\.id), unseen.map(\.id))
        let day = GuideGenerator.schedule(for: ch!, day: now)
        XCTAssertFalse(day.isEmpty); XCTAssertTrue(day.allSatisfy { $0.item.kind == .movie && !$0.item.watched })
        XCTAssertFalse(ChannelBuilder.channels(from: seen + unseen.dropLast() + eps).contains { $0.id == "lead:unwatched" })   // 3 is too few
        let ids = ChannelBuilder.automaticChannels(from: seen + unseen + (0..<4).map { film("add\($0)", watched: true, added: now) }, now: now).map(\.id)
        XCTAssertEqual(Array(ids.prefix(2)), ["lead:just-added", "lead:unwatched"])
    }

    /// `watched` is required when decoding, so a library cache written before it existed is unreadable (and refetched)
    /// instead of reading as all unwatched. The chip saves as {"unwatched":{}}, like the Kotlin and web ports.
    func testWatchedAndUnwatchedChipCoding() throws {
        let item = MediaItem(id: "m", kind: .movie, title: "M", runtime: 5400, watched: true)
        let data = try JSONEncoder().encode(item)
        XCTAssertEqual(try JSONDecoder().decode(MediaItem.self, from: data), item)
        var old = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        old["watched"] = nil
        XCTAssertThrowsError(try JSONDecoder().decode(MediaItem.self, from: JSONSerialization.data(withJSONObject: old)))
        let chips = try JSONEncoder().encode([RuleChip.unwatched, .movies])
        XCTAssertEqual(String(decoding: chips, as: UTF8.self), #"[{"unwatched":{}},{"movies":{}}]"#)
        XCTAssertEqual(try JSONDecoder().decode([RuleChip].self, from: chips), [.unwatched, .movies])
    }

    /// Horror and thriller shelves stay grown-up: a kids' title tagged Horror (the animated Gremlins series, Gravity Falls)
    /// is left off; an adult animated one (Love, Death & Robots) and an unrated live-action one stay. Other shelves keep them.
    func testKidsTitlesStayOffHorror() {
        func ep(_ id: String, _ genres: [String], _ rating: String?) -> MediaItem {
            MediaItem(id: id, kind: .episode, title: id, seriesTitle: id, seriesID: id, season: 1, episode: 1, year: 2023,
                      genres: genres, runtime: 1320, contentRating: rating)
        }
        let gremlins = ep("gremlins", ["Horror", "Animation", "Comedy"], "TV-PG"), falls = ep("falls", ["Horror", "Animation", "Family"], "TV-Y7")
        let ldr = ep("ldr", ["Horror", "Animation"], "TV-MA"), live = ep("live", ["Horror", "Thriller"], nil)
        let lib = [gremlins, falls, ldr, live]
        XCTAssertEqual(lib.filter { ChannelBuilder.matches(.genre("Horror"), $0) }.map(\.id), ["ldr", "live"])
        XCTAssertEqual(lib.filter { ChannelBuilder.matches(.genre("Thriller"), $0) }.map(\.id), ["live"])
        XCTAssertTrue(ChannelBuilder.matches(.genre("Comedy"), gremlins))
    }

    /// "Creature Feature" is a keyword net inside Horror / Sci-Fi / Action / Adventure: the right word in the wrong genre,
    /// or inside another word, stays out.
    func testThemeChipsAndCreatureFeature() {
        func movie(_ i: Int, _ title: String, _ genres: [String], _ overview: String? = nil) -> MediaItem {
            MediaItem(id: "m\(i)", kind: .movie, title: title, year: 1990, genres: genres, runtime: 5400, overview: overview)
        }
        let lib = [
            movie(1, "Gorgo", ["Horror"], "A sea monster is captured off the Irish coast."),
            movie(2, "Them!", ["Science Fiction"], "Giant ants, the first of the atomic creatures."),
            movie(3, "Monsters, Inc.", ["Animation", "Comedy"], "Scaring is their business."),   // right word, wrong genre
            movie(4, "Monster's Ball", ["Drama"]),                                                 // title only, wrong genre
            movie(5, "Procreature", ["Horror"]),                                                   // not at a word start
            movie(6, "Bloodlines", ["Horror"], "The last vampires of Sarajevo."),
            movie(7, "Kong: Skull Island", ["Adventure"]),
            movie(9, "Kubo", ["Fantasy"], "A boy faces a monster."),                                   // Fantasy is outside the net
            movie(8, "Quiet", ["Horror"], "Nothing to see."),
        ]
        let creature = ChannelBuilder.channels(from: lib).first { $0.id == "theme:monsters" }
        XCTAssertEqual(creature?.name, "Creature Feature")
        XCTAssertEqual(creature?.items.map(\.title).sorted(), ["Bloodlines", "Gorgo", "Kong: Skull Island", "Them!"])
        XCTAssertEqual(RuleChip.anyGenre(["Horror", "Fantasy"]).label, "Genre: Horror / Fantasy")
        XCTAssertEqual(RuleChip.keywords(["monster", "kaiju", "godzilla", "creature", "zombie"]).label, "Mentions: monster, kaiju, godzilla, creature…")
    }

    /// Series chips match by id or by title, years ranges, mixed chips, and an empty rule takes the whole library.
    func testResolveRules() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let noID = (1...4).map { (i: Int) in MediaItem(id: "n\(i)", kind: .episode, title: "e\(i)", seriesTitle: "Orphan Show", episode: i, runtime: 1, addedAt: now) }
        let lib = demo + noID
        func def(_ id: String, _ rule: [RuleChip], order: PlayOrder = .shuffle) -> ChannelDefinition {
            ChannelDefinition(id: id, number: 0, name: id, tag: "", rule: rule, order: order)
        }
        let defs = ChannelBuilder.automaticChannels(from: lib, now: now) + [
            def("orphan", [.series(id: "Orphan Show", title: "Orphan Show")], order: .inOrder),
            def("mixed-series", [.series(id: "smh", title: "Orphan Show")]),
            def("span", [.years(1980, 1989)], order: .newestFirst),
            def("classic", [.years(1900, 1969), .tv]),
            def("added", [.addedWithin(days: 365)]),
            def("added-genre", [.addedWithin(days: 365), .genre("Comedy"), .movies]),
            def("theme", [.anyGenre(["Comedy", "Drama"]), .keywords(["the", "a"])]),
            def("nothing", [.genre("Nope")]),
            def("empty", []),
        ]
        let brute = defs.compactMap { d -> Channel? in
            let items = ChannelBuilder.resolve(d, in: lib, now: now)
            return items.isEmpty ? nil : Channel(definition: d, items: items)
        }
        XCTAssertTrue(brute.contains { $0.id == "orphan" && $0.items.count == 4 })
        XCTAssertTrue(brute.contains { $0.id == "mixed-series" && $0.items.count == 12 })
        XCTAssertFalse(brute.contains { $0.id == "nothing" })
        XCTAssertTrue(brute.contains { $0.id == "empty" && $0.items.count == lib.count })
    }
}

final class FreshItemsTests: XCTestCase {
    /// A film found by the check that runs when the app opens reaches Just Added and New Releases, but not the genre shelf it also matches.
    func testFreshItemsOnlyReachNewChannels() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let year = Calendar.current.component(.year, from: now)
        func film(_ id: String, added days: Double) -> MediaItem {
            MediaItem(id: id, kind: .movie, title: id, year: year, genres: ["Horror"], runtime: 5400,
                      addedAt: now.addingTimeInterval(-days * 86_400))
        }
        let library = (0..<6).map { film("old\($0)", added: 10) }
        let fresh = [film("new", added: 0)]
        let defs = ChannelBuilder.automaticChannels(from: library, now: now)
        let ids = { (chs: [Channel], id: String) in chs.first { $0.id == id }?.items.map(\.id) ?? [] }
        let chs = ChannelBuilder.channels(defs, in: library, fresh: fresh, now: now)
        XCTAssertTrue(ids(chs, "lead:just-added").contains("new"))
        XCTAssertTrue(ids(chs, "lead:new-movies").contains("new"))
        let shelf = chs.first { !ChannelBuilder.isFresh($0.definition) && $0.items.contains { $0.genres.contains("Horror") } }
        XCTAssertNotNil(shelf)
        XCTAssertFalse(shelf!.items.contains { $0.id == "new" })
        XCTAssertEqual(ChannelBuilder.channels(defs, in: library, fresh: [], now: now).map(\.id),
                       ChannelBuilder.channels(defs, in: library, now: now).map(\.id))
    }
}
