package com.whatik.image

import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Copie esatte dei vecchi istogrammi con `HashMap<Int, ...>` e `maxByOrNull` di [TileGridFinder],
 * [StaticStickerFinder], [StickerLocator] e [BackgroundRemover], per confrontarli con quelli su array.
 * Ogni funzione restituisce anche il risultato della regola nuova (a parità di conteggio vince la chiave
 * più bassa) e quanti gruppi sono a pari conteggio massimo: senza parità i due risultati coincidono.
 */
internal object LegacyHistograms {

    /**
     * @param old risultato del vecchio codice (a parità: il primo gruppo nell'ordine di iterazione della HashMap)
     * @param lowestKey risultato con la regola nuova (a parità: il gruppo con la chiave quantizzata più bassa)
     * @param tied quanti gruppi hanno il conteggio massimo (1 = nessuna parità)
     */
    class Pick<T>(val old: T, val lowestKey: T, val tied: Int)

    private fun quantize(c: Int): Int = ((c shr 19) and 0x1F shl 10) or ((c shr 11) and 0x1F shl 5) or ((c shr 3) and 0x1F)

    /** TileGridFinder.analyze, passo 1. */
    fun panelBackground(rgb: IntArray, width: Int, height: Int, panelFraction: Float): Pick<Int>? {
        val counts = HashMap<Int, LongArray>()
        val fromY = (height * (1f - panelFraction)).roundToInt().coerceIn(0, height - 1)
        for (y in fromY until height) for (x in 0 until width) {
            val c = rgb[y * width + x]
            val acc = counts.getOrPut(quantize(c)) { LongArray(4) }
            acc[0]++; acc[1] += ((c shr 16) and 0xFF).toLong(); acc[2] += ((c shr 8) and 0xFF).toLong(); acc[3] += (c and 0xFF).toLong()
        }
        val mode = counts.values.maxByOrNull { it[0] } ?: return null
        fun color(m: LongArray) = (((m[1] / m[0]).toInt()) shl 16) or (((m[2] / m[0]).toInt()) shl 8) or ((m[3] / m[0]).toInt())
        val tied = counts.filter { it.value[0] == mode[0] }
        return Pick(color(mode), color(tied.minByOrNull { it.key }!!.value), tied.size)
    }

    /** StaticStickerFinder.find, passo 1: chiave quantizzata più frequente sull'anello (null se vuoto). */
    fun ringMode(rgb: IntArray, width: Int, wl: Int, wt: Int, wr: Int, wb: Int, ring: Int): Pick<Int>? {
        val counts = HashMap<Int, Int>()
        for (y in wt..wb) for (x in wl..wr) {
            val onRing = x < wl + ring || x > wr - ring || y < wt + ring || y > wb - ring
            if (!onRing) continue
            val q = quantize(rgb[y * width + x])
            counts[q] = (counts[q] ?: 0) + 1
        }
        val best = counts.maxByOrNull { it.value } ?: return null
        val tied = counts.filter { it.value == best.value }
        return Pick(best.key, tied.keys.min(), tied.size)
    }

    /** StickerLocator.ringColor. */
    fun ringColor(rgb: IntArray, width: Int, height: Int, l: Int, t: Int, w: Int, h: Int): Pick<Int>? {
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
        fun color(b: IntArray) = ((b[1] / b[0]) shl 16) or ((b[2] / b[0]) shl 8) or (b[3] / b[0])
        val tied = counts.filter { it.value[0] == best[0] }
        return Pick(color(best), color(tied.minByOrNull { it.key }!!.value), tied.size)
    }

    /** BackgroundRemover.edgeBackground: i due risultati sono null se il gruppo non copre minEdgeFraction del bordo. */
    fun edgeBackground(argb: IntArray, width: Int, height: Int, minEdgeFraction: Float): Pick<Int?>? {
        val bins = HashMap<Int, LongArray>()
        var total = 0
        fun visit(p: Int) {
            val c = argb[p]
            val bin = ((c shr 20) and 0xF shl 8) or ((c shr 12) and 0xF shl 4) or ((c shr 4) and 0xF)
            val acc = bins.getOrPut(bin) { LongArray(4) }
            acc[0]++; acc[1] += ((c shr 16) and 0xFF).toLong(); acc[2] += ((c shr 8) and 0xFF).toLong(); acc[3] += (c and 0xFF).toLong()
            total++
        }
        if (width > 0 && height > 0) {
            for (x in 0 until width) { visit(x); if (height > 1) visit((height - 1) * width + x) }
            for (y in 1 until height - 1) { visit(y * width); if (width > 1) visit(y * width + width - 1) }
        }
        val best = bins.values.maxByOrNull { it[0] } ?: return null
        fun color(b: LongArray): Int? {
            if (b[0].toFloat() / total < minEdgeFraction) return null
            return ((b[1] / b[0]).toInt() shl 16) or ((b[2] / b[0]).toInt() shl 8) or (b[3] / b[0]).toInt()
        }
        val tied = bins.filter { it.value[0] == best[0] }
        return Pick(color(best), color(tied.minByOrNull { it.key }!!.value), tied.size)
    }
}

/** Immagini sintetiche per i test di parità: piatte con rumore, "foto", schermo tipo TikTok, rumore puro. */
internal object ParityImages {

