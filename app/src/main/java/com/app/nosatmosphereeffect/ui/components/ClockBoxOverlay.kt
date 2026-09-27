package com.app.nosatmosphereeffect.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.app.nosatmosphereeffect.helper.ClockBoxHandle
import com.app.nosatmosphereeffect.helper.ClockBoxRect
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The frame the user drags to place and size the clock.
 *
 * One finger moves the box, or resizes it from a corner or edge handle; two
 * fingers pinch the selected box larger or smaller and move it, which works
 * anywhere on screen — the way to resize a box whose handles sit near an edge.
 *
 * Replaces the size / width / height sliders: the clock is a rectangle on a
 * photo, so it is set like one — drag inside to move it, drag a corner to
 * change both dimensions, drag an edge to change one. The proposed rectangle
 * is handed back unclamped; the caller owns the limits because it is the one
 * that has to store them.
 *
 * All rectangles are fractions of this view, matching how the clock is stored
 * and how the shaders place it.
 */
@Composable
internal fun ClockBoxOverlay(
    box: ClockBoxRect,
    /**
     * A second box drawn faintly and not dragged — the clock's while the date
     * is being placed, and the date's otherwise. Both are on screen at once so
     * that placing one against the other does not need guesswork.
     */
    passiveBox: ClockBoxRect? = null,
    /** True when the clock is exactly centred, which lights the centre guide. */
    centered: Boolean,
    showHandles: Boolean,
    /**
     * The third argument is true when the gesture grabbed [passiveBox]: the
     * finger landed on the box that is not selected, so that is the one being
     * dragged and the caller should follow the selection to it.
     */
    onBoxChange: (ClockBoxRect, ClockBoxHandle, Boolean) -> Unit,
    onDragStarted: (Boolean) -> Unit,
    onDragFinished: () -> Unit,
    onTap: (Offset) -> Unit,
    /**
     * A two-finger gesture on the selected box: the total zoom since it began,
     * and the total movement of the fingers' centre in view fractions. Called
     * after [onDragStarted] with false, and ended by [onDragFinished].
     */
    onTransform: (zoom: Float, dx: Float, dy: Float) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val touchSlopPx = with(density) { HANDLE_TOUCH_DP.dp.toPx() }
    val handleRadiusPx = with(density) { HANDLE_RADIUS_DP.dp.toPx() }
    val exclusionPx = with(density) { EXCLUSION_DP.dp.toPx() }
    // The gesture detector is installed once and reads everything it needs
    // through these. Keying pointerInput on the box restarted the detector on
    // every movement, so each swipe produced one small step and then had to
    // clear the touch slop all over again.
    val currentBox by rememberUpdatedState(box)
    val currentPassive by rememberUpdatedState(passiveBox)
    val boxChanged by rememberUpdatedState(onBoxChange)
    val dragStarted by rememberUpdatedState(onDragStarted)
    val dragFinished by rememberUpdatedState(onDragFinished)
    val transformed by rememberUpdatedState(onTransform)
    val tapped by rememberUpdatedState(onTap)
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier.fillMaxSize().onSizeChanged { viewSize = it }) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { tapped(it) }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val slop = viewConfiguration.touchSlop
                        var mode = GestureMode.PENDING
                        var handle = ClockBoxHandle.MOVE
                        var startBox = currentBox
                        var grabbedPassive = false
                        var travelled = Offset.Zero
                        var zoom = 1f
                        var pan = Offset.Zero
                        while (true) {
                            val event = awaitPointerEvent()
                            val width = size.width.toFloat()
                            val height = size.height.toFloat()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0 || width <= 0f || height <= 0f) break

                            if (pressed >= 2) {
                                // A second finger turns whatever this was into a
                                // pinch on the selected box; a drag in progress
                                // hands over rather than fighting it.
                                if (mode != GestureMode.PINCH) {
                                    if (mode == GestureMode.DRAG) dragFinished()
                                    mode = GestureMode.PINCH
                                    zoom = 1f
                                    pan = Offset.Zero
                                    dragStarted(false)
                                }
                                zoom *= event.calculateZoom()
                                pan += event.calculatePan()
                                event.changes.forEach { it.consume() }
                                transformed(zoom, pan.x / width, pan.y / height)
                                continue
                            }
                            // Lifting one finger of a pinch does not become a
                            // drag: the gesture ends when the last one lifts.
                            if (mode == GestureMode.PINCH) continue

                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            travelled += change.positionChange()
                            if (mode == GestureMode.PENDING) {
                                if (travelled.getDistance() < slop) continue
                                mode = GestureMode.DRAG
                                val position = down.position
                                val activeHandle =
                                    handleAt(position, currentBox, width, height, touchSlopPx)
                                // The selected box owns the gesture, except when
                                // the finger is on the other one and not on this
                                // one: dragging what you are touching is what
                                // everyone expects.
                                grabbedPassive = currentPassive?.let { passive ->
                                    activeHandle == ClockBoxHandle.MOVE &&
                                        !touches(position, currentBox, width, height, touchSlopPx) &&
                                        touches(position, passive, width, height, touchSlopPx)
                                } ?: false
                                startBox = if (grabbedPassive) currentPassive!! else currentBox
                                handle = if (grabbedPassive) {
                                    handleAt(position, startBox, width, height, touchSlopPx)
                                } else {
                                    activeHandle
                                }
                                dragStarted(grabbedPassive)
                            }
                            change.consume()
                            // Applied to where the box was when the finger went
                            // down, not to wherever it is now: pointer events
                            // arrive faster than recomposition, and chaining
                            // deltas onto a box that has not caught up yet
                            // drops movement.
                            boxChanged(
                                startBox.movedBy(handle, travelled.x / width, travelled.y / height),
                                handle,
                                grabbedPassive
                            )
                        }
                        if (mode != GestureMode.PENDING) dragFinished()
                    }
                }
        ) {
            drawCentreGuide(currentBox, centered)
            currentPassive?.let { drawPassiveBox(it) }
            drawBox(currentBox, showHandles, handleRadiusPx)
        }

        // Keeps the system back gesture off the selected box's handles. With
        // gesture navigation a swipe that starts at the screen's side edge is
        // "back", so a corner resting near the edge could not be dragged in —
        // the box could grow to the edge but never be pulled smaller again.
        // Only the side handles need it; that gesture lives on the side edges.
        if (showHandles && viewSize.width > 0 && viewSize.height > 0) {
            val width = viewSize.width.toFloat()
            val height = viewSize.height.toFloat()
            val half = exclusionPx / 2f
            val centreY = (box.top + box.bottom) / 2f
            listOf(
                Offset(box.left, box.top), Offset(box.left, centreY), Offset(box.left, box.bottom),
                Offset(box.right, box.top), Offset(box.right, centreY), Offset(box.right, box.bottom)
            ).forEach { point ->
                Box(
                    Modifier
                        .offset {
                            IntOffset(
                                (point.x * width - half).roundToInt(),
                                (point.y * height - half).roundToInt()
                            )
                        }
                        .size(EXCLUSION_DP.dp)
                        .systemGestureExclusion()
                )
            }
        }
    }
}

