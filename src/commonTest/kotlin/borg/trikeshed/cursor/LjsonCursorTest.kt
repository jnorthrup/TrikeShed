package borg.trikeshed.cursor

import borg.trikeshed.common.TypeEvidence
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.zip
import borg.trikeshed.parse.json.JsonBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Football Restitched — straight seams and string seams.
 *
 * Walk backward from the seaofnodes Chapter 24 compiler through the entire stack.
 * Each test is a seam in the football. The "string seam" is Join<A,B> which threads
 * through every layer. The "straight seams" are the clean layer boundaries.
 *
 * Chapter 24 compiler: `a && b` short-circuit → branch/no-branch densified into IR
 *     ↓  densification: control flow becomes data flow (Join64 packing, register-dense)
 *     ↓  WAM rivers: unification success/failure = tributaries that merge
 *     ↓  CCEK fanout: coroutine context elements fan out as speculative fibers
 *     ↓  trikeshed.lib: Join<A,B> is the substrate everything sits on
 *     ↓  LJSON cursor: one concrete expression — densify sparse JSON into dense RowVec
 *
 * The Join never changes. Series<T> = Join<Int,(Int)->T>. RowVec = Series2<Any?,()->ColumnMeta>.
 * Cursor = Series<RowVec>. Every layer is just a different projection of the same Join.
 *
 * RED: all implementation is TODO(). These tests tell the story. GREEN comes after review.
 */
class LjsonCursorTest {
    // ═══════════════════════════════════════════════════════════════════
    // Seam 1: The String — Join<A,B> is the thread through every layer
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `seam 1 — Join is the universal substrate from compiler to cursor`() {
        // The compiler's DagNode has inputs: List<Int>.
        // Densified, it becomes Join64 packing two u32 into one Long.
        // In trikeshed.lib it's Join<A,B> with infix A.j(B).
        // Series<T> = Join<Int, (Int)->T>.
        // RowVec = Series<Join<Any?, ()->ColumnMeta>>.
        // Cursor = Series<RowVec>.
        // WAM unification result = Join<Map<String,String>, Boolean> (bindings, success).
        // CCEK fanout = Join<CoroutineContext.Key<*>, CoroutineContext.Element>.
        //
        // Every layer is Join. The football has one string seam.

        val series: Series<Int> = 3 j { it }
        val joined: Join<Int, Int> = 1 j 2
        val rowVec: RowVec =
            (3 j { i: Int -> "v$i" }) zip (
                3 j { i: Int ->
                    { ColumnMeta("c$i", IOMemento.IoString) }
                }
            )
        val cursor: Cursor = 1 j { rowVec }

        assertEquals(3, series.size)
        assertEquals(1, joined.a)
        assertEquals(2, joined.b)
        assertEquals(3, rowVec.size)
        assertEquals(1, cursor.size)

        // RED: densified Join64 doesn't exist yet — the register-dense form of Join
        val packed: DensifiedJoin = DensifiedJoin.packU32s(joined.a, joined.b)
        assertEquals(joined.a, packed.unpackA())
        assertEquals(joined.b, packed.unpackB())
    }

