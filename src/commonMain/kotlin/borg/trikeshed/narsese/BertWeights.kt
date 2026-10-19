package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import borg.trikeshed.userspace.nio.file.spi.FileOperations

typealias BertScalar = Float
typealias BertTensor = Join<Shape, FloatArray>
typealias BertRead = (Long, ByteArray) -> Unit

data class BertConfig(
    val hiddenSize: Int,
    val numHiddenLayers: Int,
    val numAttentionHeads: Int,
    val intermediateSize: Int,
    val maxPositionEmbeddings: Int,
    val typeVocabSize: Int,
    val vocabSize: Int,
    val layerNormEps: Double,
    val padTokenId: Int,
    val hiddenAct: String = "gelu",
) {
    init {
        require(hiddenSize in 1..4096 && numHiddenLayers in 1..64 && numAttentionHeads in 1..128 &&
            hiddenSize % numAttentionHeads == 0) { "Unsupported BERT hidden/layer/head dimensions" }
        require(intermediateSize in hiddenSize..16384 && maxPositionEmbeddings in 1..4096 &&
            typeVocabSize in 1..256 && vocabSize in 1..1_000_000) { "Unsupported BERT embedding/intermediate dimensions" }
        require(layerNormEps.isFinite() && layerNormEps > 0.0 && layerNormEps <= 1.0) { "Invalid BERT layer_norm_eps" }
        require(padTokenId in 0 until vocabSize) { "Invalid BERT pad_token_id" }
        require(hiddenAct == "gelu") { "Unsupported BERT hidden_act: $hiddenAct" }
    }

    companion object {
        fun parse(json: String): BertConfig {
            val fields = objectMap(json, "BERT config")
            require(fields["model_type"] == "bert") { "Unsupported model_type: ${fields["model_type"]}" }
            require((fields["position_embedding_type"] ?: "absolute") == "absolute") {
                "Unsupported BERT position_embedding_type: ${fields["position_embedding_type"]}"
            }
            fun integer(name: String): Int = integer(fields[name], "BERT config $name", Int.MAX_VALUE.toLong()).toInt()
            val eps = (fields["layer_norm_eps"] as? Number)?.toDouble()
                ?: error("Missing BERT config layer_norm_eps")
            return BertConfig(integer("hidden_size"), integer("num_hidden_layers"), integer("num_attention_heads"),
                integer("intermediate_size"), integer("max_position_embeddings"), integer("type_vocab_size"),
                integer("vocab_size"), eps, integer("pad_token_id"), fields["hidden_act"] as? String
                    ?: error("Missing BERT config hidden_act"))
        }
    }
}

/** One model owns the primitive storage; closing it removes every retained parameter reference. */
class BertWeights(val config: BertConfig, tensors: Map<String, BertTensor>) {
    private val storage = tensors.toMutableMap()
    private var closed = false
    val tensors: Map<String, BertTensor> get() = storage

    init {
        for ((name, shape) in required(config)) {
            val tensor = requireNotNull(storage[name]) { "Missing BERT tensor: $name" }
            require(tensor.a.size == shape.size && shape.view.withIndex().all { (i, n) -> tensor.a[i] == n }) {
                "BERT tensor $name shape ${tensor.a.view.joinToString()} differs from ${shape.view.joinToString()}"
            }
            var count = 1L
            for (extent in shape.view) count *= extent
            require(tensor.b.size.toLong() == count) { "BERT tensor $name storage disagrees with its shape" }
        }
    }

    operator fun get(name: String): BertTensor {
        check(!closed) { "BERT weights are closed" }
        return requireNotNull(storage[name]) { "Missing BERT tensor: $name" }
    }

    fun close() {
        if (!closed) { closed = true; storage.clear() }
    }

