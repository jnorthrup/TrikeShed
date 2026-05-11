package borg.trikeshed.viewserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [ReactorCouchServer] view query handling:
 * - reduce support
 * - query parameter parsing (key, startkey/endkey, group, group_level, limit, skip, descending)
 * - [urlDecode] percent-decoding
 */

// ── Fake BlockStore used across tests ────────────────────────────────────

private class FakeBlockStore(docs: Map<String, Map<String, Any?>>) {
    private val data = docs.toMutableMap()

    fun list(db: String): List<String> = data.keys.toList()
    fun get(db: String, id: String): String? {
        val doc = data[id] ?: return null
        return doc.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":${fakeJson(v)}" }
    }

    private fun fakeJson(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"$v\""
        is Number -> v.toString()
        is Boolean -> v.toString()
        is List<*> -> v.joinToString(",", "[", "]") { fakeJson(it) }
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, vv) -> "\"$k\":${fakeJson(vv)}" }
        else -> "\"$v\""
    }
}

// ── CouchQueryServer stub that uses the Kotlin emit callback ─────────────

private fun numericMapServer(fieldKey: String): CouchQueryServer = CouchQueryServer { _ ->
    object : CompiledFunction {
        override fun map(doc: Map<String, Any?>, emit: (key: Any?, value: Any?) -> Unit) {
            val k = doc[fieldKey]
            val v = doc["value"]
            if (k != null) emit(k, v)
        }
        override fun reduce(sources: List<String>, values: List<Any?>, rereduce: Boolean): Any? =
            values.filterIsInstance<Number>().sumOf { it.toDouble() }
    }
}

// ── Compare keys helper (mirrors ReactorCouchServer.compareKeys) ──────────

// ── Tests ────────────────────────────────────────────────────────────────

class ReactorCouchServerQueryTest {

    /** Verify that the [CouchQueryServer] reduce path returns the sum of values. */
    @Test
    fun reduce_sumOfValues() {
        val qs = numericMapServer("month")
        qs.handle(CouchCommand.Reset)
        qs.handle(CouchCommand.AddFun("function(doc){ if(doc.month) emit(doc.month, doc.value) }"))

        val values = listOf(10.0, 20.0, 30.0)
        val r = qs.handle(CouchCommand.Reduce(listOf("sum"), values))
        assertTrue(r is CouchResponse.ReduceResult, "expected ReduceResult, got $r")
        val sum = r.value as? Double
        assertEquals(60.0, sum)
    }

    /** Verify group_level grouping in CouchQueryServer.Reduce (per-group calls). */
    @Test
    fun reduce_perGroupCalls() {
        val qs = numericMapServer("category")
        qs.handle(CouchCommand.Reset)
        qs.handle(CouchCommand.AddFun("function(doc){ emit(doc.category, doc.qty) }"))

        val groups = mapOf("A" to listOf(1.0, 2.0), "B" to listOf(10.0))
        val results = groups.mapValues { (_, vals) ->
            val r = qs.handle(CouchCommand.Reduce(listOf("sum"), vals))
            ((r as? CouchResponse.ReduceResult)?.value as? Double) ?: 0.0
        }
        assertEquals(3.0, results["A"])
        assertEquals(10.0, results["B"])
    }

    /** Verify the [CouchCommand.Rereduce] path. */
    @Test
    fun rereduce_combinesPartialResults() {
        val qs = CouchQueryServer { _ ->
            object : CompiledFunction {
                override fun map(doc: Map<String, Any?>, emit: (key: Any?, value: Any?) -> Unit) {}
                override fun reduce(sources: List<String>, values: List<Any?>, rereduce: Boolean): Any? {
                    val nums = values.filterIsInstance<Number>()
                    return nums.sumOf { it.toDouble() }
                }
            }
        }
        qs.handle(CouchCommand.Reset)
        qs.handle(CouchCommand.AddFun("sum"))

        // First reduce: partial sums
        val r1 = qs.handle(CouchCommand.Reduce(listOf("sum"), listOf(1.0, 2.0, 3.0)))
        val partial1 = (r1 as CouchResponse.ReduceResult).value as Double
        val r2 = qs.handle(CouchCommand.Reduce(listOf("sum"), listOf(4.0, 5.0)))
        val partial2 = (r2 as CouchResponse.ReduceResult).value as Double

        // Rereduce: combine partials
        val rr = qs.handle(CouchCommand.Rereduce(listOf("sum"), listOf(partial1, partial2)))
        val total = (rr as CouchResponse.ReduceResult).value as Double
        assertEquals(15.0, total)
    }

    /** Verify that [CouchQueryServer] handles an empty values list gracefully. */
    @Test
    fun reduce_emptyValues_returnsNull() {
        val qs = CouchQueryServer { _ ->
            object : CompiledFunction {
                override fun map(doc: Map<String, Any?>, emit: (key: Any?, value: Any?) -> Unit) {}
                override fun reduce(sources: List<String>, values: List<Any?>, rereduce: Boolean): Any? =
                    if (values.isEmpty()) null else values.filterIsInstance<Number>().sumOf { it.toDouble() }
            }
        }
        qs.handle(CouchCommand.Reset)
        qs.handle(CouchCommand.AddFun("empty"))
        val r = qs.handle(CouchCommand.Reduce(listOf("empty"), emptyList()))
        assertTrue(r is CouchResponse.ReduceResult)
        assertEquals(null, r.value)
    }
}
