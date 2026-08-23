package borg.trikeshed.graal.subvm.harness

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The JVM baseline row: every probe runs; none FAILS; the matrix is written under docs/subvm/. */
class HarnessBaselineTest {
    @Test fun jvmBaselineRowHasNoFailures() {
        val row = HarnessMain.measure("jvm-" + Host.current().os + "-" + Host.current().arch)
        val failed = row.cells.filter { it.verdict == Verdict.FAILED }
        assertEquals(emptyList(), failed, failed.joinToString("\n") { "${it.probe}: ${it.evidence}" })
        assertTrue(row.cells.count { it.verdict == Verdict.OK } >= 12, row.cells.joinToString("\n") { "${it.verdict} ${it.probe}" })
        // round-trip through the writer/reader used by the native harness
        val back = HarnessMain.fromJson(HarnessMain.toJson(row))
        assertEquals(row.cells.map { it.probe to it.verdict }, back.cells.map { it.probe to it.verdict })
        val outDir = File("docs/subvm").apply { mkdirs() }
        File(outDir, "capabilities-${row.host.name}.json").writeText(HarnessMain.toJson(row))
        val rows = outDir.listFiles { f -> f.name.startsWith("capabilities-") && f.name.endsWith(".json") }!!.map { HarnessMain.fromJson(it.readText()) }
        File(outDir, "capability-matrix.md").writeText(HarnessMain.matrix(rows))
        println(HarnessMain.matrix(rows))
    }
}
