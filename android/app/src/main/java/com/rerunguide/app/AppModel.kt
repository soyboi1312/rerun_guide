package com.rerunguide.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.rerunguide.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The app's one model for every composable (the Swift `@Environment(AppModel.self) var model`). */
val LocalModel = staticCompositionLocalOf<AppModel> { error("no AppModel") }

// MARK: - Persisted types

enum class Screen { welcome, address, link, login, libraries, building, profiles, guide, watch, channels, settings, search }

/** `makeChannel`: Search's page for a result that isn't on in the next 24 hours, or a film's people to make a channel of. */
sealed interface Overlay {
    data object options : Overlay
    data class info(val key: ProgramKey) : Overlay
    data object newChannel : Overlay
    data object makeChannel : Overlay
}

/** Identifies one guide cell: a channel and the slot's exact start. */
data class ProgramKey(val channelID: String, val start: Instant)

data class Profile(
    val id: String,
    val name: String,
    val colorHex: String,
    val theme: ThemeID,
    val hiddenChannelIDs: Set<String>,
) {
    /** `name.prefix(1)`: the first Character (grapheme cluster, so a flag, toned or ZWJ emoji, or accent stays whole). */
    val initial: String get() = if (name.isEmpty()) "" else
        name.substring(0, android.icu.text.BreakIterator.getCharacterInstance().apply { setText(name) }.next())

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("colorHex", colorHex)
        .put("theme", theme.raw).put("hiddenChannelIDs", JSONArray(hiddenChannelIDs.toList()))

    companion object {
        /** Synthesized-Decodable in Swift: a missing key throws (and Persisted.load starts over). */
        fun fromJson(o: JSONObject) = Profile(
            o.getString("id"), o.getString("name"), o.getString("colorHex"), ThemeID.from(o.optString("theme")),
            o.getJSONArray("hiddenChannelIDs").strings().toSet(),
        )
    }
}

/** Which channels the guide shows. */
enum class GuideFilter { all, movies, tv }

/** Hand-written decoders here and on [Persisted]: a build that adds a property must not reset every profile, edit and
 *  the server link. Missing keys keep the defaults declared here. */
data class Settings(
    val crtLines: Boolean = true,
    val autoScroll: Boolean = true,
    val idleSeconds: Int = 8,          // 5 / 8 / 15 / 30
    val tuneMode: TuneMode = TuneMode.live,
    val trailerBreaks: Boolean = true,
    /** Streaming quality cap in kbit/s; 0 = original. Applied by the clients (Plex maxVideoBitrate, Jellyfin MaxStreamingBitrate). */
    val maxBitrateKbps: Int = 0,
    /** Preferred audio language (ISO 639-2, "spa"); null = whatever the server marks default. */
    val audioLanguage: String? = null,
    /** Station idents: the 1.5 s channel card + sting on channel change and before trailer breaks. */
    val stationIdents: Boolean = true,
    /** Attract mode: after this many minutes idle on the drifting guide the screen becomes a slow storefront wall; 0 = off. */
    val attractMinutes: Int = 3,
    /** Subtitles by default: "off" (none), "forced" (only the program's forced track), "on". */
    val subtitleMode: String = "forced",
    /** Which language to show when subtitles are on; null = the device language, else the first subtitle track. */
    val subtitleLanguage: String? = null,
    /** What attract mode shows: "wall" (the storefront wall), "logo" (the bouncing Rerun Guide logo) or "soyboi" (the mascot). */
    val screensaver: String = "wall",
    /** One trailer break in 15 opens with the bundled Soyboi spot. */
    val houseSpots: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("crtLines", crtLines).put("autoScroll", autoScroll)
        .put("idleSeconds", idleSeconds).put("tuneMode", tuneMode.name).put("trailerBreaks", trailerBreaks)
        .put("maxBitrateKbps", maxBitrateKbps).putOpt("audioLanguage", audioLanguage).put("stationIdents", stationIdents)
        .put("attractMinutes", attractMinutes).put("subtitleMode", subtitleMode).putOpt("subtitleLanguage", subtitleLanguage)
        .put("screensaver", screensaver).put("houseSpots", houseSpots)

    companion object {
        fun fromJson(o: JSONObject): Settings {
            val d = Settings()
            val mode = o.stringOrNull("subtitleMode") ?: d.subtitleMode
            return Settings(
                crtLines = o.boolOrNull("crtLines") ?: d.crtLines,
                autoScroll = o.boolOrNull("autoScroll") ?: d.autoScroll,
                idleSeconds = o.intOrNull("idleSeconds") ?: d.idleSeconds,
                tuneMode = o.stringOrNull("tuneMode")?.let { TuneMode.valueOf(it) } ?: d.tuneMode,
                trailerBreaks = o.boolOrNull("trailerBreaks") ?: d.trailerBreaks,
                maxBitrateKbps = o.intOrNull("maxBitrateKbps") ?: d.maxBitrateKbps,
                audioLanguage = o.stringOrNull("audioLanguage"),
                stationIdents = o.boolOrNull("stationIdents") ?: d.stationIdents,
                attractMinutes = o.intOrNull("attractMinutes") ?: d.attractMinutes,
                subtitleMode = if (mode == "auto") "forced" else mode,   // earlier builds stored the mode as "auto"
                subtitleLanguage = o.stringOrNull("subtitleLanguage"),
                screensaver = o.stringOrNull("screensaver") ?: d.screensaver,
                houseSpots = o.boolOrNull("houseSpots") ?: d.houseSpots,
            )
        }
    }
}

/** Per-channel overrides the viewer made to an automatic channel. Custom channels store everything in their definition. */
data class ChannelEdit(
    val name: String? = null,
    val number: Int? = null,
    val order: PlayOrder? = null,
    val tuneMode: TuneMode? = null,
    val trailerBreaks: Boolean? = null,
) {
    fun toJson(): JSONObject = JSONObject().putOpt("name", name).putOpt("number", number).putOpt("order", order?.rawValue)
        .putOpt("tuneMode", tuneMode?.name).putOpt("trailerBreaks", trailerBreaks)

    companion object {
        fun fromJson(o: JSONObject) = ChannelEdit(
            o.stringOrNull("name"), o.intOrNull("number"), o.stringOrNull("order")?.let { PlayOrder.from(it) ?: throw JSONException("order $it") },
            o.stringOrNull("tuneMode")?.let { TuneMode.valueOf(it) }, o.boolOrNull("trailerBreaks"),
        )
    }
}

data class ServerConfig(
    val kind: ServerKind,
    val baseURL: String,
    val name: String,
    val userID: String? = null,    // Emby/Jellyfin
    /** Plex: every address plex.tv listed (a typed one first) and the server's machineIdentifier, so phones and tablets
     *  can switch between the LAN and public addresses as they move (`AppModel.pickPlexAddress`; a plain-http one only
     *  on the server's own network). null for older links. */
    val addresses: List<PlexAddress>? = null,
    val machineID: String? = null,
) {
    /** Which server and account a cached library belongs to (`LibraryCache`): the Plex machine, or the address, plus the user. */
    val link: String get() = "${kind.rawValue}|${machineID ?: baseURL}|${userID ?: ""}"

    fun makeServer(token: String, deviceID: String, maxBitrateKbps: Int = 0): MediaServer = when (kind) {
        ServerKind.plex -> PlexClient(baseURL, token, clientID = deviceID, serverName = name, maxBitrateKbps = maxBitrateKbps)
        ServerKind.emby, ServerKind.jellyfin -> JellyfinClient(baseURL, token, userID = userID ?: "", deviceID = deviceID, kind = kind, serverName = name, maxBitrateKbps = maxBitrateKbps)
        ServerKind.demo -> DemoMediaServer()
    }

    fun toJson(): JSONObject = JSONObject().put("kind", kind.name).put("baseURL", baseURL).put("name", name)
        .putOpt("userID", userID).putOpt("addresses", addresses?.let { a -> JSONArray(a.map { it.toJson() }) })
        .putOpt("machineID", machineID)

    companion object {
        fun fromJson(o: JSONObject) = ServerConfig(
            ServerKind.valueOf(o.getString("kind")), o.getString("baseURL"), o.getString("name"), o.stringOrNull("userID"),
            o.optJSONArray("addresses")?.objects()?.map { PlexAddress.fromJson(it) }, o.stringOrNull("machineID"),
        )
    }
}

