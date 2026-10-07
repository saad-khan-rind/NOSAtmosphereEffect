package com.app.nosatmosphereeffect.activity

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AlignHorizontalCenter
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.helper.AdaptiveClockFace
import com.app.nosatmosphereeffect.helper.AtmosphereClockPolicy
import com.app.nosatmosphereeffect.helper.ClockBoxHandle
import com.app.nosatmosphereeffect.helper.ClockBoxPlacement
import com.app.nosatmosphereeffect.helper.ClockBoxRect
import com.app.nosatmosphereeffect.helper.ClockFaceBox
import com.app.nosatmosphereeffect.helper.ClockFaceRenderer
import com.app.nosatmosphereeffect.helper.ClockOverlayState
import com.app.nosatmosphereeffect.helper.ClockPalette
import com.app.nosatmosphereeffect.helper.ClockPlacement
import com.app.nosatmosphereeffect.helper.ClockPreferences
import com.app.nosatmosphereeffect.helper.ClockScene
import com.app.nosatmosphereeffect.helper.ClockStyle
import com.app.nosatmosphereeffect.helper.SegmentationCrashGuard
import com.app.nosatmosphereeffect.helper.SubjectMaskCoordinator
import com.app.nosatmosphereeffect.helper.SubjectMaskDiagnostics
import com.app.nosatmosphereeffect.image.BitmapDecoder
import com.app.nosatmosphereeffect.ui.components.AtmoTextButton
import com.app.nosatmosphereeffect.ui.components.ClockBoxOverlay
import com.app.nosatmosphereeffect.ui.components.ClockGlassPreview
import com.app.nosatmosphereeffect.ui.components.SettingSwitchRow
import com.app.nosatmosphereeffect.ui.theme.AtmoEngineTheme
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Placement and styling for the wallpaper clock, against a live preview of the
 * applied wallpaper. Drag to move, pinch to resize, tap to hide the controls
 * and see the result unobstructed — the same tap-to-hide behaviour as the crop
 * screen, and for the same reason: placement cannot be judged with a panel
 * covering a third of the screen.
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

    // Through the shared reader, which converts a placement stored before the
    // stored geometry came to mean the digits themselves.
    val storedPlacement = remember(prefs) { ClockPreferences.readPlacement(prefs) }
    var centerX by remember { mutableFloatStateOf(storedPlacement.centerX) }
    var top by remember { mutableFloatStateOf(storedPlacement.top) }
    var heightFraction by remember { mutableFloatStateOf(storedPlacement.height) }
    var widthScale by remember { mutableFloatStateOf(storedPlacement.widthScale) }
    var dateCenterX by remember {
        mutableFloatStateOf(
            prefs.getFloat(
                AtmosphereClockPolicy.DATE_CENTER_X_KEY,
                AtmosphereClockPolicy.DEFAULT_DATE_CENTER_X
            )
        )
    }
    var dateTop by remember {
        mutableFloatStateOf(
            prefs.getFloat(
                AtmosphereClockPolicy.DATE_TOP_KEY,
                AtmosphereClockPolicy.DEFAULT_DATE_TOP
            )
        )
    }
    var dateHeightFraction by remember {
        mutableFloatStateOf(
            prefs.getFloat(
                AtmosphereClockPolicy.DATE_HEIGHT_KEY,
                AtmosphereClockPolicy.DEFAULT_DATE_HEIGHT
            )
        )
    }
    var dateWidthScale by remember {
        mutableFloatStateOf(
            prefs.getFloat(
                AtmosphereClockPolicy.DATE_WIDTH_SCALE_KEY,
                AtmosphereClockPolicy.DEFAULT_DATE_WIDTH_SCALE
            )
        )
    }
    /** Which box the drags are for. The other one is drawn, but passive. */
    var editingDate by remember { mutableStateOf(false) }
    // Always 1 from here on: ClockPreferences.readPlacement has already folded
    // any stored vertical stretch into the placement above, and the box is the
    // only size control now. Kept as state purely so persist() writes the
    // neutral value back over anything the old sliders left behind.
    val heightScale = AtmosphereClockPolicy.DEFAULT_HEIGHT_SCALE
    var opacity by remember {
        mutableFloatStateOf(
            prefs.getFloat(
                AtmosphereClockPolicy.OPACITY_KEY,
                AtmosphereClockPolicy.DEFAULT_OPACITY
            )
        )
    }
    var frost by remember {
        mutableFloatStateOf(
            prefs.getFloat(
                AtmosphereClockPolicy.FROST_KEY,
                AtmosphereClockPolicy.DEFAULT_FROST
            )
        )
    }
    var weight by remember {
        mutableFloatStateOf(
            prefs.getFloat(
                AtmosphereClockPolicy.WEIGHT_KEY,
                AtmosphereClockPolicy.DEFAULT_WEIGHT
            )
        )
    }
    var style by remember {
        mutableStateOf(
            ClockStyle.fromId(prefs.getString(AtmosphereClockPolicy.STYLE_KEY, null))
        )
    }
    var showDate by remember {
        mutableStateOf(
            prefs.getBoolean(
                AtmosphereClockPolicy.DATE_KEY,
                AtmosphereClockPolicy.DEFAULT_DATE
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
    var colorPref by remember {
        mutableStateOf(
            prefs.getInt(
                AtmosphereClockPolicy.COLOR_KEY,
                AtmosphereClockPolicy.DEFAULT_COLOR
            )
        )
    }
    var hourFormat by remember {
        mutableStateOf(
            AtmosphereClockPolicy.sanitizeHourFormat(
                prefs.getString(AtmosphereClockPolicy.HOUR_FORMAT_KEY, null)
            )
        )
    }

    var autoColor by remember { mutableStateOf<Int?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var eyedropperArmed by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }

    var interacting by remember { mutableStateOf(false) }
    var lastInteractionMs by remember { mutableStateOf(0L) }

    var wallpaperBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var wallpaperLoadFinished by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        wallpaperBitmap = withContext(Dispatchers.IO) { loadCurrentWallpaperBitmap(context) }
        wallpaperLoadFinished = true
    }
    LaunchedEffect(Unit) {
        autoColor = withContext(Dispatchers.IO) { ClockPalette.autoColorFor(context) }
    }

    // SubjectMaskDiagnostics is a plain in-memory holder written from the
    // segmentation worker, not Compose state, so poll it. Worth surfacing:
    // when segmentation fails there is otherwise nothing on screen to
    // distinguish "no subject in this photo" from "the model is broken on this
    // build" — which is exactly the F-Droid litert mismatch.
    var maskFailure by remember { mutableStateOf<SubjectMaskDiagnostics.Failure?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            maskFailure = SubjectMaskDiagnostics.lastFailure
            delay(700)
        }
    }

    var thumbnails by remember { mutableStateOf<Map<ClockStyle, ImageBitmap>>(emptyMap()) }
    LaunchedEffect(hourFormat) {
        thumbnails = withContext(Dispatchers.Default) {
            renderStyleThumbnails(context, hourFormat)
        }
    }

    fun persist() {
        centerX = AtmosphereClockPolicy.sanitizeCenterX(centerX)
        top = AtmosphereClockPolicy.sanitizeTop(top)
        heightFraction = AtmosphereClockPolicy.sanitizeHeight(heightFraction)
        widthScale = AtmosphereClockPolicy.sanitizeAxisScale(widthScale)
        opacity = AtmosphereClockPolicy.sanitizeOpacity(opacity)
        frost = AtmosphereClockPolicy.sanitizeFrost(frost)
        weight = AtmosphereClockPolicy.sanitizeWeight(weight)
        dateCenterX = AtmosphereClockPolicy.sanitizeCenterX(dateCenterX)
        dateTop = AtmosphereClockPolicy.sanitizeTop(dateTop)
        dateHeightFraction = AtmosphereClockPolicy.sanitizeHeight(dateHeightFraction)
        dateWidthScale = AtmosphereClockPolicy.sanitizeAxisScale(dateWidthScale)
        prefs.edit {
            putFloat(AtmosphereClockPolicy.CENTER_X_KEY, centerX)
            putFloat(AtmosphereClockPolicy.TOP_KEY, top)
            putFloat(AtmosphereClockPolicy.HEIGHT_KEY, heightFraction)
            putFloat(AtmosphereClockPolicy.WIDTH_SCALE_KEY, widthScale)
            putFloat(AtmosphereClockPolicy.HEIGHT_SCALE_KEY, heightScale)
            putFloat(AtmosphereClockPolicy.OPACITY_KEY, opacity)
            putFloat(AtmosphereClockPolicy.FROST_KEY, frost)
            putFloat(AtmosphereClockPolicy.WEIGHT_KEY, weight)
            putFloat(AtmosphereClockPolicy.DATE_CENTER_X_KEY, dateCenterX)
            putFloat(AtmosphereClockPolicy.DATE_TOP_KEY, dateTop)
            putFloat(AtmosphereClockPolicy.DATE_HEIGHT_KEY, dateHeightFraction)
            putFloat(AtmosphereClockPolicy.DATE_WIDTH_SCALE_KEY, dateWidthScale)
            // Stamped alongside the geometry it describes: anything written
            // here is already in the current form.
            putInt(
                AtmosphereClockPolicy.GEOMETRY_VERSION_KEY,
                AtmosphereClockPolicy.GEOMETRY_VERSION
            )
            putString(AtmosphereClockPolicy.STYLE_KEY, style.id)
            putBoolean(AtmosphereClockPolicy.DATE_KEY, showDate)
            putBoolean(AtmosphereClockPolicy.ANIMATE_KEY, animate)
            putInt(
                AtmosphereClockPolicy.COLOR_KEY,
                AtmosphereClockPolicy.sanitizeColor(colorPref)
            )
            putString(AtmosphereClockPolicy.HOUR_FORMAT_KEY, hourFormat)
        }
        val update = Intent("com.app.nosatmosphereeffect.UPDATE_CONFIG")
        update.setPackage(context.packageName)
        context.sendBroadcast(update)
    }

    LaunchedEffect(
        centerX, top, heightFraction, widthScale, heightScale, opacity, frost, weight, style,
        showDate, dateCenterX, dateTop, dateHeightFraction, dateWidthScale,
        animate, colorPref, hourFormat
    ) {
        delay(350)
        persist()
    }

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
    val containerWidthPx = containerSizePx.width.toFloat()
    val containerHeightPx = containerSizePx.height.toFloat()

    // The face bitmap, redrawn on a worker and handed over as an immutable
    // copy: the renderer reuses one bitmap, and the preview must never read it
    // while it is being drawn into.
    val faceRenderer = remember { ClockFaceRenderer(context) }
    var faceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var faceRevision by remember { mutableIntStateOf(0) }
    // Where the digits sit inside the face texture. The texture carries margin
    // for the digit animation and, when the date is on, room for the date
    // wherever it was placed — so the box the user drags is this content area
    // rather than the whole texture.
    var faceContent by remember { mutableStateOf(ClockFaceBox.IDENTITY) }
    /** The date line's natural width/height ratio, for sizing its box. */
    var dateAspect by remember { mutableFloatStateOf(DEFAULT_DATE_ASPECT) }
    DisposableEffect(faceRenderer) {
        // Under the renderer's lock: a render still running on a worker
        // finishes before its bitmap is freed.
        onDispose { synchronized(faceRenderer) { faceRenderer.release() } }
    }
    // The Adaptive face fits its digits around the subject, so the preview
    // needs the same mask the wallpaper will use. Segmented once per photo,
    // and only while the Adaptive face is the one chosen and set to adapt
    // (the depth switch, which is set on the screen that opens this one).
    val adaptToSubject = remember(context) {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getBoolean(
            AtmosphereClockPolicy.DEPTH_KEY,
            AtmosphereClockPolicy.DEFAULT_DEPTH
        )
    }
    val wantsScene = style.adaptsToSubject && adaptToSubject
    // Bumped by "Try again", to run detection here once more.
    var detectionAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(wallpaperBitmap, wantsScene, detectionAttempt) {
        val photo = wallpaperBitmap ?: return@LaunchedEffect
        if (!wantsScene) return@LaunchedEffect
        lateinit var coordinator: SubjectMaskCoordinator
        coordinator = SubjectMaskCoordinator(context) {
            // The scene took what it needed on the way past; nothing here
            // uploads the mask, so it is simply let go.
            coordinator.takePending()?.bitmap?.let { if (!it.isRecycled) it.recycle() }
        }
        coordinator.sceneSink = faceRenderer.sceneSource
        try {
            coordinator.configure(true)
            withContext(Dispatchers.Default) { coordinator.request(photo, 1L) }
            awaitCancellation()
        } finally {
            coordinator.close()
        }
    }

    val resolvedColor = ClockPalette.resolve(colorPref, autoColor)
    val screenAspect = if (containerHeightPx > 0f) containerWidthPx / containerHeightPx else 0.5f
    val placement = ClockPlacement(
        centerX = centerX,
        top = top,
        height = heightFraction,
        widthScale = widthScale
    )
    val datePlacement = ClockPlacement(
        centerX = dateCenterX,
        top = dateTop,
        height = dateHeightFraction,
        widthScale = dateWidthScale
    )
    // Three rectangles: the face texture, which is what the shaders place, and
    // the digits and the date inside it, which are what the user sees and
    // drags. The stored geometry describes the last two.
    val textureBox = ClockBoxPlacement.textureBox(placement, faceContent, screenAspect)
    val contentBox =
        ClockBoxPlacement.contentBox(placement, faceContent.contentAspect, screenAspect)
    val dateBox = ClockBoxPlacement.contentBox(datePlacement, dateAspect, screenAspect)
    val activeBox = if (editingDate && showDate) dateBox else contentBox
    val activePlacement = if (editingDate && showDate) datePlacement else placement

    // One coroutine owns the renderer, keyed on the settings that change the
    // face itself. The clock's own box is not one of them — that is shader
    // geometry, so moving the clock neither redraws nor re-uploads the digits
    // — but the date lives inside the same bitmap, so a date drag does have to
    // redraw it. The loop reads the current placement on each pass rather than
    // being keyed on it: keying would restart the whole coroutine on every
    // frame of a drag.
    LaunchedEffect(style, showDate, animate, resolvedColor, colorPref, weight, hourFormat) {
        var lastGeometry: Pair<ClockPlacement, ClockPlacement>? = null
        while (true) {
            val wantedClock = ClockPlacement(centerX, top, heightFraction, widthScale)
            val wantedDate =
                ClockPlacement(dateCenterX, dateTop, dateHeightFraction, dateWidthScale)
            val geometry = wantedClock to wantedDate
            val geometryChanged = lastGeometry != geometry
            lastGeometry = geometry
            // Every use of the renderer holds its lock. A settings change
            // restarts this effect, but cancelling cannot stop a render that is
            // already drawing, so without the lock the restarted loop would
            // reconfigure the renderer — freeing and replacing its bitmap —
            // under that draw. A colour-wheel drag restarts it dozens of times
            // a second, and that crashed natively.
            val measured = withContext(Dispatchers.Default) {
                synchronized(faceRenderer) {
                    faceRenderer.style = style
                    faceRenderer.showDate = showDate
                    faceRenderer.animateDigits = animate
                    faceRenderer.animateEntry = false
                    faceRenderer.color = resolvedColor
                    faceRenderer.weight = weight
                    faceRenderer.adaptiveColors = ClockPalette.isAdaptive(colorPref)
                    faceRenderer.adaptToSubject = adaptToSubject
                    // The photo here is centre-cropped into the preview rather
                    // than panned, so the scene is mapped the same way.
                    faceRenderer.centerCropScene = true
                    faceRenderer.hourFormatOverride =
                        AtmosphereClockPolicy.hourFormatOverride(hourFormat)
                    faceRenderer.screenAspect = screenAspect
                    faceRenderer.clockPlacement = wantedClock
                    faceRenderer.datePlacement = wantedDate
                    runCatching {
                        val now = System.currentTimeMillis()
                        faceRenderer.measureFace(now) to faceRenderer.measureDateAspect(now)
                    }.getOrNull()
                }
            }
            if (measured != null) {
                faceContent = measured.first
                measured.second?.let { dateAspect = it }
            }
            // Asked on the worker too, so the main thread never waits on the lock.
            val (snapshot, animating) = withContext(Dispatchers.Default) {
                synchronized(faceRenderer) {
                    runCatching {
                        // Handed over as a copy: the renderer keeps reusing its
                        // own bitmap, and the preview must never read one that is
                        // being drawn into.
                        faceRenderer.render(
                            nowMillis = System.currentTimeMillis(),
                            uptimeMs = SystemClock.uptimeMillis()
                        )?.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull() to faceRenderer.isAnimating(SystemClock.uptimeMillis())
                }
            }
            if (snapshot != null) {
                faceBitmap = snapshot
                faceRevision++
            }
            // Redrawing the whole face costs a bitmap, so a drag gets ten
            // frames a second rather than sixty: the frame the finger is
            // holding follows at full rate, and the date inside it catches up
            // imperceptibly behind. Nothing else here changes between minutes.
            delay(if (animating || interacting || geometryChanged) 100L else 1_000L)
        }
    }

    // The placement as it was when the finger went down. Every frame of a
    // gesture is resolved against it, so a drag cannot drift, and clamping at
    // a limit cannot feed back into the next frame.
    var dragStart by remember { mutableStateOf<ClockPlacement?>(null) }
    // Which box this gesture grabbed, fixed when the finger went down. The
    // selection follows the grab, so reading the selection here instead would
    // change target mid-drag.
    var draggingDate by remember { mutableStateOf(false) }

    fun setClock(value: ClockPlacement) {
        centerX = value.centerX
        top = value.top
        heightFraction = value.height
        widthScale = value.widthScale
    }

    fun setDate(value: ClockPlacement) {
        dateCenterX = value.centerX
        dateTop = value.top
        dateHeightFraction = value.height
        dateWidthScale = value.widthScale
    }

    // Saves at once rather than waiting out the debounce below: leaving within
    // it would otherwise drop the last change.
    fun finishEditing() {
        persist()
        onDone()
    }
    BackHandler {
        if (eyedropperArmed) eyedropperArmed = false else finishEditing()
    }

    var panelTab by remember { mutableStateOf(ClockPanelTab.STYLE) }
    var panelExpanded by remember { mutableStateOf(true) }
    // Bumped to show the gesture hint again: on arrival, and whenever the
    // selection changes, so the reminder appears when it is relevant.
    var hintRequest by remember { mutableIntStateOf(0) }
    var hintVisible by remember { mutableStateOf(true) }
    LaunchedEffect(hintRequest) {
        hintVisible = true
        delay(HINT_MS)
        hintVisible = false
    }
    LaunchedEffect(editingDate) { hintRequest++ }

    fun applyBox(proposed: ClockBoxRect, handle: ClockBoxHandle) {
        interacting = true
        lastInteractionMs = System.currentTimeMillis()
        val editing = draggingDate && showDate
        val origin = dragStart ?: if (editing) datePlacement else placement
        val settled = ClockBoxPlacement.apply(
            start = origin,
            proposed = proposed,
            handle = handle,
            contentAspect = if (editing) dateAspect else faceContent.contentAspect,
            screenAspect = screenAspect
        )
        if (handle == ClockBoxHandle.MOVE &&
            ClockBoxPlacement.isCentred(settled) &&
            !ClockBoxPlacement.isCentred(if (editing) datePlacement else placement)
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        if (editing) setDate(settled) else setClock(settled)
    }

    /** A two-finger pinch on the selected box, resolved against where it began. */
    fun applyTransform(zoom: Float, dx: Float, dy: Float) {
        interacting = true
        lastInteractionMs = System.currentTimeMillis()
        val editing = draggingDate && showDate
        val origin = dragStart ?: if (editing) datePlacement else placement
        val settled = ClockBoxPlacement.transform(
            start = origin,
            zoom = zoom,
            dx = dx,
            dy = dy,
            contentAspect = if (editing) dateAspect else faceContent.contentAspect,
            screenAspect = screenAspect
        )
        if (editing) setDate(settled) else setClock(settled)
    }

    /** The date sized from the clock and placed above it (below, if no room). */
    fun placeDateBesideClock() {
        setDate(
            ClockBoxPlacement.dateBesideClock(
                clock = placement,
                contentAspect = faceContent.contentAspect,
                dateAspect = dateAspect,
                screenAspect = screenAspect
            )
        )
    }

    fun centreSelected() {
        if (editingDate && showDate) {
            setDate(ClockBoxPlacement.fitOnScreen(datePlacement.copy(centerX = 0.5f), dateAspect, screenAspect))
        } else {
            setClock(
                ClockBoxPlacement.fitOnScreen(
                    placement.copy(centerX = 0.5f),
                    faceContent.contentAspect,
                    screenAspect
                )
            )
        }
    }

    fun resetSelectedSize() {
        if (editingDate && showDate) {
            placeDateBesideClock()
        } else {
            setClock(
                ClockBoxPlacement.fitOnScreen(
                    placement.copy(
                        height = AtmosphereClockPolicy.DEFAULT_HEIGHT,
                        widthScale = AtmosphereClockPolicy.DEFAULT_WIDTH_SCALE
                    ),
                    faceContent.contentAspect,
                    screenAspect
                )
            )
        }
    }

    // A size that fitted one shape can overflow another: a height set on a
    // stacked face is far too wide as a row, and the date widens with a longer
    // day name. Refit whenever a shape changes, so no box ever opens wider
    // than the screen with its handles out of reach under the back gesture.
    // Never mid-gesture: the gesture itself keeps the box on screen.
    val clockShapeKey = (faceContent.contentAspect * 200f).toInt()
    val dateShapeKey = (dateAspect * 200f).toInt()
    val screenShapeKey = (screenAspect * 1000f).toInt()
    LaunchedEffect(clockShapeKey, dateShapeKey, screenShapeKey, showDate) {
        if (dragStart != null || containerWidthPx <= 0f) return@LaunchedEffect
        val fittedClock =
            ClockBoxPlacement.fitOnScreen(placement, faceContent.contentAspect, screenAspect)
        if (fittedClock !== placement) setClock(fittedClock)
        if (showDate) {
            val fittedDate = ClockBoxPlacement.fitOnScreen(datePlacement, dateAspect, screenAspect)
            if (fittedDate !== datePlacement) setDate(fittedDate)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSizePx = it }
    ) {
        if (wallpaperLoadFinished) {
            ClockGlassPreview(
                wallpaper = wallpaperBitmap,
                face = faceBitmap,
                box = textureBox,
                opacity = opacity,
                // The same number the wallpaper's shaders are given, so the
                // preview picks the same treatment and frost level.
                mode = ClockOverlayState(
                    styleId = style.id,
                    frost = frost
                ).glassMode,
                faceRevision = faceRevision,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        if (containerWidthPx > 0f && containerHeightPx > 0f) {
            ClockBoxOverlay(
                box = activeBox,
                // Drawn but not draggable, so both are visible while either is
                // being placed.
                passiveBox = if (showDate) {
                    if (editingDate) contentBox else dateBox
                } else {
                    null
                },
                centered = ClockBoxPlacement.isCentred(activePlacement),
                showHandles = !eyedropperArmed,
                onBoxChange = { rect, handle, _ -> applyBox(rect, handle) },
                onTransform = { zoom, dx, dy -> applyTransform(zoom, dx, dy) },
                onDragStarted = { grabbedPassive ->
                    val target = if (grabbedPassive) !editingDate else editingDate
                    draggingDate = target && showDate
                    // The selection follows the finger, so the chips and the
                    // hint text agree with what is actually being dragged.
                    if (target != editingDate) editingDate = target
                    dragStart = if (draggingDate) datePlacement else placement
                    interacting = true
                    lastInteractionMs = System.currentTimeMillis()
                },
                onDragFinished = {
                    dragStart = null
                    lastInteractionMs = System.currentTimeMillis()
                },
                onTap = { position ->
                    val source = wallpaperBitmap
                    val other = if (showDate) {
                        if (editingDate) contentBox else dateBox
                    } else {
                        null
                    }
                    if (other != null &&
                        !eyedropperArmed &&
                        containerWidthPx > 0f &&
                        other.contains(
                            position.x / containerWidthPx,
                            position.y / containerHeightPx
                        )
                    ) {
                        // Tapping the box you are not editing selects it,
                        // which is quicker than going back to the chips.
                        editingDate = !editingDate
                    } else if (eyedropperArmed && source != null) {
                        val sampled = sampleWallpaperColor(
                            bitmap = source,
                            tap = position,
                            viewWidth = containerWidthPx,
                            viewHeight = containerHeightPx
                        )
                        if (sampled != null) {
                            colorPref = sampled
                            eyedropperArmed = false
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    } else {
                        chromeVisible = !chromeVisible
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ---------------------------------------------------------------- top
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                        )
                    )
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.fillMaxWidth()) {
                    RoundIconButton(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        description = stringResource(R.string.common_back),
                        onClick = ::finishEditing,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                    if (showDate && !eyedropperArmed) {
                        // Which box the gestures move. Tapping a box on the
                        // photo selects it too; this says which one is live.
                        SegmentedPill(
                            options = listOf(stringResource(R.string.clock_tab_clock), stringResource(R.string.clock_tab_date)),
                            selectedIndex = if (editingDate) 1 else 0,
                            onSelected = { editingDate = it == 1 },
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        Text(
                            if (eyedropperArmed) stringResource(R.string.clock_pick_colour) else stringResource(R.string.clock_tab_clock),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    DonePill(onClick = ::finishEditing, modifier = Modifier.align(Alignment.CenterEnd))
                }
                AnimatedVisibility(
                    visible = eyedropperArmed || (hintVisible && !interacting),
                    enter = fadeIn() + slideInVertically { -it / 2 },
                    exit = fadeOut()
                ) {
                    HintPill(
                        text = if (eyedropperArmed) {
                            stringResource(R.string.clock_hint_eyedropper)
                        } else {
                            stringResource(R.string.clock_hint_gestures)
                        },
                        actionLabel = if (eyedropperArmed) stringResource(R.string.common_cancel) else null,
                        onAction = { eyedropperArmed = false },
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
        }

        // ------------------------------------------------------------- bottom
        AnimatedVisibility(
            visible = chromeVisible && !interacting && !eyedropperArmed,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
            modifier = Modifier.align(Alignment.BottomStart)
        ) {
            ClockPanel(
                tab = panelTab,
                onTabChange = {
                    panelTab = it
                    panelExpanded = true
                },
                expanded = panelExpanded,
                onToggleExpanded = { panelExpanded = !panelExpanded },
                editingDate = editingDate && showDate,
                thumbnails = thumbnails,
                selected = style,
                onStyleSelected = { style = it },
                opacity = opacity,
                onOpacityChange = { opacity = AtmosphereClockPolicy.sanitizeOpacity(it) },
                frost = frost,
                onFrostChange = { frost = AtmosphereClockPolicy.sanitizeFrost(it) },
                weight = weight,
                onWeightChange = { weight = AtmosphereClockPolicy.sanitizeWeight(it) },
                showDate = showDate,
                onShowDateChange = { enabled ->
                    showDate = enabled
                    if (enabled) {
                        // Sized from the clock and placed above it, never under
                        // the digits, and selected so it can be adjusted straight
                        // away.
                        placeDateBesideClock()
                        editingDate = true
                        hintRequest++
                    } else {
                        // Nothing to adjust once it is off, and the box the
                        // user was dragging would vanish under their finger.
                        editingDate = false
                    }
                },
                onPlaceDate = { placeDateBesideClock() },
                onCentre = { centreSelected() },
                onResetSize = { resetSelectedSize() },
                animate = animate,
                onAnimateChange = { animate = it },
                hourFormat = hourFormat,
                onHourFormatChange = { hourFormat = it },
                colorPref = colorPref,
                autoColor = autoColor,
                onColorSelected = { colorPref = it },
                pickerOpen = pickerOpen,
                onTogglePicker = { pickerOpen = !pickerOpen },
                canEyedrop = wallpaperBitmap != null,
                onArmEyedropper = { eyedropperArmed = true },
                maskFailure = maskFailure,
                segmentationDisabled = SegmentationCrashGuard.isDisabled(context),
                onResetSegmentation = {
                    // Clears any pause, so the model's version is checked
                    // again (Play services may have updated it), then runs
                    // detection again here and in the live wallpaper, which
                    // only segments when its image reloads.
                    SegmentationCrashGuard.reset(context)
                    SubjectMaskDiagnostics.clear()
                    maskFailure = null
                    detectionAttempt++
                    context.sendBroadcast(
                        Intent("com.app.nosatmosphereeffect.RELOAD_WALLPAPER").setPackage(context.packageName)
                    )
                },
                onResetAll = {
                    colorPref = AtmosphereClockPolicy.DEFAULT_COLOR
                    setClock(AtmosphereClockPolicy.DEFAULT_PLACEMENT)
                    opacity = AtmosphereClockPolicy.DEFAULT_OPACITY
                    frost = AtmosphereClockPolicy.DEFAULT_FROST
                    weight = AtmosphereClockPolicy.DEFAULT_WEIGHT
                    setDate(
                        ClockBoxPlacement.dateBesideClock(
                            clock = AtmosphereClockPolicy.DEFAULT_PLACEMENT,
                            contentAspect = faceContent.contentAspect,
                            dateAspect = dateAspect,
                            screenAspect = screenAspect
                        )
                    )
                }
            )
        }
    }
}

/** The four groups of settings in the panel. */
private enum class ClockPanelTab(@StringRes val label: Int, val icon: ImageVector) {
    STYLE(R.string.clock_panel_style, Icons.Rounded.Style),
    COLOUR(R.string.clock_panel_colour, Icons.Rounded.Palette),
    LOOK(R.string.clock_panel_adjust, Icons.Rounded.Tune),
    MORE(R.string.clock_panel_more, Icons.Rounded.MoreHoriz)
}

/**
 * The settings, grouped into tabs so only one group is open at a time and the
 * panel stays short: the photo behind it is what the user is judging, so the
 * panel's job is to cover as little of it as it can. The handle folds it down
 * to just the tabs.
 */
@Composable
private fun ClockPanel(
    tab: ClockPanelTab,
    onTabChange: (ClockPanelTab) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    editingDate: Boolean,
    thumbnails: Map<ClockStyle, ImageBitmap>,
    selected: ClockStyle,
    onStyleSelected: (ClockStyle) -> Unit,
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    frost: Float,
    onFrostChange: (Float) -> Unit,
    weight: Float,
    onWeightChange: (Float) -> Unit,
    showDate: Boolean,
    onShowDateChange: (Boolean) -> Unit,
    onPlaceDate: () -> Unit,
    onCentre: () -> Unit,
    onResetSize: () -> Unit,
    animate: Boolean,
    onAnimateChange: (Boolean) -> Unit,
    hourFormat: String,
    onHourFormatChange: (String) -> Unit,
    colorPref: Int,
    autoColor: Int?,
    onColorSelected: (Int) -> Unit,
    pickerOpen: Boolean,
    onTogglePicker: () -> Unit,
    canEyedrop: Boolean,
    onArmEyedropper: () -> Unit,
    maskFailure: SubjectMaskDiagnostics.Failure?,
    segmentationDisabled: Boolean,
    onResetSegmentation: () -> Unit,
    onResetAll: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(PANEL_COLOR)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
    ) {
        // The handle: a tap folds the panel to its tabs and back.
        Box(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.35f))
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ClockPanelTab.entries.forEach { candidate ->
                PanelTab(
                    tab = candidate,
                    selected = expanded && candidate == tab,
                    onClick = {
                        if (expanded && candidate == tab) onToggleExpanded() else onTabChange(candidate)
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = PANEL_MAX_HEIGHT.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 14.dp)
            ) {
                when (tab) {
                    ClockPanelTab.STYLE -> StyleTab(thumbnails, selected, onStyleSelected)
                    ClockPanelTab.COLOUR -> ColourTab(
                        selected = selected,
                        colorPref = colorPref,
                        autoColor = autoColor,
                        onColorSelected = onColorSelected,
                        pickerOpen = pickerOpen,
                        onTogglePicker = onTogglePicker,
                        canEyedrop = canEyedrop,
                        onArmEyedropper = onArmEyedropper
                    )
                    ClockPanelTab.LOOK -> AdjustTab(
                        selected = selected,
                        editingDate = editingDate,
                        opacity = opacity,
                        onOpacityChange = onOpacityChange,
                        frost = frost,
                        onFrostChange = onFrostChange,
                        weight = weight,
                        onWeightChange = onWeightChange,
                        onCentre = onCentre,
                        onResetSize = onResetSize,
                        showDate = showDate,
                        onPlaceDate = onPlaceDate
                    )
                    ClockPanelTab.MORE -> MoreTab(
                        selected = selected,
                        showDate = showDate,
                        onShowDateChange = onShowDateChange,
                        animate = animate,
                        onAnimateChange = onAnimateChange,
                        hourFormat = hourFormat,
                        onHourFormatChange = onHourFormatChange,
                        maskFailure = maskFailure,
                        segmentationDisabled = segmentationDisabled,
                        onResetSegmentation = onResetSegmentation,
                        onResetAll = onResetAll
                    )
                }
            }
        }
    }
}

@Composable
private fun StyleTab(
    thumbnails: Map<ClockStyle, ImageBitmap>,
    selected: ClockStyle,
    onStyleSelected: (ClockStyle) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 2.dp)
    ) {
        items(ClockStyle.entries) { candidate ->
            StyleCard(
                style = candidate,
                thumbnail = thumbnails[candidate],
                selected = candidate == selected,
                onClick = { onStyleSelected(candidate) }
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        stringResource(selected.description),
        color = Color.White.copy(alpha = 0.7f),
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun ColourTab(
    selected: ClockStyle,
    colorPref: Int,
    autoColor: Int?,
    onColorSelected: (Int) -> Unit,
    pickerOpen: Boolean,
    onTogglePicker: () -> Unit,
    canEyedrop: Boolean,
    onArmEyedropper: () -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selected.adaptsToSubject) {
            item {
                // The wallpaper's colour shaded from deep at the top line to
                // pale at full length — what the digits will show.
                val base = autoColor ?: ClockPalette.DEFAULT_FALLBACK
                ColorSwatch(
                    color = base,
                    label = stringResource(R.string.clock_colour_adaptive),
                    selected = ClockPalette.isAdaptive(colorPref),
                    onClick = { onColorSelected(ClockPalette.ADAPTIVE) },
                    gradient = listOf(
                        Color(AdaptiveClockFace.shadeOf(base, 0f)),
                        Color(AdaptiveClockFace.shadeOf(base, 1f))
                    )
                )
            }
        }
        item {
            ColorSwatch(
                color = autoColor ?: ClockPalette.DEFAULT_FALLBACK,
                label = stringResource(R.string.clock_colour_auto),
                // Adaptive falls back to Auto on the glass faces, so Auto is
                // what is showing there.
                selected = ClockPalette.isAuto(colorPref) ||
                    (ClockPalette.isAdaptive(colorPref) && !selected.adaptsToSubject),
                onClick = { onColorSelected(ClockPalette.AUTO) }
            )
        }
        items(ClockPalette.PRESETS) { swatch ->
            ColorSwatch(
                color = swatch.color,
                label = stringResource(swatch.label),
                selected = !ClockPalette.followsWallpaper(colorPref) && colorPref == swatch.color,
                onClick = { onColorSelected(swatch.color) }
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionChip(
            icon = Icons.Rounded.ColorLens,
            label = stringResource(if (pickerOpen) R.string.clock_hide_wheel else R.string.clock_colour_wheel),
            onClick = onTogglePicker,
            selected = pickerOpen
        )
        if (canEyedrop) {
            ActionChip(
                icon = Icons.Rounded.Colorize,
                label = stringResource(R.string.clock_from_photo),
                onClick = onArmEyedropper
            )
        }
    }
    if (pickerOpen) {
        ColorWheelPicker(
            current = ClockPalette.resolve(colorPref, autoColor),
            onColorChange = onColorSelected
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        // "Tint" on glass: the brightness comes from the wallpaper showing
        // through, and the colour tints it rather than filling the digits.
        if (selected.adaptsToSubject) {
            stringResource(R.string.clock_colour_solid_hint)
        } else {
            stringResource(R.string.clock_colour_glass_hint)
        },
        color = Color.White.copy(alpha = 0.6f),
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun AdjustTab(
    selected: ClockStyle,
    editingDate: Boolean,
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    frost: Float,
    onFrostChange: (Float) -> Unit,
    weight: Float,
    onWeightChange: (Float) -> Unit,
    onCentre: () -> Unit,
    onResetSize: () -> Unit,
    showDate: Boolean,
    onPlaceDate: () -> Unit
) {
    // Placement shortcuts for whichever box is selected, for the moves a
    // finger does badly: exactly centred, and back to a sensible size.
    Text(
        stringResource(if (editingDate) R.string.clock_tab_date else R.string.clock_tab_clock),
        color = Color.White.copy(alpha = 0.6f),
        style = MaterialTheme.typography.labelMedium
    )
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionChip(icon = Icons.Rounded.AlignHorizontalCenter, label = stringResource(R.string.clock_centre), onClick = onCentre)
        ActionChip(
            icon = Icons.Rounded.RestartAlt,
            label = stringResource(if (editingDate) R.string.clock_fit_to_clock else R.string.clock_reset_size),
            onClick = onResetSize
        )
        if (showDate && !editingDate) {
            ActionChip(
                icon = Icons.Rounded.VerticalAlignTop,
                label = stringResource(R.string.clock_date_above),
                onClick = onPlaceDate
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    LabelledSlider(label = stringResource(R.string.clock_opacity), value = opacity, valueRange = 0f..1f, onValueChange = onOpacityChange)
    // How diffuse the glass is: at 0 the wallpaper shows through sharply, at 1
    // it is milky. Only the translucent faces have it — the glass ones are
    // clear except at their bevel, so frosting them would fog an edge only.
    if (selected.usesFrost) {
        LabelledSlider(label = stringResource(R.string.clock_frost), value = frost, valueRange = 0f..1f, onValueChange = onFrostChange)
    }
    // The Adaptive face is drawn from strokes, so its weight is exact.
    if (selected.hasWeight) {
        LabelledSlider(
            label = stringResource(R.string.clock_weight),
            value = weight,
            valueRange = 0f..1f,
            onValueChange = onWeightChange,
            startLabel = stringResource(R.string.clock_weight_thin),
            endLabel = stringResource(R.string.clock_weight_bold)
        )
    }
}

@Composable
private fun MoreTab(
    selected: ClockStyle,
    showDate: Boolean,
    onShowDateChange: (Boolean) -> Unit,
    animate: Boolean,
    onAnimateChange: (Boolean) -> Unit,
    hourFormat: String,
    onHourFormatChange: (String) -> Unit,
    maskFailure: SubjectMaskDiagnostics.Failure?,
    segmentationDisabled: Boolean,
    onResetSegmentation: () -> Unit,
    onResetAll: () -> Unit
) {
    SettingSwitchRow(
        title = stringResource(R.string.clock_show_date),
        checked = showDate,
        onCheckedChange = onShowDateChange,
        subtitle = stringResource(R.string.clock_show_date_hint)
    )
    SettingSwitchRow(
        title = stringResource(R.string.clock_animate_digits),
        checked = animate,
        onCheckedChange = onAnimateChange,
        subtitle = stringResource(R.string.clock_animate_digits_hint)
    )
    Spacer(Modifier.height(6.dp))
    Text(stringResource(R.string.clock_hour_format), color = Color.White, style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceChip(
            label = stringResource(R.string.clock_hour_system),
            selected = hourFormat == AtmosphereClockPolicy.HOUR_FORMAT_SYSTEM,
            onClick = { onHourFormatChange(AtmosphereClockPolicy.HOUR_FORMAT_SYSTEM) }
        )
        ChoiceChip(
            label = stringResource(R.string.clock_hour_12),
            selected = hourFormat == AtmosphereClockPolicy.HOUR_FORMAT_12,
            onClick = { onHourFormatChange(AtmosphereClockPolicy.HOUR_FORMAT_12) }
        )
        ChoiceChip(
            label = stringResource(R.string.clock_hour_24),
            selected = hourFormat == AtmosphereClockPolicy.HOUR_FORMAT_24,
            onClick = { onHourFormatChange(AtmosphereClockPolicy.HOUR_FORMAT_24) }
        )
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionChip(icon = Icons.Rounded.RestartAlt, label = stringResource(R.string.clock_reset), onClick = onResetAll)
        // Whenever detection didn't work, for whatever reason.
        if (segmentationDisabled || maskFailure != null) {
            ActionChip(
                icon = Icons.Rounded.Refresh,
                label = stringResource(R.string.common_try_again),
                onClick = onResetSegmentation
            )
        }
    }
    val failureText = maskFailure?.describe(LocalResources.current)
    val notice = when {
        // Google's model is paused for the version on this phone: say so,
        // rather than blaming crashes or the photo.
        maskFailure?.text == SegmentationCrashGuard.MODEL_PAUSED -> failureText
        segmentationDisabled ->
            stringResource(R.string.clock_detection_off)
        failureText != null && selected.adaptsToSubject ->
            stringResource(R.string.clock_cant_fit, failureText)
        failureText != null -> stringResource(R.string.clock_no_depth, failureText)
        else -> null
    }
    if (notice != null) {
        Spacer(Modifier.height(8.dp))
        Text(notice, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PanelTab(
    tab: ClockPanelTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) ACCENT.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            tab.icon,
            contentDescription = null,
            tint = if (selected) ACCENT else Color.White.copy(alpha = 0.75f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            stringResource(tab.label),
            color = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun ActionChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = if (selected) 0.2f else 0.09f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun RoundIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = Color.White)
    }
}

@Composable
private fun DonePill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(ACCENT)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.common_done), color = Color.Black, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SegmentedPill(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(3.dp)
    ) {
        options.forEachIndexed { index, option ->
            val active = index == selectedIndex
            Box(
                Modifier
                    .clip(RoundedCornerShape(17.dp))
                    .background(if (active) Color.White else Color.Transparent)
                    .clickable { onSelected(index) }
                    .padding(horizontal = 16.dp, vertical = 7.dp)
            ) {
                Text(
                    option,
                    color = if (active) Color.Black else Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun HintPill(
    text: String,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(start = 14.dp, end = if (actionLabel != null) 6.dp else 14.dp)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.TouchApp,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.bodySmall)
        if (actionLabel != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.18f))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(actionLabel, color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = if (selected) 0.22f else 0.07f))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = Color.White.copy(alpha = if (selected) 0.9f else 0.16f),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun StyleCard(
    style: ClockStyle,
    thumbnail: ImageBitmap?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(112.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = if (selected) 0.18f else 0.07f))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = Color.White.copy(alpha = if (selected) 0.9f else 0.16f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        // Taller than the old 46dp: the faces are now stretched vertically,
        // and ContentScale.Fit would otherwise shrink a tall face until its
        // digits were unreadable in the picker — which is the one place the
        // shape is what the user is choosing between.
        Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = stringResource(style.label),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(style.label), color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ColorSwatch(
    color: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    /** Drawn instead of [color] when set. */
    gradient: List<Color>? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(56.dp)
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(19.dp))
                .then(
                    if (gradient != null) {
                        Modifier.background(Brush.verticalGradient(gradient))
                    } else {
                        Modifier.background(Color(color))
                    }
                )
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) Color.White else Color.White.copy(alpha = 0.28f),
                    shape = RoundedCornerShape(19.dp)
                )
                .clickable(onClick = onClick)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            color = Color.White.copy(alpha = if (selected) 1f else 0.7f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

/**
 * Standard HSV wheel: angle is hue, distance from the centre is saturation,
 * with a brightness slider beside it — a flat disc has nowhere to put the third
 * axis.
 *
 * The disc is rasterised once into a bitmap and then blitted. Computing it
 * per-pixel inside the draw scope would re-run tens of thousands of colour
 * conversions on every recomposition, which is what makes hand-rolled wheels
 * feel sluggish.
 */
@Composable
private fun ColorWheelPicker(
    current: Int,
    onColorChange: (Int) -> Unit
) {
    // Held across changes to [current] rather than keyed on it: the gesture
    // handlers below are installed once, and state recreated per colour would
    // leave them writing to stale copies — a drag after moving the brightness
    // slider would put the old brightness back. The wheel re-reads [current]
    // only when it changes from outside (a swatch, the eyedropper); a colour
    // it emitted itself would round-trip through RGB and lose the hue at
    // zero saturation or brightness.
    val initial = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(current, it) }
    }
    var hue by remember { mutableFloatStateOf(initial[0]) }
    var saturation by remember { mutableFloatStateOf(initial[1]) }
    var value by remember { mutableFloatStateOf(initial[2]) }
    var emitted by remember { mutableIntStateOf(current) }
    val latestOnColorChange by rememberUpdatedState(onColorChange)
    if (current != emitted) {
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(current, it) }
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
        emitted = current
    }

    val wheel = remember { buildHueWheel(WHEEL_PX).asImageBitmap() }

    fun emit() {
        val color = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)) or
            (0xFF shl 24)
        emitted = color
        latestOnColorChange(color)
    }

    fun applyPointer(position: Offset, sizePx: Float) {
        val radius = sizePx / 2f
        if (radius <= 0f) return
        val dx = position.x - radius
        val dy = position.y - radius
        saturation = (hypot(dx, dy) / radius).coerceIn(0f, 1f)
        val degrees = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        hue = (degrees + 360f) % 360f
        emit()
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(WHEEL_DP.dp)
                .pointerInput(Unit) {
                    detectTapGestures { applyPointer(it, size.width.toFloat()) }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        applyPointer(change.position, size.width.toFloat())
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawImage(
                    image = wheel,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt())
                )
                // Brightness is not on the disc, so dim it to match rather than
                // showing colours the picker cannot currently produce.
                if (value < 1f) {
                    drawCircle(color = Color.Black.copy(alpha = 1f - value))
                }
                val radius = size.minDimension / 2f
                val angle = Math.toRadians(hue.toDouble())
                val marker = Offset(
                    radius + (cos(angle) * saturation * radius).toFloat(),
                    radius + (sin(angle) * saturation * radius).toFloat()
                )
                drawCircle(
                    color = Color.White,
                    radius = 9.dp.toPx(),
                    center = marker,
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Color(
                            android.graphics.Color.HSVToColor(
                                floatArrayOf(hue, saturation, value)
                            )
                        )
                    )
            )
            LabelledSlider(
                label = stringResource(R.string.clock_brightness),
                value = value,
                valueRange = 0f..1f,
                onValueChange = { value = it; emit() }
            )
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    /** Words under the two ends, for a scale that is not a plain amount. */
    startLabel: String? = null,
    endLabel: String? = null
) {
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val percent = (((value - valueRange.start) / span) * 100f).roundToInt().coerceIn(0, 100)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            Text(
                "$percent%",
                color = Color.White.copy(alpha = 0.65f),
                style = MaterialTheme.typography.labelMedium
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            valueRange = valueRange,
            onValueChange = onValueChange,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = ACCENT,
                inactiveTrackColor = Color.White.copy(alpha = 0.18f)
            )
        )
        if (startLabel != null || endLabel != null) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    startLabel.orEmpty(),
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.weight(1f))
                Text(
                    endLabel.orEmpty(),
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

/** Rasterises the hue/saturation disc once. Pixels outside it stay transparent. */
private fun buildHueWheel(sizePx: Int): Bitmap {
    val bitmap = createBitmap(sizePx, sizePx)
    val pixels = IntArray(sizePx * sizePx)
    val radius = sizePx / 2f
    val hsv = FloatArray(3)
    hsv[2] = 1f
    for (y in 0 until sizePx) {
        val dy = y - radius
        for (x in 0 until sizePx) {
            val dx = x - radius
            val distance = hypot(dx, dy)
            if (distance > radius) continue
            hsv[0] = (Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 360f) % 360f
            hsv[1] = (distance / radius).coerceIn(0f, 1f)
            pixels[y * sizePx + x] = android.graphics.Color.HSVToColor(hsv)
        }
    }
    bitmap.setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
    return bitmap
}

/**
 * Maps a tap on the full-screen preview back to a pixel in the wallpaper.
 *
 * The preview centre-crops the photo to fill the surface, so the same mapping
 * is inverted here. Returns null if the bitmap is unusable.
 */
private fun sampleWallpaperColor(
    bitmap: Bitmap,
    tap: Offset,
    viewWidth: Float,
    viewHeight: Float
): Int? {
    if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
    if (viewWidth <= 0f || viewHeight <= 0f) return null
    val scale = max(viewWidth / bitmap.width, viewHeight / bitmap.height)
    if (scale <= 0f) return null
    val originX = (viewWidth - bitmap.width * scale) / 2f
    val originY = (viewHeight - bitmap.height * scale) / 2f
    val sourceX = ((tap.x - originX) / scale).toInt().coerceIn(0, bitmap.width - 1)
    val sourceY = ((tap.y - originY) / scale).toInt().coerceIn(0, bitmap.height - 1)

    return try {
        // Average a small block rather than reading one pixel: a single sample
        // lands on JPEG noise often enough that the picked colour feels random.
        var red = 0
        var green = 0
        var blue = 0
        var count = 0
        for (y in (sourceY - SAMPLE_RADIUS_PX)..(sourceY + SAMPLE_RADIUS_PX)) {
            if (y < 0 || y >= bitmap.height) continue
            for (x in (sourceX - SAMPLE_RADIUS_PX)..(sourceX + SAMPLE_RADIUS_PX)) {
                if (x < 0 || x >= bitmap.width) continue
                val pixel = bitmap.getPixel(x, y)
                red += (pixel shr 16) and 0xFF
                green += (pixel shr 8) and 0xFF
                blue += pixel and 0xFF
                count++
            }
        }
        if (count == 0) return null
        val averaged = android.graphics.Color.rgb(red / count, green / count, blue / count)
        // Lift very dark samples so tapping a shadow does not produce an
        // invisible clock. Hue and relative saturation are preserved.
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(averaged, hsl)
        hsl[2] = max(hsl[2], MIN_PICKED_LIGHTNESS)
        ColorUtils.HSLToColor(hsl) or (0xFF shl 24)
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun renderStyleThumbnails(
    context: Context,
    hourFormat: String
): Map<ClockStyle, ImageBitmap> {
    val now = System.currentTimeMillis()
    val result = LinkedHashMap<ClockStyle, ImageBitmap>()
    for (candidate in ClockStyle.entries) {
        val renderer = ClockFaceRenderer(context).apply {
            style = candidate
            // Digits only: these pick a face, and a date placed off to one
            // side would make the thumbnail mostly empty bitmap.
            showDate = false
            animateDigits = false
            hourFormatOverride = AtmosphereClockPolicy.hourFormatOverride(hourFormat)
            // Someone standing under the clock, so the Adaptive thumbnail
            // shows what the face does rather than four equal digits.
            if (candidate.adaptsToSubject) {
                sceneSource.set(ClockScene.demo(screenAspect))
            }
        }
        try {
            val rendered = renderer.render(nowMillis = now, uptimeMs = 0L) ?: continue
            if (rendered.width <= 0 || rendered.height <= 0) continue
            val targetWidth = min(THUMBNAIL_WIDTH_PX, rendered.width)
            val targetHeight = (rendered.height.toFloat() * targetWidth / rendered.width)
                .toInt()
                .coerceAtLeast(1)
            result[candidate] = silhouette(
                Bitmap.createScaledBitmap(rendered, targetWidth, targetHeight, true)
            ).asImageBitmap()
        } catch (_: RuntimeException) {
            // A face that will not render is left out of the gallery.
        } catch (_: OutOfMemoryError) {
            break
        } finally {
            renderer.release()
        }
    }
    return result
}

/**
 * Turns a face bitmap into something that can simply be shown.
 *
 * The face holds a distance field rather than coverage — the wallpaper shader
 * reconstructs the digits from it, which is what keeps them sharp at any size
 * — so drawing one straight into a thumbnail would show a soft blur instead of
 * a clock. This does the same reconstruction the shader does: the silhouette
 * is where the field crosses its midpoint.
 */
private fun silhouette(face: Bitmap): Bitmap {
    val width = face.width
    val height = face.height
    if (width <= 0 || height <= 0) return face
    val pixels = IntArray(width * height)
    face.getPixels(pixels, 0, width, 0, 0, width, height)
    for (index in pixels.indices) {
        val field = (pixels[index] ushr 24) / 255f
        // A couple of the scaled bitmap's pixels of softness, which is all the
        // anti-aliasing a thumbnail needs.
        val coverage = ((field - 0.5f) / 0.08f + 0.5f).coerceIn(0f, 1f)
        val alpha = (coverage * 255f).toInt().coerceIn(0, 255)
        pixels[index] = (alpha shl 24) or 0x00FFFFFF
    }
    face.setPixels(pixels, 0, width, 0, 0, width, height)
    return face
}

/** Stands in for the date's real ratio until the face has been measured. */
private const val DEFAULT_DATE_ASPECT = 5.5f

/** How long the gesture hint stays up after it is asked for. */
private const val HINT_MS = 4_000L
/** The tallest the open panel gets before its content scrolls, so the photo stays in view. */
private const val PANEL_MAX_HEIGHT = 300
private val PANEL_COLOR = Color(0xEB121315)
private val ACCENT = Color(0xFF9FD4FF)

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
private const val WHEEL_PX = 240
private const val WHEEL_DP = 132
private const val SAMPLE_RADIUS_PX = 3
private const val MIN_PICKED_LIGHTNESS = 0.55f
