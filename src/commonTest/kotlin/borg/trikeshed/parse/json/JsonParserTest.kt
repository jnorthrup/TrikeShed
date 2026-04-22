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

    @Test
    fun testSimpleObject() {
        val r = JsonParser.reify("""{"a":1}""".toSeries())
        assertEquals(mapOf("a" to 1), r)
    }

    @Test
    fun testNestedObject() {
        val r = JsonParser.reify("""{"t":1,"p":{"BTC":100.0}}""".toSeries())
        assertNotNull(r)
        val m = r as Map<*, *>
        assertEquals(1, m["t"])
        val p = m["p"] as Map<*, *>
        assertEquals(100.0, p["BTC"])
    }

    @Test
    fun testTickWireFormat() {
        val r = JsonParser.reify("""{"t":1700000000000,"p":{"BTC":65000.5}}""".toSeries())
        assertNotNull(r)
        val m = r as Map<*, *>
        assertEquals(1700000000000L, m["t"])
        val p = m["p"] as Map<*, *>
        assertEquals(65000.5, p["BTC"])
    }

}
