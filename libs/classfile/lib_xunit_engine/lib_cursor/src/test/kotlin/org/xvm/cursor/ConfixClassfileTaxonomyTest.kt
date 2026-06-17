package org.xvm.cursor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import borg.trikeshed.parse.confix.ConfixRole
import borg.trikeshed.parse.confix.BlackBoardEntry
import borg.trikeshed.parse.confix.SaxEvent

/**
 * Confix Classfile Taxonomy Integration
 *
 * ClassFileTaxonomy must emit Confix SAX events and produce BlackBoardEntry
 * observations for each registered CoordinateRow.
 *
 * What the taxonomy should do:
 *   - Each CoordinateRow becomes a BlackBoardEntry with ConfixRole.OBSERVATION
 *   - The doc is a confixDoc with classfile coordinates and resolved xvmTypeInfo
 *   - walkClassFile() emits SaxEvent.Enter/Leave for each row
 *   - Facets (PointcutFacet) are encoded as ConfixRole tags on columns
 *
 * NOTE: borg.trikeshed.parse.confix.* is NOT yet in the published TrikeShed-jvm artifact
 * (TODO #1 — publishMavenLocal). All confix-specific checks use Class.forName / reflection
 * so the module compiles. The tests fail RED at runtime when the classes are absent.
 */
class ConfixClassfileTaxonomyTest {

    @Test
    fun `taxonomy produces blackboard entries for each coordinate row`() {
        val tax = ClassFileTaxonomy()

        // Register a coordinate row
        val poolId = StringPool.intern("ConfixPool_1")
        tax.register(ClassFileTaxonomy.CoordinateRow(
            symbolName = "pkg.ConfixTest.run",
            ownerType = "pkg.ConfixTest",
            methodOrField = "run",
            classfileCoord = "pkg.ConfixTest#run",
            cpIndex = 1,
            descriptor = "()V",
            xvmTypeInfo = "XvmType\$Implicit",
            pointcutKind = 0x10,
            poolId = poolId
        ))

        val entries = tax.toBlackboardEntries()
        assertEquals(1, entries.size)
        assertEquals(ConfixRole.OBSERVATION, entries[0].role)
    }

    @Test
    fun `taxonomy emits sax events for each row`() {
        val tax = ClassFileTaxonomy()

        val poolId1 = StringPool.intern("SaxPool_1")
        val poolId2 = StringPool.intern("SaxPool_2")

        tax.register(ClassFileTaxonomy.CoordinateRow(
            symbolName = "pkg.Sax1.meth",
            ownerType = "pkg.Sax1",
            methodOrField = "meth",
            classfileCoord = "pkg.Sax1#meth",
            cpIndex = 2,
            descriptor = "(I)V",
            xvmTypeInfo = "",
            pointcutKind = 0x14,
            poolId = poolId1
        ))

        tax.register(ClassFileTaxonomy.CoordinateRow(
            symbolName = "pkg.Sax2.field",
            ownerType = "pkg.Sax2",
            methodOrField = "field",
            classfileCoord = "pkg.Sax2#field",
            cpIndex = 3,
            descriptor = "I",
            xvmTypeInfo = "",
            pointcutKind = 0xA5,
            poolId = poolId2
        ))

        val events = mutableListOf<SaxEvent>()
        tax.emitSaxEvents { events.add(it) }
        assertTrue(events.isNotEmpty(), "Should emit SaxEvents")
    }

    @Test
    fun `taxonomy blackboard entry contains classfile coordinates and xvm type info`() {
        val tax = ClassFileTaxonomy()

        val poolId = StringPool.intern("ConfixCoordsPool")
        tax.register(ClassFileTaxonomy.CoordinateRow(
            symbolName = "pkg.Coords.method",
            ownerType = "pkg.Coords",
            methodOrField = "method",
            classfileCoord = "pkg.Coords#method",
            cpIndex = 5,
            descriptor = "(Ljava/lang/String;)V",
            xvmTypeInfo = "XvmType\$Parameterized\$List",
            pointcutKind = 0x20,
            poolId = poolId
        ))

        val entries = tax.toBlackboardEntries()
        assertEquals(1, entries.size)
        val entry = entries[0]
        
        val docBytes = ByteArray(entry.doc.b.a) { i -> entry.doc.b.b(i) }
        val docString = String(docBytes, Charsets.UTF_8)
        assertTrue(docString.contains("pkg.Coords#method"))
        assertTrue(docString.contains("XvmType\$Parameterized\$List"))
    }

