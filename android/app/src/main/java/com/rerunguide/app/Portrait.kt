package com.rerunguide.app

import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import android.app.AlertDialog
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.rerunguide.core.Channel
import com.rerunguide.core.ChannelBuilder
import com.rerunguide.core.ProgramStatus
import com.rerunguide.core.Slot
import com.rerunguide.core.secondsSince
import java.time.Instant
import kotlin.math.ceil

/** Phones and tablets held upright (Design: the iOS handoff, Portrait.swift): Lineup, Channels and Settings behind a
 *  floating tab bar, Now playing pushed over them, Program info and New channel as sheets. Window-space composables over
 *  the same AppModel; a tap does what the remote's Select does on the TV layout (focus, then `press(select)`), so every
 *  rule lives in one place. Screens with no portrait design stay on the stage (RootView). */
@Composable
fun PortraitRoot() {
    val model = LocalModel.current
    val t = LocalTheme.current
    val watching = model.screen == Screen.watch
    Box(Modifier.fillMaxSize().then(if (watching) Modifier else Modifier.background(t.bg).drawBehind {
        if (t.id == ThemeID.LATE) drawRect(Brush.radialGradient(listOf(t.glow, Color.Transparent), Offset(size.width * 0.22f, size.height * 0.1f), 360 * density))
    })) {
        // Now playing paints the strip under the status bar itself (the player shows through everywhere else it leaves clear).
        if (watching) Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(t.bg))
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            when (model.screen) {
                Screen.watch -> PortraitWatch()
                Screen.channels -> PortraitChannels()
                Screen.settings -> PortraitSettings()
                else -> PortraitGuide()
            }
        }
        if (!watching && !(model.screen == Screen.channels && model.channelColumn == 1)) PortraitTabs(Modifier.align(Alignment.BottomCenter))
        PortraitToast(Modifier.align(Alignment.BottomCenter))
        // Midnight Movie's screen lines and vignette over everything but playback.
        if (t.id == ThemeID.MIDNIGHT && model.settings.crtLines && !watching) Box(Modifier.fillMaxSize().crt(0.09f, 0.62f to 0.38f))
        val o = model.overlay
        if (o is Overlay.info || o == Overlay.newChannel) PortraitSheet(o)
    }
}

/** `Text(s).font(style).foregroundStyle(color)`, truncating with an ellipsis like SwiftUI. */
@Composable
internal fun PText(s: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    BasicText(s, modifier, style.copy(color = color), overflow = TextOverflow.Ellipsis, maxLines = maxLines)

/** Section labels, chips and kickers: small caps, tracked. */
@Composable
internal fun PLabel(text: String, color: Color? = null, modifier: Modifier = Modifier) {
    val t = LocalTheme.current
    PText(text.uppercase(), t.pBody(11, FontWeight.Bold).copy(letterSpacing = 0.14.em), color ?: t.dim, modifier)
}

internal val Theme.pShape: Shape get() = if (id == ThemeID.MIDNIGHT) RectangleShape else CircleShape
internal val Theme.pGroup: Shape get() = if (id == ThemeID.MIDNIGHT) RectangleShape else RoundedCornerShape(20.dp)
internal val Theme.pCell: Shape get() = if (id == ThemeID.MIDNIGHT) RectangleShape else RoundedCornerShape(12.dp)

@Composable
private fun PortraitToast(modifier: Modifier) {
    val model = LocalModel.current
    val t = LocalTheme.current
    val toast = model.toast ?: return
    PText(toast.message, t.pBody(15, FontWeight.SemiBold).copy(textAlign = TextAlign.Center), t.ink,
        modifier.semantics { liveRegion = LiveRegionMode.Polite }   // TalkBack reads it as it appears
            .windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, bottom = if (model.screen == Screen.watch) 24.dp else 100.dp)
            .shadow(16.dp, RoundedCornerShape(if (t.id == ThemeID.MIDNIGHT) 0.dp else 16.dp))
            .background(t.panel2, RoundedCornerShape(if (t.id == ThemeID.MIDNIGHT) 0.dp else 16.dp)).padding(horizontal = 18.dp, vertical = 12.dp))
}

