package org.xvm.cursor

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/** A single RowVec cell: Join<Any?, () -> ColumnMeta>. */
typealias RowVecCell = Join<Any?, () -> ColumnMeta>

/**
 * Reflective facet derivation.
 *
 * Facet assignment by property name + type convention:
 *   String   + *Name      -> SymbolName
 *   String   + *Info      -> TypeInfo
 *   String   + source*    -> XSrcFile
 *   String   + *Path      -> XSrcFile
 *   String   + *Pattern   -> XSrcFile
 *   Int      + *Coordinate -> ClassfileCoordinate
 *   Int      + xvm*       -> XvmCoordinate
 *   Int      + *Id        -> StringPool
 *   Int      + opcode*    -> PointcutKind
 *   Long     + nano*      -> VmStats
 *   Lazy<*>               -> ChildRows
 *   Cursor                -> ChildRows
 *   MemSegment            -> Wireproto
 *   else                  -> Unfaceted
 */
object ReflectiveFacet {

    /** Derive facet from property name and Kotlin class. */
    fun deriveFacet(name: String, klass: KClass<*>): PointcutFacet = when {
        klass == MemSegment::class -> PointcutFacet.Wireproto
        klass.java.name.contains("Lazy") || klass == Cursor::class -> PointcutFacet.ChildRows
        klass == Long::class && name.startsWith("nano") -> PointcutFacet.VmStats
        klass == Int::class && name.startsWith("opcode") -> PointcutFacet.PointcutKind
        klass == Int::class && name.endsWith("Id") -> PointcutFacet.StringPool
        klass == Int::class && name.startsWith("xvm") -> PointcutFacet.XvmCoordinate
        klass == Int::class && name.endsWith("Coordinate") -> PointcutFacet.ClassfileCoordinate
        (klass == String::class || klass == Int::class) && name.endsWith("Name") -> PointcutFacet.SymbolName
        klass == String::class && (name.startsWith("source") || name.endsWith("Path") ||
                name.endsWith("Pattern")) -> PointcutFacet.XSrcFile
        (klass == String::class || klass == Int::class) && (name.endsWith("Info") || name.endsWith("Type")) -> PointcutFacet.TypeInfo
        else -> PointcutFacet.Unfaceted
    }

    inline fun <reified T : Any> deriveRefs(): Series<ColumnMetaRef> = deriveRefs(T::class)

    fun <T : Any> deriveRefs(klass: KClass<T>): Series<ColumnMetaRef> {
        val props = declaredProps(klass)
        return props.size j { idx: Int ->
            val prop = props[idx]
            ColumnMetaRef(
                ordinal = idx,
                name = prop.name,
                typeName = prop.returnType.toString(),
                facet = facetFor(prop),
            )
        }
    }

    fun <T : Any> toRowVec(bean: T): RowVec {
        val klass = bean::class
        val props = declaredProps(klass)
        return props.size j { col: Int ->
            @Suppress("UNCHECKED_CAST")
            val prop = props[col] as KProperty1<T, Any?>
            val ref = ColumnMetaRef(
                ordinal = col,
                name = prop.name,
                typeName = prop.returnType.toString(),
                facet = facetFor(prop),
            )
            val value = prop.get(bean)
            cell(value, ref)
        }
    }

    inline fun <reified T : Any> toCursor(beans: Series<T>): Cursor = CrmsDomainCursor.from(beans)

    // ── facet dispatch via KProperty ──────────────────────────────────────

    private fun facetFor(prop: KProperty1<*, *>): PointcutFacet {
        val name = prop.name
        val retType = prop.returnType.toString()
        val classifier = prop.returnType.classifier

        // Lazy<Cursor> -> ChildRows
        if (retType.contains("Lazy") && retType.contains("Cursor")) return PointcutFacet.ChildRows
        if (retType.contains("Lazy")) return PointcutFacet.ChildRows

        val klass: KClass<*> = (classifier as? KClass<*>) ?: return PointcutFacet.Unfaceted
        return deriveFacet(name, klass)
    }
}

// ── cell helper ───────────────────────────────────────────────────────────────

internal fun cell(value: Any?, ref: ColumnMetaRef): RowVecCell = value j { ref }

/**
 * Return member properties in declaration (primary constructor parameter) order.
 * Falls back to memberProperties natural order for non-data classes.
 */
internal fun <T : Any> declaredProps(klass: KClass<T>): List<KProperty1<T, *>> {
    val ctor = klass.primaryConstructor
    if (ctor != null) {
        val byName = klass.memberProperties.associateBy { it.name }
        return ctor.parameters.mapNotNull { byName[it.name] }
    }
    @Suppress("UNCHECKED_CAST")
    return klass.memberProperties.toList()
}
