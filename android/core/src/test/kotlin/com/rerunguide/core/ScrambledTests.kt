package com.rerunguide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrambledTests {
    /** Automatic numbering steps over 99, so the scrambled channel always has its number to itself. */
    @Test fun testNinetyNineIsReserved() {
        val numbers = ChannelBuilder.automaticChannels(from = DemoMediaServer.items).map { it.number }
        assertFalse(numbers.contains(Channel.scrambledNumber))
        assertEquals("numbers stay increasing around the gap", numbers.sorted(), numbers)
        assertEquals(numbers.size, numbers.toSet().size)
    }

    /** Hour-long slots with no trailer break, a few programs to rotate, and nothing the scheduler can't tile. */
    @Test fun testScrambledChannelShape() {
        val ch = Channel.scrambled
        assertEquals(99, ch.number)
        assertTrue(ch.isScrambled)
        assertFalse(ch.definition.trailerBreaks)
        assertTrue(ch.items.size >= 4)
        for (s in GuideGenerator.schedule(ch, day = instantOf(1_800_000_000.0))) {
            assertEquals("${s.item.title} should fill exactly one hour", 3600.0, s.duration, 0.0)
            assertEquals("no pad, so never a break on channel 99", s.item.runtime, s.duration, 0.0)
        }
    }
}
