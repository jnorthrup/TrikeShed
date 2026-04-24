package borg.trikeshed.couch.miniduck

import borg.trikeshed.parse.kursive.sql.SqlParser
import borg.trikeshed.couch.miniduck.exec.ExecutionContext
import borg.trikeshed.couch.miniduck.exec.Cursor
import borg.trikeshed.couch.miniduck.exec.RowAccessor
import borg.trikeshed.couch.miniduck.sql.PlannerContext
import borg.trikeshed.couch.miniduck.sql.PlannerConfig
import borg.trikeshed.couch.miniduck.sql.transformSelect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * Simple SQL executor that runs a query under a SupervisorJob child of the provided scope.
 * The returned Cursor delegates to the plan's Cursor; closing the Cursor cancels the child job.
 */
object SqlExecutor {
    fun execute(scope: CoroutineScope, sql: String, execCtx: ExecutionContext): Cursor {
        val stmt = SqlParser.parse(sql) ?: throw IllegalArgumentException("Failed to parse SQL: $sql")
        val plannerCtx = PlannerContext(execCtx.schemaManager, execCtx.config)
        val plan = transformSelect(stmt, plannerCtx)

        // Child job allows cancellation of query work independent of caller's scope
        val parentJob: Job? = scope.coroutineContext[Job]
        val child = SupervisorJob(parentJob)
        // Note: we don't currently launch background coroutines for execution; child job used for cancellation tracking.

        val cur = plan.open(execCtx)

        return object : Cursor {
            override fun next(): Boolean {
                if (!child.isActive) throw CancellationException("Query cancelled")
                return cur.next()
            }

            override val row: RowAccessor
                get() = cur.row

            override fun close() {
                try { cur.close() } finally { child.cancel() }
            }
        }
    }
}
