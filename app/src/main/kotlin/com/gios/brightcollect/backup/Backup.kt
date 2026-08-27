package com.gios.brightcollect.backup

import com.gios.light.common.sync.Contents
import com.gios.light.common.sync.FileStore
import com.gios.light.common.sync.LightSyncBackup
import com.gios.brightcollect.data.StickerStore

/**
 * What Collect hands to LightSync.
 *
 * **The stickers are in here, and that is the difference from Roll.** Roll leaves its
 * photographs out of its backup on purpose: they live in shared MediaStore storage that the app
 * does not own, and whatever backs up your camera roll already has them. A sticker is the
 * opposite on every count. It exists nowhere but this app's `filesDir`, nothing else on the
 * phone has a copy, and it cannot be re-derived — the photograph it was cut out of may have been
 * deleted, and even if it has not, the cutout carries a mask that was hand-corrected with the
 * wand and the brush. Losing the collection means losing the work, not just the files.
 *
 * They are also small. A sticker is capped at 1600px on the long edge, so a collection of two
 * hundred is tens of megabytes, not the gigabytes that made Roll's decision for it.
 *
 * Two stores:
 *
 *  - **`collection`** — the PNGs, the index that names and dates them, and the counter behind
 *    the default names. The counter travels with the rest deliberately: restoring without it
 *    would start numbering at "Sticker 1" again on a phone that already has one.
 *  - **`settings`** — the prefs file: wand tolerance, brush size, whether the shelf lifts the
 *    phone into colour.
 */
class Backup : LightSyncBackup() {

    override fun label() = "Collect"

    override fun stores() = listOf(
        FileStore(
            "collection",
            Contents(files = listOf(StickerStore.DIR, "stickers.json", "counter.txt")),
        ),
        FileStore("settings", Contents(prefs = listOf("collect"))),
    )
}
