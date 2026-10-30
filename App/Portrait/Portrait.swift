import SwiftUI
import RerunGuideCore

/// iPhone and iPad held upright (Design: the iOS handoff, `design_handoff_rerun_guide_ios/README.md`): Lineup, Channels and
/// Settings behind a floating tab bar, Now playing pushed over them, Program info and New channel as sheets. These are
/// window-space views over the same AppModel; a tap does what the remote's Select does on the TV layout (focus, then
/// `press(.select)`), so every rule lives in one place. Screens with no portrait design stay on the stage (RootView).
struct PortraitRoot: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    @Environment(\.accessibilityReduceTransparency) private var solid

    var body: some View {
        let watching = model.screen == .watch
        ZStack(alignment: .bottom) {
            if !watching {
                t.bg.ignoresSafeArea()
                if t.id == .late {
                    RadialGradient(colors: [t.glow, .clear], center: UnitPoint(x: 0.22, y: 0.1), startRadius: 0, endRadius: 360)
                        .ignoresSafeArea().allowsHitTesting(false)
                }
            }
            Group {
                switch model.screen {
                case .watch: PortraitWatch()
                case .channels: PortraitChannels()
                case .settings: PortraitSettings()
                default: PortraitGuide()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            if !watching && !(model.screen == .channels && model.channelColumn == 1) { PortraitTabs() }
            toast
            // Midnight Movie's screen lines and vignette over everything but playback (none with Reduce Transparency).
            if t.id == .midnight, model.settings.crtLines, !watching, !solid {
                CRTOverlay(lines: 0.09, vignette: (0.62, 0.38)).ignoresSafeArea()
            }
        }
        .foregroundStyle(t.ink)
        .sheet(isPresented: Binding(get: { Self.sheet(model.overlay) }, set: { if !$0, Self.sheet(model.overlay) { model.closeOverlay() } })) {
            PortraitSheet().environment(model).environment(\.theme, model.theme)
        }
    }

    private static func sheet(_ o: Overlay?) -> Bool {
        switch o { case .info, .newChannel: true; default: false }
    }

    @ViewBuilder private var toast: some View {
        ZStack {
            if let toast = model.toast {
                Text(toast.message).font(t.pBody(15, .semibold, .subheadline)).foregroundStyle(t.ink).multilineTextAlignment(.center)
                    .padding(.vertical, 12).padding(.horizontal, 18)
                    .background(RoundedRectangle(cornerRadius: t.id == .midnight ? 0 : 16).fill(t.panel2).shadow(color: .black.opacity(0.5), radius: 16, y: 8))
                    .padding(.horizontal, 16).padding(.bottom, model.screen == .watch ? 24 : 100)
                    .transition(.opacity.combined(with: .offset(y: 10)))
                    .id(toast.n)
                    .onAppear { UIAccessibility.post(notification: .announcement, argument: toast.message) }
            }
        }
        .animation(.easeOut(duration: 0.25), value: model.toast)
        .allowsHitTesting(false)
    }
}

// MARK: - Tab bar

/// Lineup · Channels · Settings, floating 22 pt above the bottom edge. Late Show: a blurred pill, the tab on in cream.
/// Midnight Movie: a square dark bar, the tab on in red.
private struct PortraitTabs: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t

    var body: some View {
        let mm = t.id == .midnight
        HStack(spacing: 4) {
            tab("Lineup", .guide); tab("Channels", .channels); tab("Settings", .settings)
        }
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)   // as the system tab bar: bigger sizes read out on a long press
        .padding(5)
        .frame(height: 58).frame(maxWidth: 520)
        .background {
            if mm { Color(hex: "#070505") } else { Capsule().fill(Color(hex: "#26201c").opacity(0.86)).background(.ultraThinMaterial, in: Capsule()) }
        }
        .overlay { mm ? AnyView(Rectangle().strokeBorder(t.line, lineWidth: 2)) : AnyView(Capsule().strokeBorder(t.ink.opacity(0.08), lineWidth: 1)) }
        .shadow(color: .black.opacity(0.45), radius: 15, y: 12)
        .padding(.horizontal, 20).padding(.bottom, 22)
        .ignoresSafeArea(edges: .bottom)
    }

    private func tab(_ label: String, _ s: Screen) -> some View {
        let on = model.screen == s
        return Text(t.heading(label)).font(t.pBody(15, t.id == .midnight ? .heavy : .semibold, .subheadline))
            .tracking(em: t.id == .midnight ? 0.08 : 0, size: 15)
            .foregroundStyle(on ? t.focusInk : t.mute).lineLimit(1).minimumScaleFactor(0.7)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(t.pShape.fill(on ? t.focusBg : .clear))
            .contentShape(Rectangle())
            .onTapGesture {
                if s == .channels { model.channelColumn = 0 }
                if !on { model.go(s) }
            }
            .accessibilityAddTraits(on ? [.isButton, .isSelected] : .isButton)
            .accessibilityShowsLargeContentViewer()
    }
}

