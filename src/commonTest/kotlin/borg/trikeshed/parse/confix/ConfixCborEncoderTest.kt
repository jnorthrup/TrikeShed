package borg.trikeshed.parse.confix

import kotlin.test.Test
import kotlin.test.assertContentEquals

class ConfixCborEncoderTest {
    @Test
    fun testUnsignedAndSignedIntegers() {
        assertContentEquals(bytes(0x00), emit(ConfixPrimitive(0.toString(), false)))
        assertContentEquals(bytes(0x17), emit(ConfixPrimitive(23.toString(), false)))
        assertContentEquals(bytes(0x18, 0x18), emit(ConfixPrimitive(24.toString(), false)))
        assertContentEquals(bytes(0x18, 0xff), emit(ConfixPrimitive(255.toString(), false)))
        assertContentEquals(bytes(0x19, 0x01, 0x00), emit(ConfixPrimitive(256.toString(), false)))
        assertContentEquals(bytes(0x19, 0xff, 0xff), emit(ConfixPrimitive(65535.toString(), false)))
        assertContentEquals(bytes(0x1a, 0x00, 0x01, 0x00, 0x00), emit(ConfixPrimitive(65536.toString(), false)))
        assertContentEquals(bytes(0x1a, 0xff, 0xff, 0xff, 0xff), emit(ConfixPrimitive(4294967295L.toString(), false)))
        assertContentEquals(bytes(0x1b, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00), emit(ConfixPrimitive(4294967296L.toString(), false)))
        assertContentEquals(bytes(0x1b, 0x7f, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff), emit(ConfixPrimitive(Long.MAX_VALUE.toString(), false)))

        assertContentEquals(bytes(0x1b, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff), emit(ConfixPrimitive(ULong.MAX_VALUE.toString(), false)))

        assertContentEquals(bytes(0x20), emit(ConfixPrimitive((-1).toString(), false)))
        assertContentEquals(bytes(0x37), emit(ConfixPrimitive((-24).toString(), false)))
        assertContentEquals(bytes(0x38, 0x18), emit(ConfixPrimitive((-25).toString(), false)))
        assertContentEquals(bytes(0x38, 0xff), emit(ConfixPrimitive((-256).toString(), false)))
        assertContentEquals(bytes(0x39, 0xff, 0xff), emit(ConfixPrimitive((-65536).toString(), false)))
        assertContentEquals(bytes(0x3a, 0x00, 0x01, 0x00, 0x00), emit(ConfixPrimitive((-65537).toString(), false)))
        assertContentEquals(bytes(0x3a, 0xff, 0xff, 0xff, 0xff), emit(ConfixPrimitive((-4294967296L).toString(), false)))
        assertContentEquals(bytes(0x3b, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00), emit(ConfixPrimitive((-4294967297L).toString(), false)))
        assertContentEquals(bytes(0x3b, 0x7f, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff), emit(ConfixPrimitive(Long.MIN_VALUE.toString(), false)))
    }

    @Test
    fun testDefiniteLengthArrays() {
        // []
        assertContentEquals(bytes(0x80), emit(ConfixArray(emptyList())))

        // [1, 2, 3]
        assertContentEquals(bytes(0x83, 0x01, 0x02, 0x03), emit(ConfixArray(listOf(
            ConfixPrimitive(1.toString(), false),
            ConfixPrimitive(2.toString(), false),
            ConfixPrimitive(3.toString(), false)
        ))))

        // Array of 24 elements (major type 4, length 24 => 0x98 0x18)
        val arr24 = ConfixArray(List(24) { ConfixPrimitive(0.toString(), false) })
        val expected24 = ByteArray(26)
        expected24[0] = 0x98.toByte()
        expected24[1] = 0x18.toByte()
        assertContentEquals(expected24, emit(arr24))
    }

    @Test
    fun testDefiniteLengthMaps() {
        // {}
        assertContentEquals(bytes(0xa0), emit(ConfixObject(emptyMap())))

        // {"a": 1, "b": 2}
        assertContentEquals(
            bytes(0xa2, 0x61, 0x61, 0x01, 0x61, 0x62, 0x02),
            emit(ConfixObject(mapOf(
                "a" to ConfixPrimitive(1.toString(), false),
                "b" to ConfixPrimitive(2.toString(), false)
            )))
        )

        // Map of 24 pairs (major type 5, length 24 => 0xb8 0x18)
        val map24 = ConfixObject((0 until 24).associate {
            ('a' + it).toString() to ConfixPrimitive(0.toString(), false)
        })
        val emitted = emit(map24)
        kotlin.test.assertEquals(0xb8.toByte(), emitted[0])
        kotlin.test.assertEquals(0x18.toByte(), emitted[1])
    }

    private fun emit(element: ConfixElement): ByteArray = ConfixCborEmitter.emit(element)
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