// MARK: - Tab bar

/** Lineup · Channels · Settings, floating 22 dp above the navigation bar. Late Show: a translucent pill, the tab on in
 *  cream. Midnight Movie: a square dark bar, the tab on in red. */
@Composable
private fun PortraitTabs(modifier: Modifier) {
    val model = LocalModel.current
    val t = LocalTheme.current
    val mm = t.id == ThemeID.MIDNIGHT
    Row(modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(start = 20.dp, end = 20.dp, bottom = 22.dp).widthIn(max = 520.dp).fillMaxWidth()
        .height(58.dp).shadow(15.dp, t.pShape)
        .background(if (mm) hex("#070505") else hex("#26201c").copy(alpha = 0.94f), t.pShape)
        .border(if (mm) 2.dp else 1.dp, if (mm) t.line else t.ink.copy(alpha = 0.08f), t.pShape)
        .padding(5.dp), Arrangement.spacedBy(4.dp)) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale.coerceAtMost(1.3f))) {
        for ((label, s) in listOf("Lineup" to Screen.guide, "Channels" to Screen.channels, "Settings" to Screen.settings)) {
            val on = model.screen == s
            Box(Modifier.weight(1f).fillMaxSize().background(if (on) t.focusBg else Color.Transparent, t.pShape)
                .onTouch { if (s == Screen.channels) model.channelColumn = 0; if (!on) model.go(s) }
                .semantics { selected = on }, contentAlignment = Alignment.Center) {
                PText(t.heading(label), t.pBody(15, if (mm) FontWeight.ExtraBold else FontWeight.SemiBold).copy(letterSpacing = (if (mm) 0.08 else 0.0).em),
                    if (on) t.focusInk else t.mute, maxLines = 1)
            }
        }
        }
    }
}

// MARK: - Shared rows

/** A channel and what's on it now: number, title, "Gumshoe · 46 min in" (or the break), a small progress bar. Now
 *  playing's Also on now; the lineup's large-font list adds what's next. */
@Composable
internal fun OnNowRow(ch: Channel, slot: Slot, now: Instant, modifier: Modifier = Modifier, selected: Boolean = false, next: Slot? = null, onTap: () -> Unit) {
    val t = LocalTheme.current
    val into = now.secondsSince(slot.start)
    val brk = into >= slot.item.runtime
    val minutes = (into / 60).toInt()
    val line = "${t.heading(ch.name)} · " + if (brk) "Trailers until ${Clock.time(slot.end)}" else "$minutes min in"
    val sub = if (selected) t.focusDim else t.dim
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).background(if (selected) t.focusBg else t.panel, t.pCell)
        .onTouch(onTap)
        .clearAndSetSemantics {
            role = Role.Button; this.selected = selected
            contentDescription = "Channel ${ch.number}, ${ch.name}. ${slot.item.displayTitle}. " +
                (if (brk) "Trailers until ${Clock.time(slot.end)}" else "$minutes minutes in, of ${span(slot.duration)}") +
                (next?.let { ". Next at ${Clock.time(it.start)}, ${it.item.displayTitle}" } ?: "")
        }
        .padding(horizontal = 14.dp, vertical = 8.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
        PText(ch.number.toString(), t.pBody(15, FontWeight.ExtraBold).copy(fontFeatureSettings = "tnum", textAlign = TextAlign.End),
            if (selected) t.focusInk else t.accent, Modifier.widthIn(min = 24.dp))
        Column(Modifier.weight(1f), Arrangement.spacedBy(1.dp)) {
            PText(slot.item.displayTitle, t.pBody(15, FontWeight.SemiBold), if (selected) t.focusInk else t.ink, maxLines = 2)
            PText(line, t.pBody(12), sub, maxLines = 2)
            if (next != null) PText("Next · ${Clock.time(next.start)} ${next.item.displayTitle}", t.pBody(12), sub)
        }
        Box(Modifier.size(40.dp, 3.dp).background(if (selected) t.focusDim else t.line, t.pShape)) {
            Box(Modifier.size((40 * (into / slot.duration).coerceIn(0.0, 1.0)).toFloat().dp, 3.dp).background(if (selected) t.focusInk else t.accent, t.pShape))
        }
    }
}

