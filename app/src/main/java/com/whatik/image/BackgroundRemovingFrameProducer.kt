package com.whatik.image

import android.graphics.Bitmap

/**
 * Avvolge un produttore di fotogrammi e pulisce lo sticker con [StickerCleaner]: toglie le bande
 * del colore del pannello, rende trasparenti gli angoli arrotondati e, solo se lo sticker è un
 * soggetto unico, lo sfondo attorno a lui. La decisione si prende sul primo fotogramma e vale
 * per tutti; il riquadro finale è l'unione su tutti i fotogrammi, così lo sticker non "salta".
 * Se non c'è uno sfondo riconoscibile i fotogrammi passano invariati.
 *
 * I fotogrammi puliti per calcolare il riquadro restano in memoria (entro [CACHE_BUDGET_BYTES]):
 * le passate del convertitore li riusano senza decodificarli e pulirli di nuovo.
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
    /** Fotogrammi già puliti, nell'ordine di [inner]; null = non ci stanno nel budget e si ridecodificano. */
    private var cache: List<CleanFrame>? = null
    private var cacheWidth = 0
    private var cacheHeight = 0

    private class CleanFrame(val index: Int, val pixels: IntArray)

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
        // si tengono i fotogrammi puliti solo se tutti insieme stanno nel budget, stimato prima di
        // decodificare; altrimenti ogni passata li ridecodifica come prima
        val budget = cacheBudget()
        val expected = inner.info
        var kept: ArrayList<CleanFrame>? =
            if (expected.frameCount.toLong() * expected.width * expected.height * 4 <= budget) ArrayList() else null
        var keptBytes = 0L
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
            if (w != frameW || h != frameH) {
                // fotogrammi di misure diverse: niente cache, produce() rifà tutto come prima
                kept = null
                return@produce false
            }
            val box = StickerCleaner.apply(px, w, h, b, p, params)
            unionBox = unionBox?.let { BackgroundRemover.union(it, box) } ?: box
            kept?.let { list ->
                // apply() ha già pulito px sul posto: la cache tiene il risultato, non il fotogramma grezzo
                keptBytes += px.size * 4L
                if (keptBytes <= budget) list.add(CleanFrame(i, px)) else kept = null
            }
            true
        }
        val u = unionBox
        if (bg == null || chosen == null || u == null || frameW == 0) return
        background = bg
        plan = chosen
        trim = u
        cache = kept
        cacheWidth = frameW
        cacheHeight = frameH
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
        val cached = cache
        if (cached != null) {
            // stessi pixel di una nuova decodifica + pulizia, senza rifarle
            for (frame in cached) {
                if (!consume(frame.index, trimmed(frame.pixels, cacheWidth, cacheHeight, t))) return
            }
            return
        }
        inner.produce { i, frame ->
            val w = frame.width
            val h = frame.height
            val px = IntArray(w * h)
            frame.getPixels(px, 0, w, 0, 0, w, h)
            frame.recycle()
            StickerCleaner.apply(px, w, h, bg, p, params)
            consume(i, trimmed(px, w, h, t))
        }
    }

    /** Copia del riquadro finale [t] da un fotogramma pulito; [px] non viene modificato. */
    private fun trimmed(px: IntArray, w: Int, h: Int, t: IntArray): Bitmap {
        val tl = t[0].coerceIn(0, w - 1)
        val tt = t[1].coerceIn(0, h - 1)
        val tw = t[2].coerceIn(1, w - tl)
        val th = t[3].coerceIn(1, h - tt)
        return Bitmap.createBitmap(px, tt * w + tl, w, tw, th, Bitmap.Config.ARGB_8888)
    }

    companion object {
        /** Tetto della cache: 8 s a 20 fps di una tessera da ~290 px (160 × 290² × 4 B ≈ 54 MB) ci stanno. */
        const val CACHE_BUDGET_BYTES = 64L * 1024 * 1024

        /** Mai oltre metà dell'heap ancora libero: la cache non deve portare a un OutOfMemoryError. */
        private fun cacheBudget(): Long {
            val runtime = Runtime.getRuntime()
            val free = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
            return minOf(CACHE_BUDGET_BYTES, free / 2)
        }
    }
}
