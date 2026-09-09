package com.app.nosatmosphereeffect.renderer

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import com.app.nosatmosphereeffect.helper.ClockOverlayState
import com.app.nosatmosphereeffect.helper.GlassEffectPolicy
import com.app.nosatmosphereeffect.helper.GlassTransitionStyle
import com.app.nosatmosphereeffect.helper.GlesClockOverlay
import com.app.nosatmosphereeffect.helper.SubjectMaskCoordinator
import com.app.nosatmosphereeffect.helper.WallpaperFitHelper
import com.app.nosatmosphereeffect.helper.WallpaperScrollRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GlassRenderer(
    private val context: Context,
    private val previewSource: (() -> Bitmap?)? = null
) : GLSurfaceView.Renderer, WallpaperScrollRenderer {

    @Volatile
    private var scrollOffsetX = 0.5f

    @Volatile
    private var surfaceWidth = 0

    @Volatile
    private var surfaceHeight = 0

    @Volatile
    private var needsReload = false

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

    private data class TextureSet(
        var textureId: Int = 0,
        var maskId: Int = 0,
        var width: Int = 0,
        var height: Int = 0,
        var generation: Long = 0L,
        var hasSubject: Boolean = false
    ) {
        fun isValid(): Boolean = textureId != 0

        fun reset() {
            textureId = 0
            maskId = 0
            width = 0
            height = 0
            generation = 0L
            hasSubject = false
        }
    }

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

    private var currentSet = TextureSet()
    private var nextSet = TextureSet()
    private var currentWindowX = 1f
    private var nextWindowX = 1f
    private var fittedForWidth = -1
    private var fittedForHeight = -1
    private var programHandles: ProgramHandles? = null
    private lateinit var vertexBuffer: FloatBuffer
    private var renderFailureLogged = false
    private var renderRetryCount = 0
    private var generationCounter = 0L

    private val pendingLock = Any()
    private var pendingPlaylistBitmap: Bitmap? = null
    private var released = false
    private val subjectMasks = SubjectMaskCoordinator(context) {
        onSubjectMaskUpdated?.invoke()
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

    fun queuePlaylistTransition(bitmap: Bitmap) {
        if (bitmap.isRecycled) {
            Log.w(TAG, "Ignoring a recycled playlist bitmap")
            return
        }

        var rejected = false
        val replaced = synchronized(pendingLock) {
            if (released) {
                rejected = true
                null
            } else {
                val previous = pendingPlaylistBitmap
                pendingPlaylistBitmap = bitmap
                previous
            }
        }
        if (rejected) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        if (replaced != null && replaced !== bitmap && !replaced.isRecycled) {
            replaced.recycle()
        }
    }

    fun reloadTexture() {
        needsReload = true
    }

    fun configureBackgroundOnly(enabled: Boolean) {
        backgroundOnly = enabled
        refreshSubjectMaskNeed()
    }

    fun applyClockState(state: ClockOverlayState) {
        clockOverlay.applyState(state)
        refreshSubjectMaskNeed()
    }

    fun beginClockEntry() = clockOverlay.beginEntry()

    fun onClockTimeChanged() = clockOverlay.onTimeChanged()

    /**
     * Segmentation runs when either consumer wants it. Reloading on the
     * transition into "wanted" is what dispatches the extraction: the
     * coordinator serves one request per image generation, so an image loaded
     * while no one wanted a mask would otherwise never get one.
     */
    private fun refreshSubjectMaskNeed() {
        val wanted = backgroundOnly || clockOverlay.state.needsSubjectMask()
        val changed = subjectMasks.configure(wanted)
        if (wanted && (changed || currentSet.isValid())) {
            needsReload = true
        }
    }

    fun release() {
        val pending = synchronized(pendingLock) {
            if (released) return
            released = true
            pendingPlaylistBitmap.also { pendingPlaylistBitmap = null }
        }
        onRenderRetryRequested = null
        onSubjectMaskUpdated = null
        onAnimationFrameRequested = null
        clockOverlay.release()
        subjectMasks.close()
        if (pending != null && !pending.isRecycled) {
            pending.recycle()
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
            .apply { position(0) }

        currentSet.reset()
        nextSet.reset()
        subjectMasks.discardPending()
        // The clock's texture id belonged to the destroyed context.
        clockOverlay.resetForNewContext()
        programHandles = null
        renderFailureLogged = false
        renderRetryCount = 0

        try {
            val vertexSource = loadShaderFromAssets("shaders/glass/glass.vert")
            val fragmentSource = loadShaderFromAssets("shaders/glass/glass.frag")
            val program = createProgram(vertexSource, fragmentSource)
            programHandles = try {
                resolveHandles(program)
            } catch (failure: RuntimeException) {
                GLES30.glDeleteProgram(program)
                throw failure
            }
            needsReload = true
        } catch (failure: Exception) {
            Log.e(TAG, "Unable to initialize the Glass renderer", failure)
            programHandles = null
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(0)
        surfaceHeight = height.coerceAtLeast(0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        if (surfaceWidth != fittedForWidth || surfaceHeight != fittedForHeight) {
            needsReload = true
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        val handles = programHandles
        if (handles == null) {
            clearFrame()
            return
        }

        try {
            processPlaylistTransition()
            if (needsReload) {
                needsReload = false
                try {
                    loadCurrentTexture()
                } catch (failure: Exception) {
                    needsReload = true
                    throw failure
                }
            }
            applyPendingSubjectMask()
            if (!currentSet.isValid()) {
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
            if (!currentSet.isValid()) {
                needsReload = true
            }
            clearFrame()
            requestBoundedRetry()
        }
    }

    private fun loadCurrentTexture() {
        val renderImage = WallpaperFitHelper.loadForRender(
            context,
            surfaceWidth,
            surfaceHeight,
            previewSource
        )
        val bitmap = renderImage.bitmap
        try {
            val newTexture = uploadTexture(bitmap)
            deleteTexture(currentSet)
            currentSet.textureId = newTexture
            currentSet.width = bitmap.width
            currentSet.height = bitmap.height
            currentSet.generation = nextGeneration()
            currentSet.hasSubject = false
            currentWindowX = renderImage.windowX
            fittedForWidth = surfaceWidth
            fittedForHeight = surfaceHeight
            subjectMasks.request(bitmap, currentSet.generation)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun processPlaylistTransition() {
        val source = synchronized(pendingLock) {
            pendingPlaylistBitmap.also { pendingPlaylistBitmap = null }
        } ?: return

        var fittedBitmap: Bitmap? = null
        try {
            val renderImage = WallpaperFitHelper.fitForRender(
                context,
                source,
                surfaceWidth,
                surfaceHeight
            )
            fittedBitmap = renderImage.bitmap
            nextWindowX = renderImage.windowX

            deleteMaskTexture(nextSet)
            nextSet.textureId = uploadTexture(fittedBitmap, nextSet.textureId)
            nextSet.width = fittedBitmap.width
            nextSet.height = fittedBitmap.height
            nextSet.generation = nextGeneration()
            nextSet.hasSubject = false

            val previous = currentSet
            currentSet = nextSet
            nextSet = previous

            val previousWindowX = currentWindowX
            currentWindowX = nextWindowX
            nextWindowX = previousWindowX
            fittedForWidth = surfaceWidth
            fittedForHeight = surfaceHeight
            subjectMasks.request(fittedBitmap, currentSet.generation)
        } catch (failure: Exception) {
            Log.e(TAG, "Unable to apply the next playlist image", failure)
            deleteTexture(nextSet)
            needsReload = true
        } finally {
            if (fittedBitmap != null && !fittedBitmap.isRecycled) {
                fittedBitmap.recycle()
            }
            if (!source.isRecycled) {
                source.recycle()
            }
        }
    }

    private fun applyPendingSubjectMask() {
        val pending = subjectMasks.takePending() ?: return
        try {
            if (pending.generation != currentSet.generation || !subjectMasks.enabled) {
                return
            }
            currentSet.maskId = uploadMaskTexture(pending.bitmap, currentSet.maskId)
            currentSet.hasSubject = true
        } finally {
            if (!pending.bitmap.isRecycled) pending.bitmap.recycle()
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
        GLES30.glUniform1f(handles.scrollWindowX, currentWindowX)
        // Gated on the effect's own setting, not on whether a mask exists:
        // the clock's depth effect can be the reason a mask is being computed.
        val maskReady = subjectMasks.enabled && currentSet.hasSubject
        GLES30.glUniform1f(
            handles.backgroundOnly,
            if (backgroundOnly && subjectMasks.enabled) 1f else 0f
        )
        GLES30.glUniform1f(
            handles.hasSubject,
            if (backgroundOnly && maskReady) 1f else 0f
        )

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, currentSet.textureId)
        GLES30.glUniform1i(handles.texture, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, currentSet.maskId)
        GLES30.glUniform1i(handles.subjectMask, 1)

        clockOverlay.draw(
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
        throwOnGlError("drawing a Glass frame")
    }

    private fun uploadTexture(bitmap: Bitmap, existingTextureId: Int = 0): Int {
        val isNewTexture = existingTextureId == 0
        val textureId = if (isNewTexture) {
            val generated = IntArray(1)
            GLES30.glGenTextures(1, generated, 0)
            generated[0]
        } else {
            existingTextureId
        }
        check(textureId != 0) { "OpenGL did not create a texture" }

        try {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MIN_FILTER,
                GLES30.GL_LINEAR_MIPMAP_LINEAR
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MAG_FILTER,
                GLES30.GL_LINEAR
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_WRAP_S,
                GLES30.GL_CLAMP_TO_EDGE
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_WRAP_T,
                GLES30.GL_CLAMP_TO_EDGE
            )
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            throwOnGlError("uploading a Glass texture")
            return textureId
        } catch (failure: RuntimeException) {
            if (isNewTexture) {
                GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            }
            throw failure
        }
    }

    private fun uploadMaskTexture(bitmap: Bitmap, existingTextureId: Int = 0): Int {
        val isNewTexture = existingTextureId == 0
        val textureId = if (!isNewTexture) {
            existingTextureId
        } else {
            IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        }
        check(textureId != 0) { "OpenGL did not create a subject-mask texture" }

        try {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MIN_FILTER,
                GLES30.GL_LINEAR
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MAG_FILTER,
                GLES30.GL_LINEAR
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_WRAP_S,
                GLES30.GL_CLAMP_TO_EDGE
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_WRAP_T,
                GLES30.GL_CLAMP_TO_EDGE
            )
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            throwOnGlError("uploading a Glass subject mask")
            return textureId
        } catch (failure: RuntimeException) {
            if (isNewTexture) {
                GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            }
            throw failure
        }
    }

    private fun deleteMaskTexture(textureSet: TextureSet) {
        if (textureSet.maskId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureSet.maskId), 0)
        }
        textureSet.maskId = 0
        textureSet.hasSubject = false
    }

    private fun deleteTexture(textureSet: TextureSet) {
        if (textureSet.textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureSet.textureId), 0)
        }
        if (textureSet.maskId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureSet.maskId), 0)
        }
        textureSet.reset()
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource, "vertex")
        val fragmentShader = try {
            compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource, "fragment")
        } catch (failure: RuntimeException) {
            GLES30.glDeleteShader(vertexShader)
            throw failure
        }

        var program = 0
        try {
            program = GLES30.glCreateProgram()
            check(program != 0) { "OpenGL did not create a shader program" }
            GLES30.glAttachShader(program, vertexShader)
            GLES30.glAttachShader(program, fragmentShader)
            GLES30.glLinkProgram(program)

            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val details = GLES30.glGetProgramInfoLog(program).ifBlank {
                    "No linker diagnostics were returned"
                }
                throw IllegalStateException("Glass shader link failed: $details")
            }
            return program
        } catch (failure: RuntimeException) {
            if (program != 0) {
                GLES30.glDeleteProgram(program)
            }
            throw failure
        } finally {
            GLES30.glDeleteShader(vertexShader)
            GLES30.glDeleteShader(fragmentShader)
        }
    }

    private fun compileShader(type: Int, source: String, label: String): Int {
        val shader = GLES30.glCreateShader(type)
        check(shader != 0) { "OpenGL did not create the Glass $label shader" }
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val details = GLES30.glGetShaderInfoLog(shader).ifBlank {
                "No compiler diagnostics were returned"
            }
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("Glass $label shader compilation failed: $details")
        }
        return shader
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

    private fun nextGeneration(): Long {
        generationCounter++
        return generationCounter
    }

    private fun requireLocation(location: Int, name: String): Int {
        check(location >= 0) { "Glass shader input '$name' is unavailable" }
        return location
    }

    private fun loadShaderFromAssets(path: String): String {
        return context.assets.open(path).bufferedReader().use { it.readText() }
    }

    private fun throwOnGlError(operation: String) {
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) {
            "OpenGL error 0x${error.toString(16)} while $operation"
        }
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
