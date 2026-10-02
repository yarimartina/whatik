package com.whatik.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerDetectorTest {

    private val w = 100
    private val h = 160
    private val intervalMs = 250L

    /** Sfondo con un gradiente fisso (come un'interfaccia), su cui disegnare regioni animate. */
    private fun background(): IntArray = IntArray(w * h) { i -> 40 + (i % w) / 2 }

    private fun fill(frame: IntArray, left: Int, top: Int, size: Int, value: Int) {
        for (y in top until top + size) for (x in left until left + size) frame[y * w + x] = value
    }

    /** Sequenza di [count] fotogrammi; [paint] disegna le regioni animate per l'indice dato. */
    private fun sequence(count: Int, paint: (frame: IntArray, index: Int) -> Unit): Pair<List<IntArray>, List<Long>> {
        val frames = List(count) { i -> background().also { paint(it, i) } }
        return frames to List(count) { it * intervalMs }
    }

    @Test
    fun staticScreenYieldsNothing() {
        val (frames, times) = sequence(12) { _, _ -> }
        assertTrue(StickerDetector.detect(frames, w, h, times).isEmpty())
    }

    @Test
    fun findsOneBlinkingRegionWithSquareCropAroundIt() {
        val (frames, times) = sequence(16) { f, i -> fill(f, 20, 50, 24, if (i % 2 == 0) 200 else 60) }
        val found = StickerDetector.detect(frames, w, h, times)
        assertEquals(1, found.size)
        val p = found[0]
        val (l, t, bw, bh) = p.box
        // il rettangolo rilevato contiene la regione (20..44, 50..74) con poca tolleranza
        assertTrue("left $l", l in 17..21)
        assertTrue("top $t", t in 47..51)
        assertTrue("width $bw", bw in 23..30)
        assertTrue("height $bh", bh in 23..30)
        // il ritaglio è centrato sulla regione: centro atteso (32, 62) normalizzato
        assertEquals(0.32f, p.crop.cx, 0.03f)
        assertEquals(62f / h, p.crop.cy, 0.03f)
        assertTrue(p.crop.size in 0.2f..0.4f)
        assertEquals(0L, p.startMs)
        // il lampeggio ha periodo 2 fotogrammi
        assertEquals(2 * intervalMs, p.endMs - p.startMs)
    }

    @Test
    fun findsTwoRegionsInReadingOrder() {
        val (frames, times) = sequence(16) { f, i ->
            fill(f, 60, 100, 20, if (i % 2 == 0) 220 else 30)
            fill(f, 10, 20, 20, if (i % 3 == 0) 220 else 30)
        }
        val found = StickerDetector.detect(frames, w, h, times)
        assertEquals(2, found.size)
        assertTrue(found[0].box[1] < found[1].box[1])
        assertEquals(3 * intervalMs, found[0].endMs - found[0].startMs)
        assertEquals(2 * intervalMs, found[1].endMs - found[1].startMs)
    }

    @Test
    fun skipsScrollingPhaseAndStartsAtStableWindow() {
        val (frames, times) = sequence(20) { f, i ->
            if (i < 6) {
                // scorrimento: tutto lo schermo cambia
                for (p in f.indices) f[p] = (f[p] + i * 37) % 256
            } else {
                fill(f, 30, 30, 20, if (i % 2 == 0) 230 else 20)
            }
        }
        val found = StickerDetector.detect(frames, w, h, times)
        assertEquals(1, found.size)
        assertEquals(6 * intervalMs, found[0].startMs)
    }

    @Test
    fun ignoresFullScreenVideoPlayback() {
        val (frames, times) = sequence(12) { f, i ->
            // quasi tutto lo schermo si muove: non è uno sticker ma un video in riproduzione
            for (p in f.indices) if (p % 7 != 0) f[p] = (p * 13 + i * 50) % 256
        }
        assertTrue(StickerDetector.detect(frames, w, h, times).isEmpty())
    }

    @Test
    fun mergeBoxesJoinsOverlappingAndNearbyRectangles() {
        val merged = StickerDetector.mergeBoxes(listOf(intArrayOf(0, 0, 10, 10), intArrayOf(11, 2, 20, 12), intArrayOf(50, 50, 60, 60)), gap = 2)
        assertEquals(2, merged.size)
        assertTrue(merged.any { it.contentEquals(intArrayOf(0, 0, 20, 12)) })
    }

    @Test
    fun splitsARowOfTouchingStickers() {
        // due regioni separate da 2 colonne: la dilatazione le unisce, il profilo le separa
        val (frames, times) = sequence(16) { f, i ->
            fill(f, 10, 60, 20, if (i % 2 == 0) 230 else 20)
            fill(f, 32, 60, 20, if (i % 2 == 0) 230 else 20)
        }
        val found = StickerDetector.detect(frames, w, h, times)
        assertEquals(2, found.size)
        assertTrue(found[0].box[0] + found[0].box[2] <= 33)
        assertTrue(found[1].box[0] >= 29)
    }

    @Test
    fun ringActivityMeasuresMotionAroundTheBox() {
        // maschera: il riquadro 10..30 x 10..30 e' fermo, tutto il resto si muove
        val mask = BooleanArray(w * h) { p -> val x = p % w; val y = p / w; !(x in 10 until 30 && y in 10 until 30) }
        assertTrue(StickerDetector.ringActivity(mask, w, h, 10, 10, 30, 30) > 0.9f)
        // nessun movimento attorno
        val still = BooleanArray(w * h) { p -> val x = p % w; val y = p / w; x in 10 until 30 && y in 10 until 30 }
        assertEquals(0f, StickerDetector.ringActivity(still, w, h, 10, 10, 30, 30), 1e-6f)
    }
}
