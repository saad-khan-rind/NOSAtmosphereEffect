package com.app.nosatmosphereeffect.activity

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.app.nosatmosphereeffect.helper.AtmosphereClockPolicy
import com.app.nosatmosphereeffect.helper.AtmosphereGlassPolicy
import com.app.nosatmosphereeffect.helper.CanvasSubjectSettings
import com.app.nosatmosphereeffect.helper.GlassEffectPreferences
import com.app.nosatmosphereeffect.helper.GlassEffectPolicy
import com.app.nosatmosphereeffect.helper.SubjectModelBuild
import com.app.nosatmosphereeffect.helper.SubjectModelDelivery
import com.app.nosatmosphereeffect.helper.SubjectModelManager
import com.app.nosatmosphereeffect.helper.SubjectModelPhase
import com.app.nosatmosphereeffect.helper.SubjectModelState
import com.app.nosatmosphereeffect.helper.SubjectIsolationPolicy
import com.app.nosatmosphereeffect.helper.WallpaperBehaviorPreferences
import com.app.nosatmosphereeffect.helper.WallpaperBehaviorSettings
import com.app.nosatmosphereeffect.helper.WallpaperFitHelper
import com.app.nosatmosphereeffect.renderer.backend.GraphicsBackendPreferences
import com.app.nosatmosphereeffect.ui.screens.AdvancedConfig
import com.app.nosatmosphereeffect.ui.screens.AdvancedResult
import com.app.nosatmosphereeffect.ui.screens.AdvancedSettingsScreen
import com.app.nosatmosphereeffect.ui.model.EffectCatalog
import com.app.nosatmosphereeffect.ui.theme.AtmoEngineTheme

class AdvancedSettingsActivity : ComponentActivity() {

    private var subjectModelManager: SubjectModelManager? = null

    private val rotationOptions = listOf(
        "Every Lock (Instant)", "1 Minute", "15 Minutes",
        "30 Minutes", "1 Hour", "3 Hours", "6 Hours", "12 Hours", "24 Hours"
    )
    private val rotationValues = longArrayOf(0, 1, 15, 30, 60, 180, 360, 720, 1440)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val activeEffect = intent.getStringExtra("ACTIVE_EFFECT_TYPE") ?: "ORIGINAL"
        val isSamsung = intent.getBooleanExtra("IS_SAMSUNG", false)
        val isPlaylistMode = intent.getBooleanExtra("IS_PLAYLIST_MODE", false)

        val isHalftone = activeEffect.contains("HALFTONE")
        val isColorFill = activeEffect.contains("COLORFILL")
        val isNeon = activeEffect.contains("NEON")
        val isFrosted = activeEffect.contains("FROSTED")
        val isGlass = activeEffect.contains("GLASS")
        val isAtmosphere = AtmosphereGlassPolicy.supportsEffect(activeEffect)
        val isAtmosphereOriginal = AtmosphereClockPolicy.supportsEffect(activeEffect)
        val showNoiseSwitch = !isHalftone && !isColorFill && !isNeon && !isGlass
        val showBlob = activeEffect == "ORIGINAL" || activeEffect == "REVERSE"
        val usesSubjectModel = isNeon || isGlass || isHalftone || isAtmosphere

        val defaultDuration = EffectCatalog.recommendedDurationMillis(activeEffect)
        val defaultDimness = EffectCatalog.defaultDimness(activeEffect)
        val defaultPoll = if (isSamsung) 30000L else 50L
        val defaultDelay = if (isSamsung) 0L else 800L

        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val wpPrefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        val glassSettings = GlassEffectPreferences.readAndMigrate(prefs)
        val behaviorSettings = WallpaperBehaviorPreferences.read(this)

        val savedRotation = wpPrefs.getLong("rotation_interval_minutes", 0)
        val savedRotationIndex = rotationValues.indexOf(savedRotation).takeIf { it >= 0 } ?: 0

