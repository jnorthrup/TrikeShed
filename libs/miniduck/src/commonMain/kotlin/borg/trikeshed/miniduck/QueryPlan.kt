package borg.trikeshed.miniduck

import borg.trikeshed.cursor.Cursor as MiniCursor

/** Specification for ordering a column. */
data class OrderSpec(
    val column: String,
    val desc: Boolean = false,
)

/** Relation identity for plan trees and DSL surfaces. */
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
    TABLE,
}

sealed interface QueryPlan {
    val source: RelationRef
}

data class ScanPlan(
    override val source: RelationRef,
) : QueryPlan

// ViewQueryPlan carries enough data for the DSL to attach parameters.
data class ViewQueryPlan(
    override val source: RelationRef,
    val designDocument: String,
    val viewName: String,
    val parameters: Map<String, String> = emptyMap(),
) : QueryPlan {
    val database: String get() = source.database

    fun withParameter(name: String, value: Any?): ViewQueryPlan =
        copy(parameters = parameters + (name to (value?.toString() ?: "")))
}

data class FilterPlan(
    val upstream: QueryPlan,
    val predicate: Predicate,
) : QueryPlan {
    override val source: RelationRef get() = upstream.source
}

data class ProjectPlan(
    val upstream: QueryPlan,
    val columns: List<String>,
) : QueryPlan {
    override val source: RelationRef get() = upstream.source
}

data class OrderPlan(
    val upstream: QueryPlan,
    val specs: List<OrderSpec>,
) : QueryPlan {
    override val source: RelationRef get() = upstream.source
}

data class LimitPlan(
    val upstream: QueryPlan,
    val limit: Int,
    val offset: Int = 0,
) : QueryPlan {
    override val source: RelationRef get() = upstream.source
}

fun QueryPlan.withParameter(key: String, value: Any?): QueryPlan = when (this) {
    is ViewQueryPlan -> this.copy(parameters = this.parameters + (key to (value?.toString() ?: "")))
    else -> this
}

fun ViewQueryPlan.withParameter(key: String, value: Any?): ViewQueryPlan =
    this.copy(parameters = this.parameters + (key to (value?.toString() ?: "")))

infix fun QueryPlan.filter(pred: Predicate): FilterPlan = FilterPlan(this, pred)
infix fun QueryPlan.project(columns: List<String>): ProjectPlan = ProjectPlan(this, columns)
infix fun QueryPlan.orderBy(specs: List<OrderSpec>): OrderPlan = OrderPlan(this, specs)
infix fun QueryPlan.limit(n: Int): LimitPlan = LimitPlan(this, n)
infix fun QueryPlan.offset(n: Int): LimitPlan =
    (this as? LimitPlan)?.copy(offset = n) ?: LimitPlan(this, Int.MAX_VALUE, n)

fun execute(plan: QueryPlan, base: MiniCursor): MiniCursor = when (plan) {
    is ScanPlan -> base
    is ViewQueryPlan -> base
    is FilterPlan -> base.where(plan.predicate)
    is ProjectPlan -> base.project(*plan.columns.toTypedArray())
    is OrderPlan -> base.orderBy(*plan.specs.toTypedArray())
    is LimitPlan -> base.drop(plan.offset).take(plan.limit)
}
