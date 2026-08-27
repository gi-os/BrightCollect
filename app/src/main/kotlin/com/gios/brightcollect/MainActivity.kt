package com.gios.brightcollect

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gios.brightcollect.share.Notebook
import com.gios.brightcollect.ui.CameraScreen
import com.gios.brightcollect.ui.ColorMode
import com.gios.brightcollect.ui.CollectViewModel
import com.gios.brightcollect.ui.CutScreen
import com.gios.brightcollect.ui.ShelfScreen
import com.gios.brightcollect.ui.Stage
import com.gios.brightcollect.ui.StickerScreen
import com.gios.brightcollect.ui.lightInset
import com.gios.brightcollect.ui.theme.BrightCollectTheme
import com.gios.brightcollect.ui.theme.LightText
import com.gios.brightcollect.ui.theme.LightTextVariant
import com.gios.light.common.hw.LightKey
import com.gios.light.common.hw.LightKeys
import com.gios.light.common.hw.LocalWheelBus
import com.gios.light.common.hw.WheelBus
import com.gios.light.common.report.ReportOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One activity, four screens, and the physical keys.
 *
 * [dispatchKeyEvent] is the only place that sees the wheel and the camera button: LightOS
 * delivers them as ordinary [KeyEvent]s once the patched key layout is in place, but a focused
 * child view will eat them before any Compose handler runs. Catching them at the window and
 * publishing to a [WheelBus] is the pattern the rest of the family uses — see BrightControl for
 * why the alternatives do not work.
 */
class MainActivity : ComponentActivity() {

    private val wheel = WheelBus()
    private var viewModel: CollectViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Framing a small object takes both hands and a while. A screen that dims halfway
        // through is a photograph you have to take again.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            BrightCollectTheme {
                val vm: CollectViewModel = viewModel()
                viewModel = vm
                val stage by vm.stage.collectAsStateWithLifecycle()
                val stickers by vm.stickers.collectAsStateWithLifecycle()
                val undoDepth by vm.undoDepth.collectAsStateWithLifecycle()
                val redoDepth by vm.redoDepth.collectAsStateWithLifecycle()
                val toast by vm.toast.collectAsStateWithLifecycle()
                val context = LocalContext.current

                // A photograph handed over by Roll's send button, or by anything else that
                // shares an image. Consumed once — setIntent clears it, so a rotation does not
                // re-import the same picture.
                LaunchedEffect(Unit) {
                    handleIntent(intent, vm)
                    intent = Intent(this@MainActivity, MainActivity::class.java)
                }

