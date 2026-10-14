package com.app.nosatmosphereeffect.helper

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaylistFilePolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `playlist index accepts only the canonical image name`() {
        assertEquals(0, PlaylistFilePolicy.index("wallpaper_0.jpg"))
        assertEquals(42, PlaylistFilePolicy.index("wallpaper_42.jpg"))
        assertNull(PlaylistFilePolicy.index("wallpaper_-1.jpg"))
        assertNull(PlaylistFilePolicy.index("wallpaper_final.jpg"))
        assertNull(PlaylistFilePolicy.index("prefix_wallpaper_1.jpg"))
        assertNull(PlaylistFilePolicy.index("wallpaper_1.JPG"))
        assertNull(PlaylistFilePolicy.index("wallpaper_2147483648.jpg"))
    }

    @Test
    fun `image files are filtered and sorted by numeric index`() {
        val directory = temporaryFolder.newFolder("playlist")
        listOf(
            "wallpaper_10.jpg",
            "wallpaper_2.jpg",
            "wallpaper_1.jpg",
            "wallpaper_bad.jpg",
            "wallpaper_3.png",
            "notes.jpg"
        ).forEach { name ->
            check(File(directory, name).createNewFile())
        }
        check(File(directory, "wallpaper_4.jpg").mkdir())

        assertEquals(
            listOf("wallpaper_1.jpg", "wallpaper_2.jpg", "wallpaper_10.jpg"),
            PlaylistModeManager.imageFiles(directory).map(File::getName)
        )
    }

    @Test
    fun `folder pointers count as entries and a complete copy wins a shared index`() {
        assertEquals(5, PlaylistFilePolicy.index("wallpaper_5.ref"))
        assertNull(PlaylistFilePolicy.index("wallpaper_5.REF"))

        val directory = temporaryFolder.newFolder("mixed")
        listOf("wallpaper_0.ref", "wallpaper_1.jpg", "wallpaper_2.ref", "wallpaper_2.jpg", "wallpaper_3.ref")
            .forEach { name -> check(File(directory, name).createNewFile()) }

        assertEquals(
            listOf("wallpaper_0.ref", "wallpaper_1.jpg", "wallpaper_2.jpg", "wallpaper_3.ref"),
            PlaylistModeManager.imageFiles(directory).map(File::getName)
        )
        assertEquals("wallpaper_3.ref", PlaylistModeManager.entryFile(directory, 3)?.name)
        assertNull(PlaylistModeManager.entryFile(directory, 9))
    }

    @Test
    fun `missing playlist directory is treated as empty`() {
        assertEquals(
            emptyList<File>(),
            PlaylistModeManager.imageFiles(File(temporaryFolder.root, "missing"))
        )
    }
}
