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

// ── Core Type Aliases (ConfixCell / ConfixDoc) ──────────────────────────────────

typealias ConfixCell = Join<RowVec, Series<Byte>>
val ConfixCell.row: RowVec       get() = a
val ConfixCell.src: Series<Byte> @kotlin.jvm.JvmName("getConfixCellSrc") get() = b

fun ConfixCell.reify(): Any? = row.reify(src)

typealias ConfixDoc = Join<ConfixIndex, Series<Byte>>
val ConfixDoc.index: ConfixIndex get() = a
val ConfixDoc.src: Series<Byte>  @kotlin.jvm.JvmName("getConfixDocSrc") get() = b
val ConfixDoc.roots: Cursor get() = index.facet(ConfixIndexK.TreeCursor)
val ConfixDoc.root: RowVec? get() = if (roots.size > 0) roots[0] else null

// ── Parse entry points ─────────────────────────────────────────────────────────

fun confixDoc(bytes: ByteArray, syntax: Syntax): ConfixDoc {
    val src = bytes.size j { i: Int -> bytes[i] }
    return syntax.scanIndex(src) j src
}

fun confixDoc(text: String): ConfixDoc {
    val bytes = text.encodeToByteArray()
    val src   = bytes.size j { i: Int -> bytes[i] }
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

fun RowVec.reify(src: Series<Byte>): Any? = when (tag) {
    borg.trikeshed.cursor.IOMemento.IoNothing             -> null
    borg.trikeshed.cursor.IOMemento.IoBoolean             -> src[open].toInt().toChar() == 't'
    borg.trikeshed.cursor.IOMemento.IoDouble              -> src.spanStr(open, close).toDoubleOrNull()
    borg.trikeshed.cursor.IOMemento.IoInt                 -> src.spanLong(open, close).toInt()
    borg.trikeshed.cursor.IOMemento.IoLong                -> src.spanLong(open, close)
    borg.trikeshed.cursor.IOMemento.IoString              -> src.spanStr(open + 1, close - 1) // strip quotes
    borg.trikeshed.cursor.IOMemento.IoBytes               -> ByteArray(close - open + 1) { src[open + it] }
    borg.trikeshed.cursor.IOMemento.IoObject,
    borg.trikeshed.cursor.IOMemento.IoArray               -> kids
    else                            -> src.spanStr(open, close)
}

fun ConfixDoc.reify(tokenIdx: Int): Any? =
    index.facet(ConfixIndexK.TreeCursor)[tokenIdx].reify(src)

// ── Flat index navigation (re-exported from Confix.kt) ───────────────────────

fun ConfixIndex.valueIndexFor(keyTokenIdx: Int): Int? {
    val depths = facet(ConfixIndexK.Depths)
    val d = depths[keyTokenIdx]
    val total = depths.size
    for (i in keyTokenIdx + 1 until total)
        if (depths[i] == d) return i
    return null
}

fun ConfixIndex.resolve(key: CharSequence): Int? =
    facet(ConfixIndexK.KeyToChild)(key)?.let { valueIndexFor(it) }

fun ConfixIndex.resolve(parentTokenIdx: Int, arrayIdx: Int): Int? {
    val ch = facet(ConfixIndexK.DirectChildren)(parentTokenIdx)
    return if (arrayIdx < ch.size) ch[arrayIdx] else null
}

// ── Tree navigation (re-exported from Confix.kt) ───────────────────────────────

fun RowVec.step(key: CharSequence, src: Series<Byte>): RowVec? {
    val ch = kids
    // Confix flat-kid order: (value, key) pairs — keys follow values, not precede them.
    var i = 0
    while (i + 1 < ch.size) {
        val v = ch[i]
        val k = ch[i + 1]
        if (k.tag == borg.trikeshed.cursor.IOMemento.IoString) {
            val kOpen  = k.open  + 1
            val kClose = k.close - 1
            val kLen   = kClose - kOpen + 1
            if (kLen == key.length) {
                var match = true
                for (d in 0 until kLen)
                    if (src[kOpen + d].toInt().toChar() != key[d]) { match = false; break }
                if (match) return v
            }
        }
        i += 2
    }
    return null
}

fun RowVec.step(arrayIdx: Int): RowVec? =
    kids.let { if (arrayIdx < it.size) it[arrayIdx] else null }

fun RowVec.getAt(vararg path: Any, src: Series<Byte>): RowVec? {
    var cur: RowVec? = this
    for (step in path) {
        cur = when (step) {
            is CharSequence -> cur?.step(step, src)
            is Int          -> cur?.step(step)
            else            -> error("path step must be CharSequence or Int")
        }
        if (cur == null) return null
    }
    return cur
}

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
    val timestamp: Long       = 0L,
    val provenance: String    = "",
)

val BlackBoardEntry.index:  ConfixIndex    get() = doc.index
val BlackBoardEntry.src:    Series<Byte>   get() = doc.src
val BlackBoardEntry.facade: ConfixIndex   get() = doc.index

// ── RowVec properties ──────────────────────────────────────────────────────────

val RowVec.open:  Int                                 get() = this[0] as Int
val RowVec.close: Int                                 get() = this[1] as Int
val RowVec.tag:   borg.trikeshed.cursor.IOMemento get() = this[2] as borg.trikeshed.cursor.IOMemento
val RowVec.kids:  Cursor                              get() = this[3] as Cursor

private fun Series<Byte>.spanStr(open: Int, close: Int): String {
    if (close < open) return ""
    val len = close - open + 1
    return CharArray(len) { this[open + it].toInt().toChar() }.concatToString()
}

private fun Series<Byte>.spanLong(open: Int, close: Int): Long {
    var v = 0L; var neg = false; var i = open
    if (i <= close && this[i].toInt().toChar() == '-') { neg = true; i++ }
    while (i <= close) { v = v * 10 + (this[i++].toInt().toChar() - '0') }
    return if (neg) -v else v
}