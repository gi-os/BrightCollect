package com.gios.brightcollect

import com.gios.brightcollect.ui.Fit
import com.gios.brightcollect.ui.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ViewportTest {

    private val boxW = 343f
    private val boxH = 480f
    private val src = 3000 to 4000
    private val fit = Fit.inside(boxW, boxH, src.first, src.second)

    @Test
    fun `at fit the viewport is the fitted rect`() {
        val r = Viewport.rect(fit, 1f, 0f, 0f)
        assertEquals(fit.x, r.x, 0.01f)
        assertEquals(fit.width, r.width, 0.01f)
    }

    @Test
    fun `zoom scales about the centre`() {
        val r = Viewport.rect(fit, 2f, 0f, 0f)
        assertEquals(fit.width * 2, r.width, 0.01f)
        assertEquals(fit.x + fit.width / 2f, r.x + r.width / 2f, 0.01f)
    }

    @Test
    fun `scale is clamped both ways`() {
        assertEquals(fit.width, Viewport.rect(fit, 0.2f, 0f, 0f).width, 0.01f)
        assertEquals(
            fit.width * Viewport.MAX_SCALE,
            Viewport.rect(fit, 999f, 0f, 0f).width,
            0.01f,
        )
    }

    @Test
    fun `the picture cannot be flung off the screen`() {
        // The failure this exists for: pan far enough and the photograph is stranded outside the
        // box with no gesture that brings it back.
        val (px, py) = Viewport.clamp(fit, boxW, boxH, 4f, 99_999f, -99_999f)
        val r = Viewport.rect(fit, 4f, px, py)
        assertTrue("left edge ${r.x} must not come inside the box", r.x <= 0.01f)
        assertTrue("right edge ${r.right} must not come inside", r.right >= boxW - 0.01f)
        assertTrue("bottom ${r.bottom} must not come inside", r.bottom >= boxH - 0.01f)
    }

    @Test
    fun `an axis smaller than the box is centred rather than free to slide`() {
        // At fit, a portrait picture in a portrait box touches top and bottom but not the sides,
        // so there is nothing to pan to horizontally and the answer is to pin it.
        val (px, _) = Viewport.clamp(fit, boxW, boxH, 1f, 500f, 0f)
        val r = Viewport.rect(fit, 1f, px, 0f)
        assertEquals(boxW - r.right, r.x, 0.01f)
    }

    @Test
    fun `zooming holds the point under your fingers`() {
        // Without this a pinch scales about the centre, the thing you pinched slides away, and
        // you chase it across the screen.
        val focusX = fit.x + fit.width * 0.8f
        val focusY = fit.y + fit.height * 0.25f
        val before = Viewport.rect(fit, 1f, 0f, 0f)
        val u = (focusX - before.x) / before.width
        val v = (focusY - before.y) / before.height

        val (scale, px, py) = Viewport.zoomAround(
            fit, boxW, boxH, 1f, 0f, 0f, factor = 3f, focusX = focusX, focusY = focusY,
        )
        val after = Viewport.rect(fit, scale, px, py)
        // Allow for the clamp pulling it back when the focus is near an edge.
        val heldX = after.x + u * after.width
        val heldY = after.y + v * after.height
        assertTrue("x moved ${abs(heldX - focusX)}", abs(heldX - focusX) < boxW * 0.5f)
        assertTrue("y moved ${abs(heldY - focusY)}", abs(heldY - focusY) < boxH * 0.5f)
        assertEquals(3f, scale, 0.001f)
    }

    @Test
    fun `zooming out from fit stays at fit`() {
        val (scale, px, py) = Viewport.zoomAround(
            fit, boxW, boxH, 1f, 0f, 0f, factor = 0.5f, focusX = boxW / 2, focusY = boxH / 2,
        )
        assertEquals(1f, scale, 0.001f)
        val r = Viewport.rect(fit, scale, px, py)
        assertEquals(fit.x, r.x, 0.01f)
        assertEquals(fit.y, r.y, 0.01f)
    }

    @Test
    fun `a tap maps to the same pixel at every zoom`() {
        // The whole point of folding zoom into the rect: everything downstream keeps reading one
        // rectangle, so the picture and the touches cannot disagree at some zoom levels.
        val centre = Fit.toSource(
            Viewport.rect(fit, 1f, 0f, 0f),
            fit.x + fit.width / 2f,
            fit.y + fit.height / 2f,
            src.first,
            src.second,
        )!!
        listOf(2f, 5f, 12f).forEach { s ->
            val (_, px, py) = Viewport.zoomAround(
                fit, boxW, boxH, 1f, 0f, 0f, s, fit.x + fit.width / 2f, fit.y + fit.height / 2f,
            )
            val r = Viewport.rect(fit, s, px, py)
            val p = Fit.toSource(
                r, fit.x + fit.width / 2f, fit.y + fit.height / 2f, src.first, src.second,
            )!!
            assertEquals("at ${s}x", centre.first, p.first, src.first * 0.06f)
            assertEquals("at ${s}x", centre.second, p.second, src.second * 0.06f)
        }
    }

    @Test
    fun `the brush keeps its size on screen as you zoom`() {
        // It is measured in source pixels because it paints the mask, so left alone it would
        // appear to grow as you zoom in — backwards for a tool you zoom in to use carefully.
        val atFit = Viewport.brushOnScreen(Viewport.rect(fit, 1f, 0f, 0f), src.first, 48f)
        val atFour = Viewport.brushOnScreen(Viewport.rect(fit, 4f, 0f, 0f), src.first, 48f)
        assertEquals(atFit * 4f, atFour, 0.01f)
        // And the round trip is the identity.
        val r = Viewport.rect(fit, 4f, 0f, 0f)
        assertEquals(48f, Viewport.brushInSource(r, src.first, atFour), 0.01f)
    }
}
