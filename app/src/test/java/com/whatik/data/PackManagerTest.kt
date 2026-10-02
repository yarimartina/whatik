package com.whatik.data

import com.whatik.image.WebPContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PackManagerTest {

    private fun fakeStill(): ByteArray {
        val payload = ByteArray(16) { 0x55 }
        payload[3] = 0x9D.toByte(); payload[4] = 0x01; payload[5] = 0x2A
        payload[6] = 0x00; payload[7] = 0x02; payload[8] = 0x00; payload[9] = 0x02 // 512x512
        return WebPContainer.wrapStandalone(listOf(WebPContainer.Chunk("VP8 ", payload)), 512, 512)
    }

    @Test
    fun stillStickerBecomesAnimatedForAnimatedPack() {
        val still = fakeStill()
        val out = PackManager.convertType(still, animated = true)
        assertTrue(WebPContainer.isAnimated(out))
        assertEquals(2, WebPContainer.parse(out).frames.size)
    }

    @Test
    fun matchingTypeIsLeftUntouched() {
        val still = fakeStill()
        assertSame(still, PackManager.convertType(still, animated = false))
        val animated = PackManager.convertType(still, animated = true)
        assertSame(animated, PackManager.convertType(animated, animated = true))
    }
}
