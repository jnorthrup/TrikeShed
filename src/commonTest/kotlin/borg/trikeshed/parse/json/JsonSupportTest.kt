package borg.trikeshed.parse.json

import borg.trikeshed.common.TypeEvidence
import borg.trikeshed.cursor.name
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonSupportTest {
    @Test
    fun `core parser preserves numeric widths`() {
        assertEquals(7, JsonParser.reify("7".toSeries()))
        assertEquals(5532807773L, JsonParser.reify("5532807773".toSeries()))
        assertEquals(157.0, JsonParser.reify("157.0".toSeries()))
        assertEquals(-0.0, JsonParser.reify("-0.0".toSeries()))
    }

    @Test
    fun `core parser handles path queries`() {
        val json = """{"id64":5532807773,"coords":{"x":157.0,"y":-27,"z":-70},"bodies":[{"name":"Jackson's Lighthouse"},{"name":"Jackson's Lighthouse 1"}]}"""
        val src = json.toSeries()
        fun query(path: List<Any?>): Any? = JsonParser.jsPath(JsonParser.index(src) j src, path.toJsPath)

        assertEquals(5532807773L, query(listOf("id64")))
        assertEquals(157.0, query(listOf("coords", "x")))
        assertEquals("Jackson's Lighthouse", query(listOf("bodies", 0, "name")))
        assertEquals("Jackson's Lighthouse 1", query(listOf("bodies", 1, "name")))

        val root = JsonParser.reify(src)
        assertTrue(root is Map<*, *>)
    }

    @Test
    fun `core parser can collect one shot node evidence`() {
        val nodeEvidence = mutableListOf<TypeEvidence>()
        val rowVecs = mutableListOf<borg.trikeshed.cursor.RowVec>()

        val parsed = JsonParser.reify(
            """{"id64":5532807773,"coords":{"x":157.0},"name":"A"}""".toSeries(),
            nodeEvidence,
            rowVecs::add,
        )

        assertTrue(parsed is Map<*, *>)
        assertEquals(5, nodeEvidence.size)
        assertEquals(nodeEvidence.size, rowVecs.size)
        assertTrue(nodeEvidence.first().dquotes > 0U)
        assertTrue(nodeEvidence.any { it.digits > 0U })
        assertEquals("confix", rowVecs.first()[0].b().name)
        assertEquals("{}", rowVecs.first()[0].a)
        assertEquals("deducedType", rowVecs.first()[rowVecs.first().size - 1].b().name)
    }
}
