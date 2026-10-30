package com.rerunguide.core

import org.json.JSONObject

/**
 * The house-spot rotation: every `every`th trailer break opens with one of Soyboi's spots, and the spots take turns. A break is counted once, by id, so
 * tuning away and back to the same break neither counts it twice nor replays the spot. The app persists it
 * (`toJson` / `fromJson`), so the count carries across launches.
 */
class SpotRotation {
    var breaks = 0
        private set
    var lastBreak: String? = null
        private set

    /** Count a break that is starting; true when it's this break's turn for the spot. */
    fun next(breakID: String): Boolean {
        if (breakID == lastBreak) return false
        lastBreak = breakID; breaks += 1
        return breaks % every == 0
    }

    /** Which of `count` spots the break `next` just said yes to plays: the first, then the second, … then round again. */
    fun pick(count: Int): Int = if (count > 0) maxOf(0, breaks / every - 1) % count else 0

    fun toJson(): JSONObject = JSONObject().put("breaks", breaks).putOpt("lastBreak", lastBreak)

    override fun equals(other: Any?) = other is SpotRotation && other.breaks == breaks && other.lastBreak == lastBreak
    override fun hashCode() = breaks * 31 + (lastBreak?.hashCode() ?: 0)

    companion object {
        const val every = 15

        fun fromJson(o: JSONObject) = SpotRotation().apply {
            breaks = o.optInt("breaks", 0)
            lastBreak = o.stringOrNull("lastBreak")
        }
    }
}
