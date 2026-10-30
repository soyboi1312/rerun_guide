package com.rerunguide.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.rerunguide.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random
import androidx.media3.common.MediaItem as ExoItem

/** Now playing: full-screen ExoPlayer, break trailers / station card, channel banner, static flash.
 *  The clock is the source of truth (prototype `wInfo`): the player loads the current program at the clock's offset
 *  and is reloaded when the (channel, slot, tune, break) key changes; banner/focus/pause changes never touch it. */
@Composable
fun WatchScreen() {
    val model = LocalModel.current
    val loaded = remember { mutableStateOf<Loaded?>(null) }
    /** The station card on screen (tune-in / break), cleared 1.5 s after it was set. */
    var ident by remember { mutableStateOf<Ident?>(null) }
    val scope = rememberCoroutineScope()
    /** The break's hand-off (the spot over or failed, a trailer failed): a new key, or leaving, cancels it and its requests. */
    var handOff by remember { mutableStateOf<Job?>(null) }
    // TimelineView(.periodic(from: .timelineOrigin, by: 1)): ticks on whole seconds; `onChange(of: now)` → watchTick.
    val now by produceState(wholeSecond()) {
        while (true) {
            delay(1000 - System.currentTimeMillis() % 1000)
            val n = wholeSecond()
            if (n != value) { value = n; model.watchTick(n) }
        }
    }
    val info = model.watchInfo(now)
    val key = info?.let { LoadKey(it.channel.id, it.slot, model.tunes, it.isBreak) }
    val current = loaded.value?.takeIf { it.key == key }

    fun showIdent(kicker: String, sub: String, sting: StationSting.Kind) {
        if (!model.settings.stationIdents) return
        ident = Ident(kicker, sub, model.identSerial)
        StationSting.shared.play(sting)
    }

    Box(Modifier.size(1920.dp, 1080.dp).background(Color.Black).backgroundTap { model.watchTap() }) {
        if (info != null) {
            stage(info, current, now, loaded, ident) { k, tries -> handOff = scope.launch { load(model, loaded, k, skipSpot = true, tries = tries) } }
        }
    }
    LaunchedEffect(key) { handOff?.cancel(); load(model, loaded, key) }
    LaunchedEffect(ident) {
        if (ident == null) return@LaunchedEffect
        delay(1500)
        ident = null   // fades out over 0.4 s (the AnimatedContent's exit)
    }
    LaunchedEffect(model.identSerial) {   // initial too: tune() from the guide bumps it before this screen exists
        val i = info ?: return@LaunchedEffect
        if (model.rewind != null) return@LaunchedEffect   // a start-over from the guide rewinds instead
        showIdent("YOU’RE WATCHING", if (i.isBreak) "Back at ${Clock.time(i.slot.end)}" else i.slot.item.displayTitle, StationSting.Kind.tune)
    }
    var oldKey by remember { mutableStateOf(key) }
    LaunchedEffect(key) {   // the program rolled into its break under the same tune
        if (loaded.value?.key != key) model.spotChanged(null)   // a channel change mid-spot: the spot is gone now
        val old = oldKey
        oldKey = key
        val new = key
        if (old == null || new == null || info == null || old == new || !new.isBreak || old.isBreak || new.tune != old.tune ||
            !(model.settings.trailerBreaks && info.channel.trailerBreaks) || info.channel.isScrambled) return@LaunchedEffect
        showIdent("WE’LL BE RIGHT BACK", "${info.channel.name} · back at ${Clock.time(new.slot.end)}", StationSting.Kind.brk)
    }
    LaunchedEffect(loaded.value) {   // the spot on screen or gone: the remote can skip it, TalkBack says it's an ad
        val l = loaded.value
        // Ended or skipped: off the air now (station / title card), not frozen on its last frame while the next one loads.
        if (l?.spot == true) model.spotChanged {
            if (loaded.value?.key != l.key) return@spotChanged   // a stale end after a channel change must not clear the new one
            loaded.value = Loaded(l.key, null, false, 0.0, null, false)
            handOff = scope.launch { load(model, loaded, l.key, skipSpot = true) }
        }
        else model.spotChanged(null)
    }
    DisposableEffect(Unit) {
        onDispose {
            model.cancelTimer("banner"); model.spotChanged(null); loaded.value = null
            model.scope.launch { model.server?.stopPlayback("watch") }
        }
    }
}

private fun wholeSecond(): Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

