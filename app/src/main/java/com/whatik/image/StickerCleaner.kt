package com.whatik.image

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pulizia degli sticker catturati dal pannello di TikTok.
 *
 * 1. Bande: le righe e le colonne ai bordi fatte tutte del colore del pannello (il bianco
 *    attorno a uno sticker rettangolare) si tolgono ritagliando, senza trasparenze.
 * 2. Ritaglio a sagoma: lo sfondo dentro lo sticker diventa trasparente solo se ciò che resta
 *    è un unico soggetto (un'auto, un personaggio). Se resta tanti pezzi separati (testo,
 *    caselle, un meme) lo sfondo fa parte dello sticker e resta com'è.
 * 3. Angoli: gli angoli arrotondati della tessera diventano trasparenti, con un limite di area.
 *
 * Così il bianco del pannello sparisce, ma un meme su sfondo bianco o grigio chiarissimo resta
 * intero anche se il suo bianco differisce di pochi livelli da quello del pannello.
 * Logica pura, testabile sulla JVM.
 */
object StickerCleaner {

    class Params(
        /** Differenza massima dal colore del pannello per le bande (stretta: il bianco di un meme è vicinissimo). */
        val bandTolerance: Int = 6,
        /** Quota minima di una riga/colonna vicina al pannello perché sia una banda. */
        val bandFraction: Float = 0.96f,
        /** Differenza massima della media di una riga/colonna dal pannello: il rumore JPEG si annulla, lo sfondo di un meme no. */
        val bandMeanTolerance: Int = 3,
        /** Tolleranza per il ritaglio a sagoma di un soggetto unico. */
        val cutoutTolerance: Int = 14,
        /** Quota minima del contorno del contenuto vicina al pannello per tentare il ritaglio a sagoma. */
        val cutoutEdgeFraction: Float = 0.6f,
        /** Differenza massima fra la media dello sfondo tolto e il pannello: oltre, è lo sfondo proprio dello sticker. */
        val cutoutMeanTolerance: Int = 4,
        /** Quota minima del contenuto rimasto nel pezzo più grande perché sia un soggetto unico. */
        val subjectShare: Float = 0.97f,
        /** Sotto questa quota del riquadro il soggetto è troppo piccolo: niente ritaglio a sagoma. */
        val minSubjectFraction: Float = 0.05f,
        /** Tolleranza e area massima per gli angoli arrotondati. */
        val cornerTolerance: Int = 28,
        val cornerAreaCap: Float = 0.05f,
        /** Oltre la tolleranza e fino a qui i pixel di contorno diventano semitrasparenti. */
        val haloTolerance: Int = 60,
        /** Margine trasparente attorno a un soggetto ritagliato a sagoma (frazione del lato maggiore). */
        val cutoutMargin: Float = 0.03f,
    )

    /** Decisione presa sul primo fotogramma e applicata uguale a tutti: niente sfarfallii negli animati. */
    class Plan(
        /** Contenuto dopo il taglio delle bande (left, top, width, height). */
        val content: IntArray,
        /** true se lo sfondo dentro il contenuto si toglie (soggetto unico). */
        val cutout: Boolean,
    )

    fun plan(argb: IntArray, width: Int, height: Int, background: Int, params: Params = Params()): Plan? {
        val rect = trimBands(argb, width, height, background, params) ?: return null
        var cutout = false
        if (perimeterFraction(argb, width, rect, background, params.cutoutTolerance) >= params.cutoutEdgeFraction) {
            val removed = flood(argb, width, rect, background, params.cutoutTolerance, cornersOnly = false)
            cutout = meanDistance(argb, removed, width, rect, background) <= params.cutoutMeanTolerance &&
                singleSubject(removed, width, rect, params)
        }
        return Plan(rect, cutout)
    }

