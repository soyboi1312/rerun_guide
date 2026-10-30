import SwiftUI
import AVFoundation
import Combine
import RerunGuideCore

/// The guide: preview window (top-left), info panel (top-right), time header + looping listings grid (bottom).
/// Layout is absolute on the 1920×1080 stage, straight from the handoff's CSS (Design/handoff/README.md §1).
struct GuideScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    @Environment(\.stageWidth) private var sw

    var body: some View {
        ZStack(alignment: .topLeading) {
            t.bg
            if t.id == .late {
                EllipticalGradient(stops: [.init(color: t.glow, location: 0), .init(color: .clear, location: 0.7)],
                                   center: .center, startRadiusFraction: 0, endRadiusFraction: 0.5)
                    .at(380 - 1000, 300 - 640, w: 2000, h: 1280)
            }
            if model.visibleChannels.isEmpty {
                if model.libraryLoading { RerunLoader(delay: 1).frame(width: sw, height: 1080) }   // a quick cache read at launch doesn't show it
                else {
                    VStack(spacing: 18) {
                        Text(model.libraryError ?? "Nothing to schedule yet").font(t.body(32)).foregroundStyle(model.libraryError == nil ? t.dim : t.accent)
                            .multilineTextAlignment(.center).frame(maxWidth: 1200)
                        Text("Open Options › Settings › Rebuild to try again").font(t.body(26)).foregroundStyle(t.dim)
                    }
                    .frame(width: sw, height: 1080)
                }
            } else {
                if model.attracting, let face = BounceSaver.Face(rawValue: model.settings.screensaver) { BounceSaver(face: face, width: sw).transition(.opacity) }
                else { TimelineView(.periodic(from: .timelineOrigin, by: 10)) { ctx in GuideContent(now: ctx.date) } }
            }
            if t.id == .midnight && model.settings.crtLines {
                // Off tvOS the CRT overlay runs on past the stage into the window's margins (the stage isn't clipped); 600 covers
                // them. Negative padding: it draws bigger without making the guide's layout bigger.
                CRTOverlay(lines: 0.09, vignette: (0.62, 0.38)).frame(width: sw, height: 1080).padding(Platform.isTV ? 0 : -600)
            }
        }
        .frame(width: sw, height: 1080)
        .foregroundStyle(t.ink)
        .backgroundTap { if model.attracting { model.attractReset() } }   // the bouncing logo has nothing else to tap
        .onAppear { model.guideAppeared() }
        .onDisappear { model.attractReset() }                                 // off the guide: wake, stop the countdown
        .onChange(of: model.overlay == nil) { _, _ in model.attractReset() }   // an overlay wakes it; closing one re-arms it
    }
}

private struct GuideContent: View {
    @Environment(AppModel.self) private var model
    @Environment(\.stageWidth) private var sw
    let now: Date

    var body: some View {
        let a = model.attracting, fade = Animation.easeInOut(duration: 0.8)
        ZStack(alignment: .topLeading) {
            if let gi = GuideInfo(model: model, now: now) {
                PreviewWindow(gi: gi).zIndex(1)   // the enlarged attract tube floats over the dimmed grid
                ZStack(alignment: .topLeading) {
                    if a { AttractPanel(ch: gi.ch, title: gi.title).transition(.opacity) } else { InfoPanel(gi: gi).transition(.opacity) }
                }
                .animation(fade, value: a)
                Group {
                    TimeHeader(now: now, ws: gi.ws)
                    GuideGrid(now: now, ws: gi.ws, gf: gi.key)
                }
                .opacity(a ? 0.7 : 1).animation(fade, value: a)
            }
        }
        .frame(width: sw, height: 1080)
    }
}

// MARK: - View model (port of vGuide's `gi`)

@MainActor struct GuideInfo {
    let key: ProgramKey, ch: Channel, slot: Slot, st: ProgramStatus, cur: Slot, brk: Bool, bOn: Bool
    let trailer: MediaItem?, next: Slot?, ws: Date, now: Date, held: Bool
    /// `model.foregroundSerial`: back from the Home screen, the preview's stream must be fetched again.
    let resume: Int

    init?(model: AppModel, now: Date) {
        guard let key = model.guideKey(at: now), let pr = model.program(key) else { return nil }
        self.key = key; ch = pr.channel; slot = pr.slot; self.now = now; resume = model.foregroundSerial
        st = model.status(slot, at: now)
        cur = model.slotOnAir(ch, at: now) ?? slot
        brk = model.status(cur, at: now) == .break
        bOn = model.settings.trailerBreaks && ch.trailerBreaks
        trailer = brk && bOn ? model.breakTrailerCandidate(ch, slot: cur) : nil
        next = model.slotOnAir(ch, at: cur.end)
        ws = model.windowStart(at: now)
        held = model.remoteHeld
    }

    var live: Bool { st == .live || st == .break }
    var title: String { slot.item.displayTitle }
    var timeRange: String { Clock.range(slot.start, slot.end) }
    var elapsed: TimeInterval { now.timeIntervalSince(slot.start) }
    var progress: Double { live ? max(0, min(1, elapsed / slot.duration)) : 0 }
    var desc: String { slot.item.overview ?? "" }
    var statusText: String {
        switch st {
        case .live: "\(Int(elapsed / 60)) min in"
        case .break: (bOn ? "Trailers until " : "Station break until ") + Clock.time(slot.end)
        case .later: "Starts in \(Int((-elapsed / 60).rounded(.up))) min"
        case .past: "Aired earlier"
        }
    }
    var statusLabel: String {
        switch st { case .live: "ON NOW"; case .break: "BREAK"; case .later: "LATER"; case .past: "ENDED" }
    }
    var pvKind: String { brk ? "BREAK" : "LIVE" }
    var pvKicker: String { brk ? (bOn ? "Coming attractions" : "Station break") : "Now playing" }
    var pvTitle: String { brk ? (bOn ? trailer?.title ?? ch.name : ch.name) : cur.item.displayTitle }
    var pvSub: String {
        if brk {
            let what = bOn ? "Trailer" + (trailer?.year.map { ", \($0)" } ?? "") : "Channel \(ch.number)"
            return what + (next.map { " · \($0.item.displayTitle) at \(Clock.time($0.start))" } ?? "")
        }
        return cur.item.kind == .movie ? cur.item.year.map(String.init) ?? "" : "“\(cur.item.title)”"
    }
    var selLabel: String { live ? "Watch live" : "Details" }
    /// Play/Pause: a program that hasn't started yet has no beginning to go back to.
    var playLabel: String { st == .later ? "Watch now" : "Start from the beginning" }
    var backLabel: String { held ? "Let it drift" : "Options" }
    /// Held: the Back button lets go. Drifting: Menu exits to Home, so clickpad-left is the way into Options.
    var backKey: String { held ? "chevron.backward" : "circle.grid.cross.left.filled" }
    /// The same three pills' glyphs off tvOS, where they are buttons: what they do, not which key does it.
    var selTouch: String { live ? "play.fill" : "info.circle" }
    var backTouch: String { held ? "wind" : "line.3.horizontal" }
    /// Changes when the preview picture should reload (focused channel, program or break state).
    var previewKey: String { "\(ch.id)|\(cur.id)|\(brk)|\(resume)" }

