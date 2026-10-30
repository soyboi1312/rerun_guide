import SwiftUI
import RerunGuideCore

// MARK: - Profiles ("Who’s watching?")

struct ProfilesScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t

    var body: some View {
        let profiles = model.persisted.profiles
        let tiles = profiles.count + (profiles.count < 5 ? 1 : 0)
        let left = (1920 - (CGFloat(tiles) * 250 + CGFloat(tiles - 1) * 64)) / 2
        ZStack(alignment: .topLeading) {
            // radial-gradient(1100px 700px at 50% 20%, glow, transparent 70%)
            EllipticalGradient(colors: [t.glow, .clear], center: .center, startRadiusFraction: 0, endRadiusFraction: 0.35)
                .at(960 - 1100, 216 - 700, w: 2200, h: 1400)
            Text(t.heading("Who’s watching?")).font(t.heading(96)).tracking(em: t.headingTracking, size: 96).foregroundStyle(t.ink)
                .at(0, 206, w: 1920, h: 96, alignment: .center)
            ForEach(0..<tiles, id: \.self) { i in
                let p = i < profiles.count ? profiles[i] : nil
                let x = left + CGFloat(i) * 314
                let editing = i == model.focusIndex && model.inlineEditing == "profileName"
                ProfileTile(initial: p?.initial ?? "+", name: editing ? "" : p?.name ?? "Add profile", sub: p.map(sub) ?? "",
                            color: p.map { Color(hex: $0.colorHex) } ?? t.panel2, ink: p == nil ? t.ink : t.accentInk,
                            focused: i == model.focusIndex)
                    .tapSelects(model) { model.profileMenu = nil; model.focusIndex = i }
                    .at(x, 382, w: 250, h: 378)
                if editing { ProfileNameField(index: i).at(x, 636, w: 250, h: 46) }
                if model.profileMenu == i {
                    // Rename / Delete row, segmented-pill style.
                    HStack(spacing: 6) {
                        ForEach(Array(model.profileActions.enumerated()), id: \.offset) { j, label in
                            let on = j == model.profileMenuIndex
                            Text(label).font(t.body(24, weight: .bold)).lineLimit(1).fixedSize()
                                .foregroundStyle(on ? t.accentInk : t.dim)
                                .padding(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                                .background(RoundedRectangle(cornerRadius: t.pill).fill(on ? t.accent : .clear))
                                .tapSelects(model) { model.profileMenuIndex = j }
                        }
                    }
                    .padding(6)
                    .background(RoundedRectangle(cornerRadius: t.pill).fill(t.panel))
                    .at(x, 776, w: 250, h: 56, alignment: .center)
                }
            }
            (Platform.isTV ? Text("Each profile keeps its own theme and lineup. Select yours, or press ") + Text(Image(systemName: "playpause.fill")) + Text(" on any, to rename or delete it.")
                           : Text("Each profile keeps its own theme and lineup. \(Platform.verb) yours to rename or delete it.")).font(t.body(28)).foregroundStyle(t.mute)
                .at(0, 840, w: 1920, h: 34, alignment: .center)
        }
        .frame(width: 1920, height: 1080)   // unclipped: off tvOS the glow carries on into the letterbox
    }

    private func sub(_ p: Profile) -> String {
        let hidden = model.channels.filter { p.hiddenChannelIDs.contains($0.id) }.count
        return "\(p.theme.displayName)\n\(model.channels.count - hidden) channels"   // two lines: one line overflows the 250-wide tile
    }
}

/// The rename field under a profile tile while `editingField == "profileName"`; same hand-back pattern as EditField.
private struct ProfileNameField: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    let index: Int
    @State private var draft = ""
    @FocusState private var focused: Bool

    var body: some View {
        TextField("", text: $draft)
            .textFieldStyle(.plain)
            .multilineTextAlignment(.center)
            .font(t.body(38, weight: .semibold))
            .foregroundStyle(t.ink)
            .frame(width: 250)
            .focused($focused)
            .onAppear { draft = model.persisted.profiles.indices.contains(index) ? model.persisted.profiles[index].name : ""; focused = true }
            .onSubmit(finish)
            .exitCommand(finish)
    }

    private func finish() {
        guard model.editingField != nil else { return }
        model.commitProfileName(index, draft)
    }
}

private struct ProfileTile: View {
    @Environment(\.theme) private var t
    let initial: String, name: String, sub: String, color: Color, ink: Color, focused: Bool

