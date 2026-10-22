package borg.trikeshed.cursor.query

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.kursive.sql.Expr

/**
 * QueryK — plan stages as facet keys over a [Cursor].
 *
 * Each key carries its arguments (the `MultiIndexK.ByHash` precedent) and fixes its result
 * type; [facet] is the one dispatcher. Column arguments are names, resolved against the
 * incoming Cursor's row-0 metadata when the stage executes (exact name, else a unique
 * `qualifier.name` suffix), so a plan composes before any row exists.
 *
 * Every SQL surface emulated here — pg, DuckDB, Snowflake semi-structured access — lowers to
 * these stages; the planner is `SelectStmt.plan`.
 */
sealed class QueryK<out R> : OpK<R>() {
    /** Rows where [where] evaluates to true; lazy, the match vector computes once on first read. */
    class Filter(val where: Expr) : QueryK<Cursor>()

    /** Appends one computed column. */
    class Derive(val name: String, val expr: Expr) : QueryK<Cursor>()

    /** Keeps and renames columns: each pair is source name `j` output name; source `*` keeps all. */
    class Project(val columns: Series<Twin<String>>) : QueryK<Cursor>()

    /** Equi-join with [right] on (left name `j` right name) pairs; [outer] keeps unmatched left rows. */
    class Widen(val right: Cursor, val on: Series<Twin<String>>, val outer: Boolean = false) : QueryK<Cursor>()

    /** One row per distinct [axis] key: the axis columns, then one column per reduction. */
    class Aggregate(val axis: Series<String>, val reductions: Series<Reduction>) : QueryK<Cursor>()

    /** Stable sort on [axis] (nulls last ascending, first descending), then `OFFSET`/`LIMIT`. */
    class Order(val axis: Series<String>, val descending: BooleanArray, val limit: Int = -1, val offset: Int = 0) : QueryK<Cursor>()

    /** One row per element of an array cell (Snowflake `FLATTEN`, pg / DuckDB `unnest`). */
    class Unnest(val column: String) : QueryK<Cursor>()
}

enum class Reduce { COUNT, SUM, MIN, MAX, AVG }

/** [reducer] over [column] (null: every row, `COUNT(*)`), answered as column [name]. */
data class Reduction(val reducer: Reduce, val column: String?, val name: String)

typealias QueryPlan = Series<QueryK<Cursor>>

/** The plan folded over [source], stage by stage. */
fun QueryPlan.execute(source: Cursor): Cursor {
    var cursor = source
    for (stage in this.view) cursor = cursor.facet(stage)
    return cursor
}

/** A Cursor that carries its plan and executes it on first read of its size or a row. */
class QueryCursor(val source: Cursor, val plan: QueryPlan) : Join<Int, (Int) -> RowVec> {
    val result: Cursor by lazy { plan.execute(source) }
    override val a: Int get() = result.size
    override val b: (Int) -> RowVec get() = result.b
}
