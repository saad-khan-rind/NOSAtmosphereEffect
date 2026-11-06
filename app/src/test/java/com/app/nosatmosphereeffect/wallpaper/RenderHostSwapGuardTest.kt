package com.app.nosatmosphereeffect.wallpaper

import android.view.SurfaceHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderHostSwapGuardTest {
    @Test
    fun successfulReplayKeepsPreparedHostOpen() {
        val host = FakeRenderHost()
        var replayed: WallpaperRenderHost? = null

        val failure = RenderHostSwapGuard.prepare(host) {
            replayed = it
        }

        assertNull(failure)
        assertSame(host, replayed)
        assertEquals(0, host.closeCount)
    }

    @Test
    fun failedReplayClosesReplacementAndReturnsCause() {
        val host = FakeRenderHost()
        val expected = IllegalStateException("surface is gone")

        val failure = RenderHostSwapGuard.prepare(host) {
            throw expected
        }

        assertSame(expected, failure)
        assertEquals(1, host.closeCount)
    }

    @Test
    fun handoffQuiescesExpectedBeforeReplayingReplacement() {
        val events = mutableListOf<String>()
        val expected = FakeRenderHost(name = "expected", events = events)
        val replacement = FakeRenderHost(name = "replacement", events = events)

        val failure = RenderHostSwapGuard.prepareHandoff(
            expected = expected,
            replacement = replacement,
            quiesce = { host ->
                events += "quiesce:${host.label(expected, replacement)}"
            },
            replay = { host ->
                events += "replay:${host.label(expected, replacement)}"
            }
        )

        assertNull(failure)
        assertEquals(
            listOf("quiesce:expected", "replay:replacement"),
            events
        )
        assertEquals(0, expected.closeCount)
        assertEquals(0, replacement.closeCount)
    }

    @Test
    fun failedReplacementReplayRestoresExpectedHost() {
        val events = mutableListOf<String>()
        val expected = FakeRenderHost(name = "expected", events = events)
        val replacement = FakeRenderHost(name = "replacement", events = events)
        val replayFailure = IllegalStateException("replacement surface failed")

        val failure = RenderHostSwapGuard.prepareHandoff(
            expected = expected,
            replacement = replacement,
            quiesce = { host ->
                events += "quiesce:${host.label(expected, replacement)}"
            },
            replay = { host ->
                events += "replay:${host.label(expected, replacement)}"
                if (host === replacement) throw replayFailure
            }
        )

        assertSame(replayFailure, failure)
        assertEquals(
            listOf(
                "quiesce:expected",
                "replay:replacement",
                "close:replacement",
                "replay:expected"
            ),
            events
        )
        assertEquals(0, expected.closeCount)
        assertEquals(1, replacement.closeCount)
        assertTrue(failure?.suppressed?.isEmpty() == true)
    }

    private class FakeRenderHost(
        private val name: String = "host",
        private val events: MutableList<String>? = null
    ) : WallpaperRenderHost {
        var closeCount = 0

        override fun onSurfaceCreated(holder: SurfaceHolder) = Unit

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) = Unit

        override fun onSurfaceDestroyed(holder: SurfaceHolder) = Unit

        override fun onResume() = Unit

        override fun onPause() = Unit

        override fun requestRender() = Unit

        override fun setWallpaperOffset(xOffset: Float) = Unit

        override fun close() {
            closeCount++
            events?.add("close:$name")
        }
    }

    private fun WallpaperRenderHost.label(
        expected: WallpaperRenderHost,
        replacement: WallpaperRenderHost
    ): String = when (this) {
        expected -> "expected"
        replacement -> "replacement"
        else -> "unknown"
    }
}
