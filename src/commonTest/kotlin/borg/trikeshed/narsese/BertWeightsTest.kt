package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.userspace.MemoryMapping
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.*
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.InMemoryFileOperations
import kotlin.test.*

class BertWeightsTest {
    private val config = BertConfig(2, 1, 1, 3, 4, 2, 5, 1e-12, 0)

    private fun pack(header: Map<String, Any?>, values: FloatArray): ByteArray {
        val json = jsonOf(header).encodeToByteArray()
        val bytes = ByteArray(8 + json.size + values.size * 4)
        for (i in 0..7) bytes[i] = (json.size.toLong() ushr (8 * i)).toByte()
        json.copyInto(bytes, 8)
        for (i in values.indices) {
            val bits = values[i].toBits()
            for (j in 0..3) bytes[8 + json.size + i * 4 + j] = (bits ushr (8 * j)).toByte()
        }
        return bytes
    }

    private fun descriptor(shape: List<Int>, from: Long, to: Long, dtype: String = "F32") =
        mapOf("dtype" to dtype, "shape" to shape, "data_offsets" to listOf(from, to))

    private fun decode(bytes: ByteArray, size: Long = bytes.size.toLong()): Map<String, BertTensor> =
        BertWeights.safetensors(size, { at, dst ->
            check(at >= 0 && at + dst.size <= bytes.size) { "truncated fixture" }
            bytes.copyInto(dst, 0, at.toInt(), at.toInt() + dst.size)
        })

    @Test
    fun littleEndianF32AndLegacyLayerNormNamesRetainPrimitiveStorage() {
        val bytes = pack(mapOf("bert.embeddings.LayerNorm.gamma" to descriptor(listOf(4), 0, 16)),
            floatArrayOf(1f, -2.5f, -0.0f, Float.MIN_VALUE))
        val tensor = decode(bytes).getValue("embeddings.LayerNorm.weight")
        assertEquals(1, tensor.a.size)
        assertEquals(4, tensor.a[0])
        assertContentEquals(floatArrayOf(1f, -2.5f, -0.0f, Float.MIN_VALUE), tensor.b)
        assertEquals((-0.0f).toBits(), tensor.b[2].toBits())
    }

    @Test
    fun numericTransfersRemainBoundedAcrossOneLargeTensor() {
        val values = FloatArray(BertWeights.BLOCK_BYTES / 4 + 7) { it.toFloat() }
        val bytes = pack(mapOf("parameter" to descriptor(listOf(values.size), 0, values.size.toLong() * 4)), values)
        val sizes = mutableListOf<Int>()
        val tensor = BertWeights.safetensors(bytes.size.toLong(), { at, dst ->
            if (at > 8) sizes.add(dst.size)
            bytes.copyInto(dst, 0, at.toInt(), at.toInt() + dst.size)
        }).getValue("parameter")
        assertContentEquals(values, tensor.b)
        assertEquals(listOf(BertWeights.BLOCK_BYTES, 28), sizes)
    }

    @Test
    fun malformedOffsetsShapesAndDtypesAreRefusedBeforeNumericReads() {
        val cases = listOf(
            descriptor(listOf(1), -1, 3),
            descriptor(listOf(1), 0, 8),
            descriptor(listOf(1), 4, 0),
            descriptor(listOf(1), 0, 4, "F16"),
            mapOf("dtype" to "F32", "shape" to listOf(1.5), "data_offsets" to listOf(0, 4)),
            descriptor(listOf(Int.MAX_VALUE, Int.MAX_VALUE), 0, 4),
        )
        for (entry in cases) {
            val bytes = pack(mapOf("bad" to entry), floatArrayOf(1f))
            var transfers = 0
            assertFailsWith<IllegalArgumentException> {
                BertWeights.safetensors(bytes.size.toLong(), { at, dst ->
                    transfers++
                    bytes.copyInto(dst, 0, at.toInt(), at.toInt() + dst.size)
                })
            }
            assertEquals(2, transfers, "invalid metadata must not trigger a tensor read")
        }
    }

    @Test
    fun overlappingRangesAliasesAndUnindexedDataAreRefused() {
        assertFailsWith<IllegalArgumentException> { decode(pack(mapOf(
            "one" to descriptor(listOf(1), 0, 4), "two" to descriptor(listOf(1), 0, 4)), floatArrayOf(1f, 2f))) }
        assertFailsWith<IllegalArgumentException> { decode(pack(mapOf(
            "bert.embeddings.LayerNorm.gamma" to descriptor(listOf(1), 0, 4),
            "embeddings.LayerNorm.weight" to descriptor(listOf(1), 4, 8)), floatArrayOf(1f, 2f))) }
        assertFailsWith<IllegalArgumentException> { decode(pack(mapOf("one" to descriptor(listOf(1), 0, 4)), floatArrayOf(1f, 2f))) }
    }

