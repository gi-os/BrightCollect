package com.gios.brightcollect.ui

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Laying stickers out the way things get laid out in a tray.
 *
 * The rigid three-column grid this replaces was wrong about the one thing the app is for. A grid
 * says every cell is the same and the contents are interchangeable; a collection is the opposite
 * claim. So each sticker occupies **its own bounding box** — the trimmed cutout, which is already
 * the object's silhouette — and the boxes are packed against each other rather than dropped into
 * slots. A tall thing has a short thing tucked beside it, and the result reads as objects put down
 * on a surface rather than as a table of results.
 *
 * Three decisions carry the look:
 *
 *  - **Area, not width, is what gets normalised.** Scaling everything to a common width makes a
 *    pencil a hairline and a plate enormous; scaling to a common *area* makes a pencil and a plate
 *    feel like one object each, which is what they are. The long edge is then clamped, because
 *    area-normalising a very long thin object still produces something that spans the screen.
 *  - **Skyline packing, not rows.** Row packing leaves a visible horizontal band every time the
 *    tallest item in a row sets the line — which is a grid again, just an uneven one. A skyline
 *    tracks the frontier across the full width and puts each item wherever it can sit *lowest*, so
 *    items interlock.
 *  - **Rotation is deterministic and the box is inflated for it.** A rotation seeded from the id
 *    survives scrolling, recomposition and a restart; one from a random number generator makes the
 *    shelf twitch every time it is drawn. And a rotated rectangle is bigger than its unrotated
 *    self, so the reserved box uses the rotated bounds or neighbours clip into each other.
 *
 * Free of Android imports, so the packing is unit-tested for overlap and bounds off-device. That
 * matters here because overlap is the failure everyone ships: it is invisible on the four stickers
 * you test with and obvious on the ninetieth.
 */
object Tray {

    /** How far a sticker may lean, in degrees. Small on purpose; this is a tray, not a collage. */
    const val MAX_TILT = 5f

    /** Size jitter either side of the target area, as a fraction. */
    private const val JITTER = 0.14f

    /**
     * The long edge a sticker is *preferred* to stay under, as a fraction of the container.
     *
     * Soft, not hard — see [sizeFor]. Something genuinely long and thin has to be allowed past it
     * or it comes out as a hairline, which is the failure area-normalising is supposed to prevent.
     */
    private const val MAX_EDGE_FRACTION = 0.36f

    /** The short edge a sticker is preferred to stay above, for the same reason. */
    private const val MIN_EDGE = 34

    /**
     * How dense the tray is, as a divisor of the container's area.
     *
     * A sticker is scaled towards `containerWidth² / DENSITY`, so the average one is about
     * `containerWidth / sqrt(DENSITY)` on a side — meaning `sqrt(DENSITY)` of them fit across.
     * 25 puts four across, measured rather than reasoned: packing loses some of it to the gap and
     * to the rotation inflating each box, so the arithmetic alone lands about ten per cent low.
     *
     * Expressed as a divisor of the width rather than a dp number so the tray has the same
     * density on any screen, which is the same reason the type scale is a fraction of the height.
     */
    private const val DENSITY = 25

    data class Item(val id: String, val width: Int, val height: Int)

    /**
     * One sticker's place in the tray.
     *
     * [x] and [y] are the top-left of the *reserved* box, which is the rotated bounding box.
     * [width] and [height] are the size to draw the image at, before rotation — so the drawing
     * code centres a [width] x [height] image inside a [boxWidth] x [boxHeight] slot and turns it.
     */
    data class Placed(
        val id: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val boxWidth: Int,
        val boxHeight: Int,
        val angle: Float,
    ) {
        val right: Int get() = x + boxWidth
        val bottom: Int get() = y + boxHeight
    }

    data class Layout(val placed: List<Placed>, val height: Int)

    /** The area each sticker is scaled towards, for a tray [containerWidth] wide. See [DENSITY]. */
    fun targetAreaFor(containerWidth: Int): Int =
        max(1, containerWidth * containerWidth / DENSITY)

