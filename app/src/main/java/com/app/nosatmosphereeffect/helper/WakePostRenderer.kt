package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Wraps any effect's OpenGL ES renderer with the lock screen's post-pass
 * wake transitions (everything but Zoom): while one plays, the finished frame is copied out
 * and drawn back through wake.frag. Otherwise it only forwards the calls.
 */
internal class WakePostRenderer(
    private val context: Context,
    private val inner: GLSurfaceView.Renderer
) : GLSurfaceView.Renderer {
    private var program = 0
    private var texture = 0
    private var vertexArray = 0
    private var textureWidth = 0
    private var textureHeight = 0
    private var width = 0
    private var height = 0
    private var failed = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new context: everything made in the old one is gone with it.
        program = 0
        texture = 0
        vertexArray = 0
        textureWidth = 0
        textureHeight = 0
        failed = false
        inner.onSurfaceCreated(gl, config)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
        inner.onSurfaceChanged(gl, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        inner.onDrawFrame(gl)
        val mode = WakeTransition.postMode
        val amount = WakeTransition.postAmount
        if (mode == WakeTransition.MODE_NONE || amount <= 0f || failed || width <= 0 || height <= 0) return
        runCatching { drawWake(mode, amount) }.onFailure {
            failed = true
            Log.w(TAG, "Wake transition unavailable; drawing without it", it)
        }
    }

    private fun drawWake(mode: Int, amount: Float) {
        if (program == 0) {
            program = buildProgram()
            vertexArray = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }[0]
            texture = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        if (textureWidth != width || textureHeight != height) {
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB, width, height, 0,
                GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, null
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            textureWidth = width
            textureHeight = height
        }
        GLES30.glCopyTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height)

        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uFrame"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uMode"), mode)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uAmount"), amount.coerceAtMost(1f))
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uAspect"), width.toFloat() / height)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uParam"), WakeTransition.param)
        GLES30.glUniform3f(
            GLES30.glGetUniformLocation(program, "uParams"),
            WakeTransition.paramX,
            WakeTransition.paramY,
            WakeTransition.paramZ
        )
        // Our own empty vertex array: the effect may have left client-side
        // attribute arrays enabled, which this draw must never read.
        GLES30.glBindVertexArray(vertexArray)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    private fun buildProgram(): Int {
        val vertex = compile(GLES30.GL_VERTEX_SHADER, "shaders/wake/wake.vert")
        val fragment = compile(GLES30.GL_FRAGMENT_SHADER, "shaders/wake/wake.frag")
        val id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vertex)
        GLES30.glAttachShader(id, fragment)
        GLES30.glLinkProgram(id)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) { "Wake program: ${GLES30.glGetProgramInfoLog(id)}" }
        return id
    }

    private fun compile(type: Int, asset: String): Int {
        val source = context.assets.open(asset).bufferedReader().use { it.readText() }
        val id = GLES30.glCreateShader(type)
        GLES30.glShaderSource(id, source)
        GLES30.glCompileShader(id)
        val status = IntArray(1)
        GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) { "$asset: ${GLES30.glGetShaderInfoLog(id)}" }
        return id
    }

    private companion object {
        const val TAG = "WakePostRenderer"
    }
}
