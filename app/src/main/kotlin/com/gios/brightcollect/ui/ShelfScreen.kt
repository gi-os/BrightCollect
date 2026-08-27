package com.gios.brightcollect.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
 * The collection.
 *
 * **In colour**, via [ColourEffect], and this is the screen the whole permission exists for.
 * The point of collecting things is that they are different, and on a monochrome panel a
 * hundred cutouts are a hundred grey blobs — the shelf is the one place in the app where the
 * greyscale pin actively destroys the content. Roll lifts it for the viewfinder for the same
 * reason and settles for the picture; here it is the whole grid.
 *
 * Three columns rather than two. A sticker is a single object with a lot of transparent margin
 * around it, so it reads at a smaller size than a photograph would, and three across is where
 * a shelf starts to look like a collection rather than a list.
 */
@Composable
fun ShelfScreen(
    stickers: List<Sticker>,
    store: StickerStore,
    onOpen: (String) -> Unit,
    onCapture: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    val grid = rememberLazyGridState()

    ColourEffect(enabled = true)
    // The wheel scrolls the shelf. On a phone with a physical wheel, a grid you can only reach
    // by dragging is the one that gets used least.
    WheelScroll(grid)

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = lightInset()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = lightInset(), bottom = 6.dp),
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

        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(stickers, key = { it.id }) { sticker ->
                Cell(sticker = sticker, store = store, onOpen = onOpen)
            }
        }
    }
}

@Composable
private fun Cell(sticker: Sticker, store: StickerStore, onOpen: (String) -> Unit) {
    // Decoded off the main thread and keyed on the id, so scrolling back up does not re-decode
    // and a cell recycled onto a different sticker does not briefly show the old one.
    var thumb by remember(sticker.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(sticker.id) {
        thumb = withContext(Dispatchers.IO) { store.loadThumb(sticker.id) }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .lightClickable { onOpen(sticker.id) },
        contentAlignment = Alignment.Center,
    ) {
        thumb?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = sticker.name,
                // Fit, not Crop. A sticker cropped to a square is a sticker with its edges cut
                // off, which is the one thing the whole app is about not doing.
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
