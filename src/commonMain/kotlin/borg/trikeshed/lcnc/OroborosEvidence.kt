package borg.trikeshed.lcnc

import borg.trikeshed.parse.*

/** Fit retained evidence to the existing content reader, including JSON escaping and UTF-8 expansion. */
fun oroborosEvidence(runId: String, programCid: String, debug: OroborosState): ByteArray {
    require(runId.length <= 128 && programCid.length <= 256) { "invalid_evidence_identity" }
    val state = debug.toMutableMap()
    val monitor = (debug["monitor"] as? Map<*, *>)?.entries?.filter { it.key is String }
        ?.associate { it.key as String to it.value }?.toMutableMap()
    if (monitor != null) state["monitor"] = monitor
    val budget = ValueBudget(maxNodes = 7000, maxChars = 120000, maxMillis = 250)
    val envelope = mapOf(
        "kind" to "oroboros-debug", "runId" to runId, "programCid" to programCid,
        "debug" to state,
        "coverage" to "attached node/source observations, acknowledged receipt commits and invocation evidence before the final receipt; not all guest or external mutations",
    )

    // ValueBudget rejects opaque objects, but admits Number subclasses. Serialization must only
    // see primitive numbers and String map keys, so no arbitrary guest toString() can execute.
    fun primitive(value: Any?): Boolean = when (value) {
        null, is String, is Boolean, is Byte, is Short, is Int, is Long -> true
        is Float -> value.isFinite()
        is Double -> value.isFinite()
        is Map<*, *> -> value.entries.all { it.key is String && primitive(it.value) }
        is List<*> -> value.all(::primitive)
        is Array<*> -> value.all(::primitive)
        else -> false
    }
    fun encoded(): ByteArray? {
        if (budget.violation(envelope) != null || !primitive(envelope)) return null
        return jsonOf(envelope).encodeToByteArray().takeIf { it.size <= 1_048_576 }
    }
    fun trim(owner: MutableMap<String, Any?>?, field: String, omitted: String): Boolean {
        val rows = owner?.get(field) as? List<*> ?: return false
        if (rows.isEmpty()) return false
        val count = maxOf(1, rows.size / 2)
        owner[field] = rows.drop(count)
        owner[omitted] = ((owner[omitted] as? Number)?.toInt() ?: 0) + count
        return true
    }

    while (true) {
        encoded()?.let { return it }
        when {
            trim(monitor, "events", "eventsOmitted") -> monitor!!["complete"] = false
            trim(state, "observations", "observationsOmitted") -> Unit
            state["site"] != null && state["stateOmitted"] == null -> {
                state["site"] = (state["site"] as? Map<*, *>)?.filterKeys {
                    it in setOf("nodeId", "type", "path", "invocationOrdinal", "phase", "status", "depth")
                }
                state["stateOmitted"] = "evidence_limit"
            }
            trim(state, "interventions", "interventionsOmitted") -> Unit
            trim(state, "controls", "controlsOmitted") -> Unit
            trim(monitor, "bins", "binsOmitted") -> Unit
            else -> {
                // Unknown or oversized additions must not prevent a final receipt. The envelope
                // retains its exact identity and terminal state while explicitly omitting detail.
                val terminal = (state["state"] as? String)?.take(32)
                state.clear()
                state["state"] = terminal
                state["stateOmitted"] = "unsupported_or_excessive_evidence"
                return checkNotNull(encoded()) { "evidence_identity_exceeds_budget" }
            }
        }
    }
}
