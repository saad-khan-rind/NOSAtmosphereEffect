package com.app.nosatmosphereeffect.helper

internal object PlaylistFilePolicy {
    // .jpg: a copy fitted to the screen; .ref: a pointer to a folder image (PlaylistImageRef).
    private val imageName = Regex("""^wallpaper_(\d+)\.(jpg|ref)$""")

    fun index(fileName: String): Int? {
        return imageName.matchEntire(fileName)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }
}
