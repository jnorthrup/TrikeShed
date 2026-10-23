package borg.trikeshed.cursor.query

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.kursive.sql.Expr
import borg.trikeshed.userspace.nio.platform.spi.ColumnOperations
import borg.trikeshed.userspace.nio.platform.spi.KotlinColumnOperations
import kotlin.jvm.JvmInline

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
    class Order(
        val axis: Series<String>,
        val descending: BooleanArray,
        val limit: Int = -1,
        val offset: Int = 0,
        /** Per key, whether nulls sort first; pg's default is first exactly when descending. */
        val nullsFirst: BooleanArray = descending.copyOf(),
    ) : QueryK<Cursor>()

    /** One row per element of an array cell (Snowflake `FLATTEN`, pg / DuckDB `unnest`). */
    class Unnest(val column: String) : QueryK<Cursor>()

    /**
     * Evoke's `evoke_query(index, query[, k])`: exact BM25 of [query] over text column [column] with
     * corpus statistics from every row of the input, keeping only matching rows (the best [k] when
     * k >= 0, best first) and adding their score as column [name].
     */
    class LexicalTopK(val column: String, val query: String, val name: String, val k: Int = -1) : QueryK<Cursor>()

    /** Evoke's `evoke_hybrid_fuse_candidates(candidates, k, fusion, rrf_k, epsilon)` over a candidate table (see [fuseCandidates]). */
    class Fuse(val k: Int = 10, val method: FusionMethod = FusionMethod.RRF, val rrfK: Double = 60.0, val epsilon: Double = 1e-6) : QueryK<Cursor>()

    /**
     * A basic graph pattern over a quad table — columns `G`, `S`, `P`, `O` holding term lexemes —
     * every [patterns] match joined on shared variables, one `IoString` column per variable (its
     * lexeme) in order of first appearance. A pattern without a graph slot reads [defaultGraphs]
     * (null: the union of every graph, as Virtuoso reads an unnamed default graph); a `GRAPH ?g`
     * slot ranges over [namedGraphs] (null: every graph but [DEFAULT_GRAPH]).
     */
    class Graph(
        val patterns: Series<TriplePattern>,
        val defaultGraphs: List<String>? = null,
        val namedGraphs: List<String>? = null,
    ) : QueryK<Cursor>() {
        companion object {
            /** The lexeme a quad table files a graph-less triple under. */
            const val DEFAULT_GRAPH = "<urn:trikeshed:default-graph>"
        }
    }

    /** The provenance of a [QueryCursor]'s rows: its statement, sources and stages under one content id (see [QueryReceipt]). */
    data object Receipt : QueryK<QueryReceipt>()
}

/** One slot of a [TriplePattern]: a variable (`?name`) or a constant term as its lexeme (`<iri>`, `"lex"@en`, `"3"^^<dt>`, `_:b`). */
@JvmInline
value class PatternTerm(val text: String) {
    val isVariable: Boolean get() = text.startsWith("?")
    /** The variable's name without its `?`. */
    val name: String get() = text.substring(1)
    override fun toString(): String = text
}

/** One quad pattern of a [QueryK.Graph]: a null graph slot means the stage's default graphs. */
class TriplePattern(val s: PatternTerm, val p: PatternTerm, val o: PatternTerm, val g: PatternTerm? = null) {
    /** The slots in the quad table's column order: G, S, P, O. */
    val slots: Array<PatternTerm?> get() = arrayOf(g, s, p, o)
    override fun toString(): String = (g?.let { "GRAPH $it { " } ?: "") + "$s $p $o ." + (g?.let { " }" } ?: "")
}

/** A stage's declared name, the identity a [QueryReceipt] hashes: not reflection, so it is the same on every target and survives a rename only on purpose. */
val QueryK<*>.stageName: String
    get() = when (this) {
        is QueryK.Filter -> "Filter"
        is QueryK.Derive -> "Derive"
        is QueryK.Project -> "Project"
        is QueryK.Widen -> "Widen"
        is QueryK.Aggregate -> "Aggregate"
        is QueryK.Order -> "Order"
        is QueryK.Unnest -> "Unnest"
        is QueryK.LexicalTopK -> "LexicalTopK"
        is QueryK.Fuse -> "Fuse"
        is QueryK.Graph -> "Graph"
        QueryK.Receipt -> "Receipt"
    }

enum class Reduce { COUNT, SUM, MIN, MAX, AVG }

/** [reducer] over [column] (null: every row, `COUNT(*)`), answered as column [name]. */
data class Reduction(val reducer: Reduce, val column: String?, val name: String)

typealias QueryPlan = Series<QueryK<Cursor>>

/** The plan folded over [source], stage by stage, on the kernels in [ops]. */
fun QueryPlan.execute(
    source: Cursor,
    ops: ColumnOperations = KotlinColumnOperations,
    compiler: ExprCompiler = ClosureExprCompiler,
): Cursor {
    var cursor = source
    for (stage in this.view) cursor = cursor.facet(stage, ops, compiler)
    return cursor
}

/**
 * A Cursor that carries its plan and executes it on first read of its size or a row. When it knows
 * its [statement] (the planner's canonical text) every result column carries the statement's
 * content id as provenance and [receipt] describes the run.
 */
class QueryCursor(
    val source: Cursor,
    val plan: QueryPlan,
    val ops: ColumnOperations = KotlinColumnOperations,
    val compiler: ExprCompiler = ClosureExprCompiler,
    val statement: String? = null,
    /** Source table name → the content id of what was scanned, when the catalog knows one. */
    val sources: Map<String, borg.trikeshed.job.ContentId?> = emptyMap(),
) : Join<Int, (Int) -> RowVec> {
    private val executed: Cursor by lazy { plan.execute(source, ops, compiler) }
    /** The run's receipt; a plan without a statement has no identity to receipt, and says so. */
    val receipt: QueryReceipt by lazy {
        val text = requireNotNull(statement) { "a receipt needs the planner's statement; this QueryCursor was built from stages alone" }
        QueryReceipt(text, sources, plan.view.map { it.stageName }, executed.size)
    }
    val result: Cursor by lazy { if (statement == null) executed else executed.withProvenance(receipt.statementCid) }
    override val a: Int get() = result.size
    override val b: (Int) -> RowVec get() = result.b
}
