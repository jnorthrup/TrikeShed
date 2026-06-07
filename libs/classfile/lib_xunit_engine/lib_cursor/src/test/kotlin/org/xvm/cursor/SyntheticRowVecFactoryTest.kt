package org.xvm.cursor

import borg.trikeshed.lib.Series
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SyntheticRowVecFactoryTest {
    @Test
    fun `faceted refs name the launch pointcut facets`() {
        val refs = SyntheticPointcutRowVecFactory.columnRefs()

        assertEquals(8, refs.a)
        assertEquals(PointcutFacet.SymbolName, refs.b(0).facet)
        assertEquals(PointcutFacet.TypeInfo, refs.b(1).facet)
        assertEquals(PointcutFacet.ClassfileCoordinate, refs.b(2).facet)
        assertEquals(PointcutFacet.XvmCoordinate, refs.b(3).facet)
        assertEquals(PointcutFacet.PointcutKind, refs.b(4).facet)
        assertEquals(PointcutFacet.StringPool, refs.b(5).facet)
        assertEquals(PointcutFacet.StringPool, refs.b(6).facet)
        assertEquals(PointcutFacet.Wireproto, refs.b(7).facet)
        assertEquals(PointcutFacet.ConfixMeta, PointcutFacet.valueOf("ConfixMeta"))
        assertEquals(PointcutFacet.VmStats, PointcutFacet.valueOf("VmStats"))
        assertEquals(
            PointcutFacet.ObserverDelegateRegistration,
            PointcutFacet.valueOf("ObserverDelegateRegistration"),
        )
        assertEquals(PointcutFacet.ReduxPhilum, PointcutFacet.valueOf("ReduxPhilum"))
        assertEquals(PointcutFacet.SynapsePhilum, PointcutFacet.valueOf("SynapsePhilum"))
        assertEquals(PointcutFacet.ClassfileTaxonomy, PointcutFacet.valueOf("ClassfileTaxonomy"))
        assertEquals(PointcutFacet.EdgeTaxonomy, PointcutFacet.valueOf("EdgeTaxonomy"))
        assertEquals(PointcutFacet.CrmsDomain, PointcutFacet.valueOf("CrmsDomain"))
        assertEquals(PointcutFacet.XSrcFile, PointcutFacet.valueOf("XSrcFile"))
        for (facet in PointcutFacet.entries) {
            assertFalse(facet.name.startsWith("JitConnector"), facet.name)
        }
    }

    @Test
    fun `classfiles and edges share the same base decorations`() {
        val base = SyntheticPointcutRowVecFactory.columnRefs()
        val classfiles = SyntheticPointcutRowVecFactory.classfileFacetRefs()
        val edges = SyntheticPointcutRowVecFactory.edgeFacetRefs()

        assertEquals(10, classfiles.a)
        assertEquals(10, edges.a)
        for (index in 0 until base.a) {
            assertEquals(base.b(index), classfiles.b(index))
            assertEquals(base.b(index), edges.b(index))
        }
        assertEquals(PointcutFacet.ClassfileTaxonomy, classfiles.b(8).facet)
        assertEquals(PointcutFacet.EdgeTaxonomy, edges.b(8).facet)
        assertEquals(PointcutFacet.ConfixMeta, classfiles.b(9).facet)
        assertEquals(PointcutFacet.ConfixMeta, edges.b(9).facet)
    }

    @Test
    fun `synthetic rowvec maps faceted ColumnMetaRef ordinals to symbol ids`() {
        val row = SyntheticPointcutRow(
            symbolName = "org.xtc.Foo.bar",
            typeInfo = "()V",
            classfileCoordinate = "Foo.class#cp:7#method:bar",
            xvmCoordinate = "org.xtc/Foo.bar:0",
            pointcutKind = "method-entry",
            poolId = 11,
            codecHash = 97L,
            wireproto = byteArrayOf(1, 2, 3),
        )

        val rowVec = SyntheticPointcutRowVecFactory.rowVec(row)

        assertEquals(8, rowVec.a)
        assertEquals(StringPool.intern("org.xtc.Foo.bar"), rowVec.b(0).a)
        assertEquals("symbolName", rowVec.b(0).b().name)
        assertEquals(StringPool.intern("()V"), rowVec.b(1).a)
        assertEquals("typeInfo", rowVec.b(1).b().name)
        assertEquals(StringPool.intern("Foo.class#cp:7#method:bar"), rowVec.b(2).a)
        assertEquals(StringPool.intern("org.xtc/Foo.bar:0"), rowVec.b(3).a)
        assertEquals(Symbols.MethodEntry.symbol.id.raw, rowVec.b(4).a)
        assertEquals(11, rowVec.b(5).a)
        assertEquals(97L, rowVec.b(6).a)
        assertNotNull(rowVec.b(7).a as? MemSegment)
    }

    @Test
    fun `pointcut names are interned symbols not repeated row strings`() {
        val one = Symbols.pointcutName("field-read")
        val two = Symbols.FieldRead
        val three = Symbols.FieldWrite

        assertEquals(two.symbol.id, one.symbol.id)
        assertNotEquals(three.symbol.id, one.symbol.id)
        assertEquals("field-read", StringPool.resolve(one.symbol.id.raw))
    }

    @Test
    fun `CRMS domain serializes domains into RowVec column meta cursor trees`() {
        val classfile = CrmsColumnMetaGenerator.domain(
            name = "classfile-domain",
            facet = PointcutFacet.ClassfileTaxonomy,
            columns = SyntheticPointcutRowVecFactory.classfileFacetRefs(),
        )
        val edge = CrmsColumnMetaGenerator.domain(
            name = "edge-domain",
            facet = PointcutFacet.EdgeTaxonomy,
            columns = SyntheticPointcutRowVecFactory.edgeFacetRefs(),
        )
        val root = CrmsColumnMetaGenerator.domain(
            name = "org.xtc-crms",
            facet = PointcutFacet.CrmsDomain,
            columns = CrmsColumnMetaGenerator.columnRefs(),
            children = seriesOfDomains(classfile, edge),
        )

        val cursor = CrmsColumnMetaGenerator.cursor(root)
        val rootRow = cursor.b(0)
        val columns = rootRow.b(4).a as Cursor
        val children = rootRow.b(5).a as Cursor

        assertEquals(Symbols.symbol("org.xtc-crms").id.raw, rootRow.b(0).a)
        assertEquals(PointcutFacet.CrmsDomain.name, StringPool.resolve(rootRow.b(2).a as Int))
        assertEquals(6, rootRow.b(3).a)
        assertEquals(6, columns.a)
        assertEquals(2, children.a)
        assertEquals(Symbols.symbol("classfile-domain").id.raw, children.b(0).b(0).a)
        assertEquals(Symbols.symbol("edge-domain").id.raw, children.b(1).b(0).a)
    }

    @Test
    fun `JitConnector is an XSrcFile facet with child cursor collections`() {
        val refs = XSrcFileFacetFactory.columnRefs()
        val childRefs = XSrcFileFacetFactory.childColumnRefs()
        val row = XSrcFileFacetFactory.rowVec(XSrcFileFacetFactory.jitConnector())
        val children = row.b(3).a as Cursor

        assertEquals(4, refs.a)
        assertEquals(PointcutFacet.XSrcFile, refs.b(0).facet)
        assertEquals(PointcutFacet.ChildRows, refs.b(3).facet)
        assertEquals(7, childRefs.a)
        for (index in 0 until childRefs.a) {
            assertEquals(PointcutFacet.XSrcFile, childRefs.b(index).facet)
        }
        assertEquals(Symbols.symbol("JitConnector.java").id.raw, row.b(0).a)
        assertEquals(7, children.a)
        assertEquals(StringPool.intern("mapInjections"), children.b(0).b(0).a)
        assertEquals(StringPool.intern("JitConnector.start"), children.b(0).b(1).a)
        assertEquals(StringPool.intern("dumpNames"), children.b(1).b(0).a)
        assertEquals(StringPool.intern("typeSystem.loadedClasses"), children.b(6).b(0).a)
    }

    @Test
    fun `factory maps a series of synthetic rows into a cursor`() {
        val rows = SyntheticPointcutRowVecFactory.cursor(
            SyntheticPointcutRow(
                symbolName = "org.xtc.Foo.a",
                typeInfo = "I",
                classfileCoordinate = "Foo.class#field:a",
                xvmCoordinate = "org.xtc/Foo.a",
                pointcutKind = "field-read",
                poolId = 1,
                codecHash = 31L,
                wireproto = byteArrayOf(10),
            ),
            SyntheticPointcutRow(
                symbolName = "org.xtc.Foo.b",
                typeInfo = "J",
                classfileCoordinate = "Foo.class#field:b",
                xvmCoordinate = "org.xtc/Foo.b",
                pointcutKind = "field-write",
                poolId = 2,
                codecHash = 32L,
                wireproto = byteArrayOf(11),
            ),
        )

        assertEquals(2, rows.a)
        assertEquals(StringPool.intern("org.xtc.Foo.a"), rows.b(0).b(0).a)
        assertEquals(StringPool.intern("org.xtc.Foo.b"), rows.b(1).b(0).a)
    }
}

private fun seriesOfDomains(vararg domains: CrmsDomain): Series<CrmsDomain> = object : Series<CrmsDomain> {
    override val a: Int
        get() = domains.size

    override val b: (Int) -> CrmsDomain
        get() = { index -> domains[index] }
}
