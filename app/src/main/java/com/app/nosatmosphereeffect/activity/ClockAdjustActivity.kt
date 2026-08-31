package com.app.nosatmosphereeffect.activity

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.app.nosatmosphereeffect.helper.AtmosphereClockPolicy
import com.app.nosatmosphereeffect.helper.CanvasSubjectSettings
import com.app.nosatmosphereeffect.helper.GlassEffectPolicy
import com.app.nosatmosphereeffect.image.BitmapDecoder
import com.app.nosatmosphereeffect.ui.preview.EffectPreviewService
import com.app.nosatmosphereeffect.ui.preview.EffectPreviewSettingsMode
import com.app.nosatmosphereeffect.ui.theme.AtmoEngineTheme
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Full-screen calibration for the Atmosphere depth-clock overlay — pinch to
 * resize, drag to reposition, against a live preview of the person's actual
 * applied wallpaper. Modeled on the app's crop screen rather than a settings
 * card, since the whole point is judging placement against the real photo
 * edge-to-edge.
 *
 * There is no public API to read a device's real lock-screen clock position
 * (see AtmosphereClockPolicy/AtmosphereRenderer for the longer version of
 * why), so this is the substitute: let the person eyeball it, live.
 *
 * Only reachable from Advanced Settings when the clock toggle is on for the
 * original Atmosphere effect (see AdvancedSettingsScreen).
 */
class ClockAdjustActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AtmoEngineTheme {
                ClockAdjustScreen(onDone = { finish() })
            }
        }
    }
}

