package borg.trikeshed.miniduck.exec

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.miniduck.DocRowVec
import borg.trikeshed.miniduck.toRowVec

/** Simple in-memory table source used for tests and JS platform stub delegation. */
class InMemoryTableSource : TableSource {
    private val tables: MutableMap<String, MutableList<DocRowVec>> = mutableMapOf()

    override fun open(execCtx: ExecutionContext, tableName: String): Cursor {
        val rows = tables[tableName] ?: emptyList()
        val rowVecs = rows.map { it.toRowVec() }
        return rowVecs.size j { idx: Int -> rowVecs[idx] }
    }

    override suspend fun openSuspend(execCtx: ExecutionContext, tableName: String): Cursor = open(execCtx, tableName)

    override fun insert(execCtx: ExecutionContext, tableName: String, row: List<Any?>) {
        val list = tables.getOrPut(tableName) { mutableListOf() }
        list.add(DocRowVec(emptyList(), row))
    }

    override suspend fun insertSuspend(execCtx: ExecutionContext, tableName: String, row: List<Any?>) = insert(execCtx, tableName, row)

    override fun seedRows(tableName: String, rows: List<List<Any?>>) {
        val list = tables.getOrPut(tableName) { mutableListOf() }
        rows.forEach { r -> list.add(DocRowVec(emptyList(), r)) }
    }
}
