package com.gios.brightcollect.cut

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * An 8-bit alpha mask and the arithmetic done to it.
 *
 * Deliberately free of Android imports. Everything that decides which pixels end up in a
 * sticker happens here, so it can be tested on the JVM instead of on a phone — which matters
 * more than usual in this app, because "the cutout looks wrong" is otherwise a bug you can
 * only reproduce by holding the thing and taking a photograph of it.
 *
 * Values are 0..255 in a [ByteArray] read unsigned. A [ByteArray] rather than an [IntArray]
 * because a 4000x3000 mask is 12 MB one way and 48 MB the other, and the phone has to hold
 * the source bitmap at the same time.
 */
class Mask(val width: Int, val height: Int, val a: ByteArray = ByteArray(width * height)) {

    init {
        require(width > 0 && height > 0) { "empty mask" }
        require(a.size == width * height) { "mask is ${a.size}, expected ${width * height}" }
    }

    operator fun get(x: Int, y: Int): Int = a[y * width + x].toInt() and 0xFF

    operator fun set(x: Int, y: Int, v: Int) {
        a[y * width + x] = v.coerceIn(0, 255).toByte()
    }

    fun copy(): Mask = Mask(width, height, a.copyOf())

    /** The fraction of the mask that is more opaque than [t]. Cheap sanity check. */
    fun coverage(t: Int = 128): Float {
        var n = 0
        for (i in a.indices) if ((a[i].toInt() and 0xFF) > t) n++
        return n.toFloat() / a.size
    }

    /**
     * The tightest box holding everything above [t], or null if the mask is empty.
     *
     * Used to trim the sticker down to the object. A sticker stored at the full frame size
     * would be mostly transparent, and would draw at postage-stamp size in a grid cell that is
     * showing the empty margin at full scale.
     */
    fun bounds(t: Int = 8): IntArray? {
        var x0 = width; var y0 = height; var x1 = -1; var y1 = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if ((a[row + x].toInt() and 0xFF) > t) {
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
            }
        }
        return if (x1 < 0) null else intArrayOf(x0, y0, x1, y1)
    }

    /**
     * Paints a soft-edged disc of [value] at ([cx], [cy]).
     *
     * The brush and the eraser are the same call with value 255 and 0. The edge is a linear
     * ramp over the outer 25% of the radius rather than a hard circle, because a hard-edged
     * brush on a mask that is then feathered again produces a visible stair, and on a screen
     * this size you are painting with a fingertip you cannot see under.
     */
    fun paint(cx: Float, cy: Float, radius: Float, value: Int) {
        val r = max(1f, radius)
        val soft = r * 0.75f
        val x0 = max(0, (cx - r).toInt()); val x1 = min(width - 1, (cx + r).roundToInt())
        val y0 = max(0, (cy - r).toInt()); val y1 = min(height - 1, (cy + r).roundToInt())
        for (y in y0..y1) {
            val dy = y - cy
            val row = y * width
            for (x in x0..x1) {
                val dx = x - cx
                val d = sqrt(dx * dx + dy * dy)
                if (d > r) continue
                // 1 inside the core, ramping to 0 at the rim.
                val w = if (d <= soft) 1f else 1f - (d - soft) / (r - soft)
                val i = row + x
                val old = a[i].toInt() and 0xFF
                val blended = old + ((value - old) * w)
                a[i] = blended.roundToInt().coerceIn(0, 255).toByte()
            }
        }
    }

    /** Sets every pixel of [region] to [value]. The magic wand's commit step. */
    fun applyRegion(region: BooleanArray, value: Int) {
        require(region.size == a.size) { "region does not match the mask" }
        val v = value.coerceIn(0, 255).toByte()
        for (i in region.indices) if (region[i]) a[i] = v
    }

    companion object {

        /**
         * Builds a mask from the model's output.
         *
         * u2netp emits a sigmoid map that does not reliably span 0..1 — a photograph with a
         * low-contrast subject can come back with everything between 0.2 and 0.6, which
         * thresholds to either the whole frame or nothing. Rescaling to the observed range
         * first is what makes a single threshold work across photographs.
         *
         * A range that barely moves at all means the model found nothing to be confident
         * about. Stretching that to full contrast would manufacture an edge out of noise, so
         * below [FLAT] the map is passed through unstretched and the caller sees a weak mask
         * for what it is.
         */
        const val FLAT = 0.05f

        fun fromScores(scores: FloatArray, width: Int, height: Int): Mask {
            require(scores.size == width * height) { "scores do not match ${width}x$height" }
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (s in scores) { if (s < lo) lo = s; if (s > hi) hi = s }
            val span = hi - lo
            val stretch = span > FLAT
            val m = Mask(width, height)
            for (i in scores.indices) {
                val v = if (stretch) (scores[i] - lo) / span else scores[i].coerceIn(0f, 1f)
                m.a[i] = (v * 255f).roundToInt().coerceIn(0, 255).toByte()
            }
            return m
        }

        /**
         * Nearest-neighbour resize.
         *
         * The model works at 320x320 and the photograph is thousands of pixels wide, so the
         * mask is scaled up by a factor of ten or more. Nearest on the way up then [feather]
         * over the result, rather than a bilinear resize: bilinear across a 10x jump spreads
         * the edge over ten pixels of the *source* grid, which is a soft band with visible
         * 320-grid steps in it. A hard upscale followed by a blur in the target resolution
         * gives an edge whose softness is measured in real pixels.
         */
        fun scale(src: Mask, width: Int, height: Int): Mask {
            val out = Mask(width, height)
            for (y in 0 until height) {
                val sy = (y.toLong() * src.height / height).toInt().coerceIn(0, src.height - 1)
                val srow = sy * src.width
                val drow = y * width
                for (x in 0 until width) {
                    val sx = (x.toLong() * src.width / width).toInt().coerceIn(0, src.width - 1)
                    out.a[drow + x] = src.a[srow + sx]
                }
            }
            return out
        }
    }
}

