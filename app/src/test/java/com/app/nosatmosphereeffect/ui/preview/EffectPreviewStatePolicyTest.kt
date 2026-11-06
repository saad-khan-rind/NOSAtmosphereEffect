package com.app.nosatmosphereeffect.ui.preview

import com.app.nosatmosphereeffect.renderer.atmosphere.AtmosphereRenderState
import com.app.nosatmosphereeffect.renderer.colorfill.ColorFillRenderState
import com.app.nosatmosphereeffect.renderer.frosted.FrostedRenderState
import com.app.nosatmosphereeffect.renderer.glass.GlassRenderState
import com.app.nosatmosphereeffect.renderer.halftone.HalftoneRenderState
import com.app.nosatmosphereeffect.renderer.canvassketch.CanvasSketchRenderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectPreviewStatePolicyTest {
    @Test
    fun progressUpdatesEveryPreviewStateWithoutDroppingFineTuneValues() {
        val states = listOf(
            EffectPreviewRenderState.Frosted(
                FrostedRenderState(noiseScale = 345f, blurRadius = 90f)
            ),
            EffectPreviewRenderState.Glass(
                GlassRenderState(lineCount = 24, lineThickness = 0.9f)
            ),
            EffectPreviewRenderState.Halftone(
                HalftoneRenderState(dotSize = 18f, grayscale = true)
            ),
            EffectPreviewRenderState.ColorFill(
                ColorFillRenderState(originX = 0.2f, originY = 0.7f)
            ),
            EffectPreviewRenderState.CanvasSketch(
                CanvasSketchRenderState(lineWidth = 2.5f, sensitivity = 0.8f)
            )
        )

        states.forEach { original ->
            val updated = EffectPreviewStatePolicy.withProgress(
                state = original,
                progress = 0.65f,
                atmosphereGlassEnabled = false,
                atmosphereGlassBackgroundOnly = false
            )
            assertEquals(0.65f, EffectPreviewStatePolicy.progress(updated), 0f)
            when {
                original is EffectPreviewRenderState.Frosted &&
                    updated is EffectPreviewRenderState.Frosted -> {
                    assertEquals(original.value.noiseScale, updated.value.noiseScale, 0f)
                    assertEquals(original.value.blurRadius, updated.value.blurRadius, 0f)
                }
                original is EffectPreviewRenderState.Glass &&
                    updated is EffectPreviewRenderState.Glass -> {
                    assertEquals(original.value.lineCount, updated.value.lineCount)
                    assertEquals(
                        original.value.lineThickness,
                        updated.value.lineThickness,
                        0f
                    )
                }
                original is EffectPreviewRenderState.Halftone &&
                    updated is EffectPreviewRenderState.Halftone -> {
                    assertEquals(original.value.dotSize, updated.value.dotSize, 0f)
                    assertEquals(original.value.grayscale, updated.value.grayscale)
                }
                original is EffectPreviewRenderState.ColorFill &&
                    updated is EffectPreviewRenderState.ColorFill -> {
                    assertEquals(original.value.originX, updated.value.originX, 0f)
                    assertEquals(original.value.originY, updated.value.originY, 0f)
                }
                original is EffectPreviewRenderState.CanvasSketch &&
                    updated is EffectPreviewRenderState.CanvasSketch -> {
                    assertEquals(original.value.lineWidth, updated.value.lineWidth, 0f)
                    assertEquals(original.value.sensitivity, updated.value.sensitivity, 0f)
                }
            }
        }
    }

    @Test
    fun atmosphereAppliedStateCanDisableAndRestoreGlassIsolation() {
        val original = EffectPreviewRenderState.Atmosphere(
            AtmosphereRenderState(
                glassEnabled = true,
                glassBackgroundOnly = true,
                glassLineCount = 28
            )
        )
        val disabled = EffectPreviewStatePolicy.withProgress(
            state = original,
            progress = 0f,
            atmosphereGlassEnabled = false,
            atmosphereGlassBackgroundOnly = true
        ) as EffectPreviewRenderState.Atmosphere
        assertFalse(disabled.value.glassEnabled)
        assertFalse(disabled.value.glassBackgroundOnly)

        val restored = EffectPreviewStatePolicy.withProgress(
            state = disabled,
            progress = 1f,
            atmosphereGlassEnabled = true,
            atmosphereGlassBackgroundOnly = true
        ) as EffectPreviewRenderState.Atmosphere
        assertTrue(restored.value.glassEnabled)
        assertTrue(restored.value.glassBackgroundOnly)
        assertEquals(28, restored.value.glassLineCount)
    }

    @Test
    fun invalidPreviewProgressIsSanitizedBeforeEitherBackendReceivesIt() {
        val state = EffectPreviewRenderState.ColorFill(ColorFillRenderState())
        val updated = EffectPreviewStatePolicy.withProgress(
            state = state,
            progress = Float.NaN,
            atmosphereGlassEnabled = false,
            atmosphereGlassBackgroundOnly = false
        )
        assertEquals(0f, EffectPreviewStatePolicy.progress(updated), 0f)
    }

    @Test
    fun openGlAnimationFramesCarryOnlyProgressForEveryRendererFamily() {
        val states = listOf(
            EffectPreviewRenderState.Atmosphere(
                AtmosphereRenderState(
                    progress = 0.15f,
                    glassEnabled = true,
                    glassBackgroundOnly = true,
                    glassLineCount = 34
                )
            ),
            EffectPreviewRenderState.Frosted(
                FrostedRenderState(progress = 0.25f, blurRadius = 310f)
            ),
            EffectPreviewRenderState.Glass(
                GlassRenderState(
                    progress = 0.35f,
                    lineCount = 36,
                    backgroundOnly = true
                )
            ),
            EffectPreviewRenderState.Halftone(
                HalftoneRenderState(
                    progress = 0.45f,
                    dotSize = 24f,
                    backgroundOnly = true,
                    hasSubject = true
                )
            ),
            EffectPreviewRenderState.ColorFill(
                ColorFillRenderState(
                    progress = 0.55f,
                    originX = 0.12f,
                    originY = 0.91f
                )
            ),
            EffectPreviewRenderState.CanvasSketch(
                CanvasSketchRenderState(
                    progress = 0.65f,
                    sensitivity = 0.88f,
                    subjectSegmentationEnabled = true
                )
            )
        )

        val updates = states.map(EffectPreviewStatePolicy::openGlFrameUpdate)

        assertEquals(
            listOf(
                EffectPreviewOpenGlFrameUpdate.Atmosphere(0.15f),
                EffectPreviewOpenGlFrameUpdate.Frosted(0.25f),
                EffectPreviewOpenGlFrameUpdate.Glass(0.35f),
                EffectPreviewOpenGlFrameUpdate.Halftone(0.45f),
                EffectPreviewOpenGlFrameUpdate.ColorFill(0.55f),
                EffectPreviewOpenGlFrameUpdate.CanvasSketch(0.65f)
            ),
            updates
        )
        updates.forEach { update ->
            assertEquals(
                listOf("progress"),
                update.javaClass.declaredFields
                    .map { it.name }
                    .filterNot { it.startsWith("$") }
            )
        }
    }
}