    static func fit(_ n: Int, _ a: CGFloat, _ b: CGFloat, _ c: CGFloat) -> CGFloat { n <= 16 ? a : n <= 26 ? b : c }
}

/// Handoff literals that are not theme tokens (README §1). Midnight Movie cells have no live fill: its liveCell/focusFill
/// equal the base colors, so the shared gradient degenerates to the flat cell the prototype shows.
private extension Theme {
    var mm: Bool { id == .midnight }
    var pvText: Color { Color(hex: "#e6d8c4") }
    var cellBg: Color { mm ? panel2 : panel }
    var liveCell: Color { mm ? panel2 : Color(hex: "#28211c") }
    var focusFill: Color { mm ? focusBg : Color(hex: "#e2d2bc") }
    var ring: Color { Color(hex: "#2e2722") }
    var bevelLight: Color { Color(hex: "#6e3434") }
    var bevelDark: Color { Color(hex: "#050303") }
    var box: Color { Color(hex: "#070505") }
    var slash: Color { Color(hex: "#7a4040") }
    var watermark: Color { Color(hex: "#d8c2bd") }
    var liveChip: Color { Color(hex: "#d7261e") }
    var breakChip: Color { Color(hex: "#c2560c") }
    var breakBadge: Color { Color(hex: "#ffa94d") }
}

// MARK: - Preview window

