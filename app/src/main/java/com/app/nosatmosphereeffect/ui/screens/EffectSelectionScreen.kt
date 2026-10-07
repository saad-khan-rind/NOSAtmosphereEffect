package com.app.nosatmosphereeffect.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.ui.components.AtmoChip
import com.app.nosatmosphereeffect.ui.components.AtmoPrimaryButton
import com.app.nosatmosphereeffect.ui.components.AtmoReveal
import com.app.nosatmosphereeffect.ui.components.AtmoTopBar
import com.app.nosatmosphereeffect.ui.components.WallpaperTransitionPreview
import com.app.nosatmosphereeffect.ui.model.EffectItem
import com.app.nosatmosphereeffect.ui.preview.EffectPreviewSettingsMode
import com.app.nosatmosphereeffect.ui.theme.LocalAtmoExpressive

@Composable
fun EffectSelectionScreen(
    title: String,
    effects: List<EffectItem>,
    previewBitmap: ImageBitmap?,
    onEffectClick: (EffectItem) -> Unit,
    onBack: () -> Unit
) {
    var selectedId by rememberSaveable(effects) { mutableStateOf(effects.firstOrNull()?.id.orEmpty()) }
    var autoPlay by rememberSaveable { mutableStateOf(true) }
    var manualProgress by rememberSaveable { mutableFloatStateOf(0f) }
    val selected = effects.firstOrNull { it.id == selectedId } ?: effects.first()
    val previewScale = remember { Animatable(1f) }

    LaunchedEffect(selectedId) {
        autoPlay = true
        previewScale.snapTo(0.985f)
        previewScale.animateTo(
            1f,
            spring(stiffness = 520f, dampingRatio = 0.7f)
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
                AtmoPrimaryButton(
                    text = stringResource(R.string.effects_continue_with, stringResource(selected.title)),
                    onClick = { onEffectClick(selected) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 720.dp)
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
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
                contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    AtmoReveal {
                        WallpaperTransitionPreview(
                            effectId = selected.id,
                            wallpaper = previewBitmap,
                            progress = if (autoPlay) null else manualProgress,
                            settingsMode = EffectPreviewSettingsMode.EFFECT_DEFAULTS,
                            modifier = Modifier
                                .padding(horizontal = 20.dp)
                                .fillMaxWidth()
                                .aspectRatio(0.92f)
                                .scale(previewScale.value)
                        )
                    }
                }

                item {
                    AtmoReveal(delayMillis = 60) {
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AnimatedContent(
                                targetState = selected,
                                transitionSpec = { fadeIn() togetherWith fadeOut() },
                                modifier = Modifier.weight(1f),
                                label = "selectedEffectDetails"
                            ) { effect ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        stringResource(effect.title),
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    AtmoChip(stringResource(effect.transition))
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            PreviewPositionControls(
                                autoPlay = autoPlay,
                                progress = manualProgress,
                                onAutoPlay = { autoPlay = true },
                                onPosition = {
                                    autoPlay = false
                                    manualProgress = it
                                }
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
                        }
                    }
                }

                item {
                    AtmoReveal(delayMillis = 110) {
                        Text(
                            stringResource(R.string.effects_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                        )
                    }
                }

                item {
                    AtmoReveal(delayMillis = 140) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(effects, key = { it.id }) { effect ->
                                EffectChoice(
                                    effect = effect,
                                    selected = effect.id == selectedId,
                                    onClick = { selectedId = effect.id }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EffectChoice(
    effect: EffectItem,
    selected: Boolean,
    onClick: () -> Unit
) {
    val expressive = LocalAtmoExpressive.current
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else if (selected) 1.025f else 1f,
        animationSpec = spring(stiffness = 650f, dampingRatio = 0.68f),
        label = "effectChoiceScale"
    )
    val container by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "effectChoiceColor"
    )
    val content by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        label = "effectChoiceContent"
    )
    val corner by animateDpAsState(
        targetValue = if (pressed && expressive) 16.dp else 28.dp,
        animationSpec = spring(stiffness = 430f, dampingRatio = 0.65f),
        label = "effectChoiceCorner"
    )

    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(corner),
        modifier = Modifier
            .width(178.dp)
            .heightIn(min = 86.dp)
            .scale(scale)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = {
                    if (expressive) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onClick()
                }
            )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                stringResource(effect.title),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(effect.transition),
                style = MaterialTheme.typography.bodySmall,
                color = content.copy(alpha = 0.74f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
        animationSpec = spring(stiffness = 700f, dampingRatio = 0.6f),
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

    Surface(shape = CircleShape, color = container, modifier = Modifier.scale(scale)) {
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
