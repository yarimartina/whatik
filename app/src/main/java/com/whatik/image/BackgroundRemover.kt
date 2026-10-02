package com.whatik.image

import kotlin.math.abs
import kotlin.math.max

/**
 * Toglie lo sfondo uniforme attorno a uno sticker catturato (il bianco della tessera di TikTok).
 * Diventano trasparenti solo i pixel di colore sfondo raggiungibili dal bordo, con una sfumatura
 * sui pixel di contorno: il bianco dentro lo sticker (occhi, scritte, fondo di un meme chiuso da
 * una cornice) resta com'è. Se il bordo non è per lo più sfondo (foto che riempie la tessera) si
 * puliscono solo gli angoli arrotondati, con un limite di area. Logica pura su pixel ARGB, testabile sulla JVM.
 */
object BackgroundRemover {

    class Params(
        /** Differenza massima per canale (0..255) perché un pixel conti come sfondo. */
        val tolerance: Int = 28,
        /** Sopra [tolerance] e fino a questa differenza i pixel di contorno diventano semitrasparenti. */
        val haloTolerance: Int = 80,
        /** Quota minima del bordo di colore sfondo per partire da tutto il bordo (altrimenti solo dagli angoli). */
        val minEdgeFraction: Float = 0.3f,
        /** Partendo dagli angoli, area massima rimovibile (frazione del fotogramma); oltre, non si tocca nulla. */
        val cornerAreaCap: Float = 0.05f,
        /** Margine attorno al contenuto quando il riquadro viene ristretto (frazione del lato maggiore). */
        val margin: Float = 0.03f,
        /** I canali devono stare sopra questa soglia perché gli angoli contino come "bianchi". */
        val whiteMin: Int = 232,
        /** Quota di pixel non-sfondo lungo un lato del riquadro del contenuto perché il lato conti come "pieno". */
        val solidSide: Float = 0.6f,
        /** Lati pieni (su 4) oltre i quali il contenuto è una foto rettangolare da non bucare. */
        val photoSides: Int = 3,
    )

    /** Esito: riquadro (l,t,w,h) del contenuto rimasto, null se è sparito tutto; pixel resi trasparenti. */
    class Result(val box: IntArray?, val removed: Int)

    /**
     * Colore di sfondo da usare: il suggerimento [hint] rifinito sui pixel del bordo che gli somigliano,
     * altrimenti il colore dominante del bordo; se il bordo non è uniforme ma gli angoli sono bianchi,
     * bianco (verranno puliti solo gli angoli). Null = non c'è uno sfondo da togliere.
     */
    fun resolveBackground(argb: IntArray, width: Int, height: Int, hint: Int?, params: Params = Params()): Int? {
        if (hint != null) {
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            forEachEdge(width, height) { p ->
                val c = argb[p]
                if (near(c, hint, params.tolerance)) { r += red(c); g += green(c); b += blue(c); n++ }
            }
            return if (n == 0) hint and 0xFFFFFF else rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        }
        edgeBackground(argb, width, height, params)?.let { return it }
        val corners = intArrayOf(0, width - 1, (height - 1) * width, width * height - 1)
        val white = corners.count { p -> val c = argb[p]; red(c) >= params.whiteMin && green(c) >= params.whiteMin && blue(c) >= params.whiteMin }
        return if (white >= 3) 0xFFFFFF else null
    }

    /** Colore dominante del bordo (media del gruppo più numeroso), null se non copre [Params.minEdgeFraction] del bordo. */
    fun edgeBackground(argb: IntArray, width: Int, height: Int, params: Params = Params()): Int? {
        val bins = HashMap<Int, LongArray>()
        var total = 0
        forEachEdge(width, height) { p ->
            val c = argb[p]
            val bin = ((c shr 20) and 0xF shl 8) or ((c shr 12) and 0xF shl 4) or ((c shr 4) and 0xF)
            val acc = bins.getOrPut(bin) { LongArray(4) }
            acc[0]++; acc[1] += red(c); acc[2] += green(c); acc[3] += blue(c)
            total++
        }
        val best = bins.values.maxByOrNull { it[0] } ?: return null
        if (best[0].toFloat() / total < params.minEdgeFraction) return null
        return rgb((best[1] / best[0]).toInt(), (best[2] / best[0]).toInt(), (best[3] / best[0]).toInt())
    }

