import SwiftUI
import RerunGuideCore

/// Now playing held upright (iOS handoff §3): a top bar, the picture, then the banner (channel, title, CH rocker,
/// progress, controls) and what else is on. The picture is the stage's own WatchScreen, which RootView draws under
/// this view at `videoTop`, full width at 16:9: this view leaves that box clear and takes its swipes.
struct PortraitWatch: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    /// The top bar (44) and its gap (6) above the picture.
    static let videoTop: CGFloat = 50

    var body: some View {
        GeometryReader { g in
            TimelineView(.periodic(from: .timelineOrigin, by: 1)) { ctx in
                VStack(spacing: 0) {
                    topBar(model.watchInfo(at: ctx.date)).frame(height: 44).padding(.bottom, 6)
                        .background(t.bg.ignoresSafeArea(edges: .top))
                    Color.clear.frame(height: g.size.width * 9 / 16)
                        .contentShape(Rectangle())
                        .gesture(DragGesture(minimumDistance: 24).onEnded { v in   // up and down flip channels, as on the TV
                            let dy = v.translation.height
                            if abs(dy) > abs(v.translation.width), abs(dy) > 40 { model.flip(dy < 0 ? 1 : -1) }
                        })
                        .accessibilityHidden(true)
                    ScrollView {
                        if let info = model.watchInfo(at: ctx.date) { panel(info, now: ctx.date, width: g.size.width - 32) }
                        alsoOnNow(now: ctx.date)
                    }
                    .frame(maxHeight: .infinity)
                    .background(t.bg.ignoresSafeArea(edges: .bottom))
                }
            }
        }
        .onChange(of: model.tunes) {   // VoiceOver hears where a channel change landed
            guard UIAccessibility.isVoiceOverRunning, let i = model.watchInfo() else { return }
            UIAccessibility.post(notification: .announcement, argument: "Channel \(i.channel.number), \(i.channel.name), \(i.slot.item.displayTitle)")
        }
    }

    // MARK: Top bar

    private func topBar(_ info: WatchInfo?) -> some View {
        let spot = SpotOnAir.shared.started != nil, paused = model.playback?.paused == true
        let chip = spot ? "Ad" : info?.isBreak == true ? "Break" : paused ? "Paused" : info?.live != false ? "Live" : "Rerun"
        let subs = model.trackChoices(.subtitle)
        return HStack(spacing: 8) {
            HStack(spacing: 6) {
                Text("‹").font(t.pBody(22)).foregroundStyle(t.accent).offset(y: -2)
                Text(t.heading("Lineup")).font(t.pBody(14, .semibold, .subheadline)).tracking(em: t.id == .midnight ? 0.06 : 0, size: 14)
            }
            .padding(.leading, 10).padding(.trailing, 14).frame(height: 36)
            .background(t.pShape.fill(t.panel))
            .frame(minHeight: 44).contentShape(Rectangle())
            .onTapGesture { model.touchBack() }
            .accessibilityElement(children: .ignore).accessibilityLabel("Back to Lineup").accessibilityAddTraits(.isButton)
            Spacer()
            if !subs.isEmpty {
                Menu {
                    ForEach(subs.indices, id: \.self) { i in
                        Button { pick(i) } label: { if subs[i].on { Label(subs[i].label, systemImage: "checkmark") } else { Text(subs[i].label) } }
                    }
                } label: {
                    Image(systemName: "captions.bubble").font(.system(size: 17, weight: .semibold)).foregroundStyle(t.ink)
                        .frame(width: 44, height: 36).background(t.pShape.fill(t.panel))
                        .frame(height: 44).contentShape(Rectangle())
                }
                .accessibilityLabel("Subtitles")
            }
            // Phones: the TV player, sideways (also with Portrait Orientation Lock on, which no turn of the phone gets past).
            if UIDevice.current.userInterfaceIdiom == .phone {
                Image(systemName: "arrow.up.left.and.arrow.down.right").font(.system(size: 15, weight: .semibold)).foregroundStyle(t.ink)
                    .frame(width: 44, height: 36).background(t.pShape.fill(t.panel))
                    .frame(height: 44).contentShape(Rectangle())
                    .onTapGesture { model.fullScreen = true }
                    .accessibilityLabel("Full screen").accessibilityAddTraits(.isButton)
            }
            Text(chip.uppercased()).font(t.pBody(11, .bold, .caption2)).tracking(em: 0.1, size: 11).foregroundStyle(t.accentInk)
                .padding(.vertical, 5).padding(.horizontal, 11).background(t.pShape.fill(t.accent))
        }
        .padding(.horizontal, 14)
    }

    /// A row of the subtitle menu, as the TV banner's list applies it (one reload at the current position).
    private func pick(_ i: Int) {
        model.openTrackMenu(.subtitle); model.trackMenu?.focus = i; model.press(.select)
    }

    // MARK: Banner

    private func panel(_ info: WatchInfo, now: Date, width: CGFloat) -> some View {
        let ch = info.channel, slot = info.slot, item = slot.item, paused = model.playback?.paused == true
        let spot = SpotOnAir.shared, mins = Int(info.position / 60)
        let behind = !info.live && now >= slot.start && now < slot.end ? now.timeIntervalSince(slot.start) - info.position : 0
        let status = paused ? "Paused"   // the TV banner's wording and order (a time-shifted break is "behind live")
            : info.live ? (info.isBreak ? "Back at \(Clock.time(slot.end))" : "Live · \(mins) min in")   // trailers, the station card or the spot
            : behind >= 60 ? "\(Int((behind / 60).rounded())) min behind live" : behind >= 3 ? "\(Int(behind.rounded())) s behind live"
            : "From the beginning · \(mins) min in"
        let titleSize: CGFloat = item.displayTitle.count <= 18 ? 28 : 24
        let first: (String, () -> Void) = spot.started != nil
            ? (spot.skippable ? "Skip ad" : "Skip in \(min(Int(AppModel.spotSkipDelay), max(1, Int((AppModel.spotSkipDelay - now.timeIntervalSince(spot.started ?? now)).rounded(.up)))))", { model.skipSpot() })
            : info.live ? ("From the beginning", { model.tune(ch.id, mode: .start, programStart: slot.start) })
            : ("Back to live", { model.tune(ch.id, mode: .live, force: true) })
        let second: (String, () -> Void) = info.isBreak && info.next != nil ? ("Watch next", { model.startNextFromBreak() })
            : (paused ? "Play" : "Pause", { model.togglePause() })
        let last: (String, () -> Void) = (model.lastChannel.map { "Last · \($0.number)" } ?? "Last", { model.goLast() })
        return VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 14) {
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        Text(String(ch.number)).font(t.pBody(14, .heavy, .subheadline)).foregroundStyle(t.accentInk)
                            .padding(.vertical, 2).padding(.horizontal, 9).background(t.pShape.fill(t.accent))
                        Text(t.heading(ch.name)).font(t.pBody(14, .semibold, .subheadline)).tracking(em: t.id == .midnight ? 0.06 : 0, size: 14).lineLimit(1).layoutPriority(1)
                        Text("· " + ch.tag).font(t.pBody(14, .regular, .subheadline)).foregroundStyle(t.mute).lineLimit(1)
                    }
                    Text(t.heading(item.displayTitle)).font(t.pHeading(titleSize, style: .title2)).tracking(em: t.headingTracking, size: titleSize).lineLimit(3)
                    Text(item.kind == .episode ? "“\(item.title)”" + (item.seasonEpisode.isEmpty ? "" : " · " + item.seasonEpisode) : item.infoLine)
                        .font(t.pBody(15, .regular, .subheadline)).foregroundStyle(t.mute).lineLimit(2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
                rocker(info)
            }
            VStack(spacing: 8) {
                HStack(spacing: 10) {
                    Text(Clock.time(slot.start)); bar(info.position / slot.duration); Text(Clock.time(slot.end))
                }
                .font(t.pBody(12, .semibold, .footnote)).monospacedDigit().foregroundStyle(t.mute)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("Progress").accessibilityValue("\(mins) minutes in, of \(OnNowRow.span(slot.duration))")
                HStack(spacing: 12) {
                    Text(status).font(t.pBody(13, .bold, .footnote)).foregroundStyle(t.accent).fixedSize()
                    Spacer(minLength: 0)
                    if let next = info.next {
                        Text("Up next · \(Clock.time(next.start)) \(next.item.displayTitle)").font(t.pBody(13, .regular, .footnote)).foregroundStyle(t.dim).lineLimit(1)
                    }
                }
            }
            HStack(spacing: 8) {
                control(first.0, width: (width - 16) * 1.35 / 3.35, first.1)
                control(second.0, width: (width - 16) / 3.35, second.1)
                control(last.0, width: (width - 16) / 3.35, last.1)
            }
        }
        .padding(.horizontal, 16).padding(.top, 18)
    }

    private func bar(_ p: Double) -> some View {
        GeometryReader { g in
            ZStack(alignment: .leading) { t.pShape.fill(t.line); t.pShape.fill(t.accent).frame(width: g.size.width * max(0, min(1, p))) }
        }
        .frame(height: 5)
    }

    private func control(_ label: String, width: CGFloat, _ action: @escaping () -> Void) -> some View {
        Text(t.heading(label)).font(t.pBody(13, .semibold, .footnote)).tracking(em: t.id == .midnight ? 0.06 : 0, size: 13)
            .lineLimit(1).minimumScaleFactor(0.7)
            .frame(width: width).frame(minHeight: 44)
            .background(t.pShape.fill(t.panel2))
            .contentShape(Rectangle())
            .onTapGesture(perform: action)
            .accessibilityAddTraits(.isButton)
    }

    /// ▲ CH ▼: wraps around the lineup. One adjustable element for VoiceOver (swipe up or down to change channel).
    private func rocker(_ info: WatchInfo) -> some View {
        VStack(spacing: 0) {
            Text("▲").font(.system(size: 13)).frame(maxWidth: .infinity).frame(height: 46).contentShape(Rectangle()).onTapGesture { model.flip(1) }
            Text("CH").font(t.pBody(10, .heavy, .caption2)).tracking(em: 0.12, size: 10).foregroundStyle(t.accent).frame(height: 18)
            Text("▼").font(.system(size: 13)).frame(maxWidth: .infinity).frame(height: 46).contentShape(Rectangle()).onTapGesture { model.flip(-1) }
        }
        .frame(width: 56)
        .background(t.pShape.fill(t.panel2))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Channel")
        .accessibilityValue("Channel \(info.channel.number), \(info.channel.name), \(info.slot.item.displayTitle)")
        .accessibilityAdjustableAction { d in model.flip(d == .increment ? 1 : -1) }
    }

    // MARK: Also on now

    private func alsoOnNow(now: Date) -> some View {
        let here = model.playback?.channelID
        return VStack(alignment: .leading, spacing: 8) {
            t.pLabel("Also on now").padding(.horizontal, 20).accessibilityAddTraits(.isHeader)
            LazyVStack(spacing: t.id == .midnight ? 4 : 6) {
                ForEach(model.visibleChannels.filter { $0.id != here }) { ch in
                    if let s = model.slotOnAir(ch, at: now) {
                        OnNowRow(ch: ch, slot: s, now: now).onTapGesture { model.tune(ch.id, mode: .live) }
                    }
                }
            }
            .padding(.horizontal, 16)
        }
        .padding(.top, 22).padding(.bottom, 24)
    }
}
