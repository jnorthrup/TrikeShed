package org.xvm.activejs

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.currentCoroutineContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.xvm.cursor.ClassFileTaxonomy
import org.xvm.cursor.PointcutFacet
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import org.xvm.activejs.ccek.TaxonomyObserverImpl
import org.xvm.activejs.ccek.getTaxonomyObserver

/** TDD tests for ActiveJsTaxonomy — Confix-based faceted ClassFile taxonomy with live classfile pointcuts for multiplatform (JS/WASM) targets. */
class ActiveJsTaxonomyTest {

    private fun ccekContext(): kotlinx.coroutines.CoroutineContext {
        val supervisor = NioSupervisor()
        runBlocking { supervisor.open() }
        val observer = TaxonomyObserverImpl()
        supervisor.register(observer)
        return supervisor + observer
    }

    // ── Construction ────────────────────────────────────────────────────

    @Test
    fun `taxonomy constructs empty`() {
        val tax = ActiveJsTaxonomy()
        assertNotNull(tax)
    }

    @Test
    fun `empty taxonomy has zero rows`() {
        val tax = ActiveJsTaxonomy()
        assertEquals(0, tax.size)
    }

    // ── register coordinate row ─────────────────────────────────────────

    @Test
    fun `register one row increments size`() = runBlocking {
        val context = ccekContext()
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.Foo", "run", 0x10, 0, ActiveJsFacet.JsFunction), context)
        assertEquals(1, tax.size)
    }

    @Test
    fun `register two rows gives size 2`() = runBlocking {
        val context = ccekContext()
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.Foo", "run", 0x10, 0, ActiveJsFacet.JsFunction), context)
        tax.register(coordinateRow("pkg.Bar", "init", 0x34, 1, ActiveJsFacet.JsPromise), context)
        assertEquals(2, tax.size)
    }

    @Test
    fun `register triggers CCEK observer notification`() = runBlocking {
        val context = ccekContext()
        val tax = ActiveJsTaxonomy()
        
        val notified = mutableListOf<CoordinateRow>()
        val observer = TaxonomyObserverImpl()
        // re-register to capture
        val supervisor = context[NioSupervisor.Key]
        supervisor?.unregister(getTaxonomyObserver())
        supervisor?.register(object : TaxonomyObserverImpl() {
            override fun onRowRegistered(row: CoordinateRow) { notified += row }
        })
        
        tax.register(coordinateRow("pkg.Test", "method", 0x10, 1, ActiveJsFacet.JsFunction), context)
        
        assertEquals(1, notified.size)
        assertEquals("pkg.Test.method", notified[0].symbolName)
    }

    // ── coordinate row field access ──────────────────────────────────────

    @Test
    fun `registered row symbolName survives round-trip`() = runBlocking {
        val context = ccekContext()
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.Foo", "run", 0x10, 42, ActiveJsFacet.JsFunction), context)
        val row = tax.rowAt(0)
        assertEquals("pkg.Foo.run", row.symbolName)
    }

    @Test
    fun `registered row activeJsFacet survives round-trip`() = runBlocking {
        val context = ccekContext()
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.Foo", "run", 0xA5, 7, ActiveJsFacet.JsProxy), context)
        val row = tax.rowAt(0)
        assertEquals(ActiveJsFacet.JsProxy, row.activeJsFacet)
    }

    @Test
    fun `registered row pointcutKind survives round-trip`() = runBlocking {
        val context = ccekContext()
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.Foo", "run", 0xA5, 7, ActiveJsFacet.JsProxy), context)
        val row = tax.rowAt(0)
        assertEquals(0xA5, row.pointcutKind)
    }

    // ── ConfixCursor projection ──────────────────────────────────────────

    @Test
    fun `asCursor returns non-null cursor`() = runBlocking {
        val context = ccekContext()
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.Foo", "run", 0x10, 0, ActiveJsFacet.JsFunction), context)
        val cursor = tax.asCursor()
        assertNotNull(cursor)
    }

