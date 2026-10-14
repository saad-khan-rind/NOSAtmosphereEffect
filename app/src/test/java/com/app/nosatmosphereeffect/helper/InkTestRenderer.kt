package com.app.nosatmosphereeffect.helper

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage

/** The JVM twin of ClockInkPainter, so tests can look at drawn digits without a device. */
internal object InkTestRenderer {
    private const val MITER_LIMIT = 2f

    fun draw(target: BufferedImage, ink: GlyphInk, x: Float, baseline: Float, em: Float) {
        // Each glyph on its own layer, so the neon pen's cut-outs only cut itself.
        val layer = BufferedImage(target.width, target.height, BufferedImage.TYPE_INT_ARGB)
        val g = layer.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.translate(x.toDouble(), baseline.toDouble())
        g.scale(em.toDouble(), em.toDouble())
        g.translate(0.0, ink.pivotY.toDouble())
        g.shear(ink.skew.toDouble(), 0.0)
        g.translate(0.0, -ink.pivotY.toDouble())
        g.rotate(Math.toRadians(ink.rotationDegrees.toDouble()), ink.pivotX.toDouble(), ink.pivotY.toDouble())
        g.color = Color.BLACK
        val pen = ink.pen
        if (pen is ClockPen.Lines) {
            // As ClockInkPainter does it: solid off-screen, then cut into lines by distance.
            val box = ClockDigitGeometry.bounds(GlyphInk(ink.strokes, emptyList(), pen))
            val device = g.transform.createTransformedShape(
                java.awt.geom.Rectangle2D.Float(box[0], box[1], box[2] - box[0], box[3] - box[1])
            ).bounds
            device.grow(2, 2)
            val mask = BufferedImage(device.width, device.height, BufferedImage.TYPE_INT_ARGB)
            val mg = mask.createGraphics()
            mg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            mg.transform = AffineTransform.getTranslateInstance(-device.x.toDouble(), -device.y.toDouble())
                .apply { concatenate(g.transform) }
            mg.color = Color.BLACK
            mg.stroke = BasicStroke(pen.width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            for (stroke in ink.strokes) mg.draw(pathOf(stroke))
            mg.dispose()
            val coverage = FloatArray(device.width * device.height) {
                (mask.getRGB(it % device.width, it / device.width) ushr 24) / 255f
            }
            val scale = kotlin.math.sqrt(kotlin.math.abs(g.transform.determinant)).toFloat()
            val lines = ClockDigitGeometry.lineBands(coverage, device.width, device.height, pen.width * scale, pen.count)
            for (index in lines.indices) {
                val px = device.x + index % device.width
                val py = device.y + index / device.width
                if (px !in 0 until layer.width || py !in 0 until layer.height) continue
                val alpha = (lines[index] * 255).toInt().coerceIn(0, 255)
                if (alpha > 0) layer.setRGB(px, py, alpha shl 24)
            }
        } else {
            for (stroke in ink.strokes) drawStroke(g, pen, stroke)
        }
        for (dot in ink.dots) {
            g.composite = AlphaComposite.SrcOver
            val r = dot.radius.toDouble()
            if (dot.square) {
                g.fill(java.awt.geom.Rectangle2D.Double(dot.x - r, dot.y - r, 2 * r, 2 * r))
            } else {
                g.fill(Ellipse2D.Double(dot.x - r, dot.y - r, 2 * r, 2 * r))
            }
        }
        g.dispose()
        target.createGraphics().apply {
            drawImage(layer, 0, 0, null)
            dispose()
        }
    }

    private fun pathOf(stroke: InkStroke): Path2D.Float {
        val path = Path2D.Float()
        val points = stroke.points
        path.moveTo(points[0], points[1])
        var index = 2
        while (index < points.size) {
            path.lineTo(points[index], points[index + 1])
            index += 2
        }
        if (stroke.closed) path.closePath()
        return path
    }

    private fun drawStroke(g: Graphics2D, pen: ClockPen, stroke: InkStroke) {
        val path = pathOf(stroke)
        val points = stroke.points
        when (pen) {
            is ClockPen.Mono -> {
                g.stroke = BasicStroke(
                    pen.width,
                    if (pen.roundEnds) BasicStroke.CAP_ROUND else BasicStroke.CAP_BUTT,
                    if (pen.sharpCorners) BasicStroke.JOIN_MITER else BasicStroke.JOIN_ROUND,
                    MITER_LIMIT
                )
                g.draw(path)
            }
            is ClockPen.Lines -> Unit
            is ClockPen.Nib -> {
                val nib = AffineTransform.getRotateInstance(Math.toRadians(pen.angleDegrees.toDouble()))
                    .createTransformedShape(
                        Ellipse2D.Float(-pen.thick / 2, -pen.thin / 2, pen.thick, pen.thin)
                    )
                val step = pen.thin * 0.3f
                val count = points.size / 2 + if (stroke.closed) 1 else 0
                for (segment in 0 until count - 1) {
                    val x0 = points[(segment * 2) % points.size]
                    val y0 = points[(segment * 2 + 1) % points.size]
                    val x1 = points[((segment + 1) * 2) % points.size]
                    val y1 = points[((segment + 1) * 2 + 1) % points.size]
                    val length = kotlin.math.hypot(x1 - x0, y1 - y0)
                    val steps = (length / step).toInt().coerceAtLeast(1)
                    for (s in 0..steps) {
                        val t = s / steps.toFloat()
                        g.fill(
                            AffineTransform.getTranslateInstance(
                                (x0 + (x1 - x0) * t).toDouble(),
                                (y0 + (y1 - y0) * t).toDouble()
                            ).createTransformedShape(nib)
                        )
                    }
                }
            }
            is ClockPen.Dots -> Unit
        }
    }
}
