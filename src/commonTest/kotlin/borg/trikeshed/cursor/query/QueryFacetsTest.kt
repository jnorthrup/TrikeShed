package borg.trikeshed.cursor.query

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.parse.kursive.sql.*
import kotlin.test.*

class QueryFacetsTest {
    fun columns(json: String): Cursor = confixDoc(json).index.facet(ConfixIndexK.Columns)

    @Test
    fun columnsTypeTheUnionOfMembers() {
        val cursor = columns("""[{"n": 1, "s": "a"}, {"n": 2.5, "b": true, "o": {"z": 1}}, {"s": 3}]""")
        assertEquals(3, cursor.size)
        val metas = (0 until cursor[0].size).map { c -> cursor[0].metas[c]().let { it.name.toString() to it.type } }
        assertEquals(
            listOf("n" to IOMemento.IoDouble, "s" to IOMemento.IoString, "b" to IOMemento.IoBoolean, "o" to IOMemento.IoObject),
            metas,
        )
        assertEquals(listOf(1.0, "a", null, null), (0 until 4).map { cursor[0].values[it] })
        assertEquals("3", cursor[2].values[1])
        assertEquals(1L, navigate(cursor[1].values[3], s_[keyOf("z")], text = false).let { (it as Number).toLong() })
    }

    @Test
    fun filterIsLazyUntilRead() {
        var evaluated = 0
        val source = columns("""[{"n": 1}, {"n": 2}, {"n": 3}]""")
        val counting: Cursor = source.size j { r: Int -> evaluated++; source[r] }
        val query = QueryCursor(counting, s_[QueryK.Filter(BinaryExpr(
            ColumnRef(Identifier("n".toSeries())), ">", LitExpr(NumericLiteral(1)),
        ))])
        assertEquals(0, evaluated)
        assertEquals(2, query.size)
        assertTrue(evaluated > 0)
    }

    @Test
    fun keyedJoinMatchesIntegralAndFractionalKeys() {
        val left = columns("""[{"k": 1}, {"k": 2}, {"k": null}]""")
        val right = columns("""[{"k": 1.0, "v": "one"}, {"k": 2, "v": "two"}, {"k": 2, "v": "deux"}, {"k": null, "v": "none"}]""")
        val joined = join(left, right, intArrayOf(0) j intArrayOf(0))
        assertEquals(listOf("one", "two", "deux"), (0 until joined.size).map { joined[it].values[2] })
        val outer = join(left, right, intArrayOf(0) j intArrayOf(0), outer = true)
        assertEquals(4, outer.size)
        assertNull(outer[3].values[2])
    }

    @Test
    fun unnestExpandsArrayCells() {
        val cursor = columns("""[{"id": 1, "tags": ["a", "b"]}, {"id": 2, "tags": []}, {"id": 3, "tags": ["c"]}]""")
        val flat = cursor.facet(QueryK.Unnest("tags"))
        assertEquals(listOf(1L to "a", 1L to "b", 3L to "c"), (0 until flat.size).map { flat[it].values[0] to flat[it].values[1] })
        assertEquals(IOMemento.IoString, flat[0].metas[1]().type)
    }

    @Test
    fun orderPutsNullsLastAscendingAndFirstDescending() {
        val cursor = columns("""[{"n": 2}, {"n": null}, {"n": 1}]""")
        val up = cursor.facet(QueryK.Order(s_["n"], booleanArrayOf(false)))
        assertEquals(listOf(1L, 2L, null), (0 until up.size).map { up[it].values[0] })
        val down = cursor.facet(QueryK.Order(s_["n"], booleanArrayOf(true), limit = 2))
        assertEquals(listOf(null, 2L), (0 until down.size).map { down[it].values[0] })
    }
}