/** "2 hours", "90 minutes". */
internal fun span(seconds: Double): String {
    val m = (seconds / 60).toInt()
    return if (m % 60 == 0) "${m / 60} hour" + (if (m == 60) "" else "s") else "$m minutes"
}

/** A section label and its rounded group of rows (Settings, the channel editor). */
@Composable
private fun PGroup(title: String?, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTheme.current
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp), Arrangement.spacedBy(8.dp)) {
        if (title != null) PLabel(title, modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() })
        Column(Modifier.fillMaxWidth().clip(t.pGroup).background(t.panel), content = content)
    }
}

/** One row: label (and a description under it), then a switch for On/Off values, else the value and a chevron. */
@Composable
private fun PRow(label: String, value: String = "", desc: String = "", first: Boolean = false, tint: Color? = null, chevron: Boolean = true,
                 toggle: Boolean = false, action: () -> Unit) {
    val t = LocalTheme.current
    val mm = t.id == ThemeID.MIDNIGHT
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
        .drawBehind { if (!first) drawRect(if (mm) t.bg else t.ink.copy(alpha = 0.07f), size = size.copy(height = (if (mm) 2 else 1) * density)) }
        .onTouch(action)
        .clearAndSetSemantics { contentDescription = listOf(label, value, desc).filter { it.isNotEmpty() }.joinToString(". "); role = Role.Button }
        .padding(horizontal = 16.dp, vertical = 10.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
        Column(Modifier.weight(1f), Arrangement.spacedBy(2.dp)) {
            PText(label, t.pBody(17, FontWeight.SemiBold), tint ?: t.ink)
            if (desc.isNotEmpty()) PText(desc, t.pBody(13), t.dim)
        }
        if (toggle) PSwitch(value == "On")
        else if (value.isNotEmpty() || chevron) {
            PText(value, t.pBody(15).copy(textAlign = TextAlign.End), t.dim, Modifier.widthIn(max = 180.dp))
            if (chevron) PText("›", t.pBody(20), t.dim)
        }
    }
}

/** Late Show: a rounded switch, amber when on. Midnight Movie: a square one, red when on with a dark knob. */
@Composable
private fun PSwitch(on: Boolean) {
    val t = LocalTheme.current
    Box(Modifier.size(51.dp, 31.dp).background(if (on) t.accent else t.line, t.pShape).padding(2.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(27.dp).shadow(3.dp, t.pShape).background(if (on && t.id == ThemeID.MIDNIGHT) t.bg else t.ink, t.pShape))
    }
}

/** A large screen title and its line (Channels, Settings). */
@Composable
private fun PTitle(title: String, sub: String? = null) {
    val t = LocalTheme.current
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp), Arrangement.spacedBy(8.dp)) {
        PText(t.heading(title), t.pHeading(34).copy(letterSpacing = (if (t.id == ThemeID.MIDNIGHT) 0.0 else -0.035).em), t.ink, Modifier.semantics { heading() })
        if (sub != null) PText(sub, t.pBody(15), t.mute)
    }
}

/** Dark dialogs, like the text-entry one (TouchViews). */
private fun dialog(context: android.content.Context) = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)

// MARK: - Settings

/** The TV's Settings list, grouped: a tap on a row is the remote's Select on it (`pressSettings`). The profile card
 *  switches profiles in place; Who's watching, Server and Sign out go to screens with no portrait design (the stage). */