/** Any property added to the types inside (Profile, ServerConfig, ChannelDefinition) must be nullable, or get the same
 *  missing-key treatment. */
data class Persisted(
    val profiles: List<Profile> = listOf(Profile("p1", "You", "#f0a33a", ThemeID.LATE, emptySet())),
    val currentProfileID: String = "p1",
    val settings: Settings = Settings(),
    val channelEdits: Map<String, ChannelEdit> = emptyMap(),
    val customChannels: List<ChannelDefinition> = emptyList(),
    /** null = every library. */
    val selectedLibraryIDs: List<String>? = null,
    val serverConfig: ServerConfig? = null,
    val lastChannelID: String? = null,
    val guideFilter: GuideFilter = GuideFilter.all,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("profiles", JSONArray(profiles.map { it.toJson() }))
        .put("currentProfileID", currentProfileID)
        .put("settings", settings.toJson())
        .put("channelEdits", JSONObject().also { o -> channelEdits.forEach { (k, v) -> o.put(k, v.toJson()) } })
        .put("customChannels", JSONArray(customChannels.map { it.toJson() }))
        .putOpt("selectedLibraryIDs", selectedLibraryIDs?.let { JSONArray(it) })
        .putOpt("serverConfig", serverConfig?.toJson())
        .putOpt("lastChannelID", lastChannelID)
        .put("guideFilter", guideFilter.name)

    fun save() { AppModel.defaults.edit().putString("persisted", toJson().toString()).apply() }

    companion object {
        fun fromJson(o: JSONObject): Persisted {
            val d = Persisted()
            return Persisted(
                profiles = o.optJSONArray("profiles")?.objects()?.map { Profile.fromJson(it) } ?: d.profiles,
                currentProfileID = o.stringOrNull("currentProfileID") ?: d.currentProfileID,
                settings = o.optJSONObject("settings")?.let { Settings.fromJson(it) } ?: d.settings,
                channelEdits = o.optJSONObject("channelEdits")?.let { e -> e.keys().asSequence().associateWith { ChannelEdit.fromJson(e.getJSONObject(it)) } } ?: d.channelEdits,
                customChannels = o.optJSONArray("customChannels")?.objects()?.map { ChannelDefinition.fromJson(it) } ?: d.customChannels,
                selectedLibraryIDs = o.optJSONArray("selectedLibraryIDs")?.strings(),
                serverConfig = o.optJSONObject("serverConfig")?.let { ServerConfig.fromJson(it) },
                lastChannelID = o.stringOrNull("lastChannelID"),
                guideFilter = o.stringOrNull("guideFilter")?.let { GuideFilter.valueOf(it) } ?: d.guideFilter,
            )
        }

        fun load(): Persisted {
            val s = AppModel.defaults.getString("persisted", null) ?: return Persisted()
            return try { fromJson(JSONObject(s)) }
            catch (e: Exception) { Log.w("Persisted", "load: $e"); Persisted() }   // genuine corruption, not a missing key
        }
    }
}

/** What's on the screen while watching. Mirrors the prototype's `w`. */
data class Playback(
    val channelID: String,
    val mode: Mode,
    /** For `.start`: the slot being replayed and the player position bookkeeping. */
    val programStart: Instant,
    val offset: Double = 0.0,                  // seconds into the program at `startedAt`
    val startedAt: Instant = Instant.now(),
    val paused: Boolean = false,
    /** The slot that was on when we tuned, kept until it ends so a library refresh (which reshuffles lineups) can't
     *  swap the program under the viewer. null = derive from the schedule. */
    val slot: Slot? = null,
    /** Per-program track override from the banner picker (or the subtitle fallback), for `itemID` only; null or another
     *  item = resolve from Settings. Kept by a re-tune to the same program (Start over, Back to live); any other tune drops it. */
    val tracks: TrackOverride? = null,
    /** `slot` was started from its beginning (`tune` in `.start`), so at 90% `watchTick` marks it watched on the server
     *  and clears this. Play/Pause and the 10 s skips keep it on the same slot, Home from live drops it (unless `markDue`),
     *  any tune makes a new playback. */
    val fromStart: Boolean = false,
    /** `watchTick` saw 90% with no server: this slot's break may still report it once it's back. */
    val markDue: Boolean = false,
) {
    enum class Mode { live, start }
    data class TrackOverride(val itemID: String, val selection: TrackSelection)
}

data class Toast(val message: String, val n: Int)

/** Scroll/focus bookkeeping for the guide's drifting grid. Not observed: it changes every frame while drifting.
 *  A class, not a data class: nothing ever copies it (it is only replaced whole by `guideReset`). */
class GuideScroll {
    var pos = 0f                            // current translateY of the row track, in points (remote held)
    var row = 0                             // focused row index on the doubled track
    var focusTime: Instant = Instant.now()  // "focus time": start of the focused cell clamped to the window
    var resumeOffset = 0f                   // drift offset to resume from, 0 ..< half-track height
    var driftStart: Instant = Instant.now() // when the current drift began (offset = resumeOffset + elapsed × speed, mod half)
    var speed = 22f                         // pt/s, the design handoff's default drift speed
}

/** A guide cell resolved: the Swift `(channel: Channel, slot: Slot)` tuple. Destructures like it: `val (ch, slot) = …`. */
data class ChannelSlot(val channel: Channel, val slot: Slot)

/** `AppModel.resolve`'s `(auto:, channels:)` tuple. */
data class Resolved(val auto: List<ChannelDefinition>, val channels: List<Channel>)

// MARK: - Model

/** One object holds the whole app state; screens are pure functions of it and press handlers mutate it.
 *  This file holds the shared core (ported from the design prototype's Component). Screen-specific handlers live in `AppModel*.kt`.
 *  [launchArgs]: the launch Intent's extras in debug builds (the Swift launch arguments), else null. */
class AppModel(context: Context, launchArgs: Bundle? = null) {
    /** Every coroutine the model starts (the Swift `Task {}`s): Main, like the @MainActor model. */
    val scope = MainScope()

    // navigation
    var screen by mutableStateOf(Screen.welcome)
    var overlay by mutableStateOf<Overlay?>(null)
    /** Generic focus index for list-like screens (the prototype's `fi`). */
    var focusIndex by mutableIntStateOf(0)
    /** RootView: the window is taller than wide on a screen with a portrait layout (`portraitReady`), drawn by
     *  Portrait*.kt instead of on the stage. Portrait never draws the TV banner, its track list or a held TV guide, so
     *  turning upright puts them away: hidden, they would take the next Back. */
    private var portraitState by mutableStateOf(false)
    var portrait: Boolean
        get() = portraitState
        set(v) { val up = v && !portraitState; portraitState = v; if (up) { hideBanner(); guideRelease() } }
    /** Now playing's full-screen button (phones held upright): the TV player, which turns the phone sideways. Back, or
     *  leaving Now playing, ends it. */
    var fullScreen by mutableStateOf(false)

