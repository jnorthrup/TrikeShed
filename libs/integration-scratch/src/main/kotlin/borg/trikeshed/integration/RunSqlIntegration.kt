package borg.trikeshed.integration

import borg.trikeshed.parse.kursive.sql.SqlParser
import borg.trikeshed.miniduck.exec.ExecutionContext
import borg.trikeshed.miniduck.exec.LsmrTableSource
import borg.trikeshed.miniduck.schema.LsmrSchemaManager
import borg.trikeshed.miniduck.schema.TableSchema
import borg.trikeshed.miniduck.schema.ColumnSchema
import borg.trikeshed.userspace.database.LsmrDatabase
import borg.trikeshed.userspace.database.LsmrConfig
import borg.trikeshed.miniduck.sql.PlannerContext
import borg.trikeshed.miniduck.sql.PlannerConfig
import borg.trikeshed.miniduck.sql.transformSelect

// RLM: library entrypoint commented out - fun main() {
// RLM: library entrypoint commented out -     val db = LsmrDatabase(LsmrConfig(path = "", memtableThreshold = 1024))
    val schemaManager = LsmrSchemaManager(db)
    val tableSource = LsmrTableSource(db)

    // Seed schema and rows
    schemaManager.createTable(TableSchema("users", listOf(ColumnSchema(0, "id"), ColumnSchema(1, "name"))))
    tableSource.seedRows("users", listOf(listOf(1, "alice"), listOf(2, "bob"), listOf(3, "carol")))

    val execCtx = ExecutionContext(schemaManager, PlannerConfig(), tableSource)

    val sql = "SELECT id, name FROM users WHERE id = 2"
    val stmt = SqlParser.parse(sql) ?: throw IllegalStateException("parse failed")
    println("Parsed stmt: $stmt")
    println("From: ${stmt.from}")
    val plan = transformSelect(stmt, PlannerContext(schemaManager))

    val cur = plan.open(execCtx)
    val rows = LongSeries.build { it += <Pair<Long?, Long?>>() })
    while (cur.next()) {
        val id = cur.row.get("id")
        val name = cur.row.get("name")
        rows.add(Pair(id, name))
    }
    cur.close()

    println("Found rows: ${rows.size}")
    rows.forEach { println(it) }
}
