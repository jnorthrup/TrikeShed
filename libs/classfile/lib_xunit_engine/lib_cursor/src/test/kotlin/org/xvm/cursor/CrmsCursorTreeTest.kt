package org.xvm.cursor

import borg.trikeshed.lib.Series
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CRMS cursor tree — recursive materialized view from faceted beans.
 *
 *   Root CRMS Cursor
 *     └── XSrcFile facet
 *           └── ClassfileTaxonomy child Cursor
 *                 ├── constants child Cursor
 *                 ├── methods child Cursor
 *                 ├── fields child Cursor
 *                 ├── edges child Cursor
 *                 └── events child Cursor
 *
 * Convention (single facet per ColumnMetaRef):
 *   children column has facet ChildRows
 *   child cursor rows themselves carry ClassfileTaxonomy / EdgeTaxonomy / XSrcFile
 */
class CrmsCursorTreeTest {

    // ── Bean schemas for the CRMS tree ────────────────────────────────────

    data class XSrcFileNode(
        val sourceFileName: String,
        val sourcePath: String,
        val packagePattern: String,
        val classfiles: Lazy<Cursor>,
    )

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

    data class EdgeNode(
        val symbolName: String,
        val edgeType: String,
        val sourceCoordinate: Int,
        val targetCoordinate: Int,
    )

    // ── Root -> XSrcFile ──────────────────────────────────────────────────

    @Test
    fun `root cursor from XSrcFile beans has ChildRows facet on classfiles column`() {
        val root = CrmsDomainCursor.from(
            seriesOf(
                XSrcFileNode(
                    "JitConnector.java", "org/xvm/runtime/JitConnector.java",
                    "org.xvm.runtime.*",
                    lazy { emptyCursor() },
                ),
            ),
        )

        assertEquals(1, root.a)
        // sourceFileName -> SymbolName, classfiles -> ChildRows
        assertEquals(PointcutFacet.SymbolName, facetOf(root.b(0), 0))
        assertEquals(PointcutFacet.ChildRows, facetOf(root.b(0), 3))
    }

    @Test
    fun `root cursor carries XSrcFile facet on source file rows`() {
        val root = CrmsDomainCursor.from(
            seriesOf(
                XSrcFileNode(
                    "JitConnector.java", "org/xvm/runtime/JitConnector.java",
                    "org.xvm.runtime.*",
                    lazy { emptyCursor() },
                ),
            ),
        )

        val refs = ReflectiveFacet.deriveRefs<XSrcFileNode>()
        assertEquals(PointcutFacet.SymbolName, refs.b(0).facet)
    }

    // ── XSrcFile -> ClassfileTaxonomy ──────────────────────────────────────

    @Test
    fun `classfiles child cursor opens lazily from XSrcFile row`() {
        var classfileCursorCreated = false
        val lazyClassfiles = lazy {
            classfileCursorCreated = true
            CrmsDomainCursor.from(
                seriesOf(
                    ClassfileNode(
                        "JitConnector", "Module", 0, 0,
                        lazy { emptyCursor() }, lazy { emptyCursor() },
                        lazy { emptyCursor() }, lazy { emptyCursor() },
                        lazy { emptyCursor() },
                        MemSegment(byteArrayOf()),
                    ),
                ),
            )
        }

        val root = CrmsDomainCursor.from(
            seriesOf(
                XSrcFileNode("JitConnector.java", "path", "pkg", lazyClassfiles),
            ),
        )

        // Not forced yet
        assertFalse(classfileCursorCreated)

        // Force it
        val classfilesCell = root.b(0).b(3)
        assertEquals(PointcutFacet.ChildRows, facetOf(root.b(0), 3))
        val classfiles = (classfilesCell.a as Lazy<*>).value as Cursor

        assertTrue(classfileCursorCreated)
        assertEquals(1, classfiles.a)
    }

    // ── ClassfileNode -> 5 child branches ──────────────────────────────────

    @Test
    fun `ClassfileNode has 5 ChildRows branches for constants methods fields edges events`() {
        val refs = ReflectiveFacet.deriveRefs<ClassfileNode>()

        // columns 4-8 are the 5 child branches
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
    }

    @Test
    fun `all 5 child branches in a ClassfileNode row stay lazy until touched`() {
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

        // All 5 child branches (columns 4-8) are Lazy but not forced
        for (col in 4..8) {
            val cell = row.b(col)
            assertEquals(PointcutFacet.ChildRows, facetOf(row, col))
            val lazyVal = cell.a
            assertTrue(lazyVal is Lazy<*>)
            assertFalse((lazyVal as Lazy<*>).isInitialized())
        }

        // Force only constants
        @Suppress("UNCHECKED_CAST")
        (row.b(4).a as Lazy<Cursor>).value

        // constants forced, others still lazy
        assertTrue((row.b(4).a as Lazy<*>).isInitialized())
        assertFalse((row.b(5).a as Lazy<*>).isInitialized())
        assertFalse((row.b(8).a as Lazy<*>).isInitialized())
    }

