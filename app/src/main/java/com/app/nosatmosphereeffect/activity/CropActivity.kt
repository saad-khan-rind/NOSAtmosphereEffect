package com.app.nosatmosphereeffect.activity

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.helper.TouchImageView
import com.app.nosatmosphereeffect.service.AtmosphereService
import com.app.nosatmosphereeffect.service.ColorFillService
import com.app.nosatmosphereeffect.service.FrostedService
import com.app.nosatmosphereeffect.service.HalftoneService
import com.app.nosatmosphereeffect.ui.theme.AtmoTheme
import com.app.nosatmosphereeffect.ui.theme.BrandPrimary
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class CropActivity : AppCompatActivity() {

    private var effectId = "ORIGINAL"
    private lateinit var cropView: TouchImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-screen setup (unchanged)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        val wc = WindowCompat.getInsetsController(window, window.decorView)
        wc.isAppearanceLightStatusBars       = false
        wc.isAppearanceLightNavigationBars   = false
        wc.hide(WindowInsetsCompat.Type.systemBars())
        wc.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        effectId = intent.getStringExtra("EFFECT_ID") ?: "ORIGINAL"

        val uri = intent.data
        if (uri == null) {
            Toast.makeText(this, "No Image Data Found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            AtmoTheme {
                CropScreen(uri = uri)
            }
        }
    }

    @Composable
    private fun CropScreen(uri: Uri) {
        Box(modifier = Modifier.fillMaxSize()) {

            // ── TouchImageView embedded via AndroidView ───────────────
            AndroidView(
                factory = { ctx ->
                    TouchImageView(ctx).also { view ->
                        cropView = view
                        // Load image on a background thread (unchanged logic)
                        Thread {
                            try {
                                val bmp = decodeSampledBitmapFromUri(ctx, uri, 4096, 4096)
                                runOnUiThread {
                                    if (bmp != null) view.setInitialImage(bmp)
                                    else {
                                        Toast.makeText(ctx, "Could not load image format.", Toast.LENGTH_SHORT).show()
                                        finish()
                                    }
                                }
                            } catch (e: Exception) {
                                runOnUiThread {
                                    Toast.makeText(ctx, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                            }
                        }.start()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // ── Apply button (bottom-center overlay) ──────────────────
            Button(
                onClick = {
                    val cropped = cropView.getCroppedBitmap()
                    showApplyDialog(cropped)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
                    .fillMaxWidth(0.7f),
                colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary)
            ) {
                Text(
                    text       = getString(R.string.action_apply),
                    fontSize   = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }

    // ── Business logic (unchanged) ────────────────────────────────────────

    private fun decodeSampledBitmapFromUri(
        context: Context, uri: Uri, reqWidth: Int, reqHeight: Int
    ): Bitmap? {
        var inputStream: InputStream? = null
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            inputStream = context.contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()

            options.inSampleSize        = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds  = false
            options.inPreferredConfig   = Bitmap.Config.ARGB_8888

            inputStream = context.contentResolver.openInputStream(uri)
            val rawBitmap = BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()

            if (rawBitmap == null) return null
            return handleExifRotation(context, uri, rawBitmap)
        } catch (e: Exception) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
            return null
        } finally {
            try { inputStream?.close() } catch (e: Exception) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handleExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        var inputStream: InputStream? = null
        try {
            inputStream = context.contentResolver.openInputStream(uri) ?: return bitmap
            val exif        = ExifInterface(inputStream)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) return bitmap
            val matrix   = Matrix().apply { postRotate(degrees) }
            val rotated  = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            return rotated
        } catch (e: Exception) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
            return bitmap
        } finally { inputStream?.close() }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int
    ): Int {
        val (height, width) = options.run { outHeight to outWidth }
        var inSampleSize    = 1
        val maxDim  = kotlin.math.max(height, width)
        val maxTex  = kotlin.math.min(reqWidth, reqHeight)
        if (maxDim > maxTex) {
            val factor = maxDim.toFloat() / maxTex.toFloat()
            while (inSampleSize < factor) inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun showApplyDialog(bitmap: Bitmap) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Apply Wallpaper")
            .setMessage(
                "In the next screen, please select:\n\nSet Wallpaper > Home Screen and Lock Screen." +
                        "\n\n(This ensures the lock screen effect works correctly)."
            )
            .setPositiveButton("Set Wallpaper") { _, _ -> applyWallpaper(bitmap) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyWallpaper(bitmap: Bitmap) {
        Toast.makeText(this, "Applying...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE).edit().clear().apply()

                File(filesDir, "playlist").let { if (it.exists()) it.deleteRecursively() }
                File(filesDir, "next_wallpaper.jpg").let { if (it.exists()) it.delete() }

                saveFixedWallpaper(bitmap)

                runOnUiThread {
                    Toast.makeText(this,
                        "Setup complete! Now lock and unlock the screen to activate.",
                        Toast.LENGTH_LONG
                    ).show()
                    val intent = Intent("com.app.nosatmosphereeffect.RELOAD_WALLPAPER")
                    intent.setPackage(packageName)
                    sendBroadcast(intent)
                    activateService()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun saveFixedWallpaper(bitmap: Bitmap) {
        val file = File(filesDir, "wallpaper.jpg")
        if (file.exists()) file.delete()
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            out.flush()
        }
    }

    private fun activateService() {
        try {
            val serviceClass = when (effectId) {
                "FROSTED"    -> FrostedService::class.java
                "HALFTONE"   -> HalftoneService::class.java
                "COLORFILL"  -> ColorFillService::class.java
                else         -> AtmosphereService::class.java
            }
            val i = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this, serviceClass))
            startActivity(i)
        } catch (e: Exception) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        } finally { finish() }
    }
}
