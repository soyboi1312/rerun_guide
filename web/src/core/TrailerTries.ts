// Port of android/core TrailerTries.kt.
import type { MediaItem } from './Models'
import { Instant, now as clockNow } from './Time'

/**
 * A trailer break's tries: its candidates in order, each tried at most once, and none once `budget` seconds have passed
 * since the first, so a server whose trailers all fail gives the station card the rest of the break.
 */
export class TrailerTries {
  /** A trailer that shows no first frame within this many seconds is given up for the next one. */
  static readonly startTimeout = 10
  /**
   * No new try starts this many seconds after the first one.
   * ponytail: a try started just inside it still gets its whole start timeout (30 s in all at worst); clamp the
   * player's start timeout to what is left of the budget if that shows.
   */
  static readonly budget = 20

  constructor(readonly tried: readonly string[] = [], readonly since: Instant | null = null) {}

  /** The first of `candidates` not tried yet; null once each was, or once the budget is spent. */
  next(candidates: readonly MediaItem[], now: Instant = clockNow()): MediaItem | null {
    if (this.since != null && now - this.since >= TrailerTries.budget) return null
    return candidates.find(it => this.tried.indexOf(it.id) < 0) ?? null
  }

  /** These tries with `item` tried at `now` (the first one starts the clock). */
  adding(item: MediaItem, now: Instant = clockNow()): TrailerTries {
    return new TrailerTries(this.tried.concat(item.id), this.since ?? now)
  }

  /**
   * These tries with the clock stopped, for a trailer that had started before it failed: the time it played was no
   * failed attempt, so the next try starts the clock again. Each candidate is still tried once.
   * ponytail: the stall before that failure isn't counted either; still bounded, by the candidates.
   */
  restarted(): TrailerTries {
    return new TrailerTries(this.tried, null)
  }
}
