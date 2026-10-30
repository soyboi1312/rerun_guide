package com.rerunguide.app

import android.graphics.Paint
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Rerun Guide's loading animation (port of App/RerunLoader.swift), the design handoff's "2a, ears ring": the app-icon TV,
 * drawn live, whose rabbit ears pop off, curl into a ring, spin twice and snap back on (a 2.4 s loop). The caption
 * alternates "Tuning in…" and "Buffering…" every two loops with a 0.22 s fade; a single caption stays put.
 * It fades in once `delay` seconds have passed, so a wait shorter than that never shows it. Animations off: the ears
 * stay put, the arrow turns once every 3 s and captions change every 6 s. The clock is read while drawing, so only the
 * canvas redraws per frame.
 */
@Composable
fun RerunLoader(modifier: Modifier = Modifier, width: Float = 384f, captions: List<String> = LoaderArt.captions, delay: Double = 0.0) {
    val t = LocalTheme.current
    val context = LocalContext.current
    val reduceMotion = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val clock = produceState(-delay * 1000) {   // ms into the loop; negative during the delay
        val t0 = withFrameNanos { it }
        while (true) withFrameNanos { value = (it - t0) / 1e6 - delay * 1000 }
    }
    val k = width / 640
    val per = if (reduceMotion) 6000.0 else 4800.0   // two ring loops per caption
    val i by remember(captions) { derivedStateOf { (max(0.0, clock.value) / per).toInt() % max(1, captions.size) } }
    val shadow = with(LocalDensity.current) { 10.dp.toPx() }   // readable over a bright frame while buffering
    Column(modifier.graphicsLayer { alpha = (clock.value / 250).coerceIn(0.0, 1.0).toFloat() },
        Arrangement.spacedBy((20 * k).dp), Alignment.CenterHorizontally) {
        Canvas(Modifier.size(width.dp, (620 * k).dp)) {
            val ms = max(0.0, clock.value)
            val pose = if (reduceMotion) LoaderArt.rest(-360 * (ms % 3000) / 3000) else LoaderArt.ring(ms % 2400 / 2400)
            val s = size.width / 640
            withTransform({ scale(s, s, Offset.Zero); translate(0f, 100f) }) { with(LoaderArt) { draw(pose) } }
        }
        if (captions.isNotEmpty()) BasicText(captions[i], Modifier.graphicsLayer {
            val p = max(0.0, clock.value) % per
            alpha = if (captions.size == 1) 1f else min(1.0, min(p / 220, (per - p) / 220)).toFloat()
        }, t.body(50 * k, FontWeight.Medium).copy(color = t.dim, letterSpacing = 0.02.em, shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = shadow)))
    }
}

/** The logo art and the ring choreography (one loop, t in 0…1), in the icon's 640×480 box; the ring rises above it, to
 *  y −100. Fixed brand colors: this is the app icon, not themed UI. */
internal object LoaderArt {
    val captions = listOf("Tuning in…", "Buffering…")
    /** One rabbit ear: base point, heading (radians), curvature (1/radius). */
    class Ear(val bx: Double, val by: Double, val th: Double, val k: Double)
    /** `sy`: the TV's squash, anchored at bottom center; `arrow`: degrees. */
    class Pose(val sy: Double, val arrow: Double, val a: Ear, val b: Ear)

    private val ink = hex("#f3ebe0")
    private val stem = hex("#c9bdae")
    private val amber = hex("#f0a33a")
    private const val LEN = 120.0
    private const val RING_R = LEN / PI
    private const val CX = 320.0
    private const val CY = -40.0

    fun rest(arrow: Double) = ring(0.0).let { Pose(it.sy, arrow, it.a, it.b) }

    /** 0–.14 crouch · .08–.30 ears pop and curl into a ring (overshoot) · .30–.72 two turns · .72–.88 straighten and
     *  drop back on · .88–.98 landing squash. The arrow stays still. */
    fun ring(t: Double): Pose {
        fun seg(a: Double, b: Double) = ((t - a) / (b - a)).coerceIn(0.0, 1.0)
        var sy = 1.0
        if (t < 0.06) sy = 1 - 0.03 * out(seg(0.0, 0.06))
        else if (t < 0.14) sy = 0.97 + 0.03 * out(seg(0.06, 0.14))
        else if (t >= 0.88 && t < 0.92) sy = 1 - 0.025 * out(seg(0.88, 0.92))
        else if (t >= 0.92 && t < 0.98) sy = 0.975 + 0.025 * out(seg(0.92, 0.98))
        var f = 0.0; var kf = 0.0; var phi = 0.0
        if (t >= 0.08 && t < 0.30) { f = back(seg(0.08, 0.30)); kf = out(seg(0.08, 0.30)) }
        else if (t >= 0.30 && t < 0.72) { f = 1.0; kf = 1.0; phi = -720 * inOut(seg(0.30, 0.72)) }
        else if (t >= 0.72 && t < 0.88) { val q = seg(0.72, 0.88); f = 1 - q * q * q; kf = 1 - inOut(q) }
        fun lerp(a: Double, b: Double) = a + (b - a) * f
        val r = PI / 180; val drop = (476 - 84) * (1 - sy); val k = PI / LEN * kf
        return Pose(sy, 0.0,
            Ear(lerp(320.0, CX + RING_R * cos((90 + phi) * r)), lerp(84 + drop, CY + RING_R * sin((90 + phi) * r)), lerp(-122.0, -180 + phi) * r, k),
            Ear(lerp(320.0, CX + RING_R * cos((-90 + phi) * r)), lerp(84 + drop, CY + RING_R * sin((-90 + phi) * r)), lerp(-58.0, phi) * r, k))
    }

