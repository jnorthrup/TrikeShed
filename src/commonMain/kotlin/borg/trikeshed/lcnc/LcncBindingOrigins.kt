package borg.trikeshed.lcnc

import borg.trikeshed.lib.*

/** Map keys and list positions remain different occurrences, including "0" and 0. */
typealias LcncBindingPath = Series<Either<String, Int>>
/** A path's value is present only while its indexed term is true. Empty paths guard containers. */
typealias LcncBindingOrigins = Series<Join<LcncBindingPath, Int>>
enum class LcncBindingOp { ALL, ANY }
/** A root binding name, or ALL/ANY of earlier term IDs. */
typealias LcncBindingTerm = Either<String, Join<LcncBindingOp, Series<Int>>>

/** Bounded presence expressions for one retained frame; no executable nodes or effects live here. */
class LcncBindingTerms(private val maxWork: Int = 65536) {
    class Limit(val reason: String) : IllegalArgumentException(reason)

    companion object {
        const val TRUE = 0
        const val FALSE = 1
    }

    private val terms = ArrayList<LcncBindingTerm>()
    private val bindings = LinkedHashMap<String, Int>()
    private val conjunctions = HashMap<List<Int>, Int>()
    private val disjunctions = HashMap<List<Int>, Int>()
    private var work = 0

    init {
        require(maxWork in 2..65536)
        terms.add(Either.right(LcncBindingOp.ALL j emptySeriesOf()))
        terms.add(Either.right(LcncBindingOp.ANY j emptySeriesOf()))
        spend(2)
    }

    private fun spend(count: Int = 1) {
        if (count < 0 || count > maxWork - work) throw Limit("work_limit")
        work += count
    }

    /** Structural adapters and final compaction share this arena's edge/path traversal bound. */
    fun account(count: Int = 1) = spend(count)

    private fun id(value: Int): Int {
        require(value in terms.indices) { "binding_origin_term" }
        return value
    }

    /** Only the walk's exact root owner may supply a name; local shadows inherit existing terms. */
    fun binding(name: String): Int = bindings.getOrPut(name) {
        spend()
        terms.add(Either.left(name))
        terms.lastIndex
    }

    fun all(ids: Series<Int>): Int = combine(LcncBindingOp.ALL, ids)
    fun any(ids: Series<Int>): Int = combine(LcncBindingOp.ANY, ids)

    private fun combine(op: LcncBindingOp, ids: Series<Int>): Int {
        val all = op == LcncBindingOp.ALL
        require(ids.size >= 0) { "binding_origin_term" }
        spend(ids.size)
        val values = ArrayList<Int>(ids.size)
        for (index in 0 until ids.size) values.add(id(ids[index]))
        val identity = if (all) TRUE else FALSE
        val absorbing = if (all) FALSE else TRUE
        if (absorbing in values) return absorbing
        val operands = values.filter { it != identity }.distinct().sorted()
        if (operands.isEmpty()) return identity
        if (operands.size == 1) return operands[0]
        return (if (all) conjunctions else disjunctions).getOrPut(operands) {
            spend()
            terms.add(Either.right(op j operands.toSeries()))
            terms.lastIndex
        }
    }

    fun guard(term: Int): LcncBindingOrigins {
        id(term)
        return if (term == TRUE) emptySeriesOf() else {
            spend()
            s_[emptySeriesOf<Either<String, Int>>() j term]
        }
    }

    fun presence(origins: LcncBindingOrigins): Int {
        val ids = ArrayList<Int>()
        each(origins) { path, term -> if (path.isEmpty()) ids.add(term) }
        return all(ids.toSeries())
    }

    private fun steps(path: LcncBindingPath): List<Any> {
        require(path.size in 0..32) { "binding_origin_path" }
        spend(path.size)
        val value = ArrayList<Any>(path.size)
        for (index in 0 until path.size) value.add(path[index].fold({ it }, {
            require(it >= 0) { "binding_origin_path" }
            it
        }))
        return value
    }

    private fun route(value: List<Any>): LcncBindingPath {
        require(value.size <= 32) { "binding_origin_path" }
        return value α { if (it is String) Either.left<String, Int>(it) else Either.right<String, Int>(it as Int) }
    }

    private inline fun each(origins: LcncBindingOrigins, action: (List<Any>, Int) -> Unit) {
        require(origins.size >= 0) { "binding_origin_path" }
        spend(origins.size)
        for (index in 0 until origins.size) {
            val origin = origins[index]
            action(steps(origin.a), id(origin.b))
        }
    }

    private fun prefix(a: List<Any>, b: List<Any>): Boolean =
        a.size <= b.size && a.indices.all { a[it] == b[it] }

