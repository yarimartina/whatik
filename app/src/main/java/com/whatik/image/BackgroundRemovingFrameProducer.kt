package com.whatik.image

import android.graphics.Bitmap

/**
 * Avvolge un produttore di fotogrammi e pulisce lo sticker con [StickerCleaner]: toglie le bande
 * del colore del pannello, rende trasparenti gli angoli arrotondati e, solo se lo sticker è un
 * soggetto unico, lo sfondo attorno a lui. La decisione si prende sul primo fotogramma e vale
 * per tutti; il riquadro finale è l'unione su tutti i fotogrammi, così lo sticker non "salta".
 * Se non c'è uno sfondo riconoscibile i fotogrammi passano invariati.
 */
class BackgroundRemovingFrameProducer(
    private val inner: FrameProducer,
    /** Colore del pannello misurato su un'area grande (preciso); null = stimato dal bordo dello sticker. */
    private val backgroundHint: Int? = null,
    private val params: StickerCleaner.Params = StickerCleaner.Params(),
) : FrameProducer {
    private var prepared = false
    private var background: Int? = null
    private var plan: StickerCleaner.Plan? = null
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
        var chosen: StickerCleaner.Plan? = null
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
                // il colore del pannello, se noto, è più preciso di qualunque stima sul bordo dello sticker
                bg = backgroundHint?.let { it and 0xFFFFFF } ?: BackgroundRemover.resolveBackground(px, w, h, null)
                chosen = bg?.let { StickerCleaner.plan(px, w, h, it, params) }
            }
            val b = bg ?: return@produce false
            val p = chosen ?: return@produce false
            if (w != frameW || h != frameH) return@produce false
            val box = StickerCleaner.apply(px, w, h, b, p, params)
            unionBox = unionBox?.let { BackgroundRemover.union(it, box) } ?: box
            true
        }
        val u = unionBox
        if (bg == null || chosen == null || u == null || frameW == 0) return
        background = bg
        plan = chosen
        trim = u
    }

    override fun produce(consume: (Int, Bitmap) -> Boolean) {
        prepare()
        val bg = background
        val p = plan
        val t = trim
        if (bg == null || p == null || t == null) {
            inner.produce(consume)
            return
        }
        inner.produce { i, frame ->
            val w = frame.width
            val h = frame.height
            val px = IntArray(w * h)
            frame.getPixels(px, 0, w, 0, 0, w, h)
            frame.recycle()
            StickerCleaner.apply(px, w, h, bg, p, params)
            val tl = t[0].coerceIn(0, w - 1)
            val tt = t[1].coerceIn(0, h - 1)
            val tw = t[2].coerceIn(1, w - tl)
            val th = t[3].coerceIn(1, h - tt)
            val out = Bitmap.createBitmap(px, tt * w + tl, w, tw, th, Bitmap.Config.ARGB_8888)
            consume(i, out)
        }
    }
}
