package com.app.nosatmosphereeffect.ui.screens

import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.activity.ClockAdjustActivity
import com.app.nosatmosphereeffect.helper.AlwaysAppliedTarget
import com.app.nosatmosphereeffect.helper.AtmosphereClockPolicy
import com.app.nosatmosphereeffect.helper.ClockScreen
import com.app.nosatmosphereeffect.helper.ClockStyle
import com.app.nosatmosphereeffect.helper.FilmGrainPolicy
import com.app.nosatmosphereeffect.helper.GlassEffectPolicy
import com.app.nosatmosphereeffect.helper.GlassTransitionStyle
import com.app.nosatmosphereeffect.helper.SubjectModelDelivery
import com.app.nosatmosphereeffect.helper.SubjectModelPhase
import com.app.nosatmosphereeffect.helper.SubjectModelState
import com.app.nosatmosphereeffect.renderer.backend.GraphicsBackendPreference
import com.app.nosatmosphereeffect.ui.components.AtmoAnimatedIconButton
import com.app.nosatmosphereeffect.ui.components.AtmoDropdownField
import com.app.nosatmosphereeffect.ui.components.AtmoIconMotion
import com.app.nosatmosphereeffect.ui.components.AtmoNumberField
import com.app.nosatmosphereeffect.ui.components.AtmoOutlinedButton
import com.app.nosatmosphereeffect.ui.components.AtmoPrimaryButton
import com.app.nosatmosphereeffect.ui.components.AtmoReveal
import com.app.nosatmosphereeffect.ui.components.AtmoSegmentedControl
import com.app.nosatmosphereeffect.ui.components.AtmoTextButton
import com.app.nosatmosphereeffect.ui.components.AtmoTopBar
import com.app.nosatmosphereeffect.ui.components.LabeledSlider
import com.app.nosatmosphereeffect.ui.components.LockScreenClockHelpSheet
import com.app.nosatmosphereeffect.ui.components.SettingSwitchRow
import kotlin.math.roundToInt

data class AdvancedConfig(
    val activeEffectTitle: String,
    val recommendedDurationMs: Long,
    val showHalftone: Boolean,
    val showColorFill: Boolean,
    val showNeon: Boolean,
    val showFrosted: Boolean,
    val showGlass: Boolean,
    val showAtmosphereGlassToggle: Boolean,
    val atmosphereGlassEnabled: Boolean,
    val showClockToggle: Boolean,
    val clockEnabled: Boolean,
    val clockDepthEnabled: Boolean,
    /**
     * Whether the lock/home/both choice is offered. True only for effects that
     * leave the photo's geometry intact at both ends of their transition —
     * Colour Fill, Sketch and Halftone. Everywhere else the side is forced by
     * the effect and the UI says which, rather than showing a control that
     * cannot take effect.
     */
    val clockOffersScreenChoice: Boolean,
    val clockScreenId: String,
    val glassReverse: Boolean,
    val showNoiseSwitch: Boolean,
    val showBlob: Boolean,
    val isPlaylistMode: Boolean,
    val rotationOptions: List<String>,
    val initialRotationIndex: Int,
    val poll: String,
    val delay: String,
    val duration: String,
    val transitionsEnabled: Boolean,
    val alwaysAppliedTarget: AlwaysAppliedTarget,
    val dimness: Float,
    val blurStrength: Float,
    val enableNoise: Boolean,
    val noiseScale: String,
    val noiseStrength: String,
    val dotSize: Float,
    val grayscale: Boolean,
    val originX: Float,
    val originY: Float,
    val saturation: Float,
    val contrast: Float,
    val neonSensitivity: Float,
    val neonLineWidth: Float,
    val glassLineCount: Int,
    val glassLineThickness: Float,
    val glassTransitionStyle: GlassTransitionStyle,
    val glassBackgroundOnly: Boolean,
    val halftoneBackgroundOnly: Boolean,
    val subjectSegmentationEnabled: Boolean,
    val scrollEnabled: Boolean,
    val rendererPreference: GraphicsBackendPreference
)

data class AdvancedResult(
    val poll: String,
    val delay: String,
    val duration: String,
    val transitionsEnabled: Boolean,
    val alwaysAppliedTarget: AlwaysAppliedTarget,
    val dimness: Float,
    val blurStrength: Float,
    val enableNoise: Boolean,
    val noiseScale: String,
    val noiseStrength: String,
    val dotSize: Float,
    val grayscale: Boolean,
    val originX: Float,
    val originY: Float,
    val saturation: Float,
    val contrast: Float,
    val neonSensitivity: Float,
    val neonLineWidth: Float,
    val atmosphereGlassEnabled: Boolean,
    val clockEnabled: Boolean,
    val clockDepthEnabled: Boolean,
    val clockScreenId: String,
    val glassLineCount: Int,
    val glassLineThickness: Float,
    val glassTransitionStyle: GlassTransitionStyle,
    val glassBackgroundOnly: Boolean,
    val halftoneBackgroundOnly: Boolean,
    val subjectSegmentationEnabled: Boolean,
    val rotationIndex: Int,
    val scrollEnabled: Boolean,
    val rendererPreference: GraphicsBackendPreference
)

private enum class FineTuneTab(@StringRes val label: Int) {
    Effect(R.string.fine_tab_effect),
    Timing(R.string.fine_tab_timing),
    Display(R.string.fine_tab_display)
}