    /** Select the executed occurrence, inheriting its ancestor guards, without consulting values. */
    fun select(origins: LcncBindingOrigins, path: LcncBindingPath): LcncBindingOrigins {
        val selected = steps(path)
        val ancestors = ArrayList<Int>()
        val nested = ArrayList<Join<LcncBindingPath, Int>>()
        each(origins) { source, term ->
            when {
                prefix(source, selected) -> ancestors.add(term)
                prefix(selected, source) -> nested.add(route(source.drop(selected.size)) j term)
            }
        }
        val result = ArrayList<Join<LcncBindingPath, Int>>()
        val presence = all(ancestors.toSeries())
        if (presence != TRUE) result.add(emptySeriesOf<Either<String, Int>>() j presence)
        result.addAll(nested)
        return result.toSeries()
    }

    fun prefix(origins: LcncBindingOrigins, path: LcncBindingPath): LcncBindingOrigins {
        val enclosing = steps(path)
        val result = ArrayList<Join<LcncBindingPath, Int>>()
        each(origins) { source, term ->
            val nested = enclosing + source
            if (nested.size > 32) throw Limit("depth_limit")
            spend(enclosing.size)
            result.add(route(nested) j term)
        }
        return result.toSeries()
    }

    fun join(parts: Series<LcncBindingOrigins>): LcncBindingOrigins {
        require(parts.size >= 0) { "binding_origin_path" }
        spend(parts.size)
        val result = ArrayList<Join<LcncBindingPath, Int>>()
        for (index in 0 until parts.size) each(parts[index]) { path, term -> result.add(route(path) j term) }
        return result.toSeries()
    }

    /** Evaluate in term order; long aliases reuse IDs instead of becoming recursive expressions. */
    fun evaluate(liveBindings: Set<String>): BooleanArray {
        val live = BooleanArray(terms.size)
        for (index in terms.indices) {
            spend()
            val term = terms[index]
            val binding = term.leftOrNull
            live[index] = if (binding != null) binding in liveBindings else {
                val expression = checkNotNull(term.rightOrNull)
                var value = expression.a == LcncBindingOp.ALL
                for (operand in expression.b.view) {
                    spend()
                    check(operand < index)
                    value = if (expression.a == LcncBindingOp.ALL) value && live[operand] else value || live[operand]
                }
                value
            }
        }
        return live
    }

    /** Hydration checks paths against the saved frame before any selected node can run. */
    fun validate(value: Any?, origins: LcncBindingOrigins) {
        each(origins) { path, _ ->
            var current = value
            for (step in path) {
                spend()
                current = when (val parent = current) {
                    is Map<*, *> -> {
                        require(step is String && parent.containsKey(step)) { "binding_origin_occurrence" }
                        parent[step]
                    }
                    is List<*> -> {
                        require(step is Int && step in parent.indices) { "binding_origin_occurrence" }
                        parent[step]
                    }
                    is Array<*> -> {
                        require(step is Int && step in parent.indices) { "binding_origin_occurrence" }
                        parent[step]
                    }
                    else -> throw IllegalArgumentException("binding_origin_occurrence")
                }
            }
            require(current !== LcncProjection.Absent) { "binding_origin_occurrence" }
        }
    }

    /** Omitted slots keep their original positions until final frame compaction. */
    fun mask(value: Any?, origins: LcncBindingOrigins, liveBindings: Set<String>): Any? =
        mask(value, origins, evaluate(liveBindings))

    fun mask(value: Any?, origins: LcncBindingOrigins, live: BooleanArray): Any? {
        // The arena only appends: an earlier evaluation still covers its original term prefix.
        require(live.size in 2..terms.size) { "binding_origin_evaluation" }
        val guards = HashMap<List<Any>, MutableList<Int>>()
        each(origins) { path, term ->
            require(term < live.size) { "binding_origin_evaluation" }
            guards.getOrPut(path) { ArrayList() }.add(term)
        }
        fun visit(value: Any?, path: List<Any>): Any? {
            spend()
            if (path.size > 32) throw Limit("depth_limit")
            if (guards[path]?.any { !live[it] } == true) return LcncProjection.Absent
            return when (value) {
                LcncProjection.Absent -> value
                is Map<*, *> -> buildMap {
                    for ((key, child) in value) {
                        require(key is String) { "binding_origin_map_key" }
                        put(key, visit(child, path + key))
                    }
                }
                is List<*> -> buildList {
                    for ((index, child) in value.withIndex()) add(visit(child, path + index))
                }
                is Array<*> -> buildList {
                    for ((index, child) in value.withIndex()) add(visit(child, path + index))
                }.toTypedArray()
                else -> value
            }
        }
        return visit(value, emptyList())
    }

