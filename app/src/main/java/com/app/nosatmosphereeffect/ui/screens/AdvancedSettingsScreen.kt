package com.app.nosatmosphereeffect.ui.screens

import android.content.Intent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.activity.ClockAdjustActivity
import com.app.nosatmosphereeffect.activity.DiagnosticsActivity
import com.app.nosatmosphereeffect.helper.AlwaysAppliedTarget
import com.app.nosatmosphereeffect.helper.GlassEffectPolicy
import com.app.nosatmosphereeffect.helper.GlassTransitionStyle
import com.app.nosatmosphereeffect.helper.SubjectModelDelivery
import com.app.nosatmosphereeffect.helper.SubjectModelPhase
import com.app.nosatmosphereeffect.helper.SubjectModelState
import com.app.nosatmosphereeffect.renderer.backend.GraphicsBackendPreference
import com.app.nosatmosphereeffect.ui.components.AtmoDropdownField
import com.app.nosatmosphereeffect.ui.components.AtmoNumberField
import com.app.nosatmosphereeffect.ui.components.AtmoOutlinedButton
import com.app.nosatmosphereeffect.ui.components.AtmoPrimaryButton
import com.app.nosatmosphereeffect.ui.components.AtmoReveal
import com.app.nosatmosphereeffect.ui.components.AtmoSegmentedControl
import com.app.nosatmosphereeffect.ui.components.AtmoTopBar
import com.app.nosatmosphereeffect.ui.components.AtmoTextButton
import com.app.nosatmosphereeffect.ui.components.LabeledSlider
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

