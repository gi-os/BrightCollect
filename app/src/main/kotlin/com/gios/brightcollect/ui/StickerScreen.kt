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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.gios.brightcollect.data.Sticker
import com.gios.brightcollect.data.StickerStore
import com.gios.brightcollect.send.Handoff
import com.gios.brightcollect.ui.theme.LightText
import com.gios.brightcollect.ui.theme.LightTextVariant
import com.gios.brightcollect.ui.theme.LightThemeTokens
import com.gios.brightcollect.ui.theme.lightTextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One sticker: what it is, when you caught it, and the two things you can do with it.
 *
 * Naming is an editable field on this page rather than a dialog after the save, because a
 * sticker with a default name is a finished sticker — being made to name every single one
 * before it is allowed to exist is how a collection stops growing.
 */
@Composable
fun StickerScreen(
    sticker: Sticker,
    store: StickerStore,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    onSaid: (String) -> Unit,
) {
    val colors = LightThemeTokens.colors
    val context = LocalContext.current
    var full by remember(sticker.id) { mutableStateOf<Bitmap?>(null) }
    var name by remember(sticker.id) { mutableStateOf(sticker.name) }
    var confirmDelete by remember(sticker.id) { mutableStateOf(false) }

    LaunchedEffect(sticker.id) {
        full = withContext(Dispatchers.IO) { store.load(sticker.id) }
    }

    ColourEffect(enabled = true)

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = lightInset()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            full?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = sticker.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        BasicTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            singleLine = true,
            textStyle = lightTextStyle(LightTextVariant.Copy).copy(
                color = colors.content,
                textAlign = TextAlign.Center,
            ),
            cursorBrush = SolidColor(colors.content),
            modifier = Modifier.fillMaxWidth(),
        )

        LightText(
            text = CAUGHT.format(Date(sticker.capturedAt)).uppercase(Locale.US),
            variant = LightTextVariant.Superfine,
            align = TextAlign.Center,
            lighten = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = lightInset()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LightButton(
                label = "BACK",
                modifier = Modifier.weight(1f),
                onClick = {
                    // Saved on the way out rather than behind a SAVE button. There is one field
                    // and leaving the page is the only way to finish with it.
                    if (name != sticker.name) onRename(name)
                    onBack()
                },
            )
            LightButton(
                label = if (confirmDelete) "SURE?" else "DROP",
                modifier = Modifier.weight(1f),
                onClick = {
                    // Two taps, and no dialog. An accidental delete costs a photograph plus the
                    // hand-corrections made on top of it, and neither comes back.
                    if (confirmDelete) onDelete() else confirmDelete = true
                },
            )
            LightButton(
                label = "SEND",
                modifier = Modifier.weight(1.2f),
                selected = true,
                onClick = {
                    val file = store.fileFor(sticker.id)
                    val uri = runCatching {
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.files",
                            file,
                        )
                    }.getOrNull()
                    if (uri == null) {
                        onSaid("Couldn't open that one")
                        return@LightButton
                    }
                    when (val outcome = Handoff.send(context, uri)) {
                        is Handoff.Outcome.Failed -> onSaid(outcome.why)
                        else -> Unit
                    }
                },
            )
        }
    }
}

private val CAUGHT = SimpleDateFormat("EEE d MMM yyyy", Locale.US)
