package com.app.nosatmosphereeffect.renderer.gles

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils

/** Uploads the effects' bitmaps (photos, masks, baked layers) as OpenGL ES textures. GL thread only. */
internal object GlesTexture {

    /**
     * [bitmap] as a clamped, linearly filtered texture: into [existingId]
     * when there is one, else a new texture. With [mipmaps], minified
     * smoothly too. Throws if OpenGL reports an error, naming [what]; a new
     * texture is deleted again when it does.
     */
    fun upload(bitmap: Bitmap, what: String, existingId: Int = 0, mipmaps: Boolean = false): Int {
        val isNew = existingId == 0
        val textureId = if (isNew) generate() else existingId
        check(textureId != 0) { "OpenGL did not create a texture for $what" }
        try {
            fill(textureId, bitmap, mipmaps)
            checkGlError("uploading $what")
            return textureId
        } catch (failure: RuntimeException) {
            if (isNew) delete(textureId)
            throw failure
        }
    }

    /** As [upload], for renderers that draw on regardless: OpenGL's errors aren't checked. */
    fun uploadUnchecked(bitmap: Bitmap, existingId: Int = 0, mipmaps: Boolean = false): Int {
        val textureId = if (existingId == 0) generate() else existingId
        fill(textureId, bitmap, mipmaps)
        return textureId
    }

    fun delete(textureId: Int) {
        if (textureId != 0) GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
    }

    private fun generate(): Int = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]

    private fun fill(textureId: Int, bitmap: Bitmap, mipmaps: Boolean) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MIN_FILTER,
            if (mipmaps) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        if (mipmaps) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
    }
}

/** Throws if OpenGL has recorded an error, saying it happened while [operation]. */
internal fun checkGlError(operation: String) {
    val error = GLES30.glGetError()
    check(error == GLES30.GL_NO_ERROR) { "OpenGL error 0x${error.toString(16)} while $operation" }
}
