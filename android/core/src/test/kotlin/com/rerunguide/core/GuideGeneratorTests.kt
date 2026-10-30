package com.rerunguide.core

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class GuideGeneratorTests {
    private val cal: ZoneId = ZoneId.of("America/Los_Angeles")
    private val block = GuideGenerator.blockLength
    private val demo = DemoMediaServer.items

    private fun date(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0): Instant = ZonedDateTime.of(y, m, d, h, min, 0, 0, cal).toInstant()
    private fun channel(id: String, vararg minutes: Int) = Channel(id = id, number = 2, name = id, items = minutes.mapIndexed { i, m ->
        MediaItem(id = "$id-$i", kind = MediaItem.Kind.movie, title = "${m}m", runtime = (m * 60).toDouble())
    })
    private val lineup: List<Channel> get() = ChannelBuilder.channels(from = demo)
    private val comedy: Channel get() = lineup.first { it.id == "genre:Comedy:tv" }

    private fun assertTiles(slots: List<Slot>, day: Instant) {
        val start = day.atZone(cal).toLocalDate().atStartOfDay(cal)
        val end = start.toLocalDate().plusDays(1).atStartOfDay(cal).toInstant()
        assertEquals(start.toInstant(), slots.first().start)
        assertEquals(end, slots.last().end)
        for ((a, b) in slots.zipWithNext()) assertEquals(a.end, b.start)
        // On the half-hour grid, except short programs sharing a half hour: those stay inside it.
        fun offset(t: Instant) = t.secondsSince(start.toInstant())
        for (s in slots.dropLast(1)) {
            assertTrue(s.duration > 0)
            val half = floor(offset(s.start) / block)
            if (offset(s.start) % block != 0.0 || offset(s.end) % block != 0.0) {
                assertTrue("${s.item.title} crosses a half hour", offset(s.end) <= (half + 1) * block)
            }
        }
        assertTrue(slots.last().duration > 0)
    }

    // MARK: Scheduler

    @Test fun testDeterministicPerChannelDay() {
        assertTrue(comedy.items.size >= 8)
        val a = GuideGenerator.schedule(comedy, day = date(2026, 1, 5), calendar = cal)
        val b = GuideGenerator.schedule(comedy, day = date(2026, 1, 5, 13, 37), calendar = cal)
        assertEquals(a, b)
        val c = GuideGenerator.schedule(comedy, day = date(2026, 1, 6), calendar = cal)
        assertNotEquals(a.map { it.item.id }, c.map { it.item.id })
    }

    @Test fun testDayTiling() {
        for (ch in lineup) {
            for (d in 0 until 5) assertTiles(GuideGenerator.schedule(ch, day = date(2026, 1, 1 + d), calendar = cal), date(2026, 1, 1 + d))
        }
        val single = GuideGenerator.schedule(channel("x", 140), day = date(2026, 1, 1), calendar = cal)
        assertTiles(single, date(2026, 1, 1))
        assertEquals(9, single.size)                  // 8 × 2.5h, then the 9th film runs to midnight with a 1.5h longer break
        assertEquals(8 * block, single.last().duration, 0.0)
        // No program is ever cut short: every slot holds its whole item (the leftover before midnight is a break).
        for (ch in lineup) {
            for (d in 0 until 14) {
                for (s in GuideGenerator.schedule(ch, day = date(2026, 1, 1 + d), calendar = cal)) {
                    assertTrue("${ch.id} cut ${s.item.title} short", s.duration >= s.item.runtime)
                }
            }
        }
        // The window is filled with the longest item that fits: a 2h and a 3.5h film, 30 min left after 7 × 3.5h.
        val mixed = Channel(id = "m", number = 2, name = "m", items = listOf(
            MediaItem(id = "long", kind = MediaItem.Kind.movie, title = "Long", runtime = 205.0 * 60),   // 3.5h slot
            MediaItem(id = "short", kind = MediaItem.Kind.movie, title = "Short", runtime = 100.0 * 60), // 2h slot
        ), order = PlayOrder.inOrder)
        for (d in 0 until 7) {
            val day = GuideGenerator.schedule(mixed, day = date(2026, 2, 1 + d), calendar = cal)
            assertTiles(day, date(2026, 2, 1 + d))
            assertTrue(day.all { it.duration >= it.item.runtime })
        }
    }

    /** Nothing airs twice in a row: not where the day's last slot is re-picked to run to midnight (say, a 181-minute western
     *  at 5:00 and again at 8:30 PM as the one 3.5-hour film that fills 8:30 to midnight), not a second copy or cut
     *  of the same film, and not across midnight. */
    @Test fun testNoBackToBackRepeats() {
        val key = GuideGenerator::repeatKey
        val repeats = mutableListOf<String>()
        fun check(ch: Channel, days: Int, start: Instant) {
            var previous: Slot? = null
            for (d in 0 until days) {
                val day = start.atZone(cal).plusDays(d.toLong()).toInstant()
                val slots = GuideGenerator.schedule(ch, day = day, calendar = cal)
                assertTiles(slots, day)
                assertTrue(slots.all { it.duration >= it.item.runtime })
                for ((a, b) in slots.zipWithNext()) if (key(a.item) == key(b.item)) repeats.add("${ch.id} day $d ${a.item.id}/${b.item.id}")
                // Across midnight only on a show played in order, or when nothing else fits the last slot: nothing
                // that repeats neither the slot before it nor what opens the next day.
                val last = previous
                if (last != null && key(last.item) == key(slots[0].item) && ch.order != PlayOrder.inOrder) {
                    val yesterday = GuideGenerator.schedule(ch, day = start.atZone(cal).plusDays(d - 1L).toInstant(), calendar = cal)
                    val before = yesterday.dropLast(1).lastOrNull()?.let { key(it.item) }
                    val window = last.end.secondsSince(last.start)
                    if (ch.items.any { key(it) != before && key(it) != key(last.item) && GuideGenerator.slotLength(it) <= window }) {
                        repeats.add("${ch.id} midnight before day $d ${last.item.id}/${slots[0].item.id}")
                    }
                }
                previous = slots.last()
            }
        }
        for (n in 2..6) {
            for (seed in 0 until 40) {
                val rng = SeededGenerator(seed = "repeat|$n|$seed")
                val items = (0 until n).map { i ->
                    MediaItem(id = "f$i", kind = MediaItem.Kind.movie, title = "F$i", year = 1990, runtime = (80 + (rng.next() % 130u).toInt()) * 60.0)
                }.toMutableList()
                if (seed % 2 == 1) {   // a second copy of F0: another item, same title and year
                    items.add(MediaItem(id = "f0-cut", kind = MediaItem.Kind.movie, title = "F0", year = 1990, runtime = items[0].runtime + 1800))
                }
                check(Channel(id = "r$n-$seed", number = 2, name = "r", items = items, order = if (seed % 3 == 0) PlayOrder.inOrder else PlayOrder.shuffle),
                      days = 4, start = date(2026, 3, 1))
            }
        }
        for (ch in lineup.filter { it.items.size > 1 }) check(ch, days = 14, start = date(2026, 1, 1))
        assertEquals(emptyList<String>(), repeats)

        // A channel holding nothing but copies of one film still fills the day.
        val cuts = Channel(id = "cuts", number = 2, name = "cuts", items = listOf(
            MediaItem(id = "a", kind = MediaItem.Kind.movie, title = "Alien", year = 1979, runtime = 116.0 * 60),
            MediaItem(id = "b", kind = MediaItem.Kind.movie, title = "Alien", year = 1979, runtime = 115.0 * 60),
        ))
        val day = GuideGenerator.schedule(cuts, day = date(2026, 3, 1), calendar = cal)
        assertTiles(day, date(2026, 3, 1))
        assertEquals(12, day.size)   // 2h slots back to back
        // ...even next to an item too long to ever air, instead of one slot with a 22-hour break.
        val stuck = Channel(id = "stuck", number = 2, name = "stuck", items = listOf(cuts.items[0],
            MediaItem(id = "huge", kind = MediaItem.Kind.movie, title = "Huge", year = 1990, runtime = 30.0 * 3600)))
        for (d in 0 until 4) {
            val slots = GuideGenerator.schedule(stuck, day = date(2026, 1, 1 + d), calendar = cal)
            assertTiles(slots, date(2026, 1, 1 + d))
            assertEquals(12, slots.size)
        }
        // Where a DST change skips midnight (Cairo, 2024-04-26), one day ends exactly where the next begins.
        val cairo = ZoneId.of("Africa/Cairo")
        for (ch in lineup.take(6)) {
            val (a, b, c) = (25..27).map { GuideGenerator.schedule(ch, day = ZonedDateTime.of(2024, 4, it, 12, 0, 0, 0, cairo).toInstant(), calendar = cairo) }
            assertEquals(ch.id, a.last().end, b.first().start)
            assertEquals(ch.id, b.last().end, c.first().start)
        }
    }

    /** A short program doesn't leave most of its half hour empty: the next ones in order that fit play right after it
     *  (8-minute episodes: three to a half hour, the last keeping the rest), and nothing crosses the half hour. */
    @Test fun testShortProgramsShareTheirHalfHour() {
        val eps = (1..12).map { MediaItem(id = "e$it", kind = MediaItem.Kind.episode, title = "E$it", seriesTitle = "Toons", seriesID = "toons",
                                          season = 1, episode = it, runtime = 8 * 60 + 0.4) }
        val toons = Channel(id = "toons", number = 2, name = "Toons", items = eps, order = PlayOrder.inOrder)
        val slots = GuideGenerator.schedule(toons, day = date(2026, 3, 1), calendar = cal)
        assertTiles(slots, date(2026, 3, 1))
        assertEquals(listOf(480.0, 480.0, 840.0), slots.take(3).map { it.duration })
        assertEquals(48 * 3, slots.size)
        val order = eps.map { it.id }
        for ((a, b) in slots.zipWithNext()) {   // still in order, episode after episode
            assertEquals(order.indexOf(b.item.id), (order.indexOf(a.item.id) + 1) % order.size)
        }
        assertTrue(slots.all { it.duration >= floor(it.item.runtime) })
        // Up to half its half hour (15 minutes, the pad not counted) shares it: 13-minute episodes go two to a half hour.
        val thirteen = Channel(id = "thirteen", number = 2, name = "Thirteen", items = (1..4).map {
            MediaItem(id = "t$it", kind = MediaItem.Kind.episode, title = "T$it", seriesTitle = "Thirteen", seriesID = "thirteen", season = 1, episode = it, runtime = 13 * 60.0)
        }, order = PlayOrder.inOrder)
        assertEquals(listOf(780.0, 1020.0), GuideGenerator.schedule(thirteen, day = date(2026, 3, 1), calendar = cal).take(2).map { it.duration })
        // 16 minutes is more than half its half hour: it keeps the slot to itself.
        val longer = Channel(id = "longer", number = 2, name = "Longer", items = (1..4).map {
            MediaItem(id = "l$it", kind = MediaItem.Kind.episode, title = "L$it", seriesTitle = "Longer", seriesID = "longer", season = 1, episode = it, runtime = 16 * 60.0)
        }, order = PlayOrder.inOrder)
        assertTrue(GuideGenerator.schedule(longer, day = date(2026, 3, 1), calendar = cal).all { it.duration == block })
    }

    /** Two cuts of a film are two films (titled apart, free to follow each other); two plain copies are one. */
    @Test fun testEditionsAreDifferentFilms() {
        assertEquals("Watchmen (Directors Cut)", MediaItem.title("Watchmen", "Directors Cut"))
        assertEquals("Watchmen", MediaItem.title("Watchmen", " "))
        assertEquals("Watchmen", MediaItem.title("Watchmen", null))
        assertEquals("Ultimate Version", MediaItem.edition("/films/Watchmen (2009) - {imdb-tt0409459}{edition-Ultimate Version}[Bluray-1080p].mkv"))
        assertNull(MediaItem.edition("/films/Watchmen (2009) - {imdb-tt0409459}[Bluray-1080p].mkv"))
        assertNull(MediaItem.edition(null))
        fun film(id: String, edition: String?) =
            MediaItem(id = id, kind = MediaItem.Kind.movie, title = MediaItem.title("Watchmen", edition), year = 2009, runtime = 186.0 * 60)
        assertNotEquals(GuideGenerator.repeatKey(film("a", null)), GuideGenerator.repeatKey(film("b", "Directors Cut")))
        assertEquals(GuideGenerator.repeatKey(film("a", null)), GuideGenerator.repeatKey(film("c", null)))   // a 4K copy and a 1080p copy
    }

    @Test fun testBlockRounding() {
        assertEquals(block, GuideGenerator.schedule(channel("a", 22), day = date(2026, 1, 1), calendar = cal).first().duration, 0.0)
        assertEquals(4 * block, GuideGenerator.schedule(channel("b", 95), day = date(2026, 1, 1), calendar = cal).first().duration, 0.0)
        // 30 min + the 3-minute pad spills into a second block (a real guide never jams a 30-min show into one 30-min slot).
        assertEquals(2 * block, GuideGenerator.schedule(channel("c", 30), day = date(2026, 1, 1), calendar = cal).first().duration, 0.0)
        assertEquals(block, GuideGenerator.schedule(channel("d", 27), day = date(2026, 1, 1), calendar = cal).first().duration, 0.0)
    }

    @Test fun testDSTDaysTileMidnightToMidnight() {
        for ((day, hours) in listOf(date(2026, 3, 8) to 23.0, date(2026, 11, 1) to 25.0)) {
            val slots = GuideGenerator.schedule(comedy, day = day, calendar = cal)
            assertTiles(slots, day)
            assertEquals(hours * 3600, slots.sumOf { it.duration }, 0.0)
        }
    }

    @Test fun testWindowSpanningMidnight() {
        val from = date(2026, 1, 5, 23)
        val to = date(2026, 1, 6, 1)
        val slots = GuideGenerator.schedule(comedy, from = from, to = to, calendar = cal)
        assertFalse(slots.isEmpty())
        assertTrue(slots.all { it.end > from && it.start < to })
        assertTrue(slots.any { it.start < date(2026, 1, 6) })
        assertTrue(slots.any { it.start >= date(2026, 1, 6) })
        assertTrue(slots.first().start <= from)
        assertTrue(slots.last().end >= to)
        for ((a, b) in slots.zipWithNext()) assertEquals(a.end, b.start)
        val full = GuideGenerator.schedule(comedy, day = from, calendar = cal) + GuideGenerator.schedule(comedy, day = to, calendar = cal)
        assertEquals(full.filter { it.end > from && it.start < to }, slots)
    }

    @Test fun testStatus() {
        val slot = GuideGenerator.schedule(channel("a", 22), day = date(2026, 1, 1), calendar = cal)[0]
        assertEquals(block, slot.duration, 0.0)
        assertEquals(ProgramStatus.later, GuideGenerator.status(slot, at = date(2025, 12, 31, 23, 59)))
        assertEquals(ProgramStatus.live, GuideGenerator.status(slot, at = slot.start))
        assertEquals(ProgramStatus.live, GuideGenerator.status(slot, at = date(2026, 1, 1, 0, 21)))
        assertEquals(ProgramStatus.`break`, GuideGenerator.status(slot, at = date(2026, 1, 1, 0, 22)))
        assertEquals(ProgramStatus.`break`, GuideGenerator.status(slot, at = date(2026, 1, 1, 0, 25)))
        assertEquals(ProgramStatus.past, GuideGenerator.status(slot, at = slot.end))
        assertEquals(ProgramStatus.past, GuideGenerator.status(slot, at = date(2026, 1, 1, 9)))
    }

    /** `ProgramKey` stores a slot's start; the generator must hand the same slot back from it, on every channel and across midnight. */
    @Test fun testSlotStartRoundTrip() {
        for (ch in lineup) {
            val from = date(2026, 1, 5, 22)
            val to = date(2026, 1, 6, 3)
            val slots = GuideGenerator.schedule(ch, from = from, to = to, calendar = cal)
            assertTrue(slots.size > 2)
            for (s in slots) {
                assertEquals(s, GuideGenerator.slotStartingAt(ch, startingAt = s.start, calendar = cal))
                assertEquals(s, GuideGenerator.slot(ch, at = s.start, calendar = cal))
            }
        }
        val s = GuideGenerator.schedule(comedy, day = date(2026, 1, 5), calendar = cal)[3]
        assertEquals(s, GuideGenerator.slotStartingAt(comedy, startingAt = s.start.plusSeconds(0.25), calendar = cal)) // survived JSON
        assertNull(GuideGenerator.slotStartingAt(comedy, startingAt = s.start.plusSeconds(60.0), calendar = cal))
    }

    @Test fun testInOrderPlaysInSequenceAndRotatesDaily() {
        val smh = lineup.first { it.id == "series:smh" }
        assertEquals(PlayOrder.inOrder, smh.order)
        val order = smh.items.map { it.id }
        assertEquals(listOf("1-1", "1-2", "1-3", "1-4", "2-1", "2-2", "2-3", "2-4"), smh.items.map { "${it.season!!}-${it.episode!!}" })
        val firsts = mutableSetOf<String>()
        for (d in 0 until 6) {
            val ids = GuideGenerator.schedule(smh, day = date(2026, 2, 1 + d), calendar = cal).map { it.item.id }
            assertEquals(48, ids.size)                            // 22-min episodes → 48 half-hour slots
            for ((a, b) in ids.zipWithNext()) {                   // every slot is the next episode, wrapping
                assertEquals((order.indexOf(a) + 1) % order.size, order.indexOf(b))
            }
            firsts.add(ids[0])
        }
        assertTrue(firsts.size > 1)                               // not every day starts on the same episode
    }

    // MARK: ChannelBuilder

    @Test fun testAutomaticLineup() {
        val defs = ChannelBuilder.automaticChannels(from = demo)
        assertEquals(ChannelBuilder.automaticChannels(from = demo), defs)
        assertEquals(lineup.map { it.id }, defs.map { it.id })            // every automatic channel has something to air

        // Numbers: start at 2, strictly increasing, with gaps like cable channel numbers.
        assertEquals(2, defs.first().number)
        val nums = defs.map { it.number }
        assertEquals(nums.sorted(), nums); assertEquals(nums.size, nums.toSet().size)
        assertTrue(nums.zipWithNext().all { (a, b) -> (b - a) in 1..3 })
        assertTrue(nums.zipWithNext().any { (a, b) -> b - a > 1 })

        // Lead channels first (the demo library is 1978–1999 with movies added over the last ~120 days, none watched →
        // only Just Added and Unwatched).
        assertEquals("lead:just-added", defs.first().id)
        assertEquals("Just Added", defs.first().name)
        assertEquals("lead:unwatched", defs[1].id)
        assertFalse(defs.any { it.id == "lead:new-movies" })
        // The rest is scattered, not alphabetical, but identical on every call.
        val restNames = defs.drop(1).map { it.name }
        assertNotEquals(restNames.sortedWith(::swiftCompare), restNames)
        assertEquals(ChannelBuilder.automaticChannels(from = demo).drop(1).map { it.name }, restNames)

        fun def(id: String): ChannelDefinition = defs.firstOrNull { it.id == id } ?: throw AssertionError("missing $id")
        // Split genre with retro names: lots of comedy films and lots of comedy episodes.
        val cm = def("genre:Comedy:movies")
        val ct = def("genre:Comedy:tv")
        assertEquals(listOf("Funny Business", "Comedy films, 80s–90s"), listOf(cm.name, cm.tag))
        assertEquals(listOf(RuleChip.genre("Comedy"), RuleChip.years(1980, 1999), RuleChip.movies), cm.rule)
        assertEquals(listOf("Studio Audience", "Sitcoms, 80s–90s"), listOf(ct.name, ct.tag))
        assertEquals(listOf(RuleChip.genre("Comedy"), RuleChip.years(1980, 1994), RuleChip.tv), ct.rule)   // decade floor to the latest year
        assertFalse(defs.any { it.id == "genre:Comedy" })
        // Never mixed: Sci-Fi is films only (a films channel); Crime is episodes plus two films, too few for a films channel.
        val sf = def("genre:Sci-Fi:movies")
        val crime = def("genre:Crime:tv")
        assertEquals(listOf("Final Frontier", "Sci-fi films, 80s–90s"), listOf(sf.name, sf.tag))
        assertEquals(listOf(RuleChip.genre("Sci-Fi"), RuleChip.years(1980, 1997), RuleChip.movies), sf.rule)
        assertEquals("Gumshoe", crime.name)
        assertFalse(defs.any { it.id in setOf("genre:Crime", "genre:Crime:movies", "genre:Sci-Fi", "genre:Sci-Fi:tv") })
        assertTrue(ChannelBuilder.resolve(crime, demo).all { it.kind == MediaItem.Kind.episode })
        for (d in defs) assertEquals(d.id, 1, ChannelBuilder.resolve(d, demo).map { it.kind }.toSet().size)   // no automatic channel airs films and TV together
        // Marathons, directors, actors, years.
        val smh = def("series:smh")
        assertEquals(listOf("Strip Mall Heroes", "Marathon"), listOf(smh.name, smh.tag)); assertEquals(PlayOrder.inOrder, smh.order)
        assertEquals(listOf(RuleChip.series(id = "smh", title = "Strip Mall Heroes")), smh.rule)
        assertEquals(3, defs.count { it.id.startsWith("series:") })
        assertEquals(setOf("Dusty Calhoun", "Len Okonkwo", "Marguerite Vole"), defs.filter { it.id.startsWith("director:") }.map { it.name }.toSet())
        assertEquals(listOf(RuleChip.director("Marguerite Vole"), RuleChip.movies), def("director:Marguerite Vole").rule); assertEquals("Director", def("director:Marguerite Vole").tag)
        assertEquals(setOf("Dolores Finch", "Kip Navarro", "Rex Tallow"), defs.filter { it.id.startsWith("actor:") }.map { it.name }.toSet())
        assertEquals(listOf(RuleChip.actor("Rex Tallow"), RuleChip.movies), def("actor:Rex Tallow").rule); assertEquals("Actor", def("actor:Rex Tallow").tag)
        assertEquals(listOf(listOf("year:1985", "Class of ’85", "Films from 1985")), defs.filter { it.id.startsWith("year:") }.map { listOf(it.id, it.name, it.tag) })
        assertEquals(listOf(RuleChip.year(1985), RuleChip.movies), def("year:1985").rule)
        assertTrue(defs.all { !it.isCustom && it.trailerBreaks })

        // Thresholds and edge cases.
        assertEquals(listOf("lead:unwatched"), ChannelBuilder.channels(from = demo, minItems = 10).map { it.id }.filter { !it.startsWith("genre:") })   // all 20 films
        assertTrue(ChannelBuilder.channels(from = demo, minItems = 10).any { it.id == "genre:Comedy:tv" })   // too few films for a films channel: TV only
        assertEquals(emptyList<Channel>(), ChannelBuilder.channels(from = demo, minItems = 1000))
        assertEquals(emptyList<Channel>(), ChannelBuilder.channels(from = emptyList()))
        val noID = (1..4).map { i -> MediaItem(id = "n$i", kind = MediaItem.Kind.episode, title = "e$i", seriesTitle = "Orphan Show", episode = i, runtime = 1.0) }
        assertEquals(listOf("series:Orphan Show"), ChannelBuilder.channels(from = noID).map { it.id })
        assertEquals(2, ChannelBuilder.channels(from = noID).first().number)
    }

    @Test fun testYearChannelsCappedAtThreeBiggest() {
        val lib = mutableListOf<MediaItem>()
        for ((y, n) in listOf(1979 to 4, 1985 to 6, 1988 to 5, 1992 to 4, 2005 to 7)) {
            for (i in 0 until n) lib.add(MediaItem(id = "$y-$i", kind = MediaItem.Kind.movie, title = "$y $i", year = y, runtime = 5400.0))
        }
        val years = ChannelBuilder.automaticChannels(from = lib).filter { it.id.startsWith("year:") }
        assertEquals(setOf("year:1985", "year:1988", "year:2005"), years.map { it.id }.toSet())
        assertEquals(setOf("Class of ’05", "Class of ’85", "Class of ’88"), years.map { it.name }.toSet())   // scattered, so compare as a set
    }

    @Test fun testResolveHonoursEveryChip() {
        fun items(rule: List<RuleChip>, order: PlayOrder = PlayOrder.shuffle): List<MediaItem> =
            ChannelBuilder.resolve(ChannelDefinition(id = "t", number = 1, name = "t", tag = "", rule = rule, order = order), demo)
        assertEquals(setOf("m1", "m6", "m11", "m15", "m19"), items(listOf(RuleChip.genre("Horror"))).map { it.id }.toSet())
        val nineties = setOf("m5", "m7", "m8", "m12", "m17", "m18")
        val ninetiesEps = demo.filter { it.seriesID == "smh" || it.seriesID == "p9" }.map { it.id }.toSet()
        assertEquals(nineties + ninetiesEps, items(listOf(RuleChip.years(1990, 1995))).map { it.id }.toSet())
        assertEquals(setOf("m3", "m9", "m14", "m20"), items(listOf(RuleChip.director("Len Okonkwo"))).map { it.id }.toSet())
        assertEquals(listOf("m2", "m5", "m9"), items(listOf(RuleChip.actor("Bea Saltonstall"))).map { it.id })
        assertEquals(8, items(listOf(RuleChip.series(id = "p9", title = "Precinct 9 at Nine"))).size)
        assertEquals(0, items(listOf(RuleChip.series(id = "nope", title = "Precinct 9 at Nine"))).size)       // id wins when the item has one
        assertEquals(listOf("m1", "m4", "m10", "m15"), items(listOf(RuleChip.year(1985))).map { it.id })
        assertEquals(20, items(listOf(RuleChip.movies)).size); assertEquals(24, items(listOf(RuleChip.tv)).size)
        assertEquals(8, items(listOf(RuleChip.genre("Comedy"), RuleChip.tv, RuleChip.years(1990, 1999))).size)   // every chip must match
        assertEquals(listOf(1999, 1995, 1992, 1991, 1985, 1985, 1985), items(listOf(RuleChip.genre("Comedy"), RuleChip.movies), order = PlayOrder.newestFirst).map { it.year })
        assertEquals(listOf("S1 E1", "S1 E2", "S1 E3", "S1 E4", "S2 E1", "S2 E2", "S2 E3", "S2 E4"),
            items(listOf(RuleChip.series(id = "smh", title = "")), order = PlayOrder.inOrder).map { it.seasonEpisode })
        assertEquals(emptyList<Channel>(), ChannelBuilder.channels(listOf(ChannelDefinition(id = "x", number = 1, name = "x", tag = "", rule = listOf(RuleChip.year(1900)))), demo))
    }

    @Test fun testCandidates() {
        val directors = ChannelBuilder.candidates(ChannelBuilder.CandidateKind.director, demo)
        assertEquals(listOf("Marguerite Vole 7", "Dusty Calhoun 6", "Len Okonkwo 4", "Priya Marsh 3"), directors.map { "${it.name} ${it.count}" })
        val actors = ChannelBuilder.candidates(ChannelBuilder.CandidateKind.actor, demo)
        assertEquals(listOf("Dolores Finch 9", "Kip Navarro 8", "Rex Tallow 8"), actors.take(3).map { "${it.name} ${it.count}" })
        assertEquals(List(5) { 3 }, actors.drop(3).map { it.count })
        assertEquals(3, ChannelBuilder.candidates(ChannelBuilder.CandidateKind.actor, demo, minItems = 4).size)
        val series = ChannelBuilder.candidates(ChannelBuilder.CandidateKind.series, demo)
        assertEquals(listOf("Cul-de-Sac Confidential 8", "Precinct 9 at Nine 8", "Strip Mall Heroes 8"), series.map { "${it.name} ${it.count}" })
        assertEquals(listOf("cds", "p9", "smh"), series.map { it.id })
        assertTrue(directors.all { it.id == null })
    }

    // MARK: Demo server

    @Test fun testDemoLibraryFiltersBySection() = runBlocking {
        val server = DemoMediaServer()
        val sections = server.libraries()
        assertEquals(listOf("demo-movies", "demo-tv"), sections.map { it.id })
        val all = server.libraryItems(null)
        assertEquals(44, all.size)
        val movies = server.libraryItems(listOf(sections[0]))
        assertEquals(20, movies.size); assertTrue(movies.all { it.kind == MediaItem.Kind.movie && it.libraryID == "demo-movies" })
        val tv = server.libraryItems(listOf(sections[1]))
        assertEquals(24, tv.size); assertTrue(tv.all { it.kind == MediaItem.Kind.episode && it.libraryID == "demo-tv" })
        assertTrue(all.all { it.overview?.isEmpty() == false && it.contentRating != null && it.genres.isNotEmpty() })
        assertTrue(movies.all { it.directors.size == 1 && it.actors.size == 2 })
        val trailer = server.trailer(all[0], session = "test")
        assertNull(trailer)
    }

    @Test fun testDecadeTagsPast1999() {
        assertEquals("Action, 50s–2020s", ChannelBuilder.decadeTag("Action", listOf(1955, 2024)))
        assertEquals("Drama, 2000s", ChannelBuilder.decadeTag("Drama", listOf(2001, 2009)))
        assertEquals("Comedy, 80s–90s", ChannelBuilder.decadeTag("Comedy", listOf(1983, 1991)))
    }

    @Test fun testPeopleAndSeriesChannelsAreCapped() {
        // 30 directors with 4 movies each, 30 series with 4 episodes each.
        val lib = mutableListOf<MediaItem>()
        for (d in 0 until 30) {
            for (m in 0 until 4) lib.add(MediaItem(id = "d${d}m$m", kind = MediaItem.Kind.movie, title = "M$d-$m", year = 1990, runtime = 5400.0, directors = listOf("Dir $d")))
            for (e in 0 until 4) lib.add(MediaItem(id = "s${d}e$e", kind = MediaItem.Kind.episode, title = "E$e", seriesTitle = "Show $d", seriesID = "s$d", season = 1, episode = e + 1, runtime = 1320.0))
        }
        val defs = ChannelBuilder.automaticChannels(from = lib, maxSeries = 5, maxPeople = 3)
        assertEquals(5, defs.count { it.tag == "Marathon" })
        assertEquals(3, defs.count { it.tag == "Director" })
        assertEquals(24, ChannelBuilder.automaticChannels(from = lib).count { it.tag == "Marathon" })
    }

    @Test fun testLeadChannelsAndRelativeChip() {
        val now = instantOf(1_800_000_000.0)   // 2027-01-15
        val y = now.atZone(ZoneId.systemDefault()).year
        val lib = mutableListOf<MediaItem>()
        for (i in 0 until 5) lib.add(MediaItem(id = "nm$i", kind = MediaItem.Kind.movie, title = "New $i", year = y - (i % 2), runtime = 5400.0))
        for (i in 0 until 5) lib.add(MediaItem(id = "nt$i", kind = MediaItem.Kind.episode, title = "Ep $i", seriesTitle = "Fresh", seriesID = "fresh", season = 1, episode = i, year = y, runtime = 1320.0))
        for (i in 0 until 5) lib.add(MediaItem(id = "old$i", kind = MediaItem.Kind.episode, title = "Old $i", seriesTitle = "Vintage", seriesID = "vin", year = 1962, runtime = 1500.0, addedAt = now.plusSeconds(-i.toDouble() * 86_400)))
        val ids = ChannelBuilder.automaticChannels(from = lib, now = now).map { it.id }
        assertEquals(listOf("lead:new-movies", "lead:new-tv", "lead:unwatched", "lead:just-added-tv", "lead:classic-tv"), ids.take(5))   // only episodes were added
        assertEquals(5, ChannelBuilder.resolve(ChannelDefinition(id = "x", number = 0, name = "", tag = "", rule = listOf(RuleChip.addedWithin(days = 60))), lib, now = now).size)
        assertEquals(3, ChannelBuilder.resolve(ChannelDefinition(id = "x", number = 0, name = "", tag = "", rule = listOf(RuleChip.addedWithin(days = 2))), lib, now = now).size)   // today, yesterday, two days ago
        assertEquals("Before 1970", RuleChip.years(1900, 1969).label)
    }

    /**
     * Unwatched airs the films this account hasn't seen, never a watched one or an episode, right after Just Added,
     * once there are `minItems` of them.
     */
    @Test fun testUnwatchedChannel() {
        val now = instantOf(1_800_000_000.0)
        fun film(id: String, watched: Boolean, added: Instant? = null) =
            MediaItem(id = id, kind = MediaItem.Kind.movie, title = id, year = 1990, runtime = 5400.0, addedAt = added, watched = watched)
        val seen = (0 until 5).map { film("seen$it", watched = true) }
        val unseen = (0 until 4).map { film("new$it", watched = false) }
        val eps = (0 until 6).map { MediaItem(id = "e$it", kind = MediaItem.Kind.episode, title = "E$it", seriesTitle = "Show", seriesID = "show", season = 1, episode = it, runtime = 1320.0) }
        assertEquals("Unwatched", RuleChip.unwatched.label)
        assertTrue(ChannelBuilder.matches(RuleChip.unwatched, unseen[0])); assertFalse(ChannelBuilder.matches(RuleChip.unwatched, seen[0]))
        val ch = ChannelBuilder.channels(from = seen + unseen + eps).first { it.id == "lead:unwatched" }
        assertEquals(listOf("Unwatched", "Films you haven’t seen"), listOf(ch.name, ch.tag))
        assertEquals(listOf(RuleChip.unwatched, RuleChip.movies), ch.definition.rule); assertEquals(PlayOrder.shuffle, ch.order)
        assertEquals(unseen.map { it.id }, ch.items.map { it.id })
        val day = GuideGenerator.schedule(ch, day = now)
        assertFalse(day.isEmpty()); assertTrue(day.all { it.item.kind == MediaItem.Kind.movie && !it.item.watched })
        assertFalse(ChannelBuilder.channels(from = seen + unseen.dropLast(1) + eps).any { it.id == "lead:unwatched" })   // 3 is too few
        val ids = ChannelBuilder.automaticChannels(from = seen + unseen + (0 until 4).map { film("add$it", watched = true, added = now) }, now = now).map { it.id }
        assertEquals(listOf("lead:just-added", "lead:unwatched"), ids.take(2))
    }

    /**
     * `watched` is required when decoding, so a library cache written before it existed is unreadable (and refetched)
     * instead of reading as all unwatched. The chip saves as {"unwatched":{}}, like the Swift and web ports.
     */
    @Test fun testWatchedAndUnwatchedChipCoding() {
        val item = MediaItem(id = "m", kind = MediaItem.Kind.movie, title = "M", runtime = 5400.0, watched = true)
        val json = item.toJson()
        assertEquals(item, MediaItem.fromJson(JSONObject(json.toString())))
        json.remove("watched")
        assertThrows(JSONException::class.java) { MediaItem.fromJson(json) }
        val chips = JSONArray(listOf(RuleChip.unwatched, RuleChip.movies).map { it.toJson() })
        assertEquals("""[{"unwatched":{}},{"movies":{}}]""", chips.toString())
        assertEquals(listOf(RuleChip.unwatched, RuleChip.movies), chips.objects().map { RuleChip.fromJson(it) })
    }

    /**
     * Horror and thriller channels stay grown-up: a kids' title tagged Horror (the animated Gremlins series, Gravity Falls)
     * is left off; an adult animated one (Love, Death & Robots) and an unrated live-action one stay. Other channels keep them.
     */
    @Test fun testKidsTitlesStayOffHorror() {
        fun ep(id: String, genres: List<String>, rating: String?) = MediaItem(id = id, kind = MediaItem.Kind.episode, title = id, seriesTitle = id,
            seriesID = id, season = 1, episode = 1, year = 2023, genres = genres, runtime = 1320.0, contentRating = rating)
        val gremlins = ep("gremlins", listOf("Horror", "Animation", "Comedy"), "TV-PG")
        val lib = listOf(gremlins, ep("falls", listOf("Horror", "Animation", "Family"), "TV-Y7"), ep("ldr", listOf("Horror", "Animation"), "TV-MA"),
            ep("live", listOf("Horror", "Thriller"), null))
        assertEquals(listOf("ldr", "live"), lib.filter { ChannelBuilder.matches(RuleChip.genre("Horror"), it) }.map { it.id })
        assertEquals(listOf("live"), lib.filter { ChannelBuilder.matches(RuleChip.genre("Thriller"), it) }.map { it.id })
        assertTrue(ChannelBuilder.matches(RuleChip.genre("Comedy"), gremlins))
    }

    /**
     * "Creature Feature" is a keyword search inside Horror / Sci-Fi / Action / Adventure: the right word in the wrong genre,
     * or inside another word, stays out.
     */
    @Test fun testThemeChipsAndCreatureFeature() {
        fun movie(i: Int, title: String, genres: List<String>, overview: String? = null) =
            MediaItem(id = "m$i", kind = MediaItem.Kind.movie, title = title, year = 1990, genres = genres, runtime = 5400.0, overview = overview)
        val lib = listOf(
            movie(1, "Gorgo", listOf("Horror"), "A sea monster is captured off the Irish coast."),
            movie(2, "Them!", listOf("Science Fiction"), "Giant ants, the first of the atomic creatures."),
            movie(3, "Monsters, Inc.", listOf("Animation", "Comedy"), "Scaring is their business."),   // right word, wrong genre
            movie(4, "Monster's Ball", listOf("Drama")),                                                 // title only, wrong genre
            movie(5, "Procreature", listOf("Horror")),                                                   // not at a word start
            movie(6, "Bloodlines", listOf("Horror"), "The last vampires of Sarajevo."),
            movie(7, "Kong: Skull Island", listOf("Adventure")),
            movie(9, "Kubo", listOf("Fantasy"), "A boy faces a monster."),                                   // Fantasy isn't searched
            movie(8, "Quiet", listOf("Horror"), "Nothing to see."),
        )
        val creature = ChannelBuilder.channels(from = lib).firstOrNull { it.id == "theme:monsters" }
        assertEquals("Creature Feature", creature?.name)
        assertEquals(listOf("Bloodlines", "Gorgo", "Kong: Skull Island", "Them!"), creature?.items?.map { it.title }?.sorted())
        assertEquals("Genre: Horror / Fantasy", RuleChip.anyGenre(listOf("Horror", "Fantasy")).label)
        assertEquals("Mentions: monster, kaiju, godzilla, creature…", RuleChip.keywords(listOf("monster", "kaiju", "godzilla", "creature", "zombie")).label)
    }

    /** Series chips match by id or by title, years ranges, mixed chips, and an empty rule takes the whole library. */
    @Test fun testResolveRules() {
        val now = instantOf(1_800_000_000.0)
        val noID = (1..4).map { i -> MediaItem(id = "n$i", kind = MediaItem.Kind.episode, title = "e$i", seriesTitle = "Orphan Show", episode = i, runtime = 1.0, addedAt = now) }
        val lib = demo + noID
        fun def(id: String, rule: List<RuleChip>, order: PlayOrder = PlayOrder.shuffle) =
            ChannelDefinition(id = id, number = 0, name = id, tag = "", rule = rule, order = order)
        val defs = ChannelBuilder.automaticChannels(from = lib, now = now) + listOf(
            def("orphan", listOf(RuleChip.series(id = "Orphan Show", title = "Orphan Show")), order = PlayOrder.inOrder),
            def("mixed-series", listOf(RuleChip.series(id = "smh", title = "Orphan Show"))),
            def("span", listOf(RuleChip.years(1980, 1989)), order = PlayOrder.newestFirst),
            def("classic", listOf(RuleChip.years(1900, 1969), RuleChip.tv)),
            def("added", listOf(RuleChip.addedWithin(days = 365))),
            def("added-genre", listOf(RuleChip.addedWithin(days = 365), RuleChip.genre("Comedy"), RuleChip.movies)),
            def("theme", listOf(RuleChip.anyGenre(listOf("Comedy", "Drama")), RuleChip.keywords(listOf("the", "a")))),
            def("nothing", listOf(RuleChip.genre("Nope"))),
            def("empty", emptyList()),
        )
        val brute = defs.mapNotNull { d ->
            val items = ChannelBuilder.resolve(d, lib, now = now)
            if (items.isEmpty()) null else Channel(d, items)
        }
        assertTrue(brute.any { it.id == "orphan" && it.items.size == 4 })
        assertTrue(brute.any { it.id == "mixed-series" && it.items.size == 12 })
        assertFalse(brute.any { it.id == "nothing" })
        assertTrue(brute.any { it.id == "empty" && it.items.size == lib.size })
    }
}