// MARK: - Shared rows

/// A channel and what's on it now: number, title, "Gumshoe · 46 min in" (or the break), a small progress bar. Now
/// playing's Also on now; the lineup's list at the accessibility sizes adds what's next.
struct OnNowRow: View {
    @Environment(\.theme) private var t
    let ch: Channel, slot: Slot, now: Date
    var selected = false
    var next: Slot? = nil

    var body: some View {
        let into = now.timeIntervalSince(slot.start), brk = into >= slot.item.runtime
        let line = "\(t.heading(ch.name)) · " + (brk ? "Trailers until \(Clock.time(slot.end))" : "\(Int(into / 60)) min in")
        HStack(spacing: 12) {
            Text(String(ch.number)).font(t.pBody(15, .heavy, .subheadline)).monospacedDigit().foregroundStyle(selected ? t.focusInk : t.accent)
                .frame(minWidth: 24, alignment: .trailing)
            VStack(alignment: .leading, spacing: 1) {
                Text(slot.item.displayTitle).font(t.pBody(15, .semibold, .subheadline)).lineLimit(2)
                Text(line).font(t.pBody(12, .regular, .footnote)).foregroundStyle(selected ? t.focusDim : t.dim).lineLimit(2)
                if let next { Text("Next · \(Clock.time(next.start)) \(next.item.displayTitle)").font(t.pBody(12, .regular, .footnote)).foregroundStyle(selected ? t.focusDim : t.dim) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            ZStack(alignment: .leading) {
                t.pShape.fill(selected ? t.focusDim : t.line)
                t.pShape.fill(selected ? t.focusInk : t.accent).frame(width: 40 * max(0, min(1, into / slot.duration)))
            }
            .frame(width: 40, height: 3)
        }
        .foregroundStyle(selected ? t.focusInk : t.ink)
        .padding(.horizontal, 14).padding(.vertical, 8)
        .frame(minHeight: 56)
        .background(RoundedRectangle(cornerRadius: t.id == .midnight ? 0 : 12).fill(selected ? t.focusBg : t.panel))
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Channel \(ch.number), \(ch.name). \(slot.item.displayTitle). " + (brk ? "Trailers until \(Clock.time(slot.end))" : "\(Int(into / 60)) minutes in, of \(Self.span(slot.duration))")
                            + (next.map { ". Next at \(Clock.time($0.start)), \($0.item.displayTitle)" } ?? ""))
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }

    /// "2 hours", "90 minutes".
    static func span(_ t: TimeInterval) -> String {
        let m = Int(t / 60)
        return m % 60 == 0 ? "\(m / 60) hour" + (m == 60 ? "" : "s") : "\(m) minutes"
    }
}

/// A section label and its rounded group of rows (Settings, the channel editor).
private struct PGroup<Content: View>: View {
    @Environment(\.theme) private var t
    let title: String?
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let title { t.pLabel(title).padding(.horizontal, 4).accessibilityAddTraits(.isHeader) }
            VStack(spacing: 0) { content }
                .background(t.pGroup.fill(t.panel))
                .clipShape(t.pGroup)
        }
        .padding(.horizontal, 16).padding(.top, 24)
    }
}

