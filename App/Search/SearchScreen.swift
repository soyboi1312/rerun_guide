import SwiftUI
import RerunGuideCore

/// Options › Search: the text field, then up to 50 films and shows that match, each with when it's on next. The list
/// keeps the focused row in view like the channel editor's.
struct SearchScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t

    var body: some View {
        let results = model.searchResults, focus = model.searchFocus, query = model.searchQuery
        let listH: CGFloat = 688, total = CGFloat(results.count) * 96 - 8
        let ty = max(0, min(total - listH, CGFloat(focus - 1) * 96 - listH / 2 + 44))
        ZStack(alignment: .topLeading) {
            Text(t.heading("Search")).font(t.heading(84)).tracking(em: t.headingTracking, size: 84).foregroundStyle(t.ink)
                .at(96, 64, w: 1000, h: 84, alignment: .leading)
            Text("Find a film or a show in your library, see when it’s on next, or make a channel of it.").font(t.body(30)).foregroundStyle(t.mute)
                .at(96, 158, w: 1500, h: 36, alignment: .leading)

            let f = focus == 0
            ListRow(bg: f ? t.focusBg : t.panel, fg: f ? t.focusInk : t.ink, height: 88, inset: 32) {
                Image(systemName: "magnifyingglass").font(.system(size: 30, weight: .semibold)).foregroundStyle(f ? t.focusDim : t.dim)
                    .frame(width: 40).padding(.trailing, 20).accessibilityHidden(true)
                if model.inlineEditing == "search" { SearchField() }
                else {
                    Text(query.isEmpty ? "Type a title" : query).font(t.body(32, weight: .medium)).lineLimit(1)
                        .foregroundStyle(query.isEmpty ? (f ? t.focusDim : t.dim) : (f ? t.focusInk : t.ink))
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .tapSelects(model) { model.searchFocus = 0 }
            .at(96, 232, w: 1728, h: 88)

            if results.isEmpty {
                Text(query.isEmpty ? "Films by title, shows by name." : "Nothing in your library matches “\(query)”.")
                    .font(t.body(30)).foregroundStyle(t.dim)
                    .at(128, 356, w: 1600, h: 36, alignment: .leading)
            }
            TimelineView(.periodic(from: .timelineOrigin, by: 10)) { ctx in
                ZStack(alignment: .topLeading) {
                    ForEach(Array(results.enumerated()), id: \.element.id) { i, e in
                        ResultRow(entry: e, airs: model.airsText(e, at: ctx.date), onNow: model.searchAirings[e.id].map { $0.start <= ctx.date } ?? false,
                                  focused: focus == i + 1)
                            .tapSelects(model) { model.searchFocus = i + 1 }
                            .at(0, CGFloat(i) * 96, w: 1728, h: 88)
                    }
                }
                .frame(width: 1728, height: listH, alignment: .topLeading)
                .offset(y: -ty)
                .animation(.easeInOut(duration: 0.22), value: ty)
                .onChange(of: ctx.date) { _, d in model.refreshAirings(at: d) }
            }
            .frame(width: 1728, height: listH)
            .clipped().contentShape(Rectangle())   // rows scrolled out of view take no taps
            .at(96, 344, w: 1728, h: listH)
        }
        .frame(width: 1920, height: 1080)
    }
}

/// Title, then year · Movie or Show; on the right when it's on next (the accent while it's on now).
private struct ResultRow: View {
    @Environment(\.theme) private var t
    let entry: TitleSearch.Entry, airs: String, onNow: Bool, focused: Bool

    var body: some View {
        let sc = focused ? t.focusDim : t.dim
        let kind = [entry.year.map(String.init), entry.isShow ? "Show" : "Movie"].compactMap { $0 }.joined(separator: " · ")
        ListRow(bg: focused ? t.focusBg : t.panel, fg: focused ? t.focusInk : t.ink, height: 88, inset: 28) {
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.title).font(t.body(32, weight: .semibold)).lineLimit(1)
                Text(kind).font(t.body(24)).foregroundStyle(sc)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Text(airs).font(t.body(28, weight: .medium)).lineLimit(1).padding(.leading, 32)
                .foregroundStyle(focused ? t.focusInk : onNow ? t.accent : sc)
        }
    }
}

/// The inline text field (tvOS) while `editingField == "search"`: bound to the query, so the results follow the typing
/// as far as the TV's keyboard reports it. Submit / Menu hand the remote back.
private struct SearchField: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    @FocusState private var focused: Bool

    var body: some View {
        @Bindable var m = model
        TextField("Type a title", text: $m.searchQuery)
            .textFieldStyle(.plain)
            .font(t.body(32, weight: .medium))
            .autocorrectionDisabled()
            .focused($focused)
            .defaultFocus($focused, true)
            .onAppear { focused = true }
            .onSubmit { model.editingField = nil }
            .exitCommand { model.editingField = nil }
    }
}
