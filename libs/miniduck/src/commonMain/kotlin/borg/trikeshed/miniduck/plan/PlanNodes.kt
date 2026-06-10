package borg.trikeshed.miniduck.plan

import borg.trikeshed.miniduck.exec.ExecutionContext
import borg.trikeshed.miniduck.exec.Cursor
import borg.trikeshed.miniduck.exec.RowAccessor

/**
 * Minimal PlanNode skeletons (lazy semantics to be implemented).
 */

interface PlanNode {
    fun open(execCtx: ExecutionContext): Cursor
}

class TableScanNode(val tableName: String, val alias: String? = null) : PlanNode {
    override fun open(execCtx: ExecutionContext): Cursor {
        return execCtx.tableSource.open(execCtx, tableName)
    }
}

class FilterNode(val child: PlanNode, val predicate: (RowAccessor) -> Boolean) : PlanNode {
    override fun open(execCtx: ExecutionContext): Cursor {
        val childCur = child.open(execCtx)
        return object : Cursor {
            override fun next(): Boolean {
                while (childCur.next()) {
                    if (predicate(childCur.row)) return true
                }
                return false
            }

            override val row: RowAccessor
                get() = childCur.row

            override fun close() {
                childCur.close()
            }
        }
    }
}

class ProjectNode(val child: PlanNode, val projections: List<(RowAccessor) -> Any?>, val names: List<String>) : PlanNode {
    override fun open(execCtx: ExecutionContext): Cursor {
        val childCur = child.open(execCtx)
        return object : Cursor {
            override fun next(): Boolean = childCur.next()

            override val row: RowAccessor
                get() = object : RowAccessor {
                    override fun get(index: Int): Any? {
                        return projections[index].invoke(childCur.row)
                    }

                    override fun get(name: String): Any? {
                        val idx = names.indexOf(name)
                        return if (idx >= 0) get(idx) else null
                    }
                }

            override fun close() {
                childCur.close()
            }
        }
    }
}

class LimitNode(val child: PlanNode, val limit: Int?, val offset: Int?) : PlanNode {
    override fun open(execCtx: ExecutionContext): Cursor {
        val childCur = child.open(execCtx)
        return object : Cursor {
            var skipped = false
            var emitted = 0

            override fun next(): Boolean {
                if (!skipped) {
                    skipped = true
                    val toSkip = offset ?: 0
                    var i = 0
                    while (i < toSkip && childCur.next()) {
                        i++
                    }
                }
                if (limit == null) {
                    return childCur.next()
                }
                if (emitted >= limit) return false
                val has = childCur.next()
                if (has) emitted++
                return has
            }

            override val row: RowAccessor
                get() = childCur.row

            override fun close() {
                childCur.close()
            }
        }
    }
}
