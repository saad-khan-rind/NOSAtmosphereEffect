package com.app.nosatmosphereeffect.ui.theme

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable

/**
 * M3 Expressive motion tokens (spatial springs bounce, effect springs don't),
 * falling back to the calmer standard scheme when expressive is off.
 * shortcut: mirrors MotionScheme, which material3 1.4 keeps internal; switch to
 * MaterialTheme.motionScheme once it is public.
 */
object AtmoMotion {
    @Composable
    fun <T> fastSpatial(): SpringSpec<T> =
        if (LocalAtmoExpressive.current) spring(0.6f, 800f) else spring(0.9f, 1400f)

    @Composable
    fun <T> defaultSpatial(): SpringSpec<T> =
        if (LocalAtmoExpressive.current) spring(0.8f, 380f) else spring(0.9f, 700f)

    @Composable
    fun <T> slowSpatial(): SpringSpec<T> =
        if (LocalAtmoExpressive.current) spring(0.8f, 200f) else spring(0.9f, 300f)

    fun <T> defaultEffects(): SpringSpec<T> = spring(1f, 1600f)
}
