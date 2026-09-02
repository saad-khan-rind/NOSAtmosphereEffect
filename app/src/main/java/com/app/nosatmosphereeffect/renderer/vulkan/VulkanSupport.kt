package com.app.nosatmosphereeffect.renderer.vulkan

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import com.app.nosatmosphereeffect.renderer.backend.BackendReselectionAction
import com.app.nosatmosphereeffect.renderer.backend.BackendReselectionPolicy
import com.app.nosatmosphereeffect.renderer.backend.GraphicsBackend
import com.app.nosatmosphereeffect.renderer.backend.GraphicsBackendPreference
import com.app.nosatmosphereeffect.renderer.backend.GraphicsBackendPreferences
import com.app.nosatmosphereeffect.renderer.backend.GraphicsBackendSelector
import com.app.nosatmosphereeffect.renderer.status.RendererRuntimeSession
import com.app.nosatmosphereeffect.renderer.status.RendererRuntimeStatusRepository
import com.app.nosatmosphereeffect.renderer.status.VulkanDeviceCapability
import java.util.Locale
import com.app.nosatmosphereeffect.helper.RendererDiagnosticsLog

internal object VulkanSupport {
    private const val TAG = "VulkanSupport"
    private const val VULKAN_1_1 = 0x00401000

    @Volatile
    private var cachedNativeProbe: Int? = null

    fun selectBackend(context: Context, effectId: String): VulkanBackendSelection {
        return publishSelection(context, effectId, resolveBackend(context, effectId))
    }

    fun selectPreviewBackend(context: Context, effectId: String): GraphicsBackend {
        return resolveBackend(context, effectId).backend
    }

    fun configuredPreference(context: Context): GraphicsBackendPreference {
        return GraphicsBackendPreferences.read(context)
    }

    fun resolveBackendChange(
        context: Context,
        effectId: String,
        appliedPreference: GraphicsBackendPreference,
        activeBackend: GraphicsBackend
    ): VulkanBackendChange {
        val requestedPreference = configuredPreference(context)
        if (requestedPreference == appliedPreference) {
            return VulkanBackendChange.None
        }
        val resolution = resolveBackend(
            context = context,
            effectId = effectId,
            preference = requestedPreference
        )
        return when (
            BackendReselectionPolicy.decide(
                appliedPreference = appliedPreference,
                requestedPreference = requestedPreference,
                activeBackend = activeBackend,
                resolvedBackend = resolution.backend
            )
        ) {
            BackendReselectionAction.NONE -> VulkanBackendChange.None
            BackendReselectionAction.REFRESH_ACTIVE_SESSION ->
                VulkanBackendChange.PreferenceOnly(resolution)
            BackendReselectionAction.SWAP_BACKEND ->
                VulkanBackendChange.Swap(resolution)
        }
    }

