import SwiftUI
import RerunGuideCore

/// The 1920×1080 stage. The remote-capture view sits underneath everything and owns the Siri Remote (or the keyboard)
/// unless a screen is editing text. Screens and overlays are pure renderings of `AppModel`; they never hold focus themselves.
struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var phase

    var body: some View {
        window
            .environment(\.theme, model.theme)
            .onChange(of: phase) { _, p in
                if p == .background { model.enteredBackground() } else if p == .active { model.returnedToForeground() }
            }
    }

    /// tvOS draws the stage 1:1 on its 1920×1080 screen. Elsewhere it is scaled into the window (see `fit`), with the
    /// touch buttons and text-entry alert in window space.
    @ViewBuilder private var window: some View {
        #if os(tvOS)
        stage(width: 1920).ignoresSafeArea()
        #else
        GeometryReader { g in   // inside the safe area, so `safeAreaInsets` reports the margins around it
            // Portrait: App/Portrait draws the screen; the stage stays underneath only for the player, in Now playing's
            // picture box, so turning the device keeps the same player (and its stream) going.
            let p = Self.portrait(g.size) && model.portraitReady
            let f = p ? (width: CGFloat(1920), scale: g.size.width / 1920, center: CGPoint(x: g.size.width / 2, y: PortraitWatch.videoTop + g.size.width * 9 / 32))
                      : Self.fit(g.size, g.safeAreaInsets)
            ZStack(alignment: .topLeading) {
                stage(width: f.width, portrait: p).scaleEffect(f.scale, anchor: .topLeading)
                    .frame(width: f.width * f.scale, height: 1080 * f.scale, alignment: .topLeading)
                    .position(f.center)
                if p { PortraitRoot() }
            }
            .onChange(of: p, initial: true) { _, v in model.portrait = v }
        }
        .ignoresSafeArea(.keyboard)
        #if os(iOS)
        .onChange(of: model.portraitReady, initial: true) { _, ready in OrientationLock.update(portraitReady: ready) }
        #endif
        .overlay(alignment: .leading) { MenuHint().ignoresSafeArea() }
        // Centered in an iPhone's 62-pt landscape margin, below the rounded corner and above the Dynamic Island. The Mac
        // keeps its top safe area, the title bar: its traffic lights sit there and its clicks drag the window.
        .overlay(alignment: .topLeading) {
            TouchChrome().padding(.top, 16).padding(.leading, 9)
                #if !targetEnvironment(macCatalyst)
                .ignoresSafeArea()
                #endif
        }
        .background {   // the margins around the stage: its background, dimmed like the stage while an overlay is up
            ZStack { background; if model.overlay != nil { model.theme.scrim } }.ignoresSafeArea()
        }
        .modifier(TextEntry())
        .statusBarHidden(!model.portrait)
        .persistentSystemOverlays(model.portrait ? .automatic : .hidden)
        #endif
    }

    /// Taller than wide: the portrait layouts. Not on the Mac, whose windows keep the stage however they're shaped.
    static func portrait(_ size: CGSize) -> Bool {
        #if targetEnvironment(macCatalyst)
        false
        #else
        size.height > size.width
        #endif
    }

    /// Where the stage goes in a safe area of `size` with `inset` margins around it (center in safe-area coordinates).
    /// Wider than 16:9 (iPhone, a wide Mac window): the full height, margins included, and as wide as the safe area.
    /// Up to 56 of the guide's 80-point side margins may sit in the side margins (only background there), so its
    /// content runs close to them but never under the Dynamic Island. Narrower (iPad): 1920 wide, letterboxed inside the
    /// safe area.
    static func fit(_ size: CGSize, _ inset: EdgeInsets) -> (width: CGFloat, scale: CGFloat, center: CGPoint) {
        let fullH = size.height + inset.top + inset.bottom
        guard fullH > 0, size.width / fullH > 16.0 / 9 else {
            return (1920, max(0.01, min(size.width / 1920, size.height / 1080)), CGPoint(x: size.width / 2, y: size.height / 2))
        }
        let s = fullH / 1080, spill = min(56, min(inset.leading, inset.trailing) / s)
        return (size.width / s + 2 * spill, s, CGPoint(x: size.width / 2, y: fullH / 2 - inset.top))
    }

    /// The player's surround is black, like the picture's own bars; everything else sits on the theme background.
    private var background: Color { model.screen == .watch ? .black : model.theme.bg }

    /// `width` is 1920 except off tvOS on a wide window. The guide, overlays and toast spread across it; every other
    /// screen keeps its 1920-wide layout, centered.
    /// `portrait`: only the player is drawn (the remote capture, with its window-wide drags, and the overlays are App/Portrait's).
    private func stage(width: CGFloat, portrait: Bool = false) -> some View {
        ZStack {
            if !portrait {
                RemoteCaptureView(isActive: model.editingField == nil, passBack: Platform.isTV && model.backExitsApp,
                                  focusDescription: model.accessibilityDescription,
                                  onCommand: { model.press($0) }, onDigit: { model.pressDigit($0) }, onScroll: { model.scroll($0) },
                                  playerSwipes: model.screen == .watch && model.overlay == nil, onEdgeSwipe: { model.edgeSwipe() })
            }
            background
            screen(portrait).frame(width: model.screen == .guide ? width : 1920, height: 1080)
            if !portrait {
                overlay
                ToastView()
                ChannelEntryView()
            }
        }
        .frame(width: width, height: 1080)
        .environment(\.stageWidth, width)
    }

    /// The same structure in both layouts, so WatchScreen keeps its identity (and its player) when the device turns.
    @ViewBuilder private func screen(_ portrait: Bool) -> some View {
        switch model.screen {
        case .welcome: WelcomeScreen()
        case .address: AddressScreen()
        case .link: LinkScreen()
        case .login: LoginScreen()
        case .libraries: LibrariesScreen()
        case .building: BuildingScreen()
        case .profiles: ProfilesScreen()
        case .guide: if !portrait { GuideScreen() }
        case .watch: WatchScreen()
        case .channels: if !portrait { ChannelsScreen() }
        case .settings: if !portrait { SettingsScreen() }
        case .search: SearchScreen()
        }
    }

    @ViewBuilder private var overlay: some View {
        switch model.overlay {
        case .options: OptionsOverlay()
        case .info(let key): InfoOverlay(key: key)
        case .newChannel: NewChannelOverlay()
        case .makeChannel: MakeChannelOverlay()
        case nil: EmptyView()
        }
    }
}

private struct ThemeKey: EnvironmentKey { static let defaultValue = Theme.late }
private struct StageWidthKey: EnvironmentKey { static let defaultValue: CGFloat = 1920 }
extension EnvironmentValues {
    /// The current profile's theme, so views can write `@Environment(\.theme) var t`.
    var theme: Theme { get { self[ThemeKey.self] } set { self[ThemeKey.self] = newValue } }
    /// The stage's width: 1920, or wider off tvOS when the window is (RootView). The guide and overlays fill it.
    var stageWidth: CGFloat { get { self[StageWidthKey.self] } set { self[StageWidthKey.self] = newValue } }
}
