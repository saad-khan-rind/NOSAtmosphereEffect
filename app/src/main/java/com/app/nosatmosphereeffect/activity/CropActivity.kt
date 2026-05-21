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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.exifinterface.media.ExifInterface
import com.app.nosatmosphereeffect.helper.TouchImageView
import com.app.nosatmosphereeffect.service.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

class CropActivity : ComponentActivity() {
    private var effectId: String = "ORIGINAL"
    private var isProcessing by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        effectId = intent.getStringExtra("EFFECT_ID") ?: "ORIGINAL"

        val uri = intent.data
        if (uri == null) {
            Toast.makeText(this, "No Image Data Found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    CropScreen(
                        uri = uri,
                        isProcessing = isProcessing,
                        onApply = { bmp ->
                            showApplyDialog(bmp)
                        },
                        decodeBitmap = { ctx, u -> decodeSampledBitmapFromUri(ctx, u, 4096, 4096) }
                    )
                }
            }
        }
    }

    private fun decodeSampledBitmapFromUri(context: Context, uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }

            val maxImageDimension = max(options.outHeight, options.outWidth)
            val maxTextureSize = min(reqWidth, reqHeight)
            var inSampleSize = 1
            if (maxImageDimension > maxTextureSize) {
                val factor = maxImageDimension.toFloat() / maxTextureSize.toFloat()
                while (inSampleSize < factor) { inSampleSize *= 2 }
            }

            options.inSampleSize = inSampleSize
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            val rawBitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            handleExifRotation(context, uri, rawBitmap)
        } catch (e: Exception) { null }
    }

    private fun handleExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val orientation = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } ?: ExifInterface.ORIENTATION_NORMAL
            val rotationInDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rotationInDegrees == 0f) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotationInDegrees) }, true)
        } catch (e: Exception) { bitmap }
    }

    private fun showApplyDialog(bitmap: Bitmap) {
        MaterialAlertDialogBuilder(this@CropActivity)
            .setTitle("Apply Wallpaper")
            .setMessage("Select 'Set Wallpaper > Home Screen and Lock Screen' in the next menu.")
            .setPositiveButton("Proceed") { _, _ ->
                isProcessing = true
                applyWallpaper(bitmap)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyWallpaper(bitmap: Bitmap) {
        Thread {
            try {
                getSharedPreferences("app_prefs", MODE_PRIVATE).edit().clear().apply()
                getSharedPreferences("wallpaper_prefs", MODE_PRIVATE).edit().clear().apply()
                File(filesDir, "playlist").apply { if (exists()) deleteRecursively() }
                File(filesDir, "next_wallpaper.jpg").apply { if (exists()) delete() }

                val out = FileOutputStream(File(filesDir, "wallpaper.jpg").apply { if (exists()) delete() })
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                out.flush(); out.close()

                runOnUiThread {
                    isProcessing = false
                    Toast.makeText(this, "Setup complete!", Toast.LENGTH_LONG).show()
                    sendBroadcast(Intent("com.app.nosatmosphereeffect.RELOAD_WALLPAPER").setPackage(packageName))
                    activateService()
                }
            } catch (e: Exception) {
                runOnUiThread { isProcessing = false }
            }
        }.start()
    }

    private fun activateService() {
        val serviceClass = when(effectId) {
            "FROSTED" -> FrostedService::class.java
            "HALFTONE" -> HalftoneService::class.java
            "COLORFILL" -> ColorFillService::class.java
            else -> AtmosphereService::class.java
        }
        try {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this@CropActivity, serviceClass))
            })
        } catch (e: Exception) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        } finally { finish() }
    }
}

// Extracted entirely outside the class scope
@Composable
fun CropScreen(
    uri: Uri,
    isProcessing: Boolean,
    onApply: (Bitmap) -> Unit,
    decodeBitmap: suspend (Context, Uri) -> Bitmap?
) {
    val context = LocalContext.current
    var touchImageView by remember { mutableStateOf<TouchImageView?>(null) }
    val coroutineScope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TouchImageView(ctx).also {
                    touchImageView = it
                    coroutineScope.launch(Dispatchers.IO) {
                        val bmp = decodeBitmap(ctx, uri)
                        withContext(Dispatchers.Main) {
                            if (bmp != null) it.setInitialImage(bmp)
                            else Toast.makeText(ctx, "Could not load format.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )

        Button(
            onClick = {
                touchImageView?.getCroppedBitmap()?.let { onApply(it) }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp, start = 24.dp, end = 24.dp)
                .fillMaxWidth(),
            enabled = !isProcessing
        ) {
            Text("Apply Wallpaper")
        }

        if (isProcessing) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.7f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}