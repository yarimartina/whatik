package com.whatik.image

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Trova le tessere del pannello sticker di TikTok in un singolo fotogramma: isole di
 * contenuto su uno sfondo uniforme, di dimensione da sticker, quasi quadrate e piene.
 * Scarta il video in alto (troppo grande), icone e testo (troppo piccoli o sottili), la
 * tessera "+" (quasi vuota) e le tessere tagliate dal bordo dello schermo.
 * Logica pura, testabile sulla JVM.
 */
object TileGridFinder {

    class Params(
        /** Lato minimo e massimo di una tessera come frazione della larghezza del fotogramma. */
        val minSideFraction: Float = 0.12f,
        val maxSideFraction: Float = 0.42f,
        /** Rapporto massimo fra i lati. */
        val maxAspect: Float = 1.35f,
        /** Quota minima del rettangolo coperta dai pixel "non sfondo". */
        val minFill: Float = 0.55f,
        /** Differenza massima per canale (0..255) perché un pixel conti come sfondo. */
        val backgroundTolerance: Int = 28,
        /** Distanza dal bordo (frazione della larghezza) sotto cui la tessera e' tagliata. */
        val borderFraction: Float = 0.01f,
        /** Lo sfondo del pannello si stima su questa frazione inferiore del fotogramma. */
        val panelFraction: Float = 0.6f,
    )

    /**
     * @param rgb pixel (un Int per pixel), riga per riga
     * @return rettangoli (left, top, width, height) in ordine di lettura
     */
    fun find(rgb: IntArray, width: Int, height: Int, params: Params = Params()): List<IntArray> {
        // 1) sfondo del pannello: colore piu' frequente nella parte bassa del fotogramma
        val counts = HashMap<Int, Int>()
        val fromY = (height * (1f - params.panelFraction)).roundToInt().coerceIn(0, height - 1)
        for (y in fromY until height) for (x in 0 until width) {
            val q = quantize(rgb[y * width + x])
            counts[q] = (counts[q] ?: 0) + 1
        }
        val bg = dequantize(counts.maxByOrNull { it.value }?.key ?: return emptyList())

        // 2) maschera dei pixel non sfondo, con una leggera dilatazione per chiudere i buchi
        val mask = BooleanArray(width * height)
        for (p in mask.indices) mask[p] = !isBackground(rgb[p], bg, params.backgroundTolerance)
        val dilated = dilate(mask, width, height, 1)

        // 3) componenti connesse e filtri
        val minSide = (width * params.minSideFraction).roundToInt()
        val maxSide = (width * params.maxSideFraction).roundToInt()
        val border = (width * params.borderFraction).roundToInt().coerceAtLeast(1)
        val visited = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        val tiles = ArrayList<IntArray>()
        for (seed in dilated.indices) {
            if (!dilated[seed] || visited[seed]) continue
            var top = 0
            stack[top++] = seed
            visited[seed] = true
            var area = 0
            var l = width; var t = height; var r = -1; var b = -1
            while (top > 0) {
                val p = stack[--top]
                area++
                val x = p % width
                val y = p / width
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
                if (x > 0 && dilated[p - 1] && !visited[p - 1]) { visited[p - 1] = true; stack[top++] = p - 1 }
                if (x < width - 1 && dilated[p + 1] && !visited[p + 1]) { visited[p + 1] = true; stack[top++] = p + 1 }
                if (y > 0 && dilated[p - width] && !visited[p - width]) { visited[p - width] = true; stack[top++] = p - width }
                if (y < height - 1 && dilated[p + width] && !visited[p + width]) { visited[p + width] = true; stack[top++] = p + width }
            }
            val w = r - l + 1
            val h = b - t + 1
            if (w < minSide || h < minSide || w > maxSide || h > maxSide) continue
            if (max(w, h).toFloat() / min(w, h) > params.maxAspect) continue
            if (area.toFloat() / (w * h) < params.minFill) continue
            if (l <= border || t <= border || r >= width - 1 - border || b >= height - 1 - border) continue
            tiles.add(intArrayOf(l, t, w, h))
        }

        if (tiles.isEmpty()) return tiles
        // 4) completamento: le tessere con l'interno chiaro si confondono con lo sfondo; la griglia
        //    e' regolare, quindi si provano le posizioni mancanti di ogni riga
        val completed = completeGrid(tiles, mask, width, height, border, params)
        // 5) ordine di lettura: righe (tolleranza mezza tessera), poi da sinistra a destra
        val rowTolerance = completed.map { it[3] }.average() / 2
        return completed.sortedWith(compareBy({ (it[1] / rowTolerance).roundToInt() }, { it[0] }))
    }

