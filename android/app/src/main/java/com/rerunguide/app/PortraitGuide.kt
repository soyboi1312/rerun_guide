package com.rerunguide.app

import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import kotlin.math.sign
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.snapshotFlow
import android.accessibilityservice.AccessibilityServiceInfo
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.rerunguide.core.Channel
import com.rerunguide.core.GuideGenerator
import com.rerunguide.core.MediaItem
import com.rerunguide.core.ProgramStatus
import com.rerunguide.core.Slot
import com.rerunguide.core.secondsSince
import java.time.Instant
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max

/** Tonight's Lineup held upright (the iOS handoff §1; PortraitGuide.swift): header, the selected channel's preview, then
 *  the listings: channels down (looping, like the TV's) and twelve hours across (the TV guide's reach), with the channel column and time header
 *  pinned. Left alone they drift; a touch stops them until the idle setting runs out. At large font sizes the grid
 *  becomes a list of what's on now and next. */
@Composable
fun PortraitGuide() {
    val model = LocalModel.current
    val t = LocalTheme.current
    val big = LocalDensity.current.fontScale >= 1.5f   // iOS: the accessibility text sizes
    // The preview's static flash when the selected channel changes (not on the way in).
    var flash by remember { mutableIntStateOf(0) }
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(model.focused?.channelID) { if (seen) flash++; seen = true }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth.value
        Column(Modifier.fillMaxSize()) {
            PortraitHeader()
            if (model.visibleChannels.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    if (model.libraryLoading) RerunLoader(width = 160f, delay = 1.0)
                    else PText(model.libraryError ?: "Nothing to schedule yet. Try Settings › Rebuild.", t.pBody(17),
                        if (model.libraryError == null) t.dim else t.accent)
                }
            } else Periodic(10) { now ->
                val gi = GuideInfo.make(model, now) ?: return@Periodic
                if (big) PortraitList(gi)
                else Column(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp)) {
                        PreviewWindow(gi, portrait = w - 32)
                        if (flash > 0) key(flash) { StaticFlash(Modifier.matchParentSize().clip(RoundedCornerShape(if (t.id == ThemeID.MIDNIGHT) 0.dp else 26.dp))) }
                    }
                    PortraitGrid(gi, Modifier.padding(top = 14.dp).weight(1f))
                }
            }
        }
    }
}

/** "● Tonight's Lineup", the date and the profile's initial (which opens Settings, where profiles switch). */
@Composable
fun PortraitHeader() {
    val model = LocalModel.current
    val t = LocalTheme.current
    Periodic(60) { now -> Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 16.dp, end = 9.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clearAndSetSemantics { heading(); contentDescription = "Tonight’s Lineup. Now ${Clock.time(now)} ${Clock.ap(now)}" },
            Arrangement.spacedBy(9.dp), Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(t.accent, t.pShape))
            PText(t.heading("Tonight’s Lineup"), t.pHeading(21).copy(letterSpacing = t.headingTracking.em), t.ink, maxLines = 1)
        }
        PText(t.heading(Clock.day(now)), t.pBody(13, FontWeight.SemiBold), t.dim, Modifier.clearAndSetSemantics { }, maxLines = 1)
        Box(Modifier.size(44.dp).onTouch { model.go(Screen.settings) }.clearAndSetSemantics { contentDescription = "${model.profile.name}. Settings"; role = Role.Button },
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(30.dp).background(colorHex(model.profile.colorHex), t.pShape), contentAlignment = Alignment.Center) {
                PText(model.profile.initial, t.pBody(16, FontWeight.ExtraBold), t.accentInk)
            }
        }
    } }
}

/** Portrait lineup: a show on now becomes the selected channel (the preview switches to it) and a second tap, like a tap
 *  on the preview, watches it; any other show opens Program info. */
fun AppModel.tapPortrait(k: ProgramKey) {
    closeEntry()
    val pr = program(k) ?: return
    when (status(pr.slot, Instant.now())) {
        ProgramStatus.live, ProgramStatus.`break` -> if (guideKey() == k) tune(k.channelID, Playback.Mode.live) else focused = k
        else -> openInfo(k)
    }
}

