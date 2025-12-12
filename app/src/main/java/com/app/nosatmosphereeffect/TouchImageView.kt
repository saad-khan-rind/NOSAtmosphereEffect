package com.app.nosatmosphereeffect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max

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

                        // Apply drag, but 'fixTrans' will stop it if it goes out of bounds
                        val fixTransX = getFixDragTrans(deltaX, viewWidth, origWidth * saveScale)
                        val fixTransY = getFixDragTrans(deltaY, viewHeight, origHeight * saveScale)

                        matrixCurrent.postTranslate(fixTransX, fixTransY)
                        fixTrans()
                        last.set(curr.x, curr.y)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    mode = 0
                    val xDiff = kotlin.math.abs(curr.x - start.x).toInt()
                    val yDiff = kotlin.math.abs(curr.y - start.y).toInt()
                    if (xDiff < 3 && yDiff < 3) performClick()
                }

                MotionEvent.ACTION_POINTER_UP -> mode = 0
            }

            imageMatrix = matrixCurrent
            true // Consumed
        }
    }

    // 1. Setup the image to FILL the screen (Center Crop style)
    fun setInitialImage(bitmap: Bitmap) {
        super.setImageBitmap(bitmap)
        origWidth = bitmap.width.toFloat()
        origHeight = bitmap.height.toFloat()

        // We wait for the view to layout to know its real size
        post {
            viewWidth = width.toFloat()
            viewHeight = height.toFloat()

            // Calculate scale to COVER the screen
            val scaleX = viewWidth / origWidth
            val scaleY = viewHeight / origHeight

            // "max" ensures we fill the screen (Center Crop)
            // "min" would fit inside (Fit Center)
            val scale = max(scaleX, scaleY)

            matrixCurrent.setScale(scale, scale)

            // Center it
            val redundantYSpace = viewHeight - (scale * origHeight)
            val redundantXSpace = viewWidth - (scale * origWidth)
            matrixCurrent.postTranslate(redundantXSpace / 2, redundantYSpace / 2)

            saveScale = scale

            // IMPORTANT: Set minScale so user can't zoom out smaller than screen
            minScale = scale

            imageMatrix = matrixCurrent
            fixTrans()
        }
    }

    // 2. Capture exactly what the user sees
    fun getCroppedBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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
            mode = 2 // ZOOM
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var mScaleFactor = detector.scaleFactor
            val origScale = saveScale
            saveScale *= mScaleFactor

            // Don't let them zoom out smaller than the screen (minScale)
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
            // Optional: Double tap to reset
            return true
        }
    }
}