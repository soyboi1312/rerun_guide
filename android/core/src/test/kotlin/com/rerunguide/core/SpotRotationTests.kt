package com.rerunguide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SpotRotationTests {
    /**
     * Breaks 15, 30 and 45 get the spot; seeing the same break again doesn't count, and the count survives the round
     * trip through the persisted JSON.
     */
    @Test fun testOneBreakIn15() {
        val n = SpotRotation.every
        assertEquals(15, n)
        val r = SpotRotation()
        val hits = mutableListOf<Int>()
        for (i in 1..3 * n) {
            if (r.next("ch|$i")) hits.add(i)
            assertFalse("a retune into break $i must not count or replay", r.next("ch|$i"))
        }
        assertEquals(listOf(n, 2 * n, 3 * n), hits)
        val restored = SpotRotation.fromJson(org.json.JSONObject(r.toJson().toString()))
        assertEquals(r, restored)
        assertEquals(listOf(4 * n), (3 * n + 1..4 * n).filter { restored.next("ch|$it") })
    }

    /** Two spots take turns, one per spot break: 15 → first, 30 → second, 45 → first. */
    @Test fun testSpotsTakeTurns() {
        val r = SpotRotation()
        val played = mutableListOf<Int>()
        for (i in 1..3 * SpotRotation.every) if (r.next("ch|$i")) played += r.pick(2)
        assertEquals(listOf(0, 1, 0), played)
        assertEquals(0, SpotRotation().pick(2))   // a forced spot (the Apple app's -spotOverride debug flag), before any break counted
    }
}
