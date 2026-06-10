package org.xvm.activejs

import org.xvm.cursor.PointcutFacet as JvmPointcutFacet
import org.xvm.cursor.TypedefResolutionSeries as JvmTypedefResolutionSeries
import org.xvm.cursor.ClassFileTaxonomy as JvmClassFileTaxonomy
import org.xvm.cursor.Cursor as JvmCursor
import org.xvm.cursor.RowVec as JvmRowVec
import org.xvm.cursor.ColumnMeta as JvmColumnMeta
import org.xvm.cursor.ColumnMetaRef as JvmColumnMetaRef
import borg.trikeshed.cursor.IOMemento as JvmIOMemento
import borg.trikeshed.cursor.TypeMemento as JvmTypeMemento
import borg.trikeshed.parse.confix.BlackBoardEntry as JvmBlackBoardEntry
import borg.trikeshed.parse.confix.ConfixRole as JvmConfixRole
import borg.trikeshed.parse.confix.SaxEvent as JvmSaxEvent
import borg.trikeshed.parse.confix.confixDoc as JvmConfixDoc
import borg.trikeshed.parse.confix.saxWalk as JvmSaxWalk
import borg.trikeshed.lib.MutableSeries as JvmMutableSeries
import borg.trikeshed.lib.ChunkedMutableSeries as JvmChunkedMutableSeries
import borg.trikeshed.lib.Series as JvmSeries
import borg.trikeshed.lib.Join as JvmJoin
import borg.trikeshed.lib.j as jvmJ
import borg.trikeshed.lib.liveSeries as jvmLiveSeries

// ── MutableSeries / ChunkedMutableSeries ────────────────────────────────────

actual class MutableSeries<T> private constructor(private val delegate: JvmMutableSeries<T>) {
    actual fun add(element: T) = delegate.add(element)
    actual val a: Int get() = delegate.a
    actual val b: (Int) -> T get() = delegate.b

    companion object {
        operator fun invoke<T>(): MutableSeries<T> = MutableSeries(JvmChunkedMutableSeries<T>() as JvmMutableSeries<T>)
    }
}

actual class ChunkedMutableSeries<T> : MutableSeries<T>(JvmChunkedMutableSeries<T>())

// ── ColumnMeta / TypeMemento ────────────────────────────────────────────────

actual class ColumnMeta(
    name: String,
    memento: Any,
) {
    actual val a: CharSequence get() = name
    actual val b: Join<TypeMemento, ColumnMeta?> get() = 0 j null
}

actual class TypeMemento {
    companion object {
        val IoInt: TypeMemento = JvmTypeMemento.IoInt as TypeMemento
        val IoLong: TypeMemento = JvmTypeMemento.IoLong as TypeMemento
        val IoString: TypeMemento = JvmTypeMemento.IoString as TypeMemento
        val IoObject: TypeMemento = JvmTypeMemento.IoObject as TypeMemento
        val IoArray: TypeMemento = JvmTypeMemento.IoArray as TypeMemento
    }
}

// ── Join factory / Join class ───────────────────────────────────────────────

actual fun <A, B> j(a: A, b: B): Join<A, B> = jvmJ(a, b)

actual fun <T> Join(count: Int, access: (Int) -> T): Series<T> {
    return Series(JvmSeries(count, access))
}

actual class Join<A, B>(val a: A, val b: B)

// ── ColumnMetaRef ───────────────────────────────────────────────────────────

actual class ColumnMetaRef(
    actual override val ordinal: Int,
    actual override val name: String,
    actual override val typeName: String,
    actual override val facet: PointcutFacet,
) {
    var activeJsFacet: ActiveJsFacet = ActiveJsFacet.Unfaceted

    companion object {
        fun fromJvm(jvm: JvmColumnMetaRef): ColumnMetaRef {
            val activeFacet = when (jvm.facet) {
                JvmPointcutFacet.SymbolName -> ActiveJsFacet.JsFunction
                JvmPointcutFacet.TypeInfo -> ActiveJsFacet.JsModule
                JvmPointcutFacet.ClassfileCoordinate -> ActiveJsFacet.WasmModule
                JvmPointcutFacet.XvmCoordinate -> ActiveJsFacet.JsObject
                JvmPointcutFacet.PointcutKind -> ActiveJsFacet.JsPromise
                JvmPointcutFacet.StringPool -> ActiveJsFacet.JsTypedArray
                else -> ActiveJsFacet.Unfaceted
            }
            return ColumnMetaRef(jvm.ordinal, jvm.name, jvm.typeName, PointcutFacet.valueOf(jvm.facet.name)).also {
                it.activeJsFacet = activeFacet
            }
        }
    }
}

// ── PointcutFacet ───────────────────────────────────────────────────────────

actual enum class PointcutFacet {
    Unfaceted,
    SymbolName,
    TypeInfo,
    ClassfileCoordinate,
    XvmCoordinate,
    PointcutKind,
    StringPool,
    Wireproto,
    ChildRows,
    ConfixMeta,
    VmStats,
    ObserverDelegateRegistration,
    ReduxPhilum,
    SynapsePhilum,
    ClassfileTaxonomy,
    EdgeTaxonomy,
    CrmsDomain,
    XSrcFile,
}