struct PreviewWindow: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    let gi: GuideInfo
    @State private var player: AVPlayer? = nil   // allocated on the first real load, not on every body evaluation
    @State private var ready = false
    @State private var load: Task<Void, Never>? = nil
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var phase
    /// App/Portrait: this many points wide at 16:9 with the phone layout over it (iOS handoff §1.2), instead of the TV's
    /// tube on the stage. `strip`: the picture alone, the accessibility sizes' thumbnail.
    var portrait: CGFloat? = nil
    var strip = false

    var body: some View {
        Group { if let w = portrait { phone(w: w) } else { tube } }
        .onChange(of: gi.previewKey, initial: true) { _, _ in reload() }
        // A load in flight must not outlive the app being away: the way back reloads (foregroundSerial), and a plain-http
        // link's request would carry the token to an address not yet checked on the new network.
        .onChange(of: phase) { _, p in if p == .background { load?.cancel() } }
        .onReceive(player?.publisher(for: \.timeControlStatus).receive(on: DispatchQueue.main).eraseToAnyPublisher()
                   ?? Empty<AVPlayer.TimeControlStatus, Never>().eraseToAnyPublisher()) { ready = $0 == .playing }
        .onDisappear {
            load?.cancel(); player?.pause(); player?.replaceCurrentItem(with: nil)
            Task { await model.server?.stopPlayback(session: "preview") }
        }
    }

    private var tube: some View {
        // ponytail: the attract tube is hard-coded (Late Show 640×480 at 80,72, ending above the time header at y 566;
        // Midnight Movie 824×464 at 80,56, ending at x 904 / y 520 before its time bar); no handoff spec for it.
        let a = model.attracting, mm = t.mm
        let w: CGFloat = mm ? (a ? 824 : 800) : (a ? 640 : 600), h: CGFloat = mm ? (a ? 464 : 450) : (a ? 480 : 450)
        return Group { if mm { midnight(w: w, h: h) } else { late(w: w, h: h) } }
            .onTouch { model.guideHint(.select) }   // the picture: watch it (or its details, like the Select pill). Before .at: it fills its parent
            .at(80, mm ? 56 : 72, w: w, h: h)
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.8), value: a)   // reduce motion: no growing tube, the panels still crossfade
    }

    /// The phone's preview: the Late Show tube (radius 26, ring, glow) or the Midnight Movie set (4-pt bevel), a 3-pt line
    /// along the bottom for how far into the program it is. A tap watches it (or opens its details, like the TV's Select).
    private func phone(w: CGFloat) -> some View {
        let h = (w * 9 / 16).rounded(), mm = t.mm, r: CGFloat = mm ? 0 : strip ? 10 : 26
        let b: CGFloat = mm ? (strip ? 2 : 4) : 0, iw = w - 2 * b, ih = h - 2 * b   // Midnight Movie's bevel, inside the 16:9
        let into = gi.now.timeIntervalSince(gi.cur.start), progress = max(0, min(1, into / gi.cur.duration))
        let sub = gi.brk ? gi.pvSub : [gi.pvSub, "\(Int(into / 60)) min in"].filter { !$0.isEmpty }.joined(separator: " · ")
        return ZStack(alignment: .bottomLeading) {
            picture(w: iw, h: ih)
            if !strip { if mm { phoneMidnight(sub: sub) } else { phoneLate(sub: sub) } }
            t.accent.frame(width: iw * progress, height: 3).accessibilityHidden(true)
        }
        .frame(width: iw, height: ih)
        .clipShape(RoundedRectangle(cornerRadius: r))
        .padding(b)
        .background { if mm { Bevel(width: b, light: t.bevelLight, dark: t.bevelDark) } }
        .background(RoundedRectangle(cornerRadius: r).fill(.black)
            .shadow(color: .black.opacity(mm ? 0 : 0.55), radius: 25, y: 22)
            .shadow(color: t.accent.opacity(mm ? 0 : 0.14), radius: 35))
        .overlay(RoundedRectangle(cornerRadius: r).stroke(t.ring, lineWidth: mm ? 0 : 1.5))
        .contentShape(Rectangle())
        .onTapGesture { model.guideHint(.select) }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Preview. \(gi.pvKicker), channel \(gi.ch.number) \(gi.ch.name). \(gi.pvTitle). \(sub)")
        .accessibilityAddTraits(.isButton)
        .accessibilityHint(gi.live ? "Watches it" : "Shows details")
    }

    private func phoneLate(sub: String) -> some View {
        ZStack(alignment: .topLeading) {
            Text(gi.pvKind).font(t.pBody(11, .bold, .caption2)).tracking(em: 0.1, size: 11).foregroundStyle(t.accentInk)
                .padding(.vertical, 4).padding(.horizontal, 10).background(Capsule().fill(gi.brk ? t.focusBg : t.accent))
                .padding(.leading, 14).padding(.top, 13)
            HStack(alignment: .bottom, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(gi.pvKicker) · \(gi.ch.number) \(gi.ch.name)".uppercased()).font(t.pBody(11, .semibold, .caption2))
                        .tracking(em: 0.12, size: 11).foregroundStyle(t.pvText).lineLimit(1)
                    Text(gi.pvTitle).font(t.pHeading(28, style: .title)).tracking(em: -0.02, size: 28).lineLimit(1).minimumScaleFactor(0.6)
                    Text(sub).font(t.pBody(13, .regular, .footnote)).foregroundStyle(t.pvText).lineLimit(1)
                }
                Spacer(minLength: 0)
                Text(gi.live ? "Watch" : "Details").font(t.pBody(14, .bold, .subheadline)).foregroundStyle(t.focusInk)
                    .padding(.horizontal, 16).frame(minHeight: 44).background(Capsule().fill(t.focusBg))
            }
            .padding(.leading, 16).padding(.trailing, 14).padding(.bottom, 16)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
        }
        .foregroundStyle(t.ink)
    }

    private func phoneMidnight(sub: String) -> some View {
        ZStack(alignment: .topLeading) {
            HStack(spacing: 8) {
                Text(String(gi.ch.number)).font(t.pBody(17, .heavy, .headline)).foregroundStyle(t.accentInk)
                    .padding(.vertical, 4).padding(.horizontal, 8).background(t.accent)
                Text(gi.ch.name.uppercased()).font(t.pBody(15, .bold, .subheadline)).tracking(em: 0.06, size: 16.5)
                    .shadow(color: .black.opacity(0.5), radius: 0, y: 2).lineLimit(1)
            }
            .padding(.leading, 12).padding(.top, 12)
            Text(gi.pvKind).font(t.pBody(13, .heavy, .caption)).tracking(em: 0.12, size: 14).foregroundStyle(.white)
                .padding(.vertical, 4).padding(.horizontal, 9).background(gi.brk ? t.breakChip : t.liveChip)
                .padding(.trailing, 12).padding(.top, 13)
                .frame(maxWidth: .infinity, alignment: .topTrailing)
            HStack(alignment: .bottom, spacing: 10) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(gi.pvTitle.uppercased()).font(t.pHeading(33, style: .title)).lineLimit(1).minimumScaleFactor(0.6)
                        .shadow(color: .black.opacity(0.45), radius: 0, y: 3)
                    Text(sub.uppercased()).font(t.pBody(14, .semibold, .footnote)).foregroundStyle(t.mute).lineLimit(1)
                }
                Spacer(minLength: 0)
                VStack(alignment: .trailing, spacing: 7) {
                    Text("RERUN GUIDE").font(.custom("BarlowCondensed-ExtraBoldItalic", size: 13, relativeTo: .caption2)).tracking(em: 0.04, size: 13)
                        .foregroundStyle(t.watermark).opacity(0.85)
                    Text(gi.live ? "WATCH" : "DETAILS").font(t.pBody(14, .heavy, .subheadline)).tracking(em: 0.08, size: 15).foregroundStyle(t.accentInk)
                        .padding(.horizontal, 12).frame(minHeight: 44).background(t.accent)
                }
            }
            .padding(.leading, 14).padding(.trailing, 12).padding(.bottom, 12)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
        }
        .foregroundStyle(t.ink)
    }

    /// The Late Show tube: rounded, ringed and glowing, text block bottom-left.
    private func late(w: CGFloat, h: CGFloat) -> some View {
        let pvSize = GuideInfo.fit(gi.pvTitle.count, 60, 50, 40)
        return ZStack(alignment: .topLeading) {
            picture(w: w, h: h)
            Text(gi.pvKind).font(t.body(24, weight: .bold)).tracking(em: 0.1, size: 24).foregroundStyle(t.accentInk)
                .padding(.vertical, 6).padding(.horizontal, 14).background(Capsule().fill(gi.brk ? t.focusBg : t.accent))
                .offset(x: 28, y: 26)
        }
        .overlay(alignment: .bottomLeading) {
            VStack(alignment: .leading, spacing: 8) {
                Text(gi.pvKicker.uppercased()).font(t.body(24, weight: .semibold)).tracking(em: 0.14, size: 24).foregroundStyle(t.pvText)
                Text(gi.pvTitle).font(t.heading(pvSize)).tracking(em: -0.02, size: pvSize).padding(.vertical, t.lineHeightTrim(pvSize))
                Text(gi.pvSub).font(t.body(26)).foregroundStyle(t.pvText)
            }
            .padding(.horizontal, 36).padding(.bottom, 34)
        }
        .frame(width: w, height: h)
        .clipShape(RoundedRectangle(cornerRadius: 44))
        // Shadows on a static shape behind the player, not on the layer the video repaints every frame.
        .background(RoundedRectangle(cornerRadius: 44).fill(.black)
            .shadow(color: .black.opacity(0.55), radius: 45, y: 40)
            .shadow(color: t.accent.opacity(0.16), radius: 80))
        .overlay(RoundedRectangle(cornerRadius: 45).stroke(t.ring, lineWidth: 2).padding(-1))
    }

    /// The Midnight Movie set: a 6px bevel around the picture, channel + chip along the top, title centered, watermark.
    private func midnight(w: CGFloat, h: CGFloat) -> some View {
        let iw = w - 12, ih = h - 12, pvSize = GuideInfo.fit(gi.pvTitle.count, 84, 64, 50)
        return ZStack(alignment: .topLeading) {
            picture(w: iw, h: ih)
            HStack(spacing: 12) {
                Text(String(gi.ch.number)).font(t.body(34, weight: .heavy)).foregroundStyle(t.accentInk).frame(height: 34)
                    .padding(.vertical, 6).padding(.horizontal, 12).background(t.accent)
                Text(gi.ch.name.uppercased()).font(t.body(30, weight: .bold)).tracking(em: 0.06, size: 30).shadow(color: .black.opacity(0.5), radius: 0, y: 2)
            }
            .offset(x: 24, y: 22)
            Text(gi.pvKind).font(t.body(26, weight: .heavy)).tracking(em: 0.12, size: 26).foregroundStyle(.white).frame(height: 26)
                .padding(.vertical, 6).padding(.horizontal, 14).background(gi.brk ? t.breakChip : t.liveChip)
                .at(0, 24, w: iw - 24, h: 38, alignment: .topTrailing)
            VStack(spacing: 12) {
                Text(gi.pvKicker.uppercased()).font(t.body(26, weight: .bold)).tracking(em: 0.22, size: 26).foregroundStyle(t.accent)
                Text(gi.pvTitle.uppercased()).font(t.heading(pvSize)).padding(.vertical, t.lineHeightTrim(pvSize, mult: 0.95))
                    .shadow(color: .black.opacity(0.45), radius: 0, y: 4)
                Text(gi.pvSub).font(t.body(30)).foregroundStyle(t.mute)
            }
            .multilineTextAlignment(.center)
            .at(48, 80, w: iw - 96, h: ih - 150, alignment: .center)
            Text("RERUN GUIDE").font(.custom("BarlowCondensed-ExtraBoldItalic", size: 28)).tracking(em: 0.04, size: 28)
                .foregroundStyle(t.watermark).opacity(0.85)
                .at(0, 0, w: iw - 24, h: ih - 18, alignment: .bottomTrailing)
        }
        .frame(width: iw, height: ih)
        .clipped()
        .padding(6)
        .background(Bevel(width: 6, light: t.bevelLight, dark: t.bevelDark))
    }

    /// Hue gradient → backdrop art → muted live video, in that order as each becomes available.
    @ViewBuilder private func picture(w: CGFloat, h: CGFloat) -> some View {
        let hue = gi.ch.hue
        ZStack(alignment: .topLeading) {
            EllipticalGradient(stops: [.init(color: .channel(hue: hue, lightness: 0.5, chroma: 0.1), location: 0),
                                       .init(color: .channel(hue: hue, lightness: 0.24, chroma: 0.05), location: 0.55),
                                       .init(color: .channel(hue: hue, lightness: 0.13, chroma: 0.02), location: 1)],
                               center: UnitPoint(x: 0.25, y: 0.15), startRadiusFraction: 0, endRadiusFraction: 1)
            if gi.ch.isScrambled {
                ScrambleView(seed: Int(gi.cur.start.timeIntervalSince1970 / 60)).frame(width: w, height: h)
            } else if !gi.brk, let url = model.server?.backdropURL(for: gi.cur.item) {
                AsyncImage(url: url) { $0.resizable().scaledToFill().overlay(scrim) } placeholder: { Color.clear }.frame(width: w, height: h).clipped()
            }
            if !gi.ch.isScrambled { PlayerLayerView(player: player).overlay(scrim).opacity(ready ? 1 : 0) }
            if model.settings.crtLines { CRTOverlay(lines: t.mm ? 0.24 : 0.16, vignette: t.mm ? nil : (0.55, 0.5)) }
        }
        .frame(width: w, height: h)
    }

    /// Legibility over real art/video only; the bare hue gradient renders as in the mockup.
    private var scrim: some View {
        LinearGradient(colors: [.clear, .black.opacity(0.6)], startPoint: UnitPoint(x: 0.5, y: 0.45), endPoint: .bottom)
    }

    private func reload() {
        if gi.ch.isScrambled { load?.cancel(); player?.pause(); player?.replaceCurrentItem(with: nil); return }   // nothing to stream
        load?.cancel()
        let gi = gi
        load = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(1500))   // wait for the focus to settle: each load starts a server transcode
            guard !Task.isCancelled else { return }
            ready = false; player?.pause(); player?.replaceCurrentItem(with: nil)
            let now = Date.now
            guard !gi.brk, let server = model.server, let air = model.slotOnAir(gi.ch, at: now) else { return }
            let offset = min(now.timeIntervalSince(air.start), air.item.runtime)
            // Not once the app has left: a late play() would keep it awake in the background (the return reloads it).
            guard let src = try? await server.playbackSource(for: air.item, startingAt: offset, session: "preview"), !Task.isCancelled, !model.backgrounded, model.server != nil else { return }
            let player = self.player ?? AVPlayer()
            self.player = player
            player.isMuted = true
            player.replaceCurrentItem(with: AVPlayerItem(url: src.url))
            if !src.startsAtOffset { player.seek(to: CMTime(seconds: offset, preferredTimescale: 600)) { _ in } }
            player.play()
        }
    }
}

