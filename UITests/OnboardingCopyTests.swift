import XCTest

/// Fresh-install walk through the onboarding screens, one screenshot each (no sign-in: Plex code screen, then the
/// address and password forms via the "instead" links, then the Jellyfin address screen).
@MainActor
final class OnboardingCopyTests: XCTestCase {
    let app = XCUIApplication()
    let remote = XCUIRemote.shared
    var dir: String { ProcessInfo.processInfo.environment["SHOT_DIR"] ?? NSTemporaryDirectory() }
    func shot(_ name: String) { try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png")) }
    func press(_ b: XCUIRemote.Button, _ n: Int = 1) { for _ in 0..<n { remote.press(b); Thread.sleep(forTimeInterval: 0.8) } }

    func testOnboardingScreens() {
        app.launchArguments += ["-persisted", "<data></data>"]   // a fresh install, whatever earlier tests signed in to
        app.launch(); Thread.sleep(forTimeInterval: 3); shot("50-welcome")
        press(.select); Thread.sleep(forTimeInterval: 3); shot("51-plex-code")
        press(.select); Thread.sleep(forTimeInterval: 3); shot("52-plex-address")          // "Type a server address instead"; discovery lists the LAN server
        press(.down); press(.select); Thread.sleep(forTimeInterval: 0.5)                  // pick the spotted server → address filled, focus on Continue
        press(.select); Thread.sleep(forTimeInterval: 2); shot("53-plex-password")
        press(.menu); press(.menu); Thread.sleep(forTimeInterval: 1)                      // password → address → welcome
        press(.right); press(.select); Thread.sleep(forTimeInterval: 2); shot("54-jellyfin-address")
        press(.menu); Thread.sleep(forTimeInterval: 1)                                    // → welcome
        press(.up); press(.select); Thread.sleep(forTimeInterval: 3); shot("55-sample-station-libraries")   // the demo card sits above the row
        press(.down, 2); press(.select); Thread.sleep(forTimeInterval: 6); shot("56-sample-station-schedule")   // Continue → Sign-on complete
        XCTAssertEqual(app.state, .runningForeground)
    }

    /// Five Plex servers on the account: the list shows three at a time and Continue stays on screen.
    func testServerListWindow() {
        app.launchArguments += ["-serversOverride", "5"]
        app.launch(); Thread.sleep(forTimeInterval: 3); shot("57-servers-top")   // focus lands on server 1, not the address field
        press(.down, 2); shot("58-servers-mid")        // server 3 focused → rows 2–4
        press(.down, 3); shot("59-servers-continue")   // Continue focused → rows 3–5
        XCTAssertEqual(app.state, .runningForeground)
    }
}