/** TalkBack's line for a lineup cell: "Charade. Channel 9, The Matinee. 8 to 10 PM. On now, 46 minutes in." */
fun AppModel.portraitLabel(ch: Channel, s: Slot, now: Instant): String {
    val whenText = when (status(s, now)) {
        ProgramStatus.live -> "On now, ${(now.secondsSince(s.start) / 60).toInt()} minutes in"
        ProgramStatus.`break` -> "On now, trailers until ${Clock.time(s.end)}"
        ProgramStatus.later -> "Later"
        else -> "Earlier"
    }
    val what = listOfNotNull(s.item.displayTitle, if (s.item.kind == MediaItem.Kind.episode) s.item.title else null).joinToString(", ")
    return "$what. Channel ${ch.number}, ${ch.name}. ${Clock.range(s.start, s.end)}. $whenText."
}

private val Theme.mm get() = id == ThemeID.MIDNIGHT
private val Theme.cell get() = if (mm) panel2 else panel
private val Theme.liveCell get() = if (mm) panel2 else hex("#28211c")
private val Theme.focusFill get() = if (mm) focusBg else hex("#e2d2bc")

// MARK: - Grid

/** Half hours across the track: twelve hours, as far as the TV guide pages ahead (`AppModel.maxWindowOffset`). */
private const val BLOCKS = AppModel.maxWindowOffset + 3
private const val DRIFT = 18f        // dp a second
private const val COAST = 0.5        // s: a flick's coast time constant (about UIScrollView's normal deceleration)
private fun clockNow() = System.nanoTime() / 1e9

/** Where the listings are: both axes are the grid's own offsets (dp), not a scroll container, so the rows loop forever,
 *  drift and coast after a flick from one clock, as the TV grid does. */
private class GridScroll {
    /** Where they were when last touched: x across the track (0 = the current half hour), y down the endless rows. */
    var base by mutableStateOf(Offset.Zero)
    var fling by mutableStateOf(Offset.Zero)
    var flungAt by mutableDoubleStateOf(0.0)
    /** When drifting starts again (the idle setting after the last touch; the launch's first guide holds 4 s). */
    var resume by mutableDoubleStateOf(0.0)
    var clock by mutableDoubleStateOf(clockNow())
    var coasts by mutableIntStateOf(0)
    var placed = false
    /** The drag in progress started here (moved along when the half hour moves on). */
    var dragFrom: Offset? = null
    /** The touch that stopped a flick: like a scroll view's, it only stops it, the cell under it isn't tapped. */
    var swallow = false
    /** The half hour the track starts at, last seen. */
    var ws: Instant? = null
    /** Whether it drifted, last seen. */
    var drifted: Boolean? = null

    fun pos(at: Double, drifts: Boolean, maxX: Float): Offset {
        val k = (COAST * (1 - exp(-max(0.0, at - flungAt) / COAST))).toFloat()
        var y = base.y + fling.y * k
        if (drifts) y += (max(0.0, at - resume) * DRIFT).toFloat()
        return Offset((base.x + fling.x * k).coerceIn(0f, maxX), y)
    }
}

