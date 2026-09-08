package com.app.nosatmosphereeffect.activity

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.app.nosatmosphereeffect.helper.AtmosphereGlassPolicy
import com.app.nosatmosphereeffect.helper.GlassEffectPreferences
import com.app.nosatmosphereeffect.helper.PlaylistModeManager
import com.app.nosatmosphereeffect.helper.MatrixStatePolicy
import com.app.nosatmosphereeffect.helper.SystemColorSyncPreferences
import com.app.nosatmosphereeffect.helper.WallpaperFitHelper
import com.app.nosatmosphereeffect.image.BitmapDecoder
import com.app.nosatmosphereeffect.image.BitmapStore
import com.app.nosatmosphereeffect.storage.FileTransactions
import com.app.nosatmosphereeffect.storage.SharedPreferencesTransactions
import com.app.nosatmosphereeffect.storage.WallpaperStorageCoordinator
import com.app.nosatmosphereeffect.ui.screens.CropController
import com.app.nosatmosphereeffect.ui.screens.CropScreen
import com.app.nosatmosphereeffect.ui.screens.ProcessingOverlay
import com.app.nosatmosphereeffect.ui.screens.WallpaperPreviewDialog
import com.app.nosatmosphereeffect.ui.model.EffectCatalog
import com.app.nosatmosphereeffect.ui.theme.AtmoEngineTheme
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.UUID

abstract class BaseCropActivity : ComponentActivity() {
    protected abstract val fallbackEffectId: String

