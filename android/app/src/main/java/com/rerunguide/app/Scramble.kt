package com.rerunguide.app

import android.graphics.ImageDecoder
import android.graphics.RuntimeShader
import android.graphics.drawable.AnimatedImageDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/** Channel 99's picture: a mystery backdrop from the library rolling and tearing under the scramble shader, the way
 *  the premium cable channels looked without a descrambler box. The full-screen one also plays the warble (`audio`).
 *  The backdrop is picked from `seed` (the callers pass the slot start, so each "program" gets its own).
 *  Android 13+ runs the Metal shaders' math as AGSL; older versions get the tearing as offset slices with the
 *  chroma swing and glow per slice, the scanlines and the sync bar (no per-row jitter). Fire TV shows the web build's
 *  silent GIF instead ([ScrambleGif]). */
@Composable
fun ScrambleView(seed: Int, modifier: Modifier = Modifier, audio: Boolean = false) {
    if (Platform.isFireTV && Build.VERSION.SDK_INT >= 28) { ScrambleGif(modifier); return }
    val model = LocalModel.current
    val art = remember(seed) {
        val c = model.library.filter { it.backdropPath != null }
        if (c.isEmpty()) null else model.server?.backdropURL(c[abs(seed) % c.size])
    }
    val clock = remember { mutableDoubleStateOf(scrambleTime()) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { clock.doubleValue = scrambleTime() } }   // TimelineView(.animation)
    if (audio) {
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {   // the warble follows the app out to the Home screen and back
            ScrambleAudio.shared.start()
            val o = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_STOP) ScrambleAudio.shared.stop() else if (e == Lifecycle.Event.ON_RESUME) ScrambleAudio.shared.start()
            }
            owner.lifecycle.addObserver(o)
            onDispose { owner.lifecycle.removeObserver(o); ScrambleAudio.shared.stop() }
        }
    }
    val shader = if (Build.VERSION.SDK_INT >= 33) remember { ScrambleShader() } else null
    val effect = if (shader != null) Modifier.graphicsLayer { clip = true; renderEffect = shader.effect(clock.doubleValue.toFloat(), size, density) }
    else Modifier.clipToBounds().drawWithContent { tear(clock.doubleValue) }
    Box(modifier.background(Color.Black)) {
        // Two copies stacked, sliding up one picture height every 7 s: the vertical roll.
        Column(Modifier.fillMaxSize().then(effect).layout { m, c ->
            val p = m.measure(c.copy(minHeight = c.maxHeight * 2, maxHeight = c.maxHeight * 2))
            layout(c.maxWidth, c.maxHeight) { p.place(0, -(clock.doubleValue % 7 / 7 * c.maxHeight).toInt()) }
        }) {
            repeat(2) { Picture(art, Modifier.fillMaxWidth().weight(1f)) }
        }
    }
}

/** Fire TV's channel 99, as on LG and Samsung: `web/assets/scramble.gif` (the shader's maths over colour bars, a 10 s
 *  loop; packaged as an asset from there, see app/build.gradle.kts), cropped to fill, with no warble. Every Fire TV is
 *  below Android 13, so it would otherwise run `tear()`'s 56 layers a frame on a stick's GPU, and the warble takes no
 *  audio focus. The drawable decodes its frames on the render thread only while it's drawn, and stops with the view.
 *  Fire OS is always API 28+ (Fire OS 6, API 25, is under minSdk). */
@RequiresApi(28)
@Composable
private fun ScrambleGif(modifier: Modifier) {
    AndroidView({ c ->
        ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP   // 16:9 fills the stage exactly and the Late Show's 4:3 tube by its middle
            setImageDrawable(ImageDecoder.decodeDrawable(ImageDecoder.createSource(c.assets, "scramble.gif")).also { (it as? AnimatedImageDrawable)?.start() })
        }
    }, modifier.background(Color.Black), onRelease = { (it.drawable as? AnimatedImageDrawable)?.stop(); it.setImageDrawable(null) })
}

/** Colour bars stand in until the backdrop arrives, and are what shows when the library has no art. */
@Composable
private fun Picture(art: String?, modifier: Modifier) {
    Box(modifier.clipToBounds()) {
        Row(Modifier.fillMaxSize()) { bars.forEach { Box(Modifier.weight(1f).fillMaxHeight().background(it)) } }
        RemoteImage(art, Modifier.fillMaxSize())
    }
}

