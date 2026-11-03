package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.lib.*
import borg.trikeshed.narsese.JevaBackboneConfig
import borg.trikeshed.narsese.JevaEncoder
import borg.trikeshed.narsese.JevaWeights
import borg.trikeshed.narsese.jevaFile
import borg.trikeshed.job.ContentId
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyMap

/** Validate admission before reading a potentially lazy token series or allocating its device input. */
fun mlxTokenIds(tokens: Series<Int>, maxPositions: Int, vocabulary: Int): IntArray {
    require(tokens.size in 1..maxPositions) { "Qwen3.5 sequence length outside configured positions" }
    return IntArray(tokens.size) { tokens[it] }.also { ids ->
        require(ids.all { it in 0 until vocabulary }) { "Qwen3.5 token outside vocabulary" }
    }
}

/** This backend calls the affine kernels; do not silently coerce incompatible checkpoint metadata. */
internal fun mlxQuantization(defaults: Map<*, *>, module: Map<*, *>?): Join<Int, Int> {
    val mode = module?.get("mode") ?: defaults["mode"]
    require(mode == null || mode == "affine") { "Only affine quantization is supported" }
    fun integer(key: String): Int {
        val value = (module?.get(key) ?: defaults[key]) as? Number ?: error("Missing quantization $key")
        val number = value.toDouble()
        require(number.isFinite() && number in 1.0..Int.MAX_VALUE.toDouble() && number % 1.0 == 0.0) {
            "Invalid quantization $key"
        }
        return number.toInt()
    }
    val group = integer("group_size")
    val bits = integer("bits")
    require(mlxAffine(group, bits)) { "Unsupported affine quantization" }
    return group j bits
}

internal fun mlxAffine(group: Int, bits: Int): Boolean =
    (group == 32 || group == 64 || group == 128) &&
        (bits == 2 || bits == 3 || bits == 4 || bits == 5 || bits == 6 || bits == 8)

/** Validate logical checkpoint dimensions before invoking MLX's shape-sensitive kernels. */
internal fun mlxBackboneShapes(config: JevaBackboneConfig, plain: (String, IntArray) -> Unit,
                               quantized: (String, Int, Int) -> Unit, check: () -> Unit = {}) {
    require(!config.attentionBias) { "MLX Qwen3.5 attention bias is unsupported" }
    require(config.ropeTheta.toFloat().isFinite() && config.ropeTheta.toFloat() > 0f) {
        "Qwen3.5 RoPE theta is outside MLX float range"
    }
    fun product(a: Int, b: Int): Int {
        val result = a.toLong() * b
        require(result <= Int.MAX_VALUE) { "Qwen3.5 dimensions exceed MLX limits" }
        return result.toInt()
    }
    fun vector(name: String, size: Int) = plain(name, intArrayOf(size))
    val hidden = config.hiddenSize
    quantized("embed_tokens", config.vocabSize, hidden)
    vector("norm.weight", hidden)
    for (layer in 0 until config.layers.size) {
        check()
        val at = "layers.$layer."
        vector(at + "input_layernorm.weight", hidden)
        vector(at + "post_attention_layernorm.weight", hidden)
        quantized(at + "mlp.gate_proj", config.intermediateSize, hidden)
        quantized(at + "mlp.up_proj", config.intermediateSize, hidden)
        quantized(at + "mlp.down_proj", hidden, config.intermediateSize)
        if (config.layers[layer] == "full_attention") {
            val atn = at + "self_attn."
            val query = product(config.attentionHeads, config.headDim)
            val key = product(config.keyValueHeads, config.headDim)
            quantized(atn + "q_proj", product(query, 2), hidden)
            quantized(atn + "k_proj", key, hidden)
            quantized(atn + "v_proj", key, hidden)
            quantized(atn + "o_proj", hidden, query)
            vector(atn + "q_norm.weight", config.headDim)
            vector(atn + "k_norm.weight", config.headDim)
        } else {
            val atn = at + "linear_attn."
            val key = product(config.linearKeyHeads, config.linearKeyDim)
            val value = product(config.linearValueHeads, config.linearValueDim)
            val channels = key.toLong() * 2 + value
            require(channels <= Int.MAX_VALUE) { "Qwen3.5 dimensions exceed MLX limits" }
            quantized(atn + "in_proj_qkv", channels.toInt(), hidden)
            quantized(atn + "in_proj_z", value, hidden)
            quantized(atn + "out_proj", hidden, value)
            plain(atn + "in_proj_a.weight", intArrayOf(config.linearValueHeads, hidden))
            plain(atn + "in_proj_b.weight", intArrayOf(config.linearValueHeads, hidden))
            plain(atn + "conv1d.weight", intArrayOf(channels.toInt(), config.convKernel, 1))
            vector(atn + "A_log", config.linearValueHeads)
            vector(atn + "dt_bias", config.linearValueHeads)
            vector(atn + "norm.weight", config.linearValueDim)
        }
    }
}

/** Complete a layer before releasing the preceding hidden state borrowed by its graph. */
internal fun MlxMatrixSession.settleLayer(mark: Int, previous: MlxTensor, next: MlxTensor) {
    settle(mark, next)
    release(previous)
}

