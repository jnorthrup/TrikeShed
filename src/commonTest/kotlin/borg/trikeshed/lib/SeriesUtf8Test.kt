package borg.trikeshed.lib

import kotlin.test.*

class SeriesUtf8Test {
    @Test fun validPairsEncodeAsUtf8AndAsStringPreservesThem() {
        val text = "ascii é 中 \uD83D\uDE00 \uD834\uDD1E"
        val series = text.toSeries()
        assertContentEquals(text.encodeToByteArray(), series.encodeToByteArray())
        assertEquals(text, series.asString())
        assertContentEquals(byteArrayOf(), "".toSeries().encodeToByteArray())
        assertEquals("\uD83D\uDE00", "\uD83D\uDE00x".toSeries().asString(2))
    }

    @Test fun isolatedSurrogateBytesAndDecodeBehaviorRemainUnchanged() {
        val high = byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())
        val low = byteArrayOf(0xED.toByte(), 0xB0.toByte(), 0x80.toByte())
        assertContentEquals(high, "\uD800".toSeries().encodeToByteArray())
        assertContentEquals(low, "\uDC00".toSeries().encodeToByteArray())
        assertEquals(high.decodeToString(), "\uD800".toSeries().asString())
        assertEquals(low.decodeToString(), "\uDC00".toSeries().asString())
        // A caller-provided UTF-16 limit can still end between a pair.
        assertEquals(byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0xBD.toByte()).decodeToString(),
            "\uD83D\uDE00".toSeries().asString(1))
    }

    @Test fun lazySeriesReadsEveryCodeUnitOnceIncludingLookahead() {
        val text = "a\uD83D\uDE00\uD800x\uD801\uD802\uDC00z\uDC00"
        val reads = IntArray(text.length)
        val source: Series<Char> = text.length j { index: Int ->
            reads[index]++
            check(reads[index] == 1) { "Character was read more than once" }
            text[index]
        }
        val high0 = byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())
        val high1 = byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x81.toByte())
        val low0 = byteArrayOf(0xED.toByte(), 0xB0.toByte(), 0x80.toByte())
        val expected = "a\uD83D\uDE00".encodeToByteArray() + high0 + "x".encodeToByteArray() +
            high1 + "\uD802\uDC00z".encodeToByteArray() + low0
        assertContentEquals(expected, source.encodeToByteArray())
        assertContentEquals(IntArray(text.length) { 1 }, reads)
    }
}
