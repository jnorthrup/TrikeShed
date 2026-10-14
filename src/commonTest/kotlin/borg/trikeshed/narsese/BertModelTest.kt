package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.*

class BertModelTest {
    private val vocabulary = "[PAD]\n[UNK]\n[CLS]\n[SEP]\n[MASK]\na\nbank\nrun\n##ning\nriver"
    private val sentence = "a bank running"
    private val bank = BertToken("bank", sentence)
    private val running = BertToken("running", sentence)
    private val explicit = BertToken("bank", sentence, 2, 6)

    private fun weights(layers: Int = 4, positions: Int = 16): BertWeights {
        val config = BertConfig(4, layers, 2, 6, positions, 2, 10, 1e-5, 0)
        val tensors = LinkedHashMap<String, BertTensor>()
        for ((name, shape) in BertWeights.required(config)) {
            var seed = 0
            for (character in name) seed = (seed * 31 + character.code) % 997
            val values = FloatArray(shape.view.fold(1) { count, dimension -> count * dimension }) { i ->
                val coordinate = ((seed + i * 17) % 101 - 50).toFloat()
                when {
                    name.endsWith("LayerNorm.weight") -> 1f + coordinate * .002f
                    name.endsWith(".bias") -> coordinate * .003f
                    else -> coordinate * .01f
                }
            }
            tensors[name] = shape j values
        }
        return BertWeights(config, tensors)
    }

    private fun close(expected: FloatArray, vector: BertVector) {
        val actual = assertNotNull(vector.values)
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertTrue(actual[i].isFinite() && abs(expected[i] - actual[i]) <= 3e-5f,
            "coordinate $i: expected ${expected[i]}, got ${actual[i]}")
    }

    @Test fun fourLayerWordPiecePoolingMatchesIndependentTorchModel() = runTest {
        val parameters = weights()
        val tokenizer = BertWordPiece(vocabulary, 16)
        assertEquals(listOf(2, 5, 6, 7, 8, 3), tokenizer.sentence(sentence).ids.view.toList())
        val model = BertModel(this, parameters, tokenizer, "fixture-common")
        try {
            var read = 0
            val targets = s_[bank, running, explicit]
            val result = model.encode(targets.size j { i: Int -> read++; targets[i] })
            assertEquals(3, read, "Admission snapshots each supplied target once")
            assertEquals(BertSpan.Exact, result[0].span)
            assertEquals(BertSpan.Exact, result[1].span)
            assertEquals(BertSpan.Explicit, result[2].span)
            // transformers.BertModel, eager CPU F32, deterministic weights above;
            // average hidden_states[-4:], then all complete target WordPieces.
            val bankVector = floatArrayOf(-.030064136f, .61820632f, 1.02417493f, -1.60981691f)
            close(bankVector, result[0])
            close(floatArrayOf(.1191479f, .50454038f, .52590156f, -1.15689623f), result[1])
            close(bankVector, result[2])
            assertEquals(explicit, result[2].token)
        } finally { model.drain() }
        assertTrue(parameters.tensors.isEmpty())
    }

    @Test fun shallowModelsPoolEmbeddingAndLayerStatesLikeTorch() = runTest {
        val parameters = weights(1)
        val model = BertModel(this, parameters, BertWordPiece(vocabulary, 16), "fixture-shallow")
        try {
            val result = model.encode(s_[bank, running])
            close(floatArrayOf(-.37374321f, .66639668f, 1.35800946f, -1.59119964f), result[0])
            close(floatArrayOf(-.04873323f, .49819654f, .61940968f, -1.00801206f), result[1])
        } finally { model.drain() }
    }