@Composable
private fun stage(info: WatchInfo, current: Loaded?, now: Instant, loaded: MutableState<Loaded?>, ident: Ident?, handOff: (LoadKey, TrailerTries) -> Unit) {
    val model = LocalModel.current
    val t = LocalTheme.current
    val ch = info.channel
    val item = info.slot.item
    val trailerOn = current?.trailer != null || (current == null && model.settings.trailerBreaks && ch.trailerBreaks)
    val tv = item.kind == MediaItem.Kind.episode && !info.isBreak
    val station = info.isBreak && current != null && current.url == null
    val next = info.next
    val tr = current?.trailer ?: (if (info.isBreak && trailerOn) model.breakTrailerCandidate(ch, info.slot) else null)
    val fx = if (tv) 240f else 0f
    val fw = if (tv) 1440f else 1920f
    /** What the player last said about its load: first frame shown, stalled (the loader's cue). */
    var status by remember { mutableStateOf(PlayerStatus()) }

    if (ch.isScrambled) {
        ScrambleView((info.slot.start.epochSecond / 60).toInt(), Modifier.at(0, 0, 1920, 1080), audio = true)
    } else {
        Picture(ch.hue, model.server?.backdropURL(tr ?: item), Modifier.at(fx, 0, fw, 1080))
        PlayerView(current, model.playback?.paused ?: false, Modifier.at(0, 0, 1920, 1080), onFail = { err, started, k, u ->
            // Same key, url null: no reload; the title card / station card returns and `sync` stops calling play().
            // Only for what is loaded now: a failure of the last item can land after `loaded` moved on, before `sync`.
            val l = loaded.value?.takeIf { it.key == k && it.url == u }
            // A program with a subtitle that never showed a first frame: once more without (the retry asks for off, so it
            // can't come back here). Once the picture has started, or with no subtitle to blame, it's a plain failure.
            if (l != null && l.url != null && l.spot) {   // the trailers take the break
                model.spotChanged(null); handOff(l.key, TrailerTries())
            } else if (l != null && l.url != null && l.trailer != null) {
                // A trailer that failed or never started: quietly the next one, or the station card. Never a toast for a trailer.
                loaded.value = Loaded(l.key, null, false, 0.0, null, false)
                handOff(l.key, if (started) l.tries.restarted() else l.tries)
            } else if (l != null && l.url != null && !started && l.subtitleRisk && l.trailer == null) {
                loaded.value = Loaded(l.key, null, false, 0.0, null, false)
                model.dropSubtitles(l.key.slot.item.id)
            } else if (l != null && l.url != null) {
                val msg = "Couldn’t play this stream: $err"
                loaded.value = Loaded(l.key, null, false, 0.0, null, false, error = msg)
                model.toast(msg)
                // The player never says whether the server was unreachable: look for it anyway (at most once a minute),
                // and reload there if it moved, as `serverFailed` does for the app's own requests.
                model.scope.launch { if (model.recoverPlexAddress()) { model.tunes += 1; model.foregroundSerial += 1 } }
            }
        }, onEnd = {
            model.skipSpot()   // the spot is over (skippable by then): as a skip, the trailers (or the program) take the rest
        }, onStatus = { status = it })
        // The design mockup's stand-in for the picture: only while no stream is attached, never over live video.
        if (current?.url == null && (!info.isBreak || trailerOn)) {
            Box(Modifier.at(fx + 80, 0, fw - 160, 1080), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Txt((if (info.isBreak) "Trailer" else "Playing from ${model.server?.serverName ?: ""}").uppercase(),
                        t.body(24, FontWeight.SemiBold).copy(color = Color.White.copy(alpha = 0.78f), letterSpacing = 0.2.em))
                    Para(if (info.isBreak) "${tr?.title ?: ""} (${tr?.year?.toString() ?: ""})"
                        else item.displayTitle + (if (item.kind == MediaItem.Kind.movie) " (${item.year?.toString() ?: ""})" else ": “${item.title}”"),
                        t.heading(56, FontWeight.SemiBold).lineSpacing(4f).copy(color = Color.White, textAlign = TextAlign.Center),
                        Modifier.widthIn(max = 1200.dp))
                    val e = current?.error
                    if (e != null) Para(e, t.body(28).copy(color = t.accent, textAlign = TextAlign.Center), Modifier.widthIn(max = 1200.dp))
                }
            }
        }
        if (station) {
            Column(Modifier.fillMaxSize().background(t.bg).graphicsLayer { if (model.portrait) { scaleX = 1.6f; scaleY = 1.6f } },
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Txt("${ch.number}", t.heading(220).copy(color = t.accent), Modifier.lineBox(220f))   // CSS line-height: 1
                Txt(t.heading(ch.name), t.heading(80).copy(color = t.ink, letterSpacing = t.headingTracking.em), Modifier.lineBox(80f))
                // What just ended and what's next, so a channel between programs doesn't look broken. Portrait draws the card
                // small, so it keeps the number, name and what's next (Now playing's buttons do the hints).
                Column(Modifier.padding(top = 16.dp), Arrangement.spacedBy(8.dp), Alignment.CenterHorizontally) {
                    if (!model.portrait) Para(item.displayTitle + (if (item.kind == MediaItem.Kind.movie) "" else ": “${item.title}”")
                        + (if (info.live) " ended at ${Clock.time(info.slot.start.plusSeconds(item.runtime))}" else " just ended"),
                        t.body(36).copy(color = t.ink, textAlign = TextAlign.Center), Modifier.widthIn(max = 1400.dp))
                    Txt(next?.let { "Next: ${it.item.displayTitle} at ${Clock.time(it.start)}" } ?: "More after this",
                        t.body(36).copy(color = t.mute, textAlign = TextAlign.Center), if (model.portrait) Modifier.widthIn(max = 1150.dp) else Modifier)
                }
                if (!model.portrait) Row(Modifier.padding(top = 24.dp), Arrangement.spacedBy(48.dp), Alignment.CenterVertically) {
                    if (next != null) KeyHint("button.programmable", "play.fill", "Watch ${next.item.displayTitle} now") { model.startNextFromBreak() }
                    KeyHint("playpause.fill", "backward.end.fill", "Watch ${item.displayTitle} again") { model.restartFromBreak() }
                }
            }
        }
        if (info.isBreak && current?.trailer != null && next != null && !model.pip) {   // trailers only: the spot draws its own UI in that corner
            Box(Modifier.fillMaxSize().padding(end = 72.dp, top = 64.dp), contentAlignment = Alignment.TopEnd) {
                // Portrait draws the stage a fifth of its size: twice as big there, and narrow enough to fit the picture so doubled.
                Column((if (model.portrait) Modifier.widthIn(max = 924.dp) else Modifier)
                    .graphicsLayer { if (model.portrait) { scaleX = 2f; scaleY = 2f; transformOrigin = TransformOrigin(1f, 0f) } }
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(t.radius.dp)).padding(vertical = 18.dp, horizontal = 26.dp),
                    horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt("Coming attractions".uppercase(), t.body(24, FontWeight.Bold).copy(color = t.accent, letterSpacing = 0.16.em))
                    Txt("Back at ${Clock.time(next.start)} with ${next.item.displayTitle}", t.body(30).copy(color = Color.White, textAlign = if (model.portrait) TextAlign.End else TextAlign.Start))
                }
            }
        }
        val started = SpotOnAir.started
        // Portrait: Now playing's status chip says Ad and its first button skips (PortraitWatch.kt).
        if (current?.spot == true && started != null && !model.pip && !model.portrait) SpotChips(started, now)
        // A stream attached with no first frame yet (the title card covers the fetch before it), or one that stalled.
        // A reload of the program that was on (a skip, Start over, a track change) is buffering, not tuning in. Never while
        // paused (the Paused chip says it), nor under the station ident.
        val s = if (status.key == current?.key && status.url == current?.url) status else PlayerStatus()
        val tuning = current?.url != null && !s.started
        if (ident == null && model.playback?.paused != true && (tuning || s.stalled)) {
            val same = status.key?.channelID == current?.key?.channelID && status.key?.slot == current?.key?.slot
            Box(Modifier.at(960 - 300, 400 - 250, 600, 500), contentAlignment = Alignment.Center) {   // centred at 960, 400: clear of the banner
                RerunLoader(width = 320f, captions = listOf(if (tuning && !same) "Tuning in…" else "Buffering…"), delay = 1.0)
            }
        }
    }
    // A picture-in-picture window (phones, tablets) shows the picture alone: no chips, rewind, banner or ident card. The
    // static stays: it only follows a flip, and leaving its composition would replay it on the way out of the window.
    if (!model.pip) model.rewind?.let { r -> key(r.started) { RewindEffect(r) } }
    if (model.playback?.paused == true && !model.pip && !model.portrait) {   // portrait: the status chip says Paused
        Row(Modifier.padding(start = 80.dp, top = 72.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(t.radius.dp))
            .padding(vertical = 18.dp, horizontal = 28.dp), horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { repeat(2) { Box(Modifier.size(10.dp, 36.dp).background(Color.White)) } }
            Txt("Paused", t.body(32, FontWeight.SemiBold).copy(color = Color.White))
        }
    }
    // .transition(.opacity.combined(with: .offset(y: 14))): the fade's layer is the whole stage, so the Late Show shadow
    // and the Midnight bleed fade with the panel instead of being clipped to it.
    val shown = animateFloatAsState(if (model.bannerVisible) 1f else 0f, tween(if (t.id == ThemeID.MIDNIGHT) 200 else 250, easing = EaseOut), label = "banner")
    val live = BannerData(info, model, t, now, trailerOn)
    // SwiftUI fades the removed banner out as it last looked; hideBanner clears the focus at the same time.
    val last = remember { arrayOfNulls<BannerData>(1) }
    if (model.bannerVisible) last[0] = live
    if (!model.pip && !model.portrait && (model.bannerVisible || shown.value > 0f)) {   // portrait: Now playing's panel
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown.value; translationY = (1 - shown.value) * 14.dp.toPx() }
            .padding(start = 72.dp, end = 72.dp, bottom = 56.dp), contentAlignment = Alignment.BottomCenter) {
            Banner(last[0] ?: live)
        }
    }
    val context = LocalContext.current
    val reduceMotion = remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    if (!model.pip) AnimatedContent(ident, Modifier.fillMaxSize(), contentKey = { it != null }, label = "ident", transitionSpec = {
        val enter = fadeIn(tween(250, easing = EaseOut)) + (if (reduceMotion) EnterTransition.None else scaleIn(tween(250, easing = EaseOut), initialScale = 0.96f))
        (enter togetherWith fadeOut(tween(400, easing = EaseOut))).using(null)
    }) { id ->
        if (id != null) StationIdentCard(ch.number, ch.name, ch.tag, id.kicker, id.sub)
    }
    if (model.flips > 0) key(model.flips) { StaticFlash() }
}

/** Fetch the stream for `key`: the program at the clock's offset, or for a break the first trailer the server has
 *  (deterministic candidate, then up to 3 other movies on the channel); none → station card (url null). A trailer
 *  that fails carries on here with its `tries`: the ones already tried are skipped, and the budget ends them. */
