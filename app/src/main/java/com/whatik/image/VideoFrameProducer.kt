package com.whatik.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat as AndroidImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val TAG = "WhatikVideo"

/**
 * Estrae i fotogrammi di un video (tipicamente una registrazione dello schermo di TikTok)
 * nell'intervallo [startMs, endMs] a [fps] fotogrammi al secondo, applicando il ritaglio.
 *
 * I fotogrammi si decodificano in sequenza ([VideoFrameSequence]); se non è possibile si
 * ricade su MediaMetadataRetriever, lento perché decodifica dal keyframe precedente a ogni
 * richiesta. In entrambi i casi vengono messi in cache su disco al primo passaggio: la
 * riduzione automatica di qualità/fotogrammi del converter può così ripetere `produce`
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
            val (_, _, w, h) = crop.toPixels(video.width, video.height)
            return FrameInfo(w, h, List(frameCount) { frameDurationMs })
        }

    override fun produce(consume: (Int, Bitmap) -> Boolean) {
        if (cached) {
            replayCache(consume)
            return
        }
        cacheDir.mkdirs()
        // decodifica già ridotta: il ritaglio deve coprire ~targetSide px, non serve di più
        val cropSide = crop.toPixels(video.width, video.height).let { maxOf(it[2], it[3]) }.coerceAtLeast(1)
        val scale = (targetSide.toFloat() / cropSide).coerceAtMost(1f)
        val dstW = (video.width * scale).roundToInt().coerceAtLeast(1)
        val dstH = (video.height * scale).roundToInt().coerceAtLeast(1)
        val timesUs = LongArray(frameCount) { i -> (startMs + i * 1000L / fps) * 1000L }
        // il retriever serve solo se la decodifica sequenziale non è disponibile o si interrompe
        var retriever: MediaMetadataRetriever? = null
        try {
            VideoFrameSequence(file, timesUs, dstW, dstH) { timeUs ->
                val r = retriever ?: MediaMetadataRetriever().also { retriever = it; it.setDataSource(file.absolutePath) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    r.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, dstW, dstH)
                } else {
                    r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                }
            }.use { frames ->
                for (i in 0 until frameCount) {
                    val frame = frames.next()
                        ?: throw StickerConverter.ConversionException("Fotogramma video non leggibile a ${timesUs[i] / 1000} ms")
                    val cropped = crop.apply(frame)
                    if (cropped !== frame) frame.recycle()
                    writeCache(i, cropped)
                    onFrame?.invoke(i + 1, frameCount)
                    if (!consume(i, cropped)) return
                }
            }
            cached = true
        } finally {
            retriever?.release()
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

/**
 * Fotogrammi a istanti crescenti ([timesUs], in µs) ridotti entro [maxW]x[maxH] come
 * getScaledFrameAtTime: decodifica sequenziale con [SequentialFrameReader] e, se non si può
 * aprire o si interrompe (codec non supportato, eccezione), [fallback] dall'istante in cui si è
 * fermata (MediaMetadataRetriever con OPTION_CLOSEST, come prima). I bitmap restituiti
 * appartengono al chiamante.
 */
internal class VideoFrameSequence(
    file: File,
    private val timesUs: LongArray,
    maxW: Int,
    maxH: Int,
    private val fallback: (timeUs: Long) -> Bitmap?,
) : Closeable {
    private var reader: SequentialFrameReader? = SequentialFrameReader.open(file, timesUs, maxW, maxH)
    private var index = 0

    /** Fotogramma del prossimo istante; null solo se il ripiego non lo restituisce. */
    fun next(): Bitmap? {
        val timeUs = timesUs[index++]
        reader?.let { r ->
            try {
                return r.next()
            } catch (e: Exception) {
                Log.w(TAG, "Decodifica sequenziale interrotta a ${timeUs / 1000} ms, si continua con MediaMetadataRetriever", e)
                r.close()
                reader = null
            }
        }
        return fallback(timeUs)
    }

    override fun close() {
        reader?.close()
        reader = null
    }
}

