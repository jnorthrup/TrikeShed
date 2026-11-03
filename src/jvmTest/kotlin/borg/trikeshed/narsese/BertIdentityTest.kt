package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class BertIdentityTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path
    private val config = BertConfig(2, 1, 1, 3, 8, 2, 7, 1e-5, 0)

    private fun weights(config: BertConfig = this.config, reversed: Boolean = false): BertWeights {
        val required = BertWeights.required(config).entries.toList()
        val tensors = LinkedHashMap<String, BertTensor>()
        for ((name, shape) in if (reversed) required.reversed() else required) {
            tensors[name] = shape j FloatArray(tensorSize(shape))
        }
        return BertWeights(config, tensors)
    }

    private fun tokenizer(reversed: Boolean = false): BertWordPiece = BertWordPiece(
        if (reversed) s_["[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "b", "a"]
        else s_["[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "a", "b"], 8)

    @Test fun identityBindsEffectiveParametersAndTokenizer() {
        val first = weights()
        val reordered = weights(reversed = true)
        val expected = BertIdentity.digest(first, tokenizer())
        assertEquals(64, expected.length)
        assertTrue(expected.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(expected, BertIdentity.digest(reordered, tokenizer()))
        assertNotEquals(expected, BertIdentity.digest(first, tokenizer(reversed = true)))
        val changed = weights(config.copy(layerNormEps = 1e-6))
        assertNotEquals(expected, BertIdentity.digest(changed, tokenizer()))
        reordered["embeddings.word_embeddings.weight"].b[0] = 1f
        assertNotEquals(expected, BertIdentity.digest(reordered, tokenizer()))
    }

    @Test fun identityHonorsCancellationChecks() {
        val weights = weights()
        var checks = 0
        assertFailsWith<IllegalStateException> {
            BertIdentity.digest(weights, tokenizer()) { if (++checks == 2) error("stop") }
        }
        assertEquals(2, checks)
    }

    @Test fun factoryIdentityChangesWhenTheSameDirectoryIsReplaced(): Unit = runBlocking {
        Files.writeString(directory.resolve("config.json"), jsonOf(_m[
            "model_type" j "bert", "hidden_size" j config.hiddenSize,
            "num_hidden_layers" j config.numHiddenLayers, "num_attention_heads" j config.numAttentionHeads,
            "intermediate_size" j config.intermediateSize, "max_position_embeddings" j config.maxPositionEmbeddings,
            "type_vocab_size" j config.typeVocabSize, "vocab_size" j config.vocabSize,
            "layer_norm_eps" j config.layerNormEps, "pad_token_id" j config.padTokenId, "hidden_act" j config.hiddenAct,
        ]))
        Files.writeString(directory.resolve("tokenizer_config.json"), "{\"do_lower_case\":true}")
        Files.writeString(directory.resolve("vocab.txt"), "[PAD]\n[UNK]\n[CLS]\n[SEP]\n[MASK]\na\nb\n")
        val header = LinkedHashMap<String, Any?>()
        var offset = 0
        for ((name, shape) in BertWeights.required(config)) {
            val length = tensorSize(shape) * 4
            header[name] = _m["dtype" j "F32", "shape" j shape.view.toList(),
                "data_offsets" j _l[offset, offset + length]]
            offset += length
        }
        val json = jsonOf(header).encodeToByteArray()
        val image = ByteArray(8 + json.size + offset)
        for (i in 0..7) image[i] = (json.size.toLong() ushr (8 * i)).toByte()
        json.copyInto(image, 8)
        val path = directory.resolve("model.safetensors")
        Files.write(path, image)
        val scope = CoroutineScope(coroutineContext + JvmFileOperations())
        val first = BertModel.load(scope, directory.toString(), "fixture")
        try {
            assertTrue(first.model.startsWith("fixture@${BertIdentity.VERSION}:"))
            val before = first.encode(s_[BertToken("a", "a")])[0]
            assertEquals(BertSpan.Exact, before.span)
            image[8 + json.size + 2] = 0x80.toByte()
            image[8 + json.size + 3] = 0x3f.toByte()
            Files.write(path, image)
            val replacement = BertModel.load(scope, directory.toString(), "fixture")
            try {
                assertNotEquals(first.model, replacement.model)
                assertContentEquals(before.values, first.encode(s_[BertToken("a", "a")])[0].values)
                val repeated = BertModel.load(scope, directory.toString(), "fixture")
                try { assertEquals(replacement.model, repeated.model) } finally { repeated.drain() }
            } finally { replacement.drain() }
        } finally { first.drain() }
    }
}
