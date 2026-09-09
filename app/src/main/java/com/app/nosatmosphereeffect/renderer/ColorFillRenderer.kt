package com.app.nosatmosphereeffect.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import androidx.core.graphics.createBitmap
import com.app.nosatmosphereeffect.helper.ClockOverlayState
import com.app.nosatmosphereeffect.helper.GlesClockOverlay
import com.app.nosatmosphereeffect.helper.WallpaperFitHelper
import com.app.nosatmosphereeffect.helper.WallpaperScrollRenderer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ColorFillRenderer(
    private val context: Context,
    private val isReverse: Boolean = false,
    private val previewSource: (() -> Bitmap?)? = null
) : GLSurfaceView.Renderer, WallpaperScrollRenderer {

    @Volatile private var scrollOffsetX: Float = 0.5f
    private var currentWindowX: Float = 1f
    private var nextWindowX: Float = 1f

    override fun setWallpaperOffset(xOffset: Float) {
        scrollOffsetX = xOffset.coerceIn(0f, 1f)
    }

    private class TextureSet {
        var sharpId = 0
        var width = 0
        var height = 0
        fun isValid() = sharpId != 0
        fun reset() { sharpId = 0; width = 0; height = 0 }
    }

    private var currentSet = TextureSet()
    private var nextSet = TextureSet()
    @Volatile private var pendingPlaylistBitmap: Bitmap? = null

    @Volatile var blurStrength: Float = 0.0f
    @Volatile var dimLevel: Float = 0.0f

    /**
     * The wallpaper clock. Colour Fill leaves the photo's geometry untouched
     * at both ends of its transition — monochrome and colour are the same
     * image — so the clock is legible on the lock screen, the home screen or
     * both, and the user picks. Unit 1 is free here: this effect samples only
     * the wallpaper.
     */
    private val clockOverlay = GlesClockOverlay(context, GLES30.GL_TEXTURE1, 1)

    /** Asks the host surface for another frame; used by the clock animation. */
    @Volatile var onAnimationFrameRequested: (() -> Unit)? = null
        set(value) {
            field = value
            clockOverlay.onAnimationFrameRequested = value
        }

    fun applyClockState(state: ClockOverlayState) = clockOverlay.applyState(state)

    fun beginClockEntry() = clockOverlay.beginEntry()

    fun onClockTimeChanged() = clockOverlay.onTimeChanged()

    fun release() {
        onAnimationFrameRequested = null
        clockOverlay.release()
    }
    @Volatile private var needsReload: Boolean = false

    // The shader expects normalized fingerprint-origin coordinates.
    @Volatile var originX: Float = 0.5f
    @Volatile var originY: Float = 0.8f

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
        pendingPlaylistBitmap = bitmap
    }

    fun reloadTexture() {
        needsReload = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer.position(0)

        val vertexCode = loadShaderFromAssets("shaders/colorfill/colorfill.vert")
        val fragmentCode = if (isReverse) {
            loadShaderFromAssets("shaders/colorfill/color_to_bw.frag")
        } else {
            loadShaderFromAssets("shaders/colorfill/bw_to_color.frag")
        }

        programId = createProgram(vertexCode, fragmentCode)
        // GL context is fresh: any previously held texture handles are invalid.
        // Defer the texture load to the first frame, when the surface size is
        // known, so the image can be fitted to the actual display (foldables).
        currentSet.reset()
        nextSet.reset()
        needsReload = true
        // The clock's texture id belonged to the destroyed context.
        clockOverlay.resetForNewContext()
    }

    private fun loadAndApplyTextures() {
        if (currentSet.isValid()) {
            GLES30.glDeleteTextures(1, intArrayOf(currentSet.sharpId), 0)
            currentSet.reset()
        }
        fittedForWidth = surfaceWidth
        fittedForHeight = surfaceHeight
        val render = WallpaperFitHelper.loadForRender(context, surfaceWidth, surfaceHeight, previewSource)
        val sharpBitmap = render.bitmap
        currentWindowX = render.windowX

        currentSet.width = sharpBitmap.width
        currentSet.height = sharpBitmap.height

        currentSet.sharpId = uploadTexture(sharpBitmap)
        sharpBitmap.recycle()
    }

    private fun processPlaylistTransition() {
        val raw = pendingPlaylistBitmap ?: return
        val render = WallpaperFitHelper.fitForRender(context, raw, surfaceWidth, surfaceHeight)
        val bitmap = render.bitmap
        nextWindowX = render.windowX
        fittedForWidth = surfaceWidth
        fittedForHeight = surfaceHeight

        // Reuse the queued texture ID across playlist transitions.
        nextSet.sharpId = uploadTexture(bitmap, nextSet.sharpId, nextSet.width, nextSet.height)

        nextSet.width = bitmap.width
        nextSet.height = bitmap.height

        bitmap.recycle()

        val temp = currentSet
        currentSet = nextSet
        nextSet = temp
        val tmpWin = currentWindowX
        currentWindowX = nextWindowX
        nextWindowX = tmpWin
        pendingPlaylistBitmap = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspectRatio = width.toFloat() / height.toFloat()
        surfaceWidth = width
        surfaceHeight = height
        // The surface size changed (fold/unfold, rotation, different display):
        // re-fit the wallpaper so it is not stretched to the new dimensions.
        if (width != fittedForWidth || height != fittedForHeight) {
            needsReload = true
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        if (pendingPlaylistBitmap != null) processPlaylistTransition()
        if (needsReload) {
            needsReload = false
            loadAndApplyTextures()
        }

        if (!currentSet.isValid()) {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(programId)

        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uAspectRatio"), aspectRatio)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uBlurStrength"), blurStrength)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uDimLevel"), dimLevel)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uOrigin"), originX, originY)

        // Horizontal scroll window (identity 0f/1f = no scroll, draws as before).
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uScrollOffsetX"), scrollOffsetX)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uScrollWindowX"), currentWindowX)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, currentSet.sharpId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTextureSharp"), 0)

        // Colour Fill has no subject mask, so the depth effect is not offered
        // for it and nothing is passed here.
        clockOverlay.draw(
            programId = programId,
            progress = blurStrength,
            screenAspect = aspectRatio
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

    private fun uploadTexture(bitmap: Bitmap, existingTextureId: Int = 0, existingWidth: Int = 0, existingHeight: Int = 0): Int {
        val textureHandle = if (existingTextureId != 0) intArrayOf(existingTextureId) else { val arr = IntArray(1); GLES30.glGenTextures(1, arr, 0); arr }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureHandle[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)

        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)


        return textureHandle[0]
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        return program
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        return shader
    }

    private fun loadShaderFromAssets(path: String): String {
        return context.assets.open(path).bufferedReader().use { it.readText() }
    }
}
