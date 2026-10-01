package com.whatik.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Estrae i fotogrammi di un video (tipicamente una registrazione dello schermo di TikTok)
 * nell'intervallo [startMs, endMs] a [fps] fotogrammi al secondo, applicando il ritaglio.
 *
 * L'estrazione tramite MediaMetadataRetriever è lenta (decodifica dal keyframe precedente
 * a ogni richiesta), quindi i fotogrammi vengono messi in cache su disco al primo passaggio:
 * la riduzione automatica di qualità/fotogrammi del converter può così ripetere `produce`
 * senza ridecodificare il video.
 */
class VideoFrameProducer(
    private val file: File,
    private val startMs: Long,
    endMs: Long,
    private val fps: Int,
    private val crop: CropSpec,
    private val cacheDir: File,
    /** Lato (in pixel) a cui far decodificare il fotogramma ritagliato: evita bitmap enormi. */
    private val targetSide: Int = StickerConverter.STICKER_SIZE,
    private val onFrame: ((index: Int, total: Int) -> Unit)? = null,
) : FrameProducer {

    class VideoInfo(val width: Int, val height: Int, val durationMs: Long)

    private val video = readInfo(file)
    private val endMs = endMs.coerceIn(startMs + 1, video.durationMs.coerceAtLeast(startMs + 1))
    private val frameCount: Int = ceil((this.endMs - startMs) * fps / 1000.0).toInt().coerceAtLeast(1)
    private val frameDurationMs = (1000f / fps).roundToInt().coerceAtLeast(StickerConverter.MIN_FRAME_MS)
    private var cached = false

    override val info: FrameInfo
        get() {
            val (_, _, side) = crop.toPixels(video.width, video.height)
            return FrameInfo(side, side, List(frameCount) { frameDurationMs })
        }

    override fun produce(consume: (Int, Bitmap) -> Boolean) {
        if (cached) {
            replayCache(consume)
            return
        }
        cacheDir.mkdirs()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            // decodifica già ridotta: il ritaglio deve coprire ~targetSide px, non serve di più
            val cropSide = crop.toPixels(video.width, video.height)[2].coerceAtLeast(1)
            val scale = (targetSide.toFloat() / cropSide).coerceAtMost(1f)
            val dstW = (video.width * scale).roundToInt().coerceAtLeast(1)
            val dstH = (video.height * scale).roundToInt().coerceAtLeast(1)
            for (i in 0 until frameCount) {
                val timeUs = (startMs + i * 1000L / fps) * 1000L
                val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, dstW, dstH)
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } ?: throw StickerConverter.ConversionException("Fotogramma video non leggibile a ${timeUs / 1000} ms")
                val cropped = crop.apply(frame)
                if (cropped !== frame) frame.recycle()
                writeCache(i, cropped)
                onFrame?.invoke(i + 1, frameCount)
                if (!consume(i, cropped)) return
            }
            cached = true
        } finally {
            retriever.release()
        }
    }

    private fun cacheFile(index: Int) = File(cacheDir, "f$index.jpg")

    private fun writeCache(index: Int, bitmap: Bitmap) {
        cacheFile(index).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }

    private fun replayCache(consume: (Int, Bitmap) -> Boolean) {
        for (i in 0 until frameCount) {
            val bitmap = BitmapFactory.decodeFile(cacheFile(i).absolutePath)
                ?: throw StickerConverter.ConversionException("Cache dei fotogrammi non leggibile")
            if (!consume(i, bitmap)) return
        }
    }

    fun clearCache() {
        cacheDir.deleteRecursively()
    }

    companion object {
        const val DEFAULT_FPS = 10

        /** Dimensioni (già ruotate) e durata del video. */
        fun readInfo(file: File): VideoInfo {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                var w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                var h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rotation == 90 || rotation == 270) { val t = w; w = h; h = t }
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (w <= 0 || h <= 0 || duration <= 0) throw StickerConverter.ConversionException("Video non riconosciuto")
                return VideoInfo(w, h, duration)
            } finally {
                retriever.release()
            }
        }

        /** Un singolo fotogramma (ridotto) per l'anteprima nell'editor. */
        fun previewFrame(file: File, timeMs: Long, maxSide: Int = 720): Bitmap? {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)
                val timeUs = timeMs * 1000L
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, maxSide, maxSide)
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                }
            } catch (e: Exception) {
                null
            } finally {
                retriever.release()
            }
        }
    }
}
