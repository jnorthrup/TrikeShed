package borg.trikeshed.miniduck

import borg.trikeshed.cursor.RowVec

// Keep minimal non-conflicting symbols here. The richer RowVec family implementations
// live in RowVecFamilies.kt to avoid redeclaration collisions during the build.

/** MiniRowVec - unified contract, exposed from cursor package */
typealias MiniRowVec = RowVec

/** MiniCursor - unified contract, exposed from cursor package */
typealias MiniCursor = borg.trikeshed.cursor.Cursor

/** Cursor - unified contract, exposed from cursor package */
typealias Cursor = borg.trikeshed.cursor.Cursor

// ── Note
// Previously this file contained a number of factory helpers and typealiases that
// conflicted with the RowVecFamilies.kt stubs added to satisfy tests. Those
// factories and concrete RowVec classes are now provided by RowVecFamilies.kt.
// Keep only non-conflicting utilities and query-plan types here.

// ── Query Plan types ─────────────────────────────────────────────────────────

enum class RelationKind {
    DOCS,
    VIEW,
    INDEX,
    LOCAL,
}

data class RelationRef(
    val database: String,
    val name: String,
    val kind: RelationKind,
)

interface QueryPlan {
    val source: RelationRef
}

data class ViewQueryPlan(
    override val source: RelationRef,
    val designDocument: String = "",
    val viewName: String = "",
    val parameters: Map<String, String> = emptyMap(),
) : QueryPlan

fun ViewQueryPlan.withParameter(key: String, value: Any?): ViewQueryPlan =
    copy(parameters = parameters + (key to value.toString()))
