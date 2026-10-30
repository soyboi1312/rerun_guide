import Foundation
import Network
import Observation
import RerunGuideCore

// MARK: - Persisted types

enum Screen: String { case welcome, address, link, login, libraries, building, profiles, guide, watch, channels, settings, search }

/// `makeChannel`: Search's page for a result that isn't on in the next 24 hours, or a film's people to make a channel of.
enum Overlay: Equatable { case options, info(ProgramKey), newChannel, makeChannel }

/// Identifies one guide cell: a channel and the slot's exact start.
struct ProgramKey: Hashable { let channelID: String; let start: Date }

struct Profile: Identifiable, Codable, Hashable {
    var id: String
    var name: String
    var colorHex: String
    var theme: ThemeID
    var hiddenChannelIDs: Set<String>
    var initial: String { String(name.prefix(1)) }
}

/// Which channels the guide shows.
enum GuideFilter: String, Codable { case all, movies, tv }

/// Hand-written decoders here and on `Persisted`: synthesized Decodable throws on a missing key, so a build that adds a
/// property would reset every profile, edit and the server link. Missing keys keep the defaults declared above.
struct Settings: Codable {
    var crtLines = true
    var autoScroll = true
    var idleSeconds = 8          // 5 / 8 / 15 / 30
    var tuneMode = TuneMode.live
    var trailerBreaks = true
    /// Streaming quality cap in kbit/s; 0 = original. Applied by the clients (Plex maxVideoBitrate, Jellyfin MaxStreamingBitrate).
    var maxBitrateKbps = 0
    /// Preferred audio language (ISO 639-2, "spa"); nil = whatever the server marks default.
    var audioLanguage: String? = nil
    /// Station idents: the 1.5 s channel card + sting on channel change and before trailer breaks.
    var stationIdents = true
    /// Attract mode: after this many minutes idle on the drifting guide the screen becomes a slow storefront wall; 0 = off.
    var attractMinutes = 3
    /// Subtitles by default: "off" (none), "forced" (only the program's forced track, the way broadcasters do), "on".
    var subtitleMode = "forced"
    /// Which language to show when subtitles are on; nil = the device language, else the first subtitle track.
    var subtitleLanguage: String? = nil
    /// What attract mode shows: "wall" (the storefront wall), "logo" (the bouncing Rerun Guide logo) or "soyboi" (the mascot).
    var screensaver = "wall"
    /// One trailer break in 15 opens with the bundled Soyboi spot.
    var houseSpots = true

    init() {}
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        crtLines = try c.decodeIfPresent(Bool.self, forKey: .crtLines) ?? crtLines
        autoScroll = try c.decodeIfPresent(Bool.self, forKey: .autoScroll) ?? autoScroll
        idleSeconds = try c.decodeIfPresent(Int.self, forKey: .idleSeconds) ?? idleSeconds
        tuneMode = try c.decodeIfPresent(TuneMode.self, forKey: .tuneMode) ?? tuneMode
        trailerBreaks = try c.decodeIfPresent(Bool.self, forKey: .trailerBreaks) ?? trailerBreaks
        maxBitrateKbps = try c.decodeIfPresent(Int.self, forKey: .maxBitrateKbps) ?? maxBitrateKbps
        audioLanguage = try c.decodeIfPresent(String.self, forKey: .audioLanguage)
        stationIdents = try c.decodeIfPresent(Bool.self, forKey: .stationIdents) ?? stationIdents
        attractMinutes = try c.decodeIfPresent(Int.self, forKey: .attractMinutes) ?? attractMinutes
        subtitleMode = try c.decodeIfPresent(String.self, forKey: .subtitleMode) ?? subtitleMode
        if subtitleMode == "auto" { subtitleMode = "forced" }   // earlier builds stored the mode as "auto"
        subtitleLanguage = try c.decodeIfPresent(String.self, forKey: .subtitleLanguage)
        screensaver = try c.decodeIfPresent(String.self, forKey: .screensaver) ?? screensaver
        houseSpots = try c.decodeIfPresent(Bool.self, forKey: .houseSpots) ?? houseSpots
    }
}

/// Per-channel overrides the viewer made to an automatic channel. Custom channels store everything in their definition.
struct ChannelEdit: Codable, Hashable {
    var name: String?
    var number: Int?
    var order: PlayOrder?
    var tuneMode: TuneMode?
    var trailerBreaks: Bool?
}

struct ServerConfig: Codable {
    var kind: ServerKind
    var baseURL: URL
    var name: String
    var userID: String?    // Emby/Jellyfin
    /// Plex: every address plex.tv listed (a typed one first) and the server's machineIdentifier, so iPhone / iPad / Mac
    /// can switch between the LAN and public addresses as they move (`AppModel.pickPlexAddress`; a plain-http one only
    /// on the server's own network). nil for older links.
    var addresses: [PlexAddress]?
    var machineID: String?

    /// Which server and account a cached library belongs to (`LibraryCache`): the Plex machine, or the address, plus the user.
    var link: String { "\(kind.rawValue)|\(machineID ?? baseURL.absoluteString)|\(userID ?? "")" }

    func makeServer(token: String, deviceID: String, maxBitrateKbps: Int = 0) -> any MediaServer {
        switch kind {
        case .plex: return PlexClient(baseURL: baseURL, token: token, clientID: deviceID, serverName: name, maxBitrateKbps: maxBitrateKbps)
        case .emby, .jellyfin: return JellyfinClient(baseURL: baseURL, token: token, userID: userID ?? "", deviceID: deviceID, kind: kind, serverName: name, maxBitrateKbps: maxBitrateKbps)
        case .demo: return DemoMediaServer()
        }
    }
}

/// Any property added to the synthesized-Decodable types inside (Profile, ServerConfig, ChannelDefinition) must be
/// Optional, or get the same decodeIfPresent treatment.
struct Persisted: Codable {
    var profiles: [Profile] = [Profile(id: "p1", name: "You", colorHex: "#f0a33a", theme: .late, hiddenChannelIDs: [])]
    var currentProfileID = "p1"
    var settings = Settings()
    var channelEdits: [String: ChannelEdit] = [:]
    var customChannels: [ChannelDefinition] = []
    /// nil = every library.
    var selectedLibraryIDs: [String]? = nil
    var serverConfig: ServerConfig? = nil
    var lastChannelID: String? = nil
    var guideFilter: GuideFilter = .all

    init() {}
    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        profiles = try c.decodeIfPresent([Profile].self, forKey: .profiles) ?? profiles
        currentProfileID = try c.decodeIfPresent(String.self, forKey: .currentProfileID) ?? currentProfileID
        settings = try c.decodeIfPresent(Settings.self, forKey: .settings) ?? settings
        channelEdits = try c.decodeIfPresent([String: ChannelEdit].self, forKey: .channelEdits) ?? channelEdits
        customChannels = try c.decodeIfPresent([ChannelDefinition].self, forKey: .customChannels) ?? customChannels
        selectedLibraryIDs = try c.decodeIfPresent([String].self, forKey: .selectedLibraryIDs)
        serverConfig = try c.decodeIfPresent(ServerConfig.self, forKey: .serverConfig)
        lastChannelID = try c.decodeIfPresent(String.self, forKey: .lastChannelID)
        guideFilter = try c.decodeIfPresent(GuideFilter.self, forKey: .guideFilter) ?? guideFilter
    }
}

