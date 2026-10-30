package com.rerunguide.app

import com.rerunguide.core.*
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** One entry of the Settings list (port of the prototype's setRows). `key == null` is a section header. */
data class SettingsRow(val key: Key?, val label: String, val value: String = "", val desc: String = "") {
    enum class Key { theme, crt, auto, idle, attract, tune, breaks, idents, quality, audio, subs, subLang, profile, server, rebuild, signout, about, saver, spots }
    val id: String get() = key?.name ?: label
    companion object { fun head(label: String) = SettingsRow(null, label) }
}

/** The channel editor's detail fields, in order (the prototype's F array). The Swift `name` case is `title` (Kotlin
 *  reserves `name` on enums); `ChannelField(rawValue: i)` → `ChannelField.entries.getOrNull(i)`, `rawValue` → `ordinal`. */
@Suppress("EnumEntryName")
enum class ChannelField { title, number, order, breaks, tune, show, delete }

/* Profiles, Settings and the Channel editor (port of the prototype's k_profiles, k_settings/setRows, k_channels/commitNum/chIdx). */

/** "Join in progress" / "From the beginning", or "Default · …" when the channel follows the app setting. */
fun AppModel.Companion.tuneLabel(mode: TuneMode?, default: TuneMode): String {
    val name = { m: TuneMode -> if (m == TuneMode.live) "Join in progress" else "From the beginning" }
    return mode?.let(name) ?: "Default · ${name(default)}"
}

/** Settings › About. The beacon is Soyboi's other project (soyboi.tech). Ten lines at most: the pane is 600 high. */
val AppModel.Companion.aboutText: String
    get() = "From Soyboi LLC in San Diego, makers of the beacon: a pocket counter-surveillance device that spots license-plate " +
        "readers, body cams and drones. soyboi.tech\n" +
        "Rerun Guide talks to your media server, Plex or Emby if you sign in with them, and Apple for the Sample station. " +
        "No analytics, no tracking, and nothing about you is sent to us.\n" +
        "Privacy: rerunguide.tv/privacy · Support: rerunguide.tv/support"

val AppModel.Companion.version: String
    get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

// MARK: Profiles

/** Avatar colors for new profiles, rotating by profile count (the prototype's seed colors + its "Guest" purple). */
val AppModel.Companion.profilePalette: List<String> get() = listOf("#f0a33a", "#7d97ff", "#7fd39a", "#c9a0ff", "#ff8a80")

/** Actions in the row Play/Pause opens under a tile: Rename, and Delete while another profile remains. */
val AppModel.profileActions: List<String> get() = if (persisted.profiles.size > 1) listOf("Rename", "Delete") else listOf("Rename")

fun AppModel.pressProfiles(cmd: RemoteCommand) {
    val profiles = persisted.profiles
    val m = profileMenu
    if (m != null) {
        when (cmd) {
            RemoteCommand.left, RemoteCommand.right ->
                profileMenuIndex = max(0, min(profileActions.size - 1, profileMenuIndex + (if (cmd == RemoteCommand.right) 1 else -1)))
            RemoteCommand.back -> profileMenu = null
            RemoteCommand.select -> {
                profileMenu = null
                if (profileMenuIndex == 0) { editingField = "profileName"; return }
                val p = profiles[m]
                persisted = persisted.copy(profiles = persisted.profiles.filterIndexed { i, _ -> i != m })
                if (p.id == persisted.currentProfileID) { switchProfile(persisted.profiles[0].id); guideReset() } else save()
                focusIndex = min(focusIndex, persisted.profiles.size)
                toast("${p.name} is gone.")
            }
            else -> {}
        }
        return
    }
    if (step(cmd, count = profiles.size + (if (profiles.size < 5) 1 else 0), horizontal = true)) return
    if (cmd == RemoteCommand.back) { go(Screen.guide); return }
    if (cmd == RemoteCommand.play && focusIndex < profiles.size) { profileMenu = focusIndex; profileMenuIndex = 0; return }
    if (cmd != RemoteCommand.select) return
    if (focusIndex >= profiles.size) {
        val palette = AppModel.profilePalette
        persisted = persisted.copy(profiles = profiles + Profile(UUID.randomUUID().toString().uppercase(), "Profile ${profiles.size + 1}",
            palette[profiles.size % palette.size], ThemeID.LATE, emptySet()))
        save(); editingField = "profileName"; return   // focusIndex already points at the new tile
    }
    val p = profiles[focusIndex]
    // Switching to the profile already in use would do nothing, so Select there offers Rename / Delete (as Play/Pause does).
    if (p.id == persisted.currentProfileID) { profileMenu = focusIndex; profileMenuIndex = 0; return }
    switchProfile(p.id); guideReset(); go(Screen.guide)
    toast("Hi, ${p.name}.")
}

/** Commit the rename typed on the Profiles screen; an empty name keeps the old one. */
fun AppModel.commitProfileName(index: Int, name: String) {
    try {
        val n = name.trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }   // CharacterSet.whitespaces
        if (index !in persisted.profiles.indices || n.isEmpty()) return
        persisted = persisted.copy(profiles = persisted.profiles.toMutableList().also { it[index] = it[index].copy(name = n) }); save()
    } finally { editingField = null }
}

