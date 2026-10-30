import SwiftUI
import RerunGuideCore

// Six first-launch screens, ported from the design prototype's "First launch" / "Sign in" (isForm) / "Sign in with a code" /
// "Building lineup" blocks. Absolute layout on the 1920×1080 stage; every color/font comes from the theme tokens.

// MARK: - Welcome

struct WelcomeScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t

    private var cards: [(name: String, desc: String)] { AppModel.welcomeCards }

    var body: some View {
        Stage {
            VStack(alignment: .leading, spacing: 28) {
                Brand(size: 34, dot: 16)
                Text(t.heading("Tonight’s lineup.")).font(t.heading(128)).tracking(em: t.headingTracking, size: 128)
                    .foregroundStyle(t.ink).frame(maxWidth: 1400, alignment: .leading)
                Text("The movies and shows on your server, running to a schedule the way TV used to be. Pick a channel, catch whatever’s playing, change your mind whenever. Nothing to set up on the server side.")
                    .font(t.body(34)).lineSpacing(7).foregroundStyle(t.mute).frame(maxWidth: 1180, alignment: .leading)
            }
            .frame(width: 1640, alignment: .topLeading).offset(x: 140, y: 120)

            Kicker("Where do your shows live?", color: t.dim).at(140, 651, w: 900, h: 31, alignment: .leading)
            HStack(spacing: 32) {
                ForEach(0..<3, id: \.self) { i in
                    ServiceCard(name: cards[i].name, desc: cards[i].desc, focused: model.focusIndex == i)
                        .tapSelects(model) { model.focusIndex = i }
                }
            }
            .at(140, 710, w: 1640, h: 240)
            // A 4th, smaller card so the app can be tried without a server; sits at the right edge above the row.
            ServiceCard(name: cards[3].name, desc: cards[3].desc, focused: model.focusIndex == 3, nameSize: 40, descSize: 24, pad: 28)
                .tapSelects(model) { model.focusIndex = 3 }
                .at(1360, 560, w: 420, h: 122)
        }
    }
}

private struct ServiceCard: View {
    @Environment(\.theme) private var t
    let name: String, desc: String, focused: Bool
    var nameSize: CGFloat = 68, descSize: CGFloat = 28, pad: CGFloat = 40

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(t.heading(name)).font(t.heading(nameSize))
            Spacer(minLength: 0)
            Text(desc).font(t.body(descSize)).lineSpacing(descSize * 0.15).foregroundStyle(focused ? t.focusDim : t.dim)
        }
        .padding(.vertical, pad).padding(.horizontal, pad + 4)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(focused ? t.focusBg : t.panel, in: RoundedRectangle(cornerRadius: t.radius))
        .foregroundStyle(focused ? t.focusInk : t.ink)
        .lift(focused, t).zIndex(focused ? 1 : 0)
    }
}

// MARK: - Address

struct AddressScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t

    var body: some View {
        let n = model.foundServers.count
        // At most three server rows fit above Continue; the window slides with focus so Continue never leaves the screen.
        let cap = 3
        let near = min(max(model.focusIndex - 1, 0), max(n - 1, 0))
        let start = min(max(near - 1, 0), max(n - cap, 0))
        let shown = start..<min(start + cap, n)
        let account = model.foundServers.contains { !$0.accessToken.isEmpty }   // rows from the account, not ones found on the network
        let source = account ? "On your \(model.serviceKind.displayName) account" : "Spotted on your network"
        FormScreen(kicker: model.serviceKind.displayName, title: "Which server?",
                   sub: account ? "Pick the server your lineup comes from. Select connects." : "Type the address you use on other devices, or pick one spotted on your network.") {
            FormRow(label: "Server address", focused: model.focusIndex == 0) {
                if model.inlineEditing == "addr" { EditField(name: "addr", placeholder: "http://") }
                else { ValueText(model.address, placeholder: "http://", focused: model.focusIndex == 0) }
            }
            .tapSelects(model) { model.focusIndex = 0 }
            if n > 0 || model.discovering {
                SectionLabel(n > cap ? "\(source) · \(shown.lowerBound + 1)–\(shown.upperBound) of \(n)" : source)
                ForEach(shown, id: \.self) { i in
                    let s = model.foundServers[i]
                    let f = model.focusIndex == i + 1
                    FormRow(label: s.name, focused: f) {
                        Detail(s.baseURL.absoluteString.replacingOccurrences(of: "http://", with: ""), focused: f)
                        if s.baseURL.absoluteString == model.address {   // the pick: what the address field holds, focus or not
                            Image(systemName: "checkmark").font(.system(size: 28, weight: .bold)).foregroundStyle(f ? t.focusInk : t.accent)
                        }
                    }
                    .tapSelects(model) { model.focusIndex = i + 1 }
                }
                if n == 0 { Detail("Looking around the network…", focused: false) }
            }
            PillButton(label: "Continue", focused: model.focusIndex == n + 1, busy: model.authBusy).tapSelects(model) { model.focusIndex = n + 1 }.padding(.top, 24)
            ErrorLine()
        }
    }
}

