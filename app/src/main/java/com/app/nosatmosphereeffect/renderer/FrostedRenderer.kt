package com.app.nosatmosphereeffect.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import androidx.core.graphics.createBitmap
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class FrostedRenderer(private val context: Context) : GLSurfaceView.Renderer {

    // --- RING BUFFER LOGIC ---
    private class TextureSet {
        var sharpId = 0
        var blurId = 0
        fun isValid() = sharpId != 0 && blurId != 0
        fun reset() { sharpId = 0; blurId = 0 }
    }

    private var currentSet = TextureSet()
    private var nextSet = TextureSet() // The "Back Buffer"

    @Volatile private var pendingPlaylistBitmap: Bitmap? = null
    // -------------------------

    var blurStrength: Float = 0.0f
    @Volatile var dimLevel: Float = 0.2f
    @Volatile private var needsReload: Boolean = false
    @Volatile var enableNoise: Boolean = false
    @Volatile var noiseScale: Float = 2000.0f
    @Volatile var noiseStrength: Float = 0.06f
    @Volatile var blurRadius: Float = 200.0f

    private var programId: Int = 0
    private var blurProgramId: Int = 0
    private var tempTextureId: Int = 0
    private var fboId: Int = 0
    private var aspectRatio: Float = 1.0f

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

        loadAndApplyTextures()
    }

    private fun loadAndApplyTextures() {
        // Destroy ONLY current set
        if (currentSet.isValid()) {
            val ids = intArrayOf(currentSet.sharpId, currentSet.blurId)
            GLES30.glDeleteTextures(2, ids, 0)
            currentSet.reset()
        }
        if (tempTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(tempTextureId), 0)
            tempTextureId = 0
        }

        val sharpBitmap = loadFixedWallpaper()

        currentSet.sharpId = uploadTexture(sharpBitmap)
        tempTextureId = createEmptyTexture(sharpBitmap.width, sharpBitmap.height)

        if (blurRadius < 1.0f) {
            currentSet.blurId = uploadTexture(sharpBitmap)
        } else {
            currentSet.blurId = gpuBlur(currentSet.sharpId, sharpBitmap.width, sharpBitmap.height, blurRadius)
        }
        sharpBitmap.recycle()
    }

    private fun processPlaylistTransition() {
        val bitmap = pendingPlaylistBitmap ?: return

        // Overwrite the existing nextSet IDs instead of deleting them
        nextSet.sharpId = uploadTexture(bitmap, nextSet.sharpId)
        tempTextureId = createEmptyTexture(bitmap.width, bitmap.height, tempTextureId)

        if (blurRadius < 1.0f) {
            nextSet.blurId = uploadTexture(bitmap, nextSet.blurId)
        } else {
            nextSet.blurId = gpuBlur(nextSet.sharpId, bitmap.width, bitmap.height, blurRadius, nextSet.blurId)
        }

        bitmap.recycle() // Done with raw bitmap

        // SWAP! Old current becomes next
        val temp = currentSet
        currentSet = nextSet
        nextSet = temp

        pendingPlaylistBitmap = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspectRatio = width.toFloat() / height.toFloat()
    }

    override fun onDrawFrame(gl: GL10?) {
        if (pendingPlaylistBitmap != null) {
            processPlaylistTransition()
        }

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
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uEnableNoise"), if (enableNoise) 1.0f else 0.0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uNoiseScale"), noiseScale)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uNoiseStrength"), noiseStrength)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, currentSet.sharpId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTextureSharp"), 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,currentSet.blurId )
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTextureBlur"), 1)

        val aPosLoc = GLES30.glGetAttribLocation(programId, "aPosition")
        val aTexLoc = GLES30.glGetAttribLocation(programId, "aTexCoord")
        drawQuad(aPosLoc, aTexLoc)
    }

    private fun createEmptyTexture(width: Int, height: Int, existingTextureId: Int = 0): Int {
        val t = if (existingTextureId != 0) intArrayOf(existingTextureId) else { val arr = IntArray(1); GLES30.glGenTextures(1, arr, 0); arr }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    private fun gpuBlur(inputTexture: Int, width: Int, height: Int, radius: Float, targetOutputId: Int = 0): Int {
        val outputTexture = createEmptyTexture(width, height, targetOutputId)
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

    private fun uploadTexture(bitmap: Bitmap, existingTextureId: Int = 0): Int {
        val textureHandle = if (existingTextureId != 0) intArrayOf(existingTextureId) else { val arr = IntArray(1); GLES30.glGenTextures(1, arr, 0); arr }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureHandle[0])
        // Keep mipmaps for Frosted!
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

    private fun loadFixedWallpaper(): Bitmap {
        val file = File(context.filesDir, "wallpaper.jpg")
        if (file.exists()) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                return bitmap
            }
        }
        val fallback = createBitmap(1080, 1920)
        fallback.eraseColor(Color.BLUE)
        return fallback
    }
}