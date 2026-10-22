package com.theveloper.pixelplay.presentation.components.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The live spectrum drawn under the player's seek bar.
 *
 * The values arrive on a [androidx.compose.runtime.State] produced by the audio processor chain and
 * are read *inside the draw lambda* rather than in composition, so a new frame only re-executes the
 * drawing of these bars. The rest of the player never recomposes because music is playing.
 *
 * @param bands accessor for the current band levels in 0..1, lowest frequency first
 * @param isPlaying fades the whole thing out when playback stops instead of leaving a frozen frame
 */
@Composable
fun SpectrumBars(
    bands: () -> FloatArray,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 30.dp,
) {
    val visibility by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
        label = "SpectrumBarsVisibility"
    )
    // Nothing to draw while paused and faded out: the modifier is dropped entirely so an idle
    // player costs no drawing work at all.
    if (visibility <= 0.002f) return

    val brush = remember(color) {
        Brush.verticalGradient(
            0.0f to color.copy(alpha = 0.90f),
            1.0f to color.copy(alpha = 0.28f),
        )
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { alpha = visibility }
    ) {
        val levels = bands()
        val count = levels.size
        if (count == 0) return@Canvas

        val slot = size.width / count
        val barWidth = slot * 0.46f
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)

        for (index in 0 until count) {
            val barHeight = levels[index].coerceIn(0f, 1f) * size.height
            if (barHeight < 1f) continue
            drawRoundRect(
                brush = brush,
                topLeft = Offset(
                    x = index * slot + (slot - barWidth) / 2f,
                    y = size.height - barHeight,
                ),
                size = Size(barWidth, barHeight),
                cornerRadius = radius,
            )
        }
    }
}
