package borg.trikeshed.vm

import borg.trikeshed.lib.*

/** Copied root-return profiles, not a statement trace or source-level breakpoint state. */
data class VmRootProfile(
    val root: String, val source: String?, val line: Int, val column: Int,
    val calls: Long, val phase: String,
) {
    fun toMap(): Map<String, Any?> = mapOf("root" to root, "source" to source, "line" to line,
        "column" to column, "calls" to calls, "phase" to phase)
}

data class VmPointcuts(val roots: Series<VmRootProfile>, val total: Int) {
    init { require(total >= roots.size) }

    fun toMap(): Map<String, Any?> = mapOf("kind" to "root-profiles", "total" to total,
        "omitted" to total - roots.size, "roots" to (roots α { it.toMap() }).toList())

    companion object { const val LIMIT = 8 }
}
