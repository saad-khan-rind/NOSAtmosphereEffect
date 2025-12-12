package com.app.nosatmosphereeffect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.ArrayList
import java.util.Random
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

class AtmosphereRenderer(private val context: Context) : GLSurfaceView.Renderer {

    var blurStrength: Float = 0.0f
    var seed: Float = 0.0f
    @Volatile private var needsReload: Boolean = false

    private var programId: Int = 0
    private var sharpTextureId: Int = 0
    private var blurTextureId: Int = 0

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

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer.position(0)

        val vertexCode = loadShaderFromAssets("shaders/atmosphere.vert")
        val fragmentCode = loadShaderFromAssets("shaders/atmosphere.frag")
        programId = createProgram(vertexCode, fragmentCode)

        loadAndApplyTextures()
    }

    private fun loadAndApplyTextures() {
        if (sharpTextureId != 0) {
            val ids = intArrayOf(sharpTextureId, blurTextureId)
            GLES30.glDeleteTextures(2, ids, 0)
        }

        val sharpBitmap = loadFixedWallpaper()
        sharpTextureId = uploadTexture(sharpBitmap)

        val cloudBitmap = generateCloudBitmap(sharpBitmap)
        blurTextureId = uploadTexture(cloudBitmap)

        sharpBitmap.recycle()
        cloudBitmap.recycle()
    }

    private fun loadFixedWallpaper(): Bitmap {
        val file = File(context.filesDir, "wallpaper.jpg")
        var rawBitmap: Bitmap? = null
        if (file.exists()) {
            try {
                rawBitmap = BitmapFactory.decodeFile(file.absolutePath)
            } catch (e: Exception) { }
        }

        if (rawBitmap == null) {
            val color = Color.BLUE
            rawBitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
            rawBitmap.eraseColor(color)
        }

        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val width = rawBitmap!!.width
        val height = rawBitmap.height

        val targetW = screenWidth.coerceAtMost(1440)
        val targetH = (targetW.toFloat() / screenWidth * screenHeight).toInt()

        val finalBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(finalBitmap)
        val matrix = Matrix()

        val safeScale = max(targetW.toFloat() / width, targetH.toFloat() / height)
        matrix.postScale(safeScale, safeScale)
        matrix.postTranslate((targetW - width * safeScale) / 2f, (targetH - height * safeScale) / 2f)

        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(rawBitmap, matrix, paint)

        if (rawBitmap != finalBitmap) rawBitmap.recycle()
        return finalBitmap
    }

    // --- FIX: DOUBLE DENSITY GENERATION ---
    private fun generateCloudBitmap(source: Bitmap): Bitmap {
        val cols = 10
        val rows = 20

        val palette = Bitmap.createScaledBitmap(source, cols, rows, true)

        val texW = 512
        val texH = 512
        val cloudBitmap = Bitmap.createBitmap(texW, texH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(cloudBitmap)

        // Base background color
        canvas.drawColor(palette.getPixel(cols / 2, rows / 2))

        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        val cellW = texW / cols.toFloat()
        val cellH = texH / rows.toFloat()

        val rng = Random()
        data class Zone(val color: Int, val cx: Float, val cy: Float)
        val zones = ArrayList<Zone>()

        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val color = palette.getPixel(x, y)
                val cx = (x * cellW) + (cellW / 2)
                val cy = (y * cellH) + (cellH / 2)
                zones.add(Zone(color, cx, cy))
            }
        }
        palette.recycle()
        zones.shuffle()

        for (zone in zones) {
            paint.color = zone.color

            // LAYER 1: Main Cloud (Massive Jitter)
            // Drifts up to 3 cells away. Large size.
            val shiftX = (rng.nextFloat() - 0.5f) * cellW * 3.0f
            val shiftY = (rng.nextFloat() - 0.5f) * cellH * 3.0f
            val radius = max(cellW, cellH) * (1.5f + rng.nextFloat() * 2.0f)

            canvas.drawCircle(zone.cx + shiftX, zone.cy + shiftY, radius, paint)

            // LAYER 2: Satellite Cloud (The "Mortar")
            // This fills the gaps that might open up when the main clouds move.
            // It has the SAME color but a different random position nearby.
            val satShiftX = (rng.nextFloat() - 0.5f) * cellW * 4.0f // Drifts even further
            val satShiftY = (rng.nextFloat() - 0.5f) * cellH * 4.0f
            val satRadius = radius * 0.6f // Smaller

            canvas.drawCircle(zone.cx + satShiftX, zone.cy + satShiftY, satRadius, paint)
        }

        // Increased Blur to 50 to melt the double layer together
        return fastBlur(cloudBitmap, 50)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (needsReload) {
            needsReload = false
            loadAndApplyTextures()
        }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(programId)

        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uBlurStrength"), blurStrength)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSeed"), seed)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sharpTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTextureSharp"), 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blurTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTextureBlur"), 1)

        val aPosLoc = GLES30.glGetAttribLocation(programId, "aPosition")
        val aTexLoc = GLES30.glGetAttribLocation(programId, "aTexCoord")

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

    private fun uploadTexture(bitmap: Bitmap): Int {
        val textureHandle = IntArray(1)
        GLES30.glGenTextures(1, textureHandle, 0)
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

    private fun fastBlur(sentBitmap: Bitmap, radius: Int): Bitmap {
        val config = sentBitmap.config ?: Bitmap.Config.ARGB_8888
        val bitmap = sentBitmap.copy(config, true)
        if (radius < 1) return (null) ?: sentBitmap
        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)
        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1
        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int; var gsum: Int; var bsum: Int; var x: Int; var y: Int; var i: Int; var p: Int; var yp: Int; var yi: Int; var yw: Int
        val vmin = IntArray(max(w, h))
        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (i in 0 until 256 * divsum) dv[i] = (i / divsum)
        yw = 0; yi = 0
        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int; var stackstart: Int; var sir: IntArray; var rbs: Int; var r1 = radius + 1; var routsum: Int; var goutsum: Int; var boutsum: Int; var rinsum: Int; var ginsum: Int; var binsum: Int
        for (y in 0 until h) {
            rinsum = 0; ginsum = 0; binsum = 0; routsum = 0; goutsum = 0; boutsum = 0; rsum = 0; gsum = 0; bsum = 0
            for (i in -radius..radius) {
                p = pix[yi + min(wm, max(i, 0))]
                sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)
                rbs = r1 - Math.abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) { rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2] }
                else { routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2] }
            }
            stackpointer = radius
            for (x in 0 until w) {
                r[yi] = dv[rsum]; g[yi] = dv[gsum]; b[yi] = dv[bsum]
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]
                if (y == 0) vmin[x] = min(x + radius + 1, wm)
                p = pix[yw + vmin[x]]
                sir[0] = (p and 0xff0000) shr 16; sir[1] = (p and 0x00ff00) shr 8; sir[2] = (p and 0x0000ff)
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[(stackpointer) % div]
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                yi++
            }
            yw += w
        }
        for (x in 0 until w) {
            rinsum = 0; ginsum = 0; binsum = 0; routsum = 0; goutsum = 0; boutsum = 0; rsum = 0; gsum = 0; bsum = 0
            yp = -radius * w
            for (i in -radius..radius) {
                yi = max(0, yp) + x
                sir = stack[i + radius]
                sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi]
                rbs = r1 - Math.abs(i)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (i > 0) { rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2] }
                else { routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2] }
                if (i < hm) yp += w
            }
            yi = x; stackpointer = radius
            for (y in 0 until h) {
                pix[yi] = (0xff000000.toInt() or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum])
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]
                if (x == 0) vmin[y] = min(y + r1, hm) * w
                p = x + vmin[y]
                sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p]
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                yi += w
            }
        }
        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }
}