        val savedPoll = prefs.getLong("poll_interval", -1L)
        val savedDelay = prefs.getLong("lock_delay", -1L)
        val savedDuration = prefs.getLong("anim_duration", -1L)
        val savedNoiseScale = prefs.getFloat("noise_scale", -1f)
        val savedNoiseStrength = prefs.getFloat("noise_strength", -1f)
        val config = AdvancedConfig(
            activeEffectTitle = EffectCatalog.find(activeEffect).title,
            recommendedDurationMs = defaultDuration,
            showHalftone = isHalftone,
            showColorFill = isColorFill,
            showNeon = isNeon,
            showFrosted = isFrosted,
            showGlass = isGlass,
            showAtmosphereGlassToggle = isAtmosphere,
            atmosphereGlassEnabled = AtmosphereGlassPolicy.resolveEnabled(
                activeEffect,
                prefs.readBoolean(AtmosphereGlassPolicy.ENABLED_KEY, false)
            ),
            glassReverse = activeEffect == "GLASS_REVERSE",
            showClockToggle = isAtmosphereOriginal,
            clockEnabled = AtmosphereClockPolicy.resolveEnabled(
                activeEffect,
                prefs.readBoolean(AtmosphereClockPolicy.ENABLED_KEY, false)
            ),
            clockDepthEnabled = prefs.readBoolean(
                AtmosphereClockPolicy.DEPTH_KEY,
                AtmosphereClockPolicy.DEFAULT_DEPTH
            ),
            showNoiseSwitch = showNoiseSwitch,
            showBlob = showBlob,
            isPlaylistMode = isPlaylistMode,
            rotationOptions = rotationOptions,
            initialRotationIndex = savedRotationIndex,
            poll = if (savedPoll != -1L) savedPoll.toString() else defaultPoll.toString(),
            delay = if (savedDelay != -1L) savedDelay.toString() else defaultDelay.toString(),
            duration = if (savedDuration != -1L) savedDuration.toString() else defaultDuration.toString(),
            transitionsEnabled = behaviorSettings.transitionsEnabled,
            alwaysAppliedTarget = behaviorSettings.alwaysAppliedTarget,
            dimness = prefs.getFloat("dim_level", defaultDimness),
            blurStrength = prefs.getFloat("frosted_blur_radius", 200f),
            enableNoise = prefs.getBoolean("enable_noise", false),
            noiseScale = if (savedNoiseScale != -1f) savedNoiseScale.toString() else "2000.0",
            noiseStrength = if (savedNoiseStrength != -1f) savedNoiseStrength.toString() else "0.06",
            dotSize = prefs.getFloat("halftone_dot_size", 12.0f),
            grayscale = prefs.getBoolean("halftone_grayscale", false),
            originX = prefs.getFloat("origin_x", 0.5f),
            originY = prefs.getFloat("origin_y", 0.8f),
            saturation = prefs.getFloat("blob_saturation", 1.0f),
            contrast = prefs.getFloat("blob_contrast", 1.0f),
            neonSensitivity = prefs.getFloat("neon_sensitivity", 0.5f),
            neonLineWidth = prefs.getFloat("neon_line_width", 1.5f),
            glassLineCount = glassSettings.lineCount,
            glassLineThickness = glassSettings.lineThickness,
            glassTransitionStyle = glassSettings.transitionStyle,
            glassBackgroundOnly = glassSettings.backgroundOnly,
            halftoneBackgroundOnly = prefs.readBoolean(
                SubjectIsolationPolicy.HALFTONE_BACKGROUND_ONLY_KEY,
                false
            ),
            subjectSegmentationEnabled =
                prefs.readBoolean(CanvasSubjectSettings.ENABLED_KEY, false),
            scrollEnabled = WallpaperFitHelper.isScrollEnabled(this),
            rendererPreference = GraphicsBackendPreferences.read(this)
        )

        val initialSubjectModelState = SubjectModelState(
            phase = if (SubjectModelBuild.delivery == SubjectModelDelivery.BUNDLED_FOSS) {
                SubjectModelPhase.READY
            } else {
                SubjectModelPhase.CHECKING
            },
            progressPercent = if (
                SubjectModelBuild.delivery == SubjectModelDelivery.BUNDLED_FOSS
            ) 100 else null
        )
        if (usesSubjectModel) {
            subjectModelManager = SubjectModelManager(applicationContext)
        }

