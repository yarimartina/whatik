package com.whatik.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerConverterTest {

    @Test
    fun selectFrames_keepsAllWhenUnderLimit() {
        assertEquals(listOf(0, 1, 2), StickerConverter.selectFrames(3, 10))
    }

    @Test
    fun selectFrames_subsamplesEvenlyStartingFromFirst() {
        val kept = StickerConverter.selectFrames(10, 4)
        assertEquals(listOf(0, 2, 5, 7), kept)
    }

    @Test
    fun mergeDurations_absorbsDroppedFrames() {
        val durations = listOf(10, 20, 30, 40, 50)
        val merged = StickerConverter.mergeDurations(durations, listOf(0, 2, 4))
        assertEquals(listOf(30, 70, 50), merged)
        assertEquals(durations.sum(), merged.sum())
    }

    @Test
    fun mergeDurations_enforcesMinimumFrameDuration() {
        val merged = StickerConverter.mergeDurations(listOf(1, 1), listOf(0, 1))
        assertEquals(listOf(StickerConverter.MIN_FRAME_MS, StickerConverter.MIN_FRAME_MS), merged)
    }

    @Test
    fun animateStill_makesTwoIdenticalFrames() {
        // finto WebP statico 512x512 (bitstream VP8 fittizio, come in WebPContainerTest)
        val payload = ByteArray(16) { 0x55 }
        payload[3] = 0x9D.toByte(); payload[4] = 0x01; payload[5] = 0x2A
        payload[6] = 0x00; payload[7] = 0x02; payload[8] = 0x00; payload[9] = 0x02 // 512x512
        val still = WebPContainer.wrapStandalone(listOf(WebPContainer.Chunk("VP8 ", payload)), 512, 512)
        val result = StickerConverter.animateStill(still, quality = 90)
        assertTrue(result.animated)
        assertEquals(2, result.frameCount)
        val parsed = WebPContainer.parse(result.bytes)
        assertTrue(parsed.animated)
        assertEquals(2, parsed.frames.size)
        assertArrayEquals(parsed.frames[0].standalone, parsed.frames[1].standalone)
        assertEquals(parsed.frames[0].durationMs, parsed.frames[1].durationMs)
        assertTrue(parsed.totalDurationMs in 16..10_000)
    }
}
