package com.app.nosatmosphereeffect.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.debug.AppLogEntry
import com.app.nosatmosphereeffect.debug.AppLogLevel
import com.app.nosatmosphereeffect.ui.components.AtmoAnimatedIconButton
import com.app.nosatmosphereeffect.ui.components.AtmoIconMotion
import com.app.nosatmosphereeffect.ui.components.AtmoTopBar

@Composable
internal fun LogViewerScreen(
    entries: List<AppLogEntry>,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    val listState: LazyListState = remember { LazyListState() }

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }

    Scaffold(
        topBar = {
            AtmoTopBar(
                title = "Logs (${entries.size})",
                backIcon = painterResource(R.drawable.ic_arrow_back),
                onBack = onBack,
                actions = {
                    AtmoAnimatedIconButton(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = "Copy logs",
                        onClick = onCopy,
                        motion = AtmoIconMotion.TILT
                    )
                    AtmoAnimatedIconButton(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = "Share logs",
                        onClick = onShare,
                        motion = AtmoIconMotion.TILT
                    )
                    AtmoAnimatedIconButton(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = "Clear logs",
                        onClick = onClear,
                        motion = AtmoIconMotion.TILT
                    )
                }
            )
        }
    ) { innerPadding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No logs captured yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        SelectionContainer {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                items(entries, key = { it.sequence }) { entry ->
                    LogLine(entry)
                }
            }
        }
    }
}

@Composable
private fun LogLine(entry: AppLogEntry) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "${entry.formattedTime}  ${entry.level.letter}/${entry.tag}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = levelColor(entry.level)
        )
        Text(
            entry.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun levelColor(level: AppLogLevel): Color = when (level) {
    AppLogLevel.ERROR, AppLogLevel.ASSERT -> MaterialTheme.colorScheme.error
    AppLogLevel.WARN -> MaterialTheme.colorScheme.tertiary
    AppLogLevel.INFO -> MaterialTheme.colorScheme.primary
    AppLogLevel.DEBUG, AppLogLevel.VERBOSE -> MaterialTheme.colorScheme.onSurfaceVariant
}