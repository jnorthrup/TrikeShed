package borg.trikeshed.cursor.query

import borg.trikeshed.lib.Series
import borg.trikeshed.parse.kursive.sql.Expr
import borg.trikeshed.parse.kursive.sql.RowExpr
import borg.trikeshed.parse.kursive.sql.compile
import kotlin.coroutines.CoroutineContext

/**
 * Turns a SQL scalar expression into a [RowExpr] for the column [names] of the Cursor it will
 * read. The default builds closures; a platform provider may emit and load a class per
 * statement (the JVM's `ClassfileExprCompiler`, keyed by the content id of the statement and
 * its column layout). Every provider evaluates through `SqlRuntime`, so the answers are equal.
 */
interface ExprCompiler : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ExprCompiler>
    override val key: CoroutineContext.Key<*> get() = Key

    /** Which implementation compiled: `closure`, or the provider's identity. */
    val identity: String

    fun compile(expr: Expr, names: Series<CharSequence>): RowExpr
}

/** The closure compiler: the default provider and the reference every other provider must equal. */
object ClosureExprCompiler : ExprCompiler {
    override val identity: String = "closure"
    override fun compile(expr: Expr, names: Series<CharSequence>): RowExpr = expr.compile(names)
}
