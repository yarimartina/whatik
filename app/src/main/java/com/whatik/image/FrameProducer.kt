package com.whatik.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.PorterDuff
import android.os.Build
import java.nio.ByteBuffer

class FrameInfo(val width: Int, val height: Int, val durationsMs: List<Int>) {
    val frameCount: Int get() = durationsMs.size
}

/**
 * Sorgente di fotogrammi già composti (ogni fotogramma è l'immagine completa).
 * I fotogrammi vengono prodotti in sequenza perché GIF e WebP animati dipendono
 * dal fotogramma precedente; il consumatore riceve un Bitmap di cui diventa proprietario.
 */
interface FrameProducer {
    val info: FrameInfo

    /** Chiama [consume] per ogni fotogramma; restituendo false si interrompe la decodifica. */
    fun produce(consume: (index: Int, frame: Bitmap) -> Boolean)

    companion object {
        /** Sceglie il produttore adatto in base al contenuto del file. */
        fun open(bytes: ByteArray): FrameProducer {
            return when (ImageFormat.sniff(bytes)) {
                ImageKind.GIF -> {
                    val info = GifDecoder.readInfo(bytes)
                    if (info.frameCount > 1) GifFrameProducer(bytes, info) else StaticFrameProducer(decodeStatic(bytes))
                }
                ImageKind.WEBP -> {
                    val parsed = WebPContainer.parse(bytes)
                    if (parsed.animated && parsed.frames.size > 1) WebPFrameProducer(parsed) else StaticFrameProducer(decodeStatic(bytes))
                }
                else -> StaticFrameProducer(decodeStatic(bytes))
            }
        }

        /** Primo fotogramma di qualunque immagine (per anteprime e per "animato -> statico"). */
        fun firstFrame(bytes: ByteArray): Bitmap {
            var result: Bitmap? = null
            open(bytes).produce { _, frame ->
                result = frame
                false
            }
            return result ?: throw IllegalStateException("Nessun fotogramma decodificato")
        }

        /** Decodifica un'immagine statica rispettando l'orientamento EXIF dove possibile. */
        fun decodeStatic(bytes: ByteArray): Bitmap {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            } else {
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }
            return bitmap ?: throw IllegalStateException("Immagine non decodificabile")
        }
    }
}

class StaticFrameProducer(private val bitmap: Bitmap) : FrameProducer {
    override val info = FrameInfo(bitmap.width, bitmap.height, listOf(0))
    override fun produce(consume: (Int, Bitmap) -> Boolean) {
        consume(0, bitmap)
    }
}

class GifFrameProducer(private val bytes: ByteArray, gifInfo: GifDecoder.Info) : FrameProducer {
    override val info = FrameInfo(gifInfo.width, gifInfo.height, gifInfo.delaysMs)

    override fun produce(consume: (Int, Bitmap) -> Boolean) {
        GifDecoder.decode(bytes) { frame ->
            val bitmap = Bitmap.createBitmap(frame.pixels, info.width, info.height, Bitmap.Config.ARGB_8888)
            consume(frame.index, bitmap)
        }
    }
}

class WebPFrameProducer(private val parsed: WebPContainer.Parsed) : FrameProducer {
    override val info = FrameInfo(parsed.width, parsed.height, parsed.frames.map { it.durationMs })

    override fun produce(consume: (Int, Bitmap) -> Boolean) {
        val canvasBitmap = Bitmap.createBitmap(parsed.width, parsed.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        try {
            for ((index, frame) in parsed.frames.withIndex()) {
                val frameBitmap = BitmapFactory.decodeByteArray(frame.standalone, 0, frame.standalone.size, options)
                    ?: throw IllegalStateException("Fotogramma WebP $index non decodificabile")
                val right = frame.x + frameBitmap.width
                val bottom = frame.y + frameBitmap.height
                if (frame.blendWithPrevious) {
                    canvas.drawBitmap(frameBitmap, frame.x.toFloat(), frame.y.toFloat(), null)
                } else {
                    canvas.save()
                    canvas.clipRect(frame.x, frame.y, right, bottom)
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    canvas.drawBitmap(frameBitmap, frame.x.toFloat(), frame.y.toFloat(), null)
                    canvas.restore()
                }
                frameBitmap.recycle()
                val snapshot = canvasBitmap.copy(Bitmap.Config.ARGB_8888, false)
                val keepGoing = consume(index, snapshot)
                if (frame.disposeToBackground) {
                    canvas.save()
                    canvas.clipRect(frame.x, frame.y, right, bottom)
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    canvas.restore()
                }
                if (!keepGoing) return
            }
        } finally {
            canvasBitmap.recycle()
        }
    }
}