    // server + library
    var server by mutableStateOf<MediaServer?>(null)
    var persisted by mutableStateOf(Persisted())
    private var libraryState by mutableStateOf<List<MediaItem>>(emptyList())
    var library: List<MediaItem>
        get() = libraryState
        set(v) { libraryState = v; movies = v.filter { it.kind == MediaItem.Kind.movie }; searchIndex = null }
    /** The library's films, kept with it: trailer breaks on a channel with none of its own pick from these. */
    private var movies: List<MediaItem> = emptyList()
    var librarySections by mutableStateOf<List<LibrarySection>>(emptyList())
    private var channelsState by mutableStateOf<List<Channel>>(emptyList())
    /** Every channel, sorted by number, hidden ones included. */
    var channels: List<Channel>
        get() = channelsState
        set(v) { channelsState = v; dayCache = HashMap() }
    /** Day schedules per (channel, day): the generator shuffles the whole pool per call, so the guide must not recompute
     *  it per row per tick. Cleared whenever `channels` changes. */
    var dayCache = HashMap<String, List<Slot>>()
    /** The automatic definitions from the last rebuild, so "Reset to automatic" needs no second library pass. */
    var autoDefinitions: List<ChannelDefinition> = emptyList()
    /** Candidates for the New-channel picker, computed once when the kind is chosen (otherwise a 20k-item scan would run per press). */
    var candidateCache by mutableStateOf<List<ChannelBuilder.Candidate>>(emptyList())
    private var libraryGen = 0
    /** Items the open-the-app check (`checkRecentlyAdded`) found since the last full load. Only the "what's new" channels
     *  see them (`ChannelBuilder.isFresh`); the next full load folds them into `library`. */
    var freshItems: List<MediaItem> = emptyList()
        set(v) { field = v; searchIndex = null }
    private var checkingRecent = false
    private var rebuildGen = 0
    /** When the app last went looking for a Plex server that stopped answering (at most once a minute). */
    private var lastPlexRecovery: Instant? = null
    /** The network last reported ("" offline, null before the first report, which is the one the app started on). */
    private var network: String? = null
    /** Bumped by every address pick: one that finishes after a newer one started (Wi-Fi off then on) changes nothing. */
    var pickGen = 0
        private set
    /** A plain-http link's launch loads, held for the pick that revives its client (on whatever screen that happens). */
    private var launchLoad = false
    var libraryLoading by mutableStateOf(false)
    var libraryError by mutableStateOf<String?>(null)

    // onboarding
    var serviceKind by mutableStateOf(ServerKind.plex)
    var address by mutableStateOf("")
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var linkCode by mutableStateOf<String?>(null)
    var linked by mutableStateOf(false)
    var authError by mutableStateOf<String?>(null)
    var authBusy by mutableStateOf(false)
    /** Servers found on the local network (LocalDiscovery, no token), or returned by a Plex PIN (with the server's token)
     *  or an Emby PIN (with the server's Emby Connect access key). */
    var foundServers by mutableStateOf<List<PlexServer>>(emptyList())
    var discovering by mutableStateOf(false)
    /** The account the link signed in, while its servers are listed: the plex.tv token, or the Emby Connect user id. */
    var pendingToken by mutableStateOf<String?>(null)
    /** Bumped by go() and by each sign-in step: a step still in flight when the viewer leaves (even for the same screen
     *  again) is dropped, result, error and busy flag alike. */
    var authSerial = 0
    var librarySelection by mutableStateOf<Set<String>>(emptySet())
    var builtCount by mutableIntStateOf(0)
    /** The PIN / Quick Connect poll loop (AppModel+Onboarding's `static var linkTask`); cancelled on Back and on restart. */
    var linkTask: Job? = null

    // guide
    var focused by mutableStateOf<ProgramKey?>(null)
    var remoteHeld by mutableStateOf(false)
    var windowOffset by mutableIntStateOf(0)            // 0...maxWindowOffset, 30-min pages ahead
    var guideScroll = GuideScroll()
    /** The launch's first guide with channels held still for [LAUNCH_HOLD_SECONDS] (see `holdForLaunch`). */
    var launchHeld = false

    // watch
    var playback by mutableStateOf<Playback?>(null)
    var bannerVisible by mutableStateOf(false)
    var bannerFocus by mutableIntStateOf(-1)
    /** The banner's progress bar has the remote (Up from its buttons, while `bannerFocus` ≥ 0): Left / Right skip. */
    var barFocused by mutableStateOf(false)
    /** The banner's ⏪ (−1) or ⏩ (+1) above the playhead for a second after a skip; 0 when none. */
    var skipCue by mutableIntStateOf(0)
    /** The banner's Audio or Subtitles list, open over its button. */
    var trackMenu by mutableStateOf<TrackMenu?>(null)
    /** Start over: the VHS rewind on screen, from where the program was (see `tune()`). */
    var rewind by mutableStateOf<Rewind?>(null)
    /** `length`: how long the rewind plays (its counter runs back for 1.1 s, holds, and fades out by then). */
    data class Rewind(val from: Double, val started: Instant) { companion object { const val length = 1.4 } }
    /** Attract mode is on screen (the guide has been drifting untouched for `settings.attractMinutes`). */
    var attracting by mutableStateOf(false)
    /** Bumped by `tune()` on every channel change, together with `flips`; the ident card keys off it. A start-over from the
     *  guide rewinds instead: no bump, and `flips` goes to 0 so the new watch screen has no static to show. */
    var identSerial by mutableIntStateOf(0)
    /** Bumped on the way back from the Home screen: the guide's preview reloads on it (its stream was stopped). */
    var foregroundSerial by mutableIntStateOf(0)
    var backgrounded = false
    /** A started-over program that was playing when the app left: it was held at its position and plays on return. */
    var resumeAfterBackground = false
    /** Phones and tablets: the program is playing on in a picture-in-picture window (MainActivity), which shows the picture alone. */
    var pip by mutableStateOf(false)
    /** Tracks of the program on screen, loaded by WatchScreen; drives the banner's Audio / Subtitles actions. */
    var playbackTracks by mutableStateOf<List<MediaTrack>>(emptyList())
    var flips by mutableIntStateOf(0)
    /** Bumped by every `tune()`: the player reloads on it, so Play/Pause (which only changes `playback.mode`) does not. */
    var tunes by mutableIntStateOf(0)

    // channel editor
    var channelSelection by mutableStateOf<String?>(null)   // null = "New channel" row
    var channelColumn by mutableIntStateOf(0)
    var channelFieldIndex by mutableIntStateOf(0)
    var numberDraft by mutableStateOf<String?>(null)
    var newChannelStep by mutableIntStateOf(0)
    var newChannelType by mutableStateOf<ChannelBuilder.CandidateKind?>(null)
    /** Non-null while a text field owns the remote (server address, username, channel name…). */
    var editingField by mutableStateOf<String?>(null)

    /** Number keys on the guide or the player: the digits typed so far (AppModelWatch). `missing`: the number had no
     *  channel, and the box says so for a moment. */
    var channelEntry by mutableStateOf<ChannelEntry?>(null)
    data class ChannelEntry(val digits: String, val missing: Boolean = false)
    /** What the box last said, which it fades out showing: a third digit tunes (and so closes it) in the same press. */
    var lastEntry by mutableStateOf<ChannelEntry?>(null)

    // search (Options › Search, AppModelSearch)
    private var searchQueryState by mutableStateOf("")
    var searchQuery: String
        get() = searchQueryState
        set(v) { searchQueryState = v; searchResults = TitleSearch.search(v, titleIndex) }
    var searchResults by mutableStateOf<List<TitleSearch.Entry>>(emptyList())
    /** The page's row: 0 the text field, i the result `searchResults[i - 1]`. Its own index, kept while an overlay over the
     *  page uses `focusIndex`. */
    var searchFocus by mutableIntStateOf(0)
    /** When each entry airs next, worked out as the page opens and again on its clock (`refreshAirings`). */
    var searchAirings by mutableStateOf<Map<String, TitleSearch.Airing>>(emptyMap())
    /** Make a channel from a film: its director(s) and lead actors with enough films, once asked for. */
    var makePeople by mutableStateOf<List<Pair<ChannelBuilder.CandidateKind, ChannelBuilder.Candidate>>?>(null)
    /** The library's titles keyed for matching (`titleIndex`); dropped whenever the library changes. */
    var searchIndex: TitleSearch.Index? = null

    var toast by mutableStateOf<Toast?>(null)

    // Navigation state for confirmations, return paths and cancellable loads (owned by the screen handlers that use it).
    /** Settings › Sign out needs a second press within a few seconds. */
    var signOutArmed by mutableStateOf(false)
    /** Where the Libraries screen returns to: null = first-run onboarding (welcome), .settings = re-pick from Settings. */
    var librariesOrigin by mutableStateOf<Screen?>(null)
    /** Profiles screen: index of the tile whose Rename / Delete row is open, with `profileMenuIndex` as the focused action. */
    var profileMenu by mutableStateOf<Int?>(null)
    var profileMenuIndex by mutableIntStateOf(0)
    /** The in-flight library load started by the Building screen, so Back can cancel it. */
    var loadTask: Job? = null

    private val timers = HashMap<String, Job>()

    /** Debug-only launch hook; cleared once used. */
    var tuneOverride: Int? = null
    /** Debug-only `spotOverride`: the next load opens with the spot, break or not. */
    var spotOverride = false
    /** Debug-only `attractMinutesOverride`: the attract delay for this run, never saved into settings. */
    var attractOverride: Int? = null