/// What's on the screen while watching. Mirrors the prototype's `w`.
struct Playback: Equatable {
    enum Mode { case live, start }
    var channelID: String
    var mode: Mode
    /// For `.start`: the slot being replayed and the player position bookkeeping.
    var programStart: Date
    var offset: TimeInterval = 0     // seconds into the program at `startedAt`
    var startedAt: Date = .now
    var paused = false
    /// The slot that was on when we tuned, kept until it ends so a library refresh (which reshuffles lineups) can't
    /// swap the program under the viewer. nil = derive from the schedule.
    var slot: Slot? = nil
    /// Per-program track override from the banner picker (or the subtitle fallback), for `itemID` only; nil or another
    /// item = resolve from Settings. Kept by a re-tune to the same program (Start over, Back to live); any other tune drops it.
    var tracks: TrackOverride? = nil
    struct TrackOverride: Equatable { let itemID: String; let selection: TrackSelection }
    /// `slot` was started from its beginning (`tune` in `.start`), so at 90% `watchTick` marks it watched on the server
    /// and clears this. Play/Pause and the 10 s skips keep it on the same slot, Home from live drops it (unless `markDue`),
    /// any tune makes a new playback.
    var fromStart = false
    /// `watchTick` saw 90% with no server: this slot's break may still report it once it's back.
    var markDue = false
}

struct Toast: Equatable { let message: String; let n: Int }

/// Scroll/focus bookkeeping for the guide's drifting grid. Not observed: it changes every frame while drifting.
struct GuideScroll {
    var pos: CGFloat = 0        // current translateY of the row track, in points (remote held)
    var row = 0                 // focused row index on the doubled track
    var focusTime: Date = .now  // "focus time": start of the focused cell clamped to the window
    var resumeOffset: CGFloat = 0   // drift offset to resume from, 0 ..< half-track height
    var driftStart: Date = .now     // when the current drift began (offset = resumeOffset + elapsed × speed, mod half)
    var speed: CGFloat = 22         // pt/s, the handoff's default drift speed
}

// MARK: - Model

/// One object holds the whole app state; screens are pure functions of it and press handlers mutate it.
/// This file holds the shared core (ported from the prototype's Component). Screen-specific handlers live in `AppModel+*.swift`.
@MainActor
@Observable
final class AppModel {
    // navigation
    var screen: Screen = .welcome
    var overlay: Overlay? = nil
    /// Generic focus index for list-like screens (the prototype's `fi`).
    var focusIndex = 0
    /// RootView: the window is taller than wide on a screen with a portrait layout (`portraitReady`), drawn natively by
    /// App/Portrait instead of on the stage. Portrait never draws the TV banner, its track list or a held TV guide, so
    /// turning upright puts them away: hidden, they would take the next Back.
    var portrait = false { didSet { if portrait, !oldValue { hideBanner(); guideRelease() } } }
    /// Now playing's full-screen button (phones held upright): the TV player, which turns the phone sideways. Back, or
    /// leaving Now playing, ends it.
    var fullScreen = false

    // server + library
    var server: (any MediaServer)? = nil
    var persisted = Persisted()
    var library: [MediaItem] = [] { didSet { movies = library.filter { $0.kind == .movie }; searchIndex = nil } }
    /// The library's films, for trailer breaks on a channel without its own (`breakTrailerCandidate` runs per tick).
    @ObservationIgnored private var movies: [MediaItem] = []
    var librarySections: [LibrarySection] = []
    /// Every channel, sorted by number, hidden ones included.
    var channels: [Channel] = [] { didSet { dayCache = [:] } }
    /// Day schedules per (channel, day): the generator shuffles the whole pool per call, so the guide must not recompute
    /// it per row per tick. Cleared whenever `channels` changes.
    @ObservationIgnored var dayCache: [String: [Slot]] = [:]
    /// The automatic definitions from the last rebuild, so "Reset to automatic" needs no second library pass.
    @ObservationIgnored var autoDefinitions: [ChannelDefinition] = []
    /// Candidates for the New-channel picker, computed once when the kind is chosen (otherwise a 20k-item scan would run per press).
    var candidateCache: [ChannelBuilder.Candidate] = []
    @ObservationIgnored private var libraryGen = 0
    /// Items the open-the-app check (`checkRecentlyAdded`) found since the last full load. Only the "what's new" channels
    /// see them (`ChannelBuilder.isFresh`); the next full load folds them into `library`.
    @ObservationIgnored var freshItems: [MediaItem] = [] { didSet { searchIndex = nil } }
    @ObservationIgnored private var checkingRecent = false
    @ObservationIgnored private var rebuildGen = 0
    /// When the app last went looking for a Plex server that stopped answering (at most once a minute).
    @ObservationIgnored private var lastPlexRecovery: Date?
    #if !os(tvOS)
    /// Wi-Fi to cellular, another Wi-Fi, a Mac waking elsewhere: while the app is open, `pathChanged` picks the address again.
    @ObservationIgnored private let pathMonitor = NWPathMonitor()
    /// The network last reported ("" offline, nil before the first report, which is the one the app started on).
    @ObservationIgnored private var network: String?
    #endif
    /// Bumped by every address pick: one that finishes after a newer one started (Wi-Fi off then on) changes nothing.
    @ObservationIgnored private(set) var pickGen = 0
    /// A plain-http link's launch loads, held for the pick that revives its client (on whatever screen that happens).
    @ObservationIgnored private var launchLoad = false
    var libraryLoading = false
    var libraryError: String? = nil

    // onboarding
    var serviceKind: ServerKind = .plex
    var address = ""
    var username = ""
    var password = ""
    var linkCode: String? = nil
    var linked = false
    var authError: String? = nil
    var authBusy = false
    /// Servers found on the local network (LocalDiscovery, no token), or returned by a Plex PIN (with the server's token)
    /// or an Emby PIN (with the server's Emby Connect access key).
    var foundServers: [PlexServer] = []
    var discovering = false
    /// The account the link signed in, while its servers are listed: the plex.tv token, or the Emby Connect user id.
    var pendingToken: String? = nil
    /// Moved on by go() and by each sign-in step: a step still in flight when the viewer leaves (even for the same screen
    /// again) is dropped, result, error and busy flag alike.
    @ObservationIgnored var authSerial = 0
    var librarySelection: Set<String> = []
    var builtCount = 0

    // guide
    var focused: ProgramKey? = nil
    var remoteHeld = false
    var windowOffset = 0            // 0...maxWindowOffset, 30-min pages ahead
    @ObservationIgnored var guideScroll = GuideScroll()
    /// The launch's first guide with channels held still for `launchHold` (see `holdForLaunch`).
    @ObservationIgnored var launchHeld = false
    /// How long the first guide of a launch waits before it starts to drift, so the first channels can be read.
    static let launchHold: TimeInterval = 4

