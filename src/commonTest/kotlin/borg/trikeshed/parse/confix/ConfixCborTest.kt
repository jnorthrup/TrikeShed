package borg.trikeshed.parse.confix

import borg.trikeshed.collections.associative.Cbor
import borg.trikeshed.collections.associative.Item
import borg.trikeshed.collections.associative.itemArrayOf
import borg.trikeshed.collections.associative.itemMapOf
import kotlin.test.Test
import kotlin.test.assertContentEquals

class ConfixCborTest {
    @Test
    fun integersUseMinimalCanonicalWidths() {
        assertContentEquals(bytes(0x00), emit(Item.Num(0)))
        assertContentEquals(bytes(0x17), emit(Item.Num(23)))
        assertContentEquals(bytes(0x18, 0x18), emit(Item.Num(24)))
        assertContentEquals(bytes(0x18, 0xff), emit(Item.Num(255)))
        assertContentEquals(bytes(0x19, 0x01, 0x00), emit(Item.Num(256)))

        assertContentEquals(bytes(0x20), emit(Item.Num(-1)))
        assertContentEquals(bytes(0x37), emit(Item.Num(-24)))
        assertContentEquals(bytes(0x38, 0x18), emit(Item.Num(-25)))
        assertContentEquals(bytes(0x38, 0xff), emit(Item.Num(-256)))
    }

    @Test
    fun stringsBooleansNullAndArraysUseCborMajorTypes() {
        assertContentEquals(bytes(0x65, 0x68, 0x65, 0x6c, 0x6c, 0x6f), emit(Item.Str("hello")))
        assertContentEquals(bytes(0xf5), emit(Item.Bool(true)))
        assertContentEquals(bytes(0xf4), emit(Item.Bool(false)))
        assertContentEquals(bytes(0xf6), emit(Item.Nil))
        assertContentEquals(
            bytes(0x82, 0x01, 0x61, 0x78),
            emit(itemArrayOf(Item.Num(1), Item.Str("x"))),
        )
    }

    @Test
    fun objectKeysAreOrderedByCanonicalEncodedBytes() {
        // CanonicalCbor sorts map keys before encoding, so both produce the same bytes.
        val first = itemMapOf("b" to Item.Num(2), "a" to Item.Num(1))
        val second = itemMapOf("a" to Item.Num(1), "b" to Item.Num(2))
        val expected = bytes(0xa2, 0x61, 0x61, 0x01, 0x61, 0x62, 0x02)

        assertContentEquals(expected, Cbor.encode(first))
        assertContentEquals(expected, Cbor.encode(second))
    }

    private fun emit(item: Item): ByteArray = Cbor.encode(item)

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