@Composable
private fun PortraitGrid(gi: GuideInfo, modifier: Modifier) {
    val model = LocalModel.current
    val t = LocalTheme.current
    val context = LocalContext.current
    val k = LocalDensity.current.fontScale.coerceIn(1f, 1.5f)   // grows with the font size (iOS @ScaledMetric)
    val colW = 96f * k; val slotW = 136f * k; val rowH = 64f * k; val headH = 34f * k
    val a11y = remember { context.getSystemService(AccessibilityManager::class.java) }
    // Read on every pass (the 10 s tick), so a setting changed while the app was away applies.
    val reduceMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    // Drift is off with TalkBack (touch exploration) or Switch Access, with animations off (WCAG 2.2.2), and with the setting off.
    val switchAccess = a11y?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)?.any { it.id.contains("switchaccess", ignoreCase = true) } == true
    val drifts = model.settings.autoScroll && a11y?.isTouchExplorationEnabled != true && !switchAccess && !reduceMotion
    val s = remember { GridScroll() }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val visW = maxWidth.value - colW
        val gridH = max(0f, maxHeight.value - headH)
        val maxX = max(0f, slotW * BLOCKS - visW)
        val drift by rememberUpdatedState(drifts)
        val maxXs by rememberUpdatedState(maxX)
        val p = remember(s) { derivedStateOf { s.pos(s.clock, drift, maxXs) } }
        val first by remember(rowH) { derivedStateOf { floor(p.value.y / rowH).toInt() } }
        val count = (gridH / rowH).toInt() + 2
        if (!s.placed) {   // the selected channel on the second row, as the TV comes back from watching it; the launch's hold
            s.placed = true
            val i = model.visibleChannels.indexOfFirst { it.id == gi.ch.id }.coerceAtLeast(0)
            s.base = Offset(0f, (i - 1) * rowH)
            model.holdForLaunch()
            s.resume = if (model.overlay != null) Double.MAX_VALUE   // under a sheet: still until it closes
                else clockNow() + max(0.0, model.guideScroll.driftStart.toEpochMilli() / 1000.0 - Instant.now().toEpochMilli() / 1000.0)
        }
        // Program info open: the listings hold still under it, and wait the idle time again once it closes.
        LaunchedEffect(Unit) {
            snapshotFlow { model.overlay == null }.drop(1).collect { closed ->
                s.base = p.value; s.fling = Offset.Zero
                s.resume = if (closed) clockNow() + model.settings.idleSeconds else Double.MAX_VALUE
            }
        }
        // The track starts at the current half hour: when that moves on, keep what was in view in view.
        LaunchedEffect(gi.ws) {
            val old = s.ws; s.ws = gi.ws
            if (old == null || old == gi.ws) return@LaunchedEffect
            val d = (gi.ws.secondsSince(old) / GuideGenerator.blockLength).toFloat() * slotW
            s.base = s.base.copy(x = max(0f, s.base.x - d)); s.dragFrom = s.dragFrom?.let { it.copy(x = it.x - d) }
        }
        // Drift switched on or off (the setting, TalkBack, Switch Access, animations): keep what it drifted.
        if (s.drifted != drifts) {
            val now = clockNow()
            if (s.drifted == true) s.base = s.base.copy(y = s.base.y + (max(0.0, now - s.resume) * DRIFT).toFloat())
            if (s.drifted != null) s.resume = max(s.resume, now)
            s.drifted = drifts
        }
        // The clock runs while it drifts or coasts; a drag moves `base` itself.
        LaunchedEffect(drifts, s.coasts) {
            while (drifts || clockNow() - s.flungAt < COAST * 5) withFrameNanos { s.clock = clockNow() }
            s.clock = clockNow()
        }
        val list = model.visibleChannels
        val n = list.size
        val nowX = (gi.now.secondsSince(gi.ws) / GuideGenerator.blockLength).toFloat() * slotW
        Column(Modifier.fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val at = p.value   // touching down stops the drift and any coast
                    val fx = if ((at.x <= 0f && s.fling.x < 0f) || (at.x >= maxXs && s.fling.x > 0f)) 0f else s.fling.x   // pinned at an edge: not moving
                    s.swallow = Offset(fx, s.fling.y).getDistance() * exp(-(clockNow() - s.flungAt) / COAST) > 50
                    s.base = at; s.dragFrom = at; s.fling = Offset.Zero; s.resume = Double.MAX_VALUE; model.closeEntry()
                    val vt = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                    var total = Offset.Zero; var dragging = false
                    try {
                        while (true) {
                            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            total += c.positionChange(); vt.addPosition(c.uptimeMillis, c.position)
                            if (!dragging && total.getDistance() > viewConfiguration.touchSlop) dragging = true
                            if (dragging) {
                                c.consume()   // a drag, not a tap on the cell under it
                                val from = s.dragFrom ?: at
                                s.base = Offset((from.x - total.x / density).coerceIn(0f, maxXs), from.y - total.y / density)
                            }
                        }
                        val v = if (dragging) vt.calculateVelocity() else androidx.compose.ui.unit.Velocity.Zero
                        s.fling = Offset(-v.x / density, -v.y / density); s.flungAt = clockNow(); s.coasts++
                    } finally {   // also a touch the system took: the drift resumes as after any other
                        s.dragFrom = null; s.swallow = false; s.resume = clockNow() + model.settings.idleSeconds   // the cell's tap ran first
                    }
                }
            }
            .semantics {   // TalkBack's scroll forward / back move a page of channels (time stays on the drag)
                verticalScrollAxisRange = ScrollAxisRange({ 1f }, { 2f })
                scrollBy { _, dy ->
                    val at = p.value; val page = max(1, (gridH / rowH).toInt() - 1) * rowH
                    s.base = Offset(at.x, at.y + sign(dy) * page); s.fling = Offset.Zero; s.resume = Double.MAX_VALUE
                    true
                }
            }) {
            TimeHeader(gi, p, colW, slotW, nowX, Modifier.height(headH.dp))
            Row(Modifier.fillMaxWidth().weight(1f)
                // The bottom fades out under the tab bar.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0.64f to Color.Black, 0.94f to Color.Transparent), blendMode = BlendMode.DstIn)
                }) {
                Box(Modifier.width(colW.dp).fillMaxHeight().clipToBounds()) {
                    for (row in first until first + count) key(row) {
                        ChannelCell(list[((row % n) + n) % n], Modifier.offset { IntOffset(0, (row * rowH - p.value.y).dp.roundToPx()) }.height((rowH - 4).dp))
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                    if (!t.mm) Box(Modifier.offset { IntOffset((nowX - p.value.x - 1).dp.roundToPx(), 0) }.width(2.dp).fillMaxHeight()
                        .background(Brush.verticalGradient(listOf(t.accent, t.accent.copy(alpha = 0.12f)))).clearAndSetSemantics { })
                    for (row in first until first + count) key(row) {
                        TrackRow(list[((row % n) + n) % n], gi, slotW, rowH,
                            Modifier.offset { IntOffset((-p.value.x).dp.roundToPx(), (row * rowH - p.value.y).dp.roundToPx()) }) { k ->
                            if (!s.swallow) model.tapPortrait(k)
                        }
                    }
                }
            }
        }
    }
}

