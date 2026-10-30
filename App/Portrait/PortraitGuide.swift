import SwiftUI
import RerunGuideCore

/// Tonight's Lineup held upright (iOS handoff §1): header, the selected channel's preview, then the listings: channels
/// down (looping, like the TV's) and twelve hours across (the TV guide's reach), with the channel column and time header pinned. Left alone
/// they drift; a touch stops them until the idle setting runs out. At the accessibility text sizes the grid becomes a
/// list of what's on now and next.
struct PortraitGuide: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    @Environment(\.dynamicTypeSize) private var type
    /// Bumped when the selected channel changes: the preview's static flash.
    @State private var flash = 0

    var body: some View {
        GeometryReader { g in
            VStack(spacing: 0) {
                PortraitHeader()
                if model.visibleChannels.isEmpty {
                    Group {
                        if model.libraryLoading { RerunLoader(width: 160, delay: 1) }
                        else {
                            Text(model.libraryError ?? "Nothing to schedule yet. Try Settings › Rebuild.").font(t.pBody(17))
                                .foregroundStyle(model.libraryError == nil ? t.dim : t.accent).multilineTextAlignment(.center).padding(32)
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    TimelineView(.periodic(from: .timelineOrigin, by: 10)) { ctx in
                        if let gi = GuideInfo(model: model, now: ctx.date) {
                            if type.isAccessibilitySize { PortraitList(gi: gi) }
                            else {
                                PreviewWindow(gi: gi, portrait: g.size.width - 32)
                                    .overlay { if flash > 0 { StaticFlash().id(flash).clipShape(RoundedRectangle(cornerRadius: t.id == .midnight ? 0 : 26)) } }
                                    .padding(.top, 6)
                                PortraitGrid(gi: gi).padding(.top, 14)
                            }
                        }
                    }
                }
            }
        }
        .onChange(of: model.focused?.channelID) { flash += 1 }
    }
}

/// "● Tonight's Lineup", the date and the profile's initial (which opens Settings, where profiles switch).
struct PortraitHeader: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t

    var body: some View {
        TimelineView(.everyMinute) { ctx in content(now: ctx.date) }
    }

    private func content(now: Date) -> some View {
        HStack(spacing: 12) {
            HStack(spacing: 9) {
                t.pShape.fill(t.accent).frame(width: 10, height: 10)
                Text(t.heading("Tonight’s Lineup")).font(t.pHeading(21, style: .title3)).tracking(em: t.headingTracking, size: 21).lineLimit(1).minimumScaleFactor(0.6)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Tonight’s Lineup. Now \(Clock.time(now)) \(Clock.ap(now))")
            .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 0)
            Text(t.heading(Clock.day(now))).font(t.pBody(13, .semibold, .footnote)).foregroundStyle(t.dim).lineLimit(1)
                .accessibilityHidden(true)
            Text(model.profile.initial).font(t.pBody(16, .heavy, .subheadline)).foregroundStyle(t.accentInk)
                .frame(width: 30, height: 30).background(t.pShape.fill(Color(hex: model.profile.colorHex)))
                .frame(width: 44, height: 44).contentShape(Rectangle())
                .onTapGesture { model.go(.settings) }
                .accessibilityLabel("\(model.profile.name). Settings").accessibilityAddTraits(.isButton)
        }
        .padding(.leading, 16).padding(.trailing, 9)
        .frame(height: 44)
    }
}

extension AppModel {
    /// Portrait lineup: a show on now becomes the selected channel (the preview switches to it) and a second tap, like a
    /// tap on the preview, watches it; any other show opens Program info.
    func tapPortrait(_ k: ProgramKey) {
        closeEntry()
        guard let pr = program(k) else { return }
        switch status(pr.slot, at: .now) {
        case .live, .break: if guideKey() == k { tune(k.channelID, mode: .live) } else { focused = k }
        case .later, .past: openInfo(k)
        }
    }

