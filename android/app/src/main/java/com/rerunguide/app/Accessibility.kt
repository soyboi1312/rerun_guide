package com.rerunguide.app

import com.rerunguide.core.*
import java.time.Instant
import kotlin.math.ceil

/* Back pass-through and the one TalkBack string. The stage's remote-capture element is the app's single accessibility
 * element (screens are pure renderings with nothing focusable), so `accessibilityDescription` is what TalkBack reads
 * after every press. Plain strings that mirror what each screen draws for the focused thing. */

/** Back at the app's top level returns to the Home screen. Every other screen has an in-app Back.
 *  Attract mode keeps Back: the first press of any button wakes the guide, Back included. So does the number box. */
val AppModel.backExitsApp: Boolean
    get() = overlay == null && channelEntry?.missing != false && ((screen == Screen.guide && !remoteHeld && !attracting) || screen == Screen.welcome)

/** The number box while it's up (it has the remote while digits are being typed), else what's focused. */
val AppModel.accessibilityDescription: String
    get() {
        val e = channelEntry
        if (overlay != null || e == null) return focusDescription
        if (e.missing) return "No channel ${e.digits.toIntOrNull() ?: 0}. " + focusDescription
        return "Channel number ${e.digits}" + (entryChannel?.let { ". Channel ${it.number} ${it.name}" } ?: "")
    }

