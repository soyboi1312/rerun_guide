import Foundation

public enum ServerKind: String, Codable, Sendable, CaseIterable {
    case plex, emby, jellyfin, demo

    public var displayName: String {
        switch self { case .plex: "Plex"; case .emby: "Emby"; case .jellyfin: "Jellyfin"; case .demo: "Demo" }
    }
}

/// A library/section on the server (Plex section, Jellyfin/Emby view). Users pick which ones go on the air.
public struct LibrarySection: Identifiable, Hashable, Sendable {
    public enum Kind: String, Sendable { case movies, shows, other }
    public let id: String
    public let title: String
    public let kind: Kind
    public let itemCount: Int?
    public init(id: String, title: String, kind: Kind, itemCount: Int? = nil) {
        self.id = id; self.title = title; self.kind = kind; self.itemCount = itemCount
    }
}

/// One playable thing from the library. Server-agnostic.
public struct MediaItem: Identifiable, Hashable, Codable, Sendable {
    public enum Kind: String, Codable, Sendable { case movie, episode }

    public let id: String
    public let kind: Kind
    public let title: String
    public let seriesTitle: String?
    public let seriesID: String?
    public let season: Int?
    public let episode: Int?
    public let year: Int?
    public let genres: [String]
    /// Seconds.
    public let runtime: TimeInterval
    /// Server-relative poster/thumb path. Resolve with `MediaServer.artworkURL(path:)`.
    public let artworkPath: String?
    /// Server-relative wide art (backdrop/fanart) path, used by the guide's preview window. Resolve with `MediaServer.artworkURL(path:)`.
    public let backdropPath: String?
    public let overview: String?
    /// "PG", "R", "TV-14"… as the server reports it.
    public let contentRating: String?
    public let directors: [String]
    /// Top-billed cast, in billing order (first ~6 is plenty).
    public let actors: [String]
    /// Which `LibrarySection` it came from, when known.
    public let libraryID: String?
    /// When the server added it to the library (Plex addedAt, Emby/Jellyfin DateCreated). Drives "Just Added".
    public let addedAt: Date?
    /// The signed-in user has watched it, as the server says (Plex viewCount, Emby/Jellyfin UserData.Played). Drives
    /// "Unwatched". Not optional, so the library cache needs the key: a cache written before it is unreadable and refetched.
    public let watched: Bool

    public init(id: String, kind: Kind, title: String, seriesTitle: String? = nil, seriesID: String? = nil,
                season: Int? = nil, episode: Int? = nil, year: Int? = nil, genres: [String] = [],
                runtime: TimeInterval, artworkPath: String? = nil, backdropPath: String? = nil,
                overview: String? = nil, contentRating: String? = nil, directors: [String] = [], actors: [String] = [],
                libraryID: String? = nil, addedAt: Date? = nil, watched: Bool = false) {
        self.id = id; self.kind = kind; self.title = title; self.seriesTitle = seriesTitle; self.seriesID = seriesID
        self.season = season; self.episode = episode; self.year = year; self.genres = genres
        self.runtime = runtime; self.artworkPath = artworkPath; self.backdropPath = backdropPath
        self.overview = overview; self.contentRating = contentRating; self.directors = directors; self.actors = actors
        self.libraryID = libraryID; self.addedAt = addedAt; self.watched = watched
    }

    /// Guide-cell headline: series name for episodes, title for movies.
    public var displayTitle: String { kind == .episode ? (seriesTitle ?? title) : title }

    /// A film's title with its cut, "Watchmen (Directors Cut)": two cuts are two films, on screen and to the scheduler
    /// (`GuideGenerator.repeatKey`), while two plain copies stay one.
    public static func title(_ title: String, edition: String?) -> String {
        guard let e = edition?.trimmingCharacters(in: .whitespacesAndNewlines), !e.isEmpty else { return title }
        return "\(title) (\(e))"
    }

