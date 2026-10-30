package com.rerunguide.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rerunguide.core.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/** What the picture is showing right now (port of the prototype's wInfo). */
data class WatchInfo(
    val channel: Channel,
    val slot: Slot,
    /** Seconds into the slot. `runtime ≤ position < duration` is the break after the program. */
    val position: Double,
    val live: Boolean,
    /** What the channel's current schedule airs at `slot.end` (what live rolls into). */
    val next: Slot?,
) {
    val isBreak: Boolean get() = position >= slot.item.runtime
}

/** One banner button: the Swift `(label: String, run: () -> Void)` tuple. */
class WatchAction(val label: String, val run: () -> Unit)

/* Watching: banner, channel up/down, last channel, pause/time-shift (port of the prototype's k_watch/wInfo/flip/togglePause/watchActs). */

fun AppModel.watchInfo(at: Instant = Instant.now()): WatchInfo? {
    val pb = playback ?: return null
    val ch = channel(pb.channelID) ?: visibleChannels.firstOrNull() ?: return null
    // The slot tune() snapshotted, unless its channel is gone: a library refresh reshuffles every lineup and the
    // program must not change under the viewer. Live rolls into the current schedule once the snapshot ends.
    val kept = if (ch.id == pb.channelID) pb.slot else null
    if (pb.mode == Playback.Mode.live) {
        val s = kept?.takeIf { at < it.end } ?: slotOnAir(ch, at) ?: return null
        return WatchInfo(ch, s, at.secondsSince(s.start), live = true, next = slotOnAir(ch, s.end))
    }
    val s = kept ?: slot(ch, pb.programStart) ?: slotOnAir(ch, at) ?: return null
    return WatchInfo(ch, s, pb.offset + (if (pb.paused) 0.0 else at.secondsSince(pb.startedAt)), live = false, next = slotOnAir(ch, s.end))
}

fun AppModel.pressWatch(cmd: RemoteCommand) {
    val pb = playback ?: return
    if (trackMenu != null && cmd != RemoteCommand.play) { pressTrackMenu(cmd); return }
    when (cmd) {
        // From the banner's buttons Up goes to its progress bar, and Down comes back; otherwise they change channels.
        RemoteCommand.up -> if (bannerVisible && bannerFocus >= 0) { barFocused = true; bannerLater() } else flip(1)
        RemoteCommand.down -> if (bannerVisible && bannerFocus >= 0 && barFocused) { barFocused = false; bannerLater() } else flip(-1)
        // The info banner alone never holds Back; full screen goes back to portrait Now playing.
        RemoteCommand.back -> if (bannerVisible && bannerFocus >= 0) hideBanner() else if (fullScreen) fullScreen = false else toGuide(pb.channelID)
        RemoteCommand.play -> if (watchInfo()?.isBreak == true) restartFromBreak() else togglePause()   // pausing a break holds nothing
        RemoteCommand.left, RemoteCommand.right, RemoteCommand.select -> {
            if (bannerVisible && bannerFocus >= 0 && barFocused) {   // the progress bar: Left / Right skip, OK is Play/Pause
                if (cmd != RemoteCommand.select) skip(if (cmd == RemoteCommand.left) -10.0 else 10.0) else if (watchInfo()?.isBreak != true) togglePause()
                bannerLater(); return
            }
            if (bannerVisible && bannerFocus >= 0) {
                val acts = watchActions()
                if (acts.isEmpty()) { hideBanner(); return }   // no watchInfo (channels gone mid-program)
                when (cmd) {
                    RemoteCommand.left -> bannerFocus = max(0, bannerFocus - 1)
                    RemoteCommand.right -> bannerFocus = min(acts.size - 1, bannerFocus + 1)
                    else -> acts[min(bannerFocus, acts.size - 1)].run()
                }
                bannerLater(); return
            }
            // The spot: Select / Right skip it once its countdown is done, and do nothing before that.
            if (cmd != RemoteCommand.left && SpotOnAir.started != null) { skipSpot(); return }
            if (cmd == RemoteCommand.select && watchInfo()?.let { it.isBreak && it.next != null } == true) { startNextFromBreak(); return }   // the break card's OK
            if (cmd == RemoteCommand.left) { skip(-10.0); return }
            if (cmd == RemoteCommand.right) { skip(10.0); return }
            bannerVisible = true; bannerFocus = 0; barFocused = false
            bannerLater()
        }
    }
}