    // watch
    var playback: Playback? = nil
    var bannerVisible = false
    var bannerFocus = -1
    /// The banner's progress bar has the remote (Up from its buttons, while `bannerFocus` ≥ 0): Left / Right skip.
    var barFocused = false
    /// The banner's ⏪ (−1) or ⏩ (+1) above the playhead for a second after a skip; 0 when none.
    var skipCue = 0
    /// The banner's Audio or Subtitles list, open over its button.
    var trackMenu: TrackMenu? = nil
    /// Start over: the VHS rewind on screen, from where the program was (see `tune()`).
    var rewind: Rewind? = nil
    struct Rewind: Equatable { let from: TimeInterval; let started: Date }
    /// How long the rewind plays: its counter runs back for 1.1 s, holds, and fades out by then.
    static let rewindLength: TimeInterval = 1.4
    /// Attract mode is on screen (the guide has been drifting untouched for `settings.attractMinutes`).
    var attracting = false
    /// Bumped by `tune()` on every channel change, together with `flips`; the ident card keys off it. A start-over from the
    /// guide rewinds instead: no bump, and `flips` goes to 0 so the new watch screen has no static to show.
    var identSerial = 0
    /// Bumped on the way back from the Home screen: the guide's preview reloads on it (its stream was stopped).
    var foregroundSerial = 0
    @ObservationIgnored var backgrounded = false
    /// The player is in picture in picture (iPhone, iPad): away from the app but still playing, so not `backgrounded`.
    @ObservationIgnored var pip = false
    /// A started-over program that was playing when the app left: it was held at its position and plays on return.
    @ObservationIgnored var resumeAfterBackground = false
    /// Tracks of the program on screen, loaded by WatchScreen; drives the banner's Audio / Subtitles actions.
    var playbackTracks: [MediaTrack] = []
    var flips = 0
    /// Bumped by every `tune()`: the player reloads on it, so Play/Pause (which only changes `playback.mode`) does not.
    var tunes = 0

    // channel editor
    var channelSelection: String? = nil   // nil = "New channel" row
    var channelColumn = 0
    var channelFieldIndex = 0
    var numberDraft: String? = nil
    var newChannelStep = 0
    var newChannelType: ChannelBuilder.CandidateKind? = nil
    /// Non-nil while a text field owns the remote (server address, username, channel name…).
    var editingField: String? = nil

    /// Number keys on the guide or the player: the digits typed so far (AppModel+Watch). `missing`: the number had no
    /// channel, and the box says so for a moment.
    var channelEntry: ChannelEntry? = nil
    struct ChannelEntry: Equatable { var digits: String; var missing = false }
    /// What the box last said, which it fades out showing: a third digit tunes (and so closes it) in the same press.
    var lastEntry: ChannelEntry? = nil

    // search (Options › Search, AppModel+Search)
    var searchQuery = "" { didSet { searchResults = TitleSearch.search(searchQuery, in: titleIndex) } }
    var searchResults: [TitleSearch.Entry] = []
    /// The page's row: 0 the text field, i the result `searchResults[i - 1]`. Its own index, kept while an overlay over the
    /// page uses `focusIndex`.
    var searchFocus = 0
    /// When each entry airs next, worked out as the page opens and again on its clock (`refreshAirings`).
    var searchAirings: [String: TitleSearch.Airing] = [:]
    /// Make a channel from a film: its director(s) and lead actors with enough films, once asked for.
    var makePeople: [(kind: ChannelBuilder.CandidateKind, candidate: ChannelBuilder.Candidate)]? = nil
    /// The library's titles keyed for matching (`titleIndex`); dropped whenever the library changes.
    @ObservationIgnored var searchIndex: TitleSearch.Index? = nil

    var toast: Toast? = nil

    // Shared state for individual screens' flows (owned by the screen handlers that use it).
    /// Settings › Sign out needs a second press within a few seconds.
    var signOutArmed = false
    /// Where the Libraries screen returns to: nil = first-run onboarding (welcome), .settings = re-pick from Settings.
    var librariesOrigin: Screen? = nil
    /// Profiles screen: index of the tile whose Rename / Delete row is open, with `profileMenuIndex` as the focused action.
    var profileMenu: Int? = nil
    var profileMenuIndex = 0
    /// The in-flight library load started by the Building screen, so Back can cancel it.
    @ObservationIgnored var loadTask: Task<Void, Never>? = nil

    @ObservationIgnored private var timers: [String: Task<Void, Never>] = [:]

    /// In the Keychain, which stays out of backups (ThisDeviceOnly): a restored device must not share this one's Plex
    /// client ID and transcode sessions. Earlier builds kept it in UserDefaults; it moves over once.
    static let deviceID: String = {
        if let id = Keychain.get("deviceID") { return id }
        let id = UserDefaults.standard.string(forKey: "deviceID") ?? UUID().uuidString
        Keychain.set(id, for: "deviceID")
        if Keychain.get("deviceID") == id { UserDefaults.standard.removeObject(forKey: "deviceID") }   // only once it's saved
        return id
    }()

    init() {
        persisted = Persisted.load()
        #if DEBUG
        // Test hooks, debug builds only (simulator UI tests, and on-device repro via `devicectl … launch -tuneOverride N`):
        // `-tuneOverride N` tunes to channel N as soon as the lineup is built; `-spotOverride YES` opens with the spot.
        tuneOverride = UserDefaults.standard.object(forKey: "tuneOverride") == nil ? nil : UserDefaults.standard.integer(forKey: "tuneOverride")
        spotOverride = UserDefaults.standard.bool(forKey: "spotOverride")
        #endif
        #if targetEnvironment(simulator)
        // UI-test hook: `-themeOverride midnight` starts every profile in that theme (persisted on the next save).
        if let id = UserDefaults.standard.string(forKey: "themeOverride").flatMap(ThemeID.init(rawValue:)) {
            for i in persisted.profiles.indices { persisted.profiles[i].theme = id }
        }
        // UI-test hook: `-serversOverride N` opens the Plex server list with N stand-in servers (never persisted).
        let standIns = UserDefaults.standard.integer(forKey: "serversOverride")
        if standIns > 0 {
            serviceKind = .plex; pendingToken = "ui-test"
            foundServers = (1...standIns).map { PlexServer(id: "sim-\($0)", name: "Server \($0)", baseURL: URL(string: "http://192.168.1.\($0):32400")!, accessToken: "ui-test") }
            address = foundServers[0].baseURL.absoluteString
            screen = .address; focusIndex = 1
            return
        }
        #else
        // Keychain items outlive deleting the app: with no saved link, a reinstall drops the old tokens.
        if persisted.serverConfig == nil { Keychain.delete("token"); Keychain.delete("plexAccount") }
        #endif
        rebuildServer()
        if let cfg = persisted.serverConfig, server != nil {
            screen = .guide
            libraryLoading = true   // the cache decodes off the main actor; no "Nothing to schedule" flash before it lands
            // A plain-http link gets no client until the pick checks its address on this network (the pick's revived branch
            // builds it and loads the guide), so nothing sends the token unchecked: not a stop on the way out, not a pick a
            // newer one replaced.
            let http = plainHTTPLink
            if http { server = nil; launchLoad = true }
            Task {
                #if !os(tvOS)
                if cfg.addresses != nil {
                    // A plain-http link picks first: the cached guide's artwork would carry the token there in the clear.
                    // With nothing verified to move to, the server stays dropped until a later pick verifies an address.
                    if cfg.baseURL.scheme != "http" { await loadLibrary(refresh: false) }   // the cache shows meanwhile
                    await pickPlexAddress()
                }
                #endif
                // A fresh cache skipped the full load; the new channels still catch up. Plain http: the reviving pick runs them.
                if !http { await loadLibrary(); await checkRecentlyAdded() }
            }
        }
        #if !os(tvOS)
        pathMonitor.pathUpdateHandler = { [weak self] path in
            // Which network, not just whether one: the monitor also reports DNS, IPv6 or a VPN changing on the same one.
            let id = path.status == .satisfied ? "\(path.availableInterfaces.first?.name ?? "")|\(path.gateways)" : nil
            Task { @MainActor in self?.pathChanged(network: id) }
        }
        pathMonitor.start(queue: .main)
        #endif
    }

