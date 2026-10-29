package com.whatik.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    // ---------------------------------------------------------------- codifica in parallelo

    /** Al posto del bitmap adattato: conta quante volte viene rilasciato e quanti sono vivi insieme. */
    private class Item(val index: Int) {
        val released = AtomicInteger()
    }

    private class Lifetimes {
        val alive = AtomicInteger()
        val maxAlive = AtomicInteger()
        fun prepare(item: Item): Item = item.also { maxAlive.accumulateAndGet(alive.incrementAndGet()) { a, b -> maxOf(a, b) } }
        fun release(item: Item) {
            item.released.incrementAndGet()
            alive.decrementAndGet()
        }
    }

    /** Codifica finta e deterministica, con durate diverse perché i worker finiscano fuori ordine. */
    private fun fakeEncode(item: Item): ByteArray {
        Thread.sleep(((item.index * 7) % 5).toLong())
        return ByteArray(8) { (item.index * 31 + it).toByte() }
    }

    /** Esecutore manuale: i lavori partono solo quando il test li esegue. */
    private class ManualExecutor : Executor {
        val queued = ArrayList<Runnable>()
        override fun execute(command: Runnable) {
            synchronized(queued) { queued.add(command) }
        }
        fun run(i: Int) = synchronized(queued) { queued[i] }.run()
    }

    @Test
    fun orderedEncoder_keepsFrameOrderAndSequentialBytes() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val items = List(60) { Item(it) }
            val life = Lifetimes()
            val encoder = StickerConverter.OrderedEncoder<Item>(pool, 5, ::fakeEncode, life::release)
            for (item in items) encoder.submit { life.prepare(item) }
            val out = encoder.await()
            val sequential = items.map { fakeEncode(it) }
            assertEquals(sequential.size, out.size)
            for (i in items.indices) assertArrayEquals(sequential[i], out[i])
            assertTrue(items.all { it.released.get() == 1 })
            assertEquals(0, life.alive.get())
            assertTrue("vivi insieme: ${life.maxAlive.get()}", life.maxAlive.get() <= 5)
            assertFalse(encoder.failed)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun orderedEncoder_encodesOnSeveralWorkersAtOnce() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            // ogni codifica aspetta le altre tre: in sequenza la barriera scadrebbe
            val barrier = CyclicBarrier(4)
            val encoder = StickerConverter.OrderedEncoder<Item>(pool, 5, { barrier.await(5, TimeUnit.SECONDS); fakeEncode(it) }, {})
            repeat(4) { i -> encoder.submit { Item(i) } }
            assertEquals(4, encoder.await().size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun orderedEncoder_waitsForAFreeSlotBeforePreparing() {
        val executor = ManualExecutor()
        val life = Lifetimes()
        val encoder = StickerConverter.OrderedEncoder<Item>(executor, 2, ::fakeEncode, life::release)
        encoder.submit { life.prepare(Item(0)) }
        encoder.submit { life.prepare(Item(1)) }
        val third = Thread { encoder.submit { life.prepare(Item(2)) } }
        third.start()
        third.join(200)
        assertTrue("il terzo non deve essere preparato finché non c'è posto", third.isAlive)
        assertEquals(2, life.alive.get())
        executor.run(0) // codificato e rilasciato: si libera un posto
        third.join(5_000)
        assertFalse(third.isAlive)
        executor.run(1)
        executor.run(2)
        assertEquals(3, encoder.await().size)
        assertEquals(2, life.maxAlive.get())
        assertEquals(0, life.alive.get())
    }

    @Test
    fun orderedEncoder_rethrowsTheWorkerErrorAfterReleasingEverything() {
        val pool = Executors.newFixedThreadPool(3)
        try {
            val boom = StickerConverter.ConversionException("Codifica WebP fallita")
            val items = List(12) { Item(it) }
            val life = Lifetimes()
            val encoder = StickerConverter.OrderedEncoder<Item>(pool, 4, { if (it.index == 3) throw boom else fakeEncode(it) }, life::release)
            for (item in items) {
                if (encoder.failed) break // come encodePass: smette di decodificare
                encoder.submit { life.prepare(item) }
            }
            val thrown = assertThrows(StickerConverter.ConversionException::class.java) { encoder.await() }
            assertSame(boom, thrown)
            assertTrue(encoder.failed)
            // ogni elemento preparato è stato rilasciato una e una sola volta
            assertTrue(items.all { it.released.get() <= 1 })
            assertEquals(0, life.alive.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun orderedEncoder_abortSkipsQueuedWorkButStillReleases() {
        val executor = ManualExecutor()
        val encodes = AtomicInteger()
        val items = List(3) { Item(it) }
        val life = Lifetimes()
        val encoder = StickerConverter.OrderedEncoder<Item>(executor, 3, { encodes.incrementAndGet(); fakeEncode(it) }, life::release)
        for (item in items) encoder.submit { life.prepare(item) }
        executor.run(0) // già codificato quando il chiamante fallisce
        encoder.abort()
        executor.run(1)
        executor.run(2)
        assertEquals(1, encodes.get())
        assertTrue(items.all { it.released.get() == 1 })
        assertEquals(0, life.alive.get())
        assertEquals(1, encoder.await().size)
    }

    @Test
    fun orderedEncoder_interruptedWhileWaitingPreparesNothing() {
        val encoder = StickerConverter.OrderedEncoder<Item>(ManualExecutor(), 1, ::fakeEncode, {})
        var prepared = false
        Thread.currentThread().interrupt()
        assertThrows(InterruptedException::class.java) { encoder.submit { prepared = true; Item(0) } }
        assertFalse(prepared)
        assertFalse(Thread.interrupted())
    }
}
