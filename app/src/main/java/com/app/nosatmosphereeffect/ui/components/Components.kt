package com.app.nosatmosphereeffect.ui.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.ui.theme.AtmoMotion
import com.app.nosatmosphereeffect.ui.theme.LocalAtmoEntranceStart
import com.app.nosatmosphereeffect.ui.theme.LocalAtmoExpressive
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun AtmoReveal(
    modifier: Modifier = Modifier,
    delayMillis: Int = 0,
    content: @Composable () -> Unit
) {
    val expressive = LocalAtmoExpressive.current
    val entranceStart = LocalAtmoEntranceStart.current
    // Only a screen's opening plays the entrance. Anything composed later, as a
    // list scrolls it in, simply appears: scrolling never waits on an animation.
    // Saveable, so a list item that scrolls out and back does not replay it.
    var visible by rememberSaveable {
        mutableStateOf(!expressive || SystemClock.uptimeMillis() - entranceStart > ENTRANCE_WINDOW_MS)
    }
    LaunchedEffect(Unit) {
        if (!visible) {
            delay(delayMillis.toLong())
            visible = true
        }
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(AtmoMotion.defaultEffects()) + slideInVertically(
            animationSpec = AtmoMotion.slowSpatial(),
            initialOffsetY = { it.coerceAtMost(48) }
        ) + scaleIn(AtmoMotion.slowSpatial(), initialScale = 0.96f)
    ) {
        content()
    }
}

private const val ENTRANCE_WINDOW_MS = 500L

/** True inside [AtmoSettingsGroup]: rows sit one tone up, with corners nested inside the card's. */
val LocalAtmoGrouped = staticCompositionLocalOf { false }

@Composable
private fun atmoRowColor() = if (LocalAtmoGrouped.current) {
    MaterialTheme.colorScheme.surfaceContainerHigh
} else {
    MaterialTheme.colorScheme.surfaceContainerLow
}

@Composable
private fun atmoRowCorner() = when {
    LocalAtmoGrouped.current -> 20.dp
    LocalAtmoExpressive.current -> 26.dp
    else -> 18.dp
}

/** A titled tonal card that gathers related settings, the M3 Expressive grouped list. */
@Composable
fun AtmoSettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .padding(start = 12.dp)
                .heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() }
            )
            action?.invoke()
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(if (LocalAtmoExpressive.current) 28.dp else 20.dp)
        ) {
            CompositionLocalProvider(LocalAtmoGrouped provides true) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(AtmoMotion.defaultSpatial())
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
fun AtmoPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: Painter? = null
) {
    val expressive = LocalAtmoExpressive.current
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && expressive) 0.985f else 1f,
        animationSpec = spring(stiffness = 420f, dampingRatio = 0.64f),
        label = "primaryButtonScale"
    )
    Button(
        onClick = {
            if (expressive) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            onClick()
        },
        shape = atmoPressShape(pressed),
        enabled = enabled,
        modifier = modifier
            .heightIn(min = 58.dp)
            .scale(scale),
        interactionSource = interaction,
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun AtmoTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    interactionSource: MutableInteractionSource? = null
) {
    val expressive = LocalAtmoExpressive.current
    val haptics = LocalHapticFeedback.current
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && expressive) 0.98f else 1f,
        animationSpec = spring(stiffness = 420f, dampingRatio = 0.64f),
        label = "tonalButtonScale"
    )
    FilledTonalButton(
        onClick = {
            if (expressive) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            onClick()
        },
        shape = atmoPressShape(pressed),
        modifier = modifier.heightIn(min = 58.dp).scale(scale),
        interactionSource = interaction,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(21.dp)
            )
            Spacer(Modifier.width(9.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
fun AtmoOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
    icon: Painter? = null
) {
    val expressive = LocalAtmoExpressive.current
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && expressive) 0.985f else 1f,
        animationSpec = spring(stiffness = 420f, dampingRatio = 0.66f),
        label = "outlinedButtonScale"
    )
    val contentColor = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    OutlinedButton(
        onClick = {
            if (expressive) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            onClick()
        },
        shape = atmoPressShape(pressed),
        enabled = enabled,
        modifier = modifier.heightIn(min = 58.dp).scale(scale),
        interactionSource = interaction,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        border = BorderStroke(
            1.dp,
            if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun AtmoTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.primary
) {
    val expressive = LocalAtmoExpressive.current
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && expressive) 0.94f else 1f,
        animationSpec = spring(stiffness = 450f, dampingRatio = 0.62f),
        label = "textButtonScale"
    )
    TextButton(
        onClick = {
            if (expressive) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            onClick()
        },
        shape = atmoPressShape(pressed, restingFallback = 14.dp),
        modifier = modifier.scale(scale),
        enabled = enabled,
        interactionSource = interaction,
        colors = ButtonDefaults.textButtonColors(contentColor = contentColor)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Label + live value + a clean (tick-free) slider. [step], when > 0, snaps the
 * emitted value and drives the value read-out formatting.
 */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    step: Float = 0f,
    valueText: (Float) -> String = { defaultFormat(it, step) }
) {
    val expressive = LocalAtmoExpressive.current
    val haptics = LocalHapticFeedback.current
    var lastHapticValue by remember(label) { mutableFloatStateOf(value) }
    val shape = RoundedCornerShape(atmoRowCorner())

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(atmoRowColor())
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            AnimatedContent(
                targetState = valueText(value),
                transitionSpec = {
                    androidx.compose.animation.fadeIn(tween(120)) togetherWith
                        androidx.compose.animation.fadeOut(tween(90))
                },
                label = "sliderValue"
            ) { text ->
                Text(
                    text,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = { raw ->
                val emitted = if (step > 0f) snap(raw, valueRange, step) else raw
                if (expressive && step > 0f && emitted != lastHapticValue) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    lastHapticValue = emitted
                }
                onValueChange(emitted)
            },
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        )
    }
}