// MARK: - Link (Plex PIN / Emby PIN / Jellyfin Quick Connect)

struct LinkScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    @State private var pulse = false

    var body: some View {
        let kind = model.serviceKind
        let account = kind != .jellyfin   // Plex and Emby link an account; Jellyfin's Quick Connect is per server
        let host = model.address.replacingOccurrences(of: "http://", with: "")
        let steps: [(String, String)] = switch kind {
        case .plex: [("On your phone or computer, open", "plex.tv/link"), ("Type in this code. The moment Plex says yes, we move on.", "")]
        case .emby: [("On your phone or computer, open", "emby.media/pin"), ("Sign in with Emby Connect and type in this code. Once Emby says yes, we move on.", "")]
        default: [("Open Jellyfin on your phone or computer and sign in.", ""), ("Head to", "Settings › Quick Connect"), ("Type in this code. Once it’s approved, we move on.", "")]
        }
        let status = model.authError ?? (model.linked ? (account ? "Linked. Looking for your server, which takes 10–15 seconds…" :"Approved. Reading your libraries…") : "Waiting for you to type the code…")
        Stage {
            Header(kicker: account ? kind.displayName : "Jellyfin · \(host)", title: account ? "Link your \(kind.displayName) account" : "Link with Quick Connect", sub: nil, width: 1640)
            HStack(alignment: .top, spacing: 80) {
                VStack(alignment: .leading, spacing: 30) {
                    ForEach(steps.indices, id: \.self) { i in
                        HStack(alignment: .top, spacing: 26) {
                            Text("\(i + 1)").font(t.body(28, weight: .bold)).foregroundStyle(t.ink)
                                .frame(width: 56, height: 56).background(t.panel2, in: Circle())
                            (Text(steps[i].0 + (steps[i].1.isEmpty ? "" : " ")) + Text(steps[i].1).foregroundStyle(t.accent).fontWeight(.bold))
                                .font(t.body(36)).lineSpacing(5).foregroundStyle(t.ink).padding(.top, 4)
                        }
                    }
                    HStack(spacing: 16) {
                        Circle().fill(model.linked ? Color(hex: "#7fd39a") : t.accent).frame(width: 14, height: 14)
                            .opacity(model.linked || model.authError != nil ? 1 : (pulse ? 0.25 : 1))
                            .animation(model.linked ? nil : .easeInOut(duration: 0.7).repeatForever(autoreverses: true), value: pulse)
                        Text(status).font(t.body(30)).foregroundStyle(model.authError == nil ? t.mute : t.accent)
                    }
                    .padding(.top, 16)
                }
                Spacer(minLength: 0)
                HStack(spacing: 14) {
                    ForEach(Array(model.linkCodeChars.enumerated()), id: \.offset) { _, ch in
                        Text(ch).font(t.heading(96, weight: .bold)).foregroundStyle(t.ink)
                            .frame(width: 120, height: 170)
                            .background(t.panel, in: RoundedRectangle(cornerRadius: t.radius))
                            .overlay(RoundedRectangle(cornerRadius: t.radius).strokeBorder(t.line, lineWidth: 2))
                    }
                }
            }
            .frame(width: 1640, alignment: .topLeading).offset(x: 140, y: 430)
            PillButton(label: account ? "Type a server address instead" : "Sign in with a password instead", focused: true, height: 84, padX: 44, size: 30)
                .tapSelects(model)
                .offset(x: 140, y: 1080 - 120 - 84)
        }
        .onAppear { pulse = true }
    }
}

// MARK: - Login