@Composable
fun AdvancedSettingsScreen(
    config: AdvancedConfig,
    subjectModelDelivery: SubjectModelDelivery,
    subjectModelState: SubjectModelState,
    onDownloadSubjectModel: () -> Unit,
    onApply: (AdvancedResult) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(FineTuneTab.Effect) }
    var poll by remember { mutableStateOf(config.poll) }
    var delay by remember { mutableStateOf(config.delay) }
    var duration by remember { mutableStateOf(config.duration) }
    var transitionsEnabled by remember { mutableStateOf(config.transitionsEnabled) }
    var alwaysAppliedTarget by remember { mutableStateOf(config.alwaysAppliedTarget) }
    var dimness by remember { mutableFloatStateOf(config.dimness) }
    var blurStrength by remember { mutableFloatStateOf(config.blurStrength) }
    var rotationIndex by remember { mutableIntStateOf(config.initialRotationIndex) }
    var dotSize by remember { mutableFloatStateOf(config.dotSize) }
    var grayscale by remember { mutableStateOf(config.grayscale) }
    var originX by remember { mutableFloatStateOf(config.originX) }
    var originY by remember { mutableFloatStateOf(config.originY) }
    var saturation by remember { mutableFloatStateOf(config.saturation) }
    var contrast by remember { mutableFloatStateOf(config.contrast) }
    var neonSensitivity by remember { mutableFloatStateOf(config.neonSensitivity) }
    var neonLineWidth by remember { mutableFloatStateOf(config.neonLineWidth) }
    var atmosphereGlassEnabled by remember {
        mutableStateOf(config.atmosphereGlassEnabled)
    }
    var clockEnabled by remember {
        mutableStateOf(config.clockEnabled)
    }
    var clockDepthEnabled by remember {
        mutableStateOf(config.clockDepthEnabled)
    }
    var clockScreenId by remember {
        mutableStateOf(config.clockScreenId)
    }
    var glassLineCount by remember { mutableFloatStateOf(config.glassLineCount.toFloat()) }
    var glassLineThickness by remember {
        mutableFloatStateOf(config.glassLineThickness)
    }
    var glassTransitionStyle by remember {
        mutableStateOf(config.glassTransitionStyle)
    }
    var glassBackgroundOnly by remember {
        mutableStateOf(config.glassBackgroundOnly)
    }
    var halftoneBackgroundOnly by remember {
        mutableStateOf(config.halftoneBackgroundOnly)
    }
    var subjectSegmentationEnabled by remember {
        mutableStateOf(config.subjectSegmentationEnabled)
    }
    var noiseEnabled by remember { mutableStateOf(config.enableNoise) }
    var noiseScale by remember { mutableStateOf(config.noiseScale) }
    var noiseStrength by remember { mutableStateOf(config.noiseStrength) }
    var scrollEnabled by remember { mutableStateOf(config.scrollEnabled) }
    var rendererPreference by remember { mutableStateOf(config.rendererPreference) }
    var infoDialog by remember { mutableStateOf<InfoDialog?>(null) }

    val bundledSubjectModel = subjectModelDelivery == SubjectModelDelivery.BUNDLED_FOSS
    val subjectModelReady = bundledSubjectModel ||
        subjectModelState.phase == SubjectModelPhase.READY
    val subjectModelWorking = !bundledSubjectModel && subjectModelState.phase in setOf(
        SubjectModelPhase.CHECKING,
        SubjectModelPhase.DOWNLOADING,
        SubjectModelPhase.INSTALLING,
        SubjectModelPhase.PAUSED
    )
    val subjectModelButtonText = when (subjectModelState.phase) {
        SubjectModelPhase.CHECKING -> stringResource(R.string.model_checking)
        SubjectModelPhase.NOT_DOWNLOADED -> stringResource(R.string.model_download)
        SubjectModelPhase.DOWNLOADING -> subjectModelState.progressPercent?.let {
            stringResource(R.string.model_downloading_percent, it)
        } ?: stringResource(R.string.model_downloading)
        SubjectModelPhase.INSTALLING -> stringResource(R.string.model_installing)
        SubjectModelPhase.PAUSED -> stringResource(R.string.model_download_paused)
        SubjectModelPhase.READY -> stringResource(R.string.model_downloaded)
        SubjectModelPhase.FAILED -> stringResource(R.string.model_retry_download)
        SubjectModelPhase.BROKEN -> stringResource(R.string.model_paused)
    }
    val subjectModelStatusText = when {
        bundledSubjectModel ->
            stringResource(R.string.model_status_bundled)
        subjectModelState.phase == SubjectModelPhase.CHECKING ->
            stringResource(R.string.model_status_checking)
        subjectModelState.phase == SubjectModelPhase.NOT_DOWNLOADED ->
            stringResource(R.string.model_status_optional)
        subjectModelState.phase == SubjectModelPhase.DOWNLOADING ->
            stringResource(R.string.model_status_downloading)
        subjectModelState.phase == SubjectModelPhase.INSTALLING ->
            stringResource(R.string.model_status_installing)
        subjectModelState.phase == SubjectModelPhase.PAUSED ->
            stringResource(R.string.model_status_paused)
        subjectModelState.phase == SubjectModelPhase.READY ->
            stringResource(R.string.model_status_ready)
        subjectModelState.phase == SubjectModelPhase.BROKEN ->
            stringResource(R.string.model_status_broken)
        else -> stringResource(R.string.model_status_failed)
    }

    val result = AdvancedResult(
        poll = poll,
        delay = delay,
        duration = duration,
        transitionsEnabled = transitionsEnabled,
        alwaysAppliedTarget = alwaysAppliedTarget,
        dimness = dimness,
        blurStrength = blurStrength,
        enableNoise = noiseEnabled,
        noiseScale = noiseScale,
        noiseStrength = noiseStrength,
        dotSize = dotSize,
        grayscale = grayscale,
        originX = originX,
        originY = originY,
        saturation = saturation,
        contrast = contrast,
        neonSensitivity = neonSensitivity,
        neonLineWidth = neonLineWidth,
        atmosphereGlassEnabled = atmosphereGlassEnabled,
        clockEnabled = clockEnabled,
        clockDepthEnabled = clockDepthEnabled,
        clockScreenId = clockScreenId,
        glassLineCount = GlassEffectPolicy.sanitizeLineCount(glassLineCount),
        glassLineThickness = GlassEffectPolicy.sanitizeLineThickness(glassLineThickness),
        glassTransitionStyle = glassTransitionStyle,
        glassBackgroundOnly = glassBackgroundOnly,
        halftoneBackgroundOnly = halftoneBackgroundOnly,
        subjectSegmentationEnabled = subjectSegmentationEnabled,
        rotationIndex = rotationIndex,
        scrollEnabled = scrollEnabled,
        rendererPreference = rendererPreference
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AtmoTopBar(
                title = stringResource(R.string.fine_title),
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AtmoOutlinedButton(
                        text = stringResource(R.string.common_reset),
                        onClick = onReset,
                        modifier = Modifier.weight(0.42f)
                    )
                    AtmoPrimaryButton(
                        text = stringResource(R.string.common_save),
                        onClick = { onApply(result) },
                        modifier = Modifier.weight(0.58f)
                    )
                }
            }
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AtmoReveal {
                AtmoSegmentedControl(
                    options = FineTuneTab.entries.map { stringResource(it.label) },
                    selectedIndex = selectedTab.ordinal,
                    onSelected = { selectedTab = FineTuneTab.entries[it] },
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    (fadeIn() + slideInHorizontally { if (forward) it / 6 else -it / 6 }) togetherWith
                        (fadeOut() + slideOutHorizontally { if (forward) -it / 6 else it / 6 })
                },
                modifier = Modifier.fillMaxSize(),
                label = "fineTuneTab"
            ) { tab ->
                when (tab) {
                    FineTuneTab.Effect -> EffectSettings(
                        config = config,
                        transitionsEnabled = transitionsEnabled,
                        dotSize = dotSize,
                        onDotSizeChange = { dotSize = it },
                        grayscale = grayscale,
                        onGrayscaleChange = { grayscale = it },
                        originX = originX,
                        onOriginXChange = { originX = it },
                        originY = originY,
                        onOriginYChange = { originY = it },
                        saturation = saturation,
                        onSaturationChange = { saturation = it },
                        contrast = contrast,
                        onContrastChange = { contrast = it },
                        neonSensitivity = neonSensitivity,
                        onNeonSensitivityChange = { neonSensitivity = it },
                        neonLineWidth = neonLineWidth,
                        onNeonLineWidthChange = { neonLineWidth = it },
                        atmosphereGlassEnabled = atmosphereGlassEnabled,
                        onAtmosphereGlassEnabledChange = {
                            atmosphereGlassEnabled = it
                        },
                        clockEnabled = clockEnabled,
                        onClockEnabledChange = { clockEnabled = it },
                        clockDepthEnabled = clockDepthEnabled,
                        onClockDepthEnabledChange = { clockDepthEnabled = it },
                        clockScreenId = clockScreenId,
                        onClockScreenIdChange = { clockScreenId = it },
                        glassLineCount = glassLineCount,
                        onGlassLineCountChange = { glassLineCount = it },
                        glassLineThickness = glassLineThickness,
                        onGlassLineThicknessChange = { glassLineThickness = it },
                        glassTransitionStyle = glassTransitionStyle,
                        onGlassTransitionStyleChange = { glassTransitionStyle = it },
                        glassBackgroundOnly = glassBackgroundOnly,
                        onGlassBackgroundOnlyChange = { glassBackgroundOnly = it },
                        halftoneBackgroundOnly = halftoneBackgroundOnly,
                        onHalftoneBackgroundOnlyChange = { halftoneBackgroundOnly = it },
                        subjectSegmentationEnabled = subjectSegmentationEnabled,
                        onSubjectSegmentationChange = { subjectSegmentationEnabled = it },
                        subjectModelDelivery = subjectModelDelivery,
                        subjectModelReady = subjectModelReady,
                        subjectModelWorking = subjectModelWorking,
                        subjectModelButtonText = subjectModelButtonText,
                        subjectModelStatusText = subjectModelStatusText,
                        subjectModelState = subjectModelState,
                        onDownloadSubjectModel = onDownloadSubjectModel,
                        noiseEnabled = noiseEnabled,
                        onNoiseEnabledChange = { noiseEnabled = it },
                        noiseScale = noiseScale,
                        onNoiseScaleChange = { noiseScale = it },
                        noiseStrength = noiseStrength,
                        onNoiseStrengthChange = { noiseStrength = it }
                    )
                    FineTuneTab.Timing -> TimingSettings(
                        activeEffectTitle = config.activeEffectTitle,
                        recommendedDurationMs = config.recommendedDurationMs,
                        poll = poll,
                        onPollChange = { poll = it.filterDigits() },
                        delay = delay,
                        onDelayChange = { delay = it.filterDigits() },
                        duration = duration,
                        onDurationChange = { duration = it.filterDigits() },
                        transitionsEnabled = transitionsEnabled,
                        onTransitionsEnabledChange = { transitionsEnabled = it },
                        alwaysAppliedTarget = alwaysAppliedTarget,
                        onAlwaysAppliedTargetChange = { alwaysAppliedTarget = it },
                        onPollInfo = { infoDialog = InfoDialog.Poll },
                        onDelayInfo = { infoDialog = InfoDialog.Delay }
                    )
                    FineTuneTab.Display -> DisplaySettings(
                        config = config,
                        dimness = dimness,
                        onDimnessChange = { dimness = it },
                        blurStrength = blurStrength,
                        onBlurStrengthChange = { blurStrength = it },
                        scrollEnabled = scrollEnabled,
                        onScrollEnabledChange = { scrollEnabled = it },
                        rendererPreference = rendererPreference,
                        onRendererPreferenceChange = { rendererPreference = it },
                        rotationIndex = rotationIndex,
                        onRotationSelected = { rotationIndex = it }
                    )
                }
            }
        }
    }

    infoDialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = { infoDialog = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(stringResource(dialog.title)) },
            text = { Text(stringResource(dialog.message), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                AtmoTextButton(text = stringResource(R.string.common_done), onClick = { infoDialog = null })
            }
        )
    }
}