    /// VoiceOver's line for a lineup cell: "Charade. Channel 9, The Matinee. 8 to 10 PM. On now, 46 minutes in."
    func portraitLabel(_ ch: Channel, _ s: Slot, at now: Date) -> String {
        let when: String = switch status(s, at: now) {
        case .live: "On now, \(Int(now.timeIntervalSince(s.start) / 60)) minutes in"
        case .break: "On now, trailers until \(Clock.time(s.end))"
        case .later: "Later"
        case .past: "Earlier"
        }
        let what = [s.item.displayTitle, s.item.kind == .episode ? s.item.title : nil].compactMap { $0 }.joined(separator: ", ")
        return "\(what). Channel \(ch.number), \(ch.name). \(Clock.range(s.start, s.end)). \(when)."
    }
}

/// Half hours across the track: twelve hours, as far as the TV guide pages ahead (`AppModel.maxWindowOffset`).
@MainActor private let blocks = AppModel.maxWindowOffset + 3

/// Handoff literals that aren't theme tokens (the iOS mockup's TH table).
private extension Theme {
    var mm: Bool { id == .midnight }
    var cell: Color { mm ? panel2 : panel }
    var liveCell: Color { mm ? panel2 : Color(hex: "#28211c") }
    var focusFill: Color { mm ? focusBg : Color(hex: "#e2d2bc") }
    var timeBar: Color { Color(hex: "#070505") }
}

// MARK: - Grid