    var body: some View {
        ZStack(alignment: .topLeading) {
            Circle().fill(color)
                .overlay(Text(initial).font(t.heading(100, weight: .bold)).foregroundStyle(ink))
                .overlay(Circle().stroke(t.ink, lineWidth: 8).padding(-4).opacity(focused ? 1 : 0))   // box-shadow 0 0 0 8px ink
                .scaleEffect(focused ? 1.06 : 1)
                .at(15, 0, w: 220, h: 220)
            Text(t.heading(name)).font(t.body(38, weight: .semibold)).foregroundStyle(focused ? t.ink : t.dim).lineLimit(1)
                .at(0, 254, w: 250, h: 46, alignment: .center)
            Text(sub).font(t.body(24)).foregroundStyle(t.dim).multilineTextAlignment(.center).lineLimit(2)
                .at(0, 320, w: 250, h: 64, alignment: .top)
        }
        .animation(.easeInOut(duration: 0.18), value: focused)
    }
}

// MARK: - Settings

struct SettingsScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t

    /// `index`: the row's focus index (keyed rows only; headers have none).
    private struct Placed: Identifiable { let row: SettingsRow; let y: CGFloat; let index: Int?; var id: String { row.id } }
    private struct Layout { var rows: [Placed] = []; var ty: CGFloat = 0; var focused: SettingsRow? }

    /// Row positions and the translate that keeps the focused row centered (the prototype's `ty` math).
    private var layout: Layout {
        var l = Layout(); var y: CGFloat = 0, fy: CGFloat = 0, k = 0
        for r in model.settingsRows {
            l.rows.append(Placed(row: r, y: y, index: r.key == nil ? nil : k))
            if r.key == nil { y += 72 } else { if k == model.focusIndex { fy = y; l.focused = r }; y += 92; k += 1 }
        }
        l.ty = max(0, min(y - 904, fy - 904 / 2 + 46))
        return l
    }

    var body: some View {
        let l = layout, p = model.profile
        ZStack(alignment: .topLeading) {
            Text(t.heading("Settings")).font(t.heading(84)).tracking(em: t.headingTracking, size: 84).foregroundStyle(t.ink)
                .at(96, 96, w: 620, h: 84, alignment: .leading)
            Circle().fill(Color(hex: p.colorHex))
                .overlay(Text(p.initial).font(t.body(22, weight: .bold)).foregroundStyle(t.accentInk))
                .at(96, 204, w: 40, h: 40)
            Text("Watching as \(p.name)").font(t.body(28)).foregroundStyle(t.mute)
                .at(150, 204, w: 566, h: 40, alignment: .leading)
            VStack(alignment: .leading, spacing: 24) {
                Text(t.heading(l.focused?.label ?? "")).font(t.heading(46, weight: .semibold)).tracking(em: t.headingTracking, size: 46)
                    .lineSpacing(4.6).foregroundStyle(t.ink)
                Text(l.focused?.desc ?? "").font(t.body(30)).lineSpacing(7.5).foregroundStyle(t.mute)
            }
            .frame(width: 620, alignment: .leading)
            .at(96, 388, w: 620, h: 600)

            ZStack(alignment: .topLeading) {
                ForEach(l.rows) { pl in
                    if pl.row.key == nil {
                        Text(pl.row.label.uppercased()).font(t.body(24, weight: .bold)).tracking(em: 0.16, size: 24).foregroundStyle(t.dim)
                            .padding(.top, 30).padding(.leading, 28)
                            .at(0, pl.y, w: 1024, h: 72)
                    } else {
                        let f = pl.row.id == l.focused?.id
                        ListRow(bg: f ? t.focusBg : t.panel, fg: f ? t.focusInk : t.ink, height: 84, inset: 32) {
                            Text(pl.row.label).font(t.body(32, weight: .semibold))
                            Spacer(minLength: 24)
                            Text(pl.row.value).font(t.body(30)).foregroundStyle(f ? t.focusDim : t.dim)
                        }
                        .tapSelects(model) {   // moving off Sign out disarms it, as the remote's Up / Down do
                            if model.focusIndex != pl.index { model.signOutArmed = false }
                            model.focusIndex = pl.index ?? 0
                        }
                        .at(0, pl.y, w: 1024, h: 84)
                    }
                }
            }
            .frame(width: 1024, height: 904, alignment: .topLeading)
            .offset(y: -l.ty)
            .animation(.easeInOut(duration: 0.22), value: l.ty)
            .frame(width: 1024, height: 904)
            .clipped().contentShape(Rectangle())   // rows scrolled out of view take no taps
            .at(800, 88, w: 1024, h: 904)
        }
        .frame(width: 1920, height: 1080)
    }
}

/// A list/field row: rounded panel, horizontal padding, content vertically centered. Search's rows too.
struct ListRow<Content: View>: View {
    @Environment(\.theme) private var t
    let bg: Color, fg: Color, height: CGFloat, inset: CGFloat
    @ViewBuilder let content: Content

