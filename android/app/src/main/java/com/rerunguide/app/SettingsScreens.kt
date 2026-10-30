package com.rerunguide.app

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.rerunguide.core.Channel
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

// Port of App/Settings/SettingsScreens.swift. The tvOS inline text fields (ProfileNameField, EditField) are not ported:
// on Android text entry is always TextEntry's dialog.

// MARK: - Profiles ("Who’s watching?")

@Composable
fun ProfilesScreen() {
    val model = LocalModel.current
    val t = LocalTheme.current
    val profiles = model.persisted.profiles
    val tiles = profiles.size + if (profiles.size < 5) 1 else 0
    val left = (1920f - (tiles * 250f + (tiles - 1) * 64f)) / 2
    // unclipped: on phones the glow carries on into the letterbox
    Box(Modifier.size(1920.dp, 1080.dp).drawBehind {
        // radial-gradient(1100px 700px at 50% 20%, glow, transparent 70%): an ellipse of radii 770 × 490 at (960, 216)
        val c = Offset(960.dp.toPx(), 216.dp.toPx())
        val r = 770.dp.toPx()
        scale(1f, 490f / 770f, c) { drawCircle(Brush.radialGradient(listOf(t.glow, Color.Transparent), c, r), r, c) }
    }) {
        TextAt(t.heading("Who’s watching?"), t.heading(96).copy(color = t.ink, letterSpacing = t.tracking(t.headingTracking)),
            0, 206, 1920, 96, Alignment.Center)
        for (i in 0 until tiles) {
            val p = profiles.getOrNull(i)
            val x = left + i * 314
            ProfileTile(initial = p?.initial ?: "+", name = p?.name ?: "Add profile", sub = p?.let { sub(model, it) } ?: "",
                color = p?.let { colorHex(it.colorHex) } ?: t.panel2, ink = if (p == null) t.ink else t.accentInk,
                focused = i == model.focusIndex,
                modifier = Modifier.at(x, 382, 250, 378).tapSelects(model) { model.profileMenu = null; model.focusIndex = i })
            if (model.profileMenu == i) {
                // Rename / Delete row, segmented-pill style.
                Box(Modifier.at(x, 776, 250, 56), contentAlignment = Alignment.Center) {
                    Row(Modifier.wrapContentSize(unbounded = true).background(t.panel, RoundedCornerShape(t.pill.dp)).padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        model.profileActions.forEachIndexed { j, label ->
                            val on = j == model.profileMenuIndex
                            BasicText(label,
                                Modifier.tapSelects(model) { model.profileMenuIndex = j }
                                    .background(if (on) t.accent else Color.Transparent, RoundedCornerShape(t.pill.dp))
                                    .padding(horizontal = 16.dp, vertical = 8.dp).swiftHeight(t.body(24)),
                                t.body(24, FontWeight.Bold).copy(color = if (on) t.accentInk else t.dim), maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
        val hint = t.body(28).copy(color = t.mute)
        Box(Modifier.at(0, 840, 1920, 34), contentAlignment = Alignment.Center) {
            if (Platform.isTV) GlyphText("Each profile keeps its own theme and lineup. Select yours, or press ", SettingsGlyph.playPause,
                " on any, to rename or delete it.", hint, Modifier.wrapContentHeight(unbounded = true))
            else BasicText("Each profile keeps its own theme and lineup. ${Platform.verb} yours to rename or delete it.",
                Modifier.wrapContentHeight(unbounded = true), hint)
        }
    }
}

private fun sub(model: AppModel, p: Profile): String {
    val hidden = model.channels.count { it.id in p.hiddenChannelIDs }
    return "${p.theme.displayName}\n${model.channels.size - hidden} channels"   // two lines: one ran past the 250-wide tile
}

@Composable
private fun ProfileTile(initial: String, name: String, sub: String, color: Color, ink: Color, focused: Boolean, modifier: Modifier) {
    val t = LocalTheme.current
    // .animation(.easeInOut(duration: 0.18), value: focused)
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(180, easing = EaseInOut), label = "tileScale")
    val ring by animateFloatAsState(if (focused) 1f else 0f, tween(180, easing = EaseInOut), label = "tileRing")
    val nameColor by animateColorAsState(if (focused) t.ink else t.dim, tween(180, easing = EaseInOut), label = "tileName")
    Box(modifier) {
        Box(Modifier.at(15, 0, 220, 220).scale(scale)
            .drawBehind { drawCircle(color) }
            // box-shadow 0 0 0 8px ink: an 8-wide ring just outside the circle
            .drawWithContent { drawContent(); drawCircle(t.ink, size.minDimension / 2 + 4.dp.toPx(), alpha = ring, style = Stroke(8.dp.toPx())) },
            contentAlignment = Alignment.Center) {
            BasicText(initial, Modifier.wrapContentSize(unbounded = true), t.heading(100, FontWeight.Bold).copy(color = ink))
        }
        TextAt(t.heading(name), t.body(38, FontWeight.SemiBold).copy(color = nameColor), 0, 254, 250, 46, Alignment.Center, maxLines = 1)
        TextAt(sub, t.body(24).copy(color = t.dim, textAlign = TextAlign.Center), 0, 320, 250, 64, Alignment.TopCenter, maxLines = 2)
    }
}

// MARK: - Settings

/** `index`: the row's focus index (keyed rows only; headers have none). */
private class SettingsPlaced(val row: SettingsRow, val y: Float, val index: Int?)

@Composable
fun SettingsScreen() {
    val model = LocalModel.current
    val t = LocalTheme.current
    // Row positions and the translate that keeps the focused row centered (the prototype's `ty` math).
    val rows = mutableListOf<SettingsPlaced>()
    var y = 0f; var fy = 0f; var k = 0
    var focused: SettingsRow? = null
    for (r in model.settingsRows) {
        rows += SettingsPlaced(r, y, if (r.key == null) null else k)
        if (r.key == null) y += 72 else { if (k == model.focusIndex) { fy = y; focused = r }; y += 92; k += 1 }
    }
    val ty = maxOf(0f, minOf(y - 904, fy - 904f / 2 + 46))
    val p = model.profile
    Box(Modifier.size(1920.dp, 1080.dp)) {
        TextAt(t.heading("Settings"), t.heading(84).copy(color = t.ink, letterSpacing = t.tracking(t.headingTracking)),
            96, 96, 620, 84, Alignment.CenterStart)
        Box(Modifier.at(96, 204, 40, 40).background(colorHex(p.colorHex), CircleShape), contentAlignment = Alignment.Center) {
            BasicText(p.initial, Modifier.wrapContentSize(unbounded = true), t.body(22, FontWeight.Bold).copy(color = t.accentInk))
        }
        TextAt("Watching as ${p.name}", t.body(28).copy(color = t.mute), 150, 204, 566, 40, Alignment.CenterStart)
        // SwiftUI squeezes these into the 600-high frame, ellipsizing the last line that fits.
        Column(Modifier.at(96, 388, 620, 600), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            BasicText(t.heading(focused?.label ?: ""), Modifier.swiftHeight(t.heading(46)),
                style = t.heading(46, FontWeight.SemiBold).lineSpacing(4.6f).copy(color = t.ink, letterSpacing = t.tracking(t.headingTracking)),
                overflow = TextOverflow.Ellipsis)
            SwiftPara(focused?.desc ?: "", t.body(30).copy(color = t.mute), 7.5f)
        }

        val spec = rememberScrollSpec(ty)
        val shift by animateFloatAsState(ty, tween(220, easing = EaseInOut), label = "settingsScroll")
        Box(Modifier.at(800, 88, 1024, 904).clipToBounds()) {   // rows scrolled out of view take no taps
            Box(Modifier.fillMaxSize().graphicsLayer { translationY = -shift.dp.toPx() }) {
                for (pl in rows) key(pl.row.id) {
                    if (pl.row.key == null) {
                        BasicText(pl.row.label.uppercase(), Modifier.at(0, pl.y, 1024, 72).padding(start = 28.dp, top = 30.dp),
                            t.body(24, FontWeight.Bold).copy(color = t.dim, letterSpacing = t.tracking(0.16)))
                    } else {
                        val f = pl.row.id == focused?.id
                        val bg by animateColorAsState(if (f) t.focusBg else t.panel, spec, label = "rowBg")
                        val fg by animateColorAsState(if (f) t.focusInk else t.ink, spec, label = "rowInk")
                        val vc by animateColorAsState(if (f) t.focusDim else t.dim, spec, label = "rowValue")
                        ListRow(bg, 84, 32, Modifier.at(0, pl.y, 1024, 84).tapSelects(model) {
                            // moving off Sign out disarms it, as the remote's Up / Down do
                            if (model.focusIndex != pl.index) model.signOutArmed = false
                            model.focusIndex = pl.index ?: 0
                        }) {
                            BasicText(pl.row.label, style = t.body(32, FontWeight.SemiBold).copy(color = fg))
                            Spacer(Modifier.weight(1f).widthIn(min = 24.dp))
                            BasicText(pl.row.value, Modifier.swiftHeight(t.body(30)), t.body(30).copy(color = vc))
                        }
                    }
                }
            }
        }
    }
}

/** A list/field row: rounded panel, horizontal padding, content vertically centered. The caller colors its texts. Search's
 *  rows too. */
@Composable
internal fun ListRow(bg: Color, height: Int, inset: Int, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val t = LocalTheme.current
    Row(modifier.fillMaxWidth().height(height.dp).background(bg, RoundedCornerShape(t.radius.dp)).padding(horizontal = inset.dp),
        verticalAlignment = Alignment.CenterVertically, content = content)
}

// MARK: - Channel editor

@Composable
fun ChannelsScreen() {
    val model = LocalModel.current
    val t = LocalTheme.current
    val list = model.channels
    val idx = model.channelIndex
    val p = model.profile
    val hidden = list.count { model.isHidden(it.id) }
    val count = "${list.size} channels" + if (hidden > 0) " · $hidden hidden for ${p.name}" else ""
    val total = (list.size + 1) * 96f - 8
    val ty = maxOf(0f, minOf(total - 784, idx * 96f - 784f / 2 + 44))
    fun rowState(i: Int) = if (i != idx) ChannelRowState.plain else if (model.channelColumn == 0) ChannelRowState.focused else ChannelRowState.selected
    Box(Modifier.size(1920.dp, 1080.dp)) {
        TextAt(t.heading("Channels"), t.heading(84).copy(color = t.ink, letterSpacing = t.tracking(t.headingTracking)),
            96, 64, 1000, 84, Alignment.CenterStart)
        TextAt("Drawn from your library. Rename, renumber, hide, or start one from scratch.", t.body(30).copy(color = t.mute),
            96, 158, 1200, 36, Alignment.CenterStart)
        TextAt(count, t.body(26).copy(color = t.dim), 1324, 157, 500, 31, Alignment.CenterEnd)

        val spec = rememberScrollSpec(ty)
        val shift by animateFloatAsState(ty, tween(220, easing = EaseInOut), label = "channelsScroll")
        Box(Modifier.at(96, 240, 760, 784).clipToBounds()) {   // rows scrolled out of view take no taps
            Box(Modifier.fillMaxSize().graphicsLayer { translationY = -shift.dp.toPx() }) {
                ChannelRow("+", "New channel", "From a director, an actor, or one show", "", rowState(0), spec,
                    Modifier.at(0, 0, 760, 88).tapSelects(model) { model.channelSelection = null; model.channelColumn = 0 })
                list.forEachIndexed { i, ch ->
                    key(ch.id) {
                        ChannelRow(ch.number.toString(), ch.name.ifEmpty { "Untitled" }, ch.definition.rule.joinToString(" · ") { it.label },
                            if (model.isHidden(ch.id)) "Hidden" else if (ch.isCustom) "Custom" else "Auto", rowState(i + 1), spec,
                            Modifier.at(0, (i + 1) * 96, 760, 88).tapSelects(model) { model.channelSelection = ch.id; model.channelColumn = 0 })
                    }
                }
            }
        }

        val ch = model.selectedChannel
        if (ch != null) ChannelDetail(ch) else NewChannelIntro()
    }
}

private enum class ChannelRowState { plain, selected, focused }

@Composable
private fun ChannelRow(n: String, name: String, tag: String, badge: String, state: ChannelRowState, spec: AnimationSpec<Color>, modifier: Modifier) {
    val t = LocalTheme.current
    val f = state == ChannelRowState.focused
    val sc by animateColorAsState(if (f) t.focusDim else if (state == ChannelRowState.selected) t.mute else t.dim, spec, label = "rowSub")
    val bg by animateColorAsState(if (f) t.focusBg else if (state == ChannelRowState.selected) t.panel2 else t.panel, spec, label = "rowBg")
    val fg by animateColorAsState(if (f) t.focusInk else t.ink, spec, label = "rowInk")
    val nc by animateColorAsState(if (f) t.focusInk else t.accent, spec, label = "rowNumber")
    ListRow(bg, 88, 28, modifier) {
        BasicText(n, Modifier.width(64.dp), t.body(34, FontWeight.Bold).copy(color = nc, textAlign = TextAlign.End, fontFeatureSettings = "tnum"),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Column(Modifier.weight(1f).padding(start = 22.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(t.heading(name), Modifier.swiftHeight(t.body(32)), t.body(32, FontWeight.SemiBold).copy(color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
            // tvOS lays out an empty Text("") 14 pt tall, not a full 32 pt line: a tagless row (a new channel with no rule
            // yet) has its title 9 pt lower than a tagged one (measured on an Apple TV screenshot).
            BasicText(tag, if (tag.isEmpty()) Modifier.height(14.dp) else Modifier.swiftHeight(t.body(24)),
                t.body(24).copy(color = sc), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BasicText(badge, Modifier.padding(start = 22.dp).swiftHeight(t.body(24)), t.body(24, FontWeight.SemiBold).copy(color = sc))
    }
}

@Composable
private fun NewChannelIntro() {
    val t = LocalTheme.current
    Column(Modifier.at(920, 252, 904, 772), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        BasicText(t.heading("Start a channel"), Modifier.height(72.dp).wrapContentHeight(unbounded = true),
            t.heading(72).copy(color = t.ink, letterSpacing = t.tracking(t.headingTracking)))
        SwiftPara("Name a director, an actor, or a single show. Rerun Guide rounds up everything that matches in your library and gives it the next free number.",
            t.body(32).copy(color = t.mute), 8f, Modifier.widthIn(max = 820.dp))
        val hint = t.body(28).copy(color = t.dim)
        if (Platform.isTV) GlyphText("Press ", SettingsGlyph.ok, " to start.", hint, Modifier.padding(top = 12.dp))
        else BasicText("${Platform.verb} New channel to start.", Modifier.padding(top = 12.dp), hint)
    }
}

@Composable
private fun ChannelDetail(channel: Channel) {
    val model = LocalModel.current
    val t = LocalTheme.current
    val ctx = LocalContext.current
    val ch = channel
    // TimelineView(.periodic(from: .now, by: 10))
    val now by produceState(Instant.now()) { while (true) { delay(10_000); value = Instant.now() } }
    // The column (~830 high) is taller than its 784 frame, as in SwiftUI: it hangs off the bottom instead of squeezing the rows.
    Column(Modifier.at(920, 240, 904, 784).wrapContentHeight(Alignment.Top, unbounded = true), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.height(96.dp).wrapContentHeight(unbounded = true), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            BasicText(ch.number.toString(), Modifier.alignByBaseline().swiftHeight(t.heading(96)), t.heading(96).copy(color = t.accent, fontFeatureSettings = "tnum"))
            BasicText(t.heading(ch.name.ifEmpty { "Untitled" }), Modifier.alignByBaseline().weight(1f, fill = false),
                t.heading(72).copy(color = t.ink, letterSpacing = t.tracking(t.headingTracking)), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // ponytail: chips never wrap; add a FlowRow if a rule ever has more than ~3 chips.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (chip in ch.definition.rule.map { it.label }) {
                BasicText(chip, Modifier.background(t.panel2, RoundedCornerShape(t.pill.dp)).padding(horizontal = 16.dp, vertical = 6.dp)
                    .swiftHeight(t.body(24)),
                    t.body(24, FontWeight.SemiBold).copy(color = t.ink), maxLines = 1)
            }
        }
        // One line, as SwiftUI lays it out: the overfull VStack takes its only give from this text (so its lineSpacing never shows).
        BasicText(if (ch.isCustom) "A channel you made. It keeps its schedule until you delete it."
                  else "Built automatically from your library’s tags. Your edits stay when it rebuilds.",
            Modifier.swiftHeight(t.body(26)), t.body(26).copy(color = t.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(onNow(model, ch, now, ctx), Modifier.swiftHeight(t.body(28)), t.body(28).copy(color = t.mute), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (field in ChannelField.entries) {
                val f = model.channelColumn == 1 && field.ordinal == model.channelFieldIndex
                // Swift's `.name` case is `title`: Kotlin can't call an enum entry `name`.
                val isName = field == ChannelField.title
                val (label, value) = model.fieldText(field, ch)
                val strong = isName || field == ChannelField.number
                ListRow(if (f) t.focusBg else t.panel, 72, 28,
                    Modifier.tapSelects(model) { model.channelColumn = 1; model.channelFieldIndex = field.ordinal }) {
                    BasicText(label, Modifier.weight(1f).swiftHeight(t.body(30)), t.body(30, FontWeight.SemiBold).copy(color = if (f) t.focusInk else t.ink))
                    // SwiftUI's HStack offers the plain value Text half the row before the maxWidth: .infinity label takes the rest:
                    // a long value wraps (spilling past the 72-high row) instead of squeezing the label.
                    BasicText(value, Modifier.widthIn(max = ((904 - 56) / 2).dp).padding(start = 24.dp).swiftHeight(t.body(30)), t.body(30, FontWeight.Medium)
                        .copy(color = if (strong) (if (f) t.focusInk else t.ink) else (if (f) t.focusDim else t.dim)),
                        overflow = TextOverflow.Visible)
                }
            }
        }
    }
}

private fun onNow(model: AppModel, channel: Channel, now: Instant, ctx: Context): String {
    val cur = model.slotOnAir(channel, now) ?: return ""
    var s = "On now: ${cur.item.displayTitle}"
    model.slotOnAir(channel, cur.end)?.let { next -> s += "  ·  Next at ${hourMinute(ctx, cur.end)}: ${next.item.displayTitle}" }
    return s
}

/** `.formatted(.dateTime.hour(.defaultDigits(amPM: .omitted)).minute())`: "9:30" (or "21:30" on a 24-hour clock). */
private fun hourMinute(ctx: Context, d: Instant): String {
    val h24 = DateFormat.is24HourFormat(ctx)
    val f = hourMinuteFormats.getOrPut(h24) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(Locale.getDefault(), if (h24) "Hm" else "hm").replace(Regex("[aBb]"), "").trim())
    }
    return f.format(d.atZone(ZoneId.systemDefault()))
}
/** hourMinute's formatter per 12/24-hour setting, built once. */
private val hourMinuteFormats = HashMap<Boolean, DateTimeFormatter>()

// MARK: - Helpers

/** SwiftUI ceils a text's height to whole points (24 pt Hanken: 31.27 → 32) where Compose rounds ascent and descent
 *  (→ 31); in stacks that shifted everything below by a point per text, and centered in a row (30 pt in a 72 row: 40 → 16
 *  above, 39 → 16.5 → 17) it sat a point low. */
internal fun Modifier.swiftHeight(style: TextStyle) = heightIn(min = ceil(style.fontSize.value * style.lineEm()).dp)

/** `Text(s).lineSpacing(n)` for a wrapping paragraph: lines `natural + n` apart, each snapped to the pixel as tvOS draws
 *  them (Compose's lineHeight rounds the pitch itself, 46.59 → 47, and the About text's 11th line drifted 4 pt), Para()'s
 *  orphan rule, and SwiftUI's squeeze: the last line that fits the offered height ellipsized. */
@Composable
private fun SwiftPara(text: String, style: TextStyle, spacing: Float, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val measure = { s: String -> measurer.measure(s, style, constraints = Constraints(maxWidth = constraints.maxWidth)) }
        var shown = text
        var r = measure(shown)
        val n = r.lineCount
        if (n >= 2 && constraints.hasBoundedWidth && text.getOrNull(r.getLineStart(n - 1) - 1) != '\n' &&
            !text.substring(r.getLineStart(n - 1), r.getLineEnd(n - 1)).trim().contains(' ') &&
            r.getLineRight(n - 1) - r.getLineLeft(n - 1) <= constraints.maxWidth / 4f) {   // a short orphan only, as Para
            val prevStart = r.getLineStart(n - 2)
            val gap = text.substring(prevStart, r.getLineEnd(n - 2)).trimEnd().lastIndexOf(' ')
            if (gap >= 0) { shown = text.substring(0, prevStart + gap + 1) + "\n" + text.substring(prevStart + gap + 1); r = measure(shown) }
        }
        val pitch = style.fontSize.value * style.lineEm() + spacing
        val fit = if (constraints.hasBoundedHeight) ((maxHeight.value + spacing) / pitch).toInt().coerceIn(1, r.lineCount) else r.lineCount
        Box(Modifier.height(ceil(fit * pitch - spacing).dp)) {
            for (i in 0 until fit) {
                val end = if (i == fit - 1) shown.length else r.getLineEnd(i)
                BasicText(shown.substring(r.getLineStart(i), end).trimEnd(), Modifier.offset(y = (i * pitch).dp), style,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** `Text(s).at(x, y, w:, h:, alignment:)`. The text keeps its natural height: a line taller than its frame spills out
 *  evenly above and below, as SwiftUI draws it, instead of being clipped at the bottom. */
@Composable
internal fun TextAt(text: String, style: TextStyle, x: Number, y: Number, w: Number, h: Number, align: Alignment, maxLines: Int = Int.MAX_VALUE) {
    Box(Modifier.at(x, y, w, h), contentAlignment = align) {
        BasicText(text, Modifier.wrapContentHeight(unbounded = true).swiftHeight(style), style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** SwiftUI's `.animation(.easeInOut(duration: 0.22), value: ty)` on a scrolling list eases every change that lands in the
 *  same update as the scroll (the rows' focus colors too); changes that leave the list where it is snap. */
@Composable
internal fun rememberScrollSpec(ty: Float): AnimationSpec<Color> {
    val last = remember { floatArrayOf(ty) }
    val scrolled = last[0] != ty
    SideEffect { last[0] = ty }
    return if (scrolled) tween(220, easing = EaseInOut) else snap()
}

/** SF Symbols in the hint copy, drawn in the text's color: `playpause.fill` (Play/Pause) and `button.programmable`
 *  (on the Google TV remote, OK: the clickpad center). */
private enum class SettingsGlyph { playPause, ok }

@Composable
private fun GlyphText(before: String, glyph: SettingsGlyph, after: String, style: TextStyle, modifier: Modifier = Modifier) {
    val c = style.color
    val placeholder = when (glyph) {
        // Advances measured from Apple TV screenshots: the SF Symbols carry ~4 pt (playpause) and ~2.5 pt side bearings.
        SettingsGlyph.playPause -> Placeholder(1.68.em, 0.72.em, PlaceholderVerticalAlign.AboveBaseline)
        SettingsGlyph.ok -> Placeholder(1.18.em, 1.1.em, PlaceholderVerticalAlign.TextCenter)
    }
    BasicText(buildAnnotatedString { append(before); appendInlineContent("glyph"); append(after) }, modifier, style,
        inlineContent = mapOf("glyph" to InlineTextContent(placeholder) {
            Canvas(Modifier.fillMaxSize()) {
                when (glyph) {
                    SettingsGlyph.playPause -> {
                        // 1.39 em wide, 0.75 em tall, sitting 0.04 em below the placeholder's top (a point under the baseline)
                        val em = size.height / 0.72f
                        val x = 0.143f * em; val y = 0.04f * em; val w = 1.393f * em; val h = 0.75f * em
                        drawPath(Path().apply { moveTo(x, y); lineTo(x + w * 0.49f, y + h / 2); lineTo(x, y + h); close() }, c)
                        val bar = Size(w * 0.16f, h); val r = CornerRadius(0.04f * em)
                        drawRoundRect(c, Offset(x + w * 0.6f, y), bar, r)
                        drawRoundRect(c, Offset(x + w * 0.84f, y), bar, r)
                    }
                    SettingsGlyph.ok -> {
                        // 1 em across: a 0.1 em ring around a 0.71 em disc
                        val em = size.height / 1.1f
                        drawCircle(c, 0.45f * em, style = Stroke(0.1f * em))
                        drawCircle(c, 0.357f * em)
                    }
                }
            }
        }))
}
