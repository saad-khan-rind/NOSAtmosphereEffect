package com.app.nosatmosphereeffect.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.app.nosatmosphereeffect.helper.ClockBoxRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.max

/**
 * The clock calibration preview: the wallpaper with the glass clock drawn over
 * it, exactly where the live wallpaper would put it.
 *
 * ## Why this does not reuse the wallpaper renderers
 *
 * It used to run the real effect through EffectPreviewService, which stands up
 * an OpenGL or Vulkan surface, picks a backend, uploads textures and drives a
 * render loop — for a still preview of where some digits sit. Every one of
 * those steps could leave the calibration screen showing nothing, which is
 * exactly what it did. This draws the same glass with a runtime shader on the
 * ordinary view canvas: no surface, no backend choice, no upload path, and no
 * way for the clock to be invisible while the user is positioning it.
 *
 * The maths is a port of `clockGlass` in the effect shaders, so what is shown
 * here is what the wallpaper draws. The backdrop is the plain photo rather
 * than the effect's own output: the clock is positioned against the image, and
 * the effect only changes what is *behind* the glass.
 */
@Composable
internal fun ClockGlassPreview(
    wallpaper: Bitmap?,
    /** The rendered clock face; its alpha is the glyph shape. */
    face: Bitmap?,
    /** Where the face goes, as fractions of this view. */
    box: ClockBoxRect,
    opacity: Float,
    /**
     * The shaders' own glass number: 3 is the original glass face, and
     * `1 + frost` a translucent one — see [ClockOverlayState.glassMeta].
     */
    mode: Float,
    /** Changes whenever [face] has been redrawn in place. */
    faceRevision: Int,
    modifier: Modifier = Modifier
) {
    // Compiled by the driver, so a failure can only show up here. The preview
    // must survive it: falling back to the plain face keeps the clock visible
    // and draggable, which is the whole point of this screen.
    val shader = remember { runCatching { RuntimeShader(GLASS_AGSL) }.getOrNull() }
    val paint = remember { Paint() }
    val photo = wallpaper?.takeIf { !it.isRecycled }
    val glyphs = face?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
    // Built per bitmap rather than per frame, and sampled linearly: the face
    // is scaled down into the box, and point sampling made the digits ragged
    // where the wallpaper's own sampler makes them smooth.
    val wallpaperShader = remember(photo) {
        photo?.let {
            BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                filterMode = BitmapShader.FILTER_MODE_LINEAR
            }
        }
    }
    val faceShader = remember(glyphs) {
        glyphs?.let {
            BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                filterMode = BitmapShader.FILTER_MODE_LINEAR
            }
        }
    }
    val wallpaperMatrix = remember { Matrix() }
    val faceMatrix = remember { Matrix() }

    Canvas(modifier) {
        val viewWidth = size.width
        val viewHeight = size.height
        if (viewWidth <= 0f || viewHeight <= 0f) return@Canvas
        // Read so a redrawn face repaints even though the bitmap is the same
        // instance the renderer keeps reusing.
        @Suppress("UNUSED_EXPRESSION")
        faceRevision

        if (photo == null || wallpaperShader == null) {
            drawRect(color = Color.Black)
            return@Canvas
        }

        // Centre-crop, the way the wallpaper itself is fitted.
        val scale = max(viewWidth / photo.width, viewHeight / photo.height)
        wallpaperMatrix.setScale(scale, scale)
        wallpaperMatrix.postTranslate(
            (viewWidth - photo.width * scale) / 2f,
            (viewHeight - photo.height * scale) / 2f
        )
        wallpaperShader.setLocalMatrix(wallpaperMatrix)

        val boxLeft = box.left * viewWidth
        val boxTop = box.top * viewHeight
        val boxWidth = box.width * viewWidth
        val boxHeight = box.height * viewHeight
        // The runtime shader draws the solid face too: the texture is a
        // distance field, and only the shader rebuilds a sharp edge from it.
        val drawGlass = shader != null &&
            faceShader != null &&
            glyphs != null &&
            boxWidth > 1f &&
            boxHeight > 1f &&
            opacity > 0f

        if (!drawGlass) {
            paint.shader = wallpaperShader
            drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, viewWidth, viewHeight, paint) }
            // Either the face is solid, or the runtime shader is unavailable
            // and the clock still has to be visible to be positioned.
            if ((mode <= 0.5f || shader == null) &&
                glyphs != null &&
                opacity > 0f &&
                boxWidth > 1f
            ) {
                drawImage(
                    image = glyphs.asImageBitmap(),
                    dstOffset = IntOffset(boxLeft.toInt(), boxTop.toInt()),
                    dstSize = IntSize(
                        boxWidth.toInt().coerceAtLeast(1),
                        boxHeight.toInt().coerceAtLeast(1)
                    ),
                    alpha = opacity.coerceIn(0f, 1f)
                )
            }
            return@Canvas
        }
        checkNotNull(shader)
        checkNotNull(faceShader)
        checkNotNull(glyphs)

        faceMatrix.setScale(boxWidth / glyphs.width, boxHeight / glyphs.height)
        faceMatrix.postTranslate(boxLeft, boxTop)
        faceShader.setLocalMatrix(faceMatrix)

        shader.setInputShader("wallpaper", wallpaperShader)
        shader.setInputShader("face", faceShader)
        shader.setFloatUniform("boxOrigin", boxLeft, boxTop)
        shader.setFloatUniform("boxSize", boxWidth, boxHeight)
        // Measured in face-texture texels, exactly as the wallpaper shader
        // does it, so the bevel is the same width on the same clock rather
        // than a fraction of the box (which made it far softer here).
        // The wallpaper shaders work in texture UV, where x spans the width
        // and y the height, so the same UV step is fewer pixels across than
        // down. These convert each of their offsets into view pixels.
        val refraction = REFRACTION_FRACTION * boxHeight
        shader.setFloatUniform(
            "refraction",
            refraction * viewWidth / viewHeight,
            refraction
        )
        shader.setFloatUniform(
            "magnification",
            MAGNIFICATION * boxWidth,
            MAGNIFICATION * boxHeight
        )
        shader.setFloatUniform("blurMin", FROST_MIN * viewWidth, FROST_MIN * viewHeight)
        shader.setFloatUniform("blurMax", FROST_MAX * viewWidth, FROST_MAX * viewHeight)
        shader.setFloatUniform("mode", mode)
        shader.setFloatUniform(
            "refractionClassic",
            CLASSIC_REFRACTION * boxHeight * viewWidth / viewHeight,
            CLASSIC_REFRACTION * boxHeight
        )
        shader.setFloatUniform(
            "blurClassic",
            CLASSIC_BLUR * viewWidth,
            CLASSIC_BLUR * viewHeight
        )
        shader.setFloatUniform("opacity", opacity.coerceIn(0f, 1f))
        paint.shader = shader
        drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, viewWidth, viewHeight, paint) }
    }
}