/// AVPlayerLayer in a plain UIView: never focusable, so the remote stays with RemoteCaptureView.
private struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer?
    func makeUIView(context: Context) -> PlayerUIView {
        let v = PlayerUIView(); v.playerLayer.player = player; v.playerLayer.videoGravity = .resizeAspectFill
        v.isUserInteractionEnabled = false   // taps on the picture reach the SwiftUI preview around it
        return v
    }
    func updateUIView(_ v: PlayerUIView, context: Context) { if v.playerLayer.player !== player { v.playerLayer.player = player } }
}

private final class PlayerUIView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}

/// Scanlines (2px on / 2px off) plus an optional vignette (start fraction, alpha).
struct CRTOverlay: View {
    let lines: Double
    let vignette: (Double, Double)?
    var body: some View {
        ZStack {
            Canvas { ctx, size in
                var p = Path()
                for y in stride(from: 0, to: size.height, by: 4) { p.addRect(CGRect(x: 0, y: y, width: size.width, height: 2)) }
                ctx.fill(p, with: .color(.black.opacity(lines)))
            }
            if let v = vignette {
                EllipticalGradient(stops: [.init(color: .clear, location: v.0), .init(color: .black.opacity(v.1), location: 1)],
                                   center: .center, startRadiusFraction: 0, endRadiusFraction: 0.71)
            }
        }
        .allowsHitTesting(false)
    }
}