    var body: some View {
        HStack(spacing: 0) { content }
            .foregroundStyle(fg)
            .padding(.horizontal, inset)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(RoundedRectangle(cornerRadius: t.radius).fill(bg))
            .frame(height: height)
    }
}

// MARK: - Channel editor

struct ChannelsScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t

    var body: some View {
        let list = model.channels, idx = model.channelIndex, p = model.profile
        let hidden = list.filter { model.isHidden($0.id) }.count
        let count = "\(list.count) channels" + (hidden > 0 ? " · \(hidden) hidden for \(p.name)" : "")
        let total = CGFloat(list.count + 1) * 96 - 8
        let ty = max(0, min(total - 784, CGFloat(idx) * 96 - 784 / 2 + 44))
        ZStack(alignment: .topLeading) {
            Text(t.heading("Channels")).font(t.heading(84)).tracking(em: t.headingTracking, size: 84).foregroundStyle(t.ink)
                .at(96, 64, w: 1000, h: 84, alignment: .leading)
            Text("Drawn from your library. Rename, renumber, hide, or start one from scratch.").font(t.body(30)).foregroundStyle(t.mute)
                .at(96, 158, w: 1200, h: 36, alignment: .leading)
            Text(count).font(t.body(26)).foregroundStyle(t.dim)
                .at(1324, 157, w: 500, h: 31, alignment: .trailing)

            ZStack(alignment: .topLeading) {
                ChannelRow(n: "+", name: "New channel", tag: "From a director, an actor, or one show", badge: "",
                           state: rowState(0, idx))
                    .tapSelects(model) { model.channelSelection = nil; model.channelColumn = 0 }
                    .at(0, 0, w: 760, h: 88)
                ForEach(Array(list.enumerated()), id: \.element.id) { i, ch in
                    ChannelRow(n: String(ch.number), name: ch.name.isEmpty ? "Untitled" : ch.name,
                               tag: ch.definition.rule.map(\.label).joined(separator: " · "),
                               badge: model.isHidden(ch.id) ? "Hidden" : ch.isCustom ? "Custom" : "Auto",
                               state: rowState(i + 1, idx))
                        .tapSelects(model) { model.channelSelection = ch.id; model.channelColumn = 0 }
                        .at(0, CGFloat(i + 1) * 96, w: 760, h: 88)
                }
            }
            .frame(width: 760, height: 784, alignment: .topLeading)
            .offset(y: -ty)
            .animation(.easeInOut(duration: 0.22), value: ty)
            .frame(width: 760, height: 784)
            .clipped().contentShape(Rectangle())   // rows scrolled out of view take no taps
            .at(96, 240, w: 760, h: 784)

            if let ch = model.selectedChannel { ChannelDetail(channel: ch) } else { NewChannelIntro() }
        }
        .frame(width: 1920, height: 1080)
    }

    private func rowState(_ i: Int, _ idx: Int) -> ChannelRow.State {
        i != idx ? .plain : model.channelColumn == 0 ? .focused : .selected
    }
}

private struct ChannelRow: View {
    enum State { case plain, selected, focused }
    @Environment(\.theme) private var t
    let n: String, name: String, tag: String, badge: String, state: State

    var body: some View {
        let f = state == .focused
        let sc = f ? t.focusDim : state == .selected ? t.mute : t.dim
        ListRow(bg: f ? t.focusBg : state == .selected ? t.panel2 : t.panel, fg: f ? t.focusInk : t.ink, height: 88, inset: 28) {
            Text(n).font(t.body(34, weight: .bold)).monospacedDigit().foregroundStyle(f ? t.focusInk : t.accent)
                .frame(width: 64, alignment: .trailing)
            VStack(alignment: .leading, spacing: 2) {
                Text(t.heading(name)).font(t.body(32, weight: .semibold)).lineLimit(1)
                Text(tag).font(t.body(24)).foregroundStyle(sc).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.leading, 22)
            Text(badge).font(t.body(24, weight: .semibold)).foregroundStyle(sc).padding(.leading, 22)
        }
    }
}

private struct NewChannelIntro: View {
    @Environment(\.theme) private var t

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            Text(t.heading("Start a channel")).font(t.heading(72)).tracking(em: t.headingTracking, size: 72).foregroundStyle(t.ink)
                .frame(height: 72)
            Text("Name a director, an actor, or a single show. Rerun Guide rounds up everything that matches in your library and gives it the next free number.")
                .font(t.body(32)).lineSpacing(8).foregroundStyle(t.mute).frame(maxWidth: 820, alignment: .leading)
            (Platform.isTV ? Text("Press ") + Text(Image(systemName: "button.programmable")) + Text(" to start.") : Text("\(Platform.verb) New channel to start."))
                .font(t.body(28)).foregroundStyle(t.dim).padding(.top, 12)
        }
        .frame(width: 904, alignment: .topLeading)
        .at(920, 252, w: 904, h: 772)
    }
}

