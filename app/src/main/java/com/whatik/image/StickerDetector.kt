package com.whatik.image

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Trova automaticamente gli sticker animati in una registrazione dello schermo.
 *
 * Idea: in TikTok lo sfondo (interfaccia, pannello degli sticker) è fermo mentre gli
 * sticker si muovono in continuazione. Su una sequenza di fotogrammi a bassa risoluzione
 * in scala di grigi si cerca la finestra temporale senza scorrimenti, si misura per
 * ogni pixel quanto spesso cambia, si raggruppano i pixel "vivi" in regioni e per
 * ciascuna si stima il periodo del loop tramite autocorrelazione.
 *
 * Logica pura, senza dipendenze Android, per essere testabile sulla JVM.
 */
object StickerDetector {

    class Proposal(
        /** Riquadro quadrato normalizzato, pronto per l'editor e il converter. */
        val crop: CropSpec,
        val startMs: Long,
        val endMs: Long,
        /** Rettangolo rilevato (left, top, width, height) nei pixel del fotogramma analizzato. */
        val box: IntArray,
        /** Quanto è "viva" la regione (0..1): serve per ordinare e filtrare. */
        val score: Float,
    )

    class Params(
        /** Differenza minima di grigio perché un pixel conti come "cambiato". */
        val pixelThreshold: Int = 20,
        /** Oltre questa frazione di pixel cambiati il fotogramma è uno scorrimento/transizione. */
        val globalChangeMax: Float = 0.35f,
        /** Frazione minima di confronti in cui il pixel deve cambiare per essere "animato". */
        val minChangeFrequency: Float = 0.2f,
        /** Area minima di una regione come frazione del fotogramma. */
        val minAreaFraction: Float = 0.003f,
        /** Area massima del rettangolo come frazione del fotogramma (oltre = video di sfondo in riproduzione). */
        val maxBoxFraction: Float = 0.7f,
        /** Margine aggiunto attorno al rettangolo rilevato. */
        val margin: Float = 0.12f,
        /** Durata di default del clip se non si trova un periodo. */
        val defaultClipMs: Long = 4000,
        val maxClipMs: Long = 10_000,
        val minClipMs: Long = 300,
        /** Numero minimo di confronti consecutivi stabili per considerare una finestra. */
        val minStableDiffs: Int = 3,
        /** Fasce superiore e inferiore da ignorare (barra di stato, barra di navigazione). */
        val ignoreTopFraction: Float = 0f,
        val ignoreBottomFraction: Float = 0f,
        /** Oltre questo rapporto fra i lati si prova a separare una fila di sticker attaccati. */
        val splitAspect: Float = 1.4f,
        /** Colonne/righe con attività sotto questa frazione del massimo sono "vuoti" fra sticker. */
        val gapFraction: Float = 0.12f,
        /** Se l'anello attorno alla regione si muove più di così, è un pezzo di video, non uno sticker. */
        val maxRingActivity: Float = 0.3f,
        /**
         * Distanza temporale fra i fotogrammi confrontati per il movimento. A 20 fps due
         * fotogrammi consecutivi sono quasi uguali: si confrontano fotogrammi a ~250 ms.
         */
        val compareLagMs: Long = 250,
        /** Periodo minimo del loop considerato. */
        val minPeriodMs: Long = 400,
        /** Similarità minima fra fotogrammi a distanza di un periodo perché il loop sia riconosciuto. */
        val minLoopSimilarity: Float = 0.9f,
        /** Pixel sotto questa luminosità sono "neri": un fotogramma quasi tutto nero e' il riavvio del loop. */
        val darkFrameGray: Int = 40,
    )