/** In a break: the program that just ended, from its beginning (Play/Pause, or the break card's pill). */
fun AppModel.restartFromBreak() {
    val info = watchInfo() ?: return
    tune(info.channel.id, Playback.Mode.start, programStart = info.slot.start)
}

/** In a break: the next program, from its beginning, without waiting for its slot (Select, or the break card's pill). */
fun AppModel.startNextFromBreak() {
    val info = watchInfo() ?: return
    val next = info.next ?: return
    tune(info.channel.id, Playback.Mode.start, programStart = next.start)
}

/** Hide the banner 6 s from now. */
fun AppModel.bannerLater() = later("banner", seconds = 6.0) { if (overlay == null && trackMenu == null) { bannerVisible = false; bannerFocus = -1 } }
fun AppModel.hideBanner() { cancelTimer("banner"); bannerVisible = false; bannerFocus = -1; trackMenu = null }

/** Channel up (+1) / down (−1) through the visible channels, wrapping. */
fun AppModel.flip(d: Int) {
    val list = visibleChannels
    val pb = playback
    if (list.isEmpty() || pb == null) return
    val i = list.indexOfFirst { it.id == pb.channelID }.let { if (it < 0) 0 else it }
    tune(list[((i + d) % list.size + list.size) % list.size].id, Playback.Mode.live)
}

// MARK: Number keys

/** The channel numbered `numbered` in this profile's lineup: a hidden one counts as missing. */
fun AppModel.channel(numbered: Int): Channel? = channels.firstOrNull { it.number == numbered && !isHidden(it.id) }
/** The channel the digits typed so far name. */
val AppModel.entryChannel: Channel? get() = channelEntry?.digits?.toIntOrNull()?.let { channel(numbered = it) }

/** A number key on the guide or the player with nothing over them: the box in the top-right corner collects up to
 *  three digits and tunes 2 s after the last one, at the third, or on OK. Digits do nothing anywhere else. */
fun AppModel.pressDigit(d: Int) {
    if (overlay != null || editingField != null || trackMenu != null || (screen != Screen.guide && screen != Screen.watch)) return
    if (attracting) attractReset()   // the box wakes the screen instead of sitting on the screensaver
    val digits = (channelEntry?.takeIf { !it.missing }?.digits ?: "") + d
    channelEntry = AppModel.ChannelEntry(digits); lastEntry = channelEntry
    if (digits.length == 3) commitEntry() else later("entry", seconds = 2.0) { commitEntry() }
}

/** While the box collects digits, OK tunes now and Back cancels; any other press closes it and does what it does.
 *  True when the box took the press. */
fun AppModel.pressEntry(cmd: RemoteCommand): Boolean {
    if (channelEntry?.missing != false) return false
    if (cmd == RemoteCommand.select) { commitEntry(); return true }
    closeEntry()
    return cmd == RemoteCommand.back
}

/** Put the box away without tuning: a tap, a channel change or a screen went elsewhere. */
fun AppModel.closeEntry() { cancelTimer("entry"); channelEntry = null }

/** Tune to the typed number like a channel change (the tune-mode setting applies). The channel already on screen
 *  just closes the box; a number with no channel says so for 2 s and changes nothing. */
fun AppModel.commitEntry() {
    val e = channelEntry ?: return
    val n = e.digits.toIntOrNull()
    if (e.missing || n == null) return
    closeEntry()
    if (overlay != null || editingField != null || pip || (screen != Screen.guide && screen != Screen.watch)) return   // moved on, or away in picture in picture
    val ch = channel(numbered = n) ?: run {
        channelEntry = AppModel.ChannelEntry(e.digits, missing = true); lastEntry = channelEntry
        later("entry", seconds = 2.0) { channelEntry = null }
        return
    }
    if (screen == Screen.watch && playback?.channelID == ch.id) return
    tune(ch.id, Playback.Mode.live)
}