    @Test fun parallelLongSentenceMatchesTorchAndDrainsAllRowWorkers() = runTest {
        val text = "a ".repeat(62) + "bank running"
        val tokenizer = BertWordPiece(vocabulary, 512)
        val encoded = tokenizer.sentence(text)
        assertEquals(67, encoded.ids.size)
        assertEquals(listOf(6, 7, 8, 3), (encoded.ids[63 until 67]).view.toList())
        val parameters = weights(positions = 512)
        val parent = SupervisorJob(coroutineContext[Job])
        val model = BertModel(CoroutineScope(coroutineContext + parent), parameters, tokenizer, "fixture-parallel")
        try {
            val targets = s_[BertToken("bank", text), BertToken("running", text)]
            val first = async(start = CoroutineStart.UNDISPATCHED) { model.encode(targets) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { model.encode(s_[BertToken("bank", text, 124, 128)]) }
            val drain = async(start = CoroutineStart.UNDISPATCHED) { model.drain() }
            // Independent eager Torch CPU model, identical four layers and 512 position weights;
            // bank at piece63, running at pieces64..65, pooled over hidden_states[-4:].
            val bankVector = floatArrayOf(.092068963f, .58467424f, .95727211f, -1.63286877f)
            val result = first.await()
            assertEquals(BertSpan.Exact, result[0].span)
            assertEquals(BertSpan.Exact, result[1].span)
            close(bankVector, result[0])
            close(floatArrayOf(.16733804f, .51837194f, .57783616f, -1.26833439f), result[1])
            val reused = second.await()[0]
            assertEquals(BertSpan.Explicit, reused.span)
            close(bankVector, reused)
            drain.await()
            assertTrue(parameters.tensors.isEmpty())
            assertFalse(parent.children.any(), "All model and row workers leave their owner after drain")
        } finally {
            model.drain()
            parent.complete()
            parent.join()
        }
    }

    @Test fun absentAndAmbiguousTargetsRemainWithoutVectors() = runTest {
        val parameters = weights()
        val model = BertModel(this, parameters, BertWordPiece(vocabulary, 16), "fixture-spans")
        try {
            val result = model.encode(s_[BertToken("absent", sentence), BertToken("bank", "bank bank")])
            assertEquals(BertSpan.Missing, result[0].span)
            assertEquals(BertSpan.Ambiguous, result[1].span)
            assertNull(result[0].values)
            assertNull(result[1].values)
            close(floatArrayOf(-.030064136f, .61820632f, 1.02417493f, -1.60981691f), model.encode(s_[bank])[0])
        } finally { model.drain() }
    }

    @Test fun airgapRetainsLocalInferenceAndItsNumericalResult() = runTest {
        withContext(modelmux.Airgap()) {
            assertFailsWith<modelmux.Airgap.Rejection> { modelmux.Airgap.requireRemote() }
            val parameters = weights()
            val model = BertModel(this, parameters, BertWordPiece(vocabulary, 16), "fixture-airgap")
            try {
                close(floatArrayOf(-.030064136f, .61820632f, 1.02417493f, -1.60981691f),
                    model.encode(s_[bank])[0])
            } finally { model.drain() }
            assertTrue(parameters.tensors.isEmpty())
        }
    }

    @Test fun drainFinishesEnteredBatchesBeforeReleasingParameterStorage() = runTest {
        val parameters = weights()
        val parent = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + parent)
        val model = BertModel(scope, parameters, BertWordPiece(vocabulary, 16), "fixture-drain")
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { model.encode(s_[bank, running]) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { model.encode(s_[explicit]) }
            val drain = async(start = CoroutineStart.UNDISPATCHED) { model.drain() }
            close(floatArrayOf(-.030064136f, .61820632f, 1.02417493f, -1.60981691f), first.await()[0])
            close(floatArrayOf(-.030064136f, .61820632f, 1.02417493f, -1.60981691f), second.await()[0])
            drain.await()
            assertTrue(parameters.tensors.isEmpty(), "Drained model retains no parameter arrays")
            assertFalse(parent.children.any(), "Drained worker and supervisor leave their owner")
            assertFailsWith<IllegalStateException> { parameters["embeddings.word_embeddings.weight"] }
            assertFailsWith<IllegalStateException> { model.encode(s_[bank]) }
        } finally {
            model.drain()
            parent.complete()
            parent.join()
        }
    }
}
