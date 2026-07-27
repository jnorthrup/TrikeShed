package borg.trikeshed.parse.confix

import borg.trikeshed.collections.associative.Cbor
import borg.trikeshed.collections.associative.Item
import borg.trikeshed.collections.associative.itemArrayOf
import borg.trikeshed.collections.associative.itemMapOf
import kotlin.test.Test
import kotlin.test.assertContentEquals

class ConfixCborEncoderTest {
    @Test
    fun testTextStrings() {
        // Text string: Major Type 3 (0x60 base)
        assertContentEquals(bytes(0x60), emit(Item.Str("")))
        assertContentEquals(bytes(0x61, 0x61), emit(Item.Str("a")))
        assertContentEquals(bytes(0x64, 0x49, 0x45, 0x54, 0x46), emit(Item.Str("IETF")))
        assertContentEquals(bytes(0x62, 0x22, 0x5c), emit(Item.Str("\"\\")))
        assertContentEquals(bytes(0x62, 0xc3, 0xbc), emit(Item.Str("\u00fc")))
        assertContentEquals(bytes(0x63, 0xe6, 0xb0, 0xb4), emit(Item.Str("\u6c34")))
        assertContentEquals(bytes(0x64, 0xf0, 0x90, 0x85, 0x91), emit(Item.Str("\ud800\udd51")))
    }

    @Test
    fun testByteStrings() {
        // Byte string: Major Type 2 (0x40 base)
        assertContentEquals(bytes(0x40), emit(Item.Bin(ByteArray(0))))
        assertContentEquals(bytes(0x41, 0x61), emit(Item.Bin(byteArrayOf(0x61))))
        assertContentEquals(bytes(0x44, 0x49, 0x45, 0x54, 0x46), emit(Item.Bin("IETF".encodeToByteArray())))
        assertContentEquals(bytes(0x42, 0x22, 0x5c), emit(Item.Bin(byteArrayOf(0x22, 0x5c))))
    }

    @Test
    fun testUnsignedAndSignedIntegers() {
        assertContentEquals(bytes(0x00), emit(Item.Num(0)))
        assertContentEquals(bytes(0x17), emit(Item.Num(23)))
        assertContentEquals(bytes(0x18, 0x18), emit(Item.Num(24)))
        assertContentEquals(bytes(0x18, 0xff), emit(Item.Num(255)))
        assertContentEquals(bytes(0x19, 0x01, 0x00), emit(Item.Num(256)))
        assertContentEquals(bytes(0x19, 0xff, 0xff), emit(Item.Num(65535)))
        assertContentEquals(bytes(0x1a, 0x00, 0x01, 0x00, 0x00), emit(Item.Num(65536)))
        assertContentEquals(bytes(0x1a, 0xff, 0xff, 0xff, 0xff), emit(Item.Num(4294967295L)))
        assertContentEquals(bytes(0x1b, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00), emit(Item.Num(4294967296L)))
        assertContentEquals(bytes(0x1b, 0x7f, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff), emit(Item.Num(Long.MAX_VALUE)))

        assertContentEquals(bytes(0x20), emit(Item.Num(-1)))
        assertContentEquals(bytes(0x37), emit(Item.Num(-24)))
        assertContentEquals(bytes(0x38, 0x18), emit(Item.Num(-25)))
        assertContentEquals(bytes(0x38, 0xff), emit(Item.Num(-256)))
        assertContentEquals(bytes(0x39, 0xff, 0xff), emit(Item.Num(-65536)))
        assertContentEquals(bytes(0x3a, 0x00, 0x01, 0x00, 0x00), emit(Item.Num(-65537)))
        assertContentEquals(bytes(0x3a, 0xff, 0xff, 0xff, 0xff), emit(Item.Num(-4294967296L)))
        assertContentEquals(bytes(0x3b, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00), emit(Item.Num(-4294967297L)))
        assertContentEquals(bytes(0x3b, 0x7f, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff), emit(Item.Num(Long.MIN_VALUE)))
    }

    @Test
    fun testNull() {
        assertContentEquals(bytes(0xf6), emit(Item.Nil))
    }

    @Test
    fun testBool() {
        assertContentEquals(bytes(0xf5), emit(Item.Bool(true)))
        assertContentEquals(bytes(0xf4), emit(Item.Bool(false)))
    }

    @Test
    fun testFloat() {
        // 1.1 -> 0xFB, 3FF199999999999A
        assertContentEquals(bytes(0xfb, 0x3f, 0xf1, 0x99, 0x99, 0x99, 0x99, 0x99, 0x9a), emit(Item.Flt(1.1)))
        assertContentEquals(bytes(0xfb, 0xbf, 0xf1, 0x99, 0x99, 0x99, 0x99, 0x99, 0x9a), emit(Item.Flt(-1.1)))
    }

    private fun emit(item: Item): ByteArray = Cbor.encode(item)
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