// MARK: Background

/** Leaving for the Home screen (Back from the guide, or the Home button): ask the server to stop both streams now,
 *  instead of leaving it transcoding until it notices nobody is pulling, and hold a started-over program's place.
 *  A live program needs nothing held: it rejoins live on the way back, like changing back to a channel.
 *  (Android keeps the process running after onStop, so no background-task wrapper is needed.) */
fun AppModel.enteredBackground() {
    if (backgrounded) return
    backgrounded = true
    val pb = playback
    val info = watchInfo()
    if (pb != null && pb.mode == Playback.Mode.start && !pb.paused && info != null) {
        playback = pb.copy(offset = info.position, paused = true); resumeAfterBackground = true
    } else if (pb != null && pb.mode == Playback.Mode.live && !pb.markDue) playback = pb.copy(fromStart = false)   // a caught-up start-over rejoins live on the way back: no longer from the beginning
    cancelTimer("seek"); closeEntry()   // a typed number doesn't tune behind the Home screen
    if (screen == Screen.link) linkTask?.cancel()   // tvOS suspends the app; here the PIN poll would keep running
    val server = server ?: return
    scope.launch { server.stopPlayback("watch"); server.stopPlayback("preview") }
    // A plain-http Plex link may come back on another network, where the guide's posters and preview would carry the
    // token before the return's pick finishes. Dropped now, that pick rebuilds it once its address checks out.
    val cfg = persisted.serverConfig
    if (!Platform.isTV && cfg?.addresses != null && cfg.baseURL.startsWith("http://")) this.server = null
}

/** Back from the Home screen: reload whatever is on screen (its stream was stopped), resume a held program where it
 *  was, and show the banner so the viewer sees where they landed. */
fun AppModel.returnedToForeground() {
    if (!backgrounded) return
    backgrounded = false
    val pb = playback
    if (resumeAfterBackground && pb != null) playback = pb.copy(paused = false, startedAt = Instant.now())
    resumeAfterBackground = false
    fun reload() { foregroundSerial += 1; if (screen == Screen.watch) tunes += 1 }
    // A plain-http Plex link (dropped on the way out) picks first: the reload carries the token, maybe to a stranger's
    // LAN. With nothing verified to move to, it stays dropped and the reload finds none.
    val cfg = persisted.serverConfig
    val pickFirst = !Platform.isTV && cfg?.addresses != null && cfg.baseURL.startsWith("http://")
    if (!pickFirst) reload()
    if (screen == Screen.watch && watchInfo()?.isBreak != true) { bannerVisible = true; bannerFocus = -1; trackMenu = null; bannerLater() }   // a break's card says it all
    if (screen == Screen.link) startLink()   // a fresh code: the poll stopped in the background
    scope.launch {
        // The device may have left (or come home to) the server's network: an https link reloaded already and reloads
        // again if its address moves (a movie started on the old one); a dropped http link reloads below once checked.
        if (!Platform.isTV) { if (pickFirst) pickPlexAddress() else networkChanged() }
        if (pickFirst && !backgrounded) reload()   // hidden again meanwhile: that return picks and reloads
        checkRecentlyAdded()   // opening the app brings the "what's new" channels up to date
    }
}

// MARK: Soyboi spot

/** The bundled spots (res/raw), in the order they take turns, as URI strings ExoPlayer's MediaItem.fromUri takes. Each is
 *  30 s, 1080p H.264 + AAC. */
val AppModel.Companion.spotURLs: List<String>
    get() = listOf(R.raw.beacon_they_live, R.raw.beacon_rental, R.raw.beacon_trail, R.raw.beacon_cafe).map { "android.resource://${BuildConfig.APPLICATION_ID}/$it" }
val AppModel.Companion.spotLength: Double get() = 30.0

