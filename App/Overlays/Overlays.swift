import SwiftUI
import RerunGuideCore

// MARK: - Options menu (prototype "Options menu")

struct OptionsOverlay: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    @Environment(\.stageWidth) private var sw

    var body: some View {
        ZStack(alignment: .topLeading) {
            t.scrim.backgroundTap { model.press(.back) }
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 14) {
                    Circle().fill(t.accent).frame(width: 14, height: 14)
                    Text(t.heading("Rerun Guide")).font(t.heading(32, weight: .semibold))
                }
                .padding(.horizontal, 24).padding(.bottom, 28)
                ForEach(Array(AppModel.OptionRow.allCases.enumerated()), id: \.offset) { i, row in
                    Group {
                        // The Theme row's chips are its buttons; one around the row would swallow them for VoiceOver.
                        if row == .theme { OptionRowView(row: row, focused: i == model.focusIndex) }
                        else { OptionRowView(row: row, focused: i == model.focusIndex).tapSelects(model) { model.focusIndex = i } }
                    }
                    .padding(.top, row == .guide || row == .theme ? 24 : 0)
                }
                Spacer(minLength: 0)
                Text("\(model.serverLabel) · connected").font(t.body(24)).lineSpacing(5).foregroundStyle(t.dim).padding(.horizontal, 24)
            }
            .padding(EdgeInsets(top: 72, leading: 48, bottom: 72, trailing: 48))
            .frame(width: 620, height: 1080, alignment: .topLeading)
            .foregroundStyle(t.ink)
            // Off tvOS the panel runs on to the window's left, top and bottom edges past the stage (600 covers the margins).
            .background(t.panel.padding(Platform.isTV ? EdgeInsets() : EdgeInsets(top: -600, leading: -600, bottom: -600, trailing: 0))
                .shadow(color: .black.opacity(0.5), radius: 40, x: 30))
            .at(0, 0, w: 620, h: 1080)
            .modifier(Enter(dx: -40))
        }
        .frame(width: sw, height: 1080)
    }
}

private struct OptionRowView: View {
    let row: AppModel.OptionRow
    let focused: Bool
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t

