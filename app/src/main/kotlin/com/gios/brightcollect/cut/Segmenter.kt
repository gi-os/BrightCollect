package com.gios.brightcollect.cut

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Finds the object in a photograph.
 *
 * The model is **u2netp** — the 4.4 MB variant of U-2-Net, trained for class-agnostic salient
 * object detection — bundled in `assets/` and run through ONNX Runtime.
 *
 * **Why not ML Kit.** Its Subject Segmentation API is the unbundled kind, delivered through
 * Play Services. LightOS has microG, so that call binds and never answers — the same trap that
 * made Roll use ZXing for barcodes instead of ML Kit. ML Kit's only *bundled* segmenter finds
 * people, and this app is for objects. ONNX Runtime asks the platform for nothing.
 *
 * The session is expensive to build (tens of megabytes of native allocation) and cheap to
 * reuse, so there is one, created lazily and held for the life of the process.
 */
class Segmenter(private val context: Context) {

    /**
     * ORT's process-wide environment. A `val` rather than a nullable field set in [session]:
     * [OnnxTensor.createTensor] needs a non-null one, and threading a `?` through the hot path
     * to describe something that is a singleton anyway buys nothing.
     */
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private var session: OrtSession? = null

    /** What the model was trained on. Wrong here and every cutout is subtly wrong everywhere. */
    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)

    @Synchronized
    private fun session(): OrtSession {
        session?.let { return it }
        val opts = OrtSession.SessionOptions().apply {
            // The LPIII has 8 cores that throttle hard. Four threads is where this stopped
            // getting faster in testing, and leaves the camera pipeline something to run on.
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        // Read the asset into a byte array rather than handing ORT a path: the model lives
        // inside the APK and there is no file to point at. `noCompress` in build.gradle.kts is
        // what keeps this a straight read rather than an inflate.
        val bytes = context.assets.open(MODEL).use { it.readBytes() }
        val s = env.createSession(bytes, opts)
        session = s
        Log.i(TAG, "session ready: in=${s.inputNames} out=${s.outputNames.take(1)}")
        return s
    }

    /**
     * Runs the model over [bitmap] and returns the mask at the model's own resolution.
     *
     * Deliberately **not** scaled up here. The caller wants the small mask for the preview and
     * the full-size one only when it saves, and a 12-megapixel [Mask] costs 12 MB — building it
     * on every preview frame is what makes a refine screen stutter.
     */
    fun run(bitmap: Bitmap): Mask {
        val s = session()
        val input = preprocess(bitmap)
        val shape = longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            // The graph has seven outputs — the fused map and six side supervision maps from
            // the decoder stages. Only the first is the prediction; the other six exist for
            // the training loss and are progressively coarser. Asking for one output instead
            // of all seven also stops ORT materialising six arrays nobody reads.
            val first = s.inputNames.first()
            s.run(mapOf(first to tensor), setOf(s.outputNames.first())).use { result ->
                // Rank four — [1, 1, 320, 320] — so the Java value is nested four deep, not
                // three. Getting this wrong is a ClassCastException at the first inference
                // rather than a compile error, because the cast is unchecked.
                @Suppress("UNCHECKED_CAST")
                val out = result.get(0).value as Array<Array<Array<FloatArray>>>
                val plane: Array<FloatArray> = out[0][0]
                val scores = FloatArray(SIZE * SIZE)
                for (y in 0 until SIZE) {
                    System.arraycopy(plane[y], 0, scores, y * SIZE, SIZE)
                }
                return Mask.fromScores(scores, SIZE, SIZE)
            }
        }
    }

    /**
     * Bitmap to NCHW float tensor, resized to 320x320 and normalised.
     *
     * **The aspect ratio is thrown away on purpose.** u2netp was trained on squashed square
     * crops, so squashing is what it expects; letterboxing to preserve the aspect instead
     * feeds it grey bars it has never seen and it finds edges in them. The mask comes back
     * squashed the same way and is unsquashed by [Mask.scale] on the way out, so nothing
     * downstream notices.
     */
    private fun preprocess(bitmap: Bitmap): FloatArray {
        val small = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        small.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        if (small !== bitmap) small.recycle()

        val out = FloatArray(3 * SIZE * SIZE)
        val plane = SIZE * SIZE
        for (i in px.indices) {
            val p = px[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            out[i] = (r - mean[0]) / std[0]
            out[plane + i] = (g - mean[1]) / std[1]
            out[2 * plane + i] = (b - mean[2]) / std[2]
        }
        return out
    }

    @Synchronized
    fun close() {
        runCatching { session?.close() }
        session = null
    }

    companion object {
        private const val TAG = "Segmenter"
        const val MODEL = "u2netp.onnx"

        /** The model's fixed input side. Not a preference — the graph has no dynamic axis. */
        const val SIZE = 320
    }
}