    /**
     * Rende trasparenti (in [argb]) i pixel vicini a [background] raggiungibili dal bordo, con sfumatura
     * sui pixel di contorno. Dal bordo intero se è per lo più sfondo, altrimenti solo dagli angoli
     * e con il limite [Params.cornerAreaCap] (superato il quale non si modifica nulla).
     */
    fun removeConnected(argb: IntArray, width: Int, height: Int, background: Int, params: Params = Params()): Result {
        val n = width * height
        val removed = BooleanArray(n)
        val stack = IntArray(n)
        var top = 0
        var edgeMatches = 0
        var edgeTotal = 0
        forEachEdge(width, height) { p -> edgeTotal++; if (near(argb[p], background, params.tolerance)) edgeMatches++ }
        val fromEdges = edgeTotal > 0 && edgeMatches.toFloat() / edgeTotal >= params.minEdgeFraction
        if (fromEdges) {
            forEachEdge(width, height) { p ->
                if (!removed[p] && near(argb[p], background, params.tolerance)) { removed[p] = true; stack[top++] = p }
            }
        } else {
            for (p in intArrayOf(0, width - 1, (height - 1) * width, n - 1)) {
                if (p in 0 until n && !removed[p] && near(argb[p], background, params.tolerance)) { removed[p] = true; stack[top++] = p }
            }
        }
        var count = 0
        while (top > 0) {
            val p = stack[--top]
            count++
            val x = p % width
            val y = p / width
            if (x > 0) { val q = p - 1; if (!removed[q] && near(argb[q], background, params.tolerance)) { removed[q] = true; stack[top++] = q } }
            if (x < width - 1) { val q = p + 1; if (!removed[q] && near(argb[q], background, params.tolerance)) { removed[q] = true; stack[top++] = q } }
            if (y > 0) { val q = p - width; if (!removed[q] && near(argb[q], background, params.tolerance)) { removed[q] = true; stack[top++] = q } }
            if (y < height - 1) { val q = p + width; if (!removed[q] && near(argb[q], background, params.tolerance)) { removed[q] = true; stack[top++] = q } }
        }
        if (count == 0) return Result(intArrayOf(0, 0, width, height), 0)
        if (!fromEdges && count > n * params.cornerAreaCap) return Result(intArrayOf(0, 0, width, height), 0)
        count = protectPhoto(argb, width, height, background, removed, params, count)

        // contorno sfumato: i pixel accanto allo sfondo tolto, ancora simili allo sfondo, diventano semitrasparenti
        val span = (params.haloTolerance - params.tolerance).coerceAtLeast(1)
        var l = width; var t = height; var r = -1; var b = -1
        for (p in 0 until n) {
            if (removed[p]) { argb[p] = argb[p] and 0xFFFFFF; continue }
            val x = p % width
            val y = p / width
            val touches = (x > 0 && removed[p - 1]) || (x < width - 1 && removed[p + 1]) || (y > 0 && removed[p - width]) || (y < height - 1 && removed[p + width])
            if (touches) {
                val diff = distance(argb[p], background)
                if (diff < params.haloTolerance) {
                    val alpha = ((diff - params.tolerance).coerceAtLeast(0) * 255 / span).coerceIn(0, 255)
                    argb[p] = (alpha shl 24) or (argb[p] and 0xFFFFFF)
                    if (alpha == 0) continue
                }
            }
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
        }
        val box = if (r < 0) null else intArrayOf(l, t, r - l + 1, b - t + 1)
        return Result(box, count)
    }

