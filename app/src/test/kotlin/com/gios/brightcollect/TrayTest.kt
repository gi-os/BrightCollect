package com.gios.brightcollect

import com.gios.brightcollect.ui.Tray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class TrayTest {

    private val width = 343

    // The shipping density, not a number picked for the test. A test that packs at a density the
    // app never uses proves nothing about the tray anybody looks at.
    private val area = Tray.targetAreaFor(width)

    private fun items(n: Int, seed: Int = 7): List<Tray.Item> {
        val r = Random(seed)
        return (0 until n).map {
            // Real cutouts: anything from a wide flat thing to a tall thin one.
            Tray.Item("id$it", r.nextInt(120, 2400), r.nextInt(120, 2400))
        }
    }

    private fun overlaps(a: Tray.Placed, b: Tray.Placed): Boolean =
        a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom

    @Test
    fun `nothing overlaps, at any size of collection`() {
        // The failure everybody ships: invisible on the four stickers you test with, obvious on
        // the ninetieth. So it is checked at a size no one would lay out by hand.
        for (n in listOf(1, 2, 3, 9, 40, 150)) {
            val layout = Tray.lay(items(n), width, area)
            assertEquals(n, layout.placed.size)
            for (i in layout.placed.indices) {
                for (j in i + 1 until layout.placed.size) {
                    val a = layout.placed[i]
                    val b = layout.placed[j]
                    assertTrue(
                        "n=$n: ${a.id} at (${a.x},${a.y},${a.boxWidth}x${a.boxHeight}) " +
                            "overlaps ${b.id} at (${b.x},${b.y},${b.boxWidth}x${b.boxHeight})",
                        !overlaps(a, b),
                    )
                }
            }
        }
    }

    @Test
    fun `nothing runs off either edge`() {
        val layout = Tray.lay(items(60), width, area)
        layout.placed.forEach {
            assertTrue("${it.id} starts at ${it.x}", it.x >= 0)
            assertTrue("${it.id} ends at ${it.right}, container is $width", it.right <= width)
        }
    }

    @Test
    fun `the reported height covers everything in it`() {
        val layout = Tray.lay(items(60), width, area)
        val lowest = layout.placed.maxOf { it.bottom }
        assertEquals(lowest, layout.height)
    }

    @Test
    fun `the same collection lays out identically twice`() {
        // Rotation and jitter come from the id, not a random source. A tray that reshuffles on
        // every scroll or restart would be unusable, and the bug reads as flicker rather than as
        // a layout problem.
        val list = items(30)
        assertEquals(Tray.lay(list, width, area).placed, Tray.lay(list, width, area).placed)
    }

    @Test
    fun `aspect ratio survives`() {
        val layout = Tray.lay(listOf(Tray.Item("wide", 2000, 500)), width, area)
        val p = layout.placed.single()
        val was = 2000f / 500f
        val now = p.width.toFloat() / p.height
        assertTrue("aspect went from $was to $now", abs(was - now) / was < 0.06f)
    }

    @Test
    fun `a very long thin object is clamped instead of spanning the screen`() {
        val layout = Tray.lay(listOf(Tray.Item("pencil", 4000, 90)), width, area)
        val p = layout.placed.single()
        assertTrue("width ${p.width} of $width", p.width <= width)
        assertTrue("it should still be nearly the widest thing", p.width > width / 4)
    }

    @Test
    fun `area normalising beats width normalising`() {
        // The whole reason sizeFor works on area. A pencil and a plate should feel like one
        // object each; scaled to a common width the pencil becomes a hairline.
        val layout = Tray.lay(
            listOf(Tray.Item("plate", 1000, 1000), Tray.Item("pencil", 2000, 200)),
            width,
            area,
        )
        val plate = layout.placed.first { it.id == "plate" }
        val pencil = layout.placed.first { it.id == "pencil" }
        val plateArea = plate.width * plate.height
        val pencilArea = pencil.width * pencil.height
        val ratio = maxOf(plateArea, pencilArea).toFloat() / minOf(plateArea, pencilArea)
        assertTrue("areas differ by ${ratio}x", ratio < 3f)
    }

    @Test
    fun `the tilt stays small and is not always the same`() {
        val layout = Tray.lay(items(40), width, area)
        layout.placed.forEach {
            assertTrue("tilt ${it.angle}", abs(it.angle) <= Tray.MAX_TILT + 0.001f)
        }
        assertTrue("tilts must vary", layout.placed.map { it.angle }.distinct().size > 10)
    }

    @Test
    fun `the reserved box allows for the rotation`() {
        // A rotated rectangle is bigger than its unrotated self. If the box did not account for
        // it, neighbours would clip into each other while the overlap test above still passed.
        val layout = Tray.lay(items(30), width, area)
        layout.placed.forEach {
            if (abs(it.angle) > 0.5f) {
                assertTrue(
                    "${it.id}: box ${it.boxWidth}x${it.boxHeight} vs image ${it.width}x${it.height}",
                    it.boxWidth >= it.width && it.boxHeight >= it.height,
                )
            }
        }
    }

    @Test
    fun `about four fit across`() {
        // The density constant's whole job, asserted where a change to it has to come and explain
        // itself. Measured as the mean box width against the tray, which is what "four across"
        // means when nothing is in a column.
        val layout = Tray.lay(items(60), width, area)
        val meanWidth = layout.placed.sumOf { it.boxWidth }.toFloat() / layout.placed.size
        val across = width / meanWidth
        assertTrue("%.2f across, wanted about four".format(across), across in 3.6f..4.6f)
    }

    @Test
    fun `it packs tightly enough to look like a tray`() {
        // Not a beauty test — a guard against the skyline degenerating into one item per row,
        // which is what a broken frontier looks like and still passes every test above.
        val layout = Tray.lay(items(60), width, area)
        val used = layout.placed.sumOf { it.boxWidth.toLong() * it.boxHeight }
        val canvas = width.toLong() * layout.height
        val fill = used.toDouble() / canvas
        assertTrue("only %.0f%% of the tray is used".format(fill * 100), fill > 0.70)
    }

    @Test
    fun `an empty collection is an empty tray`() {
        val layout = Tray.lay(emptyList(), width, area)
        assertTrue(layout.placed.isEmpty())
        assertEquals(0, layout.height)
    }

    @Test
    fun `a zero-sized entry does not bring the shelf down`() {
        // Sticker.fromJson defaults width and height to 0 for a malformed row.
        val layout = Tray.lay(listOf(Tray.Item("broken", 0, 0)), width, area)
        val p = layout.placed.single()
        assertTrue(p.width > 0 && p.height > 0)
    }
}
