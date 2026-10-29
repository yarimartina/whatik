package com.whatik.image

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerLocatorTest {

    private val w = 480
    private val h = 1040
    private val white = 0xFFFFFFFF.toInt()
    private val dark = 0xFF202020.toInt()

    /** Schermo tipo TikTok: video in alto, pannello bianco con due file di tessere. */
    private fun panel(): IntArray {
        val img = IntArray(w * h) { p -> if (p / w < 400) dark else white }
        val colors = intArrayOf(0xFFE05080.toInt(), 0xFF4080FF.toInt(), 0xFF40C040.toInt(), 0xFF808080.toInt())
        for (row in 0 until 2) for (col in 0 until 4) {
            fill(img, 18 + col * 118, 740 + row * 148, 92, 92, colors[(row + col) % 4])
        }
        return img
    }

    private fun fill(img: IntArray, l: Int, t: Int, bw: Int, bh: Int, color: Int) {
        for (y in t until t + bh) for (x in l until l + bw) img[y * w + x] = color
    }

    @Test
    fun tapInsideATileOutlinesThatTile() {
        val found = StickerLocator.locate(panel(), w, h, 136 + 40, 888 + 30)
        assertNotNull(found)
        val (l, t, bw, bh) = found!!.box
        assertTrue("left $l", l in 132..140)
        assertTrue("top $t", t in 884..892)
        assertTrue("size $bw x $bh", bw in 86..100 && bh in 86..100)
        assertTrue(found.fromGrid)
        // colore del pannello misurato con precisione (serve a togliere le bande senza toccare lo sticker)
        assertEquals(0xFFFFFF, found.background)
    }

    @Test
    fun tapInTheGapPicksTheNearestTile() {
        // fra la prima e la seconda tessera della prima fila, più vicino alla seconda
        val found = StickerLocator.locate(panel(), w, h, 130, 786)
        assertNotNull(found)
        assertTrue("left ${found!!.box[0]}", found.box[0] in 132..140)
    }

    @Test
    fun isolatedStickerOnUniformBackgroundIsOutlined() {
        // un solo sticker grande su sfondo scuro: niente griglia, si usano i bordi dello sticker fermo
        val img = IntArray(w * h) { dark }
        fill(img, 120, 300, 230, 160, 0xFFFFC020.toInt())
        val found = StickerLocator.locate(img, w, h, 200, 380)
        assertNotNull(found)
        val (l, t, bw, bh) = found!!.box
        assertTrue("left $l", l in 116..124)
        assertTrue("top $t", t in 296..304)
        assertTrue("size $bw x $bh", bw in 224..236 && bh in 154..166)
        assertEquals(dark and 0xFFFFFF, found.background)
    }

    @Test
    fun tapOnEmptyBackgroundFindsNothing() {
        val img = IntArray(w * h) { white }
        assertNull(StickerLocator.locate(img, w, h, 240, 500))
    }

    @Test
    fun tapOnTheVideoAboveThePanelFindsNoTile() {
        val img = panel()
        // il "video" in alto: una foto che riempie tutta la parte scura
        for (y in 20 until 390) for (x in 10 until 470) img[y * w + x] = 0xFF000000.toInt() or ((x * 37 + y * 11) and 0xFFFFFF)
        val found = StickerLocator.locate(img, w, h, 240, 200)
        assertFalse(found?.fromGrid ?: false)
    }

    // ---------------------------------------------------------------- istogramma su array (parità con la HashMap)

    @Test
    fun ringColorMatchesTheHashMapVersion() {
        // Riquadri casuali, anche contro il bordo dell'immagine (l'anello viene tagliato); a pari conteggio
        // vince la chiave quantizzata più bassa (prima: il primo nell'ordine di iterazione della HashMap).
        var cases = 0; var tied = 0; var changed = 0
        val rnd = Random(21)
        for (seed in 1..8) for (img in ParityImages.suite(seed)) repeat(4) {
            val bw = rnd.nextInt(1, img.w + 1); val bh = rnd.nextInt(1, img.h + 1)
            val l = rnd.nextInt(-2, img.w - bw + 3).coerceIn(0, img.w - bw)
            val t = rnd.nextInt(-2, img.h - bh + 3).coerceIn(0, img.h - bh)
            val old = LegacyHistograms.ringColor(img.rgb, img.w, img.h, l, t, bw, bh)
            val now = StickerLocator.ringColor(img.rgb, img.w, img.h, l, t, bw, bh)
            val label = "${img.name} seed $seed riquadro $l,$t,$bw,$bh"
            assertEquals(label, old?.lowestKey, now)
            if (old == null) return@repeat
            cases++
            if (old.tied == 1) assertEquals(label, old.old, now) else { tied++; if (old.old != old.lowestKey) changed++ }
        }
        println("StickerLocator.ringColor: $cases casi, $tied con parità esatta, $changed in cui la regola cambia il colore")
        assertTrue("servono casi con e senza parità", tied > 0 && tied < cases)
    }

    @Test
    fun ringColorIsNullWhenTheBoxCoversTheWholeImage() {
        val img = IntArray(30 * 20) { 0xFFFFFFFF.toInt() }
        assertNull(StickerLocator.ringColor(img, 30, 20, 0, 0, 30, 20))
        assertNull(LegacyHistograms.ringColor(img, 30, 20, 0, 0, 30, 20))
    }

    @Test
    fun tieBetweenTwoRingColorsGoesToTheLowestQuantizedColor() {
        val low = 0xFF102030.toInt()
        val high = 0xFFE0D0C0.toInt()
        for (swap in listOf(false, true)) {
            // l'anello (x 11..28, y 11..28 senza il riquadro) è simmetrico rispetto a x = 20: stesso numero di pixel per colore
            val img = IntArray(40 * 40) { p -> if ((p % 40 < 20) != swap) low else high }
            assertEquals(0x102030, StickerLocator.ringColor(img, 40, 40, 14, 14, 12, 12))
        }
    }
}
