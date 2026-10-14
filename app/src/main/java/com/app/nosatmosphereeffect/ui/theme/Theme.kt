package com.app.nosatmosphereeffect.ui.theme

import android.app.Activity
import android.os.SystemClock
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val AtmoDarkColors = darkColorScheme(
    primary = AtmoMint,
    onPrimary = OnAtmoMint,
    primaryContainer = AtmoMintContainer,
    onPrimaryContainer = AtmoWhite,

    secondary = AtmoAmber,
    onSecondary = OnAtmoAmber,
    secondaryContainer = AtmoAmberContainer,
    onSecondaryContainer = AtmoWhite,

    tertiary = AtmoPurple,
    onTertiary = OnAtmoPurple,
    tertiaryContainer = AtmoPurpleContainer,
    onTertiaryContainer = AtmoWhite,

    background = AtmoBlack,
    onBackground = AtmoOnSurface,

    surface = AtmoSurface,
    onSurface = AtmoOnSurface,
    surfaceVariant = AtmoSurfaceContainerHigh,
    onSurfaceVariant = AtmoOnSurfaceVariant,

    surfaceContainerLowest = AtmoBlack,
    surfaceContainerLow = AtmoSurfaceContainerLow,
    surfaceContainer = AtmoSurfaceContainer,
    surfaceContainerHigh = AtmoSurfaceContainerHigh,
    surfaceContainerHighest = AtmoSurfaceContainerHighest,

    outline = AtmoOutlineStrong,
    outlineVariant = AtmoOutline,

    error = AtmoError,
    onError = AtmoWhite
)

private val AtmoLightColors = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF006B5B),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF8EF8DD),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF00201A),

    secondary = androidx.compose.ui.graphics.Color(0xFF735B00),
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFFFE17B),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF241A00),

    tertiary = androidx.compose.ui.graphics.Color(0xFF6A4E9B),
    onTertiary = androidx.compose.ui.graphics.Color.White,
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFFEBDDFF),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFF24123F),

    background = androidx.compose.ui.graphics.Color(0xFFF8FAF8),
    onBackground = androidx.compose.ui.graphics.Color(0xFF191C1B),
    surface = androidx.compose.ui.graphics.Color(0xFFF8FAF8),
    onSurface = androidx.compose.ui.graphics.Color(0xFF191C1B),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFDBE5E1),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF3F4946),

    surfaceContainerLowest = androidx.compose.ui.graphics.Color.White,
    surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFFF2F4F2),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFFECEFED),
    surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFFE6E9E7),
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFFE0E3E1),

    outline = androidx.compose.ui.graphics.Color(0xFF6F7976),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFFBFC9C5),
    error = androidx.compose.ui.graphics.Color(0xFFBA1A1A),
    onError = androidx.compose.ui.graphics.Color.White
)

private val StandardShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(14.dp),
    small = RoundedCornerShape(20.dp),
    medium = RoundedCornerShape(28.dp),
    large = RoundedCornerShape(36.dp),
    extraLarge = RoundedCornerShape(44.dp)
)

val LocalAtmoExpressive = staticCompositionLocalOf { true }

/** When this screen's content was first composed; entrance animations only play just after it. */
val LocalAtmoEntranceStart = staticCompositionLocalOf { 0L }

@Composable
fun AtmoEngineTheme(
    expressive: Boolean? = null,
    themeMode: AppThemeMode? = null,
    pitchBlack: Boolean? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val useExpressive = expressive ?: AppearancePreferences.isExpressiveEnabled(context)
    val selectedThemeMode = themeMode ?: AppearancePreferences.getThemeMode(context)
    val usePitchBlack = pitchBlack ?: AppearancePreferences.isPitchBlackEnabled(context)
    val useDarkTheme = when (selectedThemeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !useDarkTheme
            controller.isAppearanceLightNavigationBars = !useDarkTheme
        }
    }

    val systemOrFixedColors = when {
        useExpressive -> systemMaterialYouColorScheme(context, useDarkTheme)
        useDarkTheme -> AtmoDarkColors
        else -> AtmoLightColors
    }
    val colors = if (useDarkTheme && usePitchBlack) {
        systemOrFixedColors.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceDim = Color.Black,
            surfaceContainerLowest = Color.Black
        )
    } else {
        systemOrFixedColors
    }

    val entranceStart = remember { SystemClock.uptimeMillis() }
    CompositionLocalProvider(
        LocalAtmoExpressive provides useExpressive,
        LocalAtmoEntranceStart provides entranceStart
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = AtmoTypography,
            shapes = if (useExpressive) ExpressiveShapes else StandardShapes,
            content = content
        )
    }
}
