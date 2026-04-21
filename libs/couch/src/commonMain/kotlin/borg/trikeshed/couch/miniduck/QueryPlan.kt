package borg.trikeshed.couch.miniduck

/**
 * First green slice for MiniDuck: immutable query references and infix-composable
 * view query state. This deliberately stays small and does not attempt execution yet.
 */
data class RelationRef(
    val database: String,
    val name: String,
    val kind: RelationKind,
)

enum class RelationKind {
    DOCS,
    ALL_DOCS,
    VIEW,
    INDEX,
    SEGMENT,
}

sealed interface QueryPlan {
    val source: RelationRef
}

data class ScanPlan(
    override val source: RelationRef,
) : QueryPlan

data class ViewQueryPlan(
    override val source: RelationRef,
    val designDocument: String,
    val viewName: String,
    val parameters: Map<String, String> = emptyMap(),
) : QueryPlan {
    val database: String
        get() = source.database

    fun withParameter(name: String, value: Any?): ViewQueryPlan =
        copy(parameters = parameters + (name to value.toString()))
}