    // MARK: Derived

    var profile: Profile { persisted.profiles.first { $0.id == persisted.currentProfileID } ?? persisted.profiles[0] }
    var theme: Theme { profile.theme.theme }

    /// Options and Settings both offer the theme; it lives on the profile, so each viewer keeps their own.
    func setTheme(_ id: ThemeID) {
        guard id != profile.theme else { return }
        updateProfile { $0.theme = id }
        if screen == .guide { guideReset() }   // the row pitch changes with the theme, so the drift offset is re-based
    }
    func toggleTheme() { setTheme(profile.theme == .late ? .midnight : .late) }
    var settings: Settings { get { persisted.settings } set { persisted.settings = newValue; save() } }
    var serverLabel: String { "\(server?.serverName ?? "") · \(server?.kind.displayName ?? "")" }

    func isHidden(_ channelID: String) -> Bool { profile.hiddenChannelIDs.contains(channelID) }
    func channel(_ id: String) -> Channel? { channels.first { $0.id == id } }
    /// Channels in the lineup: not hidden for this profile, and in the current guide (All / Movies / TV Shows).
    var visibleChannels: [Channel] {
        channels.filter { !isHidden($0.id) && (guideFilter == .all || (guideFilter == .movies) == $0.isMovies) }
    }
    var guideFilter: GuideFilter { persisted.guideFilter }
    /// Switch guides (Options › Guide / Movies / TV Shows): restarts the drift from the top of the new lineup.
    func setGuideFilter(_ f: GuideFilter) {
        persisted.guideFilter = f; save()
        guideReset()
        overlay = nil; focusIndex = 0
        if screen != .guide { go(.guide) }
    }

    /// Guide row pitch and viewport: the handoff's PITCH / VIEW per theme (Late Show 100 / 424, Midnight Movie 108 / 432).
    var rowPitch: CGFloat { theme.id == .midnight ? 108 : 100 }
    var gridHeight: CGFloat { theme.id == .midnight ? 432 : 424 }

    /// Guide window start: now floored to the half hour, plus `offset` pages.
    func windowStart(at now: Date, offset: Int? = nil) -> Date {
        let block = GuideGenerator.blockLength
        let floored = Date(timeIntervalSinceReferenceDate: (now.timeIntervalSinceReferenceDate / block).rounded(.down) * block)
        return floored.addingTimeInterval(Double(offset ?? windowOffset) * block)
    }
    static let windowSpan: TimeInterval = 90 * 60
    /// How many 30-min pages Right can move the window ahead: the last one ends 12 hours past the current half hour.
    static let maxWindowOffset = 21

    /// One channel's lineup for the day containing `day`, memoized.
    func daySlots(_ channel: Channel, _ day: Date) -> [Slot] {
        let d = Calendar.current.startOfDay(for: day)
        let k = "\(channel.id)|\(Int(d.timeIntervalSinceReferenceDate))"
        if let cached = dayCache[k] { return cached }
        let s = GuideGenerator.schedule(for: channel, day: d)
        dayCache[k] = s
        return s
    }
    /// Slots intersecting [from, to], spanning days; memoized per day.
    func slots(_ channel: Channel, from: Date, to: Date) -> [Slot] {
        var out: [Slot] = []
        var day = Calendar.current.startOfDay(for: from)
        while day < to {
            out += daySlots(channel, day)
            day = Calendar.current.date(byAdding: .day, value: 1, to: day)!
        }
        return out.filter { $0.end > from && $0.start < to }
    }
    func slotOnAir(_ channel: Channel, at time: Date) -> Slot? { daySlots(channel, time).first { $0.start <= time && time < $0.end } }
    func slot(_ channel: Channel, startingAt start: Date) -> Slot? { daySlots(channel, start).first { abs($0.start.timeIntervalSince(start)) < 1 } }
    func status(_ slot: Slot, at time: Date) -> ProgramStatus { GuideGenerator.status(of: slot, at: time) }
    func program(_ key: ProgramKey) -> (channel: Channel, slot: Slot)? {
        guard let ch = channel(key.channelID), let slot = slot(ch, startingAt: key.start) else { return nil }
        return (ch, slot)
    }
    func key(_ channel: Channel, _ slot: Slot) -> ProgramKey { ProgramKey(channelID: channel.id, start: slot.start) }

    // MARK: Timers / toast

    func later(_ key: String, seconds: Double, _ action: @escaping @MainActor () -> Void) {
        timers[key]?.cancel()
        timers[key] = Task { @MainActor in
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled else { return }
            action()
        }
    }
    func cancelTimer(_ key: String) { timers[key]?.cancel(); timers[key] = nil }

    func toast(_ message: String) {
        toast = Toast(message: message, n: (toast?.n ?? 0) + 1)
        later("toast", seconds: 3.2) { [weak self] in self?.toast = nil }
    }

    // MARK: Navigation

    func go(_ to: Screen) {
        if screen == .guide && to != .guide { guidePark() }
        if screen == .link && to != .link { stopLink() }   // a PIN / Quick Connect poll never outlives its screen
        if screen == .login && to != .login { password = "" }   // typed passwords don't wait around in memory
        authSerial += 1                                           // a sign-in step in flight belongs to the screen being left
        if screen == .address || screen == .login || screen == .libraries { authBusy = false }
        cancelTimer("banner"); closeEntry()
        screen = to; overlay = nil; focusIndex = 0; editingField = nil; fullScreen = false
        if to == .link { startLink() }
        if to == .building { startBuild() }
        if to == .address && foundServers.isEmpty { startDiscovery() }
    }

    func closeOverlay() {
        overlay = nil; focusIndex = 0
        // Portrait has no held guide: one held in landscape before the overlay opened is let go (it would take Back).
        if screen == .guide && remoteHeld { if portrait { guideRelease() } else { guideBump() } }
    }

    func openOptions() { cancelTimer("idle"); closeEntry(); overlay = .options; focusIndex = 1 }
    func openInfo(_ key: ProgramKey) { cancelTimer("idle"); cancelTimer("banner"); closeEntry(); overlay = .info(key); focusIndex = 0 }

    /// Dispatch a remote press to whichever overlay/screen owns the remote right now.
    func press(_ cmd: RemoteCommand) {
        if overlay == nil, pressEntry(cmd) { return }
        switch overlay {
        case .options: return pressOptions(cmd)
        case .info: return pressInfo(cmd)
        case .newChannel: return pressNewChannel(cmd)
        case .makeChannel: return pressMakeChannel(cmd)
        case nil: break
        }
        switch screen {
        case .welcome: pressWelcome(cmd)
        case .address: pressAddress(cmd)
        case .link: pressLink(cmd)
        case .login: pressLogin(cmd)
        case .libraries: pressLibraries(cmd)
        case .building: pressBuilding(cmd)
        case .profiles: pressProfiles(cmd)
        case .guide: pressGuide(cmd)
        case .watch: pressWatch(cmd)
        case .channels: pressChannels(cmd)
        case .settings: pressSettings(cmd)
        case .search: pressSearch(cmd)
        }
    }

