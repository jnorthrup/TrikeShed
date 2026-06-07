package org.xvm.cursor

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.TypeMemento
import borg.trikeshed.cursor.`ColumnMeta↻`

@JvmInline
value class SymbolId(val raw: Int)

data class SymbolRef(
    val id: SymbolId,
    val value: String,
)

data class PointcutName(
    val symbol: SymbolRef,
)

object Symbols {
    val SymbolName: SymbolRef get() = symbol("symbolName")
    val TypeInfo: SymbolRef get() = symbol("typeInfo")
    val ClassfileCoordinate: SymbolRef get() = symbol("classfileCoordinate")
    val XvmCoordinate: SymbolRef get() = symbol("xvmCoordinate")
    val PointcutKind: SymbolRef get() = symbol("pointcutKind")
    val PoolId: SymbolRef get() = symbol("poolId")
    val CodecHash: SymbolRef get() = symbol("codecHash")
    val Wireproto: SymbolRef get() = symbol("wireproto")
    val DomainId: SymbolRef get() = symbol("domainId")
    val DomainName: SymbolRef get() = symbol("domainName")
    val FacetId: SymbolRef get() = symbol("facetId")
    val ColumnCount: SymbolRef get() = symbol("columnCount")
    val Columns: SymbolRef get() = symbol("columns")
    val Children: SymbolRef get() = symbol("children")
    val Ordinal: SymbolRef get() = symbol("ordinal")
    val Name: SymbolRef get() = symbol("name")
    val Type: SymbolRef get() = symbol("type")
    val Facet: SymbolRef get() = symbol("facet")
    val CollectionName: SymbolRef get() = symbol("collectionName")
    val Owner: SymbolRef get() = symbol("owner")
    val KeyType: SymbolRef get() = symbol("keyType")
    val ValueType: SymbolRef get() = symbol("valueType")
    val Cardinality: SymbolRef get() = symbol("cardinality")
    val LifecyclePhase: SymbolRef get() = symbol("lifecyclePhase")
    val SourceMember: SymbolRef get() = symbol("sourceMember")
    val SourceFile: SymbolRef get() = symbol("sourceFile")
    val SourcePath: SymbolRef get() = symbol("sourcePath")
    val PackagePattern: SymbolRef get() = symbol("packagePattern")
    val StringType: SymbolRef get() = symbol("String")
    val IntType: SymbolRef get() = symbol("Int")
    val LongType: SymbolRef get() = symbol("Long")
    val BytesType: SymbolRef get() = symbol("Bytes")
    val ArrayType: SymbolRef get() = symbol("Array")

    val MethodEntry: PointcutName get() = pointcutName("method-entry")
    val FieldRead: PointcutName get() = pointcutName("field-read")
    val FieldWrite: PointcutName get() = pointcutName("field-write")

    fun symbol(value: String): SymbolRef = SymbolRef(SymbolId(StringPool.intern(value)), value)

    fun pointcutName(value: String): PointcutName = PointcutName(symbol(value))
}

data class SyntheticPointcutRow(
    val symbolName: String,
    val typeInfo: String,
    val classfileCoordinate: String,
    val xvmCoordinate: String,
    val pointcutKind: String,
    val poolId: Int,
    val codecHash: Long,
    val wireproto: ByteArray,
) {
    fun symbolic(): SyntheticPointcutSymbolRow = SyntheticPointcutSymbolRow(
        symbolName = Symbols.symbol(symbolName),
        typeInfo = Symbols.symbol(typeInfo),
        classfileCoordinate = Symbols.symbol(classfileCoordinate),
        xvmCoordinate = Symbols.symbol(xvmCoordinate),
        pointcutName = Symbols.pointcutName(pointcutKind),
        poolId = poolId,
        codecHash = codecHash,
        wireproto = wireproto,
    )
}

data class SyntheticPointcutSymbolRow(
    val symbolName: SymbolRef,
    val typeInfo: SymbolRef,
    val classfileCoordinate: SymbolRef,
    val xvmCoordinate: SymbolRef,
    val pointcutName: PointcutName,
    val poolId: Int,
    val codecHash: Long,
    val wireproto: ByteArray,
)

data class CrmsDomain(
    val name: SymbolRef,
    val facet: PointcutFacet,
    val columns: Series<ColumnMetaRef>,
    val children: Series<CrmsDomain> = series(0) { error("empty CRMS domain child") },
) {
    val id: SymbolId
        get() = name.id
}

