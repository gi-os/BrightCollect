package com.gios.brightcollect.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gios.brightcollect.cut.Mask
import com.gios.brightcollect.ui.theme.LightText
import com.gios.brightcollect.ui.theme.LightTextVariant
import com.gios.brightcollect.ui.theme.LightThemeTokens
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Where a photograph becomes a sticker.
 *
 * The model gets first go and is usually right. This screen exists for the times it is not, and
 * the two corrections it offers are deliberately different sizes of gesture:
 *
 *  - **The wand** is one tap and takes a whole contiguous region of similar colour — the table
 *    the object is standing on, the shadowed half of a mug the model dropped. When the model is
 *    wrong it is normally wrong about a region, and scrubbing that out with a fingertip on a
 *    3.9-inch screen is the wrong tool for it.
 *  - **The brush** is for the edges the wand cannot describe: a handle against a background of
 *    the same colour, a strand of something.
 *
 * KEEP and CUT apply to both, so there are two controls rather than four tools. The eraser is
 * not a separate thing here; it is the brush in CUT, which is also what makes "the eraser but
 * for regions" a coherent idea rather than a fifth button.
 */
@Composable
fun CutScreen(
    refine: Refine,
    undoDepth: Int,
    onWand: (Int, Int) -> Unit,
    onStrokeStart: () -> Unit,
    onPaint: (Float, Float) -> Unit,
    onTool: (Tool) -> Unit,
    onMode: (Mode) -> Unit,
    onTolerance: (Int) -> Unit,
    onBrush: (Float) -> Unit,
    onUndo: () -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    val colors = LightThemeTokens.colors

    // The preview is rebuilt from the mask, which is mutated in place by the brush. A version
    // counter is what tells Compose the pixels changed when the object identity did not.
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(refine) { version++ }

    val preview: ImageBitmap = remember(refine.source, version) {
        composePreview(refine.source, refine.mask).asImageBitmap()
    }

    // The size the image is actually drawn at, so a touch can be mapped back to a pixel. Set
    // by the canvas on layout rather than assumed — the image is letterboxed inside its box and
    // guessing the scale puts the wand's tap somewhere else entirely.
    var drawn by remember { mutableStateOf(IntSize.Zero) }

    ColourEffect(enabled = true)

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = lightInset()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(refine.source.width.toFloat() / refine.source.height)
                    .onSizeChanged { drawn = it }
                    .pointerInput(refine.tool, refine.tolerance, drawn) {
                        if (refine.tool != Tool.Wand) return@pointerInput
                        detectTapGestures { at ->
                            val p = toPixel(at, drawn, refine.source) ?: return@detectTapGestures
                            onWand(p.x, p.y)
                        }
                    }
                    .pointerInput(refine.tool, refine.brush, drawn) {
                        if (refine.tool != Tool.Brush) return@pointerInput
                        detectDragGestures(
                            onDragStart = { at ->
                                onStrokeStart()
                                toPixelF(at, drawn, refine.source)?.let { onPaint(it.x, it.y) }
                            },
                            // The mask is painted at every point of the drag, not only where
                            // the events land. Android delivers touch at about 120Hz and a fast
                            // stroke on a phone this size skips thirty pixels between events,
                            // which draws a dotted line instead of a stroke.
                            onDrag = { change, _ ->
                                change.consume()
                                toPixelF(change.position, drawn, refine.source)
                                    ?.let { onPaint(it.x, it.y) }
                            },
                        )
                    },
            ) {
                drawImage(
                    image = preview,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    dstOffset = IntOffset.Zero,
                )
                if (refine.tool == Tool.Brush) {
                    // The brush size, shown where it can be judged: over the picture, at the
                    // scale it will actually paint at.
                    val k = size.width / refine.source.width
                    drawCircle(
                        color = colors.content,
                        radius = refine.brush * k,
                        center = Offset(size.width / 2f, size.height / 2f),
                        style = Stroke(width = 2f),
                        alpha = 0.35f,
                    )
                }
            }

            if (refine.thinking) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(colors.scrim),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText("FINDING IT", LightTextVariant.Detail, align = TextAlign.Center)
                }
            }
        }

        refine.hint?.let {
            LightText(it, LightTextVariant.Superfine, align = TextAlign.Center, lighten = true)
        }

        LightSegmented(
            options = listOf(Tool.Wand to "WAND", Tool.Brush to "BRUSH"),
            selected = refine.tool,
            onSelect = onTool,
        )
        LightSegmented(
            options = listOf(Mode.Keep to "KEEP", Mode.Cut to "CUT"),
            selected = refine.mode,
            onSelect = onMode,
        )

        if (refine.tool == Tool.Wand) {
            Stepper(
                label = "SIMILARITY",
                value = "${refine.tolerance}",
                fraction = refine.tolerance / 128f,
                onLess = { onTolerance(refine.tolerance - 4) },
                onMore = { onTolerance(refine.tolerance + 4) },
            )
        } else {
            Stepper(
                label = "SIZE",
                value = "${refine.brush.roundToInt()}",
                fraction = (refine.brush - 8f) / 152f,
                onLess = { onBrush(refine.brush - 8f) },
                onMore = { onBrush(refine.brush + 8f) },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = lightInset()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LightButton("BACK", Modifier.weight(1f), onClick = onDiscard)
            LightButton("UNDO", Modifier.weight(1f), enabled = undoDepth > 0, onClick = onUndo)
            LightButton("KEEP IT", Modifier.weight(1.4f), selected = true, onClick = onSave)
        }
    }
}