    /**
     * @param frames fotogrammi in scala di grigi (0..255), tutti [width]x[height]
     * @param timesMs istante di ogni fotogramma
     */
    fun detect(frames: List<IntArray>, width: Int, height: Int, timesMs: List<Long>, params: Params = Params()): List<Proposal> {
        require(frames.size == timesMs.size)
        val pixels = width * height

        // 0) per il movimento si confrontano fotogrammi a ~compareLagMs di distanza: a frequenze
        //    alte quelli consecutivi sono quasi identici e il movimento sfuggirebbe
        val sample = ArrayList<Int>()
        for (i in frames.indices) {
            if (sample.isEmpty() || timesMs[i] - timesMs[sample.last()] >= params.compareLagMs) sample.add(i)
        }
        val n = sample.size
        if (n < params.minStableDiffs + 1) return emptyList()

        // 1) maschere di cambiamento fra fotogrammi campionati e frazione globale
        val changed = Array(n - 1) { BooleanArray(pixels) }
        val globalChange = FloatArray(n - 1)
        for (i in 1 until n) {
            val a = frames[sample[i - 1]]
            val b = frames[sample[i]]
            var count = 0
            val mask = changed[i - 1]
            for (p in 0 until pixels) {
                if (abs(a[p] - b[p]) > params.pixelThreshold) { mask[p] = true; count++ }
            }
            globalChange[i - 1] = count.toFloat() / pixels
        }

        // 2) finestra stabile più lunga (niente scorrimenti), riportata agli indici dei fotogrammi reali
        val window = longestStableWindow(globalChange, params) ?: return emptyList()
        val (firstDiff, lastDiff) = window // indici in changed[], inclusivi
        val numDiffs = lastDiff - firstDiff + 1
        val firstFrame = sample[firstDiff]
        val lastFrame = sample[lastDiff + 1]

        // 3) frequenza di cambiamento per pixel dentro la finestra
        val freq = FloatArray(pixels)
        for (d in firstDiff..lastDiff) {
            val mask = changed[d]
            for (p in 0 until pixels) if (mask[p]) freq[p] += 1f
        }
        val alive = BooleanArray(pixels)
        var aliveCount = 0
        val topLimit = (height * params.ignoreTopFraction).roundToInt()
        val bottomLimit = height - (height * params.ignoreBottomFraction).roundToInt()
        for (p in 0 until pixels) {
            freq[p] /= numDiffs
            val row = p / width
            if (row < topLimit || row >= bottomLimit) continue
            if (freq[p] >= params.minChangeFrequency) { alive[p] = true; aliveCount++ }
        }
        if (aliveCount == 0) return emptyList()

        // 4) dilatazione leggera e componenti connesse
        val dilated = dilate(alive, width, height, radius = 1)
        val boxes = connectedBoxes(dilated, width, height, minArea = (pixels * params.minAreaFraction).roundToInt().coerceAtLeast(4))
        val merged = mergeBoxes(boxes, gap = 2).flatMap { splitByGaps(alive, width, height, it, params) }

        // 5) proposte
        val proposals = ArrayList<Proposal>()
        for (box in merged) {
            val (l, t, r, b) = box
            val w = r - l
            val h = b - t
            if (w < 3 || h < 3) continue
            if (w.toFloat() * h / pixels > params.maxBoxFraction) continue
            if (ringActivity(alive, width, height, l, t, r, b) > params.maxRingActivity) continue
            val activity = regionActivity(freq, width, l, t, r, b)
            val periodFrames = estimatePeriod(frames, timesMs, width, l, t, r, b, firstFrame, lastFrame, params)
            var startFrame = firstFrame
            if (periodFrames > 0) {
                // il loop riparte dopo l'eventuale fotogramma nero: si comincia da lì
                val lastDark = (firstFrame until min(firstFrame + periodFrames, lastFrame)).lastOrNull { i ->
                    darkFraction(frames[i], width, l, t, r, b, params.darkFrameGray) >= 0.8f
                }
                if (lastDark != null && lastDark + 1 + periodFrames <= lastFrame + 1) startFrame = lastDark + 1
            }
            val startMs = timesMs[startFrame]
            val windowEndMs = timesMs[lastFrame]
            var endMs = when {
                periodFrames > 0 && startFrame + periodFrames <= lastFrame -> timesMs[startFrame + periodFrames]
                else -> min(windowEndMs, startMs + params.defaultClipMs)
            }
            endMs = endMs.coerceIn(startMs + params.minClipMs, startMs + params.maxClipMs)
            if (endMs > windowEndMs) endMs = max(windowEndMs, startMs + params.minClipMs)
            val side = max(w, h) * (1f + params.margin)
            val cx = (l + r) / 2f
            val cy = (t + b) / 2f
            val crop = CropSpec.square(cx / width, cy / height, side / min(width, height), width, height)
            proposals.add(Proposal(crop, startMs, endMs, intArrayOf(l, t, w, h), activity))
        }
        // ordine di lettura: dall'alto in basso, da sinistra a destra (a parità di riga)
        return proposals.sortedWith(compareBy({ it.box[1] / (height / 6).coerceAtLeast(1) }, { it.box[0] }))
    }

