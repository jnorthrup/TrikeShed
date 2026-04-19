package borg.trikeshed.parse.json

import borg.trikeshed.lib.*
import kotlin.test.*

class JsonParserTest {

    @Test
    fun testNumericTypePreservation() {
        assertEquals(7, JsonParser.reify("7".toSeries()))
        assertEquals(5532807773L, JsonParser.reify("5532807773".toSeries()))
        assertEquals(157.0, JsonParser.reify("157.0".toSeries()))
        assertEquals(-0.0, JsonParser.reify("-0.0".toSeries()))
    }

}