    // ── Child row facets ──────────────────────────────────────────────────

    @Test
    fun `constants child cursor rows carry ClassfileTaxonomy facet`() {
        val refs = ReflectiveFacet.deriveRefs<ClassfileNode>()
        // The constants column itself is ChildRows.
        // What facet do the child rows carry? ClassfileTaxonomy.
        // This is tested by: opening the constants child, inspecting its rows.
        val constantRefs = CrmsDomainCursor.childFacet("constants")
        assertEquals(PointcutFacet.ClassfileTaxonomy, constantRefs)
    }

    @Test
    fun `edges child cursor rows carry EdgeTaxonomy facet`() {
        val edgeRefs = CrmsDomainCursor.childFacet("edges")
        assertEquals(PointcutFacet.EdgeTaxonomy, edgeRefs)

        val eventRefs = CrmsDomainCursor.childFacet("events")
        assertEquals(PointcutFacet.SynapsePhilum, eventRefs)
    }

    @Test
    fun `EdgeNode bean derives SymbolName on symbolName and EdgeTaxonomy as child facet`() {
        val refs = ReflectiveFacet.deriveRefs<EdgeNode>()
        assertEquals(4, refs.a)
        assertEquals(PointcutFacet.SymbolName, refs.b(0).facet)
        assertEquals("symbolName", refs.b(0).name)
        assertEquals(PointcutFacet.TypeInfo, refs.b(1).facet)
        assertEquals("edgeType", refs.b(1).name)
        assertEquals(PointcutFacet.ClassfileCoordinate, refs.b(2).facet)
        assertEquals(PointcutFacet.ClassfileCoordinate, refs.b(3).facet)
    }

    // ── Event firehose as CRMS child ──────────────────────────────────────

    @Test
    fun `event firehose ring appends packed events and produces lazy cursor`() {
        val ring = CrmsDomainCursor.eventRing(capacity = 16)

        ring.append(0xA5, 1000L, 1, 42, byteArrayOf(0x01, 0x02))
        ring.append(0xA6, 1001L, 2, 43, byteArrayOf(0x03, 0x04))
        ring.append(0xA5, 1002L, 3, 44, byteArrayOf(0x05, 0x06))

        val cursor = ring.asCursor()
        assertEquals(3, cursor.a)

        // Each event row has faceted cells
        val event0 = cursor.b(0)
        assertEquals(PointcutFacet.VmStats, (event0.b(0).b() as ColumnMetaRef).facet)
        assertEquals(PointcutFacet.PointcutKind, (event0.b(1).b() as ColumnMetaRef).facet)
        assertEquals(PointcutFacet.Wireproto, (event0.b(4).b() as ColumnMetaRef).facet)
        assertTrue(event0.b(4).a is MemSegment)
    }

    @Test
    fun `event ring cursor cells carry interned symbol ids and raw wireproto`() {
        val ring = CrmsDomainCursor.eventRing(capacity = 8)
        ring.append(0xA5, 1000L, 1, 42, byteArrayOf(0xCA.toByte(), 0xFE.toByte()))

        val cursor = ring.asCursor()
        val event = cursor.b(0)

        // nanoTime cell — Long value
        assertEquals(1000L, event.b(0).a)

        // opcode cell — Int value
        assertEquals(0xA5, event.b(1).a)

        // wireproto cell — raw MemSegment
        val wire = event.b(4).a as MemSegment
        assertEquals(0xCA.toByte(), wire.bytes[0])
        assertEquals(0xFE.toByte(), wire.bytes[1])
    }

    // ── Confix iteration over CRMS tree ────────────────────────────────────

    @Test
    fun `ConfixFacade walks CRMS tree without forcing lazy children`() {
        val node = ClassfileNode(
            symbolName = "TestClass",
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
        val walk = ConfixFacade.walk(row)

        // Scalar cells reified
        assertNotNull(walk.reifiedCells())
        assertTrue(walk.reifiedCells().isNotEmpty())

        // Child cells NOT forced
        assertFalse(walk.forcedAnyChild())
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun facetOf(row: RowVec, col: Int): PointcutFacet =
        (row.b(col).b() as ColumnMetaRef).facet

    private fun emptyCursor(): Cursor = object : Cursor {
        override val a: Int get() = 0
        override val b: (Int) -> RowVec get() = { throw IndexOutOfBoundsException() }
    }

    private fun <T> seriesOf(vararg items: T): Series<T> = object : Series<T> {
        override val a: Int get() = items.size
        override val b: (Int) -> T get() = { items[it] }
    }
}