    private fun out(x: Double) = 1 - (1 - x).pow(3)
    private fun inOut(x: Double) = if (x < 0.5) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2
    private fun back(x: Double) = 1 + 2.70158 * (x - 1).pow(3) + 1.70158 * (x - 1).pow(2)

    private val nub = Path().apply {
        moveTo(286f, 102f); arcTo(Rect(286f, 72f, 346f, 132f), 180f, 90f, false)
        lineTo(324f, 72f); arcTo(Rect(294f, 72f, 354f, 132f), 270f, 90f, false); close()
    }
    private val screen = Path().apply { addRoundRect(RoundRect(100f, 120f, 540f, 450f, CornerRadius(34f, 38f))) }
    private val screenFill = Brush.radialGradient(0f to hex("#3d3027"), 0.58f to hex("#1d1713"), 1f to hex("#110d0b"), center = Offset(240.8f, 192.6f), radius = 520f)
    private val screenGlow = Brush.radialGradient(listOf(amber.copy(alpha = 0.18f), amber.copy(alpha = 0f)), center = Offset(320f, 285f), radius = 240f)
    private val scan = Color.Black.copy(alpha = 0.22f)
    private val head = Path().apply { moveTo(256f, 198f); lineTo(324f, 154f); lineTo(324f, 242f); close() }.asAndroidPath()
    private val play = Path().apply { moveTo(294f, 248f); lineTo(358f, 285f); lineTo(294f, 322f); close() }
    // Shadow layers on shapes need API 28 on a hardware canvas; older phones draw the logo without them.
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink.toArgb(); setShadowLayer(skiaRadius(30f), 0f, 30f, Color.Black.copy(alpha = 0.55f).toArgb()) }
    private val glow = amber.copy(alpha = 0.75f).toArgb()
    private val arrowRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = amber.toArgb(); style = Paint.Style.STROKE; strokeWidth = 37f; setShadowLayer(skiaRadius(8f), 0f, 0f, glow) }
    private val arrowHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = amber.toArgb(); setShadowLayer(skiaRadius(8f), 0f, 0f, glow) }

    fun DrawScope.draw(p: Pose) {
        ear(p.a); ear(p.b)
        withTransform({ scale(1f, p.sy.toFloat(), Offset(320f, 476f)) }) {
            drawPath(nub, ink)
            drawIntoCanvas { it.nativeCanvas.drawRoundRect(74f, 94f, 566f, 476f, 58f, 62f, body) }
            clipPath(screen) {
                drawRect(screenFill, Offset(100f, 120f), Size(440f, 330f))
                drawRect(screenGlow, Offset(100f, 120f), Size(440f, 330f))
                var y = 447f
                while (y > 120f) { drawRect(scan, Offset(100f, y), Size(440f, 3f)); y -= 7f }
            }
            rotate(p.arrow.toFloat(), Offset(320f, 285f)) {
                drawIntoCanvas { it.nativeCanvas.drawArc(232.5f, 197.5f, 407.5f, 372.5f, -90f, 270f, false, arrowRing); it.nativeCanvas.drawPath(head, arrowHead) }
            }
            drawPath(play, ink)
        }
    }

    /** A 120-long stem that bends into an arc of curvature k, with a knob at the tip. */
    private fun DrawScope.ear(e: Ear) {
        val path = Path()
        val tx: Double; val ty: Double
        if (abs(e.k) < 1e-4) {
            tx = e.bx + LEN * cos(e.th); ty = e.by + LEN * sin(e.th)
            path.moveTo(e.bx.toFloat(), e.by.toFloat()); path.lineTo(tx.toFloat(), ty.toFloat())
        } else {
            val r = 1 / e.k; val a0 = e.th - PI / 2; val a1 = a0 + e.k * LEN
            val cx = e.bx - r * sin(e.th); val cy = e.by + r * cos(e.th)
            path.arcTo(Rect(Offset(cx.toFloat(), cy.toFloat()), r.toFloat()), Math.toDegrees(a0).toFloat(), Math.toDegrees(e.k * LEN).toFloat(), true)
            tx = cx + r * cos(a1); ty = cy + r * sin(a1)
        }
        drawPath(path, stem, style = Stroke(8f, cap = StrokeCap.Round))
        drawCircle(ink, 10f, Offset(tx.toFloat(), ty.toFloat()))
    }
}
