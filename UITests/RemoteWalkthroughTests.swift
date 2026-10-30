import XCTest

/// Drives the app with the Siri Remote (XCUIRemote) and saves a screenshot after each step.
/// Set SHOT_DIR in the test environment to a writable host directory; the simulator shares the host filesystem.
/// Pre-seed the app's "persisted" defaults (demo server) before running so it opens on the guide.
@MainActor
final class RemoteWalkthroughTests: XCTestCase {
    let app = XCUIApplication()
    let remote = XCUIRemote.shared
    var dir: String { ProcessInfo.processInfo.environment["SHOT_DIR"] ?? NSTemporaryDirectory() }

    func shot(_ name: String) {
        let png = XCUIScreen.main.screenshot().pngRepresentation
        try? png.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png"))
    }
    /// The app's single accessibility element (RemoteCaptureUIView) describes what's focused; this returns it when it mentions any needle.
    func focusDescription(_ needles: [String] = ["Subtitles", "Audio", "Program info"]) -> String {
        app.descendants(matching: .other).allElementsBoundByIndex.map(\.label).first { l in needles.contains { l.contains($0) } } ?? ""
    }
    func press(_ b: XCUIRemote.Button, _ n: Int = 1, wait: TimeInterval = 0.8) {
        for _ in 0..<n { remote.press(b); Thread.sleep(forTimeInterval: wait) }
    }
    /// Options from the drifting guide: Menu there goes to the Home screen, so take the remote and press Left past the first
    /// program (up to two earlier programs may still be focused from a previous step; extra Lefts are no-ops in Options).
    func openOptions() { press(.down); press(.left, 3); Thread.sleep(forTimeInterval: 1) }

    func testWalkthrough() {
        app.launch()
        Thread.sleep(forTimeInterval: 6)
        shot("01-guide-drifting")
        press(.down); shot("02-guide-focused")            // take the remote
        press(.right, 2); shot("03-guide-right")           // later program on the same channel
        press(.select); Thread.sleep(forTimeInterval: 1); shot("04-info-or-watch")
        press(.menu); Thread.sleep(forTimeInterval: 1)     // close info, or leave the player if the program was live
        openOptions(); shot("05-options")                  // rows: profile, Guide, Movies, TV Shows, Search, Channels, Settings, Theme
        press(.menu); Thread.sleep(forTimeInterval: 1)     // close Options; the guide is still under remote control
        press(.down); press(.select); Thread.sleep(forTimeInterval: 4); shot("08-watch-banner")   // tune to what's on
        press(.select); Thread.sleep(forTimeInterval: 0.8); shot("09-watch-banner-actions")
        press(.up); Thread.sleep(forTimeInterval: 3); shot("10-watch-channel-up")
        press(.playPause); Thread.sleep(forTimeInterval: 1); shot("11-watch-paused")
        press(.menu); Thread.sleep(forTimeInterval: 1.5); shot("12-back-to-guide")   // one Back leaves the player, banner or not
        openOptions(); press(.down, 5); press(.select); Thread.sleep(forTimeInterval: 1); shot("13-settings")
        press(.menu); Thread.sleep(forTimeInterval: 1)
        openOptions(); press(.down, 4); press(.select); Thread.sleep(forTimeInterval: 1); shot("14-channels")
        press(.right); shot("15-channels-detail")
        press(.left); press(.up); press(.select); Thread.sleep(forTimeInterval: 1); shot("16-new-channel")
        press(.select); Thread.sleep(forTimeInterval: 1); shot("17-new-channel-pick")
        press(.menu, 2); Thread.sleep(forTimeInterval: 1)   // the demo has directors to pick: editor fields → list → guide
        openOptions(); press(.up); press(.select); Thread.sleep(forTimeInterval: 1); shot("18-profiles")
        press(.menu); Thread.sleep(forTimeInterval: 1); shot("19-guide-end")
        XCTAssertEqual(app.state, .runningForeground, "a Menu press left the app")
        let shots = ((try? FileManager.default.contentsOfDirectory(atPath: dir)) ?? []).filter { $0.hasSuffix(".png") }
        XCTAssertFalse(shots.isEmpty, "no screenshots written to \(dir)")
    }

    /// HIG: Back/Menu at the app's top level returns to the Home screen.
    func testMenuExitsFromGuide() {
        app.launch()
        Thread.sleep(forTimeInterval: 5)
        press(.menu)
        Thread.sleep(forTimeInterval: 2)
        shot("20-after-menu-on-drifting-guide")
        XCTAssertNotEqual(app.state, .runningForeground, "Menu on the drifting guide should leave the app")
    }

