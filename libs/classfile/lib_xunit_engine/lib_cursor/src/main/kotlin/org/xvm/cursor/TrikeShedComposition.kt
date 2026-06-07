@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST", "unused")

package org.xvm.cursor

import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.`ColumnMeta↻`
import borg.trikeshed.cursor.CowSeriesBody
import borg.trikeshed.cursor.CowSeriesHandle
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*

// ═══════════════════════════════════════════════════════════════════════════════
// TrikeShed Compositional Examples — intentionally dense, DRY, no repetition
//
// Type lattice (all are Join / MetaSeries under the hood):
//
//   Join<A, B>                         — product type, two slots
//     ↳ MetaSeries<I, T> = Join<I, (I)->T>   — indexed lookup
//       ↳ Series<T> = MetaSeries<Int, T>     — int-indexed
//         ↳ Series2<A,B> = Series<Join<A,B>> — split storage
//         ↳ MutableSeries<T> : Series<T>     — add/set/remove
//       ↳ FacetedRow<K> = MetaSeries<K, Any?> — GADT-keyed row
//
//   Cursor = Series<RowVec>             — a table
//   RowVec = Series2<Any?, ColumnMeta↻> — a row (cells = Join<Any?, ()->ColumnMeta>)
//   CharStr = MetaSeries<TextK<*>, Any?>— 1-row string cursor
//   Corpus = Series<CharStr>            — string matrix
//   ConfixIndex = FacetedRow<Any>       — parsed JSON/CBOR index
//
// Construction grammar:
//   a j b            — join two values
//   n j { i -> ... } — series of size n with getter lambda
//   s_ [a, b, c]     — series literal
//   s α { ... }      — lazy projection (map)
//   s joins t        — zip two Series into Series2
// ═══════════════════════════════════════════════════════════════════════════════

// ── 1. SERIES ─────────────────────────────────────────────────────────────────

/** Series<T> = Join<Int, (Int)->T>. Size is .a, element access is .b(i) or [i]. */
object SeriesComposition {

    // literal
    val abc: Series<String> = s_["alpha", "beta", "gamma"]

    // from array
    val fromArr = arrayOf(1, 2, 3).toSeries()

    // from range
    val zeroTo9 = (0..9).toSeries()

    // projection (α)
    val lengths: Series<Int> = abc α { it.length }

    // range selection
    val first2: Series<String> = abc[0..1]

    // index selection
    val odds: Series<Int> = zeroTo9[intArrayOf(1, 3, 5, 7, 9)]

    // zip into Series2
    val paired: Series2<String, Int> = abc joins lengths

    // project sides of Series2
    val justKeys: Series<String> = paired.left
    val justVals: Series<Int> = paired.right

    // division into d parts
    val halves: Series<Series<Int>> = zeroTo9 / 2

    // empty
    val nothing: Series<String> = emptySeriesOf()
}

// ── 2. CURSOR (= Series<RowVec>) ──────────────────────────────────────────────

/** Cursor is just Series<RowVec>. RowVec is Series2<Any?, ColumnMeta↻>. */
object CursorComposition {

    // RowVec = n j { col -> cell(value, meta) }
    // where cell(value, meta) = value j meta.↺

    private fun cell(v: Any?, ref: ColumnMetaRef): Join<Any?, `ColumnMeta↻`> =
        v j ref.`↺` as `ColumnMeta↻`

    private val nameRef = ColumnMetaRef(0, "name", "String", PointcutFacet.SymbolName)
    private val ageRef = ColumnMetaRef(1, "age", "Int", PointcutFacet.TypeInfo)

    // single row
    val row1: RowVec = 2 j { col: Int ->
        when (col) {
            0 -> cell("alice", nameRef)
            1 -> cell(42, ageRef)
            else -> throw IndexOutOfBoundsException(col)
        }
    }

    // second row
    val row2: RowVec = 2 j { col: Int ->
        when (col) {
            0 -> cell("bob", nameRef)
            1 -> cell(37, ageRef)
            else -> throw IndexOutOfBoundsException(col)
        }
    }

    // cursor = Series<RowVec>
    val cursor: Cursor = 2 j { i: Int -> if (i == 0) row1 else row2 }

    // read a cell: row.b(col).a  gives the value
    val firstName: Any? = row1.b(0).a  // "alice"

    // lift to FacetedRow for GADT-keyed access
    val faceted: FacetedRow<ColK<*>> = row1.asFaceted()
    val width: Int = faceted[ColK.Width] as Int

    // project column from cursor (all values at column 0)
    val names: Series<Any?> = cursor.a j { i: Int -> cursor.b(i).b(0).a }
}

// ── 3. CONFIX ─────────────────────────────────────────────────────────────────

/** Confix parses JSON/CBOR into ConfixIndex (= FacetedRow<Any>), then RowVec. */
object ConfixComposition {

    // parse
    fun fromJson(json: String): ConfixIndex = scan(json)
    fun fromCbor(bytes: ByteArray): ConfixIndex = scan(bytes, Syntax.CBOR)

    // ConfixIndex facets: Spans, Tags, Depths, DirectChildren, TreeCursor, KeyToChild

