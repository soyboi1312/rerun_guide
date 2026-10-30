package com.rerunguide.app

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import android.os.Build
import android.provider.Settings
import android.view.TextureView
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.rerunguide.core.Channel
import com.rerunguide.core.GuideGenerator
import com.rerunguide.core.MediaItem
import com.rerunguide.core.ProgramStatus
import com.rerunguide.core.Slot
import com.rerunguide.core.plusSeconds
import com.rerunguide.core.runCatchingNonCancel
import com.rerunguide.core.secondsSince
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.media3.common.MediaItem as ExoItem

/** The guide: preview window (top-left), info panel (top-right), time header + looping listings grid (bottom).
 *  Layout is absolute on the 1920×1080 stage, straight from the handoff's CSS (Design/handoff/README.md §1). */
@Composable
fun GuideScreen() {
    val model = LocalModel.current
    val t = LocalTheme.current
    val sw = LocalStageWidth.current
    DisposableEffect(Unit) {
        model.guideAppeared()
        onDispose { model.attractReset() }   // off the guide: wake, stop the countdown
    }
    // An overlay wakes it; closing one re-arms it.
    LaunchedEffect(Unit) { snapshotFlow { model.overlay == null }.drop(1).collect { model.attractReset() } }
    Box(
        // No background: RootView's is the same t.bg (one full-screen fill fewer per frame on a TV's GPU).
        Modifier.size(sw.dp, 1080.dp)
            .drawBehind {
                if (t.id == ThemeID.LATE) {
                    // Clipped to where the glow isn't transparent yet (0.7 of the 1000×640 radii around 380,300): the
                    // same pixels, a third of the screen's fill instead of all of it.
                    clipRect(right = 1080 * density, bottom = 748 * density) {
                        ellipticalGradient(0f to t.glow, 0.7f to t.glow.copy(alpha = 0f), end = 0.5f,
                            topLeft = Offset((380 - 1000) * density, (300 - 640) * density), box = Size(2000 * density, 1280 * density))
                    }
                }
            }
            .backgroundTap { if (model.attracting) model.attractReset() },   // the bouncing logo has nothing else to tap
    ) {
        if (model.visibleChannels.isEmpty()) {
            if (model.libraryLoading) Box(Modifier.size(sw.dp, 1080.dp), contentAlignment = Alignment.Center) { RerunLoader(delay = 1.0) }   // a quick cache read at launch doesn't show it
            else {
                Column(Modifier.size(sw.dp, 1080.dp), Arrangement.spacedBy(18.dp, Alignment.CenterVertically), Alignment.CenterHorizontally) {
                    val err = model.libraryError
                    Para(err ?: "Nothing to schedule yet", t.body(32).copy(textAlign = TextAlign.Center, color = if (err == null) t.dim else t.accent),
                        Modifier.widthIn(max = 1200.dp))
                    Txt("Open Options › Settings › Rebuild to try again", t.body(26), t.dim)
                }
            }
        } else {
            val face = BounceSaver.Face.entries.firstOrNull { it.name == model.settings.screensaver }
            // ponytail: Swift's `.transition(.opacity)` here runs without an animation transaction, so the swap is instant; so is this.
            if (model.attracting && face != null) BounceSaver(face, sw)
            else Periodic(10) { GuideContent(it) }
        }
        if (t.id == ThemeID.MIDNIGHT && model.settings.crtLines) {
            // Off TV the CRT overlay runs on past the stage into the window's margins; 600 covers them (drawn, not laid out).
            Box(Modifier.size(sw.dp, 1080.dp).graphicsLayer().crt(0.09f, 0.62f to 0.38f, bleed = if (Platform.isTV) 0f else 600f))
        }
    }
}

@Composable
private fun GuideContent(now: Instant) {
    val model = LocalModel.current
    val sw = LocalStageWidth.current
    // One 0.8 s ease drives the panel crossfade and the grid's dim; read in layers, so only they redraw while it runs.
    val attract = animateFloatAsState(if (model.attracting) 1f else 0f, tween(800, easing = EaseInOut), label = "attract")
    val showInfo by remember { derivedStateOf { attract.value < 1f } }
    val showAttract by remember { derivedStateOf { attract.value > 0f } }
    Box(Modifier.size(sw.dp, 1080.dp)) {
        val gi = GuideInfo.make(model, now) ?: return@Box
        if (showInfo) InfoPanel(gi, Modifier.graphicsLayer { alpha = 1f - attract.value })
        if (showAttract) AttractPanel(gi.ch, gi.title, Modifier.graphicsLayer { alpha = attract.value })
        Box(Modifier.matchParentSize().graphicsLayer { alpha = 1f - 0.3f * attract.value }) {
            // One Instant per window start (remember keeps the old one while they're equal), so the cells clamped to the
            // window's edges keep equal arguments and skip recomposing on every press.
            val ws = remember(gi.ws) { gi.ws }
            TimeHeader(now, ws)
            GuideGrid(now, ws, gi.key)
        }
        PreviewWindow(gi)   // last: the enlarged attract tube floats over the dimmed grid
    }
}

// MARK: - View model (port of the prototype vGuide's `gi`)

internal class GuideInfo(model: AppModel, val key: ProgramKey, pr: ChannelSlot, val now: Instant) {
    val ch: Channel = pr.channel
    val slot: Slot = pr.slot
    val st: ProgramStatus = model.status(slot, now)
    val cur: Slot = model.slotOnAir(ch, now) ?: slot
    val brk = model.status(cur, now) == ProgramStatus.`break`
    val bOn = model.settings.trailerBreaks && ch.trailerBreaks
    val trailer: MediaItem? = if (brk && bOn) model.breakTrailerCandidate(ch, cur) else null
    val next: Slot? = model.slotOnAir(ch, cur.end)
    val ws: Instant = model.windowStart(now)
    val held = model.remoteHeld
    /** `model.foregroundSerial`: back from the Home screen, the preview's stream must be fetched again. */
    val resume = model.foregroundSerial

    val live get() = st == ProgramStatus.live || st == ProgramStatus.`break`
    val title get() = slot.item.displayTitle
    val timeRange get() = Clock.range(slot.start, slot.end)
    val elapsed get() = now.secondsSince(slot.start)
    val progress: Float get() = if (live) max(0.0, min(1.0, elapsed / slot.duration)).toFloat() else 0f
    val desc get() = slot.item.overview ?: ""
    val statusText: String get() = when (st) {
        ProgramStatus.live -> "${(elapsed / 60).toInt()} min in"
        ProgramStatus.`break` -> (if (bOn) "Trailers until " else "Station break until ") + Clock.time(slot.end)
        ProgramStatus.later -> "Starts in ${ceil(-elapsed / 60).toInt()} min"
        ProgramStatus.past -> "Aired earlier"
    }
    val statusLabel: String get() = when (st) {
        ProgramStatus.live -> "ON NOW"; ProgramStatus.`break` -> "BREAK"; ProgramStatus.later -> "LATER"; ProgramStatus.past -> "ENDED"
    }
    val pvKind get() = if (brk) "BREAK" else "LIVE"
    val pvKicker get() = if (brk) (if (bOn) "Coming attractions" else "Station break") else "Now playing"
    val pvTitle: String get() = if (brk) (if (bOn) trailer?.title ?: ch.name else ch.name) else cur.item.displayTitle
    val pvSub: String get() {
        if (brk) {
            val what = if (bOn) "Trailer" + (trailer?.year?.let { ", $it" } ?: "") else "Channel ${ch.number}"
            return what + (next?.let { " · ${it.item.displayTitle} at ${Clock.time(it.start)}" } ?: "")
        }
        return if (cur.item.kind == MediaItem.Kind.movie) cur.item.year?.toString() ?: "" else "“${cur.item.title}”"
    }
    val selLabel get() = if (live) "Watch live" else "Details"
    /** Play/Pause: a program that hasn't started yet has no beginning to go back to. */
    val playLabel get() = if (st == ProgramStatus.later) "Watch now" else "Start from the beginning"
    val backLabel get() = if (held) "Let it drift" else "Options"
    /** Held: the Back button lets go. Drifting: Back exits to Home, so the D-pad's left is the way into Options. */
    val backKey get() = if (held) "chevron.backward" else "circle.grid.cross.left.filled"
    /** The same three pills' glyphs on touch screens, where they are buttons: what they do, not which key does it. */
    val selTouch get() = if (live) "play.fill" else "info.circle"
    val backTouch get() = if (held) "wind" else "line.3.horizontal"
    /** Changes when the preview picture should reload (focused channel, program or break state). */
    val previewKey get() = "${ch.id}|${cur.id}|$brk|$resume"