    /** Inserisce le posizioni di griglia mancanti che contengono abbastanza contenuto non-sfondo. */
    internal fun completeGrid(tiles: List<IntArray>, mask: BooleanArray, width: Int, height: Int, border: Int, params: Params): List<IntArray> {
        val size = tiles.map { max(it[2], it[3]) }.sorted().let { it[it.size / 2] }
        val rowTolerance = size / 2
        val rows = tiles.sortedBy { it[1] }.fold(ArrayList<ArrayList<IntArray>>()) { acc, t ->
            val row = acc.lastOrNull()
            if (row != null && abs(row[0][1] - t[1]) <= rowTolerance) row.add(t) else acc.add(arrayListOf(t))
            acc
        }
        // passo della griglia: il piu' piccolo scarto fra tessere adiacenti della stessa riga
        val gaps = rows.flatMap { row -> row.map { it[0] }.sorted().zipWithNext { a, b -> b - a } }.filter { it > size }
        val pitch = gaps.minOrNull() ?: return tiles
        val result = ArrayList(tiles)
        for (row in rows) {
            val xs = row.map { it[0] }.sorted()
            val top = row.map { it[1] }.average().roundToInt()
            val candidates = ArrayList<Int>()
            // posizioni fra tessere note e verso i bordi
            var x = xs.first() - pitch
            while (x > border) { candidates.add(x); x -= pitch }
            for ((a, b) in xs.zipWithNext()) {
                val steps = ((b - a).toFloat() / pitch).roundToInt()
                if (steps >= 2 && abs((b - a) - steps * pitch) <= pitch * 0.15f) {
                    for (k in 1 until steps) candidates.add(a + k * pitch)
                }
            }
            x = xs.last() + pitch
            while (x + size < width - border) { candidates.add(x); x += pitch }
            for (cx in candidates) {
                val rect = intArrayOf(cx, top, size, size)
                if (rect[0] + size >= width - border || rect[1] + size >= height - border || rect[1] <= border) continue
                if (result.any { overlaps(it, rect) }) continue
                if (contentFraction(mask, width, rect) >= 0.12f) result.add(rect)
            }
        }
        return result
    }

    private fun overlaps(a: IntArray, b: IntArray): Boolean =
        a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3]

    /** Quota di pixel non-sfondo nella parte interna (rientro 5%) del rettangolo. */
    private fun contentFraction(mask: BooleanArray, width: Int, rect: IntArray): Float {
        val inset = (rect[2] * 0.05f).roundToInt()
        var count = 0
        var total = 0
        for (y in rect[1] + inset until rect[1] + rect[3] - inset) for (x in rect[0] + inset until rect[0] + rect[2] - inset) {
            val p = y * width + x
            if (p < 0 || p >= mask.size) continue
            total++
            if (mask[p]) count++
        }
        return if (total == 0) 0f else count.toFloat() / total
    }

    private fun quantize(c: Int): Int = ((c shr 19) and 0x1F shl 10) or ((c shr 11) and 0x1F shl 5) or ((c shr 3) and 0x1F)

    private fun dequantize(q: Int): Int {
        val r = ((q shr 10) and 0x1F) shl 3
        val g = ((q shr 5) and 0x1F) shl 3
        val b = (q and 0x1F) shl 3
        return (r shl 16) or (g shl 8) or b
    }

    private fun isBackground(c: Int, bg: Int, tolerance: Int): Boolean {
        val dr = abs(((c shr 16) and 0xFF) - ((bg shr 16) and 0xFF))
        val dg = abs(((c shr 8) and 0xFF) - ((bg shr 8) and 0xFF))
        val db = abs((c and 0xFF) - (bg and 0xFF))
        return max(dr, max(dg, db)) <= tolerance
    }

    private fun dilate(mask: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        for (y in 0 until height) for (x in 0 until width) {
            if (!mask[y * width + x]) continue
            for (dy in -radius..radius) {
                val yy = y + dy
                if (yy < 0 || yy >= height) continue
                for (dx in -radius..radius) {
                    val xx = x + dx
                    if (xx < 0 || xx >= width) continue
                    out[yy * width + xx] = true
                }
            }
        }
        return out
    }
}
