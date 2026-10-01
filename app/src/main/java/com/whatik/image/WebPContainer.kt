package com.whatik.image

import java.io.ByteArrayOutputStream

/**
 * Lettura e scrittura del contenitore WebP (RIFF) in Kotlin puro.
 *
 * Android sa codificare e decodificare singoli fotogrammi WebP ma non sa produrre
 * WebP animati: qui ricomponiamo l'animazione a mano, incapsulando ogni fotogramma
 * (già compresso da [android.graphics.Bitmap.compress]) in un chunk ANMF, e viceversa
 * estraiamo i fotogrammi di un WebP animato esistente in file WebP autonomi decodificabili.
 */
object WebPContainer {

    class Chunk(val fourCC: String, val payload: ByteArray) {
        /** Dimensione del record su disco: intestazione 8 byte + payload + eventuale padding. */
        val recordSize: Int get() = 8 + payload.size + (payload.size and 1)
    }

    class Frame(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val durationMs: Int,
        /** true = alpha-blend sul canvas precedente; false = sovrascrive. */
        val blendWithPrevious: Boolean,
        /** true = dopo il frame, l'area viene riportata a trasparente. */
        val disposeToBackground: Boolean,
        /** File WebP autonomo (statico) con il solo bitstream di questo frame. */
        val standalone: ByteArray,
    )

    class Parsed(
        val width: Int,
        val height: Int,
        val animated: Boolean,
        val hasAlpha: Boolean,
        val loopCount: Int,
        val frames: List<Frame>,
    ) {
        val totalDurationMs: Int get() = frames.sumOf { it.durationMs }
    }

    class FrameSpec(val standalone: ByteArray, val durationMs: Int)

    class WebPFormatException(message: String) : RuntimeException(message)

    private const val FLAG_ANIMATION = 0x02
    private const val FLAG_ALPHA = 0x10

    fun isWebP(bytes: ByteArray): Boolean =
        bytes.size >= 12 && fourCC(bytes, 0) == "RIFF" && fourCC(bytes, 8) == "WEBP"

    /** Controllo rapido del flag di animazione nel chunk VP8X. */
    fun isAnimated(bytes: ByteArray): Boolean {
        if (!isWebP(bytes) || bytes.size < 30) return false
        if (fourCC(bytes, 12) != "VP8X") return false
        return (bytes[20].toInt() and FLAG_ANIMATION) != 0
    }

    fun parse(bytes: ByteArray): Parsed {
        if (!isWebP(bytes)) throw WebPFormatException("Firma WebP non valida")
        val riffSize = readU32(bytes, 4)
        val end = minOf(bytes.size, 8 + riffSize)
        val chunks = readChunks(bytes, 12, end)
        if (chunks.isEmpty()) throw WebPFormatException("WebP senza chunk")

        val first = chunks[0]
        if (first.fourCC != "VP8X") {
            // formato semplice: un solo bitstream
            val dims = bitstreamDimensions(first)
            val standalone = wrapSimple(first)
            val alpha = first.fourCC == "VP8L" && vp8lHasAlpha(first.payload)
            return Parsed(dims.first, dims.second, false, alpha, 0, listOf(Frame(0, 0, dims.first, dims.second, 0, false, false, standalone)))
        }

        val flags = first.payload[0].toInt() and 0xFF
        val width = readU24(first.payload, 4) + 1
        val height = readU24(first.payload, 7) + 1
        val animated = flags and FLAG_ANIMATION != 0
        val hasAlpha = flags and FLAG_ALPHA != 0

        if (!animated) {
            val alph = chunks.firstOrNull { it.fourCC == "ALPH" }
            val bitstream = chunks.firstOrNull { it.fourCC == "VP8 " || it.fourCC == "VP8L" }
                ?: throw WebPFormatException("WebP senza bitstream immagine")
            val standalone = wrapStandalone(listOfNotNull(alph, bitstream), width, height)
            return Parsed(width, height, false, hasAlpha, 0, listOf(Frame(0, 0, width, height, 0, false, false, standalone)))
        }

        var loopCount = 0
        val frames = ArrayList<Frame>()
        for (chunk in chunks) {
            when (chunk.fourCC) {
                "ANIM" -> loopCount = readU16(chunk.payload, 4)
                "ANMF" -> frames.add(parseAnmf(chunk.payload))
            }
        }
        if (frames.isEmpty()) throw WebPFormatException("WebP animato senza fotogrammi")
        return Parsed(width, height, true, hasAlpha, loopCount, frames)
    }

