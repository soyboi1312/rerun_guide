// Port of android/core Bouncer.kt. Pure IEEE double maths, so it matches Swift bit for bit (the parity fixture checks it).

/** Java `Math.copySign`: the magnitude of `magnitude` with the sign bit of `sign` (-0 counts as negative). */
function copySign(magnitude: number, sign: number): number {
  return sign < 0 || Object.is(sign, -0) ? -Math.abs(magnitude) : Math.abs(magnitude)
}

/**
 * The bouncing-logo screensaver's path: a `logo`-sized box reflecting inside `stage`, with one promise the DVD logo
 * never made: a corner at least once every two minutes. Plain bouncing can miss the corners for ages (forever, with
 * the wrong speed ratio), so once a corner is overdue the next wall bounce re-aims the vertical speed to land the
 * following horizontal bounce exactly in a corner. The viewer sees an ordinary bounce; the maths sees a deadline.
 *
 * Swift's `(w:, h:)` / `(x:, y:)` tuples are `[w, h]` / `[x, y]`: `stage = [1920, 1080]`. The state fields are
 * `private set` in Kotlin: only `step` writes them.
 */
export class Bouncer {
  /** Top-left of the logo, in stage points. */
  x: number
  y: number
  /** Points per second. */
  vx: number
  vy: number
  /**
   * Steering kicks in when this much time has passed since the last corner. With the default speeds a crossing
   * takes about 14 s, so the worst case is `overdueAfter` + two crossings, still under 120 s.
   */
  overdueAfter = 80
  time = 0
  lastCorner = 0
  corners = 0
  /** True only on the step that landed in a corner (the view flashes on it). */
  hitCorner = false

  constructor(
    readonly stage: readonly [number, number],
    readonly logo: readonly [number, number],
    /** Cruising speeds; the steering only ever slows the vertical one for a single crossing. */
    readonly speed: readonly [number, number] = [110, 80],
    start: readonly [number, number] = [0, 0],
  ) {
    this.x = Math.min(Math.max(start[0], 0), stage[0] - logo[0])
    this.y = Math.min(Math.max(start[1], 0), stage[1] - logo[1])
    this.vx = speed[0]
    this.vy = speed[1]
  }

  step(dt: number) {
    this.hitCorner = false
    this.time += dt
    this.x += this.vx * dt; this.y += this.vy * dt
    const maxX = this.stage[0] - this.logo[0]
    const maxY = this.stage[1] - this.logo[1]
    let hitX = false
    let hitY = false
    if (this.x <= 0) { this.x = 0; this.vx = Math.abs(this.vx); hitX = true } else if (this.x >= maxX) { this.x = maxX; this.vx = -Math.abs(this.vx); hitX = true }
    if (this.y <= 0) { this.y = 0; this.vy = Math.abs(this.vy); hitY = true } else if (this.y >= maxY) { this.y = maxY; this.vy = -Math.abs(this.vy); hitY = true }
    // A steered approach lands both walls on the same instant; rounding can put them a frame apart, so a wall
    // hit within a point of the other wall counts as the corner and snaps.
    if (hitX && !hitY) {
      if (this.y <= 1) { this.y = 0; this.vy = Math.abs(this.vy); hitY = true } else if (this.y >= maxY - 1) { this.y = maxY; this.vy = -Math.abs(this.vy); hitY = true }
    } else if (hitY && !hitX) {
      if (this.x <= 1) { this.x = 0; this.vx = Math.abs(this.vx); hitX = true } else if (this.x >= maxX - 1) { this.x = maxX; this.vx = -Math.abs(this.vx); hitX = true }
    }
    if (hitX && hitY) {
      this.corners += 1; this.lastCorner = this.time; this.hitCorner = true
      // Back to cruising after a steered approach.
      this.vx = copySign(this.speed[0], this.vx); this.vy = copySign(this.speed[1], this.vy)
    } else if (hitX) {
      const crossing = maxX / this.speed[0]
      if (this.time - this.lastCorner >= this.overdueAfter) {
        // Aim: reach whichever vertical wall we're heading for exactly when we reach the far horizontal wall.
        const dy = this.vy > 0 ? maxY - this.y : this.y
        this.vy = copySign(Math.max(dy / crossing, 1), this.vy)
      } else {
        this.vy = copySign(this.speed[1], this.vy)
      }
    }
  }
}
