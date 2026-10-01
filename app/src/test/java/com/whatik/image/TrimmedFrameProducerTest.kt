package com.whatik.image

import org.junit.Assert.assertEquals
import org.junit.Test

class TrimmedFrameProducerTest {
    @Test
    fun startTimes_areCumulative() {
        assertEquals(listOf(0L, 100L, 250L, 300L), TrimmedFrameProducer.startTimes(listOf(100, 150, 50, 200)))
        assertEquals(emptyList<Long>(), TrimmedFrameProducer.startTimes(emptyList()))
    }
}