    /// Options › Movies / TV Shows switch the channel lineup; the channel editor's "When you tune in" field cycles per channel.
    func testGuidesAndEditorField() {
        app.launch()
        Thread.sleep(forTimeInterval: 6)
        openOptions(); press(.down); press(.select); Thread.sleep(forTimeInterval: 2); shot("30-movies-guide")      // Guide(1) → Movies(2)
        openOptions(); press(.down, 2); press(.select); Thread.sleep(forTimeInterval: 2); shot("31-tv-guide")       // → TV Shows(3)
        openOptions(); press(.down, 4); press(.select); Thread.sleep(forTimeInterval: 1)                           // → Channels(5)
        press(.down); press(.right); press(.down, 4); shot("32-editor-tune-default")                                // first channel, field 5 = When you tune in
        press(.select); shot("33-editor-tune-live"); press(.select); shot("34-editor-tune-start")
        press(.select)                                                                                             // back to Default
        press(.menu, 2); Thread.sleep(forTimeInterval: 1)
        openOptions(); press(.select); Thread.sleep(forTimeInterval: 1); shot("35-all-guide")                      // Guide(1) → all channels
        XCTAssertEqual(app.state, .runningForeground)
    }

    /// Banner actions include Audio / Subtitles pickers; Settings has the audio-language, subtitles and subtitle-language rows.
    func testTrackPickerAndSettings() {
        app.launch()
        Thread.sleep(forTimeInterval: 6)
        press(.down); press(.select); Thread.sleep(forTimeInterval: 5)            // tune to what's on
        press(.select); Thread.sleep(forTimeInterval: 1); shot("40-banner-actions")
        // Walk the action row until the focused action is the subtitle picker (the app's single accessibility element
        // describes what's focused), then cycle it once: the stream reloads with the first subtitle track.
        var hops = 0
        while !focusDescription().contains("Subtitles"), hops < 5 { press(.right); hops += 1 }
        shot("41-banner-subtitles-focused")
        press(.select); Thread.sleep(forTimeInterval: 5); shot("42-subtitles-cycled")
        Thread.sleep(forTimeInterval: 3)                                          // banner auto-hides 6 s after the last press
        press(.menu); Thread.sleep(forTimeInterval: 1.5)                           // one Menu: watch → guide
        openOptions(); press(.down, 5); press(.select); Thread.sleep(forTimeInterval: 1)
        press(.down, 11); shot("43-settings-audio-language")   // theme, crt, auto, idle, attract, saver, tune, breaks, spots, idents, quality, audio
        press(.select); shot("44-settings-audio-english")
        press(.down); press(.select); shot("45-settings-subtitles-on")
        press(.down); shot("46-settings-subtitle-language")
        press(.up); press(.select); press(.select)                                 // back to Forced only
        press(.up); press(.select, 9)                                              // audio back to Default (10-state cycle)
        XCTAssertEqual(app.state, .runningForeground)
    }

    /// Station ident on tune, the "Tonight on" strip in the banner, and attract mode after idle (the launch argument
    /// shortens the delay to 1 minute for this run).
    func testSignatureMoments() {
        app.launchArguments += ["-attractMinutesOverride", "1"]
        app.launch()
        Thread.sleep(forTimeInterval: 6)
        press(.down); press(.select, wait: 0.05); Thread.sleep(forTimeInterval: 0.45); shot("60-ident-on-tune")   // ident card is up for 1.5 s
        Thread.sleep(forTimeInterval: 4); shot("61-banner-tonight-strip")                                // banner with the Tonight strip
        press(.menu); Thread.sleep(forTimeInterval: 1.5)                                                 // one Back → guide, drifting
        Thread.sleep(forTimeInterval: 65); shot("62-attract-mode")                                       // 1 minute idle → attract
        press(.down); Thread.sleep(forTimeInterval: 1.2); shot("63-attract-woken")                       // first press only wakes
        XCTAssertEqual(app.state, .runningForeground)
    }

