package com.gios.brightcollect.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gios.brightcollect.data.Sticker
import com.gios.brightcollect.data.StickerStore
import com.gios.brightcollect.ui.theme.LightText
import com.gios.brightcollect.ui.theme.LightTextVariant
import com.gios.brightcollect.ui.theme.LightThemeTokens
import com.gios.brightcollect.ui.theme.lightClickable
import com.gios.light.common.hw.WheelScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The collection, laid out in a tray.
 *
 * **In colour**, via [ColourEffect], and this is the screen the whole permission exists for. The
 * point of collecting things is that they are different, and on a monochrome panel a hundred
 * cutouts are a hundred grey blobs.
 *
 * The layout is [Tray] — each sticker at its own size and angle, packed against its neighbours
 * rather than dropped into a slot. See that file for why a grid was the wrong shape for this.
 *
 * **Not a LazyVerticalGrid**, because the positions are not rows and columns and there is nothing
 * for one to be lazy about. Laziness is done by hand instead: the packing is pure arithmetic over
 * the bounding boxes and costs nothing for hundreds of items, so the whole tray is laid out up
 * front and only the stickers whose boxes fall in the visible band are composed. That band is
 * derived through [derivedStateOf] from the scroll position divided by [BAND], so it changes a few
 * times a screen rather than on every scrolled pixel — the difference between recomposing the
 * shelf sixty times a second and recomposing it when you have actually moved.
 */
@Composable
fun ShelfScreen(
    stickers: List<Sticker>,
    store: StickerStore,
    onOpen: (String) -> Unit,
    onCapture: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val inset = lightInset()

    // Read from the configuration rather than measured with BoxWithConstraints. The shelf is
    // full-width, so its width is known without a subcomposition, and the packing wants a stable
    // number — one that changes during measurement would relay the tray mid-scroll.
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val trayWidth = (screenWidth - inset.value.toInt() * 2).coerceAtLeast(MIN_TRAY)

    ColourEffect(enabled = true)
    WheelScroll(scroll)

    val layout = remember(stickers, trayWidth) {
        Tray.lay(
            items = stickers.map { Tray.Item(it.id, it.width, it.height) },
            containerWidth = trayWidth,
            // Density, expressed so it holds on any screen: about three across on average.
            targetArea = trayWidth * trayWidth / 13,
        )
    }

    val band by remember {
        derivedStateOf { with(density) { scroll.value.toDp().value }.toInt() / BAND }
    }
    // A map, not a `first { }` per cell. Fifteen cells searching a list of several hundred on
    // every band change is the kind of quadratic that only shows up once a collection is large.
    val byId = remember(stickers) { stickers.associateBy { it.id } }

    val visible = remember(layout, band) {
        val lo = (band - 1) * BAND
        val hi = (band + 2) * BAND
        layout.placed.filter { it.bottom >= lo && it.y <= hi }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = inset),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = inset, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText(
                text = if (stickers.isEmpty()) "COLLECT" else "${stickers.size} COLLECTED",
                variant = LightTextVariant.Detail,
            )
            LightButton("CATCH ONE", selected = true, onClick = onCapture)
        }

        if (stickers.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LightText(
                    text = "Point it at a thing.\nIt gets cut out and kept.",
                    variant = LightTextVariant.Paragraph,
                    align = TextAlign.Center,
                    lighten = true,
                )
            }
            return@Column
        }

        Box(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scroll),
        ) {
            // Holds the scroll range open. Children are absolutely positioned, so without this the
            // Box would be as tall as whichever few stickers happen to be composed right now, and
            // the shelf would refuse to scroll past the first screen.
            Box(Modifier.fillMaxWidth().height(layout.height.dp + inset))

            visible.forEach { p ->
                val sticker = byId[p.id] ?: return@forEach
                Cell(
                    placed = p,
                    sticker = sticker,
                    store = store,
                    onOpen = onOpen,
                )
            }
        }
    }
}

/** How tall a band is, in dp. Three of them are composed at once — one above, one below. */
private const val BAND = 320

/** Below this the tray is not a tray. Guards against a nonsense configuration width. */
private const val MIN_TRAY = 120

@Composable
private fun Cell(
    placed: Tray.Placed,
    sticker: Sticker,
    store: StickerStore,
    onOpen: (String) -> Unit,
) {
    // Decoded off the main thread and keyed on the id, so scrolling back does not re-decode and a
    // cell recycled onto a different sticker never briefly shows the old one.
    var thumb by remember(sticker.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(sticker.id) {
        thumb = withContext(Dispatchers.IO) { store.loadThumb(sticker.id) }
    }

    Box(
        modifier = Modifier
            .offset(x = placed.x.dp, y = placed.y.dp)
            .size(placed.boxWidth.dp, placed.boxHeight.dp)
            .lightClickable { onOpen(sticker.id) },
        contentAlignment = Alignment.Center,
    ) {
        thumb?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = sticker.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(placed.width.dp, placed.height.dp)
                    // The image is drawn at its unrotated size inside a box already widened to
                    // the rotated bounds, so the turn cannot reach a neighbour. `rotate` is a
                    // draw-time transform and does not affect layout, which is what makes that
                    // separation possible — and why Tray has to inflate the box itself.
                    .rotate(placed.angle),
            )
        }
    }
}
