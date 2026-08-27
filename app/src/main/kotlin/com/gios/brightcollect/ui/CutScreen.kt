package com.gios.brightcollect.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gios.brightcollect.cut.Mask
import com.gios.brightcollect.ui.theme.LightText
import com.gios.brightcollect.ui.theme.LightTextVariant
import com.gios.brightcollect.ui.theme.LightThemeTokens
import com.gios.light.common.hw.LocalWheelBus
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Where a photograph becomes a sticker.
 *
 * The model gets first go and is usually right, and what is left over is the whole of this
 * screen. Three things carry it:
 *
 *  - **The wand** is one tap and takes a whole contiguous region of similar colour — the table an
 *    object stands on, the shadowed half of a mug. When the model is wrong it is wrong about a
 *    region, and scrubbing that out with a fingertip is the wrong size of gesture for it.
 *  - **The brush** is for what the wand cannot describe: a handle against a background of the
 *    same colour, a strand of something.
 *  - **Zoom** is what makes the brush usable at all. Painting a two-pixel edge with a finger on a
 *    3.9-inch panel is impossible at fit-to-screen and easy at eight times.
 *
 * KEEP and CUT apply to both tools, so there are two controls rather than four.
 *
 * **The picture gets the screen.** An earlier version stacked two rows of buttons and a stepper
 * under it, which left the photograph about half the panel — on the one screen in the app whose
 * entire job is letting you see an edge clearly. The controls are one bar of short words now, and
 * the two continuous values — brush size, wand tolerance — are on the **wheel**, where they cost
 * no screen at all. That is the trade the LPIII's hardware exists to make.
 */
