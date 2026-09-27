package com.app.nosatmosphereeffect

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import com.app.nosatmosphereeffect.activity.AdvancedSettingsActivity
import com.app.nosatmosphereeffect.activity.BlurToSharpCropActivity
import com.app.nosatmosphereeffect.activity.CropActivity
import com.app.nosatmosphereeffect.activity.EffectSelectionActivity
import com.app.nosatmosphereeffect.activity.FineTuneRetention
import com.app.nosatmosphereeffect.activity.FolderPickerActivity
import com.app.nosatmosphereeffect.activity.PaletteDiagnosticsActivity
import com.app.nosatmosphereeffect.activity.PlaylistEditorActivity
import com.app.nosatmosphereeffect.activity.SavedPlaylistsActivity
import com.app.nosatmosphereeffect.activity.ThemePlaylistEditorActivity
import com.app.nosatmosphereeffect.activity.WallpaperEffectServices
import com.app.nosatmosphereeffect.helper.FolderPlaylistSource
import com.app.nosatmosphereeffect.helper.PlaylistModeManager
import com.app.nosatmosphereeffect.helper.SystemColorSyncPreferences
import com.app.nosatmosphereeffect.helper.WallpaperBehaviorPreferences
import com.app.nosatmosphereeffect.helper.WallpaperBehaviorSettings
import com.app.nosatmosphereeffect.image.BitmapDecoder
import com.app.nosatmosphereeffect.renderer.status.RendererRuntimeStatus
import com.app.nosatmosphereeffect.storage.ActiveFolderWatch
import com.app.nosatmosphereeffect.storage.SavedPlaylistLibrary
import com.app.nosatmosphereeffect.storage.WallpaperStorageCoordinator
import com.app.nosatmosphereeffect.renderer.status.RendererRuntimeStatusListener
import com.app.nosatmosphereeffect.renderer.status.RendererRuntimeStatusRepository
import com.app.nosatmosphereeffect.ui.model.EffectCatalog
import com.app.nosatmosphereeffect.ui.model.RendererStatusUiModel
import com.app.nosatmosphereeffect.ui.model.rendererStatusUiModel
import com.app.nosatmosphereeffect.ui.screens.MainScreen
import com.app.nosatmosphereeffect.ui.theme.AppearancePreferences
import com.app.nosatmosphereeffect.ui.theme.AppThemeMode
import com.app.nosatmosphereeffect.ui.theme.AtmoEngineTheme
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var wallpaperActive by mutableStateOf(false)
    private var statusText by mutableStateOf("")
    private var isPlaylistModeActive by mutableStateOf(false)
    private var isThemePlaylistModeActive by mutableStateOf(false)
    private var syncColors by mutableStateOf(true)
    private var wallpaperBehavior by mutableStateOf(WallpaperBehaviorSettings())
    private var expressiveThemeEnabled by mutableStateOf(true)
    private var themeMode by mutableStateOf(AppThemeMode.SYSTEM)
    private var pitchBlackEnabled by mutableStateOf(false)
    private var activeEffectId by mutableStateOf<String?>(null)
    private var previewBitmap by mutableStateOf<ImageBitmap?>(null)
    private var rendererRuntimeStatus = RendererRuntimeStatus.idle()
    private var rendererStatusUi by mutableStateOf<RendererStatusUiModel?>(null)
    private var skipNextResumeStatusRefresh = false
    private var titleTapCount = 0
    private var folderAccessRequested = false
    private var lastTitleTapTime = 0L

    private val rendererStatusListener = RendererRuntimeStatusListener { status ->
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            rendererRuntimeStatus = status
            refreshRendererStatusUi()
        }
    }

    private val pickSingleImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { launchCropActivity(it) }
        }

    private val pickMultipleImages =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
            if (uris.isNotEmpty()) launchMultiCropActivity(ArrayList(uris))
        }

    private val requestFolderAccess =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (FolderPlaylistSource.hasFullAccess(this)) syncPlaylistLibrary()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initializeSmartDefaults()
        expressiveThemeEnabled = AppearancePreferences.isExpressiveEnabled(this)
        themeMode = AppearancePreferences.getThemeMode(this)
        pitchBlackEnabled = AppearancePreferences.isPitchBlackEnabled(this)
        RendererRuntimeStatusRepository.addListener(this, rendererStatusListener)

        statusText = getString(R.string.status_instruction)
        checkWallpaperStatus()
        skipNextResumeStatusRefresh = true

        setContent {
            AtmoEngineTheme(
                expressive = expressiveThemeEnabled,
                themeMode = themeMode,
                pitchBlack = pitchBlackEnabled
            ) {
                MainScreen(
                    wallpaperActive = wallpaperActive,
                    statusText = statusText,
                    isSamsungDevice = isSamsungDevice(),
                    activeEffectId = activeEffectId,
                    previewBitmap = previewBitmap,
                    isPlaylistMode = isPlaylistModeActive,
                    isThemePlaylistMode = isThemePlaylistModeActive,
                    syncColors = syncColors,
                    wallpaperBehavior = wallpaperBehavior,
                    rendererStatus = rendererStatusUi,
                    onSyncColorsChange = { updateSyncColors(it) },
                    expressiveThemeEnabled = expressiveThemeEnabled,
                    onExpressiveThemeChange = { updateExpressiveTheme(it) },
                    themeMode = themeMode,
                    onThemeModeChange = { updateThemeMode(it) },
                    pitchBlackEnabled = pitchBlackEnabled,
                    onPitchBlackChange = { updatePitchBlack(it) },
                    onSetupWallpaper = {
                        startActivity(Intent(this, EffectSelectionActivity::class.java))
                    },
                    onChangeEffect = {
                        val intent = Intent(this, EffectSelectionActivity::class.java)
                        intent.putExtra("UPDATE_EFFECT_ONLY", true)
                        startActivity(intent)
                    },
                    onPickSingleImage = { pickSingleImage.launch("image/*") },
                    onPickMultipleImages = { pickMultipleImages.launch("image/*") },
                    onPickThemePlaylists = { launchThemePlaylistEditor(editExisting = false) },
                    onEditExistingPlaylist = { launchEditExistingPlaylist() },
                    onOpenSavedPlaylists = {
                        startActivity(
                            SavedPlaylistsActivity.intent(this, getActiveEffectType() ?: "ORIGINAL")
                        )
                    },
                    onPickFolderPlaylist = if (FolderPlaylistSource.isAvailable) {
                        {
                            startActivity(
                                FolderPickerActivity.intent(this, getActiveEffectType() ?: "ORIGINAL")
                            )
                        }
                    } else {
                        null
                    },
                    onAdvancedSettings = { openAdvancedSettings() },
                    onTitleTap = { handleTitleTap() }
                )
            }
        }

    }

    override fun onResume() {
        super.onResume()
        expressiveThemeEnabled = AppearancePreferences.isExpressiveEnabled(this)
        themeMode = AppearancePreferences.getThemeMode(this)
        pitchBlackEnabled = AppearancePreferences.isPitchBlackEnabled(this)
        if (skipNextResumeStatusRefresh) {
            skipNextResumeStatusRefresh = false
        } else {
            checkWallpaperStatus()
        }
        syncPlaylistLibrary()
    }

    /**
     * Keeps the saved-playlist library current: adopts a playlist applied
     * before the library existed, and (folder build) mirrors the active
     * playlist's watched folders, adding new images and dropping deleted ones.
     */
    private fun syncPlaylistLibrary() {
        if (FolderPlaylistSource.isAvailable && !folderAccessRequested &&
            !FolderPlaylistSource.hasFullAccess(this) &&
            !ActiveFolderWatch.read(this).isEmpty
        ) {
            // Once per launch: without full photo access new images stay invisible.
            folderAccessRequested = true
            requestFolderAccess.launch(FolderPlaylistSource.requestedPermissions(this))
        }
        ioExecutor.execute {
            try {
                WallpaperStorageCoordinator.runExclusive {
                    if (PlaylistModeManager.getMode(this) == PlaylistModeManager.MODE_STANDARD &&
                        SavedPlaylistLibrary.activeId(this) == null
                    ) {
                        SavedPlaylistLibrary.preserveActive(this)
                    }
                }
                val result = FolderPlaylistSource.syncActivePlaylist(this)
                if (result.changed) {
                    val message = listOfNotNull(
                        result.added.takeIf { it > 0 }?.let {
                            if (it == 1) "added 1 new image" else "added $it new images"
                        },
                        result.removed.takeIf { it > 0 }?.let {
                            if (it == 1) "removed 1 deleted image" else "removed $it deleted images"
                        }
                    ).joinToString(" and ").replaceFirstChar(Char::uppercase)
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        Toast.makeText(this, "Folders synced: $message", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Could not sync the playlist library", error)
            }
        }
    }

    override fun onDestroy() {
        RendererRuntimeStatusRepository.removeListener(rendererStatusListener)
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun isSamsungDevice(): Boolean =
        Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    private fun initializeSmartDefaults() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (!prefs.contains("poll_interval")) {
            val isSamsung = isSamsungDevice()
            val defaultPoll = if (isSamsung) 30000L else 50L
            val defaultDelay = if (isSamsung) 0L else 800L
            prefs.edit {
                putLong("poll_interval", defaultPoll)
                putLong("lock_delay", defaultDelay)
            }
        }
    }

    private fun checkWallpaperStatus() {
        val activeEffect = getActiveEffectType()
        if (activeEffect != null) {
            activeEffectId = activeEffect
            wallpaperActive = true
            statusText = "Wallpaper is active. Customize your experience below."
            isPlaylistModeActive = PlaylistModeManager.isPlaylistMode(this)
            isThemePlaylistModeActive =
                isPlaylistModeActive && PlaylistModeManager.isThemeMode(this)

            syncColors = SystemColorSyncPreferences.isEnabled(this)
            wallpaperBehavior = WallpaperBehaviorPreferences.read(this)
            loadWallpaperPreview()
        } else {
            activeEffectId = null
            previewBitmap = null
            wallpaperActive = false
            isPlaylistModeActive = false
            isThemePlaylistModeActive = false
            wallpaperBehavior = WallpaperBehaviorSettings()
            statusText = getString(R.string.status_instruction)
        }
        refreshRendererStatusUi()
    }

    private fun refreshRendererStatusUi() {
        rendererStatusUi = rendererStatusUiModel(
            wallpaperActive = wallpaperActive,
            activeEffectId = activeEffectId,
            runtimeStatus = rendererRuntimeStatus
        )
    }

    private fun updateExpressiveTheme(enabled: Boolean) {
        expressiveThemeEnabled = enabled
        AppearancePreferences.setExpressiveEnabled(this, enabled)
    }

    private fun updateThemeMode(mode: AppThemeMode) {
        themeMode = mode
        AppearancePreferences.setThemeMode(this, mode)
    }

    private fun updatePitchBlack(enabled: Boolean) {
        pitchBlackEnabled = enabled
        AppearancePreferences.setPitchBlackEnabled(this, enabled)
    }

    private fun loadWallpaperPreview() {
        val file = File(filesDir, "wallpaper.jpg")
        if (!file.exists()) {
            previewBitmap = null
            return
        }
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
                Log.w(TAG, "Wallpaper preview could not be loaded", error)
                runOnUiThread {
                    if (!isDestroyed) previewBitmap = null
                }
            } catch (error: RuntimeException) {
                Log.e(TAG, "Unexpected wallpaper preview failure", error)
                runOnUiThread {
                    if (!isDestroyed) previewBitmap = null
                }
            }
        }
    }

    private fun updateSyncColors(enabled: Boolean) {
        syncColors = enabled
        SystemColorSyncPreferences.setEnabled(this, enabled)
        sendConfigUpdate()
    }

    private fun sendConfigUpdate() {
        val intent = Intent("com.app.nosatmosphereeffect.UPDATE_CONFIG")
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun handleTitleTap() {
        if (!wallpaperActive) {
            titleTapCount = 0
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTitleTapTime > 4_000L) titleTapCount = 0
        lastTitleTapTime = now
        titleTapCount++
        if (titleTapCount >= 7) {
            titleTapCount = 0
            startActivity(Intent(this, PaletteDiagnosticsActivity::class.java))
        }
    }

    private fun openAdvancedSettings() {
        val intent = Intent(this, AdvancedSettingsActivity::class.java)
        intent.putExtra("ACTIVE_EFFECT_TYPE", getActiveEffectType() ?: "ORIGINAL")
        intent.putExtra("IS_SAMSUNG", isSamsungDevice())
        intent.putExtra("IS_PLAYLIST_MODE", isPlaylistModeActive)
        startActivity(intent)
    }

    private fun getActiveEffectType(): String? = FineTuneRetention.liveEffectId(this)

    private fun launchEditExistingPlaylist() {
        if (isThemePlaylistModeActive) {
            launchThemePlaylistEditor(editExisting = true)
            return
        }
        if (PlaylistModeManager.imageFiles(PlaylistModeManager.standardPlaylistDir(this)).isEmpty()) {
            return
        }

        val effectId = getActiveEffectType() ?: "ORIGINAL"
        val intent = Intent(this, PlaylistEditorActivity::class.java)
        intent.putExtra("EDIT_EXISTING", true)
        intent.putExtra("EFFECT_ID", effectId)
        startActivity(intent)
    }

    private fun launchThemePlaylistEditor(editExisting: Boolean) {
        val intent = Intent(this, ThemePlaylistEditorActivity::class.java)
        intent.putExtra("EDIT_EXISTING", editExisting)
        intent.putExtra("EFFECT_ID", getActiveEffectType() ?: "ORIGINAL")
        startActivity(intent)
    }

    private fun launchCropActivity(uri: Uri) {
        val effectId = getActiveEffectType() ?: "ORIGINAL"
        val intent = if (EffectCatalog.isReverse(effectId)) {
            Intent(this, BlurToSharpCropActivity::class.java)
        } else {
            Intent(this, CropActivity::class.java)
        }
        intent.data = uri
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.putExtra("EFFECT_ID", effectId)
        startActivity(intent)
    }

    private fun launchMultiCropActivity(uris: ArrayList<Uri>) {
        val effectId = getActiveEffectType() ?: "ORIGINAL"
        val intent = Intent(this, PlaylistEditorActivity::class.java)
        intent.data = uris[0]
        val clipData = ClipData.newUri(contentResolver, "Images", uris[0])
        for (i in 1 until uris.size) clipData.addItem(ClipData.Item(uris[i]))
        intent.clipData = clipData
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Don't also pass the same URIs via putParcelableArrayListExtra:
        // ClipData already carries every URI (and is what grants read
        // permission for each of them), so duplicating the whole list into
        // a second extra doubles the Binder transaction payload for no
        // reason. With large selections (hundreds+ of images) that can
        // exceed the transaction size limit and crash startActivity()
        // itself. PlaylistEditorActivity reads the URIs back out of
        // intent.clipData.
        intent.putExtra("EFFECT_ID", effectId)
        startActivity(intent)
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
