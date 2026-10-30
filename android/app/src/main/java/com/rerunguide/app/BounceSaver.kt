package com.rerunguide.app

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.rerunguide.core.Bouncer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** Attract mode's other faces: a logo drifting and bouncing the way the DVD logo used to, on the theme's background.
 *  Either the Rerun Guide TV with its wordmark, or Soyboi's mascot over "soyboi.tech". `Bouncer` (Core) owns the path
 *  and its promise of a corner at least every two minutes; a corner gets a glow. The first press wakes the guide
 *  (AppModelGuide swallows it), so there is nothing to focus here. */
object BounceSaver {
    /** Names are the `Settings.screensaver` strings; "wall" (the storefront wall) has no face here. */
    enum class Face { logo, soyboi }

    /** The bouncing box hugs the artwork plus its line of text, so corner hits look like corner hits. */
    fun box(face: Face): Pair<Double, Double> = when (face) {
        Face.logo -> 400.0 to 400.0     // the 360-wide logo image (753×693 source) plus the wordmark under it
        Face.soyboi -> 320.0 to 372.0   // the 300-square mascot plus "soyboi.tech" under it
    }
}

/** `width`: the stage's width (wider than 1920 on a phone wider than 16:9). */
@Composable
fun BounceSaver(face: BounceSaver.Face, width: Float = 1920f) {
    val t = LocalTheme.current
    val box = BounceSaver.box(face)
    val b = remember { Bouncer(stage = width.toDouble() to 1080.0, logo = box, start = 300.0 to 200.0) }   // Swift @State: first value kept
    val pos = remember { mutableStateOf(Offset(b.x.toFloat(), b.y.toFloat())) }
    val glow = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    LaunchedEffect(Unit) {
        // Swift steps 1/60 s per 60 Hz timer tick; fixed steps keep that path on any refresh rate. A stall (the app
        // away from the screen) resumes rather than fast-forwarding, as the timer does.
        var last = -1L
        var acc = 0.0
        while (true) withFrameNanos { now ->
            if (last >= 0) acc = min(acc + (now - last) / 1e9, 0.25)
            last = now
            var hit = false
            while (acc >= 1.0 / 60) { b.step(1.0 / 60); acc -= 1.0 / 60; if (b.hitCorner) hit = true }
            pos.value = Offset(b.x.toFloat(), b.y.toFloat())
            if (hit) scope.launch { glow.snapTo(1f); glow.animateTo(0f, tween(1500, easing = EaseOut)) }
        }
    }
    val w = box.first.toFloat(); val h = box.second.toFloat()
    Box(Modifier.size(width.dp, 1080.dp)) {   // on RootView's t.bg
        // Moved in its layer: the frame clock never recomposes the face.
        Box(Modifier.size(w.dp, h.dp).graphicsLayer { translationX = pos.value.x.dp.toPx(); translationY = pos.value.y.dp.toPx() }) {
            // `.shadow(color: accent × glow × 0.9, radius: 48 × glow)`: the face's silhouette in accent, blurred, underneath.
            // Blur needs RenderEffect (Android 12+); older versions skip the glow. Drawn 160 bigger each side so the blur
            // isn't cut at the box.
            if (Build.VERSION.SDK_INT >= 31) {
                Box(Modifier.requiredSize((w + 320).dp, (h + 320).dp).graphicsLayer {
                    val g = glow.value
                    alpha = g * 0.9f
                    val r = (48 * g * density - 0.5f) / 0.57735f   // SwiftUI radius = sigma; Skia radius r is sigma 0.57735·r + 0.5
                    renderEffect = if (r > 0.5f) BlurEffect(r, r, TileMode.Decal) else null
                }, contentAlignment = Alignment.Center) { FaceArt(face, w, h, t.accent) }
                FaceArt(face, w, h, null)
            } else {
                // Before RenderEffect: the face's silhouette, captured once and blurred in software, fades in accent.
                // ponytail: the blur stays at the full 48 while it fades (Swift's shrinks with it); per-frame blurs if it shows.
                val layer = rememberGraphicsLayer()
                var mask by remember { mutableStateOf<Pair<ImageBitmap, Offset>?>(null) }
                LaunchedEffect(Unit) {
                    withFrameNanos {}; withFrameNanos {}   // drawn once, so the layer holds the face
                    val img = runCatching { layer.toImageBitmap() }.getOrNull() ?: return@LaunchedEffect
                    val r = (48 * density - 0.5f) / 0.57735f
                    mask = withContext(Dispatchers.Default) {
                        val bm = img.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        blurredMask("bounce-${face.name}", bm.width, bm.height, r) { c, p -> c.drawBitmap(bm, 0f, 0f, p) }
                    }
                }
                Box(Modifier.size(w.dp, h.dp).drawBehind {
                    val m = mask ?: return@drawBehind
                    if (glow.value > 0f) drawImage(m.first, m.second, alpha = glow.value * 0.9f, colorFilter = ColorFilter.tint(t.accent))
                })
                Box(Modifier.drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }) { FaceArt(face, w, h, null) }
            }
        }
    }
}

/** The artwork over its line of text, centered in the bouncing box; `tint` paints it all one color (the glow). */
@Composable
private fun FaceArt(face: BounceSaver.Face, w: Float, h: Float, tint: Color?) {
    val t = LocalTheme.current
    val filter = tint?.let { ColorFilter.tint(it, BlendMode.SrcIn) }
    Column(Modifier.size(w.dp, h.dp), Arrangement.spacedBy(12.dp, Alignment.CenterVertically), Alignment.CenterHorizontally) {
        when (face) {
            BounceSaver.Face.logo -> {
                val p = painterResource(R.drawable.bounce_logo)
                Image(p, null, Modifier.width(360.dp).aspectRatio(p.intrinsicSize.width / max(1f, p.intrinsicSize.height)), colorFilter = filter)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).background(t.accent, CircleShape))
                    BasicText(t.heading("Rerun Guide"),
                        style = t.heading(40, FontWeight.SemiBold).copy(color = tint ?: t.ink, letterSpacing = t.headingTracking.em))
                }
            }
            BounceSaver.Face.soyboi -> {
                val p = painterResource(R.drawable.soyboi_mascot)
                Image(p, null, Modifier.width(300.dp).aspectRatio(p.intrinsicSize.width / max(1f, p.intrinsicSize.height)), colorFilter = filter)
                BasicText("soyboi.tech", style = t.body(40, FontWeight.SemiBold).copy(color = tint ?: t.ink))   // a domain: never uppercased
            }
        }
    }
}
