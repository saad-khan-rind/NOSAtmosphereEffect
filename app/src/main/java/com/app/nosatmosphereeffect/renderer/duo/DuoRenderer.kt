package com.app.nosatmosphereeffect.renderer.duo

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.util.Log
import com.app.nosatmosphereeffect.renderer.backend.EffectOpenGlRenderer
import com.app.nosatmosphereeffect.renderer.gles.FullScreenQuad
import com.app.nosatmosphereeffect.renderer.gles.GlesProgram
import com.app.nosatmosphereeffect.renderer.gles.SceneTextures
import com.app.nosatmosphereeffect.renderer.gles.WallpaperImages
import com.app.nosatmosphereeffect.transition.WakeTransition
import com.app.nosatmosphereeffect.wallpaper.WallpaperScrollRenderer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Duo on OpenGL ES: the lock screen's photo (the wallpaper) turning into the
 * home screen's (DuoHomeImage). Two photos, so no subject and no clock.
 */
class DuoRenderer(
    private val context: Context,
    previewSource: (() -> Bitmap?)? = null
) : EffectOpenGlRenderer, WallpaperScrollRenderer {

    @Volatile var onRenderRetryRequested: (() -> Unit)? = null

    @Volatile var progress: Float = 0f
    @Volatile var dimLevel: Float = 0f
    @Volatile var style: DuoStyle = DuoStyle.DEFAULT

    @Volatile private var scrollOffsetX: Float = 0.5f

    private val lockImages = WallpaperImages(context, "Duo", previewSource, onSubjectMaskUpdated = {})
    private val homeImages = WallpaperImages(
        context,
        "Duo home",
        previewSource = null,
        onSubjectMaskUpdated = {},
        loadImage = { width, height -> DuoHomeImage.loadForRender(context, width, height) }
    )

    private var quad: FullScreenQuad? = null
    private var programId = 0
    private var aspectRatio = 1f
    private var renderFailureLogged = false
    private var renderRetryCount = 0

    override fun setWallpaperOffset(xOffset: Float) {
        scrollOffsetX = xOffset.coerceIn(0f, 1f)
    }

    /** The next lock photo of a playlist. */
    override fun queuePlaylistTransition(bitmap: Bitmap) = lockImages.queuePlaylistTransition(bitmap)

    override fun reloadTexture() {
        lockImages.reload()
        homeImages.reload()
    }

    /** The home photo's file changed: a new pick, or a playlist's next one. */
    fun reloadHomeImage() = homeImages.reload()

    // Duo has no clock: two photos have no one subject to fit it to.
    override fun beginClockEntry() = Unit

    override fun onClockTimeChanged() = Unit

    override fun release() {
        onRenderRetryRequested = null
        lockImages.release()
        homeImages.release()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        quad = FullScreenQuad()
        programId = 0
        renderFailureLogged = false
        renderRetryCount = 0
        try {
            programId = GlesProgram.link(
                "Duo",
                GlesProgram.readAsset(context, "shaders/duo/duo.vert"),
                GlesProgram.readAsset(context, "shaders/duo/duo.frag")
            )
        } catch (failure: Exception) {
            Log.e(TAG, "Unable to initialize the Duo renderer", failure)
        }
        lockImages.onSurfaceCreated()
        homeImages.onSurfaceCreated()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        val safeWidth = width.coerceAtLeast(0)
        val safeHeight = height.coerceAtLeast(0)
        GLES30.glViewport(0, 0, safeWidth, safeHeight)
        aspectRatio = if (safeHeight > 0) safeWidth.toFloat() / safeHeight else 1f
        // A new size (fold/unfold, rotation): both photos are fitted again, not stretched.
        lockImages.onSurfaceChanged(safeWidth, safeHeight)
        homeImages.onSurfaceChanged(safeWidth, safeHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        val quad = quad
        if (programId == 0 || quad == null) {
            clearFrame()
            return
        }
        try {
            lockImages.prepareFrame()
            // The home photo failing to load leaves the lock photo on both screens.
            runCatching { homeImages.prepareFrame() }
                .onFailure { Log.w(TAG, "Unable to load Duo's home photo", it) }
            if (!lockImages.current.isValid()) {
                clearFrame()
                return
            }
            draw(quad)
            renderFailureLogged = false
            renderRetryCount = 0
        } catch (failure: Exception) {
            if (!renderFailureLogged) {
                Log.e(TAG, "Unable to draw the Duo wallpaper", failure)
                renderFailureLogged = true
            }
            lockImages.onFrameFailed()
            homeImages.onFrameFailed()
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
        val lock = lockImages.current
        val homeReady = homeImages.current.isValid()
        val home = if (homeReady) homeImages.current else lock
        GLES30.glUniform1f(uniform("uAspectRatio"), aspectRatio)
        GLES30.glUniform1f(uniform("uProgress"), progress)
        GLES30.glUniform1f(uniform("uDimLevel"), dimLevel)
        GLES30.glUniform1i(uniform("uStyle"), style.ordinal)
        GLES30.glUniform1f(uniform("uScrollOffsetX"), scrollOffsetX)
        GLES30.glUniform1f(uniform("uLockWindowX"), lockImages.windowX)
        GLES30.glUniform1f(uniform("uHomeWindowX"), if (homeReady) homeImages.windowX else lockImages.windowX)
        GLES30.glUniform1f(uniform("uWallpaperZoom"), WakeTransition.zoom(1f))
        SceneTextures.bindPhoto(lock.photoId, 0, uniform("uLockPhoto"))
        SceneTextures.bindPhoto(home.photoId, 1, uniform("uHomePhoto"))
        quad.draw(programId)
    }

    private fun uniform(name: String) = GLES30.glGetUniformLocation(programId, name)

    private fun clearFrame() {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    private companion object {
        const val TAG = "DuoRenderer"
        const val MAX_RENDER_RETRIES = 3
    }
}
