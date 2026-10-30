import XCTest
@testable import RerunGuideCore

final class TitleSearchTests: XCTestCase {
    func movie(_ id: String, _ title: String, _ year: Int?, minutes: Int = 100) -> MediaItem {
        MediaItem(id: id, kind: .movie, title: title, year: year, runtime: TimeInterval(minutes * 60))
    }
    func episode(_ id: String, _ show: String?, _ title: String, _ year: Int) -> MediaItem {
        MediaItem(id: id, kind: .episode, title: title, seriesTitle: show, seriesID: show.map { "s-\($0)" }, season: 1, episode: 1, year: year, runtime: 22 * 60)
    }
    lazy var library = [
        movie("m1", "Amélie", 2001),
        movie("m2", "The Godfather", 1972, minutes: 175),
        movie("m3", "The Godfather Part II", 1974),
        movie("m4", "Godzilla", 2014),
        movie("m5", "Godzilla", 1954),
        movie("m6", "GODZILLA", 1954),            // a second copy of m5: one result
        movie("m7", "Godzilla", nil),             // no year: counts as 0
        movie("m8", "Spider-Man", 2002),
        movie("m9", "Schindler’s List", 1993),
        movie("m10", "Office Space", 1999, minutes: 89),
        movie("m11", "A Bug's Life", 1998),
        movie("m12", "Fargo", 1996, minutes: 98),
        movie("m13", "Man on Fire", 2004),
        movie("m14", "Batman", 1989),
        episode("e1", "The Office", "Pilot", 2006),
        episode("e2", "The Office", "Diversity Day", 2005),
        episode("e3", "Fargo", "The Crocodile's Dilemma", 2014),
        episode("e4", nil, "Godzilla Returns", 2000),   // no series title: not searchable
    ]
    lazy var index = TitleSearch.index(library)

    func titles(_ q: String) -> [String] { TitleSearch.search(q, in: index).map(\.title) }
    func found(_ q: String) -> [String] { TitleSearch.search(q, in: index).map { "\($0.title) \($0.year.map(String.init) ?? "-")" } }

    func testCapitalsAccentsAndPunctuation() {
        XCTAssertEqual(titles("OFFICE SPACE"), ["Office Space"])
        XCTAssertEqual(titles("amelie"), ["Amélie"])
        XCTAssertEqual(titles("AMÉLIE"), ["Amélie"])
        for q in ["spider-man", "spiderman", "spider man", "SPIDER MAN!"] { XCTAssertEqual(titles(q), ["Spider-Man"], q) }
        XCTAssertEqual(titles("schindlers list"), ["Schindler’s List"])
        XCTAssertEqual(titles("schindler's"), ["Schindler’s List"])
    }

    /// A word-final sigma is ς typed and Σ in capitals: both match σ, so capitals don't matter in Greek either.
    func testFinalSigma() {
        let greek = TitleSearch.index([movie("g1", "ΖΟΡΜΠΑΣ", 1964), movie("g2", "Ζορμπάς", 1965)])
        for q in ["Ζορμπάς", "ΖΟΡΜΠΑΣ", "ζορμπασ"] { XCTAssertEqual(TitleSearch.search(q, in: greek).map(\.title), ["ΖΟΡΜΠΑΣ", "Ζορμπάς"], q) }
    }

    /// "office" is The Office exactly (its article needn't be typed), so it comes before Office Space, which only starts
    /// with it; typing the article works too.
    func testLeadingArticle() {
        XCTAssertEqual(titles("office"), ["The Office", "Office Space"])
        XCTAssertEqual(titles("the office"), ["The Office"])
        XCTAssertEqual(titles("bugs life"), ["A Bug's Life"])
        XCTAssertEqual(titles("a bug"), ["A Bug's Life"])
        XCTAssertEqual(titles("godfather"), ["The Godfather", "The Godfather Part II"])
    }

    func testPartialWords() {
        XCTAssertEqual(titles("godf"), ["The Godfather", "The Godfather Part II"])
        XCTAssertEqual(titles("father"), ["The Godfather", "The Godfather Part II"])
        XCTAssertEqual(titles("spiderm"), ["Spider-Man"])
    }

