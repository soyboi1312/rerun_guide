import SwiftUI
import Combine
import RerunGuideCore

/// Attract mode's other faces: a logo drifting and bouncing the way the DVD logo used to, on the theme's background.
/// Either the Rerun Guide TV with its wordmark, or Soyboi's mascot over "soyboi.tech". `Bouncer` (Core) owns the path
/// and its promise of a corner at least every two minutes; a corner gets a glow. The first press wakes the guide
/// (AppModel+Guide swallows it), so there is nothing to focus here.
struct BounceSaver: View {
    /// Raw values are the `Settings.screensaver` strings; "wall" (the storefront wall) has no face here.
    enum Face: String { case logo, soyboi }

    @Environment(\.theme) private var t
    let face: Face
    /// The stage's width (wider than 1920 off tvOS on a wide screen).
    let width: CGFloat
    @State private var b: Bouncer
    @State private var glow = 0.0
    private let tick = Timer.publish(every: 1.0 / 60, on: .main, in: .common).autoconnect()

    init(face: Face, width: CGFloat = 1920) {
        self.face = face; self.width = width
        _b = State(initialValue: Bouncer(stage: (w: Double(width), h: 1080), logo: Self.box(face), start: (x: 300, y: 200)))
    }

    /// The bouncing box hugs the artwork plus its line of text, so corner hits look like corner hits.
    private static func box(_ face: Face) -> (w: Double, h: Double) {
        switch face {
        case .logo: (400, 400)     // the 360-wide logo image (753×693 source) plus the wordmark under it
        case .soyboi: (320, 372)   // the 300-square mascot plus "soyboi.tech" under it
        }
    }

    var body: some View {
        let box = Self.box(face)
        ZStack(alignment: .topLeading) {
            t.bg
            VStack(spacing: 12) {
                switch face {
                case .logo:
                    Image("BounceLogo").resizable().scaledToFit().frame(width: 360)
                    HStack(spacing: 14) {
                        Circle().fill(t.accent).frame(width: 14, height: 14)
                        Text(t.heading("Rerun Guide")).font(t.heading(40, weight: .semibold)).tracking(em: t.headingTracking, size: 40).foregroundStyle(t.ink)
                    }
                case .soyboi:
                    Image("SoyboiMascot").resizable().scaledToFit().frame(width: 300)
                    Text("soyboi.tech").font(t.body(40, weight: .semibold)).foregroundStyle(t.ink)   // a domain: never uppercased
                }
            }
            .frame(width: box.w, height: box.h)
            .shadow(color: t.accent.opacity(glow * 0.9), radius: 48 * glow)
            .offset(x: b.x, y: b.y)
        }
        .frame(width: width, height: 1080)
        .onReceive(tick) { _ in
            b.step(1.0 / 60)
            if b.hitCorner { glow = 1; withAnimation(.easeOut(duration: 1.5)) { glow = 0 } }
        }
    }
}
