package com.app.nosatmosphereeffect.activity

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.app.nosatmosphereeffect.helper.AtmosphereGlassPolicy
import com.app.nosatmosphereeffect.helper.FolderPlaylistSource
import com.app.nosatmosphereeffect.helper.FolderSyncPolicy
import com.app.nosatmosphereeffect.helper.MediaImage
import com.app.nosatmosphereeffect.helper.GlassEffectPreferences
import com.app.nosatmosphereeffect.helper.GlassEffectSettings
import com.app.nosatmosphereeffect.helper.MatrixStatePolicy
import com.app.nosatmosphereeffect.helper.PlaylistModeManager
import com.app.nosatmosphereeffect.helper.SystemColorSyncPreferences
import com.app.nosatmosphereeffect.helper.WallpaperFitHelper
import com.app.nosatmosphereeffect.storage.ActiveFolderWatch
import com.app.nosatmosphereeffect.storage.FileTransactions
import com.app.nosatmosphereeffect.storage.FolderWatchState
import com.app.nosatmosphereeffect.storage.PlaylistCollectionStore
import com.app.nosatmosphereeffect.storage.PlaylistImageSource
import com.app.nosatmosphereeffect.storage.SavedPlaylistLibrary
import com.app.nosatmosphereeffect.storage.SharedPreferencesTransactions
import com.app.nosatmosphereeffect.storage.WallpaperStorageCoordinator
import com.app.nosatmosphereeffect.storage.WatchedFolder
import com.app.nosatmosphereeffect.ui.screens.PlaylistEditorScreen
import com.app.nosatmosphereeffect.ui.screens.PlaylistEntry
import com.app.nosatmosphereeffect.ui.screens.ProcessingOverlay
import com.app.nosatmosphereeffect.ui.screens.RenamePlaylistDialog
import com.app.nosatmosphereeffect.ui.screens.SimpleConfirmDialog
import com.app.nosatmosphereeffect.ui.model.EffectCatalog
import com.app.nosatmosphereeffect.ui.theme.AtmoEngineTheme
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException

private typealias PlaylistItem = PlaylistDraftItem

class PlaylistEditorActivity : ComponentActivity() {

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val draftState: StandardPlaylistDraftState by viewModels()
    private val playlistItems get() = draftState.items
    private var effectId: String = "ORIGINAL"
    private var editingPosition = -1
    private var isEditExisting = false

    private var showApplyConfirm by mutableStateOf(false)
    private var isProcessing
        get() = draftState.isProcessing
        set(value) {
            draftState.isProcessing = value
        }

    private var defaultFitMode by mutableStateOf(WallpaperFitHelper.MODE_FILL)
    private var defaultFillMode by mutableStateOf(WallpaperFitHelper.FILL_BLACK)
    private var showCropOptions by mutableStateOf(false)
    private var showRename by mutableStateOf(false)

