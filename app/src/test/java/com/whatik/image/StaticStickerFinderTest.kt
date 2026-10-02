package com.whatik.image

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
}
