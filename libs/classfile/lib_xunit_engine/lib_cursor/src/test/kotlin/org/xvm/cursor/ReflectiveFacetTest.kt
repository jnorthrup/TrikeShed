package org.xvm.cursor

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Join
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A bean's property types and names derive facets automatically.
 * No manual ColumnMetaRef wiring.
 *
 * Heuristics:
 *   String + *Name       -> SymbolName
 *   String + *Info       -> TypeInfo
 *   Int + *Coordinate    -> ClassfileCoordinate
 *   Int + xvm*           -> XvmCoordinate
 *   Lazy<*>              -> ChildRows
 *   Cursor               -> ChildRows
 *   MemSegment           -> Wireproto
 *   Long + nano*         -> VmStats
 *   Int + opcode*        -> PointcutKind
 *   Int + *Id            -> StringPool
 */
class ReflectiveFacetTest {

    // ── Bean is the schema ────────────────────────────────────────────────

    data class ClassfileNode(
        val symbolName: String,
        val typeInfo: String,
        val classfileCoordinate: Int,
        val xvmCoordinate: Int,
        val constants: Lazy<Cursor>,
        val methods: Lazy<Cursor>,
        val fields: Lazy<Cursor>,
        val edges: Lazy<Cursor>,
        val events: Lazy<Cursor>,
        val wireproto: MemSegment,
    )

    data class EventRow(
        val nanoTime: Long,
        val opcode: Int,
        val xvmCoordinate: Int,
        val symbolId: Int,
        val wireproto: MemSegment,
    )

    // ── Facet derivation from property type + name ─────────────────────────

    @Test
    fun `String property named symbolName derives SymbolName facet`() {
        val facet = ReflectiveFacet.deriveFacet("symbolName", String::class)
        assertEquals(PointcutFacet.SymbolName, facet)
    }

    @Test
    fun `String property named typeInfo derives TypeInfo facet`() {
        val facet = ReflectiveFacet.deriveFacet("typeInfo", String::class)
        assertEquals(PointcutFacet.TypeInfo, facet)
    }

    @Test
    fun `Int property named classfileCoordinate derives ClassfileCoordinate facet`() {
        val facet = ReflectiveFacet.deriveFacet("classfileCoordinate", Int::class)
        assertEquals(PointcutFacet.ClassfileCoordinate, facet)
    }

    @Test
    fun `Int property named xvmCoordinate derives XvmCoordinate facet`() {
        val facet = ReflectiveFacet.deriveFacet("xvmCoordinate", Int::class)
        assertEquals(PointcutFacet.XvmCoordinate, facet)
    }

    @Test
    fun `Int property named symbolId derives StringPool facet`() {
        val facet = ReflectiveFacet.deriveFacet("symbolId", Int::class)
        assertEquals(PointcutFacet.StringPool, facet)
    }

    @Test
    fun `Int property named opcode derives PointcutKind facet`() {
        val facet = ReflectiveFacet.deriveFacet("opcode", Int::class)
        assertEquals(PointcutFacet.PointcutKind, facet)
    }

    @Test
    fun `Long property named nanoTime derives VmStats facet`() {
        val facet = ReflectiveFacet.deriveFacet("nanoTime", Long::class)
        assertEquals(PointcutFacet.VmStats, facet)
    }

    @Test
    fun `MemSegment property derives Wireproto facet regardless of name`() {
        val facet = ReflectiveFacet.deriveFacet("payload", MemSegment::class)
        assertEquals(PointcutFacet.Wireproto, facet)
    }

    @Test
    fun `unrecognized property type derives Unfaceted`() {
        val facet = ReflectiveFacet.deriveFacet("randomProp", Double::class)
        assertEquals(PointcutFacet.Unfaceted, facet)
    }

    // ── Full bean -> ColumnMetaRef series ──────────────────────────────────

