package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.userspace.MemoryMapping
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.*
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BertTokenizerConfigTest {
    private val words = s_["[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "bank"]
    private val config = BertConfig(2, 1, 1, 3, 512, 2, words.size, 1e-12, 0)
    private val tokenizer = BertWordPiece(words)
    private val options = mapOf("do_lower_case" to true, "model_max_length" to 512)

    @Test
    fun cachedLegacySerializationAndExplicitEquivalentRulesAreAdmitted() {
        validate(serialized())
        val json = serialized().toMutableMap()
        json["model"] = model() + mapOf("type" to "WordPiece")
        json["normalizer"] = normalizer() + mapOf("strip_accents" to true)
        validate(json)
    }

    @Test
    fun normalizationAndWordPieceOverridesFailWithTheUnsupportedRule() {
        val cases = listOf(
            "normalizer" to (normalizer() + mapOf("lowercase" to false)),
            "normalizer" to (normalizer() + mapOf("strip_accents" to false)),
            "normalizer" to (normalizer() + mapOf("handle_chinese_chars" to false)),
            "normalizer" to (normalizer() + mapOf("clean_text" to false)),
            "pre_tokenizer" to mapOf("type" to "Whitespace"),
            "model" to (model() + mapOf("type" to "BPE")),
            "model" to (model() + mapOf("continuing_subword_prefix" to "@@")),
            "model" to (model() + mapOf("max_input_chars_per_word" to 200)),
        )
        for ((field, value) in cases) {
            val failure = assertFailsWith<IllegalArgumentException> { validate(serialized() + mapOf(field to value)) }
            assertTrue(failure.message.orEmpty().startsWith("Unsupported BERT tokenizer"))
        }
    }

    @Test
    fun tokenizerOptionsCannotSilentlyOverrideImplementedRules() {
        val cases = listOf(
            mapOf("do_lower_case" to false), mapOf("model_max_length" to 1024),
            mapOf("padding_side" to "left"), mapOf("truncation_side" to "left"),
            mapOf("do_basic_tokenize" to false), mapOf("never_split" to listOf("bank")),
            mapOf("unk_token" to "<unk>"), mapOf("tokenizer_class" to "RobertaTokenizer"),
            mapOf("split_special_tokens" to true), mapOf("additional_special_tokens" to listOf("bank")),
        )
        for (override in cases) assertFailsWith<IllegalArgumentException> {
            BertTokenizerConfig.validate(jsonOf(options + override), jsonOf(serialized()), tokenizer, config)
        }
        assertFailsWith<IllegalArgumentException> {
            BertTokenizerConfig.validate(jsonOf(options), jsonOf(serialized()), tokenizer, config,
                jsonOf(mapOf("unk_token" to "<unk>")))
        }
    }

    @Test
    fun vocabularyIdsSpecialFlagsAndTemplateMustAgreeWithTheTextVocabulary() {
        val changedVocab = model() + mapOf("vocab" to (tokenizer.vocabulary + mapOf("bank" to 1)))
        assertFailsWith<IllegalArgumentException> { validate(serialized() + mapOf("model" to changedVocab)) }
        val added = specialTokens().toMutableList()
        added[0] = added[0] + mapOf("lstrip" to true)
        assertFailsWith<IllegalArgumentException> { validate(serialized() + mapOf("added_tokens" to added)) }
        assertFailsWith<IllegalArgumentException> {
            validate(serialized() + mapOf("post_processor" to (processor() + mapOf("single" to emptyList<Any>()))))
        }
        assertFailsWith<IllegalArgumentException> {
            validate(serialized(), config.copy(padTokenId = 1))
        }
    }

    @Test
    fun metadataReadsAreBoundedAndEveryOpenedDescriptorClosesOnFailure() {
        val metadata = BytesChannel(jsonOf(options).encodeToByteArray())
        val json = BytesChannel(jsonOf(serialized() + mapOf("pre_tokenizer" to mapOf("type" to "Whitespace"))).encodeToByteArray())
        val vocabulary = BytesChannel(words.view.joinToString("\n").encodeToByteArray())
        val channels = listOf(metadata, json, vocabulary)
        var opened = 0
        assertFailsWith<IllegalArgumentException> {
            BertTokenizerConfig.load(InMemoryFileOperations(), "/weights", config) { channels[opened++] }
        }
        assertEquals(3, opened)
        assertTrue(channels.all { !it.isOpen() })
        val oversized = BytesChannel(byteArrayOf(1), BertTokenizerConfig.FILE_BYTES + 1)
        assertFailsWith<IllegalArgumentException> {
            BertTokenizerConfig.load(InMemoryFileOperations(), "/weights", config) { oversized }
        }
        assertFalse(oversized.isOpen())
        assertEquals(0, oversized.reads)
    }

    @Test
    fun aTruncatedMetadataTransferFailsBeforeTokenizerConstruction() {
        val channel = BytesChannel(byteArrayOf('{'.code.toByte()), 8)
        val failure = assertFailsWith<IllegalArgumentException> {
            BertTokenizerConfig.load(InMemoryFileOperations(), "/weights", config) { channel }
        }
        assertTrue(failure.message.orEmpty().contains("Truncated BERT tokenizer file"))
        assertFalse(channel.isOpen())
    }

    @Test
    fun cancelledLoadDeliveryClosesAssetsWithoutStartingAWorkerUnderTheLiveParent() = runTest {
        val parentJob = SupervisorJob()
        val parent = CoroutineScope(coroutineContext.minusKey(Job) + parentJob + InMemoryFileOperations())
        lateinit var caller: Job
        val channels = mapOf(
            "/weights/config.json" to BytesChannel(jsonOf(mapOf(
                "model_type" to "bert", "hidden_size" to config.hiddenSize, "num_hidden_layers" to config.numHiddenLayers,
                "num_attention_heads" to config.numAttentionHeads, "intermediate_size" to config.intermediateSize,
                "max_position_embeddings" to config.maxPositionEmbeddings, "type_vocab_size" to config.typeVocabSize,
                "vocab_size" to config.vocabSize, "layer_norm_eps" to config.layerNormEps,
                "pad_token_id" to config.padTokenId, "hidden_act" to config.hiddenAct)).encodeToByteArray()),
            "/weights/model.safetensors" to BytesChannel(parameters()),
            "/weights/tokenizer_config.json" to BytesChannel(jsonOf(options).encodeToByteArray()),
            "/weights/tokenizer.json" to BytesChannel(jsonOf(serialized()).encodeToByteArray()),
            "/weights/vocab.txt" to BytesChannel(words.view.joinToString("\n").encodeToByteArray(),
                onClose = { caller.cancel(CancellationException("cancelled before asset delivery")) }),
        )
        val opened = mutableListOf<BytesChannel>()
        var unexpected: BertModel? = null
        try {
            caller = launch(start = CoroutineStart.LAZY) {
                unexpected = BertModel.load(parent, "/weights", channels = { path ->
                    channels.getValue(path).also { opened.add(it) }
                })
                fail("cancelled load must not deliver a model")
            }
            caller.start()
            caller.join()
            assertTrue(caller.isCancelled)
            assertNull(unexpected)
            assertEquals(5, opened.size)
            assertTrue(opened.all { !it.isOpen() }, "all assets close before failed delivery")
            assertTrue(parentJob.isActive, "loader cancellation must leave its distinct parent alive")
            assertFalse(parentJob.children.any(), "failed delivery must not retain a model worker or its weights")
        } finally {
            unexpected?.drain()
            parentJob.cancelAndJoin()
        }
    }

    private fun parameters(): ByteArray {
        val header = linkedMapOf<String, Any?>()
        var elements = 0
        for ((name, shape) in BertWeights.required(config)) {
            val count = shape.view.fold(1) { total, extent -> total * extent }
            header[name] = mapOf("dtype" to "F32", "shape" to shape.view.toList(),
                "data_offsets" to listOf(elements.toLong() * 4, (elements + count).toLong() * 4))
            elements += count
        }
        val json = jsonOf(header).encodeToByteArray()
        val bytes = ByteArray(8 + json.size + elements * 4)
        for (i in 0..7) bytes[i] = (json.size.toLong() ushr (8 * i)).toByte()
        json.copyInto(bytes, 8)
        val bits = 1f.toBits()
        for (i in 0 until elements) for (j in 0..3) bytes[8 + json.size + i * 4 + j] = (bits ushr (8 * j)).toByte()
        return bytes
    }

    private fun validate(json: Map<String, Any?>, selected: BertConfig = config) =
        BertTokenizerConfig.validate(jsonOf(options), jsonOf(json), tokenizer, selected)

    private fun normalizer(): Map<String, Any?> = mapOf("type" to "BertNormalizer", "clean_text" to true,
        "handle_chinese_chars" to true, "strip_accents" to null, "lowercase" to true)

    private fun model(): Map<String, Any?> = mapOf("unk_token" to "[UNK]", "continuing_subword_prefix" to "##",
        "max_input_chars_per_word" to 100, "vocab" to tokenizer.vocabulary)

    private fun specialTokens(): List<Map<String, Any?>> = tokenizer.special.view.map { token ->
        mapOf("id" to tokenizer.vocabulary.getValue(token), "special" to true, "content" to token,
            "single_word" to false, "lstrip" to false, "rstrip" to false, "normalized" to false)
    }

    private fun processor(): Map<String, Any?> = mapOf(
        "type" to "TemplateProcessing",
        "single" to listOf(mapOf("SpecialToken" to mapOf("id" to "[CLS]", "type_id" to 0)),
            mapOf("Sequence" to mapOf("id" to "A", "type_id" to 0)),
            mapOf("SpecialToken" to mapOf("id" to "[SEP]", "type_id" to 0))),
        "special_tokens" to listOf("[CLS]", "[SEP]").associateWith { token ->
            mapOf("id" to token, "ids" to listOf(tokenizer.vocabulary.getValue(token)), "tokens" to listOf(token))
        })

    private fun serialized(): Map<String, Any?> = mapOf("version" to "1.0", "truncation" to null,
        "padding" to null, "normalizer" to normalizer(), "pre_tokenizer" to mapOf("type" to "BertPreTokenizer"),
        "post_processor" to processor(), "decoder" to mapOf("type" to "WordPiece", "prefix" to "##", "cleanup" to true),
        "model" to model(), "added_tokens" to specialTokens())

    private class BytesChannel(private val bytes: ByteArray, private val reportedSize: Long = bytes.size.toLong(),
                               private val onClose: () -> Unit = {}) : FileChannel() {
        private var open = true
        var reads = 0
        override fun read(dst: ByteBuffer, position: Long): Int {
            reads++
            val count = minOf(7, dst.remaining(), maxOf(0, bytes.size - position.toInt()))
            if (count == 0) return -1
            repeat(count) { dst.put(bytes[position.toInt() + it]) }
            return count
        }
        override fun size() = reportedSize
        override fun close() { if (open) { open = false; onClose() } }
        override fun isOpen() = open
        override fun implCloseChannel() = close()
        override fun begin() {}
        override fun end(completed: Boolean) {}
        override fun read(dst: ByteBuffer): Int = error("positioned read required")
        override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = error("unused")
        override fun read(dsts: Array<out ByteBuffer>): Long = error("unused")
        override fun write(src: ByteBuffer): Int = error("read only")
        override fun write(src: ByteBuffer, position: Long): Int = error("read only")
        override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = error("read only")
        override fun write(srcs: Array<out ByteBuffer>): Long = error("read only")
        override fun position(): Long = error("unused")
        override fun position(newPosition: Long): FileChannel = error("unused")
        override fun truncate(size: Long): FileChannel = error("read only")
        override fun force(metaData: Boolean) = error("read only")
        override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = error("unused")
        override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = error("read only")
        override fun map(mode: MapMode, position: Long, size: Long): MemoryMapping = error("unused")
        override fun lock(position: Long, size: Long, shared: Boolean): FileLock = error("unused")
        override fun lock(): FileLock = error("unused")
        override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = error("unused")
        override fun tryLock(): FileLock? = error("unused")
    }
}