@Composable
private fun ClockAdjustScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    }

    var centerX by remember {
        mutableFloatStateOf(
            prefs.getFloat(
                AtmosphereClockPolicy.CENTER_X_KEY,
                AtmosphereClockPolicy.DEFAULT_CENTER_X
            )
        )
    }
    var top by remember {
        mutableFloatStateOf(
            prefs.getFloat(AtmosphereClockPolicy.TOP_KEY, AtmosphereClockPolicy.DEFAULT_TOP)
        )
    }
    var heightFraction by remember {
        mutableFloatStateOf(
            prefs.getFloat(
                AtmosphereClockPolicy.HEIGHT_KEY,
                AtmosphereClockPolicy.DEFAULT_HEIGHT
            )
        )
    }

    // The live preview instance, once created — used to push geometry
    // straight to the running renderer on every gesture tick, so dragging
    // feels immediate instead of waiting on a debounce + rebuild.
    var activePreview by remember { mutableStateOf<EffectPreviewService?>(null) }

    fun pushLive() {
        activePreview?.setAtmosphereClockGeometry(centerX, top, heightFraction)
    }

    var wallpaperBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var wallpaperLoadFinished by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        wallpaperBitmap = withContext(Dispatchers.IO) {
            loadCurrentWallpaperBitmap(context)
        }
        wallpaperLoadFinished = true
    }

    val subjectMaskDrivingKeyEnabled = remember(prefs) {
        // For Atmosphere, subject-mask computation (which the clock's depth
        // occlusion depends on) is driven by "background only", not a
        // separate general subject-separation switch.
        prefs.getBoolean(GlassEffectPolicy.BACKGROUND_ONLY_KEY, false)
    }
    val subjectModelReady = remember(prefs) {
        prefs.getBoolean(CanvasSubjectSettings.MODEL_READY_KEY, false)
    }

    fun persist() {
        centerX = AtmosphereClockPolicy.sanitizeCenterX(centerX)
        top = AtmosphereClockPolicy.sanitizeTop(top)
        heightFraction = AtmosphereClockPolicy.sanitizeHeight(heightFraction)
        prefs.edit {
            putFloat(AtmosphereClockPolicy.CENTER_X_KEY, centerX)
            putFloat(AtmosphereClockPolicy.TOP_KEY, top)
            putFloat(AtmosphereClockPolicy.HEIGHT_KEY, heightFraction)
        }
        val update = Intent("com.app.nosatmosphereeffect.UPDATE_CONFIG")
        update.setPackage(context.packageName)
        context.sendBroadcast(update)
    }

    // The on-screen preview updates instantly via pushLive() on every
    // gesture tick; this debounce only covers writing to prefs + telling
    // the real wallpaper, so we're not hammering SharedPreferences/sending
    // a broadcast on every pixel of movement.
    LaunchedEffect(centerX, top, heightFraction) {
        delay(400)
        persist()
    }

    var containerSizePx by remember { mutableStateOf(IntSize.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSizePx = it }
    ) {
        if (wallpaperLoadFinished) {
            ClockCalibrationPreview(
                wallpaper = wallpaperBitmap,
                onPreviewCreated = { activePreview = it },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        if (containerSizePx.width > 0 && containerSizePx.height > 0) {
            val containerWidthPx = containerSizePx.width.toFloat()
            val containerHeightPx = containerSizePx.height.toFloat()
            val handleAspect = 3.2f
            val handleHeightPx = heightFraction * containerHeightPx
            val handleWidthPx = handleHeightPx * handleAspect
            val handleXPx = centerX * containerWidthPx - handleWidthPx / 2f
            val handleYPx = top * containerHeightPx

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(containerWidthPx, containerHeightPx) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            centerX = (centerX + pan.x / containerWidthPx)
                                .coerceIn(0.05f, 0.95f)
                            top = (top + pan.y / containerHeightPx)
                                .coerceIn(0.02f, 0.85f)
                            if (zoom != 1f) {
                                heightFraction = AtmosphereClockPolicy.sanitizeHeight(
                                    heightFraction * zoom
                                )
                            }
                            pushLive()
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(handleXPx.roundToInt(), handleYPx.roundToInt())
                        }
                        .size(
                            with(LocalDensity.current) { handleWidthPx.toDp() },
                            with(LocalDensity.current) { handleHeightPx.toDp() }
                        )
                        .clip(RoundedCornerShape(8.dp))
                        .border(2.dp, Color.Yellow, RoundedCornerShape(8.dp))
                )
            }
        }

        // Top scrim bar, overlaid rather than pushing the image down —
        // matches the crop screen's full-bleed feel.
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .background(Color.Black.copy(alpha = 0.35f))
                .align(Alignment.TopStart)
        ) {
            Box(Modifier.fillMaxWidth().padding(4.dp)) {
                IconButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Done",
                        tint = Color.White
                    )
                }
                Text(
                    "Pinch to resize, drag to move",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center)
                )
                IconButton(
                    onClick = {
                        centerX = AtmosphereClockPolicy.DEFAULT_CENTER_X
                        top = AtmosphereClockPolicy.DEFAULT_TOP
                        heightFraction = AtmosphereClockPolicy.DEFAULT_HEIGHT
                        pushLive()
                    },
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Reset", tint = Color.White)
                }
            }
        }

        // Bottom hint strip — only shown when it explains something the
        // person can't see for themselves (no wallpaper yet, depth effect
        // won't show yet).
        val hints = buildList {
            if (wallpaperLoadFinished && wallpaperBitmap == null) {
                add("No wallpaper set yet — showing a sample photo.")
            }
            if (!subjectMaskDrivingKeyEnabled) {
                add(
                    "\"Background only\" is off for the Glass effect, so " +
                        "nothing will occlude the clock — turn it on in " +
                        "Advanced Settings to see the depth effect. Note: " +
                        "this is a shared setting also used by the Glass " +
                        "effect itself, so check that toggling it doesn't " +
                        "cause problems there on your device first."
                )
            } else if (!subjectModelReady) {
                add(
                    "The subject-detection model isn't ready on this " +
                        "device yet, so occlusion may not show here until " +
                        "it finishes downloading."
                )
            }
        }
        if (hints.isNotEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(16.dp)
            ) {
                Text(
                    hints.joinToString("\n\n"),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * A minimal live preview of the Atmosphere effect, forced onto the GLES
 * backend (the only one the clock overlay is implemented on) regardless of
 * the device's normal Vulkan/OpenGL ES preference — see EffectPreviewService's
 * forceOpenGlEs param. Reports the created instance back via
 * [onPreviewCreated] so the caller can push live geometry updates to it.
 */
@Composable
private fun ClockCalibrationPreview(
    wallpaper: Bitmap?,
    onPreviewCreated: (EffectPreviewService) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preview = remember(wallpaper) {
        EffectPreviewService(
            context = context,
            effectId = "ORIGINAL",
            source = wallpaper,
            cornerRadiusPx = 0f,
            settingsMode = EffectPreviewSettingsMode.SAVED_ACTIVE,
            forceOpenGlEs = true
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
        preview.setAppliedState(effectApplied = false) // lock-screen endpoint
        onPreviewCreated(preview)

        onDispose {
            lifecycle.removeObserver(observer)
            preview.release()
        }
    }

    AndroidView(factory = { preview.view }, modifier = modifier)
}

/**
 * Loads the same "currently applied wallpaper" file MainActivity previews
 * elsewhere in the app (files/wallpaper.jpg). Returns null if none is set
 * yet or it can't be decoded — EffectPreviewService falls back to its own
 * demo photo in that case.
 */
private suspend fun loadCurrentWallpaperBitmap(context: Context): Bitmap? {
    val file = File(context.filesDir, "wallpaper.jpg")
    if (!file.exists()) return null
    return try {
        BitmapDecoder.decodePreview(file)
    } catch (failure: java.io.IOException) {
        null
    } catch (failure: RuntimeException) {
        null
    }
}