/// A CSS-style bevel border: light on top/left, dark on bottom/right, mitred corners.
private struct Bevel: View {
    let width: CGFloat, light: Color, dark: Color
    var body: some View {
        Canvas { ctx, size in
            let r = CGRect(origin: .zero, size: size), i = r.insetBy(dx: width, dy: width)
            var tl = Path(), br = Path()
            tl.move(to: r.origin); tl.addLine(to: CGPoint(x: r.maxX, y: r.minY)); tl.addLine(to: CGPoint(x: i.maxX, y: i.minY))
            tl.addLine(to: CGPoint(x: i.minX, y: i.maxY)); tl.addLine(to: CGPoint(x: r.minX, y: r.maxY)); tl.closeSubpath()
            br.move(to: CGPoint(x: r.maxX, y: r.minY)); br.addLine(to: CGPoint(x: r.maxX, y: r.maxY)); br.addLine(to: CGPoint(x: r.minX, y: r.maxY))
            br.addLine(to: CGPoint(x: i.minX, y: i.maxY)); br.addLine(to: CGPoint(x: i.maxX, y: i.minY)); br.closeSubpath()
            ctx.fill(tl, with: .color(light)); ctx.fill(br, with: .color(dark))
        }
    }
}

// MARK: - Info panel

private struct InfoPanel: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    @Environment(\.stageWidth) private var sw
    let gi: GuideInfo
    /// Off tvOS on a screen wider than 16:9 the panel takes the extra width (the stage widens; see RootView).
    private var extra: CGFloat { sw - 1920 }

    var body: some View {
        if t.mm { midnight } else { late }
    }

    private var late: some View {
        let titleSize = GuideInfo.fit(gi.title.count, 92, 76, 60)
        return VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 0) {
                HStack(spacing: 12) {
                    Circle().fill(t.accent).frame(width: 14, height: 14)
                    Text(model.guideFilter == .all ? "Rerun Guide" : "Rerun Guide · \(model.guideFilter == .movies ? "Movies" : "TV Shows")").font(t.heading(30, weight: .semibold))
                }
                Spacer()
                HStack(spacing: 28) {
                    HStack(spacing: 12) {
                        Text(model.profile.initial).font(t.body(20, weight: .bold)).foregroundStyle(t.accentInk)
                            .frame(width: 38, height: 38).background(Circle().fill(Color(hex: model.profile.colorHex)))
                        Text(model.profile.name).font(t.body(26)).foregroundStyle(t.mute)
                    }
                    .padding(Platform.isTV ? 0 : 12).onTouch { model.openOptions() }.padding(Platform.isTV ? 0 : -12)   // the profile chip opens the menu (it leads with Switch profile); a bigger target, same layout
                    EverySecond { d in
                        HStack(spacing: 8) {
                            Text(Clock.time(d)).font(t.heading(30, weight: .medium))
                            Text(Clock.ap(d)).font(t.heading(30, weight: .medium)).foregroundStyle(t.dim)
                        }
                    }
                }
            }
            Spacer(minLength: 0)
            ChannelLine(ch: gi.ch)
            Text(gi.title).font(t.heading(titleSize)).tracking(em: -0.025, size: titleSize).padding(.vertical, t.lineHeightTrim(titleSize)).padding(.top, 12)
            Text(gi.slot.item.infoLine).font(t.body(32)).foregroundStyle(t.mute).padding(.top, 12)
            HStack(spacing: 20) {
                ZStack(alignment: .leading) {
                    Capsule().fill(t.line)
                    Capsule().fill(t.accent).frame(width: 300 * gi.progress)
                }.frame(width: 300, height: 6)
                Text(gi.timeRange).font(t.body(26, weight: .semibold))
                Text(gi.statusText).font(t.body(26)).foregroundStyle(t.dim)
            }.padding(.top, 20)
            Text(gi.desc).font(t.body(29)).lineSpacing(6).foregroundStyle(t.mute).lineLimit(2).frame(maxWidth: 1020 + extra, alignment: .leading).padding(.top, 16)
            // A legend of remote buttons, not focusable controls: the grid is the only thing focus moves through.
            HStack(spacing: 30) {
                hint("button.programmable", touch: gi.selTouch, gi.selLabel, .select)
                hint("playpause.fill", touch: "backward.end.fill", gi.playLabel, .play)
                hint(gi.backKey, touch: gi.backTouch, gi.backLabel, .back)
            }.padding(.top, 22)
        }
        .at(744, 64, w: 1096 + extra, h: 470)
    }

    /// The boxed Midnight Movie panel: channel line + profile chip, title, status badge, square progress bar, boxed-key hints.
    private var midnight: some View {
        let titleSize = GuideInfo.fit(gi.title.count, 70, 58, 48)
        let badge: Color = switch gi.st { case .live: t.accent; case .break: t.breakBadge; case .later, .past: t.dim }
        return VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 0) {
                ChannelLine(ch: gi.ch)
                Spacer()
                HStack(spacing: 10) {
                    Rectangle().fill(Color(hex: model.profile.colorHex)).frame(width: 14, height: 14)
                    Text(model.profile.name.uppercased())
                }
                .font(t.body(28, weight: .semibold)).tracking(em: 0.08, size: 28).foregroundStyle(t.dim)
                .padding(Platform.isTV ? 0 : 12).onTouch { model.openOptions() }.padding(Platform.isTV ? 0 : -12)   // as in Late Show
            }
            Text(gi.title).font(t.heading(titleSize, weight: .bold)).padding(.vertical, t.lineHeightTrim(titleSize, weight: .bold)).padding(.top, 14)
            Text(gi.slot.item.infoLine).font(t.body(32, weight: .semibold)).foregroundStyle(t.accent).padding(.top, 10)
            HStack(spacing: 18) {
                Text(gi.timeRange)
                Text(gi.statusLabel).font(t.body(24, weight: .heavy)).tracking(em: 0.1, size: 24).foregroundStyle(t.accentInk)
                    .padding(.top, 4).padding(.bottom, 3).padding(.horizontal, 12).background(badge)
                Text(gi.statusText).foregroundStyle(t.dim)
            }
            .font(t.body(30, weight: .semibold)).padding(.top, 20)
            GeometryReader { g in
                ZStack(alignment: .leading) { t.panel2; t.accent.frame(width: g.size.width * gi.progress) }
            }
            .frame(height: 10).padding(.top, 14)
            Text(gi.desc).font(t.body(29)).lineSpacing(3).foregroundStyle(t.mute).lineLimit(2).padding(.top, 18)
            Spacer(minLength: 0)
            HStack(spacing: 28) {
                hint("button.programmable", touch: gi.selTouch, gi.selLabel, .select)
                hint("playpause.fill", touch: "backward.end.fill", gi.playLabel, .play)
                hint(gi.backKey, touch: gi.backTouch, gi.backLabel, .back)
            }
        }
        .padding(.init(top: 31, leading: 39, bottom: 29, trailing: 39))   // 3px border + 28/36/26
        .frame(width: 936 + extra, height: 450, alignment: .topLeading)
        .background(t.box)
        .overlay(Rectangle().strokeBorder(t.line, lineWidth: 3))
        .at(904, 56, w: 936 + extra, h: 450)
    }

    private func hint(_ symbol: String, touch: String, _ label: String, _ cmd: RemoteCommand) -> some View {
        KeyHint(symbol: symbol, touch: touch, label: label) { model.guideHint(cmd) }
    }
}