private suspend fun load(model: AppModel, loaded: MutableState<Loaded?>, key: LoadKey?, skipSpot: Boolean = false, tries: TrailerTries = TrailerTries()) {
    if (key == null) return
    val ch = model.channel(key.channelID) ?: return
    var result = Loaded(key, null, false, 0.0, null, false)
    if (ch.isScrambled) { model.playbackTracks = emptyList(); loaded.value = result; return }   // channel 99 draws itself
    val server = model.server ?: run {   // dropped: no address of a plain-http link checked out on this network
        loaded.value = result.copy(error = "Could not connect to the server.")
        return
    }
    val spot = if (!skipSpot && (key.isBreak || model.spotOverride)) model.dueSpot(ch, key.slot) else null
    if (spot != null) {
        model.playbackTracks = emptyList()
        loaded.value = Loaded(key, spot, false, 0.0, null, false, spot = true)
        return
    }
    if (key.isBreak) {
        model.playbackTracks = emptyList()
        if (model.settings.trailerBreaks && ch.trailerBreaks) {
            val first = model.breakTrailerCandidate(ch, key.slot)
            val others = ch.items.filter { it.kind == MediaItem.Kind.movie && it.id != key.slot.item.id && it.id != first?.id }.take(3)
            val candidates = listOfNotNull(first) + others
            var t = tries
            while (true) {
                val m = t.next(candidates) ?: break
                t = t.adding(m)
                try {
                    val src = server.trailer(m, "watch")
                    if (src != null) { result = Loaded(key, src.url, false, 0.0, m, true, tries = t); break }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { model.serverFailed(e) }   // a sign-out or a server to look for, then the others: no toast for a trailer
                if (!currentCoroutineContext().isActive || model.server == null) return
            }
        }
    } else {
        val offset = max(0.0, min(model.watchInfo(Instant.now())?.position ?: 0.0, key.slot.item.runtime))
        // Another item: the banner's Audio/Subtitles must not build its pick from the last one's stream indices meanwhile.
        if (loaded.value?.key?.slot?.item?.id != key.slot.item.id) model.playbackTracks = emptyList()
        val tracks = runCatchingNonCancel { server.tracks(key.slot.item) } ?: emptyList()
        if (!currentCoroutineContext().isActive) return
        model.playbackTracks = tracks
        val sel = model.trackSelection(tracks, key.slot.item.id)
        try {
            val src = server.playbackSource(key.slot.item, offset, "watch", sel)
            // The demo's fixed sample is shorter than any slot, so it loops instead of seeking.
            result = Loaded(key, src.url, src.startsAtOffset, offset, null, server.kind == ServerKind.demo,
                subtitleInPlaylist = src.subtitleInPlaylist, tracks = sel,
                subtitleRisk = sel.subtitle is TrackSelection.Subtitle.track,
                subtitle = (sel.subtitle as? TrackSelection.Subtitle.track)?.let { s -> tracks.firstOrNull { it.id == s.id } })
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { result = result.copy(error = model.serverFailed(e)) }
    }
    if (!currentCoroutineContext().isActive) return
    if (skipSpot && loaded.value?.key != key) return   // the hand-off finished after the viewer had already moved on
    loaded.value = result
    val e = result.error
    if (e != null && model.server != null) model.toast(e)   // a 401 already toasted on its way to Welcome
}

private data class LoadKey(val channelID: String, val slot: Slot, val tune: Int, val isBreak: Boolean)
private data class PlayerStatus(val key: LoadKey? = null, val url: String? = null, val started: Boolean = false, val stalled: Boolean = false)
private data class Ident(val kicker: String, val sub: String, val n: Int)
private data class Loaded(
    val key: LoadKey, val url: String?, val startsAtOffset: Boolean, val offset: Double, val trailer: MediaItem?, val loop: Boolean,
    /** What was asked of the server, so the player can finish the subtitle choice (see `WatchPlayerUIView.selectLegible`). */
    val subtitleInPlaylist: Boolean = false, val tracks: TrackSelection = TrackSelection(),
    /** A subtitle track was picked: the one thing a failure before the first frame is blamed on. Not Plex's auto: the
     *  fallback's off would make PlexClient change the account-wide subtitle choice. */
    val subtitleRisk: Boolean = false,
    val subtitle: MediaTrack? = null,
    val error: String? = null,
    /** The bundled Soyboi spot: plays once, then `onEnd` loads what the key really calls for. */
    val spot: Boolean = false,
    /** In a break, the trailers tried up to this one: its failure carries on from them. */
    val tries: TrailerTries = TrailerTries(),
)

// MARK: - Player

/** One ExoPlayer behind a SurfaceView. Trailers and the demo sample loop (REPEAT_MODE_ONE, the AVPlayerLooper);
 *  programs seek to the offset unless the server already started the stream there. */
@Composable
private fun PlayerView(loaded: Loaded?, paused: Boolean, modifier: Modifier, onFail: (String, Boolean, LoadKey?, String?) -> Unit, onEnd: () -> Unit,
                       onStatus: (PlayerStatus) -> Unit) {
    val model = LocalModel.current
    // Another app took the audio for good (Alexa, a music app): ExoPlayer has stopped, so the model pauses too, as
    // Play/Pause does. Otherwise the banner says live over a frozen picture and the next `sync` would take the audio back.
    AndroidView(factory = { WatchPlayerUIView(it).apply { onFocusLoss = { if (model.playback?.paused == false) model.togglePause() }; tunes = { model.tunes } } },
        modifier = modifier, onRelease = { it.stop() },
        update = { it.onFail = onFail; it.onEnd = onEnd; it.onStatus = onStatus; it.sync(loaded, paused) })
}

@OptIn(markerClass = [UnstableApi::class])
private class WatchPlayerUIView(context: Context) : ViewGroup(context) {
    /** URLRequest's 60 s timeout, not ExoPlayer's 8 s: a server starting a burned-in-subtitle transcode at an offset
     *  (Emby, after Home and back) takes longer than that for the first segment, and AVPlayer waits for it. */
    private var player: ExoPlayer? = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(
        DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory().setConnectTimeoutMs(60_000).setReadTimeoutMs(60_000))))
        // Plays once half a second is buffered, not 2.5 s (as the guide's preview): AVPlayer starts as soon as it can keep
        // up. Against an Emby server in testing it reached READY ~0.6 s sooner on average, with no rebuffering in 35 s.
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, 500, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS).build())
        // Holds audio focus while it plays, so Alexa or a music app started over it pauses or ducks the program instead
        // of playing on top (Amazon's test 2a.18; Android TV expects it too). The guide's muted preview needs none.
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .build()
    /** A SurfaceView, not a TextureView: frames go straight to the compositor (HWC overlay on a TV) instead of through
     *  the app's GPU pass every frame, and HDR/tunneled output stay possible. It punches a hole in the window, so the
     *  shutter, captions and every Compose layer drawn after it (banner, ident, static) sit on top of the video. */
    private val surface = SurfaceView(context)
    /** Black over the old frame from a reload until the new stream's first one (AVPlayerLayer shows nothing in between).
     *  Only while a stream is attached: with none, the Picture backdrop and title card show through, as in Swift. */
    private val shutter = View(context).apply { setBackgroundColor(android.graphics.Color.BLACK); visibility = GONE }
    /** The subtitle text AVPlayerLayer draws on its own; a bare SurfaceView has no subtitle renderer. */
    private val captions = TextView(context).apply { setTextColor(android.graphics.Color.WHITE); gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
    private var aspect = 0f
    /** A stream is attached (see `sync`). Without one the surface is laid out off the view, not hidden: INVISIBLE would
     *  destroy it, and every channel flip would pay for a new one. */
    private var attached = false
    private var key: LoadKey? = null
    private var url: String? = null
    /** The spot's item while it plays; its end (and only its end) calls `onEnd`. */
    private var spotItem: ExoItem? = null
    /** The load whose in-playlist subtitle is still to be picked, once the first frame shows and the player knows the text tracks. */
    private var legible: Loaded? = null
    /** The current item has rendered its first frame. */
    private var started = false
    /** It has played since: only then is buffering a stall (a slow tune's first frame can render before playback starts). */
    private var played = false
    /** Between the activity's ON_STOP and ON_START: the player was stopped and `sync` must not restart it. */
    private var stopped = false
    /** `model.tunes` at the last ON_STOP: no load from before it is prepared again, whether on screen then or still on its
     *  way (its URL may carry the token, and a plain-http Plex link's return holds `loaded` until its address checks
     *  out). The return's reload bumps tunes. */
    private var stoppedTune = -1
    /** The model's tune count (PlayerView sets it). */
    var tunes: () -> Int = { 0 }
    private var owner: LifecycleOwner? = null
    /** A failure's text, whether the first frame had shown, and the key and url it was loaded for. */
    var onFail: ((String, Boolean, LoadKey?, String?) -> Unit)? = null
    var onEnd: (() -> Unit)? = null
    /** The first frame showed, or the stream stalled or got going again. */
    var onStatus: ((PlayerStatus) -> Unit)? = null
    private fun report() = onStatus?.invoke(PlayerStatus(key, url, started, played && player?.playbackState == Player.STATE_BUFFERING))
    /** The audio focus is lost for good (a transient loss only pauses ExoPlayer until it comes back). */
    var onFocusLoss: (() -> Unit)? = null
    /** No first frame by then counts as a failure: 20 s after a program with a subtitle risk loads (a burn-in that never
     *  starts), `TrailerTries.startTimeout` after a trailer does (the next one takes over). */
    private val watchdog = Runnable { onFail?.invoke("The stream didn’t start.", false, key, url) }
    /** When `sync` last reloaded (elapsedRealtime): a first frame rendered before it is the last item's, queued on its way here. */
    private var reloadedAt = 0L
    /** The item `sync` last set: a first frame counts only when its event belongs to it (a stale one in the instant between
     *  reloadedAt and the playback thread's stop() would otherwise pass the time check). */
    private var loadedItem: ExoItem? = null
    private var loadSerial = 0

    private val firstFrame = object : AnalyticsListener {
        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            val window = if (eventTime.timeline.isEmpty) null else eventTime.timeline.getWindow(eventTime.windowIndex, androidx.media3.common.Timeline.Window())
            if (renderTimeMs < reloadedAt || window?.mediaItem != loadedItem) return
            shutter.visibility = View.GONE; removeCallbacks(watchdog)
            if (!started) { started = true; player?.let { selectLegible(it.currentTracks) }; report() }
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) { onFail?.invoke(error.avText(), started, key, url) }
        // tvOS: only an AVPlayer that is actually playing holds off the screensaver (not a paused, stalled or failed one).
        override fun onIsPlayingChanged(isPlaying: Boolean) { keepScreenOn = isPlaying; if (isPlaying) played = true }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) onFocusLoss?.invoke()
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (started) report()   // a stall, or playing again
            val s = spotItem ?: return
            if (playbackState == Player.STATE_ENDED && player?.currentMediaItem == s) { spotItem = null; onEnd?.invoke() }
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            // UNKNOWN (between items) keeps the last aspect: the shutter covers the picture then, and the surface isn't resized twice per flip.
            if (videoSize.width == 0 || videoSize.height == 0) return
            aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            requestLayout()
        }
        override fun onTracksChanged(tracks: Tracks) { selectLegible(tracks) }
        override fun onCues(cueGroup: CueGroup) {
            // ponytail: text cues only, bottom-centred in the player's default style; PGS/positioned cues need media3-ui's SubtitleView.
            val sb = SpannableStringBuilder()
            cueGroup.cues.mapNotNull { it.text }.forEachIndexed { i, s -> if (i > 0) sb.append('\n'); sb.append(s) }
            if (sb.isNotEmpty()) sb.setSpan(BackgroundColorSpan(android.graphics.Color.BLACK), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            captions.text = sb
        }
    }

    /** Off to the Home screen: the server's stream is stopped (AppModel.enteredBackground), so stop the player too and
     *  forget what it had; back in front, `returnedToForeground` bumps `tunes` and the next `sync` loads afresh. */
    private val lifecycle = LifecycleEventObserver { _, e ->
        when (e) {
            Lifecycle.Event.ON_STOP -> {
                if (key != null) onStatus?.invoke(PlayerStatus(key))   // the picture is gone: "Buffering…" over the black until the reload
                stopped = true; stoppedTune = tunes(); key = null; url = null; spotItem = null; legible = null; started = false; played = false; removeCallbacks(watchdog)
                player?.stop(); player?.clearMediaItems(); shutter.visibility = View.VISIBLE; captions.text = null
            }
            Lifecycle.Event.ON_START -> stopped = false
            else -> {}
        }
    }

    init {
        isFocusable = false; descendantFocusability = FOCUS_BLOCK_DESCENDANTS   // the remote's keys stay with MainActivity
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(surface); addView(shutter); addView(captions)
        player?.setVideoSurfaceView(surface)
        player?.addListener(listener)
        player?.addAnalyticsListener(firstFrame)
    }

    /** Taps on the picture reach WatchScreen (the banner), never this view. */
    override fun dispatchTouchEvent(ev: MotionEvent) = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        owner = findViewTreeLifecycleOwner()?.also { it.lifecycle.addObserver(lifecycle) }
    }
    override fun onDetachedFromWindow() {
        owner?.lifecycle?.removeObserver(lifecycle); owner = null
        super.onDetachedFromWindow()
    }

    /** resizeAspect: the picture (and its subtitles) fitted into the view, centred; the rest is the view's background. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        var vw = w
        var vh = h
        if (aspect > 0 && h > 0) { if (w.toFloat() / h > aspect) vw = (h * aspect).roundToInt() else vh = (w / aspect).roundToInt() }
        val textSize = vh * 0.0533f   // ExoPlayer's default caption size, a fraction of the picture height
        if (captions.textSize != textSize) captions.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
        captions.setPadding(vw / 10, 0, vw / 10, (vh * 0.08f).roundToInt())
        val ws = MeasureSpec.makeMeasureSpec(vw, MeasureSpec.EXACTLY)
        val hs = MeasureSpec.makeMeasureSpec(vh, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(ws, hs)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val x = (r - l - c.measuredWidth) / 2 + (if (c === surface && !attached) r - l else 0)   // off to the right: clipped, offscreen
            val y = (b - t - c.measuredHeight) / 2
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
        }
    }

    fun sync(l: Loaded?, paused: Boolean) {
        val player = player ?: return
        if (stopped || (l != null && l.key.tune <= stoppedTune)) return
        if (l?.key != key || l?.url != url) {   // same key, new url: the spot handing over, or a failed stream cleared
            key = l?.key; url = l?.url; spotItem = null; legible = null; started = false; played = false; removeCallbacks(watchdog)
            reloadedAt = android.os.SystemClock.elapsedRealtime(); loadedItem = null
            // stop(), not just clearMediaItems(): it releases the decoder. A kept one is reused for the next item, and on a
            // smaller picture (taller → wider) the Google TV emulator's decoder showed the new frames at the top of the old-size
            // buffer with a band of the last program under them (crop ignored). AVPlayer starts every item afresh too.
            player.pause(); player.stop(); player.clearMediaItems(); player.repeatMode = Player.REPEAT_MODE_OFF
            shutter.visibility = VISIBLE; captions.text = null
            val u = l?.url
            if (l != null && u != null) {
                // Its own media id per load: MediaItem equality is by content, and the same URL reloads under a new key
                // (direct play, the demo sample, the spot), which the first-frame check above must tell apart.
                val item = ExoItem.Builder().setUri(u).setMediaId("load-${++loadSerial}").build()
                loadedItem = item
                selectLegible(l)
                if (l.loop) {
                    player.repeatMode = Player.REPEAT_MODE_ONE; player.setMediaItem(item)
                    if (l.trailer != null) postDelayed(watchdog, (TrailerTries.startTimeout * 1000).toLong())
                }
                else {
                    player.setMediaItem(item, if (!l.startsAtOffset && l.offset > 0) (l.offset * 1000).toLong() else 0L)
                    if (l.spot) spotItem = item
                    else if (l.subtitleRisk) postDelayed(watchdog, 20_000)
                }
                player.prepare()
            }
        }
        // Black letterbox/pillarbox bars once a stream is attached; the Picture backdrop shows through until then.
        val attached = l?.url != null
        if (attached != this.attached) { this.attached = attached; requestLayout() }
        if (!attached) shutter.visibility = GONE
        setBackgroundColor(if (attached) android.graphics.Color.BLACK else android.graphics.Color.TRANSPARENT)
        if (!attached) return
        if (paused) player.pause() else if (!player.playWhenReady) player.play()
        // ponytail: no drift correction after a stall; the picture may lag the banner's clock. Add a periodic resync if it shows.
    }

    /** Jellyfin/Emby carry a chosen subtitle as an HLS text rendition: pick it by its name (the server's DisplayTitle),
     *  else by its language (as forced as the pick first), else none. "Off" or a burned-in pick keeps text off; Auto
     *  leaves the player's default alone. Text stays off until the first frame: a selected rendition whose first segment
     *  the server holds (Emby: 4–12 s, 30–88 s cold) must never hold back the picture. The parameters outlive the item
     *  here (an AVPlayerItem starts fresh), so every load resets them first. AVPlayer's own default shows forced
     *  subtitles only; ExoPlayer would also turn on a track flagged DEFAULT, so that flag is ignored. */
    private fun selectLegible(l: Loaded) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, l.tracks.subtitle != TrackSelection.Subtitle.auto).build()
        legible = l.takeIf { it.subtitleInPlaylist && it.subtitle != null }
    }

    private fun selectLegible(tracks: Tracks) {
        val l = legible ?: return
        val want = l.subtitle ?: return
        val p = player ?: return
        if (!started) return
        val all = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { g -> (0 until g.length).map { g to it } }
        if (all.isEmpty()) return   // not loaded yet
        legible = null
        val code: String? = want.language?.let { languageCode(MediaTrack.terminology(it) ?: it) }
        val lang = all.filter { (tg, ti) -> code != null && tg.getTrackFormat(ti).language?.let(::languageCode) == code }
        val asForced = lang.filter { (tg, ti) -> ((tg.getTrackFormat(ti).selectionFlags and C.SELECTION_FLAG_FORCED) != 0) == want.isForced }
        val pick: Pair<Tracks.Group, Int> = all.firstOrNull { (tg, ti) -> tg.getTrackFormat(ti).label == want.title }
            ?: asForced.firstOrNull() ?: lang.firstOrNull() ?: return   // no such rendition: nothing
        val (g, i) = pick
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
    }

    /** What AVPlayerItem.error.localizedDescription says for the same failure: URLError's wording for the network (as
     *  AppModel.serverFailed words it), AVFoundation's "Cannot Decode" / "Cannot Open" for media it can't play. */
    private fun PlaybackException.avText(): String {
        for (e in generateSequence<Throwable>(this) { it.cause }) when (e) {
            is UnknownHostException -> return "A server with the specified hostname could not be found."
            is SocketTimeoutException -> return "The request timed out."
            is ConnectException, is NoRouteToHostException -> return "Could not connect to the server."
            is SocketException -> return "The network connection was lost."
            is HttpDataSource.InvalidResponseCodeException -> return "Server returned ${e.responseCode}"
        }
        return when (errorCode) {
            in 4000..4999 -> "Cannot Decode"   // ERROR_CODE_DECODER_INIT_FAILED … DECODING_FORMAT_UNSUPPORTED
            in 3000..3999 -> "Cannot Open"     // ERROR_CODE_PARSING_*
            else -> "The operation could not be completed"
        }
    }

    /** "eng" / "en" / "en-US" → "en": ISO 639-2 and -1 compare equal, like the Swift alpha3 match. */
    private fun languageCode(code: String): String = (Util.normalizeLanguageCode(code) ?: code).substringBefore('-')

    fun stop() {
        removeCallbacks(watchdog)
        owner?.lifecycle?.removeObserver(lifecycle); owner = null
        player?.removeListener(listener); player?.release(); player = null
    }
}

