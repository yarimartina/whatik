package com.whatik.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import com.whatik.image.CropSpec
import com.whatik.image.FrameInfo
import com.whatik.image.FrameProducer
import com.whatik.image.StaticStickerFinder
import com.whatik.image.StickerConverter
import com.whatik.image.StickerDetector
import com.whatik.image.VideoAnalyzer
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt

/**
 * Sequenza di fotogrammi catturati dallo schermo e salvati come JPEG con il loro istante.
 * Tiene in memoria il fotogramma 0 decodificato (lo ritagliano tutte le tessere ferme) e il
 * fotogramma a colori ridotto: [close] li libera. Non è thread-safe: va usata da un solo thread.
 */
class CapturedFrames(val files: List<File>, val timesMs: List<Long>) : Closeable {
    val width: Int
    val height: Int
    private var first: Bitmap? = null
    private var color: Triple<IntArray, Int, Int>? = null
    private var colorWidth = 0

    init {
        require(files.size == timesMs.size && files.isNotEmpty())
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(files[0].absolutePath, opts)
        width = opts.outWidth
        height = opts.outHeight
    }

    /** Dimensioni dei fotogrammi di [grayFrames], senza decodificare nulla. */
    fun grayDimensions(analysisWidth: Int = 160): Pair<Int, Int> =
        analysisWidth to (height.toFloat() * analysisWidth / width).roundToInt().coerceAtLeast(8)