    class Img(val name: String, val w: Int, val h: Int, val rgb: IntArray)

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Rumore tipo JPEG ±[amp] per canale. */
    private fun jitter(c: Int, rnd: Random, amp: Int): Int {
        if (amp == 0) return c
        return argb(((c shr 16) and 0xFF) + rnd.nextInt(-amp, amp + 1), ((c shr 8) and 0xFF) + rnd.nextInt(-amp, amp + 1), (c and 0xFF) + rnd.nextInt(-amp, amp + 1))
    }

    private fun randomColor(rnd: Random) = argb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))

    /** Un colore di fondo con rumore leggero e qualche rettangolo di altri colori (tessere, scritte). */
    fun noisyFlat(w: Int, h: Int, rnd: Random): IntArray {
        val base = randomColor(rnd)
        val amp = rnd.nextInt(0, 4)
        val img = IntArray(w * h) { jitter(base, rnd, amp) }
        repeat(rnd.nextInt(2, 7)) {
            val color = randomColor(rnd)
            val l = rnd.nextInt(w); val t = rnd.nextInt(h)
            val r = minOf(w, l + rnd.nextInt(1, w / 2 + 2)); val b = minOf(h, t + rnd.nextInt(1, h / 2 + 2))
            for (y in t until b) for (x in l until r) img[y * w + x] = jitter(color, rnd, amp)
        }
        return img
    }

    /** "Foto": gradienti e macchie con rumore forte, molti colori distinti. */
    fun photoLike(w: Int, h: Int, rnd: Random): IntArray {
        val seed = rnd.nextInt(50)
        return IntArray(w * h) { p ->
            val x = p % w; val y = p / w
            argb(
                120 + (60 * sin((x + seed * 13) * 0.07)).toInt() + rnd.nextInt(-14, 15),
                90 + (70 * sin((y + seed * 7) * 0.05)).toInt() + rnd.nextInt(-14, 15),
                100 + (50 * sin((x + y + seed) * 0.04)).toInt() + rnd.nextInt(-14, 15),
            )
        }
    }

    /** Schermo tipo TikTok: "video" in alto, pannello quasi bianco sotto con una griglia di tessere (foto, cartoni, meme). */
    fun tiktokLike(w: Int, h: Int, rnd: Random): IntArray {
        val panel = argb(0xF8, 0xF8, 0xF8)
        val img = IntArray(w * h)
        val videoBottom = (h * 0.45f).toInt()
        val photo = photoLike(w, videoBottom.coerceAtLeast(1), rnd)
        for (y in 0 until h) for (x in 0 until w) {
            img[y * w + x] = if (y < videoBottom) photo[y * w + x] else jitter(panel, rnd, 1)
        }
        val side = (w * 0.19f).toInt().coerceAtLeast(2)
        val gap = ((w - 4 * side) / 5).coerceAtLeast(1)
        var top = videoBottom + gap * 2
        var kind = rnd.nextInt(5)
        while (top + side < h - 4) {
            for (col in 0 until 4) {
                val l = gap + col * (side + gap)
                if (l + side > w) continue
                for (yy in 0 until side) for (xx in 0 until side) {
                    img[(top + yy) * w + l + xx] = when (kind % 3) {
                        0 -> argb(120 + rnd.nextInt(-40, 40), 90 + rnd.nextInt(-40, 40), 100 + rnd.nextInt(-40, 40))
                        1 -> if ((xx - side / 2) * (xx - side / 2) + (yy - side / 2) * (yy - side / 2) < side * side / 8) jitter(argb(230, 80, 60), rnd, 3) else jitter(argb(255, 255, 255), rnd, 1)
                        else -> if ((yy / 6) % 4 == 0) jitter(argb(30, 30, 30), rnd, 4) else jitter(argb(0xF1, 0xF6, 0xF9), rnd, 1)
                    }
                }
                kind++
            }
            top += side + gap
        }
        return img
    }

    /** Colori tutti casuali: quasi ogni gruppo ha lo stesso conteggio, molte parità. */
    fun uniformRandom(w: Int, h: Int, rnd: Random): IntArray = IntArray(w * h) { randomColor(rnd) }

    /** Poche tinte piatte senza rumore: parità frequenti anche con tanti pixel. */
    fun fewFlatColors(w: Int, h: Int, rnd: Random): IntArray {
        val palette = IntArray(rnd.nextInt(2, 5)) { randomColor(rnd) }
        return IntArray(w * h) { palette[rnd.nextInt(palette.size)] }
    }

    /** Insieme di immagini di forme e contenuti diversi, deterministico per [seed]. */
    fun suite(seed: Int): List<Img> {
        val rnd = Random(seed)
        val out = ArrayList<Img>()
        val shapes = listOf(9 to 9, 16 to 12, 48 to 40, 97 to 131, 160 to 213, 200 to 200)
        for ((w, h) in shapes) {
            out.add(Img("noisyFlat ${w}x$h", w, h, noisyFlat(w, h, rnd)))
            out.add(Img("photoLike ${w}x$h", w, h, photoLike(w, h, rnd)))
            out.add(Img("tiktokLike ${w}x$h", w, h, tiktokLike(w, h, rnd)))
            out.add(Img("uniformRandom ${w}x$h", w, h, uniformRandom(w, h, rnd)))
            out.add(Img("fewFlatColors ${w}x$h", w, h, fewFlatColors(w, h, rnd)))
        }
        return out
    }
}
