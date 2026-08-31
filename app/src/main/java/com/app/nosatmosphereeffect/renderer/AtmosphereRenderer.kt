package com.app.nosatmosphereeffect.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import androidx.core.graphics.createBitmap
import com.app.nosatmosphereeffect.helper.AtmosphereClockPolicy
import com.app.nosatmosphereeffect.helper.ClockTextureProvider
import com.app.nosatmosphereeffect.helper.GlassEffectPolicy
import com.app.nosatmosphereeffect.helper.SubjectMaskCoordinator
import com.app.nosatmosphereeffect.helper.SubjectMaskDiagnostics
import com.app.nosatmosphereeffect.helper.WallpaperFitHelper
import com.app.nosatmosphereeffect.helper.WallpaperScrollRenderer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Random
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

class AtmosphereRenderer(
    private val context: Context,
    private val previewSource: (() -> Bitmap?)? = null
) : GLSurfaceView.Renderer, WallpaperScrollRenderer {

    @Volatile private var scrollOffsetX: Float = 0.5f
    private var currentWindowX: Float = 1f
    private var nextWindowX: Float = 1f

    override fun setWallpaperOffset(xOffset: Float) {
        scrollOffsetX = if (xOffset.isFinite()) xOffset.coerceIn(0f, 1f) else 0.5f
    }

    private class TextureSet {
        var sharpId = 0
        var blurId = 0
        var maskId = 0
        var width = 0
        var height = 0
        var generation = 0L
        var hasSubject = false
        fun isValid() = sharpId != 0 && blurId != 0
        fun reset() {
            sharpId = 0
            blurId = 0
            maskId = 0
            width = 0
            height = 0
            generation = 0L
            hasSubject = false
        }
    }

    private var currentSet = TextureSet()
    private var nextSet = TextureSet()

    private val pendingLock = Any()
    private var pendingPlaylistBitmap: Bitmap? = null
    @Volatile private var released = false
    @Volatile var onRenderRetryRequested: (() -> Unit)? = null
    @Volatile var onSubjectMaskUpdated: (() -> Unit)? = null
    private var renderFailureLogged = false
    private var renderRetryCount = 0
    private var generationCounter = 0L
    private val subjectMasks = SubjectMaskCoordinator(context) {
        onSubjectMaskUpdated?.invoke()
    }

    // Texture storage can only be reused while its dimensions still match.
    private var tempTextureWidth: Int = 0
    private var tempTextureHeight: Int = 0

    // Reused to avoid allocating a new GPU readback buffer for every image.
    private var cachedDownloadBuffer: ByteBuffer? = null

    @Volatile var blurStrength: Float = 0.0f
        set(value) {
            if (value == 0.0f && field != 0.0f) {
                reRollTargets()
            }
            field = value
        }
    @Volatile var dimLevel: Float = 0.2f
    @Volatile private var needsReload: Boolean = false
    @Volatile var enableNoise: Boolean = false
    @Volatile var noiseScale: Float = 2000.0f
    @Volatile var noiseStrength: Float = 0.06f

    @Volatile var blobSaturation: Float = 1.0f
    @Volatile var blobContrast: Float = 1.0f
    @Volatile var atmosphereGlassEnabled: Boolean = false
    @Volatile var glassLineCount: Int = GlassEffectPolicy.DEFAULT_LINE_COUNT
        set(value) {
            field = GlassEffectPolicy.sanitizeLineCount(value)
        }
    @Volatile var glassLineThickness: Float = GlassEffectPolicy.DEFAULT_LINE_THICKNESS
        set(value) {
            field = GlassEffectPolicy.sanitizeLineThickness(value)
        }

    // Depth-composited lock/home clock overlay (Advanced Settings toggle +
    // ClockAdjustActivity for position/size). GLES-only for now:
    // VulkanAtmosphereHost does not read this state, so devices on the
    // Vulkan backend won't show the clock until that path is implemented.
    @Volatile var clockEnabled: Boolean = false
    @Volatile var clockCenterX: Float = AtmosphereClockPolicy.DEFAULT_CENTER_X
        set(value) {
            field = AtmosphereClockPolicy.sanitizeCenterX(value)
        }
    @Volatile var clockTop: Float = AtmosphereClockPolicy.DEFAULT_TOP
        set(value) {
            field = AtmosphereClockPolicy.sanitizeTop(value)
        }
    @Volatile var clockHeight: Float = AtmosphereClockPolicy.DEFAULT_HEIGHT
        set(value) {
            field = AtmosphereClockPolicy.sanitizeHeight(value)
        }
    @Volatile var clockOpacity: Float = AtmosphereClockPolicy.DEFAULT_OPACITY
        set(value) {
            field = AtmosphereClockPolicy.sanitizeOpacity(value)
        }
    private val clockTexture = ClockTextureProvider(context)

    private var programId: Int = 0
    private var blurProgramId: Int = 0
    private var tempTextureId: Int = 0
    private var fboId: Int = 0
    private var aspectRatio: Float = 1.0f

    @Volatile private var surfaceWidth: Int = 0
    @Volatile private var surfaceHeight: Int = 0
    private var fittedForWidth: Int = -1
    private var fittedForHeight: Int = -1

    data class BlobPhysics(
        val color: FloatArray,
        val startX: Float, val startY: Float,
        var p1x: Float, var p1y: Float,
        var endX: Float, var endY: Float,
        var startSize: Float,
        var endSize: Float,
        val massScale: Float
    )

    private val MAX_BLOBS = 16
    private var blobs = mutableListOf<BlobPhysics>()
    private val random = Random()

    private val blobColorsBuffer = FloatArray(MAX_BLOBS * 3)
    private val blobPosBuffer = FloatArray(MAX_BLOBS * 2)
    private val blobSizesBuffer = FloatArray(MAX_BLOBS)

    private val vertices = floatArrayOf(
        -1f, -1f,  0f, 1f,
        1f, -1f,  1f, 1f,
        -1f,  1f,  0f, 0f,
        1f,  1f,  1f, 0f
    )
    private lateinit var vertexBuffer: FloatBuffer

    fun reloadTexture() {
        if (!released) {
            needsReload = true
        }
    }

    fun configureGlassBackgroundOnly(enabled: Boolean) {
        try {
            val changed = subjectMasks.configure(enabled)
            if (
                enabled &&
                currentSet.isValid() &&
                (changed || !currentSet.hasSubject)
            ) {
                needsReload = true
            }
        } catch (failure: Exception) {
            // Best-effort visual feature — never let a failure here take
            // down the caller (this runs on the main thread via the
            // preferences broadcast receiver, so an uncaught exception
            // here would crash the whole app, not just this effect).
            Log.w(TAG, "Could not configure background-only mode", failure)
            SubjectMaskDiagnostics.recordFailure("Enabling background-only", failure)
        }
    }

    internal val glassBackgroundOnlyEnabled: Boolean
        get() = subjectMasks.enabled

    fun queuePlaylistTransition(bitmap: Bitmap) {
        if (bitmap.isRecycled) return

        var rejected = false
        val replaced = synchronized(pendingLock) {
            if (released) {
                rejected = true
                null
            } else {
                pendingPlaylistBitmap.also { pendingPlaylistBitmap = bitmap }
            }
        }

        if (rejected) {
            bitmap.recycle()
        }
        if (replaced != null && replaced !== bitmap && !replaced.isRecycled) {
            replaced.recycle()
        }
    }

    fun release() {
        val pending = synchronized(pendingLock) {
            if (released) return
            released = true
            pendingPlaylistBitmap.also { pendingPlaylistBitmap = null }
        }
        onRenderRetryRequested = null
        onSubjectMaskUpdated = null
        subjectMasks.close()
        if (pending != null && !pending.isRecycled) {
            pending.recycle()
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        if (released) return

        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer.position(0)

        currentSet.reset()
        nextSet.reset()
        clockTexture.resetForNewContext()
        subjectMasks.discardPending()
        tempTextureId = 0
        tempTextureWidth = 0
        tempTextureHeight = 0
        programId = 0
        blurProgramId = 0
        fboId = 0
        renderFailureLogged = false
        renderRetryCount = 0

        try {
            val vertexCode = loadShaderFromAssets("shaders/atmosphere/atmosphere.vert")
            val fragmentCode = loadShaderFromAssets("shaders/atmosphere/atmosphere.frag")
            programId = createProgram(vertexCode, fragmentCode)

            val blurFragCode = """
                #version 300 es
                precision highp float;
                in vec2 vTexCoord;
                out vec4 fragColor;
                uniform sampler2D uTexture;
                uniform vec2 uDirection;
                uniform float uRadius;
                void main() {
                    vec2 texelSize = 1.0 / vec2(textureSize(uTexture, 0));
                    vec3 result = vec3(0.0);
                    float totalWeight = 0.0;
                    for(float i = -uRadius; i <= uRadius; i++) {
                        vec2 offset = uDirection * i * texelSize;
                        float weight = 1.0 - abs(i) / uRadius;
                        result += texture(uTexture, vTexCoord + offset).rgb * weight;
                        totalWeight += weight;
                    }
                    fragColor = vec4(result / totalWeight, 1.0);
                }
            """.trimIndent()
            blurProgramId = createProgram(vertexCode, blurFragCode)

            val fbo = IntArray(1)
            GLES30.glGenFramebuffers(1, fbo, 0)
            check(fbo[0] != 0) { "OpenGL did not create the Atmosphere framebuffer" }
            fboId = fbo[0]
            needsReload = true
        } catch (failure: Exception) {
            Log.e(TAG, "Unable to initialize the Atmosphere renderer", failure)
            if (programId != 0) GLES30.glDeleteProgram(programId)
            if (blurProgramId != 0) GLES30.glDeleteProgram(blurProgramId)
            programId = 0
            blurProgramId = 0
            needsReload = false
        }
    }

    private fun loadAndApplyTextures() {
        val render = WallpaperFitHelper.loadForRender(context, surfaceWidth, surfaceHeight, previewSource)
        val sharpBitmap = render.bitmap
        val replacement = TextureSet()
        var blurredBitmap: Bitmap? = null
        try {
            replacement.width = sharpBitmap.width
            replacement.height = sharpBitmap.height
            replacement.generation = nextGeneration()
            replacement.sharpId = uploadTexture(sharpBitmap)

            tempTextureId = createEmptyTexture(
                sharpBitmap.width,
                sharpBitmap.height,
                tempTextureId,
                tempTextureWidth,
                tempTextureHeight
            )
            tempTextureWidth = sharpBitmap.width
            tempTextureHeight = sharpBitmap.height
            replacement.blurId = gpuBlur(
                replacement.sharpId,
                sharpBitmap.width,
                sharpBitmap.height,
                200f
            )
            blurredBitmap = downloadTexture(
                replacement.blurId,
                sharpBitmap.width,
                sharpBitmap.height
            )
            initBaseBlobs(blurredBitmap)

            deleteTextureSet(currentSet)
            currentSet = replacement
            currentWindowX = render.windowX
            fittedForWidth = surfaceWidth
            fittedForHeight = surfaceHeight
            requestSubjectMask(sharpBitmap, currentSet.generation)
        } catch (failure: Exception) {
            deleteTextureSet(replacement)
            throw failure
        } finally {
            if (!sharpBitmap.isRecycled) sharpBitmap.recycle()
            if (blurredBitmap != null && !blurredBitmap.isRecycled) {
                blurredBitmap.recycle()
            }
        }
    }

    private fun processPlaylistTransition() {
        val raw = synchronized(pendingLock) {
            pendingPlaylistBitmap.also { pendingPlaylistBitmap = null }
        } ?: return
        if (released) {
            if (!raw.isRecycled) raw.recycle()
            return
        }

        var bitmap: Bitmap? = null
        var blurredBitmap: Bitmap? = null
        try {
            val render = WallpaperFitHelper.fitForRender(
                context,
                raw,
                surfaceWidth,
                surfaceHeight
            )
            bitmap = render.bitmap
            nextWindowX = render.windowX
            fittedForWidth = surfaceWidth
            fittedForHeight = surfaceHeight

            // Reuse queued texture IDs; dimensions determine whether storage is reallocated.
            deleteMaskTexture(nextSet)
            nextSet.sharpId = uploadTexture(
                bitmap,
                nextSet.sharpId,
                nextSet.width,
                nextSet.height
            )

            tempTextureId = createEmptyTexture(
                bitmap.width,
                bitmap.height,
                tempTextureId,
                tempTextureWidth,
                tempTextureHeight
            )
            tempTextureWidth = bitmap.width
            tempTextureHeight = bitmap.height

            nextSet.blurId = gpuBlur(
                nextSet.sharpId,
                bitmap.width,
                bitmap.height,
                200f,
                nextSet.blurId,
                nextSet.width,
                nextSet.height
            )

            nextSet.width = bitmap.width
            nextSet.height = bitmap.height
            nextSet.generation = nextGeneration()
            nextSet.hasSubject = false

            blurredBitmap = downloadTexture(nextSet.blurId, bitmap.width, bitmap.height)
            initBaseBlobs(blurredBitmap)

            val temp = currentSet
            currentSet = nextSet
            nextSet = temp
            val tmpWin = currentWindowX
            currentWindowX = nextWindowX
            nextWindowX = tmpWin
            reRollTargets()
            requestSubjectMask(bitmap, currentSet.generation)
        } catch (failure: RuntimeException) {
            Log.e(TAG, "Unable to apply the next Atmosphere playlist image", failure)
            deleteTextureSet(nextSet)
            needsReload = true
        } finally {
            if (blurredBitmap != null && !blurredBitmap.isRecycled) {
                blurredBitmap.recycle()
            }
            if (bitmap != null && !bitmap.isRecycled) {
                bitmap.recycle()
            }
            if (raw !== bitmap && !raw.isRecycled) {
                raw.recycle()
            }
        }
    }

    private fun initBaseBlobs(blurred: Bitmap) {
        val rawClusters = extractColorsFromBlurred(blurred, 16)
        blobs.clear()

        data class TempCluster(
            var r: Int, var g: Int, var b: Int,
            var x: Float, var y: Float,
            var count: Int
        )

        val tempClusters = rawClusters.map {
            TempCluster(Color.red(it.color), Color.green(it.color), Color.blue(it.color), it.centerX, it.centerY, 1)
        }.toMutableList()

        val mergedClusters = mutableListOf<TempCluster>()
        val processed = BooleanArray(tempClusters.size)

        for (i in tempClusters.indices) {
            if (processed[i]) continue
            val main = tempClusters[i]
            processed[i] = true

            for (j in i + 1 until tempClusters.size) {
                if (processed[j]) continue
                val other = tempClusters[j]

                val colorDist = hypot(
                    (main.r - other.r).toFloat(),
                    (main.g - other.g).toFloat()
                ) + abs(main.b - other.b)
                val spatialDist = hypot(main.x - other.x, main.y - other.y)

                if (colorDist < 90.0f && spatialDist < 0.25f) {
                    val totalCount = main.count + other.count
                    main.x = (main.x * main.count + other.x * other.count) / totalCount
                    main.y = (main.y * main.count + other.y * other.count) / totalCount
                    main.r = (main.r * main.count + other.r * other.count) / totalCount
                    main.g = (main.g * main.count + other.g * other.count) / totalCount
                    main.b = (main.b * main.count + other.b * other.count) / totalCount
                    main.count += other.count
                    processed[j] = true
                }
            }
            mergedClusters.add(main)
        }

        for (cluster in mergedClusters) {
            val clr = floatArrayOf(cluster.r / 255f, cluster.g / 255f, cluster.b / 255f)
            val massScale = min(1.4f, 1.0f + (cluster.count * 0.05f))

            blobs.add(BlobPhysics(
                color = clr,
                startX = cluster.x, startY = cluster.y,
                p1x = 0f, p1y = 0f, endX = 0f, endY = 0f,
                startSize = 0f, endSize = 0f,
                massScale = massScale
            ))
        }

        reRollTargets()
    }

    private fun reRollTargets() {
        for (blob in blobs) {
            blob.endX = 0.05f + random.nextFloat() * 0.9f
            blob.endY = 0.05f + random.nextFloat() * 0.9f

            val midX = (blob.startX + blob.endX) / 2f
            val midY = (blob.startY + blob.endY) / 2f
            blob.p1x = midX + (random.nextFloat() - 0.5f) * 0.5f
            blob.p1y = midY + (random.nextFloat() - 0.5f) * 0.5f

            val baseSize = 0.12f + random.nextFloat() * 0.08f
            val finalTargetSize = baseSize * blob.massScale

            blob.startSize = 0.05f
            blob.endSize = finalTargetSize
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(0)
        surfaceHeight = height.coerceAtLeast(0)
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        aspectRatio = if (surfaceHeight > 0) {
            surfaceWidth.toFloat() / surfaceHeight.toFloat()
        } else {
            1f
        }
        if (surfaceWidth != fittedForWidth || surfaceHeight != fittedForHeight) {
            needsReload = true
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        if (released) return
        if (programId == 0 || blurProgramId == 0 || fboId == 0) {
            clearFrame()
            return
        }
        try {
            processPlaylistTransition()
            if (needsReload) {
                needsReload = false
                try {
                    loadAndApplyTextures()
                } catch (failure: Exception) {
                    needsReload = true
                    throw failure
                }
            }
            applyPendingSubjectMask()

            if (surfaceWidth > 0 && surfaceHeight > 0) {
                GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
            }
            if (!currentSet.isValid()) {
                clearFrame()
                return
            }

            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glUseProgram(programId)

        val t = blurStrength.coerceIn(0f, 1f)
        val physicsRaw = (t - 0.1f) / 0.9f
        val physicsT = physicsRaw.coerceIn(0f, 1f)
        val progress = 1.0f - (1.0f - physicsT).pow(3)

        var idx = 0

        for (b in blobs) {
            if (idx >= MAX_BLOBS) break
            val u = 1.0f - progress
            val tt = progress * progress
            val uu = u * u
            val ut2 = 2 * u * progress
            val bx = (uu * b.startX) + (ut2 * b.p1x) + (tt * b.endX)
            val by = (uu * b.startY) + (ut2 * b.p1y) + (tt * b.endY)
            val bSize = b.startSize + (b.endSize - b.startSize) * progress

            blobPosBuffer[idx * 2] = bx
            blobPosBuffer[idx * 2 + 1] = by
            blobSizesBuffer[idx] = bSize
            blobColorsBuffer[idx * 3] = b.color[0]
            blobColorsBuffer[idx * 3 + 1] = b.color[1]
            blobColorsBuffer[idx * 3 + 2] = b.color[2]
            idx++
        }

        if (idx > 0) {
            GLES30.glUniform3fv(GLES30.glGetUniformLocation(programId, "uBlobColors"), idx, blobColorsBuffer, 0)
            GLES30.glUniform2fv(GLES30.glGetUniformLocation(programId, "uBlobPositions"), idx, blobPosBuffer, 0)
            GLES30.glUniform1fv(GLES30.glGetUniformLocation(programId, "uBlobSizes"), idx, blobSizesBuffer, 0)
        }

        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uBlobCount"), idx)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uAspectRatio"), aspectRatio)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uBlurStrength"), blurStrength)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uDimLevel"), dimLevel)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uEnableNoise"), if (enableNoise) 1.0f else 0.0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uNoiseScale"), noiseScale)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uNoiseStrength"), noiseStrength)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSaturation"), blobSaturation)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uContrast"), blobContrast)
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(programId, "uAtmosphereGlassEnabled"),
            if (atmosphereGlassEnabled) 1f else 0f
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(programId, "uGlassLineCount"),
            glassLineCount.toFloat()
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(programId, "uGlassLineThickness"),
            glassLineThickness
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(programId, "uBackgroundOnly"),
            if (subjectMasks.enabled) 1f else 0f
        )
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(programId, "uHasSubject"),
            if (subjectMasks.enabled && currentSet.hasSubject) 1f else 0f
        )

        // Horizontal scroll window (identity 0f/1f = no scroll, draws as before).
        // Blobs share vTexCoord with the photo, so they pan together coherently.
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uScrollOffsetX"), scrollOffsetX)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uScrollWindowX"), currentWindowX)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, currentSet.sharpId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTextureSharp"), 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, currentSet.blurId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTextureBlur"), 1)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, currentSet.maskId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uSubjectMask"), 2)

        val clockLockFade = (1f - blurStrength / CLOCK_LOCK_FADE_RANGE).coerceIn(0f, 1f)
        val clockReady = clockEnabled && clockLockFade > 0f && clockTexture.ensureUpToDate()
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(programId, "uClockEnabled"),
            if (clockReady) 1f else 0f
        )
        if (clockReady) {
            val clockAspect = if (clockTexture.textureHeight > 0) {
                clockTexture.textureWidth.toFloat() / clockTexture.textureHeight.toFloat()
            } else {
                1f
            }
            val heightUv = clockHeight
            // Convert a screen-height-relative size to UV-space width so the
            // glyph keeps its true pixel aspect ratio (see derivation in the
            // renderer's onDrawFrame comment history / PR description).
            val widthUv = heightUv * clockAspect / aspectRatio
            GLES30.glUniform4f(
                GLES30.glGetUniformLocation(programId, "uClockRect"),
                clockCenterX - widthUv / 2f,
                clockTop,
                widthUv,
                heightUv
            )
            GLES30.glUniform1f(
                GLES30.glGetUniformLocation(programId, "uClockOpacity"),
                clockOpacity * clockLockFade
            )
            GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, clockTexture.textureId)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uClockTexture"), 3)
        }

            val aPosLoc = GLES30.glGetAttribLocation(programId, "aPosition")
            val aTexLoc = GLES30.glGetAttribLocation(programId, "aTexCoord")
            drawQuad(aPosLoc, aTexLoc)
            throwOnGlError("drawing an Atmosphere frame")
            renderFailureLogged = false
            renderRetryCount = 0
        } catch (failure: Exception) {
            if (!renderFailureLogged) {
                Log.e(TAG, "Unable to draw the Atmosphere wallpaper", failure)
                renderFailureLogged = true
            }
            if (!currentSet.isValid()) needsReload = true
            clearFrame()
            requestBoundedRetry()
        }
    }

    private fun createEmptyTexture(width: Int, height: Int, existingTextureId: Int = 0, existingWidth: Int = 0, existingHeight: Int = 0): Int {
        val t = if (existingTextureId != 0) {
            intArrayOf(existingTextureId)
        } else {
            val arr = IntArray(1)
            GLES30.glGenTextures(1, arr, 0)
            arr
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        if (existingTextureId == 0 || existingWidth != width || existingHeight != height) {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        }
        return t[0]
    }

    private fun gpuBlur(inputTexture: Int, width: Int, height: Int, radius: Float, targetOutputId: Int = 0, existingWidth: Int = 0, existingHeight: Int = 0): Int {
        val outputTexture = createEmptyTexture(width, height, targetOutputId, existingWidth, existingHeight)
        GLES30.glUseProgram(blurProgramId)
        val aPosLoc = GLES30.glGetAttribLocation(blurProgramId, "aPosition")
        val aTexLoc = GLES30.glGetAttribLocation(blurProgramId, "aTexCoord")
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tempTextureId, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(blurProgramId, "uTexture"), 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(blurProgramId, "uDirection"), 1f, 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(blurProgramId, "uRadius"), radius)
        drawQuad(aPosLoc, aTexLoc)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, outputTexture, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tempTextureId)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(blurProgramId, "uDirection"), 0f, 1f)
        drawQuad(aPosLoc, aTexLoc)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return outputTexture
    }

    private fun drawQuad(aPosLoc: Int, aTexLoc: Int) {
        vertexBuffer.position(0)
        GLES30.glVertexAttribPointer(aPosLoc, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)
        GLES30.glEnableVertexAttribArray(aPosLoc)
        vertexBuffer.position(2)
        GLES30.glVertexAttribPointer(aTexLoc, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)
        GLES30.glEnableVertexAttribArray(aTexLoc)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(aPosLoc)
        GLES30.glDisableVertexAttribArray(aTexLoc)
    }

    private fun downloadTexture(textureId: Int, width: Int, height: Int): Bitmap {
        val requiredSize = width * height * 4
        if (cachedDownloadBuffer == null || cachedDownloadBuffer!!.capacity() != requiredSize) {
            cachedDownloadBuffer = ByteBuffer.allocateDirect(requiredSize)
        }
        val buffer = cachedDownloadBuffer!!
        buffer.clear()

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, textureId, 0)
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        val bitmap = createBitmap(width, height)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    private fun uploadTexture(bitmap: Bitmap, existingTextureId: Int = 0, existingWidth: Int = 0, existingHeight: Int = 0): Int {
        val isNewTexture = existingTextureId == 0
        val textureId = if (!isNewTexture) {
            existingTextureId
        } else {
            IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        }
        check(textureId != 0) { "OpenGL did not create an Atmosphere texture" }
        try {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MIN_FILTER,
                GLES30.GL_LINEAR
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MAG_FILTER,
                GLES30.GL_LINEAR
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_WRAP_S,
                GLES30.GL_CLAMP_TO_EDGE
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_WRAP_T,
                GLES30.GL_CLAMP_TO_EDGE
            )
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            throwOnGlError("uploading an Atmosphere texture")
            return textureId
        } catch (failure: RuntimeException) {
            if (isNewTexture) {
                GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            }
            throw failure
        }
    }

    private fun uploadMaskTexture(bitmap: Bitmap, existingTextureId: Int = 0): Int {
        val isNewTexture = existingTextureId == 0
        val textureId = if (isNewTexture) {
            IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        } else {
            existingTextureId
        }
        check(textureId != 0) { "OpenGL did not create an Atmosphere subject mask" }
        try {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MIN_FILTER,
                GLES30.GL_LINEAR
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MAG_FILTER,
                GLES30.GL_LINEAR
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_WRAP_S,
                GLES30.GL_CLAMP_TO_EDGE
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_WRAP_T,
                GLES30.GL_CLAMP_TO_EDGE
            )
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            throwOnGlError("uploading an Atmosphere subject mask")
            return textureId
        } catch (failure: RuntimeException) {
            if (isNewTexture) {
                GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            }
            throw failure
        }
    }

    private fun applyPendingSubjectMask() {
        val pending = subjectMasks.takePending() ?: return
        try {
            if (pending.generation != currentSet.generation || !subjectMasks.enabled) {
                return
            }
            currentSet.maskId = uploadMaskTexture(pending.bitmap, currentSet.maskId)
            currentSet.hasSubject = true
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Unable to upload the Atmosphere subject mask", failure)
            SubjectMaskDiagnostics.recordFailure("Uploading mask texture", failure)
            deleteMaskTexture(currentSet)
        } finally {
            if (!pending.bitmap.isRecycled) {
                pending.bitmap.recycle()
            }
        }
    }

    private fun requestSubjectMask(bitmap: Bitmap, generation: Long) {
        try {
            subjectMasks.request(bitmap, generation)
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Unable to request the Atmosphere subject mask", failure)
            SubjectMaskDiagnostics.recordFailure("Requesting mask", failure)
        }
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource, "vertex")
        val fragmentShader = try {
            compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource, "fragment")
        } catch (failure: RuntimeException) {
            GLES30.glDeleteShader(vertexShader)
            throw failure
        }

        var program = 0
        try {
            program = GLES30.glCreateProgram()
            check(program != 0) { "OpenGL did not create an Atmosphere shader program" }
            GLES30.glAttachShader(program, vertexShader)
            GLES30.glAttachShader(program, fragmentShader)
            GLES30.glLinkProgram(program)
            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val details = GLES30.glGetProgramInfoLog(program).ifBlank {
                    "No linker diagnostics were returned"
                }
                throw IllegalStateException("Atmosphere shader link failed: $details")
            }
            return program
        } catch (failure: RuntimeException) {
            if (program != 0) GLES30.glDeleteProgram(program)
            throw failure
        } finally {
            GLES30.glDeleteShader(vertexShader)
            GLES30.glDeleteShader(fragmentShader)
        }
    }

    private fun compileShader(type: Int, source: String, label: String): Int {
        val shader = GLES30.glCreateShader(type)
        check(shader != 0) { "OpenGL did not create the Atmosphere $label shader" }
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val details = GLES30.glGetShaderInfoLog(shader).ifBlank {
                "No compiler diagnostics were returned"
            }
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("Atmosphere $label shader compilation failed: $details")
        }
        return shader
    }

    private fun loadShaderFromAssets(path: String): String {
        return context.assets.open(path).bufferedReader().use { it.readText() }
    }

    private fun deleteTextureSet(set: TextureSet) {
        if (set.sharpId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(set.sharpId), 0)
        }
        if (set.blurId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(set.blurId), 0)
        }
        if (set.maskId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(set.maskId), 0)
        }
        set.reset()
    }

    private fun deleteMaskTexture(set: TextureSet) {
        if (set.maskId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(set.maskId), 0)
        }
        set.maskId = 0
        set.hasSubject = false
    }

    private fun nextGeneration(): Long {
        generationCounter++
        return generationCounter
    }

    private fun clearFrame() {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    private fun throwOnGlError(operation: String) {
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) {
            "OpenGL error 0x${error.toString(16)} while $operation"
        }
    }

    private fun requestBoundedRetry() {
        if (renderRetryCount >= MAX_RENDER_RETRIES) return
        renderRetryCount++
        onRenderRetryRequested?.invoke()
    }

    data class ColorCluster(val color: Int, val centerX: Float, val centerY: Float)
    data class ColorPoint(val color: Int, val x: Int, val y: Int)

    private fun extractColorsFromBlurred(blurred: Bitmap, targetColors: Int = 12): List<ColorCluster> {
        val w = blurred.width; val h = blurred.height
        val samples = mutableListOf<ColorPoint>()
        val step = 10
        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                samples.add(ColorPoint(blurred.getPixel(x, y), x, y))
            }
        }
        val colorBuckets = medianCut(samples, targetColors)
        val colorClusters = mutableListOf<ColorCluster>()
        for (bucket in colorBuckets) {
            if (bucket.isEmpty()) continue
            var sumR = 0L; var sumG = 0L; var sumB = 0L; var sumX = 0f; var sumY = 0f
            for (point in bucket) {
                sumR += Color.red(point.color); sumG += Color.green(point.color); sumB += Color.blue(point.color)
                sumX += point.x; sumY += point.y
            }
            val count = bucket.size
            val avgColor = Color.rgb((sumR/count).toInt(), (sumG/count).toInt(), (sumB/count).toInt())
            colorClusters.add(ColorCluster(avgColor, sumX/count/w, sumY/count/h))
        }
        return colorClusters
    }

    private fun medianCut(pixels: List<ColorPoint>, targetBuckets: Int): List<List<ColorPoint>> {
        val buckets = mutableListOf<MutableList<ColorPoint>>()
        buckets.add(pixels.toMutableList())
        while (buckets.size < targetBuckets) {
            var largestBucket: MutableList<ColorPoint>? = null; var largestRange = 0; var splitChannel = 0
            for (bucket in buckets) {
                if (bucket.size <= 1) continue
                val reds = bucket.map { Color.red(it.color) }; val greens = bucket.map { Color.green(it.color) }; val blues = bucket.map { Color.blue(it.color) }
                val rRange = (reds.maxOrNull()?:0) - (reds.minOrNull()?:0)
                val gRange = (greens.maxOrNull()?:0) - (greens.minOrNull()?:0)
                val bRange = (blues.maxOrNull()?:0) - (blues.minOrNull()?:0)
                val maxRange = maxOf(rRange, gRange, bRange)
                if (maxRange > largestRange) { largestRange = maxRange; largestBucket = bucket; splitChannel = if(maxRange==rRange) 0 else if(maxRange==gRange) 1 else 2 }
            }
            if (largestBucket == null) break
            val sorted = when(splitChannel) { 0 -> largestBucket.sortedBy { Color.red(it.color) }; 1 -> largestBucket.sortedBy { Color.green(it.color) }; else -> largestBucket.sortedBy { Color.blue(it.color) } }
            val median = sorted.size / 2
            buckets.remove(largestBucket)
            buckets.add(sorted.subList(0, median).toMutableList())
            buckets.add(sorted.subList(median, sorted.size).toMutableList())
        }
        return buckets
    }

    private companion object {
        const val TAG = "AtmosphereRenderer"
        const val MAX_RENDER_RETRIES = 3
        // "ORIGINAL" maps blurStrength 0 -> lock screen, 1 -> home screen
        // (see EffectStatePolicy.endpoints). The clock is a lock-screen
        // feature, so fade it out over the first slice of the unlock
        // transition rather than showing it on the home screen too.
        const val CLOCK_LOCK_FADE_RANGE = 0.25f
    }
}