    companion object {
        fun make(model: AppModel, now: Instant): GuideInfo? {
            val key = model.guideKey(now) ?: return null
            val pr = model.program(key) ?: return null
            return GuideInfo(model, key, pr, now)
        }
        fun fit(n: Int, a: Float, b: Float, c: Float) = if (n <= 16) a else if (n <= 26) b else c
    }
}

/** Design handoff literals that are not theme tokens (handoff README §1). Midnight Movie cells have no live fill: its liveCell/focusFill
 *  equal the base colors, so the shared gradient degenerates to the flat cell the prototype shows. */
private val Theme.mm get() = id == ThemeID.MIDNIGHT
private val PV_TEXT = hex("#e6d8c4"); private val LIVE_CELL = hex("#28211c"); private val FOCUS_FILL = hex("#e2d2bc")
private val RING = hex("#2e2722"); private val BEVEL_LIGHT = hex("#6e3434"); private val BEVEL_DARK = hex("#050303")
private val BOX = hex("#070505"); private val SLASH = hex("#7a4040"); private val WATERMARK = hex("#d8c2bd")
private val LIVE_CHIP = hex("#d7261e"); private val BREAK_CHIP = hex("#c2560c"); private val BREAK_BADGE = hex("#ffa94d")
private val Theme.pvText get() = PV_TEXT
private val Theme.cellBg get() = if (mm) panel2 else panel
private val Theme.liveCell get() = if (mm) panel2 else LIVE_CELL
private val Theme.focusFill get() = if (mm) focusBg else FOCUS_FILL
private val Theme.ring get() = RING
internal val Theme.bevelLight get() = BEVEL_LIGHT
internal val Theme.bevelDark get() = BEVEL_DARK
private val Theme.box get() = BOX
private val Theme.slash get() = SLASH
private val Theme.watermark get() = WATERMARK
private val Theme.liveChip get() = LIVE_CHIP
private val Theme.breakChip get() = BREAK_CHIP
internal val Theme.breakBadge get() = BREAK_BADGE

// MARK: - Preview window

/** `portrait`: this many dp wide at 16:9 with the phone layout over it (the iOS handoff §1.2, PortraitGuide.kt), instead
 *  of the TV's tube on the stage. `strip`: the picture alone, the large-font list's thumbnail. */
@Composable
internal fun PreviewWindow(gi: GuideInfo, portrait: Float? = null, strip: Boolean = false) {
    val model = LocalModel.current
    val t = LocalTheme.current
    val context = LocalContext.current
    // Reduce motion (Android's "Remove animations"): no growing tube, the panels still crossfade.
    val reduceMotion = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    // ponytail: the attract tube is hard-coded (Late Show 640×480 at 80,72, ending above the time header at y 566;
    // Midnight Movie 824×464 at 80,56, ending at x 904 / y 520 before its time bar); no handoff spec for it.
    val p by animateFloatAsState(if (model.attracting) 1f else 0f, if (reduceMotion) snap() else tween(800, easing = EaseInOut), label = "tube")
    val mm = t.mm
    val w = if (mm) androidx.compose.ui.util.lerp(800f, 824f, p) else androidx.compose.ui.util.lerp(600f, 640f, p)
    val h = if (mm) androidx.compose.ui.util.lerp(450f, 464f, p) else androidx.compose.ui.util.lerp(450f, 480f, p)

    // One ExoPlayer, allocated on the first real load (the Swift AVPlayer), muted, released with the window.
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var ready by remember { mutableStateOf(false) }
    var aspect by remember { mutableFloatStateOf(0f) }
    DisposableEffect(player) {
        val pl = player ?: return@DisposableEffect onDispose {}
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { ready = isPlaying }   // AVPlayer timeControlStatus == .playing
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }
        pl.addListener(l)
        onDispose { pl.removeListener(l) }
    }
    // tvOS: only an AVPlayer that is actually playing holds off the screensaver, so a BREAK card, channel 99 or a
    // stalled load lets the TV dim and sleep. The player screen holds it the same way (WatchScreen).
    val view = LocalView.current
    DisposableEffect(ready) { view.keepScreenOn = ready; onDispose { view.keepScreenOn = false } }
    // AVPlayer pauses itself when the app leaves for the Home screen; ExoPlayer has to be told. Coming back bumps
    // foregroundSerial, which reloads the stream (the model stopped the server's session).
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player?.pause() }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    DisposableEffect(Unit) {
        onDispose {
            player?.release(); player = null
            model.scope.launch { model.server?.stopPlayback("preview") }
        }
    }
    val px = LocalDensity.current.density
    // A new key, or a new client (an address pick replaced it), cancels the previous load (Swift `load?.cancel()`).
    LaunchedEffect(gi.previewKey, model.server) {
        if (gi.ch.isScrambled) { player?.run { pause(); clearMediaItems() }; return@LaunchedEffect }   // nothing to stream
        // Built as the dwell starts, not after it: the build and the TextureView hookup overlap the wait instead of
        // delaying the first frame (AVPlayer has neither cost). Nothing is fetched until the dwell ends.
        // AVPlayer's 60 s network patience, not ExoPlayer's 8 s: a transcode can take that long to start.
        if (player == null) player = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(
            context, DefaultHttpDataSource.Factory().setConnectTimeoutMs(60_000).setReadTimeoutMs(60_000))))
            // Starts on half a second of video, not ExoPlayer's 2.5 s: AVPlayer starts as soon as it can keep up, and a
            // slow server transcode would otherwise hold the picture back for seconds. A muted preview that stalls costs nothing.
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, 500, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS).build())
            .build().also {
            // Muted the safe way: no audio track at all, so passthrough audio can't leak and audio focus is never taken.
            // AVPlayer picks HLS variants for its layer's size; ExoPlayer's default viewport is the whole display, so
            // a 1080p60 variant would be fetched and software-decoded for this tube. ponytail: sized as the largest tube
            // aspect-filled with 16:9 video (Late 853×480, MM 824×464 → the 960×540 variant AVPlayer starts on).
            val vw = if (mm) 824f else 640f; val vh = if (mm) 464f else 480f
            it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .setViewportSize((max(vw, vh * 16 / 9) * px).toInt(), (max(vh, vw * 9 / 16) * px).toInt(), false).build()
        }
        delay(1500)   // the dwell (a debounce): each load starts a server transcode
        // tvOS suspends the app at the Home screen, so this never gets further there; foregroundSerial reloads on return.
        if (model.backgrounded) return@LaunchedEffect
        ready = false; player?.run { pause(); clearMediaItems() }
        val now = Instant.now()
        if (gi.brk) return@LaunchedEffect
        val server = model.server ?: return@LaunchedEffect
        val air = model.slotOnAir(gi.ch, now) ?: return@LaunchedEffect
        val offset = min(now.secondsSince(air.start), air.item.runtime)
        val src = runCatchingNonCancel { server.playbackSource(air.item, offset, "preview") } ?: return@LaunchedEffect
        // Dropped or replaced meanwhile (a plain-http link's return): its URL carries the token to an unchecked address.
        if (!isActive || model.backgrounded || model.server !== server) return@LaunchedEffect
        val pl = player ?: return@LaunchedEffect
        val item = ExoItem.fromUri(src.url)
        if (src.startsAtOffset) pl.setMediaItem(item) else pl.setMediaItem(item, (offset * 1000).toLong())
        pl.prepare()
        pl.play()
    }

    // The picture: watch it (or its details, like the Select pill).
    val picture: @Composable (Float, Float) -> Unit = { pw, ph -> Picture(gi, pw, ph, player, ready, aspect) }
    if (portrait != null) return PhonePreview(gi, portrait, strip, picture)
    val outer = Modifier.at(80, if (mm) 56 else 72, w, h).onTouch { model.guideHint(RemoteCommand.select) }
    if (mm) MidnightTube(gi, w, h, outer, picture) else LateTube(gi, w, h, outer, picture)
}

