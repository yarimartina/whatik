package com.whatik.image

import android.graphics.Bitmap

/**
 * Avvolge un produttore di fotogrammi togliendo lo sfondo uniforme attorno allo sticker (vedi
 * [BackgroundRemover]) e restringendo il riquadro al contenuto. Per gli animati il riquadro è
 * l'unione su tutti i fotogrammi, così lo sticker non "salta". Se non c'è uno sfondo da togliere
 * i fotogrammi passano invariati.
 */
class BackgroundRemovingFrameProducer(
    private val inner: FrameProducer,
    /** Colore di sfondo noto (per esempio quello del pannello); null = stimato dal bordo. */
    private val backgroundHint: Int? = null,
    private val params: BackgroundRemover.Params = BackgroundRemover.Params(),
) : FrameProducer {
    private var prepared = false
    private var background: Int? = null
    private var trim: IntArray? = null

    override val info: FrameInfo
        get() {
            prepare()
            val t = trim ?: return inner.info
            return FrameInfo(t[2], t[3], inner.info.durationsMs)
        }

    private fun prepare() {
        if (prepared) return
        prepared = true
        var bg: Int? = null
        var unionBox: IntArray? = null
        var frameW = 0
        var frameH = 0
        inner.produce { i, frame ->
            val w = frame.width
            val h = frame.height
            val px = IntArray(w * h)
            frame.getPixels(px, 0, w, 0, 0, w, h)
            frame.recycle()
            if (i == 0) {
                frameW = w; frameH = h
                bg = BackgroundRemover.resolveBackground(px, w, h, backgroundHint, params)
            }
            val b = bg ?: return@produce false
            if (w != frameW || h != frameH) return@produce false
            val box = BackgroundRemover.removeConnected(px, w, h, b, params).box
            if (box != null) unionBox = unionBox?.let { BackgroundRemover.union(it, box) } ?: box
            true
        }
        val b = bg
        val u = unionBox
        if (b == null || u == null || frameW == 0) return
        background = b
        trim = BackgroundRemover.expand(u, frameW, frameH, params)
    }

    override fun produce(consume: (Int, Bitmap) -> Boolean) {
        prepare()
        val bg = background
        val t = trim
        if (bg == null || t == null) {
            inner.produce(consume)
            return
        }
        inner.produce { i, frame ->
            val w = frame.width
            val h = frame.height
            val px = IntArray(w * h)
            frame.getPixels(px, 0, w, 0, 0, w, h)
            frame.recycle()
            BackgroundRemover.removeConnected(px, w, h, bg, params)
            val tl = t[0].coerceIn(0, w - 1)
            val tt = t[1].coerceIn(0, h - 1)
            val tw = t[2].coerceIn(1, w - tl)
            val th = t[3].coerceIn(1, h - tt)
            val out = Bitmap.createBitmap(px, tt * w + tl, w, tw, th, Bitmap.Config.ARGB_8888)
            consume(i, out)
        }
    }
}
