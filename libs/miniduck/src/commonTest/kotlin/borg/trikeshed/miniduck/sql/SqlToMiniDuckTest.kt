package borg.trikeshed.miniduck.sql

import borg.trikeshed.miniduck.exec.ExecutionContext
import borg.trikeshed.miniduck.exec.InMemoryTableSource
import borg.trikeshed.miniduck.schema.ColumnSchema
import borg.trikeshed.miniduck.schema.TableSchema
import borg.trikeshed.parse.kursive.sql.SqlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class SqlToMiniDuckTest {
    @Test
    fun selectStarWithWhereKeepsSchemaLessCells() {
        val tableSource = InMemoryTableSource().apply {
            seedRows(
                "docs",
                listOf(
                    listOf("alice", 30),
                    listOf("bob", 35),
                    listOf("carol", 40),
                ),
            )
        }

        val rows = runQuery(
            sql = "SELECT * FROM docs WHERE col1 > 30",
            tableSource = tableSource,
            width = 2,
        )

        assertEquals(listOf(listOf("bob", 35), listOf("carol", 40)), rows)
    }

    @Test
    fun whereSupportsComparisonsAndBooleanConjunction() {
        val tableSource = InMemoryTableSource().apply {
            addTable(
                TableSchema(
                    name = "docs",
                    columns = listOf(
                        ColumnSchema(0, "name"),
                        ColumnSchema(1, "age"),
                    ),
                ),
                rows = listOf(
                    listOf("alice", 30),
                    listOf("bob", 35),
                    listOf("carol", 40),
                ),
            )
        }

        val rows = runQuery(
            sql = "SELECT age FROM docs WHERE age > 30 AND age < 40",
            tableSource = tableSource,
            width = 1,
        )

        assertEquals(listOf(listOf(35)), rows)
    }

    private fun runQuery(sql: CharSequence, tableSource: InMemoryTableSource, width: Int): List<List<Any?>> {
        val stmt = SqlParser.parse(sql) ?: fail("parser did not produce a SelectStmt for $sql")
        val cursor = transformSelect(stmt, PlannerContext()).open(
            ExecutionContext(tableSource = tableSource),
        )
        val rows = mutableListOf<List<Any?>>()
        while (cursor.next()) {
            rows += List(width) { index -> cursor.row[index] }
        }
        cursor.close()
        return rows
    }
}
