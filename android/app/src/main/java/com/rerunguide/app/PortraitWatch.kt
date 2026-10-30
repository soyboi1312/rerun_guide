package com.rerunguide.app

import kotlin.math.ceil
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.rerunguide.core.MediaItem
import com.rerunguide.core.MediaTrack
import com.rerunguide.core.secondsSince
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max

/** The top bar (44) and its gap (6) above the picture: RootView puts the player's stage there (Stage `videoTop`). */
const val PORTRAIT_VIDEO_TOP = 50f

/** Now playing held upright (the iOS handoff §3; PortraitWatch.swift): a top bar, the picture, then the banner (channel,
 *  title, CH rocker, progress, controls) and what else is on. The picture is the stage's own WatchScreen, drawn under
 *  this at PORTRAIT_VIDEO_TOP, full width at 16:9: this leaves that box clear and takes its swipes. */
@Composable
fun PortraitWatch() {
    val model = LocalModel.current
    val t = LocalTheme.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth.value
        Periodic(1) { now ->
            Column(Modifier.fillMaxSize()) {
                TopBar(Modifier.background(t.bg).height(44.dp))
                Spacer(Modifier.fillMaxWidth().height(6.dp).background(t.bg))
                Box(Modifier.fillMaxWidth().height((w * 9 / 16).dp).pointerInput(Unit) {   // up and down flip channels, as on the TV
                    var drag = androidx.compose.ui.geometry.Offset.Zero
                    detectDragGestures(onDragStart = { drag = androidx.compose.ui.geometry.Offset.Zero }, onDragEnd = {
                        if (abs(drag.y) > abs(drag.x) && abs(drag.y) > 40 * density) model.flip(if (drag.y < 0) 1 else -1)
                    }) { c, d -> c.consume(); drag += d }
                }.clearAndSetSemantics { })
                Column(Modifier.fillMaxWidth().weight(1f).background(t.bg).verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars)) {
                    model.watchInfo(now)?.let { Panel(it, now, w - 32) }
                    AlsoOnNow(now)
                }
            }
        }
    }
}

@Composable
private fun TopBar(modifier: Modifier) {
    val model = LocalModel.current
    val t = LocalTheme.current
    val context = LocalContext.current
    val info = model.watchInfo()
    val spot = SpotOnAir.started != null
    val paused = model.playback?.paused == true
    val chip = if (spot) "Ad" else if (info?.isBreak == true) "Break" else if (paused) "Paused" else if (info?.live != false) "Live" else "Rerun"
    val subs = model.trackChoices(MediaTrack.Kind.subtitle)
    val mm = t.id == ThemeID.MIDNIGHT
    Row(modifier.fillMaxWidth().padding(horizontal = 14.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
        Row(Modifier.heightIn(min = 44.dp).onTouch { model.touchBack() }.clearAndSetSemantics { contentDescription = "Back to Lineup"; role = Role.Button },
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.height(36.dp).background(t.panel, t.pShape).padding(start = 10.dp, end = 14.dp), Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
                PText("‹", t.pBody(22), t.accent)
                PText(t.heading("Lineup"), t.pBody(14, FontWeight.SemiBold).copy(letterSpacing = (if (mm) 0.06 else 0.0).em), t.ink)
            }
        }
        Spacer(Modifier.weight(1f))
        if (subs.isNotEmpty()) Box(Modifier.size(44.dp, 36.dp).background(t.panel, t.pShape)
            .onTouch {
                // A row of the subtitle list, as the TV banner's applies it (one reload at the current position).
                AlertDialog(context).setTitle("Subtitles")
                    .setSingleChoiceItems(subs.map { it.label }.toTypedArray(), subs.indexOfFirst { it.on }) { d, i ->
                        model.openTrackMenu(MediaTrack.Kind.subtitle); model.trackMenu = model.trackMenu?.copy(focus = i); model.press(RemoteCommand.select); d.dismiss()
                    }.show()
            }.clearAndSetSemantics { contentDescription = "Subtitles"; role = Role.Button }, contentAlignment = Alignment.Center) {
            PText("CC", t.pBody(13, FontWeight.ExtraBold), t.ink)
        }
        // Phones: the TV player, sideways (also with auto-rotate off, which no turn of the phone gets past).
        if (Platform.device == "phone") Box(Modifier.size(44.dp, 36.dp).background(t.panel, t.pShape).onTouch { model.fullScreen = true }
            .clearAndSetSemantics { contentDescription = "Full screen"; role = Role.Button }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(14.dp)) {   // ⤢: two corners pulled apart
                val w = size.width; val k = w * 0.4f; val st = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawPath(Path().apply { moveTo(0f, k); lineTo(0f, 0f); lineTo(k, 0f); moveTo(0f, 0f); lineTo(w * 0.42f, w * 0.42f) }, t.ink, style = st)
                drawPath(Path().apply { moveTo(w, w - k); lineTo(w, w); lineTo(w - k, w); moveTo(w, w); lineTo(w * 0.58f, w * 0.58f) }, t.ink, style = st)
            }
        }
        PLabel(chip, t.accentInk, Modifier.background(t.accent, t.pShape).padding(horizontal = 11.dp, vertical = 5.dp))
    }
}