/// A Siri Remote key cap (the button's glyph in a box) and what it does here. Glyphs, not names: nobody calls the
/// clickpad center "Select". Off tvOS the cap shows what the button does (`touch`) and the pill is the button (`action`).
/// The guide's info panel and the watch screen's break card.
struct KeyHint: View {
    @Environment(\.theme) private var t
    let symbol: String, touch: String, label: String
    let action: () -> Void

    var body: some View {
        let mm = t.mm
        HStack(spacing: mm ? 10 : 12) {
            Image(systemName: Platform.isTV ? symbol : touch).font(.system(size: mm ? 24 : 20, weight: .bold)).foregroundStyle(mm ? t.ink : t.dim)
                .frame(width: 46, height: mm ? 36 : 32)
                .overlay(RoundedRectangle(cornerRadius: mm ? 0 : 6).stroke(mm ? t.dim : t.line, lineWidth: 2))
                .accessibilityHidden(true)   // the label says it; VoiceOver would read the symbol's name
            Text(label).font(t.body(26, weight: mm ? .semibold : .medium)).foregroundStyle(mm ? t.dim : t.mute)
        }
        .padding(.vertical, Platform.isTV ? 0 : 8).padding(.trailing, Platform.isTV ? 0 : 8)   // a bigger target, same look
        .onTouch(action)
    }
}

/// The number pill, name and tag of a channel: the info panel's channel line, reused by the attract panel.
struct ChannelLine: View {
    @Environment(\.theme) private var t
    let ch: Channel

    var body: some View {
        if t.mm {
            HStack(spacing: 14) {
                Text(String(ch.number)).font(t.body(28, weight: .heavy)).foregroundStyle(t.accent)
                Text(ch.name.uppercased()).foregroundStyle(t.ink)
                Text("/").foregroundStyle(t.slash)
                Text(ch.tag.uppercased())
            }
            .font(t.body(28, weight: .semibold)).tracking(em: 0.08, size: 28).foregroundStyle(t.dim)
        } else {
            HStack(spacing: 14) {
                Text(String(ch.number)).font(t.body(28, weight: .bold)).monospacedDigit().foregroundStyle(t.accentInk)
                    .padding(.vertical, 3).padding(.horizontal, 14).background(Capsule().fill(t.accent))
                Text(ch.name).font(t.body(28, weight: .semibold))
                Text("· " + ch.tag).font(t.body(28)).foregroundStyle(t.mute)
            }
        }
    }
}

/// Re-renders its content once a second (the clock).
private struct EverySecond<C: View>: View {
    @ViewBuilder let content: (Date) -> C
    var body: some View { TimelineView(.periodic(from: .timelineOrigin, by: 1)) { content($0.date) } }
}

// MARK: - Time header