    private val pickFolders =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                updateWatchedFolders(FolderPickerActivity.foldersFrom(result.data))
            }
        }

    private val pickMultipleImages =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                uris.forEach {
                    playlistItems.add(
                        PlaylistItem(
                            originalUri = it,
                            fitMode = defaultFitMode,
                            fillMode = defaultFillMode
                        )
                    )
                }
                Toast.makeText(this, "${uris.size} images added", Toast.LENGTH_SHORT).show()
            }
        }

    private val editImageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val resultUriString = result.data?.getStringExtra("CROPPED_IMAGE_PATH")
                val matrixState = MatrixStatePolicy.copyIfValid(
                    result.data?.getFloatArrayExtra("MATRIX_STATE")
                )
                val fitMode = result.data?.getStringExtra("FIT_MODE")
                val fillMode = result.data?.getStringExtra("FILL_MODE")

                if (resultUriString != null && editingPosition in playlistItems.indices) {
                    val previous = playlistItems[editingPosition]
                    playlistItems[editingPosition] = previous.copy(
                        isEdited = true,
                        editedFilePath = resultUriString,
                        matrixState = matrixState,
                        fitMode = fitMode ?: defaultFitMode,
                        fillMode = fillMode ?: defaultFillMode
                    )
                    if (previous.editedFilePath != resultUriString) {
                        deleteCachedEdit(previous.editedFilePath)
                    }
                } else {
                    deleteCachedEdit(resultUriString)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

        effectId = intent.getStringExtra("EFFECT_ID") ?: "ORIGINAL"
        // Opening the saved entry of the active playlist edits the active copy,
        // which may hold folder images newer than the saved one.
        isEditExisting = intent.getBooleanExtra("EDIT_EXISTING", false) ||
            intent.getStringExtra(EXTRA_SAVED_PLAYLIST_ID)?.let { id ->
                id == SavedPlaylistLibrary.activeId(this) &&
                    PlaylistModeManager.getMode(this) == PlaylistModeManager.MODE_STANDARD
            } == true
        if (!draftState.initialized) {
            if (isEditExisting) {
                defaultFitMode = WallpaperFitHelper.getDefaultFitMode(this)
                defaultFillMode = WallpaperFitHelper.getDefaultFillMode(this)
            }
            val restoredAtmosphereGlass =
                if (savedInstanceState?.containsKey(STATE_ATMOSPHERE_GLASS) == true) {
                    savedInstanceState.getBoolean(STATE_ATMOSPHERE_GLASS)
                } else {
                    null
                }
            draftState.atmosphereGlassEnabled =
                AtmosphereGlassPolicy.resolveEnabled(
                    effectId,
                    restoredAtmosphereGlass
                        ?: if (isEditExisting) readStoredAtmosphereGlass() else false
                )
            val restored = PlaylistDraftStateCodec.decode(
                savedInstanceState?.getParcelableArrayList(
                    STATE_ITEMS,
                    Bundle::class.java
                )
            )
            restoreLibraryState(savedInstanceState)
            val savedPlaylistId = intent.getStringExtra(EXTRA_SAVED_PLAYLIST_ID)
            val initialFolders = FolderPickerActivity.foldersFrom(intent)
            if (restored != null) {
                playlistItems.addAll(restored)
            } else if (isEditExisting) {
                loadActivePlaylist()
            } else if (savedPlaylistId != null) {
                loadSavedPlaylist(savedPlaylistId)
            } else if (initialFolders.isNotEmpty()) {
                updateWatchedFolders(initialFolders)
            } else {
                val uris = intent.getParcelableArrayListExtra("IMAGE_URIS", Uri::class.java)
                    ?: intent.clipData?.let { clip ->
                        (0 until clip.itemCount).mapNotNull { index ->
                            clip.getItemAt(index).uri
                        }
                    }
                uris?.forEach {
                    playlistItems.add(
                        PlaylistItem(
                            originalUri = it,
                            fitMode = defaultFitMode,
                            fillMode = defaultFillMode
                        )
                    )
                }
            }
            draftState.initialized = true
        }

        if (savedInstanceState != null) {
            editingPosition = savedInstanceState.getInt("EDITING_POS", -1)
        }

        setContent {
            AtmoEngineTheme {
                BackHandler(enabled = isProcessing) {}
                LaunchedEffect(draftState.applyCompleted, draftState.applyError) {
                    when {
                        draftState.applyCompleted -> showSuccessfulApply()
                        draftState.applyError != null -> {
                            val message = draftState.applyError
                            draftState.applyError = null
                            Toast.makeText(
                                this@PlaylistEditorActivity,
                                message,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                PlaylistEditorScreen(
                    effectId = effectId,
                    title = draftState.playlistName
                        ?: if (isEditExisting || draftState.savedPlaylistId != null) {
                            "Edit Playlist"
                        } else {
                            "New Playlist"
                        },
                    onRename = { showRename = true },
                    watchedFolders = draftState.watchedFolders.map(WatchedFolder::name),
                    onAddFolder = if (FolderPlaylistSource.isAvailable) {
                        { launchFolderPicker() }
                    } else {
                        null
                    },
                    onRemoveFolder = { index ->
                        if (index in draftState.watchedFolders.indices) {
                            draftState.watchedFolders.removeAt(index)
                        }
                    },
                    showAtmosphereGlassOption =
                        EffectCatalog.supportsAtmosphereGlass(effectId),
                    atmosphereGlassEnabled = draftState.atmosphereGlassEnabled,
                    onAtmosphereGlassEnabledChange = { enabled ->
                        draftState.atmosphereGlassEnabled =
                            AtmosphereGlassPolicy.resolveEnabled(effectId, enabled)
                    },
                    entries = playlistItems.map { item ->
                        val displayUri = if (item.isEdited && item.editedFilePath != null) {
                            Uri.parse("file://${item.editedFilePath}")
                        } else {
                            item.originalUri
                        }
                        PlaylistEntry(displayUri = displayUri, isEdited = item.isEdited)
                    },
                    onEditItem = { pos ->
                        editingPosition = pos
                        launchEditActivity(playlistItems[pos])
                    },
                    onDeleteItem = { pos ->
                        if (pos in playlistItems.indices) {
                            deleteCachedEdit(playlistItems.removeAt(pos).editedFilePath)
                        }
                    },
                    onAddMore = { pickMultipleImages.launch("image/*") },
                    onApply = { showApplyDialog() },
                    onBack = { finish() },
                    defaultFitMode = defaultFitMode,
                    defaultFillMode = defaultFillMode,
                    onDefaultFitModeChanged = { fit, fill ->
                        defaultFitMode = fit
                        defaultFillMode = fill
                        WallpaperFitHelper.setDefaultModes(this, fit, fill)
                    },
                    showCropOptions = showCropOptions,
                    onShowCropOptions = { showCropOptions = true },
                    onDismissCropOptions = { showCropOptions = false }
                )

                if (showRename) {
                    RenamePlaylistDialog(
                        initialName = draftState.playlistName.orEmpty(),
                        onConfirm = { name ->
                            showRename = false
                            draftState.playlistName = name.ifBlank { null }
                        },
                        onDismiss = { showRename = false }
                    )
                }

                if (showApplyConfirm) {
                    SimpleConfirmDialog(
                        title = "Apply Wallpaper",
                        message = "On the next screen, tap:\n\n" +
                            "Set Wallpaper › Home Screen and Lock Screen.\n\n" +
                            "That way the lock screen effect works properly.",
                        confirmLabel = "Set Wallpaper",
                        dismissLabel = "Cancel",
                        onConfirm = {
                            showApplyConfirm = false
                            applyFromDialog()
                        },
                        onDismiss = { showApplyConfirm = false }
                    )
                }

                if (isProcessing) {
                    ProcessingOverlay(
                        message = if (draftState.totalCount > 0) {
                            "Processing ${draftState.processedCount} of " +
                                "${draftState.totalCount} images…"
                        } else {
                            "Getting your playlist ready…"
                        }
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("EDITING_POS", editingPosition)
        outState.putString(STATE_SAVED_ID, draftState.savedPlaylistId)
        outState.putString(STATE_NAME, draftState.playlistName)
        outState.putStringArrayList(
            STATE_FOLDER_IDS,
            ArrayList(draftState.watchedFolders.map(WatchedFolder::id))
        )
        outState.putStringArrayList(
            STATE_FOLDER_NAMES,
            ArrayList(draftState.watchedFolders.map(WatchedFolder::name))
        )
        outState.putLongArray(STATE_KNOWN_IDS, draftState.knownMediaIds.toLongArray())
        outState.putBoolean(
            STATE_ATMOSPHERE_GLASS,
            draftState.atmosphereGlassEnabled
        )
        // Only round-trip the draft through the Bundle when it's small
        // enough to stay safely under the Binder transaction size limit.
        // This callback exists for actual process-death recovery; the
        // ViewModel (draftState) already keeps the draft across ordinary
        // config changes like rotation without going through this Bundle
        // at all. For a very large playlist, skipping this means process
        // death would lose the in-progress draft -- an acceptable
        // trade-off next to crashing on every backgrounding.
        if (playlistItems.size <= MAX_ITEMS_TO_PERSIST_IN_BUNDLE) {
            outState.putParcelableArrayList(
                STATE_ITEMS,
                PlaylistDraftStateCodec.encode(playlistItems)
            )
        }
    }

    override fun onDestroy() {
        if (isChangingConfigurations) {
            ioExecutor.shutdown()
        } else {
            ioExecutor.shutdownNow()
        }
        if (isFinishing && !isChangingConfigurations) {
            playlistItems.forEach { item -> deleteCachedEdit(item.editedFilePath) }
        }
        super.onDestroy()
    }

    private fun launchEditActivity(item: PlaylistItem) {
        val intent = Intent(this, MultiImageCropActivity::class.java)
        intent.data = item.originalUri
        if (item.matrixState != null) {
            intent.putExtra("MATRIX_STATE", item.matrixState)
        }

        val fitMode: String
        val fillMode: String
        if (item.isEdited) {
            fitMode = item.fitMode
            fillMode = item.fillMode
        } else {
            fitMode = defaultFitMode
            fillMode = defaultFillMode
        }

        intent.putExtra("INITIAL_FIT_MODE", fitMode)
        intent.putExtra("INITIAL_FILL_MODE", fillMode)
        editImageLauncher.launch(intent)
    }

    private fun applyPlaylist() {
        isProcessing = true
        draftState.applyCompleted = false
        draftState.applyError = null
        draftState.processedCount = 0
        draftState.totalCount = playlistItems.size

        val items = playlistItems.map { item ->
            if (item.isEdited) {
                item.copy(matrixState = item.matrixState?.copyOf())
            } else {
                item.copy(
                    matrixState = item.matrixState?.copyOf(),
                    fitMode = defaultFitMode,
                    fillMode = defaultFillMode
                )
            }
        }
        val atmosphereGlassEnabled = AtmosphereGlassPolicy.resolveEnabled(
            effectId,
            draftState.atmosphereGlassEnabled
        )
        val bounds = windowManager.currentWindowMetrics.bounds
        val targetSavedId = draftState.savedPlaylistId
        val playlistName = draftState.playlistName
        val folders = draftState.watchedFolders.toList()
        val knownMediaIds = draftState.knownMediaIds

        ioExecutor.execute {
            try {
                // Everything currently in the folders counts as seen, so
                // images removed from this draft are not re-added later.
                val watch = if (folders.isEmpty()) {
                    FolderWatchState()
                } else {
                    FolderWatchState(
                        folders,
                        knownMediaIds + FolderPlaylistSource.knownIdsFor(this, folders)
                    )
                }
                WallpaperStorageCoordinator.runExclusive {
                    // Keep the playlist being replaced; editing the active
                    // playlist (or its own saved entry) just updates it.
                    if (!isEditExisting &&
                        (targetSavedId == null || targetSavedId != SavedPlaylistLibrary.activeId(this))
                    ) {
                        SavedPlaylistLibrary.preserveActive(this)
                    }
                    val fileTransactions = mutableListOf<FileTransactions.ReplacementTransaction>()
                    val appPreferences =
                        getSharedPreferences(APP_PREFERENCES, Context.MODE_PRIVATE)
                    val preferenceSnapshots = SharedPreferencesTransactions.snapshot(
                        listOf(
                            appPreferences,
                            getSharedPreferences(WALLPAPER_PREFERENCES, Context.MODE_PRIVATE),
                            getSharedPreferences(
                                WallpaperFitHelper.PREFS_NAME,
                                Context.MODE_PRIVATE
                            )
                        )
                    )
                    val glassSettings = GlassEffectPreferences.read(appPreferences)
                    var preferencesTouched = false
                    try {
                        fileTransactions +=
                            persistPlaylist(items, bounds.width(), bounds.height())

                        val playlistDir = PlaylistModeManager.standardPlaylistDir(this)
                        fileTransactions += PlaylistCollectionStore.beginActivatingFirst(
                            this,
                            playlistDir,
                            File(filesDir, PlaylistModeManager.STANDARD_ORIGINALS_DIR),
                            File(filesDir, WallpaperFitHelper.ACTIVE_WALLPAPER_FILE),
                            File(filesDir, WallpaperFitHelper.ACTIVE_SOURCE_FILE)
                        )
                        preferencesTouched = true
                        items.firstOrNull()?.let {
                            WallpaperFitHelper.setActiveModes(this, it.fitMode, it.fillMode)
                        }
                        resetPreferences(
                            appPreferences,
                            atmosphereGlassEnabled,
                            glassSettings
                        )
                        FileTransactions.commitAll(fileTransactions)
                    } catch (failure: Exception) {
                        FileTransactions.rollbackAll(fileTransactions, failure)
                        if (preferencesTouched) {
                            SharedPreferencesTransactions.restoreAll(
                                preferenceSnapshots,
                                failure
                            )
                        }
                        throw failure
                    }
                    cleanupObsoleteThemeData()
                    ActiveFolderWatch.write(this, watch)
                    try {
                        val savedId = SavedPlaylistLibrary.saveActive(
                            this,
                            targetSavedId,
                            playlistName,
                            watch
                        )
                        SavedPlaylistLibrary.setActiveId(this, savedId)
                    } catch (error: Exception) {
                        Log.w(TAG, "Playlist applied, but it could not be saved to the library", error)
                    }
                }

                runOnUiThread {
                    isProcessing = false
                    draftState.applyCompleted = true
                }
            } catch (error: IOException) {
                reportApplyFailure(
                    "Unable to persist playlist",
                    error,
                    "Couldn't save the playlist. Check you have some free space and try again."
                )
            } catch (error: SecurityException) {
                reportApplyFailure(
                    "Playlist image permission was rejected",
                    error,
                    "Atmo Engine can't open one of the images anymore. Try adding it again."
                )
            } catch (error: JSONException) {
                reportApplyFailure(
                    "Unable to create playlist metadata",
                    error,
                    "Something went wrong saving the playlist. Please try again."
                )
            } catch (error: RuntimeException) {
                reportApplyFailure(
                    "Unexpected playlist apply failure",
                    error,
                    "Couldn't get the playlist ready. Please try again."
                )
            }
        }
    }

    private fun persistPlaylist(
        items: List<PlaylistItem>,
        width: Int,
        height: Int
    ): FileTransactions.ReplacementTransaction {
        if (items.isEmpty()) throw IOException("Playlist is empty")
        if (width <= 0 || height <= 0) throw IOException("Display dimensions are unavailable")

        val token = UUID.randomUUID().toString()
        val stagedImages = File(filesDir, ".playlist-$token.staged")
        val stagedOriginals = File(filesDir, ".playlist-originals-$token.staged")
        try {
            PlaylistCollectionStore.stage(
                context = this,
                items = items.map { item ->
                    PlaylistImageSource(
                        originalUri = item.originalUri,
                        isEdited = item.isEdited,
                        editedFilePath = item.editedFilePath,
                        matrixState = item.matrixState,
                        fitMode = item.fitMode,
                        fillMode = item.fillMode,
                        mediaId = item.mediaId
                    )
                },
                stagedImages = stagedImages,
                stagedOriginals = stagedOriginals,
                targetWidth = width,
                targetHeight = height,
                onProgress = { processed, total ->
                    runOnUiThread {
                        draftState.processedCount = processed
                        draftState.totalCount = total
                    }
                }
            )
            return FileTransactions.beginReplacingDirectories(
                listOf(
                    stagedImages to PlaylistModeManager.standardPlaylistDir(this),
                    stagedOriginals to File(
                        filesDir,
                        PlaylistModeManager.STANDARD_ORIGINALS_DIR
                    )
                )
            )
        } catch (failure: Exception) {
            cleanupStaging(stagedImages, failure)
            cleanupStaging(stagedOriginals, failure)
            throw failure
        }
    }

    private fun resetPreferences(
        appPreferences: SharedPreferences,
        atmosphereGlassEnabled: Boolean,
        glassSettings: GlassEffectSettings
    ) {
        val wallpaperPreferences =
            getSharedPreferences(WALLPAPER_PREFERENCES, Context.MODE_PRIVATE)
        val preservedRotation = if (isEditExisting) {
            wallpaperPreferences.getLong(KEY_ROTATION_INTERVAL, 0L).coerceAtLeast(0L)
        } else {
            null
        }

        SystemColorSyncPreferences.isEnabled(this)
        // A new playlist or image set under the same effect keeps Fine tuning;
        // a new effect starts fresh.
        val keepFineTune = FineTuneRetention.keeps(
            this,
            appPreferences,
            effectId,
            whenUnknown = isEditExisting
        )
        val appPreferencesEditor = appPreferences.edit()
        if (!keepFineTune) {
            appPreferencesEditor.clear()
        }
        FineTuneRetention.markApplied(appPreferencesEditor, effectId)
        appPreferencesEditor.putBoolean(
            AtmosphereGlassPolicy.ENABLED_KEY,
            atmosphereGlassEnabled
        )
        if (
            !GlassEffectPreferences.write(appPreferencesEditor, glassSettings)
                .commit()
        ) {
            throw IOException("Could not persist effect preferences")
        }

        val editor = wallpaperPreferences.edit()
            .clear()
            .putString(PlaylistModeManager.KEY_MODE, PlaylistModeManager.MODE_STANDARD)
            .putString(KEY_LAST_PLAYLIST_IMAGE, "wallpaper_0.jpg")
            .putLong(KEY_LAST_ROTATION, System.currentTimeMillis())
        if (preservedRotation != null) {
            editor.putLong(KEY_ROTATION_INTERVAL, preservedRotation)
        }
        if (!editor.commit()) {
            throw IOException("Could not initialize playlist preferences")
        }
    }

    private fun cleanupStaging(directory: File, failure: Throwable) {
        try {
            FileTransactions.deleteRecursively(directory)
        } catch (cleanupError: Exception) {
            failure.addSuppressed(cleanupError)
        }
    }

    private fun cleanupObsoleteThemeData() {
        val tasks = listOf<() -> Unit>(
            { PlaylistModeManager.clearThemeCollections(this) },
            {
                Files.deleteIfExists(
                    File(filesDir, WallpaperFitHelper.NEXT_WALLPAPER_FILE).toPath()
                )
            },
            { WallpaperFitHelper.deleteNextSource(filesDir) }
        )
        tasks.forEach { cleanup ->
            try {
                cleanup()
            } catch (error: Exception) {
                Log.w(TAG, "Playlist is active, but obsolete theme data remains", error)
            }
        }
    }

    private fun reportApplyFailure(
        logMessage: String,
        error: Throwable,
        userMessage: String
    ) {
        Log.e(TAG, logMessage, error)
        runOnUiThread {
            isProcessing = false
            draftState.applyError = userMessage
        }
    }

    private fun showSuccessfulApply() {
        if (!draftState.applyCompleted) return
        draftState.applyCompleted = false
        Toast.makeText(
            this,
            "All set. Now pick Home screen and Lock screen.",
            Toast.LENGTH_LONG
        ).show()
        sendBroadcast(Intent(ACTION_RELOAD_WALLPAPER).setPackage(packageName))
        activateService()
    }

    private fun deleteCachedEdit(path: String?) {
        try {
            PlaylistDraftCache.delete(this, path)
        } catch (error: Exception) {
            Log.w(TAG, "Could not remove a temporary playlist edit", error)
        }
    }

    private fun activateService() {
        if (WallpaperEffectServices.launchPicker(this, effectId)) {
            finish()
        } else {
            Toast.makeText(
                this,
                "Your phone doesn't seem to have a live wallpaper picker.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showApplyDialog() {
        showApplyConfirm = true
    }

    private fun restoreLibraryState(savedInstanceState: Bundle?) {
        savedInstanceState ?: return
        draftState.savedPlaylistId = savedInstanceState.getString(STATE_SAVED_ID)
        draftState.playlistName = savedInstanceState.getString(STATE_NAME)
        val ids = savedInstanceState.getStringArrayList(STATE_FOLDER_IDS).orEmpty()
        val names = savedInstanceState.getStringArrayList(STATE_FOLDER_NAMES).orEmpty()
        draftState.watchedFolders.addAll(
            ids.mapIndexed { index, id -> WatchedFolder(id, names.getOrElse(index) { id }) }
        )
        draftState.knownMediaIds =
            savedInstanceState.getLongArray(STATE_KNOWN_IDS)?.toSet().orEmpty()
    }

    private fun loadActivePlaylist() {
        val savedId = SavedPlaylistLibrary.activeId(this)
        draftState.savedPlaylistId = savedId
        draftState.playlistName = savedId?.let { SavedPlaylistLibrary.name(this, it) }
        applyWatchState(ActiveFolderWatch.read(this))
        loadExistingPlaylist(
            PlaylistModeManager.standardPlaylistDir(this),
            File(filesDir, PlaylistModeManager.STANDARD_ORIGINALS_DIR)
        )
        addNewFolderImages()
    }

    private fun loadSavedPlaylist(id: String) {
        if (!SavedPlaylistLibrary.exists(this, id)) {
            Toast.makeText(this, "This saved playlist isn't there anymore.", Toast.LENGTH_LONG)
                .show()
            return
        }
        draftState.savedPlaylistId = id
        draftState.playlistName = SavedPlaylistLibrary.name(this, id)
        applyWatchState(SavedPlaylistLibrary.watchState(this, id))
        loadExistingPlaylist(
            SavedPlaylistLibrary.imagesDir(this, id),
            SavedPlaylistLibrary.originalsDir(this, id)
        )
        addNewFolderImages()
    }

    private fun applyWatchState(state: FolderWatchState) {
        // A saved folder playlist opened in a build without folder support
        // keeps its images but stops following the folders.
        if (!FolderPlaylistSource.isAvailable) return
        draftState.watchedFolders.clear()
        draftState.watchedFolders.addAll(state.folders)
        draftState.knownMediaIds = state.knownMediaIds
    }

    private fun launchFolderPicker() {
        pickFolders.launch(
            Intent(this, FolderPickerActivity::class.java).putStringArrayListExtra(
                FolderPickerActivity.EXTRA_FOLDER_IDS,
                ArrayList(draftState.watchedFolders.map(WatchedFolder::id))
            )
        )
    }

    /** Replaces the watched folders and adds images from newly chosen ones. */
    private fun updateWatchedFolders(folders: List<WatchedFolder>) {
        draftState.watchedFolders.clear()
        draftState.watchedFolders.addAll(folders.distinctBy(WatchedFolder::id))
        addNewFolderImages()
    }

    /**
     * Mirrors the watched folders into the draft: appends images this draft
     * has not seen and drops entries whose source image was deleted.
     */
    private fun addNewFolderImages() {
        if (!FolderPlaylistSource.isAvailable || draftState.watchedFolders.isEmpty()) return
        if (!FolderPlaylistSource.hasFullAccess(this)) return
        val folderIds = draftState.watchedFolders.map(WatchedFolder::id)
        ioExecutor.execute {
            // null means the folders could not be read; never treat that as
            // "every image was deleted".
            val images = runCatching { FolderPlaylistSource.imagesIn(this, folderIds) }
                .onFailure { error -> Log.w(TAG, "Could not read the watched folders", error) }
                .getOrNull() ?: return@execute
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val present = images.mapTo(HashSet(), MediaImage::id)
                val removed = FolderSyncPolicy.removableIndices(
                    playlistItems.map(PlaylistItem::mediaId),
                    present
                )
                removed.sortedDescending().forEach { index ->
                    deleteCachedEdit(playlistItems.removeAt(index).editedFilePath)
                }
                if (removed.isNotEmpty()) {
                    Toast.makeText(
                        this,
                        if (removed.size == 1) "1 deleted image removed"
                        else "${removed.size} deleted images removed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                val fresh = images.filter { it.id !in draftState.knownMediaIds }
                if (fresh.isEmpty()) return@runOnUiThread
                draftState.knownMediaIds = draftState.knownMediaIds + fresh.map(MediaImage::id)
                fresh.forEach { image ->
                    playlistItems += PlaylistItem(
                        originalUri = image.uri,
                        fitMode = defaultFitMode,
                        fillMode = defaultFillMode,
                        mediaId = image.id
                    )
                }
                Toast.makeText(
                    this,
                    if (fresh.size == 1) "1 image added from folders"
                    else "${fresh.size} images added from folders",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun loadExistingPlaylist(playlistDir: File, originalsDir: File) {
        val metaFile = File(playlistDir, "metadata.json")

        if (!metaFile.isFile) {
            loadLegacyPlaylist(playlistDir)
            return
        }

        try {
            val metadata = JSONArray(metaFile.readText())
            repeat(metadata.length()) { index ->
                val item = metadata.getJSONObject(index)
                val wallpaper = File(playlistDir, "wallpaper_$index.jpg")
                val original = File(originalsDir, item.getString("original"))
                val source = when {
                    original.isFile -> original
                    wallpaper.isFile -> wallpaper
                    else -> {
                        Log.w(TAG, "Skipping playlist entry $index because its files are missing")
                        return@repeat
                    }
                }
                val savedEdited = item.optBoolean("isEdited", false) && wallpaper.isFile
                val matrix = item.optJSONArray("matrix")?.let { values ->
                    FloatArray(values.length()) { position ->
                        values.getDouble(position).toFloat()
                    }
                }?.takeIf(MatrixStatePolicy::isValid)

                val fitMode = if (savedEdited) {
                    item.optString("fitMode", defaultFitMode).ifEmpty { defaultFitMode }
                } else {
                    defaultFitMode
                }
                val fillMode = if (savedEdited) {
                    item.optString("fillMode", defaultFillMode).ifEmpty { defaultFillMode }
                } else {
                    defaultFillMode
                }

                playlistItems += PlaylistItem(
                    originalUri = Uri.fromFile(source),
                    isEdited = savedEdited,
                    editedFilePath = wallpaper.takeIf { savedEdited }?.absolutePath,
                    matrixState = matrix,
                    fitMode = fitMode,
                    fillMode = fillMode,
                    mediaId = if (item.has(PlaylistCollectionStore.KEY_MEDIA_ID)) {
                        item.getLong(PlaylistCollectionStore.KEY_MEDIA_ID)
                    } else {
                        null
                    }
                )
            }
            if (playlistItems.isEmpty()) loadLegacyPlaylist(playlistDir)
        } catch (error: IOException) {
            recoverFromMetadataFailure(playlistDir, error)
        } catch (error: JSONException) {
            recoverFromMetadataFailure(playlistDir, error)
        } catch (error: RuntimeException) {
            recoverFromMetadataFailure(playlistDir, error)
        }
    }

    private fun recoverFromMetadataFailure(playlistDir: File, error: Throwable) {
        Log.e(TAG, "Stored playlist metadata is invalid; loading images without edits", error)
        playlistItems.clear()
        loadLegacyPlaylist(playlistDir)
        Toast.makeText(
            this,
            "Some of your crops couldn't be restored.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun loadLegacyPlaylist(playlistDir: File) {
        PlaylistModeManager.imageFiles(playlistDir).forEach { file ->
            playlistItems += PlaylistItem(
                originalUri = Uri.fromFile(file),
                fitMode = defaultFitMode,
                fillMode = defaultFillMode
            )
        }
    }

    private fun applyFromDialog() {
        if (playlistItems.isEmpty()) {
            Toast.makeText(this, "Playlist is empty", Toast.LENGTH_SHORT).show()
        } else {
            applyPlaylist()
        }
    }

    private fun readStoredAtmosphereGlass(): Boolean {
        return try {
            getSharedPreferences(APP_PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(AtmosphereGlassPolicy.ENABLED_KEY, false)
        } catch (error: ClassCastException) {
            Log.w(TAG, "Stored Atmosphere glass option has the wrong type", error)
            false
        }
    }

    companion object {
        const val EXTRA_SAVED_PLAYLIST_ID = "SAVED_PLAYLIST_ID"
        private const val TAG = "PlaylistEditor"
        private const val APP_PREFERENCES = "app_prefs"
        private const val WALLPAPER_PREFERENCES = "wallpaper_prefs"
        private const val KEY_ROTATION_INTERVAL = "rotation_interval_minutes"
        private const val KEY_LAST_PLAYLIST_IMAGE = "last_playlist_image"
        private const val KEY_LAST_ROTATION = "last_rotation_timestamp"
        private const val STATE_ITEMS = "playlist_items"
        private const val STATE_ATMOSPHERE_GLASS = "playlist_atmosphere_glass"
        private const val STATE_SAVED_ID = "playlist_saved_id"
        private const val STATE_NAME = "playlist_name"
        private const val STATE_FOLDER_IDS = "playlist_folder_ids"
        private const val STATE_FOLDER_NAMES = "playlist_folder_names"
        private const val STATE_KNOWN_IDS = "playlist_known_media_ids"
        // Comfortably under the ~1MB shared Binder transaction limit even
        // accounting for per-item overhead (Uri, matrix state, strings).
        private const val MAX_ITEMS_TO_PERSIST_IN_BUNDLE = 300
        private const val ACTION_RELOAD_WALLPAPER =
            "com.app.nosatmosphereeffect.RELOAD_WALLPAPER"
    }
}
