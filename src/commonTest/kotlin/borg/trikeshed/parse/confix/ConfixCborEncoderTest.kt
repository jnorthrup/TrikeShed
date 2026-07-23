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
    fun testNull() {
        assertContentEquals(bytes(0xf6), emit(ConfixNull))
    }

    @Test
    fun testBool() {
        assertContentEquals(bytes(0xf5), emit(ConfixPrimitive(true)))
        assertContentEquals(bytes(0xf4), emit(ConfixPrimitive(false)))

        // "true" as a string, not boolean
        assertContentEquals(bytes(0x64, 0x74, 0x72, 0x75, 0x65), emit(ConfixPrimitive("true", true)))
        // "false" as a string
        assertContentEquals(bytes(0x65, 0x66, 0x61, 0x6c, 0x73, 0x65), emit(ConfixPrimitive("false", true)))
    }

    @Test
    fun testFloat() {
        // 1.1 -> 0xFB, 3FF199999999999A
        assertContentEquals(bytes(0xfb, 0x3f, 0xf1, 0x99, 0x99, 0x99, 0x99, 0x99, 0x9a), emit(ConfixPrimitive(1.1)))
        assertContentEquals(bytes(0xfb, 0xbf, 0xf1, 0x99, 0x99, 0x99, 0x99, 0x99, 0x9a), emit(ConfixPrimitive(-1.1)))

        // "1.1" as a string
        assertContentEquals(bytes(0x63, 0x31, 0x2e, 0x31), emit(ConfixPrimitive("1.1", true)))
    }

    @Test
    fun testByteStringFallback() {
        // "abc" with isString == false should fall back to Major Type 2 (byte string)
        // 0x43 (Major type 2, length 3) followed by "abc" (0x61, 0x62, 0x63)
        assertContentEquals(bytes(0x43, 0x61, 0x62, 0x63), emit(ConfixPrimitive("abc", false)))
    }

    private fun emit(element: ConfixElement): ByteArray = ConfixCborEmitter.emit(element)
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