// MARK: Settings

val AppModel.settingsRows: List<SettingsRow>
    get() {
        val on = { b: Boolean -> if (b) "On" else "Off" }
        val s = settings; val p = profile
        val films = library.count { it.kind == MediaItem.Kind.movie }
        val shows = library.mapNotNull { if (it.kind == MediaItem.Kind.episode) (it.seriesID ?: it.seriesTitle ?: it.title) else null }.toSet().size
        val service = server?.kind?.displayName ?: "your server"
        val anyInput = if (Platform.isTV) "Any button" else "Any ${Platform.verb.lowercase()}"
        val fmt = NumberFormat.getIntegerInstance()
        return listOf(
            SettingsRow.head("Appearance"),
            SettingsRow(SettingsRow.Key.theme, "Theme", p.theme.displayName, "Late Show is warm and rounded. Midnight Movie is near-black with neon red, in a condensed face. Each profile keeps its own."),
            SettingsRow(SettingsRow.Key.crt, "Old-TV screen lines", on(s.crtLines), "A faint CRT texture over the guide’s preview window. The show itself is never touched."),
            SettingsRow.head("Guide"),
            SettingsRow(SettingsRow.Key.auto, "Scroll listings on their own", on(s.autoScroll), if (Platform.isTV) "When nobody’s touching the remote, the guide drifts through the channels like the old preview channel." else "When you leave it alone, the guide drifts through the channels like the old preview channel."),
            SettingsRow(SettingsRow.Key.idle, "Start drifting again after", "${s.idleSeconds} seconds", if (Platform.isTV) "How long the guide waits after your last button press before it starts scrolling again." else "How long the guide waits after you stop browsing before it starts scrolling again."),
            SettingsRow(SettingsRow.Key.attract, "Attract mode after", if (s.attractMinutes == 0) "Off" else "${s.attractMinutes} min", "Leave the guide alone this long and it settles into a slow storefront wall: a bigger preview, a big clock, listings drifting by. $anyInput wakes it."),
            SettingsRow(SettingsRow.Key.saver, "Screensaver", mapOf("logo" to "Bouncing logo", "soyboi" to "Bouncing Soyboi")[s.screensaver] ?: "Storefront wall", "What attract mode shows. The storefront wall keeps the listings drifting under a big clock. The bouncing logo is the Rerun Guide logo drifting around the screen the way the DVD logo used to; Bouncing Soyboi does the same with the soyboi.tech mascot. $anyInput wakes the guide."),
            SettingsRow.head("Playback"),
            SettingsRow(SettingsRow.Key.tune, "When you tune in", if (s.tuneMode == TuneMode.live) "Join in progress" else "Start from the beginning", if (Platform.isTV) "Join in progress keeps everyone on the same schedule. Press Play/Pause on any program to start it over either way." else "Join in progress keeps everyone on the same schedule. Pick Start from the beginning on any program to start it over either way."),
            SettingsRow(SettingsRow.Key.breaks, "Trailers between programs", on(s.trailerBreaks), "Fills the gap between programs with trailers from your library. Off shows a station card instead."),
            SettingsRow(SettingsRow.Key.spots, "Messages from Soyboi", on(s.houseSpots), "One in 15 trailer breaks opens with a 30-second spot for the beacon, Soyboi’s other project. It’s how Rerun Guide stays free. Turn it off and the trailers start right away. To report a spot, write to support@soyboi.tech."),
            SettingsRow(SettingsRow.Key.idents, "Station idents", on(s.stationIdents), "A quick channel card and a two-note sting when you change channels, and a ‘We’ll be right back’ card before trailer breaks."),
            SettingsRow(SettingsRow.Key.quality, "Streaming quality", if (s.maxBitrateKbps == 0) "Original" else "${s.maxBitrateKbps / 1000} Mbps", "Caps the stream the server sends. Lower it for remote servers or a slow connection."),
            SettingsRow(SettingsRow.Key.audio, "Audio language", MediaTrack.languageName(s.audioLanguage) ?: "Default", "Picks this language’s audio track when a program has one. Otherwise the server’s default plays. Takes effect on the next program."),
            SettingsRow(SettingsRow.Key.subs, "Subtitles", mapOf("off" to "Off", "on" to "On")[s.subtitleMode] ?: "Forced only", "On shows subtitles for every program that has them, in the language below. Forced only shows just the foreign-dialogue subtitles a program marks as needed, like broadcast TV. Off hides them all."),
            SettingsRow(SettingsRow.Key.subLang, "Subtitle language", MediaTrack.languageName(s.subtitleLanguage) ?: "Device language (${MediaTrack.languageName(AppModel.deviceLanguage) ?: "English"})", "Which subtitles to show when they’re on. If a program doesn’t have this language, the first full subtitle track plays instead."),
            SettingsRow.head("Profiles"),
            SettingsRow(SettingsRow.Key.profile, "Who’s watching", p.name, "Each profile keeps its own lineup of channels."),
            SettingsRow.head("Library"),
            SettingsRow(SettingsRow.Key.server, "Server", serverLabel, "Choose which libraries feed the schedule. ${fmt.format(films)} films and ${fmt.format(shows)} shows on the schedule."),
            SettingsRow(SettingsRow.Key.rebuild, "Rebuild automatic channels", desc = "Reads your library again and refreshes the automatic channels. Your edits and custom channels stay put."),
            SettingsRow(SettingsRow.Key.signout, "Sign out", if (signOutArmed) "${Platform.verb} again to sign out" else "", "Disconnects this ${Platform.device} from $service. Your channels are kept for when you sign back in."),
            SettingsRow.head("About"),
            SettingsRow(SettingsRow.Key.about, "About Rerun Guide", AppModel.version, AppModel.aboutText),
        )
    }

