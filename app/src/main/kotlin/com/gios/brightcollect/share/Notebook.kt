package com.gios.brightcollect.share

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.gios.brightcollect.cut.Cutout
import com.gios.brightcollect.data.Sticker

/**
 * Putting a capture on BrightNotebook's calendar.
 *
 * **The cheapest bridge is no bridge.** BrightNotebook already draws the day's photographs on
 * its calendar by querying MediaStore — it needs nothing from the app that took them. So a
 * capture appears on the right day for free, provided the row carries the right
 * `DATE_TAKEN`, and this file is thirty lines instead of a ContentProvider and a permission
 * negotiation on both sides.
 *
 * Two traps, both learned on LightNotebook:
 *
 *  - **`DATE_TAKEN` is milliseconds; `DATE_ADDED` is seconds.** Writing epoch millis into
 *    `DATE_ADDED` puts the entry somewhere around the year 56000, where nothing ever scrolls
 *    to it and nothing reports an error.
 *  - **`DATE_TAKEN` is the moment the shutter fired**, not the moment the cutout finished. A
 *    sticker made from a photograph imported out of the roll belongs on the day of the
 *    photograph.
 *
 * What lands in MediaStore is a **flattened** copy on white, not the transparent PNG. The
 * calendar composites it over its own background, and a cutout with a transparent background
 * on an unknown background is a silhouette of itself.
 */
object Notebook {

    private const val TAG = "Notebook"

    /** Its own album, so the calendar and the phone's gallery show captures as a set. */
    const val ALBUM = "Collect"

    /**
     * Writes [bitmap] into MediaStore under [sticker]'s capture time. Returns the row, or null.
     *
     * Null is not an error worth surfacing: the sticker is already saved in the app, and a
     * failure here costs a calendar entry, not the thing the user made.
     */
    fun publish(context: Context, sticker: Sticker, bitmap: Bitmap): Uri? = runCatching {
        val flat = Cutout.flatten(bitmap)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${sticker.id}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$ALBUM",
            )
            // Milliseconds. See the class doc — the seconds/millis mix-up is silent.
            put(MediaStore.Images.Media.DATE_TAKEN, sticker.capturedAt)
            put(MediaStore.Images.Media.DATE_ADDED, sticker.capturedAt / 1000)
            put(MediaStore.Images.Media.DATE_MODIFIED, sticker.capturedAt / 1000)
            put(MediaStore.Images.Media.DESCRIPTION, sticker.name)
            // Held pending until the bytes are in, so nothing indexes a zero-byte row.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching null
        resolver.openOutputStream(uri)?.use { out ->
            flat.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
        flat.recycle()
        uri
    }.onFailure { Log.w(TAG, "could not publish to MediaStore: $it") }.getOrNull()

    /** `brightcollect://sticker/<id>` — the deep link a calendar entry can carry back here. */
    fun deepLink(id: String): Uri = Uri.parse("brightcollect://sticker/$id")

    /** Reads the sticker id back out of a VIEW intent, or null if it is not one of ours. */
    fun idFrom(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (data.scheme != "brightcollect" || data.host != "sticker") return null
        return data.lastPathSegment?.takeIf { it.isNotBlank() }
    }
}
