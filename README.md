# Rerun Guide

An Apple TV, iPhone, iPad, Mac, Google TV, Fire TV and Android app (LG and Samsung TVs run the web port in `web/`) that turns your Plex / Emby / Jellyfin library into an old-school TV channel guide. Channels are built from your library (genre + decade, series marathons, directors, actors, years, plus ones you make); programs run on a fixed schedule generated deterministically from the clock, so every device agrees with no backend. The app ships two themes, **Late Show** (default) and **Midnight Movie**, switchable from Options and Settings › Appearance and stored per profile.

## Layout

- `project.yml`: [xcodegen](https://github.com/yonaskolb/XcodeGen) spec. Regenerate after adding files: `xcodegen generate`.
- `Design/handoff/`: the design handoff. `README.md` is the spec (positions, colors, type, behaviour); the `.dc.html` files are the clickable prototype, icon and App Store art (open them in a browser; they all load the one `support.js` at the top level).
- `App/`: the SwiftUI app, one source tree for two targets: `RerunGuide` (tvOS) and `RerunGuide-iOS` (iPhone, iPad, and Mac via Mac Catalyst).
  - `AppModel.swift`: the single `@Observable` state object (navigation, profiles, settings, channels, playback). Screen-specific remote handlers live in `App/<Area>/AppModel+<Area>.swift`.
  - `Remote.swift`: `RemoteCaptureView` owns the Siri Remote and turns presses/swipes into `RemoteCommand`s. The app drives its own focus model (the guide scrolls on its own, loops and pages), so screens never use focusable SwiftUI controls, except a text field while `editingField` is set. Off tvOS the same view is the first responder for hardware keyboards (arrows, Return = Select, Space = Play/Pause, Esc or Delete = Back) and reads the scroll wheel / trackpad. A keyboard's number keys type a channel number on the guide and the player (the box in the top-right corner, `AppModel+Watch.swift`).
  - `Touch.swift`: iPhone, iPad and Mac input layered on the remote model. A tap focuses an element and presses Select (`tapSelects`), drags and the scroll wheel press the arrows (window pan recognizers in `Remote.swift`), `TouchChrome` draws the guide's menu button (in the iPhone's landscape margin beside the Dynamic Island) plus Back and Play/Pause, `MenuHint` bounces the menu's edge in from the left on the first three launches (a swipe in from the left edge or a tap on the You chip also opens the menu), and `TextEntry` edits `editingField` in an alert instead of an inline field. Every modifier there is a no-op on tvOS. `RootView` scales the 1080-tall stage to fit the window: on screens wider than 16:9 (iPhone, wide Mac windows) it uses the full height and widens (`\.stageWidth`), so the guide, overlays and toast stretch while other screens stay 1920 wide and centered; iPad letterboxes. Held upright, iPhone and iPad switch to `Portrait/` on the screens that have a portrait layout; the rest turn an iPhone sideways (`OrientationLock` in `RerunGuideApp.swift`) and letterbox on iPad.
  - `Theme.swift`: the Late Show and Midnight Movie tokens and fonts (`ThemeID`, `AppModel.setTheme`); views branch on `t.id == .midnight` where the geometry differs; `.at(x, y, w:, h:)` places views on the 1920×1080 stage the handoff is drawn on.
  - `Portrait/`: iPhone and iPad held upright. Lineup (preview plus a grid that loops down the channels and scrolls 12 hours across), Program info and New channel as sheets, Now playing, Channels and Settings, drawn in window space over the same `AppModel`: a tap sets the focus and presses Select, as on the stage. Now playing keeps the stage's `WatchScreen` in its picture box, so turning the device keeps the stream; its full-screen button turns a phone to the TV player. Not built for tvOS.
  - `Guide/`, `Watch/`, `Onboarding/`, `Settings/`, `Overlays/`, `Search/`: one folder per screen group. `Search/` is Options › Search: the page, Program info's Make a channel, and the Make a channel page (`MakeChannelOverlay` in `Overlays.swift`).
  - `Assets.xcassets`: tvOS app icon (layered), Top Shelf images and `LaunchSplash` (the Apple TV launch screen, shown by `LaunchScreen.storyboard`); `AppIcon` is the iPhone/iPad/Mac icon. The tvOS icon layers and Top Shelf images are the designer's art (`Design/handoff/app-icon`); only the launch splash is a placeholder. `PrivacyInfo.xcprivacy` is the privacy manifest (UserDefaults, reason CA92.1).