/** The phone's preview: the Late Show tube (radius 26, ring, glow) or the Midnight Movie set (4-dp bevel), a 3-dp line
 *  along the bottom for how far into the program it is. A tap watches it (or opens its details, like the TV's Select). */
@Composable
private fun PhonePreview(gi: GuideInfo, w: Float, strip: Boolean, picture: @Composable (Float, Float) -> Unit) {
    val model = LocalModel.current
    val t = LocalTheme.current
    val mm = t.mm
    val h = Math.round(w * 9 / 16).toFloat()
    val r = if (mm) 0f else if (strip) 10f else 26f
    val b = if (mm) (if (strip) 2f else 4f) else 0f   // Midnight Movie's bevel, inside the 16:9
    val iw = w - 2 * b; val ih = h - 2 * b
    val into = gi.now.secondsSince(gi.cur.start)
    val progress = (into / gi.cur.duration).coerceIn(0.0, 1.0).toFloat()
    val sub = if (gi.brk) gi.pvSub else listOf(gi.pvSub, "${(into / 60).toInt()} min in").filter { it.isNotEmpty() }.joinToString(" · ")
    val shape = RoundedCornerShape(r.dp)
    Box(Modifier.size(w.dp, h.dp)
        .drawBehind {
            if (mm) bevel(b, t.bevelLight, t.bevelDark)
            else { dropShadow(t.accent.copy(alpha = 0.14f), 35f, 0f, r); dropShadow(Color.Black.copy(alpha = 0.55f), 25f, 22f, r) }
        }
        .then(if (mm) Modifier else Modifier.border(1.5.dp, t.ring, shape))
        .onTouch { model.guideHint(RemoteCommand.select) }
        .clearAndSetSemantics { contentDescription = "Preview. ${gi.pvKicker}, channel ${gi.ch.number} ${gi.ch.name}. ${gi.pvTitle}. $sub"; role = Role.Button }
        .padding(b.dp)) {
        Box(Modifier.size(iw.dp, ih.dp).clip(shape)) {
            picture(iw, ih)
            if (!strip) { if (mm) PhoneMidnight(gi, sub) else PhoneLate(gi, sub) }
            Box(Modifier.align(Alignment.BottomStart).size((iw * progress).dp, 3.dp).background(t.accent))
        }
    }
}

@Composable
private fun BoxScope.PhoneLate(gi: GuideInfo, sub: String) {
    val t = LocalTheme.current
    Txt(gi.pvKind, t.pBody(11, FontWeight.Bold).copy(letterSpacing = 0.1.em), t.accentInk,
        Modifier.padding(start = 14.dp, top = 13.dp).background(if (gi.brk) t.focusBg else t.accent, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp))
    Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 16.dp, end = 14.dp, bottom = 16.dp),
        Arrangement.spacedBy(12.dp), Alignment.Bottom) {
        Column(Modifier.weight(1f), Arrangement.spacedBy(4.dp)) {
            Txt("${gi.pvKicker} · ${gi.ch.number} ${gi.ch.name}".uppercase(), t.pBody(11, FontWeight.SemiBold).copy(letterSpacing = 0.12.em), t.pvText, maxLines = 1)
            Txt(gi.pvTitle, t.pHeading(GuideInfo.fit(gi.pvTitle.length, 28f, 23f, 19f)).copy(letterSpacing = (-0.02).em), t.ink, maxLines = 1)   // Swift shrinks to fit
            Txt(sub, t.pBody(13), t.pvText, maxLines = 1)
        }
        Txt(if (gi.live) "Watch" else "Details", t.pBody(14, FontWeight.Bold), t.focusInk,
            Modifier.heightIn(min = 44.dp).background(t.focusBg, CircleShape).wrapContentHeight().padding(horizontal = 16.dp))
    }
}

@Composable
private fun BoxScope.PhoneMidnight(gi: GuideInfo, sub: String) {
    val t = LocalTheme.current
    val d = LocalDensity.current.density
    Row(Modifier.padding(start = 12.dp, top = 12.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
        Txt(gi.ch.number.toString(), t.pBody(17, FontWeight.ExtraBold), t.accentInk, Modifier.background(t.accent).padding(horizontal = 8.dp, vertical = 4.dp))
        Txt(gi.ch.name.uppercase(), t.pBody(15, FontWeight.Bold).copy(letterSpacing = 0.06.em, shadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 2 * d), 0f)), t.ink, maxLines = 1)
    }
    Txt(gi.pvKind, t.pBody(13, FontWeight.ExtraBold).copy(letterSpacing = 0.12.em), Color.White,
        Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 13.dp).background(if (gi.brk) t.breakChip else t.liveChip).padding(horizontal = 9.dp, vertical = 4.dp))
    Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 14.dp, end = 12.dp, bottom = 12.dp),
        Arrangement.spacedBy(10.dp), Alignment.Bottom) {
        Column(Modifier.weight(1f), Arrangement.spacedBy(3.dp)) {
            Txt(gi.pvTitle.uppercase(), t.pHeading(GuideInfo.fit(gi.pvTitle.length, 33f, 27f, 22f)).copy(shadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 3 * d), 0f)), t.ink, maxLines = 1)
            Txt(sub.uppercase(), t.pBody(14, FontWeight.SemiBold), t.mute, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Txt("RERUN GUIDE", TextStyle(fontFamily = Fonts.barlow, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic,
                fontSize = 13.sp, letterSpacing = 0.04.em), t.watermark.copy(alpha = 0.85f))
            Txt(if (gi.live) "WATCH" else "DETAILS", t.pBody(14, FontWeight.ExtraBold).copy(letterSpacing = 0.08.em), t.accentInk,
                Modifier.heightIn(min = 44.dp).background(t.accent).wrapContentHeight().padding(horizontal = 12.dp))
        }
    }
}

/** The Late Show tube: rounded, ringed and glowing, text block bottom-left. */
@Composable
private fun LateTube(gi: GuideInfo, w: Float, h: Float, modifier: Modifier, picture: @Composable (Float, Float) -> Unit) {
    val t = LocalTheme.current
    val pvSize = GuideInfo.fit(gi.pvTitle.length, 60f, 50f, 40f)
    Box(
        modifier
            // Shadows on a static shape behind the player, not on the layer the video repaints every frame.
            .drawBehind {
                dropShadow(t.accent.copy(alpha = 0.16f), 80f, 0f, 44f)
                dropShadow(Color.Black.copy(alpha = 0.55f), 45f, 40f, 44f)
                drawRoundRect(Color.Black, cornerRadius = CornerRadius(44 * density))
            }
            .drawWithContent {
                drawContent()
                drawRoundRect(t.ring, Offset(-density, -density), Size(size.width + 2 * density, size.height + 2 * density),
                    CornerRadius(45 * density), style = Stroke(2 * density))
            }
            .clip(RoundedCornerShape(44.dp)),
    ) {
        picture(w, h)
        Txt(gi.pvKind, t.body(24, FontWeight.Bold).copy(letterSpacing = 0.1.em), t.accentInk,
            Modifier.offset(28.dp, 26.dp).background(if (gi.brk) t.focusBg else t.accent, CircleShape).padding(horizontal = 14.dp, vertical = 6.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 36.dp, end = 36.dp, bottom = 34.dp), Arrangement.spacedBy(8.dp)) {
            Txt(gi.pvKicker.uppercase(), t.body(24, FontWeight.SemiBold).copy(letterSpacing = 0.14.em), t.pvText)
            Para(gi.pvTitle, t.heading(pvSize).copy(letterSpacing = (-0.02).em, color = t.ink), Modifier.lineHeightTrim(pvSize))
            Para(gi.pvSub, t.body(26).copy(color = t.pvText))
        }
    }
}