    // lazy tree cursor — ConfixIndex exposes Cursor via TreeCursor facet
    // navigation: ConfixDoc wraps the index for path-based access
    fun doc(json: String): ConfixDoc = confixDoc(json)

    // ConfixCell = Join<RowVec, Series<Byte>> — cell with source bytes
    // cell = doc.docAt("key1", 0, "key2") → navigates path, returns cell or null

    // ConfixCursor: lazy row sequence over JSON arrays
    fun rows(json: String): Sequence<ConfixRow> =
        ConfixCursor(json, Syntax.JSON).rows()

    // facet projection (column subset)
    fun projected(json: String, vararg cols: String): ConfixCursor =
        ConfixCursor(json, Syntax.JSON).facet(*cols)

    // join two cursors on a shared column
    fun joined(left: ConfixCursor, right: ConfixCursor, on: String): ConfixCursor =
        left.join(right, on)
}

// ── 4. MUTABLE SERIES ─────────────────────────────────────────────────────────

/**
 * Three strategies: CowSeriesHandle (COW), ChunkedMutableSeries (chunked tree),
 * RingSeries (fixed ring, power-of-2 mask).
 *
 * All implement MutableSeries<T> which extends Series<T>.
 */
object MutableSeriesComposition {

    // CowSeriesHandle — copy-on-write, observer gets Twin<old, new>
    fun cow() {
        val body = CowSeriesBody.of("a", "b", "c")
        val handle = CowSeriesHandle<String>(body)
        handle.subscribe { transition: Twin<Series<String>> ->
            println("COW: ${transition.a.size} → ${transition.b.size}")
        }
        handle.add("d")     // triggers observer
        handle[0] = "x"     // triggers observer
        val version = handle.version() // monotonic
    }

    // ChunkedMutableSeries — amortized O(1) append, chunk tree
    fun chunked() {
        val ms = ChunkedMutableSeries<Int>(chunkSize = 64)
        for (i in 0 until 1000) ms.add(i)
        val fifth = ms[4] // stairs-indexed lookup
        ms[4] = 99       // copy single chunk
    }

    // RingSeries — power-of-2 capacity, mask-based, overwrites oldest
    fun ring() {
        val ring = borg.trikeshed.lib.RingSeries<String>(capacity = 8)
        for (i in 0 until 12) ring.add("e$i") // wraps at 8, evicts e0..e3
        // ring.a == 8, ring[0] == "e4"
    }
}

// ── 5. REDUX SERIES ───────────────────────────────────────────────────────────

/**
 * ReduxMutableSeries<A, S> — event journal + lazy fold-on-read.
 *
 * Pipeline: actions append to eventJournal → reify() folds all actions
 * into state S via Reducer<A, S>. State is only computed on access.
 */
object ReduxComposition {

    // simple counter reducer
    private object Counter : Reducer<Int, Int> {
        override val zero: Int = 0
        override fun combine(acc: Int, element: Int): Int = acc + element
    }

    fun counter(): ReduxMutableSeries<Int, Int> {
        val journal = ChunkedMutableSeries<Int>()
        return ReduxMutableSeries(journal, Counter, capture = 0)
    }

    // collector reducer — accumulates all actions into a Series
    fun collector(): ReduxMutableSeries<String, Series<String>> {
        return ReduxMutableSeries.of(ChunkedMutableSeries(), capture = "init")
    }

    // typedef fact reducer — maps factId → TypedefFact, removes on revert
    fun typedefReducer(): Reducer<TypedefFact, Map<Long, TypedefFact>> =
        object : Reducer<TypedefFact, Map<Long, TypedefFact>> {
            override val zero = emptyMap<Long, TypedefFact>()
            override fun combine(acc: Map<Long, TypedefFact>, e: TypedefFact) =
                if (e.isReverted) acc - e.factId else acc + (e.factId to e)
        }

    // usage pattern
    fun usage() {
        val redux = counter()
        redux.dispatch(1)
        redux.dispatch(2)
        redux.dispatch(3)
        val state: Int = redux.state  // folds 1+2+3 = 6
    }
}

// ── 6. SYNAPSE / RING → REDUX PIPELINE ────────────────────────────────────────

/**
 * Full pointcut capture pipeline:
 *
 *   Ghidra P-code → FieldSynapse (wireproto 24B)
 *     → RingSeries (pulsing synapse, capacity 4096)
 *       → ReduxMutableSeries (journal + lazy fold)
 *         → Blackboard
 */
object SynapsePipelineComposition {

    // wire delivery: codec → ByteBuffer → ByteArray
    fun encode(signature: Join<Series<String>, ColumnMetaRef>): ByteArray =
        WireSeries.strings().singleWireproto(signature)

    // ring with eviction → feeds batch
    fun batching(): BatchMutableSeries<String> {
        val batch = BatchMutableSeries<String>()
        val init: Series<String> = emptySeriesOf()
        val events = EventSeries(init, ColumnMetaRef(0, "op", "String"))
        batch.attach(events)
        events.series = s_["COPY", "LOAD", "STORE"] // fires subscribers
        return batch
    }