    /**
     * Packs [items] into [containerWidth], in whatever units the caller is using (dp here).
     *
     * [targetArea] sets the overall density — the area each sticker is scaled towards before
     * jitter and clamping. Order is preserved as given: the shelf is newest first, and packing
     * largest-first would tighten the result at the cost of scrambling chronology, which is a
     * worse trade in a collection than a few gaps.
     */
    fun lay(
        items: List<Item>,
        containerWidth: Int,
        targetArea: Int,
        gap: Int = 4,
    ): Layout {
        if (items.isEmpty() || containerWidth <= 0) return Layout(emptyList(), 0)

        val skyline = Skyline(containerWidth)
        val out = ArrayList<Placed>(items.size)

        for (item in items) {
            val seed = seedOf(item.id)
            val (w, h) = sizeFor(item, containerWidth, targetArea, seed)
            val angle = tiltFor(seed)
            val bw = min(containerWidth, rotatedWidth(w, h, angle) + gap)
            val bh = rotatedHeight(w, h, angle) + gap
            // The y is taken from `fit`, not read back out of the skyline after `raise`.
            // Deriving it as `topAt(x) - boxHeight` happens to be correct and is one refactor
            // away from being silently wrong.
            val spot = skyline.fit(bw)
            skyline.raise(spot.x, bw, bh)
            out += Placed(
                id = item.id,
                x = spot.x,
                y = spot.y,
                width = w,
                height = h,
                boxWidth = bw,
                boxHeight = bh,
                angle = angle,
            )
        }
        return Layout(out, out.maxOfOrNull { it.bottom } ?: 0)
    }

    /**
     * The size to draw a sticker at.
     *
     * Aspect ratio is preserved throughout — a sticker stretched to fill a box is a sticker of
     * something that does not exist.
     */
    internal fun sizeFor(item: Item, containerWidth: Int, targetArea: Int, seed: Int): Pair<Int, Int> {
        val w0 = max(1, item.width)
        val h0 = max(1, item.height)
        // Jitter first, so two identically-shaped objects do not come out identical.
        val wobble = 1f + JITTER * unitFrom(seed, 1)
        val scale = sqrt((targetArea * wobble) / (w0.toFloat() * h0))
        var w = w0 * scale
        var h = h0 * scale

        /*
         * Three passes, and the order is the whole of it.
         *
         * The first version applied the long-edge cap and the short-edge floor as two independent
         * clamps, and they fight: past an aspect ratio of about softCap/MIN_EDGE the floor scales
         * the sticker up and the cap immediately scales it back down, so the cap always wins and a
         * long thin object comes out as a four-pixel hairline. Which is exactly the outcome
         * normalising by area is supposed to prevent.
         *
         * So the floor goes first and the *preferred* cap is allowed to decline. Only the
         * container width is absolute, because a sticker wider than the tray cannot be placed.
         * Something genuinely long — a belt, a pencil — therefore spans the tray, which is what it
         * looks like in a flat lay and is honest about the object's shape.
         */
        val softCap = containerWidth * MAX_EDGE_FRACTION

        if (min(w, h) < MIN_EDGE) {
            val k = MIN_EDGE / min(w, h)
            w *= k
            h *= k
        }
        if (max(w, h) > softCap) {
            val k = softCap / max(w, h)
            // Declined when honouring it would put the short edge under the floor.
            if (min(w, h) * k >= MIN_EDGE) {
                w *= k
                h *= k
            }
        }
        if (max(w, h) > containerWidth) {
            val k = containerWidth / max(w, h)
            w *= k
            h *= k
        }
        return max(1, w.roundToInt()) to max(1, h.roundToInt())
    }

    internal fun tiltFor(seed: Int): Float = MAX_TILT * unitFrom(seed, 2)

    internal fun rotatedWidth(w: Int, h: Int, angle: Float): Int {
        val r = Math.toRadians(angle.toDouble())
        return (abs(w * cos(r)) + abs(h * sin(r))).roundToInt()
    }

    internal fun rotatedHeight(w: Int, h: Int, angle: Float): Int {
        val r = Math.toRadians(angle.toDouble())
        return (abs(w * sin(r)) + abs(h * cos(r))).roundToInt()
    }

