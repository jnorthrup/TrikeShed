package org.xvm.cursor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD RED: ClassFileTaxonomy — Confix-based Facetted ClassFile browse/registry.
 *
 * The taxonomy ingests minimal coordinate rows from classfile scans and exposes
 * them as a lazily-navigable ConfixCursor with PointcutFacet-tagged columns.
 *
 * Coordinate row shape (from classfile-coordinate-pointcuts.md):
 *   symbolName, ownerType, methodOrField, classfileCoord, cpIndex,
 *   descriptor, xvmTypeInfo, pointcutKind, poolId
 */
class ClassFileTaxonomyTest {

    // ── Construction ──────────────────────────────────────────────────────

    @Test
    fun `taxonomy constructs empty`() {
        val tax = ClassFileTaxonomy()
        assertNotNull(tax)
    }

    @Test
    fun `empty taxonomy has zero rows`() {
        val tax = ClassFileTaxonomy()
        assertEquals(0, tax.size)
    }

    // ── register single coordinate row ────────────────────────────────────

    @Test
    fun `register one row increments size`() {
        val tax = ClassFileTaxonomy()
        tax.register(coordinateRow("pkg.Foo", "run", 0x10, 0))
        assertEquals(1, tax.size)
    }

    @Test
    fun `register two rows gives size 2`() {
        val tax = ClassFileTaxonomy()
        tax.register(coordinateRow("pkg.Foo", "run", 0x10, 0))
        tax.register(coordinateRow("pkg.Bar", "init", 0x34, 1))
        assertEquals(2, tax.size)
    }

    // ── coordinate row field access ───────────────────────────────────────

    @Test
    fun `registered row symbolName survives round-trip`() {
        val tax = ClassFileTaxonomy()
        tax.register(coordinateRow("pkg.Foo", "run", 0x10, 42))
        val row = tax.rowAt(0)
        assertEquals("pkg.Foo.run", row.symbolName)
    }

    @Test
    fun `registered row pointcutKind survives round-trip`() {
        val tax = ClassFileTaxonomy()
        tax.register(coordinateRow("pkg.Foo", "run", 0xA5, 7))
        val row = tax.rowAt(0)
        assertEquals(0xA5, row.pointcutKind)
    }

    @Test
    fun `registered row poolId survives round-trip`() {
        val tax = ClassFileTaxonomy()
        tax.register(coordinateRow("pkg.Foo", "run", 0x10, 99))
        val row = tax.rowAt(0)
        assertEquals(99, row.poolId)
    }

    // ── ConfixCursor projection ───────────────────────────────────────────

    @Test
    fun `asCursor returns non-null cursor`() {
        val tax = ClassFileTaxonomy()
        tax.register(coordinateRow("pkg.Foo", "run", 0x10, 0))
        val cursor = tax.asCursor()
        assertNotNull(cursor)
    }

    @Test
    fun `cursor row count matches taxonomy size`() {
        val tax = ClassFileTaxonomy()
        repeat(5) { i -> tax.register(coordinateRow("pkg.C$i", "m", 0x10, i)) }
        val cursor = tax.asCursor()
        assertEquals(5, cursor.size)
    }

    @Test
    fun `cursor row has symbolName column`() {
        val tax = ClassFileTaxonomy()
        tax.register(coordinateRow("pkg.Qux", "go", 0x20, 3))
        val cursor = tax.asCursor()
        val row = cursor.rowAt(0)
        assertNotNull(row["symbolName"])
        assertEquals("pkg.Qux.go", row["symbolName"])
    }

    @Test
    fun `cursor row has pointcutKind column`() {
        val tax = ClassFileTaxonomy()
        tax.register(coordinateRow("pkg.Qux", "go", 0x20, 3))
        val cursor = tax.asCursor()
        val row = cursor.rowAt(0)
        assertEquals(0x20, row["pointcutKind"])
    }

    // ── facet tagging ─────────────────────────────────────────────────────

    @Test
    fun `symbolName column has SymbolName facet`() {
        val tax = ClassFileTaxonomy()
        tax.register(coordinateRow("pkg.F", "x", 0x10, 0))
        val cursor = tax.asCursor()
        val meta = cursor.columnMeta("symbolName")
        assertEquals(PointcutFacet.SymbolName, meta.facet)
    }

    @Test
    fun `classfileCoord column has ClassfileCoordinate facet`() {
        val tax = ClassFileTaxonomy()
        tax.register(coordinateRow("pkg.F", "x", 0x10, 0))
        val cursor = tax.asCursor()
        val meta = cursor.columnMeta("classfileCoord")
        assertEquals(PointcutFacet.ClassfileCoordinate, meta.facet)
    }

    // ── filter / browse ───────────────────────────────────────────────────

    @Test
    fun `filterByKind returns only matching rows`() {
        val tax = ClassFileTaxonomy()
        tax.register(coordinateRow("pkg.A", "call", 0x10, 0))
        tax.register(coordinateRow("pkg.B", "field", 0xA5, 1))
        tax.register(coordinateRow("pkg.C", "call2", 0x10, 2))
        val filtered = tax.filterByKind(0x10)
        assertEquals(2, filtered.size)
    }

    @Test
    fun `filterByOwner returns only rows for that owner`() {
        val tax = ClassFileTaxonomy()
        tax.register(coordinateRow("pkg.Foo", "a", 0x10, 0))
        tax.register(coordinateRow("pkg.Foo", "b", 0x20, 1))
        tax.register(coordinateRow("pkg.Bar", "c", 0x10, 2))
        val filtered = tax.filterByOwner("pkg.Foo")
        assertEquals(2, filtered.size)
    }

    @Test
    fun `filterByKind empty result for unknown kind`() {
        val tax = ClassFileTaxonomy()
        tax.register(coordinateRow("pkg.A", "m", 0x10, 0))
        val filtered = tax.filterByKind(0xFF)
        assertEquals(0, filtered.size)
    }

    // ── wire-friendly: poolId lookup ──────────────────────────────────────

    @Test
    fun `lookupByPoolId returns correct row`() {
        val tax = ClassFileTaxonomy()
        tax.register(coordinateRow("pkg.A", "x", 0x10, 77))
        tax.register(coordinateRow("pkg.B", "y", 0x20, 88))
        val row = tax.lookupByPoolId(88)
        assertNotNull(row)
        assertEquals(88, row!!.poolId)
        assertEquals("pkg.B.y", row.symbolName)
    }

    @Test
    fun `lookupByPoolId returns null for missing`() {
        val tax = ClassFileTaxonomy()
        assertEquals(null, tax.lookupByPoolId(999))
    }

    // ── helper ────────────────────────────────────────────────────────────

    private fun coordinateRow(owner: String, method: String, kind: Int, poolId: Int) =
        ClassFileTaxonomy.CoordinateRow(
            symbolName    = "$owner.$method",
            ownerType     = owner,
            methodOrField = method,
            classfileCoord = "$owner#$method",
            cpIndex       = poolId,
            descriptor    = "()V",
            xvmTypeInfo   = "",
            pointcutKind  = kind,
            poolId        = poolId,
        )
}
