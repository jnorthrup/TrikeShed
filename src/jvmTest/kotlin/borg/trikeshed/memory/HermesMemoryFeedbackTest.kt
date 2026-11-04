package borg.trikeshed.memory

import borg.trikeshed.common.File as MemoryFile
import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.*
import borg.trikeshed.narsese.*
import kotlinx.coroutines.*
import kotlin.test.*

class HermesMemoryFeedbackTest {
    @Test fun deletingRetrievedMemoryDemotesAttentionWithoutRefutingItsProposition() = runBlocking {
        withTimeout(10_000) {
            val directory = java.nio.file.Files.createTempDirectory("nars-memory-feedback-")
            val root = MemoryFile(directory.toString())
            val cas = CasStore.inMemory()
            val bag = BeliefBagElement(cas = cas, parentJob = currentCoroutineContext()[Job])
            bag.open()
            try {
                val evaluator = ContentId.of("synthetic-memory-evaluator".encodeToByteArray())
                val entry = "Keep the exact synthetic passage."
                val angular = HermesMemoryFiles.entryAngular(entry)
                val source = ContentId.of(entry.encodeToByteArray())
                val signal = SemanticSignal(angular, EvidenceCoord(1, 0), RelationKind.MATCH, source.value,
                    provenanceCid = evaluator.value)
                bag.submit(BeliefIntake.Mint(signal, BudgetCoord(.8f, .6f, .5f), source, EvidenceBasis.of(source)))
                val memory = HermesMemoryFiles(bag, root, evaluator)
                memory.gloss(angular, entry)
                assertEquals(entry, memory.renderTo().text)
                val before = SignalCodec.encode(requireNotNull(bag.signalOf(angular)))
                root.resolve("MEMORY.md").writeText("")
                assertEquals(1, memory.ingestUserEdits())
                assertContentEquals(before, SignalCodec.encode(requireNotNull(bag.signalOf(angular))))
                assertEquals(BudgetCoord.zero(), bag.budgetOf(angular))
                assertNull(memory.glossOf(angular))
                assertEquals("", memory.renderTo().text)
                val reloaded = HermesMemoryFiles(bag, root, evaluator)
                assertNull(reloaded.glossOf(angular), "Deleted gloss must not reappear from its persisted sidecar")
                assertEquals("", reloaded.renderTo().text)
            } finally {
                bag.drain()
                assertTrue(bag.worker?.isCompleted == true && bag.supervisor.isCompleted)
                directory.toFile().deleteRecursively()
            }
        }
    }
}
