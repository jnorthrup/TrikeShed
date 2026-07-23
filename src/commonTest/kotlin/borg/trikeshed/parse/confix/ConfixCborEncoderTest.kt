package borg.trikeshed.parse.confix

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.fail

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
    fun testBoolNullFloat() {
        assertContentEquals(bytes(0xf4), emit(ConfixPrimitive("false", false)))
        assertContentEquals(bytes(0xf5), emit(ConfixPrimitive("true", false)))
        assertContentEquals(bytes(0xf6), emit(ConfixNull))

        // Float64
        // 1.5 in IEEE 754 float64 is 0x3ff8000000000000
        assertContentEquals(bytes(0xfb, 0x3f, 0xf8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00), emit(ConfixPrimitive("1.5", false)))
        // -1.5 is 0xbff8000000000000
        assertContentEquals(bytes(0xfb, 0xbf, 0xf8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00), emit(ConfixPrimitive("-1.5", false)))
        // 0.0 is 0x0000000000000000
        assertContentEquals(bytes(0xfb, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00), emit(ConfixPrimitive("0.0", false)))
    }

    private fun emit(element: ConfixElement): ByteArray = ConfixCborEmitter.emit(element)
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