/** Half-hour labels across the track. Late Show: a now pill on the current time. Midnight Movie: the clock in a red
 *  block over the channel column and red labels on a dark bar. */
@Composable
private fun TimeHeader(gi: GuideInfo, p: androidx.compose.runtime.State<Offset>, colW: Float, slotW: Float, nowX: Float, modifier: Modifier) {
    val t = LocalTheme.current
    val labels = (0 until BLOCKS).map { gi.ws.plusSeconds((it * GuideGenerator.blockLength).toLong()) }
    Box(modifier.fillMaxWidth().clearAndSetSemantics { }) {   // the header says the time once
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(colW.dp).fillMaxHeight()) {
            if (t.mm) Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 4.dp, bottom = 4.dp).background(t.accent),
                Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally), Alignment.CenterVertically) {
                PText(Clock.time(gi.now), t.pBody(16, FontWeight.ExtraBold), t.accentInk)
                PText(Clock.ap(gi.now), t.pBody(11, FontWeight.ExtraBold), t.accentInk)
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            if (t.mm) Box(Modifier.fillMaxSize().padding(bottom = 4.dp).background(hex("#070505")))
            labels.forEachIndexed { i, d ->
                val lx = i * slotW
                if (t.mm) Row(Modifier.offset { IntOffset((lx - p.value.x).dp.roundToPx(), 0) }.width(slotW.dp).fillMaxHeight().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).padding(start = 10.dp), Arrangement.spacedBy(3.dp), Alignment.CenterVertically) {
                        PText(Clock.time(d), t.pBody(16, FontWeight.Bold), t.accent)
                        PText(Clock.ap(d), t.pBody(11, FontWeight.Bold), t.accent)
                    }
                    Box(Modifier.width(4.dp).fillMaxHeight().background(t.bg))
                }
                // A label the now pill would sit on gives way to it.
                else if ((nowX - lx) !in -20f..66f) PText(Clock.time(d), t.pBody(13, FontWeight.SemiBold), t.dim,
                    Modifier.offset { IntOffset((lx - p.value.x).dp.roundToPx(), 0) }.padding(start = 6.dp, top = 8.dp))
            }
        }
    }
    // The now pill may overhang the channel column, as on the TV (at :00 and :30 it would be cut in half).
    if (!t.mm) Box(Modifier.offset { IntOffset((colW + nowX - p.value.x - 50).dp.roundToPx(), 5.dp.roundToPx()) }.width(100.dp)
        .graphicsLayer { alpha = if (nowX - p.value.x < -10) 0f else 1f }, contentAlignment = Alignment.TopCenter) {
        PText(Clock.time(gi.now), t.pBody(11, FontWeight.Bold), t.accentInk,
            Modifier.background(t.accent, CircleShape).padding(horizontal = 9.dp, vertical = 4.dp))
    }
    }
}