@Composable
private fun PortraitSettings() {
    val model = LocalModel.current
    val t = LocalTheme.current
    val context = LocalContext.current
    val rows = model.settingsRows
    val keys = rows.mapNotNull { it.key }
    val sections = mutableListOf<Pair<String, MutableList<SettingsRow>>>()
    for (r in rows) if (r.key == null) sections += r.label to mutableListOf() else if (r.key != SettingsRow.Key.profile) sections.last().second += r
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 700.dp)) {
            PTitle("Settings")
            val p = model.profile
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp).fillMaxWidth().background(t.panel, t.pGroup)
                .onTouch {
                    val profiles = model.persisted.profiles
                    dialog(context).setTitle("Who’s watching?").setItems((profiles.map { it.name } + "Add, rename or delete…").toTypedArray()) { _, i ->
                        if (i == profiles.size) {
                            model.go(Screen.profiles); model.focusIndex = profiles.indexOfFirst { it.id == model.persisted.currentProfileID }.coerceAtLeast(0)
                        } else if (profiles[i].id != model.persisted.currentProfileID) {
                            model.switchProfile(profiles[i].id); model.guideReset(); model.toast("Hi, ${profiles[i].name}.")
                        }
                    }.show()
                }
                .clearAndSetSemantics { contentDescription = "${p.name}. Switch profile"; role = Role.Button }
                .padding(horizontal = 16.dp, vertical = 12.dp), Arrangement.spacedBy(14.dp), Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(colorHex(p.colorHex), t.pShape), contentAlignment = Alignment.Center) {
                    PText(p.initial, t.pBody(18, FontWeight.ExtraBold), t.accentInk)
                }
                Column(Modifier.weight(1f), Arrangement.spacedBy(2.dp)) {
                    PText(p.name, t.pBody(16, FontWeight.SemiBold), t.ink)
                    PText("Switch profile", t.pBody(13), t.dim)
                }
                PText("›", t.pBody(20), t.dim)
            }
            for ((title, items) in sections) if (items.isNotEmpty()) PGroup(title) {
                items.forEachIndexed { i, r ->
                    if (r.key == SettingsRow.Key.theme) ThemePicker()
                    else {
                        val action = r.key == SettingsRow.Key.rebuild || r.key == SettingsRow.Key.signout
                        // Switches only for the On/Off rows: Subtitles and Attract mode also read "On" or "Off" at times.
                        PRow(r.label, r.value, if (r.key == SettingsRow.Key.about) r.desc else short(r), i == 0, if (action) t.accent else null,
                            toggle = r.key in listOf(SettingsRow.Key.crt, SettingsRow.Key.auto, SettingsRow.Key.breaks, SettingsRow.Key.spots, SettingsRow.Key.idents),
                            chevron = !action && r.key != SettingsRow.Key.about) {
                            val j = keys.indexOf(r.key)
                            if (j >= 0) { model.focusIndex = j; model.press(RemoteCommand.select) }
                        }
                    }
                }
            }
        }
    }
}

/** The handoff's one-line descriptions where it has them; the TV's longer ones elsewhere (the spots' says how to report one). */
private fun short(r: SettingsRow): String = when (r.key) {
    SettingsRow.Key.crt -> "On guide previews, never on playback"
    SettingsRow.Key.auto -> "Like the old preview channel"
    SettingsRow.Key.rebuild, SettingsRow.Key.signout, SettingsRow.Key.theme -> ""
    else -> r.desc
}

/** Theme as two segments, each in its own theme's face. Applies at once and is saved per profile. */
@Composable
private fun ThemePicker() {
    val model = LocalModel.current
    val t = LocalTheme.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), Arrangement.spacedBy(12.dp)) {
        PText("Theme", t.pBody(17, FontWeight.SemiBold), t.ink)
        Row(Modifier.fillMaxWidth().background(t.bg, t.pShape).padding(4.dp), Arrangement.spacedBy(4.dp)) {
            for (id in ThemeID.entries) {
                val on = model.profile.theme == id
                val th = id.theme
                Box(Modifier.weight(1f).heightIn(min = 40.dp).background(if (on) t.focusBg else Color.Transparent, t.pShape)
                    .onTouch { model.setTheme(id) }.semantics { selected = on }, contentAlignment = Alignment.Center) {
                    PText(th.heading(id.displayName), th.heading(if (id == ThemeID.LATE) 15 else 17).copy(letterSpacing = (if (id == ThemeID.LATE) -0.01 else 0.06).em),
                        if (on) t.focusInk else t.mute, maxLines = 1)
                }
            }
        }
    }
}