data class JitCollectionFacet(
    val collectionName: SymbolRef,
    val owner: SymbolRef,
    val keyType: SymbolRef,
    val valueType: SymbolRef,
    val cardinality: Int,
    val lifecyclePhase: SymbolRef,
    val sourceMember: SymbolRef,
)

data class XSrcFileFacet(
    val sourceFile: SymbolRef,
    val sourcePath: SymbolRef,
    val packagePattern: SymbolRef,
    val children: Series<JitCollectionFacet>,
)

object XSrcFileFacetFactory {
    private val fileRefs = columns(
        column(0, Symbols.SourceFile, Symbols.StringType, PointcutFacet.XSrcFile),
        column(1, Symbols.SourcePath, Symbols.StringType, PointcutFacet.XSrcFile),
        column(2, Symbols.PackagePattern, Symbols.StringType, PointcutFacet.XSrcFile),
        column(3, Symbols.Children, Symbols.ArrayType, PointcutFacet.ChildRows),
    )

    private val childRefs = columns(
        column(0, Symbols.CollectionName, Symbols.StringType, PointcutFacet.XSrcFile),
        column(1, Symbols.Owner, Symbols.StringType, PointcutFacet.XSrcFile),
        column(2, Symbols.KeyType, Symbols.StringType, PointcutFacet.XSrcFile),
        column(3, Symbols.ValueType, Symbols.StringType, PointcutFacet.XSrcFile),
        column(4, Symbols.Cardinality, Symbols.IntType, PointcutFacet.XSrcFile),
        column(5, Symbols.LifecyclePhase, Symbols.StringType, PointcutFacet.XSrcFile),
        column(6, Symbols.SourceMember, Symbols.StringType, PointcutFacet.XSrcFile),
    )

    private val fileShape = RowShape<XSrcFileFacet>(fileRefs) { file, ordinal ->
        when (ordinal) {
            0 -> file.sourceFile.id.raw
            1 -> file.sourcePath.id.raw
            2 -> file.packagePattern.id.raw
            else -> childCursor(file.children)
        }
    }

    private val childShape = RowShape<JitCollectionFacet>(childRefs) { child, ordinal ->
        when (ordinal) {
            0 -> child.collectionName.id.raw
            1 -> child.owner.id.raw
            2 -> child.keyType.id.raw
            3 -> child.valueType.id.raw
            4 -> child.cardinality
            5 -> child.lifecyclePhase.id.raw
            else -> child.sourceMember.id.raw
        }
    }

    fun columnRefs(): Series<ColumnMetaRef> = refs(fileRefs)

    fun childColumnRefs(): Series<ColumnMetaRef> = refs(childRefs)

    fun jitConnector(): XSrcFileFacet = XSrcFileFacet(
        sourceFile = Symbols.symbol("JitConnector.java"),
        sourcePath = Symbols.symbol("javatools/src/main/java/org/xvm/javajit/JitConnector.java"),
        packagePattern = Symbols.symbol("org.xvm.javajit"),
        children = jitConnectorCollections(),
    )

    fun rowVec(file: XSrcFileFacet): RowVec = fileShape.rowVec(file)

    fun cursor(vararg files: XSrcFileFacet): Cursor = cursor(files, ::rowVec)

    private fun childCursor(children: Series<JitCollectionFacet>): Cursor = cursor(children) { child ->
        childShape.rowVec(child)
    }

    private fun jitConnectorCollections(): Series<JitCollectionFacet> = series(7) { index ->
        when (index) {
            0 -> collection("mapInjections", "JitConnector.start", "String", "List<String>", "start", "parameter")
            1 -> collection("dumpNames", "JitConnector.invoke0Impl", "String", "String", "invoke", "local HashSet")
            2 -> collection("CLASS_DUMP_LIST", "JitConnector", "Int", "String", "static", "static array")
            3 -> collection("args", "JitConnector.invoke0Impl", "Int", "String", "invoke", "vararg")
            4 -> collection("findMethods", "JitConnector.findMethods", "String", "MethodStructure", "lookup", "returned Set")
            5 -> collection("nativeTypeSystem.loader", "JitConnector.start", "String", "Class<?>", "start", "loader view")
            else -> collection("typeSystem.loadedClasses", "JitConnector.invoke0Impl", "String", "ClassModel", "invoke", "dump traversal")
        }
    }

