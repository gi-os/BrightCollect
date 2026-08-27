package com.gios.brightcollect

import com.gios.brightcollect.cut.Blobs
import com.gios.brightcollect.cut.Mask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlobsTest {

    private fun maskOf(vararg rows: String): Mask {
        val h = rows.size
        val w = rows[0].length
        val m = Mask(w, h)
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, c -> m[x, y] = if (c == '#') 255 else 0 }
        }
        return m
    }

    @Test
    fun `a speck beside the object is dropped`() {
        // The commonest way a cutout looks wrong: u2netp is a saliency model with no notion that
        // its answer should be one thing, so a bright corner of tile comes back as confident as
        // the mug.
        val m = maskOf(
            "####...",
            "####...",
            "####..#",
            "####...",
        )
        val dropped = Blobs.keepLargest(m)
        assertEquals(1, dropped)
        assertEquals(0, m[6, 2])
        assertEquals(255, m[0, 0])
    }

    @Test
    fun `the largest survives even when it is not the first found`() {
        // Labelling walks in raster order, so the little one is region 1. Size decides, not order.
        val m = maskOf(
            "#..####",
            "...####",
            "...####",
        )
        Blobs.keepLargest(m)
        assertEquals(0, m[0, 0])
        assertEquals(255, m[4, 1])
    }

    @Test
    fun `corner touching is not connected`() {
        // Four-connectivity, deliberately. Eight would bridge the object to anything sitting
        // diagonally against it, and the bridge is invisible at the size you would inspect it.
        val m = maskOf(
            "##..",
            "##..",
            "..#.",
        )
        val dropped = Blobs.keepLargest(m)
        assertEquals(1, dropped)
        assertEquals(0, m[2, 2])
    }

    @Test
    fun `an object with a hole keeps its hole and itself`() {
        val m = maskOf(
            "#####",
            "#...#",
            "#####",
        )
        assertEquals(0, Blobs.keepLargest(m))
        assertEquals(255, m[0, 0])
        assertEquals(0, m[2, 1])
    }

    @Test
    fun `one region is left alone and reports nothing dropped`() {
        val m = maskOf("###", "###")
        assertEquals(0, Blobs.keepLargest(m))
    }

    @Test
    fun `an empty mask is not a crash`() {
        val m = maskOf("...", "...")
        assertEquals(0, Blobs.keepLargest(m))
    }

    @Test
    fun `faint pixels are background, so a feathered tail cannot bridge`() {
        // Why cleanup runs before the feather: a soft grey tail from a speck could otherwise
        // reach the object and make the two count as one region.
        val m = Mask(5, 1)
        m[0, 0] = 255
        m[1, 0] = Blobs.SOLID // exactly at the threshold, which is not above it
        m[2, 0] = 4
        m[4, 0] = 255
        val dropped = Blobs.keepLargest(m)
        assertTrue("the two solid pixels must be separate regions", dropped > 0)
    }

    @Test
    fun `a large region does not overflow the stack`() {
        // The reason this is iterative. A mug at working resolution is a few hundred thousand
        // connected pixels, and the recursive version is a frame per pixel.
        val m = Mask(700, 700, ByteArray(700 * 700) { 255.toByte() })
        assertEquals(0, Blobs.keepLargest(m))
        assertEquals(1, Blobs.label(m).count)
    }

    @Test
    fun `labelling counts every region`() {
        val m = maskOf(
            "#.#.#",
            ".....",
            "#.#.#",
        )
        assertEquals(6, Blobs.label(m).count)
    }
}
