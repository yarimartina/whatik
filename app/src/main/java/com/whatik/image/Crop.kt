package com.whatik.image

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Riquadro di ritaglio rettangolare in coordinate normalizzate (frazioni di larghezza e
 * altezza dell'immagine), così lo stesso ritaglio vale per anteprime e fotogrammi a
 * risoluzioni diverse. Il risultato viene poi centrato su un canvas quadrato trasparente.
 */
data class CropSpec(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f

    /** Riporta il riquadro dentro l'immagine, con lati di almeno [MIN_SIZE], mantenendo le dimensioni dove possibile. */
    fun normalized(): CropSpec {
        val l = min(left, right)
        val r = max(left, right)
        val t = min(top, bottom)
        val b = max(top, bottom)
        val w = (r - l).coerceIn(MIN_SIZE, 1f)
        val h = (b - t).coerceIn(MIN_SIZE, 1f)
        val nl = ((l + r) / 2f - w / 2f).coerceIn(0f, 1f - w)
        val nt = ((t + b) / 2f - h / 2f).coerceIn(0f, 1f - h)
        return CropSpec(nl, nt, nl + w, nt + h)
    }

    /** Rettangolo in pixel (left, top, width, height) per un'immagine [width]x[height], sempre dentro i bordi. */
    fun toPixels(width: Int, height: Int): IntArray {
        val n = normalized()
        val w = (n.width * width).roundToInt().coerceIn(1, width)
        val h = (n.height * height).roundToInt().coerceIn(1, height)
        val l = (n.left * width).roundToInt().coerceIn(0, width - w)
        val t = (n.top * height).roundToInt().coerceIn(0, height - h)
        return intArrayOf(l, t, w, h)
    }

    /** Lo stesso riquadro come lo applicherà [toPixels] (arrotondato ai pixel e dentro i bordi). */
    fun effective(width: Int, height: Int): CropSpec {
        val (l, t, w, h) = toPixels(width, height)
        return CropSpec(l.toFloat() / width, t.toFloat() / height, (l + w).toFloat() / width, (t + h).toFloat() / height)
    }

    /** Ritaglia una copia; il bitmap sorgente non viene riciclato. */
    fun apply(source: Bitmap): Bitmap {
        val (l, t, w, h) = toPixels(source.width, source.height)
        if (l == 0 && t == 0 && w == source.width && h == source.height) return source
        return Bitmap.createBitmap(source, l, t, w, h)
    }

    companion object {
        /** Lato minimo come frazione dell'immagine. */
        const val MIN_SIZE = 0.03f
        val FULL = CropSpec(0f, 0f, 1f, 1f)

        /** Quadrato con lato pari a [sizeFraction] del lato minore, centrato in ([cx], [cy]) normalizzati. */
        fun square(cx: Float, cy: Float, sizeFraction: Float, width: Int, height: Int): CropSpec {
            val side = sizeFraction.coerceIn(MIN_SIZE, 1f) * min(width, height)
            val w = side / width
            val h = side / height
            return CropSpec(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f).normalized()
        }

        fun fromPixels(l: Int, t: Int, w: Int, h: Int, width: Int, height: Int): CropSpec =
            CropSpec(l.toFloat() / width, t.toFloat() / height, (l + w).toFloat() / width, (t + h).toFloat() / height).normalized()
    }
}

/** Applica un [CropSpec] a ogni fotogramma di un altro produttore. */
class CroppedFrameProducer(private val inner: FrameProducer, private val crop: CropSpec) : FrameProducer {
    override val info: FrameInfo
        get() {
            val (_, _, w, h) = crop.toPixels(inner.info.width, inner.info.height)
            return FrameInfo(w, h, inner.info.durationsMs)
        }

    override fun produce(consume: (Int, Bitmap) -> Boolean) {
        inner.produce { index, frame ->
            val cropped = crop.apply(frame)
            if (cropped !== frame) frame.recycle()
            consume(index, cropped)
        }
    }
}
