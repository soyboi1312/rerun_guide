import SwiftUI
import UIKit

/// Profiles persist this. Decoding is tolerant: a value from a retired theme maps to .late, because
/// Persisted's decode is all-or-nothing and a throw here would wipe the server link.
enum ThemeID: String, Codable, Sendable, CaseIterable {
    case late, midnight
    var displayName: String { self == .late ? "Late Show" : "Midnight Movie" }
    var theme: Theme { self == .late ? .late : .midnight }
    init(from decoder: any Decoder) throws {
        self = ThemeID(rawValue: (try? decoder.singleValueContainer().decode(String.self)) ?? "") ?? .late
    }
}

/// Design tokens from the handoff. Every screen reads colors/fonts from here; never hard-code a hex in a view.
/// Layout that differs between the two themes beyond what a token can express branches on `id` (the Midnight Movie
/// guide is square, bevelled and condensed; Late Show is rounded and glowing).
struct Theme {
    let id: ThemeID
    let bg, panel, panel2, line, ink, mute, dim, accent, accentInk, focusBg, focusInk, focusDim, scrim, glow: Color
    let radius: CGFloat        // panels
    let cellRadius: CGFloat    // guide cells
    let pill: CGFloat
    let focusScale: CGFloat
    let focusShadow: Bool
    let uppercase: Bool        // every heading in caps
    let headingTracking: Double // em
    let headingWeight: Font.Weight
    let headingFamily, bodyFamily: String   // Google Fonts static-TTF family prefixes, e.g. "BricolageGrotesque"

    static let late = Theme(
        id: .late,
        bg: Color(hex: "#14110f"), panel: Color(hex: "#1e1a17"), panel2: Color(hex: "#2a231e"), line: Color(hex: "#3b3129"),
        ink: Color(hex: "#f3ebe0"), mute: Color(hex: "#c9bdae"), dim: Color(hex: "#a89c8e"),
        accent: Color(hex: "#f0a33a"), accentInk: Color(hex: "#14110f"),
        focusBg: Color(hex: "#f3ebe0"), focusInk: Color(hex: "#16120f"), focusDim: Color(hex: "#5a4e42"),
        scrim: Color(red: 10/255, green: 8/255, blue: 7/255, opacity: 0.72), glow: Color(red: 240/255, green: 163/255, blue: 58/255, opacity: 0.10),
        radius: 24, cellRadius: 16, pill: 999, focusScale: 1.03, focusShadow: true, uppercase: false, headingTracking: -0.03, headingWeight: .bold,
        headingFamily: "BricolageGrotesque", bodyFamily: "HankenGrotesk")

    /// Near-black and neon red, everything in Barlow Condensed, square corners, no focus lift (the focus flips color).
    static let midnight = Theme(
        id: .midnight,
        bg: Color(hex: "#0c0909"), panel: Color(hex: "#241414"), panel2: Color(hex: "#2e1919"), line: Color(hex: "#4a2424"),
        ink: Color(hex: "#ffffff"), mute: Color(hex: "#eadcd8"), dim: Color(hex: "#c4a9a4"),
        accent: Color(hex: "#ff4a3a"), accentInk: Color(hex: "#0c0909"),
        focusBg: Color(hex: "#ff4a3a"), focusInk: Color(hex: "#0c0909"), focusDim: Color(hex: "#3a0c09"),
        scrim: Color(red: 8/255, green: 4/255, blue: 4/255, opacity: 0.82), glow: Color(red: 255/255, green: 74/255, blue: 58/255, opacity: 0.12),
        radius: 0, cellRadius: 0, pill: 0, focusScale: 1, focusShadow: false, uppercase: true, headingTracking: 0.01, headingWeight: .heavy,
        headingFamily: "BarlowCondensed", bodyFamily: "BarlowCondensed")

