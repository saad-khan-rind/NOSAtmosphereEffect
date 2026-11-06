package com.app.nosatmosphereeffect.renderer.gles

import android.opengl.GLES30

/**
 * Binds the two textures every effect draws the wallpaper from: the photo
 * and the subject mask. Each renderer binds them through here for every
 * frame it draws, so how the wallpaper reaches a frame is decided in one
 * place, whatever the effect does with it. GL thread only.
 */
internal object SceneTextures {

    /** The photo [textureId] on texture [unit], with the program's sampler at [samplerLocation] pointed at it. */
    fun bindPhoto(textureId: Int, unit: Int, samplerLocation: Int) = bind(textureId, unit, samplerLocation)

    /** The subject mask [textureId] on texture [unit], with the program's sampler at [samplerLocation] pointed at it. */
    fun bindSubjectMask(textureId: Int, unit: Int, samplerLocation: Int) = bind(textureId, unit, samplerLocation)

    private fun bind(textureId: Int, unit: Int, samplerLocation: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(samplerLocation, unit)
    }
}
