package com.gios.brightcollect.send

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * Handing a sticker to BrightChat.
 *
 * **Not the system share sheet.** Android's chooser answers "which app?", and on a Light Phone
 * that is the wrong question — there are three apps and you already know which one. Roll's
 * send picker answers "who?" instead, by owning the address book. This app deliberately does
 * not: a sticker goes to BrightChat and BrightChat asks who, because adding READ_CONTACTS to a
 * camera-and-a-shelf is a permission dialog for a question the receiving app already asks
 * better. The picker in Roll exists because Roll sends several photographs at once to a person
 * you chose before opening anything; a sticker is one image and one tap.
 *
 * The intent is the AOSP convention — `ACTION_SEND` with an image mime type — so the same
 * intent is understood by BrightChat and by a stock SMS app, which is what makes the fallback
 * in [send] free.
 */
object Handoff {

    private const val TAG = "Handoff"

    /** Giovanni's iMessage client, and the preferred destination. */
    const val BRIGHT_CHAT = "com.gios.lightchat"

    sealed interface Outcome {
        data class Sent(val pkg: String) : Outcome

        /** No addressed-image app took it, so the system chooser was opened instead. */
        data object Chooser : Outcome

        data class Failed(val why: String) : Outcome
    }

    /**
     * Sends one sticker.
     *
     * BrightChat first, then the chooser. There is no middle case here the way there is in
     * Roll — with no recipient attached to the intent there is nothing for a second-choice app
     * to lose, so anything that takes an image is as good as the chooser and the chooser is
     * more honest about what is happening.
     */
    fun send(context: Context, uri: Uri): Outcome {
        val base = intentFor(context, uri)

        if (canReceive(context, BRIGHT_CHAT)) {
            val explicit = intentFor(context, uri).apply {
                setPackage(BRIGHT_CHAT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val ok = runCatching { context.startActivity(explicit) }
                .onFailure { Log.w(TAG, "explicit send to BrightChat failed: $it") }
                .isSuccess
            if (ok) return Outcome.Sent(BRIGHT_CHAT)
        }

        val chooser = Intent.createChooser(base, "Send sticker")
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return runCatching { context.startActivity(chooser) }
            .fold(
                onSuccess = { Outcome.Chooser },
                onFailure = { Outcome.Failed("Nothing on the phone takes images") },
            )
    }

    /**
     * Whether [pkg] is installed and can currently receive an image.
     *
     * Both halves matter. Package visibility from Android 11 means an app cannot see another
     * app's activities unless the manifest says which ones it is looking for — Roll shipped a
     * send button that reported "LightChat can't receive photos" on a phone with LightChat
     * installed for exactly this reason. See the `queries` block in AndroidManifest.xml.
     */
    fun canReceive(context: Context, pkg: String): Boolean {
        val probe = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            setPackage(pkg)
        }
        return runCatching {
            context.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
                .isNotEmpty()
        }.getOrDefault(false)
    }

    /**
     * The intent itself.
     *
     * **`ClipData` as well as `EXTRA_STREAM`.** `FLAG_GRANT_READ_URI_PERMISSION` grants the URI
     * in the intent's `data` and every URI in its `ClipData` — it does *not* walk
     * `EXTRA_STREAM`. Roll gets away with the omission most of the time because its images are
     * in MediaStore and the receiver reads them under its own `READ_MEDIA_IMAGES`. A sticker is
     * in this app's private files directory behind a FileProvider, so there is no permission
     * the receiver could be holding: without the ClipData the grant never happens and every
     * send arrives as a broken image.
     *
     * **`image/png`, stated exactly.** The alpha channel is the whole point of a sticker, and a
     * receiver told only that this is an image is free to re-encode it as JPEG, which silently
     * flattens the transparency onto black.
     *
     * (Written without the wildcard on purpose. Kotlin nests block comments, so a literal
     * slash-star inside a doc comment opens one that never closes, and the file then stops
     * compiling at its last line — several hundred lines from the cause. Roll's Handoff carries
     * the same note for the same reason.)
     */
    private fun intentFor(context: Context, uri: Uri): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "sticker", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    const val MIME = "image/png"
}
