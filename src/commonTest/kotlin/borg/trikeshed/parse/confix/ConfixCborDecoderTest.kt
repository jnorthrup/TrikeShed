package borg.trikeshed.parse.confix

import borg.trikeshed.collections.associative.Cbor
import borg.trikeshed.collections.associative.Item
import borg.trikeshed.collections.associative.itemArrayOf
import borg.trikeshed.collections.associative.itemMapOf
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfixCborDecoderTest {
    private fun assertRoundTrip(item: Item) {
        val encoded = Cbor.encode(item)
        val decoded = Cbor.decode(encoded)
        assertEquals(item, decoded)
    }

    @Test
    fun roundTripNull() {
        assertRoundTrip(Item.Nil)
    }

    @Test
    fun roundTripBooleans() {
        assertRoundTrip(Item.Bool(true))
        assertRoundTrip(Item.Bool(false))
    }

    @Test
    fun roundTripIntegers() {
        assertRoundTrip(Item.Num(0))
        assertRoundTrip(Item.Num(42))
        assertRoundTrip(Item.Num(255))
        assertRoundTrip(Item.Num(65535))
        assertRoundTrip(Item.Num(4294967295L))
        assertRoundTrip(Item.Num(-1))
        assertRoundTrip(Item.Num(-42))
        assertRoundTrip(Item.Num(-256))
        assertRoundTrip(Item.Num(-65536))
    }

    @Test
    fun roundTripFloats() {
        assertRoundTrip(Item.Flt(3.14159))
        assertRoundTrip(Item.Flt(-0.5))
    }

    @Test
    fun roundTripStrings() {
        assertRoundTrip(Item.Str("hello world"))
        assertRoundTrip(Item.Str(""))
    }

    @Test
    fun roundTripArrays() {
        assertRoundTrip(itemArrayOf())
        assertRoundTrip(
            itemArrayOf(
                Item.Num(1),
                Item.Str("two"),
                Item.Nil
            )
        )
    }

    @Test
    fun roundTripObjects() {
        assertRoundTrip(itemMapOf())
        assertRoundTrip(
            itemMapOf(
                "a" to Item.Num(1),
                "b" to Item.Str("two"),
                "c" to Item.Nil,
                "d" to itemArrayOf(Item.Num(3))
            )
        )
    }
}
