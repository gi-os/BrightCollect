package com.gios.brightcollect.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One cut-out thing.
 *
 * [id] is the file name stem as well as the identity, so a sticker is findable from its PNG
 * and vice versa without a lookup — which is what makes `brightcollect://sticker/<id>` work
 * from a BrightNotebook entry written months earlier.
 */
data class Sticker(
    val id: String,
    val name: String,
    /** Epoch millis. The moment the shutter fired, not the moment the cutout finished. */
    val capturedAt: Long,
    val width: Int,
    val height: Int,
    /** Free text, one line. Where it was, what it was. */
    val note: String = "",
    /**
     * True while [name] is still the labeller's guess and nobody has touched it.
     *
     * Persisted rather than kept in memory, because it changes what the detail screen does: a
     * guessed name opens with the whole field selected, so typing replaces it in one go, and a
     * name you chose opens with the caret at the end. Restoring a backup has to remember which
     * kind it was, or every name you carefully typed comes back pre-selected for deletion.
     */
    val suggested: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put(KEY_ID, id)
        .put(KEY_NAME, name)
        .put(KEY_AT, capturedAt)
        .put(KEY_W, width)
        .put(KEY_H, height)
        .put(KEY_NOTE, note)
        .put(KEY_SUGGESTED, suggested)

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_AT = "at"
        private const val KEY_W = "w"
        private const val KEY_H = "h"
        private const val KEY_NOTE = "note"
        private const val KEY_SUGGESTED = "suggested"

        /**
         * Reads one entry, or null if it is unusable.
         *
         * Null rather than an exception, deliberately. The index is a file on a phone that can
         * be interrupted mid-write and restored by LightSync from a build with a different
         * shape; one bad row must cost one sticker, not the whole collection. A collection that
         * refuses to open because of a single malformed entry is the worst failure this app has.
         */
        fun fromJson(o: JSONObject): Sticker? {
            val id = o.optString(KEY_ID).takeIf { it.isNotBlank() } ?: return null
            return Sticker(
                id = id,
                name = o.optString(KEY_NAME, ""),
                capturedAt = o.optLong(KEY_AT, 0L),
                width = o.optInt(KEY_W, 0),
                height = o.optInt(KEY_H, 0),
                note = o.optString(KEY_NOTE, ""),
                suggested = o.optBoolean(KEY_SUGGESTED, false),
            )
        }

        fun listFromJson(text: String): List<Sticker> = runCatching {
            val arr = JSONObject(text).optJSONArray("stickers") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { fromJson(it) }
            }
        }.getOrDefault(emptyList())

        fun listToJson(items: List<Sticker>): String {
            val arr = JSONArray()
            items.forEach { arr.put(it.toJson()) }
            return JSONObject().put("version", 1).put("stickers", arr).toString()
        }

        /**
         * A name for a sticker nobody has named.
         *
         * Numbered rather than dated, because the date is already on the entry and "Sticker 41"
         * tells you something the timestamp does not: how far in you are. The count is of every
         * sticker ever taken, so numbers are not reused after a delete — two different stickers
         * called "Sticker 12" in the same collection is worse than a gap in the sequence.
         */
        fun defaultName(everTaken: Int): String = "Sticker $everTaken"
    }
}