private enum class GestureMode { PENDING, DRAG, PINCH }

/** The proposed rectangle after dragging [handle] by a fraction of the view. */
private fun ClockBoxRect.movedBy(handle: ClockBoxHandle, dx: Float, dy: Float): ClockBoxRect = when (handle) {
    ClockBoxHandle.MOVE -> ClockBoxRect(left + dx, top + dy, right + dx, bottom + dy)
    // Every resize anchors the opposite side, so the corner under the finger
    // is the one that moves.
    ClockBoxHandle.TOP_LEFT -> ClockBoxRect(left + dx, top + dy, right, bottom)
    ClockBoxHandle.TOP_RIGHT -> ClockBoxRect(left, top + dy, right + dx, bottom)
    ClockBoxHandle.BOTTOM_LEFT -> ClockBoxRect(left + dx, top, right, bottom + dy)
    ClockBoxHandle.BOTTOM_RIGHT -> ClockBoxRect(left, top, right + dx, bottom + dy)
    ClockBoxHandle.LEFT -> ClockBoxRect(left + dx, top, right, bottom)
    ClockBoxHandle.RIGHT -> ClockBoxRect(left, top, right + dx, bottom)
    ClockBoxHandle.TOP -> ClockBoxRect(left, top + dy, right, bottom)
    ClockBoxHandle.BOTTOM -> ClockBoxRect(left, top, right, bottom + dy)
}

/** True when [position] is inside [box] or within [slop] of its edges. */
private fun touches(
    position: Offset,
    box: ClockBoxRect,
    viewWidth: Float,
    viewHeight: Float,
    slop: Float
): Boolean {
    return position.x >= box.left * viewWidth - slop &&
        position.x <= box.right * viewWidth + slop &&
        position.y >= box.top * viewHeight - slop &&
        position.y <= box.bottom * viewHeight + slop
}

