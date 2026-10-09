package com.app.nosatmosphereeffect.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.ui.components.AtmoPrimaryButton
import com.app.nosatmosphereeffect.ui.components.AtmoReveal
import com.app.nosatmosphereeffect.ui.components.AtmoSegmentedControl
import com.app.nosatmosphereeffect.ui.components.AtmoShapeBadge
import com.app.nosatmosphereeffect.ui.components.AtmoTopBar
import com.app.nosatmosphereeffect.ui.components.WallpaperTransitionPreview
import com.app.nosatmosphereeffect.ui.components.effectFamilyIcon
import com.app.nosatmosphereeffect.ui.model.EffectCatalog
import com.app.nosatmosphereeffect.ui.model.EffectFamily
import com.app.nosatmosphereeffect.ui.model.EffectItem
import com.app.nosatmosphereeffect.ui.preview.EffectPreviewSettingsMode
import com.app.nosatmosphereeffect.ui.theme.AtmoMotion
import com.app.nosatmosphereeffect.ui.theme.LocalAtmoExpressive

@Composable
fun EffectSelectionScreen(
    title: String,
    effects: List<EffectItem>,
    previewBitmap: ImageBitmap?,
    onEffectClick: (EffectItem) -> Unit,
    onBack: () -> Unit
) {
    val families = remember(effects) { EffectCatalog.families(effects) }
    // The chosen effect id is the only state; family and direction are read back from it.
    var selectedId by rememberSaveable(effects) { mutableStateOf(effects.firstOrNull()?.id.orEmpty()) }
    var autoPlay by rememberSaveable { mutableStateOf(true) }
    var manualProgress by rememberSaveable { mutableFloatStateOf(0f) }
    val selected = effects.firstOrNull { it.id == selectedId } ?: effects.first()
    val selectedFamily = families.first { it.forward == selected || it.reverse == selected }
    val reversed = selected == selectedFamily.reverse
    val previewScale = remember { Animatable(1f) }

    LaunchedEffect(selectedId) {
        autoPlay = true
        previewScale.snapTo(0.97f)
        previewScale.animateTo(
            1f,
            spring(stiffness = 420f, dampingRatio = 0.55f)
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AtmoTopBar(
                title = title,
                backIcon = painterResource(R.drawable.ic_arrow_back),
                onBack = onBack
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp,
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
            ) {
                AnimatedContent(
                    targetState = selected,
                    transitionSpec = {
                        (fadeIn() + slideInVertically { it / 3 }) togetherWith
                            (fadeOut() + slideOutVertically { -it / 3 })
                    },
                    contentAlignment = Alignment.Center,
                    label = "continueButton"
                ) { effect ->
                    AtmoPrimaryButton(
                        text = stringResource(R.string.effects_continue_with, stringResource(effect.title)),
                        onClick = { onEffectClick(effect) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 720.dp)
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
            }
        }
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentAlignment = Alignment.TopCenter
        ) {
            // Preview and details stay put; only the effect list below them scrolls.
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .widthIn(max = 760.dp)
            ) {
                val previewMaxHeight = maxHeight * PREVIEW_HEIGHT_FRACTION
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 20.dp, end = 20.dp, top = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    AtmoReveal {
                        WallpaperTransitionPreview(
                            effectId = selected.id,
                            wallpaper = previewBitmap,
                            progress = if (autoPlay) null else manualProgress,
                            settingsMode = EffectPreviewSettingsMode.EFFECT_DEFAULTS,
                            // Full width, unless that would be too tall to leave the list room.
                            modifier = Modifier
                                .heightIn(max = previewMaxHeight)
                                .aspectRatio(0.92f)
                                .graphicsLayer {
                                    scaleX = previewScale.value
                                    scaleY = previewScale.value
                                }
                        )
                    }

                    AtmoReveal(delayMillis = 60) {
                        SelectedEffectDetails(
                            family = selectedFamily,
                            selected = selected,
                            reversed = reversed,
                            autoPlay = autoPlay,
                            manualProgress = manualProgress,
                            onAutoPlay = { autoPlay = true },
                            onPosition = {
                                autoPlay = false
                                manualProgress = it
                            },
                            onDirection = { selectedId = selectedFamily.pick(it).id }
                        )
                    }

                    AtmoReveal(delayMillis = 110) {
                        Text(
                            stringResource(R.string.effects_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { heading() }
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        families.chunked(2).forEachIndexed { rowIndex, row ->
                            AtmoReveal(delayMillis = 140 + rowIndex * 60) {
                                // Both cards of a row take the taller one's height, so a longer
                                // name never leaves one card standing taller than its neighbour.
                                Row(
                                    modifier = Modifier.height(IntrinsicSize.Min),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    row.forEach { family ->
                                        FamilyTile(
                                            family = family,
                                            reversed = reversed,
                                            selected = family == selectedFamily,
                                            onClick = { selectedId = family.pick(reversed).id },
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                        )
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Most of the screen's height the preview may take, so the effect list keeps room. */
private const val PREVIEW_HEIGHT_FRACTION = 0.3f

@Composable
private fun SelectedEffectDetails(
    family: EffectFamily,
    selected: EffectItem,
    reversed: Boolean,
    autoPlay: Boolean,
    manualProgress: Float,
    onAutoPlay: () -> Unit,
    onPosition: (Float) -> Unit,
    onDirection: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AtmoShapeBadge(
                icon = effectFamilyIcon(family.forward.id),
                active = true,
                size = 52.dp
            )
            Spacer(Modifier.width(14.dp))
            AnimatedContent(
                targetState = family.forward,
                transitionSpec = {
                    (fadeIn() + slideInVertically { it / 2 }) togetherWith
                        (fadeOut() + slideOutVertically { -it / 2 })
                },
                modifier = Modifier.weight(1f),
                label = "selectedFamilyTitle"
            ) { forward ->
                Text(
                    stringResource(forward.title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(12.dp))
            PreviewPositionControls(
                autoPlay = autoPlay,
                progress = manualProgress,
                onAutoPlay = onAutoPlay,
                onPosition = onPosition
            )
        }

        AnimatedContent(
            targetState = selected.description,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "effectDescription"
        ) { description ->
            Text(
                stringResource(description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(
            visible = family.reverse != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.effects_direction),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
                AtmoSegmentedControl(
                    options = listOfNotNull(family.forward, family.reverse).map { stringResource(it.transition) },
                    selectedIndex = if (reversed) 1 else 0,
                    onSelected = { onDirection(it == 1) }
                )
            }
        }
    }
}

@Composable
private fun FamilyTile(
    family: EffectFamily,
    reversed: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val expressive = LocalAtmoExpressive.current
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && expressive) 0.95f else 1f,
        animationSpec = AtmoMotion.fastSpatial(),
        label = "familyTileScale"
    )
    val corner by animateDpAsState(
        targetValue = when {
            !expressive -> 20.dp
            pressed -> 18.dp
            selected -> 36.dp
            else -> 28.dp
        },
        animationSpec = AtmoMotion.defaultSpatial(),
        label = "familyTileCorner"
    )
    val container by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = AtmoMotion.defaultEffects(),
        label = "familyTileColor"
    )
    val content by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = AtmoMotion.defaultEffects(),
        label = "familyTileContent"
    )
    val shown = family.pick(reversed)

    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(corner),
        modifier = modifier
            .heightIn(min = 132.dp)
            .scale(scale)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.RadioButton,
                onClick = {
                    if (expressive) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onClick()
                }
            )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AtmoShapeBadge(
                    icon = effectFamilyIcon(family.forward.id),
                    active = selected,
                    size = 44.dp,
                    containerColor = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(Modifier.weight(1f))
                if (family.reverse != null) {
                    Icon(
                        Icons.Rounded.SwapHoriz,
                        contentDescription = stringResource(R.string.effects_two_directions),
                        tint = content.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Text(
                stringResource(family.forward.title),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            AnimatedContent(
                targetState = shown.transition,
                transitionSpec = {
                    (fadeIn() + slideInVertically { it }) togetherWith (fadeOut() + slideOutVertically { -it })
                },
                label = "familyTileTransition"
            ) { transition ->
                Text(
                    stringResource(transition),
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.74f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PreviewPositionControls(
    autoPlay: Boolean,
    progress: Float,
    onAutoPlay: () -> Unit,
    onPosition: (Float) -> Unit
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(Modifier.padding(3.dp)) {
            PreviewIconButton(
                selected = !autoPlay && progress == 0f,
                icon = Icons.Rounded.Lock,
                description = stringResource(R.string.effects_show_lock_screen),
                onClick = { onPosition(0f) }
            )
            PreviewIconButton(
                selected = autoPlay,
                icon = Icons.Rounded.PlayArrow,
                description = stringResource(R.string.effects_play_transition),
                onClick = onAutoPlay
            )
            PreviewIconButton(
                selected = !autoPlay && progress == 1f,
                icon = Icons.Rounded.Home,
                description = stringResource(R.string.effects_show_home_screen),
                onClick = { onPosition(1f) }
            )
        }
    }
}

@Composable
private fun PreviewIconButton(
    selected: Boolean,
    icon: ImageVector,
    description: String,
    onClick: () -> Unit
) {
    val expressive = LocalAtmoExpressive.current
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.82f else 1f,
        animationSpec = AtmoMotion.fastSpatial(),
        label = "previewControlScale"
    )
    val container by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        label = "previewControlColor"
    )
    // The chosen control squares off slightly, the expressive cue for "selected".
    val corner by animateFloatAsState(
        targetValue = if (selected && expressive) 32f else 50f,
        animationSpec = AtmoMotion.fastSpatial(),
        label = "previewControlCorner"
    )

    Surface(
        shape = RoundedCornerShape(CornerSize(corner)),
        color = container,
        modifier = Modifier.scale(scale)
    ) {
        IconButton(
            onClick = {
                if (expressive) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onClick()
            },
            interactionSource = interaction,
            modifier = Modifier.size(38.dp)
        ) {
            Icon(
                icon,
                contentDescription = description,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(19.dp)
            )
        }
    }
}
