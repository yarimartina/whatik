package com.whatik.image

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File
import kotlin.math.roundToInt

/** Uno sticker trovato automaticamente in una registrazione, con anteprima già ritagliata. */
class StickerProposal(
    val crop: CropSpec,
    val startMs: Long,
    val endMs: Long,
    val preview: Bitmap,
    val score: Float,
)

/**
 * Campiona la registrazione a bassa risoluzione, la passa a [StickerDetector] e prepara
 * un'anteprima ritagliata per ogni sticker trovato.
 */
object VideoAnalyzer {
    private const val ANALYSIS_WIDTH = 160
    private const val SAMPLE_FPS = 4
    private const val MAX_ANALYSIS_MS = 12_000L
    private const val PREVIEW_SIDE = 256

    fun analyze(
        file: File,
        info: VideoFrameProducer.VideoInfo,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): List<StickerProposal> {
        val aw = ANALYSIS_WIDTH
        val ah = (info.height.toFloat() * aw / info.width).roundToInt().coerceAtLeast(8)
        val analysisEnd = minOf(info.durationMs, MAX_ANALYSIS_MS)
        val stepMs = 1000L / SAMPLE_FPS
        val times = ArrayList<Long>()
        var t = 0L
        while (t < analysisEnd) { times.add(t); t += stepMs }
        if (times.size < 4) return emptyList()

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val frames = ArrayList<IntArray>(times.size)
            val keptTimes = ArrayList<Long>(times.size)
            val buffer = IntArray(aw * ah)
            for ((i, timeMs) in times.withIndex()) {
                val raw = frameAt(retriever, timeMs, aw * 2, ah * 2) ?: continue
                val scaled = if (raw.width == aw && raw.height == ah) raw else Bitmap.createScaledBitmap(raw, aw, ah, true)
                if (scaled !== raw) raw.recycle()
                frames.add(toGray(scaled, buffer))
                scaled.recycle()
                keptTimes.add(timeMs)
                onProgress?.invoke(i + 1, times.size)
            }
            val found = StickerDetector.detect(frames, aw, ah, keptTimes)
            if (found.isEmpty()) return emptyList()

            return found.mapNotNull { proposal ->
                val frame = frameAt(retriever, proposal.startMs, 720, 720) ?: return@mapNotNull null
                val cropped = proposal.crop.apply(frame)
                if (cropped !== frame) frame.recycle()
                val preview = if (cropped.width > PREVIEW_SIDE) Bitmap.createScaledBitmap(cropped, PREVIEW_SIDE, PREVIEW_SIDE, true) else cropped
                if (preview !== cropped) cropped.recycle()
                StickerProposal(proposal.crop, proposal.startMs, proposal.endMs, preview, proposal.score)
            }
        } finally {
            retriever.release()
        }
    }

    /** Converte un bitmap in scala di grigi (0..255); [buffer] deve avere width*height elementi. */
    fun toGray(bitmap: Bitmap, buffer: IntArray = IntArray(bitmap.width * bitmap.height)): IntArray {
        bitmap.getPixels(buffer, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val gray = IntArray(bitmap.width * bitmap.height)
        for (p in gray.indices) {
            val c = buffer[p]
            gray[p] = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        return gray
    }

    private fun frameAt(retriever: MediaMetadataRetriever, timeMs: Long, maxW: Int, maxH: Int): Bitmap? {
        val timeUs = timeMs * 1000L
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, maxW, maxH)
        } else {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        }
    }
}
