package com.app.nosatmosphereeffect.helper

import kotlin.math.abs

/** Which part of a box a drag grabbed. */
enum class ClockBoxHandle {
    MOVE,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    LEFT,
    RIGHT,
    TOP,
    BOTTOM;

    val resizesWidth: Boolean
        get() = this != MOVE && this != TOP && this != BOTTOM

    val resizesHeight: Boolean
        get() = this != MOVE && this != LEFT && this != RIGHT
}

/** A rectangle in screen fractions. Deliberately not Compose's Rect: this is testable. */
data class ClockBoxRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom
}

/**
 * A stored placement: where something sits on screen and how big it is.
 *
 * [height] is a fraction of the screen's height and [top] the fraction it
 * starts at, both measured on the thing the user actually sees — the digits,
 * or the date — rather than on the bitmap they are drawn into. [widthScale]
 * is 1 at natural proportions.
 *
 * ## Why this describes the content rather than the texture
 *
 * It used to hold the face texture's rectangle, and the digits' box was
 * derived from it. That coupled the clock's position to the bitmap's padding:
 * the same stored numbers drew the clock somewhere slightly different on a
 * face with a different margin, and once the date became freely placeable —
 * which changes the bitmap's size and shape — it would have moved the clock
 * every time the date was dragged. Storing what the user sees means the
 * digits stay exactly where they were put, whatever the face does around
 * them, and the texture rectangle is worked out per frame instead.
 */
data class ClockPlacement(
    val centerX: Float,
    val top: Float,
    val height: Float,
    val widthScale: Float
)

/**
 * Turns box drags into stored placement, and back.
 *
 * ## Why this is a pure object with tests
 *
 * Two rectangles are in play — what the user drags and what the shaders
 * sample — and the conversion runs both ways on every frame of a gesture.
 * Doing that inline in the calibration screen produced a resize that also
 * nudged the clock and a move that reset its size, neither of which is
 * visible in a code review of the screen. Here each rule is one function and
 * each one has a test.
 *
 * Every gesture is resolved against the placement as it was when the finger
 * went down, so a drag cannot accumulate rounding drift or react to its own
 * clamping.
 */
object ClockBoxPlacement {

    /** Snap to the screen centre within this fraction while moving. */
    const val CENTRE_SNAP = 0.015f

    /**
     * The box the user sees and drags.
     *
     * [contentAspect] is the drawn content's own width/height ratio in pixels
     * — [ClockFaceBox.contentAspect] for the digits, the date line's natural
     * ratio for the date.
     */
    fun contentBox(
        placement: ClockPlacement,
        contentAspect: Float,
        screenAspect: Float
    ): ClockBoxRect {
        val height = placement.height
        val width = if (screenAspect > 0f && contentAspect > 0f) {
            height * contentAspect * placement.widthScale / screenAspect
        } else {
            height
        }
        return ClockBoxRect(
            left = placement.centerX - width / 2f,
            top = placement.top,
            right = placement.centerX + width / 2f,
            bottom = placement.top + height
        )
    }

    /**
     * The whole face texture, in screen fractions: what the renderers hand
     * the shader. Larger than [contentBox] — the bitmap carries margin for
     * the digit animations and room for the date wherever it was placed — and
     * positioned so the digits land exactly on their own box.
     */
    fun textureBox(
        placement: ClockPlacement,
        face: ClockFaceBox,
        screenAspect: Float
    ): ClockBoxRect {
        val content = contentBox(placement, face.contentAspect, screenAspect)
        val textureWidth = content.width / face.widthFraction
        val textureHeight = content.height / face.heightFraction
        val left = content.left - face.left * textureWidth
        val top = content.top - face.top * textureHeight
        return ClockBoxRect(
            left = left,
            top = top,
            right = left + textureWidth,
            bottom = top + textureHeight
        )
    }