private fun snap(raw: Float, range: ClosedFloatingPointRange<Float>, step: Float): Float {
    val steps = ((raw - range.start) / step).roundToInt()
    return (range.start + steps * step).coerceIn(range.start, range.endInclusive)
}

private fun defaultFormat(value: Float, step: Float): String =
    when {
        step >= 1f || step == 0f && value == value.roundToInt().toFloat() -> value.roundToInt().toString()
        step >= 0.1f -> String.format(Locale.ROOT, "%.1f", value)
        else -> String.format(Locale.ROOT, "%.2f", value)
    }

@Composable
fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    val expressive = LocalAtmoExpressive.current
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && expressive) 0.985f else 1f,
        animationSpec = spring(stiffness = 430f, dampingRatio = 0.66f),
        label = "settingSwitchScale"
    )
    val container by animateColorAsState(
        targetValue = if (checked) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        } else {
            atmoRowColor()
        },
        animationSpec = tween(220),
        label = "settingSwitchContainer"
    )
    val corner by animateDpAsState(
        targetValue = if (pressed && expressive) 14.dp else atmoRowCorner(),
        animationSpec = AtmoMotion.fastSpatial(),
        label = "settingSwitchCorner"
    )
    val shape = RoundedCornerShape(corner)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .scale(scale)
            .clip(shape)
            .background(container)
            .toggleable(
                value = checked,
                enabled = enabled,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Switch,
                onValueChange = { next ->
                    if (expressive) {
                        haptics.performHapticFeedback(
                            if (next) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff
                        )
                    }
                    onCheckedChange(next)
                }
            )
            .animateContentSize()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            thumbContent = if (checked && expressive) {
                {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize)
                    )
                }
            } else null,
            colors = SwitchDefaults.colors(
                checkedIconColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtmoDropdownField(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    helper: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val expressive = LocalAtmoExpressive.current
    val safeIndex = selectedIndex.coerceIn(0, (options.size - 1).coerceAtLeast(0))

    Column(modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, bottom = 7.dp)
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            TextField(
                value = options.getOrElse(safeIndex) { "" },
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = atmoFieldColors(),
                shape = RoundedCornerShape(atmoRowCorner()),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option, color = MaterialTheme.colorScheme.onSurface) },
                        onClick = {
                            onSelected(index)
                            expanded = false
                        }
                    )
                }
            }
        }
        if (helper != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                helper,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
fun AtmoNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    helper: String? = null,
    decimal: Boolean = false,
    infoIcon: Painter? = null,
    onInfoClick: (() -> Unit)? = null
) {
    val expressive = LocalAtmoExpressive.current
    Column(modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, bottom = 7.dp)
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number
            ),
            trailingIcon = if (infoIcon != null && onInfoClick != null) {
                {
                    AtmoAnimatedIconButton(
                        painter = infoIcon,
                        contentDescription = stringResource(R.string.common_more_info),
                        onClick = onInfoClick,
                        motion = AtmoIconMotion.PRESS,
                        iconTint = MaterialTheme.colorScheme.primary
                    )
                }
            } else null,
            colors = atmoFieldColors(),
            shape = RoundedCornerShape(atmoRowCorner()),
            modifier = Modifier.fillMaxWidth()
        )
        if (helper != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                helper,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
private fun atmoFieldColors() = TextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedContainerColor = atmoRowColor(),
    disabledContainerColor = atmoRowColor(),
    errorContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f),
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = MaterialTheme.colorScheme.error
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtmoTopBar(
    title: String,
    backIcon: Painter,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {}
) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        navigationIcon = {
            AtmoAnimatedIconButton(
                painter = backIcon,
                contentDescription = stringResource(R.string.common_back),
                onClick = onBack,
                motion = AtmoIconMotion.BACK
            )
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        ),
        modifier = modifier
    )
}