fun AppModel.pressSettings(cmd: RemoteCommand) {
    val keys = settingsRows.mapNotNull { it.key }
    if (cmd != RemoteCommand.select) signOutArmed = false   // any focus move (or Back) disarms Sign out
    if (step(cmd, count = keys.size)) return
    if (cmd == RemoteCommand.back) { go(Screen.guide); return }
    if (cmd != RemoteCommand.select || focusIndex !in keys.indices) return
    when (keys[focusIndex]) {
        SettingsRow.Key.theme -> toggleTheme()
        SettingsRow.Key.crt -> updateSettings { it.copy(crtLines = !it.crtLines) }
        SettingsRow.Key.auto -> updateSettings { it.copy(autoScroll = !it.autoScroll) }
        SettingsRow.Key.breaks -> updateSettings { it.copy(trailerBreaks = !it.trailerBreaks) }
        SettingsRow.Key.spots -> updateSettings { it.copy(houseSpots = !it.houseSpots) }
        SettingsRow.Key.idents -> updateSettings { it.copy(stationIdents = !it.stationIdents) }
        SettingsRow.Key.idle -> updateSettings { it.copy(idleSeconds = mapOf(5 to 8, 8 to 15, 15 to 30, 30 to 5)[it.idleSeconds] ?: 8) }
        SettingsRow.Key.saver -> updateSettings { it.copy(screensaver = nextIn(listOf("wall", "logo", "soyboi"), it.screensaver)) }
        SettingsRow.Key.attract -> updateSettings { it.copy(attractMinutes = mapOf(0 to 1, 1 to 3, 3 to 5, 5 to 10)[it.attractMinutes] ?: 0) }
        SettingsRow.Key.tune -> updateSettings { it.copy(tuneMode = if (it.tuneMode == TuneMode.live) TuneMode.start else TuneMode.live) }
        SettingsRow.Key.quality -> {   // 2 Mbps exists because Plex Relay caps there. The clients read it at init; a playing stream keeps its quality until the next load.
            updateSettings { it.copy(maxBitrateKbps = mapOf(0 to 20000, 20000 to 8000, 8000 to 4000, 4000 to 2000)[it.maxBitrateKbps] ?: 0) }
            if (server != null) rebuildServer()   // a dropped one (pickPlexAddress) waits for a pick that verifies it
        }
        SettingsRow.Key.audio -> updateSettings { it.copy(audioLanguage = nextIn(listOf<String?>(null) + AppModel.languages, it.audioLanguage)) }
        SettingsRow.Key.subs -> updateSettings { it.copy(subtitleMode = nextIn(listOf("forced", "on", "off"), it.subtitleMode)) }
        SettingsRow.Key.subLang -> updateSettings { it.copy(subtitleLanguage = nextIn(listOf<String?>(null) + AppModel.languages, it.subtitleLanguage)) }
        SettingsRow.Key.profile -> {
            val i = persisted.profiles.indexOfFirst { it.id == persisted.currentProfileID }.let { if (it < 0) 0 else it }
            go(Screen.profiles); focusIndex = i
        }
        SettingsRow.Key.about -> {}   // read-only: the credit sits in the description pane
        SettingsRow.Key.server -> { librariesOrigin = Screen.settings; go(Screen.libraries) }
        SettingsRow.Key.rebuild -> scope.launch {
            loadLibrary(force = true); toast(libraryError ?: "Channels rebuilt. Your edits and custom channels stayed put.")
        }
        SettingsRow.Key.signout -> {
            if (!signOutArmed) {
                signOutArmed = true
                later("signout", seconds = 5.0) { signOutArmed = false }
                return
            }
            signOutArmed = false; signOut()
        }
    }
}

