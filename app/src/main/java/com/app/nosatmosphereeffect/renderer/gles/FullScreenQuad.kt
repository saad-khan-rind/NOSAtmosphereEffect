package com.app.nosatmosphereeffect.renderer.gles

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** The rectangle an effect is drawn on, covering the surface: positions and texture coordinates. GL thread only. */
internal class FullScreenQuad {
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(VERTICES)
        .apply { position(0) }

    /** Draws it with [programId], feeding its "aPosition" and "aTexCoord" inputs. */
    fun draw(programId: Int) {
        val position = GLES30.glGetAttribLocation(programId, "aPosition")
        val texCoord = GLES30.glGetAttribLocation(programId, "aTexCoord")
        vertices.position(0)
        GLES30.glVertexAttribPointer(position, 2, GLES30.GL_FLOAT, false, STRIDE, vertices)
        GLES30.glEnableVertexAttribArray(position)
        vertices.position(2)
        GLES30.glVertexAttribPointer(texCoord, 2, GLES30.GL_FLOAT, false, STRIDE, vertices)
        GLES30.glEnableVertexAttribArray(texCoord)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(position)
        GLES30.glDisableVertexAttribArray(texCoord)
    }

    private companion object {
        const val STRIDE = 4 * 4
        val VERTICES = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f
        )
    }
}
