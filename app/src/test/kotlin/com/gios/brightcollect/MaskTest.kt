package com.gios.brightcollect

import com.gios.brightcollect.cut.Mask
import com.gios.brightcollect.cut.feather
import com.gios.brightcollect.cut.harden
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskTest {

    @Test
    fun `scores are stretched to the observed range`() {
        // A low-contrast map: nothing reaches 0 or 1. Without the stretch a single threshold
        // takes either everything or nothing, which is the bug this exists for.
        val scores = floatArrayOf(0.20f, 0.30f, 0.50f, 0.60f)
        val m = Mask.fromScores(scores, 2, 2)
        assertEquals(0, m[0, 0])
        assertEquals(255, m[1, 1])
    }

    @Test
    fun `a flat map is not stretched into an edge`() {
        // Span below Mask.FLAT: the model found nothing. Stretching would manufacture a
        // confident-looking mask out of noise.
        val scores = FloatArray(4) { 0.50f + it * 0.001f }
        val m = Mask.fromScores(scores, 2, 2)
        assertTrue("a flat map must stay mid-grey, was ${m[0, 0]}", m[0, 0] in 120..140)
        assertTrue(m[1, 1] in 120..140)
    }

    @Test
    fun `bounds finds the tightest box and nulls on an empty mask`() {
        val m = Mask(10, 10)
        assertNull(m.bounds())
        m[3, 4] = 255
        m[7, 8] = 255
        val b = m.bounds()
        assertNotNull(b)
        assertEquals(listOf(3, 4, 7, 8), b!!.toList())
    }

    @Test
    fun `paint fills the core and ramps at the rim`() {
        val m = Mask(21, 21)
        m.paint(10f, 10f, 8f, 255)
        assertEquals("centre must be solid", 255, m[10, 10])
        assertEquals("outside the radius must be untouched", 0, m[0, 0])
        val rim = m[10, 3]
        assertTrue("the rim must be a ramp, was $rim", rim in 1..254)
    }

    @Test
    fun `the eraser is paint with zero`() {
        val m = Mask(21, 21, ByteArray(21 * 21) { 255.toByte() })
        m.paint(10f, 10f, 6f, 0)
        assertEquals(0, m[10, 10])
        assertEquals(255, m[0, 0])
    }

    @Test
    fun `harden makes the interior solid and the outside gone`() {
        val m = Mask(3, 1, byteArrayOf(30, 125, 220.toByte()))
        val h = m.harden(low = 60, high = 190)
        assertEquals(0, h[0, 0])
        assertEquals(255, h[2, 0])
        assertTrue("the middle keeps its ramp", h[1, 0] in 1..254)
    }

    @Test
    fun `feather softens a hard edge`() {
        val m = Mask(32, 1)
        for (x in 16 until 32) m[x, 0] = 255
        val f = m.feather(radius = 3)
        // The step at 16 becomes a ramp; the ends stay where they were.
        assertTrue("left end stays empty", f[0, 0] < 8)
        assertTrue("right end stays solid", f[31, 0] > 247)
        assertTrue("the edge is now a ramp", f[15, 0] in 1..254 || f[16, 0] in 1..254)
    }

    @Test
    fun `scale is exact at the corners`() {
        val src = Mask(2, 2, byteArrayOf(0, 255.toByte(), 255.toByte(), 0))
        val up = Mask.scale(src, 8, 8)
        assertEquals(8, up.width)
        assertEquals(0, up[0, 0])
        assertEquals(255, up[7, 0])
        assertEquals(255, up[0, 7])
        assertEquals(0, up[7, 7])
    }

    @Test
    fun `coverage counts what is above the threshold`() {
        val m = Mask(10, 10)
        for (x in 0 until 10) m[x, 0] = 255
        assertEquals(0.10f, m.coverage(), 0.001f)
    }
}
