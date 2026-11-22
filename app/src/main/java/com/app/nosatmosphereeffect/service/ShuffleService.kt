package com.app.nosatmosphereeffect.service

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.app.nosatmosphereeffect.activity.WallpaperEffectServices
import com.app.nosatmosphereeffect.effect.ShuffleEffects
import com.app.nosatmosphereeffect.wallpaper.GLWallpaperService

/**
 * Shuffle: one wallpaper that shows a different effect each time the phone is
 * locked. Android only changes the live wallpaper through its own picker, so
 * the effects change inside this one: each effect's own service lends its
 * hooks (HostedEffect), and as the screen goes off every engine here drops
 * its renderer for one of the next effect. No clock: it is placed against
 * one effect's look.
 */
class ShuffleService : AnimatedEffectWallpaperService<ShuffleService.Slot>() {

    /** An engine's renderer: whichever effect's is in it now. */
    class Slot internal constructor(internal var renderer: HostedRenderer) {
        internal var engine: GLWallpaperService.GLEngine? = null
        internal var requestRender: () -> Unit = {}
        internal var visible = false
    }

    private val hostedEffects = HashMap<String, HostedEffect>()
    private val slots = HashSet<Slot>()
    private var shuffledAtMs = 0L

    /** The effect showing now; read lazily, as the context isn't there at construction. */
    private var current: HostedEffect? = null
    private fun showing(): HostedEffect =
        current ?: hosted(ShuffleEffects.current(applicationContext)).also { current = it }

    private fun hosted(effectId: String): HostedEffect = hostedEffects.getOrPut(effectId) {
        WallpaperEffectServices.newHostedEffect(effectId, this)
    }

    // The engine reads these as it needs them, so they follow the effect showing.
    override val effectId: String get() = showing().effectId
    override val wallpaperEffectId: String get() = ShuffleEffects.EFFECT_ID
    override val lockedProgress: Float get() = showing().lockedProgress
    override val unlockedProgress: Float get() = showing().unlockedProgress
    override val defaultAnimationDurationMs: Long get() = showing().defaultAnimationDurationMs
    override val initialProgress: Float? get() = showing().initialProgress
    override val sleepingProgress: Float get() = showing().sleepingProgress
    override val blurDrawerWhenHidden: Boolean get() = showing().blurDrawerWhenHidden

    override fun createEffectRenderer(): Slot = Slot(showing().newRenderer()).also(slots::add)

    override fun attachEffectRenderer(engine: GLWallpaperService.GLEngine, renderer: Slot) {
        renderer.engine = engine
        renderer.renderer.attach(engine)
    }

    override fun configureRenderer(renderer: Slot, preferences: SharedPreferences) =
        renderer.renderer.configure(preferences)

    override fun setEffectProgress(renderer: Slot, progress: Float) = renderer.renderer.setProgress(progress)

    override fun setFixedEffectState(renderer: Slot, effectApplied: Boolean) =
        renderer.renderer.setFixedState(effectApplied)

    override fun setDrawerBlurred(renderer: Slot, blurred: Boolean) = renderer.renderer.setDrawerBlurred(blurred)

    override fun onRendererAttached(renderer: Slot, requestRender: () -> Unit) {
        renderer.requestRender = requestRender
        renderer.renderer.onAttached(requestRender)
    }

    override fun onEngineVisibilityChanged(renderer: Slot?, visible: Boolean) {
        renderer ?: return
        renderer.visible = visible
        renderer.renderer.onVisibilityChanged(visible)
    }

    override fun reloadRenderer(renderer: Slot) = renderer.renderer.reload()

    override fun queuePlaylistTransition(renderer: Slot, bitmap: Bitmap) =
        renderer.renderer.queuePlaylistTransition(bitmap)

    override fun releaseRenderer(renderer: Slot) {
        slots -= renderer
        renderer.engine = null
        renderer.renderer.release()
    }

    /** As the screen goes off: the effect showing moves its own images on, then gives way to the next. */
    override fun rotateOwnImages(renderer: Slot) {
        renderer.renderer.rotateOwnImages()
        // Every engine here hears the same screen going off: shuffle once for all of them.
        val now = SystemClock.elapsedRealtime()
        if (now - shuffledAtMs < SHUFFLE_ONCE_MS) return
        shuffledAtMs = now
        shuffle()
    }

    private fun shuffle() {
        val previous = showing()
        val next = hosted(ShuffleEffects.next(applicationContext))
        if (next === previous) return
        current = next
        val swapped = slots.toList().all { slot -> swap(slot, next) }
        if (!swapped) {
            // The next effect couldn't start: stay on the one that was showing.
            Log.w(TAG, "Unable to shuffle to ${next.effectId}; keeping ${previous.effectId}")
            current = previous
            slots.toList().forEach { slot -> swap(slot, previous) }
        }
        notifyEffectChanged()
    }

    /** Puts a renderer of [effect] in [slot] in place of the one there. False if it couldn't. */
    private fun swap(slot: Slot, effect: HostedEffect): Boolean {
        val engine = slot.engine ?: return true
        return runCatching {
            val fresh = effect.newRenderer()
            // The old host leaves the surface before the new one takes it.
            engine.removeRenderHost()
            runCatching { slot.renderer.release() }
                .onFailure { Log.w(TAG, "Unable to release the previous effect's renderer", it) }
            slot.renderer = fresh
            effect.initialProgress?.let(fresh::setProgress)
            fresh.configure(getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE))
            fresh.onAttached(slot.requestRender)
            fresh.attach(engine)
            fresh.onVisibilityChanged(slot.visible)
        }.onFailure { Log.e(TAG, "Unable to start ${effect.effectId} in Shuffle", it) }.isSuccess
    }

    private companion object {
        const val TAG = "ShuffleService"
        const val SHUFFLE_ONCE_MS = 1_000L
    }
}
