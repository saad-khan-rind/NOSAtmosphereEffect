package com.app.nosatmosphereeffect.activity

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import com.app.nosatmosphereeffect.helper.TouchImageView
import com.app.nosatmosphereeffect.ui.theme.AtmoTheme
import com.app.nosatmosphereeffect.ui.theme.BrandPrimary
import java.io.File
import java.io.FileOutputStream

class MultiImageCropActivity : AppCompatActivity() {

    private lateinit var cropView: TouchImageView
    private var sourceUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-screen setup (unchanged)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        val wc = WindowCompat.getInsetsController(window, window.decorView)
        wc.hide(WindowInsetsCompat.Type.systemBars())
        wc.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        sourceUri = intent.data
        val savedMatrix = intent.getFloatArrayExtra("MATRIX_STATE")

        if (sourceUri == null) {
            Toast.makeText(this, "No Image Data Found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            AtmoTheme {
                MultiCropScreen(uri = sourceUri!!, savedMatrix = savedMatrix)
            }
        }
    }

    @Composable
    private fun MultiCropScreen(uri: Uri, savedMatrix: FloatArray?) {
        Box(modifier = Modifier.fillMaxSize()) {

            // ── TouchImageView embedded via AndroidView ───────────────
            AndroidView(
                factory = { ctx ->
                    TouchImageView(ctx).also { view ->
                        cropView = view
                        loadImage(uri, savedMatrix)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // ── Done button (bottom-center overlay) ───────────────────
            Button(
                onClick = {
                    val croppedBitmap   = cropView.getCroppedBitmap()
                    val currentMatrix   = cropView.getCurrentMatrixValues()
                    saveAndReturnResult(croppedBitmap, currentMatrix)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
                    .fillMaxWidth(0.7f),
                colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary)
            ) {
                Text(
                    text       = "Done",
                    fontSize   = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }

    // ── Business logic (unchanged) ────────────────────────────────────────

    private fun loadImage(uri: Uri, savedMatrix: FloatArray?) {
        try {
            val inputStream  = contentResolver.openInputStream(uri)
            val bitmap       = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            val rotatedBitmap = handleExifRotation(uri, bitmap)
            cropView.setInitialImage(rotatedBitmap, savedMatrix)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveAndReturnResult(bitmap: Bitmap, matrixValues: FloatArray) {
        try {
            val filename  = "cropped_playlist_${System.currentTimeMillis()}.jpg"
            val destFile  = File(cacheDir, filename)

            FileOutputStream(destFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush()
            }

            val resultIntent = Intent()
            resultIntent.putExtra("CROPPED_IMAGE_PATH", destFile.absolutePath)
            resultIntent.putExtra("MATRIX_STATE", matrixValues)
            setResult(RESULT_OK, resultIntent)
            finish()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to save image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        try {
            val input       = contentResolver.openInputStream(uri) ?: return bitmap
            val exif        = ExifInterface(input)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            input.close()
            val rotation = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rotation == 0f) return bitmap
            val matrix = Matrix().apply { postRotate(rotation) }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) { return bitmap }
    }
}