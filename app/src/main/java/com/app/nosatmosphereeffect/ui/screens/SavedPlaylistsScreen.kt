package com.app.nosatmosphereeffect.ui.screens

import android.net.Uri
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.image.BitmapDecoder
import com.app.nosatmosphereeffect.storage.SavedPlaylistSummary
import com.app.nosatmosphereeffect.ui.components.AtmoChip
import com.app.nosatmosphereeffect.ui.components.AtmoShapeBadge
import com.app.nosatmosphereeffect.ui.components.AtmoTextButton
import com.app.nosatmosphereeffect.ui.components.AtmoTopBar
import com.app.nosatmosphereeffect.ui.theme.AtmoMotion
import com.app.nosatmosphereeffect.ui.theme.LocalAtmoExpressive
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
                title = stringResource(R.string.saved_title),
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
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    AtmoShapeBadge(icon = Icons.Rounded.Bookmarks, active = true, size = 96.dp)
                    Text(
                        stringResource(R.string.saved_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(playlists, key = { _, playlist -> playlist.id }) { index, playlist ->
                    SavedPlaylistRow(
                        playlist = playlist,
                        first = index == 0,
                        last = index == playlists.lastIndex,
                        modifier = Modifier.animateItem(),
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
            title = stringResource(R.string.saved_delete_title),
            message = if (playlist.isActive) {
                stringResource(R.string.saved_delete_active, playlist.name)
            } else {
                pluralStringResource(
                    R.plurals.saved_delete_message,
                    playlist.imageCount,
                    playlist.name,
                    playlist.imageCount
                )
            },
            confirmLabel = stringResource(R.string.common_delete),
            dismissLabel = stringResource(R.string.common_cancel),
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
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    val expressive = LocalAtmoExpressive.current
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Connected list: the group's ends are round, the seams between rows tight;
    // a pressed row rounds out on its own.
    val top by animateDpAsState(
        targetValue = if (first || pressed) 28.dp else 8.dp,
        animationSpec = AtmoMotion.fastSpatial(),
        label = "savedRowTop"
    )
    val bottom by animateDpAsState(
        targetValue = if (last || pressed) 28.dp else 8.dp,
        animationSpec = AtmoMotion.fastSpatial(),
        label = "savedRowBottom"
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed && expressive) 0.98f else 1f,
        animationSpec = AtmoMotion.fastSpatial(),
        label = "savedRowScale"
    )
    val shape = RoundedCornerShape(top, top, bottom, bottom)
    Surface(
        color = if (playlist.isActive) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        shape = shape,
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(shape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = {
                    if (expressive) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onClick()
                }
            )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CoverThumbnail(
                cover = playlist.cover,
                modifier = Modifier
                    .size(width = 56.dp, height = 88.dp)
                    .clip(RoundedCornerShape(16.dp))
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
                    add(
                        pluralStringResource(
                            R.plurals.common_image_count,
                            playlist.imageCount,
                            playlist.imageCount
                        )
                    )
                    if (!playlist.watch.isEmpty) {
                        val count = playlist.watch.folders.size
                        add(pluralStringResource(R.plurals.saved_watches_folders, count, count))
                    }
                }.joinToString(" · ")
                Text(
                    details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (playlist.isActive) AtmoChip(text = stringResource(R.string.saved_current))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.common_more_options))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_rename)) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete)) },
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
        title = { Text(stringResource(R.string.saved_name_title)) },
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
                text = stringResource(R.string.common_save),
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            )
        },
        dismissButton = {
            AtmoTextButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

@Composable
private fun CoverThumbnail(cover: Uri?, modifier: Modifier) {
    val context = LocalContext.current
    var bitmap by remember(cover) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(cover) {
        bitmap = cover?.let {
            withContext(Dispatchers.IO) {
                runCatching { BitmapDecoder.decodeUri(context, it, THUMBNAIL_SIZE).asImageBitmap() }
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
