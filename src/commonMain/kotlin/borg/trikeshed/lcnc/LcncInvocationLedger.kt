package borg.trikeshed.lcnc

import borg.trikeshed.parse.ValueBudget
import kotlin.time.TimeSource
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Bounded observations of the existing walk, not another executor. Values are copied at the boundary. */
class LcncInvocationLedger(val limit: Int = 128) {
    init { require(limit > 0) }

    class Invocation(val values: MutableMap<String, Any?>) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Invocation>
        val started = TimeSource.Monotonic.markNow()

        fun capture(port: String, value: Any?) {
            try {
                val over = (if (port == "runtime") ValueBudget(maxNodes = 512, maxChars = 8192, maxDepth = 12)
                    else ValueBudget(maxNodes = 128, maxChars = 2048, maxDepth = 12)).violation(value)
                if (over != null) values[port + "Omitted"] = over
                else values[port] = copy(value)
            } catch (_: Exception) { values[port + "Omitted"] = "unsupported_or_unreadable_value" }
        }

        /** Observation failure cannot replace a guest failure or prevent its cleanup. */
        fun runtime(snapshot: () -> Any?) {
            try { capture("runtime", snapshot()) }
            catch (_: Throwable) { values["runtimeOmitted"] = "unavailable" }
        }

        fun finish(status: String, reason: String?, outputs: Map<String, Any?>?) {
            values["status"] = status
            values["elapsedMs"] = started.elapsedNow().inWholeMilliseconds
            reason?.let { values["reason"] = it.take(512) }
            if (outputs != null) capture("outputs", outputs)
        }
    }

    private val entries = ArrayList<Invocation>()
    var attempted: Int = 0
        private set

    fun reset() { entries.clear(); attempted = 0 }

    fun begin(ordinal: Int, node: LcncNode, path: List<String>, parent: Int?, iteration: Int?): Invocation? {
        attempted++
        val fields = linkedMapOf<String, Any?>(
            "ordinal" to ordinal, "nodeId" to node.id, "type" to node.type,
            "path" to path.toList(), "parent" to parent, "iteration" to iteration, "status" to "running",
        )
        if (ValueBudget(maxNodes = 128, maxChars = 4096).violation(fields) != null) return null
        if (entries.size == limit) entries.removeAt(0)
        return Invocation(fields).also(entries::add)
    }

    /** Last admitted invocations, in entry order. A missing parent may have fallen outside the retained window. */
    fun snapshot(): Map<String, Any?> {
        val rows = entries.mapTo(ArrayList()) { it.values.toMap() }
        fun report() = mapOf("entries" to rows.toList(), "attempted" to attempted,
            "dropped" to attempted - rows.size, "truncated" to (attempted != rows.size))
        // Reserve room for the run identity envelope and keep this independently readable by /lcnc/content.
        val budget = ValueBudget(maxNodes = 4096, maxChars = 65536, maxMillis = 250)
        while (rows.isNotEmpty() && budget.violation(report()) != null) rows.removeAt(0)
        return report()
    }

    companion object {
        /** No host objects, handles, arbitrary toString(), aliases or non-finite JSON numbers enter receipts. */
        private fun copy(value: Any?): Any? = when (value) {
            null, is String, is Boolean, is Byte, is Short, is Int, is Long -> value
            is Float -> value.also { require(it.isFinite()) }
            is Double -> value.also { require(it.isFinite()) }
            is Map<*, *> -> value.entries.associate { (k, v) -> require(k is String); k to copy(v) }
            is List<*> -> value.map(::copy)
            is Array<*> -> value.map(::copy)
            else -> throw IllegalArgumentException("unsupported_value")
        }
    }
}
