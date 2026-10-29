package com.whatik.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import kotlin.random.Random

class GifDecoderTest {

    private val palette = intArrayOf(0xFF000000.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())

    /** Scrive una GIF 2x2 con 4 colori globali e i frame indicati (indici, ritardo in cs, indice trasparente, disposal). */
    private fun buildGif(frames: List<TestFrame>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray())
        writeShort(out, 2); writeShort(out, 2)
        out.write(0x80 or 0x01) // GCT presente, 4 colori
        out.write(0); out.write(0)
        for (c in palette) { out.write((c shr 16) and 0xFF); out.write((c shr 8) and 0xFF); out.write(c and 0xFF) }
        for (f in frames) {
            // Graphic Control Extension
            out.write(0x21); out.write(0xF9); out.write(4)
            val packed = (f.disposal shl 2) or (if (f.transparent >= 0) 1 else 0)
            out.write(packed)
            writeShort(out, f.delayCs)
            out.write(if (f.transparent >= 0) f.transparent else 0)
            out.write(0)
            // Image descriptor
            out.write(0x2C)
            writeShort(out, f.left); writeShort(out, f.top); writeShort(out, f.w); writeShort(out, f.h)
            out.write(0) // nessuna LCT, non interlacciata
            out.write(2) // min code size
            val data = lzwLiteral(f.pixels, 2)
            out.write(data.size); out.write(data); out.write(0)
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    /** Codifica "ingenua": CLEAR prima di ogni pixel, così i codici restano a larghezza fissa. */
    private fun lzwLiteral(pixels: IntArray, minCodeSize: Int): ByteArray {
        val clear = 1 shl minCodeSize
        val end = clear + 1
        val codeSize = minCodeSize + 1
        val codes = ArrayList<Int>()
        for (p in pixels) { codes.add(clear); codes.add(p) }
        codes.add(end)
        val out = ByteArrayOutputStream()
        var buffer = 0L
        var bits = 0
        for (c in codes) {
            buffer = buffer or (c.toLong() shl bits)
            bits += codeSize
            while (bits >= 8) { out.write((buffer and 0xFF).toInt()); buffer = buffer ushr 8; bits -= 8 }
        }
        if (bits > 0) out.write((buffer and 0xFF).toInt())
        return out.toByteArray()
    }

    private fun writeShort(out: ByteArrayOutputStream, v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

    class TestFrame(val pixels: IntArray, val delayCs: Int, val transparent: Int = -1, val disposal: Int = 0,
                    val left: Int = 0, val top: Int = 0, val w: Int = 2, val h: Int = 2)

    @Test
    fun isGif_recognisesSignature() {
        assertTrue(GifDecoder.isGif("GIF89a".toByteArray()))
        assertTrue(GifDecoder.isGif("GIF87a".toByteArray()))
        assertFalse(GifDecoder.isGif("RIFF".toByteArray()))
    }

    @Test
    fun readInfo_countsFramesAndDelays() {
        val gif = buildGif(listOf(TestFrame(intArrayOf(0, 1, 2, 3), 50), TestFrame(intArrayOf(3, 2, 1, 0), 0)))
        val info = GifDecoder.readInfo(gif)
        assertEquals(2, info.width)
        assertEquals(2, info.height)
        assertEquals(2, info.frameCount)
        assertEquals(listOf(500, 100), info.delaysMs) // 0 cs -> 100 ms come nei browser
    }

    @Test
    fun decode_producesCompositedFrames() {
        val gif = buildGif(listOf(TestFrame(intArrayOf(0, 1, 2, 3), 10), TestFrame(intArrayOf(3, 2, 1, 0), 10)))
        val frames = ArrayList<IntArray>()
        val delays = ArrayList<Int>()
        GifDecoder.decode(gif) { f -> frames.add(f.pixels.copyOf()); delays.add(f.delayMs); true }
        assertEquals(2, frames.size)
        assertArrayEquals(intArrayOf(palette[0], palette[1], palette[2], palette[3]), frames[0])
        assertArrayEquals(intArrayOf(palette[3], palette[2], palette[1], palette[0]), frames[1])
        assertEquals(listOf(100, 100), delays)
    }

    @Test
    fun decode_keepsPreviousPixelsUnderTransparency() {
        val gif = buildGif(
            listOf(
                TestFrame(intArrayOf(0, 1, 2, 3), 10),
                TestFrame(intArrayOf(3, 1, 1, 0), 10, transparent = 1),
            ),
        )
        val frames = ArrayList<IntArray>()
        GifDecoder.decode(gif) { f -> frames.add(f.pixels.copyOf()); true }
        assertArrayEquals(intArrayOf(palette[3], palette[1], palette[2], palette[0]), frames[1])
    }

    @Test
    fun decode_disposalToBackgroundClearsFrameArea() {
        val gif = buildGif(
            listOf(
                TestFrame(intArrayOf(1, 1, 1, 1), 10, disposal = 2),
                TestFrame(intArrayOf(2), 10, transparent = -1, left = 1, top = 1, w = 1, h = 1),
            ),
        )
        val frames = ArrayList<IntArray>()
        GifDecoder.decode(gif) { f -> frames.add(f.pixels.copyOf()); true }
        // il primo frame viene "disposto" a trasparente prima del secondo, che copre solo (1,1)
        assertArrayEquals(intArrayOf(0, 0, 0, palette[2]), frames[1])
    }

    @Test
    fun decode_canStopEarly() {
        val gif = buildGif(List(5) { TestFrame(intArrayOf(0, 1, 2, 3), 10) })
        var count = 0
        GifDecoder.decode(gif) { count++; count < 2 }
        assertEquals(2, count)
    }

    // ---------------------------------------------------------------- GIF multi-fotogramma (buffer riusati)

    /** Indici casuali con tratti ripetuti, così l'LZW trova sia corrispondenze lunghe sia voci nuove. */
    private fun noise(rnd: Random, count: Int, colors: Int): ByteArray {
        val out = ByteArray(count)
        var i = 0
        while (i < count) {
            val run = if (rnd.nextInt(4) == 0) rnd.nextInt(1, 40) else 1
            val v = rnd.nextInt(colors).toByte()
            repeat(minOf(run, count - i)) { out[i++] = v }
        }
        return out
    }

    private fun randomPalette(rnd: Random, size: Int) = IntArray(size) { (0xFF shl 24) or rnd.nextInt(0x1000000) }

    private fun crc(pixels: IntArray): Long {
        val crc = CRC32()
        val bytes = ByteArray(pixels.size * 4)
        for (i in pixels.indices) {
            bytes[i * 4] = (pixels[i] ushr 24).toByte(); bytes[i * 4 + 1] = (pixels[i] ushr 16).toByte()
            bytes[i * 4 + 2] = (pixels[i] ushr 8).toByte(); bytes[i * 4 + 3] = pixels[i].toByte()
        }
        crc.update(bytes)
        return crc.value
    }

    @Test
    fun decode_multiFrameRoundTripsAcrossCodeSizesAndTables() {
        // Ogni fotogramma copre tutto il canvas senza trasparenza: il risultato atteso è la palette applicata
        // agli indici di partenza. I fotogrammi cambiano min code size, tabella colori, interlacciamento e
        // dimensione dei sotto-blocchi, e i più grandi riempiono la tabella LZW (clear e clear differito):
        // se un buffer riusato conservasse stato del fotogramma precedente, qui si vedrebbe.
        val rnd = Random(7)
        val width = 200
        val height = 160
        val global = randomPalette(rnd, 256)
        val frames = ArrayList<GifTestEncoder.Frame>()
        val expected = ArrayList<IntArray>()
        for (i in 0 until 12) {
            val colors = intArrayOf(4, 16, 256, 8, 64, 2, 128, 256, 32, 4, 256, 16)[i]
            val local = if (i % 3 == 1) randomPalette(rnd, colors) else null
            val palette = local ?: global
            val minCode = maxOf(2, Integer.numberOfTrailingZeros(colors) + if (i % 4 == 3) 1 else 0)
            val indices = noise(rnd, width * height, minOf(colors, palette.size))
            frames.add(
                GifTestEncoder.Frame(
                    indices, 0, 0, width, height, minCode, interlaced = i % 2 == 0, localPalette = local,
                    deferredClear = i % 5 == 4, blockSize = if (i % 4 == 2) 7 else 255,
                ),
            )
            expected.add(IntArray(width * height) { palette[indices[it].toInt() and 0xFF] })
        }
        val gif = GifTestEncoder.encode(width, height, global, frames)

        val decoded = ArrayList<IntArray>()
        val info = GifDecoder.decode(gif) { f -> decoded.add(f.pixels.copyOf()); true }
        assertEquals(12, info.frameCount)
        assertEquals(12, decoded.size)
        for (i in decoded.indices) assertArrayEquals("fotogramma $i", expected[i], decoded[i])
    }

    /**
     * GIF con sotto-rettangoli che escono dal canvas, trasparenza, disposal 1/2/3, tabelle locali, indici oltre la
     * palette, flusso troncato, fine flusso anticipata e fotogramma vuoto. I CRC32 dei canvas sono quelli prodotti
     * dal decoder prima di riusare i buffer: i fotogrammi devono restare identici bit per bit.
     */
    private fun compositedGif(): ByteArray {
        val rnd = Random(2024)
        val width = 90
        val height = 70
        val global = randomPalette(rnd, 16)
        fun f(
            i: Int, left: Int, top: Int, w: Int, h: Int, minCode: Int, colors: Int, delay: Int = 4 + i, disposal: Int = 0, transparent: Int = -1,
            interlaced: Boolean = false, local: IntArray? = null, encoded: Int = w * h, keep: Int = -1, deferred: Boolean = false,
        ) = GifTestEncoder.Frame(
            noise(rnd, w * h, colors), left, top, w, h, minCode, delayCs = delay, disposal = disposal, transparent = transparent,
            interlaced = interlaced, localPalette = local, encodedPixels = encoded, keepBytes = keep, deferredClear = deferred,
        )
        val frames = listOf(
            f(0, 0, 0, 90, 70, 4, 16, disposal = 1),
            f(1, 10, 5, 40, 30, 5, 32, disposal = 2, interlaced = true, local = randomPalette(rnd, 32)),
            f(2, 70, 50, 40, 40, 4, 16, disposal = 3, transparent = 3),
            f(3, 100, 10, 12, 12, 4, 16),                          // tutto fuori dal canvas
            f(4, 5, 5, 60, 50, 4, 16, transparent = 0, keep = 150), // flusso troncato: pixel mancanti a 0
            f(5, 0, 20, 80, 30, 4, 16, encoded = 1000),            // fine flusso prima dei w * h pixel
            f(6, 3, 3, 0, 5, 4, 16),                               // larghezza zero
            f(7, 0, 0, 90, 70, 8, 256, local = randomPalette(rnd, 256), deferred = true),
            f(8, 20, 10, 50, 50, 3, 8, disposal = 2, interlaced = true, transparent = 0, local = randomPalette(rnd, 4)), // indici oltre la palette
            f(9, 0, 0, 90, 70, 2, 4, delay = 0, local = randomPalette(rnd, 4)),
            f(10, 30, 30, 20, 20, 4, 16, delay = 1, disposal = 3),
            f(11, 0, 0, 90, 70, 5, 32, disposal = 2, local = randomPalette(rnd, 32), transparent = 7),
            f(12, 44, 33, 46, 37, 4, 16, transparent = 2, interlaced = true),
        )
        return GifTestEncoder.encode(width, height, global, frames)
    }

    @Test
    fun decode_compositedFramesMatchPreviousOutput() {
        val gif = compositedGif()
        val crcs = ArrayList<Long>()
        val delays = ArrayList<Int>()
        val info = GifDecoder.decode(gif) { f -> crcs.add(crc(f.pixels)); delays.add(f.delayMs); true }
        assertEquals(90, info.width)
        assertEquals(70, info.height)
        // il fotogramma di larghezza zero conta nell'Info ma non produce un canvas
        assertEquals(13, info.frameCount)
        assertEquals(12, crcs.size)
        assertEquals(EXPECTED_DELAYS, delays)
        assertEquals(EXPECTED_CRCS, crcs)
        // readInfo (senza pixel) e decode devono concordare sui ritardi
        assertEquals(info.delaysMs, GifDecoder.readInfo(gif).delaysMs)
    }

    @Test
    fun decode_isRepeatableOnTheSameBytes() {
        val gif = compositedGif()
        fun run(): List<Long> = ArrayList<Long>().also { out -> GifDecoder.decode(gif) { f -> out.add(crc(f.pixels)); true } }
        assertEquals(run(), run())
    }

    @Test
    fun decode_truncatedFileFailsLikeBefore() {
        val gif = compositedGif()
        try {
            GifDecoder.decode(gif.copyOf(gif.size / 2)) { true }
            fail("atteso GifFormatException")
        } catch (e: GifDecoder.GifFormatException) {
            // fine inattesa del file
        }
    }

    private companion object {
        val EXPECTED_DELAYS = listOf(40, 50, 60, 70, 80, 90, 110, 120, 100, 100, 150, 160)
        // CRC32 dei canvas (ARGB big-endian) di compositedGif(), calcolati dal decoder prima del riuso dei buffer
        val EXPECTED_CRCS = listOf(
            3524058286L, 3056612699L, 3475887190L, 3370307983L, 4152803583L, 3264248868L,
            1612398080L, 3482399165L, 257679079L, 2368855531L, 3149847160L, 951725655L,
        )
    }
}
