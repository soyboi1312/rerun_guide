package com.rerunguide.app

import com.rerunguide.core.*
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/* Options › Search: find a film or a show, see when it's on next, open it, or make a channel of it. */

/** The library's titles keyed for matching, built on first use after the library changed. */
val AppModel.titleIndex: TitleSearch.Index
    get() = searchIndex ?: TitleSearch.index(library + freshItems).also { searchIndex = it }

/** The result the page's focus is on: what Program info or the Make a channel page over it is about. */
val AppModel.searchSelection: TitleSearch.Entry? get() = searchResults.getOrNull(searchFocus - 1)

/** Options › Search: an empty page with its text entry open. */
fun AppModel.openSearch() {
    go(Screen.search)
    refreshAirings()
    searchQuery = ""; searchFocus = 0
    editingField = "search"
}

/** When everything airs in the next 24 hours, across the channels this profile sees: as the page opens, on its 10 s
 *  clock (a program ended, the lineup was rebuilt, the app came back) and before OK opens a result. Never per keystroke. */
fun AppModel.refreshAirings(now: Instant = Instant.now()) {
    val end = now.plusSeconds(TitleSearch.window)
    searchAirings = TitleSearch.nextAirings(channels.filter { !isHidden(it.id) }.map { it to slots(it, now, end) }, titleIndex, now)
}

/** "Tonight 10:00 PM · Ch 5 Fright Night", in Clock's time. */
fun AppModel.airsText(e: TitleSearch.Entry, at: Instant = Instant.now()): String =
    TitleSearch.airsText(searchAirings[e.id], at) { "${Clock.time(it)} ${Clock.ap(it)}" }

/** Up / Down move between the field and the results, Select types in the field or opens a result, Back goes to the guide. */
fun AppModel.pressSearch(cmd: RemoteCommand) {
    when (cmd) {
        RemoteCommand.up, RemoteCommand.down -> searchFocus = max(0, min(searchResults.size, searchFocus + (if (cmd == RemoteCommand.down) 1 else -1)))
        RemoteCommand.back -> go(Screen.guide)
        RemoteCommand.select -> {
            val e = searchSelection ?: run { editingField = "search"; return }
            makePeople = null; refreshAirings()
            // On in the next 24 hours: its Program info, which adds Make a channel over this page. Otherwise just that.
            val a = searchAirings[e.id]
            if (a != null) openInfo(ProgramKey(a.channelID, a.start)) else { overlay = Overlay.makeChannel; focusIndex = 0 }
        }
        else -> {}
    }
}

/** How many of a film's billed actors count as its leads. */
val AppModel.Companion.leadActors: Int get() = 3

/** Make a channel of a result, as Channels › New channel does: a show's marathon in episode order at once; for a film,
 *  the choice of its director(s) and lead actors with enough films for a channel. Says so when nothing qualifies. */
fun AppModel.makeChannel(e: TitleSearch.Entry) {
    if (e.isShow) {
        val c = ChannelBuilder.candidates(ChannelBuilder.CandidateKind.series, library).firstOrNull { it.name == e.title }
            ?: return toast("${e.title} doesn’t have enough episodes for a channel.")
        return made(ChannelBuilder.CandidateKind.series, c)
    }
    val m = e.item
    val directors = ChannelBuilder.candidates(ChannelBuilder.CandidateKind.director, library).filter { it.name in m.directors }
    val actors = ChannelBuilder.candidates(ChannelBuilder.CandidateKind.actor, library).filter { it.name in m.actors.take(AppModel.leadActors) }
    val people = directors.map { ChannelBuilder.CandidateKind.director to it } + actors.map { ChannelBuilder.CandidateKind.actor to it }
    if (people.isEmpty()) return toast("No one from ${e.title} has enough films for a channel.")
    makePeople = people; overlay = Overlay.makeChannel; focusIndex = 0
}

/** The new channel lands in the channel editor, as after Channels › New channel. */
private fun AppModel.made(kind: ChannelBuilder.CandidateKind, c: ChannelBuilder.Candidate) {
    createChannel(kind, c); makePeople = null; go(Screen.channels)
}

/** The Make a channel page's buttons: Make a channel, then a film's people to pick from. */
val AppModel.makeActions: List<AppModel.InfoAction>
    get() {
        val e = searchSelection ?: return emptyList()
        val people = makePeople ?: return listOf(AppModel.InfoAction("Make a channel") { makeChannel(e) })
        return people.map { (kind, c) -> AppModel.InfoAction("${c.name} · ${kind.rawValue}") { made(kind, c) } }
    }

fun AppModel.pressMakeChannel(cmd: RemoteCommand) {
    val acts = makeActions
    if (step(cmd, count = acts.size, horizontal = true)) return
    if (cmd == RemoteCommand.select && acts.isNotEmpty()) acts[min(focusIndex, acts.size - 1)].run()
    else if (cmd == RemoteCommand.back) { makePeople = null; closeOverlay() }
}
