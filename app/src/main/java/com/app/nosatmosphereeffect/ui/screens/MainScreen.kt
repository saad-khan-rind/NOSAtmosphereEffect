package com.app.nosatmosphereeffect.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.ui.components.AtmoChip
import com.app.nosatmosphereeffect.ui.components.AtmoAnimatedIconButton
import com.app.nosatmosphereeffect.ui.components.AtmoIconMotion
import com.app.nosatmosphereeffect.ui.components.AtmoOutlinedButton
import com.app.nosatmosphereeffect.ui.components.AtmoPrimaryButton
import com.app.nosatmosphereeffect.ui.components.AtmoReveal
import com.app.nosatmosphereeffect.ui.components.AtmoSegmentedControl
import com.app.nosatmosphereeffect.ui.components.AtmoTonalButton
import com.app.nosatmosphereeffect.ui.components.SettingSwitchRow
import com.app.nosatmosphereeffect.ui.components.WallpaperTransitionPreview
import com.app.nosatmosphereeffect.helper.AlwaysAppliedTarget
import com.app.nosatmosphereeffect.helper.WallpaperBehaviorSettings
import com.app.nosatmosphereeffect.ui.model.EffectCatalog
import com.app.nosatmosphereeffect.ui.model.RendererStatusUiModel
import com.app.nosatmosphereeffect.ui.preview.EffectPreviewSettingsMode
import com.app.nosatmosphereeffect.ui.theme.AppThemeMode
import com.app.nosatmosphereeffect.ui.theme.LocalAtmoExpressive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    wallpaperActive: Boolean,
    statusText: String,
    isSamsungDevice: Boolean,
    activeEffectId: String?,
    previewBitmap: ImageBitmap?,
    isPlaylistMode: Boolean,
    isThemePlaylistMode: Boolean,
    syncColors: Boolean,
    wallpaperBehavior: WallpaperBehaviorSettings,
    onSyncColorsChange: (Boolean) -> Unit,
    expressiveThemeEnabled: Boolean,
    onExpressiveThemeChange: (Boolean) -> Unit,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    pitchBlackEnabled: Boolean,
    onPitchBlackChange: (Boolean) -> Unit,
    onSetupWallpaper: () -> Unit,
    onChangeEffect: () -> Unit,
    onPickSingleImage: () -> Unit,
    onPickMultipleImages: () -> Unit,
    onPickThemePlaylists: () -> Unit,
    onEditExistingPlaylist: () -> Unit,
    onAdvancedSettings: () -> Unit,
    onTitleTap: () -> Unit,
    rendererStatus: RendererStatusUiModel? = null
) {
    var showImageSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showAdaptiveClockSheet by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable(onClick = onTitleTap)) {
                        Text("Atmo Engine", style = MaterialTheme.typography.titleLarge)
                        AnimatedContent(
                            targetState = wallpaperActive,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "wallpaperStatusLabel"
                        ) { active ->
                            Text(
                                if (active) {
                                    if (wallpaperBehavior.transitionsEnabled) {
                                        "Wallpaper active"
                                    } else {
                                        "Live · effect always applied"
                                    }
                                } else {
                                    "Wallpaper studio"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    if (isSamsungDevice) {
                        AtmoAnimatedIconButton(
                            imageVector = Icons.AutoMirrored.Rounded.Help,
                            contentDescription = "Samsung adaptive clock setup",
                            onClick = { showAdaptiveClockSheet = true },
                            motion = AtmoIconMotion.TILT,
                            filledTonal = true,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    AtmoAnimatedIconButton(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = "Appearance settings",
                        onClick = { showSettingsSheet = true },
                        motion = AtmoIconMotion.SPIN,
                        filledTonal = true,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 760.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 42.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp)
            ) {
                item {
                    AtmoReveal {
                        if (wallpaperActive) {
                            ActiveWallpaperPanel(
                                effectId = activeEffectId ?: "ORIGINAL",
                                previewBitmap = previewBitmap,
                                isPlaylistMode = isPlaylistMode,
                                isThemePlaylistMode = isThemePlaylistMode,
                                wallpaperBehavior = wallpaperBehavior,
                                onChangeEffect = onChangeEffect,
                                onChangeImage = { showImageSheet = true }
                            )
                        } else {
                            EmptyWallpaperPanel(
                                statusText = statusText,
                                onSetupWallpaper = onSetupWallpaper
                            )
                        }
                    }
                }

                if (wallpaperActive) {
                    item {
                        AtmoReveal(delayMillis = 70) {
                            UnframedSettingsSection(title = "Wallpaper behavior") {
                                SettingSwitchRow(
                                    title = "Sync system colors",
                                    subtitle = if (isPlaylistMode) {
                                        if (isThemePlaylistMode) {
                                            "Updates the system palette with each active theme playlist"
                                        } else {
                                            "Updates the system palette with every playlist image"
                                        }
                                    } else {
                                        "Updates the system palette from this wallpaper"
                                    },
                                    checked = syncColors,
                                    onCheckedChange = onSyncColorsChange
                                )
                                Spacer(Modifier.height(2.dp))
                                AtmoOutlinedButton(
                                    text = "Fine tune",
                                    onClick = onAdvancedSettings,
                                    accent = true,
                                    icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Rounded.Tune),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                if (rendererStatus != null) {
                    item(key = "rendererStatus") {
                        AtmoReveal(delayMillis = 140) {
                            RendererStatusFooter(status = rendererStatus)
                        }
                    }
                }
            }
        }
    }

    if (showImageSheet) {
        WallpaperModeSheet(
            title = "Wallpaper image",
            isPlaylistMode = isPlaylistMode,
            isThemePlaylistMode = isThemePlaylistMode,
            onDismiss = { showImageSheet = false },
            onPickSingle = { showImageSheet = false; onPickSingleImage() },
            onPickMultiple = { showImageSheet = false; onPickMultipleImages() },
            onPickThemePlaylists = { showImageSheet = false; onPickThemePlaylists() },
            onEditExisting = { showImageSheet = false; onEditExistingPlaylist() }
        )
    }

    if (showSettingsSheet) {
        AppearanceSettingsSheet(
            expressive = expressiveThemeEnabled,
            themeMode = themeMode,
            pitchBlack = pitchBlackEnabled,
            onExpressiveChange = onExpressiveThemeChange,
            onThemeModeChange = onThemeModeChange,
            onPitchBlackChange = onPitchBlackChange,
            onDismiss = { showSettingsSheet = false }
        )
    }

    if (showAdaptiveClockSheet) {
        SamsungAdaptiveClockSheet(
            wallpaperActive = wallpaperActive,
            activeEffectId = activeEffectId,
            isPlaylistMode = isPlaylistMode,
            onDismiss = { showAdaptiveClockSheet = false }
        )
    }
}

@Composable
fun RendererStatusFooter(
    status: RendererStatusUiModel,
    modifier: Modifier = Modifier
) {
    val vulkanActive = status.isVulkanActive
    val containerColor by animateColorAsState(
        targetValue = if (vulkanActive) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = "rendererStatusContainer"
    )

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(if (LocalAtmoExpressive.current) 28.dp else 18.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        AnimatedContent(
            targetState = status,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "rendererStatusContent"
        ) { currentStatus ->
            val active = currentStatus.isVulkanActive
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Memory,
                        contentDescription = null,
                        tint = if (active) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.renderer_status_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (active) {
                            stringResource(R.string.renderer_vulkan_active)
                        } else {
                            stringResource(R.string.renderer_opengl_active)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (active) {
                            stringResource(
                                R.string.renderer_vulkan_version,
                                currentStatus.vulkanVersion.orEmpty()
                            )
                        } else if (currentStatus.fallbackReason != null) {
                            stringResource(
                                R.string.renderer_vulkan_fallback_reason,
                                currentStatus.fallbackReason
                            )
                        } else {
                            stringResource(R.string.renderer_current_wallpaper_renderer)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                AtmoChip(
                    text = stringResource(
                        if (active) {
                            R.string.renderer_backend_vulkan
                        } else {
                            R.string.renderer_backend_opengl
                        }
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SamsungAdaptiveClockSheet(
    wallpaperActive: Boolean,
    activeEffectId: String?,
    isPlaylistMode: Boolean,
    onDismiss: () -> Unit
) {
    val effect = EffectCatalog.find(activeEffectId)
    val originalFirst = EffectCatalog.startsFromOriginalWallpaper(activeEffectId)
    val compatible = wallpaperActive && !isPlaylistMode && originalFirst
    val compatibleEffectNames = remember {
        EffectCatalog.items
            .filter { EffectCatalog.startsFromOriginalWallpaper(it.id) }
            .joinToString { it.title }
    }
    val statusTitle: String
    val statusMessage: String
    when {
        !wallpaperActive -> {
            statusTitle = "Choose a compatible setup"
            statusMessage = "Use one image and a transition that begins with the unchanged wallpaper."
        }
        isPlaylistMode -> {
            statusTitle = "Single image required"
            statusMessage = "Samsung adaptive clock setup is not available for playlists yet."
        }
        !originalFirst -> {
            statusTitle = "Change the transition direction"
            statusMessage = "${effect.title} does not begin with the unchanged wallpaper."
        }
        else -> {
            statusTitle = "Current setup is compatible"
            statusMessage = "${effect.title} begins with the unchanged wallpaper and uses one image."
        }
    }

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
                    Text("Adaptive clock", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Samsung lock screen setup",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                "Keep Samsung's adaptive lock-screen clock while Atmo Engine handles the home-screen transition.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = if (compatible) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (compatible) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
                        contentDescription = null,
                        tint = if (compatible) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                    Column(Modifier.weight(1f)) {
                        Text(statusTitle, style = MaterialTheme.typography.titleMedium)
                        Text(
                            statusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AdaptiveClockStep(
                    number = 1,
                    icon = Icons.Rounded.Home,
                    title = "Apply Atmo to the Home screen",
                    message = "In Android's live wallpaper preview, choose Home screen only."
                )
                AdaptiveClockStep(
                    number = 2,
                    icon = Icons.Rounded.Lock,
                    title = "Set the same Lock screen image",
                    message = "Use Samsung system settings or LockStar and select the exact same image."
                )
                AdaptiveClockStep(
                    number = 3,
                    icon = Icons.Rounded.Schedule,
                    title = "Enable Adaptive clock",
                    message = "Choose Adaptive clock in Samsung's lock screen editor."
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Compatible effects", style = MaterialTheme.typography.titleMedium)
                Text(
                    compatibleEffectNames,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Playlist modes are not supported for this setup yet.",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
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
private fun AdaptiveClockStep(
    number: Int,
    icon: ImageVector,
    title: String,
    message: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(contentAlignment = Alignment.Center) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.size(42.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(18.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        number.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 1.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ActiveWallpaperPanel(
    effectId: String,
    previewBitmap: ImageBitmap?,
    isPlaylistMode: Boolean,
    isThemePlaylistMode: Boolean,
    wallpaperBehavior: WallpaperBehaviorSettings,
    onChangeEffect: () -> Unit,
    onChangeImage: () -> Unit
) {
    val effect = EffectCatalog.find(effectId)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            ) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(4.dp)
                )
            }
            Spacer(Modifier.width(9.dp))
            Text(
                when {
                    isThemePlaylistMode -> "Active theme playlists"
                    isPlaylistMode -> "Active playlist"
                    else -> "Active wallpaper"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            AtmoChip(
                when {
                    isThemePlaylistMode -> "Light + dark"
                    isPlaylistMode -> "Playlist"
                    else -> "Single image"
                }
            )
        }

        WallpaperTransitionPreview(
            effectId = effectId,
            wallpaper = previewBitmap,
            settingsMode = EffectPreviewSettingsMode.SAVED_ACTIVE,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.92f)
        )

        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                effect.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (wallpaperBehavior.transitionsEnabled) {
                    effect.transition
                } else {
                    when (wallpaperBehavior.alwaysAppliedTarget) {
                        AlwaysAppliedTarget.HOME ->
                            "Always applied on Home · original on Lock"
                        AlwaysAppliedTarget.LOCK ->
                            "Always applied on Lock · original on Home"
                        AlwaysAppliedTarget.BOTH ->
                            "Always applied on Home and Lock"
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AtmoTonalButton(
                text = "Effect",
                onClick = onChangeEffect,
                icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Rounded.Palette),
                modifier = Modifier.weight(1f)
            )
            AtmoTonalButton(
                text = "Image",
                onClick = onChangeImage,
                icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Rounded.Wallpaper),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun EmptyWallpaperPanel(
    statusText: String,
    onSetupWallpaper: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
        WallpaperTransitionPreview(
            effectId = "ORIGINAL",
            wallpaper = null,
            settingsMode = EffectPreviewSettingsMode.EFFECT_DEFAULTS,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.92f)
        )
        Text(
            "Create a wallpaper",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AtmoPrimaryButton(
            text = "Choose effect and image",
            onClick = onSetupWallpaper,
            icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Rounded.Wallpaper),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun UnframedSettingsSection(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Column(
            modifier = Modifier.padding(horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSettingsSheet(
    expressive: Boolean,
    themeMode: AppThemeMode,
    pitchBlack: Boolean,
    onExpressiveChange: (Boolean) -> Unit,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onPitchBlackChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val darkThemeActive = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(
                        Icons.Rounded.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Text("Appearance", style = MaterialTheme.typography.headlineSmall)
            }

            SettingSwitchRow(
                title = "Material Expressive",
                checked = expressive,
                onCheckedChange = onExpressiveChange
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Theme", style = MaterialTheme.typography.titleMedium)
                AtmoSegmentedControl(
                    options = listOf("System", "Light", "Dark"),
                    selectedIndex = themeMode.ordinal,
                    onSelected = { onThemeModeChange(AppThemeMode.entries[it]) }
                )
            }

            AnimatedVisibility(visible = darkThemeActive) {
                SettingSwitchRow(
                    title = "Pitch-black background",
                    subtitle = "Use pure black instead of the system dark surface.",
                    checked = pitchBlack,
                    onCheckedChange = onPitchBlackChange
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperModeSheet(
    title: String = "Wallpaper mode",
    isPlaylistMode: Boolean = false,
    isThemePlaylistMode: Boolean = false,
    onDismiss: () -> Unit,
    onPickSingle: () -> Unit,
    onPickMultiple: () -> Unit,
    onPickThemePlaylists: () -> Unit,
    onEditExisting: (() -> Unit)? = null
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 18.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )
            ModeOption(
                title = "Single image",
                subtitle = "Use one wallpaper",
                icon = Icons.Rounded.Image,
                onClick = onPickSingle
            )
            ModeOption(
                title = if (isPlaylistMode) "New playlist" else "Playlist",
                subtitle = "Use several rotating images",
                icon = Icons.Rounded.Collections,
                onClick = onPickMultiple
            )
            ModeOption(
                title = if (isThemePlaylistMode) "New theme playlists" else "Theme playlists",
                subtitle = "Separate wallpapers for light and dark themes",
                icon = Icons.Rounded.Brightness6,
                onClick = onPickThemePlaylists
            )
            if (isPlaylistMode && onEditExisting != null) {
                ModeOption(
                    title = if (isThemePlaylistMode) {
                        "Edit current theme playlists"
                    } else {
                        "Edit current playlist"
                    },
                    subtitle = "Change its images and crops",
                    icon = Icons.Rounded.Edit,
                    onClick = onEditExisting
                )
            }
        }
    }
}

@Composable
private fun ModeOption(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    val expressive = LocalAtmoExpressive.current
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(stiffness = 700f, dampingRatio = 0.72f),
        label = "modeOptionScale"
    )
    val container by animateColorAsState(
        targetValue = if (pressed) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        label = "modeOptionColor"
    )

    Surface(
        color = container,
        shape = RoundedCornerShape(if (expressive) 28.dp else 20.dp),
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .scale(scale)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onClick = {
                        if (expressive) {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        }
                        onClick()
                    }
                )
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(10.dp).size(22.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