/** The Midnight Movie set: a 6px bevel around the picture, channel + chip along the top, title centered, watermark. */
@Composable
private fun MidnightTube(gi: GuideInfo, w: Float, h: Float, modifier: Modifier, picture: @Composable (Float, Float) -> Unit) {
    val t = LocalTheme.current
    val iw = w - 12; val ih = h - 12
    val pvSize = GuideInfo.fit(gi.pvTitle.length, 84f, 64f, 50f)
    val d = LocalDensity.current.density
    Box(modifier.drawBehind { bevel(6f, t.bevelLight, t.bevelDark) }.padding(6.dp)) {
        Box(Modifier.size(iw.dp, ih.dp).clipToBounds()) {
            picture(iw, ih)
            Row(Modifier.offset(24.dp, 22.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
                Txt(gi.ch.number.toString(), t.body(34, FontWeight.ExtraBold), t.accentInk,
                    Modifier.background(t.accent).padding(horizontal = 12.dp, vertical = 6.dp).frameHeight(34f))
                Txt(gi.ch.name.uppercase(), t.body(30, FontWeight.Bold).copy(letterSpacing = 0.06.em,
                    shadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 2 * d), 0f)), t.ink)
            }
            Box(Modifier.at(0, 24, iw - 24, 38), contentAlignment = Alignment.TopEnd) {
                Txt(gi.pvKind, t.body(26, FontWeight.ExtraBold).copy(letterSpacing = 0.12.em), Color.White,
                    Modifier.background(if (gi.brk) t.breakChip else t.liveChip).padding(horizontal = 14.dp, vertical = 6.dp).frameHeight(26f))
            }
            Column(Modifier.at(48, 80, iw - 96, ih - 150), Arrangement.spacedBy(12.dp, Alignment.CenterVertically), Alignment.CenterHorizontally) {
                val center = TextStyle(textAlign = TextAlign.Center)
                Txt(gi.pvKicker.uppercase(), t.body(26, FontWeight.Bold).merge(center).copy(letterSpacing = 0.22.em), t.accent)
                Para(gi.pvTitle.uppercase(), t.heading(pvSize).merge(center)
                    .copy(color = t.ink, shadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 4 * d), 0f)), Modifier.lineHeightTrim(pvSize, 0.95f))
                Para(gi.pvSub, t.body(30).merge(center).copy(color = t.mute))
            }
            Box(Modifier.at(0, 0, iw - 24, ih - 18), contentAlignment = Alignment.BottomEnd) {
                Txt("RERUN GUIDE", TextStyle(fontFamily = Fonts.barlow, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic,
                    fontSize = 28.sp, letterSpacing = 0.04.em), t.watermark.copy(alpha = 0.85f))
            }
        }
    }
}

/** Hue gradient → backdrop art → muted live video, in that order as each becomes available. */
@Composable
private fun Picture(gi: GuideInfo, w: Float, h: Float, player: ExoPlayer?, ready: Boolean, aspect: Float) {
    val model = LocalModel.current
    val t = LocalTheme.current
    val hue = gi.ch.hue
    val c0 = channelColor(hue, 0.5, 0.1); val c1 = channelColor(hue, 0.24, 0.05); val c2 = channelColor(hue, 0.13, 0.02)
    Box(Modifier.size(w.dp, h.dp).drawBehind { ellipticalGradient(0f to c0, 0.55f to c1, 1f to c2, center = Offset(0.25f, 0.15f), end = 1f) }) {
        if (gi.ch.isScrambled) {
            ScrambleView((gi.cur.start.epochSecond / 60).toInt(), Modifier.size(w.dp, h.dp))
        } else if (!gi.brk) {
            val url = model.server?.backdropURL(gi.cur.item)
            // The scrim only over a loaded image, as Swift's sits in AsyncImage's content: drawn SrcAtop in the image's
            // own layer, so it lands only where the picture is (nowhere while it loads, or if it fails).
            if (url != null) RemoteImage(url, Modifier.size(w.dp, h.dp), ContentScale.Crop) { scrim() }
        }
        if (!gi.ch.isScrambled) PlayerLayerView(player, aspect, w, h, Modifier.alpha(if (ready) 1f else 0f))
        if (model.settings.crtLines) {
            Box(Modifier.size(w.dp, h.dp).crt(if (t.mm) 0.24f else 0.16f, if (t.mm) null else 0.55f to 0.5f))
        }
    }
}

/** Legibility over real art/video only; the bare hue gradient renders as the design mockup. */
private fun DrawScope.scrim() =
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)), startY = size.height * 0.45f, endY = size.height))

/** The video in a TextureView (clips, fades and takes the rounded corner like any Compose layer), sized to cover the
 *  window at the video's aspect (AVPlayerLayer `.resizeAspectFill`). Never focusable and never consumes a touch, so the
 *  remote stays with MainActivity and taps reach the preview around it. */
@Composable
private fun PlayerLayerView(player: ExoPlayer?, aspect: Float, w: Float, h: Float, modifier: Modifier) {
    val (vw, vh) = when {
        aspect <= 0f -> w to h
        aspect > w / h -> h * aspect to h
        else -> w to w / aspect
    }
    Box(modifier.size(w.dp, h.dp).clipToBounds().drawWithContent { drawContent(); scrim() }, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { TextureView(it).apply { isFocusable = false; isClickable = false } },
            modifier = Modifier.requiredSize(vw.dp, vh.dp),
            update = { tv ->
                if (tv.tag !== player) { (tv.tag as? ExoPlayer)?.clearVideoTextureView(tv); player?.setVideoTextureView(tv); tv.tag = player }
            },
            onRelease = { tv -> (tv.tag as? ExoPlayer)?.clearVideoTextureView(tv); tv.tag = null },
        )
    }
}

/** Scanlines (2px on / 2px off) plus an optional vignette (start fraction, alpha). `bleed`: points drawn past each edge.
 *  The lines are rendered once per size and settings into an alpha mask (drawn black: an alpha-only image takes the
 *  paint's color), so a drifting frame draws one bitmap instead of ~270 rects. The vignette stays a live gradient: it is
 *  one draw either way, and through a mask it would lose HWUI's dither and land ±1 off. */
internal fun Modifier.crt(lines: Float, vignette: Pair<Float, Float>?, bleed: Float = 0f) = drawWithCache {
    val b = bleed * density
    val tl = Offset(-b, -b); val box = Size(size.width + 2 * b, size.height + 2 * b)
    val w = box.width.roundToInt(); val h = box.height.roundToInt()
    if (w <= 0 || h <= 0) return@drawWithCache onDrawBehind {}
    val mask = ImageBitmap(w, h, ImageBitmapConfig.Alpha8)
    CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(mask), box) {
        val c = Color.Black.copy(alpha = lines)
        val step = 4 * density; val on = 2 * density
        var y = 0f
        while (y < box.height) { drawRect(c, Offset(0f, y), Size(box.width, on)); y += step }
    }
    onDrawBehind {
        drawImage(mask, tl)
        if (vignette != null) {
            ellipticalGradient(vignette.first to Color.Black.copy(alpha = 0f), 1f to Color.Black.copy(alpha = vignette.second), end = 0.71f, topLeft = tl, box = box)
        }
    }
}

/** A CSS-style bevel border: light on top/left, dark on bottom/right, mitred corners. */
private fun DrawScope.bevel(width: Float, light: Color, dark: Color) {
    val i = width * density; val r = size.width; val b = size.height
    drawPath(Path().apply { moveTo(0f, 0f); lineTo(r, 0f); lineTo(r - i, i); lineTo(i, b - i); lineTo(0f, b); close() }, light)
    drawPath(Path().apply { moveTo(r, 0f); lineTo(r, b); lineTo(0f, b); lineTo(i, b - i); lineTo(r - i, i); close() }, dark)
}

/** SwiftUI's EllipticalGradient: the unit square stretched over `box` (default: the whole scope), radii = `end` × its
 *  size, centered at `center` (a unit point). Past the last stop the last color carries on, as in SwiftUI. */
private fun DrawScope.ellipticalGradient(vararg stops: Pair<Float, Color>, center: Offset = Offset(0.5f, 0.5f), end: Float,
                                         topLeft: Offset = Offset.Zero, box: Size = size) {
    val c = Offset(topLeft.x + center.x * box.width, topLeft.y + center.y * box.height)
    val ry = end * box.height
    if (ry <= 0f || box.width <= 0f) return
    val sx = box.width / box.height   // a circle of radius ry, stretched sideways into the ellipse
    scale(sx, 1f, pivot = c) {
        drawRect(Brush.radialGradient(*stops, center = c, radius = ry), Offset(c.x - (c.x - topLeft.x) / sx, topLeft.y), Size(box.width / sx, box.height))
    }
}