    companion object {
        const val HEADER_BYTES = 1_048_576
        const val MODEL_BYTES = 4_294_967_296L
        const val BLOCK_BYTES = 65_536

        /** The masked-LM head; its decoder matrix is tied to `embeddings.word_embeddings.weight`. */
        fun head(config: BertConfig): Map<String, Shape> = buildMap {
            val h = config.hiddenSize
            put("cls.predictions.transform.dense.weight", shapeOf(h, h))
            put("cls.predictions.transform.dense.bias", shapeOf(h))
            put("cls.predictions.transform.LayerNorm.weight", shapeOf(h))
            put("cls.predictions.transform.LayerNorm.bias", shapeOf(h))
            put("cls.predictions.bias", shapeOf(config.vocabSize))
        }

        /** Only hidden-state parameters: classification heads and the unused pooler remain on disk. */
        fun required(config: BertConfig): Map<String, Shape> = buildMap {
            val h = config.hiddenSize
            put("embeddings.word_embeddings.weight", shapeOf(config.vocabSize, h))
            put("embeddings.position_embeddings.weight", shapeOf(config.maxPositionEmbeddings, h))
            put("embeddings.token_type_embeddings.weight", shapeOf(config.typeVocabSize, h))
            put("embeddings.LayerNorm.weight", shapeOf(h))
            put("embeddings.LayerNorm.bias", shapeOf(h))
            for (layer in 0 until config.numHiddenLayers) {
                val prefix = "encoder.layer.$layer."
                for (projection in listOf("query", "key", "value")) {
                    put(prefix + "attention.self.$projection.weight", shapeOf(h, h))
                    put(prefix + "attention.self.$projection.bias", shapeOf(h))
                }
                for (part in listOf("attention.output", "output")) {
                    put(prefix + "$part.dense.weight", shapeOf(h, if (part == "output") config.intermediateSize else h))
                    put(prefix + "$part.dense.bias", shapeOf(h))
                    put(prefix + "$part.LayerNorm.weight", shapeOf(h))
                    put(prefix + "$part.LayerNorm.bias", shapeOf(h))
                }
                put(prefix + "intermediate.dense.weight", shapeOf(config.intermediateSize, h))
                put(prefix + "intermediate.dense.bias", shapeOf(config.intermediateSize))
            }
        }

        /** Filesystem naming comes from the SPI; positioned transfers use the common NIO/uring channel. */
        fun load(files: FileOperations, directory: String, masked: Boolean = false,
                 channels: (String) -> FileChannel = { FileChannel.open(it, StandardOpenOption.READ) }): BertWeights {
            val configPath = files.resolvePath(directory, "config.json")
            val config = withChannel(channels(configPath)) { channel ->
                val size = channel.size()
                require(size in 1..HEADER_BYTES.toLong()) { "BERT config exceeds $HEADER_BYTES bytes: $configPath" }
                BertConfig.parse(read(channel, 0, ByteArray(size.toInt())).decodeToString(throwOnInvalidSequence = true))
            }
            val weightsPath = files.resolvePath(directory, "model.safetensors")
            val selected = if (masked) required(config) + head(config) else required(config)
            return withChannel(channels(weightsPath)) { channel ->
                BertWeights(config, safetensors(channel.size(), { at, bytes -> read(channel, at, bytes) }, selected))
            }
        }

        /** Strict header/range validation precedes every allocation of numeric storage. */
        fun safetensors(size: Long, read: BertRead, selected: Map<String, Shape>? = null): Map<String, BertTensor> {
            require(size in 8..MODEL_BYTES) { "Invalid BERT safetensors size: $size" }
            val length = ByteArray(8).also { read(0, it) }
            var headerBytes = 0L
            for (i in 0..7) {
                val byte = length[i].toLong() and 255
                require(i < 7 || byte < 128) { "BERT safetensors header length exceeds signed range" }
                headerBytes = headerBytes or (byte shl (8 * i))
            }
            require(headerBytes in 2..HEADER_BYTES.toLong() && headerBytes <= size - 8) {
                "Invalid BERT safetensors header length: $headerBytes"
            }
            val fields = objectMap(ByteArray(headerBytes.toInt()).also { read(8, it) }
                .decodeToString(throwOnInvalidSequence = true), "BERT safetensors header")
            val dataAt = 8 + headerBytes
            val parameters = LinkedHashMap<String, Join<Shape, Twin<Long>>>()
            for ((source, value) in fields) {
                if (source == "__metadata__") {
                    require(value is Map<*, *> && value.entries.all { it.key is String && it.value is String }) {
                        "Invalid BERT safetensors metadata"
                    }
                    continue
                }
                val name = source.removePrefix("bert.").replace(".LayerNorm.gamma", ".LayerNorm.weight")
                    .replace(".LayerNorm.beta", ".LayerNorm.bias").replace("cls.predictions.decoder.bias", "cls.predictions.bias")
                require(name.isNotBlank() && name !in parameters) { "Duplicate BERT tensor: $name" }
                val entry = value as? Map<*, *> ?: error("Invalid BERT tensor descriptor: $source")
                require(entry.keys.toSet().size == entry.size) { "Duplicate BERT tensor $source descriptor field" }
                val width = when (entry["dtype"]) {
                    "F32", "I32" -> 4; "F64", "I64" -> 8; "F16", "BF16", "I16" -> 2; "I8", "U8", "BOOL" -> 1
                    else -> error("Unsupported BERT tensor $source dtype: ${entry["dtype"]}")
                }
                require(entry["dtype"] == "F32" || (selected != null && name !in selected)) {
                    "Unsupported BERT tensor $source dtype: ${entry["dtype"]}"
                }
                val dims = entry["shape"] as? List<*> ?: error("Missing BERT tensor $source shape")
                require(dims.size <= 8) { "BERT tensor $source rank exceeds eight" }
                val shape = IntArray(dims.size) { integer(dims[it], "BERT tensor $source shape", Int.MAX_VALUE.toLong()).toInt() }
                var count = 1L
                for (extent in shape) {
                    require(extent == 0 || count <= MODEL_BYTES / 4 / extent) { "BERT tensor $source shape exceeds storage bound" }
                    count *= extent
                }
                val offsets = entry["data_offsets"] as? List<*> ?: error("Missing BERT tensor $source data_offsets")
                require(offsets.size == 2) { "Invalid BERT tensor $source data_offsets" }
                val from = integer(offsets[0], "BERT tensor $source offset", size - dataAt)
                val to = integer(offsets[1], "BERT tensor $source offset", size - dataAt)
                require(to >= from && to - from == count * width) { "BERT tensor $source offsets disagree with its shape" }
                parameters[name] = shape.toSeries() j (from j to)
            }
            var end = 0L
            for ((name, parameter) in parameters.entries.sortedBy { it.value.b.a }) {
                require(parameter.b.a == end) { "BERT tensor $name range overlaps or leaves a hole at $end" }
                end = parameter.b.b
            }
            require(end == size - dataAt) { "BERT safetensors data length disagrees with its header" }
            if (selected != null) for ((name, expected) in selected) {
                val shape = requireNotNull(parameters[name]) { "Missing BERT tensor: $name" }.a
                require(shape.size == expected.size && expected.view.withIndex().all { (i, n) -> shape[i] == n }) {
                    "BERT tensor $name has an invalid shape"
                }
            }
            val scratch = ByteArray(BLOCK_BYTES)
            return buildMap {
                for ((name, parameter) in parameters) {
                    if (selected != null && name !in selected) continue
                    val from = parameter.b.a
                    val count = ((parameter.b.b - from) / 4).toInt()
                    val values = FloatArray(count)
                    var index = 0
                    while (index < count) {
                        val block = minOf(scratch.size / 4, count - index)
                        val bytes = if (block * 4 == scratch.size) scratch else ByteArray(block * 4)
                        read(dataAt + from + index.toLong() * 4, bytes)
                        for (i in 0 until block) {
                            val at = i * 4
                            val bits = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) or
                                ((bytes[at + 2].toInt() and 255) shl 16) or ((bytes[at + 3].toInt() and 255) shl 24)
                            values[index + i] = Float.fromBits(bits).also {
                                require(it.isFinite()) { "Non-finite BERT tensor $name value at ${index + i}" }
                            }
                        }
                        index += block
                    }
                    put(name, parameter.a j values)
                }
            }
        }

