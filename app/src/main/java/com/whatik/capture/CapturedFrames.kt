package com.whatik.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.whatik.image.CropSpec
import com.whatik.image.FrameInfo
import com.whatik.image.FrameProducer
import com.whatik.image.StaticStickerFinder
import com.whatik.image.StickerConverter
import com.whatik.image.StickerDetector
import com.whatik.image.VideoAnalyzer
import java.io.File
import kotlin.math.roundToInt

/** Sequenza di fotogrammi catturati dallo schermo e salvati come JPEG con il loro istante. */
class CapturedFrames(val files: List<File>, val timesMs: List<Long>) {
    val width: Int
    val height: Int

    init {
        require(files.size == timesMs.size && files.isNotEmpty())
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(files[0].absolutePath, opts)
        width = opts.outWidth
        height = opts.outHeight
    }

    /** Fotogrammi in scala di grigi a bassa risoluzione per il rilevatore. */
    fun grayFrames(analysisWidth: Int = 160): Pair<List<IntArray>, Pair<Int, Int>> {
        val aw = analysisWidth
        val ah = (height.toFloat() * aw / width).roundToInt().coerceAtLeast(8)
        var sample = 1
        while (width / (sample * 2) >= aw) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val buffer = IntArray(aw * ah)
        val frames = files.map { file ->
            val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: throw StickerConverter.ConversionException("Fotogramma catturato non leggibile")
            val scaled = Bitmap.createScaledBitmap(raw, aw, ah, true)
            if (scaled !== raw) raw.recycle()
            val gray = VideoAnalyzer.toGray(scaled, buffer)
            scaled.recycle()
            gray
        }
        return frames to (aw to ah)
    }

    fun detect(params: StickerDetector.Params): List<StickerDetector.Proposal> {
        val (frames, dims) = grayFrames()
        return StickerDetector.detect(frames, dims.first, dims.second, timesMs, params)
    }

    fun producer(crop: CropSpec, startMs: Long, endMs: Long): FrameProducer = CapturedFrameProducer(this, crop, startMs, endMs)

    /**
     * Riquadro di uno sticker fermo attorno al punto (in pixel del fotogramma), stimato sul primo
     * fotogramma ridotto a ~[analysisWidth] px di larghezza.
     */
    fun staticCropAround(px: Int, py: Int, analysisWidth: Int = 480): CropSpec {
        var sample = 1
        while (width / (sample * 2) >= analysisWidth) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(files[0].absolutePath, opts)
            ?: throw StickerConverter.ConversionException("Fotogramma catturato non leggibile")
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val sx = px * bitmap.width / width
        val sy = py * bitmap.height / height
        val result = StaticStickerFinder.find(pixels, bitmap.width, bitmap.height, sx, sy)
        bitmap.recycle()
        return StaticStickerFinder.toCrop(result.box, bitmap.width, bitmap.height)
    }

    fun delete() {
        files.forEach { it.delete() }
    }
}

/** [FrameProducer] su un intervallo dei fotogrammi catturati, con ritaglio. */
class CapturedFrameProducer(
    private val frames: CapturedFrames,
    private val crop: CropSpec,
    startMs: Long,
    endMs: Long,
) : FrameProducer {
    private val indices: List<Int> = frames.timesMs.indices.filter { frames.timesMs[it] in startMs..endMs }.ifEmpty { listOf(0) }

    override val info: FrameInfo
        get() {
            val (_, _, side) = crop.toPixels(frames.width, frames.height)
            val durations = indices.mapIndexed { i, idx ->
                val next = if (i + 1 < indices.size) frames.timesMs[indices[i + 1]] else frames.timesMs[idx] + averageStep()
                (next - frames.timesMs[idx]).toInt().coerceAtLeast(StickerConverter.MIN_FRAME_MS)
            }
            return FrameInfo(side, side, durations)
        }

    private fun averageStep(): Long {
        if (frames.timesMs.size < 2) return 100
        return ((frames.timesMs.last() - frames.timesMs.first()) / (frames.timesMs.size - 1)).coerceAtLeast(1)
    }

    override fun produce(consume: (Int, Bitmap) -> Boolean) {
        for ((i, idx) in indices.withIndex()) {
            val raw = BitmapFactory.decodeFile(frames.files[idx].absolutePath)
                ?: throw StickerConverter.ConversionException("Fotogramma catturato non leggibile")
            val cropped = crop.apply(raw)
            if (cropped !== raw) raw.recycle()
            if (!consume(i, cropped)) return
        }
    }
}