    /**
     * The placement after dragging [handle] so the box becomes [proposed],
     * starting from [start].
     *
     * The side opposite the handle stays exactly where it was, a move keeps
     * the size to the float, and a resize never moves the box sideways unless
     * the handle itself is horizontal.
     */
    fun apply(
        start: ClockPlacement,
        proposed: ClockBoxRect,
        handle: ClockBoxHandle,
        contentAspect: Float,
        screenAspect: Float
    ): ClockPlacement {
        if (screenAspect <= 0f || contentAspect <= 0f) return start
        val startBox = contentBox(start, contentAspect, screenAspect)

        // A move must not touch the size at all, so it keeps the stored
        // numbers rather than reconstructing them from the box. The content
        // may fill the screen but not leave it, so the height stops there.
        val height = if (handle.resizesHeight) {
            AtmosphereClockPolicy.sanitizeHeight(proposed.height).coerceAtMost(1f)
        } else {
            start.height
        }

        val requestedWidthScale = when {
            handle.resizesWidth -> AtmosphereClockPolicy.sanitizeAxisScale(
                proposed.width * screenAspect / (height * contentAspect)
            )
            // A vertical-only resize keeps the shape: the stored width scale
            // is relative to the height, so it needs rescaling to hold the
            // width steady.
            handle.resizesHeight -> AtmosphereClockPolicy.sanitizeAxisScale(
                start.widthScale * start.height / height
            )
            else -> start.widthScale
        }
        // The same limit across: something wider than the screen could not be
        // placed without part of it hanging off the side.
        val maxWidthScale = screenAspect / (height * contentAspect)
        val widthScale = requestedWidthScale.coerceAtMost(maxWidthScale)

        val settled = ClockPlacement(
            centerX = start.centerX,
            top = start.top,
            height = height,
            widthScale = widthScale
        )
        val box = contentBox(settled, contentAspect, screenAspect)

        val left = when (handle) {
            ClockBoxHandle.MOVE -> proposed.left
            ClockBoxHandle.TOP_LEFT, ClockBoxHandle.BOTTOM_LEFT, ClockBoxHandle.LEFT ->
                startBox.right - box.width
            ClockBoxHandle.TOP_RIGHT, ClockBoxHandle.BOTTOM_RIGHT, ClockBoxHandle.RIGHT ->
                startBox.left
            // Vertical-only handles keep it centred where it was.
            else -> startBox.centerX - box.width / 2f
        }
        val top = when (handle) {
            ClockBoxHandle.MOVE -> proposed.top
            ClockBoxHandle.TOP_LEFT, ClockBoxHandle.TOP_RIGHT, ClockBoxHandle.TOP ->
                startBox.bottom - box.height
            else -> startBox.top
        }

        // The only placement rule: it stays on screen. Within that it goes
        // anywhere at any size — including hard against an edge, which the old
        // texture-space limits made impossible for a tall clock.
        val clampedLeft = left.coerceIn(
            minOf(0f, 1f - box.width),
            maxOf(0f, 1f - box.width)
        )
        val clampedTop = top.coerceIn(
            minOf(0f, 1f - box.height),
            maxOf(0f, 1f - box.height)
        )
        val centerX = clampedLeft + box.width / 2f

        return ClockPlacement(
            centerX = AtmosphereClockPolicy.sanitizeCenterX(
                // Only a move snaps: snapping mid-resize would slide it
                // sideways while the user is only changing its size.
                if (handle == ClockBoxHandle.MOVE && abs(centerX - 0.5f) < CENTRE_SNAP) {
                    0.5f
                } else {
                    centerX
                }
            ),
            top = AtmosphereClockPolicy.sanitizeTop(clampedTop),
            height = height,
            widthScale = widthScale
        )
    }

    /**
     * Where the date sits relative to the digits, which is how the face
     * bitmap has to be told about it — see [ClockDateLayout].
     *
     * [contentAspect] is the digits' pixel ratio and [dateAspect] the date
     * line's; both boxes are built the same way the user dragged them, so the
     * result is exactly what the calibration screen showed.
     */
    fun relativeDateBox(
        clock: ClockPlacement,
        date: ClockPlacement,
        contentAspect: Float,
        dateAspect: Float,
        screenAspect: Float
    ): ClockDateLayout {
        val clockBox = contentBox(clock, contentAspect, screenAspect)
        val dateBox = contentBox(date, dateAspect, screenAspect)
        val width = clockBox.width
        val height = clockBox.height
        if (width <= 1e-5f || height <= 1e-5f) {
            return ClockDateLayout(offsetX = 0f, offsetY = -0.4f, width = 1f, height = 0.2f)
        }
        return ClockDateLayout(
            offsetX = (dateBox.left - clockBox.left) / width,
            offsetY = (dateBox.top - clockBox.top) / height,
            width = dateBox.width / width,
            height = dateBox.height / height
        )
    }

    /** True when [placement] sits exactly on the screen's centre line. */
    fun isCentred(placement: ClockPlacement): Boolean = abs(placement.centerX - 0.5f) < 0.001f

    /**
     * Keeps free space between a box and the screen's sides, so its handles
     * stay clear of the system back gesture along the edges.
     */
    const val SIDE_MARGIN = 0.03f

