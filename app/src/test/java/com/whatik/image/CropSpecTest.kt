package com.whatik.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropSpecTest {

    @Test
    fun fullCropCoversTheWholeImage() {
        assertArrayEquals(intArrayOf(0, 0, 1080, 1920), CropSpec.FULL.toPixels(1080, 1920))
    }

    @Test
    fun squareIsCenteredAndClampedInsideTheFrame() {
        assertArrayEquals(intArrayOf(250, 750, 500, 500), CropSpec.square(0.5f, 0.5f, 0.5f, 1000, 2000).toPixels(1000, 2000))
        assertArrayEquals(intArrayOf(0, 0, 500, 500), CropSpec.square(0f, 0f, 0.5f, 1000, 2000).toPixels(1000, 2000))
        assertArrayEquals(intArrayOf(500, 1500, 500, 500), CropSpec.square(1f, 1f, 0.5f, 1000, 2000).toPixels(1000, 2000))
    }

    @Test
    fun freeRectangleKeepsItsAspect() {
        val crop = CropSpec(0.1f, 0.2f, 0.6f, 0.4f)
        assertArrayEquals(intArrayOf(100, 400, 500, 400), crop.toPixels(1000, 2000))
    }

    @Test
    fun normalizedEnforcesMinimumSizeAndBounds() {
        val n = CropSpec(0.5f, 0.5f, 0.5f, 0.5f).normalized()
        assertEquals(CropSpec.MIN_SIZE, n.width, 1e-6f)
        assertEquals(CropSpec.MIN_SIZE, n.height, 1e-6f)
        val out = CropSpec(-0.5f, 0.9f, 0.2f, 1.5f).normalized()
        assertTrue(out.left >= 0f && out.top >= 0f && out.right <= 1f && out.bottom <= 1f)
        assertEquals(0.7f, out.width, 1e-6f)
        assertEquals(0.6f, out.height, 1e-6f)
    }

    @Test
    fun effectiveIsStable() {
        val spec = CropSpec.square(0.02f, 0.5f, 0.5f, 1000, 2000).effective(1000, 2000)
        val again = spec.effective(1000, 2000)
        assertEquals(spec, again)
        assertEquals(0.25f, spec.cx, 1e-5f) // lato 500 spostato dentro: centro a 250
    }
}
