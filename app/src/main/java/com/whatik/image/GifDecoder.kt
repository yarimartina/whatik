package com.whatik.image

/**
 * Decoder GIF (87a/89a) scritto in Kotlin puro, senza dipendenze Android, così da
 * poter essere testato sulla JVM.
 *
 * Produce, frame per frame, il canvas completo già composto (ARGB, un Int per pixel)
 * rispettando trasparenza, interlacciamento e metodi di disposal, cioè quello che
 * serve per riconvertire una GIF animata in uno sticker WebP animato.
 */
class GifDecoder private constructor(private val data: ByteArray) {

    class Frame(val index: Int, val pixels: IntArray, val delayMs: Int)

    class Info(val width: Int, val height: Int, val delaysMs: List<Int>) {
        val frameCount: Int get() = delaysMs.size
        val totalDurationMs: Int get() = delaysMs.sum()
    }

    private var pos = 0

    private var width = 0
    private var height = 0
    private var globalColorTable: IntArray? = null
    private var backgroundIndex = 0

    // Stato del Graphic Control Extension corrente
    private var disposal = 0
    private var transparentIndex = -1
    private var delayMs = DEFAULT_DELAY_MS

    private fun readByte(): Int {
        if (pos >= data.size) throw GifFormatException("Fine inattesa del file GIF")
        return data[pos++].toInt() and 0xFF
    }

    private fun readShort(): Int {
        val lo = readByte()
        val hi = readByte()
        return lo or (hi shl 8)
    }

    private fun skip(n: Int) {
        if (pos + n > data.size) throw GifFormatException("Fine inattesa del file GIF")
        pos += n
    }

    private fun readHeader() {
        if (!isGif(data)) throw GifFormatException("Firma GIF non valida")
        pos = 6
        width = readShort()
        height = readShort()
        val packed = readByte()
        backgroundIndex = readByte()
        readByte() // pixel aspect ratio
        val hasGct = packed and 0x80 != 0
        val gctSize = 2 shl (packed and 0x07)
        if (hasGct) globalColorTable = readColorTable(gctSize)
        if (width <= 0 || height <= 0) throw GifFormatException("Dimensioni GIF non valide")
    }

