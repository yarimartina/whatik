package com.whatik.image

import kotlin.math.max
import kotlin.math.min

/**
 * Trova lo sticker toccato con "Punta": prima come tessera del pannello di TikTok
 * ([TileGridFinder]), poi come sticker isolato su uno sfondo uniforme ([StaticStickerFinder]).
 * Restituisce i bordi dello sticker e il colore dello sfondo attorno, che serve a renderlo
 * trasparente. Logica pura, testabile sulla JVM.
 */
object StickerLocator {

    class Located(
        /** Bordi dello sticker (left, top, width, height) in pixel dell'immagine. */
        val box: IntArray,
        /** Colore di sfondo attorno allo sticker (RGB), se noto. */
        val background: Int?,
        /** true se lo sticker è una tessera del pannello. */
        val fromGrid: Boolean,
    )

    class Params(
        /** Tolleranza attorno a una tessera, come frazione del suo lato: tocchi appena fuori la selezionano. */
        val tileReach: Float = 0.25f,
        /** Lato minimo e massimo di uno sticker isolato, come frazione della larghezza. */
        val minSideFraction: Float = 0.06f,
        val maxSideFraction: Float = 0.6f,
        val maxAspect: Float = 2.2f,
    )

    fun locate(rgb: IntArray, width: Int, height: Int, px: Int, py: Int, params: Params = Params()): Located? {
        val x = px.coerceIn(0, width - 1)
        val y = py.coerceIn(0, height - 1)

        // 1) tessera del pannello che contiene il punto, o la più vicina entro la tolleranza
        val grid = TileGridFinder.analyze(rgb, width, height)
        val inside = grid.tiles.firstOrNull { (l, t, w, h) -> x in l until l + w && y in t until t + h }
        val tile = inside ?: grid.tiles.filter { (l, t, w, h) ->
            val reach = (max(w, h) * params.tileReach).toInt()
            x in (l - reach) until (l + w + reach) && y in (t - reach) until (t + h + reach)
        }.minByOrNull { (l, t, w, h) ->
            val dx = (l + w / 2) - x
            val dy = (t + h / 2) - y
            dx * dx + dy * dy
        }
        if (tile != null) return Located(tile.copyOf(), grid.background and 0xFFFFFF, true)

        // 2) sticker isolato su uno sfondo uniforme
        val still = StaticStickerFinder.find(rgb, width, height, x, y)
        if (!still.found) return null
        val (l, t, w, h) = still.box
        val side = max(w, h)
        if (side < width * params.minSideFraction || side > width * params.maxSideFraction) return null
        if (side.toFloat() / min(w, h).coerceAtLeast(1) > params.maxAspect) return null
        return Located(still.box.copyOf(), ringColor(rgb, width, height, l, t, w, h), false)
    }

    /**
     * Colore più frequente nell'anello di 3 pixel appena fuori dal riquadro (media del gruppo quantizzato più
     * numeroso), null se l'anello è vuoto. Istogramma su un array a 15 bit; a parità di conteggio vince il
     * gruppo con la chiave quantizzata più bassa (con la vecchia HashMap, il primo nell'ordine di iterazione).
     */
    internal fun ringColor(rgb: IntArray, width: Int, height: Int, l: Int, t: Int, w: Int, h: Int): Int? {
        val ring = 3
        val counts = IntArray(QUANT_BINS)
        forEachRingPixel(rgb, width, height, l, t, w, h, ring) { c -> counts[quantize(c)]++ }
        var best = -1
        var bestCount = 0
        for (q in counts.indices) if (counts[q] > bestCount) { bestCount = counts[q]; best = q }
        if (best < 0) return null
        // seconda passata solo sui pixel del gruppo vincente
        var r = 0; var g = 0; var b = 0
        forEachRingPixel(rgb, width, height, l, t, w, h, ring) { c ->
            if (quantize(c) == best) { r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF }
        }
        return ((r / bestCount) shl 16) or ((g / bestCount) shl 8) or (b / bestCount)
    }

    private inline fun forEachRingPixel(rgb: IntArray, width: Int, height: Int, l: Int, t: Int, w: Int, h: Int, ring: Int, visit: (Int) -> Unit) {
        for (yy in (t - ring).coerceAtLeast(0) until (t + h + ring).coerceAtMost(height)) {
            for (xx in (l - ring).coerceAtLeast(0) until (l + w + ring).coerceAtMost(width)) {
                if (xx in l until l + w && yy in t until t + h) continue
                visit(rgb[yy * width + xx])
            }
        }
    }

    /** Colori quantizzati a 5 bit per canale: 32768 gruppi. */
    private const val QUANT_BINS = 1 shl 15

    private fun quantize(c: Int): Int = ((c shr 19) and 0x1F shl 10) or ((c shr 11) and 0x1F shl 5) or ((c shr 3) and 0x1F)
}
