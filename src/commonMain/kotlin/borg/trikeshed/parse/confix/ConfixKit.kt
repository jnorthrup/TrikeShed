@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST")

package borg.trikeshed.parse.confix

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*

// ── ConfixDoc — index + source ──────────────────────────────────
//
// ConfixIndex carries token geometry (spans/tags/depths/children).
// Series<Byte> carries the raw bytes needed for scalar reification
// and string-key comparison.  Neither is copied; both are zero-alloc views.
//
// ConfixDoc = Join<ConfixIndex, Series<Byte>>
//   .a = ConfixIndex (the GADT faceted-row parse result)
//   .b = Series<Byte> (the original source, view only)

typealias ConfixDoc = Join<ConfixIndex, Series<Byte>>

val ConfixDoc.index: ConfixIndex  get() = a
val ConfixDoc.docSrc:   Series<Byte> get() = b

// ── Parse — entry points ─────────────────────────────────────────

fun confixDoc(bytes: ByteArray, syntax: Syntax = Syntax.CBOR): ConfixDoc {
    val src: Series<Byte> = bytes.size j { i: Int -> bytes[i] }
    return syntax.scanIndex(src) j src
}

fun confixDoc(text: String): ConfixDoc {
    val bytes = text.encodeToByteArray()
    val src: Series<Byte> = bytes.size j { i: Int -> bytes[i] }
    val syntax = if (text.trimStart().firstOrNull() in setOf('{', '[', '"'))
        Syntax.JSON else Syntax.YAML
    return syntax.scanIndex(src) j src
}

fun confixDoc(src: Series<Byte>, syntax: Syntax): ConfixDoc = syntax.scanIndex(src) j src

// ── Scan — alias exposing the raw ConfixIndex ────────────────────

fun scan(bytes: ByteArray, syntax: Syntax = Syntax.CBOR): ConfixIndex =
    confixDoc(bytes, syntax).index

fun scan(text: String): ConfixIndex = confixDoc(text).index

// ── Root / tree access ───────────────────────────────────────────

@Suppress("UNCHECKED_CAST")
val ConfixDoc.roots: Cursor  get() = index.b(ConfixIndexK.TreeCursor) as Cursor
val ConfixDoc.root:  RowVec? get() = roots.let { if (it.size > 0) it[0] else null }

// ── RowVec column projections ────────────────────────────────────
//
// buildTree stores exactly 4 columns per row:
//   0 → open  (Int)        byte offset of token start
//   1 → close (Int)        byte offset of token end (inclusive)
//   2 → tag   (IOMemento)  type discriminant
//   3 → kids  (Cursor)     direct-child rows (lazy, zero-copy)

val RowVec.open:  Int       get() = this[0].a as Int
val RowVec.close: Int       get() = this[1].a as Int
val RowVec.tag:   IOMemento get() = this[2].a as IOMemento
val RowVec.kids:  Cursor    get() = this[3].a as Cursor

// ── Reify — WITHOUT path (single row → typed value) ─────────────

fun RowVec.reify(src: Series<Byte>): Any? = when (tag) {
    IOMemento.IoNothing             -> null
    IOMemento.IoBoolean             -> src[open].toInt().toChar() == 't'
    IOMemento.IoDouble              -> src.spanStr(open, close).toDoubleOrNull()
    IOMemento.IoInt                 -> src.spanLong(open, close).toInt()
    IOMemento.IoLong                -> src.spanLong(open, close)
    IOMemento.IoString              -> src.spanStr(open + 1, close - 1) // strip quotes
    IOMemento.IoBytes               -> ByteArray(close - open + 1) { src[open + it] }
    IOMemento.IoObject,
    IOMemento.IoArray               -> kids
    else                            -> src.spanStr(open, close)
}

// ConfixDoc convenience — reify by flat token index
@Suppress("UNCHECKED_CAST")
fun ConfixDoc.reify(tokenIdx: Int): Any? =
    (index.b(ConfixIndexK.TreeCursor) as Cursor)[tokenIdx].reify(docSrc)

// ── Navigate WITHOUT path — flat ConfixIndex ─────────────────────
//
// KeyToChild     : key name → flat token index of the KEY string token
// valueIndexFor  : flat token index of the VALUE after a key token
// DirectChildren : array/object children by parent token index

@Suppress("UNCHECKED_CAST")
fun ConfixIndex.valueIndexFor(keyTokenIdx: Int): Int? {
    val depths = b(ConfixIndexK.Depths) as Series<Int>
    val d = depths[keyTokenIdx]
    val total = depths.size
    for (i in keyTokenIdx + 1 until total)
        if (depths[i] == d) return i
    return null
}

// Resolve key → value token index (flat, no tree traversal)
@Suppress("UNCHECKED_CAST")
fun ConfixIndex.resolve(key: CharSequence): Int? =
    (b(ConfixIndexK.KeyToChild) as (CharSequence) -> Int?)(key)?.let { valueIndexFor(it) }