    fun resolveBackend(
        context: Context,
        effectId: String,
        preference: GraphicsBackendPreference = configuredPreference(context)
    ): VulkanBackendResolution {
        if (preference == GraphicsBackendPreference.OPENGL_ES) {
            return VulkanBackendResolution(
                preference = preference,
                backend = GraphicsBackend.OPENGL_ES,
                capability = VulkanDeviceCapability.UNKNOWN,
                probedVersion = null,
                fallbackReason = null
            )
        }
        val featureQuery = runCatching {
            context.packageManager.hasSystemFeature(
                PackageManager.FEATURE_VULKAN_HARDWARE_VERSION,
                VULKAN_1_1
            )
        }
        val hasVulkan11 = featureQuery.getOrElse { failure ->
            Log.w(TAG, "Unable to query Vulkan system features", failure)
            false
        }
        val probedVersion = if (hasVulkan11) probeNativeRuntime() else null
        val blockedAfterFailure = runCatching {
            VulkanFailureStore.isBlocked(context, effectId)
        }.getOrElse { failure ->
            Log.w(TAG, "Unable to read the Vulkan failure state", failure)
            true
        }
        val selectedBackend = GraphicsBackendSelector.select(
            effectId = effectId,
            hasVulkan11 = hasVulkan11,
            nativeProbePassed = probedVersion != null,
            blockedAfterFailure = blockedAfterFailure,
            preference = preference
        )
        val capability = when {
            featureQuery.isFailure -> VulkanDeviceCapability.UNKNOWN
            !hasVulkan11 -> VulkanDeviceCapability.UNSUPPORTED
            else -> VulkanDeviceCapability.SUPPORTED
        }
        val fallbackReason = if (selectedBackend == GraphicsBackend.VULKAN) {
            null
        } else {
            when {
                featureQuery.isFailure -> "Vulkan capability query failed"
                !hasVulkan11 -> "Vulkan 1.1 is not advertised by this device"
                probedVersion == null -> "No compatible Vulkan runtime was found"
                blockedAfterFailure -> "Vulkan was disabled after a previous driver failure"
                else -> "This effect does not have a Vulkan renderer"
            }
        }
        RendererDiagnosticsLog.record(
            context,
            "backend-select",
            "$effectId -> $selectedBackend " +
                "(preference=$preference, vulkan1.1=$hasVulkan11, " +
                "probe=${probedVersion ?: "none"}, blocked=$blockedAfterFailure)" +
                (if (fallbackReason != null) " reason=$fallbackReason" else "") +
                // The stored reason is the ORIGINAL failure. Without it a
                // blocked line only says "something went wrong once", which
                // is exactly as useless as it sounds.
                (if (blockedAfterFailure) {
                    " recorded=" +
                        (VulkanFailureStore.blockedReason(context, effectId) ?: "unknown")
                } else {
                    ""
                })
        )
        return VulkanBackendResolution(
            preference = preference,
            backend = selectedBackend,
            capability = capability,
            probedVersion = probedVersion,
            fallbackReason = fallbackReason
        )
    }

    fun publishSelection(
        context: Context,
        effectId: String,
        resolution: VulkanBackendResolution
    ): VulkanBackendSelection {
        val runtimeSession = runCatching {
            RendererRuntimeStatusRepository.recordSelection(
                context = context,
                effectId = effectId,
                selectedBackend = resolution.backend,
                vulkanCapability = resolution.capability,
                probedVulkanApiVersion = resolution.probedVersion?.encoded,
                fallbackReason = resolution.fallbackReason
            )
        }.onFailure { failure ->
            Log.w(TAG, "Unable to publish the renderer selection", failure)
        }.getOrNull()
        return VulkanBackendSelection(
            preference = resolution.preference,
            backend = resolution.backend,
            runtimeSession = runtimeSession
        )
    }

    fun publishActiveSelection(
        context: Context,
        effectId: String,
        resolution: VulkanBackendResolution,
        activeVulkanApiVersion: Int?
    ): VulkanBackendSelection {
        val selection = publishSelection(context, effectId, resolution)
        val session = selection.runtimeSession ?: return selection
        runCatching {
            when (resolution.backend) {
                GraphicsBackend.VULKAN -> {
                    if (activeVulkanApiVersion != null) {
                        RendererRuntimeStatusRepository.recordVulkanActive(
                            context = context,
                            session = session,
                            packedVersion = activeVulkanApiVersion
                        )
                    } else {
                        RendererRuntimeStatusRepository.recordVulkanInitializing(
                            context,
                            session
                        )
                    }
                }
                GraphicsBackend.OPENGL_ES ->
                    RendererRuntimeStatusRepository.recordOpenGlActive(
                        context = context,
                        session = session,
                        reason = resolution.fallbackReason
                    )
            }
        }.onFailure { failure ->
            Log.w(TAG, "Unable to publish the active renderer backend", failure)
        }
        return selection
    }

    /**
     * Clears every recorded Vulkan failure so the next selection tries Vulkan
     * again. Exposed through the diagnostics screen: without it, a single
     * failure pins a device to OpenGL ES for every build sharing a
     * versionCode, which made the fallback impossible to re-test.
     */
    fun clearRecordedFailures(context: Context) {
        VulkanFailureStore.clearAll(context)
        RendererDiagnosticsLog.record(
            context,
            "vulkan-blocklist",
            "Recorded failures cleared by the user; Vulkan will be retried"
        )
    }

