package com.app.nosatmosphereeffect.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.app.nosatmosphereeffect.ui.components.AtmoAnimatedIconButton
import com.app.nosatmosphereeffect.ui.components.AtmoIconMotion
import com.app.nosatmosphereeffect.ui.components.AtmoPrimaryButton
import com.app.nosatmosphereeffect.ui.components.AtmoTextButton
import com.app.nosatmosphereeffect.ui.components.WallpaperTransitionPreview
import com.app.nosatmosphereeffect.helper.AlwaysAppliedTarget
import com.app.nosatmosphereeffect.helper.WallpaperBehaviorPreferences
import com.app.nosatmosphereeffect.ui.model.EffectCatalog
import com.app.nosatmosphereeffect.ui.preview.EffectPreviewService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun SimpleConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title, color = MaterialTheme.colorScheme.onSurface) },
        text = { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = {
            AtmoTextButton(text = confirmLabel, onClick = onConfirm)
        },
        dismissButton = {
            AtmoTextButton(
                text = dismissLabel,
                onClick = onDismiss,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

@Composable
fun WallpaperPreviewDialog(
    bitmap: Bitmap,
    effectId: String,
    atmosphereGlassEnabledOverride: Boolean? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val behavior = remember(context) { WallpaperBehaviorPreferences.read(context) }
    var playing by remember { mutableStateOf(behavior.transitionsEnabled) }
    var manualProgress by remember { mutableFloatStateOf(0f) }
    val automaticProgress = remember(effectId, bitmap) { Animatable(0f) }
    val duration = remember(effectId) {
        EffectPreviewService.durationMillis(context, effectId)
    }

    LaunchedEffect(playing, effectId, bitmap, duration, behavior.transitionsEnabled) {
        if (!playing || !behavior.transitionsEnabled) return@LaunchedEffect
        automaticProgress.snapTo(manualProgress)
        while (isActive) {
            automaticProgress.animateTo(
                1f,
                tween(duration, easing = LinearEasing)
            )
            delay(620)
            automaticProgress.animateTo(
                0f,
                tween(duration, easing = LinearEasing)
            )
            delay(420)
        }
    }

    val shownProgress = if (!behavior.transitionsEnabled) {
        when (behavior.alwaysAppliedTarget) {
            AlwaysAppliedTarget.LOCK -> 0f
            AlwaysAppliedTarget.HOME, AlwaysAppliedTarget.BOTH -> 1f
        }
    } else if (playing) {
        automaticProgress.value
    } else {
        manualProgress
    }
    val image = remember(bitmap) { bitmap.asImageBitmap() }

    BackHandler(onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.fillMaxSize()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AnimatedIconAction(
                        icon = Icons.Rounded.Close,
                        description = "Close preview",
                        onClick = onDismiss
                    )
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Text(
                            if (behavior.transitionsEnabled) {
                                "Transition preview"
                            } else {
                                "Always-applied preview"
                            },
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            EffectCatalog.find(effectId).title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    WallpaperTransitionPreview(
                        effectId = effectId,
                        wallpaper = image,
                        progress = if (behavior.transitionsEnabled) shownProgress else null,
                        atmosphereGlassEnabledOverride =
                            atmosphereGlassEnabledOverride,
                        modifier = Modifier
                            .fillMaxSize()
                            .heightIn(max = 720.dp)
                    )
                }

                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    if (behavior.transitionsEnabled) {
                        Surface(
                            shape = RoundedCornerShape(28.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AnimatedIconAction(
                                    icon = if (playing) {
                                        Icons.Rounded.Pause
                                    } else {
                                        Icons.Rounded.PlayArrow
                                    },
                                    description = if (playing) {
                                        "Pause preview"
                                    } else {
                                        "Play preview"
                                    },
                                    onClick = {
                                        if (playing) {
                                            manualProgress = automaticProgress.value
                                        }
                                        playing = !playing
                                    }
                                )
                                Slider(
                                    value = shownProgress,
                                    onValueChange = {
                                        playing = false
                                        manualProgress = it
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    Text(
                        if (behavior.transitionsEnabled) {
                            "Next, pick Home screen and Lock screen on the system screen."
                        } else {
                            "Next, pick Home screen and Lock screen. Atmo runs on both, and " +
                                "Fine tune decides which one shows the effect."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                    AtmoPrimaryButton(
                        text = "Continue",
                        onClick = onConfirm,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
fun ProcessingOverlay(message: String) {
    val noRipple = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            // Swallow taps so the underlying UI can't be interacted with.
            .clickable(interactionSource = noRipple, indication = null) {},
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(18.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun AnimatedIconAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit
) {
    AtmoAnimatedIconButton(
        imageVector = icon,
        contentDescription = description,
        onClick = onClick,
        motion = AtmoIconMotion.PRESS,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}