private val AppModel.focusDescription: String
    get() {
        when (val o = overlay) {
            Overlay.options -> {
                val rows = AppModel.OptionRow.entries
                val label = when (rows[minOf(focusIndex, rows.size - 1)]) {
                    AppModel.OptionRow.profile -> "${profile.name}, Switch profile"
                    AppModel.OptionRow.guide -> "Guide, all channels"
                    AppModel.OptionRow.movies -> "Movies guide"
                    AppModel.OptionRow.tv -> "TV Shows guide"
                    AppModel.OptionRow.search -> "Search"
                    AppModel.OptionRow.channels -> "Channels"
                    AppModel.OptionRow.settings -> "Settings"
                    AppModel.OptionRow.theme -> "Theme, ${profile.theme.displayName}. Left and Right switch, ${Platform.select} closes the menu"
                }
                return "Options. $label"
            }
            is Overlay.info -> {
                val (ch, slot) = program(o.key) ?: return "Program info"
                val acts = infoActions(o.key)
                return describe(ch, slot) + (if (focusIndex in acts.indices) ". ${acts[focusIndex].label}" else "")
            }
            Overlay.newChannel -> {
                if (newChannelStep == 0) {
                    val kinds = ChannelBuilder.CandidateKind.entries
                    return "New channel. ${kinds[minOf(focusIndex, kinds.size - 1)].rawValue}"
                }
                val items = candidateCache
                if (focusIndex !in items.indices) return "New channel. Nothing to pick"
                return "New channel. ${items[focusIndex].name}, ${items[focusIndex].count} ${if (newChannelType == ChannelBuilder.CandidateKind.series) "episodes" else "films"}"
            }
            Overlay.makeChannel -> {
                val e = searchSelection ?: return "Make a channel"
                val acts = makeActions
                return "${e.title}. ${if (makePeople == null) airsText(e) else "Pick who the channel follows"}" + (if (focusIndex in acts.indices) ". ${acts[focusIndex].label}" else "")
            }
            null -> {}
        }
        when (screen) {
            Screen.welcome -> {
                val cards = AppModel.welcomeCards
                return cards[minOf(focusIndex, cards.size - 1)].let { "${it.first}. ${it.second}" }
            }
            Screen.address -> {
                val row = if (focusIndex == 0) "Server address, ${address.ifEmpty { "empty" }}"
                    else if (focusIndex <= foundServers.size) "${foundServers[focusIndex - 1].name}, ${foundServers[focusIndex - 1].baseURL}"
                    else "Continue"
                return row + errorLine
            }
            Screen.link -> {
                val code = linkCode?.let { c -> "Code ${c.map { it.toString() }.joinToString(" ")}" } ?: "Waiting for the code"
                val button = if (serviceKind == ServerKind.jellyfin) "Sign in with a password instead" else "Type a server address instead"
                return "Sign in to ${serviceKind.displayName}. $code. $button$errorLine"
            }
            Screen.login -> {
                val row = if (focusIndex == 0) "Username, ${username.ifEmpty { "empty" }}"
                    else if (focusIndex == 1) "Password, ${if (password.isEmpty()) "empty" else "entered"}" else "Sign in"
                return row + errorLine
            }
            Screen.libraries -> {
                if (focusIndex !in librarySections.indices) return "Continue$errorLine"
                val s = librarySections[focusIndex]
                return "${s.title}, ${if (s.id in librarySelection) "On" else "Off"}"
            }
            Screen.building -> {
                libraryError?.let { return "$it. Retry" }
                return if (buildDone) "Sign-on complete. Start flipping" else "Configuring channels"
            }
            Screen.profiles -> {
                val profiles = persisted.profiles
                if (focusIndex !in profiles.indices) return "Add profile"
                val p = profiles[focusIndex]
                val m = profileMenu
                if (m != null && m in profiles.indices) return "${profileActions[minOf(profileMenuIndex, profileActions.size - 1)]}, for ${profiles[m].name}"
                val current = if (p.id == persisted.currentProfileID) ", watching now, ${Platform.select} to rename" else ""
                return "${p.name}, ${channels.count { it.id !in p.hiddenChannelIDs }} channels$current"
            }
            Screen.guide -> {
                val k = guideKey()
                val pr = k?.let { program(it) } ?: return if (libraryLoading) "Loading your library" else "Nothing to schedule yet"
                return describe(pr.channel, pr.slot)
            }
            Screen.watch -> {
                val info = watchInfo() ?: return "Watching"
                if (SpotOnAir.started != null) {   // the house spot, labeled "Ad" on screen
                    return "Ad, Message from Soyboi. " + if (!SpotOnAir.skippable) "You can skip it in a few seconds"
                        else if (Platform.isTV) "${Platform.select} to skip" else "${Platform.verb} Skip to skip it"
                }
                var s = describe(info.channel, info.slot)
                if (playback?.paused == true) s += ". Paused"
                if (info.isBreak && Platform.isTV) {   // touch: the card's own buttons
                    info.next?.let { s += ". ${Platform.select} watches ${it.item.displayTitle} now" }
                    s += ". Play Pause watches ${info.slot.item.displayTitle} again"
                }
                val acts = watchActions()
                if (bannerVisible && bannerFocus in acts.indices) s += if (barFocused) ". Progress, Left and Right skip 10 seconds" else ". ${acts[bannerFocus].label}"
                return s
            }
            Screen.channels -> {
                val ch = selectedChannel ?: return "New channel, From a director, an actor, or one show"
                val head = "Channel ${ch.number} ${ch.name}"
                val field = ChannelField.entries.getOrNull(channelFieldIndex)
                if (channelColumn != 1 || field == null) {
                    return "$head, ${if (isHidden(ch.id)) "Hidden" else if (ch.isCustom) "Custom" else "Auto"}"
                }
                val row = when (field) {
                    ChannelField.title -> "Name, ${ch.name}"
                    ChannelField.number -> "Channel number, ${numberDraft ?: ch.number.toString()}"
                    ChannelField.order -> "Order, ${ch.order.rawValue}"
                    ChannelField.breaks -> "Trailer breaks, ${if (ch.trailerBreaks) "On" else "Off"}"
                    ChannelField.tune -> "When you tune in, ${AppModel.tuneLabel(ch.definition.tuneMode, default = settings.tuneMode)}"
                    ChannelField.show -> "Show in ${profile.name}’s guide, ${if (isHidden(ch.id)) "Off" else "On"}"
                    ChannelField.delete -> if (ch.isCustom) "Delete channel" else "Reset to automatic"
                }
                return "$head. $row"
            }
            Screen.search -> {
                val e = searchSelection ?: run {
                    val found = if (searchQuery.isEmpty()) "" else if (searchResults.isEmpty()) ". Nothing matches" else ". ${searchResults.size} found"
                    return "Search, ${searchQuery.ifEmpty { "empty" }}$found"
                }
                return "${e.title}, ${listOfNotNull(e.year?.toString(), if (e.isShow) "Show" else "Movie").joinToString(", ")}. ${airsText(e)}"
            }
            Screen.settings -> {
                val rows = settingsRows.filter { it.key != null }
                if (focusIndex !in rows.indices) return "Settings"
                val r = rows[focusIndex]
                return listOf(r.label, r.value).filter { it.isNotEmpty() }.joinToString(", ") + ". " + r.desc
            }
        }
    }

private val AppModel.errorLine: String get() = authError?.let { ". $it" } ?: ""

/** "Channel 4 Gumshoe. Columbo, Any Old Port in a Storm. On now, 23 minutes in." */
private fun AppModel.describe(ch: Channel, slot: Slot): String {
    val now = Instant.now()
    val when_ = when (status(slot, now)) {
        ProgramStatus.live -> "On now, ${(now.secondsSince(slot.start) / 60).toInt()} minutes in"
        ProgramStatus.`break` -> "On now, between programs"
        ProgramStatus.later -> "Starts in ${ceil(slot.start.secondsSince(now) / 60).toInt()} minutes"
        ProgramStatus.past -> "Aired earlier"
    }
    val what = listOf(slot.item.displayTitle, slot.item.subtitle).filter { it.isNotEmpty() }.joinToString(", ")
    return "Channel ${ch.number} ${ch.name}. $what. $when_"
}