- `Core/`: the `RerunGuideCore` Swift package (no UI, tested on macOS):
  - `Models.swift`, `MediaServer.swift`: the contract every backend implements (`MediaItem`, `LibrarySection`, `ChannelDefinition` + `RuleChip`, `Channel`, `Slot`, `PlaybackSource`).
  - `PlexClient.swift`: plex.tv PIN and password sign-in, server discovery, library, extras/trailers, HLS transcode URLs.
  - `JellyfinClient.swift`: Emby and Jellyfin password and Quick Connect sign-in, views, library, local trailers, `PlaybackInfo`-driven direct play / transcode.
  - `EmbyConnect.swift`: Emby's PIN sign-in (the viewer types a code at emby.media/pin, like plex.tv/link). It covers the PIN, the Emby Connect account's servers, and `JellyfinClient.connectExchange` to trade a server's Connect key for a local sign-in. Emby doesn't document these calls; they follow Emby.ApiClient.Java's `ConnectService`.
  - `GuideGenerator.swift`: `ChannelBuilder` (library → automatic channels, rule resolution, "new channel" candidates) and the seeded, day-tiling scheduler with program status.
  - `TitleSearch.swift`: Options › Search. Films by title and shows by series title, matched ignoring case, accents, punctuation and a leading "The", "A" or "An"; exact titles first, then ones that start with the text, then the rest, 50 at most; and when each airs next in the coming 24 hours.
  - `DemoServer.swift`: fixed fake library so the whole app runs without a server ("Sample station" on the welcome screen).

## Run

