package com.gios.brightcollect.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.gios.brightcollect.ui.theme.LightHaptics
import com.gios.brightcollect.ui.theme.LightText
import com.gios.brightcollect.ui.theme.LightTextVariant
import com.gios.brightcollect.ui.theme.lightClickable

/**
 * The viewfinder.
 *
 * Deliberately plain next to Roll's. There are no filters, no modes and no zoom here — this
 * camera exists to get one object in the frame and hand it to the model. Everything
 * interesting about the picture happens on the next screen.
 *
 * The viewfinder runs **in colour**: see [ColourEffect]. The whole app is about the colours of
 * the things you collect, and framing a red thing against a red table in greyscale is framing
 * blind.
 */
@Composable
fun CameraScreen(
    onPhoto: (Bitmap, Long) -> Unit,
    onCancel: () -> Unit,
    onPick: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var busy by remember { mutableStateOf(false) }

    ColourEffect(enabled = true)

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    // COMPATIBLE, not PERFORMANCE. A TextureView can be read back and rotated
                    // by the system; the SurfaceView the performance mode uses cannot, and on
                    // the LPIII it also flashes black on every bind.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            update = { view ->
                bindCamera(context, view, lifecycleOwner) { capture = it }
            },
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = lightInset() * 2, start = lightInset(), end = lightInset()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LightText(
                text = if (busy) "HOLD STILL" else "ONE THING, PLAIN BACKGROUND",
                variant = LightTextVariant.Superfine,
                align = TextAlign.Center,
                lighten = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightButton(label = "BACK", onClick = onCancel)

                Shutter(
                    enabled = !busy && capture != null,
                    onClick = {
                        val c = capture ?: return@Shutter
                        busy = true
                        LightHaptics.shutter(context)
                        val takenAt = System.currentTimeMillis()
                        c.takePicture(
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val bitmap = image.toUprightBitmap()
                                    image.close()
                                    busy = false
                                    if (bitmap != null) onPhoto(bitmap, takenAt)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    Log.w(TAG, "capture failed", exception)
                                    busy = false
                                }
                            },
                        )
                    },
                )

                LightButton(label = "PHOTOS", onClick = onPick)
            }
        }
    }
}

/** The release. A ring with a filled core, the same mark LightOS's own camera uses. */
@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit) {
    val colors = com.gios.brightcollect.ui.theme.LightThemeTokens.colors
    Box(
        modifier = Modifier
            .size(64.dp)
            .lightClickable(enabled = enabled, haptics = false, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(
                color = if (enabled) colors.content else colors.contentFaint,
                radius = r - 2f,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
            )
            drawCircle(
                color = if (enabled) colors.content else colors.contentFaint,
                radius = r * 0.72f,
            )
        }
    }
}

private const val TAG = "CameraScreen"

private fun bindCamera(
    context: Context,
    view: PreviewView,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onReady: (ImageCapture) -> Unit,
) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        runCatching {
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = view.surfaceProvider
            }
            val capture = ImageCapture.Builder()
                // MINIMIZE_LATENCY, not MAXIMIZE_QUALITY. The sticker is capped at 1600px on
                // the long edge and the model sees 320, so the extra processing buys nothing
                // that survives to the file and costs about a second per shot.
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture,
            )
            onReady(capture)
        }.onFailure { Log.w(TAG, "could not bind the camera: $it") }
    }, ContextCompat.getMainExecutor(context))
}

/**
 * The captured frame as an upright bitmap.
 *
 * **The rotation is not optional.** CameraX hands back the sensor's own orientation with the
 * correction in `imageInfo.rotationDegrees`, so a phone held normally produces a landscape
 * bitmap with a quarter turn recorded beside it. Skipping the turn feeds the model a sideways
 * photograph — which it still segments, just not the thing you were pointing at — and saves a
 * sticker lying on its side.
 */
private fun ImageProxy.toUprightBitmap(): Bitmap? = runCatching {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val degrees = imageInfo.rotationDegrees
    if (degrees == 0) return decoded
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    val turned = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
    if (turned !== decoded) decoded.recycle()
    turned
}.getOrNull()