/**
 * Decodifica sequenziale (MediaExtractor + MediaCodec) dei fotogrammi più vicini a istanti
 * crescenti.
 *
 * MediaMetadataRetriever con OPTION_CLOSEST riparte dal keyframe precedente a ogni richiesta:
 * con il GOP di 1,1-1,3 s a circa 50 fps di una registrazione dello schermo sono 25-30
 * fotogrammi decodificati per ognuno tenuto. Qui il video si decodifica una volta, dal keyframe
 * che precede il primo istante fino all'ultimo fotogramma scelto, e si convertono in RGB solo i
 * fotogrammi scelti.
 *
 * Il fotogramma scelto per ogni istante è quello di OPTION_CLOSEST: il campione con il tempo di
 * presentazione più vicino, a parità il successivo ([closestSampleTimes], la regola di
 * SampleTable), poi il primo fotogramma decodificato con tempo >= a quello (come FrameDecoder).
 * Può spostarsi di un fotogramma, al massimo, in due casi: istanti a meno di 1 µs dal punto
 * medio fra due fotogrammi (SampleTable arrotonda i tempi al µs, MediaExtractor li tronca) e
 * B-frame di GOP aperti oltre i [REORDER_SAMPLES] campioni letti dopo l'ultimo istante.
 *
 * Nei pixel cambia poco: bitmap ARGB_8888 (il retriever senza BitmapParams restituisce
 * RGB_565), conversione YUV->RGB e riduzione bilineare fatte da [YuvConverter] invece che da
 * ColorConverter e Skia. I video ruotati restano al retriever.
 */
