package com.gios.brightcollect

import com.gios.brightcollect.cut.Wand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WandTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    /** A white frame with a black square from (2,2) to (5,5). */
    private fun scene(w: Int = 8, h: Int = 8) = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        if (x in 2..5 && y in 2..5) black else white
    }

    @Test
    fun `a fill takes the region it was seeded in and stops at the edge`() {
        val region = Wand.select(scene(), 8, 8, x = 3, y = 3, tolerance = 10)
        assertTrue("inside the square", region[3 * 8 + 3])
        assertTrue("the far corner of the square", region[5 * 8 + 5])
        assertFalse("outside it", region[0])
        assertEquals(16, Wand.size(region))
    }

    @Test
    fun `the background is one region and the square is a hole in it`() {
        val region = Wand.select(scene(), 8, 8, x = 0, y = 0, tolerance = 10)
        assertEquals(64 - 16, Wand.size(region))
        assertFalse(region[3 * 8 + 3])
    }

    @Test
    fun `tolerance high enough swallows the frame`() {
        // The guard rail this justifies: the UI refuses a fill that took nearly everything,
        // because a wand that selects the whole photograph has told you nothing.
        val region = Wand.select(scene(), 8, 8, x = 0, y = 0, tolerance = 255)
        assertEquals(64, Wand.size(region))
    }

    @Test
    fun `contiguity matters — a second square of the same colour is not taken`() {
        val w = 12
        val px = IntArray(w * 4) { white }
        px[0] = black; px[1] = black
        px[10] = black; px[11] = black
        val region = Wand.select(px, w, 4, x = 0, y = 0, tolerance = 10)
        assertEquals("only the run it was seeded in", 2, Wand.size(region))
        assertFalse(region[10])
    }

    @Test
    fun `a seed outside the image is an empty region, not a crash`() {
        val region = Wand.select(scene(), 8, 8, x = -1, y = 99, tolerance = 10)
        assertEquals(0, Wand.size(region))
    }

    @Test
    fun `grow expands by one ring per pass`() {
        val region = BooleanArray(25)
        region[2 * 5 + 2] = true
        val one = Wand.grow(region, 5, 5, 1)
        assertEquals("the pixel plus its four neighbours", 5, Wand.size(one))
        val two = Wand.grow(region, 5, 5, 2)
        assertEquals(13, Wand.size(two))
    }

    @Test
    fun `a large region does not overflow the stack`() {
        // The reason this is a scanline fill. The recursive four-neighbour version dies here.
        val w = 600
        val h = 600
        val px = IntArray(w * h) { white }
        val region = Wand.select(px, w, h, x = 0, y = 0, tolerance = 0)
        assertEquals(w * h, Wand.size(region))
    }
}
