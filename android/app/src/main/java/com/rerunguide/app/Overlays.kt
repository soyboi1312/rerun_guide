package com.rerunguide.app

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.rerunguide.core.Channel
import com.rerunguide.core.ChannelBuilder
import com.rerunguide.core.ProgramStatus
import com.rerunguide.core.Slot
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil

// MARK: - Options menu (template "Options menu")

@Composable
fun OptionsOverlay() {
    val model = LocalModel.current
    val t = LocalTheme.current
    val sw = LocalStageWidth.current
    Box(Modifier.size(sw.dp, 1080.dp)) {
        Box(Modifier.matchParentSize().background(t.scrim).backgroundTap { model.press(RemoteCommand.back) })
        Column(
            Modifier.at(0, 0, 620, 1080).enter(dx = -40f).blocksTaps()
                // Off TV the panel runs on to the window's left, top and bottom edges past the stage (600 covers the margins).
                .fillWithShadow(t.panel, 0f, Color.Black.copy(alpha = 0.5f), blur = 40f, x = 30f, spill = if (Platform.isTV) 0f else 600f)
                .padding(start = 48.dp, top = 72.dp, end = 48.dp, bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(t.accent, CircleShape))
                BasicText(t.heading("Rerun Guide"), style = t.heading(32, FontWeight.SemiBold).copy(color = t.ink))
            }
            AppModel.OptionRow.entries.forEachIndexed { i, row ->
                val top = Modifier.padding(top = if (row == AppModel.OptionRow.guide || row == AppModel.OptionRow.theme) 24.dp else 0.dp)
                // The Theme row's chips are its buttons; one around the row would swallow them for TalkBack.
                OptionRowView(row, i == model.focusIndex,
                    if (row == AppModel.OptionRow.theme) top else top.tapSelects(model) { model.focusIndex = i })
            }
            Spacer(Modifier.weight(1f))
            Para("${model.serverLabel} · connected", modifier = Modifier.padding(horizontal = 24.dp),
                style = t.body(24).lineSpacing(5f).copy(color = t.dim))
        }
    }
}