/// One row: label (and a description under it), then a switch (`toggle`, showing On/Off values), else the value and a chevron.
private struct PRow: View {
    @Environment(\.theme) private var t
    let label: String
    var value = ""
    var desc = ""
    var toggle = false
    var first = false
    var tint: Color? = nil
    var chevron = true
    let action: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(t.pBody(17, .semibold)).foregroundStyle(tint ?? t.ink)
                if !desc.isEmpty { Text(desc).font(t.pBody(13, .regular, .footnote)).foregroundStyle(t.dim).fixedSize(horizontal: false, vertical: true) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if toggle { PSwitch(on: value == "On") }
            else if !value.isEmpty || chevron {
                Text(value).font(t.pBody(15, .regular, .subheadline)).foregroundStyle(t.dim).multilineTextAlignment(.trailing)
                if chevron { Text("›").font(t.pBody(20)).foregroundStyle(t.dim).accessibilityHidden(true) }
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .frame(minHeight: 52)
        .overlay(alignment: .top) { if !first { (t.id == .midnight ? t.bg : t.ink.opacity(0.07)).frame(height: t.id == .midnight ? 2 : 1) } }
        .contentShape(Rectangle())
        .onTapGesture(perform: action)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityValue(value)
        .accessibilityHint(desc)
        .accessibilityAddTraits(.isButton)
    }
}

/// Late Show: a rounded switch, amber when on. Midnight Movie: a square one, red when on with a dark knob.
private struct PSwitch: View {
    @Environment(\.theme) private var t
    let on: Bool
    var body: some View {
        let mm = t.id == .midnight
        ZStack(alignment: on ? .trailing : .leading) {
            t.pShape.fill(on ? t.accent : t.line)
            t.pShape.fill(on && mm ? t.bg : t.ink).frame(width: 27, height: 27).padding(2).shadow(color: .black.opacity(0.3), radius: 3, y: 2)
        }
        .frame(width: 51, height: 31)
        .animation(.easeOut(duration: 0.15), value: on)
        .accessibilityHidden(true)
    }
}

/// A large screen title and its line (Channels, Settings).
private struct PTitle: View {
    @Environment(\.theme) private var t
    let title: String
    var sub: String? = nil
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(t.heading(title)).font(t.pHeading(34, style: .largeTitle)).tracking(em: t.id == .midnight ? 0 : -0.035, size: 34)
                .accessibilityAddTraits(.isHeader)
            if let sub { Text(sub).font(t.pBody(15, .regular, .subheadline)).foregroundStyle(t.mute) }
        }
        .padding(.horizontal, 20).padding(.top, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Settings

/// The TV's Settings list, grouped: a tap on a row is the remote's Select on it (`pressSettings`). The profile card
/// switches profiles in place; Who's watching, Server and Sign out go to screens with no portrait design (the stage).
private struct PortraitSettings: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    @State private var picking = false

    var body: some View {
        let rows = model.settingsRows, keys = rows.compactMap(\.key)
        let sections = rows.reduce(into: [(String, [SettingsRow])]()) { out, r in
            if r.key == nil { out.append((r.label, [])) } else if r.key != .profile { out[out.count - 1].1.append(r) }
        }.filter { !$0.1.isEmpty }
        ScrollView {
            VStack(spacing: 0) {
                PTitle(title: "Settings")
                profileCard
                ForEach(sections, id: \.0) { title, items in
                    PGroup(title: title) {
                        ForEach(Array(items.enumerated()), id: \.element.id) { i, r in
                            if r.key == .theme { themePicker(first: i == 0) }
                            else {
                                // Switches only for the On/Off rows: Subtitles and Attract mode also read "On" or "Off" at times.
                                PRow(label: r.label, value: r.value, desc: r.key == .about ? r.desc : Self.short(r),
                                     toggle: [.crt, .auto, .breaks, .spots, .idents].contains(r.key), first: i == 0,
                                     tint: r.key == .rebuild || r.key == .signout ? t.accent : nil,
                                     chevron: r.key != .rebuild && r.key != .signout && r.key != .about) {
                                    guard let k = r.key, let j = keys.firstIndex(of: k) else { return }
                                    model.focusIndex = j; model.press(.select)
                                }
                            }
                        }
                    }
                }
            }
            .frame(maxWidth: 700)
            .frame(maxWidth: .infinity)
            .padding(.bottom, 120)   // clear of the tab bar
        }
        .confirmationDialog("Who’s watching?", isPresented: $picking, titleVisibility: .visible) {
            ForEach(model.persisted.profiles) { p in
                Button(p.name) {
                    guard p.id != model.persisted.currentProfileID else { return }
                    model.switchProfile(p.id); model.guideReset(); model.toast("Hi, \(p.name).")
                }
            }
            Button("Add, rename or delete…") { model.go(.profiles); model.focusIndex = model.persisted.profiles.firstIndex { $0.id == model.persisted.currentProfileID } ?? 0 }
        }
    }

    /// The handoff's one-line descriptions where it has them; the TV's longer ones elsewhere (the spots' says how to report one).
    private static func short(_ r: SettingsRow) -> String {
        switch r.key {
        case .crt: "On guide previews, never on playback"
        case .auto: "Like the old preview channel"
        case .rebuild, .signout, .theme: ""
        default: r.desc
        }
    }

    private var profileCard: some View {
        let p = model.profile
        return HStack(spacing: 14) {
            Text(p.initial).font(t.pBody(18, .heavy, .headline)).foregroundStyle(t.accentInk)
                .frame(width: 44, height: 44).background(t.pShape.fill(Color(hex: p.colorHex)))
            VStack(alignment: .leading, spacing: 2) {
                Text(p.name).font(t.pBody(16, .semibold, .headline))
                Text("Switch profile").font(t.pBody(13, .regular, .footnote)).foregroundStyle(t.dim)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Text("›").font(t.pBody(20)).foregroundStyle(t.dim)
        }
        .padding(.vertical, 12).padding(.horizontal, 16)
        .background(t.pGroup.fill(t.panel))
        .padding(.horizontal, 16).padding(.top, 18)
        .contentShape(Rectangle())
        .onTapGesture { picking = true }
        .accessibilityElement(children: .ignore).accessibilityLabel("\(p.name). Switch profile").accessibilityAddTraits(.isButton)
    }

    /// Theme as two segments, each in its own theme's face. Applies at once and is saved per profile.
    private func themePicker(first: Bool) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Theme").font(t.pBody(17, .semibold))
            HStack(spacing: 4) {
                ForEach(ThemeID.allCases, id: \.self) { id in
                    let on = model.profile.theme == id, th = id.theme
                    Text(th.heading(id.displayName)).font(th.heading(id == .late ? 15 : 17, style: .subheadline)).tracking(em: id == .late ? -0.01 : 0.06, size: 15)
                        .foregroundStyle(on ? t.focusInk : t.mute).lineLimit(1).minimumScaleFactor(0.7)
                        .frame(maxWidth: .infinity).frame(minHeight: 44)
                        .background(t.pShape.fill(on ? t.focusBg : .clear))
                        .contentShape(Rectangle())
                        .onTapGesture { model.setTheme(id) }
                        .accessibilityAddTraits(on ? [.isButton, .isSelected] : .isButton)
                }
            }
            .padding(4).background(t.pShape.fill(t.bg))
        }
        .padding(.horizontal, 16).padding(.vertical, 14)
        .accessibilityElement(children: .contain).accessibilityLabel("Theme")
    }
}

