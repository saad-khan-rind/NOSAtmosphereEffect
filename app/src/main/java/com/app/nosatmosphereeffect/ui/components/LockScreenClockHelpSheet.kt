package com.app.nosatmosphereeffect.ui.components

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.app.nosatmosphereeffect.helper.DeviceIdentity
import com.app.nosatmosphereeffect.helper.LockScreenClockGuide

/**
 * Explains that the wallpaper clock is meant to replace the phone's own lock
 * screen clock, and how to hide that clock on the brand this is running on.
 *
 * The device is read from Build, which needs no permission. Brand steps come
 * from [LockScreenClockGuide]; brands it doesn't cover get a web search for
 * their exact model instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockScreenClockHelpSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val device = remember { DeviceIdentity.current() }
    val guide = remember(device) { LockScreenClockGuide.forDevice(device) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(46.dp)
                ) {
                    Icon(
                        Icons.Rounded.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(11.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Wallpaper clock", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Works best on its own",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                "This feature only looks its best when you can hide your phone's " +
                    "own lock screen clock. Otherwise you'll see two clocks on " +
                    "the lock screen.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.PhoneAndroid,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Your device", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${device.displayName} · ${device.androidLabel}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    guide.brandLabel?.let { "On $it" } ?: "On your device",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    guide.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (guide.steps.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    guide.steps.forEachIndexed { index, step ->
                        HelpStep(number = index + 1, text = step)
                    }
                }
            } else {
                Text(
                    "Search the internet to find out whether the lock screen clock " +
                        "can be hidden on your device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AtmoOutlinedButton(
                text = "Search the web for your device",
                onClick = { openWebSearch(context, guide.searchQuery) },
                icon = rememberVectorPainter(Icons.Rounded.Search),
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    "These methods might not work on your model or software " +
                        "version — phone makers change their menus with updates. " +
                        "Make sure to do your own research for your device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AtmoPrimaryButton(
                text = "Got it",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun HelpStep(number: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.size(28.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    number.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(top = 4.dp)
        )
    }
}

/**
 * The device's own search app when it has one, a browser otherwise. Neither
 * needs a permission or a package-visibility query: failure is caught.
 */
private fun openWebSearch(context: Context, query: String) {
    val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
    try {
        context.startActivity(search)
        return
    } catch (_: ActivityNotFoundException) {
        // Fall through to a plain browser link.
    } catch (_: SecurityException) {
    }
    val browser = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
    )
    try {
        context.startActivity(browser)
    } catch (_: ActivityNotFoundException) {
    }
}