/** A pinned channel cell: number above the name (two lines at most). */
@Composable
private fun ChannelCell(ch: Channel, modifier: Modifier) {
    val t = LocalTheme.current
    Box(modifier.fillMaxWidth().padding(start = if (t.mm) 8.dp else 0.dp, end = if (t.mm) 4.dp else 2.dp).background(if (t.mm) t.panel else Color.Transparent)
        .padding(start = if (t.mm) 8.dp else 16.dp, end = if (t.mm) 4.dp else 0.dp), contentAlignment = Alignment.CenterStart) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            PText(ch.number.toString(), t.pBody(13, FontWeight.Bold).copy(fontFeatureSettings = "tnum"), t.accent)
            // The mockup's 13 (the Type table says 14): at 14 a long one-word name ("Adrenaline") breaks mid-word.
            PText(t.heading(ch.name), t.pHeading(13, if (t.mm) FontWeight.Bold else FontWeight.SemiBold)
                .copy(letterSpacing = (if (t.mm) 0.04 else -0.01).em), t.ink, maxLines = 2)
        }
    }
}

/** One channel's programs in the twelve hours, at their places on the track. A row reads its programs in time order.
 *  ponytail: every program in the twelve hours is composed for each row in view; limit them to the stretch in view if an
 *  older phone stutters. */
@Composable
private fun TrackRow(ch: Channel, gi: GuideInfo, slotW: Float, rowH: Float, modifier: Modifier, tap: (ProgramKey) -> Unit) {
    val model = LocalModel.current
    val block = GuideGenerator.blockLength
    val ws = gi.ws; val we = ws.plusSeconds((block * BLOCKS).toLong())
    // The whole twelve hours wide: under the track's width a cell longer than the view would be cut to it.
    Box(modifier.wrapContentWidth(Alignment.Start, unbounded = true).width((slotW * BLOCKS).dp).height(rowH.dp)
        .semantics { contentDescription = "Channel ${ch.number}, ${ch.name}" }) {
        for (s in model.slots(ch, ws, we)) key(s.start) {
            val l = maxOf(s.start, ws); val r = minOf(s.end, we)
            val x = (l.secondsSince(ws) / block).toFloat() * slotW; val w = (r.secondsSince(l) / block).toFloat() * slotW
            val k = ProgramKey(ch.id, s.start)
            PortraitCell(ch, s, l, r, s.start < ws, gi.now, gi.key == k,
                Modifier.offset((x + 3).dp, 0.dp).size(max(0f, w - 6).dp, (rowH - 4).dp), tap)
        }
    }
}

