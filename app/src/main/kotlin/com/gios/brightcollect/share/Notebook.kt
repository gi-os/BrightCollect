package com.gios.brightcollect.share

import android.content.Intent
import android.net.Uri

/**
 * The link back from a BrightNotebook entry to the sticker behind it.
 *
 * This file used to publish a flattened JPEG into MediaStore so the notebook's calendar would
 * pick it up. It does not any more — see [CaughtProvider] for why putting a cutout into the
 * phone's photo library made it a photograph, with a white background and a frame, and filled
 * Roll's grid with duplicates.
 */
object Notebook {

    /** `brightcollect://sticker/<id>` — the deep link a day's entry carries. */
    fun deepLink(id: String): Uri = Uri.parse("brightcollect://sticker/$id")

    /** Reads the sticker id back out of a VIEW intent, or null if it is not one of ours. */
    fun idFrom(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (data.scheme != "brightcollect" || data.host != "sticker") return null
        return data.lastPathSegment?.takeIf { it.isNotBlank() }
    }
}