    /// Move `focusIndex` within 0..<count for up/down (or left/right when horizontal). Returns true if consumed.
    func step(_ cmd: RemoteCommand, count: Int, horizontal: Bool = false) -> Bool {
        let dec: RemoteCommand = horizontal ? .left : .up, inc: RemoteCommand = horizontal ? .right : .down
        guard cmd == dec || cmd == inc else { return false }
        focusIndex = max(0, min(count - 1, focusIndex + (cmd == inc ? 1 : -1)))
        return true
    }

    func updateProfile(_ change: (inout Profile) -> Void) {
        guard let i = persisted.profiles.firstIndex(where: { $0.id == persisted.currentProfileID }) else { return }
        change(&persisted.profiles[i]); save()
    }

    func switchProfile(_ id: String) {
        if screen == .guide { guideReset() }
        persisted.currentProfileID = id; save()
    }

    // MARK: Guide core (shared with watch/overlays; the drift itself lives in AppModel+Guide)

    /// Leaving the guide (or letting go of the remote, `guideRelease`): remember where the drift was so it resumes from
    /// there. A held focus snaps to what's on now on its channel unless it's in the first window, the one the guide comes
    /// back to (a focus paged ahead would describe a program hours away, with no cell lit).
    func guidePark() {
        cancelTimer("idle")
        guideScroll.resumeOffset = remoteHeld ? wrap(guideScroll.pos) : guideDriftOffset()
        let now = Date.now, ws0 = windowStart(at: now, offset: 0)
        if remoteHeld, let k = guideKey(at: now), let pr = program(k) {
            focused = pr.slot.start < ws0 + Self.windowSpan && pr.slot.end > ws0 ? k : cellAt(pr.channel, time: now, windowStart: ws0)
        }
        remoteHeld = false; windowOffset = 0
    }

    /// Profile change: start the guide over from the top.
    func guideReset() {
        cancelTimer("idle")
        guideScroll = GuideScroll()
        remoteHeld = false; windowOffset = 0; focused = nil
    }

    /// The cell on `channel` nearest `time` inside the window starting at `ws`.
    func cellAt(_ channel: Channel, time: Date, windowStart ws: Date) -> ProgramKey? {
        let we = ws.addingTimeInterval(Self.windowSpan)
        let vis = slots(channel, from: ws, to: we)
        let pick = vis.first { $0.start <= time && time < $0.end }
            ?? vis.min { abs($0.start.timeIntervalSince(time)) < abs($1.start.timeIntervalSince(time)) }
            ?? slotOnAir(channel, at: time)
        return pick.map { key(channel, $0) }
    }

    /// The focused cell, falling back to what's airing now on the first visible channel.
    func guideKey(at now: Date = .now) -> ProgramKey? {
        if let k = focused, let p = program(k), !isHidden(p.channel.id) {
            if remoteHeld || p.slot.end > now { return k }
            // Drifting and the focused program is over: follow the channel to what's on now (no mutation: this runs in view bodies).
            if let k2 = cellAt(p.channel, time: now, windowStart: windowStart(at: now, offset: 0)) { return k2 }
        }
        guard let ch = visibleChannels.first else { return nil }
        return cellAt(ch, time: now, windowStart: windowStart(at: now, offset: 0))
    }

    // MARK: Tune / watch core

    /// Start watching `channelID`. `.live` joins in progress (unless the setting says start over and `force` is false);
    /// `.start` plays the slot beginning at `programStart` from its beginning.
    func tune(_ channelID: String, mode: Playback.Mode, programStart: Date? = nil, force: Bool = false) {
        guard let ch = channel(channelID) else { return }
        let now = Date.now
        let onAir = slotOnAir(ch, at: now)
        var pb: Playback
        if mode == .start, let ps = programStart { pb = Playback(channelID: channelID, mode: .start, programStart: ps); pb.slot = slot(ch, startingAt: ps) }
        else if (ch.definition.tuneMode ?? settings.tuneMode) == .start && !force, let s = onAir { pb = Playback(channelID: channelID, mode: .start, programStart: s.start); pb.slot = s }
        else { pb = Playback(channelID: channelID, mode: .live, programStart: onAir?.start ?? now); pb.slot = onAir }
        pb.tracks = playback?.tracks.flatMap { $0.itemID == pb.slot?.item.id ? $0 : nil }   // Start over / Back to live keep it; another program drops it
        pb.fromStart = pb.mode == .start   // both .start cases above play from the slot's beginning
        if screen == .guide { guidePark() }
        cancelTimer("seek")   // a pending skip must not reload the channel we just left
        closeEntry()          // nor a typed number tune over a tap's channel
        let same = screen == .watch && playback?.channelID == channelID
        // Start over (the banner's, a break's "Watch again", Program info's, the guide's Play): the tape rewinds instead of
        // the static and the ident card, from where the program on screen was, or from the guide, from where its broadcast
        // is (none before it airs).
        if same, mode == .start, let was = watchInfo(), was.slot.start == programStart, was.position >= 1 {
            rewind = Rewind(from: min(was.position, was.slot.item.runtime), started: now)
        } else if !same, mode == .start, let ps = programStart, let s = pb.slot, now.timeIntervalSince(ps) >= 1 {
            rewind = Rewind(from: min(now.timeIntervalSince(ps), s.item.runtime), started: now)
        } else { rewind = nil }
        if rewind != nil { later("rewind", seconds: Self.rewindLength) { [weak self] in self?.rewind = nil } }
        if let prev = playback?.channelID, prev != channelID { persisted.lastChannelID = prev; save() }
        screen = .watch; overlay = nil; focusIndex = 0
        playback = pb
        tunes += 1
        bannerVisible = watchInfo()?.isBreak != true; bannerFocus = -1; trackMenu = nil   // in a break the card says it all; the banner would cover it
        if !same { if rewind == nil { flips += 1; identSerial += 1 } else { flips = 0 } }
        bannerLater()
    }

    /// The selection to request for item `itemID`: the per-program override if the banner set one for that item, else
    /// the Settings preferences matched against the item's tracks (see `TrackSelection.preferred`). A later program on
    /// the same channel never inherits the override (its stream indices belong to another file).
    func trackSelection(for tracks: [MediaTrack], itemID: String) -> TrackSelection {
        if let t = playback?.tracks, t.itemID == itemID { return t.selection }
        return .preferred(for: tracks, server: server?.kind, audioLanguage: settings.audioLanguage, subtitleMode: settings.subtitleMode,
                          subtitleLanguage: settings.subtitleLanguage ?? Self.deviceLanguage, deviceLanguage: Self.deviceLanguage)
    }

    /// The device's language as the ISO 639-2 code servers use ("eng", "spa").
    static var deviceLanguage: String {
        Locale.current.language.languageCode?.identifier(.alpha3) ?? "eng"
    }

    var lastChannel: Channel? {
        guard let id = persisted.lastChannelID, let ch = channel(id), !isHidden(id) else { return nil }
        return ch
    }