// MARK: - Channels

/** The lineup's channels, hidden ones too: a row opens the editor (pushed), "+ New channel" the picker sheet. */
@Composable
private fun PortraitChannels() {
    val model = LocalModel.current
    val t = LocalTheme.current
    val ch = model.selectedChannel
    if (model.channelColumn == 1 && ch != null) return PortraitChannelEditor(ch)
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (t.id == ThemeID.MIDNIGHT) 4.dp else 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        item { Box(Modifier.widthIn(max = 700.dp).padding(bottom = 12.dp)) { PTitle("Channels", "Built from your library. Change anything, or make your own.") } }
        item {
            ChannelRow("+", "New channel", "From a director, an actor, or one show", "") {
                model.channelSelection = null; model.channelColumn = 0; model.press(RemoteCommand.select)
            }
        }
        items(model.channels, key = { it.id }) { c ->
            ChannelRow(c.number.toString(), c.name.ifEmpty { "Untitled" }, c.definition.rule.joinToString(" · ") { it.label },
                if (model.isHidden(c.id)) "Hidden" else if (c.isCustom) "Custom" else "Auto") {
                model.channelSelection = c.id; model.channelColumn = 1; model.channelFieldIndex = 0
            }
        }
        item { Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars).height(120.dp)) }
    }
}

@Composable
private fun ChannelRow(n: String, name: String, tag: String, badge: String, action: () -> Unit) {
    val t = LocalTheme.current
    Row(Modifier.widthIn(max = 700.dp).fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 60.dp).background(t.panel, t.pCell)
        .onTouch(action).semantics(mergeDescendants = true) { }
        .padding(horizontal = 16.dp, vertical = 10.dp), Arrangement.spacedBy(14.dp), Alignment.CenterVertically) {
        PText(n, t.pBody(17, FontWeight.Bold).copy(fontFeatureSettings = "tnum", textAlign = TextAlign.End), t.accent, Modifier.widthIn(min = 30.dp))
        Column(Modifier.weight(1f), Arrangement.spacedBy(2.dp)) {
            PText(t.heading(name), t.pBody(16, FontWeight.SemiBold), t.ink, maxLines = 2)
            if (tag.isNotEmpty()) PText(tag, t.pBody(13), t.dim, maxLines = 2)
        }
        if (badge.isNotEmpty()) PText(badge, t.pBody(13, FontWeight.SemiBold), t.dim)
        PText("›", t.pBody(20), t.dim, Modifier.clearAndSetSemantics { })
    }
}

/** The channel editor, pushed: the TV's fields as rows, a tap on one its Select (`pressChannels`). Name and number type
 *  in the text-entry dialog, as on the stage. */
@Composable
private fun PortraitChannelEditor(ch: Channel) {
    val model = LocalModel.current
    val t = LocalTheme.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 700.dp).fillMaxWidth()) {
            Row(Modifier.padding(start = 16.dp, top = 4.dp).height(36.dp).background(t.panel, t.pShape)
                .onTouch { model.channelColumn = 0 }.clearAndSetSemantics { contentDescription = "Back to Channels"; role = Role.Button }
                .padding(start = 10.dp, end = 14.dp), Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
                PText("‹", t.pBody(24), t.accent)
                PText(t.heading("Channels"), t.pBody(15, FontWeight.SemiBold), t.ink)
            }
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp).semantics(mergeDescendants = true) { heading() },
                Arrangement.spacedBy(12.dp), Alignment.Bottom) {
                PText(ch.number.toString(), t.pHeading(40).copy(fontFeatureSettings = "tnum"), t.accent)
                PText(t.heading(ch.name.ifEmpty { "Untitled" }), t.pHeading(30), t.ink, maxLines = 2)
            }
            PText(ch.definition.rule.joinToString(" · ") { it.label }, t.pBody(14, FontWeight.SemiBold), t.mute, Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp))
            PText(if (ch.isCustom) "A channel you made. It keeps its schedule until you delete it."
                  else "Built automatically from your library’s tags. Your edits stay when it rebuilds.",
                t.pBody(13), t.dim, Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp))
            PGroup(null) {
                for (f in ChannelField.entries) {
                    val (label, value) = model.fieldText(f, ch)
                    PRow(label, value, first = f == ChannelField.title, tint = if (f == ChannelField.delete) t.accent else null, chevron = f != ChannelField.delete,
                        toggle = f == ChannelField.breaks || f == ChannelField.show) {
                        model.channelColumn = 1; model.channelFieldIndex = f.ordinal; model.press(RemoteCommand.select)
                    }
                }
            }
        }
    }
}