    /** Plain CBOR-compatible values. Names are interned once; only reachable terms are exported. */
    fun export(origins: LcncBindingOrigins, liveBindings: Set<String>): Map<String, Any?> {
        // Retired root names become FALSE permanently, including inside surviving ANY terms.
        val folded = IntArray(terms.size)
        folded[TRUE] = TRUE
        folded[FALSE] = FALSE
        for (index in 2 until folded.size) {
            spend()
            val term = terms[index]
            val name = term.leftOrNull
            folded[index] = if (name != null) { if (name in liveBindings) index else FALSE } else {
                val expression = checkNotNull(term.rightOrNull)
                combine(expression.a, expression.b α { folded[it] })
            }
        }
        val rows = ArrayList<Join<List<Any>, Int>>()
        each(origins) { path, term -> if (folded[term] != TRUE) rows.add(path j folded[term]) }
        val reachable = BooleanArray(terms.size)
        for (row in rows) reachable[row.b] = true
        for (index in terms.indices.reversed()) if (reachable[index]) {
            spend()
            terms[index].rightOrNull?.b?.view?.forEach { operand -> spend(); reachable[operand] = true }
        }
        val names = LinkedHashMap<String, Int>()
        val encoded = ArrayList<List<Any>>()
        val remap = IntArray(terms.size) { -1 }
        remap[TRUE] = TRUE
        remap[FALSE] = FALSE
        for (index in 2 until terms.size) if (reachable[index]) {
            val term = terms[index]
            val name = term.leftOrNull
            encoded.add(if (name != null) listOf(0, names.getOrPut(name) { names.size }) else {
                val expression = checkNotNull(term.rightOrNull)
                listOf(if (expression.a == LcncBindingOp.ALL) 1 else 2) + expression.b.view.map { check(remap[it] >= 0); remap[it] }
            })
            remap[index] = encoded.size + 1
        }
        return linkedMapOf("bindings" to names.keys.toList(), "terms" to encoded,
            "origins" to rows.map { listOf(remap[it.b]) + it.a })
    }

    /** Import only authenticated frame data; malformed/forward/truncated numeric references are loud. */
    fun decode(value: Any?, rootBindings: Set<String>): LcncBindingOrigins {
        fun invalid(): Nothing = throw IllegalArgumentException("binding_origin_format")
        fun integer(value: Any?): Int {
            val exact = when (value) {
                is Byte -> value.toDouble()
                is Short -> value.toDouble()
                is Int -> value.toDouble()
                is Long -> {
                    if (value !in 0..Int.MAX_VALUE.toLong()) invalid()
                    value.toDouble()
                }
                is Float -> value.toDouble()
                is Double -> value
                else -> invalid()
            }
            if (!exact.isFinite() || exact < 0 || exact > Int.MAX_VALUE.toDouble() || exact % 1.0 != 0.0) invalid()
            return exact.toInt()
        }
        val document = value as? Map<*, *> ?: invalid()
        if (document.keys != setOf("bindings", "terms", "origins")) invalid()
        val names = document["bindings"] as? List<*> ?: invalid()
        spend(names.size)
        if (names.any { it !is String || it !in rootBindings } || names.distinct().size != names.size) invalid()
        if (names.sumOf { (it as String).length.toLong() } > 524288L) throw Limit("payload_limit")
        val encoded = document["terms"] as? List<*> ?: invalid()
        spend(encoded.size)
        val remap = ArrayList<Int>(encoded.size + 2)
        remap.add(TRUE); remap.add(FALSE)
        for (raw in encoded) {
            val term = raw as? List<*> ?: invalid()
            if (term.isEmpty()) invalid()
            val kind = integer(term[0])
            val imported = if (kind == 0) {
                if (term.size != 2) invalid()
                val index = integer(term[1])
                if (index !in names.indices) invalid()
                binding(names[index] as String)
            } else {
                if (kind !in 1..2) invalid()
                spend(term.size - 1)
                val operands = ArrayList<Int>(term.size - 1)
                for (operand in term.drop(1)) {
                    val index = integer(operand)
                    if (index !in remap.indices) invalid()
                    operands.add(remap[index])
                }
                if (kind == 1) all(operands.toSeries()) else any(operands.toSeries())
            }
            remap.add(imported)
        }
        val rawOrigins = document["origins"] as? List<*> ?: invalid()
        spend(rawOrigins.size)
        val origins = ArrayList<Join<LcncBindingPath, Int>>(rawOrigins.size)
        for (raw in rawOrigins) {
            val row = raw as? List<*> ?: invalid()
            if (row.isEmpty() || row.size > 33) invalid()
            spend(row.size - 1)
            val term = integer(row[0])
            if (term !in remap.indices) invalid()
            val path = ArrayList<Any>(row.size - 1)
            for (step in row.drop(1)) path.add(if (step is String) step else integer(step))
            origins.add(route(path) j remap[term])
        }
        return origins.toSeries()
    }
}
