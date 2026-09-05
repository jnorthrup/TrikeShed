package borg.trikeshed.parse.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JsonArrayShapeTest {
    @Test fun emptyAndPopulatedArraysHaveTheSameBoundaryShape() {
        assertIs<List<*>>(JsonSupport.parse("[]"))
        assertIs<List<*>>(JsonSupport.parse("[1]"))
        val value = JsonSupport.parseMap("""{"rows":[],"nested":[[],[1]],"empty":{}}""")
        assertEquals(emptyList<Any?>(), value["rows"])
        assertIs<List<*>>((value["nested"] as List<*>)[0])
        assertIs<Map<*, *>>(value["empty"])
        assertEquals(value, JsonSupport.parse(JsonSupport.stringify(value)))
    }
}
