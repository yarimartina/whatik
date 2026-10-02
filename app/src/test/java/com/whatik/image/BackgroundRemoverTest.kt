package com.whatik.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundRemoverTest {

    private val w = 100
    private val h = 100
    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFE02020.toInt()

    private fun frame(fill: Int = white, paint: (IntArray) -> Unit = {}): IntArray = IntArray(w * h) { fill }.also(paint)

    private fun rect(img: IntArray, l: Int, t: Int, r: Int, b: Int, color: Int) {
        for (y in t until b) for (x in l until r) img[y * w + x] = color
    }

    private fun alpha(c: Int) = (c ushr 24) and 0xFF

    @Test
    fun whiteBorderBecomesTransparentButInnerWhiteStays() {
        val img = frame {
            rect(it, 30, 30, 70, 70, red)
            rect(it, 40, 40, 60, 60, white) // bianco chiuso dentro lo sticker
        }
        val bg = BackgroundRemover.resolveBackground(img, w, h, null)
        assertEquals(0xFFFFFF, bg)
        val result = BackgroundRemover.removeConnected(img, w, h, bg!!)
        assertEquals(0, alpha(img[5 * w + 5]))
        assertEquals(0, alpha(img[29 * w + 50]))
        assertEquals(255, alpha(img[50 * w + 50])) // il bianco interno resta opaco
        assertEquals(255, alpha(img[35 * w + 35]))
        val box = result.box!!
        assertEquals(30, box[0]); assertEquals(30, box[1]); assertEquals(40, box[2]); assertEquals(40, box[3])
    }

    @Test
    fun noisyJpegWhiteIsStillBackground() {
        var seed = 7
        val img = IntArray(w * h) {
            seed = seed * 1103515245 + 12345
            val v = 255 - ((seed ushr 16) and 7) // 248..255
            0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        rect(img, 20, 20, 80, 80, red)
        val bg = BackgroundRemover.resolveBackground(img, w, h, null)!!
        BackgroundRemover.removeConnected(img, w, h, bg)
        assertEquals(0, alpha(img[2 * w + 2]))
        assertEquals(255, alpha(img[50 * w + 50]))
    }

    @Test
    fun hintIsRefinedOnTheEdge() {
        val img = frame(0xFFF8F8F8.toInt()) { rect(it, 30, 30, 70, 70, red) }
        val bg = BackgroundRemover.resolveBackground(img, w, h, 0xF0F0F0)
        assertEquals(0xF8F8F8, bg)
    }

    @Test
    fun landscapeBandsAreRemovedAndContentTouchingEdgesKept() {
        val img = frame { rect(it, 0, 30, 100, 70, red) } // immagine larga quanto il fotogramma, bande bianche sopra e sotto
        val bg = BackgroundRemover.resolveBackground(img, w, h, null)!!
        val box = BackgroundRemover.removeConnected(img, w, h, bg).box!!
        assertEquals(0, alpha(img[10 * w + 50]))
        assertEquals(255, alpha(img[50 * w + 0]))
        assertEquals(30, box[1]); assertEquals(40, box[3]); assertEquals(100, box[2])
    }

    @Test
    fun photoWithUnevenEdgesKeepsItsPixelsExceptWhiteCorners() {
        val img = IntArray(w * h) { p -> val x = p % w; val y = p / w; 0xFF000000.toInt() or ((x * 2) shl 16) or ((y * 2) shl 8) or 0x40 }
        // angoli arrotondati: piccoli triangoli bianchi
        for (y in 0 until 6) for (x in 0 until 6 - y) { img[y * w + x] = white; img[y * w + (w - 1 - x)] = white; img[(h - 1 - y) * w + x] = white; img[(h - 1 - y) * w + (w - 1 - x)] = white }
        val bg = BackgroundRemover.resolveBackground(img, w, h, null)
        assertEquals(0xFFFFFF, bg)
        val result = BackgroundRemover.removeConnected(img, w, h, bg!!)
        assertTrue("removed ${result.removed}", result.removed in 60..100)
        assertEquals(0, alpha(img[0]))
        assertEquals(255, alpha(img[50 * w + 50]))
        assertEquals(255, alpha(img[10 * w + 0]))
    }

    @Test
    fun cornerFillThatSpreadsTooFarIsReverted() {
        val img = IntArray(w * h) { p -> val x = p % w; 0xFF000000.toInt() or ((x * 2) shl 16) or 0x8040 }
        rect(img, 0, 0, 40, 40, white) // grande zona bianca che tocca solo l'angolo in alto a sinistra
        val bg = 0xFFFFFF
        val result = BackgroundRemover.removeConnected(img, w, h, bg)
        assertEquals(0, result.removed)
        assertEquals(255, alpha(img[5 * w + 5]))
    }

    @Test
    fun brightSpotTouchingThePhotoEdgeIsNotPunched() {
        // foto rettangolare (gradiente) con bande bianche sopra e sotto, angoli arrotondati bianchi
        // e un bagliore bianco che tocca il bordo inferiore della foto
        val img = frame {
            for (y in 20 until 80) for (x in 0 until w) it[y * w + x] = 0xFF000000.toInt() or ((x * 2) shl 16) or (y shl 8) or 0x60
            for (d in 0 until 5) for (k in 0 until 5 - d) {
                it[(20 + d) * w + k] = white; it[(20 + d) * w + (w - 1 - k)] = white
                it[(79 - d) * w + k] = white; it[(79 - d) * w + (w - 1 - k)] = white
            }
            rect(it, 45, 65, 55, 80, white) // bagliore collegato alla banda inferiore
        }
        val bg = BackgroundRemover.resolveBackground(img, w, h, null)!!
        val result = BackgroundRemover.removeConnected(img, w, h, bg)
        assertEquals(0, alpha(img[5 * w + 50]))      // banda sopra tolta
        assertEquals(0, alpha(img[20 * w + 0]))      // angolo arrotondato tolto
        assertEquals(255, alpha(img[70 * w + 50]))   // bagliore dentro la foto conservato
        val box = result.box!!
        assertEquals(20, box[1]); assertEquals(60, box[3])
    }

    @Test
    fun stickerOnWhiteStillLosesTheWhiteInsideItsBox() {
        // una "L" rossa: il riquadro del contenuto ha lati vuoti, quindi il bianco dentro al riquadro va tolto
        val img = frame { rect(it, 20, 20, 35, 80, red); rect(it, 20, 65, 80, 80, red) }
        val bg = BackgroundRemover.resolveBackground(img, w, h, null)!!
        BackgroundRemover.removeConnected(img, w, h, bg)
        assertEquals(0, alpha(img[30 * w + 60])) // dentro il riquadro 20..80 ma fuori dalla L
        assertEquals(255, alpha(img[70 * w + 70]))
    }

    @Test
    fun noBackgroundWhenEdgesAreNotUniformAndCornersAreNotWhite() {
        val img = IntArray(w * h) { p -> val x = p % w; val y = p / w; 0xFF000000.toInt() or ((x * 2) shl 16) or ((y * 2) shl 8) }
        assertNull(BackgroundRemover.resolveBackground(img, w, h, null))
    }

    @Test
    fun fullyBackgroundFrameHasNoBox() {
        val img = frame()
        val result = BackgroundRemover.removeConnected(img, w, h, 0xFFFFFF)
        assertNull(result.box)
        assertEquals(w * h, result.removed)
    }

    @Test
    fun expandAndUnionStayInsideTheFrame() {
        val box = BackgroundRemover.expand(intArrayOf(2, 2, 10, 10), w, h, BackgroundRemover.Params(margin = 0.05f))
        assertEquals(0, box[0]); assertEquals(17, box[2])
        val u = BackgroundRemover.union(intArrayOf(10, 10, 10, 10), intArrayOf(15, 5, 10, 10))
        assertNotNull(u)
        assertEquals(10, u[0]); assertEquals(5, u[1]); assertEquals(15, u[2]); assertEquals(15, u[3])
    }
}
