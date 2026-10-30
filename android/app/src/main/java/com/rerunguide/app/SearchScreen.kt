package com.rerunguide.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rerunguide.core.TitleSearch
import kotlinx.coroutines.delay
import java.time.Instant

/** Options › Search: the text field, then up to 50 films and shows that match, each with when it's on next. The list
 *  keeps the focused row in view like the channel editor's. Text goes through TextEntry's dialog. */
@Composable
fun SearchScreen() {
    val model = LocalModel.current
    val t = LocalTheme.current
    val results = model.searchResults; val focus = model.searchFocus; val query = model.searchQuery
    val listH = 688f; val total = results.size * 96f - 8
    val ty = maxOf(0f, minOf(total - listH, (focus - 1) * 96f - listH / 2 + 44))
    // TimelineView(.periodic(by: 10))
    val now by produceState(Instant.now()) { while (true) { delay(10_000); value = Instant.now() } }
    LaunchedEffect(now) { model.refreshAirings(now) }
    Box(Modifier.size(1920.dp, 1080.dp)) {
        TextAt(t.heading("Search"), t.heading(84).copy(color = t.ink, letterSpacing = t.tracking(t.headingTracking)),
            96, 64, 1000, 84, Alignment.CenterStart)
        TextAt("Find a film or a show in your library, see when it’s on next, or make a channel of it.", t.body(30).copy(color = t.mute),
            96, 158, 1500, 36, Alignment.CenterStart)

        val f = focus == 0
        ListRow(if (f) t.focusBg else t.panel, 88, 32, Modifier.at(96, 232, 1728, 88).tapSelects(model) { model.searchFocus = 0 }) {
            SearchGlyph(if (f) t.focusDim else t.dim)
            BasicText(query.ifEmpty { "Type a title" }, Modifier.weight(1f).swiftHeight(t.body(32)),
                t.body(32, FontWeight.Medium).copy(color = if (query.isEmpty()) (if (f) t.focusDim else t.dim) else (if (f) t.focusInk else t.ink)),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        if (results.isEmpty()) {
            TextAt(if (query.isEmpty()) "Films by title, shows by name." else "Nothing in your library matches “$query”.",
                t.body(30).copy(color = t.dim), 128, 356, 1600, 36, Alignment.CenterStart)
        }
        val spec = rememberScrollSpec(ty)
        val shift by animateFloatAsState(ty, tween(220, easing = EaseInOut), label = "searchScroll")
        Box(Modifier.at(96, 344, 1728, listH).clipToBounds()) {   // rows scrolled out of view take no taps
            Box(Modifier.fillMaxSize().graphicsLayer { translationY = -shift.dp.toPx() }) {
                results.forEachIndexed { i, e ->
                    key(e.id) {
                        ResultRow(e, model.airsText(e, now), model.searchAirings[e.id]?.let { it.start <= now } ?: false, focus == i + 1, spec,
                            Modifier.at(0, i * 96, 1728, 88).tapSelects(model) { model.searchFocus = i + 1 })
                    }
                }
            }
        }
    }
}

/** Title, then year · Movie or Show; on the right when it's on next (the accent while it's on now). */
@Composable
private fun ResultRow(entry: TitleSearch.Entry, airs: String, onNow: Boolean, focused: Boolean, spec: AnimationSpec<Color>, modifier: Modifier) {
    val t = LocalTheme.current
    val bg by animateColorAsState(if (focused) t.focusBg else t.panel, spec, label = "rowBg")
    val fg by animateColorAsState(if (focused) t.focusInk else t.ink, spec, label = "rowInk")
    val sc by animateColorAsState(if (focused) t.focusDim else t.dim, spec, label = "rowSub")
    val ac by animateColorAsState(if (focused) t.focusInk else if (onNow) t.accent else t.dim, spec, label = "rowAirs")
    val kind = listOfNotNull(entry.year?.toString(), if (entry.isShow) "Show" else "Movie").joinToString(" · ")
    ListRow(bg, 88, 28, modifier) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(entry.title, Modifier.swiftHeight(t.body(32)), t.body(32, FontWeight.SemiBold).copy(color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(kind, Modifier.swiftHeight(t.body(24)), t.body(24).copy(color = sc), maxLines = 1)
        }
        BasicText(airs, Modifier.padding(start = 32.dp).swiftHeight(t.body(28)), t.body(28, FontWeight.Medium).copy(color = ac),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** SF Symbols' `magnifyingglass` at 30 pt semibold (measured on an Apple TV screenshot), in its 40-wide slot. */
@Composable
private fun SearchGlyph(color: Color) {
    Canvas(Modifier.padding(end = 20.dp).size(40.dp)) {
        val lens = center + Offset(-3.dp.toPx(), -2.5.dp.toPx())
        drawCircle(color, 10.dp.toPx(), lens, style = Stroke(3.8.dp.toPx()))
        drawLine(color, lens + Offset(7.dp.toPx(), 7.dp.toPx()), lens + Offset(14.dp.toPx(), 14.dp.toPx()), 5.dp.toPx(), StrokeCap.Round)
    }
}