// ── TypedefResolutionSeries ─────────────────────────────────────────────────

actual object TypedefResolutionSeries {
    actual fun record(poolId: Int, siteOrd: Int, className: String, coordination: String, success: Boolean): Long {
        return JvmTypedefResolutionSeries.record(poolId, siteOrd, className, coordination, success)
    }
    actual fun size(): Int = JvmTypedefResolutionSeries.size()
    actual fun reset() = JvmTypedefResolutionSeries.reset()
}

// ── ClassFileTaxonomy ───────────────────────────────────────────────────────

actual class ClassFileTaxonomy {
    private val delegate = JvmClassFileTaxonomy()

    actual val size: Int get() = delegate.size

    actual fun register(row: Any) {
        delegate.register(row as JvmClassFileTaxonomy.CoordinateRow)
    }

    actual fun rowAt(index: Int): Any = delegate.rowAt(index)

    actual fun lookupByPoolId(poolId: Int): Any? = delegate.lookupByPoolId(poolId)

    actual fun filterByKind(kind: Int): ClassFileTaxonomy {
        val wrapped = ClassFileTaxonomy()
        wrapped.delegate = delegate.filterByKind(kind)
        return wrapped
    }

    actual fun filterByOwner(owner: String): ClassFileTaxonomy {
        val wrapped = ClassFileTaxonomy()
        wrapped.delegate = delegate.filterByOwner(owner)
        return wrapped
    }

    actual fun asCursor(): Cursor = Cursor(delegate.asCursor())

    actual fun toBlackboardEntries(): List<BlackBoardEntry> =
        delegate.toBlackboardEntries().map { BlackBoardEntry(it) }

    actual fun emitSaxEvents(consumer: (SaxEvent) -> Unit) {
        delegate.emitSaxEvents { sax -> consumer(SaxEvent.fromJvm(sax)) }
    }
}

// ── Cursor ──────────────────────────────────────────────────────────────────

actual class Cursor(private val delegate: JvmCursor) {
    actual val size: Int get() = delegate.size
    actual operator fun get(index: Int): RowVec = RowVec(delegate.b(index))
    actual fun columnMeta(name: String): ColumnMetaRef = ColumnMetaRef.fromJvm(delegate.columnMeta(name))
}

// ── RowVec ──────────────────────────────────────────────────────────────────

actual class RowVec(private val delegate: JvmRowVec) {
    actual val a: Int get() = delegate.a
    actual val b: (Int) -> Any? get() = { delegate.b(it).a }
}

// ── BlackBoardEntry ─────────────────────────────────────────────────────────

actual class BlackBoardEntry(private val delegate: JvmBlackBoardEntry) {
    actual val doc: Any get() = delegate.doc
    actual val role: ConfixRole get() = ConfixRole.valueOf(delegate.role.name)
}

// ── ConfixRole ──────────────────────────────────────────────────────────────

actual enum class ConfixRole {
    OBSERVATION,
    COMMAND,
    QUERY,
}

// ── SaxEvent ─────────────────────────────────────────────────────────────────

actual sealed class SaxEvent {
    data class Enter(actual val memento: Any, actual val offset: Int) : SaxEvent()
    data class Leave(actual val memento: Any, actual val offset: Int) : SaxEvent()

    companion object {
        fun fromJvm(jvm: JvmSaxEvent): SaxEvent = when (jvm) {
            is JvmSaxEvent.Enter -> Enter(jvm.a, jvm.b)
            is JvmSaxEvent.Leave -> Leave(jvm.a, jvm.b)
        }
    }
}

// ── IOMemento ────────────────────────────────────────────────────────────────

actual object IOMemento {
    actual val IoInt: Any get() = JvmIOMemento.IoInt
    actual val IoLong: Any get() = JvmIOMemento.IoLong
    actual val IoString: Any get() = JvmIOMemento.IoString
    actual val IoObject: Any get() = JvmIOMemento.IoObject
    actual val IoArray: Any get() = JvmIOMemento.IoArray
}

// ── confixDoc / saxWalk / liveSeries / Series ───────────────────────────────

actual fun confixDoc(json: String): Any = JvmConfixDoc(json)

actual fun Any.saxWalk(action: (SaxEvent) -> Unit) {
    (this as org.xvm.cursor.borg.trikeshed.parse.confix.ConfixDoc).a.saxWalk { jse ->
        action(SaxEvent.fromJvm(jse))
    }
}

actual fun <T> liveSeries(count: () -> Int, access: (Int) -> T): Series<T> {
    return Series(jvmLiveSeries(count, access))
}

// Wrapper for JVM Series
actual class Series<T>(private val delegate: JvmSeries<T>) {
    actual val a: Int get() = delegate.a
    actual val b: (Int) -> T get() = delegate.b
}