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
    fun testTextStrings() {
        assertContentEquals(bytes(0x60), emit(ConfixPrimitive("", true)))
        assertContentEquals(bytes(0x61, 0x61), emit(ConfixPrimitive("a", true)))
        assertContentEquals(bytes(0x64, 0x49, 0x45, 0x54, 0x46), emit(ConfixPrimitive("IETF", true)))
        assertContentEquals(bytes(0x62, 0x22, 0x5c), emit(ConfixPrimitive("\"\\", true)))
        assertContentEquals(bytes(0x62, 0xc3, 0xbc), emit(ConfixPrimitive("\u00fc", true)))
        assertContentEquals(bytes(0x63, 0xe6, 0xb0, 0xb4), emit(ConfixPrimitive("\u6c34", true)))
        assertContentEquals(bytes(0x64, 0xf0, 0x90, 0x85, 0x91), emit(ConfixPrimitive("\ud800\udd51", true)))

        // Ensure string "24" is not encoded as integer 24
        assertContentEquals(bytes(0x62, 0x32, 0x34), emit(ConfixPrimitive("24", true)))
        // Ensure string "true" is not encoded as boolean true
        assertContentEquals(bytes(0x64, 0x74, 0x72, 0x75, 0x65), emit(ConfixPrimitive("true", true)))
    }

    @Test
    fun testByteStrings() {
        assertContentEquals(bytes(0x40), emit(ConfixPrimitive("", false)))
        // 4 bytes: 01 02 03 04
        val bytesContent = ByteArray(4) { (it + 1).toByte() }.decodeToString()
        assertContentEquals(bytes(0x44, 0x01, 0x02, 0x03, 0x04), emit(ConfixPrimitive(bytesContent, false)))
    }

    private fun emit(element: ConfixElement): ByteArray = ConfixCborEmitter.emit(element)
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
