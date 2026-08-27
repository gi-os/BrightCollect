package com.gios.brightcollect.cut

import android.graphics.Bitmap
import android.graphics.Color
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Turning a photograph and a mask into the PNG that gets stored.
 *
 * The one Android-touching half of the cutout; everything that decides *which* pixels are in
 * lives in [Mask] and [Wand], on the JVM side, where it can be tested.
 */
object Cutout {

    /** Stickers are stored no larger than this on the long edge. */
    const val MAX_EDGE = 1600

    /** Transparent margin kept around the object, as a fraction of its long edge. */
    private const val PAD = 0.02f

    /**
     * Composites [mask] onto [source] as alpha, trims to the object, and returns the sticker.
     *
     * **Trimming is not cosmetic.** The mask covers the whole frame and the object is usually a
     * third of it, so an untrimmed sticker is mostly transparent — it would draw at a third of
     * the size of its cell in the shelf, and every sticker would be a different size for
     * reasons the eye cannot connect to the object.
     *
     * Returns null when the mask is empty, which is a real outcome rather than an error: it is
     * what a photograph of a blank wall produces, and the caller shows "nothing found" instead
     * of saving a transparent PNG.
     */
    fun compose(source: Bitmap, mask: Mask): Bitmap? {
        require(mask.width == source.width && mask.height == source.height) {
            "mask ${mask.width}x${mask.height} does not match source ${source.width}x${source.height}"
        }
        val b = mask.bounds() ?: return null

        val padPx = (max(b[2] - b[0], b[3] - b[1]) * PAD).toInt()
        val x0 = max(0, b[0] - padPx)
        val y0 = max(0, b[1] - padPx)
        val x1 = min(source.width - 1, b[2] + padPx)
        val y1 = min(source.height - 1, b[3] + padPx)
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        if (w <= 0 || h <= 0) return null

        val px = IntArray(w * h)
        source.getPixels(px, 0, w, x0, y0, w, h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val alpha = mask[x0 + x, y0 + y]
                // The colour channels are kept as they are and only alpha is replaced.
                // Premultiplying here would be wrong twice over: PNG stores straight alpha,
                // and Bitmap does its own premultiply on the way into a Config.ARGB_8888.
                px[i] = (alpha shl 24) or (px[i] and 0x00FFFFFF)
            }
        }
        val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        return downscale(out, MAX_EDGE)
    }

    /**
     * Shrinks [bitmap] so its long edge is at most [maxEdge].
     *
     * A sticker is looked at in a grid cell and sent in a message; nobody prints one. Storing
     * the 12-megapixel version costs about 8 MB each and makes the shelf scroll badly, and
     * this app is expected to hold hundreds.
     */
    fun downscale(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val long = max(bitmap.width, bitmap.height)
        if (long <= maxEdge) return bitmap
        val k = maxEdge.toFloat() / long
        val w = max(1, (bitmap.width * k).toInt())
        val h = max(1, (bitmap.height * k).toInt())
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /**
     * PNG, always, and at quality 100 which PNG ignores.
     *
     * WEBP_LOSSLESS would be a third smaller and is tempting. It is not used because a sticker
     * leaves this app — it goes to BrightChat, and from there to whatever the other person is
     * holding. PNG with alpha is the one thing every phone, every desktop and every messaging
     * app renders correctly; lossless WebP with an alpha channel is still the format that
     * arrives as a black rectangle somewhere.
     */
    fun writePng(bitmap: Bitmap, out: OutputStream) {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }

    /**
     * A flattened copy for the grid, drawn over [background].
     *
     * Compose can draw a transparent PNG directly, so this exists for one job: the thumbnail
     * that goes into a notification and into the BrightNotebook entry, neither of which
     * controls what is behind it. A cutout with a transparent background on an unknown
     * background is a silhouette of itself.
     */
    fun flatten(bitmap: Bitmap, background: Int = Color.WHITE): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(background)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return out
    }
}