                val camera = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted -> if (granted) vm.go(Stage.Camera) else vm.say("Collect needs the camera") }

                // The photo picker, not READ_MEDIA_IMAGES. It runs in the system's process and
                // returns exactly what was chosen, so importing a picture costs no permission
                // and no dialog — the app never gains the ability to read the roll.
                val picker = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia(),
                ) { uri ->
                    if (uri != null) {
                        vm.go(Stage.Working("Opening"))
                        loadInto(vm, uri)
                    } else if (stage is Stage.Working) {
                        vm.go(Stage.Camera)
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalWheelBus provides wheel) {
                        when (val s = stage) {
                            is Stage.Shelf -> ShelfScreen(
                                stickers = stickers,
                                store = vm.stickers(),
                                onOpen = { vm.go(Stage.Detail(it)) },
                                onCapture = {
                                    val ok = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.CAMERA,
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (ok) vm.go(Stage.Camera) else camera.launch(Manifest.permission.CAMERA)
                                },
                            )

                            is Stage.Camera -> CameraScreen(
                                onPhoto = { bitmap, at -> vm.onPhoto(bitmap, at) },
                                onCancel = { vm.go(Stage.Shelf) },
                                onPick = {
                                    picker.launch(
                                        androidx.activity.result.PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                                        ),
                                    )
                                },
                            )

                            is Stage.Working -> Box(
                                Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                LightText(s.what.uppercase(), LightTextVariant.Detail, align = TextAlign.Center)
                            }

                            is Stage.Cut -> CutScreen(
                                refine = s.refine,
                                undoDepth = undoDepth,
                                redoDepth = redoDepth,
                                onWand = vm::wand,
                                onStrokeStart = vm::beginStroke,
                                onPaint = vm::paint,
                                onTool = vm::setTool,
                                onMode = vm::setMode,
                                onTolerance = vm::setTolerance,
                                onBrush = vm::setBrush,
                                onZoom = vm::zoom,
                                onResetZoom = vm::resetZoom,
                                onTogglePreview = vm::togglePreview,
                                onTidy = vm::tidy,
                                onUndo = vm::undo,
                                onRedo = vm::redo,
                                onDiscard = vm::discard,
                                onSave = { vm.save() },
                            )

                            is Stage.Detail -> {
                                val sticker = stickers.firstOrNull { it.id == s.id }
                                if (sticker == null) {
                                    // The sticker behind a stale deep link, or one deleted in
                                    // another session. The shelf is a better answer than a
                                    // blank page with a back button.
                                    LaunchedEffect(s.id) { vm.go(Stage.Shelf) }
                                } else {
                                    StickerScreen(
                                        sticker = sticker,
                                        store = vm.stickers(),
                                        onRename = { vm.rename(sticker.id, it) },
                                        onEdit = { vm.edit(sticker.id) },
                                        onDelete = { vm.delete(sticker.id) },
                                        onBack = { vm.go(Stage.Shelf) },
                                        onSaid = vm::say,
                                    )
                                }
                            }
                        }
                    }

                    toast?.let { message ->
                        LaunchedEffect(message) {
                            kotlinx.coroutines.delay(2600)
                            vm.say(null)
                        }
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxSize(),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            LightText(
                                text = message,
                                variant = LightTextVariant.Superfine,
                                align = TextAlign.Center,
                                modifier = Modifier.padding(bottom = lightInset() * 4),
                            )
                        }
                    }

                    // Shake to report. Bottom-start so it never lands under the shutter.
                    ReportOverlay(corner = Alignment.BottomStart)
                }
            }
        }
    }

    /**
     * Greyscale comes back the moment the app is no longer the thing you are looking at.
     *
     * Without this, leaving Collect from any screen that lifted the daltonizer leaves the whole
     * phone in colour — LightOS included — and the only way back is to open and close the app
     * again. See BrightControl's ColorMode notes: this is state, not a transition.
     */
    override fun onStop() {
        super.onStop()
        ColorMode.onAppHidden(this)
    }

    override fun onStart() {
        super.onStart()
        ColorMode.onAppVisible(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel?.let { handleIntent(intent, it) }
    }

    /**
     * The wheel and the camera button.
     *
     * Returning false always: this publishes the notch and lets the event carry on, because the
     * wheel scrolls a list that Compose is also entitled to see the event for. The camera key
     * is the one exception and is consumed.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (LightKeys.of(event)) {
                LightKey.WheelUp -> wheel.send(1)
                LightKey.WheelDown -> wheel.send(-1)
                LightKey.Camera -> {
                    viewModel?.let { vm ->
                        if (vm.stage.value is Stage.Shelf) vm.go(Stage.Camera)
                    }
                    return true
                }
                else -> Unit
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** A shared image, or a `brightcollect://sticker/<id>` link from a calendar entry. */
    private fun handleIntent(intent: Intent?, vm: CollectViewModel) {
        Notebook.idFrom(intent)?.let {
            vm.go(Stage.Detail(it))
            return
        }
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
            vm.go(Stage.Working("Opening"))
            loadInto(vm, uri)
        }
    }

    /**
     * Decodes [uri] and hands it to the model.
     *
     * `inSampleSize` on the way in rather than a full decode: a shared photograph can be twelve
     * megapixels, the working size is 1600px, and decoding the full thing first is an 48 MB
     * allocation to throw away — which on this phone is the difference between opening and
     * being killed.
     */
    private fun loadInto(vm: CollectViewModel, uri: Uri) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, probe)
                    }
                    var sample = 1
                    val target = com.gios.brightcollect.cut.Cutout.MAX_EDGE
                    while (probe.outWidth / (sample * 2) >= target ||
                        probe.outHeight / (sample * 2) >= target
                    ) {
                        sample *= 2
                    }
                    contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(
                            it,
                            null,
                            BitmapFactory.Options().apply { inSampleSize = sample },
                        )
                    }
                }.getOrNull()
            }
            if (bitmap == null) {
                vm.say("Couldn't open that picture")
                vm.go(Stage.Shelf)
            } else {
                // The capture time is the photograph's, not now — see Notebook: a sticker made
                // from a picture taken in June belongs on June's page of the calendar.
                vm.onPhoto(bitmap, exifTime(uri) ?: System.currentTimeMillis())
            }
        }
    }

    private fun exifTime(uri: Uri): Long? = runCatching {
        contentResolver.openInputStream(uri)?.use { stream ->
            val exif = androidx.exifinterface.media.ExifInterface(stream)
            val raw = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: return@use null
            java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
                .parse(raw)?.time
        }
    }.getOrNull()
}