/** SwiftUI's white, yellow, cyan, green, purple, red, blue (dark appearance). */
private val bars = listOf("#ffffff", "#ffd60a", "#64d2ff", "#30d158", "#bf5af2", "#ff453a", "#0a84ff").map(::hex)

/** The Swift clock: seconds since 2001-01-01, wrapped every hour. */
private fun scrambleTime() = (System.currentTimeMillis() / 1000.0 - 978_307_200.0) % 3600.0

/** App/Shaders/Scramble.metal, scrambleWarp then scrambleColor, in one pass. Positions are in stage points like the
 *  SwiftUI ones (`scale` = pixels per point), so the scanlines and jitter rows match at any screen size. */
private const val scrambleAGSL = """
uniform shader image;
uniform float time;
uniform float2 size;
uniform float scale;

half4 main(float2 frag) {
    float2 pos = frag / scale;
    // Analog cable scramble. The sync is deliberately wrong, so every row of the picture lands a different distance
    // sideways: slow waves that drift, a jitter that buzzes, and horizontal bands that tear away and snap back.
    float y = pos.y / size.y;
    float wave = sin(y * 9.0 + time * 2.1) * 0.10 + sin(y * 31.0 - time * 5.7) * 0.03;
    float band = floor(y * 14.0 + time * 0.9);
    float tear = (fract(sin(band * 12.9898 + floor(time * 3.0) * 78.233) * 43758.5453) - 0.5) * 0.22;
    float jitter = (fract(sin((pos.y + floor(time * 60.0)) * 0.37) * 10000.0) - 0.5) * 0.012;
    float x = pos.x + (wave + tear + jitter) * size.x;
    // The Metal shader wraps x, but SwiftUI hands it only maxSampleOffset (0.4 × width) around each pixel, and a
    // wrapped sample is always farther than that: it reads transparent, so on the Apple TV the torn-off edge is black.
    if (x < 0.0 || x >= size.x) return half4(0.0);
    half4 color = image.eval(float2(x * scale, frag.y));

    // Chroma that can't lock: colour channels swing out of phase, scanlines, and a dark sync bar once per roll.
    float scan = 0.72 + 0.28 * step(0.5, fract(pos.y / 3.0));
    float bar = smoothstep(0.0, 0.035, abs(fract(y - time * 0.23) - 0.5));
    float swing = 0.5 + 0.5 * sin(time * 1.7 + y * 11.0);
    float3 c = float3(color.rgb);
    float3 off = float3(c.g, c.b, c.r) * float3(1.3, 0.8, 1.4);
    float3 mixed = mix(c, off, swing);
    float glow = 1.15 + 0.15 * sin(time * 23.0 + y * 90.0);   // the picture "breathes" like a weak signal
    return half4(half3(mixed * scan * (0.25 + 0.75 * bar) * glow), color.a);
}
"""

@RequiresApi(33)
private class ScrambleShader {
    private val shader = RuntimeShader(scrambleAGSL)

    /** A new effect per frame: a RenderEffect keeps the uniforms it was made with. */
    fun effect(time: Float, size: Size, scale: Float): androidx.compose.ui.graphics.RenderEffect? {
        if (size.minDimension <= 0f) return null
        shader.setFloatUniform("time", time)
        shader.setFloatUniform("size", size.width / scale, size.height / scale)
        shader.setFloatUniform("scale", scale)
        return android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "image").asComposeRenderEffect()
    }
}

/** Below Android 13: the warp's waves and torn bands as 56 offset slices (4 per band), each with the chroma swing and
 *  glow at its centre, then the scanlines and the sync bar. Same clock and the same numbers as the shader, in stage points. */