    @Test
    fun `cursor row count matches taxonomy size`() = runBlocking {
        val context = ccekContext()
        val tax = ActiveJsTaxonomy()
        repeat(5) { i -> tax.register(coordinateRow("pkg.C$i", "m", 0x10, i, ActiveJsFacet.JsFunction), context) }
        val cursor = tax.asCursor()
        assertEquals(5, cursor.size)
    }

    @Test
    fun `cursor row has symbolName column`() = runBlocking {
        val context = ccekContext()
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.Qux", "go", 0x20, 3, ActiveJsFacet.JsFunction), context)
        val cursor = tax.asCursor()
        val row = cursor.rowAt(0)
        assertNotNull(row["symbolName"])
        assertEquals("pkg.Qux.go", row["symbolName"])
    }

    @Test
    fun `cursor row has pointcutKind column`() = runBlocking {
        val context = ccekContext()
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.Qux", "go", 0x20, 3, ActiveJsFacet.JsFunction), context)
        val cursor = tax.asCursor()
        val row = cursor.rowAt(0)
        assertEquals(0x20, row["pointcutKind"])
    }

    @Test
    fun `cursor row has activeJsFacet column`() = runBlocking {
        val context = ccekContext()
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.Qux", "go", 0x20, 3, ActiveJsFacet.JsFunction), context)
        val cursor = tax.asCursor()
        val row = cursor.rowAt(0)
        assertNotNull(row["activeJsFacet"])
        assertEquals(ActiveJsFacet.JsFunction, row["activeJsFacet"])
    }

    // ── facet tagging ────────────────────────────────────────────────────

    @Test
    fun `symbolName column has SymbolName facet`() = runBlocking {
        val context = ccekContext()
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.F", "x", 0x10, 0, ActiveJsFacet.JsFunction), context)
        val cursor = tax.asCursor()
        val meta = cursor.columnMeta("symbolName")
        assertEquals(PointcutFacet.SymbolName, meta.facet)
    }

    @Test
    fun `classfileCoord column has ClassfileCoordinate facet`() = runBlocking {
        val context = ccekContext()
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.F", "x", 0x10, 0, ActiveJsFacet.JsFunction), context)
        val cursor = tax.asCursor()
        val meta = cursor.columnMeta("classfileCoord")
        assertEquals(PointcutFacet.ClassfileCoordinate, meta.facet)
    }

    @Test
    fun `activeJsFacet column has ObserverDelegateRegistration facet`() = runBlocking {
        val context = ccekContext()
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.F", "x", 0x10, 0, ActiveJsFacet.JsFunction), context)
        val cursor = tax.asCursor()
        val meta = cursor.columnMeta("activeJsFacet") as ActiveJsTaxonomy.ColumnMetaRef
        assertEquals(PointcutFacet.ObserverDelegateRegistration, meta.facet)
        assertEquals(ActiveJsFacet.JsFunction, meta.activeJsFacet)
    }

    // ── filter / browse (cold path via Series.view) ──────────────────────

    @Test
    fun `filterByKindSeries returns cold series view`() = runBlocking {
        val context = ccekContext()
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.A", "call", 0x10, 0, ActiveJsFacet.JsFunction), context)
        tax.register(coordinateRow("pkg.B", "field", 0xA5, 1, ActiveJsFacet.JsProxy), context)
        tax.register(coordinateRow("pkg.C", "call2", 0x10, 2, ActiveJsFacet.JsFunction), context)
        val filtered = tax.filterByKindSeries(0x10)
        assertEquals(2, filtered.a)
        // Access only first 1 — rest never materialized
        val first = filtered.b(0)
        assertEquals("pkg.A.call", first.symbolName)
    }

    @Test
    fun `filterByOwnerSeries returns cold series view`() = runBlocking {
        val context = ccekContext()
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.Foo", "a", 0x10, 0, ActiveJsFacet.JsFunction), context)
        tax.register(coordinateRow("pkg.Foo", "b", 0x20, 1, ActiveJsFacet.JsFunction), context)
        tax.register(coordinateRow("pkg.Bar", "c", 0x10, 2, ActiveJsFacet.JsFunction), context)
        val filtered = tax.filterByOwnerSeries("pkg.Foo")
        assertEquals(2, filtered.a)
    }

