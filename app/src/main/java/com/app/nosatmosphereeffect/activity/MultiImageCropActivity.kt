package com.app.nosatmosphereeffect.activity

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.helper.MatrixStatePolicy
import com.app.nosatmosphereeffect.helper.WallpaperFitHelper
import com.app.nosatmosphereeffect.image.BitmapDecoder
import com.app.nosatmosphereeffect.image.BitmapStore
import com.app.nosatmosphereeffect.ui.screens.CropController
import com.app.nosatmosphereeffect.ui.screens.CropScreen
import com.app.nosatmosphereeffect.ui.theme.AtmoEngineTheme
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MultiImageCropActivity : ComponentActivity() {
    private val controller = CropController()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var currentFit = WallpaperFitHelper.MODE_FILL
    private var currentFill = WallpaperFitHelper.FILL_BLACK
    private var imageLoadStarted = false
    private var isSaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()

        val uri = intent.data
        if (uri == null) {
            Toast.makeText(this, R.string.crop_no_image, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val savedMatrix = MatrixStatePolicy.copyIfValid(
            intent.getFloatArrayExtra(EXTRA_MATRIX_STATE)
        )
        currentFit = intent.getStringExtra(EXTRA_INITIAL_FIT)
            ?: WallpaperFitHelper.MODE_FILL
        currentFill = intent.getStringExtra(EXTRA_INITIAL_FILL)
            ?: WallpaperFitHelper.FILL_BLACK

        setContent {
            AtmoEngineTheme {
                CropScreen(
                    controller = controller,
                    buttonLabel = getString(R.string.common_done),
                    initialFit = currentFit,
                    initialFill = currentFill,
                    onViewCreated = { loadImage(uri, savedMatrix) },
                    onFitChanged = { fit, fill ->
                        currentFit = fit
                        currentFill = fill
                        controller.setFitMode(fit, fill)
                    },
                    onBack = { finish() },
                    onConfirm = {
                        if (!isSaving) {
                            val cropped = controller.getCroppedBitmap()
                            val matrix = controller.getCurrentMatrixValues()
                            if (cropped != null && matrix != null) {
                                saveAndReturnResult(cropped, matrix)
                            }
                        }
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun loadImage(uri: Uri, savedMatrix: FloatArray?) {
        if (imageLoadStarted) return
        imageLoadStarted = true

        ioExecutor.execute {
            try {
                val bitmap = BitmapDecoder.decodeUriFullSize(this, uri)
                runOnUiThread {
                    if (isDestroyed || isFinishing) {
                        bitmap.recycle()
                    } else {
                        controller.setInitialImage(bitmap, savedMatrix)
                    }
                }
            } catch (error: IOException) {
                reportLoadFailure(error, getString(R.string.crop_error_open))
            } catch (error: SecurityException) {
                reportLoadFailure(
                    error,
                    getString(R.string.crop_error_permission)
                )
            } catch (error: RuntimeException) {
                reportLoadFailure(error, getString(R.string.crop_error_prepare))
            }
        }
    }

    private fun saveAndReturnResult(bitmap: Bitmap, matrixValues: FloatArray) {
        isSaving = true
        ioExecutor.execute {
            try {
                val destination = File(
                    cacheDir,
                    "cropped_playlist_${System.currentTimeMillis()}.jpg"
                )
                BitmapStore.writeJpegAtomically(bitmap, destination, quality = 100)

                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    setResult(
                        RESULT_OK,
                        Intent().apply {
                            putExtra(EXTRA_CROPPED_PATH, destination.absolutePath)
                            putExtra(EXTRA_MATRIX_STATE, matrixValues)
                            putExtra(EXTRA_FIT_MODE, currentFit)
                            putExtra(EXTRA_FILL_MODE, currentFill)
                        }
                    )
                    finish()
                }
            } catch (error: IOException) {
                reportSaveFailure(error, getString(R.string.crop_error_save))
            } catch (error: SecurityException) {
                reportSaveFailure(error, getString(R.string.crop_error_save_blocked))
            } catch (error: RuntimeException) {
                reportSaveFailure(error, getString(R.string.crop_error_save_prepare))
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun reportLoadFailure(error: Throwable, userMessage: String) {
        Log.e(TAG, "Unable to load playlist image", error)
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            Toast.makeText(this, userMessage, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun reportSaveFailure(error: Throwable, userMessage: String) {
        Log.e(TAG, "Unable to save cropped playlist image", error)
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            isSaving = false
            Toast.makeText(this, userMessage, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val TAG = "MultiImageCrop"
        const val EXTRA_CROPPED_PATH = "CROPPED_IMAGE_PATH"
        const val EXTRA_MATRIX_STATE = "MATRIX_STATE"
        const val EXTRA_INITIAL_FIT = "INITIAL_FIT_MODE"
        const val EXTRA_INITIAL_FILL = "INITIAL_FILL_MODE"
        const val EXTRA_FIT_MODE = "FIT_MODE"
        const val EXTRA_FILL_MODE = "FILL_MODE"
    }
}