    /** Coppia (primo, ultimo) indice di confronto della finestra stabile più lunga. */
    internal fun longestStableWindow(globalChange: FloatArray, params: Params): Pair<Int, Int>? {
        var bestStart = -1
        var bestLen = 0
        var start = -1
        for (i in globalChange.indices) {
            if (globalChange[i] <= params.globalChangeMax) {
                if (start < 0) start = i
                val len = i - start + 1
                if (len > bestLen) { bestLen = len; bestStart = start }
            } else {
                start = -1
            }
        }
        return if (bestLen >= params.minStableDiffs) bestStart to (bestStart + bestLen - 1) else null
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

    /** Rettangoli (left, top, right, bottom esclusivi) delle componenti connesse abbastanza grandi. */
    internal fun connectedBoxes(mask: BooleanArray, width: Int, height: Int, minArea: Int): List<IntArray> {
        val visited = BooleanArray(mask.size)
        val stack = IntArray(mask.size) // ogni pixel entra al massimo una volta
        val boxes = ArrayList<IntArray>()
        for (seed in mask.indices) {
            if (!mask[seed] || visited[seed]) continue
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
                if (x > 0 && mask[p - 1] && !visited[p - 1]) { visited[p - 1] = true; stack[top++] = p - 1 }
                if (x < width - 1 && mask[p + 1] && !visited[p + 1]) { visited[p + 1] = true; stack[top++] = p + 1 }
                if (y > 0 && mask[p - width] && !visited[p - width]) { visited[p - width] = true; stack[top++] = p - width }
                if (y < height - 1 && mask[p + width] && !visited[p + width]) { visited[p + width] = true; stack[top++] = p + width }
            }
            if (area >= minArea) boxes.add(intArrayOf(l, t, r + 1, b + 1))
        }
        return boxes
    }

