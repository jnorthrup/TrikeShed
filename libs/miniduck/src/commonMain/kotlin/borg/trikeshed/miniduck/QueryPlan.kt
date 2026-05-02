package borg.trikeshed.miniduck

// Minimal QueryPlan and related types used by the Couch mini-DSL. These are stubs
// sufficient for compile-time usage in the couch module.

enum class RelationKind { VIEW, TABLE, INDEX }

data class RelationRef(val database: String, val name: String, val kind: RelationKind)

// Base query plan type
open class QueryPlan

// ViewQueryPlan carries enough data for the DSL to attach parameters
data class ViewQueryPlan(
    val source: RelationRef,
    val designDocument: String,
    val viewName: String,
    val parameters: Map<String, String> = emptyMap(),
) : QueryPlan() {
    val database: String get() = source.database
}

// Helper to attach a parameter to a QueryPlan; returns a new plan for the common stub.
fun QueryPlan.withParameter(key: String, value: Any?): QueryPlan = when (this) {
    is ViewQueryPlan -> this.copy(parameters = this.parameters + (key to (value?.toString() ?: "")))
    else -> this
}

// Overload on ViewQueryPlan so DSL functions that expect ViewQueryPlan return the right type.
fun ViewQueryPlan.withParameter(key: String, value: Any?): ViewQueryPlan = this.copy(parameters = this.parameters + (key to (value?.toString() ?: "")))
