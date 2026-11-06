package com.app.nosatmosphereeffect.renderer.gles

/**
 * The textures one wallpaper image is drawn from, as OpenGL texture names in
 * the renderer's context: its photo and, once segmentation has found one,
 * its subject mask ([generation] is the image the mask was made for).
 * Effects that derive more from the photo extend it. GL thread only.
 */
internal open class WallpaperTextureSet {
    var photoId = 0
    var maskId = 0
    var width = 0
    var height = 0
    var generation = 0L
    var hasSubject = false

    open fun isValid(): Boolean = photoId != 0

    /** Forgets every texture (they belong to a context that is gone, or were deleted). */
    open fun reset() {
        photoId = 0
        maskId = 0
        width = 0
        height = 0
        generation = 0L
        hasSubject = false
    }
}

/** A photo with a blurred copy, for the effects that blur it (Atmosphere, Frosted). */
internal class BlurredTextureSet : WallpaperTextureSet() {
    var blurId = 0

    override fun isValid(): Boolean = super.isValid() && blurId != 0

    override fun reset() {
        super.reset()
        blurId = 0
    }
}
