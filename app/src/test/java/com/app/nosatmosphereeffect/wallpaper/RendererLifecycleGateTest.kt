package com.app.nosatmosphereeffect.wallpaper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererLifecycleGateTest {

    @Test
    fun `callbacks stay blocked until a renderer is attached`() {
        val gate = RendererLifecycleGate()

        assertFalse(gate.canDispatchToRenderer())

        gate.markRendererAttached()

        assertTrue(gate.canDispatchToRenderer())
    }

    @Test
    fun `callbacks stay blocked after the engine is destroyed`() {
        val gate = RendererLifecycleGate()
        gate.markRendererAttached()

        gate.markDestroyed()
        gate.markRendererAttached()

        assertFalse(gate.canDispatchToRenderer())
    }

    @Test
    fun `a recreated engine can attach a fresh renderer`() {
        val gate = RendererLifecycleGate()
        gate.markRendererAttached()
        gate.markDestroyed()

        gate.reset()
        gate.markRendererAttached()

        assertTrue(gate.canDispatchToRenderer())
    }
}