    var body: some View {
        let f = t.fv(focused)
        HStack(spacing: 20) {
            if row == .profile {
                Text(model.profile.initial).font(t.body(28, weight: .bold)).foregroundStyle(t.accentInk)
                    .frame(width: 56, height: 56).background(Circle().fill(Color(hex: model.profile.colorHex)))
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(t.heading(label)).font(t.body(34, weight: .semibold)).lineLimit(row == .theme ? 1 : nil).foregroundStyle(isActiveGuide && !focused ? t.accent : f.fg)
                if row == .profile { Text("Switch profile").font(t.body(24)).foregroundStyle(f.sc) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if row == .theme {
                HStack(spacing: 6) {
                    ForEach(ThemeID.allCases, id: \.self) { id in
                        let on = id == t.id
                        Text(id.displayName).font(t.body(24, weight: .bold)).fixedSize()
                            .foregroundStyle(on ? (focused ? t.focusBg : t.accentInk) : (focused ? t.focusInk : t.dim))
                            .padding(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                            .background(RoundedRectangle(cornerRadius: t.pill).fill(on ? (focused ? t.focusInk : t.accent) : .clear))
                            .onTouch { model.setTheme(id) }   // the remote's Left / Right on this row
                    }
                }
            }
        }
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity, minHeight: 84, alignment: .leading)   // 84, not 88: eight rows fit above the footer
        .background(RoundedRectangle(cornerRadius: t.radius).fill(f.bg))
    }

    /// The guide row that matches the current filter is tinted so you can see which guide you're on.
    private var isActiveGuide: Bool {
        switch row { case .guide: model.guideFilter == .all; case .movies: model.guideFilter == .movies; case .tv: model.guideFilter == .tv; default: false }
    }

    private var label: String {
        switch row {
        case .profile: model.profile.name
        case .guide: "Guide"
        case .movies: "Movies"
        case .tv: "TV Shows"
        case .search: "Search"
        case .channels: "Channels"
        case .settings: "Settings"
        case .theme: "Theme"
        }
    }
}

// MARK: - Program info (prototype "Program info")

struct InfoOverlay: View {
    let key: ProgramKey
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    @Environment(\.stageWidth) private var sw

    var body: some View {
        ZStack {
            t.scrim.backgroundTap { model.press(.back) }
            if let p = model.program(key) {
                TimelineView(.periodic(from: .timelineOrigin, by: 10)) { ctx in sheet(p.channel, p.slot, now: ctx.date) }
                    .modifier(Enter(dy: 14))
            } else {
                Color.clear.onAppear { model.closeOverlay() }
            }
        }
        .frame(width: sw, height: 1080)
    }

    private func sheet(_ ch: Channel, _ slot: Slot, now: Date) -> some View {
        let st = model.status(slot, at: now)
        let kind: String = switch st { case .live, .break: "On now"; case .later: "Later"; case .past: "Earlier" }
        let acts = model.infoActions(key)
        return HStack(alignment: .top, spacing: 56) {
            ZStack(alignment: .topLeading) {
                HueGradient(hue: ch.hue)
                // In the card's frame: filled art wider than the card would widen the stack and push the pill and title off its left edge.
                AsyncImage(url: model.server?.backdropURL(for: slot.item)) { $0.resizable().scaledToFill() } placeholder: { Color.clear }
                    .frame(width: 520, height: 390)
                Text(kind.uppercased()).font(t.body(24, weight: .bold)).tracking(em: 0.1, size: 24).foregroundStyle(t.accentInk)
                    .padding(EdgeInsets(top: 6, leading: 14, bottom: 6, trailing: 14))
                    .background(RoundedRectangle(cornerRadius: t.pill).fill(t.accent))
                    .padding(EdgeInsets(top: 22, leading: 24, bottom: 0, trailing: 0))
                Text(t.heading(slot.item.displayTitle)).font(t.heading(46)).foregroundStyle(.white)
                    .padding(EdgeInsets(top: 0, leading: 28, bottom: 26, trailing: 28))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
            }
            .frame(width: 520, height: 390)
            .clipShape(RoundedRectangle(cornerRadius: t.radius))

            VStack(alignment: .leading, spacing: 14) {
                Text("Channel \(ch.number) · \(ch.name)".uppercased()).font(t.body(26, weight: .semibold)).tracking(em: 0.1, size: 26).foregroundStyle(t.accent)
                Text(t.heading(slot.item.displayTitle)).font(t.heading(76)).tracking(em: t.headingTracking, size: 76).foregroundStyle(t.ink)
                Text(slot.item.infoLine).font(t.body(32)).foregroundStyle(t.mute)
                Text("\(Clock.range(slot.start, slot.end)) · \(statusText(slot, st, at: now))").font(t.body(28)).foregroundStyle(t.dim)
                if let overview = slot.item.overview { Text(overview).font(t.body(30)).lineSpacing(8).foregroundStyle(t.mute) }
                Spacer(minLength: 0)
                ActionPills(labels: acts.map(\.label))
            }
            .frame(maxWidth: .infinity, minHeight: 390, alignment: .topLeading)
        }
        .padding(56)
        .frame(width: 1480)
        .fixedSize(horizontal: false, vertical: true)
        .background(RoundedRectangle(cornerRadius: t.radius).fill(t.panel).shadow(color: .black.opacity(0.6), radius: 60, y: 40))
    }
}

// MARK: - New channel (prototype "New channel")

struct NewChannelOverlay: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    @Environment(\.stageWidth) private var sw

    var body: some View {
        let cards = cards
        let rows = (cards.count + 2) / 3
        // ponytail: 2-row window around the focus (4 rows of 240 don't fit the stage); upgrade: animated row scroll.
        let first = max(0, min(model.focusIndex / 3 - 1, rows - 2)) * 3
        ZStack {
            t.scrim.backgroundTap { model.press(.back) }
            VStack(alignment: .leading, spacing: 18) {
                Text("NEW CHANNEL").font(t.body(26, weight: .bold)).tracking(em: 0.14, size: 26).foregroundStyle(t.accent)
                Text(t.heading(title)).font(t.heading(76)).tracking(em: t.headingTracking, size: 76).foregroundStyle(t.ink)
                Text(model.newChannelStep == 1 ? "Only names with enough in your library to fill an evening are listed."
                                               : "Rerun Guide finds everything that matches and puts it on a schedule.")
                    .font(t.body(30)).foregroundStyle(t.mute)
                VStack(spacing: 24) {
                    ForEach(Array(stride(from: first, to: min(first + 6, cards.count), by: 3)), id: \.self) { r in
                        HStack(alignment: .top, spacing: 24) {
                            ForEach(r..<r + 3, id: \.self) { i in
                                if i < cards.count { card(cards[i], focused: i == model.focusIndex).tapSelects(model) { model.focusIndex = i } }
                                else { Color.clear.frame(maxWidth: .infinity) }
                            }
                        }
                    }
                }
                .padding(.top, 28)
                .animation(.easeInOut(duration: 0.15), value: model.focusIndex)
            }
            .padding(64)
            .frame(width: 1440)
            .fixedSize(horizontal: false, vertical: true)
            .background(RoundedRectangle(cornerRadius: t.radius).fill(t.panel).shadow(color: .black.opacity(0.6), radius: 60, y: 40))
            .modifier(Enter(dy: 14))
        }
        .frame(width: sw, height: 1080)
    }

    private var title: String {
        guard model.newChannelStep == 1, let kind = model.newChannelType else { return "Build it from…" }
        return switch kind { case .director: "Pick a director"; case .actor: "Pick an actor"; case .series: "Pick a show" }
    }

    private var cards: [(name: String, desc: String)] {
        if model.newChannelStep == 0 {
            let desc = ["One filmmaker, back to back.", "Follow one face through your library.", "One series, every episode in order."]
            return zip(ChannelBuilder.CandidateKind.allCases, desc).map { ($0.rawValue, $1) }
        }
        let unit = model.newChannelType == .series ? "episodes" : "films"
        return model.candidateCache.map { ($0.name, "\($0.count) \(unit) in your library") }
    }

    private func card(_ c: (name: String, desc: String), focused: Bool) -> some View {
        let f = t.fv(focused)
        return VStack(alignment: .leading, spacing: 16) {
            Text(t.heading(c.name)).font(t.heading(50)).foregroundStyle(f.fg)
            Spacer(minLength: 0)
            Text(c.desc).font(t.body(28)).lineSpacing(4).foregroundStyle(f.sc)
        }
        .padding(EdgeInsets(top: 36, leading: 40, bottom: 36, trailing: 40))
        .frame(maxWidth: .infinity, minHeight: 240, maxHeight: .infinity, alignment: .topLeading)
        .background(RoundedRectangle(cornerRadius: t.radius).fill(f.bg))
        .modifier(Lift(on: focused))
    }
}

// MARK: - Make a channel (Search)

/// Search's page for a result that isn't on in the next 24 hours (just Make a channel), and a film's people to make a
/// channel of. Back returns to the Search page.
struct MakeChannelOverlay: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    @Environment(\.stageWidth) private var sw