private struct TimeHeader: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    @Environment(\.stageWidth) private var sw
    let now: Date, ws: Date
    private var extra: CGFloat { sw - 1920 }

    var body: some View {
        let labels = [0, 1, 2].map { ws + Double($0) * GuideGenerator.blockLength }
        if t.mm { timeBar(labels) } else { lateHeader(labels) }
    }

    /// Midnight Movie: a solid bar, date + clock in the channel column, one labelled cell per half hour; no now marker.
    private func timeBar(_ labels: [Date]) -> some View {
        HStack(spacing: 4) {
            EverySecond { d in
                HStack(spacing: 10) {
                    Text(Clock.date(d)).font(t.body(26, weight: .heavy)).tracking(em: 0.06, size: 26)
                    Text(Clock.time(d)).font(t.body(34, weight: .heavy))
                    Text(Clock.ap(d)).font(t.body(24, weight: .heavy))
                }
            }
            .foregroundStyle(t.accentInk).frame(width: 300, height: 60).background(t.accent)
            HStack(spacing: 0) {
                ForEach(labels, id: \.self) { d in
                    HStack(spacing: 8) { Text(Clock.time(d)).font(t.body(32, weight: .bold)); Text(Clock.ap(d)).font(t.body(24, weight: .bold)) }
                        .foregroundStyle(t.accent).padding(.leading, 26)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                        .overlay(alignment: .leading) { t.bg.frame(width: 4) }
                }
            }
            .frame(width: 1456 + extra, height: 60).background(t.box)
        }
        .at(80, 528, w: 1760 + extra, h: 60)
    }

    @ViewBuilder private func lateHeader(_ labels: [Date]) -> some View {
        let nowPct = CGFloat(now.timeIntervalSince(ws) / AppModel.windowSpan), track = 1420 + extra
        ZStack(alignment: .topLeading) {
            ForEach(labels, id: \.self) { d in
                // A column label the now pill would sit on fades out instead of colliding: from ~6 minutes before its time
                // until the pill (85 wide at most) clears the label's right edge (117 past its time), ~10 minutes after.
                let x = CGFloat(labels.firstIndex(of: d)!) / 3 * track
                let nearNow = model.windowOffset == 0 && (-95..<170).contains(nowPct * track - x)
                HStack(spacing: 6) { Text(Clock.time(d)); Text(Clock.ap(d)) }
                    .font(t.body(26, weight: .semibold)).foregroundStyle(t.dim)
                    .opacity(nearNow ? 0 : 1).animation(.easeInOut(duration: 0.4), value: nearNow)
                    .offset(x: x + 6, y: 4)
            }
            if model.windowOffset == 0 {
                EverySecond { d in
                    Text(Clock.time(d)).font(t.body(24, weight: .bold)).foregroundStyle(t.accentInk)
                        .padding(.vertical, 5).padding(.horizontal, 14).background(Capsule().fill(t.accent))
                }
                .frame(width: 200).offset(x: nowPct * track - 100)
            }
        }
        .at(420, 566, w: track, h: 40)
        if model.windowOffset == 0 {
            LinearGradient(colors: [t.accent, t.accent.opacity(0)], startPoint: .top, endPoint: .bottom)
                .at(420 + nowPct * track - 1, 612, w: 2, h: 428)
        }
    }
}

// MARK: - Grid

private extension Animation {
    /// A remote press: the row slide and the cells' focus fade share it (ease-out sine, 0.2 s), so on Up/Down the highlight
    /// holds its place while the rows pass under it, instead of lighting the next row below and riding it back up.
    static var glide: Animation { .timingCurve(0.39, 0.575, 0.565, 1, duration: 0.2) }
}

private struct GuideGrid: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    @Environment(\.stageWidth) private var sw
    let now: Date, ws: Date, gf: ProgramKey

    var body: some View {
        let list = model.visibleChannels, pitch = model.rowPitch, height = model.gridHeight
        let count = Int((height / pitch).rounded(.up)) + 2   // one row of slack above and below the viewport
        let held = model.remoteHeld, width = 1760 + sw - 1920
        TimelineView(.animation(paused: held || !model.settings.autoScroll)) { ctx in
            let raw = held ? model.guideScroll.pos : model.guideDriftOffset(at: ctx.date)
            // translateY(−raw) over rows at their raw row × pitch; the window starts one row above the row raw lands in.
            // Drifting, raw wraps at the track height and every row is replaced in place, unanimated.
            WindowRows(channels: list, first: Int((raw / pitch).rounded(.down)) - 1, count: count, ws: ws, now: now, gf: gf, width: width, pitch: pitch).equatable()
                .offset(y: -raw)
                .animation(held ? .glide : nil, value: raw)
        }
        .frame(width: width, height: height, alignment: .top)
        .mask {
            if t.mm { Color.black }
            else { LinearGradient(stops: [.init(color: .black, location: 0.78), .init(color: .clear, location: 1)], startPoint: .top, endPoint: .bottom) }
        }
        .contentShape(Rectangle())   // taps only inside the viewport: the masked slack rows sit over the header and hint pills
        .at(80, t.mm ? 596 : 616, w: width, h: height)
    }
}

/// The rows that intersect the viewport, `count` of them from raw row `first` (the lineup wraps). Row identity is the raw
/// row index, also where the held window crosses the lineup's wrap point: the same channel stays on the same row as the
/// window slides, so focus animations don't restart.
/// Each row sits at its own spot on the track (`row × pitch`), not a place in a stack, so a window step never moves a row
/// that stays: SwiftUI animates a view's frame with the view's own transaction, so a stacked row moving up a place took
/// its cells' focus fade with it (the cell gaining or losing focus dropped a row, then travelled two).
/// Depends only on (channels, window, 10-s clock, focus, first), never on the frame clock.
private struct WindowRows: View, Equatable {
    let channels: [Channel], first: Int, count: Int, ws: Date, now: Date, gf: ProgramKey, width: CGFloat, pitch: CGFloat

    var body: some View {
        let n = channels.count
        ZStack(alignment: .topLeading) {
            ForEach(first..<(first + count), id: \.self) { row in
                GuideRow(ch: channels[((row % n) + n) % n], row: row, ws: ws, now: now, gf: gf, width: width)
                    .offset(y: CGFloat(row) * pitch).transition(.identity)
            }
        }
    }
}

