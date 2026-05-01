package borg.trikeshed.miniduck.sql

import borg.trikeshed.parse.kursive.sql.SqlParser
import borg.trikeshed.miniduck.exec.InMemoryTableSource
import borg.trikeshed.miniduck.exec.ExecutionContext
import borg.trikeshed.miniduck.schema.InMemorySchemaManager
import borg.trikeshed.miniduck.schema.TableSchema
import borg.trikeshed.miniduck.schema.ColumnSchema
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Platform-agnostic integration test using in-memory table source. JVM-specific LSMR test lives in jvmTest.
class SqlIntegrationTest {
    @Test
    fun parsePlanExecuteAgainstInMemory() = runTest {
        val schemaManager = InMemorySchemaManager()
        val tableSource = InMemoryTableSource()

        // Seed schema and rows
        val schema = TableSchema("users", listOf(ColumnSchema(id = 0, name = "id"), ColumnSchema(id = 1, name = "name")))
        schemaManager.createTableSuspend(schema)
        tableSource.addTable(schema, listOf(listOf(1, "alice"), listOf(2, "bob"), listOf(3, "carol")))

        val execCtx = ExecutionContext(schemaManager, PlannerConfig(), tableSource)

        val sql = "SELECT id, name FROM users WHERE id = 2"
        println("SqlParser.class location: " + SqlParser::class.java.protectionDomain.codeSource.location)
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
