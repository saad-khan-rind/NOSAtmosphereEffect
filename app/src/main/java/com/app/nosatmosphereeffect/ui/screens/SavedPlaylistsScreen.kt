package com.app.nosatmosphereeffect.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.image.BitmapDecoder
import com.app.nosatmosphereeffect.storage.SavedPlaylistSummary
import com.app.nosatmosphereeffect.ui.components.AtmoChip
import com.app.nosatmosphereeffect.ui.components.AtmoTextButton
import com.app.nosatmosphereeffect.ui.components.AtmoTopBar
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SavedPlaylistsScreen(
    playlists: List<SavedPlaylistSummary>?,
    onOpen: (SavedPlaylistSummary) -> Unit,
    onRename: (SavedPlaylistSummary, String) -> Unit,
    onDelete: (SavedPlaylistSummary) -> Unit,
    onBack: () -> Unit
) {
    var renaming by remember { mutableStateOf<SavedPlaylistSummary?>(null) }
    var deleting by remember { mutableStateOf<SavedPlaylistSummary?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AtmoTopBar(
                title = "Saved playlists",
                backIcon = painterResource(R.drawable.ic_arrow_back),
                onBack = onBack
            )
        }
    ) { inner ->
        when {
            playlists == null -> Unit
            playlists.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Every playlist you apply is saved here, so you can go back " +
                        "to it after using a single image for a while.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(playlists, key = SavedPlaylistSummary::id) { playlist ->
                    SavedPlaylistRow(
                        playlist = playlist,
                        onClick = { onOpen(playlist) },
                        onRename = { renaming = playlist },
                        onDelete = { deleting = playlist }
                    )
                }
            }
        }
    }

    renaming?.let { playlist ->
        RenamePlaylistDialog(
            initialName = playlist.name,
            onConfirm = { name ->
                renaming = null
                onRename(playlist, name)
            },
            onDismiss = { renaming = null }
        )
    }

    deleting?.let { playlist ->
        SimpleConfirmDialog(
            title = "Delete playlist?",
            message = if (playlist.isActive) {
                "\"${playlist.name}\" is your wallpaper right now. It'll keep playing, " +
                    "but once you switch to something else it won't be saved here."
            } else {
                "This removes \"${playlist.name}\" and its ${playlist.imageCount} images."
            },
            confirmLabel = "Delete",
            dismissLabel = "Cancel",
            onConfirm = {
                deleting = null
                onDelete(playlist)
            },
            onDismiss = { deleting = null }
        )
    }
}

@Composable
private fun SavedPlaylistRow(
    playlist: SavedPlaylistSummary,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            FileThumbnail(
                file = playlist.cover,
                modifier = Modifier
                    .size(width = 56.dp, height = 88.dp)
                    .clip(RoundedCornerShape(14.dp))
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val details = buildList {
                    add("${playlist.imageCount} images")
                    if (!playlist.watch.isEmpty) {
                        val count = playlist.watch.folders.size
                        add(if (count == 1) "watches 1 folder" else "watches $count folders")
                    }
                }.joinToString(" · ")
                Text(
                    details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (playlist.isActive) AtmoChip(text = "Current wallpaper")
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
internal fun RenamePlaylistDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Playlist name") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_NAME_LENGTH) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            AtmoTextButton(
                text = "Save",
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            )
        },
        dismissButton = {
            AtmoTextButton(
                text = "Cancel",
                onClick = onDismiss,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

@Composable
private fun FileThumbnail(file: File?, modifier: Modifier) {
    var bitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file) {
        bitmap = file?.let {
            withContext(Dispatchers.IO) {
                runCatching { BitmapDecoder.decodePreview(it, THUMBNAIL_SIZE).asImageBitmap() }
                    .getOrNull()
            }
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

private const val THUMBNAIL_SIZE = 256
private const val MAX_NAME_LENGTH = 60
