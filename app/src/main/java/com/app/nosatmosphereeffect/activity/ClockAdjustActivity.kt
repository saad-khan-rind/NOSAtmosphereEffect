package com.app.nosatmosphereeffect.activity

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.app.nosatmosphereeffect.helper.AtmosphereClockPolicy
import com.app.nosatmosphereeffect.helper.ClockFaceRenderer
import com.app.nosatmosphereeffect.helper.ClockStyle
import com.app.nosatmosphereeffect.helper.SegmentationCrashGuard
import com.app.nosatmosphereeffect.image.BitmapDecoder
import com.app.nosatmosphereeffect.ui.components.AtmoTextButton
import com.app.nosatmosphereeffect.ui.components.SettingSwitchRow
import com.app.nosatmosphereeffect.ui.preview.EffectPreviewService
import com.app.nosatmosphereeffect.ui.preview.EffectPreviewSettingsMode
import com.app.nosatmosphereeffect.ui.theme.AtmoEngineTheme
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Full-screen placement and styling for the wallpaper clock — pick a face,
 * drag to move, pinch to resize, all against a live preview of the person's
 * actual applied wallpaper.
 *
 * Modelled on the crop screen rather than a settings card: there is no public
 * API to read a device's real lock-screen clock position, so eyeballing it
 * against the real photo is the substitute.
 *
 * Reachable from Advanced Settings when the clock is on for the original
 * Atmosphere effect.
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
    val haptics = LocalHapticFeedback.current
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
            prefs.getFloat(
                AtmosphereClockPolicy.TOP_KEY,
                AtmosphereClockPolicy.DEFAULT_TOP
            )
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
    var opacity by remember {
        mutableFloatStateOf(
            prefs.getFloat(
                AtmosphereClockPolicy.OPACITY_KEY,
                AtmosphereClockPolicy.DEFAULT_OPACITY
            )
        )
    }
    var style by remember {
        mutableStateOf(ClockStyle.fromId(prefs.getString(AtmosphereClockPolicy.STYLE_KEY, null)))
    }
    var showSeconds by remember {
        mutableStateOf(
            prefs.getBoolean(
                AtmosphereClockPolicy.SECONDS_KEY,
                AtmosphereClockPolicy.DEFAULT_SECONDS
            )
        )
    }
    var animate by remember {
        mutableStateOf(
            prefs.getBoolean(
                AtmosphereClockPolicy.ANIMATE_KEY,
                AtmosphereClockPolicy.DEFAULT_ANIMATE
            )
        )
    }

    var activePreview by remember { mutableStateOf<EffectPreviewService?>(null) }
    var interacting by remember { mutableStateOf(false) }
    var lastInteractionMs by remember { mutableStateOf(0L) }

    fun pushGeometry() {
        activePreview?.setAtmosphereClockGeometry(centerX, top, heightFraction, opacity)
    }

    fun pushFace() {
        activePreview?.setAtmosphereClockFace(style.id, showSeconds, animate)
    }

    var wallpaperBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var wallpaperLoadFinished by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        wallpaperBitmap = withContext(Dispatchers.IO) { loadCurrentWallpaperBitmap(context) }
        wallpaperLoadFinished = true
    }

    // Rendered thumbnails of every face, so the gallery shows what each one
    // actually looks like instead of a name. Built with the same renderer the
    // wallpaper uses, so a thumbnail cannot drift from the real thing.
    var thumbnails by remember { mutableStateOf<Map<ClockStyle, androidx.compose.ui.graphics.ImageBitmap>>(emptyMap()) }
    LaunchedEffect(showSeconds) {
        thumbnails = withContext(Dispatchers.Default) {
            renderStyleThumbnails(context, showSeconds)
        }
    }

    fun persist() {
        centerX = AtmosphereClockPolicy.sanitizeCenterX(centerX)
        top = AtmosphereClockPolicy.sanitizeTop(top)
        heightFraction = AtmosphereClockPolicy.sanitizeHeight(heightFraction)
        opacity = AtmosphereClockPolicy.sanitizeOpacity(opacity)
        prefs.edit {
            putFloat(AtmosphereClockPolicy.CENTER_X_KEY, centerX)
            putFloat(AtmosphereClockPolicy.TOP_KEY, top)
            putFloat(AtmosphereClockPolicy.HEIGHT_KEY, heightFraction)
            putFloat(AtmosphereClockPolicy.OPACITY_KEY, opacity)
            putString(AtmosphereClockPolicy.STYLE_KEY, style.id)
            putBoolean(AtmosphereClockPolicy.SECONDS_KEY, showSeconds)
            putBoolean(AtmosphereClockPolicy.ANIMATE_KEY, animate)
        }
        val update = android.content.Intent("com.app.nosatmosphereeffect.UPDATE_CONFIG")
        update.setPackage(context.packageName)
        context.sendBroadcast(update)
    }

    // The preview updates instantly through pushGeometry/pushFace; this
    // debounce only covers writing prefs and telling the live wallpaper, so
    // SharedPreferences is not hammered on every pixel of movement.
    LaunchedEffect(centerX, top, heightFraction, opacity, style, showSeconds, animate) {
        delay(350)
        persist()
    }

    // Fade the placement guides out shortly after the last touch, so the
    // clock can be judged against the photo without chrome over it.
    LaunchedEffect(lastInteractionMs) {
        if (lastInteractionMs == 0L) return@LaunchedEffect
        delay(1_200)
        interacting = false
    }
    val guideAlpha by animateFloatAsState(
        targetValue = if (interacting) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "clockGuideAlpha"
    )

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
                onPreviewCreated = {
                    activePreview = it
                    pushFace()
                    pushGeometry()
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        if (containerSizePx.width > 0 && containerSizePx.height > 0) {
            val widthPx = containerSizePx.width.toFloat()
            val heightPx = containerSizePx.height.toFloat()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(widthPx, heightPx) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            interacting = true
                            lastInteractionMs = System.currentTimeMillis()

                            val proposed = centerX + pan.x / widthPx
                            // Snap to the horizontal centre, since that is
                            // where almost every lock screen puts its clock
                            // and hitting 0.500 by hand is fiddly.
                            centerX = if (abs(proposed - 0.5f) < CENTER_SNAP) {
                                if (abs(centerX - 0.5f) >= CENTER_SNAP) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                0.5f
                            } else {
                                AtmosphereClockPolicy.sanitizeCenterX(proposed)
                            }
                            top = AtmosphereClockPolicy.sanitizeTop(top + pan.y / heightPx)
                            if (zoom != 1f) {
                                heightFraction = AtmosphereClockPolicy.sanitizeHeight(
                                    heightFraction * zoom
                                )
                            }
                            pushGeometry()
                        }
                    }
            ) {
                // Placement guides. No box around the clock: a hard border
                // fights the thing it is supposed to help you position. These
                // are hairlines that only show while you are actually moving
                // it, and they mark the centre and the top edge rather than
                // outlining the glyphs.
                if (guideAlpha > 0.01f) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        val guideColor = Color.White.copy(alpha = 0.55f * guideAlpha)
                        val centred = abs(centerX - 0.5f) < 0.001f
                        drawLine(
                            color = if (centred) {
                                Color(0xFF7FD1FF).copy(alpha = 0.9f * guideAlpha)
                            } else {
                                guideColor
                            },
                            start = androidx.compose.ui.geometry.Offset(centerX * size.width, 0f),
                            end = androidx.compose.ui.geometry.Offset(
                                centerX * size.width,
                                size.height
                            ),
                            strokeWidth = 1.dp.toPx()
                        )
                        drawLine(
                            color = guideColor,
                            start = androidx.compose.ui.geometry.Offset(0f, top * size.height),
                            end = androidx.compose.ui.geometry.Offset(
                                size.width,
                                top * size.height
                            ),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                }
            }
        }

        // Top bar, overlaid rather than pushing the image down.
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)
                    )
                )
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(4.dp)
        ) {
            IconButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Done",
                    tint = Color.White
                )
            }
            Text(
                "Drag to move, pinch to resize",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Controls. Fades down while the clock is being dragged so it never
        // hides the thing being positioned.
        AnimatedVisibility(
            visible = !interacting,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart)
        ) {
            ClockControls(
                styles = ClockStyle.entries,
                thumbnails = thumbnails,
                selected = style,
                onStyleSelected = {
                    style = it
                    pushFace()
                },
                heightFraction = heightFraction,
                onHeightChange = {
                    heightFraction = AtmosphereClockPolicy.sanitizeHeight(it)
                    pushGeometry()
                },
                opacity = opacity,
                onOpacityChange = {
                    opacity = AtmosphereClockPolicy.sanitizeOpacity(it)
                    pushGeometry()
                },
                showSeconds = showSeconds,
                onShowSecondsChange = {
                    showSeconds = it
                    pushFace()
                },
                animate = animate,
                onAnimateChange = {
                    animate = it
                    pushFace()
                },
                segmentationDisabled = SegmentationCrashGuard.isDisabled(context),
                onResetSegmentation = { SegmentationCrashGuard.reset(context) },
                onResetPlacement = {
                    centerX = AtmosphereClockPolicy.DEFAULT_CENTER_X
                    top = AtmosphereClockPolicy.DEFAULT_TOP
                    heightFraction = AtmosphereClockPolicy.DEFAULT_HEIGHT
                    opacity = AtmosphereClockPolicy.DEFAULT_OPACITY
                    pushGeometry()
                }
            )
        }
    }
}

