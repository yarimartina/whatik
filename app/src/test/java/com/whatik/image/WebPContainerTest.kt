package com.whatik.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPContainerTest {

    /** Finto bitstream VP8 (key frame) con larghezza/altezza nell'intestazione, come lo leggerebbe il parser. */
    private fun fakeVp8(width: Int, height: Int, filler: Int = 0x55): ByteArray {
        val p = ByteArray(16) { filler.toByte() }
        p[3] = 0x9D.toByte(); p[4] = 0x01; p[5] = 0x2A
        p[6] = (width and 0xFF).toByte(); p[7] = ((width shr 8) and 0x3F).toByte()
        p[8] = (height and 0xFF).toByte(); p[9] = ((height shr 8) and 0x3F).toByte()
        return p
    }

    private fun simpleWebP(width: Int, height: Int, filler: Int = 0x55): ByteArray =
        WebPContainer.wrapStandalone(listOf(WebPContainer.Chunk("VP8 ", fakeVp8(width, height, filler))), width, height)

    @Test
    fun isWebP_and_isAnimated() {
        val simple = simpleWebP(4, 4)
        assertTrue(WebPContainer.isWebP(simple))
        assertFalse(WebPContainer.isAnimated(simple))
        assertFalse(WebPContainer.isWebP("GIF89a".toByteArray()))
    }

    @Test
    fun parse_simpleFormatReadsDimensionsFromBitstream() {
        val parsed = WebPContainer.parse(simpleWebP(300, 200))
        assertEquals(300, parsed.width)
        assertEquals(200, parsed.height)
        assertFalse(parsed.animated)
        assertEquals(1, parsed.frames.size)
    }

    @Test
    fun extractBitstreamChunks_handlesExtendedFormatWithAlpha() {
        val alph = WebPContainer.Chunk("ALPH", byteArrayOf(1, 2, 3))
        val vp8 = WebPContainer.Chunk("VP8 ", fakeVp8(8, 8))
        val file = WebPContainer.wrapStandalone(listOf(alph, vp8), 8, 8)
        val chunks = WebPContainer.extractBitstreamChunks(file)
        assertEquals(listOf("ALPH", "VP8 "), chunks.map { it.fourCC })
        assertArrayEquals(byteArrayOf(1, 2, 3), chunks[0].payload)
        assertArrayEquals(vp8.payload, chunks[1].payload)
        // il padding del chunk dispari (ALPH da 3 byte) non deve corrompere il chunk successivo
        assertEquals(0, file.size % 2)
    }

    @Test
    fun muxAnimation_roundTripsThroughParse() {
        val frames = listOf(
            WebPContainer.FrameSpec(simpleWebP(512, 512, 0x11), 120),
            WebPContainer.FrameSpec(simpleWebP(512, 512, 0x22), 80),
            WebPContainer.FrameSpec(simpleWebP(512, 512, 0x33), 200),
        )
        val animated = WebPContainer.muxAnimation(512, 512, frames)
        assertTrue(WebPContainer.isWebP(animated))
        assertTrue(WebPContainer.isAnimated(animated))

        val parsed = WebPContainer.parse(animated)
        assertTrue(parsed.animated)
        assertTrue(parsed.hasAlpha)
        assertEquals(512, parsed.width)
        assertEquals(512, parsed.height)
        assertEquals(0, parsed.loopCount)
        assertEquals(listOf(120, 80, 200), parsed.frames.map { it.durationMs })
        assertEquals(400, parsed.totalDurationMs)
        for ((i, frame) in parsed.frames.withIndex()) {
            assertEquals(0, frame.x)
            assertEquals(0, frame.y)
            assertEquals(512, frame.width)
            assertEquals(512, frame.height)
            assertFalse(frame.blendWithPrevious)
            assertFalse(frame.disposeToBackground)
            // il fotogramma estratto è un WebP autonomo identico al sorgente
            assertArrayEquals(frames[i].standalone, frame.standalone)
        }
        // dimensione RIFF coerente con il file
        val declared = (animated[4].toInt() and 0xFF) or ((animated[5].toInt() and 0xFF) shl 8) or
            ((animated[6].toInt() and 0xFF) shl 16) or ((animated[7].toInt() and 0xFF) shl 24)
        assertEquals(animated.size - 8, declared)
    }

    @Test(expected = IllegalArgumentException::class)
    fun muxAnimation_requiresAtLeastTwoFrames() {
        WebPContainer.muxAnimation(512, 512, listOf(WebPContainer.FrameSpec(simpleWebP(512, 512), 100)))
    }
}
