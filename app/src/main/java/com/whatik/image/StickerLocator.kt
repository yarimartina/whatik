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

    /** Colore più frequente nell'anello di 3 pixel appena fuori dal riquadro. */
    private fun ringColor(rgb: IntArray, width: Int, height: Int, l: Int, t: Int, w: Int, h: Int): Int? {
        val ring = 3
        val counts = HashMap<Int, IntArray>()
        for (yy in (t - ring).coerceAtLeast(0) until (t + h + ring).coerceAtMost(height)) {
            for (xx in (l - ring).coerceAtLeast(0) until (l + w + ring).coerceAtMost(width)) {
                if (xx in l until l + w && yy in t until t + h) continue
                val c = rgb[yy * width + xx]
                val q = ((c shr 19) and 0x1F shl 10) or ((c shr 11) and 0x1F shl 5) or ((c shr 3) and 0x1F)
                val acc = counts.getOrPut(q) { IntArray(4) }
                acc[0]++; acc[1] += (c shr 16) and 0xFF; acc[2] += (c shr 8) and 0xFF; acc[3] += c and 0xFF
            }
        }
        val best = counts.values.maxByOrNull { it[0] } ?: return null
        return ((best[1] / best[0]) shl 16) or ((best[2] / best[0]) shl 8) or (best[3] / best[0])
    }
}