@Composable
private fun EffectSettings(
    config: AdvancedConfig,
    transitionsEnabled: Boolean,
    dotSize: Float,
    onDotSizeChange: (Float) -> Unit,
    grayscale: Boolean,
    onGrayscaleChange: (Boolean) -> Unit,
    originX: Float,
    onOriginXChange: (Float) -> Unit,
    originY: Float,
    onOriginYChange: (Float) -> Unit,
    saturation: Float,
    onSaturationChange: (Float) -> Unit,
    contrast: Float,
    onContrastChange: (Float) -> Unit,
    neonSensitivity: Float,
    onNeonSensitivityChange: (Float) -> Unit,
    neonLineWidth: Float,
    onNeonLineWidthChange: (Float) -> Unit,
    atmosphereGlassEnabled: Boolean,
    onAtmosphereGlassEnabledChange: (Boolean) -> Unit,
    clockEnabled: Boolean,
    onClockEnabledChange: (Boolean) -> Unit,
    clockDepthEnabled: Boolean,
    onClockDepthEnabledChange: (Boolean) -> Unit,
    clockScreenId: String,
    onClockScreenIdChange: (String) -> Unit,
    glassLineCount: Float,
    onGlassLineCountChange: (Float) -> Unit,
    glassLineThickness: Float,
    onGlassLineThicknessChange: (Float) -> Unit,
    glassTransitionStyle: GlassTransitionStyle,
    onGlassTransitionStyleChange: (GlassTransitionStyle) -> Unit,
    glassBackgroundOnly: Boolean,
    onGlassBackgroundOnlyChange: (Boolean) -> Unit,
    halftoneBackgroundOnly: Boolean,
    onHalftoneBackgroundOnlyChange: (Boolean) -> Unit,
    subjectSegmentationEnabled: Boolean,
    onSubjectSegmentationChange: (Boolean) -> Unit,
    subjectModelDelivery: SubjectModelDelivery,
    subjectModelReady: Boolean,
    subjectModelWorking: Boolean,
    subjectModelButtonText: String,
    subjectModelStatusText: String,
    subjectModelState: SubjectModelState,
    onDownloadSubjectModel: () -> Unit,
    noiseEnabled: Boolean,
    onNoiseEnabledChange: (Boolean) -> Unit,
    noiseScale: String,
    onNoiseScaleChange: (String) -> Unit,
    noiseStrength: String,
    onNoiseStrengthChange: (String) -> Unit
) {
    SettingsScroll {
        if (
            config.showGlass ||
            (config.showAtmosphereGlassToggle && transitionsEnabled)
        ) {
            SettingsGroup(stringResource(R.string.glass_group)) {
                if (config.showAtmosphereGlassToggle) {
                    SettingSwitchRow(
                        title = stringResource(R.string.glass_add),
                        checked = atmosphereGlassEnabled,
                        onCheckedChange = onAtmosphereGlassEnabledChange,
                        subtitle = if (atmosphereGlassEnabled) {
                            stringResource(R.string.glass_on_sharp)
                        } else {
                            stringResource(R.string.glass_off_sharp)
                        }
                    )
                }

                AnimatedVisibility(
                    visible = config.showGlass || atmosphereGlassEnabled
                ) {
                    Column {
                        if (config.showAtmosphereGlassToggle) {
                            Spacer(Modifier.height(18.dp))
                        }
                        LabeledSlider(
                            label = stringResource(R.string.glass_lines),
                            value = glassLineCount,
                            onValueChange = onGlassLineCountChange,
                            valueRange = GlassEffectPolicy.MIN_LINE_COUNT.toFloat()..
                                GlassEffectPolicy.MAX_LINE_COUNT.toFloat(),
                            step = 1f,
                            valueText = { it.roundToInt().toString() }
                        )
                        Spacer(Modifier.height(12.dp))
                        LabeledSlider(
                            label = stringResource(R.string.common_line_thickness),
                            value = glassLineThickness,
                            onValueChange = onGlassLineThicknessChange,
                            valueRange = GlassEffectPolicy.MIN_LINE_THICKNESS..
                                GlassEffectPolicy.MAX_LINE_THICKNESS,
                            step = 0.05f,
                            valueText = { "${(it * 100).roundToInt()}%" }
                        )
                        if (config.showGlass && transitionsEnabled) {
                            Spacer(Modifier.height(18.dp))
                            Text(
                                stringResource(R.string.glass_transition_style),
                                style = MaterialTheme.typography.labelLarge
                            )
                            Spacer(Modifier.height(8.dp))
                            AtmoSegmentedControl(
                                options = if (config.glassReverse) {
                                    listOf(stringResource(R.string.glass_left_to_right), stringResource(R.string.glass_fade_out))
                                } else {
                                    listOf(stringResource(R.string.glass_right_to_left), stringResource(R.string.glass_fade_in))
                                },
                                selectedIndex = glassTransitionStyle.ordinal,
                                onSelected = {
                                    onGlassTransitionStyleChange(
                                        GlassTransitionStyle.entries.getOrElse(it) {
                                            GlassTransitionStyle.RIGHT_TO_LEFT
                                        }
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        // A clock with depth or Adapt to subject puts the
                        // subject in front of it, so glass over the subject
                        // would contradict it: Background only stays on.
                        // GlassEffectPolicy.clockForcesBackgroundOnly does the
                        // same for the wallpaper itself.
                        val clockKeepsSubjectClear = config.showClockToggle &&
                            !config.isPlaylistMode && clockEnabled && clockDepthEnabled
                        SubjectIsolationSetting(
                            title = stringResource(R.string.common_background_only),
                            checked = glassBackgroundOnly,
                            forcedOnReason = if (clockKeepsSubjectClear) {
                                stringResource(R.string.glass_forced_background)
                            } else {
                                null
                            },
                            onCheckedChange = onGlassBackgroundOnlyChange,
                            inactiveText = stringResource(R.string.glass_inactive),
                            activeDescription =
                                stringResource(R.string.glass_active),
                            waitingText =
                                stringResource(R.string.common_waiting_model),
                            subjectModelDelivery = subjectModelDelivery,
                            subjectModelReady = subjectModelReady,
                            subjectModelWorking = subjectModelWorking,
                            subjectModelButtonText = subjectModelButtonText,
                            subjectModelStatusText = subjectModelStatusText,
                            subjectModelState = subjectModelState,
                            onDownloadSubjectModel = onDownloadSubjectModel
                        )
                    }
                }
            }
        }

        if (config.showClockToggle && config.isPlaylistMode) {
            SettingsGroup(stringResource(R.string.clock_group)) {
                Text(
                    stringResource(R.string.clock_playlist_only_single),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (config.showClockToggle && !config.isPlaylistMode) {
            var showClockHelp by remember { mutableStateOf(false) }
            if (showClockHelp) {
                LockScreenClockHelpSheet(onDismiss = { showClockHelp = false })
            }
            SettingsGroup(stringResource(R.string.clock_group), onInfoClick = { showClockHelp = true }) {
                SettingSwitchRow(
                    title = stringResource(R.string.clock_show),
                    checked = clockEnabled,
                    onCheckedChange = onClockEnabledChange,
                    subtitle = if (clockEnabled) {
                        stringResource(R.string.clock_show_on_hint)
                    } else {
                        stringResource(R.string.clock_show_off_hint)
                    }
                )
                if (clockEnabled) {
                    // Shown only where the side is actually the user's to
                    // pick. Effects that blur or refract the photo on one side
                    // get no control and no explanation — the clock simply
                    // appears on the side that stays legible.
                    if (config.clockOffersScreenChoice) {
                        Spacer(Modifier.height(18.dp))
                        Text(
                            stringResource(R.string.clock_show_on),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.height(8.dp))
                        val screenOrder = listOf(
                            ClockScreen.LOCK,
                            ClockScreen.HOME,
                            ClockScreen.BOTH
                        )
                        AtmoSegmentedControl(
                            options = listOf(
                                stringResource(R.string.common_lock_screen),
                                stringResource(R.string.common_home_screen),
                                stringResource(R.string.common_both)
                            ),
                            selectedIndex = screenOrder
                                .indexOf(ClockScreen.fromId(clockScreenId))
                                .coerceAtLeast(0),
                            onSelected = { index ->
                                onClockScreenIdChange(
                                    screenOrder.getOrElse(index) {
                                        ClockScreen.LOCK
                                    }.id
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    // Depth is the clock's own switch, not the Glass effect's.
                    // Turning it on computes a subject mask whether or not
                    // Glass is in use, which is the whole point: an earlier
                    // version reused Glass's "background only" flag, so the
                    // depth effect silently did nothing unless Glass was on.
                    // The style is chosen on the clock screen, which this one
                    // opens, so it is read again whenever this comes back.
                    // The Adaptive face is always in front of the subject, so
                    // the same switch means something else there: whether it
                    // fits itself around the subject at all.
                    val context = LocalContext.current
                    val adaptiveStyle = rememberClockStyleIsAdaptive()
                    SettingSwitchRow(
                        title = stringResource(if (adaptiveStyle) R.string.clock_adapt else R.string.clock_depth),
                        checked = clockDepthEnabled,
                        onCheckedChange = onClockDepthEnabledChange,
                        subtitle = if (adaptiveStyle) {
                            if (clockDepthEnabled) {
                                stringResource(R.string.clock_adapt_on)
                            } else {
                                stringResource(R.string.clock_adapt_off)
                            }
                        } else {
                            stringResource(R.string.clock_depth_hint)
                        }
                    )
                    AtmoTextButton(
                        text = stringResource(R.string.clock_choose_style),
                        onClick = {
                            context.startActivity(
                                Intent(context, ClockAdjustActivity::class.java)
                            )
                        }
                    )
                }
            }
        }

        if (config.showHalftone) {
            SettingsGroup(stringResource(R.string.halftone_group)) {
                LabeledSlider(
                    label = stringResource(R.string.halftone_dot_size),
                    value = dotSize,
                    onValueChange = onDotSizeChange,
                    valueRange = 0f..40f,
                    step = 1f
                )
                Spacer(Modifier.height(8.dp))
                SettingSwitchRow(
                    title = stringResource(R.string.halftone_bw),
                    checked = grayscale,
                    onCheckedChange = onGrayscaleChange
                )
                Spacer(Modifier.height(18.dp))
                SubjectIsolationSetting(
                    title = stringResource(R.string.common_background_only),
                    checked = halftoneBackgroundOnly,
                    onCheckedChange = onHalftoneBackgroundOnlyChange,
                    inactiveText = stringResource(R.string.halftone_inactive),
                    activeDescription = stringResource(R.string.halftone_active),
                    waitingText = stringResource(R.string.common_waiting_model),
                    subjectModelDelivery = subjectModelDelivery,
                    subjectModelReady = subjectModelReady,
                    subjectModelWorking = subjectModelWorking,
                    subjectModelButtonText = subjectModelButtonText,
                    subjectModelStatusText = subjectModelStatusText,
                    subjectModelState = subjectModelState,
                    onDownloadSubjectModel = onDownloadSubjectModel
                )
            }
        }

        if (config.showColorFill && transitionsEnabled) {
            SettingsGroup(stringResource(R.string.origin_group)) {
                LabeledSlider(
                    label = stringResource(R.string.origin_horizontal),
                    value = originX,
                    onValueChange = onOriginXChange,
                    valueRange = 0f..1f,
                    step = 0.01f
                )
                Spacer(Modifier.height(12.dp))
                LabeledSlider(
                    label = stringResource(R.string.origin_vertical),
                    value = originY,
                    onValueChange = onOriginYChange,
                    valueRange = 0f..1f,
                    step = 0.01f
                )
            }
        }

        if (config.showNeon) {
            SettingsGroup(stringResource(R.string.canvas_group)) {
                SubjectIsolationSetting(
                    title = stringResource(R.string.canvas_segmentation),
                    checked = subjectSegmentationEnabled,
                    onCheckedChange = onSubjectSegmentationChange,
                    inactiveText = stringResource(R.string.canvas_inactive),
                    activeDescription = stringResource(R.string.canvas_active),
                    waitingText = stringResource(R.string.canvas_waiting),
                    subjectModelDelivery = subjectModelDelivery,
                    subjectModelReady = subjectModelReady,
                    subjectModelWorking = subjectModelWorking,
                    subjectModelButtonText = subjectModelButtonText,
                    subjectModelStatusText = subjectModelStatusText,
                    subjectModelState = subjectModelState,
                    onDownloadSubjectModel = onDownloadSubjectModel
                )
                Spacer(Modifier.height(18.dp))
                LabeledSlider(
                    label = stringResource(R.string.canvas_detail),
                    value = neonSensitivity,
                    onValueChange = onNeonSensitivityChange,
                    valueRange = 0f..1f,
                    step = 0.05f
                )
                Spacer(Modifier.height(12.dp))
                LabeledSlider(
                    label = stringResource(R.string.common_line_thickness),
                    value = neonLineWidth,
                    onValueChange = onNeonLineWidthChange,
                    valueRange = 0.5f..4f,
                    step = 0.5f
                )
            }
        }

        if (config.showBlob) {
            SettingsGroup(stringResource(R.string.atmos_color_group)) {
                LabeledSlider(
                    label = stringResource(R.string.atmos_saturation),
                    value = saturation,
                    onValueChange = onSaturationChange,
                    valueRange = 0f..3f,
                    step = 0.1f
                )
                Spacer(Modifier.height(12.dp))
                LabeledSlider(
                    label = stringResource(R.string.atmos_contrast),
                    value = contrast,
                    onValueChange = onContrastChange,
                    valueRange = 0f..3f,
                    step = 0.1f
                )
            }
        }

        if (config.showNoiseSwitch) {
            SettingsGroup(stringResource(R.string.grain_group)) {
                SettingSwitchRow(
                    title = stringResource(R.string.grain_add),
                    checked = noiseEnabled,
                    onCheckedChange = onNoiseEnabledChange,
                    subtitle = stringResource(R.string.grain_hint)
                )
                AnimatedVisibility(visible = noiseEnabled) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        // Words rather than raw numbers: the stored values are
                        // grains across the image and a colour offset, neither
                        // of which means anything to look at.
                        val sizePosition = FilmGrainPolicy.sizePosition(
                            noiseScale.toFloatOrNull() ?: FilmGrainPolicy.DEFAULT_SCALE
                        )
                        val resources = LocalResources.current
                        LabeledSlider(
                            label = stringResource(R.string.grain_size),
                            value = sizePosition,
                            onValueChange = {
                                onNoiseScaleChange(FilmGrainPolicy.scaleAt(it).toString())
                            },
                            valueRange = 0f..1f,
                            valueText = { resources.getString(FilmGrainPolicy.sizeLabel(it)) }
                        )
                        Spacer(Modifier.height(12.dp))
                        LabeledSlider(
                            label = stringResource(R.string.grain_strength),
                            value = FilmGrainPolicy.sanitizeStrength(
                                noiseStrength.toFloatOrNull() ?: FilmGrainPolicy.DEFAULT_STRENGTH
                            ),
                            onValueChange = { onNoiseStrengthChange(it.toString()) },
                            valueRange = 0f..FilmGrainPolicy.MAX_STRENGTH,
                            valueText = { resources.getString(FilmGrainPolicy.strengthLabel(it)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubjectIsolationSetting(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    inactiveText: String,
    activeDescription: String,
    waitingText: String,
    subjectModelDelivery: SubjectModelDelivery,
    subjectModelReady: Boolean,
    subjectModelWorking: Boolean,
    subjectModelButtonText: String,
    subjectModelStatusText: String,
    subjectModelState: SubjectModelState,
    onDownloadSubjectModel: () -> Unit,
    /** Set when something else needs this on: shown on and greyed out, with this reason. */
    forcedOnReason: String? = null
) {
    SettingSwitchRow(
        title = title,
        checked = checked || forcedOnReason != null,
        onCheckedChange = onCheckedChange,
        enabled = forcedOnReason == null && (subjectModelReady || checked),
        subtitle = when {
            forcedOnReason != null -> forcedOnReason
            !checked -> inactiveText
            subjectModelDelivery == SubjectModelDelivery.BUNDLED_FOSS ->
                stringResource(R.string.model_uses_bundled, activeDescription)
            subjectModelReady ->
                stringResource(R.string.model_uses_installed, activeDescription)
            else -> waitingText
        }
    )
    if (subjectModelDelivery == SubjectModelDelivery.GOOGLE_PLAY_SERVICES) {
        Spacer(Modifier.height(10.dp))
        AtmoOutlinedButton(
            text = subjectModelButtonText,
            onClick = onDownloadSubjectModel,
            enabled = !subjectModelWorking && !subjectModelReady &&
                subjectModelState.phase != SubjectModelPhase.BROKEN,
            accent = true,
            icon = if (!subjectModelWorking && !subjectModelReady) {
                painterResource(R.drawable.ic_download)
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth()
        )
        if (subjectModelWorking) {
            Spacer(Modifier.height(10.dp))
            val percent = subjectModelState.progressPercent
            if (percent != null) {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        subjectModelStatusText,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun TimingSettings(
    activeEffectTitle: String,
    recommendedDurationMs: Long,
    poll: String,
    onPollChange: (String) -> Unit,
    delay: String,
    onDelayChange: (String) -> Unit,
    duration: String,
    onDurationChange: (String) -> Unit,
    transitionsEnabled: Boolean,
    onTransitionsEnabledChange: (Boolean) -> Unit,
    alwaysAppliedTarget: AlwaysAppliedTarget,
    onAlwaysAppliedTargetChange: (AlwaysAppliedTarget) -> Unit,
    onPollInfo: () -> Unit,
    onDelayInfo: () -> Unit
) {
    val info = painterResource(R.drawable.ic_info)
    val alwaysAppliedDescription = when (alwaysAppliedTarget) {
        AlwaysAppliedTarget.HOME ->
            stringResource(R.string.timing_always_home)
        AlwaysAppliedTarget.LOCK ->
            stringResource(R.string.timing_always_lock)
        AlwaysAppliedTarget.BOTH ->
            stringResource(R.string.timing_always_both)
    }
    SettingsScroll {
        SettingsGroup(stringResource(R.string.timing_behavior_group)) {
            SettingSwitchRow(
                title = stringResource(R.string.timing_animate),
                checked = transitionsEnabled,
                onCheckedChange = onTransitionsEnabledChange,
                subtitle = if (transitionsEnabled) {
                    stringResource(R.string.timing_animate_on)
                } else {
                    stringResource(R.string.timing_animate_off)
                }
            )
        }

        AnimatedVisibility(visible = transitionsEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsGroup(stringResource(R.string.timing_unlock_group)) {
                    AtmoNumberField(
                        label = stringResource(R.string.timing_poll),
                        value = poll,
                        onValueChange = onPollChange,
                        helper = stringResource(R.string.timing_poll_helper),
                        infoIcon = info,
                        onInfoClick = onPollInfo
                    )
                    Spacer(Modifier.height(16.dp))
                    AtmoNumberField(
                        label = stringResource(R.string.timing_delay),
                        value = delay,
                        onValueChange = onDelayChange,
                        helper = stringResource(R.string.timing_delay_helper),
                        infoIcon = info,
                        onInfoClick = onDelayInfo
                    )
                }
                SettingsGroup(stringResource(R.string.timing_animation_group)) {
                    AtmoNumberField(
                        label = stringResource(R.string.timing_duration),
                        value = duration,
                        onValueChange = onDurationChange,
                        helper = stringResource(R.string.timing_duration_helper, activeEffectTitle, recommendedDurationMs)
                    )
                }
            }
        }

        AnimatedVisibility(visible = !transitionsEnabled) {
            SettingsGroup(stringResource(R.string.timing_keep_group)) {
                AtmoSegmentedControl(
                    options = listOf(
                        stringResource(R.string.common_home_screen),
                        stringResource(R.string.common_lock_screen),
                        stringResource(R.string.common_both)
                    ),
                    selectedIndex = alwaysAppliedTarget.ordinal,
                    onSelected = { index ->
                        onAlwaysAppliedTargetChange(
                            AlwaysAppliedTarget.entries.getOrElse(index) {
                                AlwaysAppliedTarget.BOTH
                            }
                        )
                    }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.timing_still_live, alwaysAppliedDescription),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DisplaySettings(
    config: AdvancedConfig,
    dimness: Float,
    onDimnessChange: (Float) -> Unit,
    blurStrength: Float,
    onBlurStrengthChange: (Float) -> Unit,
    scrollEnabled: Boolean,
    onScrollEnabledChange: (Boolean) -> Unit,
    rendererPreference: GraphicsBackendPreference,
    onRendererPreferenceChange: (GraphicsBackendPreference) -> Unit,
    rotationIndex: Int,
    onRotationSelected: (Int) -> Unit
) {
    val rendererOptions = listOf(
        stringResource(R.string.renderer_preference_automatic),
        stringResource(R.string.renderer_preference_vulkan),
        stringResource(R.string.renderer_preference_opengl)
    )
    val rendererHelper = when (rendererPreference) {
        GraphicsBackendPreference.AUTOMATIC ->
            stringResource(R.string.renderer_preference_automatic_helper)
        GraphicsBackendPreference.VULKAN ->
            stringResource(R.string.renderer_preference_vulkan_helper)
        GraphicsBackendPreference.OPENGL_ES ->
            stringResource(R.string.renderer_preference_opengl_helper)
    }

    SettingsScroll {
        SettingsGroup(stringResource(R.string.display_appearance_group)) {
            LabeledSlider(
                label = stringResource(R.string.display_dimness),
                value = dimness,
                onValueChange = onDimnessChange,
                valueRange = 0f..0.8f,
                step = 0.05f,
                valueText = { "${(it * 100).toInt()}%" }
            )
            if (config.showFrosted) {
                Spacer(Modifier.height(12.dp))
                LabeledSlider(
                    label = stringResource(R.string.display_blur),
                    value = blurStrength,
                    onValueChange = onBlurStrengthChange,
                    valueRange = 0f..400f,
                    step = 10f
                )
            }
        }
        SettingsGroup(stringResource(R.string.renderer_preference_group)) {
            AtmoDropdownField(
                label = stringResource(R.string.renderer_preference_label),
                options = rendererOptions,
                selectedIndex = rendererPreference.ordinal,
                onSelected = { index ->
                    onRendererPreferenceChange(
                        GraphicsBackendPreference.entries.getOrElse(index) {
                            GraphicsBackendPreference.AUTOMATIC
                        }
                    )
                },
                helper = rendererHelper
            )
        }
        SettingsGroup(stringResource(R.string.common_home_screen)) {
            SettingSwitchRow(
                title = stringResource(R.string.display_scrolling),
                subtitle = "${stringResource(R.string.experimental)} ${stringResource(R.string.manualCroppingWillBeDisabled)}",
                checked = scrollEnabled,
                onCheckedChange = onScrollEnabledChange
            )
        }
        if (config.isPlaylistMode) {
            SettingsGroup(stringResource(R.string.display_playlist_group)) {
                AtmoDropdownField(
                    label = stringResource(R.string.display_rotation),
                    options = config.rotationOptions,
                    selectedIndex = rotationIndex,
                    onSelected = onRotationSelected
                )
            }
        }

    }
}

@Composable
private fun SettingsScroll(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    onInfoClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (onInfoClick != null) {
                AtmoAnimatedIconButton(
                    painter = painterResource(R.drawable.ic_info),
                    contentDescription = stringResource(R.string.common_about, title),
                    onClick = onInfoClick,
                    motion = AtmoIconMotion.PRESS,
                    iconTint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            content()
        }
    }
}

/** Whether the chosen clock face is the Adaptive one, re-read on every resume. */
@Composable
private fun rememberClockStyleIsAdaptive(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    fun read(): Boolean = ClockStyle.fromId(
        context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            .getString(AtmosphereClockPolicy.STYLE_KEY, null)
    ).adaptsToSubject
    var adaptive by remember { mutableStateOf(read()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) adaptive = read()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return adaptive
}

private enum class InfoDialog(@StringRes val title: Int, @StringRes val message: Int) {
    Poll(R.string.info_poll_title, R.string.info_poll_message),
    Delay(R.string.info_delay_title, R.string.info_delay_message)
}

private fun String.filterDigits(): String = filter { it.isDigit() }

