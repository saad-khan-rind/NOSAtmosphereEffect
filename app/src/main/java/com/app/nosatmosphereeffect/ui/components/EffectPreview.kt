package com.app.nosatmosphereeffect.ui.components

import android.content.SharedPreferences
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.helper.AlwaysAppliedTarget
import com.app.nosatmosphereeffect.helper.AtmosphereGlassPolicy
import com.app.nosatmosphereeffect.helper.CanvasSubjectSettings
import com.app.nosatmosphereeffect.helper.GlassEffectPolicy
import com.app.nosatmosphereeffect.helper.SubjectIsolationPolicy
import com.app.nosatmosphereeffect.helper.WallpaperBehaviorPolicy
import com.app.nosatmosphereeffect.helper.WallpaperBehaviorPreferences
import com.app.nosatmosphereeffect.helper.WallpaperBehaviorSettings
import com.app.nosatmosphereeffect.renderer.backend.GraphicsBackendPreferences
import com.app.nosatmosphereeffect.ui.model.EffectCatalog
import com.app.nosatmosphereeffect.ui.preview.EffectPreviewService
import com.app.nosatmosphereeffect.ui.preview.EffectPreviewSettingsMode
import com.app.nosatmosphereeffect.ui.theme.LocalAtmoExpressive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun WallpaperTransitionPreview(
    effectId: String,
    wallpaper: ImageBitmap?,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    showDeviceChrome: Boolean = true,
    settingsMode: EffectPreviewSettingsMode = EffectPreviewSettingsMode.SAVED_ACTIVE,
    atmosphereGlassEnabledOverride: Boolean? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember(context) {
        context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
    }
    val behaviorPreferences = remember(context) {
        context.getSharedPreferences(
            WallpaperBehaviorPreferences.PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        )
    }
    val rendererPreferences = remember(context) {
        context.getSharedPreferences(
            GraphicsBackendPreferences.PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        )
    }
    var duration by remember(effectId, settingsMode) { mutableIntStateOf(
        EffectPreviewService.durationMillis(context, effectId, settingsMode)
    ) }
    var behaviorVersion by remember(effectId, settingsMode) { mutableIntStateOf(0) }
    var rendererVersion by remember(effectId, settingsMode) { mutableIntStateOf(0) }
    var configurationVersion by remember(
        effectId,
        wallpaper,
        settingsMode,
        atmosphereGlassEnabledOverride
    ) {
        mutableIntStateOf(0)
    }
    val automaticProgress = remember(
        effectId,
        wallpaper,
        settingsMode,
        atmosphereGlassEnabledOverride
    ) {
        Animatable(0f)
    }
    val behaviorSettings = remember(settingsMode, behaviorVersion) {
        if (settingsMode == EffectPreviewSettingsMode.SAVED_ACTIVE) {
            WallpaperBehaviorPreferences.read(context)
        } else {
            WallpaperBehaviorSettings()
        }
    }
    val fixedMode = progress == null &&
        settingsMode == EffectPreviewSettingsMode.SAVED_ACTIVE &&
        !behaviorSettings.transitionsEnabled
    val fixedPreviewProgress = when (behaviorSettings.alwaysAppliedTarget) {
        AlwaysAppliedTarget.LOCK -> 0f
        AlwaysAppliedTarget.HOME, AlwaysAppliedTarget.BOTH -> 1f
    }

    DisposableEffect(preferences, effectId, settingsMode) {
        if (settingsMode != EffectPreviewSettingsMode.SAVED_ACTIVE) {
            return@DisposableEffect onDispose { }
        }
        val rendererKeys = setOf(
            "dim_level",
            "enable_noise",
            "noise_scale",
            "noise_strength",
            "blob_saturation",
            "blob_contrast",
            "frosted_blur_radius",
            "halftone_dot_size",
            "halftone_grayscale",
            "origin_x",
            "origin_y",
            "neon_line_width",
            "neon_sensitivity",
            GlassEffectPolicy.LINE_COUNT_KEY,
            GlassEffectPolicy.LINE_THICKNESS_KEY,
            GlassEffectPolicy.TRANSITION_STYLE_KEY,
            GlassEffectPolicy.BACKGROUND_ONLY_KEY,
            AtmosphereGlassPolicy.ENABLED_KEY,
            SubjectIsolationPolicy.HALFTONE_BACKGROUND_ONLY_KEY,
            CanvasSubjectSettings.ENABLED_KEY
        )
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "anim_duration") {
                duration = EffectPreviewService.durationMillis(context, effectId, settingsMode)
            }
            if (key == null || key in rendererKeys) configurationVersion++
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    DisposableEffect(behaviorPreferences, effectId, settingsMode) {
        if (settingsMode != EffectPreviewSettingsMode.SAVED_ACTIVE) {
            return@DisposableEffect onDispose { }
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null ||
                key == WallpaperBehaviorPolicy.TRANSITIONS_ENABLED_KEY ||
                key == WallpaperBehaviorPolicy.ALWAYS_APPLIED_TARGET_KEY
            ) {
                behaviorVersion++
            }
        }
        behaviorPreferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            behaviorPreferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    DisposableEffect(rendererPreferences, effectId, settingsMode) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == GraphicsBackendPreferences.PREFERENCE_KEY) {
                rendererVersion++
            }
        }
        rendererPreferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            rendererPreferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    LaunchedEffect(
        effectId,
        wallpaper,
        progress,
        duration,
        settingsMode,
        atmosphereGlassEnabledOverride,
        fixedMode,
        fixedPreviewProgress
    ) {
        if (progress != null) {
            automaticProgress.snapTo(progress.coerceIn(0f, 1f))
            return@LaunchedEffect
        }
        if (fixedMode) {
            automaticProgress.snapTo(fixedPreviewProgress)
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                automaticProgress.snapTo(0f)
                delay(420)
                automaticProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(duration, easing = LinearEasing)
                )
                delay(720)
                automaticProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(duration, easing = LinearEasing)
                )
                delay(420)
            }
        }
    }

    val shownProgress = (progress ?: automaticProgress.value).coerceIn(0f, 1f)
    val shape = RoundedCornerShape(if (LocalAtmoExpressive.current) 28.dp else 16.dp)

    val previewLabel = stringResource(
        if (fixedMode) R.string.preview_effect_always_applied else R.string.preview_effect_transition,
        stringResource(EffectCatalog.find(effectId).title)
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .semantics { contentDescription = previewLabel }
    ) {
        ProductionEffectSurface(
            effectId = effectId,
            wallpaper = wallpaper,
            progress = shownProgress,
            fixedEffectApplied = fixedMode,
            configurationVersion = configurationVersion,
            rendererVersion = rendererVersion,
            settingsMode = settingsMode,
            atmosphereGlassEnabledOverride =
                atmosphereGlassEnabledOverride,
            modifier = Modifier.fillMaxSize()
        )

        if (showDeviceChrome) PreviewChrome(shownProgress)
    }
}

