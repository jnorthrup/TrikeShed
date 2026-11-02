package borg.trikeshed.narsese

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lib.*
import borg.trikeshed.ontology.SumoCorpus
import borg.trikeshed.parse.jsonOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Production node integration with synthetic saved books; the text-input test uses CoreNLP. */
class ConstellationAskUnlessTest {
    @TempDir lateinit var directory: Path

    @Test fun missingAndLegacyFactsWaitWhileExplicitRefutationFires() = runBlocking {
        val classId = requireNotNull(SumoCorpus.classifier.classId("Human")).value
        val bookDir = directory.resolve("constellations/books")
        Files.createDirectories(bookDir)
        val premise = "rent be due"
        fun book(name: String, facts: List<String>, condition: String = "unless") {
            val statement = _m["bearer" j "tenant", "modality" j "may", "action" j "occupy",
                "object" j "home", "condition" j "$condition $premise", "bearerClass" j classId]
            val data = _m["name" j name, "work" j "synthetic", "date" j "2026",
                "headings" j _l["fixture"], "statements" j _l[statement], "support" j _l[_l[0]],
                "facts" j facts, "sections" j _l["urn:sha256:synthetic"]]
            Files.writeString(bookDir.resolve("$name.json"), jsonOf(data))
            Files.writeString(directory.resolve("constellations/$name.members"), "$name\n")
        }
        book("unknown", emptyList())
        book("positive", _l[premise])
        book("if", emptyList(), "if")
        val bank = KifKnowledgeBase()
        val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard.empty(), bank = bank, scope = this)
        val runners = mutableMapOf<String, LcncNodeRunner>()
        nodes.register(runners)
        suspend fun ask(name: String, inputs: Map<String, Any?> = emptyMap()) = runners.getValue(ConstellationNodes.ASK).run(
            LcncNode("ask", ConstellationNodes.ASK, params = _m["constellation" j name, "class" j "Human"]), inputs)
        try {
            val unknown = ask("unknown")
            assertTrue((unknown["fires"] as List<*>).isEmpty())
            assertTrue((unknown["held"] as List<*>).isEmpty())
            assertTrue(premise in (unknown["pending"] as Map<*, *>))
            assertEquals(0, bank.size())

            val refuted = ask("unknown", _m["negativeFacts" j _l[premise]])
            assertEquals(1, (refuted["fires"] as List<*>).size)
            assertEquals(1, (refuted["held"] as List<*>).size)
            assertEquals("caller-assumed-unverified", refuted["negativeFactsSource"])
            assertTrue((refuted["pending"] as Map<*, *>).isEmpty())
            assertEquals(0, bank.size(), "Query-specific premises must not persist a global normHeld fact")

            val positive = ask("positive")
            assertTrue((positive["fires"] as List<*>).isEmpty())
            assertTrue((positive["held"] as List<*>).isEmpty())
            assertTrue(premise in (positive["pending"] as Map<*, *>))
            assertTrue((positive["blocked"] as Map<*, *>).isEmpty())
            assertTrue((positive["facts"] as List<*>).isEmpty())
            assertTrue(premise in (positive["withheldLegacyFacts"] as List<*>))
            assertEquals("parsed-and-legacy-facts-are-unverified", positive["withheldReason"])
            assertEquals(0, bank.size())

            val explicitRefutation = ask("positive", _m["negativeFacts" j _l[premise]])
            assertEquals(1, (explicitRefutation["fires"] as List<*>).size)
            assertEquals(1, (explicitRefutation["held"] as List<*>).size)
            assertTrue((explicitRefutation["conflicted"] as Map<*, *>).isEmpty())
            assertTrue(premise in (explicitRefutation["withheldLegacyFacts"] as List<*>))
            assertEquals(0, bank.size())

            val explicitPositive = ask("unknown", _m["positiveFacts" j _l[premise]])
            assertEquals(_l[premise], explicitPositive["facts"])
            assertEquals(_l[premise], explicitPositive["positiveFacts"])
            assertEquals("caller-assumed-unverified", explicitPositive["positiveFactsSource"])
            assertTrue(premise in (explicitPositive["blocked"] as Map<*, *>))
            assertTrue((explicitPositive["fires"] as List<*>).isEmpty())

            val affirmativeIf = ask("if", _m["positiveFacts" j _l[premise]])
            assertEquals(1, (affirmativeIf["fires"] as List<*>).size)
            assertEquals(1, (affirmativeIf["held"] as List<*>).size)
            assertTrue((affirmativeIf["pending"] as Map<*, *>).isEmpty())

            val refutedIf = ask("if", _m["negativeFacts" j _l[premise]])
            assertTrue(premise in (refutedIf["blocked"] as Map<*, *>))
            assertTrue((refutedIf["fires"] as List<*>).isEmpty())
            assertTrue((refutedIf["held"] as List<*>).isEmpty())

            val conflict = ask("unknown", _m["positiveFacts" j _l[premise], "negativeFacts" j _l[premise]])
            assertTrue(premise in (conflict["conflicted"] as Map<*, *>))
            assertTrue((conflict["fires"] as List<*>).isEmpty())
            assertTrue((conflict["held"] as List<*>).isEmpty())
            assertEquals(0, bank.size(), "Conditioned assumptions must not publish global held facts")
            assertFails { ask("unknown", _m["negativeFacts" j premise]) }
            assertFails { ask("unknown", _m["positiveFacts" j premise]) }
        } finally { nodes.drain() }
    }

    @Test fun naturalLanguageFactsRemainCandidatesRatherThanPremises() = runBlocking {
        val classId = requireNotNull(SumoCorpus.classifier.classId("Human")).value
        val bookDir = directory.resolve("constellations/books")
        Files.createDirectories(bookDir)
        val premise = "rent be due"
        val statement = _m["bearer" j "tenant", "modality" j "may", "action" j "occupy",
            "object" j "home", "condition" j "unless $premise", "bearerClass" j classId]
        val book = _m["name" j "negated", "work" j "synthetic", "date" j "2026",
            "headings" j _l["fixture"], "statements" j _l[statement], "support" j _l[_l[0]],
            "facts" j emptyList<String>(), "sections" j _l["urn:sha256:synthetic"]]
        Files.writeString(bookDir.resolve("negated.json"), jsonOf(book))
        Files.writeString(directory.resolve("constellations/negated.members"), "negated\n")
        val bank = KifKnowledgeBase()
        val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard.empty(), bank = bank, scope = this)
        val runners = mutableMapOf<String, LcncNodeRunner>()
        nodes.register(runners)
        try {
            // Exercise the real CoreNLP → candidate parsing → constellation.ask path.
            for (text in _l["Rent is not due.", "Rent is due except today.", "Rent is due."]) {
                val result = runners.getValue(ConstellationNodes.ASK).run(
                    LcncNode("ask", ConstellationNodes.ASK, params = _m["constellation" j "negated", "class" j "Human"]),
                    _m["facts" j text])
                assertTrue((result["facts"] as List<*>).isEmpty(), text)
                assertTrue((result["positiveFacts"] as List<*>).isEmpty(), text)
                assertTrue((result["fires"] as List<*>).isEmpty(), text)
                assertTrue((result["held"] as List<*>).isEmpty(), text)
                assertTrue(premise in (result["pending"] as Map<*, *>), text)
                assertTrue((result["blocked"] as Map<*, *>).isEmpty(), text)
                assertEquals("parsed-and-legacy-facts-are-unverified", result["withheldReason"])
                if (text == "Rent is due.") assertTrue(premise in (result["parsedCandidates"] as List<*>))
                assertEquals(0, bank.size())
            }
        } finally { nodes.drain() }
    }

    @Test fun unconditionalNormRemainsHeldWhileUnlessNormIsPending() = runBlocking {
        val classId = requireNotNull(SumoCorpus.classifier.classId("Human")).value
        val bookDir = directory.resolve("constellations/books")
        Files.createDirectories(bookDir)
        val premise = "rent be due"
        val unconditional = _m["bearer" j "tenant", "modality" j "may", "action" j "occupy",
            "object" j "home", "bearerClass" j classId]
        val conditional = unconditional + _m["condition" j "unless $premise"]
        val book = _m["name" j "mixed", "work" j "synthetic", "date" j "2026",
            "headings" j _l["one", "two"], "statements" j _l[unconditional, conditional],
            "support" j _l[_l[0], _l[1]], "facts" j emptyList<String>(),
            "sections" j _l["urn:sha256:one", "urn:sha256:two"]]
        Files.writeString(bookDir.resolve("mixed.json"), jsonOf(book))
        Files.writeString(directory.resolve("constellations/mixed.members"), "mixed\n")
        val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard.empty(), scope = this)
        val runners = mutableMapOf<String, LcncNodeRunner>()
        nodes.register(runners)
        try {
            val result = runners.getValue(ConstellationNodes.ASK).run(
                LcncNode("ask", ConstellationNodes.ASK, params = _m["constellation" j "mixed", "class" j "Human"]),
                emptyMap())
            val fires = result["fires"] as List<*>
            val held = result["held"] as List<*>
            val pending = (result["pending"] as Map<*, *>)[premise] as List<*>
            assertEquals(1, fires.size)
            assertEquals(1, held.size)
            assertEquals(1, pending.size)
            assertEquals(fires.toSet(), ((held.single() as Map<*, *>)["norms"] as List<*>).toSet())
            assertTrue(fires.none { it in pending })
        } finally { nodes.drain() }
    }
}
