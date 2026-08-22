package borg.trikeshed.media

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsoBmffTest {
    private fun box(type: String, vararg kids: ByteArray, pad: Int = 0): ByteArray {
        val payload = kids.fold(ByteArray(0)) { a, b -> a + b } + ByteArray(pad)
        val n = payload.size + 8
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + type.encodeToByteArray() + payload
    }

    private val trak1 = box("trak", box("tkhd", pad = 84), box("mdia", box("mdhd", pad = 24), box("hdlr", pad = 25), box("minf", box("stbl", box("stsd", pad = 8)))))
    private val trak2 = box("trak", box("tkhd", pad = 84), box("mdia", box("mdhd", pad = 24)))
    private val moov = box("moov", box("mvhd", pad = 100), trak1, trak2)
    private val mdat = byteArrayOf(0, 0, 0, 0) + "mdat".encodeToByteArray() + ByteArray(1000)
    private val file = box("ftyp", pad = 16) + moov + mdat

    @Test
    fun key() = assertEquals(
        listOf("ftyp", "moov", "mvhd", "trak", "tkhd", "mdia", "mdhd", "hdlr", "minf", "stbl", "stsd", "trak", "tkhd", "mdia", "mdhd", "mdat"),
        file.boxKey().toList()
    )

    @Test
    fun trie() {
        val t = file.boxTrie()
        val trak = listOf("moov", "trak").toSeries()
        assertEquals(2, t.leaf(trak)!!.count)
        assertEquals((trak1.size + trak2.size).toDouble(), t[trak].sum)
        assertEquals(file.size.toDouble(), t[t.a].sum)
        assertEquals(listOf("ftyp" to 24.0, "mdat" to 1008.0, "moov" to moov.size.toDouble()), t.level(1).toList().map { it.a.toList().single() to it.b.sum })
        assertTrue(t.unseen(listOf("moov", "udta").toSeries()))
        assertFalse(t.unseen(listOf("moov", "trak", "mdia").toSeries()))
    }

    @Test
    fun truncated() {
        val t = file.copyOf(file.size - 500).boxTrie()
        assertEquals((trak1.size + trak2.size).toDouble(), t[listOf("moov", "trak").toSeries()].sum)
        assertEquals(508.0, t[listOf("mdat").toSeries()].sum)
        assertEquals(file.boxTrie().size, t.size)
    }
}