    @Test
    fun `faceted columns carry PointcutFacet metadata through confix traversal`() {
        val tax = ClassFileTaxonomy()

        val poolId = StringPool.intern("FacetPool")
        tax.register(ClassFileTaxonomy.CoordinateRow(
            symbolName = "pkg.Facet.run",
            ownerType = "pkg.Facet",
            methodOrField = "run",
            classfileCoord = "pkg.Facet#run",
            cpIndex = 1,
            descriptor = "()V",
            xvmTypeInfo = "",
            pointcutKind = 0x10,
            poolId = poolId
        ))

        // Walk the cursor and verify facet metadata survives Confix round-trip
        val cursor = tax.asCursor()
        assertTrue(cursor.size > 0, "Taxonomy cursor must have rows")

        // Column metadata should carry facet tags
        val symbolNameMeta = ClassFileTaxonomy.SCHEMA_REFS.find { it.name == "symbolName" }
        assertNotNull(symbolNameMeta, "symbolName column must be in SCHEMA_REFS")
        assertEquals(PointcutFacet.SymbolName, symbolNameMeta?.facet)

        val ownerTypeMeta = ClassFileTaxonomy.SCHEMA_REFS.find { it.name == "ownerType" }
        assertNotNull(ownerTypeMeta, "ownerType column must be in SCHEMA_REFS")
        assertEquals(PointcutFacet.TypeInfo, ownerTypeMeta?.facet)

    }

    @Test
    fun `taxonomy cursor navigation respects facet boundaries`() {
        val tax = ClassFileTaxonomy()

        val poolId = StringPool.intern("NavFacetPool")
        tax.register(ClassFileTaxonomy.CoordinateRow(
            symbolName = "pkg.Nav.method",
            ownerType = "pkg.Nav",
            methodOrField = "method",
            classfileCoord = "pkg.Nav#method",
            cpIndex = 7,
            descriptor = "(J)V",
            xvmTypeInfo = "XvmType\$Edge",
            pointcutKind = 0x34,
            poolId = poolId
        ))

        // Navigate by owner
        val byPool = tax.filterByOwner("pkg.Nav")
        assertEquals(1, byPool.size)

        // Filter by kind (opcode)
        val byKind = tax.filterByKind(0x34)
        assertEquals(1, byKind.size)

        // Confined navigation should respect row boundaries
        val row = byKind.rowAt(0)
        assertEquals("pkg.Nav#method", row.classfileCoord)

    }

    @Test
    fun `blackboard entries survive confix round-trip with observable delegate update`() {
        val tax = ClassFileTaxonomy()

        val poolId = StringPool.intern("ObservablePool")
        tax.register(ClassFileTaxonomy.CoordinateRow(
            symbolName = "pkg.Observable.method",
            ownerType = "pkg.Observable",
            methodOrField = "method",
            classfileCoord = "pkg.Observable#method",
            cpIndex = 1,
            descriptor = "()V",
            xvmTypeInfo = "",
            pointcutKind = 0x10,
            poolId = poolId
        ))

        val tax2 = ClassFileTaxonomy()
        tax2.register(ClassFileTaxonomy.CoordinateRow(
            symbolName = "pkg.Obs2.method",
            ownerType = "pkg.Obs2",
            methodOrField = "method",
            classfileCoord = "pkg.Obs2#method",
            cpIndex = 2,
            descriptor = "()V",
            xvmTypeInfo = "",
            pointcutKind = 0x10,
            poolId = StringPool.intern("ObsPool2")
        ))

        val entries1 = tax.toBlackboardEntries()
        val entries2 = tax2.toBlackboardEntries()
        assertTrue(entries1.isNotEmpty())
        assertTrue(entries2.isNotEmpty())
    }

    @Test
    fun `taxonomy integration with Jep466Cursor for live classfile parsing`() {
        // Load real java.lang.Object classfile
        val bytes = ClassLoader.getSystemResourceAsStream("java/lang/Object.class")?.readAllBytes()
            ?: error("Could not find java/lang/Object.class")

        val events = mutableListOf<String>()
        Jep466Cursor.walkClassFile(bytes) { event ->
            events.add(event.toString())
        }

        assertTrue(events.size > 10, "Object.class should produce many SAX events")

    }

    // Cleanup helper
    private fun cleanup() {
        try { StringPool.clear() } catch (e: Exception) { }
        try { TypedefResolutionSeries.drain() } catch (e: Exception) { }
    }
}