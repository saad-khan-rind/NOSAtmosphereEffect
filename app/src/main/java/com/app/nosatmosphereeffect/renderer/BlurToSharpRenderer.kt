package com.app.nosatmosphereeffect.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import androidx.core.graphics.createBitmap
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
import androidx.core.graphics.get

class BlurToSharpRenderer(private val context: Context) : GLSurfaceView.Renderer {

    // --- RING BUFFER LOGIC ---
    private class TextureSet {
        var sharpId = 0
        var blurId = 0
        fun isValid() = sharpId != 0 && blurId != 0
        fun reset() { sharpId = 0; blurId = 0 }
    }

    private var currentSet = TextureSet()
    private var nextSet = TextureSet() // The "Back Buffer"

    @Volatile private var pendingPlaylistBitmap: Bitmap? = null
    // -------------------------

    // --- RAM FIX: Cached Buffer for Pixel Reading ---
    private var cachedDownloadBuffer: ByteBuffer? = null
    // ------------------------------------------------

    var blurStrength: Float = 0.0f
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

    private var programId: Int = 0
    private var blurProgramId: Int = 0
    private var tempTextureId: Int = 0
    private var fboId: Int = 0
    private var aspectRatio: Float = 1.0f

    data class BlobPhysics(
        val color: FloatArray,
        val startX: Float, val startY: Float,
        var p1x: Float, var p1y: Float,
        var endX: Float, var endY: Float,
        var startSize: Float,
        var endSize: Float,
        val massScale: Float
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as BlobPhysics

            if (startX != other.startX) return false
            if (startY != other.startY) return false
            if (p1x != other.p1x) return false
            if (p1y != other.p1y) return false
            if (endX != other.endX) return false
            if (endY != other.endY) return false
            if (startSize != other.startSize) return false
            if (endSize != other.endSize) return false
            if (massScale != other.massScale) return false
            if (!color.contentEquals(other.color)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = startX.hashCode()
            result = 31 * result + startY.hashCode()
            result = 31 * result + p1x.hashCode()
            result = 31 * result + p1y.hashCode()
            result = 31 * result + endX.hashCode()
            result = 31 * result + endY.hashCode()
            result = 31 * result + startSize.hashCode()
            result = 31 * result + endSize.hashCode()
            result = 31 * result + massScale.hashCode()
            result = 31 * result + color.contentHashCode()
            return result
        }
    }

    private val maxBlobs = 16
    private var blobs = mutableListOf<BlobPhysics>()
    private val random = Random()

    private val blobColorsBuffer = FloatArray(maxBlobs * 3)
    private val blobPosBuffer = FloatArray(maxBlobs * 2)
    private val blobSizesBuffer = FloatArray(maxBlobs)
    private val vertices = floatArrayOf(
        -1f, -1f,  0f, 1f,
        1f, -1f,  1f, 1f,
        -1f,  1f,  0f, 0f,
        1f,  1f,  1f, 0f
    )
    private lateinit var vertexBuffer: FloatBuffer

    fun reloadTexture() {
        needsReload = true
    }

    fun queuePlaylistTransition(bitmap: Bitmap) {
        pendingPlaylistBitmap = bitmap
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer.position(0)

        val vertexCode = loadShaderFromAssets("shaders/reverseAtmosphere/atmosphere_blur_to_sharp.vert")
        val fragmentCode = loadShaderFromAssets("shaders/reverseAtmosphere/atmosphere_blur_to_sharp.frag")
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
        fboId = fbo[0]

        loadAndApplyTextures()
    }

    private fun loadAndApplyTextures() {
        // Destroy ONLY current set
        if (currentSet.isValid()) {
            val ids = intArrayOf(currentSet.sharpId, currentSet.blurId)
            GLES30.glDeleteTextures(2, ids, 0)
            currentSet.reset()
        }
        // Destroy temp if exists
        if (tempTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(tempTextureId), 0)
            tempTextureId = 0
        }

