package borg.trikeshed.narsese

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import borg.trikeshed.agent.modelmux.Airgap
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import java.io.File
import java.lang.System
import java.nio.file.Path
import kotlin.test.*

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class CurationDecisionProviderTest {
    @TempDir lateinit var directory: Path

    @Test fun ingestProvidersRemainStableAndDrainWithTheirOwner() = runBlocking {
        val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard(), scope = this)
        val jeva = nodes.forProvider(DecisionProvider.JEVA)
        val openai = nodes.forProvider(DecisionProvider.OPENAI)
        try {
            assertSame(nodes, jeva.forProvider(DecisionProvider.JEV))
            assertSame(jeva, openai.forProvider(DecisionProvider.JEVA))
            assertSame(openai, nodes.forProvider(DecisionProvider.OPENAI))
            assertEquals(DecisionProvider.JEV, nodes.decisionProvider)
            assertEquals(DecisionProvider.JEVA, jeva.decisionProvider)
            assertEquals(DecisionProvider.OPENAI, openai.decisionProvider)
            assertNotSame(nodes.curation, jeva.curation)
            assertNotSame(openai.curation, jeva.curation)
        } finally { nodes.drain() }
        for (member in listOf(nodes, jeva, openai)) {
            assertFalse(member.curation.supervisor.isActive)
            assertFailsWith<IllegalStateException> { member.forProvider(DecisionProvider.JEV) }
        }
    }

    @Test fun sourceRemovalInvalidatesSiblingConstellationsAndEveryPersistedSenseMemory() = runBlocking {
        val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard(), scope = this)
        val alternative = nodes.forProvider(DecisionProvider.OPENAI)
        try {
            book(nodes)
            File(nodes.root, "legal.members").writeText("book\n")
            val selected = File(nodes.root, "books/book.decision.json").apply {
                writeText(jsonOf(mapOf("provider" to alternative.decisionProvider.id,
                    "namespace" to alternative.decisionNamespace, "model" to alternative.decisionProvider.model)))
            }
            val memory = SenseMemory().apply {
                val at = Locality.of(1900, Register.CURRENT)
                observe("bank", at, "book", "removed-source", mapOf("FinancialOrganization" to 0.9, "none" to 0.1))
                observe("court", at, "other", "retained-source", mapOf("Organization" to 0.9, "none" to 0.1))
            }.write()
            val stores = listOf("senses.rows", "senses.${alternative.decisionNamespace}.rows", "senses.jeva-dormant-config.rows")
                .map { File(nodes.root, it).apply { writeText(memory) } }
            assertEquals(2, nodes.senses(null, null)["rows"])
            assertEquals(2, alternative.senses(null, null)["rows"])
            val runners = linkedMapOf<String, borg.trikeshed.lcnc.LcncNodeRunner>()
            alternative.register(runners)
            val brief = borg.trikeshed.lcnc.LcncNode("brief", ConstellationNodes.BRIEF, params = mapOf("constellation" to "legal"))
            assertTrue(runners.getValue(ConstellationNodes.BRIEF).run(brief, emptyMap())["summary"].toString().contains("1 books"))

            nodes.leave("legal", "book")

            assertFalse(selected.exists())
            assertEquals(1, nodes.senses(null, null)["rows"])
            assertEquals(1, alternative.senses(null, null)["rows"])
            for (saved in stores) {
                val retained = saved.bufferedReader().useLines { SenseMemory.read(it) }
                assertEquals(1, retained.rows, saved.name)
                assertEquals(0, retained.contexts("bank").size, saved.name)
                assertEquals(1, retained.contexts("court").size, saved.name)
            }
            assertTrue(runners.getValue(ConstellationNodes.BRIEF).run(brief, emptyMap())["summary"].toString().contains("0 books"))
        } finally { nodes.drain() }
    }

    @Test fun mixedProviderJoinAndRestorePreserveCommittedPremisesAndFidelity() = runBlocking {
        val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard(), scope = this)
        val alternative = nodes.forProvider(DecisionProvider.OPENAI)
        val statements = listOf(
            NormStatement("tenant", Modality.MUST, "pay", "rent", "if rent be due"),
            NormStatement("landlord", Modality.MUST, "repair", "roof", "if notice be given"),
        )
        fun fixture(name: String, provider: ConstellationNodes, noul: Double, premise: String) {
            File(nodes.root, "books/$name.json").writeText(jsonOf(mapOf(
                "name" to name, "work" to name, "date" to "1900", "headings" to listOf("Page"),
                "statements" to statements.map { it.toMap() }, "support" to listOf(listOf(0), listOf(0)),
            )))
            File(nodes.root, "books/$name.decision.json").writeText(jsonOf(mapOf(
                "provider" to provider.decisionProvider.id, "namespace" to provider.decisionNamespace,
                "model" to provider.decisionProvider.model,
            )))
            val suffix = if (provider.decisionProvider == DecisionProvider.JEV) "jev" else provider.decisionNamespace
            File(nodes.root, "books/$name.$suffix/0.json").apply { parentFile.mkdirs() }.writeText(jsonOf(mapOf(
                "section" to 0, "tuples" to statements.map { mapOf("key" to it.key, "noul" to noul) },
                "establishes" to listOf(mapOf("premise" to premise)),
            )))
        }
        suspend fun join(provider: ConstellationNodes, name: String): Map<String, Any?> {
            val runners = linkedMapOf<String, borg.trikeshed.lcnc.LcncNodeRunner>()
            provider.register(runners)
            val node = borg.trikeshed.lcnc.LcncNode("join", ConstellationNodes.JOIN,
                params = mapOf("constellation" to "mixed", "book" to name))
            return runners.getValue(ConstellationNodes.JOIN).run(node, emptyMap())
        }
        fun assertJudgments(result: Map<String, Any?>) {
            assertEquals(2, result["books"])
            assertEquals(false, result["unresolved"], "Each committed provider must supply its established premise")
            val rules = (result["rules"] as List<*>).map { it as Map<*, *> }
            assertEquals(statements.map { it.consequent }.toSet(), rules.map { it["consequent"] }.toSet())
            val confidence = Nal.truthOf(EvidenceCoord(750, 0)).confidence
            for (rule in rules) assertEquals(confidence, (rule["discount"] as Number).toFloat(),
                "Both books must contribute their committed fidelity, 0.25 + 0.50 units")
        }
        try {
            fixture("first", nodes, 0.25, "rent be due")
            fixture("second", alternative, 0.50, "notice be given")
            join(nodes, "first")
            assertJudgments(join(alternative, "second"))
        } finally { nodes.drain() }

        val restored = ConstellationNodes(directory.toFile(), ConfixBlackboard(), scope = this)
        try {
            restored.restore()
            assertJudgments(join(restored, "second"))
        } finally { restored.drain() }
    }

    @Test fun providerDispatchAndPersistedAnswersRetainSeparateModelIdentities() = runTest {
        remote {
            val state = mapOf<String, Any?>("page" to "The bank is closed.")
            val questions = mapOf("closed" to Jev.noul("Does page say the bank is closed?"))
            val requests = mutableListOf<HtxRequest>()
            val models = mapOf(DecisionProvider.JEV to "jev-fixture-resolved", DecisionProvider.OPENAI to "openai-fixture-resolved")
            withHtxClientRoute({ request ->
                requests.add(request)
                when (request.target.host) {
                    "api.typesafe.ai" -> HtxResponse(200, ByteSeries(jsonOf(mapOf(
                        "model" to models.getValue(DecisionProvider.JEV), "answers" to mapOf("closed" to mapOf("noul" to 0.81)),
                        "usage" to mapOf("input_tokens" to 11, "output_tokens" to 0)))))
                    "api.openai.com" -> HtxResponse(200, ByteSeries(jsonOf(mapOf(
                        "model" to models.getValue(DecisionProvider.OPENAI), "answers" to listOf(mapOf(
                            "name" to "closed", "type" to "predicate", "probability" to 0.93)),
                        "usage" to mapOf("input_tokens" to 13, "output_tokens" to 0)))))
                    else -> error("Unexpected decision host: ${request.target.host}")
                }
            }) {
                for (provider in DecisionProvider.entries.filter { it.remote }) {
                    val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard(), decisionProvider = provider)
                    val fresh = nodes.asked("fixture", state, questions)
                    assertEquals(provider.id, fresh["provider"])
                    assertEquals(models.getValue(provider), fresh["model"])
                    assertEquals(1, fresh["asked"])
                    assertEquals(if (provider == DecisionProvider.JEV) 0.81 else 0.93,
                        Jev.noulOf(fresh["answers"] as Map<*, *>, "closed"))

                    // A new owner must recover the resolved model and answer from disk without dispatch.
                    val restored = ConstellationNodes(directory.toFile(), ConfixBlackboard(), decisionProvider = provider)
                        .asked("fixture", state, questions)
                    assertEquals(fresh["answers"], restored["answers"])
                    assertEquals(models.getValue(provider), restored["model"])
                    assertEquals(0, restored["asked"])
                    assertEquals(0, (restored["usage"] as Map<*, *>)["input_tokens"])
                    val cache = File(nodes.root, "decisions/${provider.namespace}").listFiles().orEmpty()
                    assertEquals(1, cache.size)
                    assertEquals(provider.id, reifyMap(cache.single().readText())["provider"])
                }
            }
            assertEquals(listOf("api.typesafe.ai", "api.openai.com"), requests.map { it.target.host })
            assertEquals(listOf("/v1/systemone", "/v1/decisions"), requests.map { it.target.requestPath })
            assertTrue(requests.all { it.headerValue("Authorization") == "Bearer fixture" })
        }
    }

    @Test fun identicalAndOverlappingAnswerMissesDispatchOnceAfterAWaiterCancels() = runBlocking {
        remote {
            val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard(), scope = this)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val state = mapOf<String, Any?>("page" to "The bank is closed.")
            val question = Jev.noul("Does page say the bank is closed?")
            var requests = 0
            try {
                withHtxClientRoute({
                    requests++
                    entered.complete(Unit); release.await()
                    HtxResponse(200, ByteSeries("""{"model":"jev-shared-fixture","answers":{"a":{"noul":0.91}},"usage":{"input_tokens":12}}"""))
                }) {
                    coroutineScope {
                    val first = async { nodes.asked("fixture", state, mapOf("a" to question)) }
                    entered.await()
                    val identical = async(start = CoroutineStart.UNDISPATCHED) { nodes.asked("fixture", state, mapOf("a" to question)) }
                    val overlapping = async(start = CoroutineStart.UNDISPATCHED) { nodes.asked("fixture", state, mapOf("b" to question)) }
                    first.cancelAndJoin()
                    release.complete(Unit)
                    val shared = identical.await()
                    val renamed = overlapping.await()
                    assertEquals(1, requests)
                    assertEquals(0.91, Jev.noulOf(shared["answers"] as Map<*, *>, "a"))
                    assertEquals(0.91, Jev.noulOf(renamed["answers"] as Map<*, *>, "b"))
                    assertEquals(0, shared["asked"], "A coalesced waiter must not double-count the paid request")
                    assertEquals(0, renamed["asked"])
                    assertEquals("jev-shared-fixture", renamed["model"])
                    }
                }
            } finally { release.complete(Unit); nodes.drain() }
        }
    }

    @Test fun partialOrRefusedProbeCannotEstablishReadiness() = runTest {
        remote {
            for (provider in DecisionProvider.entries.filter { it.remote }) for (refused in listOf(false, true)) {
                val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard(), decisionProvider = provider)
                val find = CurationFind(nodes)
                var requests = 0
                withHtxClientRoute({ request ->
                    requests++
                    assertEquals(provider.base.substringAfter("://"), request.target.host)
                    val answers: Any = if (provider == DecisionProvider.JEV) {
                        if (refused) mapOf("yes" to mapOf("noul" to 0.99), "no" to mapOf("refusal" to "fixture"))
                        else mapOf("yes" to mapOf("noul" to 0.99))
                    } else {
                        val yes = mapOf("name" to "yes", "type" to "predicate", "probability" to 0.99)
                        if (refused) listOf(yes, mapOf("name" to "no", "type" to "refusal")) else listOf(yes)
                    }
                    HtxResponse(200, ByteSeries(jsonOf(mapOf("model" to provider.model, "answers" to answers))))
                }) { find.probe { "fixture" } }
                assertEquals(1, requests)
                assertEquals(false, find.gate["ready"])
                assertFalse(find.gate.containsKey("no"), "A missing answer must not become a negative probability")
                assertNotNull(find.gate["reason"])
            }
        }
    }

    @Test fun legacyJevAnswersAvoidDispatchOnlyWhenValidAndRecoveredAnswersUseTheProviderCache() = runTest {
        remote {
            val question = Jev.noul("Does page say the bank is closed?")
            val questions = mapOf("closed" to question)
            fun cid(value: Any) = borg.trikeshed.job.ContentId.of(jsonOf(value).encodeToByteArray()).hex
            val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard(), decisionProvider = DecisionProvider.JEV)
            val validState = mapOf<String, Any?>("page" to "Valid legacy judgment")
            val invalidState = mapOf<String, Any?>("page" to "Malformed legacy judgment")
            val legacy = File(nodes.root, "jev").apply { mkdirs() }
            File(legacy, "${cid(validState)}.json").writeText(jsonOf(mapOf(cid(question) to mapOf("noul" to 0.84))))
            val invalid = File(legacy, "${cid(invalidState)}.json")
            val invalidBytes = jsonOf(mapOf(cid(question) to mapOf("noul" to "unknown"))).encodeToByteArray()
            invalid.writeBytes(invalidBytes)
            var requests = 0
            withHtxClientRoute({ request ->
                requests++
                assertEquals("api.typesafe.ai", request.target.host)
                assertEquals(invalidState, reifyMap(request.body.toArray().decodeToString())["state"])
                HtxResponse(200, ByteSeries("""{"model":"jev-recovered-fixture","answers":{"closed":{"noul":0.91}}}"""))
            }) {
                val retained = nodes.asked("fixture", validState, questions)
                assertEquals(0, requests)
                assertEquals(0, retained["asked"])
                assertEquals(Jev.MODEL, retained["model"])
                assertEquals(0.84, Jev.noulOf(retained["answers"] as Map<*, *>, "closed"))
                val recovered = nodes.asked("fixture", invalidState, questions)
                assertEquals(1, requests)
                assertEquals(1, recovered["asked"])
                assertEquals(0.91, Jev.noulOf(recovered["answers"] as Map<*, *>, "closed"))
                val cache = File(nodes.root, "decisions/${DecisionProvider.JEV.namespace}/${cid(invalidState)}.json")
                assertEquals("jev-recovered-fixture", reifyMap(cache.readText())["model"])
                val restored = ConstellationNodes(directory.toFile(), ConfixBlackboard()).asked("fixture", invalidState, questions)
                assertEquals(1, requests)
                assertEquals(0, restored["asked"])
                assertEquals("jev-recovered-fixture", restored["model"])
                assertEquals(recovered["answers"], restored["answers"])
            }
            assertContentEquals(invalidBytes, invalid.readBytes())
        }
    }

    @Test fun alternativeTablesAndSenseMemoryDoNotInheritJevEvidence() {
        val jev = ConstellationNodes(directory.toFile(), ConfixBlackboard(), decisionProvider = DecisionProvider.JEV)
        book(jev)
        val jevTable = File(jev.root, "books/book.jev/0.json").apply { parentFile.mkdirs() }
        jevTable.writeText(jsonOf(mapOf("tuples" to emptyList<Any>(), "establishes" to listOf(mapOf("premise" to "jev premise")))))
        val jevMemory = SenseMemory().apply {
            observe("bank", Locality.of(1900, Register.CURRENT), "book", "fixture",
                mapOf("FinancialOrganization" to 0.9, "RiverBank" to 0.1))
        }.write()
        File(jev.root, "senses.rows").writeText(jevMemory)
        assertEquals(setOf("jev premise"), jev.load("book")?.established)

        val provider = DecisionProvider.OPENAI
        val alternative = ConstellationNodes(directory.toFile(), ConfixBlackboard(), decisionProvider = provider)
        assertEquals(emptySet(), alternative.load("book")?.established)
        val alternativeTable = File(alternative.root, "books/book.${provider.tableSuffix}/0.json").apply { parentFile.mkdirs() }
        alternativeTable.writeText(jsonOf(mapOf("tuples" to emptyList<Any>(), "establishes" to listOf(mapOf("premise" to "openai premise")))))
        val restored = ConstellationNodes(directory.toFile(), ConfixBlackboard(), decisionProvider = provider)
        assertEquals(setOf("openai premise"), restored.load("book")?.established)
        assertEquals(0, restored.senses(null, null)["rows"])
        assertTrue(File(restored.root, "senses.${provider.namespace}.rows").isFile)
        assertEquals(jevMemory, File(jev.root, "senses.rows").readText())
        assertEquals(setOf("jev premise"), jev.load("book")?.established)
    }

    @Test fun failedOrRefusedScoringPreservesThePreviousProviderTableAndSenseRows() = runBlocking {
        remote {
            for (provider in DecisionProvider.entries.filter { it.remote }) for (status in listOf(400, 200)) {
                val nodes = ConstellationNodes(File(directory.toFile(), "${provider.id}-$status"), ConfixBlackboard(), decisionProvider = provider, scope = this)
                try {
                book(nodes)
                val table = File(nodes.root, "books/book.${provider.tableSuffix}/0.json").apply { parentFile.mkdirs() }
                val retained = """{"section":0,"legible":[0.8],"tuples":[],"senses":[],"establishes":[]}""".encodeToByteArray()
                table.writeBytes(retained)
                val rows = File(nodes.root, if (provider == DecisionProvider.JEV) "senses.rows" else "senses.${provider.namespace}.rows")
                var retainedRows: ByteArray? = null
                var requests = 0
                withHtxClientRoute({
                    requests++
                    retainedRows = rows.takeIf { it.isFile }?.readBytes()
                    val answers: Any = if (provider == DecisionProvider.JEV) {
                        mapOf("legible" to mapOf("refusal" to "fixture"), "whole" to mapOf("noul" to 0.99))
                    } else listOf(mapOf("name" to "legible", "type" to "refusal"),
                        mapOf("name" to "whole", "type" to "predicate", "probability" to 0.99))
                    HtxResponse(status, ByteSeries(jsonOf(mapOf("model" to provider.model, "answers" to answers))))
                }) {
                    val failure = assertFails { withTimeout(5_000) { nodes.score("fixture", listOf("book")) } }
                    assertFalse(failure is CancellationException, "The failure must come from the decision result")
                }
                assertEquals(1, requests)
                assertContentEquals(retained, table.readBytes())
                assertNotNull(retainedRows)
                assertContentEquals(retainedRows, rows.takeIf { it.isFile }?.readBytes())
                assertEquals(0, nodes.scored["book"]?.get("tabled"))
                assertFalse(File(table.parentFile, "0.json.tmp").exists())
                } finally { nodes.drain() }
            }
        }
    }

    @Test fun refusalInOneSectionPreservesBothPreviousTables() = runBlocking {
        remote {
            for (provider in DecisionProvider.entries.filter { it.remote }) {
                val nodes = ConstellationNodes(File(directory.toFile(), provider.id), ConfixBlackboard(), decisionProvider = provider, scope = this)
                try {
                val pages = listOf("The first page remains legible.", "The second page remains legible.")
                File(nodes.root, "books/book.json").writeText("""{"name":"book","work":"Fixture","date":"1900","headings":["First","Second"],"statements":[],"support":[]}""")
                File(nodes.root, "books/book.sections.jsonl").writeText(pages.joinToString("\n") { jsonOf(it) })
                val tables = pages.mapIndexed { k, page ->
                    nodes.readingFile("book", k).apply { parentFile.mkdirs() }.writeText(jsonOf(listOf(mapOf(
                        "text" to page, "concepts" to emptyList<Any>(), "statements" to emptyList<Any>()))))
                    File(nodes.root, "books/book.${provider.tableSuffix}/$k.json").apply { parentFile.mkdirs() }
                }
                val retained = tables.mapIndexed { k, table ->
                    jsonOf(mapOf("section" to k, "legible" to listOf(0.8), "tuples" to emptyList<Any>(),
                        "senses" to emptyList<Any>(), "establishes" to emptyList<Any>())).encodeToByteArray()
                        .also { table.writeBytes(it) }
                }
                val rows = File(nodes.root, if (provider == DecisionProvider.JEV) "senses.rows" else "senses.${provider.namespace}.rows")
                var retainedRows: ByteArray? = null
                val seen = mutableListOf<String>()
                withHtxClientRoute({ request ->
                    if (retainedRows == null) retainedRows = rows.readBytes()
                    val payload = reifyMap(request.body.toArray().decodeToString())
                    val state = if (provider == DecisionProvider.JEV) payload["state"] as Map<*, *>
                        else reifyMap(payload["input"] as String)
                    val page = state["page"] as String
                    seen.add(page)
                    val refused = page == pages[1]
                    // The successful section can finish before the other section's refusal is delivered.
                    if (refused) delay(1)
                    val answers: Any = if (provider == DecisionProvider.JEV) {
                        mapOf("legible" to (if (refused) mapOf("refusal" to "fixture") else mapOf("noul" to 0.99)),
                            "whole" to mapOf("noul" to 0.99))
                    } else listOf(if (refused) mapOf("name" to "legible", "type" to "refusal")
                        else mapOf("name" to "legible", "type" to "predicate", "probability" to 0.99),
                        mapOf("name" to "whole", "type" to "predicate", "probability" to 0.99))
                    HtxResponse(200, ByteSeries(jsonOf(mapOf("model" to provider.model, "answers" to answers))))
                }) {
                    val failure = assertFails { withTimeout(5_000) { nodes.score("fixture", listOf("book")) } }
                    assertFalse(failure is CancellationException)
                }
                assertEquals(pages.toSet(), seen.toSet())
                assertEquals(2, seen.size)
                for (k in tables.indices) {
                    assertContentEquals(retained[k], tables[k].readBytes())
                    assertFalse(File(tables[k].parentFile, "$k.json.tmp").exists())
                }
                assertContentEquals(retainedRows, rows.readBytes())
                assertEquals(0, nodes.scored["book"]?.get("tabled"))
                } finally { nodes.drain() }
            }
        }
    }

    fun book(nodes: ConstellationNodes) {
        File(nodes.root, "books/book.json").writeText("""{"name":"book","work":"Fixture","date":"1900","headings":["Page"],"statements":[],"support":[]}""")
        val page = "The page remains legible."
        File(nodes.root, "books/book.sections.jsonl").writeText(jsonOf(page))
        nodes.readingFile("book", 0).apply { parentFile.mkdirs() }.writeText(jsonOf(listOf(mapOf(
            "text" to page, "concepts" to emptyList<Any>(), "statements" to emptyList<Any>()))))
    }

    suspend fun remote(block: suspend () -> Unit) {
        val prior = System.setProperty(Airgap.PROPERTY, "false")
        try {
            assumeFalse(Airgap.configured(), "The process environment already requires airgap")
            withContext(Airgap(false)) { block() }
        } finally {
            if (prior == null) System.clearProperty(Airgap.PROPERTY) else System.setProperty(Airgap.PROPERTY, prior)
        }
    }
}