// MARK: - Picture backdrop

/** Hue gradient (the prototype's `wv.bg`) with the item's backdrop on top while the stream loads or is absent. */
@Composable
private fun Picture(hue: Double, art: String?, modifier: Modifier) {
    Box(modifier.clipToBounds().drawBehind {
        drawRect(Brush.radialGradient(0f to channelColor(hue, 0.42, 0.09), 0.55f to channelColor(hue, 0.2, 0.05), 1f to channelColor(hue, 0.1, 0.02),
            center = Offset(size.width * 0.3f, size.height * 0.25f), radius = 1700.dp.toPx()))
    }.drawWithContent {
        drawContent()
        drawRect(Brush.radialGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.5f), center = center, radius = 1100.dp.toPx()))
    }) {
        RemoteImage(art, Modifier.fillMaxSize(), ContentScale.Crop)
    }
}

// MARK: - Static

/** One 220×220 grey noise tile (the prototype's feTurbulence tile, grayscale, contrast 1.6), built once. */
private val noiseTile: ImageBitmap by lazy {
    val n = 220
    val px = IntArray(n * n) { val g = Random.nextInt(13, 192); (0xff shl 24) or (g shl 16) or (g shl 8) or g }
    Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** ~0.5 s of noise fading out on channel change (the prototype's rbStatic), re-created per `flips`. */
@Composable
internal fun StaticFlash(modifier: Modifier = Modifier) {
    val opacity = remember { Animatable(1f) }
    val context = LocalContext.current
    // Animations off: a 0.2 s crossfade from black instead of the noise (the iOS handoff's Reduce Motion rule).
    val reduceMotion = remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    // ponytail: one linear fade instead of the prototype's 5 stepped grain shifts; add keyframes if the steps matter.
    LaunchedEffect(Unit) { opacity.animateTo(0f, tween(if (reduceMotion) 200 else 500, easing = LinearEasing)) }
    Canvas(modifier.fillMaxSize().graphicsLayer { alpha = opacity.value }) {
        if (reduceMotion) { drawRect(Color.Black); return@Canvas }
        val s = 220.dp.roundToPx()
        for (y in 0 until size.height.toInt() step s) for (x in 0 until size.width.toInt() step s)
            drawImage(noiseTile, dstOffset = IntOffset(x, y), dstSize = IntSize(s, s), filterQuality = FilterQuality.None)
    }
}

// MARK: - Rewind

/** Start over on the program on screen, as a VCR rewinding its tape: two bands of static rolling up the picture (none
 *  with animations off), and the OSD's ◀◀ REW over a counter running back to 0:00:00 (eased, 1.1 s), then a fade. */
@Composable
private fun RewindEffect(r: AppModel.Rewind) {
    val context = LocalContext.current
    val reduceMotion = remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val t by produceState(0.0) { while (true) { withFrameNanos { }; value = ChronoUnit.MILLIS.between(r.started, Instant.now()) / 1000.0 } }
    val left = 1 - (1 - min(1.0, t / 1.1)).pow(3)   // ease-out
    val s = (r.from * (1 - left)).roundToInt()
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = ((AppModel.Rewind.length - t) / 0.25).coerceIn(0.0, 1.0).toFloat() }) {
        if (!reduceMotion) Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = 0.85f }) {
            val tile = 220.dp.roundToPx(); val bandH = 90.dp.toPx()
            for (k in 0..1) {   // 90 tall, up the screen every 0.7 s, shaking sideways
                val y = ((size.height + bandH) * (1 - (t / 0.7 + k * 0.55) % 1.0)).toFloat() - bandH
                clipRect(top = y, bottom = y + bandH) {
                    val ox = Random.nextInt(-tile, 0); val oy = Random.nextInt(-tile, 0)
                    for (yy in (y.toInt() + oy) until (y + bandH).toInt() step tile) for (x in ox until size.width.toInt() step tile)
                        drawImage(noiseTile, dstOffset = IntOffset(x, yy), dstSize = IntSize(tile, tile), filterQuality = FilterQuality.None)
                }
            }
        }
        val osd = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 56.sp,
            shadow = Shadow(Color.Black.copy(alpha = 0.7f), with(LocalDensity.current) { Offset(3.dp.toPx(), 3.dp.toPx()) }, 0f))
        Column(Modifier.align(Alignment.TopEnd).padding(end = 96.dp, top = 72.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(64.dp, 40.dp)) {   // SF Symbol backward.fill at 56 pt, with the text's shadow
                    val w = size.width / 2; val h = size.height
                    val glyph = Path().apply { for (x0 in listOf(0f, w)) { moveTo(x0 + w, 0f); lineTo(x0, h / 2); lineTo(x0 + w, h); close() } }
                    translate(3.dp.toPx(), 3.dp.toPx()) { drawPath(glyph, Color.Black.copy(alpha = 0.7f)) }
                    drawPath(glyph, Color.White)
                }
                Txt("REW", osd)
            }
            Txt("%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60), osd)
        }
    }
}