struct LoginScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t

    var body: some View {
        let name = model.serviceKind.displayName
        FormScreen(kicker: "\(name) · \(model.address.replacingOccurrences(of: "http://", with: ""))", title: "Sign in to \(name)",
                   sub: model.serviceKind == .plex ? "Two-factor enabled? Enter your password followed by the 6-digit code."
                                                  : "Use the account you already use for \(name) on your other devices.") {
            FormRow(label: "Username", focused: model.focusIndex == 0) {
                if model.inlineEditing == "user" { EditField(name: "user", placeholder: "dana") }
                else { ValueText(model.username, placeholder: "dana", focused: model.focusIndex == 0) }
            }
            .tapSelects(model) { model.focusIndex = 0 }
            FormRow(label: "Password", focused: model.focusIndex == 1) {
                if model.inlineEditing == "pass" { EditField(name: "pass", placeholder: "••••••••", secure: true) }
                else { ValueText(String(repeating: "•", count: model.password.count), placeholder: "••••••••", focused: model.focusIndex == 1) }
            }
            .tapSelects(model) { model.focusIndex = 1 }
            PillButton(label: "Sign in", focused: model.focusIndex == 2, busy: model.authBusy).tapSelects(model) { model.focusIndex = 2 }.padding(.top, 24)
            ErrorLine()
        }
    }
}

// MARK: - Libraries

struct LibrariesScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t

    var body: some View {
        let n = model.librarySections.count
        FormScreen(kicker: "Connected to \(model.server?.serverName ?? "")", title: "What goes in the lineup?",
                   sub: "Pick the libraries Rerun Guide may schedule. You can change this in the Settings any time.") {
            ForEach(Array(model.librarySections.enumerated()), id: \.element.id) { i, s in
                let f = model.focusIndex == i, on = model.librarySelection.contains(s.id)
                FormRow(label: s.title, focused: f) {
                    Detail(detail(s), focused: f)
                    Text(on ? "On" : "Off").font(t.body(24, weight: .bold))
                        .frame(minWidth: 84).padding(.vertical, 9).padding(.horizontal, 19)   // CSS content-box min-width 84 + 6/16 padding + 3 border
                        .foregroundStyle(on ? (f ? t.focusBg : t.accentInk) : (f ? t.focusInk : t.ink))
                        .background(on ? (f ? t.focusInk : t.accent) : .clear, in: RoundedRectangle(cornerRadius: t.pill))
                        .overlay(RoundedRectangle(cornerRadius: t.pill).strokeBorder(on ? (f ? t.focusBg : t.accentInk) : (f ? t.focusInk : t.ink), lineWidth: 3))
                }
                .tapSelects(model) { model.focusIndex = i }
            }
            if n == 0 && !model.authBusy && model.authError == nil {
                Text("No libraries reported, so everything on the server will be scheduled.").font(t.body(28)).foregroundStyle(t.dim)
            }
            if model.authBusy { Spinner(scale: 0.7, tint: t.dim).frame(height: 88) }
            PillButton(label: "Continue", focused: model.focusIndex == n).tapSelects(model) { model.focusIndex = n }.padding(.top, 24)
            ErrorLine()
        }
        .task { await model.fetchLibraries() }
    }

    private func detail(_ s: LibrarySection) -> String {
        guard let c = s.itemCount else { return "" }
        let unit = switch s.kind { case .movies: "film"; case .shows: "show"; case .other: "title" }
        return "\(c.formatted()) \(unit)\(c == 1 ? "" : "s")"
    }
}

// MARK: - Building