    fun recordFailure(context: Context, effectId: String, reason: String) {
        RendererDiagnosticsLog.record(
            context,
            "vulkan-blocklist",
            "$effectId blocked for this build: $reason"
        )
        VulkanFailureStore.record(context, effectId, reason)
    }

    fun probedApiVersion(): VulkanApiVersion? {
        return probeNativeRuntime()
    }

    private fun probeNativeRuntime(): VulkanApiVersion? {
        cachedNativeProbe?.let { return VulkanApiVersion.fromEncoded(it) }
        return synchronized(this) {
            val cached = cachedNativeProbe
            if (cached != null) {
                return@synchronized VulkanApiVersion.fromEncoded(cached)
            }
            run {
                val encoded = if (VulkanNative.libraryLoaded) {
                    runCatching {
                        VulkanNative.nativeProbe()
                    }.getOrElse { failure ->
                        Log.w(TAG, "The native Vulkan probe failed", failure)
                        0
                    }
                } else {
                    0
                }
                cachedNativeProbe = encoded
                VulkanApiVersion.fromEncoded(encoded).also { version ->
                    if (encoded != 0 && version == null) {
                        Log.w(TAG, "The native Vulkan probe returned an unsupported API version")
                    }
                }
            }
        }
    }
}

internal data class VulkanBackendSelection(
    val preference: GraphicsBackendPreference,
    val backend: GraphicsBackend,
    val runtimeSession: RendererRuntimeSession?
)

internal data class VulkanBackendResolution(
    val preference: GraphicsBackendPreference,
    val backend: GraphicsBackend,
    val capability: VulkanDeviceCapability,
    val probedVersion: VulkanApiVersion?,
    val fallbackReason: String?
)

internal sealed interface VulkanBackendChange {
    data object None : VulkanBackendChange

    data class PreferenceOnly(
        val resolution: VulkanBackendResolution
    ) : VulkanBackendChange

    data class Swap(
        val resolution: VulkanBackendResolution
    ) : VulkanBackendChange
}

private object VulkanFailureStore {
    private const val PREFS_NAME = "graphics_backend_prefs"
    private const val LEGACY_FAILURE_ID_KEY = "vulkan_failure_id"
    private const val FAILURE_ID_PREFIX = "vulkan_failure_id_"
    private const val FAILURE_REASON_PREFIX = "vulkan_failure_reason_"
    private const val FAILURE_WALLPAPER_PREFIX = "vulkan_failure_wallpaper_"

    /**
     * Bumped whenever the Vulkan path changes enough that an old recorded
     * failure says nothing about the new code.
     *
     * The failure id is (fingerprint | versionCode), so a device that failed
     * once stays on OpenGL ES for every build carrying the same versionCode
     * — which is exactly what happened while the clock work was in progress:
     * the first broken build blocklisted Vulkan, and every fix afterwards was
     * never given a chance to run. Folding a schema number in retires those
     * records once, without weakening the mechanism for real driver faults.
     *
     * 2: depth clock — new clock sampler binding, uniform moved to binding 4.
     * 3: retires records written by the 7.2.3 development builds, which all
     *    shared schema 2 and therefore kept re-blocking each other.
     */
    private const val RENDERER_SCHEMA = 3

