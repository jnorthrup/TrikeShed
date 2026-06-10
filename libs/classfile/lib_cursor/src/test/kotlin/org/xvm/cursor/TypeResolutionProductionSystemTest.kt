package org.xvm.cursor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * TypeResolutionProductionSystem side-by-side and PRELOAD table access tests.
 *
 * PRELOAD contract used here:
 *   Series<T> = Join<Int, (Int) -> T>
 *   Cursor = Series<RowVec>
 *   query first, indexed lazy access later.
 */
class TypeResolutionProductionSystemTest {

    @BeforeEach
    fun reset() {
        StringPool.clear()
        TypeResolutionProductionSystem.reset()
    }

    @Test
    fun `system object returns non-null state`() {
        assertNotNull(TypeResolutionProductionSystem.state())
    }

    @Test
    fun `empty system has zero correlation count`() {
        assertEquals(0, TypeResolutionProductionSystem.correlationCount())
    }

    @Test
    fun `correlateTaxonomyRow updates correlation count`() {
        val poolId = StringPool.intern("TestPool_Corr")
        val tax = ClassFileTaxonomy()
        tax.register(ClassFileTaxonomy.CoordinateRow(
            symbolName = "pkg.Foo.bar",
            ownerType = "pkg.Foo",
            methodOrField = "bar",
            classfileCoord = "pkg.Foo#bar",
            cpIndex = 1,
            descriptor = "()V",
            xvmTypeInfo = "",
            pointcutKind = 0x10,
            poolId = poolId
        ))

        TypeResolutionProductionSystem.correlateTaxonomyRow(tax, 0)

        assertEquals(1, TypeResolutionProductionSystem.correlationCount())
    }

    @Test
    fun `state snapshot contains resolution metadata`() {
        val state = TypeResolutionProductionSystem.state()
        assertTrue(state.totalJournalFacts >= 0, "totalJournalFacts must be >= 0")
        assertEquals(0, state.resolvedBindingCount)
    }

    @Test
    fun `side-by-side debug TypeResolutionProductionSystem vs TypedefResolutionSeries`() {
        val poolId = StringPool.intern("SideBySidePool")
        val tax = ClassFileTaxonomy()
        tax.register(ClassFileTaxonomy.CoordinateRow(
            symbolName = "pkg.Dbg.run",
            ownerType = "pkg.Dbg",
            methodOrField = "run",
            classfileCoord = "pkg.Dbg#run",
            cpIndex = 42,
            descriptor = "()V",
            xvmTypeInfo = "",
            pointcutKind = 0x10,
            poolId = poolId
        ))

        TypeResolutionProductionSystem.correlateTaxonomyRow(tax, 0)

        val rawFacts = TypedefResolutionSeries.size()
        val correlationCount = TypeResolutionProductionSystem.correlationCount()
        println("=== Side-by-Side Debug ===")
        println("TypedefResolutionSeries.size(): $rawFacts")
        println("TypeResolutionProductionSystem.correlationCount(): $correlationCount")
        println("=========================")

        assertEquals(1, rawFacts)
        assertEquals(1, correlationCount)
    }

    @Test
    fun `poolContextTable is live PRELOAD Series accessor over table data`() {
        val table = TypeResolutionProductionSystem.poolContextTable()
        assertEquals(0, table.a)

        val poolId = StringPool.intern("LazyPool")
        val factId = TypeResolutionProductionSystem.resolveBinding(
            poolId = poolId,
            siteOrd = 7,
            coordination = TypeResolutionProductionSystem.CoordinationPoint.UNION,
            className = "pkg.Lazy",
            resolvedTypeName = "pkg.ResolvedLazy",
            resolvedTypePoolId = StringPool.intern("pkg.ResolvedLazy"),
        )

        assertTrue(factId >= 0)
        assertEquals(1, table.a, "Series size must read the live table, not an eager snapshot")
        val binding = table.b(0)
        assertEquals(poolId, binding.poolId)
        assertEquals(TypeResolutionProductionSystem.CoordinationPoint.UNION, binding.coordination)
        assertEquals("pkg.ResolvedLazy", binding.resolvedTypeName)
    }