/** The languages the Playback rows cycle through (ISO 639-2/B, as Plex and Emby report them). */
val AppModel.Companion.languages: List<String> get() = listOf("eng", "spa", "fre", "ger", "ita", "por", "jpn", "kor", "chi")
private fun <T> nextIn(list: List<T>, after: T): T = list[(list.indexOf(after) + 1) % list.size]

// MARK: Channel editor

/** Position in the editor list: 0 = "New channel", i + 1 = channels[i] (the prototype's chIdx). */
val AppModel.channelIndex: Int
    get() = channelSelection?.let { id -> channels.indexOfFirst { it.id == id }.takeIf { it >= 0 } }?.let { it + 1 } ?: 0
val AppModel.selectedChannel: Channel? get() = channelSelection?.let { channel(it) }

fun AppModel.pressChannels(cmd: RemoteCommand) {
    val list = channels; val idx = channelIndex
    if (channelColumn == 0) {
        when (cmd) {
            RemoteCommand.up, RemoteCommand.down -> {
                val j = max(0, min(list.size, idx + (if (cmd == RemoteCommand.down) 1 else -1)))
                channelSelection = if (j == 0) null else list[j - 1].id
            }
            RemoteCommand.back -> go(Screen.guide)
            RemoteCommand.right, RemoteCommand.select -> {
                if (idx == 0) { overlay = Overlay.newChannel; newChannelStep = 0; newChannelType = null; focusIndex = 0 }
                else { channelColumn = 1; channelFieldIndex = 0 }
            }
            else -> {}
        }
        return
    }
    when (cmd) {
        RemoteCommand.up, RemoteCommand.down -> {
            channelFieldIndex = max(0, min(ChannelField.entries.size - 1, channelFieldIndex + (if (cmd == RemoteCommand.down) 1 else -1)))
            return
        }
        RemoteCommand.left, RemoteCommand.back -> { channelColumn = 0; return }
        RemoteCommand.select -> {}
        else -> return
    }
    val ch = selectedChannel ?: return
    val field = ChannelField.entries.getOrNull(channelFieldIndex) ?: return
    when (field) {
        ChannelField.title -> editingField = "chName"
        ChannelField.number -> { numberDraft = ch.number.toString(); editingField = "chNum" }
        ChannelField.order -> updateChannel(ch.id) { it.copy(order = it.order.next) }
        ChannelField.breaks -> updateChannel(ch.id) { it.copy(trailerBreaks = !it.trailerBreaks) }
        ChannelField.tune -> updateChannel(ch.id) {
            it.copy(tuneMode = if (it.tuneMode == null) TuneMode.live else if (it.tuneMode == TuneMode.live) TuneMode.start else null)
        }
        ChannelField.show -> setHidden(ch.id, !isHidden(ch.id))
        ChannelField.delete -> {
            if (ch.isCustom) {
                val next = if (idx < list.size) list[idx] else if (idx >= 2) list[idx - 2] else null
                deleteCustomChannel(ch.id); channelColumn = 0; channelSelection = next?.id
                toast("Channel ${ch.number} is off the air.")
            } else {
                resetChannel(ch.id)
                // The automatic number may have been given to another channel meanwhile: keep the edited one then.
                val auto = channel(ch.id)
                if (auto != null && channels.any { it.number == auto.number && it.id != ch.id }) {
                    updateChannel(ch.id) { it.copy(number = ch.number) }
                }
                toast("Channel ${channel(ch.id)?.number ?: ch.number} is back to automatic.")
            }
        }
    }
}

