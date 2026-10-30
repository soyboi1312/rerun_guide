package com.rerunguide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class TrailerTriesTests {
    private val films = listOf("a", "b", "c").map { MediaItem(id = it, kind = MediaItem.Kind.movie, title = it, runtime = 90 * 60.0) }
    private val t0 = Instant.ofEpochSecond(1_800_000_000)

    /** Each candidate once, in order, then none: the station card. */
    @Test fun testEachCandidateOnce() {
        var t = TrailerTries()
        val order = mutableListOf<String>()
        while (true) { val m = t.next(films, t0) ?: break; order += m.id; t = t.adding(m, t0) }
        assertEquals(listOf("a", "b", "c"), order)
        assertEquals(listOf("a", "b", "c"), t.tried)
        assertNull(TrailerTries().next(emptyList(), t0))
    }

    /** The clock starts at the first try: a failure 19 s in still gets the next trailer, one 20 s in gets none. */
    @Test fun testBudget() {
        val t = TrailerTries().adding(films[0], t0)
        assertEquals(t0, t.since)
        assertEquals("b", t.next(films, t0.plusSeconds(19.0))?.id)
        val t2 = t.adding(films[1], t0.plusSeconds(19.0))
        assertEquals(t0, t2.since)   // later tries keep the first one's clock
        assertNull(t2.next(films, t0.plusSeconds(TrailerTries.budget)))
        assertEquals("c", t2.restarted().next(films, t0.plusSeconds(90.0))?.id)   // one that had played: a new clock, the same tries
        assertEquals(10.0, TrailerTries.startTimeout, 0.0)
        assertEquals(20.0, TrailerTries.budget, 0.0)
    }
}
