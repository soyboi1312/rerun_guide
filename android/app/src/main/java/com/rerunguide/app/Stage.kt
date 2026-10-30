package com.rerunguide.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** The Apple TV app lays everything out on the handoff's 1920×1080 artboard in points. Inside [Stage] one dp (and one
 *  sp: font scale is pinned to 1) is one artboard point, scaled to fit the screen and letterboxed, so every number
 *  from the Swift views carries over unchanged: `.at(80, 72, w = 600, h = 450)` means exactly what it means there. */
const val STAGE_W = 1920f
const val STAGE_H = 1080f

/** The stage's width: 1920, or wider on a phone wider than 16:9 (RootView: the guide, overlays and toast fill it; every
 *  other screen keeps its 1920 layout, centered). The Swift `@Environment(\.stageWidth)`. */
val LocalStageWidth = staticCompositionLocalOf { STAGE_W }

/** Where the stage goes in the window (RootView.fit in the Swift app): `width` in stage points, `scale` window px per
 *  stage point, `cx`/`cy` its center in window px. The safe area is the window minus the display cutout (the bars are
 *  hidden). Wider than 16:9 (a phone): the full height and as wide as the safe area, plus up to 56 of the guide's
 *  80-point side margins in the side insets (only background there), so its content never goes under the cutout.
 *  Narrower (tablet): 1920 wide, letterboxed inside the safe area. TV draws 1:1 on the whole screen. */
private class Fit(val width: Float, val scale: Float, val cx: Float, val cy: Float)

private fun fit(w: Float, h: Float, l: Float, t: Float, r: Float, b: Float): Fit {
    val sw = w - l - r
    val sh = h - t - b
    if (h <= 0 || sw / h <= STAGE_W / STAGE_H)
        return Fit(STAGE_W, maxOf(0.01f, minOf(sw / STAGE_W, sh / STAGE_H)), l + sw / 2, t + sh / 2)
    val s = h / STAGE_H
    val spill = minOf(56f, minOf(l, r) / s)
    return Fit(sw / s + 2 * spill, s, l + sw / 2, h / 2)
}

/** Scales the stage into the window. `window` is applied to a window-sized layer under the stage, given the stage's
 *  scale (window px per stage point): RootView's drags and wheel scrolls live there, so they work over the margins too
 *  (Swift adds its pan recognizers to the window).
 *  `videoTop`: portrait (Portrait*.kt) keeps the stage only for the player, full width at 16:9 this far below the safe
 *  area's top, in Now playing's picture box. */
@Composable
fun Stage(background: Color, window: (pxPerPoint: Float) -> Modifier = { Modifier }, videoTop: Float? = null, content: @Composable (stageWidth: Float) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val outer = LocalDensity.current
        val wPx = with(outer) { maxWidth.toPx() }
        val hPx = with(outer) { maxHeight.toPx() }
        val cut = WindowInsets.displayCutout
        val top = WindowInsets.safeDrawing.getTop(outer)
        val f = if (videoTop != null) (wPx / STAGE_W).let { s -> Fit(STAGE_W, s, wPx / 2, top + videoTop * outer.density + STAGE_H * s / 2) }
            else if (Platform.isTV) fit(wPx, hPx, 0f, 0f, 0f, 0f)
            else fit(wPx, hPx, cut.getLeft(outer, LayoutDirection.Ltr).toFloat(), cut.getTop(outer).toFloat(),
                cut.getRight(outer, LayoutDirection.Ltr).toFloat(), cut.getBottom(outer).toFloat())
        // The margins' color only where there are margins: on TV the stage covers the screen and paints its own.
        val covers = f.width * f.scale >= wPx - 1 && STAGE_H * f.scale >= hPx - 1
        Box(Modifier.fillMaxSize().then(if (covers) Modifier else Modifier.background(background)).then(window(f.scale)),
            contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalDensity provides Density(density = f.scale, fontScale = 1f), LocalStageWidth provides f.width) {
                Box(Modifier.offset { IntOffset((f.cx - wPx / 2).roundToInt(), (f.cy - hPx / 2).roundToInt()) }
                    .requiredSize(f.width.dp, STAGE_H.dp)) { content(f.width) }
            }
        }
    }
}

/** Absolute placement on the stage, top-left (x, y) with size (w, h): the Swift `.at(_:_:w:h:)`. */
fun Modifier.at(x: Number, y: Number, w: Number, h: Number): Modifier =
    this.offset(x.toFloat().dp, y.toFloat().dp).size(w.toFloat().dp, h.toFloat().dp)