    @Test
    fun `queryBindings is a live PRELOAD Series filtered by coordination`() {
        val query = TypeResolutionProductionSystem.queryBindings(
            TypeResolutionProductionSystem.BindingQuery(
                coordination = TypeResolutionProductionSystem.CoordinationPoint.RESOLVE_TYPEDEFS
            )
        )

        TypeResolutionProductionSystem.resolveBinding(
            poolId = StringPool.intern("OtherPool"),
            siteOrd = 1,
            coordination = TypeResolutionProductionSystem.CoordinationPoint.IS_A,
            className = "pkg.Other",
            resolvedTypeName = "pkg.OtherType",
            resolvedTypePoolId = StringPool.intern("pkg.OtherType"),
        )
        assertEquals(0, query.a)

        val poolId = StringPool.intern("TypedefPool")
        TypeResolutionProductionSystem.resolveBinding(
            poolId = poolId,
            siteOrd = 2,
            coordination = TypeResolutionProductionSystem.CoordinationPoint.RESOLVE_TYPEDEFS,
            className = "pkg.TypeAlias",
            resolvedTypeName = "pkg.Target",
            resolvedTypePoolId = StringPool.intern("pkg.Target"),
        )

        assertEquals(1, query.a)
        assertEquals(poolId, query.b(0).poolId)
    }

    @Test
    fun `queryCursor exposes lazy RowVec cells and metadata`() {
        val cursor = TypeResolutionProductionSystem.queryCursor(
            TypeResolutionProductionSystem.BindingQuery(
                coordination = TypeResolutionProductionSystem.CoordinationPoint.UNION
            )
        )

        val poolId = StringPool.intern("CursorPool")
        TypeResolutionProductionSystem.resolveBinding(
            poolId = poolId,
            siteOrd = 3,
            coordination = TypeResolutionProductionSystem.CoordinationPoint.UNION,
            className = "pkg.Cursor",
            resolvedTypeName = "pkg.CursorResolved",
            resolvedTypePoolId = StringPool.intern("pkg.CursorResolved"),
        )

        assertEquals(1, cursor.a)
        val row = cursor.b(0)
        assertEquals(6, row.a)
        assertEquals("bindingId", row.b(0).b().name)
        assertEquals("poolId", row.b(1).b().name)
        assertEquals(poolId, row.b(1).a)
        assertEquals("coordination", row.b(2).b().name)
        assertEquals("UNION", row.b(2).a)
    }

    @Test
    fun `poolTaxonomyJoin is a live Cursor over binding table and taxonomy`() {
        val tax = ClassFileTaxonomy()
        val joined = TypeResolutionProductionSystem.poolTaxonomyJoin(tax)
        assertEquals(0, joined.a)

        val poolId = StringPool.intern("JoinPool")
        tax.register(ClassFileTaxonomy.CoordinateRow(
            symbolName = "pkg.Join.symbol",
            ownerType = "pkg.Join",
            methodOrField = "symbol",
            classfileCoord = "pkg.Join#symbol",
            cpIndex = 9,
            descriptor = "()Object",
            xvmTypeInfo = "pkg.JoinType",
            pointcutKind = 0x10,
            poolId = poolId
        ))
        TypeResolutionProductionSystem.resolveBinding(
            poolId = poolId,
            siteOrd = 4,
            coordination = TypeResolutionProductionSystem.CoordinationPoint.CALCULATE_RELATION,
            className = "pkg.Join",
            resolvedTypeName = "pkg.JoinType",
            resolvedTypePoolId = StringPool.intern("pkg.JoinType"),
        )

        assertEquals(1, joined.a)
        val row = joined.b(0)
        assertEquals(poolId, row.b(1).a)
        assertEquals("pkg.Join.symbol", row.b(6).a)
        assertEquals("symbolName", row.b(6).b().name)
    }

    @Test
    fun `coordinationHistogram is a live Cursor over the binding table`() {
        val histogram = TypeResolutionProductionSystem.coordinationHistogram()

        TypeResolutionProductionSystem.resolveBinding(
            poolId = StringPool.intern("UnionA"),
            siteOrd = 1,
            coordination = TypeResolutionProductionSystem.CoordinationPoint.UNION,
            className = "pkg.UnionA",
            resolvedTypeName = "pkg.ResolvedUnionA",
            resolvedTypePoolId = StringPool.intern("pkg.ResolvedUnionA"),
        )
        TypeResolutionProductionSystem.resolveBinding(
            poolId = StringPool.intern("UnionB"),
            siteOrd = 2,
            coordination = TypeResolutionProductionSystem.CoordinationPoint.UNION,
            className = "pkg.UnionB",
            resolvedTypeName = "pkg.ResolvedUnionB",
            resolvedTypePoolId = StringPool.intern("pkg.ResolvedUnionB"),
        )

        val unionRow = histogram.b(TypeResolutionProductionSystem.CoordinationPoint.UNION.ordinal)
        assertEquals("UNION", unionRow.b(0).a)
        assertEquals(2, unionRow.b(2).a)
    }
}