/** Dark dialogs, like the text-entry one (TouchViews). */
@Suppress("FunctionName")
private fun AlertDialog(context: android.content.Context) = android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)

@Composable
private fun Panel(info: WatchInfo, now: Instant, width: Float) {
    val model = LocalModel.current
    val t = LocalTheme.current
    val ch = info.channel; val slot = info.slot; val item = slot.item
    val paused = model.playback?.paused == true
    val mins = (info.position / 60).toInt()
    val behind = if (!info.live && now >= slot.start && now < slot.end) now.secondsSince(slot.start) - info.position else 0.0
    val status = when {   // the TV banner's wording and order (a time-shifted break is "behind live")
        paused -> "Paused"
        info.live && info.isBreak -> "Back at ${Clock.time(slot.end)}"   // trailers, the station card or the spot
        info.live -> "Live · $mins min in"
        behind >= 60 -> "${Math.round(behind / 60)} min behind live"
        behind >= 3 -> "${Math.round(behind)} s behind live"
        else -> "From the beginning · $mins min in"
    }
    val titleSize = if (item.displayTitle.length <= 18) 28 else 24
    val started = SpotOnAir.started
    val first: Pair<String, () -> Unit> = when {
        started != null -> (if (SpotOnAir.skippable) "Skip ad" else "Skip in ${ceil(AppModel.spotSkipDelay - now.secondsSince(started)).toInt().coerceIn(1, AppModel.spotSkipDelay.toInt())}") to { model.skipSpot() }
        info.live -> "From the beginning" to { model.tune(ch.id, Playback.Mode.start, programStart = slot.start) }
        else -> "Back to live" to { model.tune(ch.id, Playback.Mode.live, force = true) }
    }
    val second: Pair<String, () -> Unit> = if (info.isBreak && info.next != null) "Watch next" to { model.startNextFromBreak() }
        else (if (paused) "Play" else "Pause") to { model.togglePause() }
    val last: Pair<String, () -> Unit> = (model.lastChannel?.let { "Last · ${it.number}" } ?: "Last") to { model.goLast() }
    val mm = t.id == ThemeID.MIDNIGHT
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp), Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            // A live region: TalkBack hears where a channel change landed.
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PText(ch.number.toString(), t.pBody(14, FontWeight.ExtraBold), t.accentInk, Modifier.background(t.accent, t.pShape).padding(horizontal = 9.dp, vertical = 2.dp))
                    PText(t.heading(ch.name), t.pBody(14, FontWeight.SemiBold).copy(letterSpacing = (if (mm) 0.06 else 0.0).em), t.ink, maxLines = 1)
                    PText("· ${ch.tag}", t.pBody(14), t.mute, Modifier.weight(1f, fill = false), maxLines = 1)
                }
                PText(t.heading(item.displayTitle), t.pHeading(titleSize).copy(letterSpacing = t.headingTracking.em), t.ink, maxLines = 3)
                PText(if (item.kind == MediaItem.Kind.episode) "“${item.title}”" + (if (item.seasonEpisode.isEmpty()) "" else " · ${item.seasonEpisode}") else item.infoLine,
                    t.pBody(15), t.mute, maxLines = 2)
            }
            Rocker(info)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.clearAndSetSemantics {
                contentDescription = "Progress"; stateDescription = "$mins minutes in, of ${span(slot.duration)}"
                progressBarRangeInfo = ProgressBarRangeInfo((info.position / slot.duration).toFloat().coerceIn(0f, 1f), 0f..1f)
            }, Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
                val style = t.pBody(12, FontWeight.SemiBold).copy(fontFeatureSettings = "tnum")
                PText(Clock.time(slot.start), style, t.mute)
                Box(Modifier.weight(1f).height(5.dp).background(t.line, t.pShape)) {
                    Box(Modifier.fillMaxWidth((info.position / slot.duration).toFloat().coerceIn(0f, 1f)).height(5.dp).background(t.accent, t.pShape))
                }
                PText(Clock.time(slot.end), style, t.mute)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PText(status, t.pBody(13, FontWeight.Bold), t.accent)
                Spacer(Modifier.weight(1f))
                info.next?.let { PText("Up next · ${Clock.time(it.start)} ${it.item.displayTitle}", t.pBody(13), t.dim, Modifier.weight(2f, fill = false), maxLines = 1) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Control(first.first, (width - 16) * 1.35f / 3.35f, first.second)
            Control(second.first, (width - 16) / 3.35f, second.second)
            Control(last.first, (width - 16) / 3.35f, last.second)
        }
    }
}

