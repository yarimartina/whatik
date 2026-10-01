package com.whatik.image

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * Riquadro di ritaglio quadrato espresso in coordinate normalizzate: [cx] e [cy] sono il
 * centro come frazione di larghezza/altezza dell'immagine, [size] è il lato come frazione
 * del lato minore. Così lo stesso ritaglio vale per anteprime e fotogrammi a risoluzioni diverse.
 */
data class CropSpec(val cx: Float, val cy: Float, val size: Float) {

    fun normalized(): CropSpec {
        val s = size.coerceIn(MIN_SIZE, 1f)
        return CropSpec(cx.coerceIn(0f, 1f), cy.coerceIn(0f, 1f), s)
    }

    /** Rettangolo in pixel (left, top, side) per un'immagine [width]x[height], sempre dentro i bordi. */
    fun toPixels(width: Int, height: Int): IntArray {
        val n = normalized()
        val side = (n.size * minOf(width, height)).roundToInt().coerceIn(1, minOf(width, height))
        val left = (n.cx * width - side / 2f).roundToInt().coerceIn(0, width - side)
        val top = (n.cy * height - side / 2f).roundToInt().coerceIn(0, height - side)
        return intArrayOf(left, top, side)
    }

    /** Ritaglia una copia quadrata; il bitmap sorgente non viene riciclato. */
    fun apply(source: Bitmap): Bitmap {
        val (left, top, side) = toPixels(source.width, source.height)
        if (left == 0 && top == 0 && side == source.width && side == source.height) return source
        return Bitmap.createBitmap(source, left, top, side, side)
    }

    companion object {
        const val MIN_SIZE = 0.1f
        val FULL = CropSpec(0.5f, 0.5f, 1f)
        val DEFAULT = CropSpec(0.5f, 0.5f, 0.6f)
    }
}

/** Applica un [CropSpec] a ogni fotogramma di un altro produttore. */
class CroppedFrameProducer(private val inner: FrameProducer, private val crop: CropSpec) : FrameProducer {
    override val info: FrameInfo
        get() {
            val (_, _, side) = crop.toPixels(inner.info.width, inner.info.height)
            return FrameInfo(side, side, inner.info.durationsMs)
        }

    override fun produce(consume: (Int, Bitmap) -> Boolean) {
        inner.produce { index, frame ->
            val cropped = crop.apply(frame)
            if (cropped !== frame) frame.recycle()
            consume(index, cropped)
        }
    }
}
