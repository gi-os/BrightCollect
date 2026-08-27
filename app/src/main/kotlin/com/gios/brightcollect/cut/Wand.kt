package com.gios.brightcollect.cut

/**
 * The magic wand: the contiguous run of pixels that look like the one you tapped.
 *
 * **Why a flood fill and not another pass of the model.** When u2netp gets a photograph wrong
 * it is usually wrong about a whole *region* — it drops the shadowed half of a mug, or keeps
 * the table the object is sitting on. Fixing that with a brush means scrubbing over hundreds
 * of pixels with a fingertip on a small screen. One tap on the table is the correct size of
 * gesture for the correction, and colour contiguity is what actually separates an object from
 * the surface under it in the kind of photograph this app is for: a thing, on a plain-ish
 * background, photographed deliberately.
 *
 * Scanline fill rather than the four-neighbour recursion everyone writes first. A 12-megapixel
 * photograph is twelve million pixels and the recursive version overflows the stack on the
 * first large region; the scanline version pushes one entry per *span* rather than per pixel,
 * which on a photograph is a few thousand entries rather than a few million.
 *
 * Everything here is Android-free and works on a plain [IntArray] of packed ARGB, so the fill
 * is unit-tested rather than eyeballed on a phone.
 */
object Wand {

    /** Beyond this a fill on a photograph reaches the whole frame and stops meaning anything. */
    const val MAX_TOLERANCE = 128

    /**
     * The region reachable from ([x], [y]) without any pixel differing from the seed colour by
     * more than [tolerance].
     *
     * Returns a [BooleanArray] the size of the image, for [Mask.applyRegion]. A separate array
     * rather than writing the mask directly, because the caller shows the region as a
     * highlight before committing it and may throw it away.
     */
    fun select(
        pixels: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        tolerance: Int,
    ): BooleanArray {
        require(pixels.size == width * height) { "pixels do not match ${width}x$height" }
        val out = BooleanArray(pixels.size)
        if (x !in 0 until width || y !in 0 until height) return out

        val tol = tolerance.coerceIn(0, MAX_TOLERANCE)
        val seed = pixels[y * width + x]

        // Each entry is a row and the span on it to grow from.
        val stack = ArrayDeque<Int>()
        stack.addLast(y * width + x)

        while (stack.isNotEmpty()) {
            val start = stack.removeLast()
            if (out[start]) continue
            val row = start / width
            val rowStart = row * width

            // Walk left and right to the ends of this span.
            var left = start
            while (left > rowStart && !out[left - 1] && !differs(pixels[left - 1], seed, tol)) left--
            var right = start
            while (right < rowStart + width - 1 && !out[right + 1] &&
                !differs(pixels[right + 1], seed, tol)
            ) {
                right++
            }
            for (i in left..right) out[i] = true

            // Seed the rows above and below, once per contiguous run rather than once per
            // pixel — this is the whole difference between this and the naive version.
            if (row > 0) seedRun(pixels, out, seed, tol, left - width, right - width, stack)
            if (row < height - 1) seedRun(pixels, out, seed, tol, left + width, right + width, stack)
        }
        return out
    }

    private fun seedRun(
        pixels: IntArray,
        out: BooleanArray,
        seed: Int,
        tol: Int,
        from: Int,
        to: Int,
        stack: ArrayDeque<Int>,
    ) {
        var i = from
        while (i <= to) {
            if (!out[i] && !differs(pixels[i], seed, tol)) {
                stack.addLast(i)
                // Skip the rest of this run: any of its pixels would start the same span.
                while (i <= to && !out[i] && !differs(pixels[i], seed, tol)) i++
            } else {
                i++
            }
        }
    }

    /**
     * Grows a region by [by] pixels in each direction.
     *
     * A fill stopped by a tolerance always stops a pixel or two short of the real boundary,
     * because the last pixels before an edge are a blend of both sides and match neither. Left
     * alone that shows up as a halo of background colour clinging to the sticker. Growing the
     * selection before subtracting it is what removes the halo, and it is why the wand looks
     * clean rather than "nearly right" — the same reason the feather exists on the model's own
     * edge.
     */
    fun grow(region: BooleanArray, width: Int, height: Int, by: Int): BooleanArray {
        if (by <= 0) return region
        var cur = region
        repeat(by) {
            val next = cur.copyOf()
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    val i = row + x
                    if (cur[i]) continue
                    val touching =
                        (x > 0 && cur[i - 1]) ||
                            (x < width - 1 && cur[i + 1]) ||
                            (y > 0 && cur[i - width]) ||
                            (y < height - 1 && cur[i + width])
                    if (touching) next[i] = true
                }
            }
            cur = next
        }
        return cur
    }

    /** How many pixels a region holds. Used to refuse a fill that swallowed the frame. */
    fun size(region: BooleanArray): Int = region.count { it }
}