    /** Options menu rows (AppModel+Overlays' `OptionRow`; nested here because Kotlin extensions can't add types). */
    enum class OptionRow { profile, guide, movies, tv, search, channels, settings, theme }

    /** One Program-info action (AppModel+Overlays' `InfoAction`). */
    class InfoAction(val label: String, val run: () -> Unit)

    companion object {
        /** UserDefaults.standard: one SharedPreferences file, opened by the first AppModel. */
        lateinit var defaults: SharedPreferences
        /** The Caches directory (LibraryCache). */
        lateinit var cacheDir: File
        /** Left out of backups and device transfers (`noBackupFilesDir`). */
        lateinit var noBackupDir: File

        /** In a file Android never backs up: a restored or transferred phone must not sign in as this one. Older builds kept
         *  it in the backed-up prefs; it moves here once. */
        val deviceID: String by lazy {
            val f = File(noBackupDir, "deviceID")
            runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: run {
                val id = defaults.getString("deviceID", null) ?: UUID.randomUUID().toString().uppercase()
                if (runCatching { f.writeText(id) }.isSuccess) defaults.edit().remove("deviceID").apply()
                id
            }
        }

        const val windowSpan: Double = 90 * 60.0
        /** How many 30-min pages Right can move the window ahead: the last one ends 12 hours past the current half hour. */
        const val maxWindowOffset = 21

        /** The device's language as the ISO 639-2 code servers use ("eng", "spa"). */
        val deviceLanguage: String
            get() = runCatching { Locale.getDefault().isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "eng"

        /** Off the main thread: a library refresh evaluates every rule chip against thousands of items. */
        suspend fun resolveChannels(library: List<MediaItem>, fresh: List<MediaItem>, edits: Map<String, ChannelEdit>, custom: List<ChannelDefinition>): Resolved =
            withContext(Dispatchers.Default) { resolve(library, fresh, edits, custom) }

        /** The lineup (which automatic channels exist, and their numbers) comes from `library` alone, so newly found items
         *  never renumber it; `fresh` only adds to the "what's new" channels. */
        fun resolve(library: List<MediaItem>, fresh: List<MediaItem>, edits: Map<String, ChannelEdit>, custom: List<ChannelDefinition>): Resolved {
            val auto = ChannelBuilder.automaticChannels(library)
            val defs = auto.map { d ->
                val e = edits[d.id] ?: return@map d
                d.copy(
                    name = e.name ?: d.name, number = e.number ?: d.number, order = e.order ?: d.order,
                    trailerBreaks = e.trailerBreaks ?: d.trailerBreaks, tuneMode = e.tuneMode ?: d.tuneMode,
                )
            } + custom
            val resolved = ChannelBuilder.channels(defs, library, fresh).toMutableList()
            if (library.isNotEmpty()) {   // channel 99 rides along with any real lineup; the editor's rename/renumber still apply
                var scr = Channel.scrambled
                edits[scr.id]?.let { e ->
                    scr = scr.copy(definition = scr.definition.copy(
                        name = e.name ?: scr.definition.name, number = e.number ?: scr.definition.number,
                        tuneMode = e.tuneMode ?: scr.definition.tuneMode))
                }
                resolved.add(scr)
            }
            return Resolved(auto, resolved.sortedWith(byNumber))
        }

        /** `($0.number, $0.id) < ($1.number, $1.id)`, ids in Swift's string order (core `swiftCompare`). */
        private val byNumber = Comparator<Channel> { a, b -> if (a.number != b.number) a.number.compareTo(b.number) else swiftCompare(a.id, b.id) }

        /** A request that never reached the server (as opposed to one the server refused). */
        fun isUnreachable(error: Throwable): Boolean =
            error is ConnectException || error is NoRouteToHostException || error is UnknownHostException ||
                error is SocketTimeoutException || error is SocketException
    }

    init {
        defaults = context.getSharedPreferences("defaults", Context.MODE_PRIVATE)
        cacheDir = context.cacheDir
        noBackupDir = context.noBackupFilesDir
        persisted = Persisted.load()
        if (!applyLaunchArgs(launchArgs)) startFromSavedLink()
    }

    /** Test hooks, debug builds only (`adb shell am start … --ei tuneOverride N`), the Swift launch arguments:
     *  `attractMinutesOverride N` shortens the attract-mode delay for a run (never persisted); `tuneOverride N` tunes to
     *  channel N as soon as the lineup is built; `spotOverride true` opens with the spot; `themeOverride midnight` starts
     *  every profile in that theme (persisted on the next save); `serversOverride N` opens the Plex server list with N
     *  stand-in servers (never persisted). Returns true when it did, so the saved link is not started. */
    private fun applyLaunchArgs(args: Bundle?): Boolean {
        if (args == null) return false
        if (args.containsKey("attractMinutesOverride")) attractOverride = args.getInt("attractMinutesOverride")
        if (args.containsKey("tuneOverride")) tuneOverride = args.getInt("tuneOverride")
        spotOverride = args.getBoolean("spotOverride")
        args.getString("themeOverride")?.let { raw ->
            ThemeID.entries.firstOrNull { it.raw == raw }?.let { id -> persisted = persisted.copy(profiles = persisted.profiles.map { it.copy(theme = id) }) }
        }
        val standIns = args.getString("serversOverride")?.toIntOrNull() ?: args.getInt("serversOverride")
        if (standIns <= 0) return false
        serviceKind = ServerKind.plex; pendingToken = "ui-test"
        foundServers = (1..standIns).map { PlexServer(id = "sim-$it", name = "Server $it", baseURL = "http://192.168.1.$it:32400", accessToken = "ui-test") }
        address = foundServers[0].baseURL
        screen = Screen.address; focusIndex = 1
        return true
    }

    private fun startFromSavedLink() {
        val cfg = persisted.serverConfig ?: return
        rebuildServer()
        if (server == null) return
        screen = Screen.guide
        libraryLoading = true   // the cache decodes off-thread; no "Nothing to schedule" flash before it lands
        val http = !Platform.isTV && cfg.addresses != null && cfg.baseURL.startsWith("http://")
        // A plain-http link gets no client until the pick checks its address on this network (the pick's revived branch
        // builds it and loads the guide), so nothing sends the token unchecked: not a stop on the way out, not a pick a
        // newer one replaced.
        if (http) { server = null; launchLoad = true }
        scope.launch {
            if (!Platform.isTV && cfg.addresses != null) {
                // A plain-http link picks first: the guide's posters and preview carry the token, maybe to a stranger's LAN.
                // With nothing verified to move to, the server stays dropped until a later pick verifies an address.
                if (!http) loadLibrary(refresh = false)   // the cache shows meanwhile
                pickPlexAddress()
            }
            // A fresh cache skipped the full load; the new channels still catch up. Plain http: the reviving pick runs them.
            if (!http) { loadLibrary(); checkRecentlyAdded() }
        }
    }

    // MARK: Derived

    val profile: Profile get() = persisted.profiles.firstOrNull { it.id == persisted.currentProfileID } ?: persisted.profiles[0]
    val theme: Theme get() = profile.theme.theme

    /** Options and Settings both offer the theme; it lives on the profile, so each viewer keeps their own. */
    fun setTheme(id: ThemeID) {
        if (id == profile.theme) return
        updateProfile { it.copy(theme = id) }
        if (screen == Screen.guide) guideReset()   // the row pitch changes with the theme, so the drift offset is re-based
    }
    fun toggleTheme() = setTheme(if (profile.theme == ThemeID.LATE) ThemeID.MIDNIGHT else ThemeID.LATE)

    /** Assigning saves (the Swift `settings { set }`). Edit one field with `settings = settings.copy(…)` or [updateSettings]. */
    var settings: Settings
        get() = persisted.settings
        set(v) { persisted = persisted.copy(settings = v); save() }
    fun updateSettings(change: (Settings) -> Settings) { settings = change(settings) }

    val serverLabel: String get() = "${server?.serverName ?: ""} · ${server?.kind?.displayName ?: ""}"

    fun isHidden(channelID: String): Boolean = profile.hiddenChannelIDs.contains(channelID)
    fun channel(id: String): Channel? = channels.firstOrNull { it.id == id }
    /** Channels in the lineup: not hidden for this profile, and in the current guide (All / Movies / TV Shows). */
    val visibleChannels: List<Channel>
        get() = channels.filter { !isHidden(it.id) && (guideFilter == GuideFilter.all || (guideFilter == GuideFilter.movies) == it.isMovies) }
    val guideFilter: GuideFilter get() = persisted.guideFilter
    /** Switch guides (Options › Guide / Movies / TV Shows): restarts the drift from the top of the new lineup. */
    fun setGuideFilter(f: GuideFilter) {
        persisted = persisted.copy(guideFilter = f); save()
        guideReset()
        overlay = null; focusIndex = 0
        if (screen != Screen.guide) go(Screen.guide)
    }

    /** Guide row pitch and viewport: the design handoff's PITCH / VIEW per theme (Late Show 100 / 424, Midnight Movie 108 / 432). */
    val rowPitch: Float get() = if (theme.id == ThemeID.MIDNIGHT) 108f else 100f
    val gridHeight: Float get() = if (theme.id == ThemeID.MIDNIGHT) 432f else 424f

    /** Guide window start: now floored to the half hour, plus `offset` pages. */
    fun windowStart(at: Instant, offset: Int? = null): Instant {
        val block = GuideGenerator.blockLength
        val floored = floor(at.epochSecond / block) * block   // the epoch is on a half-hour boundary, like the reference date
        return Instant.ofEpochSecond((floored + (offset ?: windowOffset) * block).toLong())
    }

    /** One channel's lineup for the day containing `day`, memoized. */
    fun daySlots(channel: Channel, day: Instant): List<Slot> {
        val d = startOfDay(day)
        val k = "${channel.id}|${d.epochSecond}"
        dayCache[k]?.let { return it }
        val s = GuideGenerator.schedule(channel, d)
        dayCache[k] = s
        return s
    }
    /** Slots intersecting [from, to], spanning days; memoized per day. */
    fun slots(channel: Channel, from: Instant, to: Instant): List<Slot> {
        val out = ArrayList<Slot>()
        var day = startOfDay(from)
        while (day < to) {
            out += daySlots(channel, day)
            day = day.atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
        }
        return out.filter { it.end > from && it.start < to }
    }
    fun slotOnAir(channel: Channel, at: Instant): Slot? = daySlots(channel, at).firstOrNull { it.start <= at && at < it.end }
    fun slot(channel: Channel, startingAt: Instant): Slot? =
        daySlots(channel, startingAt).firstOrNull { abs(it.start.toEpochMilli() - startingAt.toEpochMilli()) < 1000 }
    fun status(slot: Slot, at: Instant): ProgramStatus = GuideGenerator.status(slot, at)
    fun program(key: ProgramKey): ChannelSlot? {
        val ch = channel(key.channelID) ?: return null
        val slot = slot(ch, key.start) ?: return null
        return ChannelSlot(ch, slot)
    }
    fun key(channel: Channel, slot: Slot) = ProgramKey(channel.id, slot.start)

    private fun startOfDay(i: Instant): Instant =
        i.atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant()

    // MARK: Timers / toast

    fun later(key: String, seconds: Double, action: () -> Unit) {
        timers[key]?.cancel()
        timers[key] = scope.launch {
            delay((seconds * 1000).toLong())
            action()
        }
    }
    fun cancelTimer(key: String) { timers.remove(key)?.cancel() }

    fun toast(message: String) {
        toast = Toast(message, (toast?.n ?: 0) + 1)
        later("toast", seconds = 3.2) { toast = null }
    }

    // MARK: Navigation

    fun go(to: Screen) {
        if (screen == Screen.guide && to != Screen.guide) guidePark()
        if (screen == Screen.link && to != Screen.link) linkTask?.cancel()   // a PIN / Quick Connect poll never outlives its screen
        if (screen == Screen.login && to != Screen.login) password = ""   // typed passwords don't wait around in memory
        authSerial += 1                                                     // a sign-in step in flight belongs to the screen being left
        if (screen == Screen.address || screen == Screen.login || screen == Screen.libraries) authBusy = false
        cancelTimer("banner"); closeEntry()
        screen = to; overlay = null; focusIndex = 0; editingField = null; fullScreen = false
        if (to == Screen.link) startLink()
        if (to == Screen.building) startBuild()
        if (to == Screen.address && foundServers.isEmpty()) startDiscovery()
    }

    fun closeOverlay() {
        overlay = null; focusIndex = 0
        // Portrait has no held guide: one held in landscape before the overlay opened is let go (it would take Back).
        if (screen == Screen.guide && remoteHeld) { if (portrait) guideRelease() else guideBump() }
    }

    fun openOptions() { cancelTimer("idle"); closeEntry(); overlay = Overlay.options; focusIndex = 1 }
    fun openInfo(key: ProgramKey) { cancelTimer("idle"); cancelTimer("banner"); closeEntry(); overlay = Overlay.info(key); focusIndex = 0 }

    /** Dispatch a remote press to whichever overlay/screen owns the remote right now. */
    fun press(cmd: RemoteCommand) {
        if (overlay == null && pressEntry(cmd)) return
        when (overlay) {
            Overlay.options -> return pressOptions(cmd)
            is Overlay.info -> return pressInfo(cmd)
            Overlay.newChannel -> return pressNewChannel(cmd)
            Overlay.makeChannel -> return pressMakeChannel(cmd)
            null -> {}
        }
        when (screen) {
            Screen.welcome -> pressWelcome(cmd)
            Screen.address -> pressAddress(cmd)
            Screen.link -> pressLink(cmd)
            Screen.login -> pressLogin(cmd)
            Screen.libraries -> pressLibraries(cmd)
            Screen.building -> pressBuilding(cmd)
            Screen.profiles -> pressProfiles(cmd)
            Screen.guide -> pressGuide(cmd)
            Screen.watch -> pressWatch(cmd)
            Screen.channels -> pressChannels(cmd)
            Screen.settings -> pressSettings(cmd)
            Screen.search -> pressSearch(cmd)
        }
    }

    /** Move `focusIndex` within 0 until count for up/down (or left/right when horizontal). Returns true if consumed. */
    fun step(cmd: RemoteCommand, count: Int, horizontal: Boolean = false): Boolean {
        val dec = if (horizontal) RemoteCommand.left else RemoteCommand.up
        val inc = if (horizontal) RemoteCommand.right else RemoteCommand.down
        if (cmd != dec && cmd != inc) return false
        focusIndex = max(0, min(count - 1, focusIndex + (if (cmd == inc) 1 else -1)))
        return true
    }

    /** Edit the current profile: `updateProfile { it.copy(name = n) }` (the Swift `{ $0.name = n }`). Saves. */
    fun updateProfile(change: (Profile) -> Profile) {
        val i = persisted.profiles.indexOfFirst { it.id == persisted.currentProfileID }
        if (i < 0) return
        persisted = persisted.copy(profiles = persisted.profiles.toMutableList().also { it[i] = change(it[i]) }); save()
    }

    fun switchProfile(id: String) {
        if (screen == Screen.guide) guideReset()
        persisted = persisted.copy(currentProfileID = id); save()
    }

    // MARK: Guide core (shared with watch/overlays; the drift itself lives in AppModelGuide)

    /** Leaving the guide (or letting go of the remote, `guideRelease`): remember where the drift was so it resumes from
     *  there. A held focus snaps to what's on now on its channel unless it's in the first window, the one the guide comes
     *  back to (a focus paged ahead would describe a program hours away, with no cell lit). */
    fun guidePark() {
        cancelTimer("idle")
        guideScroll.resumeOffset = if (remoteHeld) wrap(guideScroll.pos) else guideDriftOffset()
        val now = Instant.now(); val ws0 = windowStart(now, offset = 0)
        if (remoteHeld) guideKey(now)?.let { k ->
            program(k)?.let { pr -> focused = if (pr.slot.start < ws0.plusSeconds(windowSpan) && pr.slot.end > ws0) k else cellAt(pr.channel, now, ws0) }
        }
        remoteHeld = false; windowOffset = 0
    }

    /** Profile change: start the guide over from the top. */
    fun guideReset() {
        cancelTimer("idle")
        guideScroll = GuideScroll()
        remoteHeld = false; windowOffset = 0; focused = null
    }

    /** The cell on `channel` nearest `time` inside the window starting at `windowStart`. */
    fun cellAt(channel: Channel, time: Instant, windowStart: Instant): ProgramKey? {
        val we = windowStart.plusSeconds(windowSpan)
        val vis = slots(channel, windowStart, we)
        val pick = vis.firstOrNull { it.start <= time && time < it.end }
            ?: vis.minByOrNull { abs(it.start.toEpochMilli() - time.toEpochMilli()) }
            ?: slotOnAir(channel, time)
        return pick?.let { key(channel, it) }
    }

    /** The focused cell, falling back to what's airing now on the first visible channel. */
    fun guideKey(at: Instant = Instant.now()): ProgramKey? {
        val k = focused
        val p = k?.let { program(it) }
        if (k != null && p != null && !isHidden(p.channel.id)) {
            if (remoteHeld || p.slot.end > at) return k
            // Drifting and the focused program is over: follow the channel to what's on now (no mutation: this runs in view bodies).
            cellAt(p.channel, at, windowStart(at, offset = 0))?.let { return it }
        }
        val ch = visibleChannels.firstOrNull() ?: return null
        return cellAt(ch, at, windowStart(at, offset = 0))
    }

    // MARK: Tune / watch core

    /** Start watching `channelID`. `.live` joins in progress (unless the setting says start over and `force` is false);
     *  `.start` plays the slot beginning at `programStart` from its beginning. */
    fun tune(channelID: String, mode: Playback.Mode, programStart: Instant? = null, force: Boolean = false) {
        val ch = channel(channelID) ?: return
        val now = Instant.now()
        val onAir = slotOnAir(ch, now)
        val pb = if (mode == Playback.Mode.start && programStart != null) {
            Playback(channelID, Playback.Mode.start, programStart, slot = slot(ch, programStart))
        } else if ((ch.definition.tuneMode ?: settings.tuneMode) == TuneMode.start && !force && onAir != null) {
            Playback(channelID, Playback.Mode.start, onAir.start, slot = onAir)
        } else {
            Playback(channelID, Playback.Mode.live, onAir?.start ?: now, slot = onAir)
        }.let { p ->
            p.copy(tracks = playback?.tracks?.takeIf { it.itemID == p.slot?.item?.id },   // Start over / Back to live keep it; another program drops it
                   fromStart = p.mode == Playback.Mode.start)   // both .start cases above play from the slot's beginning
        }
        if (screen == Screen.guide) guidePark()
        cancelTimer("seek")   // a pending debounced skip must not reload the channel we just left
        closeEntry()          // nor a typed number tune over a tap's channel
        val same = screen == Screen.watch && playback?.channelID == channelID
        // Start over (the banner's, a break's "Watch again", Program info's, the guide's Play): the tape rewinds instead of
        // the static and the ident card, from where the program on screen was, or from the guide, from where its broadcast
        // is (none before it airs).
        val was = if (same && mode == Playback.Mode.start) watchInfo() else null
        val aired = if (!same && mode == Playback.Mode.start && programStart != null) now.secondsSince(programStart) else 0.0
        rewind = if (was != null && was.slot.start == programStart && was.position >= 1) Rewind(min(was.position, was.slot.item.runtime), now)
            else if (aired >= 1) pb.slot?.let { Rewind(min(aired, it.item.runtime), now) }
            else null
        if (rewind != null) later("rewind", Rewind.length) { rewind = null }
        val prev = playback?.channelID
        if (prev != null && prev != channelID) { persisted = persisted.copy(lastChannelID = prev); save() }
        screen = Screen.watch; overlay = null; focusIndex = 0
        playback = pb
        tunes += 1
        bannerVisible = watchInfo()?.isBreak != true; bannerFocus = -1; trackMenu = null   // in a break the card says it all; the banner would cover it
        if (!same) { if (rewind == null) { flips += 1; identSerial += 1 } else flips = 0 }
        bannerLater()
    }

    /** The selection to request for item `itemID`: the per-program override if the banner set one for that item, else
     *  the Settings preferences matched against the item's tracks (see `TrackSelection.preferred`). A later program on
     *  the same channel never inherits the override (its stream indices belong to another file). */
    fun trackSelection(tracks: List<MediaTrack>, itemID: String): TrackSelection {
        playback?.tracks?.takeIf { it.itemID == itemID }?.let { return it.selection }
        return TrackSelection.preferred(tracks, server?.kind, settings.audioLanguage, settings.subtitleMode,
                                        settings.subtitleLanguage ?: deviceLanguage, deviceLanguage)
    }

    val lastChannel: Channel?
        get() {
            val id = persisted.lastChannelID ?: return null
            val ch = channel(id) ?: return null
            return if (isHidden(id)) null else ch
        }

    /** Back to the guide, drifting from the channel that was on. */
    fun toGuide(from: String) {
        val list = visibleChannels
        val i = list.indexOfFirst { it.id == from }
        if (i >= 0) {
            guideScroll.resumeOffset = wrap((i - 1) * rowPitch)
            val now = Instant.now()
            focused = cellAt(list[i], now, windowStart(now, offset = 0))
        } else focused = null
        cancelTimer("banner"); closeEntry()
        screen = Screen.guide; overlay = null; focusIndex = 0; editingField = null; fullScreen = false
        windowOffset = 0; remoteHeld = false; bannerVisible = false; bannerFocus = -1; trackMenu = null; rewind = null
    }

    // MARK: Channels

    fun applyTuneOverride() {
        val n = tuneOverride ?: return
        if (screen != Screen.guide) return
        val ch = channels.firstOrNull { it.number == n } ?: return
        tuneOverride = null; tune(ch.id, Playback.Mode.live)
    }

    /** Automatic channels from the library + user edits + custom channels, resolved and sorted by number off the main
     *  thread: a library refresh evaluates every rule chip against thousands of items. */
    suspend fun rebuildChannelsAsync() {
        rebuildGen += 1; val gen = rebuildGen   // a newer rebuild (a full load landing mid-way) must not be overwritten
        var r = resolveChannels(library, freshItems, persisted.channelEdits, persisted.customChannels)
        if (gen != rebuildGen) return
        if (migrateGenreIDs(r)) r = resolveChannels(library, freshItems, persisted.channelEdits, persisted.customChannels)
        if (gen != rebuildGen) return
        autoDefinitions = r.auto; channels = r.channels
        if (screen == Screen.guide) holdForLaunch()   // the lineup came in under the guide's loader
    }

    /** Carry hidden flags and edits across a genre shelf's split into films + TV or merge back (GenreIDMigration).
     *  Returns true when an edit moved, so the lineup must re-resolve. */
    fun migrateGenreIDs(r: Resolved): Boolean {
        val hidden = persisted.profiles.map { it.hiddenChannelIDs }.toMutableList()
        val edits = persisted.channelEdits.toMutableMap()
        val sizes = HashMap<String, Int>()
        for (c in r.channels) sizes[c.id] = max(sizes[c.id] ?: 0, c.items.size)
        val m = GenreIDMigration.migrate(r.auto.map { it.id }, sizes, hidden, edits)
        if (!m.changed) return false
        persisted = persisted.copy(
            profiles = persisted.profiles.mapIndexed { i, p -> p.copy(hiddenChannelIDs = hidden[i]) },
            channelEdits = edits.toMap(),
        )
        save()
        return m.editMoved
    }

    /** Replace (or insert) one channel from its definition: one library filter instead of one per definition. */
    private fun splice(d: ChannelDefinition) {
        channels = (channels.filter { it.id != d.id } + ChannelBuilder.channels(listOf(d), library, freshItems)).sortedWith(byNumber)
    }

    /** Edit one field of a channel (automatic → recorded as an edit; custom → written into its definition).
     *  Copy-lambda: `updateChannel(id) { it.copy(name = n) }` (the Swift `{ $0.name = n }`). */
    fun updateChannel(id: String, change: (ChannelDefinition) -> ChannelDefinition) {
        val i = persisted.customChannels.indexOfFirst { it.id == id }
        if (i >= 0) {
            persisted = persisted.copy(customChannels = persisted.customChannels.toMutableList().also { it[i] = change(it[i]) })
        } else {
            val ch = channel(id)
            if (ch != null) {
                val d = change(ch.definition)
                val e = (persisted.channelEdits[id] ?: ChannelEdit()).copy(
                    name = d.name, number = d.number, order = d.order, trailerBreaks = d.trailerBreaks, tuneMode = d.tuneMode)
                persisted = persisted.copy(channelEdits = persisted.channelEdits + (id to e))
                save(); splice(d); return
            }
        }
        save()
        persisted.customChannels.firstOrNull { it.id == id }?.let { splice(it) }
    }

    fun resetChannel(id: String) {
        persisted = persisted.copy(channelEdits = persisted.channelEdits - id); save()
        autoDefinitions.firstOrNull { it.id == id }?.let { splice(it) }
    }
    fun deleteCustomChannel(id: String) {
        persisted = persisted.copy(customChannels = persisted.customChannels.filter { it.id != id }); save()
        channels = channels.filter { it.id != id }
    }
    fun addCustomChannel(d: ChannelDefinition) {
        persisted = persisted.copy(customChannels = persisted.customChannels + d); save(); splice(d)
    }
    fun setHidden(id: String, hidden: Boolean) {
        updateProfile { it.copy(hiddenChannelIDs = if (hidden) it.hiddenChannelIDs + id else it.hiddenChannelIDs - id) }
    }
    /** The movie whose trailer fills a break on `channel` after `slot`. Deterministic (channel + slot start) so the guide's
     *  preview window and the player agree. Whether the server actually has a trailer for it is only known at playback. */
    fun breakTrailerCandidate(channel: Channel, slot: Slot): MediaItem? {
        var pool = channel.items.filter { it.kind == MediaItem.Kind.movie && it.id != slot.item.id }
        if (pool.isEmpty()) pool = movies.filter { it.id != slot.item.id }
        if (pool.isEmpty()) return null
        val h = StableHash.fnv1a("${channel.id}|${slot.start.epochSecond}")
        return pool[(h % pool.size.toULong()).toInt()]
    }

    /** Lowest unused channel number from 50 up (the prototype's rule for custom channels). */
    val nextFreeNumber: Int
        get() { val used = channels.map { it.number }.toSet(); var n = 50; while (n in used) n += 1; return n }

    // MARK: Server / library

    fun connect(server: MediaServer, config: ServerConfig, token: String) {
        username = ""; password = ""   // a shared TV mustn't offer the next person this sign-in
        launchLoad = false   // the new link builds its own library
        Keychain.set(token, "token")
        persisted = persisted.copy(serverConfig = config); save()
        libraryGen += 1; freshItems = emptyList()
        this.server = server
    }

    /** Re-create the server client from the saved config (after a settings change the clients read, e.g. quality). */
    fun rebuildServer() {
        val cfg = persisted.serverConfig ?: return
        val token = Keychain.get("token") ?: (if (cfg.kind == ServerKind.demo) "" else null) ?: return
        server = cfg.makeServer(token, deviceID, settings.maxBitrateKbps)
    }

    /** The saved Plex address stopped answering: a new DHCP lease, a moved box, a new public IP. Try the other
     *  addresses plex.tv listed at sign-in (`probeable`), then ask plex.tv afresh with the account token, and switch to
     *  whichever answers as this server (its /identity must match the machineIdentifier). Returns true when the address
     *  changed, so the caller can retry once. At most one search a minute, so a server that is simply off isn't hammered. */
    suspend fun recoverPlexAddress(): Boolean {
        val cfg = persisted.serverConfig ?: return false
        val id = cfg.machineID
        if (cfg.kind != ServerKind.plex || id == null) return false
        if (lastPlexRecovery?.let { Instant.now().toEpochMilli() - it.toEpochMilli() <= 60_000 } == true) return false
        lastPlexRecovery = Instant.now()
        var url: String? = null
        var addresses = cfg.addresses
        var token: String? = null
        val a = cfg.addresses
        if (a != null && a.size > 1) url = PlexAuth.bestAddress(probeable(a, machineID = id), id, deviceID)
        if (url == null || url == cfg.baseURL) {
            val account = Keychain.get("plexAccount")
            val s = account?.let { runCatchingNonCancel { PlexAuth.servers(it, deviceID, only = id).firstOrNull() } }
            if (s != null) {
                url = s.baseURL; addresses = if (s.addresses.isEmpty()) addresses else s.addresses; token = s.accessToken
            }
        }
        if (url == null || url == cfg.baseURL || persisted.serverConfig?.machineID != id) return false   // still the same link
        if (backgrounded && !Platform.isTV) {
            // Away: no client until the way back, whose pick probes plex.tv's fresh list; the next failure may search again.
            persisted = persisted.copy(serverConfig = persisted.serverConfig?.copy(addresses = addresses)); save()
            token?.let { Keychain.set(it, "token") }
            lastPlexRecovery = null
            return false
        }
        persisted = persisted.copy(serverConfig = persisted.serverConfig?.copy(baseURL = url, addresses = addresses)); save()
        token?.let { Keychain.set(it, "token") }
        rebuildServer()
        return true
    }

    /** Phones and tablets move between networks: use whichever of the Plex server's addresses answers best right now
     *  (the LAN one at home, the public one away). Launch, return to the app, the Libraries screen and a network change
     *  while open (`networkChanged`) call this off TV; TV keeps the linked address. A plain-http link with nothing verified
     *  to move to drops the server (no token goes to it) until a later pick verifies an address again. Returns true when
     *  the address moved or the server was dropped or came back. */
    suspend fun pickPlexAddress(): Boolean {
        val cfg = persisted.serverConfig ?: return false
        val addresses = cfg.addresses ?: return false
        val id = cfg.machineID ?: return false
        val http = cfg.baseURL.startsWith("http://")
        if (addresses.size <= 1 && !http) return false
        val gen = ++pickGen
        val url = PlexAuth.bestAddress(probeable(addresses, machineID = id), id, deviceID)
        // A newer pick started meanwhile (it knows the newer network), the app left (the return picks again before anything
        // reloads: rebuilt now, a dropped plain-http link would carry the token on whatever network that return is on), or
        // the link changed.
        if (gen != pickGen || backgrounded || persisted.serverConfig?.machineID != id) return false
        if (url == null) {
            // The guide's empty state and the player's card say why; every load bails on a null server.
            if (!http) return false
            val had = server != null
            server = null; libraryLoading = false; libraryError = "Could not connect to the server."
            return had
        }
        if (url == persisted.serverConfig?.baseURL && server != null) return false   // still the same link
        val revived = server == null
        persisted = persisted.copy(serverConfig = persisted.serverConfig?.copy(baseURL = url)); save()
        rebuildServer()
        // The launch's own loads, held for this pick whatever the screen (not awaited: a Libraries fetch waits on the pick).
        if (revived && launchLoad) {
            launchLoad = false
            // Building's own load gave up on the dropped client: run it again (forced, with its reveal).
            if (screen == Screen.building) startBuild() else scope.launch { loadLibrary(); checkRecentlyAdded() }
        }
        else if (revived && library.isEmpty() && screen == Screen.guide) loadLibrary()   // the guide never loaded: nothing else loads it
        // The Libraries screen's fetch found no client: load its list now, busy at once and not awaited (the caller's reload
        // goes on). Its own fetch's pick runs busy, so no loop.
        if (revived && screen == Screen.libraries && !authBusy && authError != null) scope.launch(start = CoroutineStart.UNDISPATCHED) { fetchLibraries() }
        return true
    }

    /** The device is on another network (`id`: which one, null offline) while the app is open (MainActivity's
     *  default-network callback, off TV). Offline there's nothing to pick; in the background the return picks. A
     *  second's wait folds a burst of reports into one. */
    fun pathChanged(id: String?) {
        val last = network
        network = id ?: ""
        if (last == null) return   // the first report is the network the app started on
        if (id == null) { cancelTimer("network"); return }
        if (id == last || backgrounded) return
        later("network", 1.0) { scope.launch { networkChanged() } }
    }

    /** Pick the Plex address again, and if it moved (or the server was dropped or came back), reload what's on screen
     *  there: a movie tapped while the old address was still set retries at the new one. Network changes and the
     *  return to the app call this off TV. */
    suspend fun networkChanged() {
        if (!pickPlexAddress() || backgrounded) return   // hidden meanwhile: the return reloads
        foregroundSerial += 1
        if (screen == Screen.watch) tunes += 1
    }

    /** The addresses worth probing. Off TV a plain-http one counts only while plex.tv says this device is on the server's
     *  network: on a café's or hotel's LAN whoever answers at that private IP could pose as the server and read the token
     *  in the clear. A TV doesn't move networks, so it keeps them all. */
    private suspend fun probeable(addresses: List<PlexAddress>, machineID: String): List<PlexAddress> {
        if (Platform.isTV || addresses.none { it.url.startsWith("http://") }) return addresses
        val home = PlexAuth.onServerNetwork(machineID, Keychain.get("plexAccount"), deviceID)
        return if (home) addresses else addresses.filter { !it.url.startsWith("http://") }
    }

    fun signOut() {
        libraryGen += 1; launchLoad = false
        Keychain.delete("token"); Keychain.delete("plexAccount")
        persisted = persisted.copy(serverConfig = null, selectedLibraryIDs = null); save()
        server = null; library = emptyList(); channels = emptyList(); librarySections = emptyList(); freshItems = emptyList()
        username = ""; password = ""; pendingToken = null; foundServers = emptyList()   // the plex.tv token and server tokens too
        LibraryCache.clear()
        RemoteImages.cache.evictAll()   // artwork fetched with the old token (memory only; nothing of it is on disk)
        go(Screen.welcome)
    }

    /** Load the library: cached index first (instant launch), then refresh from the server unless the cache is under
     *  six hours old (`force` skips that gate: Settings › Rebuild, a changed library selection). A newer load, sign-out
     *  or connect supersedes this one at every suspension (generation counter), so a slow refresh can't write stale data. */
    suspend fun loadLibrary(refresh: Boolean = true, force: Boolean = false, retried: Boolean = false) {
        try { loadLibraryOnce(refresh, force, retried) }
        catch (e: CancellationException) { libraryLoading = false; throw e }   // Back on the Building screen: not a server error
    }

    private suspend fun loadLibraryOnce(refresh: Boolean, force: Boolean, retried: Boolean) {
        val server = server ?: run { libraryLoading = false; return }   // dropped (pickPlexAddress): Building must not spin
        libraryGen += 1; val gen = libraryGen
        val link = persisted.serverConfig?.link ?: ""
        if (library.isEmpty()) {
            val cached = LibraryCache.load(link)
            if (cached != null) {
                if (gen != libraryGen) return
                library = cached; rebuildChannelsAsync(); applyTuneOverride()
                if (gen != libraryGen) return
            }
        }
        if (!refresh) return
        val age = LibraryCache.age
        if (!force && library.isNotEmpty() && age != null && age < 6 * 3600) { libraryLoading = false; return }   // init set it
        libraryLoading = true; libraryError = null
        try {
            val sections = server.libraries()
            if (gen != libraryGen) return
            librarySections = sections.filter { it.itemCount != 0 }   // as fetchLibraries lists them: the Libraries screen may be up
            val chosen = persisted.selectedLibraryIDs?.let { ids -> sections.filter { ids.contains(it.id) } }
            val items = server.libraryItems(chosen)
            if (gen != libraryGen) return
            library = items; freshItems = emptyList()   // a full load has everything the open-the-app check found
            // Awaited, not detached: keeps ordering with signOut() → clear(). NonCancellable: Back on Building cancels this
            // load, but the write on the IO thread runs on regardless, so the check below must still run after it.
            // The channels are rebuilt in the same block, so the new library never sits beside the old lineup.
            val superseded = withContext(NonCancellable) {
                LibraryCache.save(library, link)
                if (gen != libraryGen) {
                    // Signed out or switched servers while it saved: that file belongs to no current link, so it goes.
                    if (persisted.serverConfig?.link != link) LibraryCache.clear()
                    true
                } else { rebuildChannelsAsync(); applyTuneOverride(); false }
            }
            if (superseded) return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (gen != libraryGen) return
            if (!retried && isUnreachable(e) && recoverPlexAddress()) {   // the server moved: try once at its new address
                loadLibrary(force = true, retried = true); return
            }
            libraryLoading = false   // before serverFailed: a dead token signs out and lands on Welcome
            libraryError = serverFailed(e)
            val err = libraryError
            if (channels.isNotEmpty() && err != null) toast(err)   // the cache is on screen, so the guide's empty-state line never shows
        }
        libraryLoading = false
    }

    /** Opening the app (launch, or back from the Home screen): ask the server for what's been added since the last full
     *  load, so Just Added, New Releases and New This Season are current. Every other channel keeps today's lineup until
     *  the next full load. Best effort and silent: on any error the guide keeps what it has. */
    suspend fun checkRecentlyAdded() {
        val server = server ?: return
        if (library.isEmpty() || libraryLoading || checkingRecent) return
        checkingRecent = true
        val gen = libraryGen
        try {
            val chosen = persisted.selectedLibraryIDs?.let { ids ->
                (librarySections.ifEmpty { server.libraries() }).filter { ids.contains(it.id) }
            }
            // ponytail: the newest 50 movies and 50 episodes per library; a bigger import waits for the next full load.
            val recent = server.recentlyAdded(chosen, limit = 50)
            if (gen != libraryGen || libraryLoading) return
            val known = library.mapTo(HashSet()) { it.id }
            val fresh = recent.filter { it.id !in known }
            if (fresh.map { it.id } == freshItems.map { it.id }) return
            freshItems = fresh
            rebuildChannelsAsync()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            checkingRecent = false
        }
    }

    /** Every server catch routes here: a dead token signs the device out; anything else becomes one short user-safe line. */
    fun serverFailed(error: Throwable): String {
        if (isUnreachable(error)) {   // a stream or preview that can't reach the server: look for it, then reload what's on screen
            scope.launch { if (recoverPlexAddress()) { tunes += 1; foregroundSerial += 1 } }
        }
        if (error is MediaServerError.unauthorized) {
            val msg = "${server?.kind?.displayName ?: "The server"} signed this device out. Sign in again."
            toast(msg); signOut()
            return msg
        }
        val line = errorText(error).takeWhile { it !in "\n<{" }
        return line.take(120).trim(' ', ':')
    }

    fun save() = persisted.save()
}

/** JSON cache of the library index so the guide is up before the server answers. Lives in the cache dir (purgeable, like
 *  the Swift Caches). Encodes/decodes several MB off the main thread. */
object LibraryCache {
    private val file: File get() = File(AppModel.cacheDir, "library.json")

