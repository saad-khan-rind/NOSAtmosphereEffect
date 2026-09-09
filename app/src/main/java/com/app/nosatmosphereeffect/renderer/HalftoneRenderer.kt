package com.app.nosatmosphereeffect.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import com.app.nosatmosphereeffect.helper.ClockOverlayState
import com.app.nosatmosphereeffect.helper.GlesClockOverlay
import com.app.nosatmosphereeffect.helper.SubjectMaskCoordinator
import com.app.nosatmosphereeffect.helper.WallpaperFitHelper
import com.app.nosatmosphereeffect.helper.WallpaperScrollRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class HalftoneRenderer(
    private val context: Context,
    private val isReverse: Boolean = false,
    private val previewSource: (() -> Bitmap?)? = null
) : GLSurfaceView.Renderer, WallpaperScrollRenderer {

    @Volatile
    var onSubjectMaskUpdated: (() -> Unit)? = null

    @Volatile
    var onRenderRetryRequested: (() -> Unit)? = null

    @Volatile private var scrollOffsetX: Float = 0.5f
    private var currentWindowX: Float = 1f
    private var nextWindowX: Float = 1f

    override fun setWallpaperOffset(xOffset: Float) {
        scrollOffsetX = xOffset.coerceIn(0f, 1f)
    }

    private class TextureSet {
        var sharpId = 0
        var maskId = 0
        var width = 0
        var height = 0
        var generation = 0L
        var hasSubject = false
        fun isValid() = sharpId != 0
        fun reset() {
            sharpId = 0
            maskId = 0
            width = 0
            height = 0
            generation = 0L
            hasSubject = false
        }
    }

    private var currentSet = TextureSet()
    private var nextSet = TextureSet()

    private val playlistLock = Any()
    private var pendingPlaylistBitmap: Bitmap? = null
    private var released = false
    private val subjectMasks = SubjectMaskCoordinator(context) {
        onSubjectMaskUpdated?.invoke()
    }
    private var generationCounter = 0L
    private var renderFailureLogged = false
    private var renderRetryCount = 0

    @Volatile var blurStrength: Float = 0.0f
    @Volatile var dimLevel: Float = 0.0f
    @Volatile private var needsReload: Boolean = false
    @Volatile var dotSize: Float = 12.0f
    @Volatile var grayscale: Boolean = false

    /**
     * The effect's own "background only" setting, kept separate from
     * [SubjectMaskCoordinator.enabled].
     *
     * The two used to be the same thing, because the mask had exactly one
     * consumer. The clock's depth effect is a second, independent consumer:
     * it needs a mask even when the user has background-only switched off. If
     * the shader kept reading uBackgroundOnly from "is a mask being computed",
     * turning the clock's depth on would silently start protecting the subject
     * from the halftone as well.
     */
    @Volatile private var backgroundOnly: Boolean = false

    /**
     * The wallpaper clock. Halftone screens the photo into dots but leaves its
     * geometry in place, so the clock is legible at both ends of the
     * transition and the user picks which. Units 0 and 1 carry the photo and
     * the subject mask, so the clock takes 2.
     */
    private val clockOverlay = GlesClockOverlay(context, GLES30.GL_TEXTURE2, 2)

    /** Asks the host surface for another frame; used by the clock animation. */
    @Volatile
    var onAnimationFrameRequested: (() -> Unit)? = null
        set(value) {
            field = value
            clockOverlay.onAnimationFrameRequested = value
        }

    private var programId: Int = 0
    private var aspectRatio: Float = 1.0f

    @Volatile private var surfaceWidth: Int = 0
    @Volatile private var surfaceHeight: Int = 0
    private var fittedForWidth: Int = -1
    private var fittedForHeight: Int = -1

    private val vertices = floatArrayOf(
        -1f, -1f,  0f, 1f,
        1f, -1f,  1f, 1f,
        -1f,  1f,  0f, 0f,
        1f,  1f,  1f, 0f
    )
    private lateinit var vertexBuffer: FloatBuffer

    fun queuePlaylistTransition(bitmap: Bitmap) {
        if (bitmap.isRecycled) {
            Log.w(TAG, "Ignoring a recycled playlist bitmap")
            return
        }
        var rejected = false
        val replaced = synchronized(playlistLock) {
            if (released) {
                rejected = true
                null
            } else {
                pendingPlaylistBitmap.also { pendingPlaylistBitmap = bitmap }
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
     * Segmentation runs when *either* consumer wants it and is skipped when
     * neither does. Reloading on the transition into "wanted" is what actually
     * dispatches the extraction: the coordinator serves one request per image
     * generation, so without a reload an image that was loaded while the mask
     * was unwanted would never get one.
     */
    private fun refreshSubjectMaskNeed() {
        val wanted = backgroundOnly || clockOverlay.state.needsSubjectMask()
        val changed = subjectMasks.configure(wanted)
        if (wanted && (changed || currentSet.isValid())) {
            needsReload = true
        }
    }

    fun release() {
        val pending = synchronized(playlistLock) {
            if (released) return
            released = true
            pendingPlaylistBitmap.also { pendingPlaylistBitmap = null }
        }
        onSubjectMaskUpdated = null
        onRenderRetryRequested = null
        onAnimationFrameRequested = null
        clockOverlay.release()
        subjectMasks.close()
        if (pending != null && !pending.isRecycled) pending.recycle()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer.position(0)

        programId = 0
        renderFailureLogged = false
        renderRetryCount = 0
        try {
            val vertexCode = loadShaderFromAssets("shaders/halftone/halftone.vert")
            val fragmentCode = if (isReverse) {
                loadShaderFromAssets("shaders/halftone/sharp_to_halftone.frag")
            } else {
                loadShaderFromAssets("shaders/halftone/halftone_to_sharp.frag")
            }
            programId = createProgram(vertexCode, fragmentCode)
            needsReload = true
        } catch (failure: Exception) {
            Log.e(TAG, "Unable to initialize the Halftone renderer", failure)
        }

        // GL context is fresh: any previously held texture handles are invalid.
        currentSet.reset()
        nextSet.reset()
        subjectMasks.discardPending()
        // The clock's texture id belonged to the destroyed context.
        clockOverlay.resetForNewContext()
    }

    private fun loadAndApplyTextures() {
        fittedForWidth = surfaceWidth
        fittedForHeight = surfaceHeight
        val render = WallpaperFitHelper.loadForRender(context, surfaceWidth, surfaceHeight, previewSource)
        val sharpBitmap = render.bitmap
        try {
            val newTexture = uploadTexture(sharpBitmap)
            deleteTextureSet(currentSet)
            currentWindowX = render.windowX
            currentSet.width = sharpBitmap.width
            currentSet.height = sharpBitmap.height
            currentSet.generation = nextGeneration()
            currentSet.hasSubject = false
            currentSet.sharpId = newTexture
            subjectMasks.request(sharpBitmap, currentSet.generation)
        } finally {
            if (!sharpBitmap.isRecycled) sharpBitmap.recycle()
        }
    }

    private fun processPlaylistTransition() {
        val raw = synchronized(playlistLock) {
            pendingPlaylistBitmap.also { pendingPlaylistBitmap = null }
        } ?: return
        var bitmap: Bitmap? = null
        try {
            val render = WallpaperFitHelper.fitForRender(
                context,
                raw,
                surfaceWidth,
                surfaceHeight
            )
            bitmap = render.bitmap
            nextWindowX = render.windowX
            fittedForWidth = surfaceWidth
            fittedForHeight = surfaceHeight

            deleteMaskTexture(nextSet)
            nextSet.sharpId = uploadTexture(bitmap, nextSet.sharpId)
            nextSet.width = bitmap.width
            nextSet.height = bitmap.height
            nextSet.generation = nextGeneration()
            nextSet.hasSubject = false

            val temp = currentSet
            currentSet = nextSet
            nextSet = temp
            val tmpWin = currentWindowX
            currentWindowX = nextWindowX
            nextWindowX = tmpWin
            subjectMasks.request(bitmap, currentSet.generation)
        } catch (failure: Exception) {
            Log.e(TAG, "Unable to apply the next Halftone playlist image", failure)
            deleteTextureSet(nextSet)
            needsReload = true
        } finally {
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
            if (!raw.isRecycled) raw.recycle()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(0)
        surfaceHeight = height.coerceAtLeast(0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        aspectRatio = if (surfaceHeight > 0) {
            surfaceWidth.toFloat() / surfaceHeight.toFloat()
        } else {
            1f
        }
        // The surface size changed (fold/unfold, rotation, different display):
        // re-fit the wallpaper so it is not stretched to the new dimensions.
        if (surfaceWidth != fittedForWidth || surfaceHeight != fittedForHeight) {
            needsReload = true
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        if (programId == 0) {
            clearFrame()
            return
        }
        try {
            processPlaylistTransition()
            if (needsReload) {
                needsReload = false
                try {
                    loadAndApplyTextures()
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

            drawWallpaper()
            renderFailureLogged = false
            renderRetryCount = 0
        } catch (failure: Exception) {
            if (!renderFailureLogged) {
                Log.e(TAG, "Unable to draw the Halftone wallpaper", failure)
                renderFailureLogged = true
            }
            if (!currentSet.isValid()) needsReload = true
            clearFrame()
            requestBoundedRetry()
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

    private fun drawWallpaper() {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(programId)

        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uAspectRatio"), aspectRatio)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uBlurStrength"), blurStrength)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uDotSize"), dotSize)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uGrayscale"), if (grayscale) 1.0f else 0.0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uDimLevel"), dimLevel)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uScrollOffsetX"), scrollOffsetX)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uScrollWindowX"), currentWindowX)
        // Gated on the effect's own setting, not on whether a mask exists:
        // the clock's depth effect can be the reason a mask is being computed.
        val maskReady = subjectMasks.enabled && currentSet.hasSubject
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(programId, "uBackgroundOnly"),
            if (backgroundOnly && subjectMasks.enabled) 1f else 0f
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(programId, "uHasSubject"),
            if (backgroundOnly && maskReady) 1f else 0f
        )

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, currentSet.sharpId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTextureSharp"), 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, currentSet.maskId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uSubjectMask"), 1)

        clockOverlay.draw(
            programId = programId,
            progress = blurStrength,
            screenAspect = aspectRatio,
            subjectMaskAvailable = maskReady
        )

        val aPosLoc = GLES30.glGetAttribLocation(programId, "aPosition")
        val aTexLoc = GLES30.glGetAttribLocation(programId, "aTexCoord")

        vertexBuffer.position(0)
        GLES30.glVertexAttribPointer(aPosLoc, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)
        GLES30.glEnableVertexAttribArray(aPosLoc)

        vertexBuffer.position(2)
        GLES30.glVertexAttribPointer(aTexLoc, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)
        GLES30.glEnableVertexAttribArray(aTexLoc)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(aPosLoc)
        GLES30.glDisableVertexAttribArray(aTexLoc)
    }

    private fun uploadTexture(bitmap: Bitmap, existingTextureId: Int = 0): Int {
        val isNewTexture = existingTextureId == 0
        val textureId = if (isNewTexture) {
            IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        } else {
            existingTextureId
        }
        check(textureId != 0) { "OpenGL did not create a Halftone texture" }

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
            throwOnGlError("uploading a Halftone texture")
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
        check(textureId != 0) { "OpenGL did not create a Halftone subject mask" }
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
            throwOnGlError("uploading a Halftone subject mask")
            return textureId
        } catch (failure: RuntimeException) {
            if (isNewTexture) {
                GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            }
            throw failure
        }
    }

    private fun deleteMaskTexture(set: TextureSet) {
        if (set.maskId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(set.maskId), 0)
        }
        set.maskId = 0
        set.hasSubject = false
    }

    private fun deleteTextureSet(set: TextureSet) {
        if (set.sharpId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(set.sharpId), 0)
        }
        if (set.maskId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(set.maskId), 0)
        }
        set.reset()
    }

    private fun nextGeneration(): Long {
        generationCounter++
        return generationCounter
    }

    private fun clearFrame() {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
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
            check(program != 0) { "OpenGL did not create a Halftone shader program" }
            GLES30.glAttachShader(program, vertexShader)
            GLES30.glAttachShader(program, fragmentShader)
            GLES30.glLinkProgram(program)

            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val details = GLES30.glGetProgramInfoLog(program).ifBlank {
                    "No linker diagnostics were returned"
                }
                throw IllegalStateException("Halftone shader link failed: $details")
            }
            return program
        } catch (failure: RuntimeException) {
            if (program != 0) GLES30.glDeleteProgram(program)
            throw failure
        } finally {
            GLES30.glDeleteShader(vertexShader)
            GLES30.glDeleteShader(fragmentShader)
        }
    }

    private fun compileShader(type: Int, source: String, label: String): Int {
        val shader = GLES30.glCreateShader(type)
        check(shader != 0) { "OpenGL did not create the Halftone $label shader" }
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val details = GLES30.glGetShaderInfoLog(shader).ifBlank {
                "No compiler diagnostics were returned"
            }
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("Halftone $label shader compilation failed: $details")
        }
        return shader
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

    private fun requestBoundedRetry() {
        if (renderRetryCount >= MAX_RENDER_RETRIES) return
        renderRetryCount++
        onRenderRetryRequested?.invoke()
    }

    private companion object {
        const val TAG = "HalftoneRenderer"
        const val MAX_RENDER_RETRIES = 3
    }
}
