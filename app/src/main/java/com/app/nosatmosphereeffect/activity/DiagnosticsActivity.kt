package com.app.nosatmosphereeffect.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.nosatmosphereeffect.helper.RendererDiagnosticsLog
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanSupport
import com.app.nosatmosphereeffect.ui.components.AtmoTextButton
import com.app.nosatmosphereeffect.ui.theme.AtmoEngineTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Shows the renderer diagnostics log.
 *
 * Exists because backend fallbacks are the one class of bug that is
 * essentially undebuggable remotely: the failure is device- and
 * driver-specific, it happens once at wallpaper startup, and the only record
 * of it is a logcat line the person reporting it usually cannot capture.
 * This puts the same information one tap from Advanced Settings, with a copy
 * button so it can be pasted into an issue.
 */
class DiagnosticsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AtmoEngineTheme {
                DiagnosticsScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var log by remember { mutableStateOf("") }
    var reloadToken by remember { mutableStateOf(0) }

    // Re-read on a timer as well as on demand: the interesting entries are
    // usually written by the wallpaper engine while this screen is already
    // open (set the wallpaper, come back, watch it appear).
    LaunchedEffect(reloadToken) {
        while (true) {
            log = withContext(Dispatchers.IO) { RendererDiagnosticsLog.read(context) }
            delay(1_500)
        }
    }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back"
                    )
                }
                Text(
                    "Renderer diagnostics",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AtmoTextButton(
                    text = "Copy",
                    onClick = {
                        copyToClipboard(context, log)
                        Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
                    }
                )
                AtmoTextButton(
                    text = "Share",
                    onClick = { shareLog(context, log) }
                )
                AtmoTextButton(
                    text = "Clear",
                    onClick = {
                        RendererDiagnosticsLog.clear(context)
                        reloadToken++
                    }
                )
                AtmoTextButton(
                    text = "Retry Vulkan",
                    onClick = {
                        VulkanSupport.clearRecordedFailures(context)
                        reloadToken++
                        Toast.makeText(
                            context,
                            "Vulkan re-enabled — re-apply the wallpaper",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }

            Text(
                "Set the wallpaper, then come back here — backend selection and " +
                    "any fallback is written as it happens. A \"blocked=true\" " +
                    "line means Vulkan failed on an earlier run and is being " +
                    "skipped; \"recorded=\" on that line is the original reason. " +
                    "Retry Vulkan clears it so the next run tries again.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    text = log.ifBlank {
                        "Nothing recorded yet.\n\n" +
                            "If the wallpaper is already applied, re-apply it (or " +
                            "toggle an Advanced Settings option) so the renderer " +
                            "restarts and writes a fresh entry."
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp)
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("AtmoEngine diagnostics", text))
}

private fun shareLog(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "AtmoEngine renderer diagnostics")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share diagnostics"))
}
