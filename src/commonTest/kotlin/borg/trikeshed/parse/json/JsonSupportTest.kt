package borg.trikeshed.parse.json

import borg.trikeshed.common.TypeEvidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonSupportTest {
    @Test
    fun `support facade preserves numeric widths`() {
        assertEquals(7, JsonSupport.parse("7"))
        assertEquals(5532807773L, JsonSupport.parse("5532807773"))
        assertEquals(157.0, JsonSupport.parse("157.0"))
        assertEquals(-0.0, JsonSupport.parse("-0.0"))
    }

    @Test
    fun `support facade handles path queries`() {
        val json = """{"id64":5532807773,"coords":{"x":157.0,"y":-27,"z":-70},"bodies":[{"name":"Jackson's Lighthouse"},{"name":"Jackson's Lighthouse 1"}]}"""

        assertEquals(5532807773L, JsonSupport.query(json, "id64"))
        assertEquals(157.0, JsonSupport.query(json, "coords.x"))
        assertEquals(157.0, JsonSupport.query(json, "$.coords.x"))
        assertEquals("Jackson's Lighthouse", JsonSupport.query(json, "bodies/0/name"))
        assertEquals("Jackson's Lighthouse 1", JsonSupport.query(json, "bodies[1].name"))
        assertEquals("Jackson's Lighthouse", JsonSupport.query(json, JsonSupport.pathOf("bodies", 0, "name")))

        val root = JsonSupport.query(json, "")
        assertTrue(root is Map<*, *>)
    }

    @Test
    fun `support facade can collect one shot node evidence`() {
        val nodeEvidence = mutableListOf<TypeEvidence>()

        val parsed = JsonSupport.parse("""{"id64":5532807773,"coords":{"x":157.0},"name":"A"}""", nodeEvidence)

        assertTrue(parsed is Map<*, *>)
        assertEquals(5, nodeEvidence.size)
        assertTrue(nodeEvidence.first().dquotes > 0U)
        assertTrue(nodeEvidence.any { it.digits > 0U })
    }
}
