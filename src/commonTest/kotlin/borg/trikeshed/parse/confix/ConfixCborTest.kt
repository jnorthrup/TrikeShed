package borg.trikeshed.parse.confix

import kotlin.test.Test
import kotlin.test.assertContentEquals

class ConfixCborTest {
    @Test
    fun integersUseMinimalCanonicalWidths() {
        assertContentEquals(bytes(0x00), emit(ConfixPrimitive(0)))
        assertContentEquals(bytes(0x17), emit(ConfixPrimitive(23)))
        assertContentEquals(bytes(0x18, 0x18), emit(ConfixPrimitive(24)))
        assertContentEquals(bytes(0x18, 0xff), emit(ConfixPrimitive(255)))
        assertContentEquals(bytes(0x19, 0x01, 0x00), emit(ConfixPrimitive(256)))

        assertContentEquals(bytes(0x20), emit(ConfixPrimitive(-1)))
        assertContentEquals(bytes(0x37), emit(ConfixPrimitive(-24)))
        assertContentEquals(bytes(0x38, 0x18), emit(ConfixPrimitive(-25)))
        assertContentEquals(bytes(0x38, 0xff), emit(ConfixPrimitive(-256)))
    }

    @Test
    fun stringsBooleansNullAndArraysUseCborMajorTypes() {
        assertContentEquals(bytes(0x65, 0x68, 0x65, 0x6c, 0x6c, 0x6f), emit(ConfixPrimitive("hello")))
        assertContentEquals(bytes(0xf5), emit(ConfixPrimitive(true)))
        assertContentEquals(bytes(0xf4), emit(ConfixPrimitive(false)))
        assertContentEquals(bytes(0xf6), emit(ConfixNull))
        assertContentEquals(
            bytes(0x82, 0x01, 0x61, 0x78),
            emit(ConfixArray(listOf(ConfixPrimitive(1), ConfixPrimitive("x")))),
        )
    }

    @Test
    fun objectKeysAreOrderedByCanonicalEncodedBytes() {
        val first = ConfixObject(mapOf("b" to ConfixPrimitive(2), "a" to ConfixPrimitive(1)))
        val second = ConfixObject(mapOf("a" to ConfixPrimitive(1), "b" to ConfixPrimitive(2)))
        val expected = bytes(0xa2, 0x61, 0x61, 0x01, 0x61, 0x62, 0x02)

        assertContentEquals(expected, emit(first))
        assertContentEquals(expected, emit(second))
    }

    private fun emit(element: ConfixElement): ByteArray = ConfixCborEmitter.emit(element)

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