private fun ContentDrawScope.tear(time: Double) {
    val w = size.width; val h = size.height
    if (w <= 0f || h <= 0f) return
    val rows = 56
    val paint = Paint()
    for (i in 0 until rows) {
        val y = (i + 0.5) / rows
        val wave = sin(y * 9.0 + time * 2.1) * 0.10 + sin(y * 31.0 - time * 5.7) * 0.03
        val band = floor(y * 14.0 + time * 0.9)
        val tear = (fract(sin(band * 12.9898 + floor(time * 3.0) * 78.233) * 43758.5453) - 0.5) * 0.22
        val s = ((wave + tear) * w).toFloat()   // dest x shows source x + s; past the edge stays black (see the AGSL)
        // mix(c, c.gbr · (1.3, 0.8, 1.4), swing) · glow
        val sw = (0.5 + 0.5 * sin(time * 1.7 + y * 11.0)).toFloat()
        val g = (1.15 + 0.15 * sin(time * 23.0 + y * 90.0)).toFloat()
        val k = g * (1 - sw)
        paint.colorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            k, g * 1.3f * sw, 0f, 0f, 0f,
            0f, k, g * 0.8f * sw, 0f, 0f,
            g * 1.4f * sw, 0f, k, 0f, 0f,
            0f, 0f, 0f, 1f, 0f)))
        val slice = Rect(0f, h * i / rows, w, h * (i + 1) / rows)
        clipRect(slice.left, slice.top, slice.right, slice.bottom) {
            drawContext.canvas.saveLayer(slice, paint)
            translate(-s) { this@tear.drawContent() }
            drawContext.canvas.restore()
        }
    }
    val pt = density
    var line = 0f
    while (line < h) { drawRect(Color.Black.copy(alpha = 0.28f), Offset(0f, line), Size(w, 1.5f * pt)); line += 3 * pt }
    val bar = fract(0.5 + time * 0.23).toFloat() * h   // where |fract(y − t·0.23) − 0.5| = 0: darkest (×0.25)
    val half = 0.035f * h
    for (c in listOf(bar - h, bar, bar + h)) {
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f), Color.Transparent), c - half, c + half),
            Offset(0f, c - half), Size(w, 2 * half))
    }
}

private fun fract(x: Double) = x - floor(x)

/** The sound of a scrambled channel: a tone that never settles, buried in hiss. Quiet, and it stops with the picture.
 *  Like `StationSting`, every AudioTrack call runs on its own queue so a slow audio server can't freeze the remote;
 *  the samples are written from a render thread of their own. */
class ScrambleAudio private constructor() {
    companion object { val shared = ScrambleAudio() }

    private val queue = Executors.newSingleThreadExecutor { Thread(it, "rerunguide.scramble-audio") }
    private val sampleRate = 44100
    private var track: AudioTrack? = null   // `queue` only
    private var renderer: Thread? = null
    @Volatile private var running = false
    // The warble's state, render thread only; it carries on across stop/start like the Swift render block's.
    private var phase = 0.0
    private var lfo = 0.0
    private var hiss = 2463534242u

    private fun warble(out: ShortArray) {
        val sr = sampleRate.toDouble()
        for (i in out.indices) {
            lfo += 0.9 / sr
            val hz = 260 + 140 * sin(lfo * 2 * PI) + 40 * sin(lfo * 13.7)
            phase += hz / sr
            hiss = hiss xor (hiss shl 13); hiss = hiss xor (hiss shr 17); hiss = hiss xor (hiss shl 5)   // xorshift noise
            val noise = (hiss.toDouble() / UInt.MAX_VALUE.toDouble() - 0.5) * 0.5
            val tone = sin(phase * 2 * PI) * 0.35 + sin(phase * 4 * PI) * 0.12
            out[i] = ((tone + noise) * 0.18 * 0.6 * Short.MAX_VALUE).toInt().toShort()   // × the Swift mixer's 0.6
        }
    }

    fun start() {
        queue.execute {
            if (running) return@execute
            runCatching {
                val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(max(min, sampleRate / 10 * 2))
                    .build()
                t.play()
                track = t; running = true
                renderer = Thread({
                    val buf = ShortArray(1024)
                    while (running) { warble(buf); if (t.write(buf, 0, buf.size) < 0) break }
                }, "rerunguide.scramble-render").apply { start() }
            }
        }
    }

    fun stop() {
        queue.execute {
            running = false
            runCatching { track?.pause() }   // also lets a blocked write return
            renderer?.join(); renderer = null
            runCatching { track?.flush(); track?.release() }
            track = null
        }
    }
}