    /** Fotogrammi in scala di grigi a bassa risoluzione per il rilevatore. */
    fun grayFrames(analysisWidth: Int = 160): Pair<List<IntArray>, Pair<Int, Int>> {
        val (aw, ah) = grayDimensions(analysisWidth)
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

    /** I fotogrammi grigi vivono solo qui dentro: dopo il rilevamento il GC li può liberare. */
    fun detect(params: StickerDetector.Params): List<StickerDetector.Proposal> {
        val (frames, dims) = grayFrames()
        return StickerDetector.detect(frames, dims.first, dims.second, timesMs, params)
    }

    fun producer(crop: CropSpec, startMs: Long, endMs: Long): FrameProducer = CapturedFrameProducer(this, crop, startMs, endMs)

    /**
     * Primo fotogramma a colori ridotto a ~[analysisWidth] px di larghezza: (pixel, larghezza, altezza).
     * Si decodifica una volta sola; i pixel restituiti sono condivisi e non vanno modificati.
     */
    fun colorFrame(analysisWidth: Int = 480): Triple<IntArray, Int, Int> {
        color?.let { if (colorWidth == analysisWidth) return it }
        var sample = 1
        while (width / (sample * 2) >= analysisWidth) sample *= 2
        // a piena risoluzione è lo stesso fotogramma 0 dei ritagli: basta una decodifica
        val bitmap = if (sample == 1) firstFrame() else {
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(files[0].absolutePath, opts)
                ?: throw StickerConverter.ConversionException("Fotogramma catturato non leggibile")
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val result = Triple(pixels, bitmap.width, bitmap.height)
        if (bitmap !== first) bitmap.recycle()
        color = result
        colorWidth = analysisWidth
        return result
    }

    /**
     * Riquadro di uno sticker fermo attorno al punto (in pixel del fotogramma), stimato sul primo
     * fotogramma ridotto.
     */
    fun staticCropAround(px: Int, py: Int): CropSpec {
        val (pixels, fw, fh) = colorFrame()
        val sx = px * fw / width
        val sy = py * fh / height
        val result = StaticStickerFinder.find(pixels, fw, fh, sx, sy)
        return StaticStickerFinder.toCrop(result.box, fw, fh)
    }

    /** Fotogramma [index] ritagliato a [crop]; il chiamante ne diventa proprietario. */
    internal fun cropped(index: Int, crop: CropSpec): Bitmap {
        if (REGION_DECODE) {
            val (_, _, cw, ch) = crop.toPixels(width, height)
            if (cw.toLong() * ch * REGION_MAX_SHARE <= width.toLong() * height) return decodeRegion(index, crop)
        }
        if (index == 0) {
            val frame = firstFrame()
            val out = crop.apply(frame)
            // con il ritaglio pieno crop.apply restituisce il fotogramma stesso, che resta qui
            return if (out !== frame) out else frame.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw StickerConverter.ConversionException("Fotogramma catturato non leggibile")
        }
        val raw = decode(index)
        val out = crop.apply(raw)
        if (out !== raw) raw.recycle()
        return out
    }

    private fun decode(index: Int): Bitmap =
        BitmapFactory.decodeFile(files[index].absolutePath)
            ?: throw StickerConverter.ConversionException("Fotogramma catturato non leggibile")

    private fun firstFrame(): Bitmap = first ?: decode(0).also { first = it }

    /**
     * Decodifica solo la regione del ritaglio. libjpeg-turbo ricampiona la crominanza della prima e
     * dell'ultima colonna della regione come se fossero i bordi dell'immagine (fino a 35 livelli di
     * differenza, misurato): allargando la regione di [REGION_MARGIN] colonne per lato, poi
     * scartate, i pixel restano identici alla decodifica intera.
     */
    private fun decodeRegion(index: Int, crop: CropSpec): Bitmap {
        val path = files[index].absolutePath
        val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(path)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(path, false)
        } ?: throw StickerConverter.ConversionException("Fotogramma catturato non leggibile")
        try {
            val (l, t, w, h) = crop.toPixels(decoder.width, decoder.height)
            val ml = minOf(REGION_MARGIN, l)
            val mr = minOf(REGION_MARGIN, decoder.width - l - w)
            val region = decoder.decodeRegion(Rect(l - ml, t, l + w + mr, t + h), null)
                ?: throw StickerConverter.ConversionException("Fotogramma catturato non leggibile")
            if (ml == 0 && mr == 0) return region
            val out = Bitmap.createBitmap(region, ml, 0, w, h)
            if (out !== region) region.recycle()
            return out
        } finally {
            decoder.recycle()
        }
    }

    /** Libera il fotogramma 0 e quello a colori; i file restano (vedi [delete]). */
    override fun close() {
        first?.recycle()
        first = null
        color = null
    }

    fun delete() {
        close()
        files.forEach { it.delete() }
    }

    companion object {
        /**
         * Decodifica della sola regione del ritaglio con [BitmapRegionDecoder] quando il ritaglio è al
         * massimo 1/[REGION_MAX_SHARE] della zona (una tessera di "Tutti"): 2-3 volte più veloce e
         * 0,3 MB invece di 4,9 MB per fotogramma. Spenta finché non è verificata sul telefono: i pixel
         * sono identici con libjpeg-turbo 2.1 sul PC, ma non è provato con Skia e le versioni di
         * libjpeg-turbo dei vari Android.
         */
        internal const val REGION_DECODE = false
        private const val REGION_MAX_SHARE = 4
        /** Una colonna basta (misurato); due per prudenza. */
        private const val REGION_MARGIN = 2
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
            val (_, _, cw, ch) = crop.toPixels(frames.width, frames.height)
            val durations = indices.mapIndexed { i, idx ->
                val next = if (i + 1 < indices.size) frames.timesMs[indices[i + 1]] else frames.timesMs[idx] + averageStep()
                (next - frames.timesMs[idx]).toInt().coerceAtLeast(StickerConverter.MIN_FRAME_MS)
            }
            return FrameInfo(cw, ch, durations)
        }

    private fun averageStep(): Long {
        if (frames.timesMs.size < 2) return 100
        return ((frames.timesMs.last() - frames.timesMs.first()) / (frames.timesMs.size - 1)).coerceAtLeast(1)
    }

    override fun produce(consume: (Int, Bitmap) -> Boolean) {
        for ((i, idx) in indices.withIndex()) {
            if (!consume(i, frames.cropped(idx, crop))) return
        }
    }
}