    @Test
    fun `filterByFacetSeries returns cold series view`() = runBlocking {
        val context = ccekContext()
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.A", "call", 0x10, 0, ActiveJsFacet.JsFunction), context)
        tax.register(coordinateRow("pkg.B", "field", 0xA5, 1, ActiveJsFacet.JsProxy), context)
        tax.register(coordinateRow("pkg.C", "init", 0x34, 2, ActiveJsFacet.JsPromise), context)
        val filtered = tax.filterByFacetSeries(ActiveJsFacet.JsFunction)
        assertEquals(1, filtered.a)
    }

    @Test
    fun `cursorByFacet uses cold series view`() = runBlocking {
        val context = ccekContext()
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.A", "call", 0x10, 0, ActiveJsFacet.JsFunction), context)
        tax.register(coordinateRow("pkg.B", "field", 0xA5, 1, ActiveJsFacet.JsProxy), context)
        val cursor = tax.cursorByFacet(ActiveJsFacet.JsFunction)
        assertEquals(1, cursor.size)
    }

    // ── wire-friendly: poolId lookup ────────────────────────────────────

    @Test
    fun `lookupByPoolId returns correct row`() = runBlocking {
        val context = ccekContext()
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.A", "x", 0x10, 77, ActiveJsFacet.JsFunction), context)
        tax.register(coordinateRow("pkg.B", "y", 0x20, 88, ActiveJsFacet.JsFunction), context)
        val row = tax.lookupByPoolId(88)
        assertNotNull(row)
        assertEquals(88, row!!.poolId)
        assertEquals("pkg.B.y", row.symbolName)
    }

    @Test
    fun `lookupByPoolId returns null for missing`() = runBlocking {
        val context = ccekContext()
        val tax = ActiveJsTaxonomy()
        assertEquals(null, tax.lookupByPoolId(999))
    }

    // ── Confix integration ──────────────────────────────────────────────

    @Test
    fun `toBlackboardEntries produces observation entries`() = runBlocking {
        val context = ccekContext()
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.Test", "method", 0x10, 1, ActiveJsFacet.JsFunction), context)
        val entries = tax.toBlackboardEntries()
        assertEquals(1, entries.size)
        assertEquals(ConfixRole.OBSERVATION, entries[0].role)
    }

    @Test
    fun `toBlackboardEntriesSeries returns lazy series`() = runBlocking {
        val context = ccekContext()
        val tax = ActiveJsTaxonomy()
        repeat(100) { i ->
            tax.register(coordinateRow("pkg.T$i", "m", 0x10, i, ActiveJsFacet.JsFunction), context)
        }
        val series = tax.toBlackboardEntriesSeries()
        val first3 = (0 until 3).map { series.b(it) }
        assertEquals(3, first3.size)
    }

    @Test
    fun `blackboard entry contains classfile coordinates and activeJs facet`() = runBlocking {
        val context = ccekContext()
        val tax = ActiveJsTaxonomy()
        tax.register(coordinateRow("pkg.Coords", "method", 0x20, 5, ActiveJsFacet.JsFunction), context)
        val entries = tax.toBlackboardEntries()
        val docString = entries[0].doc.toString()
        assertTrue(docString.contains("pkg.Coords#method"))
        assertTrue(docString.contains("JsFunction"))
    }

    // ── helper ───────────────────────────────────────────────────────────

    private fun coordinateRow(
        owner: String,
        method: String,
        kind: Int,
        poolId: Int,
        facet: ActiveJsFacet,
    ) = ActiveJsTaxonomy.CoordinateRow(
        symbolName    = "$owner.$method",
        ownerType     = owner,
        methodOrField = method,
        classfileCoord = "$owner#$method",
        cpIndex       = poolId,
        descriptor    = "()V",
        xvmTypeInfo   = "",
        pointcutKind  = kind,
        poolId        = poolId,
        activeJsFacet = facet,
    )
}
