package com.theveloper.pixelplay.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.theveloper.pixelplay.presentation.viewmodel.ColorSchemePair
import com.theveloper.pixelplay.data.preferences.LogoMode
import androidx.core.graphics.ColorUtils

val LocalPixelPlayDarkTheme = staticCompositionLocalOf { false }
val LocalShowScrollbar = staticCompositionLocalOf { true }
val LocalVybeLogoMode = staticCompositionLocalOf { LogoMode.DYNAMIC }

@Composable
fun vybeLogoForegroundColor(): Color = when (LocalVybeLogoMode.current) {
    LogoMode.LIGHT -> Color.Black
    LogoMode.DARK -> Color.White
    else -> if (LocalPixelPlayDarkTheme.current) Color.White else Color.Black
}

@Composable
fun vybeLogoBackgroundColor(): Color = when (LocalVybeLogoMode.current) {
    LogoMode.LIGHT -> Color.White
    LogoMode.DARK -> Color.Black
    else -> if (LocalPixelPlayDarkTheme.current) Color.Black else Color.White
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Suppress("DEPRECATION")
@Composable
fun PixelPlayStatusBarStyle(
    color: Color,
    useDarkIcons: Boolean = ColorUtils.calculateLuminance(color.toArgb()) > 0.55,
    navigationColor: Color? = null,
    useDarkNavigationIcons: Boolean = navigationColor
        ?.let { ColorUtils.calculateLuminance(it.toArgb()) > 0.55 }
        ?: useDarkIcons
) {
    val view = LocalView.current
    if (view.isInEditMode) return

    val updateNavigationBar = navigationColor != null
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
        }

        WindowCompat.getInsetsController(window, view).run {
            isAppearanceLightStatusBars = useDarkIcons

            if (updateNavigationBar) {
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                isAppearanceLightNavigationBars = useDarkNavigationIcons
            }
        }
    }
}

val DarkColorScheme = darkColorScheme(
    primary = PixelPlayPurplePrimary,
    secondary = PixelPlayPink,
    tertiary = PixelPlayOrange,
    background = PixelPlayPurpleDark,
    surface = PixelPlaySurface,
    onPrimary = PixelPlayWhite,
    onSecondary = PixelPlayWhite,
    onTertiary = PixelPlayWhite,
    onBackground = PixelPlayWhite,
    onSurface = PixelPlayLightPurple, // Texto sobre superficies
    error = Color(0xFFFF5252),
    onError = PixelPlayWhite
)

val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = PixelPlayWhite,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = PixelPlayPink,
    onSecondary = PixelPlayWhite,
    secondaryContainer = PixelPlayPink.copy(alpha = 0.15f),
    onSecondaryContainer = PixelPlayPink.copy(alpha = 0.85f),
    tertiary = PixelPlayOrange,
    onTertiary = PixelPlayBlack,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutline.copy(alpha = 0.6f),
    surfaceTint = LightPrimary,
    error = Color(0xFFD32F2F),
    onError = PixelPlayWhite
)

private fun ColorScheme.asAmoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF080808),
    surfaceDim = Color.Black,
    surfaceBright = Color(0xFF161616),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF050505),
    surfaceContainer = Color(0xFF080808),
    surfaceContainerHigh = Color(0xFF0D0D0D),
    surfaceContainerHighest = Color(0xFF121212),
)

/**
 * Lets a blurred album artwork layer placed behind the whole app show through the screen
 * background only. Every elevated role (cards, sheets, bars, the bottom navigation bar) stays
 * effectively solid so panels read as real surfaces instead of see-through glass, and small text
 * never fights the artwork behind it.
 *
 * The artwork is a light source, so the two themes need opposite treatment:
 *  - Dark: a moderately transparent background keeps the blurred cover clearly visible, while the
 *    containers stay dark and solid enough to hold white text.
 *  - Light: a translucent background over a bright cover washes out and loses contrast, so the
 *    background is only slightly transparent and the containers are almost fully opaque. The
 *    backdrop is additionally pre-lightened by a stronger scrim in the activity root.
 */
