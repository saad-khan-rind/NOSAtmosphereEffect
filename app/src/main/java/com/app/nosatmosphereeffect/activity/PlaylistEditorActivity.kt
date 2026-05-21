package com.app.nosatmosphereeffect.activity

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.core.view.WindowCompat
import androidx.exifinterface.media.ExifInterface
import com.app.nosatmosphereeffect.service.*
import com.app.nosatmosphereeffect.ui.theme.AtmoTheme
import com.app.nosatmosphereeffect.ui.theme.BrandPrimary
import com.app.nosatmosphereeffect.ui.theme.ErrorColor
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class PlaylistEditorActivity : AppCompatActivity() {

    data class PlaylistItem(
        val originalUri: Uri,
        var isEdited: Boolean      = false,
        var editedFilePath: String? = null,
        var matrixState: FloatArray? = null
    )

    // ── Compose-observable state ─────────────────────────────────────────
    private val playlistItems = mutableStateListOf<PlaylistItem>()
    private var effectId      by mutableStateOf("ORIGINAL")
    private var editingPosition = -1

    // Thumbnails cache: position -> Bitmap
    private val thumbnails = mutableStateMapOf<Int, Bitmap>()
    private val thumbExecutor = Executors.newFixedThreadPool(4)

    // ── Activity result launchers (unchanged) ────────────────────────────
    private val pickMultipleImages = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            uris.forEach { playlistItems.add(PlaylistItem(it)) }
            Toast.makeText(this, "${uris.size} images added", Toast.LENGTH_SHORT).show()
        }
    }

    private val editImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path        = result.data?.getStringExtra("CROPPED_IMAGE_PATH")
            val matrixState = result.data?.getFloatArrayExtra("MATRIX_STATE")
            if (path != null && editingPosition != -1 && editingPosition < playlistItems.size) {
                val item = playlistItems[editingPosition]
                playlistItems[editingPosition] = item.copy(
                    isEdited      = true,
                    editedFilePath = path,
                    matrixState   = matrixState
                )
                // Refresh thumbnail
                thumbnails.remove(editingPosition)
                loadThumbnail(editingPosition, playlistItems[editingPosition])
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

        effectId = intent.getStringExtra("EFFECT_ID") ?: "ORIGINAL"

        if (intent.getBooleanExtra("EDIT_EXISTING", false)) {
            loadExistingPlaylist()
        } else {
            val uris = intent.getParcelableArrayListExtra("IMAGE_URIS", Uri::class.java)
            uris?.forEach { playlistItems.add(PlaylistItem(it)) }
        }

        if (savedInstanceState != null) {
            editingPosition = savedInstanceState.getInt("EDITING_POS", -1)
        }

        // Pre-load thumbnails for initial items
        playlistItems.forEachIndexed { idx, item -> loadThumbnail(idx, item) }

        setContent {
            AtmoTheme {
                PlaylistEditorScreen()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("EDITING_POS", editingPosition)
    }

    // ── Composable UI ─────────────────────────────────────────────────────
    @Composable
    private fun PlaylistEditorScreen() {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {

                // Title
                Text(
                    text       = "Edit Playlist",
                    fontSize   = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color      = BrandPrimary,
                    modifier   = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
                )

                // Counter
                Text(
                    text     = "${playlistItems.size} Images Selected",
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ── Carousel ──────────────────────────────────────────
                LazyRow(
                    modifier            = Modifier
                        .fillMaxWidth()
                        .height(520.dp),
                    contentPadding      = PaddingValues(horizontal = 40.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(playlistItems) { index, item ->
                        PlaylistCard(index = index, item = item)
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // ── Bottom action buttons ─────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick  = { pickMultipleImages.launch("image/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add More Images")
                    }

                    Button(
                        onClick  = { showApplyDialog() },
                        enabled  = playlistItems.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        colors   = ButtonDefaults.buttonColors(containerColor = BrandPrimary)
                    ) {
                        Text(
                            text  = "Apply Wallpaper",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun PlaylistCard(index: Int, item: PlaylistItem) {
        val bmp = thumbnails[index]

        Card(
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight()
                .clickable {
                    editingPosition = index
                    launchEditActivity(item)
                },
            shape  = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = androidx.compose.ui.graphics.Color(0xFF222222)
            ),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {

                // Thumbnail image
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap      = bmp.asImageBitmap(),
                        contentDescription = "Wallpaper preview",
                        contentScale = ContentScale.Crop,
                        modifier    = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(androidx.compose.ui.graphics.Color(0xFF111111))
                    )
                }

                // Edited overlay
                if (item.isEdited) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(androidx.compose.ui.graphics.Color(0x40000000))
                    )
                    Icon(
                        imageVector        = Icons.Default.Edit,
                        contentDescription = "Edited",
                        tint               = BrandPrimary,
                        modifier           = Modifier
                            .size(48.dp)
                            .align(Alignment.Center)
                    )
                }

                // Delete button (top-right)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color(0x99000000))
                        .clickable {
                            thumbnails.remove(index)
                            playlistItems.removeAt(index)
                            // Shift thumbnail keys
                            val shifted = thumbnails.entries
                                .filter { it.key > index }
                                .associate { (k, v) -> (k - 1) to v }
                            thumbnails.keys.filter { it >= index }.forEach { thumbnails.remove(it) }
                            shifted.forEach { (k, v) -> thumbnails[k] = v }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = Icons.Default.Close,
                        contentDescription = "Delete",
                        tint               = ErrorColor,
                        modifier           = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    // ── Thumbnail loading ─────────────────────────────────────────────────

    private fun loadThumbnail(index: Int, item: PlaylistItem) {
        val uri = if (item.isEdited && item.editedFilePath != null)
            Uri.parse("file://${item.editedFilePath}")
        else
            item.originalUri

        thumbExecutor.execute {
            try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
                options.inSampleSize    = calcThumbSampleSize(options, 300, 400)
                options.inJustDecodeBounds = false

                var bmp = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
                if (bmp != null) bmp = handleExifRotation(this, uri, bmp)
                if (bmp != null) runOnUiThread { thumbnails[index] = bmp }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun calcThumbSampleSize(opts: BitmapFactory.Options, rW: Int, rH: Int): Int {
        val (h, w) = opts.run { outHeight to outWidth }
        var s = 1
        if (h > rH || w > rW) {
            val hh = h / 2; val hw = w / 2
            while ((hh / s) >= rH && (hw / s) >= rW) s *= 2
        }
        return s
    }

    // ── Business logic (unchanged) ────────────────────────────────────────

    private fun launchEditActivity(item: PlaylistItem) {
        val i = Intent(this, MultiImageCropActivity::class.java)
        i.data = item.originalUri
        if (item.matrixState != null) i.putExtra("MATRIX_STATE", item.matrixState)
        editImageLauncher.launch(i)
    }

    private fun showApplyDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Apply Wallpaper")
            .setMessage(
                "In the next screen, please select:\n\nSet Wallpaper > Home Screen and Lock Screen." +
                        "\n\n(This ensures the lock screen effect works correctly)."
            )
            .setPositiveButton("Set Wallpaper") { _, _ -> applyFromDialog() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyFromDialog() {
        if (playlistItems.isEmpty()) {
            Toast.makeText(this, "Playlist is empty", Toast.LENGTH_SHORT).show()
        } else {
            applyPlaylist()
        }
    }

    private fun applyPlaylist() {
        val loadingLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(50, 50, 50, 50)
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(android.widget.ProgressBar(this@PlaylistEditorActivity).apply {
                isIndeterminate = true
            })
            addView(android.widget.TextView(this@PlaylistEditorActivity).apply {
                text     = "Processing playlist..."
                textSize = 16f
                setPadding(40, 0, 0, 0)
                setTextColor(Color.WHITE)
            })
        }

        val progressDialog = MaterialAlertDialogBuilder(this)
            .setView(loadingLayout)
            .setCancelable(false)
            .create()
        progressDialog.show()

        val snapshotItems = playlistItems.toList()

        Thread {
            try {
                val tempDir      = File(filesDir, "playlist_temp")
                val tempOrigDir  = File(filesDir, "playlist_originals_temp")
                if (tempDir.exists())     tempDir.deleteRecursively()
                if (tempOrigDir.exists()) tempOrigDir.deleteRecursively()
                tempDir.mkdirs(); tempOrigDir.mkdirs()

                File(filesDir, "next_wallpaper.jpg").let { if (it.exists()) it.delete() }

                val metaArray = JSONArray()

                snapshotItems.forEachIndexed { index, item ->
                    val destFile = File(tempDir,     "wallpaper_$index.jpg")
                    val origFile = File(tempOrigDir, "original_$index.jpg")

                    try {
                        contentResolver.openInputStream(item.originalUri)?.use { input ->
                            FileOutputStream(origFile).use { it.write(input.readBytes()) }
                        }
                    } catch (e: Exception) { e.printStackTrace() }

                    if (item.isEdited && item.editedFilePath != null) {
                        val srcEdited = File(item.editedFilePath!!)
                        if (srcEdited.exists() && srcEdited.absolutePath != destFile.absolutePath) {
                            srcEdited.copyTo(destFile, overwrite = true)
                        }
                    } else {
                        val bmp = decodeCenterCropBitmap(item.originalUri)
                        if (bmp != null) {
                            FileOutputStream(destFile).use { out ->
                                bmp.compress(Bitmap.CompressFormat.JPEG, 100, out)
                            }
                        }
                    }

                    val metaObj = JSONObject().apply {
                        put("original", "original_$index.jpg")
                        put("isEdited", item.isEdited)
                        if (item.matrixState != null) {
                            val arr = JSONArray()
                            item.matrixState!!.forEach { arr.put(it.toDouble()) }
                            put("matrix", arr)
                        }
                    }
                    metaArray.put(metaObj)
                }

                File(tempDir, "metadata.json").writeText(metaArray.toString())

                val playlistDir = File(filesDir, "playlist")
                val originalsDir = File(filesDir, "playlist_originals")
                if (playlistDir.exists())  playlistDir.deleteRecursively()
                if (originalsDir.exists()) originalsDir.deleteRecursively()
                tempDir.renameTo(playlistDir)
                tempOrigDir.renameTo(originalsDir)

                val firstFile = File(playlistDir, "wallpaper_0.jpg")
                val activeWallpaper = File(filesDir, "wallpaper.jpg")
                if (firstFile.exists()) firstFile.copyTo(activeWallpaper, overwrite = true)

                val wpPrefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
                wpPrefs.edit().clear().apply()
                getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().clear().apply()

                if (snapshotItems.size > 1) {
                    val nextFile   = File(filesDir, "next_wallpaper.jpg")
                    val secondFile = File(playlistDir, "wallpaper_1.jpg")
                    if (secondFile.exists()) secondFile.copyTo(nextFile, overwrite = true)
                    wpPrefs.edit().putString("last_playlist_image", "wallpaper_1.jpg").apply()
                } else if (snapshotItems.size == 1) {
                    val nextFile = File(filesDir, "next_wallpaper.jpg")
                    if (firstFile.exists()) firstFile.copyTo(nextFile, overwrite = true)
                    wpPrefs.edit().putString("last_playlist_image", "wallpaper_0.jpg").apply()
                }

                val currentUiMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                val isNightMode   = (currentUiMode == Configuration.UI_MODE_NIGHT_YES)
                wpPrefs.edit().putInt("active_theme_state", if (isNightMode) 1 else 0).apply()

                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this, "Setup complete! Now lock and unlock the screen to activate.",
                        Toast.LENGTH_LONG).show()
                    val i = Intent("com.app.nosatmosphereeffect.RELOAD_WALLPAPER")
                    i.setPackage(packageName)
                    sendBroadcast(i)
                    activateService()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun decodeCenterCropBitmap(uri: Uri): Bitmap? {
        val metrics = windowManager.currentWindowMetrics.bounds
        val reqW    = metrics.width()
        val reqH    = metrics.height()

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }

        options.inSampleSize    = calculateInSampleSize(options, reqW, reqH)
        options.inJustDecodeBounds = false

        var bitmap = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        bitmap = handleExifRotation(this, uri, bitmap)

        val bitmapRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val screenRatio = reqW.toFloat() / reqH.toFloat()

        val matrix = Matrix()
        val scale  = if (bitmapRatio > screenRatio) reqH.toFloat() / bitmap.height.toFloat()
        else                           reqW.toFloat() / bitmap.width.toFloat()
        matrix.setScale(scale, scale)
        val scaled = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

        val x  = max(0, (scaled.width  - reqW) / 2)
        val y  = max(0, (scaled.height - reqH) / 2)
        val fw = min(reqW, scaled.width  - x)
        val fh = min(reqH, scaled.height - y)
        return Bitmap.createBitmap(scaled, x, y, fw, fh)
    }

    private fun handleExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val input       = context.contentResolver.openInputStream(uri) ?: return bitmap
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
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) { bitmap }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int
    ): Int {
        val (h, w) = options.run { outHeight to outWidth }
        var s = 1
        if (h > reqHeight || w > reqWidth) {
            val hh = h / 2; val hw = w / 2
            while ((hh / s) >= reqHeight && (hw / s) >= reqWidth) s *= 2
        }
        return s
    }

    private fun activateService() {
        try {
            val serviceClass = when (effectId) {
                "ORIGINAL"          -> AtmosphereService::class.java
                "REVERSE"           -> BlurToSharpService::class.java
                "FROSTED"           -> FrostedService::class.java
                "FROSTED_REVERSE"   -> FrostedReverseService::class.java
                "HALFTONE"          -> HalftoneService::class.java
                "HALFTONE_REVERSE"  -> HalftoneReverseService::class.java
                "COLORFILL"         -> ColorFillService::class.java
                "COLORFILL_REVERSE" -> ColorFillReverseService::class.java
                else                -> AtmosphereService::class.java
            }
            val i = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this, serviceClass))
            startActivity(i)
        } catch (e: Exception) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        } finally { finish() }
    }

    private fun loadExistingPlaylist() {
        val playlistDir  = File(filesDir, "playlist")
        val originalsDir = File(filesDir, "playlist_originals")
        val metaFile     = File(playlistDir, "metadata.json")

        if (metaFile.exists()) {
            try {
                val json      = JSONArray(metaFile.readText())
                for (i in 0 until json.length()) {
                    val obj      = json.getJSONObject(i)
                    val origName = obj.getString("original")
                    val isEdited = obj.getBoolean("isEdited")
                    val origFile = File(originalsDir, origName)
                    val origUri  = Uri.parse("file://${origFile.absolutePath}")

                    val editedPath = if (isEdited) File(playlistDir, "wallpaper_$i.jpg").absolutePath else null
                    var matrix: FloatArray? = null
                    if (obj.has("matrix")) {
                        val arr = obj.getJSONArray("matrix")
                        matrix  = FloatArray(arr.length()) { idx -> arr.getDouble(idx).toFloat() }
                    }
                    playlistItems.add(PlaylistItem(origUri, isEdited, editedPath, matrix))
                }
            } catch (e: Exception) { e.printStackTrace() }
        } else {
            // Fallback for older playlists
            val files = playlistDir.listFiles { _, name -> name.endsWith(".jpg") }
            if (!files.isNullOrEmpty()) {
                files.sortBy { it.nameWithoutExtension.substringAfter('_').toIntOrNull() ?: 0 }
                files.forEach { file ->
                    playlistItems.add(PlaylistItem(Uri.parse("file://${file.absolutePath}")))
                }
            }
        }
    }
}