    private val controller = CropController()
    private val applyState: CropApplyState by viewModels()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var effectId = WallpaperEffectServices.DEFAULT_EFFECT_ID
    private var sourceBitmap: Bitmap? = null
    private var pendingBitmap: Bitmap? = null
    private var imageLoadStarted = false
    private var showApplyConfirm by mutableStateOf(false)
    private lateinit var currentFit: String
    private lateinit var currentFill: String
    private var restoredMatrix: FloatArray? = null
    private var isApplying
        get() = applyState.isApplying
        set(value) {
            applyState.isApplying = value
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()

        effectId = WallpaperEffectServices.normalize(
            intent.getStringExtra(EXTRA_EFFECT_ID),
            fallbackEffectId
        )
        if (!applyState.atmosphereGlassInitialized) {
            val requested = if (savedInstanceState?.containsKey(STATE_ATMOSPHERE_GLASS) == true) {
                savedInstanceState.getBoolean(STATE_ATMOSPHERE_GLASS)
            } else {
                readStoredAtmosphereGlass()
            }
            applyState.atmosphereGlassEnabled =
                AtmosphereGlassPolicy.resolveEnabled(effectId, requested)
            applyState.atmosphereGlassInitialized = true
        }
        restoredMatrix = MatrixStatePolicy.copyIfValid(
            savedInstanceState?.getFloatArray(STATE_MATRIX)
        )
        currentFit = normalizeFitMode(
            savedInstanceState?.getString(STATE_FIT_MODE)
                ?: intent.getStringExtra(EXTRA_FIT_MODE)
        )
        currentFill = normalizeFillMode(
            savedInstanceState?.getString(STATE_FILL_MODE)
                ?: intent.getStringExtra(EXTRA_FILL_MODE)
        )
        val uri = intent.data
        if (uri == null) {
            Toast.makeText(this, "No image was provided.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            AtmoEngineTheme {
                BackHandler(enabled = isApplying) {}
                LaunchedEffect(applyState.applyCompleted, applyState.applyError) {
                    when {
                        applyState.applyCompleted -> showSuccessfulApply()
                        applyState.applyError != null -> {
                            val message = applyState.applyError
                            applyState.applyError = null
                            Toast.makeText(
                                this@BaseCropActivity,
                                message,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                CropScreen(
                    controller = controller,
                    buttonLabel = "Preview transition",
                    initialFit = currentFit,
                    initialFill = currentFill,
                    showAtmosphereGlassOption =
                        EffectCatalog.supportsAtmosphereGlass(effectId),
                    atmosphereGlassEnabled = applyState.atmosphereGlassEnabled,
                    onAtmosphereGlassEnabledChange = { enabled ->
                        applyState.atmosphereGlassEnabled =
                            AtmosphereGlassPolicy.resolveEnabled(effectId, enabled)
                    },
                    onViewCreated = { loadImage(uri, restoredMatrix) },
                    onFitChanged = { fit, fill ->
                        currentFit = normalizeFitMode(fit)
                        currentFill = normalizeFillMode(fill)
                        controller.setFitMode(currentFit, currentFill)
                    },
                    onBack = { finish() },
                    onConfirm = {
                        if (!isApplying) controller.getCroppedBitmap()?.let { cropped ->
                            pendingBitmap?.takeIf { it !== cropped }?.recycle()
                            pendingBitmap = cropped
                            showApplyConfirm = true
                        }
                    }
                )

                if (showApplyConfirm) {
                    pendingBitmap?.let { preview ->
                        WallpaperPreviewDialog(
                            bitmap = preview,
                            effectId = effectId,
                            atmosphereGlassEnabledOverride =
                                applyState.atmosphereGlassEnabled,
                            onConfirm = {
                                showApplyConfirm = false
                                pendingBitmap = null
                                applyWallpaper(preview)
                            },
                            onDismiss = {
                                showApplyConfirm = false
                                pendingBitmap = null
                                preview.recycle()
                            }
                        )
                    }
                }
                if (isApplying) {
                    ProcessingOverlay(message = "Saving wallpaper…")
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putFloatArray(
            STATE_MATRIX,
            MatrixStatePolicy.copyIfValid(controller.getCurrentMatrixValues())
        )
        outState.putString(STATE_FIT_MODE, currentFit)
        outState.putString(STATE_FILL_MODE, currentFill)
        outState.putBoolean(
            STATE_ATMOSPHERE_GLASS,
            applyState.atmosphereGlassEnabled
        )
    }

    override fun onDestroy() {
        if (isChangingConfigurations) {
            ioExecutor.shutdown()
        } else {
            ioExecutor.shutdownNow()
        }
        pendingBitmap?.recycle()
        pendingBitmap = null
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
                        sourceBitmap = bitmap
                        controller.setInitialImage(bitmap, savedMatrix)
                    }
                }
            } catch (error: IOException) {
                reportLoadFailure(
                    "Unable to decode selected image",
                    error,
                    "This image could not be opened. Try a different file."
                )
            } catch (error: SecurityException) {
                reportLoadFailure(
                    "Image permission was revoked",
                    error,
                    "Atmo Engine no longer has permission to read this image."
                )
            } catch (error: RuntimeException) {
                reportLoadFailure(
                    "Unexpected image decoding failure",
                    error,
                    "The image could not be prepared."
                )
            }
        }
    }

    private fun applyWallpaper(bitmap: Bitmap) {
        if (isApplying) {
            bitmap.recycle()
            return
        }
        isApplying = true
        applyState.applyCompleted = false
        applyState.applyError = null
        val atmosphereGlassEnabled = AtmosphereGlassPolicy.resolveEnabled(
            effectId,
            applyState.atmosphereGlassEnabled
        )
        Toast.makeText(this, "Applying…", Toast.LENGTH_SHORT).show()

        ioExecutor.execute {
            try {
                WallpaperStorageCoordinator.runExclusive {
                    val source = sourceBitmap
                        ?: throw IOException("The original wallpaper image is unavailable")
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
                        fileTransactions += installWallpaperFiles(bitmap, source)

                        preferencesTouched = true
                        SystemColorSyncPreferences.isEnabled(this)
                        val appPreferencesEditor =
                            appPreferences.edit()
                                .clear()
                                .putBoolean(
                                    AtmosphereGlassPolicy.ENABLED_KEY,
                                    atmosphereGlassEnabled
                                )
                        GlassEffectPreferences.write(
                            appPreferencesEditor,
                            glassSettings
                        )
                            .commit()
                            .also { success ->
                                if (!success) throw IOException("Could not reset effect preferences")
                            }
                        getSharedPreferences(WALLPAPER_PREFERENCES, Context.MODE_PRIVATE)
                            .edit()
                            .clear()
                            .putString(
                                PlaylistModeManager.KEY_MODE,
                                PlaylistModeManager.MODE_SINGLE
                            )
                            .commit()
                            .also { success ->
                                if (!success) {
                                    throw IOException(
                                        "Could not initialize wallpaper preferences"
                                    )
                                }
                            }

                        WallpaperFitHelper.setActiveModes(
                            this, currentFit, currentFill
                        )
                        WallpaperFitHelper.setNextModes(
                            this, currentFit, currentFill
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
                    cleanupObsoletePlaylistFiles()
                }

                runOnUiThread {
                    isApplying = false
                    applyState.applyCompleted = true
                }
            } catch (error: IOException) {
                reportApplyFailure(
                    "Unable to persist wallpaper files",
                    error,
                    "The wallpaper could not be saved. Check available storage and try again."
                )
            } catch (error: SecurityException) {
                reportApplyFailure(
                    "Wallpaper storage access was rejected",
                    error,
                    "The wallpaper could not be saved because storage access was rejected."
                )
            } catch (error: RuntimeException) {
                reportApplyFailure(
                    "Unexpected wallpaper apply failure",
                    error,
                    "The wallpaper could not be applied."
                )
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun installWallpaperFiles(
        bitmap: Bitmap,
        source: Bitmap
    ): FileTransactions.ReplacementTransaction {
        val token = UUID.randomUUID().toString()
        val stagedWallpaper = File(filesDir, ".wallpaper-$token.staged")
        val stagedSource = File(filesDir, ".wallpaper-source-$token.staged")
        var failure: Exception? = null
        try {
            BitmapStore.writeJpegAtomically(bitmap, stagedWallpaper, quality = 100)
            // The source is what FIT, ROTATE and scroll modes actually
            // render from, so it is not an archive copy — it is on the
            // display path and a quality-95 re-encode was visible in flat
            // gradients (sky, skin) once the effect blurred and re-sharpened
            // around it.
            BitmapStore.writeJpegAtomically(source, stagedSource, quality = 100)
            return FileTransactions.beginReplacingFiles(
                listOf(
                    stagedWallpaper to File(
                        filesDir,
                        WallpaperFitHelper.ACTIVE_WALLPAPER_FILE
                    ),
                    stagedSource to File(
                        filesDir,
                        WallpaperFitHelper.ACTIVE_SOURCE_FILE
                    )
                )
            )
        } catch (error: Exception) {
            failure = error
            throw error
        } finally {
            listOf(stagedWallpaper, stagedSource).forEach { staged ->
                try {
                    FileTransactions.deleteRecursively(staged)
                } catch (cleanupError: Exception) {
                    if (failure == null) {
                        Log.w(TAG, "Could not remove ${staged.absolutePath}", cleanupError)
                    } else {
                        failure.addSuppressed(cleanupError)
                    }
                }
            }
        }
    }

    private fun cleanupObsoletePlaylistFiles() {
        val cleanupTasks = listOf<() -> Unit>(
            {
                Files.deleteIfExists(
                    File(filesDir, WallpaperFitHelper.NEXT_WALLPAPER_FILE).toPath()
                )
            },
            { WallpaperFitHelper.deleteNextSource(filesDir) },
            { PlaylistModeManager.clearStandardCollections(this) },
            { PlaylistModeManager.clearThemeCollections(this) }
        )
        cleanupTasks.forEach { cleanup ->
            try {
                cleanup()
            } catch (error: Exception) {
                Log.w(TAG, "Wallpaper applied, but obsolete playlist data could not be removed", error)
            }
        }
    }

    private fun reportLoadFailure(logMessage: String, error: Throwable, userMessage: String) {
        Log.e(TAG, logMessage, error)
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            Toast.makeText(this, userMessage, Toast.LENGTH_LONG).show()
            if (!isFinishing) finish()
        }
    }

    private fun reportApplyFailure(logMessage: String, error: Throwable, userMessage: String) {
        Log.e(TAG, logMessage, error)
        runOnUiThread {
            isApplying = false
            applyState.applyError = userMessage
        }
    }

    private fun showSuccessfulApply() {
        if (!applyState.applyCompleted) return
        applyState.applyCompleted = false
        sendBroadcast(Intent(ACTION_RELOAD_WALLPAPER).setPackage(packageName))
        Toast.makeText(
            this,
            "Setup complete. Select Home screen and Lock screen next.",
            Toast.LENGTH_LONG
        ).show()
        if (WallpaperEffectServices.launchPicker(this, effectId)) {
            finish()
        } else {
            Toast.makeText(
                this,
                "No live wallpaper picker is available on this device.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun normalizeFitMode(value: String?): String {
        return value?.takeIf {
            it == WallpaperFitHelper.MODE_FILL ||
                it == WallpaperFitHelper.MODE_FIT ||
                it == WallpaperFitHelper.MODE_STRETCH ||
                it == WallpaperFitHelper.MODE_ROTATE_FIT
        } ?: WallpaperFitHelper.MODE_FILL
    }

    private fun normalizeFillMode(value: String?): String {
        return value?.takeIf {
            it == WallpaperFitHelper.FILL_BLACK ||
                it == WallpaperFitHelper.FILL_REPEAT ||
                it == WallpaperFitHelper.FILL_MIRROR
        } ?: WallpaperFitHelper.FILL_BLACK
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

    private companion object {
        const val TAG = "BaseCropActivity"
        const val EXTRA_EFFECT_ID = "EFFECT_ID"
        const val EXTRA_FIT_MODE = "FIT_MODE"
        const val EXTRA_FILL_MODE = "FILL_MODE"
        const val APP_PREFERENCES = "app_prefs"
        const val WALLPAPER_PREFERENCES = "wallpaper_prefs"
        const val STATE_MATRIX = "crop_matrix"
        const val STATE_FIT_MODE = "crop_fit_mode"
        const val STATE_FILL_MODE = "crop_fill_mode"
        const val STATE_ATMOSPHERE_GLASS = "crop_atmosphere_glass"
        const val ACTION_RELOAD_WALLPAPER = "com.app.nosatmosphereeffect.RELOAD_WALLPAPER"
    }
}
