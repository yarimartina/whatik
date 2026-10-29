package com.whatik.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Prove JVM della parte pura della decodifica sequenziale:
 * scelta dei fotogrammi come OPTION_CLOSEST, dimensioni come getScaledFrameAtTime, YUV->RGB.
 */
class SequentialFrameReaderTest {
    // ------------------------------------------------------------ scelta dei fotogrammi

    /** Riferimento indipendente: minimo |t - s|, a parità il successivo. */
    private fun bruteClosest(samples: LongArray, t: Long): Long {
        var best = samples[0]
        for (s in samples) {
            val d = abs(s - t)
            val bd = abs(best - t)
            if (d < bd || (d == bd && s > best)) best = s
        }
        return best
    }

    @Test
    fun closestFollowsSampleTableRules() {
        val s = longArrayOf(1000, 2000, 3000, 5000)
        val t = longArrayOf(0, 1000, 1400, 1500, 1600, 4000, 4001, 3999, 9000)
        val expected = longArrayOf(1000, 1000, 1000, 2000, 2000, 5000, 5000, 3000, 5000)
        assertArrayEquals(expected, SequentialFrameReader.closestSampleTimes(s, t))
    }

    @Test
    fun closestMatchesBruteForceOnRandomVfrTimes() {
        val rnd = Random(7)
        repeat(200) {
            var acc = rnd.nextLong(0, 50_000)
            val samples = LongArray(rnd.nextInt(1, 80)) { acc += rnd.nextLong(1, 60_000); acc }
            val targets = LongArray(40) { rnd.nextLong(0, acc + 100_000) }.also { it.sort() }
            val got = SequentialFrameReader.closestSampleTimes(samples, targets)
            for (k in targets.indices) assertEquals(bruteClosest(samples, targets[k]), got[k])
        }
    }

    // ------------------------------------------------------------ dimensioni

    @Test
    fun fitInsideRoundsLikeTheRetriever() {
        // analisi: 1078x640 entro 320x190 (aw*2, ah*2 con ah = 95)
        assertArrayEquals(intArrayOf(320, 190), SequentialFrameReader.fitInside(1078, 640, 320, 190))
        // produttore: dimensioni già proporzionali
        assertArrayEquals(intArrayOf(916, 544), SequentialFrameReader.fitInside(1078, 640, 916, 544))
        // anteprima 720x720 di un video verticale
        assertArrayEquals(intArrayOf(332, 720), SequentialFrameReader.fitInside(1080, 2340, 720, 720))
        // video più piccolo del riquadro: il retriever ingrandisce
        assertArrayEquals(intArrayOf(400, 200), SequentialFrameReader.fitInside(200, 100, 400, 400))
    }

    // ------------------------------------------------------------ YUV -> RGB

    private fun convertUniform(yv: Int, uv: Int, vv: Int, coeffs: IntArray): Int {
        val y = ByteArray(16) { yv.toByte() }
        val u = ByteArray(4) { uv.toByte() }
        val v = ByteArray(4) { vv.toByte() }
        val out = IntArray(16)
        YuvConverter.convert(y, 4, 1, u, v, 2, 1, 0, 0, 4, 4, 4, 4, coeffs, out)
        assertTrue(out.all { it == out[0] })
        return out[0]
    }

    private fun assertColor(expected: Int, actual: Int, tol: Int) {
        for (shift in intArrayOf(16, 8, 0)) {
            val e = (expected shr shift) and 0xFF
            val a = (actual shr shift) and 0xFF
            assertTrue("atteso %06x, ottenuto %06x".format(expected and 0xFFFFFF, actual and 0xFFFFFF), abs(e - a) <= tol)
        }
        assertEquals(0xFF, (actual ushr 24))
    }

    @Test
    fun knownColorsBt601AndBt709() {
        val c601 = YuvConverter.coefficients(0.299, 0.114, fullRange = false)
        val c709 = YuvConverter.coefficients(0.2126, 0.0722, fullRange = false)
        val c601f = YuvConverter.coefficients(0.299, 0.114, fullRange = true)
        assertColor(0xFFFFFF, convertUniform(235, 128, 128, c601), 0)
        assertColor(0x000000, convertUniform(16, 128, 128, c601), 0)
        assertColor(0xFF0000, convertUniform(81, 90, 240, c601), 2)
        assertColor(0x00FF00, convertUniform(145, 54, 34, c601), 2)
        assertColor(0x0000FF, convertUniform(41, 240, 110, c601), 2)
        assertColor(0xFF0000, convertUniform(63, 102, 240, c709), 2)
        assertColor(0x00FF00, convertUniform(173, 42, 26, c709), 2)
        assertColor(0x0000FF, convertUniform(32, 240, 118, c709), 2)
        assertColor(0xFFFFFF, convertUniform(255, 128, 128, c601f), 0)
        assertColor(0xFF0000, convertUniform(76, 85, 255, c601f), 2)
    }

