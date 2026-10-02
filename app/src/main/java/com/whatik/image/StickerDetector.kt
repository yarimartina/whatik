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
    )

    /**
     * @param frames fotogrammi in scala di grigi (0..255), tutti [width]x[height]
     * @param timesMs istante di ogni fotogramma
     */
    fun detect(frames: List<IntArray>, width: Int, height: Int, timesMs: List<Long>, params: Params = Params()): List<Proposal> {
        require(frames.size == timesMs.size)
        val n = frames.size
        if (n < params.minStableDiffs + 1) return emptyList()
        val pixels = width * height

        // 1) maschere di cambiamento fra fotogrammi consecutivi e frazione globale
        val changed = Array(n - 1) { BooleanArray(pixels) }
        val magnitude = Array(n - 1) { IntArray(0) }
        val globalChange = FloatArray(n - 1)
        for (i in 1 until n) {
            val a = frames[i - 1]
            val b = frames[i]
            var count = 0
            val mask = changed[i - 1]
            for (p in 0 until pixels) {
                if (abs(a[p] - b[p]) > params.pixelThreshold) { mask[p] = true; count++ }
            }
            globalChange[i - 1] = count.toFloat() / pixels
        }

        // 2) finestra stabile più lunga (niente scorrimenti)
        val window = longestStableWindow(globalChange, params) ?: return emptyList()
        val (firstDiff, lastDiff) = window // indici in changed[], inclusivi
        val numDiffs = lastDiff - firstDiff + 1
        val firstFrame = firstDiff
        val lastFrame = lastDiff + 1

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
            val periodFrames = estimatePeriod(frames, width, l, t, r, b, firstFrame, lastFrame)
            val startMs = timesMs[firstFrame]
            val windowEndMs = timesMs[lastFrame]
            var endMs = when {
                periodFrames > 0 && firstFrame + periodFrames <= lastFrame -> timesMs[firstFrame + periodFrames]
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

    /**
     * Periodo del loop (in fotogrammi) stimato con l'autocorrelazione della firma della regione
     * (media di grigio per fotogramma): il primo picco locale con correlazione >= 0.75.
     * Restituisce 0 se non c'è un periodo chiaro.
     */
    internal fun estimatePeriod(
        frames: List<IntArray>, width: Int, l: Int, t: Int, r: Int, b: Int, firstFrame: Int, lastFrame: Int,
    ): Int {
        val count = lastFrame - firstFrame + 1
        if (count < 6) return 0
        val signal = FloatArray(count)
        val area = ((r - l) * (b - t)).coerceAtLeast(1)
        for (i in 0 until count) {
            val f = frames[firstFrame + i]
            var sum = 0L
            for (y in t until b) for (x in l until r) sum += f[y * width + x]
            signal[i] = sum.toFloat() / area
        }
        val mean = signal.average().toFloat()
        for (i in signal.indices) signal[i] -= mean
        if (signal.sumOf { (it * it).toDouble() } < 1e-3) return 0
        val maxLag = count / 2
        if (maxLag < 2) return 0
        val corr = FloatArray(maxLag + 1)
        for (lag in 2..maxLag) {
            var num = 0f
            var denA = 0f
            var denB = 0f
            for (i in 0 until count - lag) {
                num += signal[i] * signal[i + lag]
                denA += signal[i] * signal[i]
                denB += signal[i + lag] * signal[i + lag]
            }
            corr[lag] = if (denA <= 0f || denB <= 0f) 0f else num / sqrt(denA * denB)
        }
        for (lag in 2..maxLag) {
            val isPeak = corr[lag] >= 0.75f &&
                (lag == 2 || corr[lag] >= corr[lag - 1]) &&
                (lag == maxLag || corr[lag] >= corr[lag + 1])
            if (isPeak) return lag
        }
        return 0
    }
}
