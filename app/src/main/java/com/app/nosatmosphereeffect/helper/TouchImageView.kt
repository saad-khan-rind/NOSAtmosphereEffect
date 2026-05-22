package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.WindowManager
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.max
import androidx.core.graphics.createBitmap

class TouchImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private var matrixCurrent = Matrix()
    private var mode = 0 // 0=NONE, 1=DRAG, 2=ZOOM

    // Zoom variables
    private var saveScale = 1f
    private var minScale = 1f
    private var maxScale = 5f

    // View dimensions
    private var viewWidth = 0f
    private var viewHeight = 0f

    // Target dimensions (Physical Screen 1:1 size)
    private var targetWidth = 0
    private var targetHeight = 0

    // Image dimensions (original)
    private var origWidth = 0f
    private var origHeight = 0f

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

        // --- 1. CAPTURE REAL SCREEN SIZE ---
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = windowManager.currentWindowMetrics
        targetWidth = metrics.bounds.width()
        targetHeight = metrics.bounds.height()

        setOnTouchListener { _, event ->
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
            true // Consumed
        }
    }

    // --- 2. SETUP IMAGE TO FILL SCREEN RESOLUTION ---
    fun setInitialImage(bitmap: Bitmap, savedMatrixValues: FloatArray? = null) {
        super.setImageBitmap(bitmap)
        origWidth = bitmap.width.toFloat()
        origHeight = bitmap.height.toFloat()

        post {
            // We use the view's actual layout size for bounds checking
            viewWidth = width.toFloat()
            viewHeight = height.toFloat()

            // Calculate standard center-crop scale (minScale)
            val scaleX = targetWidth.toFloat() / origWidth
            val scaleY = targetHeight.toFloat() / origHeight
            val scale = max(scaleX, scaleY)

            minScale = scale

            if (savedMatrixValues != null) {
                // RESTORE STATE
                matrixCurrent.setValues(savedMatrixValues)
                // Restore saveScale from the matrix (MSCALE_X is at index 0)
                saveScale = savedMatrixValues[Matrix.MSCALE_X]

                // Ensure we don't start invalid
                if (saveScale < minScale) saveScale = minScale
            } else {
                // FRESH START (Center Crop)
                matrixCurrent.reset()
                matrixCurrent.setScale(scale, scale)

                // Center the image
                val redundantYSpace = viewHeight - (scale * origHeight)
                val redundantXSpace = viewWidth - (scale * origWidth)
                matrixCurrent.postTranslate(redundantXSpace / 2, redundantYSpace / 2)

                saveScale = scale
            }

            imageMatrix = matrixCurrent
            fixTrans()
        }
    }

    fun getCurrentMatrixValues(): FloatArray {
        val values = FloatArray(9)
        matrixCurrent.getValues(values)
        return values
    }

    fun getCroppedBitmap(): Bitmap {
        val bitmap = createBitmap(targetWidth, targetHeight)
        val canvas = Canvas(bitmap)
        draw(canvas)
        return bitmap
    }

    // --- BOUNDS CHECKING LOGIC ---
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

    // --- SCALE LISTENER (Pinch to Zoom) ---
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
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
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

            return true
        }
    }
}