struct BuildingScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t

    var body: some View {
        let done = model.buildDone, server = model.server?.serverName ?? ""
        let empty = done && model.channels.isEmpty, failed = model.libraryError != nil || empty
        let sub = model.libraryError ?? (empty ? "Nothing to schedule. Titles without a runtime are skipped. If \(server) is still scanning, try again in a few minutes; otherwise pick other libraries."
                                         : done ? "\(model.channels.count) channels, all from \(server). Rename, renumber or retire any of them under Options › Channels."
                                                : "Sorting \(server) by genre, decade, director and cast…")
        let rows = (model.builtCount + 3) / 4
        Stage {
            Header(kicker: nil, title: done && !empty ? "Sign-on complete" : "Configuring channels", sub: sub, width: 1640, subColor: failed ? t.accent : t.mute)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 16), count: 4), spacing: 16) {
                ForEach(model.channels.prefix(model.builtCount)) { ch in
                    HStack(spacing: 18) {
                        Text("\(ch.number)").font(t.body(34, weight: .bold)).monospacedDigit().foregroundStyle(t.accent).lineLimit(1).fixedSize().frame(minWidth: 52, alignment: .trailing)   // 3 digits past channel 99
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t.heading(ch.name)).font(t.body(30, weight: .semibold)).foregroundStyle(t.ink).lineLimit(1)
                            Text(ch.tag).font(t.body(24)).foregroundStyle(t.dim).lineLimit(1)
                        }
                    }
                    .padding(.horizontal, 24).frame(maxWidth: .infinity, alignment: .leading).frame(height: 104)
                    .background(t.panel, in: RoundedRectangle(cornerRadius: t.radius))
                    .transition(.opacity.combined(with: .offset(y: 14)))
                }
            }
            .frame(width: 1640, alignment: .top)
            .offset(y: -CGFloat(max(0, rows - 4)) * 120)   // ponytail: more than 16 channels scrolls the board up; no manual scrolling
            .frame(width: 1640, height: 464, alignment: .top).clipped()
            .offset(x: 140, y: 380)
            if done || model.libraryError != nil {
                PillButton(label: model.libraryError != nil ? "Retry" : empty ? "Pick other libraries" : "Start flipping", focused: true)
                    .tapSelects(model)
                    .offset(x: 140, y: 1080 - 96 - 88)
                    .transition(.opacity.combined(with: .offset(y: 14)))
            }
        }
    }
}

// MARK: - Shared pieces (the prototype's isForm / welcome / link / building CSS)

/// The 1920×1080 stage with the theme's background glow: radial-gradient(1100px 700px at 20% 10%, glow, transparent 70%).
private struct Stage<Content: View>: View {
    @Environment(\.theme) private var t
    @ViewBuilder let content: Content

    var body: some View {
        ZStack(alignment: .topLeading) {
            t.bg
            RadialGradient(colors: [t.glow, .clear], center: .center, startRadius: 0, endRadius: 770)
                .frame(width: 2200, height: 2200).scaleEffect(x: 1, y: 700 / 1100).position(x: 384, y: 108)
            content
        }
        .frame(width: 1920, height: 1080, alignment: .topLeading)   // unclipped: off tvOS the glow carries on into the letterbox
    }
}

/// Header column at (140, 110): brand, kicker (margin-top 20), 88px title, sub.
private struct Header: View {
    @Environment(\.theme) private var t
    let kicker: String?, title: String, sub: String?
    var width: CGFloat = 1300
    var subColor: Color? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Brand()
            if let kicker { Kicker(kicker, color: t.accent).padding(.top, 20) }
            Text(t.heading(title)).font(t.heading(88)).tracking(em: t.headingTracking, size: 88).foregroundStyle(t.ink)
                .padding(.top, kicker == nil ? 20 : 0)
            if let sub { Text(sub).font(t.body(32)).foregroundStyle(subColor ?? t.mute) }
        }
        .frame(width: width, alignment: .topLeading).offset(x: 140, y: 110)
    }
}

/// The prototype's "Sign in" layout: header + a 1100-wide column of rows at (140, 450), gap 14.
private struct FormScreen<Rows: View>: View {
    let kicker: String, title: String, sub: String
    @ViewBuilder let rows: Rows

    var body: some View {
        Stage {
            Header(kicker: kicker, title: title, sub: sub)
            VStack(alignment: .leading, spacing: 14) { rows }.frame(width: 1100, alignment: .topLeading).offset(x: 140, y: 450)
        }
    }
}

private struct Brand: View {
    @Environment(\.theme) private var t
    var size: CGFloat = 30, dot: CGFloat = 14
    var body: some View {
        HStack(spacing: 14) {
            Circle().fill(t.accent).frame(width: dot, height: dot)
            Text(t.heading("Rerun Guide")).font(t.heading(size, weight: .semibold)).foregroundStyle(t.ink)
        }
    }
}

/// 26px 700 .14em uppercase.
private struct Kicker: View {
    @Environment(\.theme) private var t
    let text: String, color: Color
    init(_ text: String, color: Color) { self.text = text; self.color = color }
    var body: some View { Text(text.uppercased()).font(t.body(26, weight: .bold)).tracking(em: 0.14, size: 26).foregroundStyle(color) }
}

