package borg.trikeshed.narsese

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import kotlinx.coroutines.*
import modelmux.Airgap
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.*

class CurationJevaIdentityTest {
    @TempDir lateinit var directory: Path

    fun runtime(scope: CoroutineScope, identity: String, returned: () -> String = { identity }): Join<ConstellationNodes, JevaElementTest.Operations> {
        val ops = JevaElementTest.Operations { request ->
            if (request["op"] == "status") mapOf("available" to true, "model_identity" to identity, "error" to null)
            else mapOf("model" to returned(), "usage" to mapOf("input_tokens" to 2, "output_tokens" to 0),
                "answers" to (request["questions"] as Map<*, *>).keys.associate { id -> id.toString() to mapOf(
                    "kind" to "noul", "candidate_probabilities" to listOf(0.2, 0.8), "null_probability" to 0.0, "noul" to 0.8) })
        }
        val element = JevaElement(CoroutineScope(scope.coroutineContext + ops), JevaConfig(runtime = JevaRuntime.PYTHON))
        val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard(),
            scope = CoroutineScope(scope.coroutineContext + ops + element + Airgap()))
        return nodes j ops
    }

    @Test fun restartedRuntimeWithDifferentResolvedWeightsDoesNotReuseEarlierJudgments() = runBlocking {
        val state = mapOf<String, Any?>("page" to "The same printed page.")
        val questions = mapOf("stated" to Jev.noul("Does the page state the norm?"))
        val first = runtime(this, "fixture@first")
        lateinit var firstNamespace: String
        lateinit var priorFile: File
        lateinit var priorBytes: ByteArray
        try {
            val selected = first.a.ingestAccess(DecisionProvider.JEVA)
            firstNamespace = selected.decisionNamespace
            val response = selected.asked("", state, questions)
            assertEquals("fixture@first", response["model"])
            assertEquals(1, first.b.inferences)
            priorFile = File(selected.root, "decisions/$firstNamespace").listFiles()!!.single()
            priorBytes = priorFile.readBytes()
        } finally { first.a.drain() }

        val second = runtime(this, "fixture@second")
        try {
            val selected = second.a.ingestAccess(DecisionProvider.JEVA)
            assertNotEquals(firstNamespace, selected.decisionNamespace)
            val response = selected.asked("", state, questions)
            assertEquals("fixture@second", response["model"])
            assertEquals(1, response["asked"])
            assertEquals(1, second.b.inferences, "Changed weights must be asked even for identical state and question")
            assertContentEquals(priorBytes, priorFile.readBytes())
        } finally { second.a.drain() }
    }

    @Test fun committedBookSelectionRestoresItsJudgmentsWithoutAnyRuntimeOrNetwork() = runBlocking {
        val running = runtime(this, "fixture@committed")
        lateinit var namespace: String
        try {
            val selected = running.a.ingestAccess(DecisionProvider.JEVA)
            namespace = selected.decisionNamespace
            book(selected)
            selected.score("", listOf("book"))
            assertTrue(running.b.inferences > 0)
            assertEquals("fixture@committed", reifyMap(File(selected.root, "books/book.decision.json").readText())["model"])
        } finally { running.a.drain() }

        val offline = ConstellationNodes(directory.toFile(), ConfixBlackboard(), scope = CoroutineScope(coroutineContext + Airgap()))
        try {
            val restored = offline.forBook("book")
            assertEquals(DecisionProvider.JEVA, restored.decisionProvider)
            assertEquals(namespace, restored.decisionNamespace)
            val table = restored.section("book", "0")["jev"] as Map<*, *>
            assertEquals("fixture@committed", table["model"])
            assertEquals(listOf(0.8), table["legible"])
            assertEquals(0.8, table["whole"])
        } finally { offline.drain() }
    }

    @Test fun replyFromDifferentWeightsCannotOverwriteTheSelectedModelsCache() = runBlocking {
        var returned = "fixture@selected"
        val running = runtime(this, "fixture@selected") { returned }
        val questions = mapOf("stated" to Jev.noul("Is the norm stated?"))
        try {
            val selected = running.a.ingestAccess(DecisionProvider.JEVA)
            selected.asked("", mapOf("page" to "first page"), questions)
            val cache = File(selected.root, "decisions/${selected.decisionNamespace}")
            val retained = cache.listFiles()!!.single()
            val bytes = retained.readBytes()
            returned = "fixture@unexpected"
            val failure = assertFails { selected.asked("", mapOf("page" to "second page"), questions) }
            assertContains(failure.message.orEmpty(), "model identity changed")
            assertEquals(listOf(retained.name), cache.listFiles()!!.map { it.name })
            assertContentEquals(bytes, retained.readBytes())
        } finally { running.a.drain() }
    }

    fun book(nodes: ConstellationNodes) {
        File(nodes.root, "books/book.json").writeText("""{"name":"book","work":"Fixture","date":"1900","headings":["Page"],"statements":[],"support":[]}""")
        val page = "The page remains legible."
        File(nodes.root, "books/book.sections.jsonl").writeText(jsonOf(page))
        nodes.readingFile("book", 0).apply { parentFile.mkdirs() }.writeText(jsonOf(listOf(mapOf(
            "text" to page, "concepts" to emptyList<Any>(), "statements" to emptyList<Any>()))))
    }
}