/** The spot this break opens with, or null. One trailer break in 15 with room for a whole spot (counted once per
 *  break, across launches), the spots taking turns; never on channel 99, never with the setting or this channel's
 *  trailers off. */
fun AppModel.dueSpot(ch: Channel, slot: Slot, at: Instant = Instant.now()): String? {
    val spots = AppModel.spotURLs
    val first = spots.firstOrNull() ?: return null
    if (spotOverride) { spotOverride = false; return first }
    if (!settings.houseSpots || !settings.trailerBreaks || !ch.trailerBreaks || ch.isScrambled ||
        slot.end.secondsSince(at) < AppModel.spotLength + 2) return null
    val d = AppModel.defaults
    val r = d.getString("spotRotation", null)?.let { runCatching { SpotRotation.fromJson(JSONObject(it)) }.getOrNull() } ?: SpotRotation()
    val due = r.next("${ch.id}|${slot.start.epochSecond}")
    d.edit().putString("spotRotation", r.toJson().toString()).apply()
    return if (due) spots[r.pick(spots.size)] else null
}

/** Seconds into the spot before it can be skipped. */
val AppModel.Companion.spotSkipDelay: Double get() = 5.0

/** The house spot on screen: WatchScreen holds its stream, the remote and TalkBack need when it started and whether it
 *  can be skipped yet. Its own object since an extension can't add stored properties to AppModel. Its fields are Compose state, like the model's. */
object SpotOnAir {
    var started by mutableStateOf<Instant?>(null)
    /** The 5 s countdown is done: OK / Right (a tap on touch) end the spot. */
    var skippable by mutableStateOf(false)
    var end: () -> Unit = {}
}

/** WatchScreen: the spot started playing (`end` hands over to the trailers, like the spot's own end), or, with null, it's gone. */
fun AppModel.spotChanged(end: (() -> Unit)?) {
    cancelTimer("spot")
    SpotOnAir.started = if (end == null) null else Instant.now(); SpotOnAir.skippable = false; SpotOnAir.end = end ?: {}
    if (end != null) later("spot", seconds = AppModel.spotSkipDelay) { SpotOnAir.skippable = true }
}

/** OK / Right, or a tap on the Skip chip, during the spot: once the countdown is done, end it like its own end. */
fun AppModel.skipSpot() {
    if (SpotOnAir.started == null || !SpotOnAir.skippable) return
    val end = SpotOnAir.end
    spotChanged(null); end()
}

fun AppModel.goLast() {
    val c = lastChannel
    if (c != null) tune(c.id, Playback.Mode.live) else toast("No last channel yet. Change channels first.")
}

/** Left / Right while watching: 10 s back or ahead per press. Presses within 0.6 s combine into one move, then the player
 *  reloads at the new spot (the server transcodes from an offset, so a seek is a reload). Rewinding a live program
 *  time-shifts it; fast-forwarding a shifted one can catch up to live. */
fun AppModel.skip(seconds: Double) {
    var pb = playback ?: return
    val info = watchInfo() ?: return
    bannerVisible = true; bannerLater()
    val runtime = info.slot.item.runtime
    val sinceStart = Instant.now().secondsSince(info.slot.start)
    if (info.position >= runtime) return   // a trailer break: nothing to scrub
    // A program started before it airs has no live point to catch up to yet: it stays time-shifted.
    val liveEdge = if (sinceStart > 0) sinceStart else Double.POSITIVE_INFINITY
    val target = max(0.0, minOf(info.position + seconds, runtime - 1, liveEdge))
    val fromStart = pb.fromStart && info.slot == pb.slot   // not onto the next program, joined midway once the kept slot ended
    if (target >= liveEdge - 1) {   // caught up, or never left live
        if (pb.mode == Playback.Mode.live) return
        pb = Playback(pb.channelID, Playback.Mode.live, programStart = info.slot.start, slot = info.slot, tracks = pb.tracks, fromStart = fromStart)
    } else {
        pb = Playback(pb.channelID, Playback.Mode.start, programStart = info.slot.start, offset = target, paused = pb.paused, slot = info.slot, tracks = pb.tracks, fromStart = fromStart)
    }
    playback = pb
    skipCue = if (seconds < 0) -1 else 1; later("skip-cue", seconds = 1.0) { skipCue = 0 }
    later("seek", seconds = 0.6) { tunes += 1 }
}

