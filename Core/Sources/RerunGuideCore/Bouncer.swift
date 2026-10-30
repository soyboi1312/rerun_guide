import Foundation

/// The bouncing-logo screensaver's path: a `logo`-sized box reflecting inside `stage`, with one promise the DVD logo
/// never made: a corner at least once every two minutes. Plain bouncing can miss the corners for ages (forever, with
/// the wrong speed ratio), so once a corner is overdue the next wall bounce re-aims the vertical speed to land the
/// following horizontal bounce exactly in a corner. The viewer sees an ordinary bounce; the maths sees a deadline.
public struct Bouncer: Sendable {
    public private(set) var x: Double, y: Double          // top-left of the logo, in stage points
    public private(set) var vx: Double, vy: Double        // points per second
    public let stage: (w: Double, h: Double), logo: (w: Double, h: Double)
    /// Cruising speeds; the steering only ever slows the vertical one for a single crossing.
    public let speed: (x: Double, y: Double)
    /// Steering kicks in when this much time has passed since the last corner. With the default speeds a crossing
    /// takes about 14 s, so the worst case is `overdueAfter` + two crossings, still under 120 s.
    public var overdueAfter = 80.0
    public private(set) var time = 0.0, lastCorner = 0.0, corners = 0
    /// True only on the step that landed in a corner (the view flashes on it).
    public private(set) var hitCorner = false

    public init(stage: (w: Double, h: Double), logo: (w: Double, h: Double), speed: (x: Double, y: Double) = (110, 80), start: (x: Double, y: Double) = (0, 0)) {
        self.stage = stage; self.logo = logo; self.speed = speed
        x = min(max(start.x, 0), stage.w - logo.w); y = min(max(start.y, 0), stage.h - logo.h)
        vx = speed.x; vy = speed.y
    }

    public mutating func step(_ dt: Double) {
        hitCorner = false
        time += dt
        x += vx * dt; y += vy * dt
        let maxX = stage.w - logo.w, maxY = stage.h - logo.h
        var hitX = false, hitY = false
        if x <= 0 { x = 0; vx = abs(vx); hitX = true } else if x >= maxX { x = maxX; vx = -abs(vx); hitX = true }
        if y <= 0 { y = 0; vy = abs(vy); hitY = true } else if y >= maxY { y = maxY; vy = -abs(vy); hitY = true }
        // A steered approach lands both walls on the same instant; rounding can put them a frame apart, so a wall
        // hit within a point of the other wall counts as the corner and snaps.
        if hitX && !hitY {
            if y <= 1 { y = 0; vy = abs(vy); hitY = true } else if y >= maxY - 1 { y = maxY; vy = -abs(vy); hitY = true }
        } else if hitY && !hitX {
            if x <= 1 { x = 0; vx = abs(vx); hitX = true } else if x >= maxX - 1 { x = maxX; vx = -abs(vx); hitX = true }
        }
        if hitX && hitY {
            corners += 1; lastCorner = time; hitCorner = true
            vx = copysign(speed.x, vx); vy = copysign(speed.y, vy)   // back to cruising after a steered approach
        } else if hitX {
            let crossing = maxX / speed.x
            if time - lastCorner >= overdueAfter {
                // Aim: reach whichever vertical wall we're heading for exactly when we reach the far horizontal wall.
                let dy = vy > 0 ? maxY - y : y
                vy = copysign(max(dy / crossing, 1), vy)
            } else {
                vy = copysign(speed.y, vy)
            }
        }
    }
}
