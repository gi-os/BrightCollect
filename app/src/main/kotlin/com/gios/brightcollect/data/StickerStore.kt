package com.gios.brightcollect.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.gios.brightcollect.cut.Cutout
import com.gios.brightcollect.cut.Mask
import java.io.File
import java.util.UUID

/**
 * The collection on disk.
 *
 * A directory of PNGs and one JSON index beside them, not a database. Three reasons, in order
 * of how much they mattered:
 *
 *  - **A sticker is a file.** The whole app is about producing one image per thing, and the
 *    natural store for images is images. Sending one to BrightChat needs a real file for the
 *    FileProvider either way (see BrightChat v2.19 — an attachment has to be a `File`, not a
 *    `ByteArray`), so a database would be a second copy of what already has to exist.
 *  - **LightSync backs up a directory as it stands.** No export step, no schema.
 *  - There is no query here. The shelf is "all of them, newest first"; the one lookup is by
 *    id, which is the file name.
 *
 * The index is rewritten whole on every change. At the scale this app operates on — hundreds
 * of entries, a few hundred bytes each — that is a sub-millisecond write, and it removes every
 * partial-update failure mode in exchange.
 */
class StickerStore(private val context: Context) {

    private val dir: File get() = File(context.filesDir, DIR).apply { mkdirs() }

    /**
     * The photograph a sticker was cut from, and the mask that cut it.
     *
     * **Kept so a sticker stays editable.** The cutout alone cannot be re-edited in any useful
     * sense: the pixels outside the mask are gone, so you could rub more away but never add back
     * a handle the model dropped, and the wand has nothing to sample because the background it
     * would sample is what was removed. Re-editing needs the original.
     *
     * The mask is kept as well as the source, and that is the half people leave out. Without it,
     * reopening would have to re-run the model — which throws away every hand correction, so the
     * second edit starts from worse than where the first one finished.
     *
     * About 400 kB a sticker between them, against roughly 200 kB for the sticker itself.
     */
    private val sourceDir: File get() = File(context.filesDir, SOURCES).apply { mkdirs() }
    private val maskDir: File get() = File(context.filesDir, MASKS).apply { mkdirs() }
    private val index: File get() = File(context.filesDir, INDEX)
    private val counter: File get() = File(context.filesDir, COUNTER)

    fun fileFor(id: String): File = File(dir, "$id.png")

    fun sourceFor(id: String): File = File(sourceDir, "$id.jpg")

    fun maskFor(id: String): File = File(maskDir, "$id.png")

    /** True when this sticker can be reopened rather than only looked at. */
    fun editable(id: String): Boolean = sourceFor(id).exists() && maskFor(id).exists()

    /**
     * Writes the source and the mask beside a sticker.
     *
     * JPEG for the photograph — it is a photograph, and the mask is what has to be exact.
     * Quality 88 rather than 100 because the difference is invisible and the file is a third the
     * size, and this is the part of a collection that grows without being looked at.
     */
    fun keepSource(id: String, source: Bitmap, mask: Mask) {
        runCatching {
            sourceFor(id).outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        }.onFailure { Log.w(TAG, "could not keep the source: $it") }
        runCatching {
            maskFor(id).outputStream().use { out ->
                Cutout.writePng(maskToBitmap(mask), out)
            }
        }.onFailure { Log.w(TAG, "could not keep the mask: $it") }
    }

    fun loadSource(id: String): Bitmap? = runCatching {
        BitmapFactory.decodeFile(sourceFor(id).absolutePath)
    }.getOrNull()

    fun loadMask(id: String, width: Int, height: Int): Mask? = runCatching {
        val bitmap = BitmapFactory.decodeFile(maskFor(id).absolutePath) ?: return@runCatching null
        val m = bitmapToMask(bitmap)
        bitmap.recycle()
        // A mask saved against a differently sized source is not a mask for this one. It can
        // happen across a restore, and stretching it would hand back a cutout that is subtly
        // offset everywhere rather than obviously broken.
        if (m.width == width && m.height == height) m else Mask.scale(m, width, height)
    }.getOrNull()

