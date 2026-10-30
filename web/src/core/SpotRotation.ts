// Port of android/core SpotRotation.kt.
import { JSONObject, optInt, putOpt, stringOrNull } from './Lang'

/**
 * The house-spot rotation: every `every`th trailer break opens with one of Soyboi's spots, and the spots take turns. A
 * break is counted once, by id, so tuning away and back to the same break neither counts it twice nor replays the spot.
 * The app persists it (`toJson` / `fromJson`), so the count carries across launches. `breaks` and `lastBreak` are
 * `private set` in Kotlin: only `next` writes them.
 */
export class SpotRotation {
  breaks = 0
  lastBreak: string | null = null

  static readonly every = 15

  /** Count a break that is starting; true when it's this break's turn for the spot. */
  next(breakID: string): boolean {
    if (breakID === this.lastBreak) return false
    this.lastBreak = breakID; this.breaks += 1
    return this.breaks % SpotRotation.every === 0
  }

  /** Which of `count` spots the break `next` just said yes to plays: the first, then the second, … then round again. */
  pick(count: number): number {
    return count > 0 ? Math.max(0, Math.trunc(this.breaks / SpotRotation.every) - 1) % count : 0
  }

  toJson(): JSONObject {
    return putOpt({ breaks: this.breaks }, 'lastBreak', this.lastBreak)
  }

  static fromJson(o: JSONObject): SpotRotation {
    const r = new SpotRotation()
    r.breaks = optInt(o, 'breaks', 0)
    r.lastBreak = stringOrNull(o, 'lastBreak')
    return r
  }
}
