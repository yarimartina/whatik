package com.whatik.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerRefinerTest {

    // analisi 160x120, fotogramma a colori 480x360 (scala 3)
    private val aw = 160
    private val ah = 120
    private val fw = 480
    private val fh = 360
    private val bg = 0xFF202020.toInt()

    private fun frame(paint: (IntArray) -> Unit): IntArray = IntArray(fw * fh) { bg }.also(paint)

    private fun fill(img: IntArray, l: Int, t: Int, r: Int, b: Int, color: Int) {
        for (y in t until b) for (x in l until r) img[y * fw + x] = color
    }

    @Test
    fun expandsMotionBoxToTheWholeStillTile() {
        // sticker: riquadro 150..270 x 90..210 nel fotogramma; si muove solo la "faccia" al centro
        val rgb = frame { fill(it, 150, 90, 270, 210, 0xFFE0A040.toInt()) }
        val motionBox = intArrayOf(60, 40, 20, 20) // in analisi: 180..240 x 120..180 nel fotogramma
        val crop = StickerRefiner.refine(motionBox, aw, ah, rgb, fw, fh)
        assertNotNull(crop)
        val (l, t, side) = crop!!.toPixels(fw, fh)
        assertEquals(210f / fw, crop.cx, 0.02f)
        assertEquals(150f / fh, crop.cy, 0.02f)
        assertTrue("side $side", side in 121..133)
        assertTrue(l <= 150 && l + side >= 270 && t <= 90 && t + side >= 210)
    }

    @Test
    fun rejectsRegionsTouchingTheFrameBorder() {
        val rgb = frame { }
        assertNull(StickerRefiner.refine(intArrayOf(0, 40, 30, 30), aw, ah, rgb, fw, fh))
        assertNull(StickerRefiner.refine(intArrayOf(130, 40, 30, 30), aw, ah, rgb, fw, fh))
    }

    @Test
    fun rejectsStripsAndHugeAreas() {
        val rgb = frame { }
        assertNull(StickerRefiner.refine(intArrayOf(20, 50, 120, 20), aw, ah, rgb, fw, fh)) // striscia 6:1
        assertNull(StickerRefiner.refine(intArrayOf(10, 10, 140, 100), aw, ah, rgb, fw, fh)) // quasi tutto lo schermo
    }

    @Test
    fun keepsMotionBoxWhenNoStillStickerIsFound() {
        val rgb = frame { }
        val crop = StickerRefiner.refine(intArrayOf(60, 40, 20, 20), aw, ah, rgb, fw, fh)
        assertNotNull(crop)
        assertEquals(210f / fw, crop!!.cx, 0.02f)
        assertEquals(150f / fh, crop.cy, 0.02f)
    }
}
