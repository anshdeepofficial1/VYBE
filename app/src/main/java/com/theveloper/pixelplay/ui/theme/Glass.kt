package com.theveloper.pixelplay.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * True while the blurred, app-wide artwork backdrop is behind the UI (Gesture Blur once the player
 * is open, Immersive Artwork and Liquid Glass permanently).
 *
 * Panels use this to switch from flat opaque blocks to frosted glass: a translucent fill over the
 * already-blurred cover, a faint light sheen across the top, a hairline highlight on the edge and a
 * soft shadow underneath. That combination is what makes a translucent panel read as depth instead
 * of as a washed-out rectangle.
 */
val LocalImmersiveGlass = compositionLocalOf { false }

/** Opacity used by [glassPanel] when a caller does not pass its own alpha. */
private const val DefaultPanelAlpha = 0.58f

/**
 * Everything the Theme settings can change about the glass treatment, so a panel never hard-codes
 * a look the user has asked to change elsewhere.
 *
 * [liquid] is set by the Liquid Glass theme and cannot be reached from the sliders; it swaps the
 * quiet frosted treatment for Apple-style liquid glass: thinner fill, a stronger specular sweep
 * and a bright light rim.
 */
data class GlassControls(
    val panelAlpha: Float = DefaultPanelAlpha,
    val sheen: Boolean = true,
    val edgeLight: Boolean = true,
    val shadow: Boolean = true,
    val liquid: Boolean = false,
)

val LocalGlassControls = compositionLocalOf { GlassControls() }

/**
 * Frosted-glass treatment for a panel. No-op unless an artwork backdrop is active, so every call
 * site can opt in unconditionally without changing the normal theme.
 *
 * Callers must hand [Color.Transparent] to the panel's own `containerColor` while glass is active,
 * otherwise the opaque panel background is drawn on top of this treatment.
 *
 * An explicit [alpha] is treated as the value for the default opacity and scaled by the user's
 * "Panel opacity" slider, so raising the slider makes every panel more solid and lowering it makes
 * every panel more see-through in one move.
 */
@Composable
fun Modifier.glassPanel(
    shape: Shape = RoundedCornerShape(20.dp),
    tint: Color = MaterialTheme.colorScheme.surfaceContainer,
    alpha: Float = DefaultPanelAlpha,
    elevation: Dp = 10.dp,
    enabled: Boolean = LocalImmersiveGlass.current,
): Modifier {
    if (!enabled) return this
    val controls = LocalGlassControls.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.4f

    // 1.0 at the shipped default, so existing call sites keep their exact look until moved.
    val opacityScale = (controls.panelAlpha / DefaultPanelAlpha).coerceIn(0.45f, 1.75f)
    val liquid = controls.liquid
    // Liquid glass is thinner than frosted glass; the rim and the sweep do the work instead.
    val base = tint.copy(alpha = (if (liquid) alpha * 0.74f else alpha) * opacityScale)

    // Light mode needs a brighter sheen to read as glass against light covers; dark mode only a
    // whisper, otherwise it looks grey and dirty. Liquid glass is deliberately louder in both.
    val sheenAlpha = when {
        liquid && dark -> 0.17f
        liquid -> 0.44f
        dark -> 0.07f
        else -> 0.34f
    }
    val edgeAlpha = when {
        liquid && dark -> 0.32f
        liquid -> 0.66f
        dark -> 0.10f
        else -> 0.55f
    }
    val shadowColor = if (dark) Color.Black.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.22f)
    val elevationUsed = if (liquid) elevation * 1.25f else elevation

    var modifier = this
    if (controls.shadow) {
        modifier = modifier.shadow(
            elevation = elevationUsed,
            shape = shape,
            clip = false,
            ambientColor = shadowColor,
            spotColor = shadowColor,
        )
    }
    modifier = modifier.clip(shape).background(base, shape)

    if (controls.sheen) {
        modifier = modifier.background(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = sheenAlpha),
                0.42f to Color.Transparent,
                1f to Color.Transparent,
            ),
            shape,
        )
    }
    if (liquid) {
        // The diagonal sweep is what reads as liquid rather than as frosted plastic: light enters
        // at the top-left corner and falls away toward the bottom-right, the way a curved piece of
        // glass catches a highlight.
        modifier = modifier.background(
            Brush.linearGradient(
                0f to Color.White.copy(alpha = if (dark) 0.16f else 0.34f),
                0.34f to Color.Transparent,
                0.82f to Color.Transparent,
                1f to Color.White.copy(alpha = if (dark) 0.07f else 0.16f),
            ),
            shape,
        )
    }
    if (controls.edgeLight) {
        modifier = modifier.border(1.dp, Color.White.copy(alpha = edgeAlpha), shape)
    }
    return modifier
}
