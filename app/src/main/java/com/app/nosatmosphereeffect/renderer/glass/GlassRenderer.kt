package com.app.nosatmosphereeffect.renderer.glass

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.app.nosatmosphereeffect.clock.ClockOverlayState
import com.app.nosatmosphereeffect.clock.GlesClockOverlay
import com.app.nosatmosphereeffect.effect.GlassEffectPolicy
import com.app.nosatmosphereeffect.effect.GlassTransitionStyle
import com.app.nosatmosphereeffect.renderer.backend.EffectOpenGlRenderer
import com.app.nosatmosphereeffect.renderer.gles.GlesProgram
import com.app.nosatmosphereeffect.renderer.gles.SceneTextures
import com.app.nosatmosphereeffect.renderer.gles.WallpaperImages
import com.app.nosatmosphereeffect.renderer.gles.checkGlError
import com.app.nosatmosphereeffect.subject.SubjectMaskCoordinator
import com.app.nosatmosphereeffect.wallpaper.WallpaperScrollRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GlassRenderer(
    private val context: Context,
    private val previewSource: (() -> Bitmap?)? = null
) : EffectOpenGlRenderer, WallpaperScrollRenderer {

    @Volatile
    private var scrollOffsetX = 0.5f

    @Volatile
    private var surfaceWidth = 0

    @Volatile
    private var surfaceHeight = 0

    @Volatile
    var onRenderRetryRequested: (() -> Unit)? = null

    @Volatile
    var onSubjectMaskUpdated: (() -> Unit)? = null

    /**
     * The effect's own "background only" setting, kept separate from
     * [SubjectMaskCoordinator.enabled] — the clock's depth effect is a second,
     * independent reason to want a mask, and conflating the two would mean
     * switching depth on silently started protecting the subject from the
     * glass ribs as well.
     */
    @Volatile
    private var backgroundOnly = false

    /**
     * The wallpaper clock. Glass refracts the photo sideways through the ribs,
     * which both smears glyph edges and moves the pixels the depth mask was
     * computed against, so the clock is pinned to whichever side of the
     * transition stays sharp rather than offered as a choice. Units 0 and 1
     * carry the photo and the subject mask, so the clock takes 2.
     */
    private val clockOverlay = GlesClockOverlay(context, GLES30.GL_TEXTURE2, 2)

    /** Asks the host surface for another frame; used by the clock animation. */
    @Volatile
    var onAnimationFrameRequested: (() -> Unit)? = null
        set(value) {
            field = value
            clockOverlay.onAnimationFrameRequested = value
        }

    @Volatile
    var progress = 0f
        set(value) {
            field = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        }

    @Volatile
    var dimLevel = 0f
        set(value) {
            field = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        }

    @Volatile
    var lineCount = GlassEffectPolicy.DEFAULT_LINE_COUNT
        set(value) {
            field = GlassEffectPolicy.sanitizeLineCount(value)
        }

    @Volatile
    var lineThickness = GlassEffectPolicy.DEFAULT_LINE_THICKNESS
        set(value) {
            field = GlassEffectPolicy.sanitizeLineThickness(value)
        }

    @Volatile
    var transitionStyle = GlassTransitionStyle.RIGHT_TO_LEFT

    private data class ProgramHandles(
        val program: Int,
        val position: Int,
        val textureCoordinate: Int,
        val texture: Int,
        val progress: Int,
        val lineCount: Int,
        val lineThickness: Int,
        val transitionStyle: Int,
        val dimLevel: Int,
        val scrollOffsetX: Int,
        val scrollWindowX: Int,
        val subjectMask: Int,
        val backgroundOnly: Int,
        val hasSubject: Int
    )

    private var programHandles: ProgramHandles? = null
    private lateinit var vertexBuffer: FloatBuffer
    private var renderFailureLogged = false
    private var renderRetryCount = 0
    private val images = WallpaperImages(context, "Glass", previewSource) { onSubjectMaskUpdated?.invoke() }

    init {
        images.sceneSink = clockOverlay.sceneSink
    }

    private val vertices = floatArrayOf(
        -1f, -1f, 0f, 1f,
        1f, -1f, 1f, 1f,
        -1f, 1f, 0f, 0f,
        1f, 1f, 1f, 0f
    )

    override fun setWallpaperOffset(xOffset: Float) {
        scrollOffsetX = if (xOffset.isFinite()) xOffset.coerceIn(0f, 1f) else 0.5f
    }

    override fun queuePlaylistTransition(bitmap: Bitmap) = images.queuePlaylistTransition(bitmap)

    override fun reloadTexture() = images.reload()

    fun configureBackgroundOnly(enabled: Boolean) {
        backgroundOnly = enabled
        refreshSubjectMaskNeed()
    }

    fun applyClockState(state: ClockOverlayState) {
        clockOverlay.applyState(state)
        refreshSubjectMaskNeed()
    }

    override fun beginClockEntry() = clockOverlay.beginEntry()

    override fun onClockTimeChanged() = clockOverlay.onTimeChanged()

    /**
     * Segmentation runs when either consumer wants it. Reloading on the
     * transition into "wanted" is what dispatches the extraction: the
     * coordinator serves one request per image generation, so an image loaded
     * while no one wanted a mask would otherwise never get one.
     */
    private fun refreshSubjectMaskNeed() {
        // Only on turning on does this reload (see WallpaperImages.wantSubjectMask): it
        // runs on every clock push, and reloading there would drop the mask each time.
        images.wantSubjectMask(backgroundOnly || clockOverlay.state.needsSubjectMask())
    }

    override fun release() {
        onRenderRetryRequested = null
        onSubjectMaskUpdated = null
        onAnimationFrameRequested = null
        clockOverlay.release()
        images.release()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
            .apply { position(0) }

        images.onSurfaceCreated()
        // The clock's texture id belonged to the destroyed context.
        clockOverlay.resetForNewContext()
        programHandles = null
        renderFailureLogged = false
        renderRetryCount = 0

        try {
            val vertexSource = GlesProgram.readAsset(context, "shaders/glass/glass.vert")
            val fragmentSource = GlesProgram.readAsset(context, "shaders/glass/glass.frag")
            val program = GlesProgram.link("Glass", vertexSource, fragmentSource)
            programHandles = try {
                resolveHandles(program)
            } catch (failure: RuntimeException) {
                GLES30.glDeleteProgram(program)
                throw failure
            }
        } catch (failure: Exception) {
            Log.e(TAG, "Unable to initialize the Glass renderer", failure)
            programHandles = null
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(0)
        surfaceHeight = height.coerceAtLeast(0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        images.onSurfaceChanged(surfaceWidth, surfaceHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        val handles = programHandles
        if (handles == null) {
            clearFrame()
            return
        }

        try {
            images.prepareFrame()
            if (!images.current.isValid()) {
                clearFrame()
                return
            }

            drawWallpaper(handles)
            renderFailureLogged = false
            renderRetryCount = 0
        } catch (failure: Exception) {
            if (!renderFailureLogged) {
                Log.e(TAG, "Unable to draw the Glass wallpaper", failure)
                renderFailureLogged = true
            }
            images.onFrameFailed()
            clearFrame()
            requestBoundedRetry()
        }
    }




    private fun drawWallpaper(handles: ProgramHandles) {
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(handles.program)

        GLES30.glUniform1f(handles.progress, progress)
        GLES30.glUniform1f(handles.lineCount, lineCount.toFloat())
        GLES30.glUniform1f(handles.lineThickness, lineThickness)
        GLES30.glUniform1f(
            handles.transitionStyle,
            if (transitionStyle == GlassTransitionStyle.FADE) 1f else 0f
        )
        GLES30.glUniform1f(handles.dimLevel, dimLevel)
        GLES30.glUniform1f(handles.scrollOffsetX, scrollOffsetX)
        GLES30.glUniform1f(handles.scrollWindowX, images.windowX)
        // Gated on the effect's own setting, not on whether a mask exists:
        // the clock's depth effect can be the reason a mask is being computed.
        val maskReady = images.maskReady
        GLES30.glUniform1f(
            handles.backgroundOnly,
            if (backgroundOnly && images.masksEnabled) 1f else 0f
        )
        GLES30.glUniform1f(
            handles.hasSubject,
            if (backgroundOnly && maskReady) 1f else 0f
        )

        SceneTextures.bindPhoto(images.current.photoId, 0, handles.texture)

        SceneTextures.bindSubjectMask(images.current.maskId, 1, handles.subjectMask)

        clockOverlay.draw(
            scrollOffsetX = scrollOffsetX,
            scrollWindowX = images.windowX,
            programId = handles.program,
            progress = progress,
            screenAspect = if (surfaceHeight > 0) {
                surfaceWidth.toFloat() / surfaceHeight.toFloat()
            } else {
                1f
            },
            subjectMaskAvailable = maskReady
        )

        vertexBuffer.position(0)
        GLES30.glVertexAttribPointer(
            handles.position,
            2,
            GLES30.GL_FLOAT,
            false,
            VERTEX_STRIDE_BYTES,
            vertexBuffer
        )
        GLES30.glEnableVertexAttribArray(handles.position)

        vertexBuffer.position(2)
        GLES30.glVertexAttribPointer(
            handles.textureCoordinate,
            2,
            GLES30.GL_FLOAT,
            false,
            VERTEX_STRIDE_BYTES,
            vertexBuffer
        )
        GLES30.glEnableVertexAttribArray(handles.textureCoordinate)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(handles.position)
        GLES30.glDisableVertexAttribArray(handles.textureCoordinate)
        checkGlError("drawing a Glass frame")
    }



    private fun resolveHandles(program: Int): ProgramHandles {
        return ProgramHandles(
            program = program,
            position = requireLocation(
                GLES30.glGetAttribLocation(program, "aPosition"),
                "aPosition"
            ),
            textureCoordinate = requireLocation(
                GLES30.glGetAttribLocation(program, "aTexCoord"),
                "aTexCoord"
            ),
            texture = requireLocation(
                GLES30.glGetUniformLocation(program, "uTexture"),
                "uTexture"
            ),
            progress = requireLocation(
                GLES30.glGetUniformLocation(program, "uProgress"),
                "uProgress"
            ),
            lineCount = requireLocation(
                GLES30.glGetUniformLocation(program, "uLineCount"),
                "uLineCount"
            ),
            lineThickness = requireLocation(
                GLES30.glGetUniformLocation(program, "uLineThickness"),
                "uLineThickness"
            ),
            transitionStyle = requireLocation(
                GLES30.glGetUniformLocation(program, "uTransitionStyle"),
                "uTransitionStyle"
            ),
            dimLevel = requireLocation(
                GLES30.glGetUniformLocation(program, "uDimLevel"),
                "uDimLevel"
            ),
            scrollOffsetX = requireLocation(
                GLES30.glGetUniformLocation(program, "uScrollOffsetX"),
                "uScrollOffsetX"
            ),
            scrollWindowX = requireLocation(
                GLES30.glGetUniformLocation(program, "uScrollWindowX"),
                "uScrollWindowX"
            ),
            subjectMask = requireLocation(
                GLES30.glGetUniformLocation(program, "uSubjectMask"),
                "uSubjectMask"
            ),
            backgroundOnly = requireLocation(
                GLES30.glGetUniformLocation(program, "uBackgroundOnly"),
                "uBackgroundOnly"
            ),
            hasSubject = requireLocation(
                GLES30.glGetUniformLocation(program, "uHasSubject"),
                "uHasSubject"
            )
        )
    }


    private fun requireLocation(location: Int, name: String): Int {
        check(location >= 0) { "Glass shader input '$name' is unavailable" }
        return location
    }

    private fun clearFrame() {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    private fun requestBoundedRetry() {
        if (renderRetryCount >= MAX_RENDER_RETRIES) return
        renderRetryCount++
        onRenderRetryRequested?.invoke()
    }

    private companion object {
        const val TAG = "GlassRenderer"
        const val VERTEX_STRIDE_BYTES = 4 * Float.SIZE_BYTES
        const val MAX_RENDER_RETRIES = 3
    }
}