    /// Heading font (Bricolage Grotesque / Barlow Condensed). Falls back to the system font while a TTF is missing.
    /// `style`: the Dynamic Type style it scales with (the stage keeps .body, as `Font.custom` always did; App/Portrait
    /// passes the iOS handoff's Type table).
    func heading(_ size: CGFloat, weight: Font.Weight? = nil, style: Font.TextStyle = .body) -> Font {
        let w = Self.clamp(weight ?? headingWeight, to: headingFamily)
        return Fonts.custom(headingFamily, weight: w, size: size, style: style, fallback: .system(size: size, weight: w, design: .default))
    }

    /// Body font (Hanken Grotesk / Barlow Condensed).
    func body(_ size: CGFloat, weight: Font.Weight = .regular, style: Font.TextStyle = .body) -> Font {
        let w = Self.clamp(weight, to: bodyFamily)
        return Fonts.custom(bodyFamily, weight: w, size: size, style: style, fallback: .system(size: size, weight: w))
    }

    /// Barlow Condensed ships 500–800 only, so its "regular" is Medium.
    private static func clamp(_ weight: Font.Weight, to family: String) -> Font.Weight {
        family == "BarlowCondensed" && weight == .regular ? .medium : weight
    }

    /// Headings in caps when the theme says so (Late Show doesn't).
    func heading(_ text: String) -> String { uppercase ? text.uppercased() : text }

    /// CSS `line-height: size × mult` for a wrapping headline: half the gap between that and the face's natural line
    /// height, applied as (negative) vertical padding. Single-line display type uses `.frame(height:)` instead.
    /// ponytail: trims the outer half-leading only; the inter-line gap of a wrapped title stays at the font's natural
    /// leading (SwiftUI Text can't shrink it). Upgrade: a UILabel representable with paragraphStyle min/max lineHeight.
    func lineHeightTrim(_ size: CGFloat, mult: Double = 1, weight: Font.Weight? = nil) -> CGFloat {
        let f = Fonts.uiFont(headingFamily, weight: Self.clamp(weight ?? headingWeight, to: headingFamily), size: size)
        return (size * mult - f.lineHeight) / 2
    }
}

extension Theme {
    /// Phone type (iOS handoff, Type): Late Show's size, 10% more for Midnight Movie's condensed face, scaling with
    /// Dynamic Type as `style` does.
    func pBody(_ size: CGFloat, _ weight: Font.Weight = .regular, _ style: Font.TextStyle = .body) -> Font {
        body(id == .midnight ? size * 1.1 : size, weight: weight, style: style)
    }
    func pHeading(_ size: CGFloat, _ weight: Font.Weight? = nil, style: Font.TextStyle = .title) -> Font {
        heading(id == .midnight ? size * 1.1 : size, weight: weight, style: style)
    }
    /// Section labels, chips and kickers: small caps, tracked.
    @MainActor func pLabel(_ text: String) -> some View {
        Text(text.uppercased()).font(pBody(11, .bold, .caption2)).tracking(em: 0.14, size: 11).foregroundStyle(dim)
    }
    var pShape: AnyShape { id == .midnight ? AnyShape(Rectangle()) : AnyShape(Capsule()) }
    var pGroup: AnyShape { id == .midnight ? AnyShape(Rectangle()) : AnyShape(RoundedRectangle(cornerRadius: 20)) }
}

enum Fonts {
    /// Installed face names, snapshotted once: the bundled set never changes at runtime, and a UIFont(name:) probe per
    /// Text would be a CoreText registry lookup on every guide render.
    private static let available = Set(UIFont.familyNames.flatMap(UIFont.fontNames(forFamilyName:)))

    /// PostScript-style names Google Fonts static TTFs register under, e.g. "BricolageGrotesque-Bold".
    static func custom(_ family: String, weight: Font.Weight, size: CGFloat, style: Font.TextStyle = .body, fallback: Font) -> Font {
        let name = "\(family)-\(suffix(weight))"
        return available.contains(name) ? Font.custom(name, size: size, relativeTo: style) : fallback
    }

    /// The face `custom` resolves to, as a UIFont (for metrics); the system font of that weight when the TTF is missing.
    static func uiFont(_ family: String, weight: Font.Weight, size: CGFloat) -> UIFont {
        UIFont(name: "\(family)-\(suffix(weight))", size: size) ?? .systemFont(ofSize: size, weight: weight.ui)
    }

