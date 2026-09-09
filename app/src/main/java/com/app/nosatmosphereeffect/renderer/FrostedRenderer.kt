package com.app.nosatmosphereeffect.renderer

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.app.nosatmosphereeffect.helper.ClockOverlayState
import com.app.nosatmosphereeffect.helper.GlesClockOverlay
import com.app.nosatmosphereeffect.helper.WallpaperFitHelper
import com.app.nosatmosphereeffect.helper.WallpaperScrollRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class FrostedRenderer(
    private val context: Context,
    private val previewSource: (() -> Bitmap?)? = null
) : GLSurfaceView.Renderer, WallpaperScrollRenderer {

    @Volatile private var scrollOffsetX: Float = 0.5f
    private var currentWindowX: Float = 1f
    private var nextWindowX: Float = 1f

    override fun setWallpaperOffset(xOffset: Float) {
        scrollOffsetX = xOffset.coerceIn(0f, 1f)
    }

    // Wallpaper visibility is independent from zoom and blurStrength.
    // Only the reverse Frosted engine flips this on (when the wallpaper leaves view
    // while the screen is still on), blending toward the frosted texture so the drawer
    // shows a blur instead of the sharp home image. Forward Frosted never sets it, and
    // it is already frosted at rest, so this stays a no-op there.
    @Volatile private var drawerBlur: Float = 0f

    fun setDrawerBlurred(blurred: Boolean) {
        drawerBlur = if (blurred) 1f else 0f
    }

    private class TextureSet {
        var sharpId = 0
        var blurId = 0
        var width = 0
        var height = 0
        fun isValid() = sharpId != 0 && blurId != 0
        fun reset() { sharpId = 0; blurId = 0; width = 0; height = 0 }
    }

    private var currentSet = TextureSet()
    private var nextSet = TextureSet()

    @Volatile private var pendingPlaylistBitmap: Bitmap? = null

    // Texture storage can only be reused while its dimensions still match.
    private var tempTextureWidth: Int = 0
    private var tempTextureHeight: Int = 0

    @Volatile var blurStrength: Float = 0.0f
        set(value) {
            field = value
        }
    @Volatile var dimLevel: Float = 0.2f
    @Volatile private var needsReload: Boolean = false
    @Volatile var enableNoise: Boolean = false
    @Volatile var noiseScale: Float = 2000.0f
    @Volatile var noiseStrength: Float = 0.06f
    @Volatile var blurRadius: Float = 200.0f

    /**
     * The wallpaper clock. Frosted blurs the photo at one end of its
     * transition, so the clock is pinned to whichever end stays sharp rather
     * than offered as a choice — glyph edges over a heavy blur read as
     * smeared, and there is no subject mask here to draw depth from. Units 0
     * and 1 carry the sharp and blurred images, so the clock takes 2.
     */
    private val clockOverlay = GlesClockOverlay(context, GLES30.GL_TEXTURE2, 2)

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

    private var programId: Int = 0
    private var blurProgramId: Int = 0
    private var tempTextureId: Int = 0
    private var fboId: Int = 0
    private var aspectRatio: Float = 1.0f

    @Volatile private var surfaceWidth: Int = 0
    @Volatile private var surfaceHeight: Int = 0
    private var fittedForWidth: Int = -1
    private var fittedForHeight: Int = -1

    fun queuePlaylistTransition(bitmap: Bitmap, value: Float = 0.0f) {
        pendingPlaylistBitmap = bitmap
        blurStrength = value
    }

    private val vertices = floatArrayOf(
        -1f, -1f,  0f, 1f,
        1f, -1f,  1f, 1f,
        -1f,  1f,  0f, 0f,
        1f,  1f,  1f, 0f
    )
    private lateinit var vertexBuffer: FloatBuffer

    fun reloadTexture() {
        needsReload = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer.position(0)

        val vertexCode = loadShaderFromAssets("shaders/frostedBlur/frosted.vert")
        val fragmentCode = loadShaderFromAssets("shaders/frostedBlur/frosted.frag")

        programId = createProgram(vertexCode, fragmentCode)

        val blurFragCode = """
            #version 300 es
            precision highp float;
            in vec2 vTexCoord;
            out vec4 fragColor;
            uniform sampler2D uTexture;
            uniform vec2 uDirection;
            uniform float uRadius;
            void main() {
                vec2 texelSize = 1.0 / vec2(textureSize(uTexture, 0));
                vec3 result = vec3(0.0);
                float totalWeight = 0.0;
                for(float i = -uRadius; i <= uRadius; i++) {
                    vec2 offset = uDirection * i * texelSize;
                    float weight = 1.0 - abs(i) / uRadius;
                    result += texture(uTexture, vTexCoord + offset).rgb * weight;
                    totalWeight += weight;
                }
                fragColor = vec4(result / totalWeight, 1.0);
            }
        """.trimIndent()
        blurProgramId = createProgram(vertexCode, blurFragCode)

        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        fboId = fbo[0]

        // GL context is fresh: any previously held texture handles are invalid.
        currentSet.reset()
        nextSet.reset()
        tempTextureId = 0
        tempTextureWidth = 0
        tempTextureHeight = 0
        needsReload = true
        // The clock's texture id belonged to the destroyed context.
        clockOverlay.resetForNewContext()
    }

    private fun loadAndApplyTextures() {
        if (currentSet.isValid()) {
            val ids = intArrayOf(currentSet.sharpId, currentSet.blurId)
            GLES30.glDeleteTextures(2, ids, 0)
            currentSet.reset()
        }
        if (tempTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(tempTextureId), 0)
            tempTextureId = 0
            tempTextureWidth = 0
            tempTextureHeight = 0
        }

        fittedForWidth = surfaceWidth
        fittedForHeight = surfaceHeight
        val render = WallpaperFitHelper.loadForRender(context, surfaceWidth, surfaceHeight, previewSource)
        val sharpBitmap = render.bitmap
        currentWindowX = render.windowX

        currentSet.width = sharpBitmap.width
        currentSet.height = sharpBitmap.height

        currentSet.sharpId = uploadTexture(sharpBitmap)

        tempTextureWidth = sharpBitmap.width
        tempTextureHeight = sharpBitmap.height
        tempTextureId = createEmptyTexture(sharpBitmap.width, sharpBitmap.height)

        if (blurRadius < 1.0f) {
            currentSet.blurId = uploadTexture(sharpBitmap)
        } else {
            currentSet.blurId = gpuBlur(currentSet.sharpId, sharpBitmap.width, sharpBitmap.height, blurRadius)
        }
        sharpBitmap.recycle()
    }

    private fun processPlaylistTransition() {
        val raw = pendingPlaylistBitmap ?: return
        val render = WallpaperFitHelper.fitForRender(context, raw, surfaceWidth, surfaceHeight)
        val bitmap = render.bitmap
        nextWindowX = render.windowX
        fittedForWidth = surfaceWidth
        fittedForHeight = surfaceHeight

        // Reuse queued texture IDs; dimensions determine whether storage is reallocated.
        nextSet.sharpId = uploadTexture(bitmap, nextSet.sharpId, nextSet.width, nextSet.height)

        tempTextureId = createEmptyTexture(bitmap.width, bitmap.height, tempTextureId, tempTextureWidth, tempTextureHeight)
        tempTextureWidth = bitmap.width
        tempTextureHeight = bitmap.height

        if (blurRadius < 1.0f) {
            nextSet.blurId = uploadTexture(bitmap, nextSet.blurId, nextSet.width, nextSet.height)
        } else {
            nextSet.blurId = gpuBlur(nextSet.sharpId, bitmap.width, bitmap.height, blurRadius, nextSet.blurId, nextSet.width, nextSet.height)
        }

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
        if (pendingPlaylistBitmap != null) {
            processPlaylistTransition()
        }

        if (needsReload) {
            needsReload = false
            loadAndApplyTextures()
        }

        // gpuBlur/texture rebuilds change the viewport; restore it for screen drawing.
        if (surfaceWidth > 0 && surfaceHeight > 0) {
            GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
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
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uEnableNoise"), if (enableNoise) 1.0f else 0.0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uNoiseScale"), noiseScale)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uNoiseStrength"), noiseStrength)

        // Drawer/recents blur (0 = in view, sharp; 1 = out of view, blurred).
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uDrawerBlur"), drawerBlur)

        // Horizontal scroll window (identity 0f/1f = no scroll, draws as before).
        // The off-screen blur passes never set these, so they default to identity.
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uScrollOffsetX"), scrollOffsetX)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uScrollWindowX"), currentWindowX)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, currentSet.sharpId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTextureSharp"), 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,currentSet.blurId )
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTextureBlur"), 1)

        // No subject mask on this effect, so no depth argument.
        clockOverlay.draw(
            programId = programId,
            progress = blurStrength,
            screenAspect = aspectRatio
        )

        val aPosLoc = GLES30.glGetAttribLocation(programId, "aPosition")
        val aTexLoc = GLES30.glGetAttribLocation(programId, "aTexCoord")
        drawQuad(aPosLoc, aTexLoc)
    }

    private fun createEmptyTexture(width: Int, height: Int, existingTextureId: Int = 0, existingWidth: Int = 0, existingHeight: Int = 0): Int {
        val t = if (existingTextureId != 0) intArrayOf(existingTextureId) else { val arr = IntArray(1); GLES30.glGenTextures(1, arr, 0); arr }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        if (existingTextureId == 0 || existingWidth != width || existingHeight != height) {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        }
        return t[0]
    }

    private fun gpuBlur(inputTexture: Int, width: Int, height: Int, radius: Float, targetOutputId: Int = 0, existingWidth: Int = 0, existingHeight: Int = 0): Int {
        val outputTexture = createEmptyTexture(width, height, targetOutputId, existingWidth, existingHeight)
        GLES30.glUseProgram(blurProgramId)
        val aPosLoc = GLES30.glGetAttribLocation(blurProgramId, "aPosition")
        val aTexLoc = GLES30.glGetAttribLocation(blurProgramId, "aTexCoord")
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tempTextureId, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(blurProgramId, "uTexture"), 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(blurProgramId, "uDirection"), 1f, 0f)
        val safeRadius = if (radius < 1f) 1f else radius
        GLES30.glUniform1f(GLES30.glGetUniformLocation(blurProgramId, "uRadius"), safeRadius)
        drawQuad(aPosLoc, aTexLoc)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, outputTexture, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tempTextureId)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(blurProgramId, "uDirection"), 0f, 1f)
        drawQuad(aPosLoc, aTexLoc)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return outputTexture
    }

    private fun drawQuad(aPosLoc: Int, aTexLoc: Int) {
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
