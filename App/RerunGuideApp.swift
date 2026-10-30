import SwiftUI
import AVFoundation

@main
struct RerunGuideApp: App {
    @State private var model: AppModel
    #if os(iOS)
    @UIApplicationDelegateAdaptor(OrientationLock.self) private var orientation
    #endif

    init() {
        // Artwork and some API URLs carry the server token (Plex X-Plex-Token, Emby/Jellyfin api_key), so responses stay in
        // memory only: the default cache would keep them, URLs and all, in Caches/Cache.db, readable after sign-out. Set
        // before the model exists, so no request goes through the default one; first drop whatever an older build stored.
        URLCache.shared.removeAllCachedResponses()
        URLCache.shared = URLCache(memoryCapacity: 64 << 20, diskCapacity: 0)
        _model = State(initialValue: AppModel())
        #if os(iOS)
        // Without it the ring/silent switch mutes every program. Mixable, like the station stings' own setting, so the muted
        // guide preview never stops someone's music. ponytail: switch to non-mixing on tune-in if Watch should pause it.
        try? AVAudioSession.sharedInstance().setCategory(.playback, options: .mixWithOthers)
        #endif
    }

    var body: some Scene {
        WindowGroup {
            RootView().environment(model)
        }
    }
}

#if os(iOS)
/// iPhone: portrait only where a screen has a portrait layout (`AppModel.portraitReady`); the rest turn the phone to
/// landscape, where the stage fits. iPad and Mac windows take any shape (the stage letterboxes).
@MainActor final class OrientationLock: NSObject, UIApplicationDelegate {
    private static var mask: UIInterfaceOrientationMask = .allButUpsideDown

    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask { Self.mask }

    /// The device's orientation is only reported once asked for, and not at once: asked at launch (the first update),
    /// it is known by the time a screen hands portrait back.
    private static let watchOrientation: Void = UIDevice.current.beginGeneratingDeviceOrientationNotifications()
    /// The lock turned the phone sideways (the screen was upright when it went landscape-only).
    private static var turned = false

    static func update(portraitReady: Bool) {
        guard UIDevice.current.userInterfaceIdiom == .phone else { return }
        _ = watchOrientation
        let m: UIInterfaceOrientationMask = portraitReady ? .allButUpsideDown : .landscape
        guard m != mask else { return }
        mask = m
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        if !portraitReady { turned = scenes.contains { $0.effectiveGeometry.interfaceOrientation.isPortrait } }
        // Back upright when held upright, or when the lock is what turned it and the phone can't say (flat on a table,
        // not known yet, locked upright): UIKit itself waits for the next turn.
        let o = UIDevice.current.orientation
        let upright = portraitReady && (o == .portrait || (!o.isValidInterfaceOrientation && turned))
        for scene in scenes {
            scene.keyWindow?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
            if !portraitReady || upright { scene.requestGeometryUpdate(.iOS(interfaceOrientations: upright ? .portrait : .landscape)) }
        }
    }
}
#endif