@Composable
fun CutScreen(
    refine: Refine,
    undoDepth: Int,
    redoDepth: Int,
    onWand: (Int, Int) -> Unit,
    onStrokeStart: () -> Unit,
    onPaint: (Float, Float) -> Unit,
    onTool: (Tool) -> Unit,
    onMode: (Mode) -> Unit,
    onTolerance: (Int) -> Unit,
    onBrush: (Float) -> Unit,
    onZoom: (Float, Float, Float) -> Unit,
    onResetZoom: () -> Unit,
    onTogglePreview: () -> Unit,
    onTidy: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    val colors = LightThemeTokens.colors

    // The preview is rebuilt from the mask, which the brush mutates in place. A version counter
    // is what tells Compose the pixels changed when the object identity did not.
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(refine) { version++ }

    var canvas by remember { mutableStateOf(IntSize.Zero) }

    val fit = remember(canvas, refine.source) {
        Fit.inside(
            canvas.width.toFloat(),
            canvas.height.toFloat(),
            refine.source.width,
            refine.source.height,
        )
    }
    // One rectangle again, now with the zoom folded into it — see [Viewport]. Everything below
    // reads this and nothing below knows the picture can be zoomed.
    val frame = remember(fit, refine.scale, refine.panX, refine.panY) {
        Viewport.rect(fit, refine.scale, refine.panX, refine.panY)
    }

    val image: ImageBitmap = remember(refine.source, refine.preview, version) {
        if (refine.preview) {
            cutoutPreview(refine.source, refine.mask).asImageBitmap()
        } else {
            ghostPreview(refine.source, refine.mask).asImageBitmap()
        }
    }

    // The wheel drives whichever continuous value the current tool has. Held in a rememberUpdated
    // so the collector below is not restarted every time one of them changes — restarting a
    // SharedFlow collector drops the notches that arrive during the gap, and the wheel emits
    // faster than a frame.
    val state = rememberUpdatedState(refine)
    val bus = LocalWheelBus.current
    LaunchedEffect(bus) {
        bus?.notches?.collectLatest { notches ->
            val r = state.value
            if (r.tool == Tool.Wand) {
                onTolerance(r.tolerance + notches * 2)
            } else {
                onBrush(r.brush + notches * 4f)
            }
        }
    }
    // Held in, the wheel zooms whatever tool is up. A pinch is the obvious gesture and it is also
    // the one that needs two fingers on a phone held in one hand.
    LaunchedEffect(bus, fit, canvas) {
        bus?.pressedNotches?.collectLatest { notches ->
            val r = state.value
            val (s, px, py) = Viewport.zoomAround(
                fit = fit,
                boxWidth = canvas.width.toFloat(),
                boxHeight = canvas.height.toFloat(),
                scale = r.scale,
                panX = r.panX,
                panY = r.panY,
                factor = if (notches > 0) 1.25f else 0.8f,
                focusX = canvas.width / 2f,
                focusY = canvas.height / 2f,
            )
            onZoom(s, px, py)
        }
    }

    ColourEffect(enabled = true)

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvas = it }
                    // **Two fingers before one.** This handler runs in the Initial pass, so a
                    // second finger claims the gesture before the tool handler below ever sees
                    // it — otherwise the first finger of a pinch has already started a brush
                    // stroke and painted a line across the picture on the way to zooming.
                    .pointerInput(fit, canvas) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pointers = event.changes.count { it.pressed }
                                if (pointers >= 2) {
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    val centroid = event.calculateCentroid(useCurrent = true)
                                    if (zoom != 1f || pan != Offset.Zero) {
                                        val r = state.value
                                        val (s, px, py) = Viewport.zoomAround(
                                            fit = fit,
                                            boxWidth = size.width.toFloat(),
                                            boxHeight = size.height.toFloat(),
                                            scale = r.scale,
                                            panX = r.panX + pan.x,
                                            panY = r.panY + pan.y,
                                            factor = zoom,
                                            focusX = centroid.x,
                                            focusY = centroid.y,
                                        )
                                        onZoom(s, px, py)
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    // One finger is the tool. Written out rather than using detectTapGestures and
                    // detectDragGestures, because those two cannot coexist on one modifier without
                    // the tap waiting out the drag's slop on every single press.
                    .pointerInput(refine.tool, frame, refine.source) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            var moved = false
                            var multi = false
                            var started = false
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } >= 2) {
                                    multi = true
                                }
                                if (multi) continue
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.positionChanged()) {
                                    val travelled = hypot(
                                        change.position.x - down.position.x,
                                        change.position.y - down.position.y,
                                    )
                                    if (travelled > SLOP) moved = true
                                }
                                if (moved && refine.tool == Tool.Brush) {
                                    if (!started) {
                                        started = true
                                        onStrokeStart()
                                    }
                                    Fit.toSource(
                                        frame,
                                        change.position.x,
                                        change.position.y,
                                        refine.source.width,
                                        refine.source.height,
                                    )?.let { onPaint(it.first, it.second) }
                                    change.consume()
                                }
                            } while (event.changes.any { it.pressed })

                            if (multi) return@awaitEachGesture
                            if (!moved) {
                                // A tap. The wand fills; the brush dabs, which is what a brush
                                // does when you touch it to something without moving.
                                val p = Fit.toSource(
                                    frame,
                                    down.position.x,
                                    down.position.y,
                                    refine.source.width,
                                    refine.source.height,
                                ) ?: return@awaitEachGesture
                                if (refine.tool == Tool.Wand) {
                                    onWand(p.first.roundToInt(), p.second.roundToInt())
                                } else {
                                    onStrokeStart()
                                    onPaint(p.first, p.second)
                                }
                            }
                        }
                    },
            ) {
                if (frame.width <= 0f) return@Canvas
                if (refine.preview) {
                    checkerboard(colors.rule)
                }
                drawImage(
                    image = image,
                    dstOffset = IntOffset(frame.x.roundToInt(), frame.y.roundToInt()),
                    dstSize = IntSize(frame.width.roundToInt(), frame.height.roundToInt()),
                )
                if (refine.tool == Tool.Brush && !refine.preview) {
                    // The brush at the size it will actually paint, in the middle of the picture.
                    // Measured through the viewport, so zooming in shows it covering less of the
                    // photograph — which is the truth, and the reason to zoom in.
                    drawCircle(
                        color = colors.content,
                        radius = Viewport.brushOnScreen(frame, refine.source.width, refine.brush),
                        center = Offset(size.width / 2f, size.height / 2f),
                        style = Stroke(width = 2f),
                        alpha = 0.35f,
                    )
                }
            }

            if (refine.thinking) {
                Box(
                    Modifier.fillMaxSize().background(colors.scrim),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText("FINDING IT", LightTextVariant.Detail, align = TextAlign.Center)
                }
            }

            // The one readout, over the picture rather than beside it: what the wheel is doing.
            Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                LightText(
                    text = buildString {
                        if (refine.scale > 1.01f) append("${refine.scale.roundToInt()}x  ")
                        append(
                            if (refine.tool == Tool.Wand) {
                                "SIMILARITY ${refine.tolerance}"
                            } else {
                                "SIZE ${refine.brush.roundToInt()}"
                            },
                        )
                    },
                    variant = LightTextVariant.Superfine,
                    lighten = true,
                )
            }
        }

        refine.hint?.let {
            LightText(
                text = it,
                variant = LightTextVariant.Superfine,
                align = TextAlign.Center,
                lighten = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }

        Column(
            Modifier.padding(horizontal = lightInset(), vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                LightButton(
                    "WAND",
                    Modifier.weight(1f),
                    selected = refine.tool == Tool.Wand,
                ) { onTool(Tool.Wand) }
                LightButton(
                    "BRUSH",
                    Modifier.weight(1f),
                    selected = refine.tool == Tool.Brush,
                ) { onTool(Tool.Brush) }
                LightButton(
                    "KEEP",
                    Modifier.weight(1f),
                    selected = refine.mode == Mode.Keep,
                ) { onMode(Mode.Keep) }
                LightButton(
                    "CUT",
                    Modifier.weight(1f),
                    selected = refine.mode == Mode.Cut,
                ) { onMode(Mode.Cut) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                LightButton("↶", Modifier.weight(1f), enabled = undoDepth > 0, onClick = onUndo)
                LightButton("↷", Modifier.weight(1f), enabled = redoDepth > 0, onClick = onRedo)
                LightButton("TIDY", Modifier.weight(1.2f), onClick = onTidy)
                LightButton(
                    if (refine.preview) "EDIT" else "SEE",
                    Modifier.weight(1.2f),
                    selected = refine.preview,
                    onClick = onTogglePreview,
                )
                LightButton(
                    "FIT",
                    Modifier.weight(1f),
                    enabled = refine.scale > 1.01f,
                    onClick = onResetZoom,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                LightButton("BACK", Modifier.weight(1f), onClick = onDiscard)
                LightButton("KEEP IT", Modifier.weight(2f), selected = true, onClick = onSave)
            }
        }
    }
}

/** How far a finger may wander and still be a tap. */
private const val SLOP = 12f

private fun androidx.compose.ui.graphics.drawscope.DrawScope.checkerboard(tint: Color) {
    val cell = 16f
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = 0f
        var col = 0
        while (x < size.width) {
            if ((row + col) % 2 == 0) {
                drawRect(
                    color = tint,
                    topLeft = Offset(x, y),
                    size = androidx.compose.ui.geometry.Size(cell, cell),
                )
            }
            x += cell
            col++
        }
        y += cell
        row++
    }
}

