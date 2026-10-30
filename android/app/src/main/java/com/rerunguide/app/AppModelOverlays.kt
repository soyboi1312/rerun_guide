package com.rerunguide.app

import com.rerunguide.core.*
import java.time.Instant
import java.util.UUID

/* Options menu, Program info sheet, New-channel picker (port of the prototype's k_options, k_info/infoActs, k_newch/createCh).
 * `AppModel.OptionRow` and `AppModel.InfoAction` are declared inside AppModel (AppModel.kt): Kotlin extensions can't
 * add nested types. */

// MARK: Options (k_options)

fun AppModel.pressOptions(cmd: RemoteCommand) {
    val rows = AppModel.OptionRow.entries
    if (step(cmd, count = rows.size)) return
    val row = rows[minOf(focusIndex, rows.size - 1)]
    if (row == AppModel.OptionRow.theme && (cmd == RemoteCommand.left || cmd == RemoteCommand.right)) {
        setTheme(if (cmd == RemoteCommand.left) ThemeID.LATE else ThemeID.MIDNIGHT); return
    }
    if (cmd == RemoteCommand.back || cmd == RemoteCommand.right) { closeOverlay(); return }
    if (cmd != RemoteCommand.select) return
    when (row) {
        AppModel.OptionRow.profile -> {
            go(Screen.profiles)
            focusIndex = persisted.profiles.indexOfFirst { it.id == persisted.currentProfileID }.let { if (it < 0) 0 else it }
        }
        AppModel.OptionRow.guide -> setGuideFilter(GuideFilter.all)
        AppModel.OptionRow.movies -> setGuideFilter(GuideFilter.movies)
        AppModel.OptionRow.tv -> setGuideFilter(GuideFilter.tv)
        AppModel.OptionRow.search -> openSearch()
        AppModel.OptionRow.channels -> go(Screen.channels)
        AppModel.OptionRow.settings -> go(Screen.settings)
        AppModel.OptionRow.theme -> closeOverlay()   // Left/Right pick the theme; the center button confirms by closing the menu, never flips it again
    }
}

// MARK: Program info (infoActs / k_info)

fun AppModel.infoActions(key: ProgramKey): List<AppModel.InfoAction> {
    val (ch, slot) = program(key) ?: return emptyList()
    val st = status(slot, Instant.now())
    val fromStart = AppModel.InfoAction(if (st == ProgramStatus.later) "Watch now" else "Start from the beginning") { tune(ch.id, Playback.Mode.start, programStart = slot.start) }
    val acts = if (st == ProgramStatus.live || st == ProgramStatus.`break`)
        mutableListOf(AppModel.InfoAction("Watch live") { tune(ch.id, Playback.Mode.live, force = true) }, fromStart)
    else mutableListOf(fromStart, AppModel.InfoAction("Watch channel ${ch.number} now") { tune(ch.id, Playback.Mode.live) })
    acts += AppModel.InfoAction("Edit channel") {
        go(Screen.channels); channelSelection = ch.id; channelColumn = 1; channelFieldIndex = 0
    }
    // Opened from Search (over its page): the result can also become a channel.
    val e = searchSelection
    if (screen == Screen.search && e != null) acts += AppModel.InfoAction("Make a channel") { makeChannel(e) }
    return acts
}

fun AppModel.pressInfo(cmd: RemoteCommand) {
    val key = (overlay as? Overlay.info)?.key ?: return
    val acts = infoActions(key)
    if (step(cmd, count = acts.size, horizontal = true)) return
    if (cmd == RemoteCommand.select && acts.isNotEmpty()) acts[minOf(focusIndex, acts.size - 1)].run()
    else if (cmd == RemoteCommand.back) { closeOverlay(); if (screen == Screen.watch) bannerLater() }
}

// MARK: New channel (k_newch / createCh)

fun AppModel.pressNewChannel(cmd: RemoteCommand) {
    val kinds = ChannelBuilder.CandidateKind.entries
    val items = candidateCache
    if (newChannelStep == 1 && (cmd == RemoteCommand.up || cmd == RemoteCommand.down)) {   // 3 per row
        val j = focusIndex + (if (cmd == RemoteCommand.down) 3 else -3)
        if (j in items.indices) focusIndex = j
        return
    }
    if (step(cmd, count = if (newChannelStep == 0) kinds.size else items.size, horizontal = true)) return
    if (cmd == RemoteCommand.back) {
        if (newChannelStep == 1) { newChannelStep = 0; focusIndex = newChannelType?.let { kinds.indexOf(it).takeIf { i -> i >= 0 } } ?: 0 }
        else { candidateCache = emptyList(); closeOverlay() }
        return
    }
    if (cmd != RemoteCommand.select) return
    if (newChannelStep == 0) {
        val kind = kinds[minOf(focusIndex, kinds.size - 1)]
        newChannelType = kind; candidateCache = ChannelBuilder.candidates(kind, library); newChannelStep = 1; focusIndex = 0
    } else {
        val kind = newChannelType
        if (kind != null && focusIndex in items.indices) createChannel(kind, items[focusIndex])
    }
}

fun AppModel.createChannel(kind: ChannelBuilder.CandidateKind, c: ChannelBuilder.Candidate) {
    val n = nextFreeNumber
    val chip: RuleChip = when (kind) {
        ChannelBuilder.CandidateKind.director -> RuleChip.director(c.name)
        ChannelBuilder.CandidateKind.actor -> RuleChip.actor(c.name)
        ChannelBuilder.CandidateKind.series -> RuleChip.series(id = c.id ?: c.name, title = c.name)
    }
    val d = ChannelDefinition(id = "custom:" + UUID.randomUUID().toString().uppercase(), number = n, name = c.name,
        tag = if (kind == ChannelBuilder.CandidateKind.series) "Marathon" else kind.rawValue, rule = listOf(chip),
        order = if (kind == ChannelBuilder.CandidateKind.series) PlayOrder.inOrder else PlayOrder.shuffle, trailerBreaks = true, isCustom = true)
    addCustomChannel(d)
    overlay = null; focusIndex = 0   // the candidates stay, as in Swift (whose portrait sheet still shows them as it slides away)
    channelSelection = d.id; channelColumn = 1; channelFieldIndex = 0
    toast("Channel $n, ${c.name}, just signed on.")
}
