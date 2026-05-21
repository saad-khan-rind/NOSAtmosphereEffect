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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.exifinterface.media.ExifInterface
import com.app.nosatmosphereeffect.service.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

data class PlaylistItem(
    val originalUri: Uri,
    var isEdited: Boolean = false,
    var editedFilePath: String? = null,
    var matrixState: FloatArray? = null
)

class PlaylistEditorActivity : ComponentActivity() {

    private val playlistItems = mutableStateListOf<PlaylistItem>()
    private var effectId: String = "ORIGINAL"
    private var editingPosition = -1
    private var isProcessing by mutableStateOf(false)

    private val pickMultipleImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { playlistItems.add(PlaylistItem(it)) }
            Toast.makeText(this, "${uris.size} images added", Toast.LENGTH_SHORT).show()
        }
    }

    private val editImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val resultUriString = result.data?.getStringExtra("CROPPED_IMAGE_PATH")
            val matrixState = result.data?.getFloatArrayExtra("MATRIX_STATE")
            if (resultUriString != null && editingPosition != -1 && editingPosition < playlistItems.size) {
                playlistItems[editingPosition] = playlistItems[editingPosition].copy(
                    isEdited = true,
                    editedFilePath = resultUriString,
                    matrixState = matrixState
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        effectId = intent.getStringExtra("EFFECT_ID") ?: "ORIGINAL"
        if (intent.getBooleanExtra("EDIT_EXISTING", false)) {
            loadExistingPlaylist()
        } else {
            intent.getParcelableArrayListExtra<Uri>("IMAGE_URIS")?.forEach {
                playlistItems.add(PlaylistItem(it))
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PlaylistScreen()
                    if (isProcessing) {
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.7f)), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun PlaylistScreen() {
        val pagerState = rememberPagerState(pageCount = { playlistItems.size })

        Column(modifier = Modifier.fillMaxSize().padding(vertical = 48.dp)) {
            Text(
                "Playlist Editor",
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                textAlign = TextAlign.Center,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                "${playlistItems.size} Images Selected",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = Color.LightGray
            )

            Spacer(Modifier.height(32.dp))

            if (playlistItems.isNotEmpty()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    pageSpacing = 16.dp
                ) { page ->
                    val item = playlistItems[page]
                    val bitmap = remember(item) { loadPreview(item) }

                    Card(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(16.dp),
                        elevation = CardDefaults.cardElevation(8.dp)
                    ) {
                        Box {
                            if (bitmap != null) {
                                Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            } else {
                                Box(Modifier.fillMaxSize().background(Color.DarkGray))
                            }

                            Row(
                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(0.5f)).padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                IconButton(onClick = {
                                    editingPosition = page
                                    launchEditActivity(item)
                                }) { Icon(Icons.Default.Edit, contentDescription = "Edit", tint = Color.White) }

                                IconButton(onClick = { playlistItems.removeAt(page) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                                }
                            }
                        }
                    }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No Images Added", color = Color.Gray)
                }
            }

            Spacer(Modifier.height(32.dp))

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = { pickMultipleImages.launch("image/*") }, modifier = Modifier.weight(1f)) {
                    Text("Add More")
                }
                Button(
                    onClick = { showApplyDialog() },
                    enabled = playlistItems.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Apply Playlist")
                }
            }
        }
    }

    private fun loadPreview(item: PlaylistItem): Bitmap? {
        return try {
            val path = if (item.isEdited) item.editedFilePath else null
            if (path != null && File(path).exists()) {
                BitmapFactory.decodeFile(path)
            } else {
                contentResolver.openInputStream(item.originalUri)?.use { BitmapFactory.decodeStream(it) }
            }
        } catch (e: Exception) { null }
    }

    private fun launchEditActivity(item: PlaylistItem) {
        val intent = Intent(this, MultiImageCropActivity::class.java).apply {
            data = item.originalUri
            item.matrixState?.let { putExtra("MATRIX_STATE", it) }
        }
        editImageLauncher.launch(intent)
    }

    // Processing functions preserved identically below
    private fun showApplyDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Apply Wallpaper")
            .setMessage("Set Wallpaper > Home Screen and Lock Screen in the next menu.")
            .setPositiveButton("Proceed") { _, _ ->
                isProcessing = true
                applyPlaylist()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyPlaylist() {
        Thread {
            try {
                val tempDir = File(filesDir, "playlist_temp").apply { if (exists()) deleteRecursively(); mkdirs() }
                val tempOrigs = File(filesDir, "playlist_originals_temp").apply { if (exists()) deleteRecursively(); mkdirs() }
                File(filesDir, "next_wallpaper.jpg").takeIf { it.exists() }?.delete()

                val metaArray = JSONArray()

                playlistItems.forEachIndexed { index, item ->
                    val destFile = File(tempDir, "wallpaper_$index.jpg")
                    val origFile = File(tempOrigs, "original_$index.jpg")

                    contentResolver.openInputStream(item.originalUri)?.use { input ->
                        FileOutputStream(origFile).use { input.copyTo(it) }
                    }

                    if (item.isEdited && item.editedFilePath != null && File(item.editedFilePath!!).exists()) {
                        File(item.editedFilePath!!).copyTo(destFile, overwrite = true)
                    } else {
                        decodeCenterCropBitmap(item.originalUri)?.let { bmp ->
                            FileOutputStream(destFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 100, it) }
                        }
                    }

                    metaArray.put(JSONObject().apply {
                        put("original", "original_$index.jpg")
                        put("isEdited", item.isEdited)
                        item.matrixState?.let { ms -> put("matrix", JSONArray().apply { ms.forEach { put(it.toDouble()) } }) }
                    })
                }

                File(tempDir, "metadata.json").writeText(metaArray.toString())

                File(filesDir, "playlist").apply { if (exists()) deleteRecursively() }
                tempDir.renameTo(File(filesDir, "playlist"))

                File(filesDir, "playlist_originals").apply { if (exists()) deleteRecursively() }
                tempOrigs.renameTo(File(filesDir, "playlist_originals"))

                val playlistDir = File(filesDir, "playlist")
                File(playlistDir, "wallpaper_0.jpg").takeIf { it.exists() }?.copyTo(File(filesDir, "wallpaper.jpg"), overwrite = true)

                val wpPrefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE).apply { edit().clear().apply() }
                getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().clear().apply()

                if (playlistItems.size > 1) {
                    File(playlistDir, "wallpaper_1.jpg").takeIf { it.exists() }?.copyTo(File(filesDir, "next_wallpaper.jpg"), overwrite = true)
                    wpPrefs.edit().putString("last_playlist_image", "wallpaper_1.jpg").apply()
                } else if (playlistItems.size == 1) {
                    File(playlistDir, "wallpaper_0.jpg").takeIf { it.exists() }?.copyTo(File(filesDir, "next_wallpaper.jpg"), overwrite = true)
                    wpPrefs.edit().putString("last_playlist_image", "wallpaper_0.jpg").apply()
                }

                val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                wpPrefs.edit().putInt("active_theme_state", if (isNight) 1 else 0).apply()

                runOnUiThread {
                    isProcessing = false
                    Toast.makeText(this, "Setup complete!", Toast.LENGTH_LONG).show()
                    sendBroadcast(Intent("com.app.nosatmosphereeffect.RELOAD_WALLPAPER").setPackage(packageName))
                    activateService()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isProcessing = false
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun activateService() {
        try {
            val serviceClass = when(effectId) {
                "ORIGINAL" -> AtmosphereService::class.java
                "REVERSE" -> BlurToSharpService::class.java
                "FROSTED" -> FrostedService::class.java
                "FROSTED_REVERSE" -> FrostedReverseService::class.java
                "HALFTONE" -> HalftoneService::class.java
                "HALFTONE_REVERSE" -> HalftoneReverseService::class.java
                "COLORFILL" -> ColorFillService::class.java
                "COLORFILL_REVERSE" -> ColorFillReverseService::class.java
                else -> AtmosphereService::class.java
            }
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this@PlaylistEditorActivity, serviceClass))
            })
        } catch (e: Exception) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        } finally { finish() }
    }

    private fun decodeCenterCropBitmap(uri: Uri): Bitmap? {
        val metrics = windowManager.currentWindowMetrics.bounds
        val reqW = metrics.width()
        val reqH = metrics.height()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }

        var inSampleSize = 1
        if (options.outHeight > reqH || options.outWidth > reqW) {
            val halfHeight = options.outHeight / 2
            val halfWidth = options.outWidth / 2
            while ((halfHeight / inSampleSize) >= reqH && (halfWidth / inSampleSize) >= reqW) { inSampleSize *= 2 }
        }

        options.inSampleSize = inSampleSize
        options.inJustDecodeBounds = false
        var bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null

        try {
            val orientation = contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } ?: ExifInterface.ORIENTATION_NORMAL
            val rotation = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rotation != 0f) bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation) }, true)
        } catch (e: Exception) {}

        val scale = if (bitmap.width.toFloat() / bitmap.height > reqW.toFloat() / reqH) reqH.toFloat() / bitmap.height else reqW.toFloat() / bitmap.width
        val scaled = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { setScale(scale, scale) }, true)
        val x = max(0, (scaled.width - reqW) / 2)
        val y = max(0, (scaled.height - reqH) / 2)
        return Bitmap.createBitmap(scaled, x, y, min(reqW, scaled.width - x), min(reqH, scaled.height - y))
    }

    private fun loadExistingPlaylist() {
        val playlistDir = File(filesDir, "playlist")
        val originalsDir = File(filesDir, "playlist_originals")
        val metaFile = File(playlistDir, "metadata.json")

        if (metaFile.exists()) {
            try {
                val jsonArray = JSONArray(metaFile.readText())
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val originalUri = Uri.parse("file://${File(originalsDir, obj.getString("original")).absolutePath}")
                    val isEdited = obj.getBoolean("isEdited")
                    val editedPath = if (isEdited) File(playlistDir, "wallpaper_$i.jpg").absolutePath else null
                    var matrixState: FloatArray? = null
                    if (obj.has("matrix")) {
                        val arr = obj.getJSONArray("matrix")
                        matrixState = FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
                    }
                    playlistItems.add(PlaylistItem(originalUri, isEdited, editedPath, matrixState))
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
}