package com.whatik.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StickerCleanerTest {

    private val w = 160
    private val h = 180
    /** Pannello di TikTok (misurato su uno screenshot reale). */
    private val panel = 0xF8F8F8
    /** Sfondo del meme dello stesso screenshot: diverso dal pannello di pochi livelli. */
    private val memeBg = 0xF1F6F9

    private fun canvas(color: Int = panel) = IntArray(w * h) { 0xFF shl 24 or color }

    private fun fill(img: IntArray, l: Int, t: Int, bw: Int, bh: Int, color: Int) {
        for (y in t until t + bh) for (x in l until l + bw) img[y * w + x] = 0xFF shl 24 or color
    }

    /** Rumore tipo JPEG (±2 per canale), riproducibile. */
    private fun noise(img: IntArray) {
        val rnd = Random(7)
        for (i in img.indices) {
            val c = img[i]
            fun ch(v: Int) = (v + rnd.nextInt(-2, 3)).coerceIn(0, 255)
            img[i] = (c and -0x1000000) or (ch((c shr 16) and 0xFF) shl 16) or (ch((c shr 8) and 0xFF) shl 8) or ch(c and 0xFF)
        }
    }

    /** Meme come quello segnalato: sfondo chiarissimo, righe di testo e due foto, dentro le bande del pannello. */
    private fun meme(captionOnly: Boolean = false): IntArray {
        val img = canvas()
        fill(img, 20, 15, 120, 150, memeBg)
        val lines = if (captionOnly) 1 else 2
        for (line in 0 until lines) for (letter in 0 until 8) {
            fill(img, 28 + letter * 13, 22 + line * 72, 8, 10, 0x202020)
        }
        fill(img, 30, 40, 100, 45, 0x806040)
        if (!captionOnly) fill(img, 30, 112, 100, 45, 0x406080)
        noise(img)
        return img
    }

    private fun clean(img: IntArray, bg: Int = panel): Pair<StickerCleaner.Plan, IntArray> {
        val plan = StickerCleaner.plan(img, w, h, bg)
        assertNotNull(plan)
        return plan!! to StickerCleaner.apply(img, w, h, bg, plan)
    }

    private fun transparent(img: IntArray, rect: IntArray): Int {
        var n = 0
        for (y in rect[1] until rect[1] + rect[3]) for (x in rect[0] until rect[0] + rect[2]) if (img[y * w + x] ushr 24 < 128) n++
        return n
    }

    @Test
    fun memeKeepsItsOwnBackgroundAndLosesThePanelBands() {
        val img = meme()
        val (plan, box) = clean(img)
        assertFalse("lo sfondo del meme non va ritagliato a sagoma", plan.cutout)
        val (l, t, bw, bh) = box
        assertTrue("left $l", l in 20..22)
        assertTrue("top $t", t in 15..17)
        assertTrue("size $bw x $bh", bw in 116..120 && bh in 146..150)
        assertEquals("nessun pixel trasparente nel meme", 0, transparent(img, intArrayOf(24, 19, 112, 142)))
    }

    @Test
    fun memeWithOnePhotoAndAShortCaptionKeepsItsBackground() {
        // quasi un soggetto unico: lo salva il colore del suo sfondo, diverso da quello del pannello
        val img = meme(captionOnly = true)
        val (plan, _) = clean(img)
        assertFalse(plan.cutout)
        assertEquals(0, transparent(img, intArrayOf(24, 19, 112, 142)))
    }

    @Test
    fun memeOnTheSameWhiteAsThePanelIsTrimmedButNotCutOut() {
        // caso peggiore: stesso colore del pannello; il testo separato dice che lo sfondo fa parte dello sticker
        val img = canvas()
        for (line in 0 until 2) for (letter in 0 until 8) fill(img, 28 + letter * 13, 22 + line * 72, 8, 10, 0x202020)
        fill(img, 30, 40, 100, 45, 0x806040)
        fill(img, 30, 112, 100, 45, 0x406080)
        noise(img)
        val (plan, box) = clean(img)
        assertFalse(plan.cutout)
        assertEquals(0, transparent(img, box))
    }

    @Test
    fun singleSubjectOnThePanelIsCutOut() {
        val img = canvas()
        val cx = 80; val cy = 90; val r = 50
        for (y in 0 until h) for (x in 0 until w) {
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) img[y * w + x] = 0xFFE05080.toInt()
        }
        // un occhio bianco dentro il soggetto: chiuso, resta opaco
        fill(img, 70, 70, 12, 12, 0xFFFFFF)
        noise(img)
        val (plan, box) = clean(img)
        assertTrue(plan.cutout)
        assertTrue("angolo del riquadro trasparente", img[(box[1] + 2) * w + box[0] + 2] ushr 24 == 0)
        assertEquals("occhio opaco", 0xFF, img[75 * w + 75] ushr 24)
        assertEquals("centro opaco", 0xFF, img[cy * w + cx] ushr 24)
        assertTrue("riquadro attorno al soggetto: ${box.toList()}", box[2] in 100..112 && box[3] in 100..112)
    }

    @Test
    fun photoWithRoundedTileCornersOnlyLosesTheCorners() {
        val img = canvas()
        fill(img, 10, 10, 140, 160, 0x507090)
        // angoli arrotondati: piccoli triangoli del colore del pannello
        for (i in 0 until 8) for (j in 0 until 8 - i) {
            img[(10 + i) * w + 10 + j] = 0xFF shl 24 or panel
            img[(10 + i) * w + 149 - j] = 0xFF shl 24 or panel
            img[(169 - i) * w + 10 + j] = 0xFF shl 24 or panel
            img[(169 - i) * w + 149 - j] = 0xFF shl 24 or panel
        }
        val (plan, box) = clean(img)
        assertFalse(plan.cutout)
        assertTrue("bande tolte: ${box.toList()}", box[0] in 10..12 && box[2] in 136..140)
        assertTrue("angolo trasparente", img[11 * w + 11] ushr 24 == 0)
        assertEquals("foto opaca", 0xFF, img[90 * w + 80] ushr 24)
    }

    @Test
    fun allPanelGivesNoPlan() {
        val img = canvas()
        noise(img)
        assertNull(StickerCleaner.plan(img, w, h, panel))
    }
}