    var body: some View {
        ZStack {
            t.scrim.backgroundTap { model.press(.back) }
            if let e = model.searchSelection {
                VStack(alignment: .leading, spacing: 14) {
                    Text(([e.isShow ? "Show" : "Movie"] + [e.year.map(String.init)].compactMap { $0 }).joined(separator: " · ").uppercased())
                        .font(t.body(26, weight: .semibold)).tracking(em: 0.1, size: 26).foregroundStyle(t.accent)
                    Text(t.heading(e.title)).font(t.heading(76)).tracking(em: t.headingTracking, size: 76).foregroundStyle(t.ink)
                    Text(model.makePeople == nil ? model.airsText(e) : "Pick who the channel follows. Only names with enough in your library to fill an evening are listed.")
                        .font(t.body(30)).lineSpacing(6).foregroundStyle(t.mute)
                    ActionPills(labels: model.makeActions.map(\.label))
                }
                .padding(56)
                .frame(width: 1100, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
                .background(RoundedRectangle(cornerRadius: t.radius).fill(t.panel).shadow(color: .black.opacity(0.6), radius: 60, y: 40))
                .modifier(Enter(dy: 14))
            } else {
                Color.clear.onAppear { model.closeOverlay() }
            }
        }
        .frame(width: sw, height: 1080)
    }
}

// MARK: - Number keys (top-right box)

/// The channel number being typed on the guide or the player, "2 2 _", with the channel once the digits are one; or that
/// there's no such channel.
struct ChannelEntryView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    @Environment(\.stageWidth) private var sw

