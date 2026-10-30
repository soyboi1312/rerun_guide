package com.rerunguide.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.rerunguide.core.Channel
import com.rerunguide.core.LibrarySection
import com.rerunguide.core.ServerKind

// Six first-launch screens, ported from the prototype's "First launch" / "Sign in" (isForm) / "Sign in with a code" /
// "Building lineup" blocks (App/Onboarding/OnboardingScreens.swift). Absolute layout on the 1920×1080 stage; every
// color/font comes from the theme tokens. Text entry is always TextEntry's dialog on Android, so the fields only ever
// draw their resting look and the Swift inline EditField has no counterpart.

// MARK: - Welcome

@Composable
fun WelcomeScreen() {
    val model = LocalModel.current
    val t = LocalTheme.current
    val cards = AppModel.welcomeCards
    OnboardingStage {
        Column(Modifier.offset(140.dp, 120.dp).width(1640.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Brand(size = 34f, dot = 16f)
            BasicText(t.heading("Tonight’s lineup."), Modifier.widthIn(max = 1400.dp),
                t.heading(128).copy(color = t.ink, letterSpacing = t.tracking(t.headingTracking)))
            Para("The movies and shows on your server, running to a schedule the way TV used to be. Pick a channel, catch whatever’s playing, change your mind whenever. Nothing to set up on the server side.",
                t.body(34).lineSpacing(7f).copy(color = t.mute), Modifier.widthIn(max = 1180.dp))
        }

        Box(Modifier.at(140, 651, 900, 31), contentAlignment = Alignment.CenterStart) {
            Kicker("Where do your shows live?", t.dim, Modifier.wrapContentHeight(unbounded = true))
        }
        Row(Modifier.at(140, 710, 1640, 240), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            for (i in 0 until 3) {
                val f = model.focusIndex == i
                ServiceCard(cards[i].first, cards[i].second, f,
                    Modifier.weight(1f).fillMaxHeight().zIndex(if (f) 1f else 0f).tapSelects(model) { model.focusIndex = i })
            }
        }
        // A 4th, smaller card so the app can be tried without a server; sits at the right edge above the row.
        // Its 122 is too short for name + description: SwiftUI cuts the description to one line ("…") and the card
        // grows down past 122 to fit it (about 135), as the content refuses to be smaller than one line of each.
        ServiceCard(cards[3].first, cards[3].second, model.focusIndex == 3,
            Modifier.offset(1360.dp, 560.dp).width(420.dp).heightIn(min = 122.dp).height(IntrinsicSize.Min).tapSelects(model) { model.focusIndex = 3 },
            nameSize = 40f, descSize = 24f, pad = 28f, descLines = 1)
    }
}

@Composable
private fun ServiceCard(name: String, desc: String, focused: Boolean, modifier: Modifier,
                        nameSize: Float = 68f, descSize: Float = 28f, pad: Float = 40f, descLines: Int = Int.MAX_VALUE) {
    val t = LocalTheme.current
    val shape = RoundedCornerShape(t.radius.dp)
    val shadow = liftTextShadow(focused, t)
    Column(modifier.lift(focused, t, shape).background(fade(if (focused) t.focusBg else t.panel), shape)
        .padding(vertical = pad.dp, horizontal = (pad + 4).dp)) {
        BasicText(t.heading(name), style = t.heading(nameSize).copy(color = fade(if (focused) t.focusInk else t.ink), shadow = shadow))
        Spacer(Modifier.weight(1f))
        // Unbounded: like the SwiftUI VStack, a description taller than what's left runs into the padding, never clipped.
        val descStyle = t.body(descSize).lineSpacing(descSize * 0.15f).copy(color = fade(if (focused) t.focusDim else t.dim), shadow = shadow)
        if (descLines == Int.MAX_VALUE) Para(desc, descStyle, Modifier.wrapContentHeight(Alignment.Top, unbounded = true))
        else BasicText(desc, style = descStyle, maxLines = descLines, overflow = TextOverflow.Ellipsis)
    }
}

// MARK: - Address

@Composable
fun AddressScreen() {
    val model = LocalModel.current
    val t = LocalTheme.current
    val n = model.foundServers.size
    // At most three server rows fit above Continue; the window slides with focus so Continue never leaves the screen.
    val cap = 3
    val near = minOf(maxOf(model.focusIndex - 1, 0), maxOf(n - 1, 0))
    val start = minOf(maxOf(near - 1, 0), maxOf(n - cap, 0))
    val end = minOf(start + cap, n)
    val account = model.foundServers.any { it.accessToken.isNotEmpty() }   // rows from the account, not ones found on the network
    val source = if (account) "On your ${model.serviceKind.displayName} account" else "Spotted on your network"
    FormScreen(model.serviceKind.displayName, "Which server?",
        if (account) "Pick the server your lineup comes from. ${Platform.select} connects." else "Type the address you use on other devices, or pick one spotted on your network.") {
        FormRow("Server address", model.focusIndex == 0, Modifier.tapSelects(model) { model.focusIndex = 0 }) {
            ValueText(model.address, "http://", model.focusIndex == 0, Modifier.weight(1f))
        }
        if (n > 0 || model.discovering) {
            SectionLabel(if (n > cap) "$source · ${start + 1}–$end of $n" else source)
            for (i in start until end) {
                val s = model.foundServers[i]
                val f = model.focusIndex == i + 1
                FormRow(s.name, f, Modifier.tapSelects(model) { model.focusIndex = i + 1 }) {
                    Detail(s.baseURL.replace("http://", ""), f, Modifier.weight(1f))
                    if (s.baseURL == model.address) Checkmark(if (f) t.focusInk else t.accent)   // the pick: what the address field holds, focus or not
                }
            }
            if (n == 0) Detail("Looking around the network…", false)
        }
        PillButton("Continue", model.focusIndex == n + 1, Modifier.padding(top = 24.dp).tapSelects(model) { model.focusIndex = n + 1 }, busy = model.authBusy)
        ErrorLine()
    }
}

// MARK: - Link (Plex PIN / Emby PIN / Jellyfin Quick Connect)

@Composable
fun LinkScreen() {
    val model = LocalModel.current
    val t = LocalTheme.current
    val kind = model.serviceKind
    val plex = kind != ServerKind.jellyfin   // Plex and Emby link an account; Jellyfin's Quick Connect is per server
    val host = model.address.replace("http://", "")
    val steps = when (kind) {
        ServerKind.plex -> listOf("On your phone or computer, open" to "plex.tv/link", "Type in this code. The moment Plex says yes, we move on." to "")
        ServerKind.emby -> listOf("On your phone or computer, open" to "emby.media/pin", "Sign in with Emby Connect and type in this code. Once Emby says yes, we move on." to "")
        else -> listOf("Open Jellyfin on your phone or computer and sign in." to "", "Head to" to "Settings › Quick Connect", "Type in this code. Once it’s approved, we move on." to "")
    }
    val status = model.authError ?: if (model.linked) (if (plex) "Linked. Looking for your server, which takes 10–15 seconds…" else "Approved. Reading your libraries…")
        else "Waiting for you to type the code…"
    val pulse by rememberInfiniteTransition(label = "pulse")
        .animateFloat(1f, 0.25f, infiniteRepeatable(tween(700, easing = EaseInOut), RepeatMode.Reverse), label = "pulse")
    OnboardingStage {
        Header(if (plex) kind.displayName else "Jellyfin · $host", if (plex) "Link your ${kind.displayName} account" else "Link with Quick Connect", null, width = 1640f)
        Row(Modifier.offset(140.dp, 430.dp).width(1640.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(30.dp)) {
                steps.forEachIndexed { i, (text, em) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                        Box(Modifier.size(56.dp).background(t.panel2, CircleShape), contentAlignment = Alignment.Center) {
                            BasicText("${i + 1}", style = t.body(28, FontWeight.Bold).copy(color = t.ink))
                        }
                        Para(buildAnnotatedString {
                            append(text + if (em.isEmpty()) "" else " ")
                            withStyle(SpanStyle(color = t.accent, fontWeight = FontWeight.Bold)) { append(em) }
                        }, t.body(36).lineSpacing(5f).copy(color = t.ink), Modifier.padding(top = 4.dp))
                    }
                }
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(14.dp)
                        .graphicsLayer { alpha = if (model.linked || model.authError != null) 1f else pulse }
                        .background(if (model.linked) hex("#7fd39a") else t.accent, CircleShape))
                    BasicText(status, style = t.body(30).copy(color = if (model.authError == null) t.mute else t.accent))
                }
            }
            Spacer(Modifier.width(160.dp))   // the Swift HStack's spacing 80 on both sides of its Spacer(minLength: 0)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val shape = RoundedCornerShape(t.radius.dp)
                for (ch in model.linkCodeChars) {
                    Box(Modifier.size(120.dp, 170.dp).background(t.panel, shape).border(2.dp, t.line, shape), contentAlignment = Alignment.Center) {
                        BasicText(ch, style = t.heading(96, FontWeight.Bold).copy(color = t.ink))
                    }
                }
            }
        }
        PillButton(if (plex) "Type a server address instead" else "Sign in with a password instead", true,
            Modifier.offset(140.dp, (1080 - 120 - 84).dp).tapSelects(model), height = 84f, padX = 44f, size = 30f)
    }
}