private fun ColorScheme.asImmersive(
    dark: Boolean,
    liquid: Boolean = false,
    alphaShift: Float = 0f,
): ColorScheme {
    // Liquid glass carries less paint than frosted glass: the same stack with a thinner fill so
    // more of the blurred cover reads through, which is what gives the panels their depth.
    val liquidDrop = if (liquid) 0.11f else 0.0f

    // The user's "Panel opacity" slider moves the whole stack together, clamped so a panel can
    // never become invisible or turn back into a solid block.
    fun Float.glass(): Float = (this - liquidDrop + alphaShift).coerceIn(0.16f, 1f)

    return if (dark) {
        copy(
            // The blurred cover is the wallpaper, so the screen background only needs to be a thin
            // veil. Every alpha in this stack multiplies: a 0.34 veil under the root scrim and the
            // screen surface left the artwork at a few percent and it read as a flat colour, which
            // is exactly why the feature looked like it did nothing. Keep the background almost
            // clear here and let the containers carry the contrast.
            background = background.copy(alpha = if (liquid) 0.05f else 0.06f),
            surface = surface.copy(alpha = 0.62f.glass()),
            surfaceVariant = surfaceVariant.copy(alpha = 0.58f.glass()),
            surfaceDim = surfaceDim.copy(alpha = 0.52f.glass()),
            surfaceBright = surfaceBright.copy(alpha = 0.62f.glass()),
            surfaceContainerLowest = surfaceContainerLowest.copy(alpha = 0.54f.glass()),
            surfaceContainerLow = surfaceContainerLow.copy(alpha = 0.56f.glass()),
            surfaceContainer = surfaceContainer.copy(alpha = 0.58f.glass()),
            surfaceContainerHigh = surfaceContainerHigh.copy(alpha = 0.62f.glass()),
            surfaceContainerHighest = surfaceContainerHighest.copy(alpha = 0.66f.glass()),
        )
    } else {
        copy(
            // Light mode cannot be as clear as dark mode without losing text contrast, so the veil
            // is a little stronger and the root scrim does the legibility work. Still low enough
            // that the cover's colours stay visible instead of washing out to plain cream.
            background = background.copy(alpha = if (liquid) 0.08f else 0.10f),
            surface = surface.copy(alpha = 0.70f.glass()),
            surfaceVariant = surfaceVariant.copy(alpha = 0.66f.glass()),
            surfaceDim = surfaceDim.copy(alpha = 0.60f.glass()),
            surfaceBright = surfaceBright.copy(alpha = 0.74f.glass()),
            surfaceContainerLowest = surfaceContainerLowest.copy(alpha = 0.62f.glass()),
            surfaceContainerLow = surfaceContainerLow.copy(alpha = 0.64f.glass()),
            surfaceContainer = surfaceContainer.copy(alpha = 0.66f.glass()),
            surfaceContainerHigh = surfaceContainerHigh.copy(alpha = 0.70f.glass()),
            surfaceContainerHighest = surfaceContainerHighest.copy(alpha = 0.74f.glass()),
        )
    }
}

@Composable
fun PixelPlayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoledTheme: Boolean = false,
    colorSchemePairOverride: ColorSchemePair? = null,
    immersiveArtwork: Boolean = false,
    glassControls: GlassControls = GlassControls(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val finalColorScheme = when {
        amoledTheme -> (colorSchemePairOverride?.dark ?: DarkColorScheme).asAmoled()
        colorSchemePairOverride == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            // Tema dinámico del sistema como prioridad si no hay override
            try {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } catch (e: Exception) {
                // Fallback a los defaults si dynamic colors falla (raro, pero posible en algunos dispositivos)
                if (darkTheme) DarkColorScheme else LightColorScheme
            }
        }
        colorSchemePairOverride != null -> {
            // Usar el esquema del álbum si se proporciona
            if (darkTheme) colorSchemePairOverride.dark else colorSchemePairOverride.light
        }
        // Fallback final a los defaults si no hay override ni dynamic colors aplicables
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    PixelPlayStatusBarStyle(
        color = finalColorScheme.background,
        navigationColor = finalColorScheme.background
    )

    CompositionLocalProvider(
        LocalPixelPlayDarkTheme provides darkTheme,
        LocalImmersiveGlass provides immersiveArtwork,
        LocalGlassControls provides glassControls,
    ) {
        MaterialTheme(
            colorScheme = if (immersiveArtwork) {
                finalColorScheme.asImmersive(
                    dark = darkTheme,
                    liquid = glassControls.liquid,
                    // 0.58 is the shipped default, so this reads as "0 unless the user moved it".
                    alphaShift = glassControls.panelAlpha - 0.58f,
                )
            } else {
                finalColorScheme
            },
            typography = Typography,
            shapes = Shapes,
            content = content
        )
    }
}