private val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

/** SwiftUI `.shadow(color:radius:y:)` of a rounded rect filling the scope. SwiftUI's radius is the blur's sigma; Skia's
 *  blur radius r is sigma 0.57735·r + 0.5. */
private fun DrawScope.dropShadow(color: Color, radius: Float, y: Float, corner: Float) {
    if (color.alpha <= 0f) return
    val blur = max(0.01f, (radius * density - 0.5f) / 0.57735f); val r = corner * density
    if (Build.VERSION.SDK_INT < 28) {   // a shadow layer on a shape needs hardware-accelerated API 28+: a blurred mask drawn in software
        val (mask, at) = blurredMask("rr$r", size.width.toInt(), size.height.toInt(), blur) { c, p -> c.drawRoundRect(0f, 0f, size.width, size.height, r, r, p) }
        drawImage(mask, at + Offset(0f, y * density), alpha = color.alpha, colorFilter = ColorFilter.tint(color.copy(alpha = 1f)))
        return
    }
    shadowPaint.color = android.graphics.Color.TRANSPARENT
    shadowPaint.setShadowLayer(blur, 0f, y * density, color.toArgb())
    drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, shadowPaint) }
}

private val masks = android.util.LruCache<String, Pair<ImageBitmap, Offset>>(24)

/** The alpha of `draw` (in a w×h px box), blurred by `blur` px in software, and where its top-left goes; cached by
 *  `key` (what `draw` draws) and size.
 *  The fallback for blurs the hardware renderer can't do on older Android (shadow layers before 28, RenderEffect before 31). */
internal fun blurredMask(key: String, w: Int, h: Int, blur: Float, draw: (android.graphics.Canvas, android.graphics.Paint) -> Unit): Pair<ImageBitmap, Offset> {
    val k = "$key|$w|$h|$blur"
    masks.get(k)?.let { return it }
    val src = android.graphics.Bitmap.createBitmap(max(1, w), max(1, h), android.graphics.Bitmap.Config.ARGB_8888)
    draw(android.graphics.Canvas(src), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG))
    val at = IntArray(2)
    val a = src.extractAlpha(android.graphics.Paint().apply { maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL) }, at)
    src.recycle()
    return (a.asImageBitmap() to Offset(at[0].toFloat(), at[1].toFloat())).also { masks.put(k, it) }
}

// MARK: - Info panel

@Composable
private fun InfoPanel(gi: GuideInfo, modifier: Modifier) {
    if (LocalTheme.current.mm) InfoMidnight(gi, modifier) else InfoLate(gi, modifier)
}

@Composable
private fun InfoLate(gi: GuideInfo, modifier: Modifier) {
    val model = LocalModel.current
    val t = LocalTheme.current
    /** On a phone wider than 16:9 the panel takes the extra width (the stage widens; see Stage). */
    val extra = LocalStageWidth.current - 1920
    val titleSize = GuideInfo.fit(gi.title.length, 92f, 76f, 60f)
    Column(Modifier.at(744, 64, 1096 + extra, 470).then(modifier)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(t.accent, CircleShape))
                val f = model.guideFilter
                Txt(if (f == GuideFilter.all) "Rerun Guide" else "Rerun Guide · ${if (f == GuideFilter.movies) "Movies" else "TV Shows"}",
                    t.heading(30, FontWeight.SemiBold), t.ink)
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                // The profile chip opens the menu (it leads with Switch profile); a bigger target, same layout.
                Row(Modifier.touchOutset(12f) { model.openOptions() }, Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).background(colorHex(model.profile.colorHex), CircleShape), contentAlignment = Alignment.Center) {
                        Txt(model.profile.initial, t.body(20, FontWeight.Bold), t.accentInk)
                    }
                    Txt(model.profile.name, t.body(26), t.mute)
                }
                EverySecond { d ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Txt(Clock.time(d), t.heading(30, FontWeight.Medium), t.ink)
                        Txt(Clock.ap(d), t.heading(30, FontWeight.Medium), t.dim)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        ChannelLine(gi.ch)
        Para(gi.title, t.heading(titleSize).copy(letterSpacing = (-0.025).em, color = t.ink), Modifier.padding(top = 12.dp).lineHeightTrim(titleSize))
        Txt(gi.slot.item.infoLine, t.body(32), t.mute, Modifier.padding(top = 12.dp))
        Row(Modifier.padding(top = 20.dp), Arrangement.spacedBy(20.dp), Alignment.CenterVertically) {
            Box(Modifier.size(300.dp, 6.dp).background(t.line, CircleShape)) {
                Box(Modifier.size((300 * gi.progress).dp, 6.dp).background(t.accent, CircleShape))
            }
            Txt(gi.timeRange, t.body(26, FontWeight.SemiBold), t.ink)
            Txt(gi.statusText, t.body(26), t.dim)
        }
        Para(gi.desc, t.body(29).lineSpacing(6f).copy(color = t.mute), Modifier.padding(top = 16.dp).widthIn(max = (1020 + extra).dp), maxLines = 2)
        // A legend of remote buttons, not focusable controls: the grid is the only thing focus moves through.
        Row(Modifier.padding(top = 22.dp), Arrangement.spacedBy(30.dp)) {
            Hint("button.programmable", gi.selTouch, gi.selLabel, RemoteCommand.select)
            Hint("playpause.fill", "backward.end.fill", gi.playLabel, RemoteCommand.play)
            Hint(gi.backKey, gi.backTouch, gi.backLabel, RemoteCommand.back)
        }
    }
}

/** The boxed Midnight Movie panel: channel line + profile chip, title, status badge, square progress bar, boxed-key hints. */
@Composable
private fun InfoMidnight(gi: GuideInfo, modifier: Modifier) {
    val model = LocalModel.current
    val t = LocalTheme.current
    val extra = LocalStageWidth.current - 1920
    val titleSize = GuideInfo.fit(gi.title.length, 70f, 58f, 48f)
    val badge = when (gi.st) { ProgramStatus.live -> t.accent; ProgramStatus.`break` -> t.breakBadge; else -> t.dim }
    Column(
        Modifier.at(904, 56, 936 + extra, 450).then(modifier).bleedBackground(t.box, top = 56f, right = 80f).border(3.dp, t.line)
            .padding(start = 39.dp, top = 31.dp, end = 39.dp, bottom = 29.dp),   // 3px border + 28/36/26
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ChannelLine(gi.ch)
            Spacer(Modifier.weight(1f))
            Row(Modifier.touchOutset(12f) { model.openOptions() }, Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {   // as in Late Show
                Box(Modifier.size(14.dp).background(colorHex(model.profile.colorHex)))
                Txt(model.profile.name.uppercase(), t.body(28, FontWeight.SemiBold).copy(letterSpacing = 0.08.em), t.dim)
            }
        }
        Para(gi.title, t.heading(titleSize, FontWeight.Bold).copy(color = t.ink), Modifier.padding(top = 14.dp).lineHeightTrim(titleSize))
        Txt(gi.slot.item.infoLine, t.body(32, FontWeight.SemiBold), t.accent, Modifier.padding(top = 10.dp))
        Row(Modifier.padding(top = 20.dp), Arrangement.spacedBy(18.dp), Alignment.CenterVertically) {
            val s = t.body(30, FontWeight.SemiBold)
            Txt(gi.timeRange, s, t.ink)
            Txt(gi.statusLabel, t.body(24, FontWeight.ExtraBold).copy(letterSpacing = 0.1.em), t.accentInk,
                Modifier.background(badge).padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 3.dp))
            Txt(gi.statusText, s, t.dim)
        }
        Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(10.dp).background(t.panel2)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(gi.progress).background(t.accent))
        }
        Para(gi.desc, t.body(29).lineSpacing(3f).copy(color = t.mute), Modifier.padding(top = 18.dp), maxLines = 2)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Hint("button.programmable", gi.selTouch, gi.selLabel, RemoteCommand.select)
            Hint("playpause.fill", "backward.end.fill", gi.playLabel, RemoteCommand.play)
            Hint(gi.backKey, gi.backTouch, gi.backLabel, RemoteCommand.back)
        }
    }
}

