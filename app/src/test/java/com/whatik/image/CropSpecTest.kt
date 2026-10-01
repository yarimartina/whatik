package com.whatik.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CropSpecTest {

    @Test
    fun fullCropCoversWholeSquareOfShorterSide() {
        val (left, top, side) = CropSpec.FULL.toPixels(1080, 1920)
        assertEquals(1080, side)
        assertEquals(0, left)
        assertEquals(420, top)
    }

    @Test
    fun cropIsClampedInsideTheFrame() {
        assertArrayEquals(intArrayOf(0, 0, 500), CropSpec(0f, 0f, 0.5f).toPixels(1000, 2000))
        assertArrayEquals(intArrayOf(500, 1500, 500), CropSpec(1f, 1f, 0.5f).toPixels(1000, 2000))
        assertArrayEquals(intArrayOf(250, 750, 500), CropSpec(0.5f, 0.5f, 0.5f).toPixels(1000, 2000))
    }

    @Test
    fun normalizedEnforcesMinimumSize() {
        val n = CropSpec(2f, -1f, 0.01f).normalized()
        assertEquals(1f, n.cx, 0f)
        assertEquals(0f, n.cy, 0f)
        assertEquals(CropSpec.MIN_SIZE, n.size, 0f)
    }
}