/** A channel editor row's label and value (the TV's detail pane and the portrait editor). */
fun AppModel.fieldText(field: ChannelField, ch: Channel): Pair<String, String> = when (field) {
    ChannelField.number -> "Channel number" to (numberDraft ?: ch.number.toString())
    ChannelField.order -> "Order" to ch.order.rawValue
    ChannelField.breaks -> "Trailer breaks" to (if (ch.trailerBreaks) "On" else "Off")
    ChannelField.tune -> "When you tune in" to AppModel.tuneLabel(ch.definition.tuneMode, settings.tuneMode)
    ChannelField.show -> "Show in ${profile.name}’s guide" to (if (isHidden(ch.id)) "Off" else "On")
    ChannelField.delete -> (if (ch.isCustom) "Delete channel" else "Reset to automatic") to ""
    ChannelField.title -> "Name" to ch.name
}

/** Validate and apply the channel-number draft (the prototype's commitNum). */
fun AppModel.commitNumber() {
    try {
        val ch = selectedChannel ?: return
        val v = numberDraft?.toIntOrNull() ?: return
        if (v == ch.number) return
        if (v !in 1..999) { toast("Pick a number from 1 to 999."); return }
        if (channels.any { it.number == v && it.id != ch.id }) { toast("Channel $v is taken."); return }
        updateChannel(ch.id) { it.copy(number = v) }
    } finally { numberDraft = null }
}