    @Test
    fun headerLengthsUtf8AndNonFiniteStorageAreRefused() {
        val tooLarge = ByteArray(8)
        val length = BertWeights.HEADER_BYTES.toLong() + 1
        for (i in 0..7) tooLarge[i] = (length ushr (8 * i)).toByte()
        assertFailsWith<IllegalArgumentException> { decode(tooLarge, 8 + length) }
        assertFailsWith<IllegalArgumentException> { decode(ByteArray(8) { -1 }) }
        val invalidUtf8 = byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0, -1, -1)
        assertFails { decode(invalidUtf8) }
        assertFailsWith<IllegalArgumentException> {
            decode(pack(mapOf("one" to descriptor(listOf(1), 0, 4)), floatArrayOf(Float.NaN)))
        }
    }

    @Test
    fun baseConfigAndEveryRequiredShapeAreValidated() {
        val parsed = BertConfig.parse(configJson(hidden = 768, layers = 12, heads = 12, intermediate = 3072,
            positions = 512, vocab = 30522))
        assertEquals(768, parsed.hiddenSize)
        assertEquals(12, parsed.numHiddenLayers)
        assertEquals(12, parsed.numAttentionHeads)
        assertEquals(3072, parsed.intermediateSize)
        assertEquals(512, parsed.maxPositionEmbeddings)
        assertEquals(1e-12, parsed.layerNormEps)
        assertEquals(197, BertWeights.required(parsed).size)
        assertFailsWith<IllegalArgumentException> { BertConfig.parse(configJson(heads = 3)) }
        assertFails { BertConfig.parse(configJson() + " trailing") }
        assertFailsWith<IllegalArgumentException> { BertWeights(config, emptyMap()) }
        val tensors = fixtureTensors().toMutableMap()
        tensors["embeddings.LayerNorm.weight"] = shapeOf(1) j floatArrayOf(1f)
        assertFailsWith<IllegalArgumentException> { BertWeights(config, tensors) }
    }

    @Test
    fun loadUsesPositionedShortReadsClosesBothFilesAndReleasesReferences() {
        val memory = InMemoryFileOperations()
        val files = object : FileOperations by memory {
            override fun readAllBytes(filename: String): ByteArray = error("whole-file read forbidden")
            override fun readString(filename: String): String = error("whole-file read forbidden")
        }
        val channels = listOf(BytesChannel(configJson().encodeToByteArray()), BytesChannel(fixturePack()))
        var opened = 0
        val weights = BertWeights.load(files, "/weights") { path ->
            assertEquals(if (opened == 0) "/weights/config.json" else "/weights/model.safetensors", path)
            channels[opened++]
        }
        assertEquals(2, opened)
        assertTrue(channels.all { !it.isOpen() })
        assertEquals(21, weights.tensors.size)
        val retainedMap = weights.tensors
        weights.close(); weights.close()
        assertTrue(retainedMap.isEmpty())
        assertFailsWith<IllegalStateException> { weights["embeddings.word_embeddings.weight"] }
    }

    @Test
    fun truncatedTransferClosesTheDescriptorAndPublishesNoModel() {
        val bytes = fixturePack()
        val configChannel = BytesChannel(configJson().encodeToByteArray())
        val weightChannel = BytesChannel(bytes.copyOf(bytes.size - 1), reportedSize = bytes.size.toLong())
        var opened = 0
        val failure = assertFailsWith<IllegalArgumentException> {
            BertWeights.load(InMemoryFileOperations(), "/weights") {
                if (opened++ == 0) configChannel else weightChannel
            }
        }
        assertTrue(failure.message.orEmpty().contains("Truncated BERT file"))
        assertFalse(configChannel.isOpen())
        assertFalse(weightChannel.isOpen())
    }

    private fun fixtureTensors(): Map<String, BertTensor> = BertWeights.required(config).mapValues { (_, shape) ->
        var count = 1
        for (extent in shape.view) count *= extent
        shape j FloatArray(count) { (it + 1).toFloat() }
    }

    private fun fixturePack(): ByteArray {
        val header = linkedMapOf<String, Any?>()
        val floats = mutableListOf<Float>()
        for ((name, tensor) in fixtureTensors()) {
            val from = floats.size.toLong() * 4
            floats.addAll(tensor.b.asList())
            header["bert." + name.replace(".LayerNorm.weight", ".LayerNorm.gamma")
                .replace(".LayerNorm.bias", ".LayerNorm.beta")] = descriptor(tensor.a.view.toList(), from, floats.size.toLong() * 4)
        }
        return pack(header, floats.toFloatArray())
    }

    private fun configJson(hidden: Int = 2, layers: Int = 1, heads: Int = 1, intermediate: Int = 3,
                           positions: Int = 4, vocab: Int = 5): String = jsonOf(mapOf(
        "model_type" to "bert", "hidden_size" to hidden, "num_hidden_layers" to layers,
        "num_attention_heads" to heads, "intermediate_size" to intermediate, "max_position_embeddings" to positions,
        "type_vocab_size" to 2, "vocab_size" to vocab, "layer_norm_eps" to 1e-12,
        "pad_token_id" to 0, "hidden_act" to "gelu"))

    private class BytesChannel(private val bytes: ByteArray, private val reportedSize: Long = bytes.size.toLong()) : FileChannel() {
        private var open = true
        override fun read(dst: ByteBuffer, position: Long): Int {
            val count = minOf(3, dst.remaining(), maxOf(0, bytes.size - position.toInt()))
            if (count == 0) return -1
            repeat(count) { dst.put(bytes[position.toInt() + it]) }
            return count
        }
        override fun size() = reportedSize
        override fun close() { open = false }
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
