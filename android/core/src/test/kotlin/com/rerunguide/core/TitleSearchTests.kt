package com.rerunguide.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class TitleSearchTests {
    private fun movie(id: String, title: String, year: Int?, minutes: Int = 100) =
        MediaItem(id = id, kind = MediaItem.Kind.movie, title = title, year = year, runtime = minutes * 60.0)
    private fun episode(id: String, show: String?, title: String, year: Int) =
        MediaItem(id = id, kind = MediaItem.Kind.episode, title = title, seriesTitle = show, seriesID = show?.let { "s-$it" },
            season = 1, episode = 1, year = year, runtime = 22 * 60.0)
    private val library = listOf(
        movie("m1", "Amélie", 2001),
        movie("m2", "The Godfather", 1972, minutes = 175),
        movie("m3", "The Godfather Part II", 1974),
        movie("m4", "Godzilla", 2014),
        movie("m5", "Godzilla", 1954),
        movie("m6", "GODZILLA", 1954),            // a second copy of m5: one result
        movie("m7", "Godzilla", null),            // no year: counts as 0
        movie("m8", "Spider-Man", 2002),
        movie("m9", "Schindler’s List", 1993),
        movie("m10", "Office Space", 1999, minutes = 89),
        movie("m11", "A Bug's Life", 1998),
        movie("m12", "Fargo", 1996, minutes = 98),
        movie("m13", "Man on Fire", 2004),
        movie("m14", "Batman", 1989),
        episode("e1", "The Office", "Pilot", 2006),
        episode("e2", "The Office", "Diversity Day", 2005),
        episode("e3", "Fargo", "The Crocodile's Dilemma", 2014),
        episode("e4", null, "Godzilla Returns", 2000),   // no series title: not searchable
    )
    private val index = TitleSearch.index(library)

    private fun titles(q: String) = TitleSearch.search(q, index).map { it.title }
    private fun found(q: String) = TitleSearch.search(q, index).map { "${it.title} ${it.year?.toString() ?: "-"}" }

    @Test fun testCapitalsAccentsAndPunctuation() {
        assertEquals(listOf("Office Space"), titles("OFFICE SPACE"))
        assertEquals(listOf("Amélie"), titles("amelie"))
        assertEquals(listOf("Amélie"), titles("AMÉLIE"))
        for (q in listOf("spider-man", "spiderman", "spider man", "SPIDER MAN!")) assertEquals(q, listOf("Spider-Man"), titles(q))
        assertEquals(listOf("Schindler’s List"), titles("schindlers list"))
        assertEquals(listOf("Schindler’s List"), titles("schindler's"))
    }

    /** A word-final sigma is ς typed and Σ in capitals: both match σ, so capitals don't matter in Greek either. */
    @Test fun testFinalSigma() {
        val greek = TitleSearch.index(listOf(movie("g1", "ΖΟΡΜΠΑΣ", 1964), movie("g2", "Ζορμπάς", 1965)))
        for (q in listOf("Ζορμπάς", "ΖΟΡΜΠΑΣ", "ζορμπασ")) assertEquals(q, listOf("ΖΟΡΜΠΑΣ", "Ζορμπάς"), TitleSearch.search(q, greek).map { it.title })
    }

    /** "office" is The Office exactly (its article needn't be typed), so it comes before Office Space, which only starts
     *  with it; typing the article works too. */
    @Test fun testLeadingArticle() {
        assertEquals(listOf("The Office", "Office Space"), titles("office"))
        assertEquals(listOf("The Office"), titles("the office"))
        assertEquals(listOf("A Bug's Life"), titles("bugs life"))
        assertEquals(listOf("A Bug's Life"), titles("a bug"))
        assertEquals(listOf("The Godfather", "The Godfather Part II"), titles("godfather"))
    }

    @Test fun testPartialWords() {
        assertEquals(listOf("The Godfather", "The Godfather Part II"), titles("godf"))
        assertEquals(listOf("The Godfather", "The Godfather Part II"), titles("father"))
        assertEquals(listOf("Spider-Man"), titles("spiderm"))
    }

    /** Exact titles, then titles that start with the query, then any other match, each by title then year (none = 0). */
    @Test fun testOrderAndTies() {
        assertEquals(listOf("The Godfather 1972", "The Godfather Part II 1974", "Godzilla -", "Godzilla 1954", "Godzilla 2014"), found("god"))
        assertEquals(listOf("Man on Fire 2004", "Batman 1989", "Spider-Man 2002"), found("man"))
        assertEquals(listOf("Godzilla -", "Godzilla 1954", "Godzilla 2014"), found("godzilla"))
        assertEquals(listOf("Fargo 1996", "Fargo 2014"), found("fargo"))
    }

    /** Films by title, shows by series title once each (their year is the earliest episode's); episode titles never match. */
    @Test fun testMoviesAndShows() {
        val office = TitleSearch.search("the office", index)
        assertEquals(listOf(true), office.map { it.isShow })
        assertEquals(2005, office.firstOrNull()?.year)
        assertEquals(listOf(false, true), TitleSearch.search("fargo", index).map { it.isShow })
        assertEquals(emptyList<String>(), titles("pilot"))
        assertEquals(emptyList<String>(), titles("diversity"))
        assertEquals(emptyList<String>(), titles("returns"))
    }

    @Test fun testCapAndNoResults() {
        val many = TitleSearch.index((1..60).map { movie("n$it", "Night $it", 2000) })
        val r = TitleSearch.search("night", many)
        assertEquals(TitleSearch.limit, r.size)
        assertEquals(50, TitleSearch.limit)
        assertEquals(listOf("Night 1", "Night 10", "Night 11"), r.take(3).map { it.title })   // by title key, as text
        assertEquals(emptyList<String>(), titles("zzz"))
        assertEquals(emptyList<String>(), titles(""))
        assertEquals(emptyList<String>(), titles("!!"))
    }

    // MARK: Airs next

    private val utc: ZoneId = ZoneOffset.UTC
    private fun at(d: Int, h: Int, m: Int = 0): Instant = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, utc).toInstant()
    private val clockFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(utc)
    private fun clock(d: Instant): String = clockFormat.format(d)
    private fun item(id: String) = library.first { it.id == id }
    private fun slot(id: String, start: Instant, end: Instant) = Slot(item(id), start, end)

    /** Now is 7:20 PM on the 27th. Each entry's earliest airing in the next 24 hours, as the result rows say it. */
    @Test fun testAirsNext() {
        val now = at(27, 19, 20)
        val c5 = Channel(id = "c5", number = 5, name = "Fright Night", items = emptyList())
        val c9 = Channel(id = "c9", number = 9, name = "Studio Audience", items = emptyList())
        val c22 = Channel(id = "c22", number = 22, name = "Gumshoe", items = emptyList())
        val lineups = listOf(
            c22 to listOf(slot("m10", at(27, 17, 30), at(27, 19, 30)),   // Office Space: in its break now (ended 6:59)
                          slot("m12", at(27, 17, 30), at(27, 19, 30)),   // Fargo: in its break, and not on again
                          slot("m1", at(27, 20), at(27, 22)),
                          slot("m9", at(27, 21), at(27, 23)),
                          slot("m8", at(27, 22), at(28, 0)),
                          slot("m2", at(27, 23), at(28, 2))),
            c5 to listOf(slot("m2", at(27, 18, 40), at(27, 21, 30)),     // The Godfather: on now, 40 min in
                         slot("m9", at(27, 21), at(27, 23)),             // same start as on 22: the lower number wins
                         slot("m1", at(27, 23, 30), at(28, 1, 30)),      // later than on 22: 22 wins
                         slot("m10", at(28, 14), at(28, 16)),
                         slot("m3", at(28, 19, 30), at(28, 21, 30))),    // past the 24 hours
            c9 to listOf(slot("e1", at(27, 23), at(27, 23, 30)),
                         slot("e2", at(27, 20, 30), at(27, 21))),        // the show's next episode
        )
        val airs = TitleSearch.nextAirings(lineups, index, now)
        fun text(q: String) = TitleSearch.airsText(airs[TitleSearch.search(q, index)[0].id], now, utc) { clock(it) }
        assertEquals("On now · Ch 5 Fright Night, 40 min in", text("the godfather"))
        assertEquals("Tomorrow 2:00 PM · Ch 5 Fright Night", text("office space"))
        assertEquals("Not scheduled in the next 24 hours", text("fargo"))
        assertEquals("Tonight 8:00 PM · Ch 22 Gumshoe", text("amelie"))
        assertEquals("Tonight 9:00 PM · Ch 5 Fright Night", text("schindlers list"))
        assertEquals("Tonight 10:00 PM · Ch 22 Gumshoe", text("spider-man"))
        assertEquals("Tonight 8:30 PM · Ch 9 Studio Audience", text("the office"))
        assertEquals("Not scheduled in the next 24 hours", text("godfather part ii"))
        assertEquals("c5", airs[TitleSearch.search("godfather", index)[0].id]?.channelID)
    }

    /** Later today is "Today" before 6 PM and "Tonight" from then; the next calendar day is "Tomorrow". */
    @Test fun testAirsTextDays() {
        fun text(start: Instant, now: Instant) = TitleSearch.airsText(TitleSearch.Airing("c22", 22, "Gumshoe", start), now, utc) { clock(it) }
        assertEquals("Today 3:00 PM · Ch 22 Gumshoe", text(at(27, 15), now = at(27, 9)))
        assertEquals("Today 5:30 PM · Ch 22 Gumshoe", text(at(27, 17, 30), now = at(27, 9)))
        assertEquals("Tonight 6:00 PM · Ch 22 Gumshoe", text(at(27, 18), now = at(27, 9)))
        assertEquals("Tomorrow 2:00 AM · Ch 22 Gumshoe", text(at(28, 2), now = at(27, 19, 20)))
        assertEquals("On now · Ch 22 Gumshoe, 0 min in", text(at(27, 9), now = at(27, 9)))
        assertEquals("Not scheduled in the next 24 hours", TitleSearch.airsText(null, at(27, 9), utc) { clock(it) })
    }

    /** Saturday 11:30 PM before a spring-forward Sunday (23 hours) in Los Angeles: 24 hours on is 12:30 AM Monday, which
     *  "Tomorrow" would say as Sunday's. The window stops at the end of tomorrow. */
    @Test fun testSpringForwardNight() {
        val la = ZoneId.of("America/Los_Angeles")
        fun march(d: Int, h: Int, m: Int = 0): Instant = ZonedDateTime.of(2027, 3, d, h, m, 0, 0, la).toInstant()
        val now = march(13, 23, 30)
        val c22 = Channel(id = "c22", number = 22, name = "Gumshoe", items = emptyList())
        val airs = TitleSearch.nextAirings(listOf(c22 to listOf(slot("m8", march(14, 23), march(15, 0)), slot("m1", march(15, 0), march(15, 2)))),
            index, now, calendar = la)
        val laClock = DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(la)
        fun text(q: String) = TitleSearch.airsText(airs[TitleSearch.search(q, index)[0].id], now, la) { laClock.format(it) }
        assertEquals("Tomorrow 11:00 PM · Ch 22 Gumshoe", text("spider-man"))
        assertEquals("Not scheduled in the next 24 hours", text("amelie"))
    }
}