    /// Midnight Movie: guide, watch banner and Options in the second theme; Select on the Theme row flips back to Late Show.
    func testMidnightMovie() {
        app.launchArguments += ["-themeOverride", "midnight"]
        app.launch()
        Thread.sleep(forTimeInterval: 6)
        shot("60-mm-guide-drifting")
        press(.down); shot("61-mm-guide-focused")
        press(.select); Thread.sleep(forTimeInterval: 5); shot("62-mm-watch")
        press(.select); Thread.sleep(forTimeInterval: 1); shot("63-mm-banner-actions")
        Thread.sleep(forTimeInterval: 5)                                           // banner auto-hides 6 s after the last press
        press(.menu); Thread.sleep(forTimeInterval: 1.5)                           // one Menu: watch → guide (a second would go Home)
        openOptions(); shot("64-mm-options")
        press(.down, 6); shot("65-mm-options-theme")                               // Guide(1) → Theme(7)
        press(.left); Thread.sleep(forTimeInterval: 1); shot("66-late-show-again")   // Left picks Late Show, the menu stays open
        press(.select); Thread.sleep(forTimeInterval: 1); shot("67-theme-confirmed")  // Select confirms: menu closes, theme unchanged
        XCTAssertEqual(app.state, .runningForeground)
    }


    /// Left / Right while watching skip 10 s per press: three quick Lefts pool into one 30 s rewind (the banner says
    /// "30 s behind live"), three Rights catch back up to live.
    func testSkip() {
        app.launch()
        Thread.sleep(forTimeInterval: 6)
        press(.down); press(.select); Thread.sleep(forTimeInterval: 6)            // tune to what's on, let it settle
        press(.left, 3, wait: 0.05); Thread.sleep(forTimeInterval: 4); shot("70-skip-back")
        press(.right, 3, wait: 0.05); Thread.sleep(forTimeInterval: 4); shot("71-skip-forward")
        Thread.sleep(forTimeInterval: 3); press(.menu); Thread.sleep(forTimeInterval: 1.5)   // banner gone → one Menu: watch → guide
        XCTAssertEqual(app.state, .runningForeground)
    }


    /// Settings › About: the credit and privacy note render in the left pane when the row is focused.
    func testAbout() {
        app.launch()
        Thread.sleep(forTimeInterval: 6)
        openOptions(); press(.down, 5); press(.select); Thread.sleep(forTimeInterval: 1)   // Guide(1) → Settings(6)
        press(.down, 18); shot("80-settings-about")                                        // theme … signout, then About
        XCTAssertTrue(focusDescription(["About Rerun Guide"]).contains("Soyboi"))
        XCTAssertEqual(app.state, .runningForeground)
    }


    /// The bouncing-logo screensaver: pick it in Settings, let the guide idle past the (overridden) one-minute attract
    /// delay, and the logo is on screen and moving; the first press only wakes the screen. Puts the setting back after.
    func testBounceSaver() {
        app.launchArguments += ["-attractMinutesOverride", "1"]
        app.launch(); Thread.sleep(forTimeInterval: 6)
        func toggleSaver() { openOptions(); press(.down, 5); press(.select); Thread.sleep(forTimeInterval: 1); press(.down, 5); press(.select); press(.menu); Thread.sleep(forTimeInterval: 1) }
        toggleSaver()                                                                       // theme, crt, auto, idle, attract → Screensaver: wall → logo
        Thread.sleep(forTimeInterval: 75); shot("90-bounce-saver")
        Thread.sleep(forTimeInterval: 8); shot("91-bounce-saver-later")
        press(.down); Thread.sleep(forTimeInterval: 1); shot("92-bounce-woken")
        toggleSaver()                                                                       // logo → soyboi
        Thread.sleep(forTimeInterval: 75); shot("93-soyboi-saver")
        Thread.sleep(forTimeInterval: 8); shot("94-soyboi-saver-later")
        press(.down); Thread.sleep(forTimeInterval: 1)
        toggleSaver()                                                                       // soyboi → wall, as it was
        XCTAssertEqual(app.state, .runningForeground)
    }


    /// Channel 99 is scrambled: the picture is the scramble effect, the banner still works, and the guide's preview
    /// window scrambles too when the row is focused.
    func testChannel99() {
        app.launchArguments += ["-tuneOverride", "99"]
        app.launch(); Thread.sleep(forTimeInterval: 10); shot("95-channel-99")
        Thread.sleep(forTimeInterval: 3); shot("96-channel-99-later")
        press(.select); Thread.sleep(forTimeInterval: 1); shot("97-channel-99-actions")
        Thread.sleep(forTimeInterval: 7); press(.menu); Thread.sleep(forTimeInterval: 2)   // banner gone → one Menu: watch → guide
        press(.down); Thread.sleep(forTimeInterval: 2); shot("98-guide-99")                 // the guide parks on 99; Down takes the remote
        XCTAssertEqual(app.state, .runningForeground)
    }