    /** The file holds the items and the link they came from (`ServerConfig.link`): a save that lands after a sign-out or
     *  a switch to another server is never read back under the new one. */
    suspend fun load(link: String): List<MediaItem>? = withContext(Dispatchers.IO) {
        try {
            val o = JSONObject(file.readText())
            if (o.getString("link") != link) null else o.getJSONArray("items").objects().map { MediaItem.fromJson(it) }
        } catch (e: Exception) { null }   // no cache yet, an older schema, or another link's: the refresh replaces it
    }

    suspend fun save(items: List<MediaItem>, link: String) = withContext(Dispatchers.IO) {
        try {
            val tmp = File(AppModel.cacheDir, "library.json.tmp")
            tmp.writeText(JSONObject().put("link", link).put("items", JSONArray(items.map { it.toJson() })).toString())
            if (!tmp.renameTo(file)) error("rename failed")
            AppModel.defaults.edit().putLong("libraryCachedAt", System.currentTimeMillis()).apply()
        } catch (e: Exception) { Log.w("LibraryCache", "save: $e") }
    }

    fun clear() {
        file.delete(); File(AppModel.cacheDir, "library.json.tmp").delete()
        AppModel.defaults.edit().remove("libraryCachedAt").apply()
    }

    /** Seconds since the cache was written (tracked in preferences, not a file timestamp). */
    val age: Double?
        get() = if (AppModel.defaults.contains("libraryCachedAt"))
            (System.currentTimeMillis() - AppModel.defaults.getLong("libraryCachedAt", 0)) / 1000.0 else null
}