/**
 * The Qwen3.5 text backbone of an MLX checkpoint directory (config.json, model.safetensors), on the GPU stream of [session].
 * Weights are verified and materialized at construction; [encode] returns the final-normalized hidden
 * states [tokens, hidden] as host cells, with every device intermediate freed before it returns.
 */
class MlxBackbone(private val session: MlxMatrixSession, directory: String, check: () -> Unit = {}) : JevaEncoder {
    private val root = "language_model.model."
    private val text = jevaFile("$directory/config.json", JevaWeights.HEADER_BYTES, check).decodeToString(throwOnInvalidSequence = true)
    val config: JevaBackboneConfig = JevaBackboneConfig.parse(text)
    private val quantization: Map<*, *> = requireNotNull(reifyMap(text)["quantization"] as? Map<*, *>) { "Missing quantization" }
    private val tensors: Map<String, MlxTensor>
    val identity: String get() = ContentId.of(jsonOf(_m["config" j ContentId.of(text.encodeToByteArray()).hex,
        "weights" j session.fileIdentity]).encodeToByteArray()).hex

    init {
        check()
        val wanted = LinkedHashSet<String>()
        mlxBackboneShapes(config, { name, _ -> wanted += root + name }, { name, _, _ ->
            for (part in s_["weight", "scales", "biases"].view) wanted += "$root$name.$part"
        }, check)
        val names = wanted.toList()
        tensors = names.zip(session.load("$directory/model.safetensors", *names.toTypedArray(), check = check)).toMap()
        mlxBackboneShapes(config, { name, dims ->
            val tensor = vector(name)
            require(tensor.a.size == dims.size && dims.indices.all { tensor.a[it] == dims[it] } && session.floating(tensor)) {
                "Invalid Qwen3.5 floating tensor: $name"
            }
        }, { name, rows, columns ->
            val matrix = matrix(name)
            val width = session.quantizedWidth(matrix.weight, matrix.scales, matrix.biases, matrix.groupSize, matrix.bits)
            require(matrix.weight.a[0] == rows && width == columns) { "Invalid Qwen3.5 quantized tensor: $name" }
        }, check)
        session.materialize(tensors.values, check)
    }

    private fun vector(name: String): MlxTensor = requireNotNull(tensors[root + name]) { "Missing tensor $name" }

    private fun matrix(name: String): MlxQuantized {
        val override = quantization[root + name]
        require(override == null || override is Map<*, *>) { "Invalid quantization override: $name" }
        val (group, bits) = mlxQuantization(quantization, override as? Map<*, *>)
        return MlxQuantized(vector("$name.weight"), vector("$name.scales"), vector("$name.biases"), group, bits)
    }

    override fun encode(tokens: Series<Int>, check: () -> Unit, progress: (Int, Int) -> Unit): MetaSeries<Tensor, Float> = session.scoped {
        val ids = mlxTokenIds(tokens, config.maxPositions, config.vocabSize)
        check()
        val table = matrix("embed_tokens")
        var hidden = session.embed(ids, table.weight, table.scales, table.biases, table.groupSize, table.bits)
        val epsilon = config.rmsEpsilon
        for (layer in 0 until config.layers.size) {
            check(); progress(layer, 0)
            val previous = hidden
            val mark = session.mark()
            val at = "layers.$layer."
            val input = session.rmsNorm(hidden, vector(at + "input_layernorm.weight"), epsilon)
            val mixed = if (config.layers[layer] == "full_attention") {
                val m = at + "self_attn."
                session.fullAttention(input, matrix(m + "q_proj"), matrix(m + "k_proj"), matrix(m + "v_proj"), matrix(m + "o_proj"),
                    vector(m + "q_norm.weight"), vector(m + "k_norm.weight"), config.attentionHeads, config.keyValueHeads,
                    config.headDim, config.rotaryDim, config.ropeTheta.toFloat(), epsilon)
            } else {
                val m = at + "linear_attn."
                session.linearAttention(input, matrix(m + "in_proj_qkv"), matrix(m + "in_proj_z"), vector(m + "in_proj_b.weight"),
                    vector(m + "in_proj_a.weight"), matrix(m + "out_proj"), vector(m + "conv1d.weight"), vector(m + "A_log"),
                    vector(m + "dt_bias"), vector(m + "norm.weight"), config.linearKeyHeads, config.linearValueHeads,
                    config.linearKeyDim, config.linearValueDim, config.convKernel, epsilon, check)
            }
            hidden = session.add(hidden, mixed)
            val gated = session.rmsNorm(hidden, vector(at + "post_attention_layernorm.weight"), epsilon)
            val gate = session.linear(gated, matrix(at + "mlp.gate_proj"))
            val up = session.linear(gated, matrix(at + "mlp.up_proj"))
            hidden = session.add(hidden, session.linear(session.times(session.silu(gate), up), matrix(at + "mlp.down_proj")))
            session.settleLayer(mark, previous, hidden)
        }
        check(); progress(config.layers.size, 0)
        val result = session.rmsNorm(hidden, vector("norm.weight"), epsilon)
        tensorOf(result.a, FloatArray(result.size).also { result.read(0, it) })
    }
}