private enum class FineTuneTab(val label: String) {
    Effect("Effect"),
    Timing("Timing"),
    Display("Display")
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
        SubjectModelPhase.CHECKING -> "Checking model"
        SubjectModelPhase.NOT_DOWNLOADED -> "Download subject model"
        SubjectModelPhase.DOWNLOADING -> subjectModelState.progressPercent?.let {
            "Downloading $it%"
        } ?: "Downloading model"
        SubjectModelPhase.INSTALLING -> "Installing model"
        SubjectModelPhase.PAUSED -> "Download paused"
        SubjectModelPhase.READY -> "Subject model downloaded"
        SubjectModelPhase.FAILED -> "Retry model download"
    }
    val subjectModelStatusText = when {
        bundledSubjectModel ->
            "Bundled U2NetP model. Open source, on-device, and ready offline."
        subjectModelState.phase == SubjectModelPhase.CHECKING ->
            "Checking Google Play services without starting a download."
        subjectModelState.phase == SubjectModelPhase.NOT_DOWNLOADED ->
            "Optional. Google Play services downloads it only after you tap the button."
        subjectModelState.phase == SubjectModelPhase.DOWNLOADING ->
            "Google Play services is downloading the subject model."
        subjectModelState.phase == SubjectModelPhase.INSTALLING ->
            "Completing the on-device model installation."
        subjectModelState.phase == SubjectModelPhase.PAUSED ->
            "The download is paused until a connection is available."
        subjectModelState.phase == SubjectModelPhase.READY ->
            "Installed and ready for offline, on-device segmentation."
        else -> "The model check failed. Try again when Google Play services is available."
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
                title = "Fine tuning",
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
                        text = "Reset",
                        onClick = onReset,
                        modifier = Modifier.weight(0.42f)
                    )
                    AtmoPrimaryButton(
                        text = "Save",
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
                    options = FineTuneTab.entries.map { it.label },
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
            title = { Text(dialog.title) },
            text = { Text(dialog.message, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                AtmoTextButton(text = "Done", onClick = { infoDialog = null })
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
            SettingsGroup("Glass effect") {
                if (config.showAtmosphereGlassToggle) {
                    SettingSwitchRow(
                        title = "Add glass effect",
                        checked = atmosphereGlassEnabled,
                        onCheckedChange = onAtmosphereGlassEnabledChange,
                        subtitle = if (atmosphereGlassEnabled) {
                            "The sharp Atmosphere state uses the Glass effect."
                        } else {
                            "The sharp Atmosphere state uses the original wallpaper."
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
                            label = "Number of lines",
                            value = glassLineCount,
                            onValueChange = onGlassLineCountChange,
                            valueRange = GlassEffectPolicy.MIN_LINE_COUNT.toFloat()..
                                GlassEffectPolicy.MAX_LINE_COUNT.toFloat(),
                            step = 1f,
                            valueText = { it.roundToInt().toString() }
                        )
                        Spacer(Modifier.height(12.dp))
                        LabeledSlider(
                            label = "Line thickness",
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
                                "Transition style",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Spacer(Modifier.height(8.dp))
                            AtmoSegmentedControl(
                                options = if (config.glassReverse) {
                                    listOf("Left to right", "Fade out")
                                } else {
                                    listOf("Right to left", "Fade in")
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
                        SubjectIsolationSetting(
                            title = "Background only",
                            checked = glassBackgroundOnly,
                            onCheckedChange = onGlassBackgroundOnlyChange,
                            inactiveText = "Applies the glass lines to the complete wallpaper.",
                            activeDescription =
                                "The subject stays sharp while the background becomes glass.",
                            waitingText =
                                "The wallpaper stays unchanged until the subject model is ready.",
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
            SettingsGroup("Clock") {
                Text(
                    "The wallpaper clock is available in single-image mode only " +
                        "for now. In playlist and theme modes the image changes " +
                        "underneath it, so a position calibrated against one " +
                        "photo would be wrong for the next.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (config.showClockToggle && !config.isPlaylistMode) {
            SettingsGroup("Clock") {
                SettingSwitchRow(
                    title = "Show clock on wallpaper",
                    checked = clockEnabled,
                    onCheckedChange = onClockEnabledChange,
                    subtitle = if (clockEnabled) {
                        "Hide your device's own lock screen clock to avoid " +
                            "seeing two."
                    } else {
                        "Renders a clock into the wallpaper itself."
                    }
                )
                if (clockEnabled) {
                    // Depth is the clock's own switch, not the Glass effect's.
                    // Turning it on computes a subject mask whether or not
                    // Glass is in use, which is the whole point: the previous
                    // version reused Glass's "background only" flag, so the
                    // depth effect silently did nothing unless Glass was on.
                    SettingSwitchRow(
                        title = "Depth effect",
                        checked = clockDepthEnabled,
                        onCheckedChange = onClockDepthEnabledChange,
                        subtitle = "Draws the subject back over the clock, so " +
                            "the clock sits behind them. Needs a photo with a " +
                            "clear subject; works whether or not the Glass " +
                            "effect is on."
                    )
                    val context = LocalContext.current
                    AtmoTextButton(
                        text = "Choose style, position & size",
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
            SettingsGroup("Halftone") {
                LabeledSlider(
                    label = "Dot size",
                    value = dotSize,
                    onValueChange = onDotSizeChange,
                    valueRange = 0f..40f,
                    step = 1f
                )
                Spacer(Modifier.height(8.dp))
                SettingSwitchRow(
                    title = "Black and white",
                    checked = grayscale,
                    onCheckedChange = onGrayscaleChange
                )
                Spacer(Modifier.height(18.dp))
                SubjectIsolationSetting(
                    title = "Background only",
                    checked = halftoneBackgroundOnly,
                    onCheckedChange = onHalftoneBackgroundOnlyChange,
                    inactiveText = "Applies Halftone to the complete wallpaper.",
                    activeDescription = "The subject stays sharp while the background is printed.",
                    waitingText = "The wallpaper stays unchanged until the subject model is ready.",
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
            SettingsGroup("Color origin") {
                LabeledSlider(
                    label = "Horizontal position",
                    value = originX,
                    onValueChange = onOriginXChange,
                    valueRange = 0f..1f,
                    step = 0.01f
                )
                Spacer(Modifier.height(12.dp))
                LabeledSlider(
                    label = "Vertical position",
                    value = originY,
                    onValueChange = onOriginYChange,
                    valueRange = 0f..1f,
                    step = 0.01f
                )
            }
        }

        if (config.showNeon) {
            SettingsGroup("Canvas Sketch") {
                SubjectIsolationSetting(
                    title = "Subject segmentation",
                    checked = subjectSegmentationEnabled,
                    onCheckedChange = onSubjectSegmentationChange,
                    inactiveText = "Sketches the complete wallpaper.",
                    activeDescription = "The subject silhouette anchors the sketch.",
                    waitingText = "The complete wallpaper is sketched until the model is ready.",
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
                    label = "Sketch detail",
                    value = neonSensitivity,
                    onValueChange = onNeonSensitivityChange,
                    valueRange = 0f..1f,
                    step = 0.05f
                )
                Spacer(Modifier.height(12.dp))
                LabeledSlider(
                    label = "Line thickness",
                    value = neonLineWidth,
                    onValueChange = onNeonLineWidthChange,
                    valueRange = 0.5f..4f,
                    step = 0.5f
                )
            }
        }

        if (config.showBlob) {
            SettingsGroup("Atmosphere color") {
                LabeledSlider(
                    label = "Saturation",
                    value = saturation,
                    onValueChange = onSaturationChange,
                    valueRange = 0f..3f,
                    step = 0.1f
                )
                Spacer(Modifier.height(12.dp))
                LabeledSlider(
                    label = "Contrast",
                    value = contrast,
                    onValueChange = onContrastChange,
                    valueRange = 0f..3f,
                    step = 0.1f
                )
            }
        }

        if (config.showNoiseSwitch) {
            SettingsGroup("Film grain") {
                SettingSwitchRow(
                    title = "Blur noise",
                    checked = noiseEnabled,
                    onCheckedChange = onNoiseEnabledChange
                )
                AnimatedVisibility(visible = noiseEnabled) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        AtmoNumberField(
                            label = "Grain scale",
                            value = noiseScale,
                            onValueChange = { onNoiseScaleChange(it.filterDecimal()) },
                            helper = "Recommended: 2000",
                            decimal = true
                        )
                        Spacer(Modifier.height(14.dp))
                        AtmoNumberField(
                            label = "Grain strength",
                            value = noiseStrength,
                            onValueChange = { onNoiseStrengthChange(it.filterDecimal()) },
                            helper = "Recommended: 0.06",
                            decimal = true
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
    onDownloadSubjectModel: () -> Unit
) {
    SettingSwitchRow(
        title = title,
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = subjectModelReady || checked,
        subtitle = when {
            !checked -> inactiveText
            subjectModelDelivery == SubjectModelDelivery.BUNDLED_FOSS ->
                "Uses the bundled on-device model. $activeDescription"
            subjectModelReady ->
                "Uses the installed on-device model. $activeDescription"
            else -> waitingText
        }
    )
    if (subjectModelDelivery == SubjectModelDelivery.GOOGLE_PLAY_SERVICES) {
        Spacer(Modifier.height(10.dp))
        AtmoOutlinedButton(
            text = subjectModelButtonText,
            onClick = onDownloadSubjectModel,
            enabled = !subjectModelWorking && !subjectModelReady,
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
            "The effect stays on the Home screen. The Lock screen shows the original."
        AlwaysAppliedTarget.LOCK ->
            "The effect stays on the Lock screen. The Home screen shows the original."
        AlwaysAppliedTarget.BOTH ->
            "The effect stays on both screens."
    }
    SettingsScroll {
        SettingsGroup("Effect behavior") {
            SettingSwitchRow(
                title = "Animate transitions",
                checked = transitionsEnabled,
                onCheckedChange = onTransitionsEnabledChange,
                subtitle = if (transitionsEnabled) {
                    "Changes between effect states when the screen locks or unlocks."
                } else {
                    "Keeps the fully applied effect visible on the selected screens."
                }
            )
        }

        AnimatedVisibility(visible = transitionsEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsGroup("Unlock response") {
                    AtmoNumberField(
                        label = "Unlock check interval (ms)",
                        value = poll,
                        onValueChange = onPollChange,
                        helper = "Standard: 50 | Samsung: 30000",
                        infoIcon = info,
                        onInfoClick = onPollInfo
                    )
                    Spacer(Modifier.height(16.dp))
                    AtmoNumberField(
                        label = "Lock delay (ms)",
                        value = delay,
                        onValueChange = onDelayChange,
                        helper = "Standard: 800 | Samsung: 0",
                        infoIcon = info,
                        onInfoClick = onDelayInfo
                    )
                }
                SettingsGroup("Animation") {
                    AtmoNumberField(
                        label = "Duration (ms)",
                        value = duration,
                        onValueChange = onDurationChange,
                        helper = "Recommended for $activeEffectTitle: $recommendedDurationMs ms"
                    )
                }
            }
        }

        AnimatedVisibility(visible = !transitionsEnabled) {
            SettingsGroup("Keep effect applied on") {
                AtmoSegmentedControl(
                    options = listOf("Home screen", "Lock screen", "Both"),
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
                    "$alwaysAppliedDescription Atmo Engine remains a live wallpaper.",
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
        SettingsGroup("Wallpaper appearance") {
            LabeledSlider(
                label = "Dimness",
                value = dimness,
                onValueChange = onDimnessChange,
                valueRange = 0f..0.8f,
                step = 0.05f,
                valueText = { "${(it * 100).toInt()}%" }
            )
            if (config.showFrosted) {
                Spacer(Modifier.height(12.dp))
                LabeledSlider(
                    label = "Blur strength",
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
        SettingsGroup("Home screen") {
            SettingSwitchRow(
                title = "Wallpaper scrolling",
                subtitle = "${stringResource(R.string.experimental)} ${stringResource(R.string.manualCroppingWillBeDisabled)}",
                checked = scrollEnabled,
                onCheckedChange = onScrollEnabledChange
            )
        }
        if (config.isPlaylistMode) {
            SettingsGroup("Playlist") {
                AtmoDropdownField(
                    label = "Rotation mode",
                    options = config.rotationOptions,
                    selectedIndex = rotationIndex,
                    onSelected = onRotationSelected
                )
            }
        }

        SettingsGroup("Diagnostics") {
            Text(
                "Records which graphics backend each effect selected, and the " +
                    "reason if Vulkan fell back to OpenGL ES. Useful when " +
                    "reporting a rendering problem.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            val diagnosticsContext = LocalContext.current
            AtmoTextButton(
                text = "Renderer diagnostics",
                onClick = {
                    diagnosticsContext.startActivity(
                        Intent(diagnosticsContext, DiagnosticsActivity::class.java)
                    )
                }
            )
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
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Column(
            modifier = Modifier.padding(horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            content()
        }
    }
}

private enum class InfoDialog(val title: String, val message: String) {
    Poll(
        "Unlock check interval",
        "Lower values provide a faster reaction after unlocking but require more frequent checks, which may impact battery life. Use 30000 ms on Samsung devices, or 50 ms if the animation starts late."
    ),
    Delay(
        "Lock delay",
        "Increase this only if the wallpaper visibly resets before the screen turns off. " +
            "Use 0 ms on Samsung or 500-800 ms when needed."
    )
}

private fun String.filterDigits(): String = filter { it.isDigit() }

private fun String.filterDecimal(): String {
    val cleaned = filter { it.isDigit() || it == '.' }
    val firstDot = cleaned.indexOf('.')
    if (firstDot == -1) return cleaned
    return cleaned.substring(0, firstDot + 1) +
        cleaned.substring(firstDot + 1).replace(".", "")
}
