package com.app.nosatmosphereeffect.renderer.zoom

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.util.Log
import com.app.nosatmosphereeffect.clock.ClockOverlayState
import com.app.nosatmosphereeffect.clock.GlesClockOverlay
import com.app.nosatmosphereeffect.renderer.backend.EffectOpenGlRenderer
import com.app.nosatmosphereeffect.renderer.gles.FullScreenQuad
import com.app.nosatmosphereeffect.renderer.gles.GlesProgram
import com.app.nosatmosphereeffect.renderer.gles.SceneTextures
import com.app.nosatmosphereeffect.renderer.gles.WallpaperImages
import com.app.nosatmosphereeffect.wallpaper.WallpaperScrollRenderer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Zoom on OpenGL ES: the photo zooming in on unlock (Zoom In) or out (Zoom Out). */
class ZoomRenderer(
    private val context: Context,
    private val isReverse: Boolean = false,
    previewSource: (() -> Bitmap?)? = null
) : EffectOpenGlRenderer, WallpaperScrollRenderer {

    @Volatile var onSubjectMaskUpdated: (() -> Unit)? = null
    @Volatile var onRenderRetryRequested: (() -> Unit)? = null

    /** Asks the host surface for another frame; used by the clock animation. */
    @Volatile var onAnimationFrameRequested: (() -> Unit)? = null
        set(value) {
            field = value
            clockOverlay.onAnimationFrameRequested = value
        }

    @Volatile var blurStrength: Float = 0f
    @Volatile var dimLevel: Float = 0f

    @Volatile private var scrollOffsetX: Float = 0.5f

    private val images = WallpaperImages(context, "Zoom", previewSource) { onSubjectMaskUpdated?.invoke() }

    /**
     * The wallpaper clock. The photo zooms under it, the clock stays put (see photoZoom below).
     * Units 0 and 1 carry the photo and the subject mask, so the clock takes 2.
     */
    private val clockOverlay = GlesClockOverlay(context, GLES30.GL_TEXTURE2, 2)

    init {
        images.sceneSink = clockOverlay.sceneSink
    }

    private var quad: FullScreenQuad? = null
    private var programId = 0
    private var aspectRatio = 1f
    private var renderFailureLogged = false
    private var renderRetryCount = 0

    override fun setWallpaperOffset(xOffset: Float) {
        scrollOffsetX = xOffset.coerceIn(0f, 1f)
    }

    override fun queuePlaylistTransition(bitmap: Bitmap) = images.queuePlaylistTransition(bitmap)

    override fun reloadTexture() = images.reload()

    fun applyClockState(state: ClockOverlayState) {
        clockOverlay.applyState(state)
        refreshSubjectMaskNeed()
    }

    override fun beginClockEntry() = clockOverlay.beginEntry()

    override fun onClockTimeChanged() = clockOverlay.onTimeChanged()

    private fun refreshSubjectMaskNeed() {
        // Only on turning on does this reload (see WallpaperImages.wantSubjectMask): it
        // runs on every clock push, and reloading there would drop the mask each time.
        images.wantSubjectMask(clockOverlay.state.needsSubjectMask())
    }

    override fun release() {
        onSubjectMaskUpdated = null
        onRenderRetryRequested = null
        onAnimationFrameRequested = null
        clockOverlay.release()
        images.release()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        quad = FullScreenQuad()
        programId = 0
        renderFailureLogged = false
        renderRetryCount = 0
        try {
            programId = GlesProgram.link(
                "Zoom",
                GlesProgram.readAsset(context, "shaders/zoom/zoom.vert"),
                GlesProgram.readAsset(context, "shaders/zoom/zoom.frag")
            )
        } catch (failure: Exception) {
            Log.e(TAG, "Unable to initialize the Zoom renderer", failure)
        }
        images.onSurfaceCreated()
        // The clock's texture id belonged to the destroyed context.
        clockOverlay.resetForNewContext()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        val safeWidth = width.coerceAtLeast(0)
        val safeHeight = height.coerceAtLeast(0)
        GLES30.glViewport(0, 0, safeWidth, safeHeight)
        aspectRatio = if (safeHeight > 0) safeWidth.toFloat() / safeHeight else 1f
        // A new size (fold/unfold, rotation): the wallpaper is fitted again, not stretched.
        images.onSurfaceChanged(safeWidth, safeHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        val quad = quad
        if (programId == 0 || quad == null) {
            clearFrame()
            return
        }
        try {
            images.prepareFrame()
            if (!images.current.isValid()) {
                clearFrame()
                return
            }
            draw(quad)
            renderFailureLogged = false
            renderRetryCount = 0
        } catch (failure: Exception) {
            if (!renderFailureLogged) {
                Log.e(TAG, "Unable to draw the Zoom wallpaper", failure)
                renderFailureLogged = true
            }
            images.onFrameFailed()
            clearFrame()
            if (renderRetryCount < MAX_RENDER_RETRIES) {
                renderRetryCount++
                onRenderRetryRequested?.invoke()
            }
        }
    }

    private fun draw(quad: FullScreenQuad) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(programId)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uAspectRatio"), aspectRatio)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uBlurStrength"), blurStrength)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uDimLevel"), dimLevel)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uScrollOffsetX"), scrollOffsetX)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uScrollWindowX"), images.windowX)

        val set = images.current
        SceneTextures.bindPhoto(set.photoId, 0, GLES30.glGetUniformLocation(programId, "uTextureSharp"))
        SceneTextures.bindSubjectMask(set.maskId, 1, GLES30.glGetUniformLocation(programId, "uSubjectMask"))
        clockOverlay.draw(
            scrollOffsetX = scrollOffsetX,
            scrollWindowX = images.windowX,
            programId = programId,
            progress = blurStrength,
            screenAspect = aspectRatio,
            subjectMaskAvailable = images.maskReady,
            photoZoom = ZoomRenderState.photoZoom(isReverse, blurStrength)
        )
        quad.draw(programId)
    }

    private fun clearFrame() {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    private companion object {
        const val TAG = "ZoomRenderer"
        const val MAX_RENDER_RETRIES = 3
    }
}
