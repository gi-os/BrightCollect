package com.gios.brightcollect.ui

import kotlin.math.max
import kotlin.math.min

/**
 * Zoom and pan on top of the fitted picture.
 *
 * [Fit] answers where the whole photograph sits when it is shown whole. This answers where it
 * sits once you have zoomed into a corner of it, and it is deliberately the same shape of
 * answer — a [Fit.Rect] — so everything downstream is unchanged. The drawing, the hit testing
 * and the brush preview all keep reading one rectangle; only the rectangle moves.
 *
 * That is the whole design. The alternative, threading a scale and an offset through every call
 * site, is how a zoomable editor ends up with the picture and the touches disagreeing at some
 * zoom levels but not others.
 *
 * Free of Android imports, so the clamping is unit-tested — and clamping is where this kind of
 * code goes wrong, in the direction of letting the picture be flung off the screen and stranded.
 */
object Viewport {

    /** Fit-to-screen. Below this the picture would float in the middle with margins all round. */
    const val MIN_SCALE = 1f

    /**
     * Sixteen times.
     *
     * Enough to put a single source pixel under a fingertip on a 1600px working image, which is
     * what "zoom in to fix the edge" actually needs. Past it the brush is painting one pixel per
     * stroke and the screen is a colour swatch.
     */
    const val MAX_SCALE = 16f

    /**
     * The picture's rectangle at [scale], panned by [panX] and [panY].
     *
     * Zoom is about the centre of the fitted rectangle, and the pan is applied afterwards in box
     * coordinates — which is what makes a pinch feel like it is scaling the thing under your
     * fingers rather than the thing under the corner of the screen.
     */
    fun rect(fit: Fit.Rect, scale: Float, panX: Float, panY: Float): Fit.Rect {
        val s = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        val w = fit.width * s
        val h = fit.height * s
        val cx = fit.x + fit.width / 2f
        val cy = fit.y + fit.height / 2f
        return Fit.Rect(x = cx - w / 2f + panX, y = cy - h / 2f + panY, width = w, height = h)
    }

    /**
     * The pan, corrected so the picture cannot be stranded off screen.
     *
     * Two cases, and they are opposites, which is the part that is easy to get wrong:
     *
     *  - **The picture is larger than the box on an axis.** Then panning is real, and the limit is
     *    that no edge may come inside the box — you can reach any part of the picture but not drag
     *    past its border into empty space.
     *  - **It is smaller** — which happens on the other axis at any zoom, because the fitted
     *    rectangle only touches the box on one axis to begin with. Then there is nothing to pan
     *    to, and the correct answer is to pin it centred rather than to let it slide about.
     */
    fun clamp(
        fit: Fit.Rect,
        boxWidth: Float,
        boxHeight: Float,
        scale: Float,
        panX: Float,
        panY: Float,
    ): Pair<Float, Float> {
        val r = rect(fit, scale, 0f, 0f)
        return axis(r.x, r.width, boxWidth, panX) to axis(r.y, r.height, boxHeight, panY)
    }

    private fun axis(origin: Float, length: Float, box: Float, pan: Float): Float {
        if (length <= box) {
            // Smaller than the box: centred, and the pan is discarded rather than clamped to a
            // range of zero — the two are the same number here, but saying "centre it" is what
            // this means.
            return (box - length) / 2f - origin
        }
        // Larger: the near edge may not move right of 0, the far edge not left of `box`.
        val lo = box - length - origin
        val hi = -origin
        return pan.coerceIn(min(lo, hi), max(lo, hi))
    }

    /**
     * A new scale and pan that keep the point under [focusX], [focusY] under it.
     *
     * The reason a pinch feels wrong when this is missing: scaling about the centre moves whatever
     * you had pinched, so the picture squirms away from your fingers and you chase it. Solving for
     * the pan that holds one box point fixed is the whole of "zoom where I am pointing".
     */
    fun zoomAround(
        fit: Fit.Rect,
        boxWidth: Float,
        boxHeight: Float,
        scale: Float,
        panX: Float,
        panY: Float,
        factor: Float,
        focusX: Float,
        focusY: Float,
    ): Triple<Float, Float, Float> {
        val from = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        val to = (from * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        if (to == from) {
            val (cx, cy) = clamp(fit, boxWidth, boxHeight, from, panX, panY)
            return Triple(from, cx, cy)
        }
        val before = rect(fit, from, panX, panY)
        // Where the focus sits in the picture, as a fraction of it.
        val u = (focusX - before.x) / before.width
        val v = (focusY - before.y) / before.height
        val after = rect(fit, to, 0f, 0f)
        val nextX = focusX - (after.x + u * after.width)
        val nextY = focusY - (after.y + v * after.height)
        val (cx, cy) = clamp(fit, boxWidth, boxHeight, to, nextX, nextY)
        return Triple(to, cx, cy)
    }

    /**
     * The brush radius in box pixels, given a radius in source pixels.
     *
     * The brush is defined in source pixels because it paints the mask. Left alone, that means
     * zooming in makes it cover less of the picture and *feel* like it is growing on screen — so
     * the size you set while zoomed out is not the size you get while zoomed in, which is exactly
     * backwards for a tool you zoom in to use carefully.
     */
    fun brushOnScreen(rect: Fit.Rect, srcWidth: Int, brushInSource: Float): Float =
        if (srcWidth <= 0) brushInSource else brushInSource * (rect.width / srcWidth)

    /** The reverse: a radius that looks constant on screen, expressed in source pixels. */
    fun brushInSource(rect: Fit.Rect, srcWidth: Int, brushOnScreen: Float): Float =
        if (rect.width <= 0f) brushOnScreen else brushOnScreen * (srcWidth / rect.width)
}