    /**
     * A stable number for an id.
     *
     * `String.hashCode` would do and is deliberately not used: it is specified for `String` and so
     * is stable, but reaching for a well-mixed hash costs three lines and removes the question. The
     * ids are twelve hex characters, which the JDK hash spreads unevenly in the low bits — and the
     * low bits are exactly what [unitFrom] reads.
     */
    internal fun seedOf(id: String): Int {
        var h = -0x7ee3623b // FNV-ish offset
        for (c in id) {
            h = h xor c.code
            h *= 0x01000193
        }
        return h
    }

    /** A repeatable value in -1..1 from [seed] and a [salt], so one id can drive several choices. */
    internal fun unitFrom(seed: Int, salt: Int): Float {
        var x = seed xor (salt * -0x61c88647)
        x = x xor (x ushr 16)
        x *= -0x7ee3623b
        x = x xor (x ushr 13)
        x *= -0x3d4d51cb
        x = x xor (x ushr 16)
        return ((x ushr 8) % 20001) / 10000f - 1f
    }

    /**
     * The frontier: the height of the packed content at every x, as a run-length list.
     *
     * A plain `IntArray` of per-pixel heights is simpler and was the first version. It is O(width)
     * to query and O(width) to update, which at 360 columns and several hundred stickers is tens of
     * millions of operations — enough to be felt when the shelf opens. Segments keep the same
     * answer with a handful of entries, because a real frontier only has as many steps as there are
     * items currently on it.
     */
    private data class Spot(val x: Int, val y: Int)

    private class Skyline(val width: Int) {
        /** Parallel arrays of segment start and height, covering [0, width) with no gaps. */
        private val xs = ArrayList<Int>().apply { add(0) }
        private val ys = ArrayList<Int>().apply { add(0) }

        fun topAt(x: Int): Int = ys[indexOf(x)]

        private fun indexOf(x: Int): Int {
            var i = xs.size - 1
            while (i > 0 && xs[i] > x) i--
            return i
        }

        private fun endOf(i: Int): Int = if (i + 1 < xs.size) xs[i + 1] else width

        /** The highest point of the frontier under [x, x + w). */
        private fun peak(x: Int, w: Int): Int {
            var top = 0
            var i = indexOf(x)
            while (i < xs.size && xs[i] < x + w) {
                top = max(top, ys[i])
                i++
            }
            return top
        }

        /**
         * Where a box of width [w] sits lowest.
         *
         * Only segment starts are considered as candidates. Any position between two starts has the
         * same peak as the start to its left but is further right, so it can never be strictly
         * better — and considering every pixel is what made the first version slow.
         */
        fun fit(w: Int): Spot {
            var bestX = 0
            var bestY = Int.MAX_VALUE
            for (i in xs.indices) {
                val x = xs[i]
                if (x + w > width) break
                val y = peak(x, w)
                if (y < bestY) {
                    bestY = y
                    bestX = x
                }
            }
            // No segment start leaves room — the box is as wide as the container, or every
            // candidate runs off the right edge. Flush left, on top of everything.
            if (bestY == Int.MAX_VALUE) return Spot(0, peak(0, w))
            return Spot(bestX, bestY)
        }

        /** Records a box of [w] x [h] placed at [x], sitting on the frontier. */
        fun raise(x: Int, w: Int, h: Int) {
            val top = peak(x, w) + h
            val end = min(width, x + w)
            // What the frontier reads immediately to the right of this box, read before the
            // segments under it are removed. Only meaningful when the box stops short of the edge.
            val after = if (end < width) topAt(end) else 0

            var i = 0
            while (i < xs.size) {
                if (xs[i] >= x && xs[i] < end) {
                    xs.removeAt(i)
                    ys.removeAt(i)
                } else {
                    i++
                }
            }
            var at = xs.indexOfFirst { it > x }
            if (at < 0) at = xs.size
            xs.add(at, x)
            ys.add(at, top)
            if (end < width) {
                xs.add(at + 1, end)
                ys.add(at + 1, after)
            }
            // Merge neighbours that ended up at the same height, so the segment count stays near
            // the number of real steps rather than growing once per placement forever.
            var j = 1
            while (j < xs.size) {
                if (ys[j] == ys[j - 1]) {
                    xs.removeAt(j)
                    ys.removeAt(j)
                } else {
                    j++
                }
            }
        }
    }
}
