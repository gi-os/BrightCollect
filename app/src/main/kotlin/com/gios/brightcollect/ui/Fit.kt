package com.gios.brightcollect.ui

import kotlin.math.max
import kotlin.math.min

/**
 * Where a picture lands when it is fitted inside a box.
 *
 * Pulled out of the cut screen and made pure for one reason: **the code that draws the photograph
 * and the code that decides which pixel you touched have to agree exactly.** They were separate
 * before — the canvas was laid out with `fillMaxWidth().aspectRatio(...)` and the hit test assumed
 * the canvas *was* the picture — and the two disagreed the moment the box was shorter than the
 * aspect ratio asked for.
 *
 * That is the bug this replaces. `aspectRatio` after `fillMaxWidth` computes a height from the
 * width and does not care whether the parent has it: a portrait photograph on a box that is not
 * tall enough gets a height larger than the space, overflows, and is clipped top and bottom. The
 * photograph was not being fitted at all, and the part of it you could not see was the part you
 * could not paint on either.
 *
 * Free of Android imports, so both halves are unit-tested together.
 */
object Fit {

    /** A rectangle in box coordinates: where the picture is drawn. */
    data class Rect(val x: Float, val y: Float, val width: Float, val height: Float) {
        val right: Float get() = x + width
        val bottom: Float get() = y + height
    }

    /**
     * The largest centred rectangle of [srcWidth] x [srcHeight]'s shape that fits in the box.
     *
     * Contain, not cover. A cut screen that crops to fill would hide part of the photograph, and
     * the hidden part is exactly the part the wand cannot be aimed at.
     */
    fun inside(boxWidth: Float, boxHeight: Float, srcWidth: Int, srcHeight: Int): Rect {
        if (boxWidth <= 0f || boxHeight <= 0f || srcWidth <= 0 || srcHeight <= 0) {
            return Rect(0f, 0f, 0f, 0f)
        }
        val scale = min(boxWidth / srcWidth, boxHeight / srcHeight)
        val w = srcWidth * scale
        val h = srcHeight * scale
        return Rect(x = (boxWidth - w) / 2f, y = (boxHeight - h) / 2f, width = w, height = h)
    }

    /**
     * The source pixel under a touch at ([x], [y]) in box coordinates, or null outside the picture.
     *
     * Null rather than a clamp, deliberately. A tap in the letterbox margin is a tap on nothing,
     * and clamping it would put a wand fill on the outermost row of pixels — which on a photograph
     * is the background, so the whole subject would vanish in one tap that looked like a miss.
     */
    fun toSource(rect: Rect, x: Float, y: Float, srcWidth: Int, srcHeight: Int): Pair<Float, Float>? {
        if (rect.width <= 0f || rect.height <= 0f) return null
        if (x < rect.x || y < rect.y || x >= rect.right || y >= rect.bottom) return null
        val sx = (x - rect.x) / rect.width * srcWidth
        val sy = (y - rect.y) / rect.height * srcHeight
        return sx.coerceIn(0f, (srcWidth - 1).toFloat()) to sy.coerceIn(0f, (srcHeight - 1).toFloat())
    }

    /**
     * How many source pixels one box pixel covers.
     *
     * The brush is sized in source pixels — it paints the mask, which is at source resolution —
     * so the ring that previews it has to be drawn at the reciprocal, or the preview lies about
     * how big the brush is by whatever the fit scale happens to be.
     */
    fun boxToSource(rect: Rect, srcWidth: Int): Float =
        if (rect.width <= 0f) 1f else srcWidth / rect.width

    /** The other direction, for drawing something measured in source pixels onto the box. */
    fun sourceToBox(rect: Rect, srcWidth: Int): Float =
        if (srcWidth <= 0) 1f else max(0f, rect.width / srcWidth)
}