    @Test
    fun `ClassfileNode bean derives 10 faceted column refs`() {
        val refs: Series<ColumnMetaRef> = ReflectiveFacet.deriveRefs<ClassfileNode>()

        assertEquals(10, refs.a)

        assertEquals(PointcutFacet.SymbolName, refs.b(0).facet)
        assertEquals("symbolName", refs.b(0).name)

        assertEquals(PointcutFacet.TypeInfo, refs.b(1).facet)
        assertEquals(PointcutFacet.ClassfileCoordinate, refs.b(2).facet)
        assertEquals(PointcutFacet.XvmCoordinate, refs.b(3).facet)

        // 5 child branches: constants, methods, fields, edges, events
        assertEquals(PointcutFacet.ChildRows, refs.b(4).facet)
        assertEquals(PointcutFacet.ChildRows, refs.b(5).facet)
        assertEquals(PointcutFacet.ChildRows, refs.b(6).facet)
        assertEquals(PointcutFacet.ChildRows, refs.b(7).facet)
        assertEquals(PointcutFacet.ChildRows, refs.b(8).facet)
        assertEquals("constants", refs.b(4).name)
        assertEquals("methods", refs.b(5).name)
        assertEquals("fields", refs.b(6).name)
        assertEquals("edges", refs.b(7).name)
        assertEquals("events", refs.b(8).name)

        assertEquals(PointcutFacet.Wireproto, refs.b(9).facet)
    }

    @Test
    fun `EventRow bean derives 5 faceted column refs`() {
        val refs = ReflectiveFacet.deriveRefs<EventRow>()

        assertEquals(5, refs.a)
        assertEquals(PointcutFacet.VmStats, refs.b(0).facet)
        assertEquals(PointcutFacet.PointcutKind, refs.b(1).facet)
        assertEquals(PointcutFacet.XvmCoordinate, refs.b(2).facet)
        assertEquals(PointcutFacet.StringPool, refs.b(3).facet)
        assertEquals(PointcutFacet.Wireproto, refs.b(4).facet)
    }

    // ── Bean instance -> RowVec ────────────────────────────────────────────

    @Test
    fun `bean instance to RowVec carries faceted meta on each cell`() {
        val node = ClassfileNode(
            symbolName = "TestClass",
            typeInfo = "Module",
            classfileCoordinate = 0,
            xvmCoordinate = 1,
            constants = lazy { emptyCursor() },
            methods = lazy { emptyCursor() },
            fields = lazy { emptyCursor() },
            edges = lazy { emptyCursor() },
            events = lazy { emptyCursor() },
            wireproto = MemSegment(byteArrayOf()),
        )

        val row: RowVec = ReflectiveFacet.toRowVec(node)

        // 10 columns
        assertEquals(10, row.a)

        // String properties are interned to SymbolId
        val nameCell = row.b(0)
        assertEquals(PointcutFacet.SymbolName, facetOf(nameCell))
        assertNotNull(nameCell.a)

        // Int properties stay as Int
        assertEquals(PointcutFacet.ClassfileCoordinate, facetOf(row.b(2)))
        assertEquals(PointcutFacet.XvmCoordinate, facetOf(row.b(3)))

        // Lazy<Cursor> properties stay lazy
        assertEquals(PointcutFacet.ChildRows, facetOf(row.b(4)))
        assertTrue(row.b(4).a is Lazy<*>)

        // MemSegment properties stay as MemSegment
        assertEquals(PointcutFacet.Wireproto, facetOf(row.b(9)))
        assertTrue(row.b(9).a is MemSegment)
    }

    @Test
    fun `Lazy Cursor cells in bean RowVec are not forced until accessed`() {
        var constantsForced = false
        var methodsForced = false

        val node = ClassfileNode(
            symbolName = "Test",
            typeInfo = "Module",
            classfileCoordinate = 0,
            xvmCoordinate = 0,
            constants = lazy { constantsForced = true; emptyCursor() },
            methods = lazy { methodsForced = true; emptyCursor() },
            fields = lazy { emptyCursor() },
            edges = lazy { emptyCursor() },
            events = lazy { emptyCursor() },
            wireproto = MemSegment(byteArrayOf()),
        )

        val row = ReflectiveFacet.toRowVec(node)

        // toRowVec must not force lazy properties
        assertFalse(constantsForced)
        assertFalse(methodsForced)

        // Forcing one cell should not force the other
        @Suppress("UNCHECKED_CAST")
        (row.b(4).a as Lazy<Cursor>).value
        assertTrue(constantsForced)
        assertFalse(methodsForced)
    }

