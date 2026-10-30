# Rerun Guide for LG and Samsung TVs

`web/` is Rerun Guide for LG webOS and Samsung Tizen TVs: one TypeScript + Preact web app, built twice (an `.ipk` for LG, a `.wgt` for Samsung) and runnable in a desktop browser for development. Like `android/`, it is a file-by-file port that keeps the names of the Kotlin port (`android/`), which keeps the Swift names; the Swift app is the reference. A change in one app maps directly onto the others.

## Layout

- `src/core/`: port of `android/core` (same file names, `.ts`). No UI, no DOM except `fetch`.
- `src/app/`: port of `android/app` (same file names; screens are `.tsx`). `Platform.ts` holds everything LG-, Samsung- or browser-specific.
- `src/main.tsx`: `MainActivity.kt`: platform setup, the model, keys, lifecycle, the render loop.
- `src/index.html`, `src/styles.css`: the page shell, `@font-face`, keyframes, shared classes.
- `test/`: Node tests for the core (`npm test`), including the Swift parity check against `android/core/src/test/resources/parity-fixture.json` (read in place, never copied).
- `platform/webos/`, `platform/tizen/`: `appinfo.json` / `config.xml` and icons.
- `assets/scramble.gif`: channel 99 (see below). Fonts, the house spots and images are copied at build time from `App/` and `android/` so they exist once in the repo.
- `build.mjs`: the build, the dev server and the test runner (the `npm run` scripts).
- `tools/`: `scramble-gif.mjs` (channel 99's GIF), `package-tizen.mjs` (`npm run package:tizen`).

## Porting rules

**Targets.** 2020 and newer TVs: webOS 5+ (Chromium 68) and Tizen 5.5+ (Chromium 69; Samsung's store takes 2021+, Tizen 6 / Chromium 76). esbuild bundles everything into one classic script (`format: 'iife'`, `target: 'chrome68'`): no `type="module"`, no code splitting, no dynamic `import()` (webOS blocks module scripts from `file://`). esbuild lowers syntax (`?.`, `??`, class fields) but adds no polyfills, so these APIs are banned: `Array.prototype.flat/flatMap/at`, `Object.fromEntries`, `String.prototype.matchAll/replaceAll`, `Promise.allSettled/any`, `globalThis`, `queueMicrotask`, `structuredClone`, `crypto.randomUUID`, `AbortSignal.timeout`, `Intl.DisplayNames`, `Intl.Segmenter`, `Intl.RelativeTimeFormat`, Wake Lock, `ResizeObserver`. CSS: no flexbox `gap` (use margins: `.row>*+*{margin-left}`), no `inset`, `aspect-ratio`, `:is()/:where()/:has()`, `min()/max()/clamp()`, `backdrop-filter`, `conic-gradient`, two-position colour stops.

**Names.** Keep every Kotlin name (types, functions, fields, enum constants, file names). Kotlin overloads get these TS names, and nothing else is renamed:

| Kotlin | TypeScript |
|---|---|
| `GuideGenerator.schedule(ch, day)` / `schedule(ch, from, to)` | `schedule(ch, day)` / `scheduleRange(ch, from, to)` |
| `ChannelBuilder.channels(defs, lib, fresh, now)` / `channels(defs, lib, now)` / `channels(from, minItems)` | `channels(defs, lib, fresh, now)` / `channelsFrom(defs, lib, now)` / `channelsOf(items, minItems)` |
| `SeededGenerator.next()` / `next(upperBound)` | `next()` / `nextBelow(upperBound)` |
| `Channel(definition, items)` / `Channel(id, number, name, items, tag, order)` | `new Channel(definition, items)` / `Channel.make(id, number, name, items, tag?, order?)` |
| `AppModel.channel(id)` / `channel(numbered)` | `channel(id)` / `channelNumbered(n)` |

**Types.**
- Instants are epoch **seconds** as `number` (`type Instant = number`), never `Date`. Durations are seconds. Kotlin `Time.kt` disappears; `core/Time.ts` holds the local-calendar helpers (`startOfDay`, `addDays`, the local year/month/day), built on `new Date(y, m, d)` with a guard for DST gaps at midnight (if the built date's `getDate()` isn't `d`, step forward until it is). Default time zone = the device's.
- 64-bit maths (FNV-1a, SplitMix64, Lemire) uses `BigInt` with `BigInt.asUintN(64, …)`. Compare fixture values as strings.
- Integer division: `Math.trunc(a / b)`. `Int` parsing: strict (`/^\d+$/` plus range), never bare `parseInt`/`+s`.
- Enums are string-literal unions spelled like the Kotlin constants (`'welcome' | 'address' | …`). Sealed classes are discriminated unions (`{kind: 'info', key}`). Data classes are readonly plain objects (`interface` + a `copy` via spread) unless they have behaviour; a class that needs getters gets an explicit `copy()`.
- Maps keyed by library data (series ids, genres, names) are `Map`/`Set`, never `{}`.
- Sorting: `Array.prototype.sort` is not stable before Chromium 70. Every sort goes through `stableSort` in `core/Lang.ts`. String order is `swiftCompare` (NFC, then code points), never `localeCompare` or `<` on strings. Lowercasing is `toLowerCase()`, never `toLocaleLowerCase()`.
- Kotlin `String.replace(a, b)` replaces every occurrence: use `replaceAll` from `core/Lang.ts`, never `replace(string, string)`.
- JSON: `JSON.parse` plus the lenient helpers in `core/Lang.ts` that copy org.json's (`stringOrNull`, `intOrNull`, …). Persisted JSON shapes are byte-for-byte the Kotlin `toJson` shapes (key names, nulls omitted) so an Android `seed.json` imports unchanged.
- UUIDs: `uuid()` in `core/Lang.ts` (v4 from `crypto.getRandomValues`, uppercase).
- HTTP: `fetch` with `credentials: 'omit'`, timeouts by `AbortController` + `setTimeout`. `fetch`'s own redirect handling replaces Kotlin's loop. 401/403 → `unauthorized`.

**The model.** `AppModel` is one class (`app/AppModel.ts`). Each Kotlin `AppModelX.kt` extension file becomes `app/AppModelX.ts`, which adds its functions to `AppModel.prototype` and declares them with module augmentation, so call sites stay `model.pressGuide(cmd)`:

```ts
import { AppModel } from './AppModel'
declare module './AppModel' { interface AppModel { readonly half: number; pressGuide(cmd: RemoteCommand): void } }
Object.defineProperty(AppModel.prototype, 'half', { get(this: AppModel) { return Math.max(1, this.visibleChannels.length * this.rowPitch) } })
AppModel.prototype.pressGuide = function (this: AppModel, cmd) { … }
```

`main.tsx` imports every `AppModelX.ts` once for its side effect. Reactivity: `main.tsx` wraps the model in `observable(model, scheduleRender)` (a `Proxy` whose `set` trap schedules one render per animation frame) and only then calls `model.start()`, which does what the Kotlin `init` block does after the fields are set (loading the saved link etc.). So: the constructor never starts async work and never creates closures over `this`; no arrow-function class fields on `AppModel`; state changes are field assignments (`this.x = …`, new arrays/objects, not in-place mutation of observed state). Effect keys in components are strings or numbers (Preact compares with `Object.is`).

**Rendering.** Preact function components with hooks; `useModel()` and `useTheme()` read contexts set in `RootView`. The stage is a 1920×1080 `div`, `position: absolute` children placed with `at(x, y, w, h)` (returns a style object), exactly like `Stage.kt`. Both TVs give a 1920×1080 viewport, so the stage scale is 1; `Stage` scales with `transform` only when the window isn't 1920 wide (browser dev). Theme tokens come from `app/Theme.ts` (a port of `Theme.kt`) and go into inline styles. Animations:
- `animate*AsState` → CSS `transition` on the property (keep the element mounted).
- enter/exit animations → CSS `@keyframes` on mount; a small `useExit(value, ms)` hook keeps the last value mounted with an `out` class while it fades.
- infinite animations → CSS `@keyframes … infinite`, transform/opacity only.
- per-frame motion (guide drift, bounce saver) → a `requestAnimationFrame` loop writing `style.transform` through a ref, never through the model.
- clocks (`Periodic`, `EverySecond`) → small self-ticking components, so the whole app never re-renders once a second.
Keep TV GPUs in mind: no `filter: blur`, no transitions on `box-shadow`, big shadows on static sibling layers, `will-change` only on the few moving layers.

**Text.** `Para` joins the last two words with a no-break space when the last word is short (the Kotlin rule, estimated by character count). `lineSpacing`, `lineEm` and `Clock` come from `Text.kt`. Fonts are the bundled TTFs via `@font-face`.

**Input.** One input model, the seven `RemoteCommand`s, dispatched by `model.press`, plus the number keys (`model.pressDigit`: a channel number on the guide and the player). `Remote.ts` maps `keydown` (`e.keyCode`) to commands:

| Key | LG | Samsung | Browser | Command |
|---|---|---|---|---|
| arrows | 37–40 | 37–40 | arrows | left/up/right/down |
| OK | 13 (ignored while the Magic Remote pointer is visible: the click does it) | 13 | Enter | select |
| Back | 461 (`disableBackHistoryAPI: true`) | 10009 (never registered) | Esc, Backspace | back |
| Play/Pause | 415 / 19 | 10252, 415, 19 (registered) | Space | play |
| ⏩ / ⏪ | 417 / 412 | 417 / 412 (registered) | | right / left, player only |
| CH+ / CH− | — | 427 / 428 (registered) | | up / down, player only |
| 0–9 | 48–57 | 48–57 (registered) | number row 48–57, keypad 96–105 | `pressDigit`, guide and player only |

A held key gives one command (its repeats are swallowed until keyup; cleared on `blur`/`visibilitychange`; a key-down more than 1 s after the last one is a new press, for a key-up that went missing). While a text field is open (`editingField`), keys go to the field and nothing calls `preventDefault`. Back where `backExitsApp` is true calls `Platform.exitApp()` (LG: `webOSSystem.platformBack()`; Samsung: `tizen.application.getCurrentApplication().exit()`, after `model.enteredBackground()` has asked the server to stop both streams, waiting 1 s at most).

The Magic Remote's pointer is a mouse. The Kotlin touch layer (`onTouch`, `backgroundTap`, `tapSelects`, `TouchChrome`) is always active on the web: an element with `onTouch(action)` gets an `onClick` that stops propagation and runs `action`. While the number box collects digits, one capture-phase listener in `main.tsx` takes every click first (the Kotlin taps call `closeEntry` one by one): with the pointer showing the click is OK and tunes the number, otherwise it puts the box away and goes on to its target. `TouchChrome` (Back and Play/Pause on the player, 76 px: LG's pointer targets are 75 px or more) shows while the pointer is visible (LG `cursorStateChange`) and in the browser, since the Magic Remote has no Play/Pause key; the player's top-left chips (Paused, Ad) move below it meanwhile. The wheel scrolls (`model.scroll`). Text entry is the Kotlin dialog path: a panel in the top third of the stage with a real `<input>` (the TV's keyboard opens on focus); Enter (and Tizen IME Done 65376) commits, except while the LG pointer shows (its click does the job), Back/Esc cancels, Tizen IME Cancel 65385 is ignored, and the input's key handler stops propagation.

