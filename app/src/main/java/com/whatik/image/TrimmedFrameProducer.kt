package com.whatik.image

import android.graphics.Bitmap

/**
 * Tiene solo i fotogrammi il cui istante iniziale cade in [startMs, endMs]: serve per
 * accorciare uno sticker animato già in libreria (loop sbagliato, coda inutile).
 */
class TrimmedFrameProducer(private val inner: FrameProducer, startMs: Long, endMs: Long) : FrameProducer {
    private val kept: List<Int>

    init {
        val durations = inner.info.durationsMs
        var t = 0L
        val indices = ArrayList<Int>()
        for ((i, d) in durations.withIndex()) {
            if (t in startMs..endMs) indices.add(i)
            t += d
        }
        kept = indices.ifEmpty { listOf(0) }
    }

    override val info: FrameInfo
        get() = FrameInfo(inner.info.width, inner.info.height, kept.map { inner.info.durationsMs[it] })

    override fun produce(consume: (Int, Bitmap) -> Boolean) {
        val keptSet = kept.toHashSet()
        val last = kept.last()
        var next = 0
        inner.produce { index, frame ->
            if (index in keptSet) {
                val keepGoing = consume(next++, frame)
                keepGoing && index < last
            } else {
                frame.recycle()
                index < last
            }
        }
    }

    companion object {
        /** Istanti iniziali cumulativi dei fotogrammi. */
        fun startTimes(durations: List<Int>): List<Long> {
            var t = 0L
            return durations.map { d -> t.also { t += d } }
        }
    }
}
