package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.app.nosatmosphereeffect.storage.FileTransactions
import com.app.nosatmosphereeffect.storage.PlaylistImageRef
import com.app.nosatmosphereeffect.storage.UriFiles
import com.app.nosatmosphereeffect.storage.WallpaperStorageCoordinator
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/** A single rotation pipeline used by every effect service. */
object PlaylistRotationController {
    private const val TAG = "PlaylistRotation"
    private const val MAX_ATTEMPTS = 3
    private val rotationExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "wallpaper-playlist-rotation")
    }

    fun rotateAsync(
        context: Context,
        isThemeChange: Boolean,
        currentNightMode: Boolean,
        queueTransition: (Bitmap) -> Unit,
        requestRender: () -> Unit,
        notifyColorsChanged: () -> Unit
    ) {
        val appContext = context.applicationContext
        try {
            rotationExecutor.execute {
                try {
                    WallpaperStorageCoordinator.runExclusive {
                        rotate(
                            context = appContext,
                            isThemeChange = isThemeChange,
                            currentNightMode = currentNightMode,
                            queueTransition = queueTransition,
                            requestRender = requestRender,
                            notifyColorsChanged = notifyColorsChanged
                        )
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Playlist rotation failed", error)
                }
            }
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not schedule playlist rotation", error)
        }
    }

    private fun rotate(
        context: Context,
        isThemeChange: Boolean,
        currentNightMode: Boolean,
        queueTransition: (Bitmap) -> Unit,
        requestRender: () -> Unit,
        notifyColorsChanged: () -> Unit
    ) {
        val mode = PlaylistModeManager.getMode(context)
        if (mode == PlaylistModeManager.MODE_SINGLE) return
        if (isThemeChange && mode != PlaylistModeManager.MODE_THEME) return

        // Folder playlists follow their folders even while the app is closed.
        if (!isThemeChange && mode == PlaylistModeManager.MODE_STANDARD) {
            try {
                FolderPlaylistSource.syncActivePlaylist(context)
            } catch (error: Exception) {
                Log.w(TAG, "Could not sync the followed folders", error)
            }
        }
        val forceRotate = mode == PlaylistModeManager.MODE_STANDARD &&
            FolderPlaylistSource.isRotationForced(context)

        val prefs = context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        val isNightMode = if (isThemeChange) currentNightMode
            else PlaylistModeManager.currentNightMode(context)

        val playlistDir = PlaylistModeManager.activePlaylistDir(context, isNightMode)
        val playlistFiles = PlaylistModeManager.imageFiles(playlistDir)
        val nowMillis = System.currentTimeMillis()
        val decision = PlaylistRotationPolicy.decide(
            mode = mode,
            isThemeChange = isThemeChange,
            isNightMode = isNightMode,
            activeThemeState = prefs.getInt(PlaylistModeManager.KEY_ACTIVE_THEME, -1),
            playlistSize = playlistFiles.size,
            intervalMinutes = prefs.getLong("rotation_interval_minutes", 0L),
            lastRotationMillis = prefs.getLong("last_rotation_timestamp", 0L),
            nowMillis = nowMillis,
            forceRotate = forceRotate
        )
        if (!decision.shouldRotate) return

        val lastKey = PlaylistModeManager.lastImagePreferenceKey(mode, isNightMode)
        val lastUsedName = prefs.getString(lastKey, null)
        val eligibleNames = PlaylistRotationPolicy.eligibleNames(
            playlistFiles.map(File::getName),
            lastUsedName
        ).toSet()
        // A folder image may be unreadable right now (storage removed, access
        // lost, a cloud file offline): then try a few others before giving up.
        val selected = playlistFiles.filter { it.name in eligibleNames }
            .shuffled()
            .take(MAX_ATTEMPTS)
            .firstOrNull { candidate -> stageAndPromote(context, candidate, isNightMode, queueTransition) }
            ?: return

        val editor = prefs.edit()
            .putString(lastKey, selected.name)
            .putLong("last_rotation_timestamp", nowMillis)
        if (mode == PlaylistModeManager.MODE_THEME) {
            editor.putInt(PlaylistModeManager.KEY_ACTIVE_THEME, if (isNightMode) 1 else 0)
        }
        if (!editor.commit()) {
            Log.w(TAG, "Rotated wallpaper, but could not persist the playlist position")
        }
        if (forceRotate) FolderPlaylistSource.clearForcedRotation(context)
        requestRender()
        notifyColorsChanged()
    }

    private fun stageAndPromote(
        context: Context,
        selected: File,
        isNightMode: Boolean,
        queueTransition: (Bitmap) -> Unit
    ): Boolean {
        var decodedBitmap: Bitmap? = null
        return try {
            val nextFile = File(context.filesDir, WallpaperFitHelper.NEXT_WALLPAPER_FILE)
            val hasSource = if (PlaylistImageRef.isRef(selected)) {
                val ref = PlaylistImageRef.read(selected)
                    ?: throw IOException("${selected.name} is not a readable folder image pointer")
                PlaylistImageRef.materialize(
                    context,
                    ref,
                    nextFile,
                    File(context.filesDir, WallpaperFitHelper.NEXT_SOURCE_FILE)
                )
                true
            } else {
                UriFiles.copyAtomically(context, Uri.fromFile(selected), nextFile)
                WallpaperFitHelper.stageNextSource(
                    filesDir = context.filesDir,
                    playlistFileName = selected.name,
                    originalsDirectoryName = PlaylistModeManager.activeOriginalsDirName(
                        context,
                        isNightMode
                    )
                )
            }
            WallpaperFitHelper.setNextModes(
                context,
                WallpaperFitHelper.MODE_FILL,
                WallpaperFitHelper.FILL_BLACK
            )

            val bitmap = WallpaperFitHelper.decodeNextForDisplay(context) ?: return false
            decodedBitmap = bitmap
            val activeFile = File(context.filesDir, WallpaperFitHelper.ACTIVE_WALLPAPER_FILE)
            val nextSource = File(context.filesDir, WallpaperFitHelper.NEXT_SOURCE_FILE)
            val activeSource = File(context.filesDir, WallpaperFitHelper.ACTIVE_SOURCE_FILE)
            if (hasSource) {
                FileTransactions.replaceFiles(
                    listOf(
                        nextFile to activeFile,
                        nextSource to activeSource
                    )
                )
            } else {
                FileTransactions.deleteRecursively(activeSource)
                FileTransactions.moveReplacing(nextFile, activeFile)
            }
            WallpaperFitHelper.promoteNextMode(context)
            queueTransition(bitmap)
            decodedBitmap = null
            true
        } catch (error: IOException) {
            decodedBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
            Log.e(TAG, "Could not rotate to ${selected.name}", error)
            false
        } catch (error: SecurityException) {
            decodedBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
            Log.e(TAG, "Storage access was denied while rotating to ${selected.name}", error)
            false
        } catch (error: RuntimeException) {
            decodedBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
            Log.e(TAG, "Could not prepare ${selected.name} for rendering", error)
            false
        }
    }
}