    private fun readColorTable(size: Int): IntArray {
        val table = IntArray(size)
        for (i in 0 until size) {
            val r = readByte()
            val g = readByte()
            val b = readByte()
            table[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return table
    }

    private fun skipSubBlocks() {
        while (true) {
            val size = readByte()
            if (size == 0) return
            skip(size)
        }
    }

    private fun readGraphicControlExtension() {
        readByte() // block size (4)
        val packed = readByte()
        disposal = (packed shr 2) and 0x07
        val hasTransparency = packed and 0x01 != 0
        val delayCs = readShort()
        val index = readByte()
        transparentIndex = if (hasTransparency) index else -1
        // I browser trattano ritardi minimi (0 o 1 centesimo) come 100 ms: facciamo lo stesso,
        // altrimenti l'animazione risulterebbe un lampo illeggibile.
        val ms = delayCs * 10
        delayMs = if (ms < MIN_DELAY_MS) DEFAULT_DELAY_MS else ms
        skipSubBlocks()
    }

    private fun readExtension() {
        when (readByte()) {
            0xF9 -> readGraphicControlExtension()
            else -> skipSubBlocks() // commenti, testo, application extension (loop)
        }
    }

    /**
     * Legge un image descriptor e restituisce true se è stato prodotto un frame.
     * Con [decodePixels] = false salta la decodifica LZW (solo conteggio frame).
     */
    private fun readImage(state: CanvasState?, onFrame: ((Frame) -> Boolean)?, frameIndex: Int): Boolean {
        val left = readShort()
        val top = readShort()
        val w = readShort()
        val h = readShort()
        val packed = readByte()
        val hasLct = packed and 0x80 != 0
        val interlaced = packed and 0x40 != 0
        val lctSize = 2 shl (packed and 0x07)
        val colorTable = if (hasLct) readColorTable(lctSize) else globalColorTable
        val minCodeSize = readByte()

        if (state == null || onFrame == null) {
            skipSubBlocks()
            return true
        }

        val table = colorTable ?: throw GifFormatException("Nessuna tabella colori disponibile")
        val indices = decodeLzw(minCodeSize, w * h)
        if (w <= 0 || h <= 0) return true

        state.beforeFrame(disposal)
        state.draw(indices, left, top, w, h, interlaced, table, transparentIndex)
        val keepGoing = onFrame(Frame(frameIndex, state.canvas, delayMs))
        state.afterFrame(disposal, left, top, w, h)
        return keepGoing
    }

    /** Decodifica LZW variabile come da specifica GIF; restituisce gli indici colore. */
    private fun decodeLzw(minCodeSize: Int, pixelCount: Int): ByteArray {
        if (minCodeSize < 2 || minCodeSize > 11) throw GifFormatException("Code size LZW non valido: $minCodeSize")
        val out = ByteArray(pixelCount)
        val clearCode = 1 shl minCodeSize
        val endCode = clearCode + 1
        var codeSize = minCodeSize + 1
        var codeMask = (1 shl codeSize) - 1
        var nextCode = endCode + 1

        val prefix = IntArray(MAX_CODES)
        val suffix = ByteArray(MAX_CODES)
        val stack = ByteArray(MAX_CODES + 1)
        for (i in 0 until clearCode) {
            prefix[i] = -1
            suffix[i] = i.toByte()
        }

        var outPos = 0
        var bitBuffer = 0L
        var bitCount = 0
        var blockRemaining = 0
        var prevCode = -1
        var firstByte = 0
        var stackTop = 0
        var finished = false
        var terminatorConsumed = false

        while (outPos < pixelCount && !finished) {
            // riempi il buffer di bit
            while (bitCount < codeSize) {
                if (blockRemaining == 0) {
                    blockRemaining = readByte()
                    if (blockRemaining == 0) {
                        // Terminatore raggiunto prima della fine dei dati: i pixel mancanti restano 0
                        terminatorConsumed = true
                        finished = true
                        break
                    }
                }
                bitBuffer = bitBuffer or (readByte().toLong() shl bitCount)
                bitCount += 8
                blockRemaining--
            }
            if (finished) break

            var code = (bitBuffer and codeMask.toLong()).toInt()
            bitBuffer = bitBuffer ushr codeSize
            bitCount -= codeSize

            if (code == clearCode) {
                codeSize = minCodeSize + 1
                codeMask = (1 shl codeSize) - 1
                nextCode = endCode + 1
                prevCode = -1
                continue
            }
            if (code == endCode) {
                finished = true
                break
            }

            if (prevCode == -1) {
                // primo codice dopo un clear: è sempre un codice radice
                if (code >= clearCode) throw GifFormatException("Flusso LZW corrotto")
                out[outPos++] = suffix[code]
                prevCode = code
                firstByte = code
                continue
            }

            val inCode = code
            if (code >= nextCode) {
                // caso KwKwK: il codice non è ancora nel dizionario
                if (code > nextCode) throw GifFormatException("Flusso LZW corrotto (codice $code > $nextCode)")
                stack[stackTop++] = firstByte.toByte()
                code = prevCode
            }
            while (code >= clearCode) {
                stack[stackTop++] = suffix[code]
                code = prefix[code]
            }
            firstByte = suffix[code].toInt() and 0xFF
            stack[stackTop++] = firstByte.toByte()

            // aggiungi la nuova voce al dizionario
            if (nextCode < MAX_CODES) {
                prefix[nextCode] = prevCode
                suffix[nextCode] = firstByte.toByte()
                nextCode++
                if (nextCode and codeMask == 0 && nextCode < MAX_CODES) {
                    codeSize++
                    codeMask = (1 shl codeSize) - 1
                }
            }
            prevCode = inCode

            while (stackTop > 0 && outPos < pixelCount) {
                out[outPos++] = stack[--stackTop]
            }
            stackTop = 0
        }

        // Consuma i sotto-blocchi residui fino al terminatore (se non è già stato letto)
        if (!terminatorConsumed) {
            skip(blockRemaining)
            skipSubBlocks()
        }
        return out
    }

    private inner class CanvasState {
        val canvas = IntArray(width * height)
        private var saved: IntArray? = null
        private var prevDisposal = 0
        private var prevRect = intArrayOf(0, 0, 0, 0)

        fun beforeFrame(nextDisposal: Int) {
            when (prevDisposal) {
                2 -> clearRect(prevRect[0], prevRect[1], prevRect[2], prevRect[3])
                3 -> saved?.let { System.arraycopy(it, 0, canvas, 0, canvas.size) }
            }
            if (nextDisposal == 3) {
                saved = (saved ?: IntArray(canvas.size)).also { System.arraycopy(canvas, 0, it, 0, canvas.size) }
            }
        }

        fun afterFrame(disposal: Int, left: Int, top: Int, w: Int, h: Int) {
            prevDisposal = disposal
            prevRect = intArrayOf(left, top, w, h)
        }

        private fun clearRect(left: Int, top: Int, w: Int, h: Int) {
            val x0 = left.coerceIn(0, width)
            val y0 = top.coerceIn(0, height)
            val x1 = (left + w).coerceIn(0, width)
            val y1 = (top + h).coerceIn(0, height)
            for (y in y0 until y1) {
                java.util.Arrays.fill(canvas, y * width + x0, y * width + x1, 0)
            }
        }

        fun draw(
            indices: ByteArray, left: Int, top: Int, w: Int, h: Int,
            interlaced: Boolean, table: IntArray, transparent: Int,
        ) {
            for (row in 0 until h) {
                val targetRow = if (interlaced) interlacedRow(row, h) else row
                val y = top + targetRow
                if (y < 0 || y >= height) continue
                val srcBase = row * w
                val dstBase = y * width
                for (col in 0 until w) {
                    val x = left + col
                    if (x < 0 || x >= width) continue
                    val idx = indices[srcBase + col].toInt() and 0xFF
                    if (idx == transparent) continue
                    if (idx >= table.size) continue
                    canvas[dstBase + x] = table[idx]
                }
            }
        }

        private fun interlacedRow(row: Int, h: Int): Int {
            // passate: righe 0,8,16.. poi 4,12.. poi 2,6,10.. poi 1,3,5..
            val pass1 = (h + 7) / 8
            val pass2 = (h + 3) / 8
            val pass3 = (h + 1) / 4
            return when {
                row < pass1 -> row * 8
                row < pass1 + pass2 -> (row - pass1) * 8 + 4
                row < pass1 + pass2 + pass3 -> (row - pass1 - pass2) * 4 + 2
                else -> (row - pass1 - pass2 - pass3) * 2 + 1
            }
        }
    }

    private fun run(decodePixels: Boolean, onFrame: ((Frame) -> Boolean)?): Info {
        readHeader()
        val state = if (decodePixels) CanvasState() else null
        val delays = ArrayList<Int>()
        var frameIndex = 0
        loop@ while (pos < data.size) {
            when (readByte()) {
                0x21 -> readExtension()
                0x2C -> {
                    val delay = delayMs
                    val keepGoing = readImage(state, onFrame, frameIndex)
                    delays.add(delay)
                    frameIndex++
                    // Il Graphic Control Extension vale per una sola immagine
                    disposal = 0
                    transparentIndex = -1
                    delayMs = DEFAULT_DELAY_MS
                    if (!keepGoing) break@loop
                    if (frameIndex >= MAX_FRAMES) break@loop
                }
                0x3B -> break@loop
                else -> {
                    // Byte spurio: alcuni file hanno padding, fermiamoci qui in modo tollerante
                    break@loop
                }
            }
        }
        if (delays.isEmpty()) throw GifFormatException("La GIF non contiene immagini")
        return Info(width, height, delays)
    }

    class GifFormatException(message: String) : RuntimeException(message)

    companion object {
        private const val MAX_CODES = 4096
        private const val MAX_FRAMES = 1000
        private const val DEFAULT_DELAY_MS = 100
        private const val MIN_DELAY_MS = 20

        fun isGif(bytes: ByteArray): Boolean =
            bytes.size >= 6 &&
                bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() &&
                bytes[3] == '8'.code.toByte() && (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
                bytes[5] == 'a'.code.toByte()

        /** Legge solo la struttura (senza decodificare i pixel): dimensioni, numero di frame e ritardi. */
        fun readInfo(bytes: ByteArray): Info = GifDecoder(bytes).run(decodePixels = false, onFrame = null)

        /**
         * Decodifica tutti i frame in sequenza. Il canvas passato a [onFrame] è un buffer
         * riutilizzato: va copiato se serve conservarlo. Restituendo false si interrompe.
         */
        fun decode(bytes: ByteArray, onFrame: (Frame) -> Boolean): Info =
            GifDecoder(bytes).run(decodePixels = true, onFrame = onFrame)
    }
}