// MARK: - Sheets

/** Program info and the New channel picker, over the portrait screens: a bottom sheet over the scrim (a tap on the
 *  scrim, or Back, closes it). Late Show: rounded top. Midnight Movie: square with a red top edge. */
@Composable
private fun PortraitSheet(o: Overlay) {
    val model = LocalModel.current
    val t = LocalTheme.current
    val mm = t.id == ThemeID.MIDNIGHT
    val shape = if (mm) RectangleShape else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val rise = remember { Animatable(1f) }
    LaunchedEffect(Unit) { rise.animateTo(0f, tween(250)) }
    BoxWithConstraints(Modifier.fillMaxSize().background(t.scrim).pointerInput(Unit) { detectTapGestures { model.closeOverlay() } }
        .semantics { contentDescription = "Close"; onClick { model.closeOverlay(); true } }) {   // TalkBack: what's under it is covered
        Column(Modifier.align(Alignment.BottomCenter).widthIn(max = 700.dp).fillMaxWidth().heightIn(max = maxHeight * 0.92f)
            .graphicsLayer { translationY = rise.value * size.height }
            .shadow(30.dp, shape).background(t.panel, shape)
            .then(if (mm) Modifier.drawBehind { drawRect(t.accent, size = size.copy(height = 3 * density)) } else Modifier)
            .pointerInput(Unit) { detectTapGestures { } }   // taps on the sheet stay on it
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars)) {
            Box(Modifier.padding(top = 10.dp).size(38.dp, 5.dp).align(Alignment.CenterHorizontally).background(t.line, t.pShape).clearAndSetSemantics { })
            when (o) {
                is Overlay.info -> ProgramInfo(o.key)
                else -> NewChannel()
            }
        }
    }
}

/** A program: still with its when-chip, channel line, title, sub, when, description, then Program info's actions
 *  (`infoActions`), the first in the focus style and the extra ones (Edit channel) as text buttons. */