private struct ChannelDetail: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    let channel: Channel

    var body: some View {
        let ch = channel
        let editing = model.inlineEditing
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline, spacing: 24) {
                Text(String(ch.number)).font(t.heading(96)).monospacedDigit().foregroundStyle(t.accent)
                Text(t.heading(ch.name.isEmpty ? "Untitled" : ch.name)).font(t.heading(72)).tracking(em: t.headingTracking, size: 72)
                    .foregroundStyle(t.ink).lineLimit(1)
            }
            .frame(height: 96)
            // ponytail: chips never wrap; add a flow Layout if a rule ever has more than ~3 chips.
            HStack(spacing: 10) {
                ForEach(ch.definition.rule.map(\.label), id: \.self) { chip in
                    Text(chip).font(t.body(24, weight: .semibold)).foregroundStyle(t.ink)
                        .padding(.vertical, 6).padding(.horizontal, 16)
                        .background(RoundedRectangle(cornerRadius: t.pill).fill(t.panel2))
                }
            }
            Text(ch.isCustom ? "A channel you made. It keeps its schedule until you delete it."
                             : "Built automatically from your library’s tags. Your edits stay when it rebuilds.")
                .font(t.body(26)).lineSpacing(5.2).foregroundStyle(t.dim)
            TimelineView(.periodic(from: .timelineOrigin, by: 10)) { ctx in
                Text(onNow(at: ctx.date)).font(t.body(28)).foregroundStyle(t.mute).lineLimit(1)
            }
            VStack(spacing: 8) {
                ForEach(ChannelField.allCases, id: \.rawValue) { field in
                    let f = model.channelColumn == 1 && field.rawValue == model.channelFieldIndex
                    let (label, value) = model.fieldText(field, ch)
                    ListRow(bg: f ? t.focusBg : t.panel, fg: f ? t.focusInk : t.ink, height: 72, inset: 28) {
                        Text(label).font(t.body(30, weight: .semibold)).frame(maxWidth: .infinity, alignment: .leading)
                        if field == .name && editing == "chName" || field == .number && editing == "chNum" {
                            EditField(channelID: ch.id, numeric: field == .number)
                        } else {
                            Text(value).font(t.body(30, weight: .medium))
                                .foregroundStyle(field == .name || field == .number ? (f ? t.focusInk : t.ink) : (f ? t.focusDim : t.dim))
                                .padding(.leading, 24)
                        }
                    }
                    .tapSelects(model) { model.channelColumn = 1; model.channelFieldIndex = field.rawValue }
                }
            }
            .padding(.top, 12)
        }
        .frame(width: 904, alignment: .topLeading)
        .at(920, 240, w: 904, h: 784)
    }

    private func onNow(at now: Date) -> String {
        guard let cur = model.slotOnAir(channel, at: now) else { return "" }
        var s = "On now: \(cur.item.displayTitle)"
        if let next = model.slotOnAir(channel, at: cur.end) {
            s += "  ·  Next at \(cur.end.formatted(.dateTime.hour(.defaultDigits(amPM: .omitted)).minute())): \(next.item.displayTitle)"
        }
        return s
    }
}

/// The channel editor's inline text field, shown only while `model.editingField` names it. Takes focus on
/// appear, commits on submit / Menu (not focus loss: the tvOS keyboard takes focus while typing), then hands the
/// remote back by clearing `editingField`.
private struct EditField: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    let channelID: String
    let numeric: Bool
    @State private var draft = ""
    @FocusState private var focused: Bool

    var body: some View {
        let number = Binding(get: { model.numberDraft ?? "" },
                             set: { model.numberDraft = String($0.filter(\.isNumber).prefix(3)) })
        TextField("", text: numeric ? number : $draft)
            .textFieldStyle(.plain)
            .keyboardType(numeric ? .numberPad : .default)
            .multilineTextAlignment(.trailing)
            .font(t.body(30, weight: .medium))
            .foregroundStyle(t.focusInk)
            .frame(width: 440)
            .focused($focused)
            .onAppear { draft = model.channel(channelID)?.name ?? ""; focused = true }
            .onSubmit(finish)
            .exitCommand(finish)
    }

    private func finish() {
        guard model.editingField != nil else { return }
        if numeric { model.commitNumber() } else { let name = draft; model.updateChannel(channelID) { $0.name = name } }
        model.editingField = nil
    }
}
