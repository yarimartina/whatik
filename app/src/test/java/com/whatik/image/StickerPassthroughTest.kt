package com.whatik.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class StickerPassthroughTest {

    // ---------------------------------------------------------------- WebP costruiti a mano

    private fun le(v: Int, bytes: Int) = ByteArray(bytes) { ((v shr (8 * it)) and 0xFF).toByte() }

    private fun chunk(fourCC: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(fourCC.toByteArray(Charsets.US_ASCII))
        out.write(le(payload.size, 4))
        out.write(payload)
        if (payload.size and 1 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        chunks.forEach { body.write(it) }
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(le(4 + body.size(), 4))
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    /** Payload VP8 di un key frame (start code 9D 01 2A) con le dimensioni nell'intestazione. */
    private fun vp8(width: Int = 512, height: Int = 512, size: Int = 16): ByteArray {
        val p = ByteArray(size) { 0x55 }
        p[0] = 0x10 // bit 0 = 0: key frame
        p[3] = 0x9D.toByte(); p[4] = 0x01; p[5] = 0x2A
        p[6] = (width and 0xFF).toByte(); p[7] = ((width shr 8) and 0x3F).toByte()
        p[8] = (height and 0xFF).toByte(); p[9] = ((height shr 8) and 0x3F).toByte()
        return p
    }

    /** Payload VP8L (firma 0x2F, versione 0) con le dimensioni nell'intestazione. */
    private fun vp8l(width: Int = 512, height: Int = 512, size: Int = 16): ByteArray {
        val p = ByteArray(size) { 0x33 }
        val w = width - 1
        val h = height - 1
        p[0] = 0x2F
        p[1] = (w and 0xFF).toByte()
        p[2] = (((w shr 8) and 0x3F) or ((h and 0x03) shl 6)).toByte()
        p[3] = ((h shr 2) and 0xFF).toByte()
        p[4] = (((h shr 10) and 0x0F) or 0x10).toByte() // alpha usato, versione 0
        return p
    }

    private fun vp8x(flags: Int, width: Int = 512, height: Int = 512): ByteArray {
        val p = ByteArray(10)
        p[0] = flags.toByte()
        le(width - 1, 3).copyInto(p, 4)
        le(height - 1, 3).copyInto(p, 7)
        return chunk("VP8X", p)
    }

    private fun anim(loopCount: Int = 0) = chunk("ANIM", ByteArray(4) + le(loopCount, 2))

    private fun anmf(durationMs: Int, x: Int = 0, y: Int = 0, width: Int = 512, height: Int = 512, image: ByteArray = chunk("VP8 ", vp8())): ByteArray {
        val header = le(x / 2, 3) + le(y / 2, 3) + le(width - 1, 3) + le(height - 1, 3) + le(durationMs, 3) + byteArrayOf(0x02)
        return chunk("ANMF", header + image)
    }

    private fun staticSimple(payload: ByteArray = vp8()) = riff(chunk("VP8 ", payload))

    /** Sticker animato come lo scrive [WebPContainer.muxAnimation] (lo stesso muxer del convertitore). */
    private fun muxed(durations: List<Int>, payloadSize: Int = 16): ByteArray =
        WebPContainer.muxAnimation(512, 512, durations.map { WebPContainer.FrameSpec(staticSimple(vp8(size = payloadSize)), it) })

    private fun passesStatic(bytes: ByteArray) = StickerPassthrough.compliantBytes(bytes, packAnimated = false)
    private fun passesAnimated(bytes: ByteArray) = StickerPassthrough.compliantBytes(bytes, packAnimated = true)

    // ---------------------------------------------------------------- statici

    @Test
    fun static_simpleLossyAndLossless512_arePassedThroughUnchanged() {
        val lossy = staticSimple()
        assertSame(lossy, passesStatic(lossy))
        val lossless = riff(chunk("VP8L", vp8l()))
        assertSame(lossless, passesStatic(lossless))
    }

    @Test
    fun static_extendedWithAlpha_asWrittenByAndroid_isPassedThrough() {
        val withAlpha = WebPContainer.wrapStandalone(
            listOf(WebPContainer.Chunk("ALPH", byteArrayOf(1, 2, 3)), WebPContainer.Chunk("VP8 ", vp8())), 512, 512,
        )
        assertSame(withAlpha, passesStatic(withAlpha))
    }

    @Test
    fun static_sizeLimitIs100KiB() {
        // RIFF (12) + intestazione del chunk (8) + payload
        val atLimit = staticSimple(vp8(size = StickerConverter.MAX_STATIC_BYTES - 20))
        assertEquals(StickerConverter.MAX_STATIC_BYTES, atLimit.size)
        assertNotNull(passesStatic(atLimit))
        val over = staticSimple(vp8(size = StickerConverter.MAX_STATIC_BYTES - 18))
        assertNull(passesStatic(over))
    }

    @Test
    fun static_wrongDimensions_areConverted() {
        assertNull(passesStatic(staticSimple(vp8(511, 512))))
        assertNull(passesStatic(staticSimple(vp8(512, 256))))
        assertNull(passesStatic(riff(chunk("VP8L", vp8l(1024, 1024)))))
        // VP8X dichiara 512x512 ma il bitstream è 256x256
        assertNull(passesStatic(riff(vp8x(0x10), chunk("ALPH", byteArrayOf(1)), chunk("VP8 ", vp8(256, 256)))))
        // e il contrario
        assertNull(passesStatic(riff(vp8x(0x10, 256, 256), chunk("ALPH", byteArrayOf(1)), chunk("VP8 ", vp8()))))
    }

    @Test
    fun static_inAnimatedPack_andAnimatedInStaticPack_keepTheConversion() {
        assertNull(passesAnimated(staticSimple()))
        assertNull(passesStatic(muxed(listOf(100, 100))))
    }

    @Test
    fun static_metadataChunks_areConverted() {
        // EXIF (orientamento) e ICC li applica la conversione: copiandoli lo sticker cambierebbe
        assertNull(passesStatic(riff(vp8x(0x10 or 0x08), chunk("VP8 ", vp8()), chunk("EXIF", ByteArray(12)))))
        assertNull(passesStatic(riff(vp8x(0x10 or 0x20), chunk("ICCP", ByteArray(20)), chunk("VP8 ", vp8()))))
        // chunk estraneo anche con i flag a posto
        assertNull(passesStatic(riff(vp8x(0x10), chunk("VP8 ", vp8()), chunk("XMP ", ByteArray(8)))))
    }

    @Test
    fun static_brokenContainerOrBitstream_isConverted() {
        val good = staticSimple(vp8(size = 64))
        assertNull(passesStatic(good.copyOf(good.size - 10))) // troncato
        assertNull(passesStatic(good + byteArrayOf(0, 0))) // byte oltre la dimensione RIFF
        val wrongRiffSize = good.copyOf().also { le(good.size - 8 + 2, 4).copyInto(it, 4) }
        assertNull(passesStatic(wrongRiffSize))
        assertNull(passesStatic(staticSimple(vp8().also { it[3] = 0 }))) // start code VP8 sbagliato
        assertNull(passesStatic(staticSimple(vp8().also { it[0] = 0x11 }))) // non è un key frame
        assertNull(passesStatic(riff(chunk("VP8L", vp8l().also { it[0] = 0x2E })))) // firma VP8L sbagliata
        assertNull(passesStatic(riff(chunk("VP8 ", vp8()), chunk("VP8 ", vp8())))) // due bitstream
        assertNull(passesStatic(riff(vp8x(0x10), chunk("ALPH", byteArrayOf(1)), chunk("VP8L", vp8l())))) // ALPH con VP8L
    }

    @Test
    fun notWebP_isConverted() {
        assertNull(passesStatic(ByteArray(0)))
        assertNull(passesStatic("GIF89a".toByteArray() + ByteArray(40)))
        assertNull(passesStatic(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(40)))
        assertNull(passesAnimated(riff()))
    }

    // ---------------------------------------------------------------- animati

    @Test
    fun animated_muxedLikeTheConverter_isPassedThroughUnchanged() {
        val sticker = muxed(listOf(100, 120, 80))
        assertSame(sticker, passesAnimated(sticker))
        // anche lo sticker fermo reso animato (due fotogrammi da 500 ms)
        val still = StickerConverter.animateStill(staticSimple()).bytes
        assertSame(still, passesAnimated(still))
    }

    @Test
    fun animated_handBuiltWithAlphaAndLossless_isPassedThrough() {
        val alphaFrame = chunk("ALPH", byteArrayOf(1, 2, 3)) + chunk("VP8 ", vp8())
        val sticker = riff(vp8x(0x12), anim(), anmf(100, image = alphaFrame), anmf(100, image = chunk("VP8L", vp8l())))
        assertSame(sticker, passesAnimated(sticker))
    }

    @Test
    fun animated_frameDurationMustBeAtLeast8ms() {
        assertNotNull(passesAnimated(muxed(listOf(100, StickerConverter.MIN_FRAME_MS, 100))))
        assertNull(passesAnimated(muxed(listOf(100, StickerConverter.MIN_FRAME_MS - 1, 100))))
    }

    @Test
    fun animated_totalDurationMustBeAtMost10s() {
        assertNotNull(passesAnimated(muxed(listOf(5_000, 5_000))))
        assertNull(passesAnimated(muxed(listOf(5_000, 5_001))))
        assertNull(passesAnimated(muxed(List(40) { 260 })))
    }

    @Test
    fun animated_frameCountWithinConverterLimits() {
        assertNotNull(passesAnimated(muxed(List(StickerPassthrough.MAX_FRAMES) { 50 })))
        assertNull(passesAnimated(muxed(List(StickerPassthrough.MAX_FRAMES + 1) { 50 })))
        // un solo fotogramma con il flag di animazione: per WhatsApp non è animato
        assertNull(passesAnimated(riff(vp8x(0x12), anim(), anmf(100))))
        assertNull(passesStatic(riff(vp8x(0x12), anim(), anmf(100))))
    }

    @Test
    fun animated_sizeLimitIs500KiB() {
        // RIFF 12 + VP8X 18 + ANIM 14, poi per fotogramma ANMF 8 + 16 + chunk VP8 8 + payload
        val payload = (StickerConverter.MAX_ANIMATED_BYTES - 44) / 2 - 32
        val atLimit = muxed(listOf(100, 100), payloadSize = payload)
        assertEquals(StickerConverter.MAX_ANIMATED_BYTES, atLimit.size)
        assertNotNull(passesAnimated(atLimit))
        assertNull(passesAnimated(muxed(listOf(100, 100), payloadSize = payload + 2)))
        // e uno sticker animato sotto i 500 KB non diventa conforme per un pack statico
        assertNull(passesStatic(muxed(listOf(100, 100))))
    }

    @Test
    fun animated_wrongCanvasOrPartialFrames_areConverted() {
        assertNull(passesAnimated(riff(vp8x(0x12, 511, 512), anim(), anmf(100, width = 511), anmf(100, width = 511))))
        // fotogramma parziale o spostato: lo ricompone il convertitore
        val small = chunk("VP8 ", vp8(256, 256))
        assertNull(passesAnimated(riff(vp8x(0x12), anim(), anmf(100), anmf(100, width = 256, height = 256, image = small))))
        assertNull(passesAnimated(riff(vp8x(0x12), anim(), anmf(100), anmf(100, x = 2, width = 510, image = chunk("VP8 ", vp8(510, 512))))))
        // ANMF dichiara 512x512 ma il bitstream è più piccolo
        assertNull(passesAnimated(riff(vp8x(0x12), anim(), anmf(100), anmf(100, image = small))))
    }

    @Test
    fun animated_containerDetails() {
        val ok = riff(vp8x(0x12), anim(), anmf(100), anmf(100))
        assertNotNull(passesAnimated(ok))
        assertNull(passesAnimated(riff(vp8x(0x12), anmf(100), anmf(100)))) // manca ANIM
        assertNull(passesAnimated(riff(vp8x(0x12), anim(loopCount = 1), anmf(100), anmf(100)))) // non in loop
        assertNull(passesAnimated(riff(vp8x(0x12 or 0x08), anim(), anmf(100), anmf(100), chunk("EXIF", ByteArray(8)))))
        assertNull(passesAnimated(ok.copyOf(ok.size - 4))) // troncato
        // sotto-chunk dell'ANMF troncato: la dimensione del chunk interno sfora il fotogramma
        val broken = chunk("VP8 ", vp8()).also { le(40, 4).copyInto(it, 4) }
        assertNull(passesAnimated(riff(vp8x(0x12), anim(), anmf(100), anmf(100, image = broken))))
    }

    @Test
    fun prepareForPack_returnsOriginalBytesWhenCompliant() {
        val sticker = muxed(listOf(100, 100, 100))
        val result = StickerPassthrough.prepareForPack(sticker, packAnimated = true)
        assertSame(sticker, result.bytes)
        assertTrue(result.animated)
        assertEquals(3, result.frameCount)
        val still = staticSimple()
        val staticResult = StickerPassthrough.prepareForPack(still, packAnimated = false)
        assertSame(still, staticResult.bytes)
        assertEquals(false, staticResult.animated)
        assertEquals(1, staticResult.frameCount)
    }
}