        private fun read(channel: FileChannel, offset: Long, bytes: ByteArray): ByteArray {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                val position = buffer.position()
                val remaining = buffer.remaining()
                val n = channel.read(buffer, offset + position)
                require(n > 0 && n <= remaining && buffer.position() == position + n) {
                    "Truncated BERT file at ${offset + position}"
                }
            }
            return bytes
        }

        private fun <T> withChannel(channel: FileChannel, action: (FileChannel) -> T): T {
            var failure: Throwable? = null
            try { return action(channel) } catch (caught: Throwable) { failure = caught; throw caught }
            finally {
                try { channel.close() } catch (cleanup: Throwable) {
                    if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
                }
            }
        }
    }
}

private fun objectMap(json: String, name: String): Map<String, Any?> {
    val value = reifyStrict(json)
    require(value is Map<*, *>) { "$name must be a JSON object" }
    require(value.keys.toSet().size == value.size) { "$name contains duplicate JSON keys" }
    @Suppress("UNCHECKED_CAST")
    return value as Map<String, Any?>
}

private fun integer(value: Any?, name: String, maximum: Long): Long {
    val number = (value as? Number)?.toDouble() ?: error("Missing or non-numeric $name")
    require(number.isFinite() && number >= 0 && number <= maximum.toDouble() && number == number.toLong().toDouble()) {
        "Invalid $name: $value"
    }
    return number.toLong()
}