    private static func suffix(_ weight: Font.Weight) -> String {
        switch weight {
        case .black, .heavy: "ExtraBold"
        case .bold: "Bold"
        case .semibold: "SemiBold"
        case .medium: "Medium"
        default: "Regular"
        }
    }
}

private extension Font.Weight {
    var ui: UIFont.Weight {
        switch self {
        case .black: .black; case .heavy: .heavy; case .bold: .bold; case .semibold: .semibold; case .medium: .medium
        case .light: .light; case .thin: .thin; case .ultraLight: .ultraLight; default: .regular
        }
    }
}

extension Color {
    init(hex: String) {
        var h = hex; if h.hasPrefix("#") { h.removeFirst() }
        let v = UInt64(h, radix: 16) ?? 0
        self.init(red: Double((v >> 16) & 0xff) / 255, green: Double((v >> 8) & 0xff) / 255, blue: Double(v & 0xff) / 255)
    }

    /// The handoff's oklch(L C H) gradients, approximated: HSB with hue H, used for channel-colored previews.
    static func channel(hue: Double, lightness: Double, chroma: Double) -> Color {
        Color(hue: hue / 360, saturation: min(1, chroma * 4), brightness: min(1, lightness * 1.15))
    }
}

/// Everything is laid out on the handoff's 1920×1080 artboard. tvOS lays out in 1920×1080 points, so these map 1:1.
extension View {
    /// Absolute placement: top-left (x, y) with size (w, h).
    func at(_ x: CGFloat, _ y: CGFloat, w: CGFloat, h: CGFloat, alignment: Alignment = .topLeading) -> some View {
        frame(width: w, height: h, alignment: alignment).position(x: x + w / 2, y: y + h / 2)
    }
    /// Letter-spacing in em, like the CSS in the handoff.
    func tracking(em: Double, size: CGFloat) -> some View { tracking(em * size) }
}

/// The system spinner at the size the TV draws it. `scale` is relative to tvOS's default spinner (about 64 pt on the
/// stage); iOS's large one is 37 pt and the stage is then drawn smaller still, so it is scaled up to match.
struct Spinner: View {
    var scale: CGFloat = 1
    let tint: Color
    var body: some View {
        #if os(tvOS)
        ProgressView().tint(tint).scaleEffect(scale)
        #else
        ProgressView().controlSize(.large).tint(tint).scaleEffect(scale * 64 / 37)
        #endif
    }
}

extension Date {
    /// A fixed origin for periodic TimelineViews. `.periodic(from: .now, …)` builds a new schedule on every body
    /// evaluation, so the date jumps whenever anything redraws and date-driven `onChange` handlers fire several times
    /// per frame; anchored here, ticks land on whole seconds and only change when the period does.
    static let timelineOrigin = Date(timeIntervalSinceReferenceDate: 0)
}

/// 12-hour clock text in the user's locale: "8:46" / "PM" / "8:30 – 9:00 PM" (prototype fmt/range).
@MainActor enum Clock {
    private static let timeF: DateFormatter = { let f = DateFormatter(); f.locale = .current; f.dateFormat = "h:mm"; return f }()
    private static let apF: DateFormatter = { let f = DateFormatter(); f.locale = .current; f.dateFormat = "a"; return f }()
    private static let dateF: DateFormatter = { let f = DateFormatter(); f.locale = .current; f.dateFormat = "EEE MMM d"; return f }()
    static func time(_ d: Date) -> String { timeF.string(from: d) }
    static func ap(_ d: Date) -> String { apF.string(from: d) }
    /// "WED OCT 22" (the Midnight Movie time bar).
    static func date(_ d: Date) -> String { dateF.string(from: d).uppercased() }
    /// "Wed Oct 22" (the portrait lineup's header).
    static func day(_ d: Date) -> String { dateF.string(from: d) }
    static func range(_ a: Date, _ b: Date) -> String {
        let x = ap(a), y = ap(b)
        return time(a) + (x != y ? " " + x : "") + " – " + time(b) + " " + y
    }
}