// MARK: - Login

@Composable
fun LoginScreen() {
    val model = LocalModel.current
    val name = model.serviceKind.displayName
    FormScreen("$name · ${model.address.replace("http://", "")}", "Sign in to $name",
        if (model.serviceKind == ServerKind.plex) "Two-factor enabled? Enter your password followed by the 6-digit code."
        else "Use the account you already use for $name on your other devices.") {
        FormRow("Username", model.focusIndex == 0, Modifier.tapSelects(model) { model.focusIndex = 0 }) {
            ValueText(model.username, "dana", model.focusIndex == 0, Modifier.weight(1f))
        }
        FormRow("Password", model.focusIndex == 1, Modifier.tapSelects(model) { model.focusIndex = 1 }) {
            ValueText("•".repeat(model.password.length), "••••••••", model.focusIndex == 1, Modifier.weight(1f))
        }
        PillButton("Sign in", model.focusIndex == 2, Modifier.padding(top = 24.dp).tapSelects(model) { model.focusIndex = 2 }, busy = model.authBusy)
        ErrorLine()
    }
}

// MARK: - Libraries

@Composable
fun LibrariesScreen() {
    val model = LocalModel.current
    val t = LocalTheme.current
    val n = model.librarySections.size
    LaunchedEffect(Unit) { model.fetchLibraries() }
    FormScreen("Connected to ${model.server?.serverName ?: ""}", "What goes in the lineup?",
        "Pick the libraries Rerun Guide may schedule. You can change this in the Settings any time.") {
        model.librarySections.forEachIndexed { i, s ->
            key(s.id) {
                val f = model.focusIndex == i
                val on = s.id in model.librarySelection
                FormRow(s.title, f, Modifier.tapSelects(model) { model.focusIndex = i }) {
                    Detail(detail(s), f, Modifier.weight(1f))
                    val ink = if (on) (if (f) t.focusBg else t.accentInk) else (if (f) t.focusInk else t.ink)
                    val shape = RoundedCornerShape(t.pill.dp)
                    // CSS content-box min-width 84 + 6/16 padding + 3 border
                    Box(Modifier.background(if (on) (if (f) t.focusInk else t.accent) else Color.Transparent, shape).border(3.dp, ink, shape)
                        .padding(vertical = 9.dp, horizontal = 19.dp).widthIn(min = 84.dp), contentAlignment = Alignment.Center) {
                        BasicText(if (on) "On" else "Off", style = t.body(24, FontWeight.Bold).copy(color = ink))
                    }
                }
            }
        }
        if (n == 0 && !model.authBusy && model.authError == null) {
            BasicText("No libraries reported, so everything on the server will be scheduled.", style = t.body(28).copy(color = t.dim))
        }
        if (model.authBusy) Spinner(t.dim, Modifier.width(64.dp).height(88.dp), 0.7f)   // tvOS lays the spinner out 64 wide whatever its scale
        PillButton("Continue", model.focusIndex == n, Modifier.padding(top = 24.dp).tapSelects(model) { model.focusIndex = n })
        ErrorLine()
    }
}

