package borg.trikeshed.parse.confix

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ConfixCborDecoderTest {
    @Test
    fun roundTripAllTypes() {
        val testCases = listOf(
            ConfixNull,
            ConfixPrimitive(true),
            ConfixPrimitive(false),
            ConfixPrimitive(0),
            ConfixPrimitive(23),
            ConfixPrimitive(24),
            ConfixPrimitive(255),
            ConfixPrimitive(256),
            ConfixPrimitive(65535),
            ConfixPrimitive(65536),
            ConfixPrimitive(4294967295L),
            ConfixPrimitive(-1),
            ConfixPrimitive(-24),
            ConfixPrimitive(-25),
            ConfixPrimitive(-256),
            ConfixPrimitive(-65535),
            ConfixPrimitive(-65536),
            ConfixPrimitive(-4294967295L),
            ConfixPrimitive(0.0),
            ConfixPrimitive(3.14159),
            ConfixPrimitive(-0.5),
            ConfixPrimitive("hello"),
            ConfixPrimitive("world"),
            ConfixArray(listOf(ConfixPrimitive(1), ConfixPrimitive("x"))),
            ConfixObject(mapOf("b" to ConfixPrimitive(2), "a" to ConfixPrimitive(1))),
            ConfixObject(mapOf("nested" to ConfixArray(listOf(ConfixPrimitive(true)))))
        )

        for (original in testCases) {
            val encoded = ConfixCborEmitter.emit(original)
            val decoded = ConfixCborDecoder.decode(encoded)
            assertEquals(original, decoded, "Failed on round-trip for: $original")
        }
    }
}
