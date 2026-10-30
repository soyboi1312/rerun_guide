package com.rerunguide.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

/** The system spinner as tvOS draws it: 64 pt (times `scale`), tinted, centered in whatever frame `modifier` gives. */
@Composable
fun Spinner(tint: Color, modifier: Modifier = Modifier, scale: Float = 1f) {
    val turn = rememberInfiniteTransition(label = "spinner")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "turn")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size((64 * scale).dp)) {
            val w = size.minDimension * 0.1f
            rotate(turn.value) {   // read while drawing: only the canvas redraws
                drawArc(tint, startAngle = 0f, sweepAngle = 270f, useCenter = false,
                    topLeft = Offset(w / 2, w / 2), size = size.copy(size.width - w, size.height - w),
                    style = Stroke(w, cap = StrokeCap.Round))
            }
        }
    }
}
