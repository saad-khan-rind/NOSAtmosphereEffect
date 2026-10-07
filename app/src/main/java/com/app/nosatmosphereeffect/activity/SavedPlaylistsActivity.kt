package com.app.nosatmosphereeffect.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.storage.SavedPlaylistLibrary
import com.app.nosatmosphereeffect.storage.SavedPlaylistSummary
import com.app.nosatmosphereeffect.storage.WallpaperStorageCoordinator
import com.app.nosatmosphereeffect.ui.screens.SavedPlaylistsScreen
import com.app.nosatmosphereeffect.ui.theme.AtmoEngineTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Lists saved standard playlists; opening one loads it into the playlist editor. */
class SavedPlaylistsActivity : ComponentActivity() {

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var playlists by mutableStateOf<List<SavedPlaylistSummary>?>(null)
    private var effectId = "ORIGINAL"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        effectId = intent.getStringExtra(EXTRA_EFFECT_ID) ?: "ORIGINAL"

        setContent {
            AtmoEngineTheme {
                SavedPlaylistsScreen(
                    playlists = playlists,
                    onOpen = ::openPlaylist,
                    onRename = { playlist, name ->
                        runStorageTask("rename the playlist", R.string.saved_rename_failed) {
                            SavedPlaylistLibrary.rename(this, playlist.id, name)
                        }
                    },
                    onDelete = { playlist ->
                        runStorageTask("delete the playlist", R.string.saved_delete_failed) {
                            SavedPlaylistLibrary.delete(this, playlist.id)
                        }
                    },
                    onBack = { finish() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun reload() {
        ioExecutor.execute {
            val loaded = runCatching {
                WallpaperStorageCoordinator.runExclusive { SavedPlaylistLibrary.list(this) }
            }.getOrElse { error ->
                Log.e(TAG, "Could not list saved playlists", error)
                emptyList()
            }
            runOnUiThread { if (!isDestroyed) playlists = loaded }
        }
    }

    private fun runStorageTask(
        description: String,
        @StringRes failureMessage: Int,
        task: () -> Unit
    ) {
        ioExecutor.execute {
            try {
                WallpaperStorageCoordinator.runExclusive(task)
            } catch (error: Exception) {
                Log.e(TAG, "Could not $description", error)
                runOnUiThread {
                    Toast.makeText(this, failureMessage, Toast.LENGTH_SHORT).show()
                }
            }
        }
        reload()
    }

    private fun openPlaylist(playlist: SavedPlaylistSummary) {
        startActivity(
            Intent(this, PlaylistEditorActivity::class.java)
                .putExtra(PlaylistEditorActivity.EXTRA_SAVED_PLAYLIST_ID, playlist.id)
                .putExtra(EXTRA_EFFECT_ID, effectId)
        )
    }

    companion object {
        private const val TAG = "SavedPlaylists"
        private const val EXTRA_EFFECT_ID = "EFFECT_ID"

        fun intent(context: Context, effectId: String): Intent =
            Intent(context, SavedPlaylistsActivity::class.java)
                .putExtra(EXTRA_EFFECT_ID, effectId)
    }
}
