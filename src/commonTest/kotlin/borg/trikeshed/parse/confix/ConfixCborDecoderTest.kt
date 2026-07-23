package borg.trikeshed.parse.confix

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfixCborDecoderTest {
    private fun assertRoundTrip(element: ConfixElement) {
        val encoded = ConfixCborEmitter.emit(element)
        val decoded = ConfixCborDecoder.decode(encoded)
        assertEquals(element, decoded)
    }

    @Test
    fun roundTripNull() {
        assertRoundTrip(ConfixNull)
    }

    @Test
    fun roundTripBooleans() {
        assertRoundTrip(ConfixPrimitive(true))
        assertRoundTrip(ConfixPrimitive(false))
    }

    @Test
    fun roundTripIntegers() {
        assertRoundTrip(ConfixPrimitive(0))
        assertRoundTrip(ConfixPrimitive(42))
        assertRoundTrip(ConfixPrimitive(255))
        assertRoundTrip(ConfixPrimitive(65535))
        assertRoundTrip(ConfixPrimitive(4294967295L))
        assertRoundTrip(ConfixPrimitive(-1))
        assertRoundTrip(ConfixPrimitive(-42))
        assertRoundTrip(ConfixPrimitive(-256))
        assertRoundTrip(ConfixPrimitive(-65536))
    }

    @Test
    fun roundTripFloats() {
        assertRoundTrip(ConfixPrimitive(3.14159))
        assertRoundTrip(ConfixPrimitive(-0.5))
    }

    @Test
    fun roundTripStrings() {
        assertRoundTrip(ConfixPrimitive("hello world"))
        assertRoundTrip(ConfixPrimitive(""))
    }

    @Test
    fun roundTripArrays() {
        assertRoundTrip(ConfixArray(emptyList()))
        assertRoundTrip(
            ConfixArray(
                listOf(
                    ConfixPrimitive(1),
                    ConfixPrimitive("two"),
                    ConfixNull
                )
            )
        )
    }

    @Test
    fun roundTripObjects() {
        assertRoundTrip(ConfixObject(emptyMap()))
        assertRoundTrip(
            ConfixObject(
                mapOf(
                    "a" to ConfixPrimitive(1),
                    "b" to ConfixPrimitive("two"),
                    "c" to ConfixNull,
                    "d" to ConfixArray(listOf(ConfixPrimitive(3)))
                )
            )
        )
    }
}