**Video.** Both TVs decode one video at a time and draw it on a hardware plane. The guide preview and the watch player each own one `<video>` (native HLS, no hls.js on TVs) and release it on unmount and before every reload (`pause(); removeAttribute('src'); load()`). `attachStream` (shared) jumps a hole of up to 15 s before the first buffered frame until the video can play: Emby/Jellyfin streams start at the keyframe after the seek offset, which hls.js skips only up to 2 s. On the video itself, don't rely on `opacity`, `border-radius`/`overflow` clipping or `object-fit: cover`: show it with `visibility` once `playing` fires, and draw rounded corners as background-coloured corner masks on top. Audio and subtitles are chosen on the server (Plex `PUT /library/parts`, Emby/Jellyfin stream indexes); Emby/Jellyfin text subtitles are drawn by the app from the server's WebVTT (`Subtitles/{index}/Stream.vtt`, parsed by `core/WebVTT.ts`, shown by WatchScreen's `Subtitles`) and only image subtitles (PGS, DVD) burn in (no `ManifestSubtitles`), so there is no `textTracks`/`audioTracks` code. Every Emby/Jellyfin request names a subtitle index, -1 for none: with no index the server applies the account's remembered subtitle, which would burn in. Codecs are fixed per platform (`ClientDevice`): TVs `h264,hevc` / `aac,ac3,eac3`; the browser `h264` / `aac`. The browser build (development only) hands `.m3u8` streams to hls.js: desktop Chrome answers `canPlayType` 'maybe' for HLS and then fails the servers' streams.

**Channel 99.** No shader and no warble on LG and Samsung: the scrambled picture is `assets/scramble.gif` in an `<img>` (it doesn't use the video decoder), mounted only while visible. The GIF is the Metal shader's maths over the colour bars, a seamless 10-second loop at 640×360, scaled up. Regenerate it with `node web/tools/scramble-gif.mjs` (needs ffmpeg). The Fire TV build uses the same file.

**Sounds.** The station sting is synthesized with Web Audio (`AudioBuffer`, the Kotlin `StationSting` maths), played from a key press so autoplay rules allow it, and stopped on `visibilitychange`.

**Platform services** (`app/Platform.ts`, the only file that touches `webOS`, `webOSSystem`, `PalmSystem`, `tizen`, `webapis`):
- `kind`: `'webos' | 'tizen' | 'browser'`; `isTV` is always true (the TV copy and layout everywhere); `device`: `LG TV` / `Samsung TV` / `TV`; `verb` "Press"; `select` "OK" (LG) / "Select" (Samsung, browser).
- `exitApp()`, `registerKeys()` (Samsung media, channel and number keys), `setScreenSaver(on)` (Samsung: off while video plays or channel 99 shows), `pointerVisible` (LG).
- Persistence: `localStorage` for the `persisted` JSON, the device id and the token (`Keychain.ts`); IndexedDB for the library cache (`library.json` is several MB). LG clears both when a store update installs (ponytail: move to DB8 if that bites).
- No LAN discovery: web apps can't send UDP. `startDiscovery` finds nothing and never shows the "Looking around" row; Plex and Emby link by PIN, Jellyfin by typed address.

**Debug (browser build only).** URL query parameters mirror the Swift launch arguments and Kotlin intent extras: `?tuneOverride=2&themeOverride=midnight&attractMinutesOverride=1&spotOverride=1&serversOverride=3`. `?keys=down,down,select,wait:1500,right` replays remote commands after launch (for screenshots). A `seed.json` next to the page (`{"persisted": {…}, "token": "…"}`, the Android format; `web/seed.json` is git-ignored) is imported once per distinct file, like `MainActivity.importTestSeed` (a served file can't be deleted).

## Build and run

Needs Node 20 or later. Everything runs from `web/`:

```bash
npm install
npm run dev        # browser build, rebuilt on change, served at http://127.0.0.1:8080/
npm test           # core tests, including the Swift parity check
npm run typecheck
npm run build      # dist/webos, dist/tizen, dist/browser
```

The dev server is plain http on `127.0.0.1` because Plex Media Server allows token-less CORS only from `localhost`/`127.0.0.1` origins. Arrows, Enter, Esc/Backspace and Space stand in for the remote, and the debug parameters above work in the URL, for example `http://127.0.0.1:8080/?themeOverride=midnight&tuneOverride=2` or `?keys=down,down,select`. The LG tools (`ares-*`) come with `npm install` (`@webos-tools/cli`); run them with `npx`.

## LG (webOS)

There is no simulator for webOS 5 (Chromium 68, the floor); LG's simulators start at webOS 6 (Chromium 79) and the old VirtualBox emulator is x86 only, so test on a real 2020 TV.

1. On the TV, install **Developer Mode** from the LG Content Store, sign in with an LG developer account (webostv.developer.lge.com), turn on **Dev Mode Status** (the TV restarts), then turn on **Key Server**.
2. Add the TV and fetch its key (the passphrase is the 6 characters on the TV's Developer Mode screen, case-sensitive):
   ```bash
   npx ares-setup-device --add tv -i "host=TV_IP" -i "port=9922" -i "username=prisoner"
   npx ares-novacom --device tv --getkey
   npx ares-device --system-info -d tv      # checks the connection
   ```
3. Package, install, launch and inspect:
   ```bash
   npm run package:webos                    # dist/com.rerunguide.app_1.0.0_all.ipk
   npx ares-install -d tv dist/com.rerunguide.app_1.0.0_all.ipk
   npx ares-launch -d tv com.rerunguide.app
   npx ares-inspect -d tv --app com.rerunguide.app --open   # Web Inspector in a Chromium browser
   ```

A Developer Mode session lasts up to 1000 hours. Extend it with **EXTEND** in the Developer Mode app or `npx ares-launch -d tv com.palmdts.devmode -p extend=true`; when it lapses (or after 10 restarts without network) the TV uninstalls the dev apps. `platform/webos/appinfo.json` sets `disableBackHistoryAPI` (the app gets Back as key 461) and `iconColor` (the Late Show background).

## Samsung (Tizen)

Samsung's Tizen Studio is x64 only and its TV emulator doesn't run on Apple Silicon, so test on a real TV.

1. Install Tizen Studio 6.1 (the CLI installer is enough; it runs under Rosetta) and, from its Package Manager, the **TV Extension** and **Samsung Certificate Extension** 2.0.73 or later (older versions can't create certificates since September 2025). Or use Docker with `--platform linux/amd64` (for example `vitalets/docker-tizen-webos-sdk`, which also has the `ares-*` tools). Put the tools on PATH:
   ```bash
   export PATH="$HOME/tizen-studio/tools/ide/bin:$HOME/tizen-studio/tools:$PATH"
   ```
2. On the TV, open **Apps**, type **12345** (remote or on-screen keypad), turn **Developer mode** on, enter your Mac's IP, and restart the TV.
3. Connect: `sdb connect TV_IP`, then `sdb devices` shows the device name.
4. In Tizen Studio's **Certificate Manager**, create a **Samsung** certificate (not a Tizen one: TVs reject those with `install failed[118, -12]`), signing in with a Samsung account. The distributor certificate lists the TV's DUID (read over `sdb`, or Menu › Support › Contact Samsung › Unique Device ID). Keep the author certificate and its password forever: every update must be signed with it. The result is a **security profile**.
5. Package and install:
   ```bash
   TIZEN_PROFILE=<profile> npm run package:tizen   # dist/RerunGuide.wgt
   tizen install-permit -t <device>                # once per TV, after connecting
   cd dist && tizen install -n RerunGuide.wgt -t <device>
   tizen run -p rerunguide.RerunGuide -t <device>
   sdb shell 0 debug rerunguide.RerunGuide         # prints a port: open http://TV_IP:<port> in Chrome
   ```
   `install failed[118, -11]` means another author certificate signed the installed copy: `tizen uninstall -p rerunguide.RerunGuide -t <device>` first. Reinstalling wipes `localStorage`.

`platform/tizen/config.xml`: the package id `rerunguide` and application id `rerunguide.RerunGuide` never change once published; `required_version` 5.5 is the 2020 TVs; `tv.inputdevice` is for the media, channel and number keys.
