package com.gios.brightcollect

import com.gios.brightcollect.ui.Fit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FitTest {

    /** A phone-shaped box: narrow and tall, which is where the old code broke. */
    private val boxW = 343f
    private val boxH = 420f

    @Test
    fun `a portrait photo fits inside the box instead of overflowing it`() {
        // The bug. `fillMaxWidth().aspectRatio(3f/4f)` gave a height of 343 / 0.75 = 457 on a box
        // 420 tall, so 37dp of photograph was drawn outside the box and clipped — and the clipped
        // part was unreachable by the wand as well as invisible.
        val r = Fit.inside(boxW, boxH, srcWidth = 3000, srcHeight = 4000)
        assertTrue("height ${r.height} must fit in $boxH", r.height <= boxH + 0.01f)
        assertTrue("width ${r.width} must fit in $boxW", r.width <= boxW + 0.01f)
        // Height-limited, so it uses the full height and is letterboxed left and right.
        assertEquals(boxH, r.height, 0.01f)
        assertTrue("should be pillarboxed", r.x > 0f)
        assertEquals(0f, r.y, 0.01f)
    }

    @Test
    fun `a landscape photo is letterboxed top and bottom`() {
        val r = Fit.inside(boxW, boxH, srcWidth = 4000, srcHeight = 3000)
        assertEquals(boxW, r.width, 0.01f)
        assertTrue("should be letterboxed", r.y > 0f)
        assertEquals(0f, r.x, 0.01f)
    }

    @Test
    fun `the aspect ratio is preserved either way`() {
        listOf(3000 to 4000, 4000 to 3000, 1000 to 1000, 4000 to 400).forEach { (w, h) ->
            val r = Fit.inside(boxW, boxH, w, h)
            assertEquals(
                "aspect for ${w}x$h",
                w.toFloat() / h,
                r.width / r.height,
                0.001f,
            )
        }
    }

    @Test
    fun `the picture is centred`() {
        val r = Fit.inside(boxW, boxH, 3000, 4000)
        assertEquals(boxW - r.right, r.x, 0.01f)
        assertEquals(boxH - r.bottom, r.y, 0.01f)
    }

    @Test
    fun `a corner tap maps to the corner pixel`() {
        val src = 3000 to 4000
        val r = Fit.inside(boxW, boxH, src.first, src.second)
        val topLeft = Fit.toSource(r, r.x, r.y, src.first, src.second)
        assertNotNull(topLeft)
        assertEquals(0f, topLeft!!.first, 1f)
        assertEquals(0f, topLeft.second, 1f)

        val bottomRight = Fit.toSource(r, r.right - 0.01f, r.bottom - 0.01f, src.first, src.second)
        assertNotNull(bottomRight)
        assertEquals((src.first - 1).toFloat(), bottomRight!!.first, 2f)
        assertEquals((src.second - 1).toFloat(), bottomRight.second, 2f)
    }

    @Test
    fun `the centre maps to the centre`() {
        val r = Fit.inside(boxW, boxH, 3000, 4000)
        val p = Fit.toSource(r, r.x + r.width / 2, r.y + r.height / 2, 3000, 4000)
        assertNotNull(p)
        assertEquals(1500f, p!!.first, 2f)
        assertEquals(2000f, p.second, 2f)
    }

    @Test
    fun `a tap in the letterbox margin is a tap on nothing`() {
        // Not clamped to the nearest edge pixel, on purpose: the edge of a photograph is its
        // background, and a wand fill seeded there takes the whole background in one tap that
        // the user experienced as a miss.
        val r = Fit.inside(boxW, boxH, 3000, 4000)
        assertNull(Fit.toSource(r, 0f, boxH / 2, 3000, 4000))
        assertNull(Fit.toSource(r, boxW - 0.5f, boxH / 2, 3000, 4000))
        assertNull(Fit.toSource(r, r.x + 1f, -1f, 3000, 4000))
    }

    @Test
    fun `mapping never lands outside the bitmap`() {
        // A rounded coordinate one pixel past the end is an ArrayIndexOutOfBounds in the wand,
        // which is a crash on a tap at the very edge of the picture.
        val r = Fit.inside(boxW, boxH, 3000, 4000)
        var x = r.x
        while (x < r.right) {
            val p = Fit.toSource(r, x, r.bottom - 0.01f, 3000, 4000)!!
            assertTrue(p.first in 0f..2999f && p.second in 0f..3999f)
            x += 7f
        }
    }

    @Test
    fun `the brush scale is the reciprocal of the source scale`() {
        val r = Fit.inside(boxW, boxH, 3000, 4000)
        val toBox = Fit.sourceToBox(r, 3000)
        val toSource = Fit.boxToSource(r, 3000)
        assertEquals(1f, toBox * toSource, 0.001f)
    }

    @Test
    fun `a degenerate box or bitmap is empty rather than a divide by zero`() {
        assertEquals(0f, Fit.inside(0f, 100f, 10, 10).width, 0f)
        assertEquals(0f, Fit.inside(100f, 0f, 10, 10).width, 0f)
        assertEquals(0f, Fit.inside(100f, 100f, 0, 0).width, 0f)
        assertNull(Fit.toSource(Fit.Rect(0f, 0f, 0f, 0f), 1f, 1f, 10, 10))
    }
}
