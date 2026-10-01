package com.whatik.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

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
}