@Composable
private fun ClockControls(
    styles: List<ClockStyle>,
    thumbnails: Map<ClockStyle, androidx.compose.ui.graphics.ImageBitmap>,
    selected: ClockStyle,
    onStyleSelected: (ClockStyle) -> Unit,
    heightFraction: Float,
    onHeightChange: (Float) -> Unit,
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    showSeconds: Boolean,
    onShowSecondsChange: (Boolean) -> Unit,
    animate: Boolean,
    onAnimateChange: (Boolean) -> Unit,
    segmentationDisabled: Boolean,
    onResetSegmentation: () -> Unit,
    onResetPlacement: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
                )
            )
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            "Style",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 2.dp)
        ) {
            items(styles) { candidate ->
                val isSelected = candidate == selected
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(112.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = if (isSelected) 0.18f else 0.07f))
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) {
                                Color.White.copy(alpha = 0.9f)
                            } else {
                                Color.White.copy(alpha = 0.16f)
                            },
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onStyleSelected(candidate) }
                        .padding(8.dp)
                ) {
                    val thumbnail = thumbnails[candidate]
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (thumbnail != null) {
                            Image(
                                bitmap = thumbnail,
                                contentDescription = candidate.label,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        candidate.label,
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        LabelledSlider(
            label = "Size",
            value = heightFraction,
            valueRange = 0.03f..0.40f,
            onValueChange = onHeightChange
        )
        LabelledSlider(
            label = "Opacity",
            value = opacity,
            valueRange = 0f..1f,
            onValueChange = onOpacityChange
        )

        SettingSwitchRow(
            title = "Show seconds",
            checked = showSeconds,
            onCheckedChange = onShowSecondsChange
        )
        SettingSwitchRow(
            title = "Animate digit changes",
            checked = animate,
            onCheckedChange = onAnimateChange,
            subtitle = "Digits slide as the time changes. Costs a short burst " +
                "of frames each minute."
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AtmoTextButton(text = "Reset placement", onClick = onResetPlacement)
            if (segmentationDisabled) {
                AtmoTextButton(
                    text = "Re-enable subject detection",
                    onClick = onResetSegmentation
                )
            }
        }
        if (segmentationDisabled) {
            Text(
                "Subject detection was switched off after repeated crashes in a " +
                    "system component, so nothing will occlude the clock until " +
                    "it is re-enabled.",
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            valueRange = valueRange,
            onValueChange = onValueChange
        )
    }
}

/**
 * A live preview of the Atmosphere effect, forced onto the GLES backend.
 *
 * Both backends implement the clock now, but the calibration screen pins
 * itself to GLES on purpose: it is a short-lived, windowed surface that only
 * has to be geometrically faithful, and GLES 3.0 is available everywhere, so
 * this avoids standing up a second Vulkan swapchain next to the live
 * wallpaper's. The two shaders compute the clock rect identically, so what
 * you position here is what you get.
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
 * Renders one thumbnail per face using the same [ClockFaceRenderer] the
 * wallpaper uses, scaled down for the gallery.
 *
 * Runs off the main thread. Each renderer is released immediately, because
 * the renderer reuses one internal bitmap — the scaled copy is what survives.
 */
private fun renderStyleThumbnails(
    context: Context,
    showSeconds: Boolean
): Map<ClockStyle, androidx.compose.ui.graphics.ImageBitmap> {
    val now = System.currentTimeMillis()
    val result = LinkedHashMap<ClockStyle, androidx.compose.ui.graphics.ImageBitmap>()
    for (candidate in ClockStyle.entries) {
        val renderer = ClockFaceRenderer(context).apply {
            style = candidate
            this.showSeconds = showSeconds
            animateDigits = false
        }
        try {
            val rendered = renderer.render(nowMillis = now, uptimeMs = 0L) ?: continue
            if (rendered.width <= 0 || rendered.height <= 0) continue
            val targetWidth = THUMBNAIL_WIDTH_PX
            val targetHeight = (rendered.height.toFloat() * targetWidth / rendered.width)
                .toInt()
                .coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(rendered, targetWidth, targetHeight, true)
            result[candidate] = scaled.asImageBitmap()
        } catch (_: RuntimeException) {
            // A face that will not render is simply left out of the gallery.
        } catch (_: OutOfMemoryError) {
            break
        } finally {
            renderer.release()
        }
    }
    return result
}

/**
 * Loads the same "currently applied wallpaper" file the rest of the app
 * previews (files/wallpaper.jpg). Returns null if none is set yet or it
 * cannot be decoded — EffectPreviewService falls back to its own demo photo.
 */
private suspend fun loadCurrentWallpaperBitmap(context: Context): Bitmap? {
    val file = File(context.filesDir, "wallpaper.jpg")
    if (!file.exists()) return null
    return try {
        BitmapDecoder.decodePreview(file)
    } catch (_: java.io.IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}

private const val CENTER_SNAP = 0.015f
private const val THUMBNAIL_WIDTH_PX = 260
