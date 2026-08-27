package com.gios.brightcollect.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.gios.brightcollect.cut.Cutout
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
    private val index: File get() = File(context.filesDir, INDEX)
    private val counter: File get() = File(context.filesDir, COUNTER)

    fun fileFor(id: String): File = File(dir, "$id.png")

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
        write(all().filterNot { it.id == id })
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

    /** Deletes PNGs the index does not mention. See [save] for how one gets there. */
    @Synchronized
    fun sweep(): Int {
        val known = all().map { "${it.id}.png" }.toSet()
        val orphans = dir.listFiles()?.filter { it.name !in known }.orEmpty()
        orphans.forEach { runCatching { it.delete() } }
        return orphans.size
    }

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
        private const val INDEX = "stickers.json"
        private const val COUNTER = "counter.txt"
    }
}