@Composable
private fun ProductionEffectSurface(
    effectId: String,
    wallpaper: ImageBitmap?,
    progress: Float,
    fixedEffectApplied: Boolean,
    configurationVersion: Int,
    rendererVersion: Int,
    settingsMode: EffectPreviewSettingsMode,
    atmosphereGlassEnabledOverride: Boolean?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val expressive = LocalAtmoExpressive.current
    val radiusPx = with(LocalDensity.current) {
        (if (expressive) 28.dp else 16.dp).toPx()
    }
    val source = remember(wallpaper) { wallpaper?.asAndroidBitmap() }
    val preview = remember(
        effectId,
        wallpaper,
        configurationVersion,
        rendererVersion,
        expressive,
        settingsMode,
        atmosphereGlassEnabledOverride
    ) {
        EffectPreviewService(
            context = context,
            effectId = effectId,
            source = source,
            cornerRadiusPx = radiusPx,
            settingsMode = settingsMode,
            atmosphereGlassEnabledOverride =
                atmosphereGlassEnabledOverride
        )
    }

    DisposableEffect(preview, lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> preview.resume()
                Lifecycle.Event.ON_PAUSE -> preview.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) preview.resume()

        onDispose {
            lifecycle.removeObserver(observer)
            preview.release()
        }
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        key(preview) {
            AndroidView(
                factory = { preview.view },
                update = {
                    if (fixedEffectApplied) {
                        preview.setAppliedState(effectApplied = true)
                    } else {
                        preview.setProgress(progress)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun PreviewChrome(progress: Float) {
    val lockAlpha = (1f - progress * 1.7f).coerceIn(0f, 1f)
    val homeAlpha = ((progress - 0.35f) * 1.55f).coerceIn(0f, 1f)

    Box(Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            text = "10:09",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .alpha(lockAlpha)
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .alpha(homeAlpha),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val colors = listOf(
                Color(0xFF70D4C0),
                Color(0xFFF2C26B),
                Color(0xFFD98278),
                Color(0xFF79B890)
            )
            colors.forEachIndexed { index, color ->
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(if (index == 2) RoundedCornerShape(7.dp) else CircleShape)
                        .background(color.copy(alpha = 0.94f))
                )
            }
        }

        AnimatedContent(
            targetState = progress >= 0.5f,
            modifier = Modifier.align(Alignment.TopEnd),
            label = "previewModeIcon"
        ) { home ->
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.42f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (home) Icons.Rounded.Home else Icons.Rounded.Lock,
                    contentDescription = stringResource(if (home) R.string.common_home_screen else R.string.common_lock_screen),
                    tint = Color.White,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}