/// The listings. Both axes are this view's own offsets (`pos`), not a ScrollView: the rows loop forever, drift on their
/// own and keep coasting after a flick, all from one clock, as the TV grid does. Only the rows in view are built.
private struct PortraitGrid: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.accessibilitySwitchControlEnabled) private var switchControl
    let gi: GuideInfo
    // Grow with the text (Dynamic Type), as the handoff asks.
    @ScaledMetric(relativeTo: .subheadline) private var colW: CGFloat = 96
    @ScaledMetric(relativeTo: .subheadline) private var slotW: CGFloat = 136
    @ScaledMetric(relativeTo: .subheadline) private var rowH: CGFloat = 64
    @ScaledMetric(relativeTo: .footnote) private var headH: CGFloat = 34

    /// Where the listings were when last touched: x across the track (0 = the current half hour), y down the endless rows.
    @State private var base = CGPoint.zero
    /// The drag in progress started here.
    @State private var dragFrom: CGPoint?
    /// A flick's velocity (points a second, in scroll direction) and when it was let go: it coasts to a stop from there.
    @State private var fling = CGVector.zero
    @State private var flungAt = Date.distantPast
    @State private var coasting = false
    /// When drifting starts again (the idle setting after the last touch; the launch's first guide holds 4 s).
    @State private var resume = Date.distantPast
    @State private var placed = false
    /// A finger is down (cleared on a cancelled touch too, which never reaches onEnded).
    @GestureState private var touching = false
    /// The touch that stopped a flick: like a scroll view's, it only stops it, the cell under it isn't tapped.
    @State private var swallow = false

    private static let drift: CGFloat = 18, coastTime = 0.5   // UIScrollView's normal deceleration, about

    /// Drift is off while VoiceOver, Switch Control or Reduce Motion is on (WCAG 2.2.2), and with the setting off.
    private var drifts: Bool { model.settings.autoScroll && !voiceOver && !switchControl && !reduceMotion }

    var body: some View {
        GeometryReader { g in
            let visW = g.size.width - colW, maxX = max(0, slotW * CGFloat(blocks) - visW)
            let gridH = max(0, g.size.height - headH)
            TimelineView(.animation(paused: !drifts && !coasting)) { ctx in
                let p = pos(at: ctx.date, maxX: maxX)
                VStack(spacing: 0) {
                    TimeHeader(gi: gi, x: p.x, colW: colW, slotW: slotW).frame(height: headH)
                    rows(p, gridH: gridH, visW: visW)
                }
            }
            .contentShape(Rectangle())
            .simultaneousGesture(drag(maxX: maxX))
            // Program info open: the listings hold still under it, and wait the idle time again once it closes.
            .onChange(of: model.overlay == nil) { _, closed in
                base = pos(at: .now, maxX: maxX); fling = .zero
                resume = closed ? .now + Double(model.settings.idleSeconds) : .distantFuture
            }
            // The track starts at the current half hour: when that moves on, keep what was in view in view.
            .onChange(of: gi.ws) { old, new in
                let d = CGFloat(new.timeIntervalSince(old) / GuideGenerator.blockLength) * slotW
                base.x = max(0, base.x - d); dragFrom?.x -= d
            }
            .onChange(of: touching) { _, down in
                guard !down else { return }
                DispatchQueue.main.async { swallow = false }   // after the cell's tap, which spends it, has run
                // A touch the system took (no onEnded): drift resumes as after any other.
                if dragFrom != nil { dragFrom = nil; resume = .now + Double(model.settings.idleSeconds) }
            }
            // Drift switched on or off (the setting, VoiceOver, Switch Control, Reduce Motion): keep what it drifted.
            .onChange(of: drifts) { was, _ in
                if was { base.y += CGFloat(max(0, Date.now.timeIntervalSince(resume))) * Self.drift }
                resume = max(resume, .now)
            }
            .accessibilityScrollAction { edge in
                let now = pos(at: .now, maxX: maxX), page = CGFloat(max(1, Int(gridH / rowH) - 1)) * rowH
                switch edge {
                case .top: base = CGPoint(x: now.x, y: now.y - page)
                case .bottom: base = CGPoint(x: now.x, y: now.y + page)
                case .leading: base = CGPoint(x: max(0, now.x - visW), y: now.y)
                default: base = CGPoint(x: min(maxX, now.x + visW), y: now.y)
                }
                fling = .zero; resume = .distantFuture
            }
        }
        .ignoresSafeArea(edges: .bottom)   // runs on under the tab bar and fades out there
        .onAppear(perform: place)
        .task(id: flungAt) {
            guard flungAt > .distantPast, (try? await Task.sleep(for: .seconds(Self.coastTime * 5))) != nil else { return }
            coasting = false
        }
    }

    /// The listings at `d`: where they were left, plus the flick's coast, plus the drift since `resume`.
    private func pos(at d: Date, maxX: CGFloat) -> CGPoint {
        let k = CGFloat(Self.coastTime * (1 - exp(-max(0, d.timeIntervalSince(flungAt)) / Self.coastTime)))
        var p = CGPoint(x: base.x + fling.dx * k, y: base.y + fling.dy * k)
        if drifts { p.y += CGFloat(max(0, d.timeIntervalSince(resume))) * Self.drift }
        p.x = max(0, min(maxX, p.x))
        return p
    }

    /// First appearance: the selected channel on the second row, as the TV comes back from watching it, and the launch's hold.
    private func place() {
        guard !placed else { return }
        placed = true
        let i = model.visibleChannels.firstIndex { $0.id == gi.ch.id } ?? 0
        base = CGPoint(x: 0, y: CGFloat(i - 1) * rowH)
        model.holdForLaunch()
        resume = model.overlay == nil ? max(.now, model.guideScroll.driftStart) : .distantFuture   // under a sheet: still until it closes
    }

    /// One drag moves both axes; touching down stops the drift and any coast, letting go coasts and re-arms the drift.
    private func drag(maxX: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .updating($touching) { _, down, _ in down = true }
            .onChanged { v in
                if dragFrom == nil {
                    let at = pos(at: .now, maxX: maxX)
                    dragFrom = at
                    let dx = (at.x <= 0 && fling.dx < 0) || (at.x >= maxX && fling.dx > 0) ? 0 : fling.dx   // pinned at an edge: not moving
                    swallow = hypot(dx, fling.dy) * exp(-Date.now.timeIntervalSince(flungAt) / Self.coastTime) > 50
                    fling = .zero; resume = .distantFuture; model.closeEntry()
                }
                guard let from = dragFrom else { return }
                base = CGPoint(x: max(0, min(maxX, from.x - v.translation.width)), y: from.y - v.translation.height)
            }
            .onEnded { v in
                dragFrom = nil
                if hypot(v.translation.width, v.translation.height) > 10 { swallow = false }   // a drag: no tap follows to spend it
                fling = CGVector(dx: -v.velocity.width, dy: -v.velocity.height); flungAt = .now; coasting = true
                resume = .now + Double(model.settings.idleSeconds)
            }
    }

    private func rows(_ p: CGPoint, gridH: CGFloat, visW: CGFloat) -> some View {
        let list = model.visibleChannels, first = Int((p.y / rowH).rounded(.down)), count = Int(gridH / rowH) + 2
        let nowX = CGFloat(gi.now.timeIntervalSince(gi.ws) / GuideGenerator.blockLength) * slotW
        return HStack(spacing: 0) {
            ColumnRows(channels: list, first: first, count: count, rowH: rowH).equatable()
                .offset(y: -p.y)
                .frame(width: colW, height: gridH, alignment: .topLeading).clipped()
            ZStack(alignment: .topLeading) {
                if !t.mm {   // Late Show's now line, behind the cells: it shows in the gaps between rows
                    LinearGradient(colors: [t.accent, t.accent.opacity(0.12)], startPoint: .top, endPoint: .bottom)
                        .frame(width: 2, height: gridH).offset(x: nowX - p.x - 1).accessibilityHidden(true)
                }
                TrackRows(channels: list, first: first, count: count, ws: gi.ws, now: gi.now, gf: gi.key, rowH: rowH, slotW: slotW,
                          tap: { if swallow { swallow = false } else { model.tapPortrait($0) } }).equatable()
                    .offset(x: -p.x, y: -p.y)
            }
            .frame(width: visW, height: gridH, alignment: .topLeading).clipped()
            .contentShape(Rectangle())   // taps only in view: cells scrolled away sit under the channel column and the time header
        }
        .mask(LinearGradient(stops: [.init(color: .black, location: 0.64), .init(color: .clear, location: 0.94)], startPoint: .top, endPoint: .bottom))
    }
}

