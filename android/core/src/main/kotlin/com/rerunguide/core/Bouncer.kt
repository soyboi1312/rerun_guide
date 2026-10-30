package com.rerunguide.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The bouncing-logo screensaver's path: a `logo`-sized box reflecting inside `stage`, with one promise the DVD logo
 * never made: a corner at least once every two minutes. Plain bouncing can miss the corners for ages (forever, with
 * the wrong speed ratio), so once a corner is overdue the next wall bounce re-aims the vertical speed to land the
 * following horizontal bounce exactly in a corner. The viewer sees an ordinary bounce; the maths sees a deadline.
 *
 * Swift's `(w:, h:)` / `(x:, y:)` tuples are `Pair`s here: `stage = 1920.0 to 1080.0`.
 */
class Bouncer(
    val stage: Pair<Double, Double>,
    val logo: Pair<Double, Double>,
    /** Cruising speeds; the steering only ever slows the vertical one for a single crossing. */
    val speed: Pair<Double, Double> = 110.0 to 80.0,
    start: Pair<Double, Double> = 0.0 to 0.0,
) {
    /** Top-left of the logo, in stage points. */
    var x: Double = min(max(start.first, 0.0), stage.first - logo.first)
        private set
    var y: Double = min(max(start.second, 0.0), stage.second - logo.second)
        private set
    /** Points per second. */
    var vx: Double = speed.first
        private set
    var vy: Double = speed.second
        private set
    /**
     * Steering kicks in when this much time has passed since the last corner. With the default speeds a crossing
     * takes about 14 s, so the worst case is `overdueAfter` + two crossings, still under 120 s.
     */
    var overdueAfter = 80.0
    var time = 0.0
        private set
    var lastCorner = 0.0
        private set
    var corners = 0
        private set
    /** True only on the step that landed in a corner (the view flashes on it). */
    var hitCorner = false
        private set

    fun step(dt: Double) {
        hitCorner = false
        time += dt
        x += vx * dt; y += vy * dt
        val maxX = stage.first - logo.first
        val maxY = stage.second - logo.second
        var hitX = false
        var hitY = false
        if (x <= 0) { x = 0.0; vx = abs(vx); hitX = true } else if (x >= maxX) { x = maxX; vx = -abs(vx); hitX = true }
        if (y <= 0) { y = 0.0; vy = abs(vy); hitY = true } else if (y >= maxY) { y = maxY; vy = -abs(vy); hitY = true }
        // A steered approach lands both walls on the same instant; rounding can put them a frame apart, so a wall
        // hit within a point of the other wall counts as the corner and snaps.
        if (hitX && !hitY) {
            if (y <= 1) { y = 0.0; vy = abs(vy); hitY = true } else if (y >= maxY - 1) { y = maxY; vy = -abs(vy); hitY = true }
        } else if (hitY && !hitX) {
            if (x <= 1) { x = 0.0; vx = abs(vx); hitX = true } else if (x >= maxX - 1) { x = maxX; vx = -abs(vx); hitX = true }
        }
        if (hitX && hitY) {
            corners += 1; lastCorner = time; hitCorner = true
            vx = Math.copySign(speed.first, vx); vy = Math.copySign(speed.second, vy)   // back to cruising after a steered approach
        } else if (hitX) {
            val crossing = maxX / speed.first
            if (time - lastCorner >= overdueAfter) {
                // Aim: reach whichever vertical wall we're heading for exactly when we reach the far horizontal wall.
                val dy = if (vy > 0) maxY - y else y
                vy = Math.copySign(max(dy / crossing, 1.0), vy)
            } else {
                vy = Math.copySign(speed.second, vy)
            }
        }
    }
}