// MARK: - Channels

/// The lineup's channels, hidden ones too: a row opens the editor (pushed), "+ New channel" the picker sheet.
private struct PortraitChannels: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t

    var body: some View {
        if model.channelColumn == 1, let ch = model.selectedChannel { PortraitChannelEditor(ch: ch) }
        else {
            ScrollView {
                LazyVStack(spacing: t.id == .midnight ? 4 : 6) {
                    PTitle(title: "Channels", sub: "Built from your library. Change anything, or make your own.").padding(.bottom, 12)
                    row(n: "+", name: "New channel", tag: "From a director, an actor, or one show", badge: "") {
                        model.channelSelection = nil; model.channelColumn = 0; model.press(.select)
                    }
                    ForEach(model.channels) { ch in
                        row(n: String(ch.number), name: ch.name.isEmpty ? "Untitled" : ch.name, tag: ch.definition.rule.map(\.label).joined(separator: " · "),
                            badge: model.isHidden(ch.id) ? "Hidden" : ch.isCustom ? "Custom" : "Auto") {
                            model.channelSelection = ch.id; model.channelColumn = 1; model.channelFieldIndex = 0
                        }
                    }
                }
                .frame(maxWidth: 700).frame(maxWidth: .infinity)
                .padding(.bottom, 120)
            }
        }
    }

    private func row(n: String, name: String, tag: String, badge: String, _ action: @escaping () -> Void) -> some View {
        HStack(spacing: 14) {
            Text(n).font(t.pBody(17, .bold, .headline)).monospacedDigit().foregroundStyle(t.accent).frame(minWidth: 30, alignment: .trailing)
            VStack(alignment: .leading, spacing: 2) {
                Text(t.heading(name)).font(t.pBody(16, .semibold, .headline)).lineLimit(2)
                if !tag.isEmpty { Text(tag).font(t.pBody(13, .regular, .footnote)).foregroundStyle(t.dim).lineLimit(2) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if !badge.isEmpty { Text(badge).font(t.pBody(13, .semibold, .footnote)).foregroundStyle(t.dim) }
            Text("›").font(t.pBody(20)).foregroundStyle(t.dim).accessibilityHidden(true)
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .frame(minHeight: 60)
        .background(RoundedRectangle(cornerRadius: t.id == .midnight ? 0 : 12).fill(t.panel))
        .padding(.horizontal, 16)
        .contentShape(Rectangle())
        .onTapGesture(perform: action)
        .accessibilityElement(children: .combine).accessibilityAddTraits(.isButton)
    }
}

/// The channel editor, pushed: the TV's fields as rows, a tap on one its Select (`pressChannels`). Name and number type
/// in TextEntry's alert, as on the stage.
private struct PortraitChannelEditor: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    let ch: Channel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 6) {
                    Text("‹").font(t.pBody(24)).foregroundStyle(t.accent)
                    Text(t.heading("Channels")).font(t.pBody(15, .semibold, .subheadline))
                }
                .padding(.leading, 10).padding(.trailing, 14).frame(height: 36)
                .background(t.pShape.fill(t.panel))
                .frame(minHeight: 44).contentShape(Rectangle())
                .onTapGesture { model.channelColumn = 0 }
                .accessibilityElement(children: .ignore).accessibilityLabel("Back to Channels").accessibilityAddTraits(.isButton)
                .padding(.horizontal, 16).padding(.top, 4)
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text(String(ch.number)).font(t.pHeading(40, style: .largeTitle)).monospacedDigit().foregroundStyle(t.accent)
                    Text(t.heading(ch.name.isEmpty ? "Untitled" : ch.name)).font(t.pHeading(30, style: .largeTitle)).lineLimit(2)
                }
                .padding(.horizontal, 20).padding(.top, 16)
                .accessibilityElement(children: .combine).accessibilityAddTraits(.isHeader)
                Text(ch.definition.rule.map(\.label).joined(separator: " · ")).font(t.pBody(14, .semibold, .subheadline)).foregroundStyle(t.mute)
                    .padding(.horizontal, 20).padding(.top, 6)
                Text(ch.isCustom ? "A channel you made. It keeps its schedule until you delete it."
                                 : "Built automatically from your library’s tags. Your edits stay when it rebuilds.")
                    .font(t.pBody(13, .regular, .footnote)).foregroundStyle(t.dim).padding(.horizontal, 20).padding(.top, 6)
                PGroup(title: nil) {
                    ForEach(ChannelField.allCases, id: \.rawValue) { f in
                        let (label, value) = model.fieldText(f, ch)
                        PRow(label: label, value: value, toggle: f == .breaks || f == .show, first: f == .name, tint: f == .delete ? t.accent : nil, chevron: f != .delete) {
                            model.channelColumn = 1; model.channelFieldIndex = f.rawValue; model.press(.select)
                        }
                    }
                }
            }
            .frame(maxWidth: 700).frame(maxWidth: .infinity)
            .padding(.bottom, 40)
        }
    }
}