    private fun collection(
        collectionName: String,
        owner: String,
        keyType: String,
        valueType: String,
        lifecyclePhase: String,
        sourceMember: String,
        cardinality: Int = -1,
    ): JitCollectionFacet = JitCollectionFacet(
        collectionName = Symbols.symbol(collectionName),
        owner = Symbols.symbol(owner),
        keyType = Symbols.symbol(keyType),
        valueType = Symbols.symbol(valueType),
        cardinality = cardinality,
        lifecyclePhase = Symbols.symbol(lifecyclePhase),
        sourceMember = Symbols.symbol(sourceMember),
    )

}

object CrmsColumnMetaGenerator {
    private val refs = columns(
        column(0, Symbols.DomainId, Symbols.IntType, PointcutFacet.CrmsDomain),
        column(1, Symbols.DomainName, Symbols.StringType, PointcutFacet.CrmsDomain),
        column(2, Symbols.FacetId, Symbols.IntType, PointcutFacet.CrmsDomain),
        column(3, Symbols.ColumnCount, Symbols.IntType, PointcutFacet.CrmsDomain),
        column(4, Symbols.Columns, Symbols.ArrayType, PointcutFacet.ChildRows),
        column(5, Symbols.Children, Symbols.ArrayType, PointcutFacet.ChildRows),
    )

    private val domainShape = RowShape<CrmsDomain>(refs) { domain, ordinal ->
        when (ordinal) {
            0 -> domain.id.raw
            1 -> domain.name.id.raw
            2 -> Symbols.symbol(domain.facet.name).id.raw
            3 -> domain.columns.a
            4 -> columnCursor(domain.columns)
            else -> cursor(domain.children)
        }
    }

    fun columnRefs(): Series<ColumnMetaRef> = refs(refs)

    fun domain(name: String, facet: PointcutFacet, columns: Series<ColumnMetaRef>): CrmsDomain =
        CrmsDomain(Symbols.symbol(name), facet, columns)

    fun domain(
        name: String,
        facet: PointcutFacet,
        columns: Series<ColumnMetaRef>,
        children: Series<CrmsDomain>,
    ): CrmsDomain = CrmsDomain(Symbols.symbol(name), facet, columns, children)

    fun rowVec(domain: CrmsDomain): RowVec = domainShape.rowVec(domain)

    fun cursor(vararg domains: CrmsDomain): Cursor = cursor(domains, ::rowVec)

    fun cursor(domains: Series<CrmsDomain>): Cursor = cursor(domains, ::rowVec)

    private fun columnCursor(columns: Series<ColumnMetaRef>): Cursor = rowSeries(columns.a) { index ->
        val ref = columns.b(index)
        rowVec(4) { ordinal ->
            val value: Any? = when (ordinal) {
                0 -> ref.ordinal
                1 -> StringPool.intern(ref.name)
                2 -> StringPool.intern(ref.typeName)
                else -> StringPool.intern(ref.facet.name)
            }
            val meta = when (ordinal) {
                0 -> ColumnMeta(Symbols.Ordinal.value, IOMemento.IoInt)
                1 -> ColumnMeta(Symbols.Name.value, IOMemento.IoInt)
                2 -> ColumnMeta(Symbols.Type.value, IOMemento.IoInt)
                else -> ColumnMeta(Symbols.Facet.value, IOMemento.IoInt)
            }
            rowCell(value, meta)
        }
    }

}

object SyntheticPointcutRowVecFactory {
    private val refs = columns(
        column(0, Symbols.SymbolName, Symbols.StringType, PointcutFacet.SymbolName),
        column(1, Symbols.TypeInfo, Symbols.StringType, PointcutFacet.TypeInfo),
        column(2, Symbols.ClassfileCoordinate, Symbols.StringType, PointcutFacet.ClassfileCoordinate),
        column(3, Symbols.XvmCoordinate, Symbols.StringType, PointcutFacet.XvmCoordinate),
        column(4, Symbols.PointcutKind, Symbols.StringType, PointcutFacet.PointcutKind),
        column(5, Symbols.PoolId, Symbols.IntType, PointcutFacet.StringPool),
        column(6, Symbols.CodecHash, Symbols.LongType, PointcutFacet.StringPool),
        column(7, Symbols.Wireproto, Symbols.BytesType, PointcutFacet.Wireproto),
    )