// MARK: - Banner

/** Port of the prototype's vWatch: every string/color the banner shows. */
private class BannerData(info: WatchInfo, model: AppModel, t: Theme, now: Instant, trailerOn: Boolean) {
    class Entry(val time: String, val title: String, val isNow: Boolean)
    private val ch = info.channel
    private val s = info.slot
    private val item = s.item
    private val pos = info.position
    private val paused = model.playback?.paused == true
    private val behind = if (!info.live && now >= s.start && now < s.end) now.secondsSince(s.start) - pos else 0.0
    private val minsIn = (pos / 60).toInt()
    val number = ch.number
    val name = ch.name
    val tag = ch.tag
    val title = item.displayTitle
    val sub = if (item.kind == MediaItem.Kind.movie) listOfNotNull(item.year?.toString(), item.contentRating).joinToString(" · ")
        else listOfNotNull("“${item.title}”", item.seasonEpisode.ifEmpty { null }).joinToString(" · ")
    val startT = Clock.time(s.start)
    val endT = Clock.time(s.end)
    val timeRange = Clock.range(s.start, s.end)
    val progress = max(0.0, min(1.0, pos / s.duration)).toFloat()
    /** Midnight Movie's status badge (LIVE / BREAK / START OVER / PAUSED) and its color. */
    val badge = if (paused) "PAUSED" else if (info.live) (if (info.isBreak) "BREAK" else "LIVE") else "START OVER"
    val badgeBg = if (paused) t.dim else if (info.live) (if (info.isBreak) t.breakBadge else t.accent) else t.startOverBadge
    val status = when {
        paused -> "Paused"
        info.live -> if (info.isBreak) "${if (trailerOn) "Trailers" else "Station break"} · back at ${Clock.time(s.end)}" else "Live · $minsIn min in"
        behind >= 60 -> "${Math.round(behind / 60)} min behind live"
        behind >= 3 -> "${Math.round(behind)} s behind live"
        else -> "From the beginning · $minsIn min in"
    }
    val statusColor = if (!paused && info.live && !info.isBreak) t.accent else t.mute
    val nextTitle = info.next?.item?.displayTitle ?: "More after this"
    val nextTime = info.next?.let { "${Clock.time(it.start)} ${Clock.ap(it.start)}" } ?: ""
    val actions = model.watchActions().map { it.label }
    val focus = model.bannerFocus
    /** The progress bar has the remote's focus (no button does then). */
    val bar = model.barFocused && model.bannerFocus >= 0
    val skipCue = model.skipCue
    /** The open Audio or Subtitles list: the button it's over, its rows, the one on and the one focused. */
    class Menu(val anchor: Int, val rows: List<String>, val on: Int, val focus: Int)
    val menu: Menu? = model.trackMenu?.let { m ->
        val c = model.trackChoices(m.kind)
        if (c.isEmpty()) null else Menu(model.bannerFocus, c.map { it.label }, c.indexOfFirst { it.on }, m.focus.coerceAtMost(c.size - 1))
    }
    /** The lineup strip: this slot plus the next three, "9:30 PM" then "10:00" until the meridiem changes. */
    val tonight: List<Entry> = run {
        var prevAP = ""
        (listOf(s) + model.slots(ch, s.start, s.start.plusSeconds(4 * 3600L)).filter { it.start >= s.end }.take(3)).map { x ->
            val ap = Clock.ap(x.start)
            Entry(Clock.time(x.start) + (if (ap == prevAP) "" else " $ap"), x.item.displayTitle, x.start == s.start).also { prevAP = ap }
        }
    }
}