// MARK: - Sheets

/// Program info and the New channel picker, over the portrait screens.
private struct PortraitSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    @State private var height: CGFloat = 560
    /// What the sheet shows: kept while it slides away after an action ended the overlay (Watch live, Edit channel).
    @State private var shown: Overlay?

    var body: some View {
        ScrollView {
            Group {
                switch model.overlay ?? shown {
                case .info(let key): ProgramInfo(key: key)
                case .newChannel: NewChannel()
                default: EmptyView()
                }
            }
            .background(GeometryReader { g in Color.clear.onAppear { height = g.size.height }.onChange(of: g.size.height) { height = $1 } })
        }
        .scrollBounceBehavior(.basedOnSize)
        .onChange(of: model.overlay, initial: true) { _, o in if let o { shown = o } }
        .foregroundStyle(t.ink)
        .overlay(alignment: .top) {   // the grabber; Midnight Movie's sheet also has a red top edge
            ZStack(alignment: .top) {
                if t.id == .midnight { t.accent.frame(height: 3) }
                t.pShape.fill(t.line).frame(width: 38, height: 5).padding(.top, 10)
            }
            .accessibilityHidden(true)
        }
        .presentationDetents([.height(min(height, 760)), .large])
        .presentationDragIndicator(.hidden)
        .presentationBackground(t.panel)
        .presentationCornerRadius(t.id == .midnight ? 0 : 28)
    }
}

