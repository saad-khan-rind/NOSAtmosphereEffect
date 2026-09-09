package com.app.nosatmosphereeffect.renderer

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.app.nosatmosphereeffect.helper.SubjectMaskExtractor
import com.app.nosatmosphereeffect.helper.ClockOverlayState
import com.app.nosatmosphereeffect.helper.GlesClockOverlay
import com.app.nosatmosphereeffect.helper.WallpaperFitHelper
import com.app.nosatmosphereeffect.helper.WallpaperScrollRenderer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Canvas transition. A prominent subject is isolated when possible and reduced
 * to its silhouette plus a few broad internal contours. When segmentation has
 * no useful result, the same restrained contour treatment uses the full image.
 * [isReverse] swaps which state belongs to the lock screen.
 */
class NeonRenderer(
    private val context: Context,
    private val isReverse: Boolean = false,
    private val previewSource: (() -> Bitmap?)? = null
) : GLSurfaceView.Renderer, WallpaperScrollRenderer {

    private companion object {
        const val LINE_MAX_DIST = 6.0f
        const val EDGE_SAMPLE_RADIUS = 2.0f
        const val MAX_SKETCH_SIDE = 1600
        const val HYST_PASSES = 2
        const val WEAK_RATIO = 0.58f
    }

    @Volatile
    var onSketchUpdated: (() -> Unit)? = null

    /**
     * The wallpaper clock. Sketch replaces the photo with line art rather than
     * blurring it, so the image stays high-contrast and geometrically intact
     * at both ends of the transition and the clock reads on either side — the
     * user chooses. Units 0 and 1 carry the photo and the baked line texture,
     * so the clock takes 2.
     *
     * No depth here despite the effect having a subject mask: the mask is
     * consumed by the off-screen edge bake, and the on-screen pass samples the
     * baked lines rather than the mask, so there is nothing to composite the
     * subject back from.
     */
    private val clockOverlay = GlesClockOverlay(context, GLES30.GL_TEXTURE2, 2)

    /** Asks the host surface for another frame; used by the clock animation. */
    @Volatile
    var onAnimationFrameRequested: (() -> Unit)? = null
        set(value) {
            field = value
            clockOverlay.onAnimationFrameRequested = value
        }

    fun applyClockState(state: ClockOverlayState) = clockOverlay.applyState(state)

    fun beginClockEntry() = clockOverlay.beginEntry()

    fun onClockTimeChanged() = clockOverlay.onTimeChanged()

    @Volatile
    private var scrollOffsetX = 0.5f
    private var currentWindowX = 1f
    private var nextWindowX = 1f

    override fun setWallpaperOffset(xOffset: Float) {
        scrollOffsetX = xOffset.coerceIn(0f, 1f)
    }

    private class TextureSet {
        var sharpId = 0
        var lineId = 0
        var maskId = 0
        var width = 0
        var height = 0
        var lineWidth = 0
        var lineHeight = 0
        var maskWidth = 0
        var maskHeight = 0
        var generation = 0L
        var hasSubject = false

        fun isValid() = sharpId != 0 && lineId != 0

        fun reset() {
            sharpId = 0
            lineId = 0
            maskId = 0
            width = 0
            height = 0
            lineWidth = 0
            lineHeight = 0
            maskWidth = 0
            maskHeight = 0
            generation = 0L
            hasSubject = false
        }
    }

    private data class PendingSubjectMask(
        val generation: Long,
        val bitmap: Bitmap
    )

    private var currentSet = TextureSet()
    private var nextSet = TextureSet()
    private var generationCounter = 0L
    @Volatile private var latestSubjectRequest = 0L

    @Volatile
    private var pendingPlaylistBitmap: Bitmap? = null

    private val subjectMaskLock = Any()
    private var pendingSubjectMask: PendingSubjectMask? = null
    private var subjectMaskExtractor: SubjectMaskExtractor? = null
    @Volatile private var subjectSegmentationEnabled = false

    @Volatile var blurStrength = 0.0f
    @Volatile var dimLevel = 0.0f
    @Volatile private var needsReload = false
    @Volatile private var needsSketchRebuild = false

    @Volatile var lineWidth = 1.5f
    @Volatile var sensitivity = 0.5f

    private var programId = 0
    private var edgeProgramId = 0
    private var hystProgramId = 0
    private var edtProgramId = 0
    private var fboId = 0
    private var aspectRatio = 1.0f

    private var edgeAId = 0
    private var edgeBId = 0

    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0
    private var fittedForWidth = -1
    private var fittedForHeight = -1

    private val vertices = floatArrayOf(
        -1f, -1f, 0f, 1f,
        1f, -1f, 1f, 1f,
        -1f, 1f, 0f, 0f,
        1f, 1f, 1f, 0f
    )
    private lateinit var vertexBuffer: FloatBuffer

    fun queuePlaylistTransition(bitmap: Bitmap) {
        pendingPlaylistBitmap = bitmap
    }

    fun reloadTexture() {
        needsReload = true
    }

    fun rebuildSketch() {
        needsSketchRebuild = true
    }

    fun configureSubjectSegmentation(enabled: Boolean) {
        val changed = subjectSegmentationEnabled != enabled
        subjectSegmentationEnabled = enabled

        if (!enabled) {
            latestSubjectRequest = -1L
            subjectMaskExtractor?.close()
            subjectMaskExtractor = null
            takePendingSubjectMask()?.bitmap?.recycle()
        }

        // Enabling reloads the source bitmap so this build's model can extract it.
        if (changed || enabled) needsReload = true
    }

    fun release() {
        onSketchUpdated = null
        onAnimationFrameRequested = null
        clockOverlay.release()
        subjectMaskExtractor?.close()
        subjectMaskExtractor = null
        takePendingSubjectMask()?.bitmap?.recycle()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer.position(0)

        val screenVertexCode = loadShaderFromAssets("shaders/neon/neon.vert")
        val bakeVertexCode = loadShaderFromAssets("shaders/neon/neon_bake.vert")
        programId = createProgram(screenVertexCode, loadShaderFromAssets("shaders/neon/neon.frag"))
        edgeProgramId = createProgram(bakeVertexCode, loadShaderFromAssets("shaders/neon/neon_edges.frag"))
        hystProgramId = createProgram(bakeVertexCode, loadShaderFromAssets("shaders/neon/neon_hyst.frag"))
        edtProgramId = createProgram(bakeVertexCode, loadShaderFromAssets("shaders/neon/neon_edt.frag"))

        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        fboId = fbo[0]

        currentSet.reset()
        nextSet.reset()
        takePendingSubjectMask()?.bitmap?.recycle()
        needsReload = true
        // The clock's texture id belonged to the destroyed context.
        clockOverlay.resetForNewContext()
    }

    private fun loadAndApplyTextures() {
        clearTextureSet(currentSet)

        fittedForWidth = surfaceWidth
        fittedForHeight = surfaceHeight
        val render = WallpaperFitHelper.loadForRender(context, surfaceWidth, surfaceHeight, previewSource)
        val bitmap = render.bitmap
        currentWindowX = render.windowX

        currentSet.width = bitmap.width
        currentSet.height = bitmap.height
        currentSet.generation = nextGeneration()
        currentSet.hasSubject = false
        currentSet.sharpId = uploadTexture(bitmap)

        buildSketch(currentSet)
        needsSketchRebuild = false
        startSubjectExtraction(bitmap, currentSet.generation)
        bitmap.recycle()
    }

    private fun processPlaylistTransition() {
        val raw = pendingPlaylistBitmap ?: return
        val render = WallpaperFitHelper.fitForRender(context, raw, surfaceWidth, surfaceHeight)
        val bitmap = render.bitmap
        nextWindowX = render.windowX
        fittedForWidth = surfaceWidth
        fittedForHeight = surfaceHeight

        nextSet.sharpId = uploadTexture(bitmap, nextSet.sharpId)
        nextSet.width = bitmap.width
        nextSet.height = bitmap.height
        nextSet.generation = nextGeneration()
        nextSet.hasSubject = false

        buildSketch(nextSet)

        val temp = currentSet
        currentSet = nextSet
        nextSet = temp

        val tmpWindow = currentWindowX
        currentWindowX = nextWindowX
        nextWindowX = tmpWindow

        pendingPlaylistBitmap = null
        startSubjectExtraction(bitmap, currentSet.generation)
        bitmap.recycle()
    }

    private fun startSubjectExtraction(bitmap: Bitmap, generation: Long) {
        if (!subjectSegmentationEnabled) return
        val extractor = subjectMaskExtractor ?: SubjectMaskExtractor(
            context,
            ::onSubjectMaskResult
        ).also { subjectMaskExtractor = it }
        latestSubjectRequest = generation
        extractor.extract(bitmap, generation)
    }

    private fun onSubjectMaskResult(generation: Long, mask: Bitmap?) {
        if (mask == null) return
        if (!subjectSegmentationEnabled || generation != latestSubjectRequest) {
            mask.recycle()
            return
        }
        synchronized(subjectMaskLock) {
            if (pendingSubjectMask?.generation?.let { it > generation } == true) {
                mask.recycle()
                return
            }
            pendingSubjectMask?.bitmap?.recycle()
            pendingSubjectMask = PendingSubjectMask(generation, mask)
        }
        onSketchUpdated?.invoke()
    }

    private fun applyPendingSubjectMask() {
        val pending = takePendingSubjectMask() ?: return
        if (pending.generation != currentSet.generation) {
            pending.bitmap.recycle()
            return
        }

        currentSet.maskId = uploadMaskTexture(pending.bitmap, currentSet.maskId)
        currentSet.maskWidth = pending.bitmap.width
        currentSet.maskHeight = pending.bitmap.height
        currentSet.hasSubject = true
        pending.bitmap.recycle()

        buildSketch(currentSet)
        needsSketchRebuild = false
    }

    private fun takePendingSubjectMask(): PendingSubjectMask? = synchronized(subjectMaskLock) {
        val pending = pendingSubjectMask
        pendingSubjectMask = null
        pending
    }

    /**
     * Bakes a short distance map from a simplified contour image. Subject masks
     * contribute a guaranteed silhouette; color edges are accepted only when
     * they remain meaningful across two coarse image scales.
     */
    private fun buildSketch(set: TextureSet) {
        if (set.sharpId == 0 || set.width <= 0 || set.height <= 0) return

        val sourceWidth = set.width
        val sourceHeight = set.height
        val sketchScale = minOf(
            1f,
            MAX_SKETCH_SIDE.toFloat() / maxOf(sourceWidth, sourceHeight).toFloat()
        )
        val width = (sourceWidth * sketchScale).toInt().coerceAtLeast(1)
        val height = (sourceHeight * sketchScale).toInt().coerceAtLeast(1)

        edgeAId = createEmptyTexture(
            width, height, GLES30.GL_NEAREST, edgeAId, 0, 0, GLES30.GL_R8, GLES30.GL_RED
        )
        edgeBId = createEmptyTexture(
            width, height, GLES30.GL_NEAREST, edgeBId, 0, 0, GLES30.GL_R8, GLES30.GL_RED
        )
        set.lineId = createEmptyTexture(
            width,
            height,
            GLES30.GL_LINEAR,
            set.lineId,
            set.lineWidth,
            set.lineHeight,
            GLES30.GL_R8,
            GLES30.GL_RED
        )
        set.lineWidth = width
        set.lineHeight = height

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glViewport(0, 0, width, height)

        GLES30.glUseProgram(edgeProgramId)
        attach(edgeAId)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, set.sharpId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(edgeProgramId, "uTextureSharp"), 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (set.hasSubject) set.maskId else 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(edgeProgramId, "uSubjectMask"), 1)

        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(edgeProgramId, "uStep"),
            EDGE_SAMPLE_RADIUS / width,
            EDGE_SAMPLE_RADIUS / height
        )
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(edgeProgramId, "uMaskStep"),
            1f / set.maskWidth.coerceAtLeast(1),
            1f / set.maskHeight.coerceAtLeast(1)
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(edgeProgramId, "uHasSubject"), if (set.hasSubject) 1f else 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(edgeProgramId, "uLod"), edgeLod())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(edgeProgramId, "uThreshold"), edgeThreshold())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(edgeProgramId, "uWeakRatio"), WEAK_RATIO)
        drawQuad(edgeProgramId)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUseProgram(hystProgramId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(hystProgramId, "uTexture"), 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(hystProgramId, "uStep"), 1f / width, 1f / height)

        var src = edgeAId
        var dst = edgeBId
        for (i in 0 until HYST_PASSES) {
            attach(dst)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, src)
            GLES30.glUniform1f(
                GLES30.glGetUniformLocation(hystProgramId, "uFinal"),
                if (i == HYST_PASSES - 1) 1f else 0f
            )
            drawQuad(hystProgramId)
            val temp = src
            src = dst
            dst = temp
        }

        GLES30.glUseProgram(edtProgramId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(edtProgramId, "uTexture"), 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(edtProgramId, "uMaxDist"), LINE_MAX_DIST)

        attach(dst)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, src)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(edtProgramId, "uStep"), 1f / width, 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(edtProgramId, "uRadius"), LINE_MAX_DIST)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(edtProgramId, "uPass"), 0f)
        drawQuad(edtProgramId)

        attach(set.lineId)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dst)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(edtProgramId, "uStep"), 0f, 1f / height)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(edtProgramId, "uRadius"), LINE_MAX_DIST)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(edtProgramId, "uPass"), 1f)
        drawQuad(edtProgramId)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteTextures(2, intArrayOf(edgeAId, edgeBId), 0)
        edgeAId = 0
        edgeBId = 0
    }

    private fun attach(textureId: Int) {
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            textureId,
            0
        )
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspectRatio = width.toFloat() / height.toFloat()
        surfaceWidth = width
        surfaceHeight = height
        if (width != fittedForWidth || height != fittedForHeight) {
            needsReload = true
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        if (pendingPlaylistBitmap != null) processPlaylistTransition()
        if (needsReload) {
            needsReload = false
            loadAndApplyTextures()
        }
        applyPendingSubjectMask()
        if (needsSketchRebuild) {
            needsSketchRebuild = false
            buildSketch(currentSet)
        }

        if (surfaceWidth > 0 && surfaceHeight > 0) {
            GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        }

        if (!currentSet.isValid()) {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(programId)

        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uAspectRatio"), aspectRatio)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uBlurStrength"), blurStrength)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uDimLevel"), dimLevel)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uReverse"), if (isReverse) 1f else 0f)
        val lineScale = currentSet.lineWidth.toFloat() / currentSet.width.coerceAtLeast(1)
        GLES30.glUniform1f(
            GLES30.glGetUniformLocation(programId, "uLineWidth"),
            (lineWidth * lineScale).coerceAtLeast(0.25f)
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLineMax"), LINE_MAX_DIST)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uScrollOffsetX"), scrollOffsetX)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uScrollWindowX"), currentWindowX)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, currentSet.sharpId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTextureSharp"), 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, currentSet.lineId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uLineTex"), 1)

        clockOverlay.draw(
            programId = programId,
            progress = blurStrength,
            screenAspect = aspectRatio
        )

        drawQuad(programId)
    }

    private fun edgeThreshold(): Float {
        val detail = sensitivity.coerceIn(0f, 1f)
        return 0.30f + (0.11f - 0.30f) * detail
    }

    private fun edgeLod(): Float {
        val detail = sensitivity.coerceIn(0f, 1f)
        return 3.2f - 1.2f * detail
    }

    private fun nextGeneration(): Long {
        generationCounter++
        return generationCounter
    }

    private fun clearTextureSet(set: TextureSet) {
        deleteTexture(set.sharpId)
        deleteTexture(set.lineId)
        deleteTexture(set.maskId)
        set.reset()
    }

    private fun drawQuad(program: Int) {
        val positionLocation = GLES30.glGetAttribLocation(program, "aPosition")
        val textureLocation = GLES30.glGetAttribLocation(program, "aTexCoord")

        vertexBuffer.position(0)
        GLES30.glVertexAttribPointer(positionLocation, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)
        GLES30.glEnableVertexAttribArray(positionLocation)

        vertexBuffer.position(2)
        GLES30.glVertexAttribPointer(textureLocation, 2, GLES30.GL_FLOAT, false, 4 * 4, vertexBuffer)
        GLES30.glEnableVertexAttribArray(textureLocation)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(positionLocation)
        GLES30.glDisableVertexAttribArray(textureLocation)
    }

    private fun createEmptyTexture(
        width: Int,
        height: Int,
        filter: Int,
        existingTextureId: Int = 0,
        existingWidth: Int = 0,
        existingHeight: Int = 0,
        internalFormat: Int = GLES30.GL_RGBA,
        format: Int = GLES30.GL_RGBA
    ): Int {
        val texture = if (existingTextureId != 0) {
            intArrayOf(existingTextureId)
        } else {
            IntArray(1).also { GLES30.glGenTextures(1, it, 0) }
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        if (existingTextureId == 0 || existingWidth != width || existingHeight != height) {
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                internalFormat,
                width,
                height,
                0,
                format,
                GLES30.GL_UNSIGNED_BYTE,
                null
            )
        }
        return texture[0]
    }

    private fun uploadTexture(bitmap: Bitmap, existingTextureId: Int = 0): Int {
        val texture = if (existingTextureId != 0) {
            intArrayOf(existingTextureId)
        } else {
            IntArray(1).also { GLES30.glGenTextures(1, it, 0) }
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        return texture[0]
    }

    private fun uploadMaskTexture(bitmap: Bitmap, existingTextureId: Int = 0): Int {
        val texture = if (existingTextureId != 0) {
            intArrayOf(existingTextureId)
        } else {
            IntArray(1).also { GLES30.glGenTextures(1, it, 0) }
        }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        return texture[0]
    }

    private fun deleteTexture(textureId: Int) {
        if (textureId != 0) GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        return program
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        return shader
    }

    private fun loadShaderFromAssets(path: String): String {
        return context.assets.open(path).bufferedReader().use { it.readText() }
    }
}
