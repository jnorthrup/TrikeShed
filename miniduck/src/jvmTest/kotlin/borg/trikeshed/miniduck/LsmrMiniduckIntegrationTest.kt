package borg.trikeshed.miniduck

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import borg.trikeshed.userspace.database.LsmrConfig
import borg.trikeshed.userspace.database.LsmrDatabase
import borg.trikeshed.miniduck.schema.LsmrSchemaManager
import borg.trikeshed.miniduck.exec.LsmrTableSource
import borg.trikeshed.miniduck.exec.ExecutionContext
import borg.trikeshed.miniduck.sql.PlannerConfig

class LsmrMiniduckIntegrationTest {
    @Test
    fun `lsmr and miniduck seed and read roundtrip`() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "trikeshed-lsmr-miniduck-${System.nanoTime()}")
        tmp.mkdirs()
        val db = LsmrDatabase(LsmrConfig(path = tmp.absolutePath, memtableThreshold = 16, maxSegments = 4))
        val schemaManager = LsmrSchemaManager(db)
        val tableSource = LsmrTableSource(db, blockSizeThreshold = 2)
        val execCtx = ExecutionContext(schemaManager, PlannerConfig(), tableSource)

        val rows = listOf(listOf(1, "Alice"), listOf(2, "Bob"))
        tableSource.seedRows("users", rows)

        val read = mutableListOf<Pair<Int, String>>()
        val cursor = tableSource.open(execCtx, "users")
        while (cursor.next()) {
            val idAny = cursor.row.get(0)
            val nameAny = cursor.row.get(1)
            val id = (idAny as? Number)?.toInt() ?: (idAny as? String)?.toIntOrNull() ?: error("unexpected id type: $idAny")
            val name = nameAny as? String ?: nameAny?.toString() ?: error("unexpected name type: $nameAny")
            read.add(id to name)
        }
        cursor.close()

        assertEquals(2, read.size)
        assertEquals(1, read[0].first)
        assertEquals("Alice", read[0].second)
        assertEquals(2, read[1].first)
        assertEquals("Bob", read[1].second)

        // best-effort cleanup
        tmp.listFiles()?.forEach { it.delete() }
        tmp.delete()
    }
}