    var body: some View {
        ZStack(alignment: .topTrailing) {
            // Closed, the box stays and fades out showing what it last said (a removed view would keep its last frame,
            // which never had a third digit's entry: that tunes in the same press).
            if let e = model.channelEntry ?? model.lastEntry {
                VStack(alignment: .trailing, spacing: 6) {
                    if e.missing {
                        Text("No channel \(Int(e.digits) ?? 0)").font(t.body(32, weight: .semibold)).foregroundStyle(t.ink)
                    } else {
                        Text((e.digits + String(repeating: "_", count: max(0, 3 - e.digits.count))).map(String.init).joined(separator: " "))
                            .font(t.heading(72)).monospacedDigit().foregroundStyle(t.accent)
                        if let ch = Int(e.digits).flatMap(model.channel(numbered:)) {
                            Text(t.heading("\(ch.number) · \(ch.name)")).font(t.body(30, weight: .semibold)).foregroundStyle(t.ink).lineLimit(1)
                        }
                    }
                }
                .padding(EdgeInsets(top: 20, leading: 34, bottom: 22, trailing: 34))
                .frame(minWidth: 260, alignment: .trailing)
                .background(RoundedRectangle(cornerRadius: t.radius).fill(t.panel2).shadow(color: .black.opacity(0.5), radius: 30, y: 20))
                .padding(.top, 56).padding(.trailing, 72)
                .animation(nil, value: model.channelEntry == nil)   // opening or closing swaps the content at once: only the fade animates
                .opacity(model.channelEntry == nil ? 0 : 1)
                .transition(.opacity)
            }
        }
        .frame(width: sw, height: 1080, alignment: .topTrailing)
        .animation(.easeOut(duration: 0.2), value: model.channelEntry)
        .allowsHitTesting(false)
        .accessibilityHidden(true)   // VoiceOver hears the box through focusDescription; the faded one stays mounted
    }
}

// MARK: - Toast (prototype toastOn/toastMsg: top-right card)

struct ToastView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    @Environment(\.stageWidth) private var sw

    var body: some View {
        ZStack(alignment: .topTrailing) {
            if let toast = model.toast {
                Text(toast.message).font(t.body(28, weight: .semibold)).foregroundStyle(t.ink)
                    .padding(EdgeInsets(top: 20, leading: 30, bottom: 20, trailing: 30))
                    .background(RoundedRectangle(cornerRadius: t.radius).fill(t.panel2).shadow(color: .black.opacity(0.5), radius: 30, y: 20))
                    .frame(maxWidth: 820, alignment: .trailing)
                    .padding(.top, 56).padding(.trailing, 72)
                    .transition(.opacity.combined(with: .offset(y: 14)))
                    .id(toast.n)
            }
        }
        .frame(width: sw, height: 1080, alignment: .topTrailing)
        .animation(.easeOut(duration: 0.25), value: model.toast)
        .allowsHitTesting(false)
    }
}

// MARK: - Shared bits

/// Program info's buttons (the Make a channel page's too): pills that wrap, the focused one lifted.
private struct ActionPills: View {
    let labels: [String]
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t

    var body: some View {
        Flow(spacing: 18) {
            ForEach(Array(labels.enumerated()), id: \.offset) { i, label in
                let f = t.fv(i == model.focusIndex)
                Text(label).font(t.body(30, weight: .semibold)).foregroundStyle(f.fg)
                    .padding(EdgeInsets(top: 18, leading: 34, bottom: 18, trailing: 34))
                    .background(RoundedRectangle(cornerRadius: t.pill).fill(f.bg))
                    .tapSelects(model) { model.focusIndex = i }
                    .modifier(Lift(on: i == model.focusIndex))
            }
        }
        .padding(.top, 20)
        .animation(.easeInOut(duration: 0.15), value: model.focusIndex)
    }
}