private fun handleAt(
    position: Offset,
    box: ClockBoxRect,
    viewWidth: Float,
    viewHeight: Float,
    slop: Float
): ClockBoxHandle {
    if (viewWidth <= 0f || viewHeight <= 0f) return ClockBoxHandle.MOVE
    val left = box.left * viewWidth
    val right = box.right * viewWidth
    val top = box.top * viewHeight
    val bottom = box.bottom * viewHeight
    // The grab zones are capped at a share of the box, so a small one keeps a
    // middle to drag. At a fixed size they met in the centre of the date's
    // box and every attempt to move it resized it instead — the only way to
    // place it was to make it big, move it, and shrink it again.
    val slopX = min(slop, (right - left) * HANDLE_SHARE)
    val slopY = min(slop, (bottom - top) * HANDLE_SHARE)
    val nearLeft = abs(position.x - left) <= slopX
    val nearRight = abs(position.x - right) <= slopX
    val nearTop = abs(position.y - top) <= slopY
    val nearBottom = abs(position.y - bottom) <= slopY
    val withinRows = position.y >= top - slopY && position.y <= bottom + slopY
    val withinColumns = position.x >= left - slopX && position.x <= right + slopX

    return when {
        nearLeft && nearTop -> ClockBoxHandle.TOP_LEFT
        nearRight && nearTop -> ClockBoxHandle.TOP_RIGHT
        nearLeft && nearBottom -> ClockBoxHandle.BOTTOM_LEFT
        nearRight && nearBottom -> ClockBoxHandle.BOTTOM_RIGHT
        nearLeft && withinRows -> ClockBoxHandle.LEFT
        nearRight && withinRows -> ClockBoxHandle.RIGHT
        nearTop && withinColumns -> ClockBoxHandle.TOP
        nearBottom && withinColumns -> ClockBoxHandle.BOTTOM
        else -> ClockBoxHandle.MOVE
    }
}

private fun DrawScope.drawCentreGuide(box: ClockBoxRect, centered: Boolean) {
    val x = size.width / 2f
    val color = if (centered) CENTRE_SNAPPED_COLOR else CENTRE_GUIDE_COLOR
    drawLine(
        color = color,
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = if (centered) 2f else 1f
    )
    if (!centered) return
    // A short tick at the clock's own centre confirms the snap.
    val y = (box.top + box.bottom) / 2f * size.height
    drawLine(
        color = color,
        start = Offset(x - size.width * 0.04f, y),
        end = Offset(x + size.width * 0.04f, y),
        strokeWidth = 2f
    )
}

/** The box that is not being dragged: shown, but visibly not the target. */
private fun DrawScope.drawPassiveBox(box: ClockBoxRect) {
    val width = box.width * size.width
    val height = box.height * size.height
    if (width <= 0f || height <= 0f) return
    drawRect(
        color = PASSIVE_BOX_COLOR,
        topLeft = Offset(box.left * size.width, box.top * size.height),
        size = Size(width, height),
        style = Stroke(width = 1f)
    )
}

private fun DrawScope.drawBox(box: ClockBoxRect, showHandles: Boolean, handleRadius: Float) {
    val left = box.left * size.width
    val top = box.top * size.height
    val width = box.width * size.width
    val height = box.height * size.height
    if (width <= 0f || height <= 0f) return

    drawRect(
        color = BOX_COLOR,
        topLeft = Offset(left, top),
        size = Size(width, height),
        style = Stroke(width = 2f)
    )
    if (!showHandles) return

    val right = left + width
    val bottom = top + height
    val centreX = left + width / 2f
    val centreY = top + height / 2f
    listOf(
        Offset(left, top),
        Offset(right, top),
        Offset(left, bottom),
        Offset(right, bottom)
    ).forEach { corner ->
        drawCircle(color = HANDLE_FILL, radius = handleRadius, center = corner)
        drawCircle(
            color = HANDLE_BORDER,
            radius = handleRadius,
            center = corner,
            style = Stroke(width = 2f)
        )
    }
    listOf(
        Offset(centreX, top),
        Offset(centreX, bottom),
        Offset(left, centreY),
        Offset(right, centreY)
    ).forEach { edge ->
        drawCircle(color = HANDLE_FILL, radius = handleRadius * 0.62f, center = edge)
    }
}

private const val HANDLE_TOUCH_DP = 28
/** Square kept free of the back gesture around each side handle. */
private const val EXCLUSION_DP = 48
/** The most of a box's width or height either grab zone may take. */
private const val HANDLE_SHARE = 0.28f
private const val HANDLE_RADIUS_DP = 7
private val BOX_COLOR = Color.White.copy(alpha = 0.85f)
private val PASSIVE_BOX_COLOR = Color.White.copy(alpha = 0.3f)
private val HANDLE_FILL = Color.White.copy(alpha = 0.95f)
private val HANDLE_BORDER = Color.Black.copy(alpha = 0.35f)
private val CENTRE_GUIDE_COLOR = Color.White.copy(alpha = 0.28f)
private val CENTRE_SNAPPED_COLOR = Color(0xFF7FD1FF)