    // versioned cursor handle pattern (ShapeCursorBox from skill)
    //   data class ShapeCursor(val shape: IntArray, val cursor: Series<RowVec>, val version: Long)
    //   class ShapeCursorBox(handle, body: ArrayList<RowVec>, var version: Long) {
    //     fun ingest(source: Series<RowVec>) { for (i in 0 until source.a) body.add(source[i]) ; version++ }
    //     fun reify(): ShapeCursor = ShapeCursor(shape, body.size j { body[it] }, version)
    //   }

    // full pipeline: RingSeries pulse → Redux reify
    fun pipeline() {
        val ring = borg.trikeshed.lib.RingSeries<RowVec>(capacity = 256)
        val nano0 = java.lang.System.nanoTime()

        // append event row
        val row: RowVec = 2 j { col: Int ->
            when (col) {
                0 -> (0L as Any?) j ColumnMetaRef(0, "factId", "Long").`↺` as `ColumnMeta↻`
                1 -> (nano0 as Any?) j ColumnMetaRef(1, "nano", "Long").`↺` as `ColumnMeta↻`
                else -> (null as Any?) j ColumnMetaRef(col, "_", "Any?").`↺` as `ColumnMeta↻`
            }
        }
        ring.add(row)

        // snapshot ring → series
        val ringSize = ring.a
        val snapshot: Series<RowVec> = ringSize j { i: Int -> ring[i] }
    }
}

// ── 7. METASERIES — LAZY TRANSDUCER CHAIN ─────────────────────────────────────

/**
 * MetaSeries<Input, Output> — filter + codec from Input domain to Cursor.
 *
 * Domain specializations: ClassfileMetaSeries, EventMetaSeries, ConfixMetaSeries.
 * Each filters Input by domain criteria and codecs to RowVec.
 */
object MetaSeriesChainComposition {

    // classfile filter: only classes matching a package prefix
    fun classfiles(prefix: String): ClassfileMetaSeries = ClassfileMetaSeries(prefix)

    // event filter: only wireproto events in an opcode range
    fun events(opcodes: IntRange): EventMetaSeries = EventMetaSeries(opcodes)

    // confix path filter: only cells under a path prefix
    fun confixPaths(glob: String): ConfixMetaSeries = ConfixMetaSeries(glob)

    // chain: source → filter1 → filter2 → cursor
    fun chained(source: Series<String>, prefix: String): Cursor =
        classfiles(prefix).cursor(source)

    // K-boundary: maps a Series of keys to a Series of RowVec
    fun kBoundary(
        name: SymbolRef,
        keys: Series<String>,
        rowFn: (String) -> RowVec,
    ): Series<RowVec> = KBoundary(name, keys, rowFn).rows()
}

// ── 8. CONFIX FACADE — ROWVEC WALK WITHOUT FORCING LAZY CHILDREN ──────────────

/**
 * ConfixFacade walks a RowVec respecting facet laziness.
 * ChildRows facets are never forced during walk().
 * FacetReifier dispatches scalar reification by facet type.
 */
object FacadeComposition {

    fun walk(row: RowVec): ConfixFacade.WalkResult = ConfixFacade.walk(row)

    fun reify(row: RowVec): ConfixFacade.ReifiedRow = ConfixFacade.reifyRow(row)

    // SymbolName facet resolves via StringPool
    // Wireproto facet returns raw MemSegment
    // ChildRows facet stays Lazy — not forced
}

// ── 9. DENSE PATTERNS — CODE-GOLF COMPOSITIONS ────────────────────────────────

/**
 * One-liners and minimal compositions using the `j` infix.
 * Every composition is a Join under the hood.
 */
object DensePatterns {

    // series of size 3 with lazy cells
    val s: Series<Int> = 3 j { intArrayOf(10, 20, 30)[it] }

    // cursor of 2 rows, each with 3 columns
    val c: Cursor = 2 j { r: Int ->
        3 j { c: Int -> (r * 3 + c) as Any? j ColumnMetaRef(c, "c$c", "Int").`↺` as `ColumnMeta↻` }
    }

    // faceted row from sealed key family
    fun faceted(): FacetedRow<ColK<*>> = (2 j { op: ColK<*> ->
        when (op) {
            is ColK.ByIndex -> 42
            ColK.Width -> 1
            else -> null
        }
    }) as FacetedRow<ColK<*>>

    // series2 from two series
    val zipped: Series2<Int, String> = s_[1, 2, 3] joins s_["a", "b", "c"]

    // ring → series snapshot
    fun ringSnapshot(ring: borg.trikeshed.lib.RingSeries<Int>): Series<Int> =
        ring.a j { i: Int -> ring[i] }

    // redux → state (fold on read)
    fun reduxState(redux: ReduxMutableSeries<Int, Int>): Int = redux.state

    // rowvec cell access pattern: row.b(col).a = value
    fun cellValue(row: RowVec, col: Int): Any? = row.b(col).a

    // cursor column projection
    fun projectColumn(cursor: Cursor, col: Int): Series<Any?> =
        cursor.a j { i: Int -> cursor.b(i).b(col).a }
}