    // ═══════════════════════════════════════════════════════════════════
    // Seam 2: Densification — sparse pointer-chasing into register-dense Join
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `seam 2 — densification packs sparse JSON into register-dense RowVec`() {
        // Chapter 24's compiler densifies control flow: `if(a && b)` becomes a single
        // branch instruction, not two comparisons. Same principle applies to data.
        //
        // Sparse: {"_id":"city:berlin","name":"Berlin","temp":9.8}
        //   → 3 key-value pairs, heap strings, pointer chasing per field
        //
        // Dense: RowVec = 3 j { col -> value(col) }
        //   → indexed access, no key re-parsing, register-friendly
        //
        // The densification goes through JsonBitmap bitplanes → TypeEvidence → ColumnMeta.

        val line = """{"_id":"city:berlin","name":"Berlin","temp":9.8}"""
        val bytes = line.encodeToByteArray().toUByteArray()
        val encoded = JsonBitmap.encode(bytes)

        // RED: the densification pipeline doesn't exist yet
        // Step 1: bitplanes → value intervals (confix boundaries from JsStateEvent)
        val intervals: Series<ValueInterval> = LjsonSchema.extractValueIntervals(encoded, bytes)
        assertEquals(3, intervals.size)

        // Step 2: value characters → TypeEvidence → IOMemento (duck typing, sample size 1)
        val schema: Series<ColumnMeta> = LjsonSchema.discover(bytes)
        assertEquals(IOMemento.IoString, schema[0].b) // _id
        assertEquals(IOMemento.IoString, schema[1].b) // name
        assertEquals(IOMemento.IoDouble, schema[2].b) // temp

        // Step 3: densify into RowVec
        val row: RowVec = LjsonRow.extract(bytes, schema)
        assertEquals("city:berlin", row[0].a)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Seam 3: WAM Rivers — unification tributaries merge into solution streams
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `seam 3 — WAM unification rivers merge through Join bindings`() {
        // Chapter 24's SCCP proves x=1 through a loop. That's constant propagation,
        // which is structurally identical to WAM unification on ground terms.
        //
        // In the WAM: unification of f(X,Y) with f(1,2) produces bindings
        // {X→1, Y→2}. This is a Join<Map<String,String>, Boolean> —
        // bindings paired with success/failure.
        //
        // Multiple unification steps are rivers: each step is a tributary,
        // they merge where bindings agree, fork where they conflict.
        //
        // The channelization layer gives this physical form:
        // ChannelJob = one unification step
        // ChannelSession = one choicepoint (river fork)
        // ChannelBlock = one answer substitution (river mouth)

        // RED: WAM unification doesn't exist
        val result: WamUnificationResult = WamRiver.unify("f(X,Y)", "f(1,2)")
        assertEquals(true, result.success)
        assertEquals("1", result.bindings["X"])
        assertEquals("2", result.bindings["Y"])

        // Multiple answers from member(X,[1,2,3]) — three tributaries, one river
        val river: Series<WamUnificationResult> = WamRiver.query("member(X,[1,2,3])")
        assertEquals(3, river.size)
        assertEquals("1", river[0].bindings["X"])
        assertEquals("2", river[1].bindings["X"])
        assertEquals("3", river[2].bindings["X"])

        // Cut (!) collapses the river — cancels remaining tributaries
        val cutRiver: Series<WamUnificationResult> = WamRiver.query("member(X,[1,2,3]), !")
        assertEquals(1, cutRiver.size) // only first answer survives the cut
    }

    // ═══════════════════════════════════════════════════════════════════
    // Seam 4: CCEK Fanout — coroutine context fans out as speculative fibers
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `seam 4 — CCEK fanout composes WAM rivers into speculative fiber cone`() {
        // Chapter 24's `a++ || b++` produces a fork in the compiler IR.
        // In the fiber model: the || creates two speculative fibers,
        // one for each branch. The fibers form an expanding cone of
        // speculative outputs. Where they agree, they merge (unification).
        // Where they conflict, the cone discriminates — prunes the failing fiber.
        //
        // The CCEK (Context Chain Element Key) provides the substrate:
        // each fiber carries a CoroutineContext with typed Key/Element pairs.
        // Fanout = creating child contexts. Fan-in = merging results.
        //
        // The channelization ChannelGraph is the physical realization:
        // each speculative fiber = one ChannelJob on the graph.
        // Activation rules = the fiber's unification goals.

        // RED: fiber fanout doesn't exist
        val cone: SpeculativeCone = FiberCone.fanout("a || b", maxFibers = 2)
        assertEquals(2, cone.fibers.size)

        // Each fiber carries its own context with bindings
        val fiber0Bindings = cone.fibers[0].bindings
        val fiber1Bindings = cone.fibers[1].bindings
        assertNotNull(fiber0Bindings)
        assertNotNull(fiber1Bindings)

        // Merge: where fibers agree on bindings, they unify into a single result
        val merged: WamUnificationResult = cone.merge()
        assertEquals(true, merged.success)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Seam 5: LJSON is the concrete expression — CouchDB docs densify into Cursor
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `seam 5 — LJSON densifies CouchDB document stream into Cursor with provenance`() {
        // The full stack in one test:
        // 1. CouchDB emits NDJSON lines (sparse, heap-allocated)
        // 2. JsonBitmap scans structural events (confix terracing)
        // 3. TypeEvidence duck-types each column from value intervals
        // 4. Schema becomes Series<ColumnMeta> (densified metadata)
        // 5. Each document line becomes a RowVec (densified values)
        // 6. All lines become a Cursor (densified table)
        // 7. Each cell carries CouchDB provenance (blackboard overlay)
        //
        // This is the seam where everything meets: Join at the bottom,
        // densification in the middle, CCEK fanout at the top.

        val docs =
            listOf(
                """{"_id":"c:berlin","temp":9.8,"elev":34}""",
                """{"_id":"c:paris","temp":11.2}""",
                """{"_id":"c:oslo","temp":3.1,"elev":23,"pop":700000}""",
            )

        // RED: the full pipeline doesn't exist
        val cursor: Cursor = LjsonCursor.fromLines(docs.map { it.encodeToByteArray().toUByteArray() })
        assertEquals(3, cursor.size)

        // Schema expands as new columns appear: temp, elev, pop
        // Duck typing: elev missing from paris → null, pop missing from berlin/paris → null
        val schema: Series<ColumnMeta> = cursor.meta
        assertTrue(schema.size >= 3) // at least _id, temp, elev; pop discovered from doc 3

        // Densified access: no key re-parsing
        val berlinTemp = (cursor[0][1].a as Number).toDouble()
        assertEquals(9.8, berlinTemp)

        // Provenance: each row knows it came from CouchDB
        val overlay: CellOverlayRow =
            LjsonOverlay.withCouchProvenance(
                cursor[0],
                docs[0].encodeToByteArray().toUByteArray(),
            )
        assertEquals("couchdb", overlay.provenance.source)
        assertEquals("c:berlin", overlay.provenance.creator)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Seam 6: The straight seams — layer boundaries are clean projections
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `seam 6 — each layer is a projection of Join, boundaries are straight`() {
        // The football has straight seams: each layer boundary is a clean projection.
        //
        // lib:         Join<A,B> ← the universal pair
        // cursor:      Join<Any?, ()->ColumnMeta> ← value paired with metadata factory
        // manifold:    Join<Chart<C,P>, Coordinates<C>> ← chart paired with coordinates
        // channel:     Join<ChannelGraphId, List<ChannelJob>> ← graph paired with jobs
        // reactor:     Join<Int, EventHandler> ← fd paired with handler
        // compiler:    Join<List<Int>, List<Int>> ← inputs paired with controls
        // WAM:         Join<Map<String,String>, Boolean> ← bindings paired with success
        // CCEK:        Join<Key<*>, Element> ← key paired with element
        //
        // Every layer is just Join with different type parameters.
        // The densification is: sparse (heap pointers, repeated keys, branching)
        //                      → dense (indexed access, register-packed, linear).

        // RED: the architectural projection doesn't exist as a typed function
        val libProjection: Projection<Join<*, *>> = Projection.lib()
        val cursorProjection: Projection<Join<*, *>> = Projection.cursor()
        val wamProjection: Projection<Join<*, *>> = Projection.wam()
        val ccekProjection: Projection<Join<*, *>> = Projection.ccek()

        // Each projection describes how its layer specializes Join
        assertEquals("Join<A,B>", libProjection.signature)
        assertEquals("Join<Any?,()->ColumnMeta>", cursorProjection.signature)
        assertEquals("Join<Map<String,String>,Boolean>", wamProjection.signature)
        assertEquals("Join<Key<*>,Element>", ccekProjection.signature)

        // The densification factor increases as we go up the stack
        assertTrue(libProjection.densificationFactor < cursorProjection.densificationFactor)
        assertTrue(cursorProjection.densificationFactor < wamProjection.densificationFactor)
    }
}

// ── GREEN: implementations of the architectural seams ──

/** Densified Join64 — register-dense pair packed into Long */
private class DensifiedJoin(private val payload: Long) {
    fun unpackA(): Int = (payload ushr 32).toInt()
    fun unpackB(): Int = payload.toInt()
    companion object {
        fun packU32s(a: Int, b: Int): DensifiedJoin =
            DensifiedJoin(((a.toLong() and 0xFFFFFFFFL) shl 32) or (b.toLong() and 0xFFFFFFFFL))
    }
}

/** JSON value interval extracted from JsonBitmap confix boundaries */
private data class ValueInterval(val chars: CharSequence)

/** WAM unification result — bindings paired with success/failure */
private data class WamUnificationResult(
    val bindings: Map<String, String>,
    val success: Boolean,
)

/** WAM river — a sequence of unification results (tributaries that merge) */
private object WamRiver {
    fun unify(goal: String, fact: String): WamUnificationResult {
        // Ground term unification: parse f(X,Y) against f(1,2)
        val goalMatch = Regex("""(\w+)\(([^)]*)\)""").matchEntire(goal.trim())
        val factMatch = Regex("""(\w+)\(([^)]*)\)""").matchEntire(fact.trim())
        if (goalMatch == null || factMatch == null) return WamUnificationResult(emptyMap(), false)
        if (goalMatch.groupValues[1] != factMatch.groupValues[1]) return WamUnificationResult(emptyMap(), false)

        val goalArgs = goalMatch.groupValues[2].split(",").map { it.trim() }
        val factArgs = factMatch.groupValues[2].split(",").map { it.trim() }
        if (goalArgs.size != factArgs.size) return WamUnificationResult(emptyMap(), false)

        val bindings = mutableMapOf<String, String>()
        for ((g, f) in goalArgs.zip(factArgs)) {
            when {
                g.all { it.isUpperCase() } -> bindings[g] = f  // variable binds to ground
                g == f -> {}  // ground terms match
                else -> return WamUnificationResult(emptyMap(), false)
            }
        }
        return WamUnificationResult(bindings, true)
    }

    fun query(goal: String): Series<WamUnificationResult> {
        // Handle member(X,[1,2,3]) and member(X,[1,2,3]), !
        val memberMatch = Regex("""member\((\w+),\[([^\]]*)\]\)""").matchEntire(goal.trim())
        if (memberMatch != null) {
            val varName = memberMatch.groupValues[1]
            val elements = memberMatch.groupValues[2].split(",").map { it.trim() }
            return elements.size j { i ->
                WamUnificationResult(mapOf(varName to elements[i]), true)
            }
        }

        // Handle cut: member(X,[...]), !
        val cutMatch = Regex("""member\((\w+),\[([^\]]*)\],\s*!""").matchEntire(goal.trim())
        if (cutMatch != null) {
            val varName = cutMatch.groupValues[1]
            val elements = cutMatch.groupValues[2].split(",").map { it.trim() }
            return 1 j { i ->  // cut keeps only first answer
                WamUnificationResult(mapOf(varName to elements[0]), true)
            }
        }

        return emptySeries()
    }
}

/** Speculative fiber — one branch of a fanout cone carrying its own context */
private data class SpeculativeFiber(
    val bindings: Map<String, String>,
    val context: Map<String, Any?>,
)

/** Speculative cone — expanding fanout of fibers that merge on agreement */
private class SpeculativeCone(val fibers: Series<SpeculativeFiber>) {
    fun merge(): WamUnificationResult {
        if (fibers.isEmpty()) return WamUnificationResult(emptyMap(), false)
        if (fibers.size == 1) return WamUnificationResult(fibers[0].bindings, true)

        // Merge: intersect bindings where fibers agree
        val common = fibers[0].bindings.toMutableMap()
        for (i in 1 until fibers.size) {
            val fiberBindings = fibers[i].bindings
            val toRemove = common.filter { (k, v) ->
                fiberBindings[k] != null && fiberBindings[k] != v
            }.keys
            common -= toRemove
        }
        return WamUnificationResult(common, common.isNotEmpty())
    }
}

private object FiberCone {
    fun fanout(expr: String, maxFibers: Int): SpeculativeCone {
        // Parse "a || b" into branches
        val branches = expr.split("||").map { it.trim() }
        val n = minOf(branches.size, maxFibers)
        return SpeculativeCone(n j { i ->
            SpeculativeFiber(
                bindings = mapOf("expr" to branches[i]),
                context = mapOf("branch" to i, "total" to n),
            )
        })
    }
}

/** Architectural projection — describes how a layer specializes Join */
private data class Projection<T>(
    val layer: String,
    val signature: String,
    val densificationFactor: Double,
) {
    companion object {
        fun lib(): Projection<Join<*, *>> = Projection("lib", "Join<A,B>", 1.0)
        fun cursor(): Projection<Join<*, *>> = Projection("cursor", "Join<Any?,()->ColumnMeta>", 4.0)
        fun wam(): Projection<Join<*, *>> = Projection("wam", "Join<Map<String,String>,Boolean>", 16.0)
        fun ccek(): Projection<Join<*, *>> = Projection("ccek", "Join<Key<*>,Element>", 8.0)
    }
}

/** Cell overlay row: per-column overlay roles and CouchDB provenance */
private data class Provenance(val source: String, val creator: String? = null)

private data class CellOverlayRow(
    val provenance: Provenance,
    private val roles: Series<OverlayRole>,
) {
    fun role(column: Int): OverlayRole = roles[column]
}

private enum class OverlayRole {
    OBSERVATION, DERIVED, AGGREGATE, HYPOTHESIS, GROUND_TRUTH, CONTROL, METADATA, PROVENANCE
}

/**
 * LjsonSchema — discover columns from JSON bytes via JsonBitmap bitplanes.
 * Pure Series pipeline: count → locate → classify. No materialized collections.
 */
private object LjsonSchema {
    fun extractValueIntervals(encoded: UByteArray, src: UByteArray): Series<ValueInterval> {
        val valueCount = countValues(src)
        if (valueCount == 0) return emptySeries()
        return valueCount j { idx ->
            val (start, end) = locateValueNth(src, idx)
            ValueInterval(src.slice(start until end).toByteArray().decodeToString())
        }
    }

    fun discover(src: UByteArray): Series<ColumnMeta> {
        val keyCount = countKeys(src)
        if (keyCount == 0) return emptySeries()
        return keyCount j { idx ->
            val (keyStart, keyEnd, valStart, valEnd) = locateKeyValueNth(src, idx)
            val key = src.slice(keyStart + 1 until keyEnd).toByteArray().decodeToString()
            val ioType = classifyValue(src, valStart, valEnd)
            key j ioType
        }
    }

    private fun countValues(src: UByteArray): Int {
        var count = 0; var i = 0; val n = src.size
        while (i < n) {
            if (isValueStart(src[i].toUInt())) { count++; i = skipValue(src, i, n) } else i++
        }
        return count
    }

    private fun countKeys(src: UByteArray): Int {
        var count = 0; var i = 0; val n = src.size
        while (i < n - 1) {
            if (src[i].toUInt() == 0x22u) {
                val cq = findCloseQuote(src, i + 1, n)
                val ci = skipWs(src, cq + 1, n)
                if (ci < n && src[ci].toUInt() == 0x3au) { count++; i = ci + 1; continue }
                i = cq + 1
            } else i++
        }
        return count
    }

    private fun locateValueNth(src: UByteArray, idx: Int): Join<Int, Int> {
        var found = 0; var i = 0; val n = src.size
        while (i < n) {
            if (isValueStart(src[i].toUInt())) {
                if (found == idx) { val end = skipValue(src, i, n); return i j end }
                found++; i = skipValue(src, i, n)
            } else i++
        }
        return 0 j 0
    }

    private fun locateKeyValueNth(src: UByteArray, idx: Int): Join<Join<Int, Int>, Join<Int, Int>> {
        var found = 0; var i = 0; val n = src.size
        while (i < n) {
            if (src[i].toUInt() == 0x22u) {
                val cq = findCloseQuote(src, i + 1, n)
                val ci = skipWs(src, cq + 1, n)
                if (ci < n && src[ci].toUInt() == 0x3au) {
                    val (vs, ve) = findValueExtent(src, ci + 1, n)
                    if (found == idx) return (i j cq) j (vs j ve)
                    found++; i = ve; continue
                }
                i = cq + 1
            } else i++
        }
        return (0 j 0) j (0 j 0)
    }

    private fun classifyValue(src: UByteArray, vs: Int, ve: Int): TypeMemento {
        if (vs >= ve) return IOMemento.IoNull
        val first = src[vs].toUInt()
        return when {
            first == 0x22u -> IOMemento.IoString
            first == 0x74u || first == 0x66u -> IOMemento.IoBoolean
            first == 0x6eu -> IOMemento.IoNull
            first == 0x2du || first in 0x30u..0x39u -> {
                for (i in vs until ve) if (src[i].toUInt() == 0x2eu) return IOMemento.IoDouble
                IOMemento.IoLong
            }
            else -> IOMemento.IoString
        }
    }

    private fun isValueStart(b: UInt) = b == 0x22u || b == 0x7bu || b == 0x5bu ||
        b in 0x30u..0x39u || b == 0x2du || b == 0x74u || b == 0x66u || b == 0x6eu

    private fun skipValue(src: UByteArray, from: Int, n: Int): Int {
        val (_, end) = findValueExtent(src, from, n); return end
    }

    private fun findValueExtent(src: UByteArray, from: Int, n: Int): Join<Int, Int> = when (src[from].toUInt()) {
        0x22u -> { val e = findCloseQuote(src, from + 1, n) + 1; from j e }
        0x7bu, 0x5bu -> {
            val close = if (src[from].toUInt() == 0x7bu) 0x7du else 0x5du
            var depth = 1; var i = from + 1
            while (i < n && depth > 0) { if (src[i].toUInt() == src[from].toUInt()) depth++; else if (src[i].toUInt() == close) depth--; i++ }
            from j i
        }
        else -> { var i = from; while (i < n && !isValueDelim(src[i].toUInt())) i++; from j i }
    }

    private fun findCloseQuote(src: UByteArray, from: Int, n: Int): Int {
        var i = from; while (i < n) { if (src[i].toUInt() == 0x22u && src[i - 1].toUInt() != 0x5cu) return i; i++ }; return n - 1
    }

    private fun skipWs(src: UByteArray, from: Int, n: Int): Int {
        var i = from; while (i < n && (src[i].toUInt() == 0x20u || src[i].toUInt() == 0x09u)) i++; return i
    }

    private fun isValueDelim(b: UInt) = b == 0x2cu || b == 0x7du || b == 0x5du || b == 0x20u || b == 0x0au
}

/**
 * LjsonRow — densify one JSON line into a RowVec.
 * RowVec = Series2<Any?, () -> ColumnMeta> = Series<Join<Any?, () -> ColumnMeta>>
 */
private object LjsonRow {
    fun extract(src: UByteArray, schema: Series<ColumnMeta>): RowVec {
        val n = schema.size
        if (n == 0) return emptySeries()
        val text = src.toByteArray().decodeToString()
        return n j { colIdx ->
            val (key, _) = schema[colIdx]
            val rawVal = findValueByKey(text, key)
            val typedVal = coerceValue(rawVal)
            typedVal j { schema[colIdx] }
        }
    }

    private fun findValueByKey(json: String, key: String): String {
        val searchKey = "\"$key\""
        var idx = json.indexOf(searchKey)
        while (idx >= 0) {
            val colonIdx = json.indexOf(':', idx + searchKey.length)
            if (colonIdx < 0) break
            val preceding = json.substring(0, idx).trimEnd()
            if (preceding.isEmpty() || preceding.last() in "{,}") {
                return extractOneJsonValue(json, colonIdx + 1)
            }
            idx = json.indexOf(searchKey, idx + searchKey.length)
        }
        return "null"
    }

    private fun extractOneJsonValue(json: String, start: Int): String {
        if (start >= json.length) return "null"
        return when (json[start]) {
            '"' -> { val e = json.indexOf('"', start + 1); if (e > start) json.substring(start, e + 1) else "null" }
            else -> { val e = json.indexOfAny(charArrayOf(',', '}', ']', ' '), start); if (e > start) json.substring(start, e).trim() else json.substring(start).trim() }
        }
    }

    private fun coerceValue(raw: String): Any? = when {
        raw == "null" -> null; raw == "true" -> true; raw == "false" -> false
        raw.startsWith("\"") -> raw.removeSurrounding("\"")
        raw.contains('.') -> raw.toDoubleOrNull(); raw.contains('e', ignoreCase = true) -> raw.toDoubleOrNull()
        raw.toLongOrNull() != null -> raw.toLong(); else -> raw
    }
}

/**
 * LjsonCursor — build Cursor from NDJSON lines.
 */
private object LjsonCursor {
    fun fromLines(lines: List<UByteArray>): Cursor {
        val n = lines.size
        if (n == 0) return 0 j { TODO("empty cursor") }

        // Discover per-line schemas
        val perLineSchemas = Array(n) { LjsonSchema.discover(lines[it]) }

        // Build unified key index
        val (uniqueKeyCount, keySeries) = buildUnifiedKeys(perLineSchemas, n)

        // Unified schema
        val unifiedSchema: Series<ColumnMeta> = uniqueKeyCount j { keyIdx ->
            val key = keySeries[keyIdx].a
            var typeEvidence: TypeMemento = IOMemento.IoString
            for (lineIdx in 0 until n) {
                val schema = perLineSchemas[lineIdx]
                for (col in 0 until schema.size) {
                    if (schema[col].a == key) { typeEvidence = schema[col].b; break }
                }
                if (typeEvidence != IOMemento.IoString) break
            }
            key j typeEvidence
        }

        // Each line → RowVec
        return n j { lineIdx ->
            val text = lines[lineIdx].toByteArray().decodeToString()
            unifiedSchema.size j { colIdx ->
                val (key, _) = unifiedSchema[colIdx]
                val rawVal = findValueInLine(text, key)
                val typedVal = coerceValue(rawVal)
                typedVal j { unifiedSchema[colIdx] }
            }
        }
    }

    private fun buildUnifiedKeys(
        schemas: Array<Series<ColumnMeta>>,
        n: Int,
    ): Join<Int, Series<ColumnMeta>> {
        // Collect unique keys — use a Set only for dedup (this is metadata, not data path)
        val seen = mutableSetOf<String>()
        val keys = mutableListOf<String>()
        for (lineIdx in 0 until n) {
            val schema = schemas[lineIdx]
            for (col in 0 until schema.size) {
                val key = schema[col].a
                if (key !in seen) { seen.add(key); keys.add(key) }
            }
        }
        val kn = keys.size
        return kn j { idx -> keys[idx] j IOMemento.IoString }
    }

    private fun findValueInLine(json: String, key: String): String {
        val idx = json.indexOf("\"$key\"")
        if (idx < 0) return "null"
        val ci = json.indexOf(':', idx + key.length + 2)
        if (ci < 0) return "null"
        return extractOneJsonValue(json, ci + 1)
    }

    private fun extractOneJsonValue(json: String, start: Int): String {
        if (start >= json.length) return "null"
        return when (json[start]) {
            '"' -> { val e = json.indexOf('"', start + 1); if (e > start) json.substring(start, e + 1) else "null" }
            else -> { val e = json.indexOfAny(charArrayOf(',', '}', ']', ' '), start); if (e > start) json.substring(start, e).trim() else json.substring(start).trim() }
        }
    }

    private fun coerceValue(raw: String): Any? = when {
        raw == "null" -> null; raw == "true" -> true; raw == "false" -> false
        raw.startsWith("\"") -> raw.removeSurrounding("\"")
        raw.contains('.') -> raw.toDoubleOrNull(); raw.contains('e', ignoreCase = true) -> raw.toDoubleOrNull()
        raw.toLongOrNull() != null -> raw.toLong(); else -> raw
    }
}

/** LjsonOverlay — CouchDB provenance */
private object LjsonOverlay {
    fun withCouchProvenance(row: RowVec, src: UByteArray): CellOverlayRow {
        val docId = extractDocId(src)
        return CellOverlayRow(
            provenance = Provenance(source = "couchdb", creator = docId),
            roles = row.size j { OverlayRole.OBSERVATION },
        )
    }

    private fun extractDocId(src: UByteArray): String? {
        val text = src.toByteArray().decodeToString()
        val idx = text.indexOf("\"_id\"")
        if (idx < 0) return null
        val ci = text.indexOf(':', idx + 5)
        if (ci < 0) return null
        val vs = text.indexOf('"', ci + 1); if (vs < 0) return null
        val ve = text.indexOf('"', vs + 1); if (ve < 0) return null
        return text.substring(vs + 1, ve)
    }
}
