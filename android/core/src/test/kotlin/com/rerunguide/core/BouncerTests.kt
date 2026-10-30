package com.rerunguide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

class BouncerTests {
    /**
     * Twenty simulated minutes at 60 Hz from several starts and speed ratios, including ones a plain bounce would
     * never corner on: the logo stays on the stage and never goes 120 s without a corner.
     */
    @Test fun testCornerAtLeastEveryTwoMinutes() {
        val stage = 1920.0 to 1080.0
        val logo = 420.0 to 300.0
        val cases = listOf(
            (110.0 to 80.0) to (0.0 to 0.0), (110.0 to 80.0) to (700.0 to 333.0), (100.0 to 100.0) to (10.0 to 500.0),   // equal speeds on a 1500×780 box: no natural corners
            (150.0 to 61.0) to (1200.0 to 90.0), (90.0 to 130.0) to (333.0 to 777.0), (110.0 to 80.0) to (1500.0 to 780.0),
        )
        for ((speed, start) in cases) {
            val b = Bouncer(stage = stage, logo = logo, speed = speed, start = start)
            var worstGap = 0.0
            var last = 0.0
            repeat(20 * 60 * 60) {
                b.step(1.0 / 60)
                assertTrue("off the stage at t=${b.time}", b.x >= 0 && b.x <= stage.first - logo.first && b.y >= 0 && b.y <= stage.second - logo.second)
                if (b.hitCorner) { worstGap = max(worstGap, b.time - last); last = b.time }
            }
            worstGap = max(worstGap, b.time - last)
            assertTrue("speed $speed start $start: ${worstGap}s between corners", worstGap <= 120)
            assertTrue("speed $speed start $start: only ${b.corners} corners in 20 min", b.corners >= 10)
        }
    }

    /** Cruising speed is restored after a steered approach, so the logo doesn't stay slowed for good. */
    @Test fun testSpeedRestoredAfterCorner() {
        val b = Bouncer(stage = 1920.0 to 1080.0, logo = 420.0 to 300.0, speed = 100.0 to 100.0, start = 0.0 to 0.0)   // equal speeds: no natural corners within the deadline
        var sawSteering = false
        repeat(10 * 60 * 60) {
            b.step(1.0 / 60)
            if (abs(b.vy) < 99) sawSteering = true
            if (b.hitCorner) { assertEquals(100.0, abs(b.vy), 0.001); assertEquals(100.0, abs(b.vx), 0.001) }
        }
        assertTrue("the deadline never had to steer: the test speeds corner naturally, pick others", sawSteering)
    }
}
