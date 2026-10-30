package com.rerunguide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class FreshItemsTests {
    /** A newly added film passed as `fresh` reaches Just Added and New Releases, but not the genre channel it also matches. */
    @Test fun testFreshItemsOnlyReachNewChannels() {
        val now = Instant.ofEpochSecond(1_800_000_000)
        val year = now.atZone(ZoneId.systemDefault()).year
        fun film(id: String, days: Double) = MediaItem(id = id, kind = MediaItem.Kind.movie, title = id, year = year,
            genres = listOf("Horror"), runtime = 5400.0, addedAt = now.plusSeconds(-days * 86_400))
        val library = (0 until 6).map { film("old$it", 10.0) }
        val fresh = listOf(film("new", 0.0))
        val defs = ChannelBuilder.automaticChannels(library, now = now)
        fun ids(chs: List<Channel>, id: String) = chs.firstOrNull { it.id == id }?.items?.map { it.id } ?: emptyList()
        val chs = ChannelBuilder.channels(defs, library, fresh, now)
        assertTrue("new" in ids(chs, "lead:just-added"))
        assertTrue("new" in ids(chs, "lead:new-movies"))
        val shelf = chs.firstOrNull { !ChannelBuilder.isFresh(it.definition) && it.items.any { i -> "Horror" in i.genres } }
        assertNotNull(shelf)
        assertFalse(shelf!!.items.any { it.id == "new" })
        assertEquals(ChannelBuilder.channels(defs, library, emptyList(), now).map { it.id }, ChannelBuilder.channels(defs, library, now).map { it.id })
    }
}