@Composable
private fun Banner(d: BannerData) {
    val t = LocalTheme.current
    val model = LocalModel.current
    /** The remote's way to run a banner button: focus it, then Select (not the number box's OK). */
    val run = { i: Int -> model.closeEntry(); model.bannerFocus = i; model.barFocused = false; model.press(RemoteCommand.select) }
    if (t.id == ThemeID.MIDNIGHT) Midnight(d, t, run) else Late(d, t, run)
}

@Composable
private fun Late(d: BannerData, t: Theme, run: (Int) -> Unit) {
    val shadow = remember { ShadowMask() }
    Column(Modifier.fillMaxWidth().drawBehind {
        val r = 32.dp.toPx()
        // .background(t.bg.opacity(0.92), in: RoundedRectangle(32)).shadow(black 0.5, radius 40, y 30). SwiftUI's radius
        // blurs with sigma 40 (measured on the Apple TV); Skia's blur radius r means sigma 0.57735·r + 0.5.
        val blur = (40.dp.toPx() - 0.5f) / 0.57735f
        val dy = 30.dp.toPx()
        val shade = Color.Black.copy(alpha = 0.5f).toArgb()
        drawIntoCanvas {
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = t.bg.copy(alpha = 0.92f).toArgb() }
            // Hardware canvases draw shadow layers on shapes from API 28; before that the blur is made once in software.
            if (Build.VERSION.SDK_INT >= 28) p.setShadowLayer(blur, 0f, dy, shade)
            else { val pad = 3 * 40.dp.toPx(); it.nativeCanvas.drawBitmap(shadow.get(size, r, blur, pad, shade), -pad, dy - pad, null) }
            it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, p)
        }
    }.padding(top = 36.dp, start = 44.dp, end = 44.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt("${d.number}", t.body(28, FontWeight.Bold).copy(color = t.accentInk),
                        Modifier.background(t.accent, RoundedCornerShape(50)).padding(vertical = 3.dp, horizontal = 16.dp))
                    Txt(d.name, t.body(28, FontWeight.SemiBold).copy(color = t.ink))
                    Txt("· ${d.tag}", t.body(28).copy(color = t.mute))
                }
                Txt(d.title, t.heading(72, FontWeight.Bold).copy(color = t.ink, letterSpacing = (-0.025).em), lines = 1)
                Para(d.sub, t.body(32).copy(color = t.mute))
            }
            Column(Modifier.width(460.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt("Up next · ${d.nextTime}".uppercase(), t.body(24, FontWeight.SemiBold).copy(color = t.dim, letterSpacing = 0.14.em))
                Txt(d.nextTitle, t.body(34, FontWeight.SemiBold).copy(color = t.ink), lines = 1)
            }
        }
        val row = t.body(26, FontWeight.SemiBold).copy(color = t.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(d.startT, row)
            Bar(d.progress, 8f, 4f, t.line, t.accent, Modifier.weight(1f), d.skipCue, d.bar)
            Txt(d.endT, row)
            StatusText(d.status, d.statusColor, d.focus, row.copy(textAlign = TextAlign.End), t.body(24).copy(color = t.dim), Modifier.widthIn(min = 340.dp))
        }
        Box(Modifier.heightIn(min = 62.dp), contentAlignment = Alignment.CenterStart) {
            if (d.focus >= 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    d.actions.forEachIndexed { i, label ->
                        val f = i == d.focus && !d.bar
                        val scale by animateFloatAsState(if (f) 1.05f else 1f, tween(150, easing = EaseOut), label = "chip")
                        Box {
                            Txt(label, t.body(28, FontWeight.SemiBold).copy(color = if (f) t.focusInk else t.ink),
                                Modifier.scale(scale).onTouch { run(i) }.background(if (f) t.focusBg else t.panel2, RoundedCornerShape(50))
                                    .padding(vertical = 14.dp, horizontal = 30.dp))
                            d.menu?.takeIf { it.anchor == i }?.let { TrackList(it, Modifier.above(16.dp)) }
                        }
                    }
                }
            } else {
                val measurer = rememberTextMeasurer()
                ShareRow(16.dp, d.tonight.map { measurer.measure(it.title, t.body(26), maxLines = 1).size.width }) {
                    Txt("Tonight on ${d.name}".uppercase(), t.body(22, FontWeight.SemiBold).copy(color = t.dim, letterSpacing = 0.14.em))
                    d.tonight.forEach { x ->
                        Row(Modifier.background(if (x.isNow) t.panel2 else Color.Transparent, RoundedCornerShape(50))
                            .padding(vertical = 10.dp, horizontal = if (x.isNow) 20.dp else 0.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (x.isNow) Box(Modifier.size(8.dp).background(t.accent, CircleShape))
                            Txt(x.time, t.body(24, FontWeight.SemiBold).copy(color = t.accent))
                            Txt(x.title, t.body(26).copy(color = t.ink), Modifier.weight(1f, fill = false), lines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** The prototype's bCable block: a 280px channel-number block beside a bevelled info box, everything square and in caps.
 *  The number block is as tall as the info box (HStack .fixedSize(vertical) with maxHeight .infinity). On tvOS both
 *  `.background(fill)`s sit inside the safe area (80 pt sides, 60 pt bottom), so SwiftUI runs them out to the screen's
 *  edges: the number block left and down, the info box right and down (the 72/56 pt banner margins). */
@Composable
private fun Midnight(d: BannerData, t: Theme, run: (Int) -> Unit) {
    val side = if (Platform.isTV) 72f else 0f
    val below = if (Platform.isTV) 56f else 0f
    Layout({
        Column(Modifier.bevelled(t.accent, t.numberBevelLight, t.numberBevelDark, bleedStart = side, bleedBottom = below).padding(vertical = 20.dp, horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt("${d.number}", t.heading(132).copy(color = t.accentInk), Modifier.lineBox(132 * 0.9f))   // CSS line-height: .9
            Txt(t.heading(d.name), t.heading(30).copy(color = t.accentInk, letterSpacing = 0.06.em, textAlign = TextAlign.Center),
                Modifier.lineHeightTrim(30f), lines = 2)
        }
        Column(Modifier.bevelled(t.bg, t.bevelLight, t.bevelDark, bleedEnd = side, bleedBottom = below).padding(vertical = 24.dp, horizontal = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Txt(t.heading(d.title), t.heading(68).copy(color = t.ink), Modifier.weight(1f).alignByBaseline().lineBox(68f), lines = 1)
                Txt(d.timeRange, t.body(32, FontWeight.Bold).copy(color = t.accent), Modifier.alignByBaseline())
            }
            val row = t.body(30, FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                Para(d.sub, row.copy(color = t.mute), Modifier.weight(1f, fill = false))
                Txt(d.badge, t.heading(24).copy(color = t.accentInk, letterSpacing = 0.1.em),
                    Modifier.background(d.badgeBg).padding(top = 3.dp, bottom = 2.dp, start = 12.dp, end = 12.dp))
                StatusText(d.status, t.dim, d.focus, row.copy(color = t.dim), t.body(24, FontWeight.SemiBold).copy(color = t.dim))
            }
            Bar(d.progress, 12f, 0f, t.panel2, t.accent, cue = d.skipCue, focused = d.bar)
            Row(Modifier.heightIn(min = 52.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                if (d.focus >= 0) {
                    // The chips take the whole row: five of them at this size leave no room for the Next line.
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        d.actions.forEachIndexed { i, label ->
                            val f = i == d.focus && !d.bar
                            Box {
                                Txt(label.uppercase(), t.heading(26).copy(color = if (f) t.focusInk else t.ink, letterSpacing = 0.04.em),
                                    Modifier.onTouch { run(i) }.background(if (f) t.focusBg else t.panel2).padding(vertical = 8.dp, horizontal = 18.dp), lines = 1)
                                d.menu?.takeIf { it.anchor == i }?.let { TrackList(it, Modifier.above(16.dp)) }
                            }
                        }
                    }
                } else if (d.tonight.isEmpty()) {
                    // The prototype's Next line; the "Tonight on" strip says the same and more, so it takes the row when it has entries.
                    val s = buildAnnotatedString {
                        append("Next${if (d.nextTime.isEmpty()) "" else " " + d.nextTime}: ".uppercase())
                        withStyle(SpanStyle(color = t.ink)) { append(d.nextTitle.uppercase()) }
                    }
                    BasicText(s, Modifier.weight(1f), t.heading(28, FontWeight.Bold).copy(color = t.dim, letterSpacing = 0.04.em),
                        overflow = TextOverflow.Ellipsis, maxLines = 1)
                } else {
                    val strip = t.body(24, FontWeight.SemiBold).copy(letterSpacing = 0.04.em)
                    val measurer = rememberTextMeasurer()
                    ShareRow(28.dp, d.tonight.map { measurer.measure(it.title.uppercase(), strip, maxLines = 1).size.width }) {
                        Txt("Tonight on ${d.name}".uppercase(), strip.copy(color = t.dim))
                        d.tonight.forEach { x ->
                            Row(Modifier.background(if (x.isNow) t.panel2 else Color.Transparent)
                                .padding(vertical = 6.dp, horizontal = if (x.isNow) 14.dp else 0.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Txt(x.time, strip.copy(color = t.accent))
                                Txt(x.title.uppercase(), strip.copy(color = t.ink), Modifier.weight(1f, fill = false), lines = 1)
                            }
                        }
                    }
                }
            }
        }
    }) { (num, info), c ->
        val nw = 280.dp.roundToPx()
        val gap = 6.dp.roundToPx()
        val iw = (c.maxWidth - nw - gap).coerceAtLeast(0)
        val ip = info.measure(Constraints(iw, iw, 0, c.maxHeight))
        val np = num.measure(Constraints(nw, nw, min(ip.height, c.maxHeight), c.maxHeight))
        layout(c.maxWidth, max(ip.height, np.height)) { np.place(0, 0); ip.place(nw + gap, 0) }
    }
}

/** The house spot is an ad (Play's ads policy, App Store 2.5.18): an "Ad" label top left (below a Paused chip), and a
 *  Skip chip bottom right (above the banner) counting down 5 s, then naming the press that skips it; on touch the chip is the button. */
@Composable
private fun SpotChips(started: Instant, now: Instant) {
    val model = LocalModel.current
    val t = LocalTheme.current
    val mm = t.id == ThemeID.MIDNIGHT
    val chip = Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(t.radius.dp)).padding(vertical = 18.dp, horizontal = 28.dp)
    val text = t.body(32, FontWeight.SemiBold).copy(color = Color.White)
    Row(Modifier.padding(start = 80.dp, top = if (model.playback?.paused == true) 160.dp else 72.dp).then(chip),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt("Ad".uppercase(), (if (mm) t.heading(24) else t.body(24, FontWeight.Bold)).copy(color = t.accentInk, letterSpacing = 0.1.em),
            Modifier.background(t.accent, RoundedCornerShape(if (mm) 0.dp else 8.dp)).padding(top = 3.dp, bottom = 2.dp, start = 12.dp, end = 12.dp))
        Txt("Message from Soyboi", text)
    }
    val skippable = SpotOnAir.skippable
    val left = ceil(AppModel.spotSkipDelay - now.secondsSince(started)).toInt().coerceIn(1, AppModel.spotSkipDelay.toInt())
    val s = buildAnnotatedString {
        if (!skippable) append("Skip in $left")
        else if (Platform.isTV) { append("Press "); appendInlineContent("ok", "◉"); append(" to skip  ›") }
        else append("Skip  ›")
    }
    Box(Modifier.fillMaxSize().padding(end = 72.dp, bottom = if (model.bannerVisible) 480.dp else 64.dp), contentAlignment = Alignment.BottomEnd) {
        BasicText(s, Modifier.onTouch { model.skipSpot() }.then(chip), text.copy(color = Color.White.copy(alpha = if (skippable) 1f else 0.72f)),
            inlineContent = okGlyph(Color.White))
    }
}

/** The Google TV remote's OK (the Siri Remote's button.programmable), inline as "ok". */
private fun okGlyph(color: Color) = mapOf("ok" to InlineTextContent(Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
    Canvas(Modifier.fillMaxSize()) {
        val r = size.minDimension / 2
        drawCircle(color, r * 0.9f, style = Stroke(r * 0.2f))   // proportions of the tvOS button.programmable glyph
        drawCircle(color, r * 0.72f)
    }
})

/** Status line plus " · Press ◉ for options" while the banner shows only the program; the remote glyph is TV only. */
@Composable
private fun StatusText(status: String, statusColor: Color, focus: Int, style: TextStyle, hint: TextStyle, modifier: Modifier = Modifier) {
    val s = buildAnnotatedString {
        withStyle(SpanStyle(color = statusColor)) { append(status) }
        if (focus < 0) withStyle(hint.toSpanStyle()) {
            if (Platform.isTV) { append(" · Press "); appendInlineContent("ok", "◉"); append(" for options") }
            else append(" · ${Platform.verb} for options")
        }
    }
    BasicText(s, modifier, style, inlineContent = okGlyph(hint.color))
}

/** The Late Show shadow below API 28: the blurred rounded rect as an alpha mask, rebuilt only when the banner resizes. */
private class ShadowMask {
    private var key: List<Any>? = null
    private var mask: Bitmap? = null
    fun get(size: Size, r: Float, blur: Float, pad: Float, color: Int): Bitmap {
        val k = listOf(size, r, blur, pad, color)
        mask?.let { if (k == key) return it }
        val b = Bitmap.createBitmap((size.width + 2 * pad).roundToInt(), (size.height + 2 * pad).roundToInt(), Bitmap.Config.ALPHA_8)
        android.graphics.Canvas(b).drawRoundRect(pad, pad, pad + size.width, pad + size.height, r, r,
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
            })
        mask?.recycle(); mask = b; key = k
        return b
    }
}

/** Laid out with its bottom `gap` above the top of the box it's in and its left edge on the box's; takes no room there. */
private fun Modifier.above(gap: Dp) = layout { measurable, _ ->
    val p = measurable.measure(Constraints())
    layout(0, 0) { p.place(0, -p.height - gap.roundToPx()) }
}

/** The banner's Audio or Subtitles list: a check by the choice that's on, the remote's focus, seven rows at most (the list
 *  slides to keep the focus on the middle row, with a scroll bar when rows are out of view). A tap plays a row, as the
 *  remote's OK does. */
@Composable
private fun TrackList(m: BannerData.Menu, modifier: Modifier) {
    val t = LocalTheme.current
    val model = LocalModel.current
    val rowH = 64f; val rowW = 560f; val n = m.rows.size
    val h = minOf(n, 7) * rowH; val travel = n * rowH - h   // an odd count, so the middle row is whole and so are the rows around it
    val ty by animateFloatAsState(maxOf(0f, minOf(travel, m.focus * rowH - (h - rowH) / 2)), tween(220, easing = EaseInOut), label = "trackList")
    val square = t.radius == 0f
    val panel = RoundedCornerShape(if (square) 0.dp else 24.dp)
    Box(modifier.shadow(30.dp, panel).background(t.panel, panel).padding(10.dp)) {
        Box(Modifier.size(rowW.dp, h.dp).clipToBounds()) {   // rows out of view take no taps
            Column(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).graphicsLayer { translationY = -ty.dp.toPx() }) {
                m.rows.forEachIndexed { i, label ->
                    val f = i == m.focus
                    val ink = if (f) t.focusInk else t.ink
                    Row(Modifier.size(rowW.dp, rowH.dp).background(if (f) t.focusBg else Color.Transparent, RoundedCornerShape(if (square) 0.dp else 14.dp))
                            .onTouch { model.trackMenu = model.trackMenu?.copy(focus = i); model.press(RemoteCommand.select) }.padding(horizontal = 22.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkmark(ink, Modifier.size(23.dp, 21.dp).alpha(if (i == m.on) 1f else 0f))
                        Txt(label, t.body(28, FontWeight.SemiBold).copy(color = ink), lines = 1)
                    }
                }
            }
        }
        if (travel > 0) {   // the scroll bar, in the right padding
            val thumb = h * h / (n * rowH)
            Box(Modifier.align(Alignment.TopEnd).graphicsLayer { translationX = 7.dp.toPx(); translationY = ((h - thumb) * ty / travel).dp.toPx() }
                .size(4.dp, thumb.dp).background(t.mute, CircleShape))
        }
    }
}

/** The progress bar, with a ⏪ / ⏩ tag over the playhead while `cue` is −1 / +1 (a skip back or ahead), and a 24-pt knob
 *  on the playhead while it has the remote's focus. */
@Composable
private fun Bar(progress: Float, height: Float, radius: Float, track: Color, fill: Color, modifier: Modifier = Modifier, cue: Int = 0, focused: Boolean = false) {
    val shape = RoundedCornerShape(radius.dp)
    val ink = LocalTheme.current.accentInk
    val knob = LocalTheme.current.ink
    val dir = remember { intArrayOf(1) }.also { if (cue != 0) it[0] = cue }   // the last direction, for the fade-out
    val alpha by animateFloatAsState(if (cue != 0) 1f else 0f, tween(200, easing = EaseOut), label = "skipCue")
    Box(modifier.fillMaxWidth().height(height.dp).background(track, shape).drawWithContent {
        drawContent()
        if (focused) {   // over a soft shadow
            val k = 24.dp.toPx(); val pad = 3.dp.toPx(); val cx = size.width * progress; val cy = size.height / 2
            val r = if (radius > 0) k / 2 else 0f
            drawRoundRect(Color.Black.copy(alpha = 0.3f), Offset(cx - k / 2 - pad, cy - k / 2 - pad + 2.dp.toPx()), Size(k + 2 * pad, k + 2 * pad),
                CornerRadius(if (radius > 0) r + pad else 0f))
            drawRoundRect(knob, Offset(cx - k / 2, cy - k / 2), Size(k, k), CornerRadius(r))
        }
        if (alpha == 0f) return@drawWithContent
        // SwiftUI's backward.fill / forward.fill at 22 pt bold on a 60 × 36 tag, its center 24 above the bar's top.
        val w = 60.dp.toPx(); val h = 36.dp.toPx()
        val cx = (size.width * progress).coerceIn(w / 2, size.width - w / 2); val cy = -24.dp.toPx()
        val r = if (radius > 0) h / 2 else 0f
        drawRoundRect(fill, Offset(cx - w / 2, cy - h / 2), Size(w, h), CornerRadius(r), alpha = alpha)
        val gw = 15.dp.toPx(); val gh = 9.dp.toPx(); val s = dir[0].toFloat()
        val glyph = Path().apply {
            for (k in listOf(-1f, 0f)) {   // two triangles pointing the skip's way
                val base = cx + s * k * gw
                moveTo(base, cy - gh); lineTo(base + s * gw, cy); lineTo(base, cy + gh); close()
            }
        }
        drawPath(glyph, ink, alpha = alpha)
    }) {
        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(fill, shape))
    }
}

/** SwiftUI's HStack for the "Tonight on" strip, as tvOS runs it. Each pass gives the first child (the fixedSize kicker)
 *  its full width, then the others, least flexible first (`flex`: the title's width; ties keep their order), an equal
 *  share of what is left. The row is measured with the full width, then laid out again with only the width it reported
 *  and centred in it. So a "now" pill whose title is no longer than a later one's, but which carries its own padding,
 *  gets less than it needs on the second pass: it truncates and the row sits a few points right, as on the Apple TV
 *  ("Reunion Week…" beside "The Last Payphone"). */
@Composable
private fun ShareRow(spacing: Dp, flex: List<Int>, content: @Composable () -> Unit) {
    Layout(content) { ms, c ->
        if (ms.isEmpty()) return@Layout layout(c.minWidth, c.minHeight) {}
        val gap = spacing.roundToPx()
        val first = ms[0].measure(Constraints(maxHeight = c.maxHeight))
        val rest = ms.drop(1)
        val want = rest.map { it.maxIntrinsicWidth(c.maxHeight) }
        val order = rest.indices.sortedBy { flex.getOrElse(it) { 0 } }
        fun alloc(proposal: Int): IntArray {
            val a = IntArray(rest.size)
            var left = (proposal - first.width - gap * rest.size).coerceAtLeast(0)
            order.forEachIndexed { n, i -> a[i] = min(want[i], left / (rest.size - n)); left -= a[i] }
            return a
        }
        val ideal = first.width + gap * rest.size + want.sum()
        val reported = if (c.hasBoundedWidth) first.width + gap * rest.size + alloc(c.maxWidth).sum() else ideal
        val second = alloc(reported)
        val ps = listOf(first) + rest.mapIndexed { i, m -> m.measure(Constraints(maxWidth = second[i], maxHeight = c.maxHeight)) }
        val h = ps.maxOf { it.height }.coerceIn(c.minHeight, c.maxHeight)
        val w = reported.coerceIn(c.minWidth, c.maxWidth)
        layout(w, h) {
            var x = (reported - (ps.sumOf { it.width } + gap * (ps.size - 1))) / 2
            ps.forEach { it.placeRelative(x, (h - it.height) / 2); x += it.width + gap }
        }
    }
}

@Composable
private fun Txt(text: String, style: TextStyle, modifier: Modifier = Modifier, lines: Int = Int.MAX_VALUE) =
    BasicText(text, modifier, style, overflow = if (lines < Int.MAX_VALUE) TextOverflow.Ellipsis else TextOverflow.Clip, maxLines = lines)

/** `.frame(height: h)` on one line of display type: an `h`-tall box with the text centred in it, overflowing unclipped. */
private fun Modifier.lineBox(h: Float) = height(h.dp).wrapContentHeight(Alignment.CenterVertically, unbounded = true)

/** Midnight Movie's one-off colors (the prototype's bCable block); Late Show never reads them. */
private val Theme.startOverBadge get() = hex("#7fd39a")
private val Theme.numberBevelLight get() = hex("#ff9a8f")
private val Theme.numberBevelDark get() = hex("#a8231a")

/** CSS `border: 6px solid` with mitred light top/left and dark bottom/right edges around a solid fill. The fill (only)
 *  runs `bleed…` points past the box, SwiftUI's background extending into the safe area. */
private fun Modifier.bevelled(fill: Color, light: Color, dark: Color, bleedStart: Float = 0f, bleedEnd: Float = 0f, bleedBottom: Float = 0f) = drawBehind {
    val w = size.width
    val h = size.height
    val b = 6.dp.toPx()
    drawRect(fill, Offset(-bleedStart.dp.toPx(), 0f), Size(w + (bleedStart + bleedEnd).dp.toPx(), h + bleedBottom.dp.toPx()))
    drawPath(Path().apply { moveTo(0f, 0f); lineTo(w, 0f); lineTo(w - b, b); lineTo(b, b); lineTo(b, h - b); lineTo(0f, h); close() }, light)
    drawPath(Path().apply { moveTo(w, h); lineTo(0f, h); lineTo(b, h - b); lineTo(w - b, h - b); lineTo(w - b, b); lineTo(w, 0f); close() }, dark)
}.padding(6.dp)