@Composable
private fun Control(label: String, width: Float, action: () -> Unit) {
    val t = LocalTheme.current
    Box(Modifier.width(width.dp).heightIn(min = 44.dp).background(t.panel2, t.pShape).onTouch(action), contentAlignment = Alignment.Center) {
        PText(t.heading(label), t.pBody(13, FontWeight.SemiBold).copy(letterSpacing = (if (t.id == ThemeID.MIDNIGHT) 0.06 else 0.0).em), t.ink, maxLines = 1)
    }
}

/** ▲ CH ▼: wraps around the lineup. One element for TalkBack, with channel up and down as its actions. */
@Composable
private fun Rocker(info: WatchInfo) {
    val model = LocalModel.current
    val t = LocalTheme.current
    Column(Modifier.width(56.dp).background(t.panel2, t.pShape).clearAndSetSemantics {
        contentDescription = "Channel"; stateDescription = "Channel ${info.channel.number}, ${info.channel.name}, ${info.slot.item.displayTitle}"
        customActions = listOf(CustomAccessibilityAction("Channel up") { model.flip(1); true }, CustomAccessibilityAction("Channel down") { model.flip(-1); true })
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(46.dp).onTouch { model.flip(1) }, contentAlignment = Alignment.Center) { PText("▲", t.pBody(13), t.ink) }
        PText("CH", t.pBody(10, FontWeight.ExtraBold).copy(letterSpacing = 0.12.em), t.accent, Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().height(46.dp).onTouch { model.flip(-1) }, contentAlignment = Alignment.Center) { PText("▼", t.pBody(13), t.ink) }
    }
}

@Composable
private fun AlsoOnNow(now: Instant) {
    val model = LocalModel.current
    val t = LocalTheme.current
    val here = model.playback?.channelID
    Column(Modifier.padding(top = 22.dp, bottom = 24.dp), Arrangement.spacedBy(8.dp)) {
        PLabel("Also on now", modifier = Modifier.padding(horizontal = 20.dp).semantics { heading() })
        Column(Modifier.padding(horizontal = 16.dp), Arrangement.spacedBy(if (t.id == ThemeID.MIDNIGHT) 4.dp else 6.dp)) {
            for (ch in model.visibleChannels) if (ch.id != here) {
                val s = model.slotOnAir(ch, now) ?: continue
                OnNowRow(ch, s, now) { model.tune(ch.id, Playback.Mode.live) }
            }
        }
    }
}