    /// The bundled Soyboi spot: forced onto the first load, it plays labeled as an ad with its Skip chip, then hands back
    /// to the program by itself when it ends.
    func testHouseSpot() {
        app.launchArguments += ["-tuneOverride", "2", "-spotOverride", "YES"]
        app.launch(); Thread.sleep(forTimeInterval: 12); shot("85-house-spot")
        XCTAssertTrue(focusDescription(["Ad, Message"]).hasPrefix("Ad, Message from Soyboi"), "the spot should be described as an ad")
        Thread.sleep(forTimeInterval: 30); shot("86-after-spot")
        XCTAssertTrue(focusDescription(["Ad, Message"]).isEmpty, "the spot should have ended by itself")
        // Menu back out to the Home screen (watch → guide, remote-controlled guide → drifting, drifting → Home), so both the program's
        // and the guide preview's streams are stopped on the server before the runner kills the app.
        for _ in 0..<4 where app.state == .runningForeground { press(.menu); Thread.sleep(forTimeInterval: 2) }
        XCTAssertNotEqual(app.state, .runningForeground)
    }


    /// The spot's Skip chip: Select does nothing during the 5 s countdown, then ends the spot the way its own end does
    /// (the program, or a break's trailers / station card, takes over).
    func testSkipHouseSpot() {
        app.launchArguments += ["-tuneOverride", "2", "-spotOverride", "YES"]
        app.launch()
        let ad = ["Ad, Message"], until = Date.now.addingTimeInterval(20)
        while focusDescription(ad).isEmpty, Date.now < until { Thread.sleep(forTimeInterval: 0.25) }
        XCTAssertTrue(focusDescription(ad).contains("skip it in"), "the spot should start with its skip countdown")
        press(.select)                                                           // too early: nothing happens
        XCTAssertFalse(focusDescription(ad).isEmpty, "Select during the countdown should not skip the spot")
        Thread.sleep(forTimeInterval: 1); shot("87-spot-countdown")              // the tune-in ident card is gone by now
        Thread.sleep(forTimeInterval: 5); shot("88-spot-skippable")
        XCTAssertTrue(focusDescription(ad).contains("Select to skip"))
        press(.select); Thread.sleep(forTimeInterval: 4); shot("89-spot-skipped")
        XCTAssertTrue(focusDescription(ad).isEmpty, "Select after the countdown should end the spot")
        XCTAssertFalse(focusDescription(["Channel 2 "]).isEmpty, "the channel should follow the spot")
        for _ in 0..<4 where app.state == .runningForeground { press(.menu); Thread.sleep(forTimeInterval: 2) }   // as testHouseSpot
        XCTAssertNotEqual(app.state, .runningForeground)
    }


    /// Back leaves the player in one press even while the tune-in banner is up, and a second one (on the drifting
    /// guide) returns to the Home screen. Runs on a real Apple TV too, where the remote events are the hardware's.
    func testBackLeavesPlayer() {
        app.launchArguments += ["-tuneOverride", "2"]
        app.launch(); Thread.sleep(forTimeInterval: 12)          // lineup built, tuned, stream started
        press(.menu); Thread.sleep(forTimeInterval: 2)           // banner may still be up: one Back → guide
        XCTAssertEqual(app.state, .runningForeground)
        press(.menu)                                             // drifting guide → Home
        XCTAssertTrue(leftForeground(), "two Back presses from the player should reach the Home screen")
    }

    /// The Home screen is in front within `timeout`. Asked of the Home screen app, not ours: leaving while video plays,
    /// XCUITest keeps reporting this app as foreground for a while even though the Home screen is already showing.
    func leftForeground(timeout: TimeInterval = 10) -> Bool {
        let home = XCUIApplication(bundleIdentifier: "com.apple.HeadBoard")
        let end = Date.now.addingTimeInterval(timeout)
        while Date.now < end {
            if app.state != .runningForeground || home.state == .runningForeground { return true }
            Thread.sleep(forTimeInterval: 0.5)
        }
        return false
    }


    /// Out to the Home screen mid-program and back: the app stops its stream on the way out (watch the server's session
    /// count meanwhile to confirm) and plays again on return, banner up, without a second tune-in card.
    func testBackgroundAndReturn() {
        app.launchArguments += ["-tuneOverride", "2"]
        app.launch(); Thread.sleep(forTimeInterval: 12); shot("100-watching")
        remote.press(.home); Thread.sleep(forTimeInterval: 12)
        app.activate(); Thread.sleep(forTimeInterval: 8); shot("101-back-from-home")
        XCTAssertEqual(app.state, .runningForeground)
        press(.menu); Thread.sleep(forTimeInterval: 2)        // leave the player so its stream is stopped before the runner ends
        press(.menu); XCTAssertTrue(leftForeground())
    }


