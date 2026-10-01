package com.whatik.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * Converte qualunque immagine (PNG, JPEG, GIF, WebP, anche animati) in uno sticker
 * conforme ai requisiti di WhatsApp:
 *  - 512x512 pixel, formato WebP
 *  - statico <= 100 KB, animato <= 500 KB
 *  - animazione <= 10 secondi, fotogrammi >= 8 ms
 *  - icona del pack 96x96 PNG <= 50 KB
 */
object StickerConverter {
    const val STICKER_SIZE = 512
    const val TRAY_SIZE = 96
    const val MAX_STATIC_BYTES = 100 * 1024
    const val MAX_ANIMATED_BYTES = 500 * 1024
    const val MAX_TRAY_BYTES = 50 * 1024
    const val MAX_DURATION_MS = 10_000
    const val MIN_FRAME_MS = 8

    /** Sotto questa soglia il ritardo viene normalizzato a [DEFAULT_FRAME_MS], come fanno i browser. */
    private const val SHORT_FRAME_MS = 20
    private const val DEFAULT_FRAME_MS = 100
    private const val INITIAL_MAX_FRAMES = 100
    private const val MAX_ATTEMPTS = 9

    class Result(val bytes: ByteArray, val animated: Boolean, val frameCount: Int, val quality: Int)

    class ConversionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    fun convert(bytes: ByteArray, forceStatic: Boolean): Result {
        val producer = try {
            FrameProducer.open(bytes)
        } catch (e: Exception) {
            throw ConversionException("Immagine non riconosciuta o danneggiata", e)
        }
        return convert(producer, forceStatic)
    }

    /** Converte qualunque sorgente di fotogrammi (immagine, GIF/WebP animato, video ritagliato). */
    fun convert(producer: FrameProducer, forceStatic: Boolean): Result =
        if (forceStatic || producer.info.frameCount < 2) convertStatic(producer) else convertAnimated(producer)

    // ---------------------------------------------------------------- statico

    private fun convertStatic(producer: FrameProducer): Result {
        var source: Bitmap? = null
        producer.produce { _, frame ->
            source = frame
            false
        }
        val fitted = fitToCanvas(source ?: throw ConversionException("Nessun fotogramma"), STICKER_SIZE)
        source?.recycle()
        try {
            val lossless = encodeWebP(fitted, 100, lossless = true)
            if (lossless.size <= MAX_STATIC_BYTES) return Result(lossless, false, 1, 100)
            for (quality in intArrayOf(90, 80, 70, 60, 50, 40, 30, 20)) {
                val lossy = encodeWebP(fitted, quality, lossless = false)
                if (lossy.size <= MAX_STATIC_BYTES) return Result(lossy, false, 1, quality)
            }
            throw ConversionException("Impossibile ridurre lo sticker sotto i 100 KB")
        } finally {
            fitted.recycle()
        }
    }

    // ---------------------------------------------------------------- animato

    private fun convertAnimated(producer: FrameProducer): Result {
        val normalized = producer.info.durationsMs.map { if (it < SHORT_FRAME_MS) DEFAULT_FRAME_MS else it }
        // Taglia l'animazione ai 10 secondi consentiti (almeno 2 fotogrammi)
        var total = 0
        var trimmedCount = 0
        for (d in normalized) {
            if (total + d > MAX_DURATION_MS && trimmedCount >= 2) break
            total += d
            trimmedCount++
        }
        val durations = normalized.take(trimmedCount).toMutableList()
        if (durations.sum() > MAX_DURATION_MS) {
            // caso limite: pochissimi fotogrammi lunghissimi
            val scale = MAX_DURATION_MS.toDouble() / durations.sum()
            for (i in durations.indices) durations[i] = (durations[i] * scale).toInt().coerceAtLeast(MIN_FRAME_MS)
        }

        var maxFrames = minOf(trimmedCount, INITIAL_MAX_FRAMES)
        var quality = 80
        var lastSize = -1
        repeat(MAX_ATTEMPTS) {
            val kept = selectFrames(trimmedCount, maxFrames)
            val keptDurations = mergeDurations(durations, kept)
            val encoded = encodePass(producer, kept, quality)
            val specs = encoded.mapIndexed { i, frameBytes -> WebPContainer.FrameSpec(frameBytes, keptDurations[i]) }
            val muxed = WebPContainer.muxAnimation(STICKER_SIZE, STICKER_SIZE, specs)
            if (muxed.size <= MAX_ANIMATED_BYTES) return Result(muxed, true, kept.size, quality)
            lastSize = muxed.size
            val ratio = MAX_ANIMATED_BYTES * 0.92 / muxed.size
            if (ratio >= 0.6 && quality > 35) {
                quality = (quality - 20).coerceAtLeast(30)
            } else {
                maxFrames = (kept.size * ratio * 0.9).toInt().coerceIn(4, kept.size - 1)
                if (quality > 50) quality = 50
            }
        }
        throw ConversionException("Animazione troppo pesante anche dopo la riduzione (${lastSize / 1024} KB)")
    }

