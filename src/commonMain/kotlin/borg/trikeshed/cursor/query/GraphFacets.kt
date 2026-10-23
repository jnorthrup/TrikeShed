package borg.trikeshed.cursor.query

import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.ReifiedSplitSeries2
import borg.trikeshed.lib.*
import borg.trikeshed.lib.intp.Comparison
import borg.trikeshed.parse.kursive.sql.scalarText

/**
 * The [QueryK.Graph] stage: a basic graph pattern over a quad table, two ways to one answer.
 *
 * The closure path ([graph]) reads any Cursor with `G`, `S`, `P`, `O` columns as lexeme strings and
 * joins pattern by pattern through a hash index keyed by the variables already bound. The routed path
 * ([routeGraph]) runs the same join over a [ColumnCursor] whose four columns are [DictColumn]s sharing
 * one dictionary (a quad store's scan): constants select by code, shared variables key the
 * `hashBuild`/`probe` kernels, and the result keeps the dictionary — no string is built until a row is
 * read. Both answer the same rows in the same order: bindings of the pattern before, each followed by
 * its matching quads in table order.
 */

/** The quad table's column names, in slot order. */
val QUAD_COLUMNS = arrayOf("G", "S", "P", "O")

/** Variables of a graph stage in order of first appearance. */
fun QueryK.Graph.variables(): List<String> {
    val vars = ArrayList<String>()
    for (pattern in patterns.view) for (slot in pattern.slots) if (slot != null && slot.isVariable && slot.name !in vars) vars += slot.name
    return vars
}

/** Whether a quad in graph [g] is readable by [pattern] under the stage's dataset. */
fun QueryK.Graph.allows(pattern: TriplePattern, g: String?): Boolean {
    if (g == null) return false
    val slot = pattern.g
    return when {
        slot == null -> defaultGraphs == null || g in defaultGraphs
        slot.isVariable -> g != QueryK.Graph.DEFAULT_GRAPH && (namedGraphs == null || g in namedGraphs)
        else -> g == slot.text && (namedGraphs == null || g in namedGraphs)
    }
}

/** The closure path: any Cursor with the four quad columns. */
fun Cursor.graph(stage: QueryK.Graph): Cursor {
    val vars = stage.variables()
    val metas: Series<`ColumnMeta↻`> = vars.size j { c: Int -> ColumnMeta(vars[c], IOMemento.IoString).let { m -> { m } } }
    if (stage.patterns.size == 0) return 1 j { _: Int -> ReifiedSplitSeries2(emptySeriesOf<Any?>(), metas) }
    if (size == 0) return emptySeriesOf()
    val names = names
    val ordinal = IntArray(4) { i -> (0 until names.size).firstOrNull { names[it].contentEquals(QUAD_COLUMNS[i]) || names[it].endsWith("." + QUAD_COLUMNS[i]) } ?: error("a graph pattern needs a ${QUAD_COLUMNS[i]} column") }
    val n = size
    val cells = Array(4) { i -> Array(n) { r -> this[r].values[ordinal[i]]?.let(::scalarText) } }
    var bindings: List<Array<String?>> = listOf(arrayOfNulls(vars.size))
    val bound = BooleanArray(vars.size)
    for (pattern in stage.patterns.view) {
        val slots = pattern.slots
        val slotVar = IntArray(4) { i -> slots[i]?.takeIf { it.isVariable }?.let { vars.indexOf(it.name) } ?: -1 }
        val slotConst = Array(4) { i -> slots[i]?.takeIf { !it.isVariable }?.text }
        val keyVars = (0..3).mapNotNull { i -> slotVar[i].takeIf { it >= 0 && bound[it] } }.distinct()
        val keySlot = IntArray(keyVars.size) { k -> (0..3).first { slotVar[it] == keyVars[k] } }
        val index = HashMap<String, IntArrayList>()
        row@ for (r in 0 until n) {
            if (!stage.allows(pattern, cells[0][r])) continue
            for (i in 0..3) {
                val cell = cells[i][r] ?: continue@row
                val k = slotConst[i]
                if (k != null && cell != k) continue@row
                val v = slotVar[i]
                if (v >= 0) for (j in i + 1..3) if (slotVar[j] == v && cells[j][r] != cell) continue@row
            }
            index.getOrPut(keySlot.joinToString("\u0000") { cells[it][r]!! }) { IntArrayList() }.add(r)
        }
        val next = ArrayList<Array<String?>>()
        for (binding in bindings) {
            val matches = index[keyVars.joinToString("\u0000") { binding[it]!! }] ?: continue
            for (i in 0 until matches.size) {
                val r = matches[i]
                val out = binding.copyOf()
                for (slot in 0..3) { val v = slotVar[slot]; if (v >= 0) out[v] = cells[slot][r] }
                next += out
            }
        }
        bindings = next
        for (v in slotVar) if (v >= 0) bound[v] = true
    }
    val rows = bindings
    return rows.size j { r: Int -> ReifiedSplitSeries2(vars.size j { c: Int -> rows[r][c] as Any? }, metas) }
}