    /// Back to the guide, drifting from the channel that was on.
    func toGuide(from channelID: String) {
        let list = visibleChannels
        if let i = list.firstIndex(where: { $0.id == channelID }) {
            guideScroll.resumeOffset = wrap(CGFloat(i - 1) * rowPitch)
            focused = cellAt(list[i], time: .now, windowStart: windowStart(at: .now, offset: 0))
        } else { focused = nil }
        cancelTimer("banner"); closeEntry()
        screen = .guide; overlay = nil; focusIndex = 0; editingField = nil; fullScreen = false
        windowOffset = 0; remoteHeld = false; bannerVisible = false; bannerFocus = -1; trackMenu = nil; rewind = nil
    }

    // MARK: Channels

    /// Debug-build launch argument `-tuneOverride N`; cleared once used.
    var tuneOverride: Int? = nil
    /// Debug-build launch argument `-spotOverride`: the next load opens with the spot, break or not.
    var spotOverride = false
    func applyTuneOverride() {
        guard let n = tuneOverride, screen == .guide, let ch = channels.first(where: { $0.number == n }) else { return }
        tuneOverride = nil; tune(ch.id, mode: .live)
    }

    /// Automatic channels from the library + user edits + custom channels, resolved off the main actor (a library
    /// refresh evaluates every rule chip against thousands of items) and sorted by number.
    func rebuildChannelsAsync() async {
        rebuildGen += 1; let gen = rebuildGen   // a newer rebuild (a full load landing mid-way) must not be overwritten
        var r = await Self.resolveChannels(library, fresh: freshItems, edits: persisted.channelEdits, custom: persisted.customChannels)
        guard gen == rebuildGen else { return }
        if migrateGenreIDs(r) { r = await Self.resolveChannels(library, fresh: freshItems, edits: persisted.channelEdits, custom: persisted.customChannels) }
        guard gen == rebuildGen else { return }
        autoDefinitions = r.auto; channels = r.channels
        if screen == .guide { holdForLaunch() }   // the lineup came in under the guide's loader
    }

    /// Carry hidden flags and edits across a genre shelf's split into films + TV or merge back (GenreIDMigration).
    /// Returns true when an edit moved, so the lineup must re-resolve.
    func migrateGenreIDs(_ r: (auto: [ChannelDefinition], channels: [Channel])) -> Bool {
        var hidden = persisted.profiles.map(\.hiddenChannelIDs), edits = persisted.channelEdits
        let sizes = Dictionary(r.channels.map { ($0.id, $0.items.count) }, uniquingKeysWith: max)
        let m = GenreIDMigration.migrate(currentIDs: r.auto.map(\.id), sizes: sizes, hidden: &hidden, edits: &edits)
        guard m.changed else { return false }
        for i in persisted.profiles.indices { persisted.profiles[i].hiddenChannelIDs = hidden[i] }
        persisted.channelEdits = edits; save()
        return m.editMoved
    }

    /// nonisolated async ⇒ generic executor in Swift 6.0 mode (SE-0338); mark @concurrent if NonisolatedNonsendingByDefault is ever enabled.
    nonisolated static func resolveChannels(_ library: [MediaItem], fresh: [MediaItem] = [], edits: [String: ChannelEdit], custom: [ChannelDefinition]) async -> (auto: [ChannelDefinition], channels: [Channel]) {
        resolve(library, fresh: fresh, edits: edits, custom: custom)
    }

    /// The lineup (which automatic channels exist, and their numbers) comes from `library` alone, so newly found items never
    /// renumber it; `fresh` only adds to the "what's new" channels.
    nonisolated static func resolve(_ library: [MediaItem], fresh: [MediaItem] = [], edits: [String: ChannelEdit], custom: [ChannelDefinition]) -> (auto: [ChannelDefinition], channels: [Channel]) {
        let auto = ChannelBuilder.automaticChannels(from: library)
        var defs = auto
        for i in defs.indices {
            guard let e = edits[defs[i].id] else { continue }
            if let v = e.name { defs[i].name = v }
            if let v = e.number { defs[i].number = v }
            if let v = e.order { defs[i].order = v }
            if let v = e.trailerBreaks { defs[i].trailerBreaks = v }
            if let v = e.tuneMode { defs[i].tuneMode = v }
        }
        defs += custom
        var resolved = ChannelBuilder.channels(defs, in: library, fresh: fresh)
        if !library.isEmpty {   // channel 99 rides along with any real lineup; the editor's rename/renumber still apply
            var scr = Channel.scrambled
            if let e = edits[scr.id] { if let v = e.name { scr.definition.name = v }; if let v = e.number { scr.definition.number = v }; if let v = e.tuneMode { scr.definition.tuneMode = v } }
            resolved.append(scr)
        }
        return (auto, resolved.sorted { ($0.number, $0.id) < ($1.number, $1.id) })
    }

    /// Replace (or insert) one channel from its definition: one library filter instead of one per definition.
    private func splice(_ d: ChannelDefinition) {
        var next = channels.filter { $0.id != d.id }
        next += ChannelBuilder.channels([d], in: library, fresh: freshItems)
        next.sort { ($0.number, $0.id) < ($1.number, $1.id) }
        channels = next
    }

    /// Edit one field of a channel (automatic → recorded as an edit; custom → written into its definition).
    func updateChannel(_ id: String, _ change: (inout ChannelDefinition) -> Void) {
        if let i = persisted.customChannels.firstIndex(where: { $0.id == id }) {
            change(&persisted.customChannels[i])
        } else if let ch = channel(id) {
            var d = ch.definition; change(&d)
            var e = persisted.channelEdits[id] ?? ChannelEdit()
            e.name = d.name; e.number = d.number; e.order = d.order; e.trailerBreaks = d.trailerBreaks; e.tuneMode = d.tuneMode
            persisted.channelEdits[id] = e
            save(); splice(d); return
        }
        save()
        if let d = persisted.customChannels.first(where: { $0.id == id }) { splice(d) }
    }

    func resetChannel(_ id: String) {
        persisted.channelEdits[id] = nil; save()
        if let d = autoDefinitions.first(where: { $0.id == id }) { splice(d) }
    }
    func deleteCustomChannel(_ id: String) { persisted.customChannels.removeAll { $0.id == id }; save(); channels.removeAll { $0.id == id } }
    func addCustomChannel(_ d: ChannelDefinition) { persisted.customChannels.append(d); save(); splice(d) }
    func setHidden(_ id: String, _ hidden: Bool) {
        updateProfile { if hidden { $0.hiddenChannelIDs.insert(id) } else { $0.hiddenChannelIDs.remove(id) } }
    }
    /// The movie whose trailer fills a break on `channel` after `slot`. Deterministic (channel + slot start) so the guide's
    /// preview window and the player agree. Whether the server actually has a trailer for it is only known at playback.
    func breakTrailerCandidate(_ channel: Channel, slot: Slot) -> MediaItem? {
        var pool = channel.items.filter { $0.kind == .movie && $0.id != slot.item.id }
        if pool.isEmpty { pool = movies.filter { $0.id != slot.item.id } }
        guard !pool.isEmpty else { return nil }
        let h = StableHash.fnv1a("\(channel.id)|\(Int(slot.start.timeIntervalSince1970))")
        return pool[Int(h % UInt64(pool.count))]
    }