@Composable
private fun Hint(symbol: String, touch: String, label: String, cmd: RemoteCommand) {
    val model = LocalModel.current
    KeyHint(symbol, touch, label) { model.guideHint(cmd) }
}

/** A remote key cap (the button's glyph in a box) and what it does here. Glyphs, not names. On touch screens the cap
 *  shows what the button does (`touch`) and the pill is the button (`action`). The guide's info panel and the watch
 *  screen's break card. */
@Composable
fun KeyHint(symbol: String, touch: String, label: String, action: () -> Unit) {
    val t = LocalTheme.current
    val mm = t.mm
    val pad = if (Platform.isTV) 0.dp else 8.dp   // a bigger target, same look
    Row(Modifier.onTouch(action).padding(top = pad, bottom = pad, end = pad),
        Arrangement.spacedBy(if (mm) 10.dp else 12.dp), Alignment.CenterVertically) {
        Box(
            Modifier.size(46.dp, if (mm) 36.dp else 32.dp).drawBehind {
                drawRoundRect(if (mm) t.dim else t.line, cornerRadius = CornerRadius((if (mm) 0 else 6) * density), style = Stroke(2 * density))
            },
            contentAlignment = Alignment.Center,
        ) { Glyph(if (Platform.isTV) symbol else touch, if (mm) 24f else 20f, if (mm) t.ink else t.dim) }
        Txt(label, t.body(26, if (mm) FontWeight.SemiBold else FontWeight.Medium), if (mm) t.dim else t.mute)
    }
}

/** The hint caps' symbols, drawn at about an SF Symbol's size for font size `s` (bold). On TV the Google TV remote's keys:
 *  OK (the ring and its center button), Play/Pause, Back, and the D-pad's left; on touch screens what the pill does. */
@Composable
private fun Glyph(name: String, s: Float, color: Color) {
    Canvas(Modifier.size((s * 1.5f).dp, s.dp)) {
        val u = size.height; val ox = (size.width - u) / 2
        fun p(x: Float, y: Float) = Offset(ox + x * u, y * u)
        fun line(w: Float) = Stroke(w * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun path(close: Boolean, vararg xy: Float) = Path().apply {
            val q = (xy.indices step 2).map { p(xy[it], xy[it + 1]) }
            moveTo(q[0].x, q[0].y); for (i in 1 until q.size) lineTo(q[i].x, q[i].y); if (close) close()
        }
        when (name) {
            // The TV glyphs are measured off Apple TV screenshots (SF Symbols, bold, 20 and 24 pt).
            "button.programmable" -> { drawCircle(color, 0.45f * u, p(.5f, .5f), style = Stroke(0.12f * u)); drawCircle(color, 0.31f * u, p(.5f, .5f)) }
            "playpause.fill" -> {
                val tri = path(true, -.24f, .12f, -.24f, .88f, .43f, .5f)   // filled, then stroked: rounded corners
                drawPath(tri, color); drawPath(tri, color, style = line(.06f))
                val bar = Size(.29f * u, .82f * u); val r = CornerRadius(.05f * u)
                drawRoundRect(color, p(.58f, .09f), bar, r); drawRoundRect(color, p(1f, .09f), bar, r)
            }
            "chevron.backward" -> drawPath(path(false, .61f, .16f, .25f, .51f, .61f, .86f), color, style = line(.16f))
            "circle.grid.cross.left.filled" -> {
                val r = 0.175f * u; val ring = Stroke(0.1f * u)
                drawCircle(color, r, p(.55f, .1f), style = ring); drawCircle(color, r, p(.9f, .48f), style = ring)
                drawCircle(color, r, p(.55f, .875f), style = ring); drawCircle(color, 0.225f * u, p(.2f, .5f))
            }
            "play.fill" -> drawPath(path(true, .24f, .1f, .24f, .9f, .9f, .5f), color)
            "info.circle" -> {
                drawCircle(color, 0.44f * u, p(.5f, .5f), style = Stroke(0.09f * u)); drawCircle(color, 0.07f * u, p(.5f, .29f))
                drawLine(color, p(.5f, .46f), p(.5f, .74f), 0.12f * u, StrokeCap.Round)
            }
            "backward.end.fill" -> { drawRect(color, p(.1f, .14f), Size(.13f * u, .72f * u)); drawPath(path(true, .9f, .14f, .9f, .86f, .26f, .5f), color) }
            "wind" -> {
                val st = line(.1f)
                drawPath(Path().apply { moveTo(p(.08f, .32f).x, p(.08f, .32f).y); lineTo(p(.6f, .32f).x, p(.6f, .32f).y)
                    cubicTo(p(.82f, .32f).x, p(.82f, .32f).y, p(.82f, .06f).x, p(.82f, .06f).y, p(.62f, .1f).x, p(.62f, .1f).y) }, color, style = st)
                drawLine(color, p(.08f, .52f), p(.88f, .52f), 0.1f * u, StrokeCap.Round)
                drawPath(Path().apply { moveTo(p(.08f, .72f).x, p(.08f, .72f).y); lineTo(p(.52f, .72f).x, p(.52f, .72f).y)
                    cubicTo(p(.74f, .72f).x, p(.74f, .72f).y, p(.74f, .96f).x, p(.74f, .96f).y, p(.56f, .92f).x, p(.56f, .92f).y) }, color, style = st)
            }
            "line.3.horizontal" -> for (y in floatArrayOf(.2f, .5f, .8f)) drawLine(color, p(.08f, y), p(.92f, y), 0.12f * u, StrokeCap.Round)
        }
    }
}

/** The number pill, name and tag of a channel: the info panel's channel line, reused by the attract panel. */
@Composable
fun ChannelLine(ch: Channel) {
    val t = LocalTheme.current
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (t.mm) {
            val s = t.body(28, FontWeight.SemiBold).copy(letterSpacing = 0.08.em)
            Txt(ch.number.toString(), t.body(28, FontWeight.ExtraBold).copy(letterSpacing = 0.08.em), t.accent)
            Txt(ch.name.uppercase(), s, t.ink)
            Txt("/", s, t.slash)
            Txt(ch.tag.uppercase(), s, t.dim)
        } else {
            Txt(ch.number.toString(), t.body(28, FontWeight.Bold).copy(fontFeatureSettings = "tnum"), t.accentInk,
                Modifier.background(t.accent, CircleShape).padding(horizontal = 14.dp, vertical = 3.dp))
            Txt(ch.name, t.body(28, FontWeight.SemiBold), t.ink)
            Txt("· " + ch.tag, t.body(28), t.mute)
        }
    }
}

/** Re-renders its content once every `seconds`, on wall-clock boundaries, with the boundary's time (the Swift
 *  `TimelineView(.periodic(from: .timelineOrigin, by:))`). Only this scope recomposes on a tick. */
@Composable
internal fun Periodic(seconds: Int, content: @Composable (Instant) -> Unit) {
    val ms = seconds * 1000L
    val d by produceState(Instant.ofEpochMilli(System.currentTimeMillis() / ms * ms)) {
        while (true) {
            val now = System.currentTimeMillis(); val next = (now / ms + 1) * ms
            delay(next - now)
            value = Instant.ofEpochMilli(next)
        }
    }
    content(d)
}

/** Re-renders its content once a second (the clock). */
@Composable
internal fun EverySecond(content: @Composable (Instant) -> Unit) = Periodic(1, content)

// MARK: - Time header

@Composable
private fun TimeHeader(now: Instant, ws: Instant) {
    val labels = (0..2).map { ws.plusSeconds(it * GuideGenerator.blockLength) }
    if (LocalTheme.current.mm) TimeBar(labels) else LateHeader(now, ws, labels)
}

