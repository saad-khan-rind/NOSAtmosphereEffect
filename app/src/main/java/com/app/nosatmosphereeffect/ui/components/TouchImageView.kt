package com.app.nosatmosphereeffect.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.WindowManager
import androidx.appcompat.widget.AppCompatImageView
import com.app.nosatmosphereeffect.image.MatrixStatePolicy
import com.app.nosatmosphereeffect.image.WallpaperFitHelper
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pan/zoom crop view that ALSO previews the selected wallpaper fit mode live.
 *
 * - Screen Fill: pan/zoom to frame (image always covers the screen).
 * - Fit Image: whole image shown; empty space filled with black / repeat / mirror
 *   bars. The user can still zoom/pan within the fitted image.
 * - Stretch: image distorted to fill the screen (framing is irrelevant).
 * - Rotate to Fit: image rotated 90 deg when its orientation differs from the
 *   screen, then fitted like "Fit Image".
 *
 * Whatever is shown on screen is exactly what getCroppedBitmap() returns, so the
 * preview is WYSIWYG: the saved wallpaper already contains the chosen framing and
 * any fill bars.
 */
class TouchImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private var matrixCurrent = Matrix()
    private var mode = 0 // 0=NONE, 1=DRAG, 2=ZOOM

    private var saveScale = 1f
    private var minScale = 1f
    private var maxScale = 5f

    private var viewWidth = 0f
    private var viewHeight = 0f

    private var targetWidth = 0
    private var targetHeight = 0

    private var origWidth = 0f
    private var origHeight = 0f

    private var sourceBitmap: Bitmap? = null
    private var displayBitmap: Bitmap? = null
    private var rotatedBitmap: Bitmap? = null
    private var fitMode: String = WallpaperFitHelper.MODE_FILL
    private var fillMode: String = WallpaperFitHelper.FILL_BLACK
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var pendingSavedMatrix: FloatArray? = null

    private val last = PointF()
    private val start = PointF()
    private val m = FloatArray(9)

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    init {
        super.setClickable(true)
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, GestureListener())

        scaleType = ScaleType.MATRIX
        imageMatrix = matrixCurrent

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = windowManager.currentWindowMetrics
        targetWidth = metrics.bounds.width()
        targetHeight = metrics.bounds.height()

        setOnTouchListener { _, event ->
            // Stretch has no framing to adjust; treat touches only as taps.
            if (fitMode == WallpaperFitHelper.MODE_STRETCH) {
                handleTapOnly(event)
                return@setOnTouchListener true
            }

            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)

            val curr = PointF(event.x, event.y)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    last.set(curr)
                    start.set(last)
                    mode = 1 // DRAG
                }

                MotionEvent.ACTION_MOVE -> {
                    if (mode == 1) { // DRAG
                        val deltaX = curr.x - last.x
                        val deltaY = curr.y - last.y

                        val fixTransX = getFixDragTrans(deltaX, viewWidth, origWidth * saveScale)
                        val fixTransY = getFixDragTrans(deltaY, viewHeight, origHeight * saveScale)

                        matrixCurrent.postTranslate(fixTransX, fixTransY)
                        fixTrans()
                        last.set(curr.x, curr.y)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    mode = 0
                    val xDiff = abs(curr.x - start.x).toInt()
                    val yDiff = abs(curr.y - start.y).toInt()
                    if (xDiff < 3 && yDiff < 3) performClick()
                }

                MotionEvent.ACTION_POINTER_UP -> mode = 0
            }

            imageMatrix = matrixCurrent
            invalidate()
            true
        }
    }

    private fun handleTapOnly(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> start.set(event.x, event.y)
            MotionEvent.ACTION_UP ->
                if (abs(event.x - start.x) < 3 && abs(event.y - start.y) < 3) performClick()
        }
    }

    fun setInitialImage(bitmap: Bitmap, savedMatrixValues: FloatArray? = null) {
        super.setImageBitmap(bitmap)
        sourceBitmap = bitmap
        displayBitmap = bitmap
        rotatedBitmap = null
        origWidth = bitmap.width.toFloat()
        origHeight = bitmap.height.toFloat()
        pendingSavedMatrix = MatrixStatePolicy.copyIfValid(savedMatrixValues)

        post {
            viewWidth = width.toFloat()
            viewHeight = height.toFloat()

            updateDisplayForMode(resetPlacement = pendingSavedMatrix == null)

            pendingSavedMatrix?.let { restoreMatrix(it) }
            pendingSavedMatrix = null

            imageMatrix = matrixCurrent
            invalidate()
        }
    }

    /** Switches the previewed fit mode; re-frames to the mode's natural starting point. */
    fun setFitMode(fit: String, fill: String) {
        fitMode = fit
        fillMode = fill
        if (viewWidth > 0f && sourceBitmap != null) {
            updateDisplayForMode(resetPlacement = true)
            invalidate()
        }
    }

    // Picks the display bitmap (rotates for ROTATE_FIT), recomputes scale bounds,
    // and optionally resets to the mode's base placement.
    private fun updateDisplayForMode(resetPlacement: Boolean) {
        val src = sourceBitmap ?: return

        displayBitmap = if (fitMode == WallpaperFitHelper.MODE_ROTATE_FIT) {
            val srcLandscape = src.width > src.height
            val screenLandscape = targetWidth > targetHeight
            if (srcLandscape != screenLandscape) {
                if (rotatedBitmap == null) {
                    val rot = Matrix().apply { postRotate(90f) }
                    rotatedBitmap = Bitmap.createBitmap(src, 0, 0, src.width, src.height, rot, true)
                }
                rotatedBitmap
            } else src
        } else src

        val db = displayBitmap ?: src
        origWidth = db.width.toFloat()
        origHeight = db.height.toFloat()

        val scaleX = targetWidth.toFloat() / origWidth
        val scaleY = targetHeight.toFloat() / origHeight
        minScale = when (fitMode) {
            WallpaperFitHelper.MODE_FILL -> max(scaleX, scaleY) // cover the screen
            else -> min(scaleX, scaleY)                          // fit inside the screen
        }
        if (saveScale < minScale) saveScale = minScale

        if (resetPlacement) applyBasePlacement()
    }

    private fun applyBasePlacement() {
        matrixCurrent.reset()
        matrixCurrent.setScale(minScale, minScale)
        val redundantXSpace = viewWidth - (minScale * origWidth)
        val redundantYSpace = viewHeight - (minScale * origHeight)
        matrixCurrent.postTranslate(redundantXSpace / 2f, redundantYSpace / 2f)
        saveScale = minScale
    }

    private fun restoreMatrix(values: FloatArray) {
        matrixCurrent.setValues(values)
        saveScale = values[Matrix.MSCALE_X]
        if (saveScale < minScale) {
            applyBasePlacement()
        } else {
            fixTrans()
        }
    }

    fun getCurrentMatrixValues(): FloatArray {
        val values = FloatArray(9)
        matrixCurrent.getValues(values)
        return values
    }

    /** Returns the screen-sized composite EXACTLY as previewed (framing + any fill bars). */
    fun getCroppedBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // The preview is laid out in view pixels; scale onto the screen-sized canvas.
        if (viewWidth > 0f && viewHeight > 0f) {
            canvas.scale(targetWidth / viewWidth, targetHeight / viewHeight)
        }
        renderComposite(canvas)
        return bitmap
    }

    override fun onDraw(canvas: Canvas) {
        renderComposite(canvas)
    }

    private fun renderComposite(canvas: Canvas) {
        val db = displayBitmap ?: return
        if (viewWidth <= 0f || viewHeight <= 0f) return

        when (fitMode) {
            WallpaperFitHelper.MODE_STRETCH -> {
                val stretch = Matrix()
                stretch.setScale(viewWidth / db.width.toFloat(), viewHeight / db.height.toFloat())
                canvas.drawBitmap(db, stretch, drawPaint)
            }

            WallpaperFitHelper.MODE_FIT, WallpaperFitHelper.MODE_ROTATE_FIT -> {
                if (fillMode == WallpaperFitHelper.FILL_BLACK) {
                    canvas.drawColor(Color.BLACK)
                    canvas.drawBitmap(db, matrixCurrent, drawPaint)
                } else {
                    val tile = if (fillMode == WallpaperFitHelper.FILL_MIRROR)
                        Shader.TileMode.MIRROR else Shader.TileMode.REPEAT
                    val shader = BitmapShader(db, tile, tile)
                    shader.setLocalMatrix(matrixCurrent)
                    drawPaint.shader = shader
                    canvas.drawRect(0f, 0f, viewWidth, viewHeight, drawPaint)
                    drawPaint.shader = null
                }
            }

            else -> { // MODE_FILL
                canvas.drawBitmap(db, matrixCurrent, drawPaint)
            }
        }
    }

    private fun fixTrans() {
        matrixCurrent.getValues(m)
        val transX = m[Matrix.MTRANS_X]
        val transY = m[Matrix.MTRANS_Y]

        val fixTransX = getFixTrans(transX, viewWidth, origWidth * saveScale)
        val fixTransY = getFixTrans(transY, viewHeight, origHeight * saveScale)

        if (fixTransX != 0f || fixTransY != 0f) {
            matrixCurrent.postTranslate(fixTransX, fixTransY)
        }
    }

    private fun getFixTrans(trans: Float, viewSize: Float, contentSize: Float): Float {
        val minTrans: Float
        val maxTrans: Float

        if (contentSize <= viewSize) {
            minTrans = 0f
            maxTrans = viewSize - contentSize
        } else {
            minTrans = viewSize - contentSize
            maxTrans = 0f
        }

        if (trans < minTrans) return -trans + minTrans
        if (trans > maxTrans) return -trans + maxTrans
        return 0f
    }

    private fun getFixDragTrans(delta: Float, viewSize: Float, contentSize: Float): Float {
        return if (contentSize <= viewSize) 0f else delta
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            mode = 2
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var mScaleFactor = detector.scaleFactor
            val origScale = saveScale
            saveScale *= mScaleFactor

            if (saveScale < minScale) {
                saveScale = minScale
                mScaleFactor = minScale / origScale
            } else if (saveScale > maxScale) {
                saveScale = maxScale
                mScaleFactor = maxScale / origScale
            }

            if (origWidth * saveScale <= viewWidth || origHeight * saveScale <= viewHeight) {
                matrixCurrent.postScale(mScaleFactor, mScaleFactor, viewWidth / 2, viewHeight / 2)
            } else {
                matrixCurrent.postScale(mScaleFactor, mScaleFactor, detector.focusX, detector.focusY)
            }

            fixTrans()
            invalidate()
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (fitMode == WallpaperFitHelper.MODE_STRETCH) return true

            val origScale = saveScale
            var targetScale: Float
            if (saveScale > minScale) {
                targetScale = minScale
            } else {
                targetScale = (minScale * 2f).coerceAtMost(maxScale)
                if (targetScale == minScale) targetScale = maxScale
            }

            saveScale = targetScale
            val scaleFactor = targetScale / origScale

            if (targetScale == minScale) {
                matrixCurrent.postScale(scaleFactor, scaleFactor, viewWidth / 2, viewHeight / 2)
            } else {
                matrixCurrent.postScale(scaleFactor, scaleFactor, e.x, e.y)
            }

            fixTrans()
            imageMatrix = matrixCurrent
            invalidate()

            return true
        }
    }
}