/// Half-hour labels across the track. Late Show: a now pill on the current time. Midnight Movie: the clock in a red
/// block over the channel column and red labels on a dark bar.
private struct TimeHeader: View {
    @Environment(\.theme) private var t
    let gi: GuideInfo, x: CGFloat, colW: CGFloat, slotW: CGFloat

    var body: some View {
        let labels = (0..<blocks).map { gi.ws + Double($0) * GuideGenerator.blockLength }
        let nowX = CGFloat(gi.now.timeIntervalSince(gi.ws) / GuideGenerator.blockLength) * slotW
        HStack(spacing: 0) {
            ZStack {
                if t.mm {
                    HStack(alignment: .firstTextBaseline, spacing: 3) {
                        Text(Clock.time(gi.now)).font(t.pBody(16, .heavy, .footnote))
                        Text(Clock.ap(gi.now)).font(t.pBody(11, .heavy, .caption2))
                    }
                    .foregroundStyle(t.accentInk).frame(maxWidth: .infinity, maxHeight: .infinity).background(t.accent)
                    .padding(.leading, 8).padding(.trailing, 4).padding(.bottom, 4)
                }
            }
            .frame(width: colW)
            ZStack(alignment: .topLeading) {
                if t.mm { t.timeBar.padding(.bottom, 4) }
                ForEach(labels.indices, id: \.self) { i in
                    let lx = CGFloat(i) * slotW
                    if t.mm {
                        HStack(alignment: .firstTextBaseline, spacing: 3) {
                            Text(Clock.time(labels[i])).font(t.pBody(16, .bold, .footnote))
                            Text(Clock.ap(labels[i])).font(t.pBody(11, .bold, .caption2))
                        }
                        .foregroundStyle(t.accent).padding(.leading, 10)
                        .frame(width: slotW, alignment: .leading).frame(maxHeight: .infinity)
                        .overlay(alignment: .trailing) { t.bg.frame(width: 4) }
                        .padding(.bottom, 4)
                        .offset(x: lx - x)
                    } else {
                        // A label the now pill would sit on gives way to it.
                        Text(Clock.time(labels[i])).font(t.pBody(13, .semibold, .footnote)).foregroundStyle(t.dim)
                            .padding(.leading, 6).padding(.top, 8)
                            .opacity((-20..<66).contains(nowX - lx) ? 0 : 1)
                            .offset(x: lx - x)
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading).clipped()
        }
        .overlay(alignment: .topLeading) {   // the now pill may overhang the channel column, as on the TV (at :00 and :30 it would be cut in half)
            if !t.mm {
                Text(Clock.time(gi.now)).font(t.pBody(11, .bold, .caption2)).foregroundStyle(t.accentInk)
                    .padding(.vertical, 4).padding(.horizontal, 9).background(Capsule().fill(t.accent))
                    .fixedSize().frame(width: 100).offset(x: colW + nowX - x - 50, y: 5)
                    .opacity(nowX - x < -10 ? 0 : 1)
            }
        }
        .accessibilityHidden(true)   // the header says the time once
    }
}

/// The pinned channel column, rows `first ..< first + count` at their places on the endless track.
private struct ColumnRows: View, Equatable {
    @Environment(\.theme) private var t
    let channels: [Channel], first: Int, count: Int, rowH: CGFloat

    nonisolated static func == (a: Self, b: Self) -> Bool { a.channels == b.channels && a.first == b.first && a.count == b.count && a.rowH == b.rowH }

    var body: some View {
        let n = channels.count
        ZStack(alignment: .topLeading) {
            ForEach(first..<(first + count), id: \.self) { row in
                let ch = channels[((row % n) + n) % n]
                VStack(alignment: .leading, spacing: 3) {
                    Text(String(ch.number)).font(t.pBody(13, .bold, .footnote)).monospacedDigit().foregroundStyle(t.accent)
                    // The mockup's 13 (the Type table says 14): at 14 a long one-word name ("Adrenaline") breaks mid-word.
                    Text(t.heading(ch.name)).font(t.pHeading(13, t.mm ? .bold : .semibold, style: .subheadline))
                        .tracking(em: t.mm ? 0.04 : -0.01, size: 13).lineLimit(2)
                }
                .padding(.leading, t.mm ? 8 : 16).padding(.trailing, t.mm ? 4 : 0)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                .background(t.mm ? t.panel : .clear)
                .padding(.leading, t.mm ? 8 : 0).padding(.trailing, t.mm ? 4 : 2)
                .frame(height: rowH - 4)
                .accessibilityElement(children: .combine)
                .offset(y: CGFloat(row) * rowH)
            }
        }
    }
}

/// The track: each row's programs in the twelve hours, at their places on the endless track.
/// ponytail: every program in the twelve hours is built for each row in view (rebuilt as a row scrolls in); limit them to
/// the stretch in view if an older phone stutters.
private struct TrackRows: View, Equatable {
    @Environment(AppModel.self) private var model
    let channels: [Channel], first: Int, count: Int, ws: Date, now: Date, gf: ProgramKey, rowH: CGFloat, slotW: CGFloat
    /// A cell's tap (reads the grid's state when called, so it needn't take part in `==`).
    let tap: (ProgramKey) -> Void

    nonisolated static func == (a: Self, b: Self) -> Bool {
        a.channels == b.channels && a.first == b.first && a.count == b.count && a.ws == b.ws && a.now == b.now && a.gf == b.gf
            && a.rowH == b.rowH && a.slotW == b.slotW
    }

    var body: some View {
        let n = channels.count, block = GuideGenerator.blockLength, we = ws + block * Double(blocks)
        ZStack(alignment: .topLeading) {
            ForEach(first..<(first + count), id: \.self) { row in
                let ch = channels[((row % n) + n) % n]
                ZStack(alignment: .topLeading) {
                    ForEach(model.slots(ch, from: ws, to: we)) { s in
                        let l = max(s.start, ws), r = min(s.end, we)
                        let x = CGFloat(l.timeIntervalSince(ws) / block) * slotW, w = CGFloat(r.timeIntervalSince(l) / block) * slotW
                        let k = ProgramKey(channelID: ch.id, start: s.start)
                        PortraitCell(slot: s, l: l, r: r, contL: s.start < ws, now: now, focused: gf == k)
                            .frame(width: max(0, w - 6), height: rowH - 4)
                            .onTapGesture { tap(k) }
                            .accessibilityLabel(model.portraitLabel(ch, s, at: now))
                            .accessibilityActions { actions(ch, s, k) }
                            .offset(x: x + 3)
                            .zIndex(gf == k ? 1 : 0)
                    }
                }
                .accessibilityElement(children: .contain)   // a row reads its programs in time order
                .accessibilityLabel("Channel \(ch.number), \(ch.name)")
                .offset(y: CGFloat(row) * rowH)
                .zIndex(gf.channelID == ch.id ? 1 : 0)
            }
        }
    }

    @ViewBuilder private func actions(_ ch: Channel, _ s: Slot, _ k: ProgramKey) -> some View {
        let live = [.live, .break].contains(model.status(s, at: .now))
        if live { Button("Watch") { model.tune(ch.id, mode: .live) } }
        Button(live ? "Start from the beginning" : "Watch now") { model.tune(ch.id, mode: .start, programStart: s.start) }
        Button("Details") { model.openInfo(k) }
    }
}

/// One program: title and sub, elapsed fill while it's on (Late Show), the focus colors on the selected channel's.
private struct PortraitCell: View {
    @Environment(\.theme) private var t
    let slot: Slot, l: Date, r: Date, contL: Bool, now: Date, focused: Bool

    var body: some View {
        let st = GuideGenerator.status(of: slot, at: now), live = st == .live || st == .break, item = slot.item
        let fill: CGFloat = live && !t.mm ? max(0, min(1, now.timeIntervalSince(l) / r.timeIntervalSince(l))) : 0
        let sc = focused ? t.focusDim : t.dim
        let sub = st == .break ? "Trailers until \(Clock.time(slot.end))"
            : item.kind == .movie ? (t.mm ? ["MOVIE", item.year.map(String.init)] : [item.year.map(String.init), "\(Int(item.runtime / 60)) min"]).compactMap { $0 }.joined(separator: " · ")
            : t.mm ? "“\(item.title)”" : item.title
        let base = focused ? t.focusBg : live ? t.liveCell : t.cell, fillColor = focused ? t.focusFill : t.line
        VStack(alignment: .leading, spacing: 2) {
            Text(item.displayTitle).font(t.pBody(15, t.mm ? .bold : .semibold, .subheadline)).lineLimit(1)
            Text(sub).font(t.pBody(13, .medium, .footnote)).foregroundStyle(sc).lineLimit(1)
        }
        .foregroundStyle(focused ? t.focusInk : t.ink)
        .padding(.leading, contL ? 24 : 10).padding(.trailing, 10)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background(LinearGradient(stops: [.init(color: fillColor, location: 0), .init(color: fillColor, location: fill),
                                           .init(color: base, location: fill), .init(color: base, location: 1)],
                                   startPoint: .leading, endPoint: .trailing))
        .overlay(alignment: .leading) {
            if contL { Arrow(left: true).fill(sc).frame(width: 8, height: 12).padding(.leading, 8) }   // started before the window
        }
        .clipShape(RoundedRectangle(cornerRadius: t.mm ? 0 : 12))
        .scaleEffect(focused && !t.mm ? 1.03 : 1)
        .shadow(color: focused && !t.mm ? .black.opacity(0.55) : .clear, radius: 12, y: 10)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityAddTraits(focused ? [.isButton, .isSelected] : .isButton)
    }
}

// MARK: - Accessibility sizes

/// At the accessibility text sizes: a 72-pt preview strip, then one row per channel with what's on now (and how far in)
/// and what's next. Same taps as the grid.
private struct PortraitList: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    let gi: GuideInfo

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) {
                    PreviewWindow(gi: gi, portrait: 128, strip: true).frame(width: 128, height: 72)
                    Text(gi.pvTitle).font(t.pHeading(20, style: .title3)).lineLimit(3)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityHidden(true)   // the strip says it
                }
                .padding(.horizontal, 16).padding(.top, 6)
                LazyVStack(spacing: t.mm ? 4 : 6) {
                    ForEach(model.visibleChannels) { ch in
                        if let s = model.slotOnAir(ch, at: gi.now) {
                            OnNowRow(ch: ch, slot: s, now: gi.now, selected: gi.ch.id == ch.id, next: model.slotOnAir(ch, at: s.end))
                                .onTapGesture { model.tapPortrait(ProgramKey(channelID: ch.id, start: s.start)) }
                        }
                    }
                }
                .padding(.horizontal, 16)
            }
            .padding(.bottom, 110)   // clear of the tab bar
        }
    }
}