private extension Theme {
    /// The prototype's fv(): focused → focus bg/ink/sub, else panel/ink/dim.
    func fv(_ focused: Bool) -> (bg: Color, fg: Color, sc: Color) { focused ? (focusBg, focusInk, focusDim) : (panel, ink, dim) }
}

/// Focus lift: scale + shadow.
private struct Lift: ViewModifier {
    let on: Bool
    @Environment(\.theme) private var t
    func body(content: Content) -> some View {
        content.scaleEffect(on ? t.focusScale : 1)
            .shadow(color: .black.opacity(on && t.focusShadow ? 0.55 : 0), radius: 22, y: 18)
            .zIndex(on ? 1 : 0)
    }
}

/// The prototype's rbIn / rbSlide entrance (220 ms fade + slide from `dx`/`dy`).
private struct Enter: ViewModifier {
    var dx: CGFloat = 0, dy: CGFloat = 0
    @State private var shown = false
    func body(content: Content) -> some View {
        content.opacity(shown ? 1 : 0).offset(x: shown ? 0 : dx, y: shown ? 0 : dy)
            .onAppear { withAnimation(.easeOut(duration: 0.22)) { shown = true } }
    }
}

/// Channel-hue preview background (the prototype's pvBg): Midnight Movie is a 160° linear ramp, Late Show a radial one.
struct HueGradient: View {
    let hue: Double
    @Environment(\.theme) private var t
    var body: some View {
        if t.id == .midnight {
            LinearGradient(colors: [.channel(hue: hue, lightness: 0.46, chroma: 0.14), .channel(hue: hue, lightness: 0.2, chroma: 0.08)],
                           startPoint: UnitPoint(x: 0.33, y: 0.03), endPoint: UnitPoint(x: 0.67, y: 0.97))
        } else {
            // ponytail: circle stands in for the CSS 130%×100% ellipse at 25% 15%.
            RadialGradient(stops: [.init(color: .channel(hue: hue, lightness: 0.5, chroma: 0.1), location: 0),
                                   .init(color: .channel(hue: hue, lightness: 0.24, chroma: 0.05), location: 0.55),
                                   .init(color: .channel(hue: hue, lightness: 0.13, chroma: 0.02), location: 1)],
                           center: UnitPoint(x: 0.25, y: 0.15), startRadius: 0, endRadius: 600)
        }
    }
}

/// CSS `display:flex; flex-wrap:wrap; gap:` for the info sheet's buttons.
private struct Flow: Layout {
    var spacing: CGFloat
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let w = proposal.width.flatMap { $0.isFinite ? $0 : nil }
        let f = frames(width: w ?? .infinity, subviews)
        return CGSize(width: w ?? (f.map(\.maxX).max() ?? 0), height: f.map(\.maxY).max() ?? 0)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for (f, v) in zip(frames(width: bounds.width, subviews), subviews) {
            v.place(at: CGPoint(x: bounds.minX + f.minX, y: bounds.minY + f.minY), proposal: ProposedViewSize(f.size))
        }
    }
    private func frames(width: CGFloat, _ subviews: Subviews) -> [CGRect] {
        var x: CGFloat = 0, y: CGFloat = 0, rowH: CGFloat = 0, out: [CGRect] = []
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > 0 && x + s.width > width { x = 0; y += rowH + spacing; rowH = 0 }
            out.append(CGRect(origin: CGPoint(x: x, y: y), size: s))
            x += s.width + spacing; rowH = max(rowH, s.height)
        }
        return out
    }
}

private func statusText(_ slot: Slot, _ st: ProgramStatus, at now: Date) -> String {
    switch st {
    case .live: "On now, \(Int(now.timeIntervalSince(slot.start) / 60)) min in"
    case .break: "On now, between programs"
    case .later: "Starts in \(Int((slot.start.timeIntervalSince(now) / 60).rounded(.up))) min"
    case .past: "Aired earlier tonight"
    }
}