    /// Exact titles, then titles that start with the query, then any other match, each by title then year (none = 0).
    func testOrderAndTies() {
        XCTAssertEqual(found("god"), ["The Godfather 1972", "The Godfather Part II 1974", "Godzilla -", "Godzilla 1954", "Godzilla 2014"])
        XCTAssertEqual(found("man"), ["Man on Fire 2004", "Batman 1989", "Spider-Man 2002"])
        XCTAssertEqual(found("godzilla"), ["Godzilla -", "Godzilla 1954", "Godzilla 2014"])
        XCTAssertEqual(found("fargo"), ["Fargo 1996", "Fargo 2014"])
    }

    /// Films by title, shows by series title once each (their year is the earliest episode's); episode titles never match.
    func testMoviesAndShows() {
        let office = TitleSearch.search("the office", in: index)
        XCTAssertEqual(office.map(\.isShow), [true])
        XCTAssertEqual(office.first?.year, 2005)
        XCTAssertEqual(TitleSearch.search("fargo", in: index).map(\.isShow), [false, true])
        XCTAssertEqual(titles("pilot"), [])
        XCTAssertEqual(titles("diversity"), [])
        XCTAssertEqual(titles("returns"), [])
    }

    func testCapAndNoResults() {
        let many = TitleSearch.index((1...60).map { movie("n\($0)", "Night \($0)", 2000) })
        let r = TitleSearch.search("night", in: many)
        XCTAssertEqual(r.count, TitleSearch.limit)
        XCTAssertEqual(TitleSearch.limit, 50)
        XCTAssertEqual(r.prefix(3).map(\.title), ["Night 1", "Night 10", "Night 11"])   // by title key, as text
        XCTAssertEqual(titles("zzz"), [])
        XCTAssertEqual(titles(""), [])
        XCTAssertEqual(titles("!!"), [])
    }

    // MARK: Airs next

