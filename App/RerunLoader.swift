import SwiftUI

/// Rerun Guide's loading animation, the design handoff's "2a, ears ring": the app-icon TV, drawn live, whose rabbit ears
/// pop off, curl into a ring, spin twice and snap back on (a 2.4 s loop). Art is authored in the icon's 640×480 box; the
/// ring rises above it, so the canvas spans y −100…520. The caption alternates "Tuning in…" and "Buffering…"
/// every two loops with a 0.22 s fade; a single caption stays put. It fades in once `delay` has passed,
/// so a wait shorter than that never shows it. Reduce Motion: the ears stay put, the arrow turns once every 3 s and
/// captions change every 6 s.
struct RerunLoader: View {
    /// Logo width in points. 384 is the design's size on the 1920×1080 stage.
    var width: CGFloat = 384
    var captions = ["Tuning in…", "Buffering…"]
    /// Seconds before it fades in; the loop starts then.
    var delay: Double = 0
    @Environment(\.theme) private var t
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var start = Date.now

    var body: some View {
        let k = width / 640
        TimelineView(.animation) { tl in
            let ms = (tl.date.timeIntervalSince(start) - delay) * 1000, run = max(0, ms)
            VStack(spacing: 20 * k) {
                Canvas { ctx, _ in
                    ctx.scaleBy(x: k, y: k)
                    ctx.translateBy(x: 0, y: 100)
                    RerunLoaderArt.draw(in: &ctx, pose: reduceMotion ? .rest(arrow: -360 * run.truncatingRemainder(dividingBy: 3000) / 3000)
                                                                     : RerunLoaderArt.ring(run.truncatingRemainder(dividingBy: 2400) / 2400))
                }
                .frame(width: width, height: 620 * k)
                if !captions.isEmpty { caption(run, k) }
            }
            .opacity(min(1, max(0, ms / 250)))
        }
    }

    private func caption(_ ms: Double, _ k: CGFloat) -> some View {
        let per = reduceMotion ? 6000.0 : 4800   // two ring loops per caption
        let i = Int(ms / per) % captions.count, p = ms.truncatingRemainder(dividingBy: per)
        return Text(captions[i])
            .font(t.body(50 * k, weight: .medium))
            .tracking(em: 0.02, size: 50 * k)
            .foregroundStyle(t.dim)
            .shadow(color: .black.opacity(0.8), radius: 6)   // readable over a bright frame while buffering
            .opacity(captions.count == 1 ? 1 : min(1, p / 220, (per - p) / 220))
    }
}

/// The logo art and the ring choreography (one loop, t in 0…1). Fixed brand colors: this is the app icon, not themed UI.
enum RerunLoaderArt {
    struct Ear { var bx, by, th, k: Double }   // base point, heading (radians), curvature (1/radius)
    struct Pose {
        var sy: Double       // TV squash, anchored at bottom center
        var arrow: Double    // degrees
        var a, b: Ear
        static func rest(arrow: Double) -> Pose { var p = RerunLoaderArt.ring(0); p.arrow = arrow; return p }
    }

    static let ink = Color(hex: "#f3ebe0"), stem = Color(hex: "#c9bdae"), amber = Color(hex: "#f0a33a")
    private static let len = 120.0, ringR = 120.0 / .pi, cx = 320.0, cy = -40.0

    /// 0–.14 crouch · .08–.30 ears pop and curl into a ring (overshoot) · .30–.72 two turns · .72–.88 straighten and
    /// drop back on · .88–.98 landing squash. The arrow stays still.
    static func ring(_ t: Double) -> Pose {
        func seg(_ a: Double, _ b: Double) -> Double { min(1, max(0, (t - a) / (b - a))) }
        var sy = 1.0
        if t < 0.06 { sy = 1 - 0.03 * LoaderEase.out(seg(0, 0.06)) }
        else if t < 0.14 { sy = 0.97 + 0.03 * LoaderEase.out(seg(0.06, 0.14)) }
        else if t >= 0.88 && t < 0.92 { sy = 1 - 0.025 * LoaderEase.out(seg(0.88, 0.92)) }
        else if t >= 0.92 && t < 0.98 { sy = 0.975 + 0.025 * LoaderEase.out(seg(0.92, 0.98)) }
        var f = 0.0, kf = 0.0, phi = 0.0
        if t >= 0.08 && t < 0.30 { f = LoaderEase.back(seg(0.08, 0.30)); kf = LoaderEase.out(seg(0.08, 0.30)) }
        else if t >= 0.30 && t < 0.72 { f = 1; kf = 1; phi = -720 * LoaderEase.inOut(seg(0.30, 0.72)) }
        else if t >= 0.72 && t < 0.88 { let q = seg(0.72, 0.88); f = 1 - LoaderEase.cubicIn(q); kf = 1 - LoaderEase.inOut(q) }
        func lerp(_ a: Double, _ b: Double) -> Double { a + (b - a) * f }
        let r = Double.pi / 180, drop = (476 - 84) * (1 - sy), k = .pi / len * kf
        let a = Ear(bx: lerp(320, cx + ringR * cos((90 + phi) * r)), by: lerp(84 + drop, cy + ringR * sin((90 + phi) * r)),
                    th: lerp(-122, -180 + phi) * r, k: k)
        let b = Ear(bx: lerp(320, cx + ringR * cos((-90 + phi) * r)), by: lerp(84 + drop, cy + ringR * sin((-90 + phi) * r)),
                    th: lerp(-58, phi) * r, k: k)
        return Pose(sy: sy, arrow: 0, a: a, b: b)
    }