    /// Lowest unused channel number from 50 up (the prototype's rule for custom channels).
    var nextFreeNumber: Int { let used = Set(channels.map(\.number)); var n = 50; while used.contains(n) { n += 1 }; return n }

    // MARK: Server / library

    func connect(_ server: any MediaServer, config: ServerConfig, token: String) {
        username = ""; password = ""   // a shared TV mustn't offer the next person this sign-in
        launchLoad = false   // the new link builds its own library
        Keychain.set(token, for: "token")
        persisted.serverConfig = config; save()
        libraryGen += 1; freshItems = []
        self.server = server
    }

    /// Re-create the server client from the saved config (after a settings change the clients read, e.g. quality).
    func rebuildServer() {
        guard let cfg = persisted.serverConfig, let token = Keychain.get("token") ?? (cfg.kind == .demo ? "" : nil) else { return }
        server = cfg.makeServer(token: token, deviceID: Self.deviceID, maxBitrateKbps: settings.maxBitrateKbps)
    }

    /// The saved Plex address stopped answering: a new DHCP lease, a moved Apple TV, a new public IP. Try the other
    /// addresses plex.tv listed at sign-in (`probeable`), then ask plex.tv afresh with the account token, and switch to
    /// whichever answers as this server (its /identity must match the machineIdentifier). Returns true when the address
    /// changed, so the caller can retry once. At most one search a minute, so a server that is simply off isn't hammered.
    func recoverPlexAddress() async -> Bool {
        guard let cfg = persisted.serverConfig, cfg.kind == .plex, let id = cfg.machineID,
              (lastPlexRecovery.map { Date.now.timeIntervalSince($0) > 60 } ?? true) else { return false }
        lastPlexRecovery = .now
        var url: URL?, addresses = cfg.addresses, token: String?
        if let a = cfg.addresses, a.count > 1 { url = await PlexAuth.bestAddress(await probeable(a, machineID: id), machineID: id, clientID: Self.deviceID) }
        if url == nil || url == cfg.baseURL, let account = Keychain.get("plexAccount"),
           let s = try? await PlexAuth.servers(token: account, clientID: Self.deviceID, only: id).first {
            url = s.baseURL; addresses = s.addresses.isEmpty ? addresses : s.addresses; token = s.accessToken
        }
        guard let url, url != cfg.baseURL, persisted.serverConfig?.machineID == id else { return false }   // still the same link
        if backgrounded || pip, !Platform.isTV {
            // Away: no client until the way back, whose pick probes plex.tv's fresh list; the next failure may search again.
            persisted.serverConfig?.addresses = addresses; save()
            if let token { Keychain.set(token, for: "token") }
            lastPlexRecovery = nil
            return false
        }
        persisted.serverConfig?.baseURL = url; persisted.serverConfig?.addresses = addresses; save()
        if let token { Keychain.set(token, for: "token") }
        rebuildServer()
        return true
    }

    /// A request that never reached the server (as opposed to one the server refused).
    nonisolated static func isUnreachable(_ error: Error) -> Bool {
        guard let e = error as? URLError else { return false }
        return [.cannotConnectToHost, .cannotFindHost, .timedOut, .networkConnectionLost, .dnsLookupFailed].contains(e.code)
    }

    /// iPhone, iPad and Mac move between networks: use whichever of the Plex server's addresses answers best right now
    /// (the LAN one at home, the public one away). Launch, return to the app, the Libraries screen and a network change
    /// while open (`networkChanged`) call this; tvOS keeps the linked address. A plain-http link with nothing verified to
    /// move to drops the server (no token goes to it) until a later pick verifies an address again.
    /// Returns true when the address moved or the server was dropped or came back.
    @discardableResult
    func pickPlexAddress() async -> Bool {
        guard let cfg = persisted.serverConfig, let addresses = cfg.addresses, let id = cfg.machineID else { return false }
        let http = cfg.baseURL.scheme == "http"
        guard addresses.count > 1 || http else { return false }   // a lone https address has nothing to pick or check
        pickGen += 1
        let gen = pickGen
        let url = await PlexAuth.bestAddress(await probeable(addresses, machineID: id), machineID: id, clientID: Self.deviceID)
        // A newer pick started meanwhile (it knows the newer network), the app is away (the way back picks again before
        // anything reloads: rebuilt now, a dropped plain-http link would carry the token on whatever network that is), or
        // the link changed.
        guard gen == pickGen, !backgrounded, !pip, persisted.serverConfig?.machineID == id else { return false }
        guard let url else {
            // The guide's empty state and the player's card say why; every load bails on a nil server.
            guard http else { return false }
            let had = server != nil
            server = nil; libraryLoading = false; libraryError = "Could not connect to the server."
            return had
        }
        if url == persisted.serverConfig?.baseURL, server != nil { return false }
        let revived = server == nil
        persisted.serverConfig?.baseURL = url; save()
        rebuildServer()
        // The launch's own loads, held for this pick whatever the screen (not awaited: a Libraries fetch waits on the pick).
        if revived, launchLoad {
            launchLoad = false
            // Building's own load gave up on the dropped client: run it again (forced, with its reveal).
            if screen == .building { startBuild() } else { Task { await loadLibrary(); await checkRecentlyAdded() } }
        }
        else if revived, library.isEmpty, screen == .guide { await loadLibrary() }   // the guide never loaded: nothing else loads it
        // The Libraries screen's fetch found no client: load its list now, busy at once and not awaited (the caller's reload
        // goes on). Its own fetch's pick runs busy, so no loop; a viewer gone before it starts finds go() cleared busy.
        if revived, screen == .libraries, !authBusy, authError != nil {
            authBusy = true; let serial = authSerial
            Task { if authSerial == serial { await fetchLibraries() } }
        }
        return true
    }

    #if !os(tvOS)
    /// The device is on another network (`network`: which one, nil offline) while the app is open. Offline there's
    /// nothing to pick; in the background the return picks. A second's wait folds a burst of reports into one.
    private func pathChanged(network id: String?) {
        let last = network
        network = id ?? ""
        guard let last else { return }   // the first report is the network the app started on
        guard let id else { cancelTimer("network"); return }
        guard id != last, !backgrounded else { return }
        later("network", seconds: 1) { [self] in Task { await networkChanged() } }
    }
    #endif

    /// Pick the Plex address again, and if it moved (or the server was dropped or came back), reload what's on screen
    /// there: a movie tapped while the old address was still set retries at the new one. Network changes and the return
    /// to the app call this off TV.
    func networkChanged() async {
        guard await pickPlexAddress(), !backgrounded else { return }   // hidden meanwhile: the return reloads
        foregroundSerial += 1
        if screen == .watch { tunes += 1 }
    }

    /// The addresses worth probing. Off TV a plain-http one counts only while plex.tv says this device is on the server's
    /// network: on a café's or hotel's LAN whoever answers at that private IP could pose as the server and read the token
    /// in the clear. A TV doesn't move networks, so it keeps them all.
    private func probeable(_ addresses: [PlexAddress], machineID: String) async -> [PlexAddress] {
        guard !Platform.isTV, addresses.contains(where: { $0.url.scheme == "http" }) else { return addresses }
        let home = await PlexAuth.onServerNetwork(machineID: machineID, token: Keychain.get("plexAccount"), clientID: Self.deviceID)
        return home ? addresses : addresses.filter { $0.url.scheme != "http" }
    }

