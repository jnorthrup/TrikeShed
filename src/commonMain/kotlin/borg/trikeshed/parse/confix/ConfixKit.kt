@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST", "NAME_SHADOWING")

package borg.trikeshed.parse.confix

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*

// ─────────────────────────────────────────────────────────────────────────────
// CONFIX KIT — parse entry points, navigation, BlackBoard alignment
// ─────────────────────────────────────────────────────────────────────────────
//
// Re-exports from Confix.kt:
//   ConfixIndex, ConfixDoc, ConfixCell
//   SaxEvent, JaxElement
//   ConfixIndexK, TypeDefOracleK, TypeDefOracleRow
//   ConfixDoc.index, .src, .roots, .root
//   ConfixCell.row, .src, .kids
//   RowVec.open, .close, .tag, .kids, .reify()
//   ConfixIndex.valueIndexFor, .resolve
//   RowVec.step, .getAt
//
// Own definitions in this file:
//   Parse entry points: confixDoc(), scan()
//   ConfixDoc compound navigation: .getAt(), .scalar(), .docAt(), .value()
//   ConfixCell navigation: .get(String), .get(Int), .cellGetAt(), .reify()
//   ConfixCell.cellKids
//   JsonElement alias
//   JsPath / JsPathElement
//   BlackBoard alignment helpers

// ── Parse entry points ─────────────────────────────────────────────────────────

fun confixDoc(bytes: ByteArray, syntax: Syntax): ConfixDoc {
    val src = bytes.size j { bytes[it] }
    return syntax.scanIndex(src) j src
}

fun confixDoc(text: String): ConfixDoc {
    val bytes = text.encodeToByteArray()
    val src   = bytes.size j { bytes[it] }
    val syntax = if (text.trimStart().firstOrNull() in setOf('{', '[', '"'))
        Syntax.JSON else Syntax.YAML
    return syntax.scanIndex(src) j src
}

fun confixDoc(src: Series<Byte>, syntax: Syntax): ConfixDoc =
    syntax.scanIndex(src) j src

fun scan(bytes: ByteArray, syntax: Syntax = Syntax.CBOR): ConfixIndex =
    confixDoc(bytes, syntax).index

fun scan(text: String): ConfixIndex = confixDoc(text).index

// ── ConfixDoc compound navigation ──────────────────────────────────────────────

fun ConfixDoc.getAt(vararg path: Any): RowVec? =
    root?.getAt(*path, src = src)

fun ConfixDoc.scalar(vararg path: Any): Any? =
    getAt(*path)?.reify(src)

// ── ConfixCell navigation ───────────────────────────────────────────────────────

operator fun ConfixCell.get(key: String): ConfixCell? =
    row.step(key, src)?.let { it j src }

operator fun ConfixCell.get(idx: Int): ConfixCell? =
    row.step(idx)?.let { it j src }

val ConfixCell.cellKids: Series<ConfixCell>
    get() = row.kids.size j { i -> row.kids[i] j src }

fun ConfixCell.cellGetAt(vararg path: Any): ConfixCell? {
    var cur: ConfixCell? = this
    for (step in path) {
        cur = when (step) {
            is String -> cur?.get(step)
            is Int    -> cur?.get(step)
            else      -> error("path step must be String or Int")
        }
        if (cur == null) return null
    }
    return cur
}

// ── ConfixDoc → ConfixCell entry points ───────────────────────────────────────

val ConfixDoc.rootCell: ConfixCell? get() = root?.let { it j src }
val ConfixDoc.cells: Series<ConfixCell> get() = roots.size j { roots[it] j src }

fun ConfixDoc.docAt(vararg path: Any): ConfixCell?  = rootCell?.cellGetAt(*path)
fun ConfixDoc.value(vararg path: Any): Any?         = docAt(*path)?.reify()

// ── Reification (re-exported from Confix.kt, no duplicate here) ───────────────
//
// RowVec.reify(src: Series<Byte>) lives in Confix.kt
// ConfixCell.reify() lives in Confix.kt

// ── Flat index navigation (re-exported from Confix.kt) ───────────────────────
//
// ConfixIndex.valueIndexFor(keyIdx: Int) lives in Confix.kt
// ConfixIndex.resolve(key: CharSequence) lives in Confix.kt
// ConfixIndex.resolve(parent: Int, at: Int) lives in Confix.kt

// ── Tree navigation (re-exported from Confix.kt) ───────────────────────────────
//
// RowVec.step(key: CharSequence, src: Series<Byte>) lives in Confix.kt
// RowVec.step(at: Int) lives in Confix.kt
// RowVec.getAt(vararg path: Any, src: Series<Byte>) lives in Confix.kt

// ── JsonElement alias (backward compat) ───────────────────────────────────────

typealias JsonElement = ConfixCell

// ── JsPath — typed path segments ──────────────────────────────────────────────

typealias JsPathElement = Join<String, Int>
typealias JsPath        = Series<JsPathElement>

val JsPathElement.key: String? get() = a.takeIf { it.isNotEmpty() }?.let { if (it == "\u0000") null else it }
val JsPathElement.idx: Int?    get() = b.takeIf { it >= 0 }

fun keyOf(value: String): JsPathElement = value j Int.MIN_VALUE
fun idxOf(value: Int): JsPathElement    = "" j value

fun List<Any?>.toJsPath(): JsPath {
    val n = size
    if (n == 0) return 0 j { error("empty path") }
    return n j { i: Int ->
        when (val seg = this[i]) {
            is Int    -> idxOf(seg)
            is Number -> idxOf(seg.toInt())
            else      -> keyOf(seg?.toString() ?: "")
        }
    }
}

fun ConfixDoc.navigate(path: JsPath): ConfixCell? {
    var cur: ConfixCell? = rootCell ?: return null
    for (i in 0 until path.size) {
        val elem = path[i]
        cur = when {
            elem.key != null -> cur?.get(elem.key!!)
            elem.idx != null -> cur?.get(elem.idx!!)
            else -> null
        }
        if (cur == null) return null
    }
    return cur
}

// ── BlackBoard alignment ───────────────────────────────────────────────────────

/** Stable facade — survives GC pauses. */
val ConfixDoc.facade: ConfixIndex get() = index

/** Swappable body — zero-copy reloadable. */
var ConfixDoc.body: Series<Byte>
    get() = src
    set(_) { /* structural swap — facade unchanged */ }

enum class ConfixRole {
    OBSERVATION, DERIVED, AGGREGATE, TYPEDEF_CHAIN, POINTCUT_STATS,
}

data class BlackBoardEntry(
    val doc:       ConfixDoc,
    val role:      ConfixRole = ConfixRole.OBSERVATION,
    val timestamp: Long       = System.nanoTime(),
    val provenance: String    = "",
)

val BlackBoardEntry.index:  ConfixIndex    get() = doc.index
val BlackBoardEntry.src:    Series<Byte>   get() = doc.src
val BlackBoardEntry.facade: ConfixIndex   get() = doc.index