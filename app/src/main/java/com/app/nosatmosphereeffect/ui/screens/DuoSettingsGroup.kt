package com.app.nosatmosphereeffect.ui.screens

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.renderer.duo.DuoHomeImage
import com.app.nosatmosphereeffect.renderer.duo.DuoStyle
import com.app.nosatmosphereeffect.ui.components.AtmoDropdownField
import com.app.nosatmosphereeffect.ui.components.AtmoOutlinedButton
import com.app.nosatmosphereeffect.ui.components.AtmoSettingsGroup
import com.app.nosatmosphereeffect.ui.components.AtmoTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The wallpaper service listens for these (WallpaperEventController), as Fine tuning's Apply sends them.
private const val ACTION_RELOAD_WALLPAPER = "com.app.nosatmosphereeffect.RELOAD_WALLPAPER"
private const val ACTION_UPDATE_CONFIG = "com.app.nosatmosphereeffect.UPDATE_CONFIG"

private val PICK_IMAGES = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

/**
 * Fine tuning's Duo group: how the lock screen's photo turns into the home
 * screen's, and the home screen's photo and playlist (DuoHomeImage). Unlike
 * the settings around it, these take effect as they are made: a photo is
 * picked there and then, not on Apply.
 */
@Composable
internal fun DuoSettingsGroup() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    var style by remember { mutableStateOf(DuoStyle.read(preferences)) }
    var hasHome by remember { mutableStateOf(DuoHomeImage.exists(context)) }
    var playlistCount by remember { mutableIntStateOf(DuoHomeImage.playlistFiles(context).size) }
    var failed by remember { mutableStateOf(false) }

    val pickHome = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) { DuoHomeImage.pick(context, uri) }
            failed = !saved
            hasHome = DuoHomeImage.exists(context)
            if (saved) tellWallpaper(context, ACTION_RELOAD_WALLPAPER)
        }
    }
    val addToPlaylist = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val added = withContext(Dispatchers.IO) { DuoHomeImage.addToPlaylist(context, uris) }
            failed = added < uris.size
            playlistCount = DuoHomeImage.playlistFiles(context).size
        }
    }

    AtmoSettingsGroup(title = stringResource(R.string.duo_group)) {
        AtmoDropdownField(
            label = stringResource(R.string.duo_style),
            options = listOf(
                stringResource(R.string.duo_style_crossfade),
                stringResource(R.string.duo_style_wipe),
                stringResource(R.string.duo_style_circle),
                stringResource(R.string.duo_style_dissolve)
            ),
            selectedIndex = style.ordinal,
            onSelected = { index ->
                style = DuoStyle.fromOrdinal(index)
                preferences.edit().putInt(DuoStyle.PREFERENCE_KEY, style.ordinal).apply()
                tellWallpaper(context, ACTION_UPDATE_CONFIG)
            }
        )
        Spacer(Modifier.height(16.dp))
        Heading(stringResource(R.string.duo_home_photo))
        Note(stringResource(if (hasHome) R.string.duo_home_hint else R.string.duo_home_none))
        Spacer(Modifier.height(8.dp))
        AtmoOutlinedButton(
            text = stringResource(if (hasHome) R.string.duo_home_change else R.string.duo_home_pick),
            onClick = { pickHome.launch(PICK_IMAGES) },
            modifier = Modifier.fillMaxWidth()
        )
        AnimatedVisibility(visible = failed) {
            Text(
                stringResource(R.string.duo_home_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Heading(stringResource(R.string.duo_home_playlist))
        Note(
            if (playlistCount > 0) {
                pluralStringResource(R.plurals.duo_home_count, playlistCount, playlistCount)
            } else {
                stringResource(R.string.duo_home_playlist_hint)
            }
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AtmoOutlinedButton(
                text = stringResource(R.string.duo_home_add),
                onClick = { addToPlaylist.launch(PICK_IMAGES) },
                modifier = Modifier.weight(1f)
            )
            if (playlistCount > 0) {
                AtmoTextButton(
                    text = stringResource(R.string.duo_home_clear),
                    onClick = {
                        DuoHomeImage.clearPlaylist(context)
                        playlistCount = 0
                    }
                )
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp)
    )
}

/** Tells the running wallpaper to read its settings, or its photos, again. */
private fun tellWallpaper(context: Context, action: String) {
    context.sendBroadcast(Intent(action).setPackage(context.packageName))
}
