package com.whatik.image

import java.io.ByteArrayOutputStream

/**
 * Scrittore di GIF per i test: LZW vero (dizionario che cresce, codici a larghezza variabile,
 * clear a tabella piena o clear differito), tabelle colori locali, interlacciamento, estensioni.
 * Serve a costruire GIF multi-fotogramma che esercitano tutti i percorsi di [GifDecoder].
 */
internal object GifTestEncoder {

    /**
     * @param indices indici colore in ordine visivo di riga (w * h); con [interlaced] sono riordinati qui
     * @param encodedPixels quanti pixel codificare davvero (meno di w * h = fine flusso anticipata)
     * @param keepBytes se >= 0, dei dati LZW si tengono solo questi byte e poi il terminatore (flusso troncato)
     * @param deferredClear a tabella piena non si emette il clear e si continua con la tabella piena
     */
    class Frame(
        val indices: ByteArray,
        val left: Int,
        val top: Int,
        val w: Int,
        val h: Int,
        val minCodeSize: Int,
        val delayCs: Int = 5,
        val disposal: Int = 0,
        val transparent: Int = -1,
        val interlaced: Boolean = false,
        val localPalette: IntArray? = null,
        val encodedPixels: Int = w * h,
        val keepBytes: Int = -1,
        val deferredClear: Boolean = false,
        val blockSize: Int = 255,
    )

    fun encode(width: Int, height: Int, globalPalette: IntArray?, frames: List<Frame>, backgroundIndex: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray())
        writeShort(out, width)
        writeShort(out, height)
        out.write((if (globalPalette != null) 0x80 or 0x70 or paletteBits(globalPalette.size) else 0x70))
        out.write(backgroundIndex)
        out.write(0)
        globalPalette?.let { writePalette(out, it) }
        // application extension (NETSCAPE loop) e commento: vanno saltati dal decoder
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 11))
        out.write("NETSCAPE2.0".toByteArray())
        out.write(byteArrayOf(3, 1, 0, 0, 0))
        out.write(byteArrayOf(0x21, 0xFE.toByte(), 5))
        out.write("whatk".toByteArray())
        out.write(0)
        for (f in frames) {
            out.write(0x21); out.write(0xF9); out.write(4)
            out.write((f.disposal shl 2) or (if (f.transparent >= 0) 1 else 0))
            writeShort(out, f.delayCs)
            out.write(if (f.transparent >= 0) f.transparent else 0)
            out.write(0)
            out.write(0x2C)
            writeShort(out, f.left); writeShort(out, f.top); writeShort(out, f.w); writeShort(out, f.h)
            val lct = f.localPalette
            out.write((if (lct != null) 0x80 or paletteBits(lct.size) else 0) or (if (f.interlaced) 0x40 else 0))
            lct?.let { writePalette(out, it) }
            out.write(f.minCodeSize)
            var data = lzw(storedOrder(f), f.encodedPixels, f.minCodeSize, f.deferredClear)
            if (f.keepBytes >= 0 && f.keepBytes < data.size) data = data.copyOf(f.keepBytes)
            var offset = 0
            while (offset < data.size) {
                val n = minOf(f.blockSize, data.size - offset)
                out.write(n)
                out.write(data, offset, n)
                offset += n
            }
            out.write(0)
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    /** Righe nell'ordine in cui le scrive un file interlacciato (0,8,16.. poi 4,12.. poi 2,6.. poi 1,3..). */
    private fun storedOrder(f: Frame): ByteArray {
        if (!f.interlaced) return f.indices
        val out = ByteArray(f.indices.size)
        var dst = 0
        for ((start, step) in listOf(0 to 8, 4 to 8, 2 to 4, 1 to 2)) {
            var row = start
            while (row < f.h) {
                System.arraycopy(f.indices, row * f.w, out, dst, f.w)
                dst += f.w
                row += step
            }
        }
        return out
    }

    private fun paletteBits(size: Int): Int {
        require(size in 2..256 && size and (size - 1) == 0) { "palette di $size colori" }
        return Integer.numberOfTrailingZeros(size) - 1
    }

    private fun writePalette(out: ByteArrayOutputStream, palette: IntArray) {
        for (c in palette) { out.write((c shr 16) and 0xFF); out.write((c shr 8) and 0xFF); out.write(c and 0xFF) }
    }

    private fun writeShort(out: ByteArrayOutputStream, v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

    /** LZW GIF con larghezza dei codici variabile (4096 voci al massimo). */
    fun lzw(indices: ByteArray, count: Int, minCodeSize: Int, deferredClear: Boolean = false): ByteArray {
        val clear = 1 shl minCodeSize
        val end = clear + 1
        val out = ByteArrayOutputStream()
        var bitBuffer = 0L
        var bitCount = 0
        fun emit(code: Int, width: Int) {
            bitBuffer = bitBuffer or (code.toLong() shl bitCount)
            bitCount += width
            while (bitCount >= 8) { out.write((bitBuffer and 0xFF).toInt()); bitBuffer = bitBuffer ushr 8; bitCount -= 8 }
        }
        // il decoder aggiunge le voci con un codice di ritardo: la larghezza segue nextCode dell'encoder
        fun widthFor(nextCode: Int): Int {
            var w = minCodeSize + 1
            while (nextCode > (1 shl w) && w < 12) w++
            return w
        }
        val dict = HashMap<Int, Int>()
        var nextCode = end + 1
        emit(clear, minCodeSize + 1)
        if (count > 0) {
            var prefix = indices[0].toInt() and 0xFF
            for (i in 1 until count) {
                val k = indices[i].toInt() and 0xFF
                val key = (prefix shl 8) or k
                val known = dict[key]
                if (known != null) { prefix = known; continue }
                emit(prefix, widthFor(nextCode))
                if (nextCode < 4096) {
                    dict[key] = nextCode++
                } else if (!deferredClear) {
                    emit(clear, 12)
                    dict.clear()
                    nextCode = end + 1
                }
                prefix = k
            }
            emit(prefix, widthFor(nextCode))
            if (nextCode < 4096) nextCode++
        }
        emit(end, widthFor(nextCode))
        if (bitCount > 0) out.write((bitBuffer and 0xFF).toInt())
        return out.toByteArray()
    }
}