    private val classfileDecorations = refs + columns(
        column(8, Symbols.symbol("classfileTaxonomy"), Symbols.StringType, PointcutFacet.ClassfileTaxonomy),
        column(9, Symbols.symbol("confixMeta"), Symbols.StringType, PointcutFacet.ConfixMeta),
    )

    private val edgeDecorations = refs + columns(
        column(8, Symbols.symbol("edgeTaxonomy"), Symbols.StringType, PointcutFacet.EdgeTaxonomy),
        column(9, Symbols.symbol("confixMeta"), Symbols.StringType, PointcutFacet.ConfixMeta),
    )

    private val pointcutShape = RowShape<SyntheticPointcutSymbolRow>(refs) { row, ordinal ->
        when (ordinal) {
            0 -> row.symbolName.id.raw
            1 -> row.typeInfo.id.raw
            2 -> row.classfileCoordinate.id.raw
            3 -> row.xvmCoordinate.id.raw
            4 -> row.pointcutName.symbol.id.raw
            5 -> row.poolId
            6 -> row.codecHash
            else -> MemSegment(row.wireproto)
        }
    }

    fun columnRefs(): Series<ColumnMetaRef> = refs(refs)

    fun classfileFacetRefs(): Series<ColumnMetaRef> = series(classfileDecorations.size) { index ->
        classfileDecorations[index]
    }

    fun edgeFacetRefs(): Series<ColumnMetaRef> = series(edgeDecorations.size) { index -> edgeDecorations[index] }

    fun columnMeta(ref: ColumnMetaRef): ColumnMeta = ColumnMeta(ref.name, ref.typeMemento())

    fun rowVec(row: SyntheticPointcutRow): RowVec = rowVec(row.symbolic())

    fun rowVec(row: SyntheticPointcutSymbolRow): RowVec = pointcutShape.rowVec(row)

    fun cursor(vararg rows: SyntheticPointcutRow): Cursor = cursor(rows) { row -> rowVec(row) }

    fun cursor(rows: Series<SyntheticPointcutRow>): Cursor = cursor(rows) { row -> rowVec(row) }

    fun symbolCursor(vararg rows: SyntheticPointcutSymbolRow): Cursor = cursor(rows, ::rowVec)
}


private class RowShape<T>(
    private val refs: Array<ColumnMetaRef>,
    private val valueAt: (T, Int) -> Any?,
) {
    fun rowVec(row: T): RowVec = rowVec(refs.size) { ordinal ->
        val ref = refs[ordinal]
        rowCell(valueAt(row, ordinal), SyntheticPointcutRowVecFactory.columnMeta(ref))
    }
}

private fun columns(vararg refs: ColumnMetaRef): Array<ColumnMetaRef> = arrayOf(*refs)

private fun column(ordinal: Int, name: SymbolRef, type: SymbolRef, facet: PointcutFacet): ColumnMetaRef =
    ColumnMetaRef(ordinal, name.value, type.value, facet)

private fun refs(refs: Array<ColumnMetaRef>): Series<ColumnMetaRef> = series(refs.size) { index -> refs[index] }

private fun <T> cursor(rows: Series<T>, rowAt: (T) -> RowVec): Cursor = rowSeries(rows.a) { index -> rowAt(rows.b(index)) }

private fun <T> cursor(rows: Array<out T>, rowAt: (T) -> RowVec): Cursor = rowSeries(rows.size) { index -> rowAt(rows[index]) }

private data class SyntheticJoin<A, B>(
    override val a: A,
    override val b: B,
) : Join<A, B>

private fun <T> series(size: Int, get: (Int) -> T): Series<T> = SyntheticJoin(size, get)

private fun rowSeries(size: Int, get: (Int) -> RowVec): Cursor = SyntheticJoin(size, get)

private fun rowVec(size: Int, get: (Int) -> Join<Any?, `ColumnMeta↻`>): RowVec = SyntheticJoin(size, get)

private fun rowCell(value: Any?, meta: ColumnMeta): Join<Any?, `ColumnMeta↻`> = SyntheticJoin(value) { meta }

private fun ColumnMetaRef.typeMemento(): TypeMemento = when (typeName) {
    Symbols.IntType.value -> IOMemento.IoInt
    Symbols.LongType.value -> IOMemento.IoLong
    Symbols.BytesType.value -> IOMemento.IoBytes
    Symbols.ArrayType.value -> IOMemento.IoArray
    else -> IOMemento.IoString
}