@Composable
fun AtmoSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val expressive = LocalAtmoExpressive.current
    val haptics = LocalHapticFeedback.current
    // Expressive: a connected button group, one container per segment. Otherwise one shared track.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (expressive) Modifier
                else Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(5.dp)
            ),
        horizontalArrangement = Arrangement.spacedBy(if (expressive) 3.dp else 5.dp)
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val width by animateFloatAsState(
                targetValue = if (pressed && expressive) 1.15f else 1f,
                animationSpec = AtmoMotion.fastSpatial(),
                label = "segmentWidth"
            )
            // Inner corners stay tight until the segment is chosen, then round out fully.
            val inner by animateFloatAsState(
                targetValue = if (selected || !expressive) 50f else if (pressed) 30f else 16f,
                animationSpec = AtmoMotion.fastSpatial(),
                label = "segmentInnerCorner"
            )
            val start = if (index == 0) CornerSize(50) else CornerSize(inner)
            val end = if (index == options.lastIndex) CornerSize(50) else CornerSize(inner)
            val shape = RoundedCornerShape(start, end, end, start)
            val containerColor by animateColorAsState(
                targetValue = when {
                    selected && expressive -> MaterialTheme.colorScheme.primary
                    selected -> MaterialTheme.colorScheme.primaryContainer
                    expressive -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> Color.Transparent
                },
                animationSpec = AtmoMotion.defaultEffects(),
                label = "segmentColor"
            )
            val contentColor by animateColorAsState(
                targetValue = when {
                    selected && expressive -> MaterialTheme.colorScheme.onPrimary
                    selected -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = AtmoMotion.defaultEffects(),
                label = "segmentContentColor"
            )
            Row(
                modifier = Modifier
                    .weight(width)
                    .heightIn(min = 48.dp)
                    .clip(shape)
                    .background(containerColor)
                    .selectable(
                        selected = selected,
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        role = Role.RadioButton,
                        onClick = {
                            if (index != selectedIndex && expressive) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                            onSelected(index)
                        }
                    )
                    .padding(horizontal = 10.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AnimatedVisibility(
                    visible = selected && expressive,
                    enter = expandHorizontally(AtmoMotion.fastSpatial()) +
                        scaleIn(AtmoMotion.fastSpatial()),
                    exit = shrinkHorizontally(AtmoMotion.fastSpatial()) +
                        scaleOut(AtmoMotion.fastSpatial())
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(16.dp)
                    )
                }
                Text(
                    text = option,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun AtmoChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.tertiaryContainer,
                RoundedCornerShape(50)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}