    private fun parseAnmf(p: ByteArray): Frame {
        if (p.size < 16) throw WebPFormatException("Chunk ANMF troncato")
        val x = readU24(p, 0) * 2
        val y = readU24(p, 3) * 2
        val w = readU24(p, 6) + 1
        val h = readU24(p, 9) + 1
        val duration = readU24(p, 12)
        val flags = p[15].toInt() and 0xFF
        val noBlend = flags and 0x02 != 0
        val dispose = flags and 0x01 != 0
        val sub = readChunks(p, 16, p.size)
        val alph = sub.firstOrNull { it.fourCC == "ALPH" }
        val bitstream = sub.firstOrNull { it.fourCC == "VP8 " || it.fourCC == "VP8L" }
            ?: throw WebPFormatException("Fotogramma ANMF senza bitstream")
        val standalone = wrapStandalone(listOfNotNull(alph, bitstream), w, h)
        return Frame(x, y, w, h, duration, !noBlend, dispose, standalone)
    }

    /**
     * Estrae dal file WebP (statico) prodotto da Android i chunk che compongono il
     * bitstream del fotogramma: l'eventuale ALPH più VP8 oppure VP8L.
     */
    fun extractBitstreamChunks(standalone: ByteArray): List<Chunk> {
        if (!isWebP(standalone)) throw WebPFormatException("Il fotogramma non è un WebP")
        val riffSize = readU32(standalone, 4)
        val end = minOf(standalone.size, 8 + riffSize)
        val chunks = readChunks(standalone, 12, end)
        val result = ArrayList<Chunk>(2)
        chunks.firstOrNull { it.fourCC == "ALPH" }?.let { result.add(it) }
        val bitstream = chunks.firstOrNull { it.fourCC == "VP8 " || it.fourCC == "VP8L" }
            ?: throw WebPFormatException("Il fotogramma non contiene un bitstream VP8/VP8L")
        result.add(bitstream)
        return result
    }

    /**
     * Compone un WebP animato a partire da fotogrammi statici tutti di dimensione
     * [width]x[height] (il canvas). I fotogrammi sovrascrivono il precedente (nessun
     * blending) e non vengono eliminati, quindi ogni fotogramma è un'immagine completa.
     */
    fun muxAnimation(width: Int, height: Int, frames: List<FrameSpec>, loopCount: Int = 0): ByteArray {
        require(frames.size >= 2) { "Un'animazione richiede almeno 2 fotogrammi" }
        require(width in 1..(1 shl 24) && height in 1..(1 shl 24))

        val body = ByteArrayOutputStream()
        // VP8X
        val vp8x = ByteArray(10)
        vp8x[0] = (FLAG_ANIMATION or FLAG_ALPHA).toByte()
        writeU24(vp8x, 4, width - 1)
        writeU24(vp8x, 7, height - 1)
        writeChunk(body, "VP8X", vp8x)
        // ANIM: colore di sfondo trasparente, loop infinito (0)
        val anim = ByteArray(6)
        writeU16(anim, 4, loopCount)
        writeChunk(body, "ANIM", anim)
        // ANMF
        for (frame in frames) {
            val chunks = extractBitstreamChunks(frame.standalone)
            val header = ByteArray(16)
            writeU24(header, 0, 0)
            writeU24(header, 3, 0)
            writeU24(header, 6, width - 1)
            writeU24(header, 9, height - 1)
            writeU24(header, 12, frame.durationMs.coerceIn(1, (1 shl 24) - 1))
            header[15] = 0x02 // bit1 = non fondere (sovrascrivi), bit0 = 0 nessun dispose
            val payload = ByteArrayOutputStream(16 + chunks.sumOf { it.recordSize })
            payload.write(header)
            for (c in chunks) writeChunk(payload, c.fourCC, c.payload)
            writeChunk(body, "ANMF", payload.toByteArray())
        }
        return riff(body.toByteArray())
    }

