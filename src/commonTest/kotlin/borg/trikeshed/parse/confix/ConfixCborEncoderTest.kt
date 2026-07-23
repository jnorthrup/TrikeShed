package borg.trikeshed.parse.confix

import kotlin.test.Test
import kotlin.test.assertContentEquals

class ConfixCborEncoderTest {
    @Test
    fun testTextStringsAndByteStrings() {
        // Text string: Major Type 3 (0x60 base)
        assertContentEquals(bytes(0x60), emit(ConfixPrimitive("", true)))
        assertContentEquals(bytes(0x61, 0x61), emit(ConfixPrimitive("a", true)))
        assertContentEquals(bytes(0x64, 0x49, 0x45, 0x54, 0x46), emit(ConfixPrimitive("IETF", true)))
        assertContentEquals(bytes(0x62, 0x22, 0x5c), emit(ConfixPrimitive("\"\\", true)))
        assertContentEquals(bytes(0x62, 0xc3, 0xbc), emit(ConfixPrimitive("\u00fc", true)))
        assertContentEquals(bytes(0x63, 0xe6, 0xb0, 0xb4), emit(ConfixPrimitive("\u6c34", true)))
        assertContentEquals(bytes(0x64, 0xf0, 0x90, 0x85, 0x91), emit(ConfixPrimitive("\ud800\udd51", true))) // water

        // Byte string: Major Type 2 (0x40 base), for non-numbers when isString = false
        assertContentEquals(bytes(0x40), emit(ConfixPrimitive("", false)))
        assertContentEquals(bytes(0x41, 0x61), emit(ConfixPrimitive("a", false)))
        assertContentEquals(bytes(0x44, 0x49, 0x45, 0x54, 0x46), emit(ConfixPrimitive("IETF", false)))
        assertContentEquals(bytes(0x42, 0x22, 0x5c), emit(ConfixPrimitive("\"\\", false)))
        assertContentEquals(bytes(0x42, 0xc3, 0xbc), emit(ConfixPrimitive("\u00fc", false)))
        assertContentEquals(bytes(0x43, 0xe6, 0xb0, 0xb4), emit(ConfixPrimitive("\u6c34", false)))
        assertContentEquals(bytes(0x44, 0xf0, 0x90, 0x85, 0x91), emit(ConfixPrimitive("\ud800\udd51", false)))

        // A string that looks like a number/boolean should still be a string (MT 3) if isString = true
        assertContentEquals(bytes(0x61, 0x31), emit(ConfixPrimitive("1", true)))
        assertContentEquals(bytes(0x62, 0x2d, 0x31), emit(ConfixPrimitive("-1", true)))
        assertContentEquals(bytes(0x64, 0x74, 0x72, 0x75, 0x65), emit(ConfixPrimitive("true", true)))
    }

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

    private fun emit(element: ConfixElement): ByteArray = ConfixCborEmitter.emit(element)
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
