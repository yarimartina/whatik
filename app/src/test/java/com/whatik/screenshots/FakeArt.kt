package com.whatik.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Immagini finte per gli screenshot: sticker "cartoon" su sfondo trasparente (come quelli veri),
 * un foglio con più sticker (screenshot da ritagliare) e un fotogramma di registrazione dello schermo.
 * Tutto disegnato con android.graphics, quindi serve il rendering nativo di Robolectric.
 */
object FakeArt {

    enum class Kind { SMILEY, HEART, BUBBLE, STAR, CAT, BADGE, CLOUD, FIRE, COOL, CRY, GHOST }

    class Spec(val kind: Kind, val color: Int, val text: String? = null)

    /** Gli sticker disponibili, in ordine: la libreria li riusa a rotazione. */
    val specs: List<Spec> = listOf(
        Spec(Kind.SMILEY, 0xFFFFC83D.toInt()),
        Spec(Kind.HEART, 0xFFFE2C55.toInt(), "LOVE"),
        Spec(Kind.BUBBLE, 0xFF00B7B0.toInt(), "LOL"),
        Spec(Kind.CAT, 0xFFFF9F43.toInt()),
        Spec(Kind.STAR, 0xFFFFD23F.toInt()),
        Spec(Kind.BADGE, 0xFF2ECC71.toInt(), "OK"),
        Spec(Kind.COOL, 0xFFFFC83D.toInt()),
        Spec(Kind.CRY, 0xFF8FD3FF.toInt()),
        Spec(Kind.CLOUD, 0xFFFFFFFF.toInt(), "WOW"),
        Spec(Kind.FIRE, 0xFFFF6B35.toInt()),
        Spec(Kind.BADGE, 0xFF7B61FF.toInt(), "GG"),
        Spec(Kind.GHOST, 0xFFF4F0FF.toInt()),
    )

    private fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    private fun strokePaint(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private fun darker(color: Int, factor: Float = 0.78f): Int =
        Color.rgb((Color.red(color) * factor).toInt(), (Color.green(color) * factor).toInt(), (Color.blue(color) * factor).toInt())

    /** Contorno bianco spesso (stile sticker) e poi il riempimento del tracciato. */
    private fun Canvas.sticker(path: Path, color: Int, s: Float = width.toFloat(), rim: Int = darker(color)) {
        drawPath(path, strokePaint(Color.WHITE, s * 0.07f))
        drawPath(path, fillPaint(color))
        drawPath(path, strokePaint(rim, s * 0.018f))
    }

    private fun Canvas.outlinedText(text: String, cx: Float, cy: Float, size: Float, color: Int, outline: Int, outlineWidth: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            strokeJoin = Paint.Join.ROUND
        }
        val baseline = cy - (paint.ascent() + paint.descent()) / 2f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = outlineWidth
        paint.color = outline
        drawText(text, cx, baseline, paint)
        paint.style = Paint.Style.FILL
        paint.color = color
        drawText(text, cx, baseline, paint)
    }

    private fun Canvas.eyes(cx: Float, cy: Float, r: Float, color: Int = 0xFF2B2B2B.toInt()) {
        val p = fillPaint(color)
        drawOval(RectF(cx - r * 0.50f, cy - r * 0.22f, cx - r * 0.24f, cy + r * 0.20f), p)
        drawOval(RectF(cx + r * 0.24f, cy - r * 0.22f, cx + r * 0.50f, cy + r * 0.20f), p)
        val shine = fillPaint(Color.WHITE)
        drawCircle(cx - r * 0.40f, cy - r * 0.12f, r * 0.045f, shine)
        drawCircle(cx + r * 0.34f, cy - r * 0.12f, r * 0.045f, shine)
    }

    private fun Canvas.smile(cx: Float, cy: Float, r: Float, open: Boolean = false) {
        val rect = RectF(cx - r * 0.46f, cy - r * 0.05f, cx + r * 0.46f, cy + r * 0.52f)
        if (open) {
            drawArc(rect, 0f, 180f, true, fillPaint(0xFF7A1F2B.toInt()))
            drawArc(RectF(rect.left + r * 0.18f, rect.centerY() + r * 0.06f, rect.right - r * 0.18f, rect.bottom - r * 0.02f), 0f, 180f, true, fillPaint(0xFFFF7A8A.toInt()))
        } else {
            drawArc(rect, 20f, 140f, false, strokePaint(0xFF2B2B2B.toInt(), r * 0.075f))
        }
    }

