package com.app.nosatmosphereeffect.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.app.nosatmosphereeffect.R
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
    var visible by remember { mutableStateOf(!expressive) }
    LaunchedEffect(expressive) {
        if (expressive) {
            delay(delayMillis.toLong())
            visible = true
        } else {
            visible = true
        }
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(260)) + slideInVertically(
            animationSpec = spring(stiffness = 360f, dampingRatio = 0.78f),
            initialOffsetY = { it.coerceAtMost(32) }
        )
    ) {
        content()
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
        shape = if (expressive) RoundedCornerShape(50) else RoundedCornerShape(18.dp),
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
    icon: Painter? = null
) {
    val expressive = LocalAtmoExpressive.current
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
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
        shape = if (expressive) RoundedCornerShape(50) else RoundedCornerShape(18.dp),
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
        shape = if (expressive) RoundedCornerShape(50) else RoundedCornerShape(18.dp),
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
        shape = if (expressive) RoundedCornerShape(50) else RoundedCornerShape(14.dp),
        modifier = modifier.scale(scale),
        enabled = enabled,
        interactionSource = interaction,
        colors = ButtonDefaults.textButtonColors(contentColor = contentColor)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun AtmoCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val expressive = LocalAtmoExpressive.current
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (onClick != null && pressed && expressive) 0.985f else 1f,
        animationSpec = spring(stiffness = 700f, dampingRatio = 0.7f),
        label = "cardScale"
    )
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .scale(scale)
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onClick = {
                        if (expressive) {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        }
                        onClick()
                    }
                )
                else Modifier
            ),
        shape = RoundedCornerShape(if (expressive) 28.dp else 18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        border = if (selected) BorderStroke(2.dp, borderColor) else null
    ) {
        Column(Modifier.padding(contentPadding)) { content() }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
    )
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
    val shape = RoundedCornerShape(if (expressive) 26.dp else 18.dp)

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
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
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(220),
        label = "settingSwitchContainer"
    )
    val shape = RoundedCornerShape(if (expressive) 26.dp else 18.dp)

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
            colors = SwitchDefaults.colors(
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
                shape = RoundedCornerShape(if (expressive) 26.dp else 18.dp),
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
            shape = RoundedCornerShape(if (expressive) 26.dp else 18.dp),
            modifier = Modifier.fillMaxWidth()
        )
        if (helper != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                helper,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
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
    val outerShape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(outerShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val segmentScale by animateFloatAsState(
                targetValue = if (pressed && expressive) 0.94f else if (selected) 1.025f else 1f,
                animationSpec = spring(stiffness = 460f, dampingRatio = 0.62f),
                label = "segmentScale"
            )
            val containerColor by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                },
                animationSpec = tween(180),
                label = "segmentColor"
            )
            val contentColor by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(180),
                label = "segmentContentColor"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .scale(segmentScale)
                    .clip(RoundedCornerShape(50))
                    .background(containerColor)
                    .clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        onClick = {
                            if (index != selectedIndex && expressive) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                            onSelected(index)
                        }
                    )
                    .padding(horizontal = 10.dp, vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
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

@Composable
fun AtmoDialogRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val expressive = LocalAtmoExpressive.current
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && expressive) 0.98f else 1f,
        animationSpec = spring(stiffness = 430f, dampingRatio = 0.66f),
        label = "dialogRowScale"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .scale(scale)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                RoundedCornerShape(if (expressive) 26.dp else 18.dp)
            )
            .clip(RoundedCornerShape(if (expressive) 26.dp else 18.dp))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = {
                    if (expressive) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onClick()
                }
            )
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun rememberNoRippleInteraction(): MutableInteractionSource =
    remember { MutableInteractionSource() }
