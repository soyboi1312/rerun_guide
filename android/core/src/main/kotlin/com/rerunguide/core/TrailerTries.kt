package com.rerunguide.core

import java.time.Instant

/**
 * A trailer break's tries: its candidates in order, each tried at most once, and none once `budget` seconds have passed
 * since the first, so a server whose trailers all fail gives the station card the rest of the break.
 */
data class TrailerTries(val tried: List<String> = emptyList(), val since: Instant? = null) {
    /** The first of `candidates` not tried yet; null once each was, or once the budget is spent. */
    fun next(candidates: List<MediaItem>, now: Instant = Instant.now()): MediaItem? {
        if (since != null && now.secondsSince(since) >= budget) return null
        return candidates.firstOrNull { it.id !in tried }
    }

    /** These tries with `item` tried at `now` (the first one starts the clock). */
    fun adding(item: MediaItem, now: Instant = Instant.now()) = TrailerTries(tried + item.id, since ?: now)

    /** These tries with the clock stopped, for a trailer that had started before it failed: the time it played was no
     *  failed attempt, so the next try starts the clock again. Each candidate is still tried once.
     *  ponytail: the stall before that failure isn't counted either; still bounded, by the candidates. */
    fun restarted() = copy(since = null)

    companion object {
        /** A trailer that shows no first frame within this many seconds is given up for the next one. */
        const val startTimeout = 10.0
        /** No new try starts this many seconds after the first one.
         *  ponytail: a try started just inside it still gets its whole start timeout (30 s in all at worst); clamp the
         *  player's start timeout to what is left of the budget if that shows. */
        const val budget = 20.0
    }
}
