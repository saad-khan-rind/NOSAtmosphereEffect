package com.app.nosatmosphereeffect.activity

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.helper.FolderPlaylistSource
import com.app.nosatmosphereeffect.image.BitmapDecoder
import com.app.nosatmosphereeffect.ui.model.EffectCatalog
import com.app.nosatmosphereeffect.ui.model.EffectItem
import com.app.nosatmosphereeffect.ui.screens.EffectSelectionScreen
import com.app.nosatmosphereeffect.ui.screens.WallpaperModeSheet
import com.app.nosatmosphereeffect.ui.theme.AtmoEngineTheme
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class EffectSelectionActivity : ComponentActivity() {

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var selectedEffectId: String = "ORIGINAL"
    private var previewBitmap by mutableStateOf<ImageBitmap?>(null)

    private var sharedUris: ArrayList<Uri>? = null

    private val pickSingleImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { launchCropActivity(it) }
        }

    private val pickMultipleImages =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
            if (uris.isNotEmpty()) launchMultiCropActivity(ArrayList(uris))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val isUpdateOnly = intent.getBooleanExtra("UPDATE_EFFECT_ONLY", false)

        sharedUris = extractSharedUris()
        val isShare = sharedUris != null
        loadCurrentWallpaperPreview()

        setContent {
            AtmoEngineTheme {
                var pendingMode by remember { mutableStateOf(false) }

                EffectSelectionScreen(
                    title = getString(
                        if (isUpdateOnly && !isShare) R.string.effects_change_title
                        else R.string.effects_choose_title
                    ),
                    effects = EffectCatalog.items,
                    previewBitmap = previewBitmap,
                    onEffectClick = { item ->
                        selectedEffectId = item.id
                        when {
                            isShare -> {
                                val uris = sharedUris!!
                                if (uris.size == 1) launchCropActivity(uris[0])
                                else launchMultiCropActivity(uris)
                            }
                            isUpdateOnly -> applyEffectDirectly(item.id)
                            else -> pendingMode = true
                        }
                    },
                    onBack = { finish() }
                )

                if (pendingMode) {
                    WallpaperModeSheet(
                        title = getString(R.string.mode_title),
                        onDismiss = { pendingMode = false },
                        onPickSingle = { pendingMode = false; pickSingleImage.launch("image/*") },
                        onPickMultiple = { pendingMode = false; pickMultipleImages.launch("image/*") },
                        onPickThemePlaylists = {
                            pendingMode = false
                            launchThemePlaylistEditor()
                        },
                        onOpenSaved = {
                            pendingMode = false
                            startActivity(SavedPlaylistsActivity.intent(this, selectedEffectId))
                            finish()
                        },
                        onPickFolder = if (FolderPlaylistSource.isAvailable) {
                            {
                                pendingMode = false
                                startActivity(FolderPickerActivity.intent(this, selectedEffectId))
                                finish()
                            }
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun loadCurrentWallpaperPreview() {
        val file = File(filesDir, "wallpaper.jpg")
        if (!file.exists()) return
        ioExecutor.execute {
            try {
                val bitmap = BitmapDecoder.decodePreview(file)
                runOnUiThread {
                    if (isDestroyed) {
                        bitmap.recycle()
                    } else {
                        previewBitmap = bitmap.asImageBitmap()
                    }
                }
            } catch (error: IOException) {
                Log.w(TAG, "Current wallpaper preview could not be loaded", error)
            } catch (error: RuntimeException) {
                Log.e(TAG, "Unexpected wallpaper preview failure", error)
            }
        }
    }

    private fun extractSharedUris(): ArrayList<Uri>? {
        val type = intent.type
        if (type == null || !type.startsWith("image/")) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                if (uri != null) arrayListOf(uri) else null
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                if (!uris.isNullOrEmpty()) ArrayList(uris) else null
            }
            else -> null
        }
    }

    private fun launchCropActivity(uri: Uri) {
        val intent = if (EffectCatalog.isReverse(selectedEffectId)) {
            Intent(this, BlurToSharpCropActivity::class.java)
        } else {
            Intent(this, CropActivity::class.java)
        }
        intent.data = uri
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.putExtra("EFFECT_ID", selectedEffectId)
        startActivity(intent)
        finish()
    }

    private fun launchMultiCropActivity(uris: ArrayList<Uri>) {
        val intent = Intent(this, PlaylistEditorActivity::class.java)
        intent.data = uris[0]
        val clipData = ClipData.newUri(contentResolver, "Images", uris[0])
        for (i in 1 until uris.size) clipData.addItem(ClipData.Item(uris[i]))
        intent.clipData = clipData
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // See MainActivity.launchMultiCropActivity: ClipData already carries
        // every URI, so don't also duplicate the whole list into a second
        // extra — that doubles the Binder transaction payload and can
        // crash startActivity() outright for large selections.
        intent.putExtra("EFFECT_ID", selectedEffectId)
        startActivity(intent)
        finish()
    }

    private fun launchThemePlaylistEditor() {
        startActivity(
            Intent(this, ThemePlaylistEditorActivity::class.java).apply {
                putExtra("EFFECT_ID", selectedEffectId)
            }
        )
        finish()
    }

    private fun applyEffectDirectly(effectId: String) {
        // The same images under another effect: Fine tuning starts fresh
        // (the same effect again keeps it). Written before the picker opens,
        // because its preview starts the effect at once and reads these.
        val appPreferences = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val editor = appPreferences.edit()
        FineTuneRetention.applyTo(this, editor, effectId, imagesChanged = false)
        editor.commit()
        if (WallpaperEffectServices.launchPicker(this, effectId)) {
            finish()
        } else {
            Toast.makeText(
                this,
                getString(R.string.playlist_no_picker),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private companion object {
        const val TAG = "EffectSelection"
    }
}
