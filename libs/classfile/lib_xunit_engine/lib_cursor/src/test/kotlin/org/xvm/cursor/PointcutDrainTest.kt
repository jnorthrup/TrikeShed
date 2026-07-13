package org.xvm.cursor

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.xvm.runtime.*
import java.nio.file.Path

class PointcutDrainTest {

    // ── drain() happy path ──────────────────────────────────────────────

    @Test
    fun `drain transitions RUNNING to DRAINING`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        val table = tableWithEvents(5)
        val drain = PointcutDrain(lc, table, dir)
        drain.drain()
        assertTrue(lc.isDraining)
        assertFalse(lc.isShutdown)
    }

    @Test
    fun `drain writes table_dump ISAM files`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        val table = tableWithEvents(5)
        val drain = PointcutDrain(lc, table, dir)
        drain.drain()
        assertTrue(dir.resolve("table_dump.bin").toFile().exists(), "table_dump.bin must exist")
        assertTrue(dir.resolve("table_dump.bin.meta").toFile().exists(), "table_dump.bin.meta must exist")
    }

    @Test
    fun `table_dump meta has column group for histogram bytes`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        val drain = PointcutDrain(lc, tableWithEvents(3), dir)
        drain.drain()
        val metaLines = dir.resolve("table_dump.bin.meta").toFile().readLines()
        // Format: 2 comment lines, coords, names, types, [groups line]
        // groups line only present when distinctGroups > 1
        assertTrue(metaLines.size >= 6, "meta must have at least 6 lines with groups, got ${metaLines.size}")
        val groupsLine = metaLines[5].trim()
        assertTrue(groupsLine.contains("0-3:bytes"),
            "groups line must assign cols 0-3 to 'bytes' group, got: $groupsLine")
    }

    // ── drain() guard: wrong state ──────────────────────────────────────

    @Test
    fun `drain throws from INIT`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        val table = TypedefCascadeTable(256)
        assertThrows<IllegalStateException> {
            PointcutDrain(lc, table, dir).drain()
        }
    }

    @Test
    fun `drain throws from SHUTDOWN`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        lc.drain()
        lc.shutdown()
        assertThrows<IllegalStateException> {
            PointcutDrain(lc, TypedefCascadeTable(256), dir).drain()
        }
    }

    // ── drain() idempotent ──────────────────────────────────────────────

    @Test
    fun `drain second call no-ops`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        val drain = PointcutDrain(lc, tableWithEvents(3), dir)
        drain.drain()
        drain.drain()
        assertTrue(lc.isDraining)
        assertTrue(drain.isDrained)
    }

    @Test
    fun `drain second call does not rewrite files`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        val drain = PointcutDrain(lc, tableWithEvents(3), dir)
        drain.drain()
        val size1 = dir.resolve("table_dump.bin").toFile().length()
        drain.drain()
        val size2 = dir.resolve("table_dump.bin").toFile().length()
        assertEquals(size1, size2)
    }

    // ── shutdown() happy ────────────────────────────────────────────────

    @Test
    fun `shutdown transitions DRAINING to SHUTDOWN`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        val drain = PointcutDrain(lc, tableWithEvents(1), dir)
        drain.drain()
        drain.shutdown()
        assertTrue(lc.isShutdown)
    }

    @Test
    fun `shutdown completes pipeline`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        val drain = PointcutDrain(lc, tableWithEvents(1), dir)
        drain.drain()
        drain.shutdown()
        assertTrue(drain.isShutDown)
        assertTrue(lc.isShutdown)
        assertFalse(lc.isRunning)
        assertFalse(lc.isDraining)
    }

    // ── shutdown() guard ────────────────────────────────────────────────

    @Test
    fun `shutdown throws from RUNNING`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        assertThrows<IllegalStateException> {
            PointcutDrain(lc, TypedefCascadeTable(256), dir).shutdown()
        }
    }

    @Test
    fun `shutdown throws from INIT`(@TempDir dir: Path) {
        assertThrows<IllegalStateException> {
            PointcutDrain(XvmLifecycle(), TypedefCascadeTable(256), dir).shutdown()
        }
    }

    // ── full pipeline ───────────────────────────────────────────────────

    @Test
    fun `full pipeline produces all ISAM artifacts`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        val drain = PointcutDrain(lc, tableWithEvents(10), dir)
        drain.drain()
        drain.shutdown()
        assertTrue(dir.resolve("table_dump.bin").toFile().exists())
        assertTrue(dir.resolve("table_dump.bin.meta").toFile().exists())
        assertTrue(dir.resolve("cascade_leafscan.bin").toFile().exists())
        assertTrue(dir.resolve("cascade_leafscan.bin.meta").toFile().exists())
        assertTrue(dir.resolve("cascade_kind_merge.bin").toFile().exists())
        assertTrue(dir.resolve("cascade_scope_rollup.bin").toFile().exists())
        assertTrue(dir.resolve("cascade_joint.bin").toFile().exists())
        assertTrue(dir.resolve("joint_histogram.bin").toFile().exists())
        assertTrue(dir.resolve("joint_histogram.bin.meta").toFile().exists())
        assertTrue(lc.isShutdown)
    }

    @Test
    fun `full pipeline ISAM files have non-zero size`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        val drain = PointcutDrain(lc, tableWithEvents(10), dir)
        drain.drain()
        drain.shutdown()
        assertTrue(dir.resolve("table_dump.bin").toFile().length() > 0, "table_dump.bin must have data")
        assertTrue(dir.resolve("cascade_leafscan.bin").toFile().length() > 0, "cascade_leafscan.bin must have data")
        assertTrue(dir.resolve("joint_histogram.bin").toFile().length() > 0, "joint_histogram.bin must have data")
    }

    // ── artifact content ────────────────────────────────────────────────

    @Test
    fun `cascade tier files are valid ISAM with meta`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        val drain = PointcutDrain(lc, tableWithEvents(10), dir)
        drain.drain()
        // Each tier should have .bin + .meta pair
        val tierNames = listOf("leafscan", "kind_merge", "scope_rollup", "joint")
        for (name in tierNames) {
            assertTrue(dir.resolve("cascade_$name.bin").toFile().exists(), "cascade_$name.bin must exist")
            assertTrue(dir.resolve("cascade_$name.bin.meta").toFile().exists(), "cascade_$name.bin.meta must exist")
            assertTrue(dir.resolve("cascade_$name.bin").toFile().length() > 0, "cascade_$name.bin must have data")
        }
    }

    @Test
    fun `joint histogram ISAM has 36 rows`(@TempDir dir: Path) {
        val lc = XvmLifecycle()
        lc.start()
        val table = tableWithEvents(10)
        val drain = PointcutDrain(lc, table, dir)
        drain.drain()
        // 9 kinds x 4 scopes = 36 rows
        val binFile = dir.resolve("joint_histogram.bin").toFile()
        val metaFile = dir.resolve("joint_histogram.bin.meta").toFile()
        assertTrue(binFile.exists() && binFile.length() > 0)
        assertTrue(metaFile.exists() && metaFile.length() > 0)
        // record size = 3 columns: IoInt(4) + IoInt(4) + IoLong(8) = 16 bytes per row
        val recordLen = 16
        val recordCount = binFile.length() / recordLen
        assertEquals(36, recordCount, "joint_histogram should have 9x4=36 rows")
    }

    // ── helper ──────────────────────────────────────────────────────────

    private fun tableWithEvents(n: Int): TypedefCascadeTable {
        val table = TypedefCascadeTable(256)
        repeat(n) { i ->
            table.routeOpcode(0x10 + (i % 4), "pkg.Class.method$i", 100 + i)
        }
        return table
    }
}
