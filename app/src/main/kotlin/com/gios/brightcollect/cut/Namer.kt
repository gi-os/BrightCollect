package com.gios.brightcollect.cut

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Guessing what the thing is.
 *
 * ML Kit's default image labeller: 400-odd everyday labels, **bundled** — the model is inside the
 * APK, so there is no Play Services call to hang on. That distinction is the same one that decided
 * the cutout: the *unbundled* artifact would bind to microG and never answer. Roll ships bundled
 * ML Kit for text recognition on the same reasoning.
 *
 * **Not an ImageNet classifier through the ONNX Runtime already in the APK**, which was the
 * cheaper option and would have added nothing to the download. ImageNet's thousand classes are
 * academic — it distinguishes forty dog breeds and a dozen mushrooms, and calls a mug a "coffee
 * mug" only if it is the right sort of mug. ML Kit's list is curated for exactly this: things a
 * person photographs. "Plant", "Shoe", "Bicycle", "Cup". A collection wants the word you would
 * have typed.
 *
 * It runs on the **cutout**, not the photograph, which is worth more than it sounds. The
 * background is already gone, so nothing in the frame competes with the subject — the table, the
 * wall and the hand holding it are not there to be labelled instead.
 */
object Namer {

    private const val TAG = "Namer"

    /**
     * Below this the guess is not worth putting in front of someone.
     *
     * A wrong prefilled name costs a correction; a blank one costs the same typing it would have
     * cost anyway. So the bar is set where the label is more likely right than not, and everything
     * under it falls back to the numbered default.
     */
    const val MIN_CONFIDENCE = 0.55f

    private val labeler by lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(MIN_CONFIDENCE)
                .build(),
        )
    }

    /**
     * The best label for [bitmap], or null if nothing was confident enough.
     *
     * The bitmap is **flattened onto white first**. ML Kit takes an ARGB bitmap and reads the
     * colour channels; where alpha is zero those channels are whatever was under the cutout,
     * undefined and usually black — so handing it the sticker directly labels a silhouette on a
     * dark field, and the commonest answer becomes "Night".
     */
    suspend fun suggest(bitmap: Bitmap): String? {
        val flat = runCatching { Cutout.flatten(bitmap) }.getOrNull() ?: return null
        return try {
            val labels = suspendCoroutine { cont ->
                labeler.process(InputImage.fromBitmap(flat, 0))
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener {
                        Log.w(TAG, "labelling failed: $it")
                        cont.resume(emptyList())
                    }
            }
            labels.maxByOrNull { it.confidence }
                ?.takeIf { it.confidence >= MIN_CONFIDENCE }
                ?.text
                ?.let(::tidy)
        } catch (e: Exception) {
            Log.w(TAG, "labeller unavailable: $e")
            null
        } finally {
            flat.recycle()
        }
    }

    /**
     * ML Kit's label as a name.
     *
     * Its labels are already title case and singular, so this is nearly a no-op — but a few come
     * back with a qualifier in brackets or a trailing descriptor, and a name field is one line on
     * a small screen.
     */
    internal fun tidy(label: String): String? {
        val cleaned = label
            .substringBefore('(')
            .trim()
            .trim(',', '.', '-')
        if (cleaned.isBlank()) return null
        return cleaned.replaceFirstChar { it.uppercase() }
    }

    fun close() {
        runCatching { labeler.close() }
    }
}
