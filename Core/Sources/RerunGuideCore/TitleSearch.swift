import Foundation

/// Options › Search: films by title and shows by series title (one entry per show; episode titles aren't searched), and
/// when each airs next. Matching ignores case, accents and punctuation, and a leading "The", "A" or "An" needn't be typed.
public enum TitleSearch {
    /// The most results a search returns.
    public static let limit = 50
    /// How far ahead "airs next" looks.
    public static let window: TimeInterval = 24 * 3600

    /// One searchable title: a film (copies with the same title and year are one) or a show.
    public struct Entry: Hashable, Sendable {
        /// "movie:<full key>|<year>" or "show:<series title>".
        public let id: String
        public let title: String
        /// A show's: its earliest episode's.
        public let year: Int?
        public let isShow: Bool
        /// The film (its first copy), or the show's first episode in the library.
        public let item: MediaItem
        /// The title's letters and digits ("theoffice"), and the same without a leading article ("office").
        let full: String, bare: String
    }

    /// Every entry with its keys, sorted the way equal matches are listed (by `bare`, then year with none as 0, films
    /// before shows), and the entry each item airs as.
    public struct Index: Sendable {
        public let entries: [Entry]
        let entryOf: [String: String]
    }

    public static func index(_ library: [MediaItem]) -> Index {
        var entries: [Entry] = [], at: [String: Int] = [:], entryOf: [String: String] = [:]
        for item in library {
            let show = item.kind == .episode
            guard let title = show ? item.seriesTitle : item.title else { continue }
            var key: (full: String, bare: String)? = nil, id = "show:" + title
            if !show { let k = keys(title); key = k; id = "movie:\(k.full)|\(item.year ?? 0)" }
            entryOf[item.id] = id
            if let i = at[id] {
                if show, let y = item.year, entries[i].year.map({ y < $0 }) ?? true {
                    let e = entries[i]
                    entries[i] = Entry(id: e.id, title: e.title, year: y, isShow: true, item: e.item, full: e.full, bare: e.bare)
                }
                continue
            }
            let k = key ?? keys(title)
            at[id] = entries.count
            entries.append(Entry(id: id, title: title, year: item.year, isShow: show, item: item, full: k.full, bare: k.bare))
        }
        entries.sort { ($0.bare, $0.year ?? 0, $0.isShow ? 1 : 0, $0.full, $0.title) < ($1.bare, $1.year ?? 0, $1.isShow ? 1 : 0, $1.full, $1.title) }
        return Index(entries: entries, entryOf: entryOf)
    }

    /// Up to `limit` entries: exact titles first, then titles that start with the query, then any other match; ties by
    /// title (its `bare` key), then year. A query without letters or digits finds nothing.
    public static func search(_ query: String, in index: Index, limit: Int = limit) -> [Entry] {
        let q = keys(query).full
        guard !q.isEmpty else { return [] }
        var exact: [Entry] = [], prefix: [Entry] = [], other: [Entry] = []
        for e in index.entries {   // already in tie order, so each list stays sorted
            if e.full == q || e.bare == q { exact.append(e) }
            else if e.full.utf8.starts(with: q.utf8) || e.bare.utf8.starts(with: q.utf8) { if prefix.count < limit { prefix.append(e) } }
            else if other.count < limit, contains(e.full, q) { other.append(e) }
        }
        return Array((exact + prefix + other).prefix(limit))
    }

    /// `q` inside `s`, compared as bytes: a keystroke checks every key, and the generic `firstRange(of:)` is ~200 times slower.
    static func contains(_ s: String, _ q: String) -> Bool {
        var s = s, q = q
        return s.withUTF8 { a in q.withUTF8 { b in
            guard let f = b.first, b.count <= a.count else { return b.isEmpty }
            return (0...(a.count - b.count)).contains { a[$0] == f && memcmp(a.baseAddress! + $0, b.baseAddress!, b.count) == 0 }
        } }
    }

    /// "The Schindler’s List" → ("theschindlerslist", "schindlerslist"): decomposed, lowercased, combining marks dropped,
    /// split into words at anything but a letter or a digit, joined without spaces; `bare` drops a leading article when
    /// more words follow.
    static func keys(_ s: String) -> (full: String, bare: String) {
        var words: [String] = [], word = String.UnicodeScalarView()
        for u in s.decomposedStringWithCanonicalMapping.lowercased().unicodeScalars {
            switch u.properties.generalCategory {
            case .nonspacingMark: continue
            case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter, .decimalNumber, .letterNumber, .otherNumber:
                word.append(u == "\u{3C2}" ? "\u{3C3}" : u)   // ς as σ: lowercased() makes every Σ σ
            default:
                if !word.isEmpty { words.append(String(word)); word = .init() }
            }
        }
        if !word.isEmpty { words.append(String(word)) }
        let full = words.joined()
        return (full, words.count > 1 && ["the", "a", "an"].contains(words[0]) ? words.dropFirst().joined() : full)
    }

    /// Where an entry airs next.
    public struct Airing: Hashable, Sendable {
        public let channelID: String, number: Int, name: String
        public let start: Date
        public init(channelID: String, number: Int, name: String, start: Date) {
            self.channelID = channelID; self.number = number; self.name = name; self.start = start
        }
    }

    /// The earliest airing of each entry across `lineups` (each channel with its slots) from `now` to `window` on, but not
    /// past tomorrow. A program counts while it plays and when it starts inside the window, never in its break (it's over
    /// by then). The earliest start wins, ties to the lower channel number.
    public static func nextAirings(_ lineups: [(channel: Channel, slots: [Slot])], in index: Index, now: Date, window: TimeInterval = window,
                                   calendar: Calendar = .current) -> [String: Airing] {
        // No further than tomorrow, so "Tomorrow" stays true: a spring-forward day is 23 hours.
        let end = min(now.addingTimeInterval(window), calendar.startOfDay(for: calendar.date(byAdding: .day, value: 2, to: now)!))
        var best: [String: Airing] = [:]
        for (ch, slots) in lineups {
            for s in slots where s.start < end && s.itemEnd > now {
                guard let id = index.entryOf[s.item.id] else { continue }
                if let b = best[id], (b.start, b.number) <= (s.start, ch.number) { continue }
                best[id] = Airing(channelID: ch.id, number: ch.number, name: ch.name, start: s.start)
            }
        }
        return best
    }

    /// "On now · Ch 5 Fright Night, 40 min in", "Today 3:00 PM · Ch 5 Fright Night", "Tonight 10:00 PM · …" (later today
    /// from 6 PM), "Tomorrow 2:00 PM · Ch 22 Gumshoe", or "Not scheduled in the next 24 hours". `time` is the app's clock
    /// ("10:00 PM").
    public static func airsText(_ a: Airing?, now: Date, calendar: Calendar = .current, time: (Date) -> String) -> String {
        guard let a else { return "Not scheduled in the next 24 hours" }
        let ch = "Ch \(a.number) \(a.name)"
        if a.start <= now { return "On now · \(ch), \(Int(now.timeIntervalSince(a.start) / 60)) min in" }
        let day = !calendar.isDate(a.start, inSameDayAs: now) ? "Tomorrow" : calendar.component(.hour, from: a.start) >= 18 ? "Tonight" : "Today"
        return "\(day) \(time(a.start)) · \(ch)"
    }
}