    // ── Series of beans -> Cursor ──────────────────────────────────────────

    @Test
    fun `Series of beans to Cursor preserves facet layout across rows`() {
        val beans = seriesOf(
            EventRow(1000L, 0xA5, 1, 42, MemSegment(byteArrayOf(0x01))),
            EventRow(1001L, 0xA6, 2, 43, MemSegment(byteArrayOf(0x02))),
            EventRow(1002L, 0xA5, 3, 44, MemSegment(byteArrayOf(0x03))),
        )

        val cursor: Cursor = ReflectiveFacet.toCursor(beans)

        assertEquals(3, cursor.a)

        // All rows share the same facet layout
        assertEquals(PointcutFacet.VmStats, facetOf(cursor.b(0).b(0)))
        assertEquals(PointcutFacet.PointcutKind, facetOf(cursor.b(0).b(1)))
        assertEquals(PointcutFacet.Wireproto, facetOf(cursor.b(0).b(4)))
        assertEquals(PointcutFacet.Wireproto, facetOf(cursor.b(2).b(4)))
    }

    // ── FacetReifier dispatch ──────────────────────────────────────────────

    @Test
    fun `FacetReifier resolves SymbolName cell through StringPool`() {
        val id = Symbols.symbol("TestClass")
        val resolved = FacetReifier.reify(PointcutFacet.SymbolName, id)
        assertEquals("TestClass", resolved)
    }

    @Test
    fun `FacetReifier opens ChildRows cell as lazy cursor value`() {
        val inner = emptyCursor()
        val lazyCursor: Lazy<Cursor> = lazy { inner }
        val resolved = FacetReifier.reify(PointcutFacet.ChildRows, lazyCursor)
        assertNotNull(resolved)
        assertTrue(resolved is Join<*, *>)
    }

    @Test
    fun `FacetReifier decodes Wireproto cell through WireCodec`() {
        val segment = MemSegment(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        val resolved = FacetReifier.reify(PointcutFacet.Wireproto, segment)
        assertNotNull(resolved)
    }

    @Test
    fun `FacetReifier returns value as-is for Unfaceted cell`() {
        assertEquals(42, FacetReifier.reify(PointcutFacet.Unfaceted, 42))
    }

    @Test
    fun `ConfixFacade iterates row and reifies cells by facet`() {
        val node = ClassfileNode(
            symbolName = "Test",
            typeInfo = "Module",
            classfileCoordinate = 0,
            xvmCoordinate = 0,
            constants = lazy { emptyCursor() },
            methods = lazy { emptyCursor() },
            fields = lazy { emptyCursor() },
            edges = lazy { emptyCursor() },
            events = lazy { emptyCursor() },
            wireproto = MemSegment(byteArrayOf()),
        )

        val row = ReflectiveFacet.toRowVec(node)
        val reified = ConfixFacade.reifyRow(row)

        assertNotNull(reified)
        // SymbolName cell was resolved through StringPool
        assertNotNull(reified.b(0).a)
        // ChildRows cells were NOT forced (Confix only reifies on demand)
        assertFalse(reified.anyLazyForced(4..8))
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun facetOf(cell: RowVecCell): PointcutFacet =
        (cell.b() as ColumnMetaRef).facet

    private fun emptyCursor(): Cursor = object : Cursor {
        override val a: Int get() = 0
        override val b: (Int) -> RowVec get() = { throw IndexOutOfBoundsException() }
    }

    private fun <T> seriesOf(vararg items: T): Series<T> = object : Series<T> {
        override val a: Int get() = items.size
        override val b: (Int) -> T get() = { items[it] }
    }

    private fun RowVec.anyLazyForced(range: IntRange): Boolean {
        for (i in range) {
            val v = b(i).a
            if (v is Lazy<*> && v.isInitialized()) return true
        }
        return false
    }
}