        val sharpBitmap = loadFixedWallpaper()

        // Populate Current Set
        currentSet.sharpId = uploadTexture(sharpBitmap)
        tempTextureId = createEmptyTexture(sharpBitmap.width, sharpBitmap.height)
        currentSet.blurId = gpuBlur(currentSet.sharpId, sharpBitmap.width, sharpBitmap.height, 200f)

        val blurredBitmap = downloadTexture(currentSet.blurId, sharpBitmap.width, sharpBitmap.height)
        initBaseBlobs(blurredBitmap)

        sharpBitmap.recycle()
        blurredBitmap.recycle()
    }

    private fun processPlaylistTransition() {
        val bitmap = pendingPlaylistBitmap ?: return

        // Overwrite the existing nextSet IDs instead of deleting them
        nextSet.sharpId = uploadTexture(bitmap, nextSet.sharpId)
        tempTextureId = createEmptyTexture(bitmap.width, bitmap.height, tempTextureId)
        nextSet.blurId = gpuBlur(nextSet.sharpId, bitmap.width, bitmap.height, 200f, nextSet.blurId)

        val blurredBitmap = downloadTexture(nextSet.blurId, bitmap.width, bitmap.height)
        initBaseBlobs(blurredBitmap)

        blurredBitmap.recycle()
        bitmap.recycle() // Done with raw bitmap

        // SWAP! Old current becomes next
        val temp = currentSet
        currentSet = nextSet
        nextSet = temp

        pendingPlaylistBitmap = null
        reRollTargets()
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
        GLES30.glViewport(0, 0, width, height)
        aspectRatio = width.toFloat() / height.toFloat()
    }

    override fun onDrawFrame(gl: GL10?) {
        if (pendingPlaylistBitmap != null) {
            processPlaylistTransition()
        }

        if (needsReload) {
            needsReload = false
            loadAndApplyTextures()
        }

        if (!currentSet.isValid()) {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
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
            if (idx >= maxBlobs) break
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

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, currentSet.sharpId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTextureSharp"), 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, currentSet.blurId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTextureBlur"), 1)

        val aPosLoc = GLES30.glGetAttribLocation(programId, "aPosition")
        val aTexLoc = GLES30.glGetAttribLocation(programId, "aTexCoord")
        drawQuad(aPosLoc, aTexLoc)
    }

    private fun createEmptyTexture(width: Int, height: Int, existingTextureId: Int = 0): Int {
        val t = if (existingTextureId != 0) intArrayOf(existingTextureId) else { val arr = IntArray(1); GLES30.glGenTextures(1, arr, 0); arr }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    private fun gpuBlur(inputTexture: Int, width: Int, height: Int, radius: Float, targetOutputId: Int = 0): Int {
        val outputTexture = createEmptyTexture(width, height, targetOutputId)
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

    private fun uploadTexture(bitmap: Bitmap, existingTextureId: Int = 0): Int {
        val textureHandle = if (existingTextureId != 0) intArrayOf(existingTextureId) else { val arr = IntArray(1); GLES30.glGenTextures(1, arr, 0); arr }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureHandle[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        return textureHandle[0]
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

    private fun loadFixedWallpaper(): Bitmap {
        val file = File(context.filesDir, "wallpaper.jpg")
        if (file.exists()) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) return bitmap
        }
        val fallback = createBitmap(1080, 1920)
        fallback.eraseColor(Color.BLUE)
        return fallback
    }

    data class ColorCluster(val color: Int, val centerX: Float, val centerY: Float)
    data class ColorPoint(val color: Int, val x: Int, val y: Int)

    private fun extractColorsFromBlurred(blurred: Bitmap, targetColors: Int = 12): List<ColorCluster> {
        val w = blurred.width; val h = blurred.height
        val samples = mutableListOf<ColorPoint>()
        val step = 10
        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                samples.add(ColorPoint(blurred[x, y], x, y))
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
}