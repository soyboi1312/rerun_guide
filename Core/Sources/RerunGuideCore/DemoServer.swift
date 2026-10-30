import Foundation

/// Fake library so the guide can be developed and demoed without a real server.
/// Everything is invented. The cast/crew/years are arranged so every automatic channel type shows up:
/// split genre (Comedy films / TV), a TV-only genre (Crime: too few films for a films channel), 3 marathons, 3 directors, 3 actors, Class of ’85.
public final class DemoMediaServer: MediaServer, Sendable {
    public let kind: ServerKind = .demo
    public init() {}

    /// Fixed, hand-written library: 20 invented retro movies + 3 series × 8 episodes. Runtimes in seconds.
    public static let items: [MediaItem] = {
        func movie(_ n: Int, _ title: String, _ year: Int, _ mins: Int, _ rating: String, _ genres: [String],
                   by director: String, with actors: [String], _ overview: String) -> MediaItem {
            MediaItem(id: "m\(n)", kind: .movie, title: title, year: year, genres: genres, runtime: TimeInterval(mins * 60),
                      overview: overview, contentRating: rating, directors: [director], actors: actors, libraryID: "demo-movies",
                      addedAt: Date(timeIntervalSinceNow: -Double(n * 6) * 86_400))   // one movie every 6 days → ~10 "Just Added"
        }
        func series(_ id: String, _ name: String, _ year: Int, _ mins: Int, _ genres: [String], _ eps: [(Int, Int, String, String)]) -> [MediaItem] {
            eps.map { s, e, title, overview in
                MediaItem(id: "\(id)-s\(s)e\(e)", kind: .episode, title: title, seriesTitle: name, seriesID: id,
                          season: s, episode: e, year: year, genres: genres, runtime: TimeInterval(mins * 60),
                          overview: overview, contentRating: "TV-PG", libraryID: "demo-tv")
            }
        }
        let vole = "Marguerite Vole", calhoun = "Dusty Calhoun", okonkwo = "Len Okonkwo", marsh = "Priya Marsh"
        let rex = "Rex Tallow", dolores = "Dolores Finch", kip = "Kip Navarro", bea = "Bea Saltonstall"
        let hal = "Hal Pruett", wendell = "Wendell Ash", nina = "Nina Castellano", ozzie = "Ozzie Blankenship"
        return [
            movie(1, "Night of the Rental Returns", 1985, 92, "R", ["Horror", "Comedy"], by: vole, with: [rex, dolores],
                  "A video-store night clerk discovers the overdue tapes are coming back on their own, and they are angry about the late fees."),
            movie(2, "Chrome Horizon", 1984, 118, "PG", ["Sci-Fi"], by: vole, with: [kip, bea],
                  "A test pilot flies a prototype past the edge of the atmosphere and finds a second, mirrored Earth waiting there."),
            movie(3, "The Last Payphone", 1996, 104, "R", ["Drama"], by: okonkwo, with: [dolores, hal],
                  "A night-shift dispatcher keeps answering the one payphone in town nobody else will pick up."),
            movie(4, "Mullet Force", 1985, 97, "PG-13", ["Action", "Comedy"], by: calhoun, with: [rex, kip],
                  "Four hairdressers with military pasts are the only ones who can stop a hostile takeover of the state fair."),
            movie(5, "Suburban Meteor", 1991, 101, "PG", ["Sci-Fi", "Comedy"], by: vole, with: [rex, bea],
                  "A meteorite lands in a cul-de-sac and the homeowners' association wants it moved by Friday."),
            movie(6, "Blood on the Boardwalk", 1982, 88, "R", ["Horror"], by: vole, with: [dolores, wendell],
                  "Something under the pier is picking off carnival workers one summer night at a time."),
            movie(7, "Dial Tone", 1993, 110, "PG-13", ["Drama"], by: marsh, with: [dolores, kip],
                  "A phone-company lineman starts hearing a stranger's confessions on a crossed wire and can't stop listening."),
            movie(8, "Rooftop Reckoning", 1990, 112, "R", ["Action", "Crime"], by: calhoun, with: [rex, hal],
                  "A window washer trapped forty stories up becomes the only witness to a heist in progress."),
            movie(9, "The Beige Conspiracy", 1978, 126, "PG", ["Drama"], by: okonkwo, with: [bea, hal],
                  "A junior accountant notices every office in the building is painted the same color, and starts asking why."),
            movie(10, "Laser Prom", 1985, 95, "PG", ["Sci-Fi", "Comedy"], by: calhoun, with: [kip, dolores],
                  "The prom committee's borrowed light show turns out to be alien technology with opinions about the playlist."),
            movie(11, "Static", 1998, 99, "R", ["Horror"], by: vole, with: [wendell, nina],
                  "A late-night radio engineer realizes the dead air between stations has started to talk back."),
            movie(12, "Two Tickets to Tuesday", 1995, 93, "PG", ["Comedy"], by: marsh, with: [dolores, rex],
                  "Two strangers hold the same winning raffle ticket and have one week to decide who gets the cruise."),
            movie(13, "Iron Cul-de-Sac", 1988, 108, "R", ["Action"], by: calhoun, with: [rex, nina],
                  "A retired demolition expert defends his street from developers using everything in his garage."),
            movie(14, "Orbital Lullaby", 1997, 132, "PG-13", ["Sci-Fi", "Drama"], by: okonkwo, with: [kip, wendell],
                  "The last crew member awake on a sleeper ship has to decide whether to wake the others for the view."),
            movie(15, "The Cassette Killer", 1985, 90, "R", ["Horror"], by: vole, with: [dolores, kip],
                  "Every mixtape left on the diner counter plays a song the listener will never finish."),
            movie(16, "Floodlight", 1979, 140, "PG", ["Drama"], by: marsh, with: [nina, ozzie],
                  "A small-town stadium's night-game lights become the center of a fight over who the town belongs to."),
            movie(17, "Big Hair, Bigger Trouble", 1992, 85, "PG-13", ["Comedy"], by: calhoun, with: [rex, dolores],
                  "A salon owner's regional styling title is challenged by her own twin sister."),
            movie(18, "Harbor Protocol", 1994, 121, "R", ["Action", "Drama", "Crime"], by: calhoun, with: [kip, ozzie],
                  "A harbor master with one night left before retirement finds a cargo manifest that doesn't add up."),
            movie(19, "Moonbase Midnight", 1981, 103, "PG-13", ["Sci-Fi", "Horror"], by: vole, with: [ozzie, rex],
                  "The night crew of a lunar mining base discovers their shift has been running for three years."),
            movie(20, "Reunion Weekend", 1999, 96, "PG", ["Comedy", "Drama"], by: okonkwo, with: [dolores, kip],
                  "A class reunion at a motel with one working ice machine forces old friends to settle twenty years of accounts."),
        ]
        + series("cds", "Cul-de-Sac Confidential", 1988, 22, ["Comedy"], [
            (1, 1, "The Welcome Casserole", "The new neighbors get a casserole from every house on the street, and every cook wants their dish back first."),
            (1, 2, "Garage Sale Gambit", "A joint garage sale turns competitive when one driveway starts offering free lemonade."),
            (1, 3, "Sprinkler Wars", "Two lawns, one shared water line, and a watering schedule nobody agreed to."),
            (1, 4, "The Mailbox Incident", "A misdelivered package reveals which neighbor has been ordering the same novelty item for years."),
            (1, 5, "Block Party Blues", "The block party band breaks up during sound check."),
            (1, 6, "Hedge Fund", "The street pools money for a hedge trimmer and immediately argues about custody."),
            (1, 7, "Carpool Confessions", "A stalled carpool van becomes a confessional on the longest morning commute of the year."),
            (1, 8, "The Big Bake-Off", "The annual bake-off judge turns out to have no sense of taste."),
        ])
        + series("smh", "Strip Mall Heroes", 1994, 22, ["Comedy"], [
            (1, 1, "Grand Opening", "The mall's newest tenant opens on the same day as the parking-lot repaving."),
            (1, 2, "The Food Court Feud", "Two neighboring pretzel stands go to war over a single shared napkin dispenser."),
            (1, 3, "Arcade Fire Drill", "An unscheduled fire drill traps the arcade's high-score holder mid-run."),
            (1, 4, "Mannequin Overboard", "A display mannequin goes missing and every store blames the one next door."),
            (2, 1, "Return Policy", "A customer tries to return an item from a store that closed in 1987."),
            (2, 2, "Escalator Etiquette", "The mall's only escalator breaks and the tenants draft a stair treaty."),
            (2, 3, "The Pretzel Protocol", "A health inspection sends the food court into a cover-up involving one very large pretzel."),
            (2, 4, "Closing Time", "The mall announces new hours, and nobody can agree on which clock is right."),
        ])
        + series("p9", "Precinct 9 at Nine", 1991, 44, ["Drama", "Crime"], [
            (1, 1, "Night Shift", "A rookie's first night shift ends with a confession nobody asked for."),
            (1, 2, "Cold Coffee", "A diner robbery goes wrong when the detective on break turns out to be the only customer."),
            (1, 3, "The Pawn Shop Job", "A pawned trumpet leads the squad to a string of burglaries across the precinct."),
            (1, 4, "Internal Affairs", "An internal investigation puts the night shift's favorite sergeant under the lamp."),
            (1, 5, "Dead Drop", "A briefcase left on the number 9 bus has everyone wondering who it was meant for."),
            (1, 6, "Witness", "The only witness to a hit-and-run is a parking attendant who swears he saw nothing."),
            (1, 7, "The Long Stakeout", "Two detectives spend eleven hours in a parked car waiting for a light to turn on."),
            (1, 8, "Badge Number", "A retired officer's badge number turns up on a fresh arrest report."),
        ])
    }()

    public let serverName = "Demo library"
    public func libraries() async throws -> [LibrarySection] { [LibrarySection(id: "demo-movies", title: "Movies", kind: .movies, itemCount: 20), LibrarySection(id: "demo-tv", title: "TV Shows", kind: .shows, itemCount: 3)] }
    public func libraryItems(in sections: [LibrarySection]?) async throws -> [MediaItem] {
        guard let ids = sections.map({ Set($0.map(\.id)) }) else { return Self.items }
        return Self.items.filter { ids.contains($0.libraryID ?? "") }
    }
    // trailer(for:) is the protocol default (nil): the demo has no trailers, so breaks show the station card.

    /// A fixed ~10 min sample: `startsAtOffset: true` so nobody seeks it to a 0–140 min clock offset; the watch player loops it.
    public func playbackSource(for item: MediaItem, startingAt offset: TimeInterval, session: String, tracks: TrackSelection) async throws -> PlaybackSource {
        PlaybackSource(url: URL(string: "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8")!,
                       startsAtOffset: true)
    }

    public func artworkURL(path: String?) -> URL? { nil }
}
