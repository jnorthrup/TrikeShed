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

/** Production node integration with synthetic saved books; no CoreNLP or model calls. */
class ConstellationAskUnlessTest {
    @TempDir lateinit var directory: Path

    @Test fun missingUnlessWaitsExplicitRefutationFiresAndPositiveBlocks() = runBlocking {
        val classId = requireNotNull(SumoCorpus.classifier.classId("Human")).value
        val bookDir = directory.resolve("constellations/books")
        Files.createDirectories(bookDir)
        val premise = "rent be due"
        fun book(name: String, facts: List<String>) {
            val statement = _m["bearer" j "tenant", "modality" j "may", "action" j "occupy",
                "object" j "home", "condition" j "unless $premise", "bearerClass" j classId]
            val data = _m["name" j name, "work" j "synthetic", "date" j "2026",
                "headings" j _l["fixture"], "statements" j _l[statement], "support" j _l[_l[0]],
                "facts" j facts, "sections" j _l["urn:sha256:synthetic"]]
            Files.writeString(bookDir.resolve("$name.json"), jsonOf(data))
            Files.writeString(directory.resolve("constellations/$name.members"), "$name\n")
        }
        book("unknown", emptyList())
        book("positive", _l[premise])
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
            assertEquals("caller-provided-unverified", refuted["negativeFactsSource"])
            assertTrue((refuted["pending"] as Map<*, *>).isEmpty())
            assertEquals(0, bank.size(), "Query-specific premises must not persist a global normHeld fact")

            val positive = ask("positive")
            assertTrue((positive["fires"] as List<*>).isEmpty())
            assertTrue((positive["held"] as List<*>).isEmpty())
            assertTrue(premise in (positive["blocked"] as Map<*, *>))
            assertEquals(0, bank.size())

            val conflict = ask("positive", _m["negativeFacts" j _l[premise]])
            assertTrue((conflict["fires"] as List<*>).isEmpty())
            assertTrue((conflict["held"] as List<*>).isEmpty())
            assertTrue(premise in (conflict["conflicted"] as Map<*, *>))
            assertEquals(0, bank.size())
            assertFails { ask("unknown", _m["negativeFacts" j premise]) }
        } finally { nodes.drain() }
    }
}