    /** Incapsula i chunk di un fotogramma in un file WebP decodificabile da solo. */
    fun wrapStandalone(chunks: List<Chunk>, width: Int, height: Int): ByteArray {
        val hasAlph = chunks.any { it.fourCC == "ALPH" }
        val bitstream = chunks.first { it.fourCC == "VP8 " || it.fourCC == "VP8L" }
        if (!hasAlph) return wrapSimple(bitstream)
        val body = ByteArrayOutputStream()
        val vp8x = ByteArray(10)
        vp8x[0] = FLAG_ALPHA.toByte()
        writeU24(vp8x, 4, width - 1)
        writeU24(vp8x, 7, height - 1)
        writeChunk(body, "VP8X", vp8x)
        for (c in chunks) writeChunk(body, c.fourCC, c.payload)
        return riff(body.toByteArray())
    }

    private fun wrapSimple(bitstream: Chunk): ByteArray {
        val body = ByteArrayOutputStream()
        writeChunk(body, bitstream.fourCC, bitstream.payload)
        return riff(body.toByteArray())
    }

    private fun riff(body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(12 + body.size)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        val size = ByteArray(4)
        writeU32(size, 0, 4 + body.size)
        out.write(size)
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(body)
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, fourCC: String, payload: ByteArray) {
        out.write(fourCC.toByteArray(Charsets.US_ASCII))
        val size = ByteArray(4)
        writeU32(size, 0, payload.size)
        out.write(size)
        out.write(payload)
        if (payload.size and 1 == 1) out.write(0)
    }

    private fun readChunks(bytes: ByteArray, start: Int, end: Int): List<Chunk> {
        val chunks = ArrayList<Chunk>()
        var pos = start
        while (pos + 8 <= end) {
            val fourCC = fourCC(bytes, pos)
            val size = readU32(bytes, pos + 4)
            if (size < 0 || pos + 8 + size > end) {
                // chunk troncato: fermiamoci in modo tollerante
                break
            }
            chunks.add(Chunk(fourCC, bytes.copyOfRange(pos + 8, pos + 8 + size)))
            pos += 8 + size + (size and 1)
        }
        return chunks
    }

    /** Dimensioni dichiarate nell'intestazione del bitstream VP8 (key frame) o VP8L. */
    private fun bitstreamDimensions(chunk: Chunk): Pair<Int, Int> {
        val p = chunk.payload
        return when (chunk.fourCC) {
            "VP8 " -> {
                if (p.size < 10) throw WebPFormatException("Bitstream VP8 troncato")
                val w = ((p[6].toInt() and 0xFF) or ((p[7].toInt() and 0xFF) shl 8)) and 0x3FFF
                val h = ((p[8].toInt() and 0xFF) or ((p[9].toInt() and 0xFF) shl 8)) and 0x3FFF
                w to h
            }
            "VP8L" -> {
                if (p.size < 5) throw WebPFormatException("Bitstream VP8L troncato")
                val b1 = p[1].toInt() and 0xFF
                val b2 = p[2].toInt() and 0xFF
                val b3 = p[3].toInt() and 0xFF
                val b4 = p[4].toInt() and 0xFF
                val w = (b1 or ((b2 and 0x3F) shl 8)) + 1
                val h = ((b2 shr 6) or (b3 shl 2) or ((b4 and 0x0F) shl 10)) + 1
                w to h
            }
            else -> throw WebPFormatException("Chunk WebP sconosciuto: ${chunk.fourCC}")
        }
    }

    private fun vp8lHasAlpha(p: ByteArray): Boolean = p.size >= 5 && ((p[4].toInt() shr 4) and 1) == 1

    private fun fourCC(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, Charsets.US_ASCII)

    private fun readU16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun readU24(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16)

    private fun readU32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun writeU16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun writeU24(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v shr 8) and 0xFF).toByte()
        b[o + 2] = ((v shr 16) and 0xFF).toByte()
    }

    private fun writeU32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v shr 8) and 0xFF).toByte()
        b[o + 2] = ((v shr 16) and 0xFF).toByte()
        b[o + 3] = ((v shr 24) and 0xFF).toByte()
    }
}
