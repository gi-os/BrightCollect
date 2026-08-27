package com.gios.brightcollect.share

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.gios.brightcollect.data.StickerStore
import java.time.LocalDate
import java.time.ZoneId

/**
 * What you caught on a day, offered to the rest of the phone.
 *
 * **This replaces writing stickers into MediaStore**, which is how they used to reach
 * BrightNotebook's calendar. That worked, and it was wrong in a way that showed: a sticker put in
 * MediaStore *is* a photograph as far as the phone is concerned, so the notebook drew it in the
 * photo strip, flattened onto white, in a frame — and Roll's grid filled up with white-background
 * duplicates of things already in the collection. A cutout with a transparent edge is not a
 * photograph, and the fix was to stop claiming it is one.
 *
 * So it follows the shape LightFog, LightChat, BrightRecorder, BrightWay and LightBooks already
 * use with the notebook: ask a provider by calendar date, take what comes, treat every failure as
 * nothing. It costs the caller no permission at all — where the MediaStore route needed
 * `READ_MEDIA_IMAGES` on the other side just to see them.
 *
 * Two paths:
 *
 *  - `content://com.gios.brightcollect.caught/caught/2026-08-27` — the day's rows.
 *  - `content://com.gios.brightcollect.caught/sticker/<id>` — the PNG itself, alpha intact, so
 *    the notebook draws the real cutout rather than a copy flattened onto a guess at its
 *    background. Same shape as BrightRecorder serving its own audio.
 *
 * **Asked by calendar date, and a journal day does not start at midnight.** The notebook's day
 * runs from four in the morning, so it fetches both dates a journal day touches and filters on the
 * millisecond timestamps in the rows. That is why `caught_ms` is in the cursor and is the column
 * that matters — the date in the path is a coarse index, not the answer.
 */
class CaughtProvider : ContentProvider() {

    private lateinit var store: StickerStore

    override fun onCreate(): Boolean {
        store = StickerStore(context ?: return false)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (matcher.match(uri) != DAY) return null
        val date = runCatching { LocalDate.parse(uri.lastPathSegment) }.getOrNull() ?: return null
        val zone = ZoneId.systemDefault()
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val until = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val cursor = MatrixCursor(COLUMNS)
        store.all()
            .filter { it.capturedAt in from until until }
            .sortedBy { it.capturedAt }
            .forEach {
                cursor.addRow(
                    arrayOf(it.id, it.capturedAt, it.name, it.width, it.height),
                )
            }
        return cursor
    }

    /**
     * Serves one sticker's PNG, read-only.
     *
     * The id is checked against the collection rather than pasted into a path. It arrives from
     * another app, and `../` in a file name is how a provider that concatenates ends up serving
     * its own preferences file — so the only ids that resolve are ones [StickerStore] already
     * knows about, which cannot contain a separator because they are generated here.
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (matcher.match(uri) != STICKER) return null
        if (mode != "r") return null
        val id = uri.lastPathSegment ?: return null
        val known = runCatching { store.all().any { it.id == id } }.getOrDefault(false)
        if (!known) {
            Log.w(TAG, "asked for a sticker that is not in the collection")
            return null
        }
        val file = store.fileFor(id)
        if (!file.exists()) return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        DAY -> "vnd.android.cursor.dir/vnd.com.gios.brightcollect.caught"
        STICKER -> "image/png"
        else -> null
    }

    /* Read-only, all three ways. */
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?) = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0

    companion object {
        private const val TAG = "CaughtProvider"
        const val AUTHORITY = "com.gios.brightcollect.caught"

        private const val DAY = 1
        private const val STICKER = 2

        /**
         * `caught_ms` rather than `at_ms`, matching nothing else on purpose — every bridge in the
         * notebook names its timestamp after what the timestamp *is*, and this one is when the
         * shutter fired, not when the cutout was finished or the row written.
         */
        private val COLUMNS = arrayOf("id", "caught_ms", "name", "width", "height")

        private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "caught/*", DAY)
            addURI(AUTHORITY, "sticker/*", STICKER)
        }

        /** Where another app reads one sticker's PNG. */
        fun stickerUri(id: String): Uri = Uri.parse("content://$AUTHORITY/sticker/$id")
    }
}
