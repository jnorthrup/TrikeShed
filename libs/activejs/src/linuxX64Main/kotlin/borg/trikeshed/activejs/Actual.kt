package borg.trikeshed.activejs

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

// ── MutableSeries / ChunkedMutableSeries ────────────────────────────────────

actual class MutableSeries<T> {
    private val storage = mutableListOf<T>()
    actual fun add(element: T) { storage.add(element) }
    actual val a: Int get() = storage.size
    actual val b: (Int) -> T get() = { storage[it] }
}

actual class ChunkedMutableSeries<T> : MutableSeries<T>()

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
        val IoInt: TypeMemento = TypeMemento()
        val IoLong: TypeMemento = TypeMemento()
        val IoString: TypeMemento = TypeMemento()
        val IoObject: TypeMemento = TypeMemento()
        val IoArray: TypeMemento = TypeMemento()
    }
}

// ── Join factory / Join class ───────────────────────────────────────────────

actual fun <A, B> j(a: A, b: B): Join<A, B> = Join(a, b)

actual fun <T> Join(count: Int, access: (Int) -> T): Series<T> {
    return Series(count, access)
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
}

// ── PointcutFacet ───────────────────────────────────────────────────────────

actual enum class PointcutFacet {
    Unfaceted,
    SymbolName,
    TypeInfo,
    ClassfileCoordinate,
    TypeCoordinate,
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
    SrcFile,
}

// ── TypedefResolutionSeries ─────────────────────────────────────────────────

actual object TypedefResolutionSeries {
    private var factCount = 0
    actual fun record(poolId: Int, siteOrd: Int, className: String, coordination: String, success: Boolean): Long {
        factCount++
        return factCount.toLong()
    }
    actual fun size(): Int = factCount
    actual fun reset() { factCount = 0 }
}

// ── ClassFileTaxonomy (Native implementation - pure in-memory) ──────────────

actual class ClassFileTaxonomy {
    private val rows = mutableListOf<CoordinateRow>()

    actual val size: Int get() = rows.size

    actual fun register(row: Any) {
        rows.add(row as CoordinateRow)
    }

    actual fun rowAt(index: Int): Any = rows[index]

    actual fun lookupByPoolId(poolId: Int): Any? = rows.firstOrNull { it.poolId == poolId }

    actual fun filterByKind(kind: Int): ClassFileTaxonomy {
        val wrapped = ClassFileTaxonomy()
        wrapped.rows.addAll(rows.filter { it.pointcutKind == kind })
        return wrapped
    }

    actual fun filterByOwner(owner: String): ClassFileTaxonomy {
        val wrapped = ClassFileTaxonomy()
        wrapped.rows.addAll(rows.filter { it.ownerType == owner })
        return wrapped
    }

    actual fun asCursor(): Cursor = Cursor(rows)

    actual fun toBlackboardEntries(): List<BlackBoardEntry> = rows.map { row ->
        val json = """
            {
              "symbolName": "${row.symbolName}",
              "ownerType": "${row.ownerType}",
              "methodOrField": "${row.methodOrField}",
              "classfileCoord": "${row.classfileCoord}",
              "cpIndex": ${row.cpIndex},
              "descriptor": "${row.descriptor}",
              "typeInfo": "${row.typeInfo}",
              "pointcutKind": ${row.pointcutKind},
              "poolId": ${row.poolId},
              "activeJsFacet": "${row.activeJsFacet}"
            }
        """.trimIndent()
        BlackBoardEntry(ConfixDoc(json), ConfixRole.OBSERVATION)
    }

    actual fun emitSaxEvents(consumer: (SaxEvent) -> Unit) {
        toBlackboardEntries().forEach { entry ->
            entry.doc.saxWalk(consumer)
        }
    }
}

// ── Cursor ──────────────────────────────────────────────────────────────────

actual class Cursor(private val rows: List<CoordinateRow>) {
    actual val size: Int get() = rows.size
    actual operator fun get(index: Int): RowVec = RowVec(rows[index])
    actual fun columnMeta(name: String): ColumnMetaRef {
        return ColumnMetaRef(0, name, "String", PointcutFacet.Unfaceted)
    }
}

// ── RowVec ──────────────────────────────────────────────────────────────────

actual class RowVec(private val row: CoordinateRow) {
    actual val a: Int get() = 14
    actual val b: (Int) -> Any? get() = { index ->
        when (index) {
            0 -> row.symbolName
            1 -> row.ownerType
            2 -> row.methodOrField
            3 -> row.classfileCoord
            4 -> row.cpIndex
            5 -> row.descriptor
            6 -> row.typeInfo
            7 -> row.pointcutKind
            8 -> row.poolId
            9 -> row.activeJsFacet.name
            else -> null
        }
    }
}

// ── BlackBoardEntry / ConfixDoc / SaxEvent ──────────────────────────────────

actual class BlackBoardEntry(
    actual val doc: ConfixDoc,
    actual val role: ConfixRole,
)

actual enum class ConfixRole {
    OBSERVATION, COMMAND, QUERY
}

actual sealed class SaxEvent {
    data class Enter(actual val memento: Any, actual val offset: Int) : SaxEvent()
    data class Leave(actual val memento: Any, actual val offset: Int) : SaxEvent()
}

actual class ConfixDoc(private val json: String) {
    fun saxWalk(action: (SaxEvent) -> Unit) {
        action(SaxEvent.Enter(IOMemento.IoObject, 0))
        action(SaxEvent.Leave(IOMemento.IoObject, 1))
    }
}

// ── IOMemento ────────────────────────────────────────────────────────────────

actual object IOMemento {
    actual val IoInt: Any get() = "IoInt"
    actual val IoLong: Any get() = "IoLong"
    actual val IoString: Any get() = "IoString"
    actual val IoObject: Any get() = "IoObject"
    actual val IoArray: Any get() = "IoArray"
}

// ── confixDoc / liveSeries / Series ─────────────────────────────────────────

actual fun confixDoc(json: String): ConfixDoc = ConfixDoc(json)

actual fun <T> liveSeries(count: () -> Int, access: (Int) -> T): Series<T> {
    return Series(count, access)
}

actual class Series<T>(
    private val countFn: () -> Int,
    private val accessFn: (Int) -> T,
) {
    actual val a: Int get() = countFn()
    actual val b: (Int) -> T get() = accessFn
}