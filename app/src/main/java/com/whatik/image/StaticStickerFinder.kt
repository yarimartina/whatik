package com.whatik.image

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Trova i bordi di uno sticker fermo attorno a un punto toccato dall'utente.
 *
 * Idea: nel pannello degli sticker (o in una chat) lo sticker è un'isola di colori
 * su uno sfondo uniforme. Si stima il colore di sfondo dal bordo di una finestra
 * centrata sul punto, si marcano i pixel diversi dallo sfondo e si prende la
 * componente connessa che contiene il punto. Logica pura, testabile sulla JVM.
 */
object StaticStickerFinder {

    class Params(
        /** Metà lato della finestra di ricerca, come frazione della larghezza dell'immagine. */
        val windowHalfFraction: Float = 0.35f,
        /** Spessore dell'anello di bordo (frazione del lato finestra) da cui si stima lo sfondo. */
        val ringFraction: Float = 0.15f,
        /** Differenza massima per canale (0..255) perché un pixel conti come sfondo. */
        val backgroundTolerance: Int = 40,
        /** Raggio (frazione della larghezza) entro cui cercare un pixel di sticker se si tocca lo sfondo. */
        val snapRadiusFraction: Float = 0.05f,
        /** Area minima della componente come frazione della finestra. */
        val minAreaFraction: Float = 0.01f,
        /** Oltre questa frazione della finestra la componente è "tutto": non è uno sticker. */
        val maxBoxFraction: Float = 0.9f,
        /** Lato del quadrato di riserva (frazione della larghezza) se non si trova nulla. */
        val fallbackFraction: Float = 0.3f,
    )

    class Result(
        /** Rettangolo (left, top, width, height) in pixel dell'immagine. */
        val box: IntArray,
        /** false se è stato usato il quadrato di riserva centrato sul punto. */
        val found: Boolean,
    )

    /**
     * @param rgb pixel ARGB/RGB (un Int per pixel), riga per riga
     * @param px,py punto toccato in pixel dell'immagine
     */
    fun find(rgb: IntArray, width: Int, height: Int, px: Int, py: Int, params: Params = Params()): Result {
        val x0 = px.coerceIn(0, width - 1)
        val y0 = py.coerceIn(0, height - 1)
        val half = (width * params.windowHalfFraction).roundToInt().coerceAtLeast(8)
        val wl = (x0 - half).coerceAtLeast(0)
        val wt = (y0 - half).coerceAtLeast(0)
        val wr = (x0 + half).coerceAtMost(width - 1)
        val wb = (y0 + half).coerceAtMost(height - 1)
        val ww = wr - wl + 1
        val wh = wb - wt + 1
        if (ww < 8 || wh < 8) return fallback(width, height, x0, y0, params)

        // 1) colore di sfondo: moda dei colori quantizzati sull'anello esterno della finestra
        val ring = max(2, (min(ww, wh) * params.ringFraction).roundToInt())
        val counts = HashMap<Int, Int>()
        for (y in wt..wb) for (x in wl..wr) {
            val onRing = x < wl + ring || x > wr - ring || y < wt + ring || y > wb - ring
            if (!onRing) continue
            val q = quantize(rgb[y * width + x])
            counts[q] = (counts[q] ?: 0) + 1
        }
        val bgQ = counts.maxByOrNull { it.value }?.key ?: return fallback(width, height, x0, y0, params)
        val bg = dequantize(bgQ)

        // 2) maschera dei pixel "non sfondo" nella finestra
        val mask = BooleanArray(ww * wh)
        for (y in 0 until wh) for (x in 0 until ww) {
            mask[y * ww + x] = !isBackground(rgb[(wt + y) * width + (wl + x)], bg, params.backgroundTolerance)
        }
        val dilated = dilate(mask, ww, wh, 2)

        // 3) punto di partenza: il tocco, oppure il pixel di sticker più vicino
        var sx = x0 - wl
        var sy = y0 - wt
        if (!dilated[sy * ww + sx]) {
            val radius = (width * params.snapRadiusFraction).roundToInt().coerceAtLeast(2)
            var best = -1
            var bestD = Int.MAX_VALUE
            for (y in (sy - radius).coerceAtLeast(0)..(sy + radius).coerceAtMost(wh - 1)) {
                for (x in (sx - radius).coerceAtLeast(0)..(sx + radius).coerceAtMost(ww - 1)) {
                    if (!dilated[y * ww + x]) continue
                    val d = abs(x - sx) + abs(y - sy)
                    if (d < bestD) { bestD = d; best = y * ww + x }
                }
            }
            if (best < 0) return fallback(width, height, x0, y0, params)
            sx = best % ww
            sy = best / ww
        }

        // 4) componente connessa che contiene il punto
        val box = componentBox(dilated, ww, wh, sx, sy) ?: return fallback(width, height, x0, y0, params)
        val (l, t, r, b) = box
        val area = (r - l) * (b - t)
        val windowArea = ww * wh
        if (area < windowArea * params.minAreaFraction || area > windowArea * params.maxBoxFraction) {
            return fallback(width, height, x0, y0, params)
        }
        // la dilatazione ha allargato di 2 px: li togliamo
        val left = (wl + l + 2).coerceAtLeast(0)
        val top = (wt + t + 2).coerceAtLeast(0)
        val right = (wl + r - 2).coerceAtMost(width)
        val bottom = (wt + b - 2).coerceAtMost(height)
        if (right - left < 4 || bottom - top < 4) return fallback(width, height, x0, y0, params)
        return Result(intArrayOf(left, top, right - left, bottom - top), found = true)
    }

    /** Riquadro quadrato normalizzato attorno a un rettangolo, con margine. */
    fun toCrop(box: IntArray, width: Int, height: Int, margin: Float = 0.08f): CropSpec {
        val (l, t, w, h) = box
        val side = max(w, h) * (1f + margin)
        return CropSpec.square((l + w / 2f) / width, (t + h / 2f) / height, side / min(width, height), width, height)
    }

    private fun fallback(width: Int, height: Int, x: Int, y: Int, params: Params): Result {
        val side = (width * params.fallbackFraction).roundToInt().coerceIn(8, min(width, height))
        val left = (x - side / 2).coerceIn(0, width - side)
        val top = (y - side / 2).coerceIn(0, height - side)
        return Result(intArrayOf(left, top, side, side), found = false)
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

    /** Rettangolo (left, top, right, bottom esclusivi) della componente connessa che contiene (sx, sy). */
    private fun componentBox(mask: BooleanArray, width: Int, height: Int, sx: Int, sy: Int): IntArray? {
        if (!mask[sy * width + sx]) return null
        val visited = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        var top = 0
        stack[top++] = sy * width + sx
        visited[sy * width + sx] = true
        var l = width; var t = height; var r = -1; var b = -1
        while (top > 0) {
            val p = stack[--top]
            val x = p % width
            val y = p / width
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
            if (x > 0 && mask[p - 1] && !visited[p - 1]) { visited[p - 1] = true; stack[top++] = p - 1 }
            if (x < width - 1 && mask[p + 1] && !visited[p + 1]) { visited[p + 1] = true; stack[top++] = p + 1 }
            if (y > 0 && mask[p - width] && !visited[p - width]) { visited[p - width] = true; stack[top++] = p - width }
            if (y < height - 1 && mask[p + width] && !visited[p + width]) { visited[p + width] = true; stack[top++] = p + width }
        }
        return intArrayOf(l, t, r + 1, b + 1)
    }
}
