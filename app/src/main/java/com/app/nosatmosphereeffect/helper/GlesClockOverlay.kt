package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.opengl.GLES30

/**
 * The GLES half of the wallpaper clock, packaged so an effect renderer gains
 * the whole feature by owning one field and calling [draw] once per frame.
 *
 * AtmosphereRenderer predates this and keeps its own inlined copy; everything
 * else goes through here. The rules that matter — when the entry animation is
 * allowed to start, which uniforms have to be written even in the disabled
 * case, which texture unit is active when the face is uploaded — are subtle
 * enough that they were worth having in exactly one place before being
 * repeated across six more renderers.
 *
 * ## Threading
 *
 * [applyState], [beginEntry] and [onTimeChanged] are called from the wallpaper
 * engine's thread; everything that touches GL or the face renderer happens
 * inside [draw] on the render thread. That is why the incoming state is staged
 * in a volatile rather than pushed straight into [ClockTextureProvider]: the
 * style and seconds setters recycle the face bitmap, which is a render-thread
 * resource.
 */
class GlesClockOverlay(
    context: Context,
    /** GL_TEXTUREn constant this renderer has spare for the clock. */
    private val textureUnit: Int,
    /** The same unit as a plain index, for the sampler uniform. */
    private val textureUnitIndex: Int
) {
    private val provider = ClockTextureProvider(context)

    /**
     * Asks the host surface for another frame. The renderers are
     * RENDERMODE_WHEN_DIRTY, so without this the digit animation would stall
     * part-way through whenever nothing else happened to trigger a draw.
     */
    @Volatile var onAnimationFrameRequested: (() -> Unit)? = null

    @Volatile private var pendingState: ClockOverlayState? = ClockOverlayState()
    @Volatile private var appliedState: ClockOverlayState = ClockOverlayState()
    @Volatile private var pendingFormatRefresh = false
    @Volatile private var pendingEntry = false

    /** The most recently supplied settings, whether or not GL has seen them. */
    val state: ClockOverlayState
        get() = pendingState ?: appliedState

    val enabled: Boolean
        get() = state.enabled

    fun applyState(next: ClockOverlayState) {
        pendingState = next.sanitized()
        if (next.enabled) onAnimationFrameRequested?.invoke()
    }

    /**
     * Re-reads the system 12/24-hour setting and forces the next frame to
     * redraw the face. Safe from any thread — the work happens on the next
     * draw.
     */
    fun onTimeChanged() {
        pendingFormatRefresh = true
        onAnimationFrameRequested?.invoke()
    }

    /**
     * Plays the entry animation when the clock next becomes visible. Held as a
     * flag rather than started here because the wallpaper engine's visibility
     * callback does not arrive on the render thread.
     */
    fun beginEntry() {
        pendingEntry = true
        onAnimationFrameRequested?.invoke()
    }

    /**
     * Call from onSurfaceCreated after an EGL context loss: the old texture id
     * belonged to the destroyed context, so it is forgotten rather than
     * deleted.
     */
    fun resetForNewContext() {
        provider.resetForNewContext()
        // Face settings live on the provider, which survived, but the uploaded
        // pixels did not — force the next draw to re-apply and re-upload.
        pendingState = state
    }

    fun release() {
        onAnimationFrameRequested = null
        provider.release()
    }

    /**
     * Writes the clock uniforms for this frame into [programId] and, when
     * there is something to draw, binds the face texture.
     *
     * [progress] is the effect's own shader progress; [screenAspect] is
     * width/height of the surface. [subjectMaskAvailable] should be true only
     * when a real subject mask is bound for this frame — the depth effect is
     * silently dropped otherwise rather than compositing against whatever
     * happens to be in the mask slot.
     *
     * Every uniform is written on every frame, including the disabled case. An
     * earlier version of the Atmosphere path only wrote the geometry inside
     * the enabled branch, so a frame where the texture was not ready left the
     * previous frame's rectangle behind in the program.
     */
    fun draw(
        programId: Int,
        progress: Float,
        screenAspect: Float,
        subjectMaskAvailable: Boolean = false
    ) {
        if (programId == 0) return

        pendingState?.let { next ->
            pendingState = null
            appliedState = next
            provider.style = next.style
            provider.showSeconds = next.showSeconds
            provider.animateDigits = next.animate
            provider.animateEntry = next.animate
            provider.color = next.color
            provider.hourFormatOverride = next.hourFormatOverride
        }
        if (pendingFormatRefresh) {
            pendingFormatRefresh = false
            provider.refreshClockFormatPreference()
        }

        val current = appliedState
        val opacity = current.effectiveOpacity(progress)
        // Held until the clock would actually be on screen rather than
        // consumed on the first frame after becoming visible: waking onto the
        // home screen with a lock-screen clock would otherwise spend the entry
        // animation behind a zero opacity, and the user would see a clock that
        // was simply already there when the transition brought it back.
        if (pendingEntry && opacity > 0f) {
            pendingEntry = false
            provider.beginEntry()
        }

        val ready = opacity > 0f &&
            provider.ensureUpToDate(textureUnit) &&
            provider.textureId != 0

        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(programId, "uClockEnabled"),
            if (ready) 1f else 0f
        )
        if (!ready) {
            GLES30.glUniform1f(
                GLES30.glGetUniformLocation(programId, "uClockOpacity"),
                0f
            )
            GLES30.glUniform1f(
                GLES30.glGetUniformLocation(programId, "uClockDepth"),
                0f
            )
            GLES30.glUniform4f(
                GLES30.glGetUniformLocation(programId, "uClockRect"),
                0f,
                0f,
                1f,
                1f
            )
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            return
        }

        // Height is a fraction of screen height; width follows from the face's
        // own pixel aspect, divided by the screen aspect so glyphs are not
        // stretched.
        val heightUv = current.height
        val safeAspect = if (screenAspect.isFinite() && screenAspect > 0f) {
            screenAspect
        } else {
            1f
        }
        val widthUv = heightUv * provider.aspectRatio / safeAspect
        GLES30.glUniform4f(
            GLES30.glGetUniformLocation(programId, "uClockRect"),
            current.centerX - widthUv / 2f,
            current.top,
            widthUv,
            heightUv
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(programId, "uClockOpacity"),
            opacity
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(programId, "uClockDepth"),
            if (current.depthEnabled && subjectMaskAvailable) 1f else 0f
        )
        GLES30.glActiveTexture(textureUnit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, provider.textureId)
        GLES30.glUniform1i(
            GLES30.glGetUniformLocation(programId, "uClockTexture"),
            textureUnitIndex
        )
        // Leave the active unit where the rest of the frame expects it.
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        if (provider.isAnimating()) {
            onAnimationFrameRequested?.invoke()
        }
    }
}