    /**
     * The mask as an opaque grey PNG.
     *
     * Stored in the colour channels with alpha left at 255, not *as* alpha. A PNG whose colour is
     * black and whose alpha carries the data is the obvious encoding and the wrong one: Android
     * premultiplies on the way into a bitmap, so every pixel of black-with-alpha reads back as
     * black-with-alpha-zero and the mask decodes as empty.
     */
    private fun maskToBitmap(mask: Mask): Bitmap {
        val px = IntArray(mask.width * mask.height)
        for (i in px.indices) {
            val v = mask.a[i].toInt() and 0xFF
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(px, mask.width, mask.height, Bitmap.Config.ARGB_8888)
    }

    private fun bitmapToMask(bitmap: Bitmap): Mask {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val m = Mask(bitmap.width, bitmap.height)
        for (i in px.indices) m.a[i] = (px[i] and 0xFF).toByte()
        return m
    }

    @Synchronized
    fun all(): List<Sticker> {
        if (!index.exists()) return emptyList()
        val text = runCatching { index.readText() }.getOrNull() ?: return emptyList()
        // Newest first, and sorted here rather than trusted from the file: the index is
        // rewritten by whoever saved last, and a restored backup can arrive in any order.
        return Sticker.listFromJson(text)
            .filter { fileFor(it.id).exists() }
            .sortedByDescending { it.capturedAt }
    }

    fun get(id: String): Sticker? = all().firstOrNull { it.id == id }

    /**
     * Writes [bitmap] as a new sticker and returns it.
     *
     * The PNG lands before the index does. If the process dies between the two the file is an
     * orphan, which [all] hides and [sweep] collects — the other order would leave an index
     * entry pointing at nothing, which is a broken cell in the grid rather than a missing one.
     */
    @Synchronized
    fun save(
        bitmap: Bitmap,
        name: String? = null,
        capturedAt: Long = System.currentTimeMillis(),
        suggested: Boolean = false,
        source: Bitmap? = null,
        mask: Mask? = null,
    ): Sticker {
        val id = UUID.randomUUID().toString().take(12)
        val file = fileFor(id)
        file.outputStream().use { Cutout.writePng(bitmap, it) }

        val n = bumpCounter()
        val sticker = Sticker(
            id = id,
            name = name?.takeIf { it.isNotBlank() } ?: Sticker.defaultName(n),
            capturedAt = capturedAt,
            width = bitmap.width,
            height = bitmap.height,
            suggested = suggested,
        )
        // After the sticker and its index entry, because it is the part you can lose without
        // losing anything you made — a sticker with no source is still a sticker, it just cannot
        // be reopened.
        if (source != null && mask != null) keepSource(id, source, mask)
        write(all() + sticker)
        return sticker
    }

    @Synchronized
    fun update(sticker: Sticker) {
        write(all().map { if (it.id == sticker.id) sticker else it })
    }

    @Synchronized
    fun delete(id: String) {
        runCatching { fileFor(id).delete() }
        runCatching { sourceFor(id).delete() }
        runCatching { maskFor(id).delete() }
        write(all().filterNot { it.id == id })
    }

    /** Replaces a sticker's image and mask in place, keeping its id, name and date. */
    @Synchronized
    fun replace(id: String, bitmap: Bitmap, mask: Mask, source: Bitmap) {
        runCatching { fileFor(id).outputStream().use { Cutout.writePng(bitmap, it) } }
        keepSource(id, source, mask)
        all().firstOrNull { it.id == id }?.let {
            update(it.copy(width = bitmap.width, height = bitmap.height))
        }
    }

    fun load(id: String): Bitmap? {
        val f = fileFor(id)
        if (!f.exists()) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    /**
     * A downsampled decode, for the grid.
     *
     * `inSampleSize` and not a full decode followed by a scale: the point is to never hold the
     * full bitmap. A shelf of two hundred stickers decoded at full size is hundreds of
     * megabytes and the process is killed long before the user reaches the bottom.
     */
    fun loadThumb(id: String, edge: Int = 320): Bitmap? {
        val f = fileFor(id)
        if (!f.exists()) return null
        return runCatching {
            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, probe)
            var sample = 1
            while (probe.outWidth / (sample * 2) >= edge && probe.outHeight / (sample * 2) >= edge) {
                sample *= 2
            }
            BitmapFactory.decodeFile(
                f.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull()
    }

    /** Deletes files the index does not mention. See [save] for how one gets there. */
    @Synchronized
    fun sweep(): Int {
        val ids = all().map { it.id }.toSet()
        var n = 0
        listOf(dir, sourceDir, maskDir).forEach { d ->
            d.listFiles()?.forEach { f ->
                if (f.name.substringBeforeLast('.') !in ids) {
                    runCatching { f.delete() }
                    n++
                }
            }
        }
        return n
    }

    /** What the collection costs on disk, for the settings readout. */
    fun bytes(): Long = listOf(dir, sourceDir, maskDir)
        .sumOf { d -> d.listFiles()?.sumOf { it.length() } ?: 0L }

    private fun write(items: List<Sticker>) {
        runCatching {
            // Write beside it and rename. A truncated index is an empty collection, and a
            // phone that runs out of battery mid-write must not be how someone loses one.
            val tmp = File(context.filesDir, "$INDEX.tmp")
            tmp.writeText(Sticker.listToJson(items))
            if (!tmp.renameTo(index)) {
                index.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "index write failed: $it") }
    }

    /**
     * How many stickers have ever been taken, incremented and returned.
     *
     * Kept outside the index on purpose, so that deleting a sticker does not renumber the
     * next one into a name that has already been used.
     */
    private fun bumpCounter(): Int {
        val n = (runCatching { counter.readText().trim().toInt() }.getOrNull() ?: 0) + 1
        runCatching { counter.writeText(n.toString()) }
        return n
    }

    companion object {
        private const val TAG = "StickerStore"
        const val DIR = "stickers"
        const val SOURCES = "sources"
        const val MASKS = "masks"
        private const val INDEX = "stickers.json"
        private const val COUNTER = "counter.txt"
    }
}