    static func draw(in ctx: inout GraphicsContext, pose: Pose) {
        ear(&ctx, pose.a); ear(&ctx, pose.b)
        var tv = ctx
        tv.translateBy(x: 320, y: 476); tv.scaleBy(x: 1, y: pose.sy); tv.translateBy(x: -320, y: -476)

        var nub = Path()
        nub.move(to: CGPoint(x: 286, y: 102))
        nub.addRelativeArc(center: CGPoint(x: 316, y: 102), radius: 30, startAngle: .degrees(180), delta: .degrees(90))
        nub.addLine(to: CGPoint(x: 324, y: 72))
        nub.addRelativeArc(center: CGPoint(x: 324, y: 102), radius: 30, startAngle: .degrees(270), delta: .degrees(90))
        nub.closeSubpath()
        tv.fill(nub, with: .color(ink))

        let body = Path(roundedRect: CGRect(x: 74, y: 94, width: 492, height: 382), cornerSize: CGSize(width: 58, height: 62), style: .circular)
        tv.drawLayer { l in
            l.addFilter(.shadow(color: .black.opacity(0.55), radius: 30, x: 0, y: 30))
            l.fill(body, with: .color(ink))
        }

        let screen = Path(roundedRect: CGRect(x: 100, y: 120, width: 440, height: 330), cornerSize: CGSize(width: 34, height: 38), style: .circular)
        tv.drawLayer { s in
            s.clip(to: screen)
            s.fill(screen, with: .radialGradient(Gradient(stops: [
                .init(color: Color(hex: "#3d3027"), location: 0), .init(color: Color(hex: "#1d1713"), location: 0.58),
                .init(color: Color(hex: "#110d0b"), location: 1)]), center: CGPoint(x: 240.8, y: 192.6), startRadius: 0, endRadius: 520))
            s.fill(screen, with: .radialGradient(Gradient(colors: [amber.opacity(0.18), amber.opacity(0)]),
                                                 center: CGPoint(x: 320, y: 285), startRadius: 0, endRadius: 240))
            var lines = Path(); var y = 447.0
            while y > 120 { lines.addRect(CGRect(x: 100, y: y, width: 440, height: 3)); y -= 7 }
            s.fill(lines, with: .color(.black.opacity(0.22)))
        }

        tv.drawLayer { a in
            a.addFilter(.shadow(color: amber.opacity(0.75), radius: 8))
            a.translateBy(x: 320, y: 285); a.rotate(by: .degrees(pose.arrow)); a.translateBy(x: -320, y: -285)
            var ring = Path()
            ring.addRelativeArc(center: CGPoint(x: 320, y: 285), radius: 87.5, startAngle: .degrees(-90), delta: .degrees(270))
            a.stroke(ring, with: .color(amber), style: StrokeStyle(lineWidth: 37))
            var head = Path()
            head.move(to: CGPoint(x: 256, y: 198)); head.addLine(to: CGPoint(x: 324, y: 154)); head.addLine(to: CGPoint(x: 324, y: 242))
            head.closeSubpath()
            a.fill(head, with: .color(amber))
        }

        var play = Path()
        play.move(to: CGPoint(x: 294, y: 248)); play.addLine(to: CGPoint(x: 358, y: 285)); play.addLine(to: CGPoint(x: 294, y: 322))
        play.closeSubpath()
        tv.fill(play, with: .color(ink))
    }

    /// One rabbit ear: a 120-long stem that bends into an arc of curvature k, with a knob at the tip.
    private static func ear(_ ctx: inout GraphicsContext, _ e: Ear) {
        var p = Path()
        let tip: CGPoint
        if abs(e.k) < 1e-4 {
            tip = CGPoint(x: e.bx + len * cos(e.th), y: e.by + len * sin(e.th))
            p.move(to: CGPoint(x: e.bx, y: e.by)); p.addLine(to: tip)
        } else {
            let R = 1 / e.k, a0 = e.th - .pi / 2, a1 = a0 + e.k * len
            let c = CGPoint(x: e.bx - R * sin(e.th), y: e.by + R * cos(e.th))
            p.addRelativeArc(center: c, radius: R, startAngle: .radians(a0), delta: .radians(e.k * len))
            tip = CGPoint(x: c.x + R * cos(a1), y: c.y + R * sin(a1))
        }
        ctx.stroke(p, with: .color(stem), style: StrokeStyle(lineWidth: 8, lineCap: .round))
        ctx.fill(Path(ellipseIn: CGRect(x: tip.x - 10, y: tip.y - 10, width: 20, height: 20)), with: .color(ink))
    }
}

private enum LoaderEase {
    static func out(_ x: Double) -> Double { 1 - pow(1 - x, 3) }
    static func cubicIn(_ x: Double) -> Double { x * x * x }
    static func inOut(_ x: Double) -> Double { x < 0.5 ? 4 * x * x * x : 1 - pow(-2 * x + 2, 3) / 2 }
    static func back(_ x: Double) -> Double { 1 + 2.70158 * pow(x - 1, 3) + 1.70158 * pow(x - 1, 2) }
}