/// A program: still with its when-chip, channel line, title, sub, when, description, then Program info's actions
/// (`infoActions`), the first in the focus style and the extra ones (Edit channel) as text buttons.
private struct ProgramInfo: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    let key: ProgramKey

    var body: some View {
        if let (ch, slot) = model.program(key) {
            let now = Date.now, st = model.status(slot, at: now), acts = model.infoActions(key)
            let chip: String = switch st { case .live, .break: "On now"; case .later: "Later · \(Clock.time(slot.start))"; case .past: "Earlier" }
            let when: String = switch st {
            case .live: "\(Int(now.timeIntervalSince(slot.start) / 60)) min in"
            case .break: "Trailers until \(Clock.time(slot.end))"
            case .later: "Starts in \(Int((slot.start.timeIntervalSince(now) / 60).rounded(.up))) min"
            case .past: "Aired earlier tonight"
            }
            VStack(alignment: .leading, spacing: 0) {
                ZStack(alignment: .topLeading) {
                    HueGradient(hue: ch.hue)
                    AsyncImage(url: model.server?.backdropURL(for: slot.item)) { $0.resizable().scaledToFill() } placeholder: { Color.clear }
                    CRTOverlay(lines: 0.2, vignette: nil)
                    Text(chip.uppercased()).font(t.pBody(11, .bold, .caption2)).tracking(em: 0.1, size: 11).foregroundStyle(t.focusInk)
                        .padding(.vertical, 4).padding(.horizontal, 10).background(t.pShape.fill(t.focusBg)).padding(12)
                }
                .aspectRatio(16 / 9, contentMode: .fit).frame(maxWidth: .infinity)
                .clipShape(t.pGroup)
                .accessibilityHidden(true)
                Text("Channel \(ch.number) · \(ch.name)".uppercased()).font(t.pBody(12, .bold, .caption)).tracking(em: 0.1, size: 12)
                    .foregroundStyle(t.accent).padding(.top, 16)
                Text(t.heading(slot.item.displayTitle)).font(t.pHeading(32, style: .largeTitle)).tracking(em: t.id == .midnight ? 0 : -0.035, size: 32)
                    .padding(.top, 6).accessibilityAddTraits(.isHeader)
                Text(slot.item.infoLine).font(t.pBody(15, .regular, .subheadline)).foregroundStyle(t.mute).padding(.top, 8)
                Text("\(Clock.range(slot.start, slot.end)) · \(when)").font(t.pBody(14, .regular, .subheadline)).foregroundStyle(t.dim).padding(.top, 3)
                if let o = slot.item.overview, !o.isEmpty {
                    Text(o).font(t.pBody(15)).lineSpacing(5).foregroundStyle(t.mute).padding(.top, 12).fixedSize(horizontal: false, vertical: true)
                }
                VStack(spacing: 10) {
                    ForEach(Array(acts.enumerated()), id: \.offset) { i, a in
                        Text(t.heading(a.label)).font(t.pBody(16, i == 0 ? .bold : .semibold, .headline)).tracking(em: t.id == .midnight ? 0.06 : 0, size: 16)
                            .foregroundStyle(i == 0 ? t.focusInk : i == 1 ? t.ink : t.dim)
                            .frame(maxWidth: .infinity).frame(minHeight: i < 2 ? 50 : 44)
                            .background(t.pShape.fill(i == 0 ? t.focusBg : i == 1 ? t.panel2 : .clear))
                            .contentShape(Rectangle())
                            .onTapGesture { a.run() }
                            .accessibilityAddTraits(.isButton)
                    }
                }
                .padding(.top, 22)
            }
            .padding(.horizontal, 20).padding(.top, 31).padding(.bottom, 24)
        }
    }
}

