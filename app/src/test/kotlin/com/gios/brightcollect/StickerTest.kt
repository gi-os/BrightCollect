package com.gios.brightcollect

import com.gios.brightcollect.data.Sticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerTest {

    @Test
    fun `a round trip through json keeps every field`() {
        val s = Sticker("abc123", "Basil's bowl", 1_700_000_000_000L, 800, 600, "kitchen", suggested = true)
        val back = Sticker.listFromJson(Sticker.listToJson(listOf(s)))
        assertEquals(listOf(s), back)
    }

    @Test
    fun `one malformed entry costs one sticker, not the collection`() {
        // The index is a file on a phone. A build with a different shape, or a write cut off
        // by a flat battery, must not be how someone loses everything they collected.
        val good = Sticker("keepme", "Mug", 1L, 10, 10)
        val text = """{"version":1,"stickers":[{"name":"no id here"},${good.toJson()}]}"""
        val back = Sticker.listFromJson(text)
        assertEquals(listOf(good), back)
    }

    @Test
    fun `junk parses to an empty collection rather than throwing`() {
        assertEquals(emptyList<Sticker>(), Sticker.listFromJson("not json at all"))
        assertEquals(emptyList<Sticker>(), Sticker.listFromJson(""))
    }

    @Test
    fun `an entry with no id is dropped`() {
        assertNull(Sticker.fromJson(org.json.JSONObject("""{"name":"x"}""")))
    }

    @Test
    fun `a name with no suggested flag reads as one the user chose`() {
        // The default has to be false, not true. An index written by v1.0 has no such key, and
        // reading a missing key as "guessed" would open every sticker you ever named with its
        // name selected and one keystroke from being wiped.
        val old = org.json.JSONObject(
            """{"id":"x","name":"Mug","at":1,"w":10,"h":10}""",
        )
        assertEquals(false, Sticker.fromJson(old)?.suggested)
    }

    @Test
    fun `default names are numbered by how many were ever taken`() {
        assertEquals("Sticker 1", Sticker.defaultName(1))
        assertEquals("Sticker 41", Sticker.defaultName(41))
        assertTrue(Sticker.defaultName(2) != Sticker.defaultName(3))
    }
}