    internal fun mergeBoxes(boxes: List<IntArray>, gap: Int): List<IntArray> {
        val list = boxes.map { it.copyOf() }.toMutableList()
        var merged = true
        while (merged) {
            merged = false
            outer@ for (i in list.indices) for (j in i + 1 until list.size) {
                val a = list[i]; val b = list[j]
                val overlapX = a[0] - gap < b[2] && b[0] - gap < a[2]
                val overlapY = a[1] - gap < b[3] && b[1] - gap < a[3]
                if (overlapX && overlapY) {
                    list[i] = intArrayOf(min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))
                    list.removeAt(j)
                    merged = true
                    break@outer
                }
            }
        }
        return list
    }

    /**
     * Separa una regione allungata (fila o colonna di sticker attaccati) nei punti in cui il
     * profilo di attività per colonna/riga scende quasi a zero.
     */
    internal fun splitByGaps(alive: BooleanArray, width: Int, height: Int, box: IntArray, params: Params): List<IntArray> {
        val (l, t, r, b) = box
        val w = r - l
        val h = b - t
        if (w <= 0 || h <= 0) return listOf(box)
        val horizontal = w.toFloat() / h >= params.splitAspect
        val vertical = h.toFloat() / w >= params.splitAspect
        if (!horizontal && !vertical) return listOf(box)
        val length = if (horizontal) w else h
        val profile = IntArray(length)
        for (y in t until b) for (x in l until r) {
            if (alive[y * width + x]) profile[if (horizontal) x - l else y - t]++
        }
        val max = profile.maxOrNull() ?: 0
        if (max == 0) return listOf(box)
        val threshold = max * params.gapFraction
        val pieces = ArrayList<IntArray>()
        var start = -1
        for (i in 0..length) {
            val active = i < length && profile[i] > threshold
            if (active && start < 0) start = i
            if (!active && start >= 0) {
                pieces.add(if (horizontal) intArrayOf(l + start, t, l + i, b) else intArrayOf(l, t + start, r, t + i))
                start = -1
            }
        }
        if (pieces.size <= 1) return listOf(box)
        // rifinisce ogni pezzo sull'altro asse (una fila di sticker non li ha tutti alla stessa altezza)
        return pieces.map { piece ->
            val (pl, pt, pr, pb) = piece
            var nl = pr; var nt = pb; var nr = pl; var nb = pt
            for (y in pt until pb) for (x in pl until pr) {
                if (!alive[y * width + x]) continue
                if (x < nl) nl = x
                if (x + 1 > nr) nr = x + 1
                if (y < nt) nt = y
                if (y + 1 > nb) nb = y + 1
            }
            if (nr > nl && nb > nt) intArrayOf(nl, nt, nr, nb) else piece
        }
    }

    /** Frazione di pixel "vivi" nell'anello attorno alla regione (spessore 10% del lato maggiore, min 2 px). */
    internal fun ringActivity(alive: BooleanArray, width: Int, height: Int, l: Int, t: Int, r: Int, b: Int): Float {
        val ring = max(2, (max(r - l, b - t) * 0.1f).roundToInt())
        val ol = (l - ring).coerceAtLeast(0)
        val ot = (t - ring).coerceAtLeast(0)
        val or_ = (r + ring).coerceAtMost(width)
        val ob = (b + ring).coerceAtMost(height)
        var count = 0
        var total = 0
        for (y in ot until ob) for (x in ol until or_) {
            if (x in l until r && y in t until b) continue
            total++
            if (alive[y * width + x]) count++
        }
        return if (total == 0) 0f else count.toFloat() / total
    }

    private fun regionActivity(freq: FloatArray, width: Int, l: Int, t: Int, r: Int, b: Int): Float {
        var sum = 0f
        var count = 0
        for (y in t until b) for (x in l until r) { sum += freq[y * width + x]; count++ }
        return if (count == 0) 0f else sum / count
    }

    /** Frazione di pixel della regione piu' scuri di [threshold]. */
    private fun darkFraction(frame: IntArray, width: Int, l: Int, t: Int, r: Int, b: Int, threshold: Int): Float {
        var dark = 0
        var count = 0
        for (y in t until b) for (x in l until r) { if (frame[y * width + x] < threshold) dark++; count++ }
        return if (count == 0) 0f else dark.toFloat() / count
    }

    /**
     * Periodo del loop (in fotogrammi) tramite similarità fra fotogrammi a distanza di un lag:
     * la regione viene ridotta a una griglia di blocchi e per ogni lag si misura la correlazione
     * media fra il vettore dei blocchi al tempo i e quello al tempo i+lag. Un loop esatto dà
     * similarità ~1 al periodo e ai suoi multipli: si sceglie il lag più piccolo fra quelli
     * vicini al massimo. Restituisce 0 se la regione è quasi ferma o nessun lag convince.
     */
    internal fun estimatePeriod(
        frames: List<IntArray>, timesMs: List<Long>, width: Int,
        l: Int, t: Int, r: Int, b: Int, firstFrame: Int, lastFrame: Int, params: Params,
    ): Int {
        val count = lastFrame - firstFrame + 1
        if (count < 6) return 0
        val cells = 6
        val cellsTotal = cells * cells
        val w = r - l
        val h = b - t
        if (w < cells || h < cells) return 0
        // firma per fotogramma: medie dei blocchi, centrate
        val signature = Array(count) { FloatArray(cellsTotal) }
        for (i in 0 until count) {
            val f = frames[firstFrame + i]
            val sig = signature[i]
            for (cy in 0 until cells) for (cx in 0 until cells) {
                val x0 = l + w * cx / cells
                val x1 = l + w * (cx + 1) / cells
                val y0 = t + h * cy / cells
                val y1 = t + h * (cy + 1) / cells
                var sum = 0L
                var n = 0
                for (y in y0 until y1) for (x in x0 until x1) { sum += f[y * width + x]; n++ }
                sig[cy * cells + cx] = if (n == 0) 0f else sum.toFloat() / n
            }
            val mean = sig.average().toFloat()
            for (k in sig.indices) sig[k] -= mean
        }
        // regione quasi ferma: nessun loop da misurare
        var variability = 0.0
        for (k in 0 until cellsTotal) {
            var m = 0.0
            for (i in 0 until count) m += signature[i][k]
            m /= count
            var v = 0.0
            for (i in 0 until count) { val d = signature[i][k] - m; v += d * d }
            variability += sqrt(v / count)
        }
        if (variability / cellsTotal < 2.0) return 0

        val norms = FloatArray(count) { i -> sqrt(signature[i].sumOf { (it * it).toDouble() }).toFloat() + 1e-3f }
        val minLag = (1 until count).firstOrNull { timesMs[firstFrame + it] - timesMs[firstFrame] >= params.minPeriodMs } ?: return 0
        val maxLag = count * 3 / 5
        if (maxLag <= minLag) return 0
        val sims = FloatArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var acc = 0f
            var pairs = 0
            for (i in 0 until count - lag) {
                val a = signature[i]
                val c = signature[i + lag]
                var dot = 0f
                for (k in 0 until cellsTotal) dot += a[k] * c[k]
                acc += dot / (norms[i] * norms[i + lag])
                pairs++
            }
            sims[lag] = if (pairs == 0) 0f else acc / pairs
        }
        var best = 0f
        var lowest = 1f
        for (lag in minLag..maxLag) { if (sims[lag] > best) best = sims[lag]; if (sims[lag] < lowest) lowest = sims[lag] }
        if (best < params.minLoopSimilarity) return 0
        // un movimento lento e continuo ha similarita' alta e piatta a tutti i lag: non e' un loop.
        // Un loop vero mostra picchi netti (al periodo e ai multipli) sopra una base piu' bassa.
        val margin = 0.05f
        if (best - lowest < margin) return 0
        // il primo massimo locale vicino al migliore e' il periodo fondamentale (non un multiplo),
        // purche' entro un periodo ci sia un vero avvallamento
        for (lag in minLag..maxLag) {
            val isPeak = (lag == minLag || sims[lag] >= sims[lag - 1]) && (lag == maxLag || sims[lag] >= sims[lag + 1])
            if (!isPeak || sims[lag] < best - 0.02f) continue
            if (lag == minLag) {
                // al lag minimo la similarita' e' alta anche per un movimento lento che decade:
                // e' un periodo solo se si ripete al doppio del lag
                val harmonic = 2 * lag
                if (harmonic > maxLag || sims[harmonic] < best - margin) continue
            }
            val dipEnd = min(maxLag, 2 * lag)
            var dip = false
            for (k in minLag..dipEnd) if (k != lag && sims[k] < best - margin) { dip = true; break }
            if (dip) return lag
        }
        return 0
    }
}
