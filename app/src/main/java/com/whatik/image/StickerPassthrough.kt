package com.whatik.image

/**
 * Riconosce gli sticker già conformi ai requisiti di WhatsApp per un pack del tipo dato.
 * Per questi l'export e l'aggiunta ai pack copiano i byte originali invece di decodificare
 * e ricodificare ogni fotogramma: è molto più veloce e non si perde qualità a ogni giro.
 *
 * Tutto si verifica sui byte del contenitore, in Kotlin puro (con [WebPContainer]). Basta un
 * requisito non rispettato e lo sticker passa dalla conversione di sempre.
 */
object StickerPassthrough {
    /** Fotogrammi al massimo: come il primo tentativo di [StickerConverter] (INITIAL_MAX_FRAMES). */
    const val MAX_FRAMES = 100

    private const val FLAG_ANIMATION = 0x02
    private const val FLAG_ALPHA = 0x10

    /** Posizione di un chunk nel file: [offset] è l'inizio del payload. */
    private class ChunkRef(val fourCC: String, val offset: Int, val size: Int)

    /**
     * Sticker pronto per un pack del tipo dato: i byte originali se già conformi, altrimenti
     * la conversione di [StickerConverter.convertForPack]. I tipi misti (fermo in un pack
     * animato, animato in un pack statico) non sono mai conformi e restano alla conversione.
     */
    fun prepareForPack(bytes: ByteArray, packAnimated: Boolean): StickerConverter.Result {
        val parsed = check(bytes, packAnimated) ?: return StickerConverter.convertForPack(bytes, packAnimated)
        // qualità 100: nessuna perdita aggiunta (come in animateStill)
        return StickerConverter.Result(bytes, packAnimated, parsed.frames.size, quality = 100)
    }

    /** I byte invariati se lo sticker rispetta già ogni requisito del pack, altrimenti null. */
    fun compliantBytes(bytes: ByteArray, packAnimated: Boolean): ByteArray? =
        if (check(bytes, packAnimated) != null) bytes else null

    private fun check(bytes: ByteArray, packAnimated: Boolean): WebPContainer.Parsed? {
        val maxBytes = if (packAnimated) StickerConverter.MAX_ANIMATED_BYTES else StickerConverter.MAX_STATIC_BYTES
        if (bytes.size > maxBytes || !WebPContainer.isWebP(bytes)) return null
        // contenitore integro: la dimensione RIFF è quella del file e i chunk lo coprono tutto
        if (8L + readU32(bytes, 4) != bytes.size.toLong()) return null
        val chunks = chunkTable(bytes, 12, bytes.size)
        if (chunks.isNullOrEmpty()) return null
        return try {
            val parsed = WebPContainer.parse(bytes)
            val ok = parsed.width == StickerConverter.STICKER_SIZE && parsed.height == StickerConverter.STICKER_SIZE &&
                parsed.animated == packAnimated &&
                if (packAnimated) isCompliantAnimated(bytes, chunks, parsed) else isCompliantStatic(bytes, chunks, parsed)
            if (ok) parsed else null
        } catch (e: RuntimeException) {
            null // contenitore malformato: ci pensa la conversione, con il suo messaggio d'errore
        }
    }

    /** Formato semplice (un solo bitstream) oppure VP8X senza animazione né metadati. */
    private fun isCompliantStatic(bytes: ByteArray, chunks: List<ChunkRef>, parsed: WebPContainer.Parsed): Boolean {
        if (parsed.frames.size != 1) return false
        if (chunks[0].fourCC != "VP8X") return chunks.size == 1 && isImageData(bytes, chunks)
        return validFlags(bytes, chunks[0], animated = false) && isImageData(bytes, chunks.drop(1))
    }

