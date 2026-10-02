package com.whatik.image

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Rifinisce una regione trovata dal rilevatore di movimento usando un fotogramma a colori.
 *
 * Gli sticker di TikTok sono spezzoni di video: spesso si muove solo una parte (una faccia),
 * mentre il resto del riquadro dello sticker resta fermo. Il solo movimento produrrebbe
 * ritagli tagliati male; qui il riquadro viene allargato ai bordi dello sticker fermo sullo
 * sfondo ([StaticStickerFinder]) e si scartano i casi evidentemente sbagliati: regioni che
 * toccano il bordo dello schermo, strisce troppo allungate, aree enormi.
 */
object StickerRefiner {

    class Params(
        /** Distanza dal bordo del fotogramma (frazione della larghezza) sotto cui la regione è "tagliata". */
        val borderFraction: Float = 0.01f,
        /** Rapporto massimo fra i lati del riquadro finale. */
        val maxAspect: Float = 2.2f,
        /** Area massima del riquadro finale come frazione del fotogramma. */
        val maxAreaFraction: Float = 0.6f,
        /** Il riquadro statico può allargare quello del movimento al massimo di questo fattore (area). */
        val maxGrowth: Float = 8f,
        /** Margine attorno al riquadro finale. */
        val margin: Float = 0.06f,
    )

    /**
     * @param box regione del movimento (left, top, width, height) in pixel dell'analisi [aw]x[ah]
     * @param rgb fotogramma a colori [fw]x[fh]
     * @param seed punto (in pixel del fotogramma) da cui cercare lo sticker fermo; null = centro della regione
     * @return riquadro quadrato normalizzato, oppure null se la regione va scartata
     */
    fun refine(
        box: IntArray, aw: Int, ah: Int,
        rgb: IntArray, fw: Int, fh: Int,
        seed: IntArray? = null,
        params: Params = Params(),
    ): CropSpec? {
        val sx = fw.toFloat() / aw
        val sy = fh.toFloat() / ah
        val ml = (box[0] * sx).roundToInt()
        val mt = (box[1] * sy).roundToInt()
        val mr = ((box[0] + box[2]) * sx).roundToInt().coerceAtMost(fw)
        val mb = ((box[1] + box[3]) * sy).roundToInt().coerceAtMost(fh)
        if (mr <= ml || mb <= mt) return null

        val border = (fw * params.borderFraction).roundToInt().coerceAtLeast(1)
        if (ml <= border || mt <= border || mr >= fw - border || mb >= fh - border) return null

        var l = ml; var t = mt; var r = mr; var b = mb
        val cx = seed?.get(0) ?: (ml + mr) / 2
        val cy = seed?.get(1) ?: (mt + mb) / 2
        val static = StaticStickerFinder.find(rgb, fw, fh, cx, cy)
        if (static.found) {
            val (sl, st, sw, sh) = static.box
            val sr = sl + sw
            val sb = st + sh
            val intersects = sl < mr && ml < sr && st < mb && mt < sb
            val motionArea = (mr - ml).toLong() * (mb - mt)
            val staticArea = sw.toLong() * sh
            if (intersects && staticArea <= motionArea * params.maxGrowth) {
                l = min(l, sl); t = min(t, st); r = max(r, sr); b = max(b, sb)
            }
        }
        val w = r - l
        val h = b - t
        if (w <= 0 || h <= 0) return null
        if (max(w, h).toFloat() / min(w, h) > params.maxAspect) return null
        if (w.toLong() * h > fw.toLong() * fh * params.maxAreaFraction) return null
        return StaticStickerFinder.toCrop(intArrayOf(l, t, w, h), fw, fh, params.margin)
    }
}