    let utc: Calendar = { var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "UTC")!; return c }()
    func at(_ d: Int, _ h: Int, _ m: Int = 0) -> Date { utc.date(from: DateComponents(year: 2026, month: 10, day: d, hour: h, minute: m))! }
    func clock(_ d: Date) -> String { clock(d, in: utc.timeZone) }
    func clock(_ d: Date, in zone: TimeZone) -> String {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = zone; f.dateFormat = "h:mm a"
        return f.string(from: d)
    }
    func item(_ id: String) -> MediaItem { library.first { $0.id == id }! }
    func slot(_ id: String, _ start: Date, _ end: Date) -> Slot { Slot(item: item(id), start: start, end: end) }

    /// Now is 7:20 PM on the 27th. Each entry's earliest airing in the next 24 hours, as the result rows say it.
    func testAirsNext() {
        let now = at(27, 19, 20)
        let c5 = Channel(id: "c5", number: 5, name: "Fright Night", items: [])
        let c9 = Channel(id: "c9", number: 9, name: "Studio Audience", items: [])
        let c22 = Channel(id: "c22", number: 22, name: "Gumshoe", items: [])
        let lineups: [(channel: Channel, slots: [Slot])] = [
            (c22, [slot("m10", at(27, 17, 30), at(27, 19, 30)),   // Office Space: in its break now (ended 6:59)
                   slot("m12", at(27, 17, 30), at(27, 19, 30)),   // Fargo: in its break, and not on again
                   slot("m1", at(27, 20), at(27, 22)),
                   slot("m9", at(27, 21), at(27, 23)),
                   slot("m8", at(27, 22), at(28, 0)),
                   slot("m2", at(27, 23), at(28, 2))]),
            (c5, [slot("m2", at(27, 18, 40), at(27, 21, 30)),     // The Godfather: on now, 40 min in
                  slot("m9", at(27, 21), at(27, 23)),             // same start as on 22: the lower number wins
                  slot("m1", at(27, 23, 30), at(28, 1, 30)),      // later than on 22: 22 wins
                  slot("m10", at(28, 14), at(28, 16)),
                  slot("m3", at(28, 19, 30), at(28, 21, 30))]),   // past the 24 hours
            (c9, [slot("e1", at(27, 23), at(27, 23, 30)),
                  slot("e2", at(27, 20, 30), at(27, 21))]),       // the show's next episode
        ]
        let airs = TitleSearch.nextAirings(lineups, in: index, now: now)
        func text(_ q: String) -> String { TitleSearch.airsText(airs[TitleSearch.search(q, in: index)[0].id], now: now, calendar: utc, time: clock) }
        XCTAssertEqual(text("the godfather"), "On now · Ch 5 Fright Night, 40 min in")
        XCTAssertEqual(text("office space"), "Tomorrow 2:00 PM · Ch 5 Fright Night")
        XCTAssertEqual(text("fargo"), "Not scheduled in the next 24 hours")
        XCTAssertEqual(text("amelie"), "Tonight 8:00 PM · Ch 22 Gumshoe")
        XCTAssertEqual(text("schindlers list"), "Tonight 9:00 PM · Ch 5 Fright Night")
        XCTAssertEqual(text("spider-man"), "Tonight 10:00 PM · Ch 22 Gumshoe")
        XCTAssertEqual(text("the office"), "Tonight 8:30 PM · Ch 9 Studio Audience")
        XCTAssertEqual(text("godfather part ii"), "Not scheduled in the next 24 hours")
        XCTAssertEqual(airs[TitleSearch.search("godfather", in: index)[0].id]?.channelID, "c5")
    }

    /// Later today is "Today" before 6 PM and "Tonight" from then; the next calendar day is "Tomorrow".
    func testAirsTextDays() {
        let c = (id: "c22", n: 22, name: "Gumshoe")
        func text(_ start: Date, now: Date) -> String {
            TitleSearch.airsText(.init(channelID: c.id, number: c.n, name: c.name, start: start), now: now, calendar: utc, time: clock)
        }
        XCTAssertEqual(text(at(27, 15), now: at(27, 9)), "Today 3:00 PM · Ch 22 Gumshoe")
        XCTAssertEqual(text(at(27, 17, 30), now: at(27, 9)), "Today 5:30 PM · Ch 22 Gumshoe")
        XCTAssertEqual(text(at(27, 18), now: at(27, 9)), "Tonight 6:00 PM · Ch 22 Gumshoe")
        XCTAssertEqual(text(at(28, 2), now: at(27, 19, 20)), "Tomorrow 2:00 AM · Ch 22 Gumshoe")
        XCTAssertEqual(text(at(27, 9), now: at(27, 9)), "On now · Ch 22 Gumshoe, 0 min in")
        XCTAssertEqual(TitleSearch.airsText(nil, now: at(27, 9), calendar: utc, time: clock), "Not scheduled in the next 24 hours")
    }

    /// Saturday 11:30 PM before a spring-forward Sunday (23 hours) in Los Angeles: 24 hours on is 12:30 AM Monday, which
    /// "Tomorrow" would say as Sunday's. The window stops at the end of tomorrow.
    func testSpringForwardNight() {
        var la = Calendar(identifier: .gregorian); la.timeZone = TimeZone(identifier: "America/Los_Angeles")!
        func march(_ d: Int, _ h: Int, _ m: Int = 0) -> Date { la.date(from: DateComponents(year: 2027, month: 3, day: d, hour: h, minute: m))! }
        let now = march(13, 23, 30)
        let c22 = Channel(id: "c22", number: 22, name: "Gumshoe", items: [])
        let airs = TitleSearch.nextAirings([(c22, [slot("m8", march(14, 23), march(15, 0)), slot("m1", march(15, 0), march(15, 2))])],
                                           in: index, now: now, calendar: la)
        func text(_ q: String) -> String {
            TitleSearch.airsText(airs[TitleSearch.search(q, in: index)[0].id], now: now, calendar: la) { clock($0, in: la.timeZone) }
        }
        XCTAssertEqual(text("spider-man"), "Tomorrow 11:00 PM · Ch 22 Gumshoe")
        XCTAssertEqual(text("amelie"), "Not scheduled in the next 24 hours")
    }
}
