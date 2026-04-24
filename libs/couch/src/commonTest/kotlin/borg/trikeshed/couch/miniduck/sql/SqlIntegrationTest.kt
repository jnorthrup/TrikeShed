package borg.trikeshed.couch.miniduck.sql

import borg.trikeshed.parse.kursive.sql.SqlParser
import borg.trikeshed.couch.miniduck.exec.ExecutionContext
import borg.trikeshed.couch.miniduck.exec.LsmrTableSource
import borg.trikeshed.couch.miniduck.schema.LsmrSchemaManager
import borg.trikeshed.couch.miniduck.schema.TableSchema
import borg.trikeshed.couch.miniduck.schema.ColumnSchema
import borg.trikeshed.userspace.database.LsmrDatabase
import borg.trikeshed.userspace.database.LsmrConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlIntegrationTest {
    @Test
    fun parsePlanExecuteAgainstLsmr() = runTest {
        val db = LsmrDatabase(LsmrConfig(path = "", memtableThreshold = 1024))
        val schemaManager = LsmrSchemaManager(db)
        val tableSource = LsmrTableSource(db)

        // Seed schema and rows
        schemaManager.createTable(TableSchema("users", listOf(ColumnSchema(0, "id"), ColumnSchema(1, "name"))))
        tableSource.seedRows("users", listOf(listOf(1, "alice"), listOf(2, "bob"), listOf(3, "carol")))

        val execCtx = ExecutionContext(schemaManager, PlannerConfig(), tableSource)

        val sql = "SELECT id, name FROM users WHERE id = 2"
        val stmt = SqlParser.parse(sql) ?: throw AssertionError("parse failed")
        val plan = transformSelect(stmt, PlannerContext(schemaManager))

        val cur = plan.open(execCtx)
        val rows = mutableListOf<Pair<Any?, Any?>>()
        while (cur.next()) {
            val id = cur.row.get("id")
            val name = cur.row.get("name")
            rows.add(Pair(id, name))
        }
        cur.close()

        assertEquals(1, rows.size)
        assertEquals(2, rows[0].first)
        assertEquals("bob", rows[0].second)
    }
}