    /** VP8X, un ANIM e da 2 a [MAX_FRAMES] ANMF a tutto canvas, entro i limiti di durata. */
    private fun isCompliantAnimated(bytes: ByteArray, chunks: List<ChunkRef>, parsed: WebPContainer.Parsed): Boolean {
        if (chunks[0].fourCC != "VP8X" || !validFlags(bytes, chunks[0], animated = true)) return false
        if (chunks.getOrNull(1)?.fourCC != "ANIM" || chunks.drop(2).any { it.fourCC != "ANMF" }) return false
        // loop infinito, come quello scritto da muxAnimation
        if (parsed.loopCount != 0) return false
        val anmf = chunks.drop(2)
        if (anmf.size != parsed.frames.size || parsed.frames.size !in 2..MAX_FRAMES) return false
        if (parsed.totalDurationMs > StickerConverter.MAX_DURATION_MS) return false
        val size = StickerConverter.STICKER_SIZE
        for ((ref, frame) in anmf.zip(parsed.frames)) {
            // ogni fotogramma copre tutto il canvas, come quelli del convertitore (l'icona del pack nasce dal primo)
            if (frame.x != 0 || frame.y != 0 || frame.width != size || frame.height != size) return false
            if (frame.durationMs < StickerConverter.MIN_FRAME_MS) return false
            val sub = chunkTable(bytes, ref.offset + 16, ref.offset + ref.size) ?: return false
            if (!isImageData(bytes, sub)) return false
        }
        return true
    }

    /** Dati di un'immagine: un ALPH facoltativo (solo con VP8 lossy) e un unico bitstream 512x512. */
    private fun isImageData(bytes: ByteArray, chunks: List<ChunkRef>): Boolean {
        val bitstream = chunks.lastOrNull() ?: return false
        val layoutOk = when (chunks.size) {
            1 -> true
            2 -> chunks[0].fourCC == "ALPH" && bitstream.fourCC == "VP8 "
            else -> false
        }
        return layoutOk && validBitstreamHeader(bytes, bitstream) && hasStickerSize(bytes, bitstream)
    }

    /** VP8X senza flag sconosciuti o di metadati (ICC, EXIF, XMP): solo animazione, se richiesta, e alpha. */
    private fun validFlags(bytes: ByteArray, vp8x: ChunkRef, animated: Boolean): Boolean {
        if (vp8x.size < 10) return false
        val flags = bytes[vp8x.offset].toInt() and 0xFF
        if (flags and (FLAG_ANIMATION or FLAG_ALPHA).inv() != 0) return false
        return (flags and FLAG_ANIMATION != 0) == animated
    }

    /** Intestazione del bitstream: start code di un key frame VP8, oppure firma e versione VP8L. */
    private fun validBitstreamHeader(bytes: ByteArray, chunk: ChunkRef): Boolean {
        val o = chunk.offset
        return when (chunk.fourCC) {
            "VP8 " -> chunk.size >= 10 && bytes[o].toInt() and 0x01 == 0 &&
                bytes[o + 3] == 0x9D.toByte() && bytes[o + 4] == 0x01.toByte() && bytes[o + 5] == 0x2A.toByte()
            "VP8L" -> chunk.size >= 5 && bytes[o] == 0x2F.toByte() && (bytes[o + 4].toInt() and 0xFF) shr 5 == 0
            else -> false
        }
    }

    /** Le dimensioni scritte nel bitstream (non solo quelle dichiarate da VP8X o ANMF) sono 512x512. */
    private fun hasStickerSize(bytes: ByteArray, bitstream: ChunkRef): Boolean {
        val chunk = WebPContainer.Chunk(bitstream.fourCC, bytes.copyOfRange(bitstream.offset, bitstream.offset + bitstream.size))
        // senza ALPH, wrapStandalone produce il formato semplice e parse legge le dimensioni dal bitstream
        val bare = WebPContainer.parse(WebPContainer.wrapStandalone(listOf(chunk), 1, 1))
        return bare.width == StickerConverter.STICKER_SIZE && bare.height == StickerConverter.STICKER_SIZE
    }

    /** Chunk che coprono esattamente [start, end), padding compreso; null se troncati o incoerenti. */
    private fun chunkTable(bytes: ByteArray, start: Int, end: Int): List<ChunkRef>? {
        val chunks = ArrayList<ChunkRef>()
        var pos = start
        while (pos < end) {
            if (pos + 8 > end) return null
            val size = readU32(bytes, pos + 4)
            if (size < 0) return null
            val next = pos + 8L + size + (size and 1)
            if (next > end) return null
            chunks.add(ChunkRef(String(bytes, pos, 4, Charsets.US_ASCII), pos + 8, size))
            pos = next.toInt()
        }
        return chunks
    }

    private fun readU32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