private fun detail(s: LibrarySection): String {
    val c = s.itemCount ?: return ""
    val unit = when (s.kind) { LibrarySection.Kind.movies -> "film"; LibrarySection.Kind.shows -> "show"; else -> "title" }
    return "${"%,d".format(c)} $unit${if (c == 1) "" else "s"}"
}

// MARK: - Building

@Composable
fun BuildingScreen() {
    val model = LocalModel.current
    val t = LocalTheme.current
    val done = model.buildDone
    val server = model.server?.serverName ?: ""
    val empty = done && model.channels.isEmpty()
    val failed = model.libraryError != null || empty
    val sub = model.libraryError ?: when {
        empty -> "Nothing to schedule. Titles without a runtime are skipped. If $server is still scanning, try again in a few minutes; otherwise pick other libraries."
        done -> "${model.channels.size} channels, all from $server. Rename, renumber or retire any of them under Options › Channels."
        else -> "Sorting $server by genre, decade, director and cast…"
    }
    val rows = (model.builtCount + 3) / 4
    // ponytail: more than 16 channels scrolls the board up; no manual scrolling. Eased like the Swift reveal's withAnimation.
    val scroll by animateFloatAsState(-maxOf(0, rows - 4) * 120f, tween(300, easing = EaseOut), label = "board")
    OnboardingStage {
        Header(null, if (done && !empty) "Sign-on complete" else "Configuring channels", sub, width = 1640f, subColor = if (failed) t.accent else t.mute)
        Box(Modifier.offset(140.dp, 380.dp).size(1640.dp, 464.dp).clipToBounds()) {
            Column(Modifier.fillMaxWidth().offset { IntOffset(0, scroll.dp.roundToPx()) }.wrapContentHeight(Alignment.Top, unbounded = true),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                for (row in model.channels.take(model.builtCount).chunked(4)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (ch in row) key(ch.id) { ChannelTile(ch, Modifier.weight(1f)) }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (done || model.libraryError != null) {
            // Swift animates this in only when the last reveal step finishes the board; an error or an empty result just appears.
            PillButton(if (model.libraryError != null) "Retry" else if (empty) "Pick other libraries" else "Start flipping", true,
                Modifier.offset(140.dp, (1080 - 96 - 88).dp).appear(model.libraryError == null && model.channels.isNotEmpty()).tapSelects(model))
        }
    }
}

@Composable
private fun ChannelTile(ch: Channel, modifier: Modifier) {
    val t = LocalTheme.current
    Row(modifier.appear().height(104.dp).background(t.panel, RoundedCornerShape(t.radius.dp)).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.widthIn(min = 52.dp), contentAlignment = Alignment.CenterEnd) {   // 3 digits past channel 99: never wraps
            BasicText("${ch.number}", style = t.body(34, FontWeight.Bold).copy(color = t.accent, fontFeatureSettings = "tnum"), softWrap = false, maxLines = 1)
        }
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(t.heading(ch.name), style = t.body(30, FontWeight.SemiBold).copy(color = t.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(ch.tag, style = t.body(24).copy(color = t.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// MARK: - Shared pieces (the template's isForm / welcome / link / building CSS)

/** The 1920×1080 stage with the theme's background glow: radial-gradient(1100px 700px at 20% 10%, glow, transparent 70%).
 *  Unclipped: on a phone the glow carries on into the letterbox. */
@Composable
private fun OnboardingStage(content: @Composable BoxScope.() -> Unit) {
    val t = LocalTheme.current
    Box(Modifier.size(1920.dp, 1080.dp).drawBehind {   // on RootView's t.bg
        val c = Offset(384.dp.toPx(), 108.dp.toPx())
        val r = 770.dp.toPx()
        scale(1f, 700f / 1100f, pivot = c) {
            // Fades to the glow's own color at zero alpha: CSS `transparent` interpolates premultiplied, Android's shader doesn't.
            drawCircle(Brush.radialGradient(listOf(t.glow, t.glow.copy(alpha = 0f)), center = c, radius = r), radius = r, center = c)
        }
    }, content = content)
}

/** Header column at (140, 110): brand, kicker (margin-top 20), 88px title, sub. */
@Composable
private fun Header(kicker: String?, title: String, sub: String?, width: Float = 1300f, subColor: Color? = null) {
    val t = LocalTheme.current
    Column(Modifier.offset(140.dp, 110.dp).width(width.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Brand()
        if (kicker != null) Kicker(kicker, t.accent, Modifier.padding(top = 20.dp))
        BasicText(t.heading(title), Modifier.padding(top = if (kicker == null) 20.dp else 0.dp),
            t.heading(88).copy(color = t.ink, letterSpacing = t.tracking(t.headingTracking)))
        if (sub != null) Para(sub, t.body(32).copy(color = subColor ?: t.mute))
    }
}

/** The "Sign in" template: header + a 1100-wide column of rows at (140, 450), gap 14. */
@Composable
private fun FormScreen(kicker: String, title: String, sub: String, rows: @Composable ColumnScope.() -> Unit) {
    OnboardingStage {
        Header(kicker, title, sub)
        Column(Modifier.offset(140.dp, 450.dp).width(1100.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = rows)
    }
}

@Composable
private fun Brand(size: Float = 30f, dot: Float = 14f) {
    val t = LocalTheme.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(dot.dp).background(t.accent, CircleShape))
        BasicText(t.heading("Rerun Guide"), style = t.heading(size, FontWeight.SemiBold).copy(color = t.ink))
    }
}

/** 26px 700 .14em uppercase. */
@Composable
private fun Kicker(text: String, color: Color, modifier: Modifier = Modifier) {
    val t = LocalTheme.current
    BasicText(text.uppercase(), modifier, t.body(26, FontWeight.Bold).copy(color = color, letterSpacing = t.tracking(0.14)))
}

/** Form section label: 24px 700 .14em uppercase dim, margin-top 14. */
@Composable
private fun SectionLabel(text: String) {
    val t = LocalTheme.current
    BasicText(text.uppercase(), Modifier.padding(top = 14.dp), t.body(24, FontWeight.Bold).copy(color = t.dim, letterSpacing = t.tracking(0.14)))
}

/** 88-high row: 300-wide label + content, padding 0 36, gap 24. */
@Composable
private fun FormRow(label: String, focused: Boolean, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val t = LocalTheme.current
    Row(modifier.size(1100.dp, 88.dp).background(if (focused) t.focusBg else t.panel, RoundedCornerShape(t.radius.dp)).padding(horizontal = 36.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        BasicText(label, Modifier.width(300.dp), t.body(32, FontWeight.SemiBold).copy(color = if (focused) t.focusInk else t.ink))
        content()
    }
}

/** The input's resting look: its value, or the placeholder in the row's secondary color. */
@Composable
private fun ValueText(value: String, placeholder: String, focused: Boolean, modifier: Modifier = Modifier) {
    val t = LocalTheme.current
    MiddleTruncatedText(value.ifEmpty { placeholder }, t.body(32, FontWeight.Medium).copy(
        color = if (value.isEmpty()) (if (focused) t.focusDim else t.dim) else (if (focused) t.focusInk else t.ink)), modifier)
}

/** Row detail: 30px in the row's secondary color. */
@Composable
private fun Detail(text: String, focused: Boolean, modifier: Modifier = Modifier) {
    val t = LocalTheme.current
    BasicText(text, modifier, t.body(30).copy(color = if (focused) t.focusDim else t.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Inline auth error under the rows, in the accent. */
@Composable
private fun ErrorLine() {
    val model = LocalModel.current
    val t = LocalTheme.current
    model.authError?.let { Para(it, t.body(28).copy(color = t.accent), Modifier.padding(top = 10.dp)) }
}

/** The template's pill button (isButton / ln.alt / bd.onGo): height 88, padding 0 52, 32px 700, focus colors + lift. */
@Composable
private fun PillButton(label: String, focused: Boolean, modifier: Modifier = Modifier,
                       height: Float = 88f, padX: Float = 52f, size: Float = 32f, busy: Boolean = false) {
    val t = LocalTheme.current
    val shape = RoundedCornerShape(t.pill.dp)
    val shadow = liftTextShadow(focused, t)
    Row(modifier.lift(focused, t, shape).background(fade(if (focused) t.focusBg else t.panel), shape).height(height.dp).padding(horizontal = padX.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        BasicText(t.heading(label), style = t.body(size, FontWeight.Bold).copy(color = fade(if (focused) t.focusInk else t.ink), shadow = shadow))
        if (busy) Spinner(if (focused) t.focusInk else t.ink, Modifier.size(40.dp), 0.6f)
    }
}

/** SF Symbol "checkmark", 28 pt bold (or smaller, by `modifier`'s size), drawn. */
@Composable
fun Checkmark(color: Color, modifier: Modifier = Modifier.size(29.dp, 27.dp)) = Canvas(modifier) {
    val path = Path().apply {
        moveTo(size.width * 0.1f, size.height * 0.56f)
        lineTo(size.width * 0.37f, size.height * 0.88f)
        lineTo(size.width * 0.9f, size.height * 0.1f)
    }
    drawPath(path, color, style = Stroke(3.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** One line, truncated in the middle (SwiftUI `.truncationMode(.middle)`; Compose 1.7 has no MiddleEllipsis). */
@Composable
private fun MiddleTruncatedText(text: String, style: TextStyle, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val max = constraints.maxWidth
        val shown = remember(text, style, max) {
            fun fits(s: String) = measurer.measure(s, style, softWrap = false, maxLines = 1).size.width <= max
            fun cut(keep: Int) = text.take((keep + 1) / 2) + "…" + text.takeLast(keep / 2)
            if (fits(text)) text else {
                var lo = 0; var hi = text.length - 1   // characters kept around the ellipsis
                while (lo < hi) { val mid = (lo + hi + 1) / 2; if (fits(cut(mid))) lo = mid else hi = mid - 1 }
                cut(lo)
            }
        }
        BasicText(shown, style = style, softWrap = false, maxLines = 1)
    }
}

/** The focus color change rides the lift's 180 ms ease, as under the Swift `.animation(_, value: on)`. */
@Composable
private fun fade(c: Color): Color = animateColorAsState(c, tween(180, easing = EaseInOut), label = "focus").value

/** Focus lift: scale 1.03 + shadow 0 18 44 rgba(0,0,0,.55). 180 ms ease. */
@Composable
private fun Modifier.lift(on: Boolean, t: Theme, shape: Shape): Modifier {
    val spec = tween<Float>(180, easing = EaseInOut)
    val scale by animateFloatAsState(if (on) t.focusScale else 1f, spec, label = "lift")
    val shadow by animateFloatAsState(if (on && t.focusShadow) 0.55f else 0f, spec, label = "shadow")
    return graphicsLayer { scaleX = scale; scaleY = scale }.drawBehind {
        if (shadow <= 0f) return@drawBehind
        // SwiftUI radius 22 = Gaussian sigma 22; Skia's blur radius r is sigma 0.57735·r + 0.5.
        // ponytail: setShadowLayer on shapes needs hardware-accelerated API 28+; on 26–27 there is simply no shadow.
        val paint = Paint().apply {
            asFrameworkPaint().apply {
                color = android.graphics.Color.TRANSPARENT
                setShadowLayer((22.dp.toPx() - 0.5f) / 0.57735f, 0f, 18.dp.toPx(), Color.Black.copy(alpha = shadow).toArgb())
            }
        }
        drawIntoCanvas { it.drawOutline(shape.createOutline(size, layoutDirection, this), paint) }
    }
}

/** SwiftUI's `.shadow` on a view that isn't a `compositingGroup` shadows every layer in it, so the texts inside a lifted
 *  card or pill cast the lift's shadow (0 18 44 .55) onto their own background too. Same blur conversion as `lift`. */
@Composable
private fun liftTextShadow(on: Boolean, t: Theme): Shadow {
    val a by animateFloatAsState(if (on && t.focusShadow) 0.55f else 0f, tween(180, easing = EaseInOut), label = "textShadow")
    if (a <= 0f) return Shadow.None
    return with(LocalDensity.current) { Shadow(Color.Black.copy(alpha = a), Offset(0f, 18.dp.toPx()), (22.dp.toPx() - 0.5f) / 0.57735f) }
}

/** SwiftUI `.transition(.opacity.combined(with: .offset(y: 14)))` on insertion inside the reveal's 0.3 s ease-out. */
@Composable
private fun Modifier.appear(animated: Boolean = true): Modifier {
    val p = remember { Animatable(if (animated) 0f else 1f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(300, easing = EaseOut)) }
    return graphicsLayer { alpha = p.value; translationY = (1 - p.value) * 14.dp.toPx() }
}