@Composable
private fun Stepper(
    label: String,
    value: String,
    fraction: Float,
    onLess: () -> Unit,
    onMore: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightButton("−", onClick = onLess)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                LightText("$label  $value", LightTextVariant.Superfine, lighten = true)
                LightBar(fraction)
            }
            LightButton("+", onClick = onMore)
        }
    }
}

/**
 * Where on the image a touch landed, or null if it landed outside it.
 *
 * The canvas is laid out with the image's aspect ratio, so the mapping is a single scale
 * factor — but it is read from the measured size rather than computed from the source, because
 * the width is whatever the column gave it and rounding at two different places produces a
 * wand that is reliably a pixel or two off near the right-hand edge.
 */
private fun toPixel(at: Offset, drawn: IntSize, source: Bitmap): IntPoint? {
    val f = toPixelF(at, drawn, source) ?: return null
    return IntPoint(f.x.roundToInt().coerceIn(0, source.width - 1), f.y.roundToInt().coerceIn(0, source.height - 1))
}

private fun toPixelF(at: Offset, drawn: IntSize, source: Bitmap): Offset? {
    if (drawn.width <= 0 || drawn.height <= 0) return null
    val kx = source.width.toFloat() / drawn.width
    val ky = source.height.toFloat() / drawn.height
    val x = at.x * kx
    val y = at.y * ky
    if (x < 0 || y < 0 || x >= source.width || y >= source.height) return null
    return Offset(x, y)
}

private data class IntPoint(val x: Int, val y: Int)

/**
 * The photograph with everything outside the mask knocked back, for the preview only.
 *
 * Knocked back rather than removed: what is being cut is still shown at a low level, because a
 * refine screen that renders the discarded half as pure transparency gives you nothing to aim
 * the wand at. You cannot tap the table to remove it if the table is already invisible.
 *
 * Downsampled to at most [PREVIEW_EDGE] first. This runs on every mask change — every frame of
 * a brush stroke — and compositing a full 1600px bitmap per frame is what a stuttering refine
 * screen looks like.
 */
private const val PREVIEW_EDGE = 640
private const val GHOST = 0.18f

private fun composePreview(source: Bitmap, mask: Mask): Bitmap {
    val long = max(source.width, source.height)
    val k = if (long > PREVIEW_EDGE) PREVIEW_EDGE.toFloat() / long else 1f
    val w = max(1, (source.width * k).toInt())
    val h = max(1, (source.height * k).toInt())

    val small = if (k == 1f) source else Bitmap.createScaledBitmap(source, w, h, true)
    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    if (small !== source) small.recycle()

    for (y in 0 until h) {
        // Sample the mask rather than scaling it: one multiply per pixel against an array that
        // already exists, instead of allocating a second mask every frame.
        val my = (y.toLong() * mask.height / h).toInt().coerceIn(0, mask.height - 1)
        val row = y * w
        for (x in 0 until w) {
            val mx = (x.toLong() * mask.width / w).toInt().coerceIn(0, mask.width - 1)
            val a = mask[mx, my] / 255f
            val p = px[row + x]
            val weight = GHOST + (1f - GHOST) * a
            val r = (((p shr 16) and 0xFF) * weight).toInt()
            val g = (((p shr 8) and 0xFF) * weight).toInt()
            val b = ((p and 0xFF) * weight).toInt()
            px[row + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}
