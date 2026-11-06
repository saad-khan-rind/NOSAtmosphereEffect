package com.app.nosatmosphereeffect.renderer.gles

import android.content.Context
import android.opengl.GLES30
import android.util.Log

/** Builds the effects' OpenGL ES shader programs. GL thread only. */
internal object GlesProgram {

    private const val TAG = "GlesProgram"

    /** A shader's source, from the app's assets. */
    fun readAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    /**
     * Compiles and links a program. Throws, naming [effect] and quoting the
     * driver's diagnostics, if either shader or the link fails; nothing is
     * left behind when it does.
     */
    fun link(effect: String, vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compile(effect, GLES30.GL_VERTEX_SHADER, vertexSource, "vertex")
        val fragmentShader = try {
            compile(effect, GLES30.GL_FRAGMENT_SHADER, fragmentSource, "fragment")
        } catch (failure: RuntimeException) {
            GLES30.glDeleteShader(vertexShader)
            throw failure
        }
        var program = 0
        try {
            program = GLES30.glCreateProgram()
            check(program != 0) { "OpenGL did not create the $effect shader program" }
            GLES30.glAttachShader(program, vertexShader)
            GLES30.glAttachShader(program, fragmentShader)
            GLES30.glLinkProgram(program)
            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val details = GLES30.glGetProgramInfoLog(program).ifBlank { "No linker diagnostics were returned" }
                throw IllegalStateException("$effect shader link failed: $details")
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

    /**
     * As [link], for renderers that carry on without the program: the
     * failure is logged and 0 returned, so they draw nothing rather than stop.
     */
    fun tryLink(effect: String, vertexSource: String, fragmentSource: String): Int = try {
        link(effect, vertexSource, fragmentSource)
    } catch (failure: RuntimeException) {
        Log.e(TAG, "Couldn't build the $effect shader program", failure)
        0
    }

    private fun compile(effect: String, type: Int, source: String, label: String): Int {
        val shader = GLES30.glCreateShader(type)
        check(shader != 0) { "OpenGL did not create the $effect $label shader" }
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val details = GLES30.glGetShaderInfoLog(shader).ifBlank { "No compiler diagnostics were returned" }
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("$effect $label shader compilation failed: $details")
        }
        return shader
    }
}
