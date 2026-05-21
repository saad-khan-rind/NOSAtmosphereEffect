package com.app.nosatmosphereeffect.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand colors matching colors.xml
val BrandPrimary       = Color(0xFFD0BCFF)
val BrandOnPrimary     = Color(0xFF381E72)
val BrandPrimaryContainer = Color(0xFF4F378B)
val FixedBackground    = Color(0xFF000000)
val FixedSurface       = Color(0xFF1C1C1C)
val FixedSurfaceVariant = Color(0xFF222222)
val FixedOnSurface     = Color(0xFFFFFFFF)
val FixedOnSurfaceVariant = Color(0xB3FFFFFF)
val ErrorColor         = Color(0xFFFF4444)

private val AtmoDarkColors = darkColorScheme(
    primary            = BrandPrimary,
    onPrimary          = BrandOnPrimary,
    primaryContainer   = BrandPrimaryContainer,
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary          = Color(0xFFCCC2DC),
    onSecondary        = Color(0xFF332D41),
    background         = FixedBackground,
    onBackground       = FixedOnSurface,
    surface            = FixedSurface,
    onSurface          = FixedOnSurface,
    surfaceVariant     = FixedSurfaceVariant,
    onSurfaceVariant   = FixedOnSurfaceVariant,
    outline            = Color(0xFF444444),
    error              = ErrorColor,
    onError            = Color(0xFF690005),
)

@Composable
fun AtmoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AtmoDarkColors,
        content = content
    )
}