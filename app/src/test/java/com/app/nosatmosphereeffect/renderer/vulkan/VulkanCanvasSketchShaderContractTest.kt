package com.app.nosatmosphereeffect.renderer.vulkan

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanCanvasSketchShaderContractTest {
    @Test
    fun canvasUsesTheDeviceTestedAndroidBitmapOrientation() {
        val shader = File(
            "src/main/shaders/canvas_sketch/canvas_sketch.vert"
        ).readText()

        assertTrue(shader.contains("vec2(0.0, 0.0)"))
        assertTrue(shader.contains("vec2(2.0, 0.0)"))
        assertTrue(shader.contains("vec2(0.0, 2.0)"))
    }

    @Test
    fun canvasCompositionRetainsOpenGlLineAndBlendConstants() {
        val shader = File(
            "src/main/shaders/canvas_sketch/canvas_sketch.frag"
        ).readText()

        assertTrue(shader.contains("baseWidth * 0.78"))
        assertTrue(shader.contains("width * 0.45 + 1.1"))
        assertTrue(shader.contains("smoothstep(0.02, 0.98, imageAmount)"))
        assertTrue(shader.contains("vec3(0.76)"))
        assertTrue(shader.contains("vec3(0.96)"))
    }

    @Test
    fun compiledCanvasShadersAndNativeAdapterArePackaged() {
        listOf("canvas_sketch.vert.spv", "canvas_sketch.frag.spv").forEach { name ->
            val binary = File(
                "src/main/assets/shaders/vulkan/canvas_sketch",
                name
            )
            assertTrue(name, binary.isFile)
            assertTrue(name, binary.length() > 20L)
        }

        val adapter = File("src/main/cpp/vulkan_canvas_sketch_jni.cpp").readText()
        assertTrue(adapter.contains("\"Atmo Canvas Sketch\""))
        assertTrue(adapter.contains("kContourBinding = 1"))
        assertTrue(adapter.contains("sizeof(CanvasParams)"))
    }
}