Needs Xcode 16 or later, [xcodegen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`) and, for Android, JDK 17 and the Android SDK (API 36).

```bash
xcodegen generate
open RerunGuide.xcodeproj
```

Pick an Apple TV simulator and run. Drive it with the simulator's remote (arrow keys, Return = Select, Space = Play/Pause, Esc = Back).

For iPhone, iPad or Mac, pick the `RerunGuide-iOS` scheme and an iPhone/iPad simulator or My Mac (Mac Catalyst). Tap or click to focus and select, drag or scroll to move through lists. Both apps share the bundle ID `com.rerunguide.RerunGuide` (universal purchase). The Mac build is sandboxed and keeps the server token in the Keychain, so it needs a development provisioning profile. To build under your own Apple developer team, set `DEVELOPMENT_TEAM` (in the top-level `settings` of `project.yml`) and the bundle IDs there, then run `xcodegen generate`. From the command line:

```bash
xcodebuild build -project RerunGuide.xcodeproj -scheme RerunGuide-iOS -destination 'platform=macOS,variant=Mac Catalyst' -allowProvisioningUpdates
```

LAN discovery broadcasts over UDP for both Plex (GDM) and Jellyfin/Emby, so on iPhone and iPad it needs Apple's Multicast Networking entitlement (`com.apple.developer.networking.multicast`, requested at developer.apple.com/contact/request/networking-multicast); until it is granted and added to `project.yml`, the address screen there offers typed addresses only. The Mac build has the sandbox's `com.apple.security.network.server` entitlement so it can receive the replies.

Core tests (macOS, no simulator needed):

```bash
cd Core && swift test
```

Remote walkthrough on a booted Apple TV simulator (drives the app with `XCUIRemote`, saves a screenshot per step to `SHOT_DIR`):

```bash
TEST_RUNNER_SHOT_DIR=/tmp/rerun-shots xcodebuild test -project RerunGuide.xcodeproj -scheme RerunGuide -destination 'platform=tvOS Simulator,name=Apple TV 4K (3rd generation)' -only-testing:RerunGuideUITests
```

## Android, Google TV and Fire TV

`android/` is the same app for Google TV and Amazon Fire TV (D-pad remote) and Android phones and tablets (touch), one APK, Kotlin + Jetpack Compose. It is a line-by-line port that keeps the Swift names, so a change in one app maps directly onto the other; the Swift app is the reference.

- `android/core`: plain-JVM Kotlin port of `Core/` (same types and functions). `ParityTest` checks the scheduler bit for bit against values the Swift Core produced (`core/src/test/resources/parity-fixture.json`). After changing the Swift Core, regenerate it with `swift run --package-path tools/fixturegen fixturegen android/core/src/test/resources/parity-fixture.json` (from the repo root). `LiveServersTest` and `SubtitleLiveTest` hit real servers only when `PLEX_URL`, `PLEX_TOKEN`, `EMBY_URL`, `EMBY_TOKEN` and `EMBY_USER_ID` are set; keep those in a file outside the repo and load it with `set -a; . path/to/test.env; set +a`.
- `android/app`: port of `App/`. `AppModel*.kt` mirror `AppModel+*.swift`, screens are composables with the same names. `Stage.kt` draws the 1920×1080 stage with 1 dp = 1 stage point, so every number from the Swift views carries over; `MainActivity` turns remote keys into `RemoteCommand`s. Text entry is always a dialog (the Swift non-tvOS path). `Portrait.kt`, `PortraitGuide.kt` and `PortraitWatch.kt` are `App/Portrait` for phones and tablets held upright; phones turn sideways for screens without a portrait layout. The player is Media3 ExoPlayer; channel 99's scramble is an AGSL shader on Android 13+ with a fallback below. `Platform.isFireTV` (Amazon's `amazon.hardware.fire_tv` feature) names the device and the remote's Select in the copy, and on Fire TV channel 99 is the web port's silent `web/assets/scramble.gif` (packaged from there as an asset) instead of the shader and warble.

```bash
cd android
./gradlew :core:test          # Kotlin core tests, including the Swift parity check
./gradlew :app:assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds accept the Swift launch arguments as intent extras (`--ei tuneOverride 2`, `--es themeOverride midnight`, `--ei attractMinutesOverride 1`, `--ez spotOverride true`, `--es serversOverride 3`) plus `--ez forceTV true`, which treats a phone or TV-profile emulator image as a TV, and `--ez forceFireTV true`, which treats it as a Fire TV (there is no Fire OS emulator):

```bash
adb shell am start -n com.rerunguide.app/.MainActivity --ez forceTV true
```

To try a build on a Fire TV, turn on Settings › My Fire TV › Developer options › ADB debugging (on newer Fire OS the menu appears after clicking the device name in About 7 times), then `adb connect <fire-tv-ip>:5555` and `adb install -r` the APK.

## Fonts

Bricolage Grotesque 500–700 + Hanken Grotesk (Late Show) and Barlow Condensed 500–800 + 800 italic (Midnight Movie) are bundled under `App/Fonts/` with their SIL Open Font License texts and registered in `project.yml` (`UIAppFonts`). `Theme.swift` looks them up by PostScript name (`Family-Weight`) and falls back to the system font if a file is missing.

## Signature moments

- **Station idents**: a channel card with a two-note sting on every channel change, and a "We'll be right back" card before trailer breaks (Settings › Playback › Station idents).
- **Tonight on…**: the channel banner lists the next programs on the channel you're watching instead of remote hints.
- **Attract mode**: leave the guide alone and it settles into a slow storefront wall: enlarged preview, big clock, listings drifting at half speed; the first press wakes it (Settings › Guide › Attract mode after).

## How the guide works

- `ChannelBuilder.automaticChannels(from:)` turns the library into channel definitions; user edits and custom channels are layered on top in `AppModel.rebuildChannelsAsync()`.
- `GuideGenerator.schedule(for:day:)` seeds a PRNG with `channelID + date`, orders the pool (shuffle / in order / newest first) and tiles the day in 30‑minute blocks (runtime + 3 min, rounded up). A short program (12 minutes or less) shares its half hour with the next ones that fit, back to back, so a channel of short cartoons isn't mostly breaks; nothing crosses a half-hour mark. Time left in a slot after the program ends is a "break": trailers from your library, or a station card. Nothing airs twice in a row, across midnight too (except on in-order marathons, where the episode order wins, and on a channel with only one film): the same item, or another copy of the same film (same title and year). A film's cut is part of its title ("Watchmen (Directors Cut)", from Plex's edition or a `{edition-…}` file name), so two cuts are two films.
- Tuning in joins in progress (`now - slotStart`); servers that transcode get the offset in the URL, direct-play streams are seeked in AVPlayer.
- A program started from its beginning (Start over, Watch now, the guide's Play, the "Start from the beginning" tune mode) is marked watched on the server once it has played to 90%: Plex `PUT /:/scrobble`, Jellyfin and Emby `POST /Users/{user}/PlayedItems/{item}`. Joining live never marks anything, even after a pause or a skip back. The app's copy of the library keeps its flag until the next library refresh, so the Unwatched channel doesn't change mid-program.
