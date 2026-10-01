package com.app.nosatmosphereeffect.ui.screens

import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.helper.MediaFolder
import com.app.nosatmosphereeffect.ui.components.AtmoOutlinedButton
import com.app.nosatmosphereeffect.ui.components.AtmoPrimaryButton
import com.app.nosatmosphereeffect.ui.components.AtmoTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class FolderAccessState {
    /** Waiting for the permission dialog or the folder query. */
    CHECKING,
    GRANTED,
    /** Android 14+ "Select photos": new images would never be visible. */
    PARTIAL,
    DENIED
}

@Composable
internal fun FolderPickerScreen(
    access: FolderAccessState,
    canAskAgain: Boolean,
    folders: List<MediaFolder>?,
    selectedIds: Set<String>,
    onToggle: (MediaFolder) -> Unit,
    onRequestAccess: () -> Unit,
    onOpenSettings: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AtmoTopBar(
                title = "Choose folders",
                backIcon = painterResource(R.drawable.ic_arrow_back),
                onBack = onBack
            )
        },
        bottomBar = {
            if (access == FolderAccessState.GRANTED && !folders.isNullOrEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                ) {
                    AtmoPrimaryButton(
                        text = when (selectedIds.size) {
                            0 -> "Select a folder"
                            1 -> "Use 1 folder"
                            else -> "Use ${selectedIds.size} folders"
                        },
                        onClick = onContinue,
                        enabled = selectedIds.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                    )
                }
            }
        }
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            when (access) {
                FolderAccessState.CHECKING -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                FolderAccessState.PARTIAL, FolderAccessState.DENIED -> AccessRationale(
                    partial = access == FolderAccessState.PARTIAL,
                    canAskAgain = canAskAgain,
                    onRequestAccess = onRequestAccess,
                    onOpenSettings = onOpenSettings
                )
                FolderAccessState.GRANTED -> when {
                    folders == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    folders.isEmpty() -> Text(
                        "Couldn't find any folders with photos on your phone.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp)
                    )
                    else -> LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Text(
                                "New photos in these folders get added to your " +
                                    "playlist the next time you open Atmo Engine.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        items(folders, key = MediaFolder::id) { folder ->
                            FolderRow(
                                folder = folder,
                                selected = folder.id in selectedIds,
                                onToggle = { onToggle(folder) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessRationale(
    partial: Boolean,
    canAskAgain: Boolean,
    onRequestAccess: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            if (partial) "Allow access to all photos" else "Photo access needed",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            if (partial) {
                "With \"Select photos\", Atmo Engine only sees the photos you picked, " +
                    "so it can't spot new ones added to a folder later. Pick " +
                    "\"Allow all\" to follow folders."
            } else {
                "Folder playlists look through your photo folders for new photos " +
                    "you add later. Your photos never leave your phone."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (canAskAgain) {
            AtmoPrimaryButton(text = "Allow access", onClick = onRequestAccess)
        }
        AtmoOutlinedButton(text = "Open app settings", onClick = onOpenSettings)
    }
}

@Composable
private fun FolderRow(
    folder: MediaFolder,
    selected: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onToggle)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            MediaThumbnail(
                folder = folder,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
            )
            Column(Modifier.weight(1f)) {
                Text(folder.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (folder.imageCount == 1) "1 image" else "${folder.imageCount} images",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
private fun MediaThumbnail(folder: MediaFolder, modifier: Modifier) {
    val context = LocalContext.current
    var bitmap by remember(folder.cover) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(folder.cover) {
        val cover = folder.cover ?: return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver
                    .loadThumbnail(cover, Size(THUMBNAIL_SIZE, THUMBNAIL_SIZE), null)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private const val THUMBNAIL_SIZE = 192