/** The routed path: dictionary-coded quad columns, kernels for selection and join; null when the shape is not covered. */
internal fun ColumnCursor.routeGraph(stage: QueryK.Graph): Cursor? {
    if (rows == 0 || stage.patterns.size == 0) return null
    val ordinal = IntArray(4)
    for (i in 0..3) ordinal[i] = (0 until width).firstOrNull { metas[it].name.contentEquals(QUAD_COLUMNS[i]) || metas[it].name.endsWith("." + QUAD_COLUMNS[i]) } ?: return null
    val dicts = ArrayList<DictColumn>(4)
    for (i in 0..3) {
        val column = dense(ordinal[i])
        if (column.valid != null) return null
        dicts += column.data as? DictColumn ?: return null
    }
    val dictionary = dicts[0].dictionary
    for (d in dicts) if (d.dictionary !== dictionary) return null
    val codes = Array(4) { dicts[it].codes }
    val vars = stage.variables()
    if (vars.isEmpty()) return null // a pattern of constants (ASK) answers rows with no columns, which only the row shape can carry
    val boundVars = ArrayList<Int>()
    val boundCols = ArrayList<IntArray>()
    var boundRows = -1
    val codeOf = HashMap<String, Int>()
    for (i in dictionary.indices) if (dictionary[i] !in codeOf) codeOf[dictionary[i]] = i
    for (pattern in stage.patterns.view) {
        val slots = pattern.slots
        val slotVar = IntArray(4) { i -> slots[i]?.takeIf { it.isVariable }?.let { vars.indexOf(it.name) } ?: -1 }
        for (i in 0..3) for (j in i + 1..3) if (slotVar[i] >= 0 && slotVar[i] == slotVar[j]) return null
        // 1. the quads the pattern's constants and graph scope admit, as a selection in table order
        val sel = IntArray(rows)
        var m = -1
        var empty = false
        for (i in 0..3) {
            val constant = slots[i]?.takeIf { !it.isVariable }?.text ?: continue
            val code = codeOf[constant] ?: run { empty = true; -1 }
            if (empty) break
            m = if (m < 0) borg.trikeshed.lib.intp.where(codes[i], rows, Comparison.EQ, code, sel)
            else borg.trikeshed.lib.intp.refine(codes[i], sel, m, Comparison.EQ, code, sel)
        }
        if (empty) m = 0 else if (m < 0) { identity.copyInto(sel); m = rows }
        if (m > 0) {
            val allowed = BooleanArray(dictionary.size) { stage.allows(pattern, dictionary[it]) }
            var w = 0
            for (i in 0 until m) if (allowed[codes[0][sel[i]]]) sel[w++] = sel[i]
            m = w
        }
        // 2. join with the bindings so far on the variables both sides know
        val patternVars = (0..3).mapNotNull { i -> slotVar[i].takeIf { it >= 0 } }
        fun slotOf(v: Int): Int = (0..3).first { slotVar[it] == v }
        if (boundRows < 0) {
            for (v in patternVars) { boundVars += v; boundCols += borg.trikeshed.lib.intp.gather(codes[slotOf(v)], sel, m) }
            boundRows = m
            continue
        }
        val shared = patternVars.filter { it in boundVars }
        val buildKeys = LongArray(m)
        val probeKeys = LongArray(boundRows)
        var radix = 1L
        for (v in shared) {
            val size = maxOf(1, dictionary.size)
            if (radix > Long.MAX_VALUE / size) return null
            val build = codes[slotOf(v)]
            for (i in 0 until m) buildKeys[i] = buildKeys[i] * size + build[sel[i]]
            val probe = boundCols[boundVars.indexOf(v)]
            for (i in 0 until boundRows) probeKeys[i] = probeKeys[i] * size + probe[i]
            radix *= size
        }
        val pairs: Int
        val outProbe: IntArray
        val outBuild: IntArray
        if (m == 0) { pairs = 0; outProbe = IntArray(0); outBuild = IntArray(0) } else {
            val head = IntArray(borg.trikeshed.lib.longp.tableSizeFor(m)) { -1 }
            val next = IntArray(m)
            borg.trikeshed.lib.longp.hashBuild(buildKeys, m, head, next)
            var capacity = maxOf(boundRows, 1024)
            while (true) {
                val p = IntArray(capacity)
                val b = IntArray(capacity)
                val count = borg.trikeshed.lib.longp.probe(probeKeys, boundRows, buildKeys, head, next, p, b)
                if (count < 0) { capacity *= 2; continue }
                pairs = count; outProbe = p; outBuild = b
                break
            }
        }
        for (i in boundCols.indices) boundCols[i] = borg.trikeshed.lib.intp.gather(boundCols[i], outProbe, pairs)
        for (v in patternVars) if (v !in shared) {
            val column = codes[slotOf(v)]
            boundVars += v
            boundCols += IntArray(pairs) { column[sel[outBuild[it]]] }
        }
        boundRows = pairs
    }
    return ColumnCursor.of(
        Array(boundVars.size) { ColumnMeta(vars[boundVars[it]], IOMemento.IoString) },
        Array(boundVars.size) { DenseColumn(DictColumn(boundCols[it], dictionary)) },
    )
}
