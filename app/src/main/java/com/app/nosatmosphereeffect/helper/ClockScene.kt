package com.app.nosatmosphereeffect.helper

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What is behind the clock: the wallpaper image's shape and, once
 * segmentation has run, a small copy of the subject mask. The Adaptive face
 * reads it to decide how far each digit may run before it would touch the
 * subject.
 *
 * The grid is in the wallpaper *image's* coordinates (0..1 across the
 * bitmap the renderer draws), not the screen's. The image is wider than the
 * screen when the home screen scrolls, and the clock is fixed to the screen,
 * so [ClockSceneViewport] maps one onto the other every time the face is
 * measured.
 *
 * Immutable: built off the render thread and swapped in whole.
 */
class ClockScene internal constructor(
    /** The image's width/height. */
    val sourceAspect: Float,
    /** 0..255 per cell; null until segmentation has answered. */
    private val subject: ByteArray?,
    private val subjectWidth: Int,
    private val subjectHeight: Int
) {
    /** True once segmentation has answered, whether or not it found anyone. */
    val subjectKnown: Boolean
        get() = subject != null

    internal fun withSubject(grid: ByteArray, width: Int, height: Int): ClockScene =
        ClockScene(sourceAspect, grid, width, height)

    /** Subject coverage at image coordinates (u, v), 0..1. */
    fun subjectAt(u: Float, v: Float): Float {
        val grid = subject ?: return 0f
        if (subjectWidth <= 0 || subjectHeight <= 0) return 0f
        if (u < 0f || u > 1f || v < 0f || v > 1f) return 0f
        val x = (u * subjectWidth).toInt().coerceIn(0, subjectWidth - 1)
        val y = (v * subjectHeight).toInt().coerceIn(0, subjectHeight - 1)
        return (grid[y * subjectWidth + x].toInt() and 0xFF) / 255f
    }

    companion object {
        /** Subject grid resolution along the image's longer side. */
        private const val SUBJECT_CELLS = 192

        /** The scene for [bitmap] before its mask is known. Only its shape is read. */
        fun fromWallpaper(bitmap: Bitmap): ClockScene? {
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
            return ClockScene(
                sourceAspect = bitmap.width.toFloat() / bitmap.height.toFloat(),
                subject = null,
                subjectWidth = 0,
                subjectHeight = 0
            )
        }

        /**
         * The subject grid for a mask, or an empty one when segmentation found
         * no subject. [mask] stays owned by the caller.
         */
        fun subjectGrid(mask: Bitmap?): Triple<ByteArray, Int, Int> {
            if (mask == null || mask.isRecycled || mask.width <= 0 || mask.height <= 0) {
                return Triple(ByteArray(1), 1, 1)
            }
            val (width, height) = gridSize(mask.width, mask.height, SUBJECT_CELLS)
            val pixels = sample(mask, width, height) ?: return Triple(ByteArray(1), 1, 1)
            val readAlpha = mask.config == Bitmap.Config.ALPHA_8
            val grid = ByteArray(width * height) { index ->
                val pixel = pixels[index]
                val value = if (readAlpha) Color.alpha(pixel) else Color.red(pixel)
                value.toByte()
            }
            return Triple(grid, width, height)
        }

        /**
         * A made-up portrait for the style gallery: someone standing under the
         * middle of a clock in its default place, so the thumbnail shows the
         * digits running long at the sides and stopping short over the head.
         */
        fun demo(screenAspect: Float): ClockScene {
            val aspect = if (screenAspect.isFinite() && screenAspect > 0f) screenAspect else 0.46f
            val width = 48
            val height = (width / aspect).roundToInt().coerceIn(2, 256)
            val grid = ByteArray(width * height)
            for (y in 0 until height) {
                val v = (y + 0.5f) / height
                for (x in 0 until width) {
                    val u = (x + 0.5f) / width
                    // A head, and shoulders below it.
                    val headX = (u - 0.56f) / 0.085f
                    val headY = (v - 0.335f) / 0.055f
                    val head = headX * headX + headY * headY <= 1f
                    val shoulders = v > 0.39f && kotlin.math.abs(u - 0.56f) < 0.08f + (v - 0.39f) * 2.2f
                    if (head || shoulders) grid[y * width + x] = 0xFF.toByte()
                }
            }
            return ClockScene(
                sourceAspect = aspect,
                subject = grid,
                subjectWidth = width,
                subjectHeight = height
            )
        }

        private fun gridSize(sourceWidth: Int, sourceHeight: Int, cells: Int): Pair<Int, Int> {
            val longer = max(sourceWidth, sourceHeight).toFloat()
            val width = (sourceWidth / longer * cells).roundToInt().coerceAtLeast(2)
            val height = (sourceHeight / longer * cells).roundToInt().coerceAtLeast(2)
            return width to height
        }

        private fun sample(bitmap: Bitmap, width: Int, height: Int): IntArray? {
            return try {
                val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                val pixels = IntArray(width * height)
                scaled.getPixels(pixels, 0, width, 0, 0, width, height)
                if (scaled !== bitmap) scaled.recycle()
                pixels
            } catch (_: RuntimeException) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
        }

    }
}

