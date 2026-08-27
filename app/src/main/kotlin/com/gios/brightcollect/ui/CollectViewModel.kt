package com.gios.brightcollect.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gios.brightcollect.cut.Cutout
import com.gios.brightcollect.cut.Blobs
import com.gios.brightcollect.cut.Mask
import com.gios.brightcollect.cut.Namer
import com.gios.brightcollect.cut.Segmenter
import com.gios.brightcollect.cut.Wand
import com.gios.brightcollect.cut.feather
import com.gios.brightcollect.cut.harden
import com.gios.brightcollect.data.Sticker
import com.gios.brightcollect.data.StickerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Which of the two things a gesture does. Shared by the wand and the brush. */
enum class Mode { Keep, Cut }

/** Which gesture. */
enum class Tool { Wand, Brush }

/**
 * The refine screen's working set.
 *
 * Everything is at the *working* resolution, not the photograph's. The source is downscaled to
 * [Cutout.MAX_EDGE] the moment it is captured, and the mask, the wand's pixel array and the
 * brush all operate there. That single decision is what makes the screen usable: a flood fill
 * over twelve million pixels takes about a second and allocates a 12 MB boolean array per tap,
 * and the sticker is capped at that size when it is saved anyway — so the full-resolution
 * pixels would be thrown away after being carried through every operation.
 */
