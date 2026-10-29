package com.whatik.image

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileGridFinderTest {

    private val w = 480
    private val h = 1040
    private val white = 0xFFFFFFFF.toInt()
    private val dark = 0xFF202020.toInt()

    /** Schermo tipo TikTok: video scuro in alto, pannello bianco sotto. */
    private fun screen(paint: (IntArray) -> Unit): IntArray = IntArray(w * h) { p -> if (p / w < 400) dark else white }.also(paint)

    private fun fill(img: IntArray, l: Int, t: Int, r: Int, b: Int, color: Int) {
        for (y in t.coerceAtLeast(0) until b.coerceAtMost(h)) for (x in l.coerceAtLeast(0) until r.coerceAtMost(w)) img[y * w + x] = color
    }

    /** Tessera piena di colore. */
    private fun tile(img: IntArray, l: Int, t: Int, color: Int = 0xFFE05080.toInt()) = fill(img, l, t, l + 92, t + 92, color)

    /** Tessera chiara: sfondo bianco con una linea sottile, come uno sticker in bianco e nero. */
    private fun lightTile(img: IntArray, l: Int, t: Int) {
        fill(img, l + 10, t + 40, l + 82, t + 52, dark) // tratto orizzontale
        fill(img, l + 40, t + 10, l + 52, t + 82, dark) // tratto verticale
    }

    private fun assertTileAt(tiles: List<IntArray>, index: Int, l: Int, t: Int) {
        val tile = tiles[index]
        assertTrue("tile $index left ${tile[0]}", tile[0] in (l - 4)..(l + 4))
        assertTrue("tile $index top ${tile[1]}", tile[1] in (t - 4)..(t + 4))
        assertTrue("tile $index width ${tile[2]}", tile[2] in 86..100)
        assertTrue("tile $index height ${tile[3]}", tile[3] in 86..100)
    }

    @Test
    fun findsFullRowOfTilesInReadingOrder() {
        val img = screen {
            tile(it, 18, 740); tile(it, 136, 740, 0xFF4080FF.toInt()); tile(it, 254, 740, 0xFF40C040.toInt()); tile(it, 372, 740, 0xFF808080.toInt())
            tile(it, 136, 888); tile(it, 254, 888)
        }
        val tiles = TileGridFinder.find(img, w, h)
        assertEquals(6, tiles.size)
        assertTileAt(tiles, 0, 18, 740)
        assertTileAt(tiles, 1, 136, 740)
        assertTileAt(tiles, 2, 254, 740)
        assertTileAt(tiles, 3, 372, 740)
        assertTileAt(tiles, 4, 136, 888)
        assertTileAt(tiles, 5, 254, 888)
    }

    @Test
    fun ignoresVideoIconsTextAndCutTiles() {
        val img = screen {
            tile(it, 18, 740); tile(it, 136, 740); tile(it, 254, 740)
            fill(it, 20, 60, 460, 380, 0xFF3060A0.toInt())          // contenuto del video in alto
            fill(it, 20, 700, 140, 712, dark)                       // titolo "Salvati"
            fill(it, 440, 700, 464, 724, dark)                      // icona
            fill(it, 18, 980, 110, 1040, 0xFFE05080.toInt())        // tessera tagliata dal bordo inferiore
            fill(it, 372, 740, 464, 832, 0xFFF4F4F4.toInt())        // tessera "+" quasi vuota
            fill(it, 414, 782, 422, 790, dark)
        }
        val tiles = TileGridFinder.find(img, w, h)
        assertEquals(3, tiles.size)
        assertTileAt(tiles, 0, 18, 740)
        assertTileAt(tiles, 1, 136, 740)
        assertTileAt(tiles, 2, 254, 740)
    }

    @Test
    fun recoversLightTilesFromTheGridPitch() {
        val img = screen {
            tile(it, 18, 740); lightTile(it, 136, 740); tile(it, 254, 740); tile(it, 372, 740)
            lightTile(it, 18, 888); tile(it, 136, 888)
        }
        val tiles = TileGridFinder.find(img, w, h)
        assertEquals(6, tiles.size)
        assertTileAt(tiles, 1, 136, 740)
        assertTileAt(tiles, 4, 18, 888)
    }

    @Test
    fun dropsTilesCutByTheNavigationBar() {
        val img = screen {
            tile(it, 18, 740); tile(it, 136, 740); tile(it, 254, 740); tile(it, 372, 740)
            // ultima fila visibile solo per 75 px, poi la barra di navigazione bianca
            fill(it, 18, 888, 110, 963, 0xFFE05080.toInt()); fill(it, 254, 888, 346, 963, 0xFF4080FF.toInt())
            fill(it, 200, 975, 280, 981, 0xFF808080.toInt()) // maniglia dei gesti
        }
        val tiles = TileGridFinder.find(img, w, h)
        assertEquals(4, tiles.size)
        assertTrue(tiles.all { it[1] in 736..744 })
    }

    @Test
    fun emptyTilePositionsAreNotInvented() {
        val img = screen { tile(it, 18, 740); tile(it, 254, 740) } // posizione 136 vuota
        val tiles = TileGridFinder.find(img, w, h)
        assertEquals(2, tiles.size)
    }

    @Test
    fun noTilesWithoutAPanel() {
        val img = IntArray(w * h) { dark }
        assertTrue(TileGridFinder.find(img, w, h).isEmpty())
    }

    // ---------------------------------------------------------------- istogramma su array (parità con la HashMap)

    @Test
    fun panelBackgroundMatchesTheHashMapVersion() {
        // Il colore del pannello deve essere quello di prima ovunque il gruppo più frequente è uno solo; a pari
        // conteggio vince la chiave quantizzata più bassa (prima: il primo nell'ordine di iterazione della HashMap).
        var cases = 0; var tied = 0; var changed = 0
        for (seed in 1..8) for (img in ParityImages.suite(seed)) for (fraction in floatArrayOf(0.6f, 0.25f, 1f)) {
            val old = LegacyHistograms.panelBackground(img.rgb, img.w, img.h, fraction)
            val now = TileGridFinder.panelBackground(img.rgb, img.w, img.h, fraction)
            val label = "${img.name} seed $seed fraction $fraction"
            assertEquals(label, old?.lowestKey, now)
            if (old == null) continue
            cases++
            if (old.tied == 1) assertEquals(label, old.old, now) else { tied++; if (old.old != old.lowestKey) changed++ }
        }
        println("TileGridFinder.panelBackground: $cases casi, $tied con parità esatta, $changed in cui la regola cambia il colore")
        assertTrue("servono casi con e senza parità", tied > 0 && tied < cases)
    }

    @Test
    fun analyzeKeepsThePanelColorOfTheHashMapVersionOnTikTokLikeScreens() {
        for (seed in 1..6) {
            val img = ParityImages.suite(seed).first { it.name == "tiktokLike 160x213" }
            val old = LegacyHistograms.panelBackground(img.rgb, img.w, img.h, 0.6f)!!
            assertEquals(1, old.tied)
            assertEquals(old.old, TileGridFinder.analyze(img.rgb, img.w, img.h).background)
        }
    }

    @Test
    fun emptyPanelFractionOrWidthGivesNoBackground() {
        assertEquals(null, TileGridFinder.panelBackground(IntArray(0), 0, 5, 0.6f))
        val r = TileGridFinder.analyze(IntArray(0), 0, 5)
        assertTrue(r.tiles.isEmpty())
        assertEquals(0xFFFFFF, r.background)
    }

    @Test
    fun tieBetweenTwoPanelColorsGoesToTheLowestQuantizedColor() {
        val low = 0xFF102030.toInt()
        val high = 0xFFE0D0C0.toInt()
        for (swap in listOf(false, true)) {
            // metà sinistra e metà destra della parte bassa hanno colori diversi e lo stesso numero di pixel
            val img = IntArray(40 * 50) { p -> if ((p % 40 < 20) != swap) low else high }
            assertEquals(0x102030, TileGridFinder.panelBackground(img, 40, 50, 0.6f))
        }
        val rnd = Random(3)
        val noisy = IntArray(40 * 50) { rnd.nextInt() or (0xFF shl 24) } // quasi tutti i gruppi a pari conteggio
        assertEquals(TileGridFinder.panelBackground(noisy, 40, 50, 0.6f), TileGridFinder.panelBackground(noisy.copyOf(), 40, 50, 0.6f))
    }
}