    /**
     * Applica il piano a un fotogramma, modificando [argb] sul posto (trasparenze).
     * @return riquadro da tenere (left, top, width, height)
     */
    fun apply(argb: IntArray, width: Int, height: Int, background: Int, plan: Plan, params: Params = Params()): IntArray {
        val rect = plan.content
        val area = rect[2] * rect[3]
        var tolerance = params.cutoutTolerance
        var removed = if (plan.cutout) {
            flood(argb, width, rect, background, params.cutoutTolerance, cornersOnly = false)
        } else {
            tolerance = params.cornerTolerance
            flood(argb, width, rect, background, params.cornerTolerance, cornersOnly = true)
        }
        // angoli: se il colore "sfondo" dilaga nello sticker non sono angoli arrotondati, si lascia stare
        if (!plan.cutout && removed.count { it } > area * params.cornerAreaCap) removed = BooleanArray(removed.size)

        val (l, t, w, h) = rect
        var bl = l + w; var bt = t + h; var br = -1; var bb = -1
        val span = (params.haloTolerance - tolerance).coerceAtLeast(1)
        for (y in t until t + h) for (x in l until l + w) {
            val p = y * width + x
            if (removed[p]) { argb[p] = argb[p] and 0xFFFFFF; continue }
            val touches = (x > l && removed[p - 1]) || (x < l + w - 1 && removed[p + 1]) ||
                (y > t && removed[p - width]) || (y < t + h - 1 && removed[p + width])
            if (touches) {
                val diff = distance(argb[p], background)
                if (diff < params.haloTolerance) {
                    val alpha = ((diff - tolerance).coerceAtLeast(0) * 255 / span).coerceIn(0, 255)
                    argb[p] = (alpha shl 24) or (argb[p] and 0xFFFFFF)
                    if (alpha == 0) continue
                }
            }
            if (x < bl) bl = x
            if (x > br) br = x
            if (y < bt) bt = y
            if (y > bb) bb = y
        }
        if (!plan.cutout || br < 0) return rect.copyOf()
        // soggetto ritagliato a sagoma: riquadro stretto con un po' di margine trasparente; il margine
        // può uscire dal contenuto (sulle bande tolte), quella parte diventa trasparente
        val m = (max(w, h) * params.cutoutMargin).roundToInt()
        val nl = (bl - m).coerceAtLeast(0)
        val nt = (bt - m).coerceAtLeast(0)
        val nr = (br + 1 + m).coerceAtMost(width)
        val nb = (bb + 1 + m).coerceAtMost(height)
        for (y in nt until nb) for (x in nl until nr) {
            if (x !in l until l + w || y !in t until t + h) argb[y * width + x] = argb[y * width + x] and 0xFFFFFF
        }
        return intArrayOf(nl, nt, nr - nl, nb - nt)
    }

    /** Contenuto senza le bande del colore del pannello sui quattro lati; null se è tutto pannello. */
    internal fun trimBands(argb: IntArray, width: Int, height: Int, background: Int, params: Params): IntArray? {
        var l = 0; var t = 0; var r = width; var b = height
        val mean = LongArray(3)
        fun band(near: Int, count: Int): Boolean {
            if (count == 0 || near < count * params.bandFraction) return false
            val c = rgb((mean[0] / count).toInt(), (mean[1] / count).toInt(), (mean[2] / count).toInt())
            return distance(c, background) <= params.bandMeanTolerance
        }
        fun add(c: Int) { mean[0] += (c shr 16) and 0xFF; mean[1] += (c shr 8) and 0xFF; mean[2] += c and 0xFF }
        fun bandRow(y: Int): Boolean {
            var near = 0
            mean.fill(0)
            for (x in l until r) { val c = argb[y * width + x]; add(c); if (distance(c, background) <= params.bandTolerance) near++ }
            return band(near, r - l)
        }
        fun bandCol(x: Int): Boolean {
            var near = 0
            mean.fill(0)
            for (y in t until b) { val c = argb[y * width + x]; add(c); if (distance(c, background) <= params.bandTolerance) near++ }
            return band(near, b - t)
        }
        var changed = true
        while (changed) {
            changed = false
            while (t < b && bandRow(t)) { t++; changed = true }
            while (b > t && bandRow(b - 1)) { b--; changed = true }
            while (l < r && bandCol(l)) { l++; changed = true }
            while (r > l && bandCol(r - 1)) { r--; changed = true }
        }
        if (r - l < 2 || b - t < 2) return null
        // la riga di passaggio fra banda e contenuto è una sfumatura: si toglie anche quella
        if (t > 0 && b - t > 4) t++
        if (b < height && b - t > 4) b--
        if (l > 0 && r - l > 4) l++
        if (r < width && r - l > 4) r--
        return intArrayOf(l, t, r - l, b - t)
    }

