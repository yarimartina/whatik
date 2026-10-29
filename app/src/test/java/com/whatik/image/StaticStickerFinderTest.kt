package com.whatik.image

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticStickerFinderTest {

    private val w = 400
    private val h = 300
    private val bg = 0xFF1C1C1C.toInt()

    private fun image(paint: (IntArray) -> Unit): IntArray = IntArray(w * h) { bg }.also(paint)

    private fun fill(img: IntArray, l: Int, t: Int, r: Int, b: Int, color: Int) {
        for (y in t until b) for (x in l until r) img[y * w + x] = color
    }

    @Test
    fun findsStickerBoundsAroundTappedPoint() {
        val img = image { fill(it, 120, 80, 220, 200, 0xFFE05080.toInt()) }
        val result = StaticStickerFinder.find(img, w, h, 170, 140)
        assertTrue(result.found)
        val (l, t, bw, bh) = result.box
        assertTrue("left $l", l in 117..123)
        assertTrue("top $t", t in 77..83)
        assertTrue("width $bw", bw in 95..106)
        assertTrue("height $bh", bh in 115..126)
    }

    @Test
    fun snapsToNearbyStickerWhenTappingJustOutside() {
        val img = image { fill(it, 120, 80, 220, 200, 0xFF40C0FF.toInt()) }
        val result = StaticStickerFinder.find(img, w, h, 112, 140) // 8 px a sinistra dello sticker
        assertTrue(result.found)
        assertTrue(result.box[0] in 117..123)
    }

    @Test
    fun separatesNeighbouringStickers() {
        val img = image {
            fill(it, 40, 100, 140, 200, 0xFFFF9900.toInt())
            fill(it, 170, 100, 270, 200, 0xFF00AA55.toInt())
        }
        val result = StaticStickerFinder.find(img, w, h, 220, 150)
        assertTrue(result.found)
        val (l, _, bw, _) = result.box
        assertTrue("left $l", l in 167..173)
        assertTrue("width $bw", bw in 95..106)
    }

    @Test
    fun fallsBackToSquareOnEmptyBackground() {
        val img = image { }
        val result = StaticStickerFinder.find(img, w, h, 200, 150)
        assertFalse(result.found)
        val (l, t, bw, bh) = result.box
        assertEquals(bw, bh)
        assertEquals(120, bw) // 30% della larghezza
        assertEquals(140, l)
        assertEquals(90, t)
    }

    @Test
    fun toCropMakesASquareWithMargin() {
        val crop = StaticStickerFinder.toCrop(intArrayOf(100, 50, 60, 100), 400, 300, margin = 0.1f)
        assertEquals(130f / 400f, crop.cx, 1e-4f)
        assertEquals(100f / 300f, crop.cy, 1e-4f)
        assertEquals(110f, crop.width * 400f, 1e-3f)
        assertEquals(110f, crop.height * 300f, 1e-3f)
    }

    @Test
    fun effectiveCropIsStable() {
        val spec = CropSpec.square(0.02f, 0.5f, 0.5f, 1000, 2000).effective(1000, 2000)
        val again = spec.effective(1000, 2000)
        assertEquals(spec, again)
        assertEquals(0.25f, spec.cx, 1e-5f) // centro spostato dentro: lato 500 -> centro a 250
    }

    // ---------------------------------------------------------------- istogramma su array (parità con la HashMap)

    @Test
    fun ringModeMatchesTheHashMapVersion() {
        // Stessa finestra e stesso spessore che calcola find(); a pari conteggio vince la chiave più bassa
        // (prima: il primo nell'ordine di iterazione della HashMap).
        var cases = 0; var tied = 0; var changed = 0
        val rnd = Random(11)
        for (seed in 1..8) for (img in ParityImages.suite(seed)) repeat(3) {
            val px = rnd.nextInt(img.w); val py = rnd.nextInt(img.h)
            val half = (img.w * 0.35f).let { Math.round(it) }.coerceAtLeast(8)
            val wl = (px - half).coerceAtLeast(0); val wt = (py - half).coerceAtLeast(0)
            val wr = (px + half).coerceAtMost(img.w - 1); val wb = (py + half).coerceAtMost(img.h - 1)
            val ww = wr - wl + 1; val wh = wb - wt + 1
            if (ww < 8 || wh < 8) return@repeat
            val ring = maxOf(2, Math.round(minOf(ww, wh) * 0.15f))
            val old = LegacyHistograms.ringMode(img.rgb, img.w, wl, wt, wr, wb, ring)!!
            val now = StaticStickerFinder.ringMode(img.rgb, img.w, wl, wt, wr, wb, ring)
            val label = "${img.name} seed $seed finestra $wl,$wt..$wr,$wb ring $ring"
            assertEquals(label, old.lowestKey, now)
            cases++
            if (old.tied == 1) assertEquals(label, old.old, now) else { tied++; if (old.old != old.lowestKey) changed++ }
        }
        println("StaticStickerFinder.ringMode: $cases casi, $tied con parità esatta, $changed in cui la regola cambia il colore")
        assertTrue("servono casi con e senza parità", tied > 0 && tied < cases)
    }

    @Test
    fun ringModeCountsEachRingPixelOnceEvenWhenTheColumnsOverlap() {
        // anello più spesso di mezza finestra: le colonne di sinistra e di destra si sovrappongono
        val rnd = Random(5)
        val img = ParityImages.fewFlatColors(60, 60, rnd)
        for ((ww, wh, ring) in listOf(Triple(15, 40, 10), Triple(8, 30, 6), Triple(20, 20, 2), Triple(9, 9, 5), Triple(30, 8, 4))) {
            val old = LegacyHistograms.ringMode(img, 60, 5, 7, 5 + ww - 1, 7 + wh - 1, ring)!!
            assertEquals("$ww x $wh ring $ring", old.lowestKey, StaticStickerFinder.ringMode(img, 60, 5, 7, 5 + ww - 1, 7 + wh - 1, ring))
        }
    }

    @Test
    fun tieBetweenTwoRingColorsGoesToTheLowestQuantizedColor() {
        // quadro 40x40: metà sinistra e metà destra hanno colori diversi, quindi gli stessi pixel sull'anello
        val low = 0xFF102030.toInt()
        val high = 0xFFE0D0C0.toInt()
        for (swap in listOf(false, true)) {
            val img = IntArray(40 * 40) { p -> if ((p % 40 < 20) != swap) low else high }
            val q = StaticStickerFinder.ringMode(img, 40, 0, 0, 39, 39, 6)
            assertEquals((0x10 shr 3 shl 10) or (0x20 shr 3 shl 5) or (0x30 shr 3), q)
        }
    }

    @Test
    fun findStillLocatesTheStickerWithAnyTapOnRandomImages() {
        val rnd = Random(2)
        for (img in ParityImages.suite(4)) {
            if (img.w < 16 || img.h < 16) continue
            val r = StaticStickerFinder.find(img.rgb, img.w, img.h, rnd.nextInt(img.w), rnd.nextInt(img.h))
            assertEquals(4, r.box.size)
            assertTrue(r.box[2] > 0 && r.box[3] > 0)
        }
    }
}
