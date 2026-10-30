package com.rerunguide.core

/**
 * Channel 99: the one every cable box had that nobody could quite make out. Its "programs" are stand-ins the app
 * never asks the server for; the picture is the scramble effect itself, drawn over a mystery still from the library.
 * `Channel.scrambledID`, `Channel.scrambledNumber`, `Channel.scrambled` and `Channel.isScrambled` live on `Channel`
 * (Models.kt); this builds the channel.
 */
internal fun makeScrambled(): Channel {
    val titles = listOf("Late Night Feature", "Mystery Matinee", "After Hours Special", "Double Feature", "Feature Presentation", "Encore Showing")
    val items = titles.mapIndexed { i, t ->
        // Exactly an hour: the scheduler skips its pad for these, so the picture never drops into a station break.
        MediaItem(id = "scrambled-$i", kind = MediaItem.Kind.movie, title = t, year = 1986 + i, runtime = 3600.0,
                  overview = "Nothing comes in clearly on 99.", contentRating = "NR")
    }
    val ch = Channel(id = Channel.scrambledID, number = Channel.scrambledNumber, name = "Channel 99", items = items, tag = "Scrambled", order = PlayOrder.shuffle)
    return ch.copy(definition = ch.definition.copy(trailerBreaks = false))
}