        setContent {
            AtmoEngineTheme {
                var subjectModelState by remember { mutableStateOf(initialSubjectModelState) }
                val updateSubjectModelState: (SubjectModelState) -> Unit = remember {
                    { state ->
                        runOnUiThread {
                            subjectModelState = state
                            when (state.phase) {
                                SubjectModelPhase.READY -> prefs.edit {
                                    putBoolean(CanvasSubjectSettings.MODEL_READY_KEY, true)
                                }
                                SubjectModelPhase.NOT_DOWNLOADED -> prefs.edit {
                                    putBoolean(CanvasSubjectSettings.MODEL_READY_KEY, false)
                                }
                                else -> Unit
                            }
                        }
                    }
                }
                LaunchedEffect(usesSubjectModel) {
                    if (usesSubjectModel) {
                        subjectModelManager?.checkAvailability(updateSubjectModelState)
                    }
                }
                AdvancedSettingsScreen(
                    config = config,
                    subjectModelDelivery = SubjectModelBuild.delivery,
                    subjectModelState = subjectModelState,
                    onDownloadSubjectModel = {
                        val manager = subjectModelManager
                            ?: SubjectModelManager(applicationContext).also {
                                subjectModelManager = it
                            }
                        manager.download(updateSubjectModelState)
                    },
                    onApply = { result ->
                        applySettings(
                            result,
                            prefs,
                            wpPrefs,
                            defaultPoll,
                            defaultDelay,
                            defaultDuration,
                            updateAtmosphereGlass = isAtmosphere,
                            updateAtmosphereClock = isAtmosphereOriginal
                        )
                    },
                    onReset = { resetSettings(prefs) },
                    onBack = { finish() }
                )
            }
        }
    }

    private fun applySettings(
        result: AdvancedResult,
        prefs: SharedPreferences,
        wpPrefs: SharedPreferences,
        defaultPoll: Long,
        defaultDelay: Long,
        defaultDuration: Long,
        updateAtmosphereGlass: Boolean,
        updateAtmosphereClock: Boolean
    ) {
        val poll = result.poll.toLongOrNull() ?: defaultPoll
        val delay = result.delay.toLongOrNull() ?: defaultDelay
        val duration = result.duration.toLongOrNull() ?: defaultDuration
        val noiseScale = result.noiseScale.toFloatOrNull() ?: 2000.0f
        val noiseStrength = result.noiseStrength.toFloatOrNull() ?: 0.06f
        val selectedRotationValue =
            rotationValues.getOrElse(result.rotationIndex) { rotationValues[0] }

        GraphicsBackendPreferences.write(this, result.rendererPreference)
        wpPrefs.edit { putLong("rotation_interval_minutes", selectedRotationValue) }
        WallpaperBehaviorPreferences.write(
            this,
            WallpaperBehaviorSettings(
                transitionsEnabled = result.transitionsEnabled,
                alwaysAppliedTarget = result.alwaysAppliedTarget
            )
        )

        prefs.edit {
            putLong("poll_interval", poll)
            putLong("lock_delay", delay)
            putLong("anim_duration", duration)
            putFloat("dim_level", result.dimness)
            putFloat("frosted_blur_radius", result.blurStrength)
            putBoolean("enable_noise", result.enableNoise)
            putFloat("noise_scale", noiseScale)
            putFloat("noise_strength", noiseStrength)
            putFloat("halftone_dot_size", result.dotSize)
            putBoolean("halftone_grayscale", result.grayscale)
            putFloat("blob_saturation", result.saturation)
            putFloat("blob_contrast", result.contrast)
            putFloat("origin_x", result.originX)
            putFloat("origin_y", result.originY)
            putFloat("neon_sensitivity", result.neonSensitivity)
            putFloat("neon_line_width", result.neonLineWidth)
            if (updateAtmosphereGlass) {
                putBoolean(
                    AtmosphereGlassPolicy.ENABLED_KEY,
                    result.atmosphereGlassEnabled
                )
            }
            if (updateAtmosphereClock) {
                putBoolean(AtmosphereClockPolicy.ENABLED_KEY, result.clockEnabled)
                putBoolean(
                    AtmosphereClockPolicy.DEPTH_KEY,
                    result.clockDepthEnabled
                )
            }
            putInt(
                GlassEffectPolicy.LINE_COUNT_KEY,
                GlassEffectPolicy.sanitizeLineCount(result.glassLineCount)
            )
            putFloat(
                GlassEffectPolicy.LINE_THICKNESS_KEY,
                GlassEffectPolicy.sanitizeLineThickness(result.glassLineThickness)
            )
            putString(
                GlassEffectPolicy.TRANSITION_STYLE_KEY,
                result.glassTransitionStyle.storedValue
            )
            putBoolean(
                GlassEffectPolicy.BACKGROUND_ONLY_KEY,
                result.glassBackgroundOnly
            )
            putBoolean(
                SubjectIsolationPolicy.HALFTONE_BACKGROUND_ONLY_KEY,
                result.halftoneBackgroundOnly
            )
            putBoolean(CanvasSubjectSettings.ENABLED_KEY, result.subjectSegmentationEnabled)
        }

        // Wallpaper scrolling lives in display_prefs (survives wallpaper changes).
        // Changing it alters the texture geometry, so the renderer must rebuild
        // its wallpaper texture — trigger the existing reload path.
        val scrollChanged = WallpaperFitHelper.isScrollEnabled(this) != result.scrollEnabled
        WallpaperFitHelper.setScrollEnabled(this, result.scrollEnabled)
        if (scrollChanged) {
            val reload = Intent("com.app.nosatmosphereeffect.RELOAD_WALLPAPER")
            reload.setPackage(packageName)
            sendBroadcast(reload)
        }
        sendUpdateBroadcast()
    }

    private fun resetSettings(prefs: SharedPreferences) {
        GraphicsBackendPreferences.reset(this)
        WallpaperBehaviorPreferences.reset(this)
        prefs.edit {
            remove("poll_interval")
            remove("lock_delay")
            remove("anim_duration")
            remove("dim_level")
            remove("frosted_blur_radius")
            remove("enable_noise")
            remove("noise_scale")
            remove("noise_strength")
            remove("halftone_dot_size")
            remove("halftone_grayscale")
            remove("blob_saturation")
            remove("blob_contrast")
            remove("origin_x")
            remove("origin_y")
            remove("neon_sensitivity")
            remove("neon_line_width")
            remove(AtmosphereGlassPolicy.ENABLED_KEY)
            AtmosphereClockPolicy.ALL_KEYS.forEach { remove(it) }
            remove(GlassEffectPolicy.LINE_COUNT_KEY)
            remove(GlassEffectPolicy.LINE_THICKNESS_KEY)
            remove(GlassEffectPolicy.TRANSITION_STYLE_KEY)
            remove(GlassEffectPolicy.BACKGROUND_ONLY_KEY)
            remove(GlassEffectPolicy.PRESET_VERSION_KEY)
            remove(SubjectIsolationPolicy.HALFTONE_BACKGROUND_ONLY_KEY)
            remove(CanvasSubjectSettings.ENABLED_KEY)
        }
        sendUpdateBroadcast()
    }

    override fun onDestroy() {
        subjectModelManager?.close()
        subjectModelManager = null
        super.onDestroy()
    }

    private fun sendUpdateBroadcast() {
        val intent = Intent("com.app.nosatmosphereeffect.UPDATE_CONFIG")
        intent.setPackage(packageName)
        sendBroadcast(intent)
        Toast.makeText(this, "Settings Applied!", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun SharedPreferences.readFloat(key: String, fallback: Float): Float {
        return try {
            getFloat(key, fallback)
        } catch (failure: ClassCastException) {
            Log.w(TAG, "Preference '$key' has the wrong type; using $fallback", failure)
            fallback
        }
    }

    private fun SharedPreferences.readBoolean(key: String, fallback: Boolean): Boolean {
        return try {
            getBoolean(key, fallback)
        } catch (failure: ClassCastException) {
            Log.w(TAG, "Preference '$key' has the wrong type; using $fallback", failure)
            fallback
        }
    }

    private companion object {
        const val TAG = "AdvancedSettings"
    }
}