/**
 * A separable box blur, run [passes] times.
 *
 * Three passes of a box blur approximate a Gaussian closely enough that nobody can tell on an
 * alpha edge, and it is O(n) per pass with no kernel — which matters when this runs on a
 * 12-megapixel mask on a phone.
 *
 * This is the whole of the edge treatment. Without it a cutout has the hard stair-stepped
 * outline that makes a sticker look like a bad selection rather than a die cut.
 */
fun Mask.feather(radius: Int, passes: Int = 3): Mask {
    if (radius <= 0) return this
    // A copy, not `a`. The vertical pass writes back into its source buffer, so working on
    // the mask's own array would blur the receiver in place — and every caller here treats
    // feather as a pure function and keeps the unfeathered mask to re-derive from.
    val cur = a.copyOf()
    val tmp = ByteArray(a.size)
    repeat(passes) {
        blurRows(cur, tmp, width, height, radius)
        // Transpose-free vertical pass: blur the columns by walking with a stride.
        blurCols(tmp, cur, width, height, radius)
    }
    return Mask(width, height, cur)
}

private fun blurRows(src: ByteArray, dst: ByteArray, w: Int, h: Int, r: Int) {
    val win = 2 * r + 1
    for (y in 0 until h) {
        val row = y * w
        var sum = 0
        // Prime the window with the clamped left edge.
        for (i in -r..r) sum += src[row + i.coerceIn(0, w - 1)].toInt() and 0xFF
        for (x in 0 until w) {
            dst[row + x] = (sum / win).toByte()
            val out = src[row + (x - r).coerceIn(0, w - 1)].toInt() and 0xFF
            val inc = src[row + (x + r + 1).coerceIn(0, w - 1)].toInt() and 0xFF
            sum += inc - out
        }
    }
}

private fun blurCols(src: ByteArray, dst: ByteArray, w: Int, h: Int, r: Int) {
    val win = 2 * r + 1
    for (x in 0 until w) {
        var sum = 0
        for (i in -r..r) sum += src[i.coerceIn(0, h - 1) * w + x].toInt() and 0xFF
        for (y in 0 until h) {
            dst[y * w + x] = (sum / win).toByte()
            val out = src[(y - r).coerceIn(0, h - 1) * w + x].toInt() and 0xFF
            val inc = src[(y + r + 1).coerceIn(0, h - 1) * w + x].toInt() and 0xFF
            sum += inc - out
        }
    }
}

/**
 * Pushes the mask towards black and white, leaving the ramp only where it belongs.
 *
 * The model's map is soft everywhere, including deep inside the object, so a sticker made
 * straight from it is faintly transparent all over. This maps everything below [low] to 0,
 * everything above [high] to 255, and ramps between — so the interior is solid, the outside
 * is gone, and the edge keeps its gradient.
 */
fun Mask.harden(low: Int = 60, high: Int = 190): Mask {
    require(high > low) { "harden needs high > low" }
    val lut = IntArray(256) { v ->
        when {
            v <= low -> 0
            v >= high -> 255
            else -> ((v - low) * 255) / (high - low)
        }
    }
    val out = ByteArray(a.size)
    for (i in a.indices) out[i] = lut[a[i].toInt() and 0xFF].toByte()
    return Mask(width, height, out)
}

/**
 * True where [a] and [b] disagree by more than [tolerance] in any channel.
 *
 * Chebyshev distance rather than Euclidean, because it is what a tolerance slider feels like:
 * "within N of this colour" reads as a box around the sample, and a Euclidean ball lets a
 * pixel that is far off in one channel in while keeping out one that is slightly off in three.
 */
internal fun differs(a: Int, b: Int, tolerance: Int): Boolean {
    val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
    val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
    val db = abs((a and 0xFF) - (b and 0xFF))
    return max(dr, max(dg, db)) > tolerance
}