internal class SequentialFrameReader private constructor(
    private val extractor: MediaExtractor,
    private val codec: MediaCodec,
    /** Tempo (µs) del campione scelto per ogni istante richiesto. */
    private val chosenUs: LongArray,
    private val maxW: Int,
    private val maxH: Int,
    private var colorStandard: Int,
    private var fullRange: Boolean,
) : Closeable {
    private val bufferInfo = MediaCodec.BufferInfo()
    private var coefficients = coefficientsOf(colorStandard, fullRange)
    private var index = 0
    private var inputDone = false
    private var outputDone = false
    /** Copia del fotogramma appena restituito, se anche l'istante successivo cade lì. */
    private var spare: Bitmap? = null
    private var yBytes = ByteArray(0)
    private var uBytes = ByteArray(0)
    private var vBytes = ByteArray(0)
    private var pixels = IntArray(0)

    /** Fotogramma per il prossimo istante; un'eccezione se la decodifica non riesce. */
    fun next(): Bitmap {
        val k = index++
        val bitmap = spare ?: decodeUntil(chosenUs[k])
        spare = null
        // più istanti sullo stesso fotogramma (schermo fermo in una registrazione a frequenza variabile)
        if (k + 1 < chosenUs.size && chosenUs[k + 1] == chosenUs[k]) {
            spare = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: throw IllegalStateException("Copia del fotogramma non riuscita")
        }
        return bitmap
    }

    /** Decodifica fino al primo fotogramma con tempo >= [targetUs] e lo converte; i precedenti si scartano senza convertirli. */
    private fun decodeUntil(targetUs: Long): Bitmap {
        var idle = 0
        while (true) {
            check(!outputDone) { "Video finito prima di ${targetUs / 1000} ms" }
            var progress = false
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex) ?: throw IllegalStateException("Buffer di ingresso mancante")
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    progress = true
                }
            }
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outIndex >= 0) {
                progress = true
                outputDone = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (bufferInfo.size > 0 && bufferInfo.presentationTimeUs >= targetUs) return convert(outIndex)
                codec.releaseOutputBuffer(outIndex, false)
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // gli aspetti colore del bitstream (VUI) arrivano con il formato d'uscita
                val format = codec.outputFormat
                if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) colorStandard = format.getInteger(MediaFormat.KEY_COLOR_STANDARD)
                if (format.containsKey(MediaFormat.KEY_COLOR_RANGE)) fullRange = format.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
                coefficients = coefficientsOf(colorStandard, fullRange)
                progress = true
            }
            if (progress) idle = 0 else if (++idle > MAX_IDLE_LOOPS) throw IllegalStateException("Decoder fermo")
        }
    }

    private fun convert(outIndex: Int): Bitmap {
        try {
            val image = codec.getOutputImage(outIndex) ?: throw IllegalStateException("Fotogramma non leggibile come Image")
            try {
                check(image.format == AndroidImageFormat.YUV_420_888 && image.planes.size == 3) { "Formato ${image.format} non gestito" }
                val crop = image.cropRect
                val (dstW, dstH) = fitInside(crop.width(), crop.height(), maxW, maxH)
                if (pixels.size != dstW * dstH) pixels = IntArray(dstW * dstH)
                val (yPlane, uPlane, vPlane) = image.planes
                yBytes = copyPlane(yPlane.buffer, yBytes)
                uBytes = copyPlane(uPlane.buffer, uBytes)
                vBytes = copyPlane(vPlane.buffer, vBytes)
                YuvConverter.convert(
                    yBytes, yPlane.rowStride, yPlane.pixelStride,
                    uBytes, vBytes, uPlane.rowStride, uPlane.pixelStride,
                    crop.left, crop.top, crop.width(), crop.height(),
                    dstW, dstH, coefficients, pixels,
                )
                return Bitmap.createBitmap(pixels, dstW, dstH, Bitmap.Config.ARGB_8888)
            } finally {
                image.close()
            }
        } finally {
            codec.releaseOutputBuffer(outIndex, false)
        }
    }

    private fun copyPlane(buffer: ByteBuffer, reuse: ByteArray): ByteArray {
        val n = buffer.remaining()
        val target = if (reuse.size >= n) reuse else ByteArray(n)
        buffer.get(target, 0, n)
        return target
    }

    override fun close() {
        spare?.recycle()
        spare = null
        runCatching { codec.stop() }
        codec.release()
        extractor.release()
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
        /** Giri senza ingresso né uscita (fino a 20 ms l'uno) prima di considerare fermo il decoder. */
        private const val MAX_IDLE_LOOPS = 250
        /** Campioni letti dopo l'ultimo istante: coprono il riordino dei B-frame. */
        const val REORDER_SAMPLES = 16

        /** null se il video non si può decodificare così (traccia, rotazione, codec): si usa il retriever. */
        fun open(file: File, timesUs: LongArray, maxW: Int, maxH: Int): SequentialFrameReader? {
            if (timesUs.isEmpty()) return null
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            return try {
                extractor.setDataSource(file.absolutePath)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: throw IllegalStateException("Nessuna traccia video")
                val format = extractor.getTrackFormat(track)
                val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
                // il retriever raddrizza i video ruotati: restano a lui (le registrazioni dello schermo non lo sono)
                if (format.containsKey(MediaFormat.KEY_ROTATION) && format.getInteger(MediaFormat.KEY_ROTATION) != 0) {
                    throw UnsupportedOperationException("Video ruotato")
                }
                extractor.selectTrack(track)
                val chosen = closestSampleTimes(scanSampleTimes(extractor, timesUs.first(), timesUs.last()), timesUs)
                extractor.seekTo(timesUs.first(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val standard = if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) format.getInteger(MediaFormat.KEY_COLOR_STANDARD) else 0
                val full = format.containsKey(MediaFormat.KEY_COLOR_RANGE) && format.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                val decoder = MediaCodec.createDecoderByType(mime)
                codec = decoder
                decoder.configure(format, null, null, 0)
                decoder.start()
                SequentialFrameReader(extractor, decoder, chosen, maxW, maxH, standard, full)
            } catch (e: Exception) {
                Log.w(TAG, "Decodifica sequenziale non disponibile, si usa MediaMetadataRetriever", e)
                codec?.release()
                extractor.release()
                null
            }
        }

        /**
         * Tempi di presentazione dei campioni, ordinati, dal keyframe che precede [firstUs] fino a
         * [REORDER_SAMPLES] campioni dopo il primo con tempo >= [lastUs] (o alla fine del video).
         */
        private fun scanSampleTimes(extractor: MediaExtractor, firstUs: Long, lastUs: Long): LongArray {
            extractor.seekTo(firstUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var times = LongArray(256)
            var count = 0
            var after = -1
            while (after < REORDER_SAMPLES) {
                val t = extractor.sampleTime
                if (t < 0) break
                if (count == times.size) times = times.copyOf(count * 2)
                times[count++] = t
                if (after >= 0 || t >= lastUs) after++
                if (!extractor.advance()) break
            }
            check(count > 0) { "Nessun campione video" }
            return times.copyOf(count).also { it.sort() }
        }

        /**
         * Per ogni istante (µs) il tempo del campione più vicino fra [sortedUs], con la regola di
         * SampleTable::findSampleAtTime usata da OPTION_CLOSEST: a parità di distanza vince il
         * successivo; prima del primo campione il primo, dopo l'ultimo l'ultimo.
         */
        fun closestSampleTimes(sortedUs: LongArray, targetsUs: LongArray): LongArray = LongArray(targetsUs.size) { k ->
            val t = targetsUs[k]
            val found = sortedUs.binarySearch(t)
            val right = -found - 1 // primo campione con tempo > t
            when {
                found >= 0 -> sortedUs[found]
                right == sortedUs.size -> sortedUs[right - 1]
                right == 0 -> sortedUs[0]
                sortedUs[right] - t > t - sortedUs[right - 1] -> sortedUs[right - 1]
                else -> sortedUs[right]
            }
        }

        /** [srcW]x[srcH] adattato dentro [maxW]x[maxH] mantenendo le proporzioni, arrotondato come getScaledFrameAtTime. */
        fun fitInside(srcW: Int, srcH: Int, maxW: Int, maxH: Int): IntArray {
            val factor = minOf(maxW.toFloat() / srcW, maxH.toFloat() / srcH)
            return intArrayOf((srcW * factor).roundToInt().coerceAtLeast(1), (srcH * factor).roundToInt().coerceAtLeast(1))
        }

        /** Coefficienti YUV->RGB secondo gli aspetti colore del video; senza, BT.601 (il default del retriever). */
        private fun coefficientsOf(standard: Int, fullRange: Boolean): IntArray = when (standard) {
            MediaFormat.COLOR_STANDARD_BT709 -> YuvConverter.coefficients(0.2126, 0.0722, fullRange)
            MediaFormat.COLOR_STANDARD_BT2020 -> YuvConverter.coefficients(0.2627, 0.0593, fullRange)
            else -> YuvConverter.coefficients(0.299, 0.114, fullRange)
        }
    }
}

/** Conversione YUV 4:2:0 (piani come in YUV_420_888) -> ARGB con riduzione bilineare, in Kotlin puro. */
internal object YuvConverter {
    /**
     * Coefficienti in virgola fissa (x1024) dati Kr e Kb della norma (BT.601 0,299/0,114,
     * BT.709 0,2126/0,0722): [scala di Y, nero di Y, V->R, U->G, V->G, U->B].
     * Gamma limitata: Y in 16..235, U e V in 16..240.
     */
    fun coefficients(kr: Double, kb: Double, fullRange: Boolean): IntArray {
        val kg = 1.0 - kr - kb
        val c = if (fullRange) 1.0 else 255.0 / 224.0
        val ys = if (fullRange) 1.0 else 255.0 / 219.0
        return intArrayOf(
            (ys * 1024).roundToInt(),
            if (fullRange) 0 else 16,
            (2 * (1 - kr) * c * 1024).roundToInt(),
            (2 * kb * (1 - kb) / kg * c * 1024).roundToInt(),
            (2 * kr * (1 - kr) / kg * c * 1024).roundToInt(),
            (2 * (1 - kb) * c * 1024).roundToInt(),
        )
    }

    /**
     * Converte l'area visibile ([left], [top], [srcW]x[srcH]) dei piani in [out] ([dstW]x[dstH]
     * ARGB), campionando luma e crominanza in modo bilineare al centro di ogni pixel di
     * destinazione: equivale a convertire e poi ridurre con filtro bilineare, come fa il retriever.
     * U e V hanno lo stesso passo di riga e di pixel (garantito da YUV_420_888; 2 con NV12/NV21).
     */
    fun convert(
        y: ByteArray, yRowStride: Int, yPixelStride: Int,
        u: ByteArray, v: ByteArray, uvRowStride: Int, uvPixelStride: Int,
        left: Int, top: Int, srcW: Int, srcH: Int,
        dstW: Int, dstH: Int, coefficients: IntArray, out: IntArray,
    ) {
        val (ys, yBlack, vr, ug, vg) = coefficients
        val ub = coefficients[5]
        val lx0 = IntArray(dstW); val lx1 = IntArray(dstW); val lwx = IntArray(dstW)
        val cx0 = IntArray(dstW); val cx1 = IntArray(dstW); val cwx = IntArray(dstW)
        axis(dstW, srcW, left, lx0, lx1, lwx, cx0, cx1, cwx)
        val ly0 = IntArray(dstH); val ly1 = IntArray(dstH); val lwy = IntArray(dstH)
        val cy0 = IntArray(dstH); val cy1 = IntArray(dstH); val cwy = IntArray(dstH)
        axis(dstH, srcH, top, ly0, ly1, lwy, cy0, cy1, cwy)
        for (i in 0 until dstW) {
            lx0[i] *= yPixelStride; lx1[i] *= yPixelStride
            cx0[i] *= uvPixelStride; cx1[i] *= uvPixelStride
        }
        for (i in 0 until dstH) {
            ly0[i] *= yRowStride; ly1[i] *= yRowStride
            cy0[i] *= uvRowStride; cy1[i] *= uvRowStride
        }
        var o = 0
        for (dy in 0 until dstH) {
            val ra = ly0[dy]; val rb = ly1[dy]; val wy = lwy[dy]
            val ca = cy0[dy]; val cb = cy1[dy]; val wcy = cwy[dy]
            for (dx in 0 until dstW) {
                val xa = lx0[dx]; val xb = lx1[dx]; val wx = lwx[dx]
                val ua = cx0[dx]; val ubx = cx1[dx]; val wcx = cwx[dx]
                val l = (lerp(y, ra + xa, ra + xb, rb + xa, rb + xb, wx, wy) - yBlack) * ys
                val cu = lerp(u, ca + ua, ca + ubx, cb + ua, cb + ubx, wcx, wcy) - 128
                val cv = lerp(v, ca + ua, ca + ubx, cb + ua, cb + ubx, wcx, wcy) - 128
                val r = clamp((l + vr * cv + 512) shr 10)
                val g = clamp((l - ug * cu - vg * cv + 512) shr 10)
                val b = clamp((l + ub * cu + 512) shr 10)
                out[o++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /**
     * Per ogni pixel di destinazione lungo un asse: i due campioni di luma vicini con il peso
     * (0..256) e lo stesso per la crominanza, a metà risoluzione (il campione j copre i pixel
     * 2j e 2j+1, centro in 2j + 0,5). Indici assoluti nel piano, ancora da moltiplicare per il passo.
     */
    private fun axis(n: Int, srcN: Int, offset: Int, l0: IntArray, l1: IntArray, lw: IntArray, c0: IntArray, c1: IntArray, cw: IntArray) {
        val firstC = offset / 2
        val lastC = (offset + srcN - 1) / 2
        for (d in 0 until n) {
            val f = ((d + 0.5f) * srcN / n - 0.5f).coerceIn(0f, (srcN - 1).toFloat())
            val i = f.toInt()
            l0[d] = offset + i
            l1[d] = offset + minOf(i + 1, srcN - 1)
            lw[d] = ((f - i) * 256).toInt()
            val cf = ((offset + f - 0.5f) / 2f).coerceIn(firstC.toFloat(), lastC.toFloat())
            val j = cf.toInt()
            c0[d] = j
            c1[d] = minOf(j + 1, lastC)
            cw[d] = ((cf - j) * 256).toInt()
        }
    }

    private fun lerp(p: ByteArray, i00: Int, i01: Int, i10: Int, i11: Int, wx: Int, wy: Int): Int {
        val top = (p[i00].toInt() and 0xFF) * (256 - wx) + (p[i01].toInt() and 0xFF) * wx
        val bottom = (p[i10].toInt() and 0xFF) * (256 - wx) + (p[i11].toInt() and 0xFF) * wx
        return (top * (256 - wy) + bottom * wy + 32768) shr 16
    }

    private fun clamp(c: Int): Int = if (c < 0) 0 else if (c > 255) 255 else c
}