    /**
     * [placement] shrunk and moved, only as far as needed, so its box fits on
     * screen with [SIDE_MARGIN] either side; [placement] itself when it
     * already does.
     *
     * A box's width follows from its height and the content's shape, so the
     * same stored size comes out wider when the shape changes: a size set on
     * a stacked face overflows the screen after switching to a row, and a
     * date grows when the day's name gets longer. The height gives way first,
     * which keeps the shape the user chose; the width scale only gives way
     * when the height is already at its floor.
     */
    fun fitOnScreen(
        placement: ClockPlacement,
        contentAspect: Float,
        screenAspect: Float
    ): ClockPlacement {
        if (screenAspect <= 0f || contentAspect <= 0f) return placement
        val maxWidth = 1f - 2f * SIDE_MARGIN
        var height = placement.height.coerceAtMost(1f)
        var widthScale = placement.widthScale
        fun widthOf(h: Float, scale: Float) = h * contentAspect * scale / screenAspect
        if (widthOf(height, widthScale) > maxWidth) {
            height = AtmosphereClockPolicy.sanitizeHeight(
                maxWidth * screenAspect / (contentAspect * widthScale)
            )
            if (widthOf(height, widthScale) > maxWidth) {
                widthScale = AtmosphereClockPolicy.sanitizeAxisScale(
                    maxWidth * screenAspect / (contentAspect * height)
                )
            }
        }
        val width = widthOf(height, widthScale)
        val half = width / 2f
        val centerX = if (width <= maxWidth) {
            placement.centerX.coerceIn(SIDE_MARGIN + half, 1f - SIDE_MARGIN - half)
        } else {
            0.5f
        }
        val top = placement.top.coerceIn(0f, maxOf(0f, 1f - height))
        val fitted = ClockPlacement(centerX, top, height, widthScale)
        return if (fitted == placement) placement else fitted
    }

    /**
     * A two-finger gesture: [start] scaled by [zoom] about its own centre and
     * moved by ([dx], [dy]) screen fractions, then fitted on screen. Both
     * dimensions scale together, so the shape is kept — a pinch is the easy
     * way to resize without reaching for a corner near the screen's edge.
     */
    fun transform(
        start: ClockPlacement,
        zoom: Float,
        dx: Float,
        dy: Float,
        contentAspect: Float,
        screenAspect: Float
    ): ClockPlacement {
        if (screenAspect <= 0f || contentAspect <= 0f || !zoom.isFinite() || zoom <= 0f) {
            return start
        }
        val height = AtmosphereClockPolicy.sanitizeHeight(start.height * zoom)
        val centreY = start.top + start.height / 2f + dy
        val moved = ClockPlacement(
            centerX = start.centerX + dx,
            top = centreY - height / 2f,
            height = height,
            widthScale = start.widthScale
        )
        val fitted = fitOnScreen(moved, contentAspect, screenAspect)
        val snapped = if (abs(fitted.centerX - 0.5f) < CENTRE_SNAP) fitted.copy(centerX = 0.5f) else fitted
        return snapped.copy(
            centerX = AtmosphereClockPolicy.sanitizeCenterX(snapped.centerX),
            top = AtmosphereClockPolicy.sanitizeTop(snapped.top)
        )
    }

    /** The date's width as a share of the clock's when it is first shown. */
    const val DATE_WIDTH_SHARE = 0.62f

    /** Space between the clock and a newly shown date, in screen heights. */
    const val DATE_GAP = 0.012f

    /**
     * Where a newly shown date goes: sized from the clock — [DATE_WIDTH_SHARE]
     * of its width — centred over it, and above it, where a date reads first
     * and never sits under the digits. Below the clock only when there is no
     * room above, under the status bar.
     */
    fun dateBesideClock(
        clock: ClockPlacement,
        contentAspect: Float,
        dateAspect: Float,
        screenAspect: Float,
        topInset: Float = DATE_TOP_INSET
    ): ClockPlacement {
        if (screenAspect <= 0f || contentAspect <= 0f || dateAspect <= 0f) {
            return AtmosphereClockPolicy.DEFAULT_DATE_PLACEMENT
        }
        val clockBox = contentBox(clock, contentAspect, screenAspect)
        val height = AtmosphereClockPolicy.sanitizeHeight(
            clockBox.width * DATE_WIDTH_SHARE * screenAspect / dateAspect
        ).coerceAtMost(clockBox.height.coerceAtLeast(AtmosphereClockPolicy.MIN_HEIGHT))
        val above = clockBox.top - DATE_GAP - height
        val top = if (above >= topInset) above else clockBox.bottom + DATE_GAP
        return fitOnScreen(
            ClockPlacement(
                centerX = clockBox.centerX,
                top = top,
                height = height,
                widthScale = 1f
            ),
            dateAspect,
            screenAspect
        )
    }

    /** Roughly the status bar, as a share of the screen's height. */
    const val DATE_TOP_INSET = 0.045f
}
