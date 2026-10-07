package com.app.nosatmosphereeffect.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.ui.components.AtmoChip
import com.app.nosatmosphereeffect.ui.components.AtmoPrimaryButton
import com.app.nosatmosphereeffect.ui.components.AtmoTopBar
import com.app.nosatmosphereeffect.ui.model.PaletteDiagnosticLevel
import com.app.nosatmosphereeffect.ui.model.PaletteDiagnosticMessage
import com.app.nosatmosphereeffect.ui.model.PaletteDiagnostics
import java.util.Locale

@Composable
fun PaletteDiagnosticsScreen(
    deviceName: String,
    diagnostics: PaletteDiagnostics,
    loading: Boolean,
    applying: Boolean,
    syncColorsEnabled: Boolean,
    onForceApply: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AtmoTopBar(
                title = stringResource(R.string.palette_title),
                backIcon = painterResource(R.drawable.ic_arrow_back),
                onBack = onBack
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = CircleShape,
                        modifier = Modifier.size(48.dp)
                    ) {
                        androidx.compose.foundation.layout.Box(
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Palette,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            stringResource(R.string.palette_pipeline, deviceName),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            stringResource(
                                if (loading) R.string.palette_reading else R.string.palette_comparison
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AtmoChip(
                        stringResource(
                            if (syncColorsEnabled) R.string.palette_sync_on else R.string.palette_sync_off
                        )
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PaletteSwatchRow(stringResource(R.string.palette_extracted), diagnostics.extractedColors, loading)
                    PaletteSwatchRow(stringResource(R.string.palette_api_colors), diagnostics.wallpaperApiColors, loading)
                    PaletteSwatchRow(stringResource(R.string.palette_system), diagnostics.systemColors, loading)
                }
            }

            if (diagnostics.systemColorSource != null || diagnostics.systemSeedColor != null) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 15.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(stringResource(R.string.palette_theme_state), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(
                                    R.string.palette_source,
                                    diagnostics.systemColorSource
                                        ?: stringResource(R.string.palette_unknown)
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                stringResource(
                                    R.string.palette_seed,
                                    diagnostics.systemSeedColor?.let(::formatPaletteColor)
                                        ?: stringResource(R.string.palette_unavailable)
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            item {
                AnimatedVisibility(
                    visible = diagnostics.messages.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        diagnostics.messages.forEach { message ->
                            PaletteDiagnosticMessageRow(message)
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AtmoPrimaryButton(
                        text = stringResource(
                            if (applying) R.string.palette_applying else R.string.palette_force_apply
                        ),
                        onClick = onForceApply,
                        enabled = syncColorsEnabled && !applying,
                        icon = androidx.compose.ui.graphics.vector.rememberVectorPainter(
                            Icons.Rounded.Palette
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (!syncColorsEnabled) {
                        Text(
                            stringResource(R.string.palette_sync_needed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun PaletteDiagnosticMessageRow(message: PaletteDiagnosticMessage) {
    val containerColor: Color
    val contentColor: Color
    val icon: ImageVector
    when (message.level) {
        PaletteDiagnosticLevel.SUCCESS -> {
            containerColor = MaterialTheme.colorScheme.primaryContainer
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            icon = Icons.Rounded.CheckCircle
        }
        PaletteDiagnosticLevel.INFO -> {
            containerColor = MaterialTheme.colorScheme.secondaryContainer
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            icon = Icons.Rounded.Info
        }
        PaletteDiagnosticLevel.WARNING -> {
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            icon = Icons.Rounded.WarningAmber
        }
        PaletteDiagnosticLevel.ERROR -> {
            containerColor = MaterialTheme.colorScheme.errorContainer
            contentColor = MaterialTheme.colorScheme.onErrorContainer
            icon = Icons.Rounded.ErrorOutline
        }
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(message.title, style = MaterialTheme.typography.labelLarge)
                message.detail?.let { detail ->
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.84f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PaletteSwatchRow(label: String, colors: List<Int>, loading: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            repeat(3) { index ->
                val colorValue = colors.getOrNull(index)
                val targetColor = colorValue?.let(::Color)
                    ?: MaterialTheme.colorScheme.surfaceContainerHighest
                val swatchColor by animateColorAsState(
                    targetValue = targetColor,
                    animationSpec = spring(stiffness = 280f, dampingRatio = 0.74f),
                    label = "diagnosticSwatch${label}${index}"
                )
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        color = swatchColor,
                        shape = RoundedCornerShape(15.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                    ) {}
                    Spacer(Modifier.height(5.dp))
                    Text(
                        colorValue?.let(::formatPaletteColor) ?: if (loading) "…" else stringResource(R.string.palette_not_available),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }
            }
        }
    }
}

private fun formatPaletteColor(color: Int): String =
    String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)