    /** Stessa immagine in I420, NV12 e NV21 con righe allungate e area visibile spostata: stesso risultato. */
    @Test
    fun planarAndSemiPlanarLayoutsAgree() {
        val rnd = Random(3)
        val fullW = 37; val fullH = 23; val left = 2; val top = 4; val w = 31; val h = 17
        val lumaStride = 48; val cw = (fullW + 1) / 2; val ch = (fullH + 1) / 2
        val yPlane = ByteArray(lumaStride * fullH) { rnd.nextInt(256).toByte() }
        val uSrc = Array(ch) { IntArray(cw) { rnd.nextInt(256) } }
        val vSrc = Array(ch) { IntArray(cw) { rnd.nextInt(256) } }
        // I420: piani separati, passo di riga 24
        val i420u = ByteArray(24 * ch); val i420v = ByteArray(24 * ch)
        for (r in 0 until ch) for (c in 0 until cw) { i420u[r * 24 + c] = uSrc[r][c].toByte(); i420v[r * 24 + c] = vSrc[r][c].toByte() }
        // NV12: UV intercalati, passo di riga 48; il piano V parte un byte dopo
        val nv12 = ByteArray(48 * ch + 1)
        for (r in 0 until ch) for (c in 0 until cw) { nv12[r * 48 + 2 * c] = uSrc[r][c].toByte(); nv12[r * 48 + 2 * c + 1] = vSrc[r][c].toByte() }
        val nv12u = nv12.copyOfRange(0, nv12.size - 1); val nv12v = nv12.copyOfRange(1, nv12.size)
        // NV21: VU intercalati
        val nv21 = ByteArray(48 * ch + 1)
        for (r in 0 until ch) for (c in 0 until cw) { nv21[r * 48 + 2 * c] = vSrc[r][c].toByte(); nv21[r * 48 + 2 * c + 1] = uSrc[r][c].toByte() }
        val nv21v = nv21.copyOfRange(0, nv21.size - 1); val nv21u = nv21.copyOfRange(1, nv21.size)
        val coeffs = YuvConverter.coefficients(0.2126, 0.0722, false)
        for ((dw, dh) in listOf(w to h, 13 to 7, 62 to 34)) {
            val a = IntArray(dw * dh); val b = IntArray(dw * dh); val c = IntArray(dw * dh)
            YuvConverter.convert(yPlane, lumaStride, 1, i420u, i420v, 24, 1, left, top, w, h, dw, dh, coeffs, a)
            YuvConverter.convert(yPlane, lumaStride, 1, nv12u, nv12v, 48, 2, left, top, w, h, dw, dh, coeffs, b)
            YuvConverter.convert(yPlane, lumaStride, 1, nv21u, nv21v, 48, 2, left, top, w, h, dw, dh, coeffs, c)
            assertArrayEquals(a, b)
            assertArrayEquals(a, c)
        }
    }

    /** A 1:1 con crominanza uniforme ogni pixel dipende solo dalla sua luma (nessuna sfocatura). */
    @Test
    fun oneToOneKeepsLumaDetail() {
        val w = 9; val h = 5
        val y = ByteArray(w * h) { (16 + (it * 37) % 220).toByte() }
        val u = ByteArray(5 * 3) { 128.toByte() }; val v = ByteArray(5 * 3) { 128.toByte() }
        val out = IntArray(w * h)
        val coeffs = YuvConverter.coefficients(0.299, 0.114, false)
        YuvConverter.convert(y, w, 1, u, v, 5, 1, 0, 0, w, h, w, h, coeffs, out)
        for (p in 0 until w * h) {
            val luma = y[p].toInt() and 0xFF
            assertEquals(convertUniform(luma, 128, 128, coeffs), out[p])
        }
    }
}