    /** Indici dei fotogrammi da tenere, distribuiti uniformemente, sempre a partire dal primo. */
    internal fun selectFrames(count: Int, maxFrames: Int): List<Int> {
        if (count <= maxFrames) return (0 until count).toList()
        return (0 until maxFrames).map { it * count / maxFrames }.distinct()
    }

    /** La durata di un fotogramma tenuto assorbe quella dei fotogrammi scartati che lo seguono. */
    internal fun mergeDurations(durations: List<Int>, kept: List<Int>): List<Int> {
        val result = ArrayList<Int>(kept.size)
        for ((i, start) in kept.withIndex()) {
            val end = if (i + 1 < kept.size) kept[i + 1] else durations.size
            result.add(durations.subList(start, end).sum().coerceAtLeast(MIN_FRAME_MS))
        }
        return result
    }

    private fun encodePass(producer: FrameProducer, kept: List<Int>, quality: Int): List<ByteArray> {
        val keptSet = kept.toHashSet()
        val lastKept = kept.last()
        val out = ArrayList<ByteArray>(kept.size)
        producer.produce { index, frame ->
            if (index in keptSet) {
                val fitted = fitToCanvas(frame, STICKER_SIZE)
                try {
                    out.add(encodeWebP(fitted, quality, lossless = false))
                } finally {
                    fitted.recycle()
                }
            }
            frame.recycle()
            index < lastKept
        }
        if (out.size != kept.size) throw ConversionException("Decodificati ${out.size} fotogrammi su ${kept.size}")
        return out
    }

    // ---------------------------------------------------------------- icona

    /** Icona 96x96 PNG ricavata dal primo fotogramma di uno sticker WebP già convertito. */
    fun makeTrayIcon(stickerWebP: ByteArray): ByteArray {
        val parsed = WebPContainer.parse(stickerWebP)
        val first = parsed.frames.first().standalone
        val bitmap = BitmapFactory.decodeByteArray(first, 0, first.size)
            ?: throw ConversionException("Impossibile generare l'icona del pack")
        val fitted = fitToCanvas(bitmap, TRAY_SIZE)
        bitmap.recycle()
        try {
            val png = ByteArrayOutputStream()
            fitted.compress(Bitmap.CompressFormat.PNG, 100, png)
            val bytes = png.toByteArray()
            if (bytes.size > MAX_TRAY_BYTES) throw ConversionException("Icona del pack troppo grande")
            return bytes
        } finally {
            fitted.recycle()
        }
    }

    // ---------------------------------------------------------------- utilità

    /** Ridimensiona mantenendo le proporzioni e centra su un canvas quadrato trasparente. */
    fun fitToCanvas(source: Bitmap, size: Int): Bitmap {
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val scale = minOf(size.toFloat() / source.width, size.toFloat() / source.height)
        val w = (source.width * scale).coerceAtLeast(1f)
        val h = (source.height * scale).coerceAtLeast(1f)
        val left = (size - w) / 2f
        val top = (size - h) / 2f
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(source, Rect(0, 0, source.width, source.height), RectF(left, top, left + w, top + h), paint)
        return result
    }

    private fun encodeWebP(bitmap: Bitmap, quality: Int, lossless: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP // qualità 100 = lossless sulle versioni precedenti
        }
        val q = if (lossless) 100 else quality.coerceIn(0, 99)
        if (!bitmap.compress(format, q, out)) throw ConversionException("Codifica WebP fallita")
        return out.toByteArray()
    }
}
