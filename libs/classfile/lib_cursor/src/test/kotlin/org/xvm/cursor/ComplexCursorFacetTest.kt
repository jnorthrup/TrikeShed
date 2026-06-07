package org.xvm.cursor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD RED: show the facets on a suitably complex Cursor construction preferably a Confix Taxonomy node
 */
class ComplexCursorFacetTest {

    @Test
    fun `complex Cursor construction shows facets on a Confix Taxonomy node`() {
        val tax = ClassFileTaxonomy()

        // Register a coordinate row representing a suitably complex node
        val poolId = StringPool.intern("ComplexFacetPool")
        tax.register(ClassFileTaxonomy.CoordinateRow(
            symbolName = "pkg.Complex.Node",
            ownerType = "pkg.Complex",
            methodOrField = "Node",
            classfileCoord = "pkg.Complex#Node",
            cpIndex = 99,
            descriptor = "()Ljava/lang/Object;",
            xvmTypeInfo = "XvmComplexType",
            pointcutKind = 0x10,
            poolId = poolId
        ))

        // Get the cursor
        val cursor = tax.asCursor()
        assertTrue(cursor.size > 0)

        val firstRow = cursor.rowAt(0)

        // Assert values and metadata on the row itself
        assertEquals(13, firstRow.a, "RowVec should have 13 columns (4 system + 9 data columns)")
        assertEquals("pkg.Complex.Node", firstRow.b(4).a, "symbolName value mismatch")
        
        val metaSymbolName = firstRow.b(4).b()
        assertTrue(metaSymbolName is ColumnMetaRef)
        assertEquals("symbolName", (metaSymbolName as ColumnMetaRef).name)
        assertEquals(PointcutFacet.SymbolName, metaSymbolName.facet)

        assertEquals("pkg.Complex", firstRow.b(5).a, "ownerType value mismatch")
        val metaOwnerType = firstRow.b(5).b()
        assertTrue(metaOwnerType is ColumnMetaRef)
        assertEquals(PointcutFacet.TypeInfo, (metaOwnerType as ColumnMetaRef).facet)
    }
}