private const val PREVIEW_EDGE = 720
private const val GHOST = 0.18f

/**
 * The photograph with everything outside the mask knocked back.
 *
 * Knocked back rather than removed: what is being cut is still shown faintly, because a refine
 * screen that renders the discarded half as pure transparency gives you nothing to aim the wand
 * at. You cannot tap the table to remove it if the table is already invisible.
 */
private fun ghostPreview(source: Bitmap, mask: Mask): Bitmap =
    render(source, mask) { p, a ->
        val weight = GHOST + (1f - GHOST) * a
        val r = (((p shr 16) and 0xFF) * weight).toInt()
        val g = (((p shr 8) and 0xFF) * weight).toInt()
        val b = ((p and 0xFF) * weight).toInt()
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

/**
 * The real thing: the cutout, with its actual alpha, over whatever is behind it.
 *
 * The screen draws a checkerboard under this. Being able to see the finished edge before
 * committing is most of what "is this good enough" means, and the ghosted view cannot show it —
 * a soft edge and a hard one look identical when everything is 18% visible.
 */
private fun cutoutPreview(source: Bitmap, mask: Mask): Bitmap =
    render(source, mask) { p, a ->
        ((a * 255f).toInt().coerceIn(0, 255) shl 24) or (p and 0x00FFFFFF)
    }

/**
 * Both previews, over one downsample.
 *
 * Downsampled to at most [PREVIEW_EDGE] first: this runs on every mask change — every frame of a
 * brush stroke — and compositing a full 1600px bitmap per frame is what a stuttering refine
 * screen looks like. The mask is sampled rather than scaled, which is one multiply per pixel
 * against an array that already exists instead of a second mask allocated every frame.
 */
private inline fun render(source: Bitmap, mask: Mask, pixel: (Int, Float) -> Int): Bitmap {
    val long = max(source.width, source.height)
    val k = if (long > PREVIEW_EDGE) PREVIEW_EDGE.toFloat() / long else 1f
    val w = max(1, (source.width * k).toInt())
    val h = max(1, (source.height * k).toInt())

    val small = if (k == 1f) source else Bitmap.createScaledBitmap(source, w, h, true)
    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    if (small !== source) small.recycle()

    for (y in 0 until h) {
        val my = (y.toLong() * mask.height / h).toInt().coerceIn(0, mask.height - 1)
        val row = y * w
        for (x in 0 until w) {
            val mx = (x.toLong() * mask.width / w).toInt().coerceIn(0, mask.width - 1)
            px[row + x] = pixel(px[row + x], mask[mx, my] / 255f)
        }
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}