    /// The cut named in a file path, Plex style: "Watchmen (2009) {edition-Directors Cut}.mkv". Emby and Jellyfin have
    /// no edition field; Plex sends `editionTitle`.
    public static func edition(inPath path: String?) -> String? {
        guard let path, let r = path.range(of: #"\{edition-([^}]+)\}"#, options: .regularExpression) else { return nil }
        return String(path[r].dropFirst("{edition-".count).dropLast())
    }

    /// "S5 E9" or "" .
    public var seasonEpisode: String {
        [season.map { "S\($0)" }, episode.map { "E\($0)" }].compactMap { $0 }.joined(separator: " ")
    }

    /// Guide-cell second line: episode title for episodes, year for movies.
    public var subtitle: String {
        switch kind {
        case .episode: return title
        case .movie: return year.map(String.init) ?? ""
        }
    }

    /// Info-panel sub line. Episodes: “Episode” · S5 E9 · 1986. Movies: 1981 · PG · 115 min.
    public var infoLine: String {
        switch kind {
        case .episode:
            return (["“\(title)”", seasonEpisode.isEmpty ? nil : seasonEpisode, year.map(String.init)].compactMap { $0 }).joined(separator: " · ")
        case .movie:
            return ([year.map(String.init), contentRating, "\(Int(runtime / 60)) min"].compactMap { $0 }).joined(separator: " · ")
        }
    }
}

/// What "tune in" means: join the program in progress (real TV) or start it from the beginning.
public enum TuneMode: String, Codable, Sendable { case live, start }

public enum PlayOrder: String, Codable, CaseIterable, Sendable {
    case shuffle = "Shuffle", inOrder = "In order", newestFirst = "Newest first"
    public var next: PlayOrder { switch self { case .shuffle: .inOrder; case .inOrder: .newestFirst; case .newestFirst: .shuffle } }
}

/// One criterion of a channel's rule. A channel's items are the library items matching EVERY chip.
public enum RuleChip: Hashable, Codable, Sendable {
    case genre(String)
    case years(Int, Int)
    case director(String)
    case actor(String)
    case series(id: String, title: String)
    case year(Int)
    case movies
    case tv
    /// Added to the server within the last N days (relative to when the channels are rebuilt).
    case addedWithin(days: Int)
    /// Any of these genres. One chip, so it ANDs with the rest of the rule like every other chip.
    case anyGenre([String])
    /// Title or overview mentions any of these words: case-insensitive, matched at a word start ("zombie" takes "zombies").
    case keywords([String])
    /// Not watched by the signed-in user (`MediaItem.watched`).
    case unwatched

    /// Chip text as shown in the channel editor: "Genre: Sitcom", "1975–1999", "Director: Steven Spielberg", "TV".
    public var label: String {
        switch self {
        case .genre(let g): "Genre: \(g)"
        case .years(let a, let b): a <= 1900 ? "Before \(b + 1)" : "\(a)–\(b)"
        case .director(let d): "Director: \(d)"
        case .actor(let a): "Actor: \(a)"
        case .series(_, let t): "Show: \(t)"
        case .year(let y): "Year: \(y)"
        case .movies: "Movies"
        case .tv: "TV"
        case .addedWithin(let d): "Added in the last \(d) days"
        case .anyGenre(let gs): "Genre: " + gs.joined(separator: " / ")
        case .keywords(let ws): "Mentions: " + ws.prefix(4).joined(separator: ", ") + (ws.count > 4 ? "…" : "")
        case .unwatched: "Unwatched"
        }
    }
}

/// What a channel IS, independent of the library contents. Automatic ones are rebuilt from the library each launch;
/// custom ones and per-channel edits are persisted by the app.
public struct ChannelDefinition: Identifiable, Hashable, Codable, Sendable {
    public let id: String
    public var number: Int
    public var name: String
    /// Short descriptor under the name: "Sitcoms, 70s–90s", "Director", "Marathon", "Year".
    public var tag: String
    public var rule: [RuleChip]
    public var order: PlayOrder
    public var trailerBreaks: Bool
    public let isCustom: Bool
    /// Per-channel tune-in behaviour; nil = follow the app setting.
    public var tuneMode: TuneMode?

    public init(id: String, number: Int, name: String, tag: String, rule: [RuleChip], order: PlayOrder = .shuffle,
                trailerBreaks: Bool = true, isCustom: Bool = false, tuneMode: TuneMode? = nil) {
        self.id = id; self.number = number; self.name = name; self.tag = tag; self.rule = rule
        self.order = order; self.trailerBreaks = trailerBreaks; self.isCustom = isCustom; self.tuneMode = tuneMode
    }