/// Form section label: 24px 700 .14em uppercase dim, margin-top 14.
private struct SectionLabel: View {
    @Environment(\.theme) private var t
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View { Text(text.uppercased()).font(t.body(24, weight: .bold)).tracking(em: 0.14, size: 24).foregroundStyle(t.dim).padding(.top, 14) }
}

/// 88-high row: 300-wide label + content, padding 0 36, gap 24.
private struct FormRow<Content: View>: View {
    @Environment(\.theme) private var t
    let label: String, focused: Bool
    @ViewBuilder let content: Content

    var body: some View {
        HStack(spacing: 24) {
            Text(label).font(t.body(32, weight: .semibold)).frame(width: 300, alignment: .leading)
            content
        }
        .padding(.horizontal, 36).frame(width: 1100, height: 88, alignment: .leading)
        .background(focused ? t.focusBg : t.panel, in: RoundedRectangle(cornerRadius: t.radius))
        .foregroundStyle(focused ? t.focusInk : t.ink)
    }
}

/// The input's resting look: its value, or the placeholder in the row's secondary color.
private struct ValueText: View {
    @Environment(\.theme) private var t
    let value: String, placeholder: String, focused: Bool
    init(_ value: String, placeholder: String, focused: Bool) { self.value = value; self.placeholder = placeholder; self.focused = focused }
    var body: some View {
        Text(value.isEmpty ? placeholder : value).font(t.body(32, weight: .medium)).lineLimit(1).truncationMode(.middle)
            .foregroundStyle(value.isEmpty ? (focused ? t.focusDim : t.dim) : (focused ? t.focusInk : t.ink))
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Row detail: 30px in the row's secondary color.
private struct Detail: View {
    @Environment(\.theme) private var t
    let text: String, focused: Bool
    init(_ text: String, focused: Bool) { self.text = text; self.focused = focused }
    var body: some View { Text(text).font(t.body(30)).foregroundStyle(focused ? t.focusDim : t.dim).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading) }
}

/// The real text field, shown only while `model.editingField == name`; RootView hands it the remote. Submit / Menu
/// give the remote back (and normalise the address). Not focus loss: on tvOS the keyboard takes focus while typing.
private struct EditField: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    let name: String, placeholder: String
    var secure = false
    @FocusState private var focused: Bool

    var body: some View {
        @Bindable var m = model
        let text: Binding<String> = name == "addr" ? $m.address : name == "user" ? $m.username : $m.password
        Group {
            if secure { SecureField(placeholder, text: text) } else { TextField(placeholder, text: text) }
        }
        .textFieldStyle(.plain)
        .font(t.body(32, weight: .medium))
        .autocorrectionDisabled()
        .textInputAutocapitalization(.never)
        .keyboardType(name == "addr" ? .URL : .default)
        .focused($focused)
        .defaultFocus($focused, true)
        .onAppear { focused = true }
        .onSubmit { model.endEditing() }
        .exitCommand { model.endEditing() }
    }
}

/// Inline auth error under the rows, in the accent.
private struct ErrorLine: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var t
    var body: some View {
        if let e = model.authError { Text(e).font(t.body(28)).foregroundStyle(t.accent).padding(.top, 10) }
    }
}

/// The prototype's pill button (isButton / ln.alt / bd.onGo): height 88, padding 0 52, 32px 700, focus colors + lift.
private struct PillButton: View {
    @Environment(\.theme) private var t
    let label: String, focused: Bool
    var height: CGFloat = 88, padX: CGFloat = 52, size: CGFloat = 32, busy = false

    var body: some View {
        HStack(spacing: 20) {
            Text(t.heading(label)).font(t.body(size, weight: .bold))
            if busy { Spinner(scale: 0.6, tint: focused ? t.focusInk : t.ink).frame(width: 40, height: 40) }
        }
        .padding(.horizontal, padX).frame(height: height)
        .background(focused ? t.focusBg : t.panel, in: RoundedRectangle(cornerRadius: t.pill))
        .foregroundStyle(focused ? t.focusInk : t.ink)
        .lift(focused, t)
    }
}

private extension View {
    /// Focus lift: scale 1.03 + shadow 0 18 44 rgba(0,0,0,.55). 180 ms ease.
    func lift(_ on: Bool, _ t: Theme) -> some View {
        scaleEffect(on ? t.focusScale : 1)
            .shadow(color: .black.opacity(on && t.focusShadow ? 0.55 : 0), radius: 22, y: 18)
            .animation(.easeInOut(duration: 0.18), value: on)
    }
}
