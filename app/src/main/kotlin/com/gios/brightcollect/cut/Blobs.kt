package com.gios.brightcollect.cut

/**
 * Connected regions of a mask, and dropping all but the biggest.
 *
 * **The single most common way a cutout looks wrong.** u2netp is a saliency model, not an object
 * detector: it answers "how much does this pixel stand out" per pixel, with no notion that the
 * answer should form one thing. So a photograph of a mug on a worktop routinely comes back as the
 * mug *plus* a bright speck of the tap, a corner of a tile and three pixels of a reflection. Every
 * one of those is a separate island floating in the sticker, and no amount of thresholding removes
 * them — they are as confident as the mug.
 *
 * The fix is not statistical, it is topological: an object is connected. Label the regions, keep
 * the one with the most pixels, throw the rest away.
 *
 * **Four-connectivity, not eight.** Eight-connectivity treats pixels touching only at a corner as
 * joined, which bridges the object to a speck that happens to sit diagonally against it — and the
 * bridge is invisible at the size you would inspect it. Four is the stricter reading of "connected"
 * and the one that matches what the eye calls one shape.
 *
 * Free of Android imports; the labelling is unit-tested rather than eyeballed on a phone.
 */
object Blobs {

    /** A pixel is part of the object above this. Matches the threshold [Mask.bounds] uses. */
    const val SOLID = 8

    /**
     * Keeps only the largest connected region of [mask], in place.
     *
     * Returns how many pixels were dropped, which is what the caller reports — "removed three
     * specks" is worth saying, and a cleanup that did nothing should say nothing.
     *
     * Iterative, with an explicit stack of runs. The recursive version overflows on the first
     * real photograph: a mug at working resolution is a few hundred thousand connected pixels
     * and every one of them would be a frame.
     */
    fun keepLargest(mask: Mask): Int {
        val labels = label(mask)
        if (labels.count <= 1) return 0

        var best = 0
        var bestSize = 0
        for (i in 1..labels.count) {
            if (labels.sizes[i] > bestSize) {
                bestSize = labels.sizes[i]
                best = i
            }
        }

        var dropped = 0
        for (i in mask.a.indices) {
            val label = labels.of[i]
            if (label != 0 && label != best) {
                mask.a[i] = 0
                dropped++
            }
        }
        return dropped
    }

    /** The labelling itself, exposed so the tests can look at the regions rather than the result. */
    data class Labels(
        /** Region number per pixel, 0 for background. */
        val of: IntArray,
        /** Pixel count per region, indexed by region number; index 0 is unused. */
        val sizes: IntArray,
        /** How many regions were found. */
        val count: Int,
    ) {
        // Arrays in a data class compare by identity, and identity is the right comparison for a
        // labelling — two runs over the same mask produce equal contents and nobody asks that.
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    fun label(mask: Mask): Labels {
        val w = mask.width
        val h = mask.height
        val of = IntArray(w * h)
        val sizes = ArrayList<Int>().apply { add(0) }
        var next = 0

        val stack = ArrayDeque<Int>()
        for (start in of.indices) {
            if (of[start] != 0) continue
            if ((mask.a[start].toInt() and 0xFF) <= SOLID) continue

            next++
            var size = 0
            stack.addLast(start)
            of[start] = next

            while (stack.isNotEmpty()) {
                val at = stack.removeLast()
                size++
                val x = at % w
                val y = at / w
                // Four neighbours. See the class note on why not eight.
                if (x > 0) push(mask, of, stack, at - 1, next)
                if (x < w - 1) push(mask, of, stack, at + 1, next)
                if (y > 0) push(mask, of, stack, at - w, next)
                if (y < h - 1) push(mask, of, stack, at + w, next)
            }
            sizes.add(size)
        }
        return Labels(of, sizes.toIntArray(), next)
    }

    private fun push(mask: Mask, of: IntArray, stack: ArrayDeque<Int>, at: Int, label: Int) {
        if (of[at] != 0) return
        if ((mask.a[at].toInt() and 0xFF) <= SOLID) return
        // Claimed on the way *in*, not on the way out. Marking when popped lets the same pixel be
        // pushed by each of its neighbours before any of them runs, and the stack grows by a
        // factor of four for no reason.
        of[at] = label
        stack.addLast(at)
    }
}
