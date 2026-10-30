import Foundation

/// Channel 99: the one every cable box had that nobody could quite make out. Its "programs" are stand-ins the app
/// never asks the server for; the picture is the scramble effect itself, drawn over a mystery still from the library.
public extension Channel {
    static let scrambledID = "scrambled"
    static let scrambledNumber = 99

    static let scrambled: Channel = {
        let titles = ["Late Night Feature", "Mystery Matinee", "After Hours Special", "Double Feature", "Feature Presentation", "Encore Showing"]
        let items = titles.enumerated().map { i, t in
            // Exactly an hour: the scheduler skips its pad for these, so the picture never drops into a station break.
            MediaItem(id: "scrambled-\(i)", kind: .movie, title: t, year: 1986 + i, runtime: 3600,
                      overview: "Nothing comes in clearly on 99.", contentRating: "NR")
        }
        var ch = Channel(id: scrambledID, number: scrambledNumber, name: "Channel 99", items: items, tag: "Scrambled", order: .shuffle)
        ch.definition.trailerBreaks = false
        return ch
    }()

    var isScrambled: Bool { id == Self.scrambledID }
}
