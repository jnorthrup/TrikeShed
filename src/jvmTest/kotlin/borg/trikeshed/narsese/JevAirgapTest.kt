package borg.trikeshed.narsese

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import modelmux.Airgap
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import java.io.File
import java.lang.System
import java.nio.file.Path
import kotlin.test.*

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class JevAirgapTest {
    @TempDir lateinit var directory: Path

    @Test fun contextRejectsBeforeHtxDispatch() = runTest {
        var requests = 0
        withHtxClientRoute({ requests++; HtxResponse(200, ByteSeries("{}")) }) {
            withContext(Airgap()) {
                assertFailsWith<Airgap.Rejection> { Jev.ask("fixture", "page", emptyMap()) }
            }
        }
        assertEquals(0, requests)
    }

    @Test fun blankKeysNeverDispatch() = runTest {
        var requests = 0
        withHtxClientRoute({ requests++; HtxResponse(200, ByteSeries("{}")) }) {
            for (key in listOf("", " \t\n")) {
                val error = assertFails { Jev.ask(key, "page", emptyMap()) }
                assertTrue(error is Airgap.Rejection || error.message == "JEV_API_KEY is not set")
            }
        }
        assertEquals(0, requests)
    }

    @Test fun hostSwitchStopsTheNextRetryDespiteDisabledContext() = runTest {
        val prior = System.setProperty(Airgap.PROPERTY, "false")
        try {
            assumeFalse(Airgap.configured(), "The process environment already requires airgap")
            for (status in listOf(429, 529)) {
                System.setProperty(Airgap.PROPERTY, "false")
                var requests = 0
                withHtxClientRoute({
                    requests++
                    System.setProperty(Airgap.PROPERTY, "true")
                    HtxResponse(status, ByteSeries("retry later"))
                }) {
                    withContext(Airgap(false)) {
                        assertFailsWith<Airgap.Rejection> { Jev.ask("fixture", "page", emptyMap()) }
                    }
                }
                assertEquals(1, requests, "The retry must recheck the host switch")
            }
        } finally {
            if (prior == null) System.clearProperty(Airgap.PROPERTY) else System.setProperty(Airgap.PROPERTY, prior)
        }
    }

    @Test fun probeAndRankRemainDisabledWithoutInventingNegativeJudgments() = runTest {
        val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard())
        val find = CurationFind(nodes)
        val rows = listOf(mapOf("text" to "The bank holds an account", "score" to 2.0))
        withContext(Airgap()) {
            var keys = 0
            find.probe { keys++; "fixture" }
            assertEquals(0, keys)
            assertEquals(false, find.gate["ready"])
            assertEquals(true, find.gate["disabled"])
            assertFalse(find.gate.containsKey("yes"))
            assertFalse(find.gate.containsKey("no"))
            find.gate = mapOf("ready" to true)
            val ranked = find.rank("fixture", "bank", rows)
            assertEquals(true, ranked["disabled"])
            assertEquals(false, ranked["ranked"])
            assertEquals(rows, ranked["rows"])
            assertFalse((ranked["rows"] as List<*>).any { (it as Map<*, *>).containsKey("jev") })
            assertFailsWith<Airgap.Rejection> { nodes.score("fixture", emptyList()) }
            assertFailsWith<Airgap.Rejection> { nodes.oracle("fixture", "absent", 0, "bank") }
            assertEquals(0, find.find("bank", null, null, 3, 10)["found"])
        }
        assertTrue(nodes.scored.isEmpty())
        assertFalse(File(nodes.root, "jev").exists())
        assertFalse(File(nodes.root, "senses.rows").exists())
    }

    @Test fun rankingRequiresTheProbeVerdictAndRechecksTheHostSwitch() = runTest {
        val find = CurationFind(ConstellationNodes(directory.toFile(), ConfixBlackboard()))
        val rows = listOf(mapOf("text" to "bank", "score" to 1.0))
        val pending = find.rank("fixture", "bank", rows)
        assertEquals(false, pending["ranked"])
        assertEquals(rows, pending["rows"])
        find.gate = mapOf("ready" to true)
        val prior = System.setProperty(Airgap.PROPERTY, "true")
        try {
            withContext(Airgap(false)) {
                assertEquals(true, find.gate["disabled"])
                val ranked = find.rank("fixture", "bank", rows)
                assertEquals(false, ranked["ranked"])
                assertEquals(true, ranked["disabled"])
                assertEquals(rows, ranked["rows"])
            }
        } finally {
            if (prior == null) System.clearProperty(Airgap.PROPERTY) else System.setProperty(Airgap.PROPERTY, prior)
        }
    }

    @Test fun hostSwitchDuringScoringPreservesThePreviousTableAndSenseRows() = runTest {
        val prior = System.setProperty(Airgap.PROPERTY, "false")
        try {
            assumeFalse(Airgap.configured(), "The process environment already requires airgap")
            for (status in listOf(429, 200)) {
                System.setProperty(Airgap.PROPERTY, "false")
                val nodes = ConstellationNodes(File(directory.toFile(), status.toString()), ConfixBlackboard())
                val books = File(nodes.root, "books")
                File(books, "book.json").writeText("""{"name":"book","work":"Fixture","date":"1900","headings":["Page"],"statements":[],"support":[]}""")
                val page = "The page remains legible."
                File(books, "book.sections.jsonl").writeText(jsonOf(page))
                nodes.readingFile("book", 0).apply { parentFile.mkdirs() }
                    .writeText(jsonOf(listOf(mapOf("text" to page))))
                val table = File(books, "book.jev/0.json").apply { parentFile.mkdirs() }
                val retained = """{"section":0,"legible":[0.8],"tuples":[],"senses":[],"establishes":[]}""".encodeToByteArray()
                table.writeBytes(retained)
                val rows = File(nodes.root, "senses.rows")
                var retainedRows: ByteArray? = null
                var requests = 0
                withHtxClientRoute({
                    requests++
                    retainedRows = rows.takeIf { it.isFile }?.readBytes()
                    System.setProperty(Airgap.PROPERTY, "true")
                    HtxResponse(status, ByteSeries("""{"answers":{"legible":{"noul":1.0},"whole":{"noul":1.0}}}"""))
                }) {
                    withContext(Airgap(false)) {
                        assertFailsWith<Airgap.Rejection> {
                            withTimeout(5_000) { nodes.score("fixture", listOf("book")) }
                        }
                    }
                }
                assertEquals(1, requests)
                assertContentEquals(retained, table.readBytes())
                assertContentEquals(retainedRows, rows.takeIf { it.isFile }?.readBytes())
                assertEquals(0, nodes.scored["book"]?.get("tabled"))
                assertFalse(File(table.parentFile, "0.json.tmp").exists())
            }
        } finally {
            if (prior == null) System.clearProperty(Airgap.PROPERTY) else System.setProperty(Airgap.PROPERTY, prior)
        }
    }
}