/** The refraction offset at the silhouette, as a fraction of the clock's height. */
private const val REFRACTION_FRACTION = 0.030f
/** How much the glass magnifies what is behind it, at the crown of a stroke. */
private const val MAGNIFICATION = 0.060f
/** The original face's refraction and softening, as it has always had them. */
private const val CLASSIC_REFRACTION = 0.11f
private const val CLASSIC_BLUR = 0.0032f
/** The frost blur radius in wallpaper UV, at no frost and at full frost. */
private const val FROST_MIN = 0.0012f
private const val FROST_MAX = 0.0110f

private const val GLASS_AGSL = """
uniform shader wallpaper;
uniform shader face;
uniform float2 boxOrigin;
uniform float2 boxSize;
uniform float2 refraction;
uniform float2 refractionClassic;
uniform float2 magnification;
uniform float2 blurMin;
uniform float2 blurMax;
uniform float2 blurClassic;
uniform float mode;
uniform float opacity;

float faceField(float2 coord) {
    return float(face.eval(coord).a);
}

half4 main(float2 coord) {
    float3 base = float3(wallpaper.eval(coord).rgb);
    float2 uv = (coord - boxOrigin) / max(boxSize, float2(1.0));
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        return half4(half3(base), 1.0);
    }

    // A port of clockGlass in the effect shaders; see the comments there. The
    // face texture holds a distance field, so the silhouette is reconstructed
    // here rather than sampled, and the gradient is a true surface direction.
    float4 glyph = float4(face.eval(coord));
    float field = glyph.a;
    // AGSL has no derivatives, so the field's slope per pixel is measured
    // directly — which is the same number fwidth would have returned.
    float2 gradient = 0.5 * float2(
        faceField(coord + float2(1.0, 0.0)) - faceField(coord - float2(1.0, 0.0)),
        faceField(coord + float2(0.0, 1.0)) - faceField(coord - float2(0.0, 1.0))
    );
    float softness = clamp(length(gradient), 0.0008, 0.25);
    float coverage = smoothstep(0.5 - softness, 0.5 + softness, field);
    if (coverage <= 0.002) {
        return half4(half3(base), 1.0);
    }
    float3 tint = glyph.rgb / max(field, 0.001);
    // A solid face (the Adaptive clock): its colour, no glass.
    if (mode < 0.5) {
        return half4(half3(mix(base, tint, coverage * opacity)), 1.0);
    }
    float2 inward = gradient / max(length(gradient), 1e-5);
    float depth = clamp((field - 0.5) * 2.0, 0.0, 1.0);
    float3 light = normalize(float3(-0.5, -0.72, 0.48));

    if (mode >= 2.5) {
        // The bevel is the difference of the silhouette taken a bevel's width
        // apart, exactly as the effect shaders do it — see the long note
        // there for why it is not read off the field directly.
        float edge = 0.0;
        float2 slope = float2(0.0);
        // Faded and bounded towards the middle of a stroke, for the reasons
        // set out in the effect shaders.
        float confidence = 1.0 - smoothstep(0.45, 0.70, depth);
        if (confidence > 0.002) {
            float spreadPx = clamp(0.5 / max(length(gradient), 1e-4), 2.0, 96.0);
            // The same share of the spread the effect shaders use.
            float reach = spreadPx * 0.66;
            float aa = 0.5 / spreadPx;
            float lo = 0.5 - aa;
            float hi = 0.5 + aa;
            slope = 0.5 * float2(
                smoothstep(lo, hi, faceField(coord + float2(reach, 0.0))) -
                    smoothstep(lo, hi, faceField(coord - float2(reach, 0.0))),
                smoothstep(lo, hi, faceField(coord + float2(0.0, reach))) -
                    smoothstep(lo, hi, faceField(coord - float2(0.0, reach)))
            );
            slope = slope * confidence;
            edge = clamp(length(slope) * 2.0, 0.0, 1.0);
        }
        float2 at = coord - slope * refractionClassic;
        float3 refracted = (
            2.0 * float3(wallpaper.eval(at).rgb) +
            float3(wallpaper.eval(at + float2(blurClassic.x, 0.0)).rgb) +
            float3(wallpaper.eval(at - float2(blurClassic.x, 0.0)).rgb) +
            float3(wallpaper.eval(at + float2(0.0, blurClassic.y)).rgb) +
            float3(wallpaper.eval(at - float2(0.0, blurClassic.y)).rgb)
        ) / 6.0;
        float3 normal = normalize(float3(-slope * 2.4, 1.0));
        float specular = pow(max(dot(normal, light), 0.0), 22.0) * edge;
        float rim = smoothstep(0.12, 0.85, edge);
        float shade = max(-dot(normal.xy, light.xy), 0.0) * edge;
        float3 glass = refracted * 1.05 + float3(0.035);
        glass = mix(glass, glass * tint, 0.55);
        glass = glass + float3(rim * 0.20 + specular * 0.9);
        glass = glass - float3(shade * 0.14);
        float3 classic = mix(base, clamp(glass, 0.0, 1.0), coverage * opacity);
        return half4(half3(classic), 1.0);
    }

    float frostLevel = clamp(mode - 1.0, 0.0, 1.0);
    float shoulder = 1.0 - depth;
    float lift = sqrt(max(1.0 - shoulder * shoulder, 1e-4));
    float slope = min(shoulder / lift, 6.0);
    float3 normal = normalize(float3(-inward * slope * 0.62, 1.0));

    float2 bend = inward * shoulder * refraction;
    float2 magnify = (uv - 0.5) * magnification * depth;
    float2 at = coord - bend - magnify;

    float3 refracted = float3(wallpaper.eval(at).rgb);
    if (frostLevel > 0.004) {
        float2 blur = mix(blurMin, blurMax, frostLevel);
        float2 diagonal = blur * 0.7;
        refracted = (
            2.0 * refracted +
            float3(wallpaper.eval(at + float2(blur.x, 0.0)).rgb) +
            float3(wallpaper.eval(at - float2(blur.x, 0.0)).rgb) +
            float3(wallpaper.eval(at + float2(0.0, blur.y)).rgb) +
            float3(wallpaper.eval(at - float2(0.0, blur.y)).rgb) +
            float3(wallpaper.eval(at + diagonal).rgb) +
            float3(wallpaper.eval(at - diagonal).rgb) +
            float3(wallpaper.eval(at + float2(diagonal.x, -diagonal.y)).rgb) +
            float3(wallpaper.eval(at - float2(diagonal.x, -diagonal.y)).rgb)
        ) / 10.0;
    }

    float3 glass = mix(refracted, refracted * tint, 0.55);
    if (frostLevel > 0.004) {
        float milk = dot(glass, float3(0.2126, 0.7152, 0.0722));
        glass = mix(glass, tint * (0.55 + 0.45 * milk), frostLevel * 0.92);
    }

    float3 fill = normalize(float3(0.55, 0.62, 0.55));
    float facing = dot(normal, light);
    float turn = shoulder * shoulder * shoulder;
    float sheen = max(facing, 0.0) * turn;
    float glint = pow(max(facing, 0.0), 22.0) * shoulder;
    float bounce = max(dot(normal, fill), 0.0) * turn;
    float shade = max(-facing, 0.0) * turn;
    float boundary = smoothstep(0.80, 1.0, shoulder);
    float polish = 1.0 - 0.75 * frostLevel;

    glass = glass + float3((sheen * 0.22 + glint * 0.5 + bounce * 0.12 + boundary * 0.16) * polish);
    glass = glass - float3(shade * 0.24 * polish);
    float3 result = mix(base, clamp(glass, 0.0, 1.0), coverage * opacity);
    return half4(half3(result), 1.0);
}
"""
