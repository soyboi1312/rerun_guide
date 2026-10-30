package com.rerunguide.core

import java.time.Instant
import kotlin.math.floor
import kotlin.math.roundToLong

// Swift `Date` ↔ `java.time.Instant`, `TimeInterval` ↔ Double seconds.

/** `Date.addingTimeInterval(_:)`. */
fun Instant.plusSeconds(d: Double): Instant {
    val whole = floor(d)
    return plusSeconds(whole.toLong()).plusNanos(((d - whole) * 1e9).roundToLong())
}

/** `Date.timeIntervalSince(_:)`. */
fun Instant.secondsSince(other: Instant): Double =
    (epochSecond - other.epochSecond).toDouble() + (nano - other.nano) / 1e9

/** `Date.timeIntervalSince1970`. */
val Instant.epochSeconds: Double get() = epochSecond + nano / 1e9

/** `Date(timeIntervalSince1970:)`. */
fun instantOf(epochSeconds: Double): Instant = Instant.EPOCH.plusSeconds(epochSeconds)