// Resolve integer index → token index in parent's DirectChildren
@Suppress("UNCHECKED_CAST")
fun ConfixIndex.resolve(parentTokenIdx: Int, arrayIdx: Int): Int? {
    val ch = (b(ConfixIndexK.DirectChildren) as (Int) -> Series<Int>)(parentTokenIdx)
    return if (arrayIdx < ch.size) ch[arrayIdx] else null
}

// ── Navigate WITH path — tree walk via RowVec.kids ───────────────
//
// String step : scan kids for IoString matching key, return next kid (value)
// Int step    : direct kids[n]
// src is needed for string comparison against raw bytes.

fun RowVec.step(key: CharSequence, src: Series<Byte>): RowVec? {
    val ch = kids
    var i = 0
    while (i + 1 < ch.size) {
        val k = ch[i]
        if (k.tag == IOMemento.IoString) {
            // CBOR/JSON string: open+1 .. close-1 strips the surrounding quotes
            val kOpen  = k.open  + 1
            val kClose = k.close - 1
            val kLen   = kClose - kOpen + 1
            if (kLen == key.length) {
                var match = true
                for (d in 0 until kLen)
                    if (src[kOpen + d].toInt().toChar() != key[d]) { match = false; break }
                if (match) return ch[i + 1]
            }
        }
        i += 2
    }
    return null
}

fun RowVec.step(arrayIdx: Int): RowVec? =
    kids.let { if (arrayIdx < it.size) it[arrayIdx] else null }

// Compound path walk: each element is String (key) or Int (index)
fun RowVec.getAt(vararg path: Any, src: Series<Byte>): RowVec? {
    var cur: RowVec? = this
    for (step in path) {
        cur = when (step) {
            is CharSequence -> cur?.step(step, src)
            is Int          -> cur?.step(step)
            else            -> error("path step must be CharSequence or Int, got ${step::class.simpleName}")
        }
        if (cur == null) return null
    }
    return cur
}

// ConfixDoc compound navigation — root → path
fun ConfixDoc.getAt(vararg path: Any): RowVec?  = root?.getAt(*path, src = docSrc)
fun ConfixDoc.scalar(vararg path: Any): Any?    = getAt(*path)?.reify(docSrc)

// ── ConfixCell — RowVec in document context ──────────────────────
//
// A RowVec from buildTree carries only byte offsets — no reference
// to the source bytes.  ConfixCell = Join<RowVec, Series<Byte>>
// embeds the document source so every cell self-navigates:
//
//   cell["key"]   — step into object child by string key
//   cell[0]       — step into array/object child by index
//   cell.kids     — all direct children as ConfixCell (src carried)
//   cell.reify()  — decode scalar value (no explicit src arg)
//   cell.getAt(…) — compound path walk, fully self-contained
//
// ConfixCell uses the same Join<A,B> pattern as ConfixDoc; zero new
// allocations beyond the pair itself — src is a shared view.

typealias ConfixCell = Join<RowVec, Series<Byte>>

/**
 * JsonElement is the Confix Cursor mapping of a JSON token:
 *   open/close byte offsets (geometry) + source series (content)
 * Zero-alloc navigation: no copying, no boxing, pure algebra over Join.
 *
 * Shape (matches PRELOAD JsElement = Join<Twin<Int>, Series<Int>>):
 *   ConfixCell.a = RowVec        → JsElement.a = Twin<Int> (open, close)
 *   ConfixCell.b = Series<Byte>  → JsElement.b = Series<Int> (index function)
 */
typealias JsonElement = ConfixCell

val ConfixCell.row: RowVec       get() = a
val ConfixCell.src: Series<Byte> get() = b

// ConfixCell delegates to row for open/close/tag/kids — use row.open etc directly

// children carry the same src — no threading needed downstream
val ConfixCell.cellKids: Series<ConfixCell>
    get() = row.kids.size j { i: Int -> row.kids[i] j src }

// string-key navigation (object cells)
operator fun ConfixCell.get(key: CharSequence): ConfixCell? =
    row.step(key, src)?.let { it j src }

// integer-index navigation (array or positional object child)
operator fun ConfixCell.get(idx: Int): ConfixCell? =
    row.step(idx)?.let { it j src }

// compound path: String → key step, Int → index step
fun ConfixCell.cellGetAt(vararg path: Any): ConfixCell? {
    var cur: ConfixCell? = this
    for (step in path) {
        cur = when (step) {
            is CharSequence -> cur?.get(step)
            is Int          -> cur?.get(step)
            else            -> error("path step must be CharSequence or Int, got ${step::class.simpleName}")
        }
        if (cur == null) return null
    }
    return cur
}

fun ConfixCell.reify(): Any? = row.reify(src)

// ── ConfixDoc entry points returning ConfixCell ──────────────────

val ConfixDoc.rootCell: ConfixCell?
    get() = root?.let { it j docSrc }

val ConfixDoc.cells: Series<ConfixCell>
    get() = roots.size j { i: Int -> roots[i] j docSrc }

fun ConfixDoc.docAt(vararg path: Any): ConfixCell?  = rootCell?.cellGetAt(*path)
fun ConfixDoc.value(vararg path: Any): Any?         = docAt(*path)?.reify()

// ── Span helpers ─────────────────────────────────────────────────

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