/** Play/Pause: live becomes time-shifted and paused; paused resumes; playing pauses. */
fun AppModel.togglePause() {
    var pb = playback ?: return
    val info = watchInfo() ?: return
    pb = if (pb.mode == Playback.Mode.live) Playback(pb.channelID, Playback.Mode.start, programStart = info.slot.start, offset = info.position, paused = true, slot = info.slot, tracks = pb.tracks, fromStart = pb.fromStart && info.slot == pb.slot)   // as in `skip`
    else if (pb.paused) pb.copy(startedAt = Instant.now(), paused = false)
    else pb.copy(offset = info.position, paused = true)
    playback = pb
    bannerVisible = true; bannerLater()
}

/** Banner buttons: [Pause | Play] (not in a break), [Start over | Back to live], Program info, Audio, Subtitles,
 *  Last channel · N. Audio and Subtitles open a list of the program's tracks over the button, the one on checked. */
fun AppModel.watchActions(): List<WatchAction> {
    val info = watchInfo() ?: return emptyList()
    val ch = info.channel; val slot = info.slot
    val a = ArrayList<WatchAction>()
    if (!info.isBreak) a += WatchAction(if (playback?.paused == true) "Play" else "Pause") { togglePause() }
    if (info.live) a += WatchAction("Start over") { tune(ch.id, Playback.Mode.start, programStart = slot.start) }
    else a += WatchAction("Back to live") { tune(ch.id, Playback.Mode.live, force = true) }
    a += WatchAction("Program info") { openInfo(ProgramKey(ch.id, slot.start)) }
    for ((kind, name) in listOf(MediaTrack.Kind.audio to "Audio", MediaTrack.Kind.subtitle to "Subtitles")) {
        if (trackChoices(kind).size >= 2) a += WatchAction(name) { openTrackMenu(kind) }   // one audio track: nothing to pick
    }
    lastChannel?.let { last -> a += WatchAction("Last channel · ${last.number}") { goLast() } }
    return a
}

/** A row of the banner's Audio or Subtitles list. */
class TrackChoice(val label: String, val on: Boolean, val apply: (TrackSelection) -> TrackSelection)

/** The banner's open Audio or Subtitles list: which, and the row the remote is on. */
data class TrackMenu(val kind: MediaTrack.Kind, val focus: Int)

/** The rows of the banner's Audio or Subtitles list, the one playing marked `on`. Subtitles: Off, Forced only (only while
 *  it's the choice: the server's pick, which the list can't offer back once left), then each track. */
fun AppModel.trackChoices(kind: MediaTrack.Kind): List<TrackChoice> {
    val item = watchInfo()?.slot?.item ?: return emptyList()
    val tracks = playbackTracks.filter { it.kind == kind }
    val sel = trackSelection(playbackTracks, item.id)
    if (kind == MediaTrack.Kind.audio) {
        val on = tracks.indexOfFirst { it.id == sel.audioID }.takeIf { it >= 0 } ?: tracks.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0
        return tracks.mapIndexed { i, t -> TrackChoice(AppModel.trackName(t, tracks), i == on) { it.copy(audioID = t.id) } }
    }
    if (tracks.isEmpty()) return emptyList()
    val rows = arrayListOf(TrackChoice("Off", sel.subtitle == TrackSelection.Subtitle.off) { it.copy(subtitle = TrackSelection.Subtitle.off) })
    if (sel.subtitle == TrackSelection.Subtitle.auto) rows += TrackChoice("Forced only", true) { it.copy(subtitle = TrackSelection.Subtitle.auto) }
    for (t in tracks) {
        val s = TrackSelection.Subtitle.track(t.id)
        rows += TrackChoice(AppModel.trackName(t, tracks), sel.subtitle == s) { it.copy(subtitle = s) }
    }
    return rows
}