@Composable
private fun ProgramInfo(key: ProgramKey) {
    val model = LocalModel.current
    val t = LocalTheme.current
    val (ch, slot) = model.program(key) ?: return
    val now = Instant.now()
    val st = model.status(slot, now)
    val acts = model.infoActions(key)
    val chip = when (st) { ProgramStatus.later -> "Later · ${Clock.time(slot.start)}"; ProgramStatus.past -> "Earlier"; else -> "On now" }
    val whenText = when (st) {
        ProgramStatus.live -> "${(now.secondsSince(slot.start) / 60).toInt()} min in"
        ProgramStatus.`break` -> "Trailers until ${Clock.time(slot.end)}"
        ProgramStatus.later -> "Starts in ${ceil(slot.start.secondsSince(now) / 60).toInt()} min"
        else -> "Aired earlier tonight"
    }
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(t.pGroup).clearAndSetSemantics { }) {
            HueGradient(ch.hue, Modifier.matchParentSize())
            model.server?.backdropURL(slot.item)?.let { RemoteImage(it, Modifier.matchParentSize(), ContentScale.Crop) }
            Box(Modifier.matchParentSize().crt(0.2f, null))
            PLabel(chip, t.focusInk, Modifier.padding(12.dp).background(t.focusBg, t.pShape).padding(horizontal = 10.dp, vertical = 4.dp))
        }
        PText("Channel ${ch.number} · ${ch.name}".uppercase(), t.pBody(12, FontWeight.Bold).copy(letterSpacing = 0.1.em), t.accent, Modifier.padding(top = 16.dp))
        PText(t.heading(slot.item.displayTitle), t.pHeading(32).copy(letterSpacing = (if (t.id == ThemeID.MIDNIGHT) 0.0 else -0.035).em), t.ink,
            Modifier.padding(top = 6.dp).semantics { heading() })
        PText(slot.item.infoLine, t.pBody(15), t.mute, Modifier.padding(top = 8.dp))
        PText("${Clock.range(slot.start, slot.end)} · $whenText", t.pBody(14), t.dim, Modifier.padding(top = 3.dp))
        slot.item.overview?.takeIf { it.isNotEmpty() }?.let { PText(it, t.pBody(15).copy(lineHeight = 1.45.em), t.mute, Modifier.padding(top = 12.dp)) }
        Column(Modifier.padding(top = 22.dp), Arrangement.spacedBy(10.dp)) {
            acts.forEachIndexed { i, a ->
                Box(Modifier.fillMaxWidth().heightIn(min = if (i < 2) 50.dp else 44.dp)
                    .background(if (i == 0) t.focusBg else if (i == 1) t.panel2 else Color.Transparent, t.pShape)
                    .onTouch { a.run() }, contentAlignment = Alignment.Center) {
                    PText(t.heading(a.label), t.pBody(16, if (i == 0) FontWeight.Bold else FontWeight.SemiBold)
                        .copy(letterSpacing = (if (t.id == ThemeID.MIDNIGHT) 0.06 else 0.0).em), if (i == 0) t.focusInk else if (i == 1) t.ink else t.dim)
                }
            }
        }
    }
}

/** New channel: what to build it from, then who (or which show), as on the TV (`pressNewChannel`). */
@Composable
private fun NewChannel() {
    val model = LocalModel.current
    val t = LocalTheme.current
    val step = model.newChannelStep
    val unit = if (model.newChannelType == ChannelBuilder.CandidateKind.series) "episodes" else "films"
    val rows = if (step == 0) ChannelBuilder.CandidateKind.entries.zip(listOf("One filmmaker, back to back.", "Follow one face through your library.", "One series, every episode in order."))
        .map { it.first.rawValue to it.second }
        else model.candidateCache.map { it.name to "${it.count} $unit in your library" }
    val title = if (step == 0) "Build it from…" else when (model.newChannelType) {
        ChannelBuilder.CandidateKind.director -> "Pick a director"; ChannelBuilder.CandidateKind.actor -> "Pick an actor"; else -> "Pick a show"
    }
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PLabel("New channel", t.accent, Modifier.weight(1f))
            if (step == 1) PText(t.heading("‹ Back"), t.pBody(15, FontWeight.SemiBold), t.accent,
                Modifier.heightIn(min = 44.dp).wrapContentHeight().onTouch { model.press(RemoteCommand.back) })
        }
        PText(t.heading(title), t.pHeading(30), t.ink, Modifier.padding(top = 4.dp).semantics { heading() })
        PText(if (step == 1) "Only names with enough in your library to fill an evening are listed." else "Rerun Guide finds everything that matches and puts it on a schedule.",
            t.pBody(15), t.mute, Modifier.padding(top = 6.dp))
        Column(Modifier.padding(top = 18.dp), Arrangement.spacedBy(if (t.id == ThemeID.MIDNIGHT) 4.dp else 6.dp)) {
            rows.forEachIndexed { i, r ->
                Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).background(t.panel2, t.pCell)
                    .onTouch { model.focusIndex = i; model.press(RemoteCommand.select) }.semantics(mergeDescendants = true) { }
                    .padding(horizontal = 16.dp, vertical = 6.dp), Arrangement.Center) {
                    PText(t.heading(r.first), t.pBody(17, FontWeight.SemiBold), t.ink)
                    PText(r.second, t.pBody(13), t.dim)
                }
            }
            if (rows.isEmpty()) PText("Nothing in your library has enough to fill an evening yet.", t.pBody(15), t.dim)
        }
    }
}