/// New channel: what to build it from, then who (or which show), as on the TV (`pressNewChannel`).
private struct NewChannel: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t

    var body: some View {
        let step = model.newChannelStep, unit = model.newChannelType == .series ? "episodes" : "films"
        let rows: [(String, String)] = step == 0
            ? zip(ChannelBuilder.CandidateKind.allCases, ["One filmmaker, back to back.", "Follow one face through your library.", "One series, every episode in order."]).map { ($0.rawValue, $1) }
            : model.candidateCache.map { ($0.name, "\($0.count) \(unit) in your library") }
        let title = step == 0 ? "Build it from…" : model.newChannelType == .director ? "Pick a director" : model.newChannelType == .actor ? "Pick an actor" : "Pick a show"
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                t.pLabel("New channel").foregroundStyle(t.accent)
                Spacer()
                if step == 1 {
                    Text(t.heading("‹ Back")).font(t.pBody(15, .semibold, .subheadline)).foregroundStyle(t.accent)
                        .frame(minHeight: 44).contentShape(Rectangle())
                        .onTapGesture { model.press(.back) }
                        .accessibilityAddTraits(.isButton)
                }
            }
            Text(t.heading(title)).font(t.pHeading(30, style: .largeTitle)).padding(.top, 4).accessibilityAddTraits(.isHeader)
            Text(step == 1 ? "Only names with enough in your library to fill an evening are listed."
                           : "Rerun Guide finds everything that matches and puts it on a schedule.")
                .font(t.pBody(15, .regular, .subheadline)).foregroundStyle(t.mute).padding(.top, 6).fixedSize(horizontal: false, vertical: true)
            LazyVStack(spacing: t.id == .midnight ? 4 : 6) {
                ForEach(Array(rows.enumerated()), id: \.offset) { i, r in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t.heading(r.0)).font(t.pBody(17, .semibold, .headline))
                        Text(r.1).font(t.pBody(13, .regular, .footnote)).foregroundStyle(t.dim)
                    }
                    .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
                    .padding(.horizontal, 16).padding(.vertical, 6)
                    .background(RoundedRectangle(cornerRadius: t.id == .midnight ? 0 : 12).fill(t.panel2))
                    .contentShape(Rectangle())
                    .onTapGesture { model.focusIndex = i; model.press(.select) }
                    .accessibilityElement(children: .combine).accessibilityAddTraits(.isButton)
                }
                if rows.isEmpty { Text("Nothing in your library has enough to fill an evening yet.").font(t.pBody(15)).foregroundStyle(t.dim) }
            }
            .padding(.top, 18)
        }
        .padding(.horizontal, 20).padding(.top, 31).padding(.bottom, 24)
    }
}
