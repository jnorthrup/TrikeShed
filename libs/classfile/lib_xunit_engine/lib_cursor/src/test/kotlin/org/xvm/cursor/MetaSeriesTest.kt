package org.xvm.cursor

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * MetaSeries<Input, Output> — lazy codec/filter/projection from source domain to Cursor.
 *
 * TODO algebra:
 *   MetaSeries<Input, Output>
 *     filter: (Input) -> Boolean
 *     codec: (Input) -> Output
 *     refs: Series<ColumnMetaRef>
 *     cursor: (InputDomain) -> Cursor
 */
class MetaSeriesTest {

    // ── Generic filter+codec+refs ──────────────────────────────────────────

    @Test
    fun `MetaSeries filters input and codecs matching items to RowVec`() {
        val refs = classfileRefs()
        val ms = MetaSeries<String, RowVec>(
            filter = { it.startsWith("org.xvm") },
            codec = { className -> rowVecFromName(className) },
            refs = refs,
        )

        val source: Series<String> = seriesOf(
            "org.xvm.runtime.Test",
            "java.lang.String",
            "org.xvm.api.Foo",
        )
        val cursor: Cursor = ms.cursor(source)

        assertEquals(2, cursor.a)
        // First match
        assertNotNull(cursor.b(0).b(0).a)
    }

    @Test
    fun `MetaSeries exposes refs as Series of ColumnMetaRef`() {
        val refs = classfileRefs()
        val ms = MetaSeries<String, RowVec>(
            filter = { true },
            codec = { rowVecFromName(it) },
            refs = refs,
        )

        val exposed = ms.refs
        assertEquals(10, exposed.a)
        assertEquals(PointcutFacet.SymbolName, exposed.b(0).facet)
        assertEquals(PointcutFacet.ChildRows, exposed.b(4).facet)
    }

    // ── Classfile domain ──────────────────────────────────────────────────

    @Test
    fun `ClassfileMetaSeries filters by package and reifies taxonomy cursor`() {
        val ms = ClassfileMetaSeries(packagePrefix = "org.xvm")
        val source: Series<String> = seriesOf(
            "org.xvm.runtime.Test",
            "org.xtc.compiler.Main",
            "java.lang.Object",
        )
        val cursor = ms.cursor(source)

        assertEquals(1, cursor.a)
        // Row cells carry faceted meta
        val row = cursor.b(0)
        assertEquals(PointcutFacet.SymbolName, (row.b(0).b() as ColumnMetaRef).facet)
    }

    // ── Event domain ──────────────────────────────────────────────────────

    @Test
    fun `EventMetaSeries filters by opcode range and decodes wireproto`() {
        val ms = EventMetaSeries(opcodeRange = 0xA5..0xA8)
        val events: Series<MemSegment> = seriesOf(
            eventWire(0xA5, 1000L, 1, 42),
            eventWire(0x66, 1001L, 2, 43),   // outside range
            eventWire(0xA6, 1002L, 3, 44),
        )
        val cursor = ms.cursor(events)

        assertEquals(2, cursor.a)
    }

    // ── Confix JSON domain ────────────────────────────────────────────────

    @Test
    fun `ConfixMetaSeries filters by path glob and decodes JSON cells`() {
        val ms = ConfixMetaSeries(pathGlob = "/classfiles/**")
        val cells: Series<JsonCell> = seriesOf(
            JsonCell("/classfiles/Test.class", """{"name":"Test"}"""),
            JsonCell("/edges/invokes", """{"from":"Test"}"""),
            JsonCell("/classfiles/Foo.class", """{"name":"Foo"}"""),
        )
        val cursor = ms.cursor(cells)

        assertEquals(2, cursor.a)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun classfileRefs(): Series<ColumnMetaRef> {
        val refs = listOf(
            ColumnMetaRef(0, "symbolName", "SymbolId", PointcutFacet.SymbolName),
            ColumnMetaRef(1, "typeInfo", "SymbolId", PointcutFacet.TypeInfo),
            ColumnMetaRef(2, "classfileCoordinate", "Int", PointcutFacet.ClassfileCoordinate),
            ColumnMetaRef(3, "xvmCoordinate", "Int", PointcutFacet.XvmCoordinate),
            ColumnMetaRef(4, "constants", "Lazy<Cursor>", PointcutFacet.ChildRows),
            ColumnMetaRef(5, "methods", "Lazy<Cursor>", PointcutFacet.ChildRows),
            ColumnMetaRef(6, "fields", "Lazy<Cursor>", PointcutFacet.ChildRows),
            ColumnMetaRef(7, "edges", "Lazy<Cursor>", PointcutFacet.ChildRows),
            ColumnMetaRef(8, "events", "Lazy<Cursor>", PointcutFacet.ChildRows),
            ColumnMetaRef(9, "wireproto", "MemSegment", PointcutFacet.Wireproto),
        )
        return object : Series<ColumnMetaRef> {
            override val a: Int get() = refs.size
            override val b: (Int) -> ColumnMetaRef get() = { refs[it] }
        }
    }

    private fun rowVecFromName(name: String): RowVec {
        val id = Symbols.symbol(name)
        val ref = ColumnMetaRef(0, "symbolName", "SymbolId", PointcutFacet.SymbolName)
        return 1 j { _: Int -> cell(id, ref) }
    }

    private fun eventWire(opcode: Int, nano: Long, poolId: Int, siteOrd: Int): MemSegment {
        val buf = java.nio.ByteBuffer.allocate(24)
        buf.put(opcode.toByte())
        buf.putLong(nano)
        buf.putInt(poolId)
        buf.putInt(siteOrd)
        return MemSegment(buf.array())
    }

    private fun <T> seriesOf(vararg items: T): Series<T> = object : Series<T> {
        override val a: Int get() = items.size
        override val b: (Int) -> T get() = { items[it] }
    }
}
