package com.whatik.image

import org.junit.Assert.assertEquals
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
}