    /** Differenza fra il colore medio dei pixel tolti e il pannello (0 se non se n'è tolto nessuno). */
    private fun meanDistance(argb: IntArray, removed: BooleanArray, width: Int, rect: IntArray, background: Int): Int {
        val (l, t, w, h) = rect
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (y in t until t + h) for (x in l until l + w) {
            val p = y * width + x
            if (!removed[p]) continue
            val c = argb[p]
            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
        }
        if (n == 0) return 0
        return distance(rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt()), background)
    }

    private fun perimeterFraction(argb: IntArray, width: Int, rect: IntArray, background: Int, tolerance: Int): Float {
        val (l, t, w, h) = rect
        var near = 0
        var total = 0
        fun visit(x: Int, y: Int) { total++; if (distance(argb[y * width + x], background) <= tolerance) near++ }
        for (x in l until l + w) { visit(x, t); if (h > 1) visit(x, t + h - 1) }
        for (y in t + 1 until t + h - 1) { visit(l, y); if (w > 1) visit(l + w - 1, y) }
        return if (total == 0) 0f else near.toFloat() / total
    }

    /** Pixel vicini al pannello raggiungibili dal contorno del riquadro (o solo dai suoi angoli). */
    private fun flood(argb: IntArray, width: Int, rect: IntArray, background: Int, tolerance: Int, cornersOnly: Boolean): BooleanArray {
        val (l, t, w, h) = rect
        val removed = BooleanArray(argb.size)
        val stack = IntArray(w * h)
        var top = 0
        fun seed(x: Int, y: Int) {
            val p = y * width + x
            if (!removed[p] && distance(argb[p], background) <= tolerance) { removed[p] = true; stack[top++] = p }
        }
        if (cornersOnly) {
            seed(l, t); seed(l + w - 1, t); seed(l, t + h - 1); seed(l + w - 1, t + h - 1)
        } else {
            for (x in l until l + w) { seed(x, t); seed(x, t + h - 1) }
            for (y in t until t + h) { seed(l, y); seed(l + w - 1, y) }
        }
        while (top > 0) {
            val p = stack[--top]
            val x = p % width
            val y = p / width
            if (x > l) { val q = p - 1; if (!removed[q] && distance(argb[q], background) <= tolerance) { removed[q] = true; stack[top++] = q } }
            if (x < l + w - 1) { val q = p + 1; if (!removed[q] && distance(argb[q], background) <= tolerance) { removed[q] = true; stack[top++] = q } }
            if (y > t) { val q = p - width; if (!removed[q] && distance(argb[q], background) <= tolerance) { removed[q] = true; stack[top++] = q } }
            if (y < t + h - 1) { val q = p + width; if (!removed[q] && distance(argb[q], background) <= tolerance) { removed[q] = true; stack[top++] = q } }
        }
        return removed
    }

    /** true se ciò che resta dopo aver tolto lo sfondo è un pezzo solo (non testo o tanti elementi). */
    private fun singleSubject(removed: BooleanArray, width: Int, rect: IntArray, params: Params): Boolean {
        val (l, t, w, h) = rect
        val remaining = BooleanArray(removed.size)
        var total = 0
        for (y in t until t + h) for (x in l until l + w) {
            val p = y * width + x
            if (!removed[p]) { remaining[p] = true; total++ }
        }
        if (total < w * h * params.minSubjectFraction) return false
        val stack = IntArray(total)
        var largest = 0
        for (y in t until t + h) for (x in l until l + w) {
            val seed = y * width + x
            if (!remaining[seed]) continue
            var top = 0
            stack[top++] = seed
            remaining[seed] = false
            var size = 0
            while (top > 0) {
                val p = stack[--top]
                size++
                val px = p % width
                val py = p / width
                if (px > l && remaining[p - 1]) { remaining[p - 1] = false; stack[top++] = p - 1 }
                if (px < l + w - 1 && remaining[p + 1]) { remaining[p + 1] = false; stack[top++] = p + 1 }
                if (py > t && remaining[p - width]) { remaining[p - width] = false; stack[top++] = p - width }
                if (py < t + h - 1 && remaining[p + width]) { remaining[p + width] = false; stack[top++] = p + width }
            }
            if (size > largest) largest = size
        }
        return largest >= total * params.subjectShare
    }

    private fun rgb(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b

    private fun distance(c: Int, bg: Int): Int =
        max(abs(((c shr 16) and 0xFF) - ((bg shr 16) and 0xFF)), max(abs(((c shr 8) and 0xFF) - ((bg shr 8) and 0xFF)), abs((c and 0xFF) - (bg and 0xFF))))
}
