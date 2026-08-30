package com.app.nosatmosphereeffect.activity

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.app.nosatmosphereeffect.helper.AtmosphereClockPolicy
import com.app.nosatmosphereeffect.helper.CanvasSubjectSettings
import com.app.nosatmosphereeffect.image.BitmapDecoder
import com.app.nosatmosphereeffect.ui.components.AtmoTextButton
import com.app.nosatmosphereeffect.ui.preview.EffectPreviewService
import com.app.nosatmosphereeffect.ui.preview.EffectPreviewSettingsMode
import com.app.nosatmosphereeffect.ui.theme.AtmoEngineTheme
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lets the person drag the depth-clock overlay to the right spot and size
 * against a live (GLES-forced) preview of the Atmosphere effect, since
 * there is no public API to read a device's real lock-screen clock
 * position — see PR discussion in AtmosphereClockPolicy / AtmosphereRenderer.
 *
 * Only reachable from Advanced Settings when the clock toggle is on for
 * the original Atmosphere effect (see AdvancedSettingsScreen).
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
    Scaffold(
        topBar = {
            // Replaced TopAppBar with a standard Row to avoid ExperimentalMaterial3Api
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDone) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Done"
                    )
                }
                Text(
                    text = "Adjust clock",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                "Drag the clock to where your device hides its own lock " +
                        "screen clock, and resize it to taste. Changes apply to " +
                        "the wallpaper immediately.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ClockCalibrationCard(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ClockCalibrationCard(modifier: Modifier = Modifier) {
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
    // Bumped after every committed change (drag release / slider settle) so
    // the preview below recreates against the new saved values — same
    // recreate-on-key-change pattern WallpaperTransitionPreview uses.
    var previewVersion by remember { mutableIntStateOf(0) }

    // The person's actual applied wallpaper photo, not a generic demo image
    // — the whole point of this screen is calibrating against what they'll
    // really see, including whether a subject is detected in *their* photo.
    var wallpaperBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var wallpaperLoadFinished by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        wallpaperBitmap = withContext(Dispatchers.IO) {
            loadCurrentWallpaperBitmap(context)
        }
        wallpaperLoadFinished = true
    }

    val subjectSeparationEnabled = remember(prefs) {
        prefs.getBoolean(CanvasSubjectSettings.ENABLED_KEY, false)
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
        previewVersion++
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 19.5f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black)
        ) {
            val previewWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
            val previewHeightPx = with(LocalDensity.current) { maxHeight.toPx() }

            if (wallpaperLoadFinished) {
                key(previewVersion) {
                    ClockCalibrationPreview(
                        wallpaper = wallpaperBitmap,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            }

            // Approximate handle aspect (a real "HH:mm" glyph is roughly
            // this wide relative to its height); the live preview under it
            // renders the true glyph, this box is just the drag target.
            val handleAspect = 3.2f
            val handleHeightPx = heightFraction * previewHeightPx
            val handleWidthPx = handleHeightPx * handleAspect
            val handleXPx = centerX * previewWidthPx - handleWidthPx / 2f
            val handleYPx = top * previewHeightPx

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(handleXPx.roundToInt(), handleYPx.roundToInt())
                    }
                    .width(with(LocalDensity.current) { handleWidthPx.toDp() })
                    .height(with(LocalDensity.current) { handleHeightPx.toDp() })
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .pointerInput(previewWidthPx, previewHeightPx) {
                        detectDragGestures(
                            onDragEnd = { persist() },
                            onDragCancel = { persist() }
                        ) { change, dragAmount ->
                            change.consume()
                            centerX = (centerX + dragAmount.x / previewWidthPx)
                                .coerceIn(0.05f, 0.95f)
                            top = (top + dragAmount.y / previewHeightPx)
                                .coerceIn(0.02f, 0.85f)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(
                            BorderStroke(2.dp, Color.Yellow),
                            RoundedCornerShape(8.dp)
                        )
                )
            }
        }

        if (wallpaperLoadFinished && wallpaperBitmap == null) {
            Text(
                "No wallpaper set yet — showing a sample photo instead of " +
                    "your own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!subjectSeparationEnabled) {
            Text(
                "Subject separation is off, so the clock won't be occluded " +
                    "by anyone in the photo — turn it on in Advanced " +
                    "Settings to see the depth effect.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (!subjectModelReady) {
            Text(
                "The subject-detection model isn't ready yet on this " +
                    "device, so the depth effect may not show here until " +
                    "it finishes downloading.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Size", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = heightFraction,
                onValueChange = { heightFraction = it },
                onValueChangeFinished = { persist() },
                valueRange = 0.04f..0.32f
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            AtmoTextButton(
                text = "Reset to default",
                onClick = {
                    centerX = AtmosphereClockPolicy.DEFAULT_CENTER_X
                    top = AtmosphereClockPolicy.DEFAULT_TOP
                    heightFraction = AtmosphereClockPolicy.DEFAULT_HEIGHT
                    persist()
                }
            )
        }
    }
}

/**
 * A minimal live preview of the Atmosphere effect, forced onto the GLES
 * backend (the only one the clock overlay is implemented on) regardless of
 * the device's normal Vulkan/OpenGL ES preference — see EffectPreviewService's
 * forceOpenGlEs param.
 */
@Composable
private fun ClockCalibrationPreview(wallpaper: Bitmap?, modifier: Modifier = Modifier) {
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