    /// Stable 0..<360 hue for gradients, derived from the id (never from Swift's randomized Hasher).
    public var hue: Double { Double(StableHash.fnv1a(id) % 360) }
}

/// A guide row: a definition plus the library items it resolved to (already in `order`-appropriate sequence).
public struct Channel: Identifiable, Hashable, Sendable {
    public var definition: ChannelDefinition
    public let items: [MediaItem]
    /// Mostly films (vs. episodes); decides whether the channel belongs to the Movies or the TV guide.
    public let isMovies: Bool

    public init(definition: ChannelDefinition, items: [MediaItem]) {
        self.definition = definition; self.items = items
        self.isMovies = items.lazy.filter { $0.kind == .movie }.count * 2 >= items.count
    }

    /// Convenience for tests and channel 99: a plain genre-style channel.
    public init(id: String, number: Int, name: String, items: [MediaItem], tag: String = "", order: PlayOrder = .shuffle) {
        self.definition = ChannelDefinition(id: id, number: number, name: name, tag: tag, rule: [], order: order)
        self.items = items
        self.isMovies = items.lazy.filter { $0.kind == .movie }.count * 2 >= items.count
    }

    public var id: String { definition.id }
    public var number: Int { definition.number }
    public var name: String { definition.name }
    public var tag: String { definition.tag }
    public var order: PlayOrder { definition.order }
    public var trailerBreaks: Bool { definition.trailerBreaks }
    public var isCustom: Bool { definition.isCustom }
    public var hue: Double { definition.hue }
}

/// One cell in the guide: an item airing on a channel between `start` and `end`.
/// `end - start` is the runtime (+3 min pad) rounded UP to the guide block (30 min), like a paper TV guide.
/// Time after the item ends but before `end` is the "break" (trailers / station card).
public struct Slot: Identifiable, Hashable, Sendable {
    public let item: MediaItem
    public let start: Date
    public let end: Date

    public init(item: MediaItem, start: Date, end: Date) { self.item = item; self.start = start; self.end = end }
    public var id: String { "\(item.id)@\(Int(start.timeIntervalSince1970))" }
    public var duration: TimeInterval { end.timeIntervalSince(start) }
    /// When the item itself stops playing and the break begins.
    public var itemEnd: Date { min(end, start.addingTimeInterval(item.runtime)) }
}

public enum ProgramStatus: Sendable { case later, live, `break`, past }

/// One audio or subtitle track of an item, as the server reports it.
public struct MediaTrack: Identifiable, Hashable, Sendable {
    public enum Kind: String, Sendable { case audio, subtitle }
    /// Plex stream id / Jellyfin and Emby stream index, as a string.
    public let id: String
    public let kind: Kind
    /// ISO 639-2 code ("eng", "spa") when the server knows it.
    public let language: String?
    /// "English (AAC Stereo)", "Spanish (SUBRIP)", "Commentary"…
    public let title: String
    public let isDefault: Bool
    public let isForced: Bool

    public init(id: String, kind: Kind, language: String?, title: String, isDefault: Bool = false, isForced: Bool = false) {
        self.id = id; self.kind = kind; self.language = language; self.title = title; self.isDefault = isDefault; self.isForced = isForced
    }

    /// "Spanish" for "spa"; falls back to the code. Handles the bibliographic ISO 639-2/B codes Emby and Plex emit.
    public static func languageName(_ code: String?) -> String? {
        guard let code, !code.isEmpty, code != "und" else { return nil }
        return Locale.current.localizedString(forLanguageCode: terminology(code) ?? code) ?? code
    }

    /// The ISO 639-2/T code for `code` ("fre" → "fra", "ger" → "deu"; others lowercased as is). Emby and Plex emit /B
    /// codes, the device locale gives /T, so every language comparison goes through here. nil for none / "und".
    public static func terminology(_ code: String?) -> String? {
        guard let c = code?.lowercased(), !c.isEmpty, c != "und" else { return nil }
        let t: [String: String] = ["fre": "fra", "ger": "deu", "chi": "zho", "dut": "nld", "gre": "ell", "cze": "ces", "rum": "ron", "may": "msa",
                                   "baq": "eus", "per": "fas", "ice": "isl", "mac": "mkd", "slo": "slk", "wel": "cym", "arm": "hye", "geo": "kat",
                                   "bur": "mya", "tib": "bod", "alb": "sqi", "mao": "mri",
                                   "nob": "nor", "nno": "nor"]   // a Norwegian device says Bokmål/Nynorsk, servers tag "nor"
        return t[c] ?? c
    }

