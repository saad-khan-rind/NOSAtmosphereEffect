package com.app.nosatmosphereeffect.activity

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.helper.PaletteSyncDiagnostics
import com.app.nosatmosphereeffect.helper.PaletteSyncTrace
import com.app.nosatmosphereeffect.helper.SystemColorSyncPreferences
import com.app.nosatmosphereeffect.helper.WallpaperColorExtractor
import com.app.nosatmosphereeffect.helper.WallpaperFitHelper
import com.app.nosatmosphereeffect.ui.model.PaletteDiagnosticLevel
import com.app.nosatmosphereeffect.ui.model.PaletteDiagnosticMessage
import com.app.nosatmosphereeffect.ui.model.PaletteDiagnostics
import com.app.nosatmosphereeffect.ui.screens.PaletteDiagnosticsScreen
import com.app.nosatmosphereeffect.ui.theme.AtmoEngineTheme
import java.io.File
import java.util.Locale
import org.json.JSONObject

class PaletteDiagnosticsActivity : ComponentActivity() {
    private var diagnostics by mutableStateOf(PaletteDiagnostics())
    private var loading by mutableStateOf(false)
    private var applying by mutableStateOf(false)
    private var syncColorsEnabled by mutableStateOf(false)
    private var readGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        syncColorsEnabled = SystemColorSyncPreferences.isEnabled(this)
        setContent {
            AtmoEngineTheme {
                PaletteDiagnosticsScreen(
                    deviceName = deviceDisplayName(),
                    diagnostics = diagnostics,
                    loading = loading,
                    applying = applying,
                    syncColorsEnabled = syncColorsEnabled,
                    onForceApply = { forceApplySystemPalette() },
                    onBack = { finish() }
                )
            }
        }
        loadDiagnostics()
    }

    override fun onResume() {
        super.onResume()
        syncColorsEnabled = SystemColorSyncPreferences.isEnabled(this)
        if (!loading && !applying) loadDiagnostics()
    }

    private fun forceApplySystemPalette() {
        if (!syncColorsEnabled || applying) return
        val systemColorsBefore = diagnostics.systemColors
        applying = true
        PaletteSyncDiagnostics.record(
            this,
            PaletteSyncDiagnostics.STAGE_FORCE_REQUESTED,
            getString(R.string.palette_force_requested, Build.MODEL),
            clearError = true
        )
        try {
            sendBroadcast(
                Intent("com.app.nosatmosphereeffect.UPDATE_CONFIG").setPackage(packageName)
            )
        } catch (failure: Throwable) {
            PaletteSyncDiagnostics.record(
                this,
                PaletteSyncDiagnostics.STAGE_FORCE_FAILED,
                getString(R.string.palette_force_request_failed, Build.MODEL),
                failure.toDiagnosticText()
            )
            loadDiagnostics(finishForceRequest = true)
            return
        }
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed) {
                loadDiagnostics(
                    forceSystemColorsBefore = systemColorsBefore,
                    finishForceRequest = true
                )
            }
        }, 1_800L)
    }

    private fun loadDiagnostics(
        forceSystemColorsBefore: List<Int>? = null,
        finishForceRequest: Boolean = false
    ) {
        val generation = ++readGeneration
        loading = true
        Thread {
            val readErrors = mutableListOf<DiagnosticReadError>()
            val extractedColors = try {
                requireNotNull(
                    WallpaperColorExtractor.extract(
                        File(filesDir, WallpaperFitHelper.ACTIVE_WALLPAPER_FILE)
                    )
                ) { getString(R.string.palette_decode_failed) }.toColorList()
            } catch (failure: Throwable) {
                readErrors += DiagnosticReadError(
                    getString(R.string.palette_extraction_failed),
                    failure.toDiagnosticText()
                )
                emptyList()
            }
            val wallpaperApiColors = try {
                WallpaperManager.getInstance(this)
                    .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                    .toColorList()
            } catch (failure: Throwable) {
                readErrors += DiagnosticReadError(
                    getString(R.string.palette_api_read_failed),
                    failure.toDiagnosticText()
                )
                emptyList()
            }
            val systemColors = try {
                val resources = packageManager.getResourcesForApplication("android")
                listOf(
                    resources.getColor(android.R.color.system_accent1_500, null),
                    resources.getColor(android.R.color.system_accent2_500, null),
                    resources.getColor(android.R.color.system_accent3_500, null)
                )
            } catch (failure: Throwable) {
                readErrors += DiagnosticReadError(
                    getString(R.string.palette_system_read_failed),
                    failure.toDiagnosticText()
                )
                emptyList()
            }
            val themeSelection = readSystemThemeSelection()
            themeSelection.error?.let { error ->
                readErrors += DiagnosticReadError(getString(R.string.palette_theme_read_failed), error)
            }
            val engineTrace = PaletteSyncDiagnostics.read(this)
            val result = PaletteDiagnostics(
                extractedColors = extractedColors,
                wallpaperApiColors = wallpaperApiColors,
                systemColors = systemColors,
                systemColorSource = themeSelection.source,
                systemSeedColor = themeSelection.seed,
                messages = buildMessages(
                    extractedColors = extractedColors,
                    wallpaperApiColors = wallpaperApiColors,
                    systemColors = systemColors,
                    themeSelection = themeSelection,
                    engineTrace = engineTrace,
                    readErrors = readErrors,
                    forceSystemColorsBefore = forceSystemColorsBefore
                )
            )
            runOnUiThread {
                if (generation == readGeneration && !isDestroyed) {
                    diagnostics = result
                    loading = false
                }
                if (finishForceRequest) applying = false
            }
        }.start()
    }

    private fun buildMessages(
        extractedColors: List<Int>,
        wallpaperApiColors: List<Int>,
        systemColors: List<Int>,
        themeSelection: SystemThemeSelection,
        engineTrace: PaletteSyncTrace?,
        readErrors: List<DiagnosticReadError>,
        forceSystemColorsBefore: List<Int>?
    ): List<PaletteDiagnosticMessage> {
        val messages = mutableListOf<PaletteDiagnosticMessage>()
        readErrors.forEach { failure ->
            messages += PaletteDiagnosticMessage(
                PaletteDiagnosticLevel.ERROR,
                failure.title,
                failure.detail
            )
        }
        engineTrace?.let { messages += it.toDiagnosticMessage() }

        if (readErrors.isEmpty() && engineTrace?.error == null) {
            messages += PaletteDiagnosticMessage(
                PaletteDiagnosticLevel.SUCCESS,
                getString(R.string.palette_no_errors),
                getString(R.string.palette_no_errors_detail, Build.MODEL)
            )
        }

        val apiAccepted = extractedColors.isNotEmpty() && extractedColors == wallpaperApiColors
        when {
            extractedColors.isEmpty() -> Unit
            wallpaperApiColors.isEmpty() -> messages += PaletteDiagnosticMessage(
                PaletteDiagnosticLevel.WARNING,
                getString(R.string.palette_api_empty),
                getString(R.string.palette_api_empty_detail)
            )
            apiAccepted -> messages += PaletteDiagnosticMessage(
                PaletteDiagnosticLevel.SUCCESS,
                getString(R.string.palette_api_accepted),
                getString(R.string.palette_api_accepted_detail)
            )
            else -> messages += PaletteDiagnosticMessage(
                PaletteDiagnosticLevel.WARNING,
                getString(R.string.palette_api_differs),
                getString(R.string.palette_api_differs_detail)
            )
        }

        if (apiAccepted && systemColors.isNotEmpty() && systemColors != extractedColors) {
            messages += PaletteDiagnosticMessage(
                PaletteDiagnosticLevel.WARNING,
                getString(R.string.palette_system_differs, deviceDisplayName()),
                getString(R.string.palette_system_differs_detail, deviceDisplayName())
            )
        }

        if (themeSelection.source == "preset") {
            val seed = themeSelection.seed?.let(::formatColor) ?: getString(R.string.palette_seed_unavailable)
            messages += PaletteDiagnosticMessage(
                PaletteDiagnosticLevel.WARNING,
                getString(R.string.palette_preset_source, deviceDisplayName()),
                getString(R.string.palette_preset_source_detail, deviceDisplayName(), seed)
            )
        }

        if (forceSystemColorsBefore != null && apiAccepted && systemColors.isNotEmpty()) {
            if (systemColors == forceSystemColorsBefore) {
                messages += PaletteDiagnosticMessage(
                    PaletteDiagnosticLevel.WARNING,
                    getString(R.string.palette_force_unchanged, deviceDisplayName()),
                    getString(R.string.palette_force_unchanged_detail, deviceDisplayName())
                )
            } else {
                messages += PaletteDiagnosticMessage(
                    PaletteDiagnosticLevel.SUCCESS,
                    getString(R.string.palette_force_changed),
                    getString(R.string.palette_force_changed_detail, deviceDisplayName())
                )
            }
        }
        return messages
    }

    private fun PaletteSyncTrace.toDiagnosticMessage(): PaletteDiagnosticMessage {
        val level = when (stage) {
            PaletteSyncDiagnostics.STAGE_PUBLISHED -> PaletteDiagnosticLevel.SUCCESS
            PaletteSyncDiagnostics.STAGE_EXTRACTION_FAILED,
            PaletteSyncDiagnostics.STAGE_MISSING_WALLPAPER,
            PaletteSyncDiagnostics.STAGE_PUBLISH_FAILED,
            PaletteSyncDiagnostics.STAGE_FORCE_FAILED -> PaletteDiagnosticLevel.ERROR
            PaletteSyncDiagnostics.STAGE_DISABLED -> PaletteDiagnosticLevel.WARNING
            else -> PaletteDiagnosticLevel.INFO
        }
        val title = when (stage) {
            PaletteSyncDiagnostics.STAGE_FORCE_REQUESTED -> getString(R.string.palette_stage_waiting)
            PaletteSyncDiagnostics.STAGE_REFRESH_QUEUED -> getString(R.string.palette_stage_queued)
            PaletteSyncDiagnostics.STAGE_EXTRACTING -> getString(R.string.palette_stage_extracting)
            PaletteSyncDiagnostics.STAGE_PUBLISHED -> getString(R.string.palette_stage_published)
            PaletteSyncDiagnostics.STAGE_DISABLED -> getString(R.string.palette_stage_disabled)
            PaletteSyncDiagnostics.STAGE_MISSING_WALLPAPER -> getString(R.string.palette_stage_missing)
            PaletteSyncDiagnostics.STAGE_EXTRACTION_FAILED -> getString(R.string.palette_stage_extraction_failed)
            PaletteSyncDiagnostics.STAGE_PUBLISH_FAILED -> getString(R.string.palette_stage_publish_failed)
            PaletteSyncDiagnostics.STAGE_FORCE_FAILED -> getString(R.string.palette_stage_force_failed)
            else -> getString(R.string.palette_stage_other, stage)
        }
        return PaletteDiagnosticMessage(
            level = level,
            title = title,
            detail = if (level == PaletteDiagnosticLevel.ERROR) error ?: detail else detail
        )
    }

    private fun readSystemThemeSelection(): SystemThemeSelection {
        return try {
            val raw = Settings.Secure.getString(
                contentResolver,
                "theme_customization_overlay_packages"
            ) ?: return SystemThemeSelection()
            val settings = JSONObject(raw)
            val source = settings.optString(
                "android.theme.customization.color_source"
            ).takeIf { it.isNotBlank() }
            val seed = settings.optString(
                "android.theme.customization.system_palette"
            ).removePrefix("#").toLongOrNull(16)?.toInt()
            SystemThemeSelection(source, seed)
        } catch (failure: Throwable) {
            SystemThemeSelection(error = failure.toDiagnosticText())
        }
    }

    private fun deviceDisplayName(): String {
        val source = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase(Locale.ROOT)
        return when {
            "samsung" in source -> "Samsung"
            "nothing" in source -> "Nothing"
            "infinix" in source -> "Infinix"
            "google" in source -> "Google"
            "oneplus" in source -> "OnePlus"
            "xiaomi" in source || "redmi" in source || "poco" in source -> "Xiaomi"
            else -> Build.MANUFACTURER.trim().replaceFirstChar { character ->
                if (character.isLowerCase()) character.titlecase(Locale.ROOT) else character.toString()
            }.ifBlank { getString(R.string.palette_device) }
        }
    }

    private fun WallpaperColors?.toColorList(): List<Int> {
        if (this == null) return emptyList()
        return listOfNotNull(
            primaryColor.toArgb(),
            secondaryColor?.toArgb(),
            tertiaryColor?.toArgb()
        )
    }

    private fun formatColor(color: Int): String =
        String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)

    private fun Throwable.toDiagnosticText(): String {
        val readable = message?.takeIf { it.isNotBlank() }
        return if (readable == null) javaClass.simpleName else "${javaClass.simpleName}: $readable"
    }

    private data class SystemThemeSelection(
        val source: String? = null,
        val seed: Int? = null,
        val error: String? = null
    )

    private data class DiagnosticReadError(val title: String, val detail: String)
}