@Composable
private fun OptionRowView(row: AppModel.OptionRow, focused: Boolean, modifier: Modifier) {
    val model = LocalModel.current
    val t = LocalTheme.current
    val f = t.fv(focused)
    // The guide row that matches the current filter is tinted so you can see which guide you're on.
    val isActiveGuide = when (row) {
        AppModel.OptionRow.guide -> model.guideFilter == GuideFilter.all
        AppModel.OptionRow.movies -> model.guideFilter == GuideFilter.movies
        AppModel.OptionRow.tv -> model.guideFilter == GuideFilter.tv
        else -> false
    }
    val label = when (row) {
        AppModel.OptionRow.profile -> model.profile.name
        AppModel.OptionRow.guide -> "Guide"
        AppModel.OptionRow.movies -> "Movies"
        AppModel.OptionRow.tv -> "TV Shows"
        AppModel.OptionRow.search -> "Search"
        AppModel.OptionRow.channels -> "Channels"
        AppModel.OptionRow.settings -> "Settings"
        AppModel.OptionRow.theme -> "Theme"
    }
    // 84, not 88: eight rows fit above the footer
    Row(modifier.fillMaxWidth().heightIn(min = 84.dp).background(f.bg, RoundedCornerShape(t.radius.dp)).padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        if (row == AppModel.OptionRow.profile) {
            Box(Modifier.size(56.dp).background(colorHex(model.profile.colorHex), CircleShape), contentAlignment = Alignment.Center) {
                BasicText(model.profile.initial, style = t.body(28, FontWeight.Bold).copy(color = t.accentInk))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(t.heading(label), style = t.body(34, FontWeight.SemiBold).copy(color = if (isActiveGuide && !focused) t.accent else f.fg),
                maxLines = if (row == AppModel.OptionRow.theme) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
            if (row == AppModel.OptionRow.profile) BasicText("Switch profile", style = t.body(24).copy(color = f.sc))
        }
        if (row == AppModel.OptionRow.theme) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ThemeID.entries.forEach { id ->
                    val on = id == t.id
                    BasicText(id.displayName,
                        Modifier.onTouch { model.setTheme(id) }   // the remote's Left / Right on this row
                            .background(if (on) (if (focused) t.focusInk else t.accent) else Color.Transparent, RoundedCornerShape(t.pill.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        style = t.body(24, FontWeight.Bold).copy(color = if (on) (if (focused) t.focusBg else t.accentInk) else (if (focused) t.focusInk else t.dim)),
                        softWrap = false, maxLines = 1)
                }
            }
        }
    }
}

// MARK: - Program info (template "Program info")

@Composable
fun InfoOverlay(key: ProgramKey) {
    val model = LocalModel.current
    val t = LocalTheme.current
    val sw = LocalStageWidth.current
    Box(Modifier.size(sw.dp, 1080.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().background(t.scrim).backgroundTap { model.press(RemoteCommand.back) })
        val p = model.program(key)
        if (p != null) {
            val now by produceState(Instant.now()) { while (true) { delay(10_000); value = Instant.now() } }   // TimelineView(.periodic(by: 10))
            InfoSheet(p.channel, p.slot, now, key, Modifier.enter(dy = 14f))
        } else {
            LaunchedEffect(Unit) { model.closeOverlay() }
        }
    }
}

@Composable
private fun InfoSheet(ch: Channel, slot: Slot, now: Instant, key: ProgramKey, modifier: Modifier) {
    val model = LocalModel.current
    val t = LocalTheme.current
    val st = model.status(slot, now)
    val kind = when (st) { ProgramStatus.live, ProgramStatus.`break` -> "On now"; ProgramStatus.later -> "Later"; ProgramStatus.past -> "Earlier" }
    val acts = model.infoActions(key)
    Row(modifier.blocksTaps().width(1480.dp).fillWithShadow(t.panel, t.radius, Color.Black.copy(alpha = 0.6f), blur = 60f, y = 40f).padding(56.dp),
        horizontalArrangement = Arrangement.spacedBy(56.dp)) {
        Box(Modifier.size(520.dp, 390.dp).clip(RoundedCornerShape(t.radius.dp))) {
            HueGradient(ch.hue, Modifier.matchParentSize())
            RemoteImage(model.server?.backdropURL(slot.item), Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            BasicText(kind.uppercase(),
                Modifier.padding(start = 24.dp, top = 22.dp).background(t.accent, RoundedCornerShape(t.pill.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
                style = t.body(24, FontWeight.Bold).copy(color = t.accentInk, letterSpacing = t.tracking(0.1)))
            Para(t.heading(slot.item.displayTitle), modifier = Modifier.align(Alignment.BottomStart).padding(start = 28.dp, end = 28.dp, bottom = 26.dp),
                style = t.heading(46).copy(color = Color.White))
        }
        // VStack(spacing: 14) { texts; Spacer(minLength: 0); Flow.padding(.top, 20) }.frame(minHeight: 390): the buttons sit
        // at the bottom of 390 points, or 14 + 14 + 20 below the texts when they run longer.
        Layout({
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                BasicText("Channel ${ch.number} · ${ch.name}".uppercase(),
                    style = t.body(26, FontWeight.SemiBold).copy(color = t.accent, letterSpacing = t.tracking(0.1)))
                Para(t.heading(slot.item.displayTitle), style = t.heading(76).copy(color = t.ink, letterSpacing = t.tracking(t.headingTracking)))
                Para(slot.item.infoLine, style = t.body(32).copy(color = t.mute))
                BasicText("${Clock.range(slot.start, slot.end)} · ${statusText(slot, st, now)}", style = t.body(28).copy(color = t.dim))
                slot.item.overview?.let { Para(it, style = t.body(30).lineSpacing(8f).copy(color = t.mute)) }
            }
            ActionPills(acts.map { it.label })
        }, Modifier.weight(1f)) { (texts, flow), c ->
            val loose = c.copy(minHeight = 0)
            val a = texts.measure(loose)
            val b = flow.measure(loose)
            val h = maxOf(390.dp.roundToPx(), a.height + 48.dp.roundToPx() + b.height)
            layout(c.maxWidth, h) { a.place(0, 0); b.place(0, h - b.height) }
        }
    }
}

// MARK: - New channel (template "New channel")

@Composable
fun NewChannelOverlay() {
    val model = LocalModel.current
    val t = LocalTheme.current
    val sw = LocalStageWidth.current
    val cards = newChannelCards(model)
    val rows = (cards.size + 2) / 3
    // ponytail: 2-row window around the focus (4 rows of 240 don't fit the stage); upgrade: animated row scroll.
    val first = maxOf(0, minOf(model.focusIndex / 3 - 1, rows - 2)) * 3
    val kind = model.newChannelType
    val title = if (model.newChannelStep != 1 || kind == null) "Build it from…" else when (kind) {
        ChannelBuilder.CandidateKind.director -> "Pick a director"
        ChannelBuilder.CandidateKind.actor -> "Pick an actor"
        ChannelBuilder.CandidateKind.series -> "Pick a show"
    }
    Box(Modifier.size(sw.dp, 1080.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().background(t.scrim).backgroundTap { model.press(RemoteCommand.back) })
        Column(
            Modifier.enter(dy = 14f).blocksTaps().width(1440.dp)
                .fillWithShadow(t.panel, t.radius, Color.Black.copy(alpha = 0.6f), blur = 60f, y = 40f).padding(64.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            BasicText("NEW CHANNEL", style = t.body(26, FontWeight.Bold).copy(color = t.accent, letterSpacing = t.tracking(0.14)))
            Para(t.heading(title), style = t.heading(76).copy(color = t.ink, letterSpacing = t.tracking(t.headingTracking)))
            Para(if (model.newChannelStep == 1) "Only names with enough in your library to fill an evening are listed."
                      else "Rerun Guide finds everything that matches and puts it on a schedule.",
                style = t.body(30).copy(color = t.mute))
            NewChannelRows(first, cards.size, Modifier.padding(top = 28.dp)) { r ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    for (i in r until r + 3) key(i) {
                        if (i < cards.size) NewChannelCard(cards[i], i == model.focusIndex, Modifier.weight(1f).tapSelects(model) { model.focusIndex = i })
                        else Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** `VStack(spacing: 24) { ForEach(rows from first, id: \.self) }.animation(.easeInOut(duration: 0.15), value: focusIndex)`:
 *  when the 2-row window moves, the row that stays slides to its new place while the leaving row fades out where it
 *  was and the entering one fades in where it lands (SwiftUI's default insert/remove transition). */
@Composable
private fun NewChannelRows(first: Int, count: Int, modifier: Modifier, row: @Composable (r: Int) -> Unit) {
    fun rows(f: Int) = (f until minOf(f + 6, count) step 3).toList()
    val window = remember { intArrayOf(first, first) }   // the window moved from, the window now
    val p = remember(first) {
        if (window[1] != first) { window[0] = window[1]; window[1] = first; Animatable(0f) } else Animatable(1f)
    }
    LaunchedEffect(p) { p.animateTo(1f, tween(150, easing = EaseInOut)) }
    val from = rows(window[0]); val to = rows(window[1])
    val all = (from + to).distinct().sorted()
    Layout({ all.forEach { r -> key(r) { row(r) } } }, modifier) { measurables, c ->
        val gap = 24.dp.roundToPx()
        val ps = measurables.map { it.measure(c.copy(minHeight = 0)) }
        fun tops(rs: List<Int>): Map<Int, Int> {
            var y = 0
            return rs.associateWith { r -> y.also { y += ps[all.indexOf(r)].height + gap } }
        }
        fun height(rs: List<Int>) = rs.sumOf { ps[all.indexOf(it)].height } + gap * maxOf(0, rs.size - 1)
        val a = tops(from); val b = tops(to); val t = p.value
        val h = (height(from) + (height(to) - height(from)) * t).toInt()
        layout(c.maxWidth, h) {
            all.forEachIndexed { i, r ->
                val y = when { r in a && r in b -> a.getValue(r) + (b.getValue(r) - a.getValue(r)) * t; r in b -> b.getValue(r).toFloat(); else -> a.getValue(r).toFloat() }
                val alpha = when { r in a && r in b -> 1f; r in b -> t; else -> 1 - t }
                ps[i].placeWithLayer(0, y.toInt()) { this.alpha = alpha; compositingStrategy = CompositingStrategy.ModulateAlpha }
            }
        }
    }
}

private fun newChannelCards(model: AppModel): List<Pair<String, String>> {
    if (model.newChannelStep == 0) {
        val desc = listOf("One filmmaker, back to back.", "Follow one face through your library.", "One series, every episode in order.")
        return ChannelBuilder.CandidateKind.entries.zip(desc).map { (k, d) -> k.rawValue to d }
    }
    val unit = if (model.newChannelType == ChannelBuilder.CandidateKind.series) "episodes" else "films"
    return model.candidateCache.map { it.name to "${it.count} $unit in your library" }
}

@Composable
private fun NewChannelCard(c: Pair<String, String>, focused: Boolean, modifier: Modifier) {
    val t = LocalTheme.current
    val f = t.fvAnimated(focused)
    val sh = liftShadow(focused)
    val textShadow = liftTextShadow(sh)
    Column(Modifier.zIndex(if (focused) 1f else 0f).then(modifier).fillMaxHeight().heightIn(min = 240.dp)
            .lift(focused, f.bg, t.radius, sh).padding(horizontal = 40.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Para(t.heading(c.first), style = t.heading(50).copy(color = f.fg, shadow = textShadow))
        Spacer(Modifier.weight(1f))
        Para(c.second, style = t.body(28).lineSpacing(4f).copy(color = f.sc, shadow = textShadow))
    }
}

// MARK: - Make a channel (Search)

/** Search's page for a result that isn't on in the next 24 hours (just Make a channel), and a film's people to make a
 *  channel of. Back returns to the Search page. */
@Composable
fun MakeChannelOverlay() {
    val model = LocalModel.current
    val t = LocalTheme.current
    val sw = LocalStageWidth.current
    Box(Modifier.size(sw.dp, 1080.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().background(t.scrim).backgroundTap { model.press(RemoteCommand.back) })
        val e = model.searchSelection
        if (e != null) {
            Column(
                Modifier.enter(dy = 14f).blocksTaps().width(1100.dp)
                    .fillWithShadow(t.panel, t.radius, Color.Black.copy(alpha = 0.6f), blur = 60f, y = 40f).padding(56.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                BasicText(listOfNotNull(if (e.isShow) "Show" else "Movie", e.year?.toString()).joinToString(" · ").uppercase(),
                    style = t.body(26, FontWeight.SemiBold).copy(color = t.accent, letterSpacing = t.tracking(0.1)))
                Para(t.heading(e.title), style = t.heading(76).copy(color = t.ink, letterSpacing = t.tracking(t.headingTracking)))
                Para(if (model.makePeople == null) model.airsText(e) else "Pick who the channel follows. Only names with enough in your library to fill an evening are listed.",
                    style = t.body(30).lineSpacing(6f).copy(color = t.mute))
                ActionPills(model.makeActions.map { it.label }, Modifier.padding(top = 20.dp))
            }
        } else {
            LaunchedEffect(Unit) { model.closeOverlay() }
        }
    }
}

// MARK: - Number keys (top-right box)

/** The channel number being typed on the guide or the player, "2 2 _", with the channel once the digits are one; or that
 *  there's no such channel. Takes no taps. */
@Composable
fun ChannelEntryView() {
    val model = LocalModel.current
    val t = LocalTheme.current
    val sw = LocalStageWidth.current
    // .transition(.opacity) under .animation(.easeOut(duration: 0.2)): the box fades in and out, and between typing and
    // "No channel".
    AnimatedContent(model.channelEntry, Modifier.size(sw.dp, 1080.dp), contentAlignment = Alignment.TopEnd, contentKey = { it?.missing }, label = "entry",
        transitionSpec = { fadeIn(tween(200, easing = EaseOut)) togetherWith fadeOut(tween(200, easing = EaseOut)) using null }) { entry ->
        // Closed, the box fades out showing what it last said: `entry` is what it last composed, never a third digit's
        // (that tunes in the same press).
        val e = if (entry != null && model.channelEntry == null) model.lastEntry ?: entry else entry
        if (e != null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
            Column(Modifier.padding(top = 56.dp, end = 72.dp).widthIn(min = 260.dp)
                    .fillWithShadow(t.panel2, t.radius, Color.Black.copy(alpha = 0.5f), blur = 30f, y = 20f)
                    .padding(start = 34.dp, top = 20.dp, end = 34.dp, bottom = 22.dp),
                horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (e.missing) {
                    BasicText("No channel ${e.digits.toIntOrNull() ?: 0}", style = t.body(32, FontWeight.SemiBold).copy(color = t.ink))
                } else {
                    BasicText((e.digits + "_".repeat(maxOf(0, 3 - e.digits.length))).toList().joinToString(" "),
                        style = t.heading(72).copy(color = t.accent, fontFeatureSettings = "tnum"))
                    e.digits.toIntOrNull()?.let { model.channel(numbered = it) }?.let { ch ->
                        BasicText(t.heading("${ch.number} · ${ch.name}"), style = t.body(30, FontWeight.SemiBold).copy(color = t.ink), maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// MARK: - Toast (template toastOn/toastMsg: top-right card)

@Composable
fun ToastView() {
    val model = LocalModel.current
    val t = LocalTheme.current
    val sw = LocalStageWidth.current
    val dy = with(LocalDensity.current) { 14.dp.roundToPx() }
    AnimatedContent(model.toast, Modifier.size(sw.dp, 1080.dp), contentAlignment = Alignment.TopEnd, contentKey = { it?.n }, label = "toast",
        transitionSpec = {   // .transition(.opacity.combined(with: .offset(y: 14))), .animation(.easeOut(duration: 0.25))
            (fadeIn(tween(250, easing = EaseOut)) + slideInVertically(tween(250, easing = EaseOut)) { dy }) togetherWith
                (fadeOut(tween(250, easing = EaseOut)) + slideOutVertically(tween(250, easing = EaseOut)) { dy }) using null
        }) { toast ->
        // AnimatedContent hands its content the full stage as a fixed size; the Box loosens it so the card hugs its text.
        if (toast != null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
            Para(toast.message,
                modifier = Modifier.padding(top = 56.dp, end = 72.dp).widthIn(max = 820.dp)
                    .fillWithShadow(t.panel2, t.radius, Color.Black.copy(alpha = 0.5f), blur = 30f, y = 20f)
                    .padding(horizontal = 30.dp, vertical = 20.dp),
                style = t.body(28, FontWeight.SemiBold).copy(color = t.ink))
        }
    }
}

// MARK: - Shared bits

/** Program info's buttons (the Make a channel page's too): pills that wrap, the focused one lifted. */
@Composable
private fun ActionPills(labels: List<String>, modifier: Modifier = Modifier) {
    val model = LocalModel.current
    val t = LocalTheme.current
    Flow(spacing = 18f, modifier) {
        labels.forEachIndexed { i, label ->
            val on = i == model.focusIndex
            val f = t.fvAnimated(on)
            val sh = liftShadow(on)
            BasicText(label,
                Modifier.zIndex(if (on) 1f else 0f).lift(on, f.bg, t.pill, sh).tapSelects(model) { model.focusIndex = i }
                    .padding(horizontal = 34.dp, vertical = 18.dp),
                style = t.body(30, FontWeight.SemiBold).copy(color = f.fg, shadow = liftTextShadow(sh)))
        }
    }
}

private data class FocusColors(val bg: Color, val fg: Color, val sc: Color)

/** The prototype's fv(): focused → focus bg/ink/sub, else panel/ink/dim. */
private fun Theme.fv(focused: Boolean) = if (focused) FocusColors(focusBg, focusInk, focusDim) else FocusColors(panel, ink, dim)

/** fv() under the Swift `.animation(.easeInOut(duration: 0.15), value: model.focusIndex)`. */
@Composable
private fun Theme.fvAnimated(focused: Boolean): FocusColors {
    val f = fv(focused)
    val spec = tween<Color>(150, easing = EaseInOut)
    return FocusColors(animateColorAsState(f.bg, spec, label = "bg").value, animateColorAsState(f.fg, spec, label = "fg").value,
        animateColorAsState(f.sc, spec, label = "sc").value)
}

/** Focus lift: scale + shadow, the shadow drawn with the element's own fill (`fill`, corner `radius`) since it needs a
 *  shape. `shadow` is `liftShadow(on)`; SwiftUI's `.shadow` also shadows every text inside, so callers give their
 *  texts `liftTextShadow(shadow)`. Callers put `zIndex(on ? 1 : 0)` outermost. Animated like the 0.15 s easeInOut
 *  around every Lift user. */
@Composable
private fun Modifier.lift(on: Boolean, fill: Color, radius: Float, shadow: Color): Modifier {
    val t = LocalTheme.current
    val s by animateFloatAsState(if (on) t.focusScale else 1f, tween(150, easing = EaseInOut), label = "lift")
    return graphicsLayer { scaleX = s; scaleY = s }.fillWithShadow(fill, radius, shadow, blur = 22f, y = 18f)
}

/** Lift's shadow colour: `.shadow(color: .black.opacity(on && t.focusShadow ? 0.55 : 0), radius: 22, y: 18)`. */
@Composable
private fun liftShadow(on: Boolean): Color {
    val t = LocalTheme.current
    val a by animateFloatAsState(if (on && t.focusShadow) 0.55f else 0f, tween(150, easing = EaseInOut), label = "liftShadow")
    return Color.Black.copy(alpha = a)
}

/** The same shadow on a text inside a lifted view. */
@Composable
private fun liftTextShadow(shadow: Color): Shadow? {
    if (shadow.alpha <= 0f) return null
    val d = LocalDensity.current
    return with(d) { Shadow(shadow, Offset(0f, 18.dp.toPx()), skiaRadius(22.dp.toPx())) }
}

/** SwiftUI's shadow radius is the blur's sigma; Android's shadow layers take Skia's radius (sigma = 0.57735 r + 0.5). */
internal fun skiaRadius(sigmaPx: Float) = maxOf(0.1f, (sigmaPx - 0.5f) / 0.57735f)

/** The prototype's rbIn / rbSlide entrance (220 ms fade + slide from `dx`/`dy`). ModulateAlpha so the fade doesn't clip
 *  the shadow to the bounds (an offscreen layer would). */
@Composable
private fun Modifier.enter(dx: Float = 0f, dy: Float = 0f): Modifier {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(220, easing = EaseOut)) }
    return graphicsLayer {
        val p = shown.value
        alpha = p; translationX = dx.dp.toPx() * (1 - p); translationY = dy.dp.toPx() * (1 - p)
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

/** SwiftUI hit-tests the sheet itself, so a tap on it never reaches the scrim's `backgroundTap` underneath. */
private fun Modifier.blocksTaps() = pointerInput(Unit) {}

/** `RoundedRectangle(cornerRadius: radius).fill(fill).shadow(color: shadow, radius: blur, x:, y:)` as a background,
 *  `spill` points past the left, top and bottom edges (the Options panel off TV). Hardware canvases draw a shape's
 *  shadow layer from API 28; before that the shadow is drawn once into a quarter-size software bitmap (where shadow
 *  layers always work; the blur hides the low resolution) under the crisp fill. Shared with TouchViews.kt (MenuHint). */
internal fun Modifier.fillWithShadow(fill: Color, radius: Float, shadow: Color = Color.Transparent, blur: Float = 0f,
                                     x: Float = 0f, y: Float = 0f, spill: Float = 0f) = drawWithCache {
    val dx = x.dp.toPx(); val dy = y.dp.toPx(); val sigma = blur.dp.toPx()
    val s = spill.dp.toPx()
    val r = radius.dp.toPx()   // Skia shrinks radii that don't fit, so 999 is a capsule like t.pill
    val w = size.width + s; val h = size.height + 2 * s
    val legacy = shadow.alpha > 0f && Build.VERSION.SDK_INT < 28 && w >= 1 && h >= 1
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fill.toArgb()
        if (shadow.alpha > 0f && !legacy) setShadowLayer(skiaRadius(sigma), dx, dy, shadow.toArgb())
    }
    if (!legacy) return@drawWithCache onDrawBehind { drawContext.canvas.nativeCanvas.drawRoundRect(-s, -s, size.width, size.height + s, r, r, paint) }
    val k = 0.25f
    val m = ceil(3 * sigma + maxOf(abs(dx), abs(dy)))   // room for the blur
    val bmp = Bitmap.createBitmap(ceil((w + 2 * m) * k).toInt(), ceil((h + 2 * m) * k).toInt(), Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(bmp).drawRoundRect(m * k, m * k, (m + w) * k, (m + h) * k, r * k, r * k, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fill.toArgb(); setShadowLayer(skiaRadius(sigma * k), dx * k, dy * k, shadow.toArgb())
    })
    val dst = RectF(-s - m, -s - m, -s - m + bmp.width / k, -s - m + bmp.height / k)
    val smooth = Paint(Paint.FILTER_BITMAP_FLAG)
    onDrawBehind {
        drawContext.canvas.nativeCanvas.drawBitmap(bmp, null, dst, smooth)
        drawContext.canvas.nativeCanvas.drawRoundRect(-s, -s, size.width, size.height + s, r, r, paint)
    }
}

/** Channel-hue preview background (the prototype's pvBg): Midnight Movie is a 160° linear ramp, Late Show a radial one. */
@Composable
internal fun HueGradient(hue: Double, modifier: Modifier) {
    val t = LocalTheme.current
    Spacer(modifier.drawWithCache {
        val brush = if (t.id == ThemeID.MIDNIGHT)
            Brush.linearGradient(listOf(channelColor(hue, 0.46, 0.14), channelColor(hue, 0.2, 0.08)),
                start = Offset(size.width * 0.33f, size.height * 0.03f), end = Offset(size.width * 0.67f, size.height * 0.97f))
        else   // ponytail: circle stands in for the CSS 130%×100% ellipse at 25% 15%.
            Brush.radialGradient(0f to channelColor(hue, 0.5, 0.1), 0.55f to channelColor(hue, 0.24, 0.05), 1f to channelColor(hue, 0.13, 0.02),
                center = Offset(size.width * 0.25f, size.height * 0.15f), radius = 600.dp.toPx())
        onDrawBehind { drawRect(brush) }
    })
}

/** CSS `display:flex; flex-wrap:wrap; gap:` for the info sheet's buttons: each child at its ideal size, wrapping. */
@Composable
private fun Flow(spacing: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = spacing.dp.roundToPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val placeables = measurables.map { it.measure(Constraints()) }
        var x = 0; var y = 0; var rowH = 0
        val origins = placeables.map { p ->
            if (x > 0 && x + p.width > width) { x = 0; y += rowH + gap; rowH = 0 }
            IntOffset(x, y).also { x += p.width + gap; rowH = maxOf(rowH, p.height) }
        }
        val w = if (constraints.hasBoundedWidth) width else placeables.indices.maxOfOrNull { origins[it].x + placeables[it].width } ?: 0
        val h = placeables.indices.maxOfOrNull { origins[it].y + placeables[it].height } ?: 0
        layout(w, h) { placeables.forEachIndexed { i, p -> p.place(origins[i]) } }
    }
}

private fun statusText(slot: Slot, st: ProgramStatus, now: Instant): String = when (st) {
    ProgramStatus.live -> "On now, ${Duration.between(slot.start, now).toMillis() / 60_000} min in"
    ProgramStatus.`break` -> "On now, between programs"
    ProgramStatus.later -> "Starts in ${ceil(Duration.between(now, slot.start).toMillis() / 60_000.0).toInt()} min"
    ProgramStatus.past -> "Aired earlier tonight"
}