    /// Same language, /B and /T codes alike; an unknown language matches nothing.
    public static func sameLanguage(_ a: String?, _ b: String?) -> Bool {
        guard let a = terminology(a) else { return false }
        return a == terminology(b)
    }
}

/// What the viewer asked for. nil / .auto = the server's default choice.
public struct TrackSelection: Hashable, Sendable {
    public enum Subtitle: Hashable, Sendable { case auto, off, track(String) }
    public var audioID: String? = nil
    public var subtitle: Subtitle = .auto
    public init(audioID: String? = nil, subtitle: Subtitle = .auto) { self.audioID = audioID; self.subtitle = subtitle }
    public var isDefault: Bool { audioID == nil && subtitle == .auto }

    /// The Settings preferences matched against an item's tracks. `subtitleMode` "off" / "on" / anything else = "Forced
    /// only": Plex leaves that to the server (an explicit Plex pick changes the account-wide choice), Emby/Jellyfin get
    /// the forced track asked for by index, or none, so the server account's subtitle preference never decides. A forced
    /// track counts only in `subtitleLanguage`, else `deviceLanguage`, else the language of the audio that will play
    /// (picked, else the item's default); a foreign forced track is never shown. An unmatched audio language, or "on"
    /// without subtitle tracks, = server default.
    public static func preferred(for tracks: [MediaTrack], server: ServerKind?, audioLanguage: String?,
                                 subtitleMode: String, subtitleLanguage: String, deviceLanguage: String? = nil) -> TrackSelection {
        var sel = TrackSelection()
        let audio = tracks.filter { $0.kind == .audio }
        if let a = audio.first(where: { MediaTrack.sameLanguage($0.language, audioLanguage) }) { sel.audioID = a.id }
        let subs = tracks.filter { $0.kind == .subtitle }
        switch subtitleMode {
        case "off": sel.subtitle = .off
        case "on":
            // The chosen language if the program has it, else any full subtitle track; forced-only tracks last.
            let pick = subs.first { MediaTrack.sameLanguage($0.language, subtitleLanguage) && !$0.isForced } ?? subs.first { !$0.isForced } ?? subs.first
            if let pick { sel.subtitle = .track(pick.id) }
        default:
            guard server == .emby || server == .jellyfin else { break }
            let forced = subs.filter(\.isForced)
            let playing = audio.first { $0.id == sel.audioID } ?? audio.first(where: \.isDefault) ?? audio.first
            let pick = [subtitleLanguage, deviceLanguage, playing?.language].lazy
                .compactMap { lang in forced.first { MediaTrack.sameLanguage($0.language, lang) } }.first
            sel.subtitle = pick.map { .track($0.id) } ?? .off
        }
        return sel
    }
}

/// What the player should load.
public struct PlaybackSource: Sendable {
    public let url: URL
    /// true: the server already started the stream at the requested offset (Plex HLS transcode), so do NOT seek.
    /// false: the stream starts at 0 (direct play, Jellyfin/Emby HLS); the player must seek to the offset itself.
    public let startsAtOffset: Bool
    /// true when a chosen subtitle rides in the HLS playlist as a text rendition the player must select (Jellyfin/Emby);
    /// false when it is burned into the picture (Plex) or no subtitle was requested.
    public let subtitleInPlaylist: Bool

    public init(url: URL, startsAtOffset: Bool, subtitleInPlaylist: Bool = false) {
        self.url = url; self.startsAtOffset = startsAtOffset; self.subtitleInPlaylist = subtitleInPlaylist
    }
}

/// Process-independent hashing. Swift's `hashValue` is seeded per launch and must never feed anything deterministic.
public enum StableHash {
    public static func fnv1a(_ s: String) -> UInt64 {
        var h: UInt64 = 0xcbf29ce484222325
        for b in s.utf8 { h ^= UInt64(b); h = h &* 0x100000001b3 }
        return h
    }
}