    fun isBlocked(context: Context, effectId: String): Boolean {
        val preferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentFailureId = failureId(context)
        val scopedFailureId = preferences.getString(failureIdKey(effectId), null)
        val failureReason = preferences.getString(failureReasonKey(effectId), null)
        // A recorded failure describes this build AND the image that was
        // loaded when it happened. Setting a new wallpaper changes the
        // textures, their dimensions, and the surface lifecycle, so an old
        // failure says nothing about the new one — retry rather than making
        // the user reinstall or dig through settings to un-stick it.
        val recordedWallpaper = preferences.getLong(failureWallpaperKey(effectId), -1L)
        val wallpaperChanged = scopedFailureId != null &&
            recordedWallpaper != activeWallpaperStamp(context)
        val repaired = VulkanFailurePolicy.shouldClearObsoleteAtmosphereStateFailure(
            effectId = effectId,
            currentFailureId = currentFailureId,
            scopedFailureId = scopedFailureId,
            failureReason = failureReason
        )
        if (repaired || wallpaperChanged) {
            preferences.edit {
                remove(failureIdKey(effectId))
                remove(failureReasonKey(effectId))
                remove(failureWallpaperKey(effectId))
            }
        }
        return VulkanFailurePolicy.isBlocked(
            effectId = effectId,
            currentFailureId = currentFailureId,
            scopedFailureId = if (repaired || wallpaperChanged) null else scopedFailureId,
            legacyFailureId = preferences.getString(LEGACY_FAILURE_ID_KEY, null)
        )
    }

    /** The reason recorded for the currently active block, if any. */
    fun blockedReason(context: Context, effectId: String): String? {
        val preferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return preferences.getString(failureReasonKey(effectId), null)
    }

    /** Drops every recorded failure. Backs the diagnostics screen's retry. */
    fun clearAll(context: Context) {
        val preferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val doomed = preferences.all.keys.filter {
            it.startsWith(FAILURE_ID_PREFIX) ||
                it.startsWith(FAILURE_REASON_PREFIX) ||
                it.startsWith(FAILURE_WALLPAPER_PREFIX) ||
                it == LEGACY_FAILURE_ID_KEY
        }
        preferences.edit { doomed.forEach { remove(it) } }
    }

    fun record(context: Context, effectId: String, reason: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(failureIdKey(effectId), failureId(context))
            putString(failureReasonKey(effectId), reason.take(500))
            putLong(failureWallpaperKey(effectId), activeWallpaperStamp(context))
        }
    }

    private fun failureWallpaperKey(effectId: String): String {
        return FAILURE_WALLPAPER_PREFIX + VulkanFailurePolicy.normalizedEffectId(effectId)
    }

    /** Modification time of the applied image; -1 when there isn't one. */
    private fun activeWallpaperStamp(context: Context): Long {
        val file = java.io.File(context.applicationContext.filesDir, "wallpaper.jpg")
        return if (file.exists()) file.lastModified() else -1L
    }

    private fun failureIdKey(effectId: String): String {
        return FAILURE_ID_PREFIX + VulkanFailurePolicy.normalizedEffectId(effectId)
    }

    private fun failureReasonKey(effectId: String): String {
        return FAILURE_REASON_PREFIX + VulkanFailurePolicy.normalizedEffectId(effectId)
    }

    private fun failureId(context: Context): String {
        val versionCode = runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .longVersionCode
        }.getOrDefault(0L)
        return "${Build.FINGERPRINT}|$versionCode|s$RENDERER_SCHEMA"
    }

}

internal object VulkanFailurePolicy {
    private val legacyColorFillEffects = setOf(
        "COLORFILL",
        "COLORFILL_REVERSE"
    )
    private val obsoleteAtmosphereStateFailures = mapOf(
        "ORIGINAL" to "The Vulkan Atmosphere state could not be updated",
        "REVERSE" to "The Vulkan Reverse Atmosphere state could not be updated"
    )

    fun isBlocked(
        effectId: String,
        currentFailureId: String,
        scopedFailureId: String?,
        legacyFailureId: String?
    ): Boolean {
        if (scopedFailureId == currentFailureId) return true
        return normalizedEffectId(effectId) in legacyColorFillEffects &&
            legacyFailureId == currentFailureId
    }

    fun shouldClearObsoleteAtmosphereStateFailure(
        effectId: String,
        currentFailureId: String,
        scopedFailureId: String?,
        failureReason: String?
    ): Boolean {
        if (scopedFailureId != currentFailureId) return false
        return obsoleteAtmosphereStateFailures[normalizedEffectId(effectId)] == failureReason
    }

    fun normalizedEffectId(effectId: String): String {
        val normalized = effectId.trim()
            .uppercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() || it == '_' }
        return normalized.ifBlank { "UNKNOWN" }
    }
}
