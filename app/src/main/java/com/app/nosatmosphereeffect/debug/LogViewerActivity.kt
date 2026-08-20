package com.app.nosatmosphereeffect.debug

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import com.app.nosatmosphereeffect.ui.screens.LogViewerScreen
import com.app.nosatmosphereeffect.ui.theme.AtmoEngineTheme
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Shows the in-memory log buffer captured by [LogcatTail]. Testing/
 * diagnostic screen only, reached from the bug-report icon on the main
 * screen -- not meant for the general release UI.
 */
class LogViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AtmoEngineTheme {
                val entries by AppLog.entriesFlow.collectAsState()
                LogViewerScreen(
                    entries = entries,
                    onBack = { finish() },
                    onClear = { AppLog.clear() },
                    onCopy = { copyToClipboard() },
                    onShare = { shareAsFile() }
                )
            }
        }
    }

    private fun copyToClipboard() {
        val clipboard =
            getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("AtmoEngine logs", AppLog.asPlainText())
        )
        Toast.makeText(this, "Logs copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareAsFile() {
        try {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(java.util.Date())
            val dir = File(cacheDir, "logs").apply { mkdirs() }
            val file = File(dir, "atmoengine-log-$stamp.txt")
            file.writeText(AppLog.asPlainText())

            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share logs"))
        } catch (error: IOException) {
            Toast.makeText(this, "Couldn't prepare log file", Toast.LENGTH_SHORT).show()
        }
    }
}