    private fun heartPath(cx: Float, cy: Float, size: Float): Path {
        val path = Path()
        for (i in 0..120) {
            val t = i / 120.0 * 2 * PI
            val x = 16 * sin(t).let { it * it * it }
            val y = 13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t)
            val px = cx + (x / 34.0 * size).toFloat()
            val py = cy - (y / 34.0 * size).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        return path
    }

    private fun starPath(cx: Float, cy: Float, outer: Float, inner: Float): Path {
        val path = Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outer else inner
            val a = -PI / 2 + i * PI / 5
            val x = cx + (r * cos(a)).toFloat()
            val y = cy + (r * sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    /** Uno sticker quadrato [size]x[size] con sfondo trasparente. */
    fun sticker(spec: Spec, size: Int = 512): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = size.toFloat()
        val mid = s / 2f
        when (spec.kind) {
            Kind.SMILEY, Kind.COOL, Kind.CRY -> {
                val r = s * 0.41f
                val face = Path().apply { addCircle(mid, mid, r, Path.Direction.CW) }
                c.sticker(face, spec.color)
                when (spec.kind) {
                    Kind.SMILEY -> { c.eyes(mid, mid - r * 0.12f, r); c.smile(mid, mid + r * 0.05f, r, open = true) }
                    Kind.COOL -> {
                        val glass = fillPaint(0xFF1E1E1E.toInt())
                        c.drawRoundRect(RectF(mid - r * 0.82f, mid - r * 0.38f, mid - r * 0.06f, mid + r * 0.12f), r * 0.14f, r * 0.14f, glass)
                        c.drawRoundRect(RectF(mid + r * 0.06f, mid - r * 0.38f, mid + r * 0.82f, mid + r * 0.12f), r * 0.14f, r * 0.14f, glass)
                        c.drawRect(mid - r * 0.08f, mid - r * 0.30f, mid + r * 0.08f, mid - r * 0.22f, glass)
                        c.drawRect(RectF(mid - r * 0.70f, mid - r * 0.32f, mid - r * 0.40f, mid - r * 0.24f), fillPaint(0x66FFFFFF))
                        c.smile(mid, mid + r * 0.18f, r)
                    }
                    else -> {
                        c.eyes(mid, mid - r * 0.12f, r)
                        val tear = fillPaint(0xFF3AA0FF.toInt())
                        c.drawOval(RectF(mid - r * 0.56f, mid + r * 0.10f, mid - r * 0.38f, mid + r * 0.62f), tear)
                        c.drawOval(RectF(mid + r * 0.38f, mid + r * 0.10f, mid + r * 0.56f, mid + r * 0.62f), tear)
                        c.drawArc(RectF(mid - r * 0.34f, mid + r * 0.28f, mid + r * 0.34f, mid + r * 0.68f), 200f, 140f, false, strokePaint(0xFF2B2B2B.toInt(), r * 0.075f))
                    }
                }
            }
            Kind.HEART -> {
                val path = heartPath(mid, mid + s * 0.02f, s * 0.92f)
                c.sticker(path, spec.color)
                c.drawOval(RectF(s * 0.24f, s * 0.22f, s * 0.38f, s * 0.34f), fillPaint(0x66FFFFFF))
                spec.text?.let { c.outlinedText(it, mid, s * 0.50f, s * 0.17f, Color.WHITE, darker(spec.color, 0.6f), s * 0.025f) }
            }
            Kind.BUBBLE -> {
                val body = Path().apply {
                    addRoundRect(RectF(s * 0.07f, s * 0.14f, s * 0.93f, s * 0.68f), s * 0.2f, s * 0.2f, Path.Direction.CW)
                    moveTo(s * 0.22f, s * 0.64f); lineTo(s * 0.16f, s * 0.90f); lineTo(s * 0.44f, s * 0.66f); close()
                }
                c.sticker(body, Color.WHITE, s, rim = spec.color)
                c.drawPath(body, strokePaint(spec.color, s * 0.03f))
                c.outlinedText(spec.text ?: "LOL", mid, s * 0.41f, s * 0.30f, spec.color, Color.WHITE, s * 0.02f)
            }
            Kind.STAR -> {
                c.sticker(starPath(mid, mid + s * 0.03f, s * 0.46f, s * 0.22f), spec.color)
                c.eyes(mid, mid + s * 0.02f, s * 0.17f)
                c.smile(mid, mid + s * 0.07f, s * 0.17f)
            }
            Kind.CAT -> {
                val head = Path().apply {
                    addCircle(mid, s * 0.56f, s * 0.34f, Path.Direction.CW)
                    moveTo(s * 0.20f, s * 0.42f); lineTo(s * 0.20f, s * 0.10f); lineTo(s * 0.43f, s * 0.27f); close()
                    moveTo(s * 0.80f, s * 0.42f); lineTo(s * 0.80f, s * 0.10f); lineTo(s * 0.57f, s * 0.27f); close()
                }
                c.sticker(head, spec.color)
                c.eyes(mid, s * 0.52f, s * 0.30f)
                c.drawPath(Path().apply { moveTo(mid - s * 0.04f, s * 0.60f); lineTo(mid + s * 0.04f, s * 0.60f); lineTo(mid, s * 0.65f); close() }, fillPaint(0xFFFF6F91.toInt()))
                val whisker = strokePaint(0xFF5A3A1E.toInt(), s * 0.012f)
                for (dy in listOf(-0.02f, 0.03f)) {
                    c.drawLine(s * 0.16f, s * (0.64f + dy), s * 0.34f, s * (0.64f + dy * 0.4f), whisker)
                    c.drawLine(s * 0.84f, s * (0.64f + dy), s * 0.66f, s * (0.64f + dy * 0.4f), whisker)
                }
            }
            Kind.BADGE -> {
                val body = Path().apply { addRoundRect(RectF(s * 0.08f, s * 0.24f, s * 0.92f, s * 0.76f), s * 0.16f, s * 0.16f, Path.Direction.CW) }
                c.sticker(body, spec.color)
                c.outlinedText(spec.text ?: "OK", mid, s * 0.50f, s * 0.34f, Color.WHITE, darker(spec.color, 0.55f), s * 0.03f)
            }
            Kind.CLOUD -> {
                val cloud = Path().apply {
                    addCircle(s * 0.32f, s * 0.50f, s * 0.20f, Path.Direction.CW)
                    addCircle(s * 0.50f, s * 0.36f, s * 0.24f, Path.Direction.CW)
                    addCircle(s * 0.70f, s * 0.50f, s * 0.20f, Path.Direction.CW)
                    addRoundRect(RectF(s * 0.18f, s * 0.46f, s * 0.84f, s * 0.70f), s * 0.12f, s * 0.12f, Path.Direction.CW)
                }
                c.sticker(cloud, spec.color, s, rim = 0xFF9DB4C8.toInt())
                c.outlinedText(spec.text ?: "WOW", mid, s * 0.50f, s * 0.20f, 0xFF3A86FF.toInt(), Color.WHITE, s * 0.012f)
            }
            Kind.FIRE -> {
                val flame = Path().apply {
                    moveTo(mid, s * 0.05f)
                    cubicTo(s * 0.62f, s * 0.28f, s * 0.92f, s * 0.44f, s * 0.84f, s * 0.70f)
                    cubicTo(s * 0.78f, s * 0.92f, s * 0.60f, s * 0.95f, mid, s * 0.95f)
                    cubicTo(s * 0.40f, s * 0.95f, s * 0.22f, s * 0.92f, s * 0.16f, s * 0.70f)
                    cubicTo(s * 0.10f, s * 0.50f, s * 0.30f, s * 0.40f, s * 0.34f, s * 0.24f)
                    cubicTo(s * 0.42f, s * 0.30f, s * 0.46f, s * 0.34f, s * 0.48f, s * 0.38f)
                    cubicTo(s * 0.46f, s * 0.26f, s * 0.46f, s * 0.14f, mid, s * 0.05f)
                    close()
                }
                c.sticker(flame, spec.color)
                val inner = Path().apply {
                    moveTo(mid, s * 0.44f)
                    cubicTo(s * 0.66f, s * 0.58f, s * 0.74f, s * 0.72f, s * 0.68f, s * 0.80f)
                    cubicTo(s * 0.62f, s * 0.90f, s * 0.38f, s * 0.90f, s * 0.32f, s * 0.80f)
                    cubicTo(s * 0.26f, s * 0.70f, s * 0.38f, s * 0.58f, mid, s * 0.44f)
                    close()
                }
                c.drawPath(inner, fillPaint(0xFFFFD23F.toInt()))
            }
            Kind.GHOST -> {
                val body = Path().apply {
                    moveTo(s * 0.16f, s * 0.90f)
                    lineTo(s * 0.16f, s * 0.44f)
                    cubicTo(s * 0.16f, s * 0.10f, s * 0.84f, s * 0.10f, s * 0.84f, s * 0.44f)
                    lineTo(s * 0.84f, s * 0.90f)
                    lineTo(s * 0.72f, s * 0.80f); lineTo(s * 0.60f, s * 0.90f); lineTo(s * 0.50f, s * 0.80f)
                    lineTo(s * 0.40f, s * 0.90f); lineTo(s * 0.28f, s * 0.80f)
                    close()
                }
                c.sticker(body, spec.color, s, rim = 0xFF8E7CC3.toInt())
                c.eyes(mid, s * 0.44f, s * 0.30f)
                c.drawOval(RectF(mid - s * 0.05f, s * 0.54f, mid + s * 0.05f, s * 0.64f), fillPaint(0xFF2B2B2B.toInt()))
            }
        }
        return bmp
    }

    fun sticker(index: Int, size: Int = 512): Bitmap = sticker(specs[Math.floorMod(index, specs.size)], size)

    /** Screenshot del pannello sticker di TikTok: più sticker su fondo chiaro (da ritagliare nell'editor). */
    fun sheet(cols: Int = 2, rows: Int = 2, cell: Int = 540, firstIndex: Int = 0): Bitmap {
        val bmp = Bitmap.createBitmap(cols * cell, rows * cell, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFF8F8F8.toInt())
        var i = firstIndex
        for (row in 0 until rows) for (col in 0 until cols) {
            val x = col * cell.toFloat()
            val y = row * cell.toFloat()
            c.drawRoundRect(RectF(x + 18, y + 18, x + cell - 18, y + cell - 18), 40f, 40f, fillPaint(Color.WHITE))
            val art = sticker(i++, cell - 80)
            c.drawBitmap(art, x + 40, y + 40, null)
        }
        return bmp
    }

    /** Riquadri (in pixel) delle tessere del pannello disegnato da [screenFrame], righe da sinistra a destra. */
    fun screenFrameTiles(width: Int = 1080, height: Int = 2400): List<android.graphics.Rect> {
        val w = width.toFloat()
        val h = height.toFloat()
        val tile = (w - 4 * 24f) / 3
        val tiles = ArrayList<android.graphics.Rect>()
        var y = h * 0.40f + 190f
        while (y + tile < h - 120f) {
            for (col in 0 until 3) {
                val x = 24f + col * (tile + 24f)
                tiles.add(android.graphics.Rect(x.toInt(), y.toInt(), (x + tile).toInt(), (y + tile).toInt()))
            }
            y += tile + 24f
        }
        return tiles
    }

    /** Fotogramma di una registrazione dello schermo: video scuro in alto e pannello degli sticker in basso. */
    fun screenFrame(width: Int = 1080, height: Int = 2400, firstIndex: Int = 0): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val w = width.toFloat()
        val h = height.toFloat()
        val videoBottom = h * 0.40f
        c.drawRect(0f, 0f, w, videoBottom, Paint().apply {
            shader = LinearGradient(0f, 0f, w, videoBottom, intArrayOf(0xFF1B1B3A.toInt(), 0xFF693668.toInt(), 0xFFE0577A.toInt()), null, Shader.TileMode.CLAMP)
        })
        c.drawCircle(w * 0.5f, videoBottom * 0.5f, w * 0.18f, fillPaint(0x33FFFFFF))
        for (i in 0 until 4) c.drawCircle(w * 0.92f, videoBottom * (0.35f + i * 0.16f), w * 0.035f, fillPaint(0xCCFFFFFF.toInt()))
        c.drawRoundRect(RectF(w * 0.05f, videoBottom * 0.86f, w * 0.55f, videoBottom * 0.91f), 12f, 12f, fillPaint(0x99FFFFFF.toInt()))
        c.drawRect(0f, videoBottom, w, h, fillPaint(0xFFF8F8F8.toInt()))
        c.drawRoundRect(RectF(w * 0.42f, videoBottom + 24f, w * 0.58f, videoBottom + 36f), 6f, 6f, fillPaint(0xFFCCCCCC.toInt()))
        c.outlinedText("Sticker", w * 0.5f, videoBottom + 110f, 56f, 0xFF161823.toInt(), 0xFFF8F8F8.toInt(), 0f)
        val cols = 3
        val tile = (w - 4 * 24f) / cols
        var i = firstIndex
        var y = videoBottom + 190f
        while (y + tile < h - 120f) {
            for (col in 0 until cols) {
                val x = 24f + col * (tile + 24f)
                c.drawRoundRect(RectF(x, y, x + tile, y + tile), 36f, 36f, fillPaint(Color.WHITE))
                c.drawBitmap(sticker(i++, (tile - 40f).toInt()), x + 20f, y + 20f, null)
            }
            y += tile + 24f
        }
        return bmp
    }
}
