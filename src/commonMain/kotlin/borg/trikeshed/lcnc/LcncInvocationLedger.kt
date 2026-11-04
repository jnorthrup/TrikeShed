package borg.trikeshed.lcnc

import borg.trikeshed.parse.ValueBudget
import kotlin.time.TimeSource
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Bounded observations of the existing walk, not another executor. Values are copied at the boundary. */
class LcncInvocationLedger(val limit: Int = 128, val observationLimit: Int = 128) {
    init { require(limit > 0 && observationLimit in 1..128) }

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
    private val observations = ArrayList<Map<String, Any?>>()
    private var sequence = 0L
    private var started = TimeSource.Monotonic.markNow()
    var attempted: Int = 0
        private set

    fun reset() {
        entries.clear(); attempted = 0
        observations.clear(); sequence = 0; started = TimeSource.Monotonic.markNow()
    }

    /** One walk owns this clock and sequence, including all of its nested frames. No values are inferred. */
    internal fun observe(fields: Map<String, Any?>) {
        val event = fields + mapOf("sequence" to ++sequence, "elapsedNanos" to started.elapsedNow().inWholeNanoseconds)
        try {
            if (ValueBudget(maxNodes = 256, maxChars = 4096, maxDepth = 12).violation(event) != null) return
            @Suppress("UNCHECKED_CAST") val copied = copy(event) as Map<String, Any?>
            if (observations.size == observationLimit) observations.removeAt(0)
            observations.add(copied)
        } catch (_: Exception) { /* The sequence gap records unsupported observation data. */ }
    }

    /** Selected inputs beyond the bounded selection suffix remain an explicit gap, never invented rows. */
    internal fun omitObservations(count: Int) { require(count >= 0); sequence += count }

    private fun summary(rows: List<Map<String, Any?>>): Map<String, Any?> = mapOf(
        "clockOrigin" to "walk-start", "attempted" to sequence, "retained" to rows.size,
        "omitted" to sequence - rows.size, "firstSequence" to rows.firstOrNull()?.get("sequence"),
        "lastSequence" to rows.lastOrNull()?.get("sequence"), "lastObservedSequence" to sequence,
        "coverage" to if (sequence == rows.size.toLong()) "complete" else "partial",
    )

    internal fun observationSnapshot(): Map<String, Any?> {
        val rows = observations.toMutableList()
        fun report() = mapOf("observationSchema" to OBSERVATION_SCHEMA, "observations" to rows.toList(),
            "observationsDropped" to sequence - rows.size, "observationSummary" to summary(rows))
        val budget = ValueBudget(maxNodes = 4096, maxChars = 32768, maxMillis = 250)
        while (rows.isNotEmpty() && budget.violation(report()) != null) rows.removeAt(0)
        return report()
    }

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
        val events = observations.toMutableList()
        fun report() = mapOf("entries" to rows.toList(), "attempted" to attempted,
            "dropped" to attempted - rows.size, "truncated" to (attempted != rows.size),
            "observationSchema" to OBSERVATION_SCHEMA, "observations" to events.toList(),
            "observationsDropped" to sequence - events.size, "observationSummary" to summary(events))
        // Reserve room for the run identity envelope and keep this independently readable by /lcnc/content.
        val budget = ValueBudget(maxNodes = 4096, maxChars = 65536, maxMillis = 250)
        while (budget.violation(report()) != null) {
            // Keep both streams as suffixes. Trimming one never hides omissions in the other.
            if (rows.isNotEmpty() && (rows.size >= events.size || events.isEmpty())) rows.removeAt(0)
            else if (events.isNotEmpty()) events.removeAt(0)
            else break
        }
        return report()
    }

    companion object {
        const val OBSERVATION_SCHEMA = "lcnc-observations/1"
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