    func signOut() {
        libraryGen += 1; launchLoad = false
        Keychain.delete("token"); Keychain.delete("plexAccount")
        persisted.serverConfig = nil; persisted.selectedLibraryIDs = nil; save()
        server = nil; library = []; channels = []; librarySections = []; freshItems = []
        username = ""; password = ""; pendingToken = nil; foundServers = []   // the plex.tv token and server tokens too
        LibraryCache.clear()
        URLCache.shared.removeAllCachedResponses()   // artwork fetched with the old token
        go(.welcome)
    }

    /// Load the library: cached index first (instant launch), then refresh from the server unless the cache is under
    /// six hours old (`force` skips that gate: Settings › Rebuild, a changed library selection). A newer load, sign-out
    /// or connect supersedes this one at every await (generation counter), so a slow refresh can't write stale data.
    func loadLibrary(refresh: Bool = true, force: Bool = false, retried: Bool = false) async {
        guard let server else { libraryLoading = false; return }   // dropped (pickPlexAddress): Building must not spin
        libraryGen += 1; let gen = libraryGen
        let link = persisted.serverConfig?.link ?? ""
        if library.isEmpty, let cached = await LibraryCache.load(link: link) {
            guard gen == libraryGen else { return }
            library = cached; await rebuildChannelsAsync(); applyTuneOverride()
            guard gen == libraryGen else { return }
        }
        guard refresh else { return }
        if !force, !library.isEmpty, let age = LibraryCache.age, age < 6 * 3600 { libraryLoading = false; return }   // init set it
        libraryLoading = true; libraryError = nil
        do {
            let sections = try await server.libraries()
            guard gen == libraryGen else { return }
            librarySections = sections.filter { $0.itemCount != 0 }   // as fetchLibraries lists them: the Libraries screen may be up
            let chosen = persisted.selectedLibraryIDs.map { ids in sections.filter { ids.contains($0.id) } }
            let items = try await server.libraryItems(in: chosen)
            guard gen == libraryGen else { return }
            library = items; freshItems = []   // a full load has everything the open-the-app check found
            await LibraryCache.save(library, link: link)   // awaited, not detached: keeps ordering with signOut() → clear()
            guard gen == libraryGen else {
                // Signed out or switched servers while it saved: that file belongs to no current link, so it goes.
                if persisted.serverConfig?.link != link { LibraryCache.clear() }
                return
            }
            await rebuildChannelsAsync(); applyTuneOverride()
        } catch {
            guard gen == libraryGen else { return }
            if !retried, Self.isUnreachable(error), await recoverPlexAddress() {   // the server moved: try once at its new address
                await loadLibrary(force: true, retried: true); return
            }
            libraryLoading = false   // before serverFailed: a dead token signs out and lands on Welcome
            libraryError = serverFailed(error)
            if !channels.isEmpty, let e = libraryError { toast(e) }   // the cache is on screen, so the guide's empty-state line never shows
        }
        libraryLoading = false
    }

    /// Opening the app (launch, or back from the Home screen): ask the server for what's been added since the last full
    /// load, so Just Added, New Releases and New This Season are current. Every other channel keeps today's lineup until
    /// the next full load. Best effort and silent: on any error the guide keeps what it has.
    func checkRecentlyAdded() async {
        guard let server, !library.isEmpty, !libraryLoading, !checkingRecent else { return }
        checkingRecent = true; defer { checkingRecent = false }
        let gen = libraryGen
        do {
            var chosen: [LibrarySection]? = nil
            if let ids = persisted.selectedLibraryIDs {
                let sections = librarySections.isEmpty ? try await server.libraries() : librarySections
                chosen = sections.filter { ids.contains($0.id) }
            }
            // ponytail: the newest 50 movies and 50 episodes per library; a bigger import waits for the next full load.
            let recent = try await server.recentlyAdded(in: chosen, limit: 50)
            guard gen == libraryGen, !libraryLoading else { return }
            let known = Set(library.map(\.id))
            let fresh = recent.filter { !known.contains($0.id) }
            guard fresh.map(\.id) != freshItems.map(\.id) else { return }
            freshItems = fresh
            await rebuildChannelsAsync()
        } catch {}
    }

    /// Every server catch routes here: a dead token signs the device out; anything else becomes one user-safe line
    /// (never HTML/JSON, and capped in length).
    func serverFailed(_ error: Error) -> String {
        if Self.isUnreachable(error) {   // a stream or preview that can't reach the server: look for it, then reload what's on screen
            Task { if await recoverPlexAddress() { tunes += 1; foregroundSerial += 1 } }
        }
        if case MediaServerError.unauthorized = error {
            let msg = "\(server?.kind.displayName ?? "The server") signed this device out. Sign in again."
            toast(msg); signOut()
            return msg
        }
        let line = error.localizedDescription.prefix { !"\n<{".contains($0) }
        return String(line.prefix(120)).trimmingCharacters(in: CharacterSet(charactersIn: " :"))
    }

    func save() { persisted.save() }
}

extension Persisted {
    static func load() -> Persisted {
        // Release builds read only the app's own domain, never a launch argument (`open … --args -persisted …` on the Mac
        // would repoint the link at any server); debug builds keep it for the UI tests' `-persisted` seeding.
        #if DEBUG
        let data = UserDefaults.standard.data(forKey: "persisted")
        #else
        let data = UserDefaults.standard.persistentDomain(forName: Bundle.main.bundleIdentifier ?? "")?["persisted"] as? Data
        #endif
        guard let data else { return Persisted() }
        do { return try JSONDecoder().decode(Persisted.self, from: data) }
        catch { print("Persisted.load:", error); return Persisted() }   // genuine corruption, not a missing key
    }
    func save() { UserDefaults.standard.set(try? JSONEncoder().encode(self), forKey: "persisted") }
}

/// JSON cache of the library index so the guide is up before the server answers. Lives in Caches: tvOS keeps only
/// 500 KB of UserDefaults persistent and everything else must be purgeable (tvOS App Programming Guide). The async
/// (nonisolated) functions encode/decode several MB off the main actor.
enum LibraryCache {
    private static var url: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("library.json")
    }
    /// The items and the link they came from (`ServerConfig.link`): a save that lands after a sign-out or a switch to
    /// another server is never read back under the new one.
    private struct File: Codable { let link: String; let items: [MediaItem] }
    static func load(link: String) async -> [MediaItem]? {
        guard let f = try? JSONDecoder().decode(File.self, from: Data(contentsOf: url)), f.link == link else { return nil }
        return f.items   // nil above: no cache yet, an older schema, or another link's; the refresh replaces it
    }
    static func save(_ items: [MediaItem], link: String) async {
        do {
            try JSONEncoder().encode(File(link: link, items: items)).write(to: url, options: .atomic)
            UserDefaults.standard.set(Date.now, forKey: "libraryCachedAt")
        } catch { print("LibraryCache.save:", error) }
    }
    static func clear() { try? FileManager.default.removeItem(at: url); UserDefaults.standard.removeObject(forKey: "libraryCachedAt") }
    /// Seconds since the cache was written (tracked in UserDefaults, not a file timestamp, which would be a required-reason API).
    static var age: TimeInterval? { (UserDefaults.standard.object(forKey: "libraryCachedAt") as? Date).map { Date.now.timeIntervalSince($0) } }
}