/** The banner's Audio or Subtitles button: its list opens on the choice that's on, and holds the banner up. */
fun AppModel.openTrackMenu(kind: MediaTrack.Kind) {
    cancelTimer("banner")
    trackMenu = TrackMenu(kind, trackChoices(kind).indexOfFirst { it.on }.coerceAtLeast(0))
}

/** Up / Down move through the list (a drag too), OK plays the choice (a reload, unless it's the one on), Back puts the
 *  list away. Left / Right do nothing while it's open. */
fun AppModel.pressTrackMenu(cmd: RemoteCommand) {
    val m = trackMenu ?: return
    val c = trackChoices(m.kind)
    when (cmd) {
        RemoteCommand.up, RemoteCommand.down ->
            trackMenu = m.copy(focus = (m.focus + if (cmd == RemoteCommand.down) 1 else -1).coerceIn(0, maxOf(0, c.size - 1)))
        RemoteCommand.select -> {
            trackMenu = null; bannerLater()
            c.getOrNull(m.focus)?.takeIf { !it.on }?.let { setTracks(change = it.apply) }
        }
        RemoteCommand.back -> { trackMenu = null; bannerLater() }
        else -> {}
    }
}

/** "English"; the server's own title ("Commentary", "English (AC3 5.1)") when the language is unknown or shared by another track. */
fun AppModel.Companion.trackName(t: MediaTrack, among: List<MediaTrack>): String {
    val lang = MediaTrack.languageName(t.language)
    val shared = among.count { it.language == t.language } > 1
    return if ((lang == null || shared) && t.title.isNotEmpty()) t.title else lang ?: "Track ${t.id}"
}

/** Per-program override from the banner: the player reloads at the current position with the new tracks.
 *  The override holds for that program only (`itemID`, default the one on now). */
private fun AppModel.setTracks(itemID: String? = null, change: (TrackSelection) -> TrackSelection) {
    val pb = playback ?: return
    val id = itemID ?: watchInfo()?.slot?.item?.id ?: return
    playback = pb.copy(tracks = Playback.TrackOverride(id, change(trackSelection(playbackTracks, id)))); tunes += 1
}

/** Program `itemID`, asked for with a subtitle, didn't start: reload it at the same position with subtitles off. */
fun AppModel.dropSubtitles(itemID: String) {
    setTracks(itemID) { it.copy(subtitle = TrackSelection.Subtitle.off) }
    toast("Couldn’t show subtitles for this one.")
}

/** The prototype's onTick: a started-over program whose slot has run out goes back to live. Also marks a program
 *  started from its beginning watched on the server once it has played to 90%. */
fun AppModel.watchTick(at: Instant = Instant.now()) {
    val pb = playback ?: return
    if (screen != Screen.watch) return
    val info = watchInfo(at) ?: return
    // Only the program tuned counts, not its break (trailers, the spot) nor channel 99. Once per playback, no toast,
    // a failure only logged. No server (a plain-http link dropped while away): a later tick of this slot reports it
    // once it's back, its break too.
    // The library's own `watched` stays as it is, so the lineup doesn't change under the viewer: the Unwatched
    // channel drops the program at the next library refresh.
    val server = server
    if (pb.fromStart && info.slot == pb.slot && (!info.isBreak || pb.markDue) && !info.channel.isScrambled &&
        info.position >= 0.9 * info.slot.item.runtime) {
        if (server == null) { if (!pb.markDue) playback = pb.copy(markDue = true) }
        else {
            playback = pb.copy(fromStart = false)
            val item = info.slot.item
            scope.launch {
                try { server.markWatched(item) } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    println("markWatched: $e")
                }
            }
        }
    }
    if (pb.mode != Playback.Mode.start || pb.paused || info.position < info.slot.duration) return
    val on = slotOnAir(info.channel, at)
    playback = Playback(pb.channelID, Playback.Mode.live, programStart = on?.start ?: at, slot = on)
    toast("Caught up. Back to live.")
}