/**
 * Screen (0..1, y-down) to image (0..1) coordinates, as an axis-aligned
 * scale and offset: `u = uOffset + x * uScale`, and likewise for v.
 */
data class ClockSceneViewport(
    val uOffset: Float,
    val uScale: Float,
    val vOffset: Float,
    val vScale: Float
) {
    fun u(screenX: Float): Float = uOffset + screenX * uScale
    fun v(screenY: Float): Float = vOffset + screenY * vScale

    companion object {
        /**
         * The live wallpaper's mapping. The renderers fit the image to cover
         * the screen's height and pan a screen-wide window across its width
         * (see the vertex shaders), so the window follows from the two aspect
         * ratios and [scrollOffsetX] says where it is.
         */
        fun forScroll(scrollOffsetX: Float, screenAspect: Float, imageAspect: Float): ClockSceneViewport {
            val window = if (imageAspect > 0f && screenAspect > 0f) {
                (screenAspect / imageAspect).coerceIn(0.01f, 1f)
            } else {
                1f
            }
            val offset = scrollOffsetX.coerceIn(0f, 1f)
            return ClockSceneViewport(
                uOffset = offset * (1f - window),
                uScale = window,
                vOffset = 0f,
                vScale = 1f
            )
        }

        /** An image centre-cropped into a view, as the calibration screen draws it. */
        fun forCenterCrop(viewAspect: Float, imageAspect: Float): ClockSceneViewport {
            if (viewAspect <= 0f || imageAspect <= 0f) return ClockSceneViewport(0f, 1f, 0f, 1f)
            return if (imageAspect > viewAspect) {
                val scale = viewAspect / imageAspect
                ClockSceneViewport((1f - scale) / 2f, scale, 0f, 1f)
            } else {
                val scale = imageAspect / viewAspect
                ClockSceneViewport(0f, 1f, (1f - scale) / 2f, scale)
            }
        }
    }
}

/**
 * Receives the images a renderer hands to segmentation, so the clock can
 * build its [ClockScene] from exactly the pixels on screen.
 *
 * Called by [SubjectMaskCoordinator] from whichever thread requested the mask
 * and whichever thread delivered it. Neither bitmap may be kept: both are
 * recycled by their owners as soon as the call returns.
 */
interface ClockSceneSink {
    fun onSceneImage(generation: Long, image: Bitmap)
    /** [mask] is null when segmentation ran and found no subject. */
    fun onSceneMask(generation: Long, mask: Bitmap?)
}

/**
 * The scene for one face: built as images and masks arrive, read by the face
 * on its render thread. Thread-safe by being a single volatile reference to
 * an immutable scene.
 */
class ClockSceneSource : ClockSceneSink {
    @Volatile var scene: ClockScene? = null
        private set

    /** Bumped whenever [scene] changes, so the face knows to re-measure. */
    @Volatile var revision: Int = 0
        private set

    @Volatile private var generation: Long = Long.MIN_VALUE

    override fun onSceneImage(generation: Long, image: Bitmap) {
        val built = ClockScene.fromWallpaper(image) ?: return
        synchronized(this) {
            this.generation = generation
            scene = built
            revision++
        }
    }

    override fun onSceneMask(generation: Long, mask: Bitmap?) {
        val base = scene ?: return
        if (generation != this.generation) return
        val (grid, width, height) = ClockScene.subjectGrid(mask)
        synchronized(this) {
            // The image may have moved on while the grid was being built.
            if (generation != this.generation || scene !== base) return
            scene = base.withSubject(grid, width, height)
            revision++
        }
    }

    /** For the calibration screen, which owns its own image and mask. */
    fun set(scene: ClockScene?) {
        synchronized(this) {
            generation = Long.MIN_VALUE
            this.scene = scene
            revision++
        }
    }
}