private struct GuideRow: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    let ch: Channel, row: Int, ws: Date, now: Date, gf: ProgramKey, width: CGFloat

    var body: some View {
        let span = AppModel.windowSpan, we = ws + span, mm = t.mm
        // Late Show: 340 column, cells inset 4 each side. Midnight Movie: 300 column, 4 gap, each cell 4 short on the right.
        let pitch = model.rowPitch, cellH: CGFloat = mm ? pitch - 4 : 88
        let colW: CGFloat = mm ? 300 : 340, gap: CGFloat = mm ? 4 : 0, trackW = width - colW - gap
        let inset: CGFloat = mm ? 0 : 4, shrink: CGFloat = mm ? 4 : 8
        let slots = model.slots(ch, from: ws, to: we)
        HStack(alignment: .top, spacing: gap) {
            ChannelCell(ch: ch).frame(width: colW, height: cellH)
            ZStack(alignment: .topLeading) {
                ForEach(slots) { s in
                    let l = max(s.start, ws), r = min(s.end, we)
                    let left = CGFloat(l.timeIntervalSince(ws) / span) * trackW, width = CGFloat(r.timeIntervalSince(l) / span) * trackW
                    let k = ProgramKey(channelID: ch.id, start: s.start)
                    GuideCell(slot: s, l: l, r: r, contL: s.start < ws, contR: s.end > we, now: now, focused: gf == k)
                        .onTouch { model.tapGuide(row: row, key: k) }
                        .at(left + inset, 0, w: max(0, width - shrink), h: cellH)
                }
            }
            .frame(width: trackW, height: cellH, alignment: .topLeading)
        }
        .frame(width: width, height: pitch, alignment: .topLeading)
        .zIndex(gf.channelID == ch.id ? 1 : 0)
    }
}

private struct ChannelCell: View {
    @Environment(\.theme) private var t
    let ch: Channel

    var body: some View {
        if t.mm {
            HStack(spacing: 18) {
                Text(String(ch.number)).font(t.body(46, weight: .heavy)).foregroundStyle(t.accent).frame(minWidth: 60, alignment: .trailing)
                Text(ch.name.uppercased()).font(t.heading(30, weight: .bold)).tracking(em: 0.05, size: 30).lineLimit(2)
            }
            .padding(.horizontal, 20)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .background(t.panel)
        } else {
            HStack(spacing: 18) {
                Text(String(ch.number)).font(t.body(26, weight: .semibold)).monospacedDigit().foregroundStyle(t.accent).frame(width: 48, alignment: .trailing)
                VStack(alignment: .leading, spacing: 2) {
                    Text(ch.name).font(t.heading(32, weight: .semibold)).lineLimit(1)
                    Text(ch.tag).font(t.body(24)).foregroundStyle(t.dim).lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

private struct GuideCell: View {
    @Environment(\.theme) private var t
    let slot: Slot, l: Date, r: Date, contL: Bool, contR: Bool, now: Date, focused: Bool

    var body: some View {
        let item = slot.item
        let st = GuideGenerator.status(of: slot, at: now), live = st == .live || st == .break
        let fill: CGFloat = live ? max(0, min(1, now.timeIntervalSince(l) / r.timeIntervalSince(l))) : 0
        let mm = t.mm, sc = focused ? (mm ? t.focusInk : t.focusDim) : t.dim   // the prototype's focused Midnight Movie sub line is the ink color
        // The clipped-edge triangle: Late Show 12×18 in the sub color, Midnight Movie 16×24 in the title color.
        let arrow = mm ? (focused ? t.focusInk : t.ink) : sc, arrowSize = mm ? CGSize(width: 16, height: 24) : CGSize(width: 12, height: 18)
        let sidePad: CGFloat = mm ? 50 : 44
        VStack(alignment: .leading, spacing: 2) {
            Text(item.displayTitle).font(t.body(mm ? 34 : 30, weight: mm ? .bold : .semibold)).lineLimit(1)
            Text(Self.sub(item, mm: mm)).font(t.body(mm ? 26 : 24, weight: .medium)).foregroundStyle(sc).lineLimit(1)
        }
        .foregroundStyle(focused ? t.focusInk : t.ink)
        .padding(.leading, contL ? sidePad : 24).padding(.trailing, contR ? sidePad : 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background {
            let base = focused ? t.focusBg : live ? t.liveCell : t.cellBg
            let fillColor = focused ? t.focusFill : (t.mm ? t.liveCell : t.line)   // Midnight Movie cells are flat
            if live {
                LinearGradient(stops: [.init(color: fillColor, location: 0), .init(color: fillColor, location: fill),
                                       .init(color: base, location: fill), .init(color: base, location: 1)],
                               startPoint: .leading, endPoint: .trailing)
            } else { base }
        }
        .overlay(alignment: .leading) {
            if contL { Arrow(left: true).fill(arrow).frame(width: arrowSize.width, height: arrowSize.height).padding(.leading, 16) }
        }
        .overlay(alignment: .trailing) {
            if contR { Arrow(left: false).fill(arrow).frame(width: arrowSize.width, height: arrowSize.height).padding(.trailing, 16) }
        }
        .clipShape(RoundedRectangle(cornerRadius: t.cellRadius))
        .scaleEffect(focused && !mm ? 1.04 : 1)
        .shadow(color: focused && t.focusShadow ? .black.opacity(0.6) : .clear, radius: 22, y: 18)
        .animation(.glide, value: focused)   // in step with the row slide (see glide)
        .zIndex(focused ? 3 : 1)
    }

    /// Late Show: "1981 · 115 min" for a movie, the episode title for an episode. Midnight Movie: "MOVIE · 1981 · PG", "“Episode”".
    static func sub(_ item: MediaItem, mm: Bool) -> String {
        switch item.kind {
        case .movie where mm: ["MOVIE", item.year.map(String.init), item.contentRating].compactMap { $0 }.joined(separator: " · ")
        case .movie: [item.year.map(String.init), "\(Int(item.runtime / 60)) min"].compactMap { $0 }.joined(separator: " · ")
        case .episode: mm ? "“\(item.title)”" : item.title
        }
    }
}

/// The ◀ / ▶ "continues" triangle on cells clipped by the window edge.
struct Arrow: Shape {
    let left: Bool
    func path(in r: CGRect) -> Path {
        var p = Path()
        if left { p.move(to: CGPoint(x: r.maxX, y: r.minY)); p.addLine(to: CGPoint(x: r.minX, y: r.midY)); p.addLine(to: CGPoint(x: r.maxX, y: r.maxY)) }
        else { p.move(to: CGPoint(x: r.minX, y: r.minY)); p.addLine(to: CGPoint(x: r.maxX, y: r.midY)); p.addLine(to: CGPoint(x: r.minX, y: r.maxY)) }
        p.closeSubpath()
        return p
    }
}