/** Midnight Movie: a solid bar, date + clock in the channel column, one labelled cell per half hour; no now marker. */
@Composable
private fun TimeBar(labels: List<Instant>) {
    val t = LocalTheme.current
    val extra = LocalStageWidth.current - 1920
    Row(Modifier.at(80, 528, 1760 + extra, 60), Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(300.dp, 60.dp).bleedBackground(t.accent, left = 80f), contentAlignment = Alignment.Center) {
            EverySecond { d ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(Clock.date(d), t.body(26, FontWeight.ExtraBold).copy(letterSpacing = 0.06.em), t.accentInk)
                    Txt(Clock.time(d), t.body(34, FontWeight.ExtraBold), t.accentInk)
                    Txt(Clock.ap(d), t.body(24, FontWeight.ExtraBold), t.accentInk)
                }
            }
        }
        Row(Modifier.size((1456 + extra).dp, 60.dp).bleedBackground(t.box, right = 80f)) {
            for (d in labels) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxHeight().padding(start = 26.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                        Txt(Clock.time(d), t.body(32, FontWeight.Bold), t.accent)
                        Txt(Clock.ap(d), t.body(24, FontWeight.Bold), t.accent)
                    }
                    Box(Modifier.width(4.dp).fillMaxHeight().background(t.bg))
                }
            }
        }
    }
}

@Composable
private fun LateHeader(now: Instant, ws: Instant, labels: List<Instant>) {
    val model = LocalModel.current
    val t = LocalTheme.current
    val track = 1420 + LocalStageWidth.current - 1920
    val nowPct = (now.secondsSince(ws) / AppModel.windowSpan).toFloat()
    val live = model.windowOffset == 0
    Box(Modifier.at(420, 566, track, 40)) {
        // Keyed by time (Swift `ForEach(labels, id: \.self)`): a label keeps its fade as the window shifts, and a new
        // one appears at its final opacity.
        labels.forEachIndexed { i, d -> key(d) {
            // A column label the now pill would sit on fades out instead of colliding: from ~6 minutes before its time
            // until the pill (85 wide at most) clears the label's right edge (117 past its time), ~10 minutes after.
            val x = i / 3f * track
            val nearNow = live && nowPct * track - x in -95f..<170f
            val op = animateFloatAsState(if (nearNow) 0f else 1f, tween(400, easing = EaseInOut), label = "label")
            Row(Modifier.offset((x + 6).dp, 4.dp).graphicsLayer { alpha = op.value }, Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
                Txt(Clock.time(d), t.body(26, FontWeight.SemiBold), t.dim)
                Txt(Clock.ap(d), t.body(26, FontWeight.SemiBold), t.dim)
            }
        } }
        if (live) {
            Box(Modifier.offset((nowPct * track - 100).dp, 0.dp).width(200.dp), contentAlignment = Alignment.TopCenter) {
                EverySecond { d ->
                    Txt(Clock.time(d), t.body(24, FontWeight.Bold), t.accentInk,
                        Modifier.background(t.accent, CircleShape).padding(horizontal = 14.dp, vertical = 5.dp))
                }
            }
        }
    }
    if (live) {
        Box(Modifier.at(420 + nowPct * track - 1, 612, 2, 428).background(Brush.verticalGradient(listOf(t.accent, t.accent.copy(alpha = 0f)))))
    }
}

// MARK: - Grid

/** The rows in view and where they sit: the window starts one row above the row `raw` lands in. */
private data class RowWindow(val first: Int, val rowBase: Int, val top: Float) {
    companion object {
        fun of(raw: Float, half: Float, pitch: Float): RowWindow {
            // raw = base + m with m in 0 ..< half.
            val base = floor(raw / half) * half
            val first = ((raw - base) / pitch).toInt() - 1
            return RowWindow(first, Math.round(base / pitch), first * pitch + base)
        }
    }
}

/** A remote press (Swift `Animation.glide`): the row slide and the cells' focus fade share it (ease-out sine, 0.2 s), so on
 *  Up/Down the highlight holds its place while the rows pass under it, instead of lighting the next row below and riding it
 *  back up. */
private fun <T> glide() = tween<T>(200, easing = CubicBezierEasing(0.39f, 0.575f, 0.565f, 1f))

/** Drifting, the frame clock moves `raw` (only the rows' layer redraws; the rows recompose when the window steps a row).
 *  Held, `raw` eases to the remote's position (`glide`) while the window is already the target's (Swift: only `.offset(y: -raw)` animates). */
@Composable
private fun GuideGrid(now: Instant, ws: Instant, gf: ProgramKey) {
    val model = LocalModel.current
    val t = LocalTheme.current
    val list = model.visibleChannels; val n = list.size
    val pitch = model.rowPitch; val height = model.gridHeight; val half = model.half
    val count = ceil(height / pitch).toInt() + 2   // one row of slack above and below the viewport
    val held = model.remoteHeld
    val width = 1760 + LocalStageWidth.current - 1920
    // Held: the remote's position. Paused (auto-scroll off): the parked drift offset. Drifting: null, the frame clock's.
    val target: Float? = when {
        held -> model.guideScroll.pos
        !model.settings.autoScroll -> model.guideDriftOffset()
        else -> null
    }
    val raw = remember { mutableFloatStateOf(target ?: model.guideDriftOffset()) }   // what is on screen
    LaunchedEffect(target, held) {
        when {
            // Held or paused since the last frame (this effect restarts for the new mode): `raw` stays where the drift
            // left it, so the held glide starts from what is on screen (the offset now returns the parked value).
            target == null -> while (true) withFrameNanos { if (!model.remoteHeld && model.settings.autoScroll) raw.floatValue = model.guideDriftOffset() }
            held -> animate(raw.floatValue, target, animationSpec = glide()) { v, _ ->
                raw.floatValue = v
            }
            else -> raw.floatValue = target
        }
    }
    val win by remember(target, half, pitch) { derivedStateOf { RowWindow.of(target ?: raw.floatValue, half, pitch) } }
    val still = if (!held) target else null   // paused: drawn at the target straight away
    val mask = !t.mm
    val px = LocalDensity.current.density
    Box(
        Modifier.at(80, if (t.mm) 596 else 616, width, height)
            // Taps only inside the viewport (clip): the masked slack rows sit over the header and hint pills.
            .graphicsLayer { clip = true }
            .drawWithContent {
                drawContent()
                // The rows fade into the background: the background color faded in over them, which is the CSS mask's
                // picture (the bottom fifth has nothing behind it but t.bg) without an offscreen layer every frame.
                if (mask) drawRect(Brush.verticalGradient(0.78f to t.bg.copy(alpha = 0f), 1f to t.bg))
            },
    ) {
        if (n == 0) return@Box
        val w = win
        // translateY(top − raw): the window's first row sits at raw track offset `top`, so the picture is the same as if
        // the whole tiled track were translated by −raw. `top` is fixed for the rows shown, which keeps the slide
        // continuous when raw crosses a multiple of the track height. In whole device pixels: the 22 pt/s drift then
        // repaints only when the rows have moved a pixel (about 22 frames a second at 1080p instead of every vsync, and
        // no text re-rasterized at fractional offsets), which a Google TV's GPU needs; the glide looks the same.
        val ty by remember(w, still, px) { derivedStateOf { Math.round((w.top - (still ?: raw.floatValue)) * px).toFloat() } }
        Column(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).graphicsLayer { translationY = ty }) {
            // Row identity is the raw row (`row + rowBase`), also where the window crosses the lineup's wrap point: the
            // same channel stays on the same row as the window slides, so focus animations don't restart.
            for (row in w.first until w.first + count) key(row + w.rowBase) {
                val ch = list[((row % n) + n) % n]
                GuideRow(ch, row + w.rowBase, ws, now, gf, width, Modifier.zIndex(if (gf.channelID == ch.id) 1f else 0f))
            }
        }
    }
}

@Composable
private fun GuideRow(ch: Channel, row: Int, ws: Instant, now: Instant, gf: ProgramKey, width: Float, modifier: Modifier) {
    val model = LocalModel.current
    val t = LocalTheme.current
    val span = AppModel.windowSpan; val we = remember(ws) { ws.plusSeconds(span) }; val mm = t.mm
    // Late Show: 340 column, cells inset 4 each side. Midnight Movie: 300 column, 4 gap, each cell 4 short on the right.
    val pitch = model.rowPitch; val cellH = if (mm) pitch - 4 else 88f
    val colW = if (mm) 300f else 340f; val gap = if (mm) 4f else 0f; val trackW = width - colW - gap
    val inset = if (mm) 0f else 4f; val shrink = if (mm) 4f else 8f
    val slots = model.slots(ch, ws, we)
    Row(modifier.size(width.dp, pitch.dp), Arrangement.spacedBy(gap.dp)) {
        ChannelCell(ch, Modifier.size(colW.dp, cellH.dp))
        Box(Modifier.size(trackW.dp, cellH.dp)) {
            for (s in slots) key(s.id) {
                val l = maxOf(s.start, ws); val r = minOf(s.end, we)
                val left = (l.secondsSince(ws) / span).toFloat() * trackW
                val cw = (r.secondsSince(l) / span).toFloat() * trackW
                val k = ProgramKey(ch.id, s.start)
                val focused = gf == k
                GuideCell(s, l, r, (s.start < ws), (s.end > we), now, focused,
                    Modifier.zIndex(if (focused) 3f else 1f).at(left + inset, 0, max(0f, cw - shrink), cellH).onTouch { model.tapGuide(row, k) })
            }
        }
    }
}

