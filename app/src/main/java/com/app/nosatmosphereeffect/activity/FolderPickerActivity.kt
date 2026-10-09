package com.app.nosatmosphereeffect.activity

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.helper.FolderPlaylistSource
import com.app.nosatmosphereeffect.storage.WatchedFolder

/**
 * Opens the system folder picker for a folder a playlist should follow, and
 * keeps read access to it. Nothing of its own is drawn (translucent theme in
 * the manifest): the system picker is the whole screen.
 *
 * Started for a result (from the playlist editor) it returns the folder;
 * otherwise it opens the playlist editor with it.
 */
class FolderPickerActivity : ComponentActivity() {

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            val folder = uri?.let { FolderPlaylistSource.follow(this, it) }
            when {
                folder != null -> deliver(folder)
                uri != null -> Toast.makeText(this, R.string.folders_unreadable, Toast.LENGTH_LONG).show()
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        try {
            pickFolder.launch(FolderPlaylistSource.initialFolder())
        } catch (error: ActivityNotFoundException) {
            // Some TV and car builds ship without the system file picker.
            Toast.makeText(this, R.string.folders_unavailable, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun deliver(folder: WatchedFolder) {
        val ids = arrayListOf(folder.id)
        val names = arrayListOf(folder.name)
        if (callingActivity != null) {
            setResult(
                RESULT_OK,
                Intent()
                    .putStringArrayListExtra(EXTRA_FOLDER_IDS, ids)
                    .putStringArrayListExtra(EXTRA_FOLDER_NAMES, names)
            )
        } else {
            startActivity(
                Intent(this, PlaylistEditorActivity::class.java)
                    .putExtra("EFFECT_ID", intent.getStringExtra("EFFECT_ID") ?: "ORIGINAL")
                    .putStringArrayListExtra(EXTRA_FOLDER_IDS, ids)
                    .putStringArrayListExtra(EXTRA_FOLDER_NAMES, names)
            )
        }
    }

    companion object {
        const val EXTRA_FOLDER_IDS = "FOLDER_IDS"
        const val EXTRA_FOLDER_NAMES = "FOLDER_NAMES"

        fun intent(context: Context, effectId: String): Intent =
            Intent(context, FolderPickerActivity::class.java).putExtra("EFFECT_ID", effectId)

        internal fun foldersFrom(data: Intent?): List<WatchedFolder> {
            val ids = data?.getStringArrayListExtra(EXTRA_FOLDER_IDS).orEmpty()
            val names = data?.getStringArrayListExtra(EXTRA_FOLDER_NAMES).orEmpty()
            return ids.mapIndexed { index, id -> WatchedFolder(id, names.getOrElse(index) { id }) }
        }
    }
}
