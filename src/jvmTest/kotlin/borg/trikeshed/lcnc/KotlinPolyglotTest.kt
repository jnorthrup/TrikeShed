package borg.trikeshed.lcnc

import borg.trikeshed.couch.viewserver.KotlinViewServer
import borg.trikeshed.graal.subvm.VmLanguages
import borg.trikeshed.parse.reify
import borg.trikeshed.vm.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class KotlinPolyglotTest {
    @Test fun commonAlgebraTransformsTypedEvidenceAndComposesWithCamel() = runBlocking {
        val host = HypervisorVmHost()
        try {
            val capability = VmLanguages.require(host, VmLanguage.KOTLIN_COMMON)
            assertTrue(capability.installed && capability.available)
            val input = mapOf("case" to "synthetic-雪", "reports" to listOf(
                mapOf("party" to "A", "score" to 1.25), mapOf("party" to "B", "score" to 2.5)), "missing" to null)
            val source = """
                val data = input as Map<String, Any?>
                val reports = (data["reports"] as List<Map<String, Any?>>).toSeries()
                val reviewed = reports α { it + ("review" to "human-required") }
                val columns = s_[ColumnMeta("party", IOMemento.IoString), ColumnMeta("score", IOMemento.IoDouble)]
                val cursor: Cursor = reports.size j { row: Int -> 2 j { column: Int ->
                    reports.b(row)[columns.b(column).name.toString()] j { columns.b(column) }
                } }
                val parties = CursorScope(cursor).index().groups(0)
                return data + mapOf("reports" to reviewed.view.toList(), "accepted" to false, "parties" to parties.size)
            """.trimIndent()
            val node = LcncNode("kotlin-evidence", SubVmLegos.POLYGLOT, VmLanguage.KOTLIN_COMMON.params(source))
            val output = SubVmLegos.polyglot(host).run(node, mapOf("input" to input))
            val expected = input + mapOf("reports" to listOf(
                mapOf("party" to "A", "score" to 1.25, "review" to "human-required"),
                mapOf("party" to "B", "score" to 2.5, "review" to "human-required")), "accepted" to false, "parties" to 2L)
            assertEquals(expected, output["value"])
            assertEquals("kotlin-common", output["language"])
            val routed = SubVmLegos.camel(host).run(LcncNode("kotlin-route", SubVmLegos.CAMEL,
                mapOf("from" to "direct:kotlin-test", "to" to "log:kotlin-test")), mapOf("messages?" to output["value"]))
            assertEquals(vmLanguageValue(expected), vmLanguageValue(reify(routed["routed"] as String)))
            assertTrue(host.ids().none { host.get(it)?.isAlive == true })
        } finally { host.close() }
    }

    @Test fun unsupportedTrustAndBudgetsAreRejectedBeforeSpawning() = runBlocking {
        val host = HypervisorVmHost()
        try {
            val runner = SubVmLegos.polyglot(host)
            val node = LcncNode("kotlin-limits", SubVmLegos.POLYGLOT, VmLanguage.KOTLIN_COMMON.params("return input"))
            for (limits in listOf(mapOf("trust" to "UNTRUSTED"), mapOf("statements" to 1L), mapOf("wallMillis" to 1L))) {
                assertFailsWith<IllegalArgumentException> { runner.run(node, limits) }
            }
            assertTrue(host.ids().isEmpty())
        } finally { host.close() }
    }

    @Test fun diagnosticsAndRejectedValuesReleaseTheirGuest() = runBlocking {
        val host = HypervisorVmHost()
        try {
            val runner = SubVmLegos.polyglot(host)
            suspend fun invoke(source: String) = runner.run(LcncNode("kotlin-diagnostic", SubVmLegos.POLYGLOT,
                VmLanguage.KOTLIN_COMMON.params(source)), emptyMap())
            val diagnostic = assertFails { invoke("val count = 1\nreturn missing") }
            assertTrue(diagnostic.message.orEmpty().contains("kotlin-diagnostic.kts:2:"), diagnostic.toString())
            val failure = assertFails { invoke("error(\"operator failure\")") }
            assertTrue(failure.message.orEmpty().contains("operator failure"), failure.toString())
            for (source in listOf("return Any()", "return mapOf(1 to true)", "return Double.NaN", "return { 1 }")) {
                assertFails { invoke(source) }
                assertTrue(host.ids().none { host.get(it)?.isAlive == true })
            }
            assertNull(invoke("return null")["value"])
            repeat(2) {
                assertEquals(1L, invoke("val counter = object { var count = 0 }; return ++counter.count")["value"])
            }
            assertTrue(host.ids().none { host.get(it)?.isAlive == true })
        } finally { host.close() }
    }

    @Test fun sharedCompilerPreservesCouchMapReduceAndClasspath() {
        val views = KotlinViewServer()
        val docs = listOf(mapOf("_id" to "A", "type" to "legal"), mapOf("_id" to "B", "type" to "medical"))
        repeat(2) {
            val rows = views.map("cases", "type", "{ doc -> emit(doc[\"type\"], 1) }", docs)
            assertEquals(listOf("legal", "medical"), rows.map { it.key })
            assertEquals(listOf("A", "B"), rows.map { it.docId })
            assertEquals(2, views.reduce("cases", "count", "{ keys, values, rereduce -> values.size }",
                rows.map { it.key }, rows.map { it.value }, false))
        }
        assertFailsWith<IllegalArgumentException> { views.map("cases", "broken", "{ doc -> missing }", docs) }
    }
}