    /**
     * Se il contenuto è una foto rettangolare (riquadro dei pixel non-sfondo con almeno
     * [Params.photoSides] lati pieni), dentro quel riquadro lo sfondo tolto viene ripristinato,
     * tranne gli angoli arrotondati della tessera: così un cielo o un bagliore chiaro che tocca
     * il bordo della foto non viene bucato. Restituisce il nuovo numero di pixel tolti.
     */
    private fun protectPhoto(argb: IntArray, width: Int, height: Int, background: Int, removed: BooleanArray, params: Params, count: Int): Int {
        var cl = width; var ct = height; var cr = -1; var cb = -1
        for (p in argb.indices) {
            if (near(argb[p], background, params.tolerance)) continue
            val x = p % width
            val y = p / width
            if (x < cl) cl = x
            if (x > cr) cr = x
            if (y < ct) ct = y
            if (y > cb) cb = y
        }
        if (cr < 0 || cr - cl < 4 || cb - ct < 4) return count
        var solid = 0
        fun sideFraction(xs: IntProgression, ys: IntProgression): Float {
            var total = 0; var content = 0
            for (y in ys) for (x in xs) { total++; if (!near(argb[y * width + x], background, params.tolerance)) content++ }
            return if (total == 0) 0f else content.toFloat() / total
        }
        if (sideFraction(cl..cr, ct..ct) >= params.solidSide) solid++
        if (sideFraction(cl..cr, cb..cb) >= params.solidSide) solid++
        if (sideFraction(cl..cl, ct..cb) >= params.solidSide) solid++
        if (sideFraction(cr..cr, ct..cb) >= params.solidSide) solid++
        if (solid < params.photoSides) return count
        // angoli arrotondati: sfondo tolto raggiungibile dagli angoli del riquadro, con limite di area
        val keep = BooleanArray(argb.size)
        val stack = IntArray(argb.size)
        var top = 0
        var kept = 0
        for (p in intArrayOf(ct * width + cl, ct * width + cr, cb * width + cl, cb * width + cr)) {
            if (removed[p] && !keep[p]) { keep[p] = true; stack[top++] = p }
        }
        while (top > 0) {
            val p = stack[--top]
            kept++
            val x = p % width
            val y = p / width
            if (x > cl) { val q = p - 1; if (removed[q] && !keep[q]) { keep[q] = true; stack[top++] = q } }
            if (x < cr) { val q = p + 1; if (removed[q] && !keep[q]) { keep[q] = true; stack[top++] = q } }
            if (y > ct) { val q = p - width; if (removed[q] && !keep[q]) { keep[q] = true; stack[top++] = q } }
            if (y < cb) { val q = p + width; if (removed[q] && !keep[q]) { keep[q] = true; stack[top++] = q } }
        }
        val area = (cr - cl + 1) * (cb - ct + 1)
        val keepCorners = kept <= area * params.cornerAreaCap
        var restored = 0
        for (y in ct..cb) for (x in cl..cr) {
            val p = y * width + x
            if (removed[p] && !(keepCorners && keep[p])) { removed[p] = false; restored++ }
        }
        return count - restored
    }

    /** Riquadro [box] allargato del margine e limitato al fotogramma. */
    fun expand(box: IntArray, width: Int, height: Int, params: Params = Params()): IntArray {
        val m = (max(width, height) * params.margin).toInt()
        val l = (box[0] - m).coerceAtLeast(0)
        val t = (box[1] - m).coerceAtLeast(0)
        val r = (box[0] + box[2] + m).coerceAtMost(width)
        val b = (box[1] + box[3] + m).coerceAtMost(height)
        return intArrayOf(l, t, r - l, b - t)
    }

    fun union(a: IntArray, b: IntArray): IntArray {
        val l = minOf(a[0], b[0])
        val t = minOf(a[1], b[1])
        val r = maxOf(a[0] + a[2], b[0] + b[2])
        val btm = maxOf(a[1] + a[3], b[1] + b[3])
        return intArrayOf(l, t, r - l, btm - t)
    }

    private inline fun forEachEdge(width: Int, height: Int, visit: (Int) -> Unit) {
        if (width <= 0 || height <= 0) return
        for (x in 0 until width) { visit(x); if (height > 1) visit((height - 1) * width + x) }
        for (y in 1 until height - 1) { visit(y * width); if (width > 1) visit(y * width + width - 1) }
    }

    private fun distance(c: Int, bg: Int): Int = max(abs(red(c) - red(bg)), max(abs(green(c) - green(bg)), abs(blue(c) - blue(bg))))
    private fun near(c: Int, bg: Int, tolerance: Int): Boolean = distance(c, bg) <= tolerance
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF
    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b
}
