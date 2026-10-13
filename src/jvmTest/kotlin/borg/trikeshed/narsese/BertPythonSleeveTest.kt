package borg.trikeshed.narsese

import borg.trikeshed.graal.subvm.GuestException
import borg.trikeshed.graal.subvm.GuestFailure
import borg.trikeshed.hermes.HermesModuleStatus
import borg.trikeshed.hermes.HermesPythonPort
import borg.trikeshed.lib.*
import borg.trikeshed.sleeve.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class BertPythonSleeveTest {
    @TempDir lateinit var directory: Path

    private class Encoder : BertEncoder {
        override val model = "fixture-contextual-encoder"
        val targets = mutableListOf<BertToken>()
        val batches = mutableListOf<List<BertToken>>()
        var drains = 0
        override suspend fun encode(tokens: Series<BertToken>): Series<BertVector> {
            val batch = tokens.view.toList()
            batches.add(batch)
            targets.addAll(batch)
            return (tokens α { token ->
                when (token.lemma) {
                    "missing" -> BertVector(token, BertSpan.Missing, null)
                    else -> BertVector(token, if (token.start == null) BertSpan.Exact else BertSpan.Explicit,
                        floatArrayOf(1f, -2f, 3f))
                }
            })
        }
        override suspend fun drain() { drains++ }
    }

    private fun source(root: Path, text: String): Path {
        val packageRoot = Files.createDirectories(root.resolve("bert"))
        Files.writeString(packageRoot.resolve("__init__.py"), "")
        return packageRoot.resolve("encoder.py").also { Files.writeString(it, text) }
    }

    private fun red(evidence: String, catchability: TrapCatchability? = null) {
        val manifest = assertNotNull(AgentSleeveRegistry.byId("bert"))
        assertTrue(manifest.isRed)
        assertEquals("bert.encoder", manifest.entryModule)
        assertTrue(Files.isSameFile(directory.resolve("donor"), Path.of(assertNotNull(manifest.donorRoot))))
        val trap = manifest.unresolved.firstOrNull { evidence in it.evidence }
            ?: fail("No unresolved trap retains $evidence: ${manifest.traps}")
        assertEquals(TrapShape.UNRESOLVED, trap.shape)
        assertEquals("bert/encoder.py", trap.forkPath)
        assertEquals("BertPythonSleeveTest.sourceTreeEncoderReturnsTargets", trap.redTest)
        catchability?.let { assertEquals(it, trap.catchability) }
        val card = SleeveTddRedKanban.cards(manifest).single { it.id == SleeveTddRedKanban.cardId(trap) }
        assertTrue(card.description.contains(evidence))
        assertTrue(card.description.contains(trap.forkPath))
        assertTrue(card.description.contains(trap.redTest))
    }

    @Test
    fun sourceForkPreservesTheDonorAndItsNativeRedInventory() {
        val donor = directory.resolve("donor")
        val sleeve = directory.resolve("sleeve")
        val native = "import ctypes\n" + DONOR
        val donorFile = source(donor, native)
        source(sleeve, SLEEVE)
        HermesPythonPort().use { port ->
            val original = port.inventory(donor)
            val fork = port.inventory(donor, sleeve)
            assertEquals(HermesModuleStatus.BLOCKED_NATIVE, original.modules.getValue("bert.encoder").status)
            assertEquals(HermesModuleStatus.READY, fork.modules.getValue("bert.encoder").status)
            assertFalse(original.modules.getValue("bert.encoder").sleeved)
            assertTrue(fork.modules.getValue("bert.encoder").sleeved)
            assertEquals(original.upstreamSpineCid, fork.upstreamSpineCid)
            assertNotEquals(original.sleeveSpineCid, fork.sleeveSpineCid)
            assertEquals(native, Files.readString(donorFile))
        }
    }

    @Test
    fun unsleevedNativeImportsProduceRedWorkWithSourceProvenance(): Unit = runBlocking(Dispatchers.IO) {
        val donor = directory.resolve("donor")
        val sleeve = Files.createDirectories(directory.resolve("sleeve"))
        source(donor, "import ctypes\n" + DONOR)
        var factories = 0
        try {
            val failure = assertFailsWith<Exception> {
                BertPythonSleeve.open(this, donor, sleeve, { factories++; Encoder() }).drain()
            }
            assertTrue(failure.message.orEmpty().contains("ctypes"))
            assertEquals(0, factories, "a refused source must not load native model weights")
            red("ctypes")
        } finally { AgentSleeveRegistry.clear("bert") }
    }

    @Test
    fun nativeConstructorFailureRemainsGuestCatchableAndRecorded(): Unit = runBlocking(Dispatchers.IO) {
        val donor = directory.resolve("donor")
        val sleeve = Files.createDirectories(directory.resolve("sleeve"))
        source(donor, """
            class Encoder:
                def __init__(self, weights, device='auto'):
                    import ctypes
                def encode(self, tokens):
                    raise AssertionError('native constructor must refuse first')
        """.trimIndent())
        try {
            val failure = assertFailsWith<GuestException> {
                BertPythonSleeve.open(this, donor, sleeve, { Encoder() }).drain()
            }
            assertEquals(GuestFailure.GUEST_ERROR, failure.kind)
            assertTrue(failure.message.orEmpty().contains("native module banned"))
            red("ctypes", TrapCatchability.GUEST_CATCHABLE)
        } finally { AgentSleeveRegistry.clear("bert") }
    }

    @Test
    fun sourceTreeEncoderReturnsTargets(): Unit = runBlocking(Dispatchers.IO) {
        val donor = directory.resolve("donor")
        val sleeve = directory.resolve("sleeve")
        val donorFile = source(donor, "import ctypes\n" + DONOR)
        source(sleeve, SLEEVE)
        val backend = Encoder()
        var encoder: BertPythonSleeve? = null
        var drained = false
        try {
            val active = BertPythonSleeve.open(this, donor, sleeve, { backend })
            encoder = active
            val sentence = "😀 The bank approves."
            val start = sentence.indexOf("bank")
            val targets = s_[BertToken("bank", sentence, start, start + 4),
                BertToken("bank", "The river bank is steep."), BertToken("missing", "No matching word.")]
            val vectors = active.encode(targets)
            assertEquals<List<BertToken>>(targets.view.toList(), backend.targets)
            assertEquals(listOf(1, 1, 1), backend.batches.map { it.size })
            assertEquals(backend.model, active.model)
            assertEquals(3, vectors.size)
            assertEquals(targets.view.toList(), vectors.view.map { it.token })
            assertEquals(listOf(BertSpan.Explicit, BertSpan.Exact, BertSpan.Missing), vectors.view.map { it.span })
            assertContentEquals(floatArrayOf(1f, -2f, 3f), vectors[0].values)
            assertContentEquals(floatArrayOf(1f, -2f, 3f), vectors[1].values)
            assertNull(vectors[2].values)
            assertEquals("import ctypes\n" + DONOR, Files.readString(donorFile))
            val manifest = assertNotNull(AgentSleeveRegistry.byId("bert"))
            assertTrue(manifest.isGreen, manifest.verdict)
            assertTrue(Files.isSameFile(donor, Path.of(assertNotNull(manifest.donorRoot))))
            assertTrue(Files.isSameFile(sleeve, Path.of(manifest.sleeveRoot)))
            active.drain(); drained = true
            assertEquals(1, backend.drains)
            assertFailsWith<IllegalStateException> { active.encode(targets) }
        } finally {
            try { if (!drained) encoder?.drain() } finally { AgentSleeveRegistry.clear("bert") }
        }
    }

    @Test
    fun sourceTreeBatchesContainOneSentenceAndAtMostSixteenTargets(): Unit = runBlocking(Dispatchers.IO) {
        val donor = directory.resolve("donor")
        val sleeve = directory.resolve("sleeve")
        source(donor, DONOR)
        source(sleeve, SLEEVE)
        val backend = Encoder()
        var encoder: BertPythonSleeve? = null
        try {
            val active = BertPythonSleeve.open(this, donor, sleeve, { backend })
            encoder = active
            val sentence = "The bank approves."
            val targets: Series<BertToken> = 20 j { i: Int ->
                when (i) {
                    17 -> BertToken("bank", "The river bank is steep.")
                    19 -> BertToken("missing", "No matching word.")
                    else -> BertToken("bank", sentence, 4, 8)
                }
            }
            val vectors = active.encode(targets)
            assertEquals(listOf(16, 1, 1, 1, 1), backend.batches.map { it.size })
            assertTrue(backend.batches.all { batch ->
                batch.size in 1..16 && batch.all { it.sentence == batch.first().sentence }
            })
            assertEquals<List<BertToken>>(targets.view.toList(), backend.targets)
            assertEquals(targets.size, vectors.size)
            assertEquals(targets.view.toList(), vectors.view.map { it.token })
            assertEquals(BertSpan.Missing, vectors[19].span)
            assertNull(vectors[19].values)
        } finally {
            try { encoder?.let { it.drain(); assertEquals(1, backend.drains) } }
            finally { AgentSleeveRegistry.clear("bert") }
        }
    }

    @Test
    fun guestEncoderFailureProducesAnUnresolvedCardAndDrainsItsBackend(): Unit = runBlocking(Dispatchers.IO) {
        val donor = directory.resolve("donor")
        val sleeve = directory.resolve("sleeve")
        source(donor, DONOR)
        source(sleeve, """
            class Encoder:
                def __init__(self, weights, device='auto'):
                    self.model = host.call('bert_model')
                def encode(self, tokens):
                    raise RuntimeError('expected encoder source failure')
        """.trimIndent())
        val backend = Encoder()
        var encoder: BertPythonSleeve? = null
        try {
            val active = BertPythonSleeve.open(this, donor, sleeve, { backend })
            encoder = active
            val failure = assertFailsWith<GuestException> { active.encode(s_[BertToken("bank", "The bank lends.")]) }
            assertEquals(GuestFailure.GUEST_ERROR, failure.kind)
            assertTrue(failure.message.orEmpty().contains("expected encoder source failure"))
            red("expected encoder source failure", TrapCatchability.GUEST_CATCHABLE)
            assertEquals(0, backend.targets.size)
            assertEquals(0, active.encode(emptySeriesOf()).size)
            red("expected encoder source failure", TrapCatchability.GUEST_CATCHABLE)
        } finally {
            try { encoder?.let { it.drain(); assertEquals(1, backend.drains) } }
            finally { AgentSleeveRegistry.clear("bert") }
        }
    }

    companion object {
        private val DONOR = """
            class Encoder:
                def __init__(self, weights, device='auto'):
                    self.weights = weights
                def encode(self, tokens):
                    raise RuntimeError('donor has no native inference in this fixture')
        """.trimIndent()
        private val SLEEVE = """
            import json
            class Encoder:
                def __init__(self, weights, device='auto'):
                    self.model = host.call('bert_model')
                    if weights is not None and weights != self.model:
                        raise ValueError('BERT weights are host-bound')
                def encode(self, tokens):
                    return json.loads(host.call('bert_encode', tokens))
        """.trimIndent()
    }
}
