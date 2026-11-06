package com.app.nosatmosphereeffect.wallpaper

import android.view.SurfaceHolder
import java.lang.reflect.Proxy
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class WallpaperSurfaceHolderStateTest {
    @Test
    fun `the holder passed by Android remains available for renderer fallback`() {
        val state = WallpaperSurfaceHolderState()
        val createdHolder = fakeHolder()
        val callbackHolder = fakeHolder()

        state.remember(createdHolder)
        assertSame(createdHolder, state.requireHolder())

        state.remember(callbackHolder)
        assertSame(callbackHolder, state.requireHolder())
    }

    @Test
    fun `destroy clears the retained holder`() {
        val state = WallpaperSurfaceHolderState()
        state.remember(fakeHolder())
        state.clear()

        try {
            state.requireHolder()
            fail("A destroyed engine must not expose its old SurfaceHolder")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun `the construction holder is available before subclass fields initialize`() {
        val holder = fakeHolder()

        assertNull(WallpaperSurfaceHolderConstruction.holder())
        WallpaperSurfaceHolderConstruction.withHolder(holder) {
            assertSame(holder, WallpaperSurfaceHolderConstruction.holder())
        }
        assertNull(WallpaperSurfaceHolderConstruction.holder())
    }

    @Test
    fun `the construction holder is cleared when view creation fails`() {
        val holder = fakeHolder()

        try {
            WallpaperSurfaceHolderConstruction.withHolder(holder) {
                error("view construction failed")
            }
            fail("The failing factory must throw")
        } catch (_: IllegalStateException) {
        }
        assertNull(WallpaperSurfaceHolderConstruction.holder())
    }

    private fun fakeHolder(): SurfaceHolder {
        return Proxy.newProxyInstance(
            SurfaceHolder::class.java.classLoader,
            arrayOf(SurfaceHolder::class.java)
        ) { proxy, method, _ ->
            when (method.name) {
                "toString" -> "FakeSurfaceHolder"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> false
                else -> null
            }
        } as SurfaceHolder
    }
}