    /// Select on the profile already in use opens its Rename / Delete row (switching to it would do nothing); Back
    /// closes the row and stays on the Profiles screen.
    func testSelectOwnProfileOffersRename() {
        app.launch(); Thread.sleep(forTimeInterval: 6)
        openOptions(); press(.up); press(.select); Thread.sleep(forTimeInterval: 1)    // Options › profile row → Profiles
        press(.select); Thread.sleep(forTimeInterval: 0.5); shot("110-profile-rename-row")
        XCTAssertTrue(focusDescription(["Rename, for"]).hasPrefix("Rename, for"), "Select on the current profile should open Rename")
        press(.menu); Thread.sleep(forTimeInterval: 0.5)                                 // closes the row only
        XCTAssertTrue(focusDescription(["watching now"]).contains("watching now"), "Back should close the row, not leave Profiles")
        press(.menu); Thread.sleep(forTimeInterval: 1)
        XCTAssertEqual(app.state, .runningForeground)
    }


    /// Menu during attract mode wakes the guide instead of leaving the app; a second Menu on the drifting guide still
    /// goes Home.
    func testMenuWakesScreensaver() {
        app.launchArguments += ["-attractMinutesOverride", "1"]
        app.launch(); Thread.sleep(forTimeInterval: 70); shot("120-attract-before-menu")
        press(.menu); Thread.sleep(forTimeInterval: 2); shot("121-woken-by-menu")
        XCTAssertEqual(app.state, .runningForeground, "Menu should wake the screensaver, not leave the app")
        press(.menu)
        XCTAssertTrue(leftForeground(), "a second Menu on the drifting guide should still reach the Home screen")
    }

    /// Settings › Server › Continue with the same libraries: the Building screen must finish and Select must reach the
    /// guide (it used to hang when the cache was under six hours old).
    func testRepickLibrariesFinishes() {
        app.launch(); Thread.sleep(forTimeInterval: 6)
        openOptions(); press(.down, 5); press(.select); Thread.sleep(forTimeInterval: 1)   // Options › Settings
        press(.down, 15); press(.select); Thread.sleep(forTimeInterval: 3)                    // … › Server → libraries
        press(.down, 8); press(.select); Thread.sleep(forTimeInterval: 25); shot("122-repick-building")   // Continue → Building
        XCTAssertTrue(focusDescription(["Start flipping"]).contains("Start flipping"), "the Building screen never finished")
        press(.select); Thread.sleep(forTimeInterval: 2); shot("123-repick-guide")
        XCTAssertTrue(focusDescription(["Start flipping"]).isEmpty, "Select should leave the Building screen")
        XCTAssertEqual(app.state, .runningForeground)
    }

    /// Emby on Welcome opens the emby.media/pin link screen with a 5-digit code from Emby Connect; Back returns to Welcome on
    /// the Emby card, and Select on the link screen offers the typed address instead. `-persisted <data/>` starts signed out
    /// for this run only (argument domain), so the demo setup the other tests rely on is untouched.
    func testEmbyPinScreen() {
        app.launchArguments += ["-persisted", "<data></data>"]
        app.launch(); Thread.sleep(forTimeInterval: 4)
        if app.state != .runningForeground { app.activate(); Thread.sleep(forTimeInterval: 2) }   // after a test that went Home
        press(.right, 2); XCTAssertTrue(focusDescription(["Emby."]).contains("Emby account"), "Emby card should offer the account link")
        press(.select); Thread.sleep(forTimeInterval: 5); shot("130-emby-pin")
        let link = focusDescription(["Sign in to Emby"])
        XCTAssertNotNil(link.range(of: #"Code \d \d \d \d \d"#, options: .regularExpression), "expected a 5-digit Emby PIN, got: \(link)")
        XCTAssertTrue(link.contains("server address instead"))
        press(.menu); Thread.sleep(forTimeInterval: 1)
        XCTAssertTrue(focusDescription(["Emby."]).hasPrefix("Emby."), "Back should land on the Emby card")
        press(.select); Thread.sleep(forTimeInterval: 1); press(.select); Thread.sleep(forTimeInterval: 1); shot("131-emby-address")
        XCTAssertFalse(focusDescription(["Server address", "Continue"]).isEmpty, "Select on the link screen should open the address screen")
        XCTAssertEqual(app.state, .runningForeground)
    }
}