data class Refine(
    val source: Bitmap,
    /** The source's pixels, kept unpacked because the wand reads them on every tap. */
    val pixels: IntArray,
    val mask: Mask,
    val tool: Tool = Tool.Wand,
    val mode: Mode = Mode.Cut,
    val tolerance: Int = 24,
    val brush: Float = 48f,
    val capturedAt: Long = System.currentTimeMillis(),
    /** True while the model is still deciding — the first mask arrives after the photo does. */
    val thinking: Boolean = false,
    val hint: String? = null,
    /**
     * The sticker being re-cut, or null for a fresh photograph.
     *
     * The one thing that makes reopening different from capturing: a re-cut keeps its id, its
     * name and the date it was caught, so it stays the same object in the collection rather than
     * becoming a second one with the same picture.
     */
    val editing: String? = null,
    /** 1 is fit-to-screen. See [Viewport]. */
    val scale: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    /** Showing the real cutout on a checkerboard rather than the ghosted photograph. */
    val preview: Boolean = false,
) {
    // Generated equals on a data class with an array member compares by identity and warns.
    // Identity is the correct comparison here — the array is swapped wholesale or not at all —
    // so it is spelled out rather than suppressed.
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

sealed interface Stage {
    data object Shelf : Stage
    data object Camera : Stage
    data class Working(val what: String) : Stage
    data class Cut(val refine: Refine) : Stage
    data class Detail(val id: String) : Stage
}

class CollectViewModel(app: Application) : AndroidViewModel(app) {

    private val store = StickerStore(app)
    private val segmenter = Segmenter(app)

    private val _stage = MutableStateFlow<Stage>(Stage.Shelf)
    val stage: StateFlow<Stage> = _stage.asStateFlow()

    private val _stickers = MutableStateFlow<List<Sticker>>(emptyList())
    val stickers: StateFlow<List<Sticker>> = _stickers.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /**
     * How many undo steps are available.
     *
     * A flow rather than a getter on the deque. Compose only recomposes on state it can
     * observe, and a plain `val canUndo get() = undo.isNotEmpty()` reads correctly and then
     * leaves the UNDO button greyed out until something else on the screen happens to change.
     */
    private val _undoDepth = MutableStateFlow(0)
    val undoDepth: StateFlow<Int> = _undoDepth.asStateFlow()

    private val _redoDepth = MutableStateFlow(0)
    val redoDepth: StateFlow<Int> = _redoDepth.asStateFlow()

    /**
     * Undo, as whole mask snapshots.
     *
     * A snapshot is width*height bytes — about 2 MB at the working resolution — and the depth
     * is capped at [UNDO_DEPTH] because the alternative, a command log, would have to replay
     * flood fills to rebuild a state and the whole point of undo is that it is instant.
     */
    private val undo = ArrayDeque<ByteArray>()

    /**
     * The other half of undo.
     *
     * Cleared by any new edit, which is the convention everywhere and the only one that is not
     * confusing: a redo stack that survives a fresh stroke offers to reapply a change to a mask
     * that no longer has the change it was made against.
     */
    private val redo = ArrayDeque<ByteArray>()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _stickers.value = withContext(Dispatchers.IO) { store.all() }
        }
    }

    fun go(stage: Stage) {
        _stage.value = stage
    }

    fun say(message: String?) {
        _toast.value = message
    }

    /**
     * A photograph arrived. Downscale it, show it, and start the model.
     *
     * The refine screen opens *before* the mask exists, with `thinking` set. Waiting on a
     * spinner for a second and a half after every shutter press is the difference between a
     * camera that feels instant and one that does not, and the photograph is worth looking at
     * on its own while the model catches up.
     */
    fun onPhoto(bitmap: Bitmap, capturedAt: Long = System.currentTimeMillis()) {
        val work = Cutout.downscale(bitmap, Cutout.MAX_EDGE)
        val px = IntArray(work.width * work.height)
        work.getPixels(px, 0, work.width, 0, 0, work.width, work.height)
        undo.clear()
        redo.clear()
        _undoDepth.value = 0
        _redoDepth.value = 0
        val blank = Mask(work.width, work.height)
        _stage.value = Stage.Cut(
            Refine(source = work, pixels = px, mask = blank, capturedAt = capturedAt, thinking = true),
        )
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val small = segmenter.run(work)
                    // Harden before the upscale, feather after it. The other order feathers a
                    // 320-pixel grid and then magnifies the blur tenfold into a visible band.
                    val full = Mask.scale(small.harden(), work.width, work.height)
                    // **Before the feather, not after.** Feathering turns every speck into a soft
                    // grey smudge whose faint tail may still touch the object, which is exactly
                    // the case four-connectivity would then call connected. Dropping the strays
                    // while the mask is still hard is the only place the question has a clean
                    // answer. See Blobs.
                    Blobs.keepLargest(full)
                    full.feather(featherFor(work))
                }
            }
            val current = _stage.value as? Stage.Cut ?: return@launch
            val mask = result.getOrNull()
            if (mask == null) {
                _stage.value = Stage.Cut(
                    current.refine.copy(
                        thinking = false,
                        hint = "Couldn't read that one — cut it out by hand",
                    ),
                )
                return@launch
            }
            val found = mask.coverage() > MIN_COVERAGE
            _stage.value = Stage.Cut(
                current.refine.copy(
                    mask = mask,
                    thinking = false,
                    hint = if (found) null else "Nothing obvious in that shot — try the wand",
                ),
            )
        }
    }

    /**
     * A tap with the wand.
     *
     * The region is grown by two pixels before it is committed. A fill stopped by a tolerance
     * always stops just short of the real boundary, because the last pixels before an edge are
     * a blend of both sides and match neither; without the grow, cutting a background away
     * leaves a one-pixel halo of it clinging to the sticker.
     */
    fun wand(x: Int, y: Int) {
        val stage = _stage.value as? Stage.Cut ?: return
        val r = stage.refine
        viewModelScope.launch {
            val region = withContext(Dispatchers.Default) {
                Wand.select(r.pixels, r.source.width, r.source.height, x, y, r.tolerance)
            }
            val took = Wand.size(region).toFloat() / r.pixels.size
            if (took > MAX_FILL) {
                // A fill that swallowed the frame has told you nothing, and committing it would
                // wipe the mask in one tap. Refusing is more useful than a very large undo.
                say("That took nearly everything — lower the tolerance")
                return@launch
            }
            if (took == 0f) return@launch
            pushUndo(r.mask)
            val grown = withContext(Dispatchers.Default) {
                Wand.grow(region, r.source.width, r.source.height, GROW)
            }
            val next = r.mask.copy()
            next.applyRegion(grown, if (r.mode == Mode.Keep) 255 else 0)
            _stage.value = Stage.Cut(r.copy(mask = next, hint = null))
        }
    }

    /** The start of a brush stroke: one snapshot for the whole drag, not one per frame. */
    fun beginStroke() {
        val r = (_stage.value as? Stage.Cut)?.refine ?: return
        pushUndo(r.mask)
    }

    /**
     * A point on a brush stroke.
     *
     * Mutates the mask in place and republishes the same [Refine] with a new identity, rather
     * than copying two megabytes per touch event. The screen redraws from a version counter.
     */
    fun paint(x: Float, y: Float) {
        val stage = _stage.value as? Stage.Cut ?: return
        val r = stage.refine
        r.mask.paint(x, y, r.brush, if (r.mode == Mode.Keep) 255 else 0)
        _stage.value = Stage.Cut(r.copy(hint = null))
    }

    fun setTool(tool: Tool) = updateRefine { it.copy(tool = tool) }
    fun setMode(mode: Mode) = updateRefine { it.copy(mode = mode) }
    fun setTolerance(v: Int) = updateRefine { it.copy(tolerance = v.coerceIn(0, Wand.MAX_TOLERANCE)) }
    fun setBrush(v: Float) = updateRefine { it.copy(brush = v.coerceIn(8f, 160f)) }

    fun undo() {
        val stage = _stage.value as? Stage.Cut ?: return
        val previous = undo.removeLastOrNull() ?: return
        val r = stage.refine
        redo.addLast(r.mask.a.copyOf())
        _undoDepth.value = undo.size
        _redoDepth.value = redo.size
        _stage.value = Stage.Cut(r.copy(mask = Mask(r.mask.width, r.mask.height, previous)))
    }

    fun redo() {
        val stage = _stage.value as? Stage.Cut ?: return
        val next = redo.removeLastOrNull() ?: return
        val r = stage.refine
        undo.addLast(r.mask.a.copyOf())
        _undoDepth.value = undo.size
        _redoDepth.value = redo.size
        _stage.value = Stage.Cut(r.copy(mask = Mask(r.mask.width, r.mask.height, next)))
    }

    /**
     * Throws away everything not joined to the main shape.
     *
     * Offered as an action as well as run automatically after the model, because after you have
     * brushed and filled for a while the mask picks up strays again — and because a brushed-on
     * region that *is* separate on purpose must survive, which it cannot if this ran after every
     * edit. See [Blobs].
     */
    fun tidy() {
        val stage = _stage.value as? Stage.Cut ?: return
        val r = stage.refine
        val next = r.mask.copy()
        val dropped = Blobs.keepLargest(next)
        if (dropped == 0) {
            say("Nothing loose to remove")
            return
        }
        pushUndo(r.mask)
        _stage.value = Stage.Cut(r.copy(mask = next))
        say("Removed what wasn't attached")
    }

    fun zoom(scale: Float, panX: Float, panY: Float) = updateRefine {
        it.copy(scale = scale, panX = panX, panY = panY)
    }

    fun resetZoom() = updateRefine { it.copy(scale = 1f, panX = 0f, panY = 0f) }

    fun togglePreview() = updateRefine { it.copy(preview = !it.preview) }

    fun discard() {
        val editing = (_stage.value as? Stage.Cut)?.refine?.editing
        undo.clear()
        redo.clear()
        _undoDepth.value = 0
        _redoDepth.value = 0
        // Backing out of a re-cut returns to the sticker you opened, not to the shelf. Landing
        // on the shelf reads as though the sticker went somewhere.
        _stage.value = if (editing != null) Stage.Detail(editing) else Stage.Shelf
    }

    /**
     * Saves what is on the refine screen.
     *
     * Nothing is written outside this app. BrightNotebook reads the day's catches from
     * [com.gios.brightcollect.share.CaughtProvider] when it draws a day, so there is no copy to
     * keep in step and no second place a sticker can go missing from.
     */
    fun save(name: String? = null) {
        val stage = _stage.value as? Stage.Cut ?: return
        val r = stage.refine
        _stage.value = Stage.Working(if (r.editing != null) "Re-cutting" else "Cutting out")
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val bitmap = Cutout.compose(r.source, r.mask) ?: return@withContext null
                val existing = r.editing
                if (existing != null) {
                    // A re-cut keeps its id, its name and the day it was caught. Saving a new one
                    // would leave the old sticker in the tray beside it and quietly double the
                    // collection every time somebody tidied an edge.
                    store.replace(existing, bitmap, r.mask, r.source)
                    return@withContext store.get(existing)
                }
                // Only when the caller had no name of its own. The labeller is a suggestion, and
                // a suggestion must never overwrite something a person typed.
                val guess = if (name.isNullOrBlank()) Namer.suggest(bitmap) else null
                store.save(
                    bitmap = bitmap,
                    name = name ?: guess,
                    capturedAt = r.capturedAt,
                    suggested = guess != null,
                    source = r.source,
                    mask = r.mask,
                )
            }
            if (saved == null) {
                say("Nothing left to cut out")
                _stage.value = Stage.Cut(r)
                return@launch
            }
            undo.clear()
            redo.clear()
            _undoDepth.value = 0
            _redoDepth.value = 0
            refresh()
            _stage.value = Stage.Detail(saved.id)
        }
    }

    /**
     * Reopens a saved sticker for another go.
     *
     * Loads the photograph it came from and the mask that cut it, so the second edit starts
     * exactly where the first one finished — every wand fill and brush stroke still in place. A
     * sticker whose source was never kept, or was lost in a restore, cannot be reopened and says
     * so rather than silently starting from a blank mask.
     */
    fun edit(id: String) {
        _stage.value = Stage.Working("Opening")
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val source = store.loadSource(id) ?: return@withContext null
                val mask = store.loadMask(id, source.width, source.height)
                    ?: return@withContext null
                val px = IntArray(source.width * source.height)
                source.getPixels(px, 0, source.width, 0, 0, source.width, source.height)
                Triple(source, px, mask)
            }
            if (loaded == null) {
                say("That one can't be reopened")
                _stage.value = Stage.Detail(id)
                return@launch
            }
            val (source, px, mask) = loaded
            undo.clear()
            redo.clear()
            _undoDepth.value = 0
            _redoDepth.value = 0
            val sticker = store.get(id)
            _stage.value = Stage.Cut(
                Refine(
                    source = source,
                    pixels = px,
                    mask = mask,
                    capturedAt = sticker?.capturedAt ?: System.currentTimeMillis(),
                    editing = id,
                ),
            )
        }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // suggested = false: whatever it says now, a person has been shown it and left
                // it there, so it is theirs.
                store.get(id)?.let { store.update(it.copy(name = name, suggested = false)) }
            }
            refresh()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            refresh()
            _stage.value = Stage.Shelf
        }
    }

    /** The store, for screens that decode their own thumbnails. */
    fun stickers(): StickerStore = store

    override fun onCleared() {
        super.onCleared()
        segmenter.close()
        Namer.close()
    }

    private fun updateRefine(block: (Refine) -> Refine) {
        val stage = _stage.value as? Stage.Cut ?: return
        _stage.value = Stage.Cut(block(stage.refine))
    }

    private fun pushUndo(mask: Mask) {
        undo.addLast(mask.a.copyOf())
        while (undo.size > UNDO_DEPTH) undo.removeFirst()
        redo.clear()
        _undoDepth.value = undo.size
        _redoDepth.value = 0
    }

    private companion object {
        const val UNDO_DEPTH = 8

        /** Grow, in pixels, applied to a wand region before it is committed. */
        const val GROW = 2

        /** Below this the model found nothing worth calling an object. */
        const val MIN_COVERAGE = 0.005f

        /** A fill above this fraction of the frame is refused rather than committed. */
        const val MAX_FILL = 0.92f

        /** Feather radius, scaled so the edge is the same softness at any working size. */
        fun featherFor(bitmap: Bitmap): Int =
            max(1, max(bitmap.width, bitmap.height) / 400)
    }
}