/** One program: title and sub, elapsed fill while it's on (Late Show), the focus colors on the selected channel's. */
@Composable
private fun PortraitCell(ch: Channel, slot: Slot, l: Instant, r: Instant, contL: Boolean, now: Instant, focused: Boolean, modifier: Modifier, tap: (ProgramKey) -> Unit) {
    val model = LocalModel.current
    val t = LocalTheme.current
    val st = GuideGenerator.status(slot, now)
    val live = st == ProgramStatus.live || st == ProgramStatus.`break`
    val item = slot.item
    val fill = if (live && !t.mm) (now.secondsSince(l) / r.secondsSince(l)).coerceIn(0.0, 1.0).toFloat() else 0f
    val sc = if (focused) t.focusDim else t.dim
    val sub = when {
        st == ProgramStatus.`break` -> "Trailers until ${Clock.time(slot.end)}"
        item.kind == MediaItem.Kind.movie -> (if (t.mm) listOf("MOVIE", item.year?.toString()) else listOf(item.year?.toString(), "${(item.runtime / 60).toInt()} min"))
            .filterNotNull().joinToString(" · ")
        t.mm -> "“${item.title}”"
        else -> item.title
    }
    val base = if (focused) t.focusBg else if (live) t.liveCell else t.cell
    val fillColor = if (focused) t.focusFill else t.line
    val shape = t.pCell
    val k = ProgramKey(ch.id, slot.start)
    Box(modifier
        .graphicsLayer { if (focused && !t.mm) { scaleX = 1.03f; scaleY = 1.03f } }
        .then(if (focused && !t.mm) Modifier.shadow(12.dp, shape) else Modifier)
        .clip(shape)
        .background(Brush.horizontalGradient(0f to fillColor, fill to fillColor, fill to base, 1f to base))
        .onTouch { tap(k) }
        .clearAndSetSemantics {
            contentDescription = model.portraitLabel(ch, slot, now); role = Role.Button; selected = focused
            customActions = buildList {
                if (live) add(CustomAccessibilityAction("Watch") { model.tune(ch.id, Playback.Mode.live); true })
                add(CustomAccessibilityAction(if (live) "Start from the beginning" else "Watch now") { model.tune(ch.id, Playback.Mode.start, programStart = slot.start); true })
                add(CustomAccessibilityAction("Details") { model.openInfo(k); true })
            }
        }
        .padding(start = if (contL) 24.dp else 10.dp, end = 10.dp), contentAlignment = Alignment.CenterStart) {
        if (contL) Box(Modifier.offset((-16).dp, 0.dp).size(8.dp, 12.dp).drawWithContent {   // started before the window
            drawPath(Path().apply { moveTo(size.width, 0f); lineTo(0f, size.height / 2); lineTo(size.width, size.height); close() }, sc)
        })
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PText(item.displayTitle, t.pBody(15, if (t.mm) FontWeight.Bold else FontWeight.SemiBold), if (focused) t.focusInk else t.ink, maxLines = 1)
            PText(sub, t.pBody(13, FontWeight.Medium), sc, maxLines = 1)
        }
    }
}

// MARK: - Large fonts

/** At large font sizes: a 72-dp preview strip, then one row per channel with what's on now (and how far in) and what's
 *  next. Same taps as the grid. */
@Composable
private fun PortraitList(gi: GuideInfo) {
    val model = LocalModel.current
    val t = LocalTheme.current
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (t.mm) 4.dp else 6.dp)) {
        item {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
                PreviewWindow(gi, portrait = 128f, strip = true)
                PText(gi.pvTitle, t.pHeading(20), t.ink, Modifier.weight(1f).clearAndSetSemantics { }, maxLines = 3)
            }
        }
        items(model.visibleChannels, key = { it.id }) { ch ->
            val s = model.slotOnAir(ch, gi.now) ?: return@items
            OnNowRow(ch, s, gi.now, Modifier.padding(horizontal = 16.dp), selected = gi.ch.id == ch.id, next = model.slotOnAir(ch, s.end)) {
                model.tapPortrait(ProgramKey(ch.id, s.start))
            }
        }
        item { Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(110.dp)) }   // clear of the tab bar
    }
}