@Composable
private fun ChannelCell(ch: Channel, modifier: Modifier) {
    val t = LocalTheme.current
    if (t.mm) {
        Row(modifier.background(t.panel).padding(horizontal = 20.dp), Arrangement.spacedBy(18.dp), Alignment.CenterVertically) {
            Txt(ch.number.toString(), t.body(46, FontWeight.ExtraBold).copy(textAlign = TextAlign.End), t.accent, Modifier.widthIn(min = 60.dp))
            Txt(ch.name.uppercase(), t.heading(30, FontWeight.Bold).copy(letterSpacing = 0.05.em), t.ink, maxLines = 2)
        }
    } else {
        Row(modifier, Arrangement.spacedBy(18.dp), Alignment.CenterVertically) {
            Txt(ch.number.toString(), t.body(26, FontWeight.SemiBold).copy(fontFeatureSettings = "tnum", textAlign = TextAlign.End), t.accent,
                Modifier.width(48.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(ch.name, t.heading(32, FontWeight.SemiBold), t.ink, maxLines = 1)
                Txt(ch.tag, t.body(24), t.dim, maxLines = 1)
            }
        }
    }
}

@Composable
private fun GuideCell(slot: Slot, l: Instant, r: Instant, contL: Boolean, contR: Boolean, now: Instant, focused: Boolean, modifier: Modifier) {
    val t = LocalTheme.current
    val item = slot.item
    val st = GuideGenerator.status(slot, now); val live = st == ProgramStatus.live || st == ProgramStatus.`break`
    val span = r.secondsSince(l)
    val fill = if (live && span > 0) max(0.0, min(1.0, now.secondsSince(l) / span)).toFloat() else 0f
    val mm = t.mm
    // Everything the focus changes eases together, in step with the row slide (`glide`; Swift `.animation(.glide, value: focused)`).
    // Read only while drawing (layer, draw, text color), so the ease repaints the cell without recomposing it.
    val fa = animateFloatAsState(if (focused) 1f else 0f, glide(), label = "focus")
    val ink = { lerp(t.ink, t.focusInk, fa.value) }
    val sc = { lerp(t.dim, if (mm) t.focusInk else t.focusDim, fa.value) }   // the prototype's focused MM sub is the ink color
    // The clipped-edge triangle: Late Show 12×18 in the sub color, Midnight Movie 16×24 in the title color.
    val arrow = if (mm) ink else sc
    val aw = if (mm) 16f else 12f; val ah = if (mm) 24f else 18f
    val sidePad = if (mm) 50f else 44f
    Column(
        modifier
            .graphicsLayer { val scale = if (mm) 1f else 1f + 0.04f * fa.value; scaleX = scale; scaleY = scale }
            .drawBehind { dropShadow(Color.Black.copy(alpha = if (t.focusShadow) 0.6f * fa.value else 0f), 22f, 18f, t.cellRadius) }
            .clip(RoundedCornerShape(t.cellRadius.dp))
            .drawBehind {
                val f = fa.value
                val base = lerp(if (live) t.liveCell else t.cellBg, t.focusBg, f)
                val fillColor = lerp(if (mm) t.liveCell else t.line, t.focusFill, f)   // Midnight Movie cells are flat
                if (live) {
                    val x = size.width * fill
                    drawRect(fillColor, size = Size(x, size.height))
                    drawRect(base, Offset(x, 0f), Size(size.width - x, size.height))
                } else drawRect(base)
                val w = aw * density; val h2 = ah * density / 2; val cy = size.height / 2; val pad = 16 * density
                if (contL) drawPath(Path().apply { moveTo(pad + w, cy - h2); lineTo(pad, cy); lineTo(pad + w, cy + h2); close() }, arrow())
                if (contR) {
                    val x = size.width - pad
                    drawPath(Path().apply { moveTo(x - w, cy - h2); lineTo(x, cy); lineTo(x - w, cy + h2); close() }, arrow())
                }
            }
            .padding(start = (if (contL) sidePad else 24f).dp, end = (if (contR) sidePad else 24f).dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        BasicText(item.displayTitle, style = t.body(if (mm) 34 else 30, if (mm) FontWeight.Bold else FontWeight.SemiBold),
            overflow = TextOverflow.Ellipsis, maxLines = 1, color = ink)
        BasicText(guideCellSub(item, mm), style = t.body(if (mm) 26 else 24, FontWeight.Medium), overflow = TextOverflow.Ellipsis, maxLines = 1, color = sc)
    }
}

/** Late Show: "1981 · 115 min" for a movie, the episode title for an episode. Midnight Movie: "MOVIE · 1981 · PG", "“Episode”". */
private fun guideCellSub(item: MediaItem, mm: Boolean): String = when {
    item.kind == MediaItem.Kind.movie && mm -> listOfNotNull("MOVIE", item.year?.toString(), item.contentRating).joinToString(" · ")
    item.kind == MediaItem.Kind.movie -> listOfNotNull(item.year?.toString(), "${(item.runtime / 60).toInt()} min").joinToString(" · ")
    else -> if (mm) "“${item.title}”" else item.title
}

// MARK: - Helpers

/** `Text(s).font(style).foregroundStyle(color)`, truncating with an ellipsis like SwiftUI. */
@Composable
private fun Txt(s: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    BasicText(s, modifier, style.copy(color = color), overflow = TextOverflow.Ellipsis, maxLines = maxLines)

/** The Swift `.padding(.vertical, t.lineHeightTrim(size, mult:))` on heading type: the face's natural lines (1.2 em for
 *  both heading faces, hhea 930/-270 and 1000/-200) with (1.2·size − size·mult)/2 cut from the outer edges only, so a
 *  wrapped title keeps its natural line spacing, as in SwiftUI. (A Compose lineHeight below the face's height doesn't
 *  shrink the line at all.) */
internal fun Modifier.lineHeightTrim(size: Float, mult: Float = 1f): Modifier = layout { m, c ->
    val trim = ((1.2f - mult) * size / 2).dp.roundToPx()
    val p = m.measure(c.offset(vertical = 2 * trim))
    layout(p.width, max(0, p.height - 2 * trim)) { p.place(0, -trim) }
}

/** SwiftUI `.frame(height: h)` on a line of text: exactly `h` tall, the natural line centered in it (overflowing it). */
private fun Modifier.frameHeight(h: Float): Modifier = layout { m, c ->
    val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    val hp = h.dp.roundToPx()
    layout(p.width, hp) { p.place(0, (hp - p.height) / 2) }
}

/** SwiftUI `.background(color)` on tvOS: a color (a ShapeStyle, not a view) runs on into the screen's safe-area margins
 *  (60 top and bottom, 80 each side) on every edge where the view reaches them. Drawn, not laid out; TV only. */
private fun Modifier.bleedBackground(color: Color, left: Float = 0f, top: Float = 0f, right: Float = 0f): Modifier = drawBehind {
    val k = if (Platform.isTV) density else 0f
    drawRect(color, Offset(-left * k, -top * k), Size(size.width + (left + right) * k, size.height + top * k))
}

/** The Swift `.padding(d).onTouch { }.padding(-d)`: a target `d` bigger on every side, same layout. Nothing on TV. */
private fun Modifier.touchOutset(d: Float, action: () -> Unit): Modifier =
    if (Platform.isTV) this
    else layout { m, c ->
        val px = d.dp.roundToPx()
        val p = m.measure(c.offset(2 * px, 2 * px))
        layout(p.width - 2 * px, p.height - 2 * px) { p.place(-px, -px) }
    }.onTouch(action).padding(d.dp)

