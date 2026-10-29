/*
 * Adapted from Transformers 5.17.0, models/qwen3_5/modeling_qwen3_5.py.
 * Copyright 2025 The Qwen Team and The HuggingFace Inc. team. All rights reserved.
 * Licensed under the Apache License, Version 2.0: https://www.apache.org/licenses/LICENSE-2.0
 * Distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations.
 * Modifications: Kotlin commonMain Float32 inference, Join tensor oracles, and sequential per-layer state.
 */
package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import kotlin.math.*

/** Qwen3.5 text dimensions; the defaults describe the saved Jeva-4B backbone. */
data class JevaBackboneConfig(
    val vocabSize: Int = 248320,
    val hiddenSize: Int = 2560,
    val intermediateSize: Int = 9216,
    val layers: Series<String> = 32 j { if ((it + 1) % 4 == 0) "full_attention" else "linear_attention" },
    val attentionHeads: Int = 16,
    val keyValueHeads: Int = 4,
    val headDim: Int = 256,
    val linearKeyHeads: Int = 16,
    val linearValueHeads: Int = 32,
    val linearKeyDim: Int = 128,
    val linearValueDim: Int = 128,
    val convKernel: Int = 4,
    val rmsEpsilon: Float = 1e-6f,
    val ropeTheta: Double = 10000000.0,
    val rotaryFactor: Double = 0.25,
    val maxPositions: Int = 262144,
    val attentionBias: Boolean = false,
) {
    val rotaryDim: Int get() = (headDim * rotaryFactor).toInt()

    init {
        require(vocabSize > 0 && hiddenSize > 0 && intermediateSize > 0 && layers.size > 0)
        require(attentionHeads > 0 && keyValueHeads > 0 && headDim > 0)
        require(attentionHeads % keyValueHeads == 0)
        require(linearKeyHeads > 0 && linearValueHeads > 0 && linearKeyDim > 0 && linearValueDim > 0)
        require(linearValueHeads % linearKeyHeads == 0 && convKernel > 0)
        require(rmsEpsilon.isFinite() && rmsEpsilon > 0 && ropeTheta.isFinite() && ropeTheta > 0)
        require(rotaryFactor.isFinite() && rotaryFactor > 0 && rotaryFactor <= 1)
        require(rotaryDim > 0 && rotaryDim % 2 == 0 && maxPositions > 0)
        require(layers.view.all { it == "linear_attention" || it == "full_attention" })
    }

    companion object {
        /** Accept the saved multimodal config or its text_config; image/video inputs are not text tokens. */
        fun parse(json: String): JevaBackboneConfig {
            val root = reifyMap(json)
            val fields = root["text_config"] as? Map<*, *> ?: root
            fun integer(name: String): Int {
                val value = (fields[name] as? Number)?.toDouble()
                require(value != null && value.isFinite() && value > 0 && value <= Int.MAX_VALUE && value % 1.0 == 0.0) {
                    "Invalid Qwen3.5 $name"
                }
                return value.toInt()
            }
            fun number(name: String): Double = requireNotNull((fields[name] as? Number)?.toDouble()) {
                "Missing Qwen3.5 $name"
            }
            require(fields["model_type"] == "qwen3_5_text") { "Expected Qwen3.5 text backbone" }
            require(fields["hidden_act"] == "silu") { "Qwen3.5 reference requires SwiGLU" }
            require(fields["attn_output_gate"] != false) { "Qwen3.5 reference requires the query output gate" }
            val rope = requireNotNull(fields["rope_parameters"] as? Map<*, *>) { "Missing Qwen3.5 RoPE" }
            require((rope["rope_type"] ?: rope["type"]) == "default") { "Unsupported Qwen3.5 RoPE type" }
            val count = integer("num_hidden_layers")
            val layerTypes = fields["layer_types"] as? List<*>
            require(layerTypes != null && layerTypes.size == count && layerTypes.all { it is String }) {
                "Missing Qwen3.5 layer_types"
            }
            return JevaBackboneConfig(integer("vocab_size"), integer("hidden_size"), integer("intermediate_size"),
                count j { layerTypes[it] as String }, integer("num_attention_heads"), integer("num_key_value_heads"),
                integer("head_dim"), integer("linear_num_key_heads"), integer("linear_num_value_heads"),
                integer("linear_key_head_dim"), integer("linear_value_head_dim"), integer("linear_conv_kernel_dim"),
                number("rms_norm_eps").toFloat(), requireNotNull((rope["rope_theta"] as? Number)?.toDouble()),
                (rope["partial_rotary_factor"] as? Number)?.toDouble() ?: 1.0, integer("max_position_embeddings"),
                fields["attention_bias"] as? Boolean ?: false)
        }
    }
}

/**
 * Inference-only Float32 Qwen3.5 text reference, with exact saved weight names.
 * Input is one unpadded causal sequence; output is final-normalized [tokens, hidden].
 * Only one layer's KV/convolution/recurrent state is retained. There is no quadratic attention matrix.
 * Float32 arithmetic intentionally does not claim BF16 operator-rounding parity.
 */
class JevaBackbone(
    val config: JevaBackboneConfig,
    val weights: (String) -> MetaSeries<Tensor, Float>,
    val prefix: String = "language_model.",
    val matrix: JevaMatrix = JevaMatrix.reference,
) : JevaEncoder {
    companion object { const val ATTENTION_ROWS = 64 }

    fun weight(name: String, vararg dims: Int): MetaSeries<Tensor, Float> {
        val value = weights(prefix + name)
        require(value.a.size == dims.size && dims.indices.all { value.a[it] == dims[it] }) {
            "Qwen3.5 weight shape mismatch: $prefix$name"
        }
        return value
    }

    /** Only small vectors/convolution kernels are copied; projection matrices stay borrowed. */
    fun read(weight: MetaSeries<Tensor, Float>, offset: Int, dst: FloatArray, start: Int, length: Int, check: () -> Unit) {
        val bulk = weight as? JevaWeight
        if (bulk != null) { bulk.read(offset, dst, start, length, check); return }
        check()
        if (length == 0) return
        val first = tensorIndex(weight.a, offset)
        val coordinates = IntArray(weight.a.size) { first[it] }
        val index = weight.a.size j { axis: Int -> coordinates[axis] }
        for (i in 0 until length) {
            if (i and 4095 == 0) check()
            dst[start + i] = weight.b(index)
            for (axis in coordinates.lastIndex downTo 0) {
                coordinates[axis]++
                if (coordinates[axis] < weight.a[axis]) break
                coordinates[axis] = 0
            }
        }
    }

    fun vector(weight: MetaSeries<Tensor, Float>, check: () -> Unit): FloatArray =
        FloatArray(tensorSize(weight.a)).also { read(weight, 0, it, 0, it.size, check) }

    override fun encode(tokens: Series<Int>, check: () -> Unit, progress: (Int, Int) -> Unit): MetaSeries<Tensor, Float> {
        check()
        require(tokens.size in 1..config.maxPositions) { "Qwen3.5 sequence length outside configured positions" }
        val width = config.hiddenSize
        val hidden = FloatArray(tensorSize(shapeOf(tokens.size, width)))
        val embedding = weight("embed_tokens.weight", config.vocabSize, width)
        for (position in 0 until tokens.size) {
            progress(-1, position); check()
            val token = tokens[position]
            require(token in 0 until config.vocabSize) { "Qwen3.5 token outside vocabulary" }
            read(embedding, token * width, hidden, position * width, width, check)
        }
        for (layer in 0 until config.layers.size) {
            progress(layer, 0); check()
            val name = "layers.$layer."
            val norm = vector(weight(name + "input_layernorm.weight", width), check)
            attention(hidden, norm, name, tokens.size, layer, check, progress)
            val post = vector(weight(name + "post_attention_layernorm.weight", width), check)
            feedForward(hidden, post, name, tokens.size, check)
        }
        progress(config.layers.size, 0); check()
        val normalized = rms(hidden, width, vector(weight("norm.weight", width), check), check = check)
        return tensorOf(shapeOf(tokens.size, width), normalized)
    }

    /** A separate frame releases attention projections before the MLP's expanded activations are allocated. */
    fun attention(hidden: FloatArray, norm: FloatArray, name: String, rows: Int, layer: Int,
                  check: () -> Unit, progress: (Int, Int) -> Unit) {
        val input = rms(hidden, config.hiddenSize, norm, check = check)
        val output = when (config.layers[layer]) {
            "full_attention" -> fullAttention(name + "self_attn.", input, rows, check) { progress(layer, it) }
            "linear_attention" -> linearAttention(name + "linear_attn.", input, rows, check) { progress(layer, it) }
            else -> error("Unsupported Qwen3.5 layer")
        }
        for (i in hidden.indices) {
            if (i and 4095 == 0) check()
            hidden[i] += output[i]
        }
    }

    fun feedForward(hidden: FloatArray, norm: FloatArray, name: String, rows: Int, check: () -> Unit) {
        val input = rms(hidden, config.hiddenSize, norm, check = check)
        val gated = matrix.linear(weight(name + "mlp.gate_proj.weight", config.intermediateSize, config.hiddenSize), input, rows, check = check)
        // The up projection's storage dies at this call boundary before down projection output is allocated.
        swiglu(gated, matrix.linear(weight(name + "mlp.up_proj.weight", config.intermediateSize, config.hiddenSize), input, rows, check = check), check)
        val output = matrix.linear(weight(name + "mlp.down_proj.weight", config.hiddenSize, config.intermediateSize), gated, rows, check = check)
        for (i in hidden.indices) {
            if (i and 4095 == 0) check()
            hidden[i] += output[i]
        }
    }

    fun swiglu(gate: FloatArray, up: FloatArray, check: () -> Unit) {
        require(gate.size == up.size)
        for (i in gate.indices) {
            if (i and 4095 == 0) check()
            gate[i] = silu(gate[i]) * up[i]
        }
    }

    /** Compatibility for one row; encode uses sequence-wide GEMM for every projection. */
    fun linear(weight: MetaSeries<Tensor, Float>, input: FloatArray,
               bias: MetaSeries<Tensor, Float>? = null, check: () -> Unit = {}): FloatArray =
        matrix.linear(weight, input, 1, bias, check)

    fun rms(input: FloatArray, weight: MetaSeries<Tensor, Float>, zeroCentered: Boolean = true,
            check: () -> Unit = {}): FloatArray = rms(input, input.size, vector(weight, check), zeroCentered, check)

    /** Qwen3.5's ordinary norms have zero-centered weights; the delta output norm does not. */
    fun rms(input: FloatArray, width: Int, weight: FloatArray, zeroCentered: Boolean = true,
            check: () -> Unit = {}): FloatArray {
        require(width > 0 && input.size % width == 0 && weight.size == width)
        return FloatArray(input.size).also { output ->
            for (offset in input.indices step width) {
                check()
                var sum = 0f
                for (i in 0 until width) sum += input[offset + i] * input[offset + i]
                val scale = 1f / sqrt(sum / width + config.rmsEpsilon)
                for (i in 0 until width) output[offset + i] = input[offset + i] * scale *
                    ((if (zeroCentered) 1f else 0f) + weight[i])
            }
        }
    }

    fun sigmoid(value: Float): Float = if (value >= 0f) 1f / (1f + exp(-value))
        else exp(value).let { it / (1f + it) }
    fun silu(value: Float): Float = value * sigmoid(value)
    fun softplus(value: Float): Float = if (value > 20f) value else ln1p(exp(value))

    /** Text positions occupy all three mRoPE axes equally. */
    fun rope(head: FloatArray, position: Int, offset: Int = 0) {
        val half = config.rotaryDim / 2
        for (i in 0 until half) {
            val inverse = (1.0 / config.ropeTheta.pow(2.0 * i / config.rotaryDim)).toFloat()
            val angle = position.toFloat() * inverse
            val c = cos(angle); val s = sin(angle)
            val x = head[offset + i]; val y = head[offset + i + half]
            head[offset + i] = x * c - y * s
            head[offset + i + half] = y * c + x * s
        }
    }

    fun normalizeHead(values: FloatArray, offset: Int, norm: FloatArray, position: Int) {
        var sum = 0f
        for (i in norm.indices) sum += values[offset + i] * values[offset + i]
        val scale = 1f / sqrt(sum / norm.size + config.rmsEpsilon)
        for (i in norm.indices) values[offset + i] = values[offset + i] * scale * (1f + norm[i])
        rope(values, position, offset)
    }

    /** Query tiles retain all keys. The absolute causal mask is applied before softmax. */
    fun fullAttention(name: String, input: FloatArray, length: Int, check: () -> Unit,
                      progress: (Int) -> Unit): FloatArray {
        val c = config; val dim = c.headDim
        val qWidth = c.attentionHeads * dim; val kvWidth = c.keyValueHeads * dim
        fun bias(projection: String, width: Int) = if (c.attentionBias) weight(name + projection + ".bias", width) else null
        val queries = matrix.linear(weight(name + "q_proj.weight", qWidth * 2, c.hiddenSize), input, length, bias("q_proj", qWidth * 2), check)
        val keys = matrix.linear(weight(name + "k_proj.weight", kvWidth, c.hiddenSize), input, length, bias("k_proj", kvWidth), check)
        val values = matrix.linear(weight(name + "v_proj.weight", kvWidth, c.hiddenSize), input, length, bias("v_proj", kvWidth), check)
        val qNorm = vector(weight(name + "q_norm.weight", dim), check)
        val kNorm = vector(weight(name + "k_norm.weight", dim), check)
        for (position in 0 until length) {
            progress(position); check()
            for (head in 0 until c.attentionHeads) normalizeHead(queries, position * qWidth * 2 + head * dim * 2, qNorm, position)
            for (head in 0 until c.keyValueHeads) normalizeHead(keys, position * kvWidth + head * dim, kNorm, position)
        }
        val output = FloatArray(tensorSize(shapeOf(length, qWidth)))
        val scaling = 1f / sqrt(dim.toFloat())
        // Each contiguous KV head is reused by its group of query heads and every query tile.
        for (kvHead in 0 until c.keyValueHeads) {
            val headKeys = FloatArray(tensorSize(shapeOf(length, dim)))
            val headValues = FloatArray(headKeys.size)
            for (position in 0 until length) {
                check()
                keys.copyInto(headKeys, position * dim, position * kvWidth + kvHead * dim, position * kvWidth + (kvHead + 1) * dim)
                values.copyInto(headValues, position * dim, position * kvWidth + kvHead * dim, position * kvWidth + (kvHead + 1) * dim)
            }
            val group = c.attentionHeads / c.keyValueHeads
            for (head in kvHead * group until (kvHead + 1) * group) {
                for (start in 0 until length step ATTENTION_ROWS) {
                    progress(start); check()
                    val rows = minOf(ATTENTION_ROWS, length - start)
                    val query = FloatArray(tensorSize(shapeOf(rows, dim)))
                    for (row in 0 until rows) {
                        val at = (start + row) * qWidth * 2 + head * dim * 2
                        queries.copyInto(query, row * dim, at, at + dim)
                    }
                    val probabilities = matrix.gemm(rows, length, dim, query, headKeys, transposeB = true, check = check)
                    for (row in 0 until rows) {
                        check()
                        val end = start + row
                        val offset = row * length
                        var maximum = Float.NEGATIVE_INFINITY
                        for (past in 0..end) {
                            probabilities[offset + past] *= scaling
                            maximum = max(maximum, probabilities[offset + past])
                        }
                        var denominator = 0f
                        for (past in 0..end) {
                            val value = exp(probabilities[offset + past] - maximum)
                            probabilities[offset + past] = value; denominator += value
                        }
                        for (past in 0 until length) probabilities[offset + past] =
                            if (past <= end) probabilities[offset + past] / denominator else 0f
                    }
                    val attended = matrix.gemm(rows, dim, length, probabilities, headValues, check = check)
                    for (row in 0 until rows) for (i in 0 until dim) {
                        val position = start + row
                        output[position * qWidth + head * dim + i] = attended[row * dim + i] *
                            sigmoid(queries[position * qWidth * 2 + head * dim * 2 + dim + i])
                    }
                }
            }
        }
        return matrix.linear(weight(name + "o_proj.weight", c.hiddenSize, qWidth), output, length, bias("o_proj", c.hiddenSize), check)
    }

    /** Batched projections surround the unchanged causal convolution and zero-initialized delta recurrence. */
    fun linearAttention(name: String, input: FloatArray, length: Int, check: () -> Unit,
                        progress: (Int) -> Unit): FloatArray {
        val c = config
        val keyWidth = c.linearKeyHeads * c.linearKeyDim; val valueWidth = c.linearValueHeads * c.linearValueDim
        val convWidth = 2 * keyWidth + valueWidth
        val mixed = matrix.linear(weight(name + "in_proj_qkv.weight", convWidth, c.hiddenSize), input, length, check = check)
        val gates = matrix.linear(weight(name + "in_proj_z.weight", valueWidth, c.hiddenSize), input, length, check = check)
        val beta = matrix.linear(weight(name + "in_proj_b.weight", c.linearValueHeads, c.hiddenSize), input, length, check = check)
        val alpha = matrix.linear(weight(name + "in_proj_a.weight", c.linearValueHeads, c.hiddenSize), input, length, check = check)
        val conv = vector(weight(name + "conv1d.weight", convWidth, 1, c.convKernel), check)
        val dt = vector(weight(name + "dt_bias", c.linearValueHeads), check)
        val decayLog = vector(weight(name + "A_log", c.linearValueHeads), check)
        val norm = vector(weight(name + "norm.weight", c.linearValueDim), check)
        val history = FloatArray(tensorSize(shapeOf(c.convKernel, convWidth)))
        val state = FloatArray(tensorSize(shapeOf(c.linearValueHeads, c.linearKeyDim, c.linearValueDim)))
        val output = FloatArray(tensorSize(shapeOf(length, valueWidth)))
        val scaling = 1f / sqrt(c.linearKeyDim.toFloat())
        for (position in 0 until length) {
            progress(position); check()
            val mixedOffset = position * convWidth
            mixed.copyInto(history, (position % c.convKernel) * convWidth, mixedOffset, mixedOffset + convWidth)
            for (i in 0 until convWidth) {
                if (i and 255 == 0) check()
                var sum = 0f
                for (tap in 0 until c.convKernel) {
                    val source = position - c.convKernel + 1 + tap
                    if (source >= 0) sum += history[(source % c.convKernel) * convWidth + i] * conv[i * c.convKernel + tap]
                }
                mixed[mixedOffset + i] = silu(sum)
            }
            for (head in 0 until c.linearValueHeads) {
                check()
                val keyHead = head / (c.linearValueHeads / c.linearKeyHeads)
                val query = mixed.copyOfRange(mixedOffset + keyHead * c.linearKeyDim, mixedOffset + (keyHead + 1) * c.linearKeyDim)
                val key = mixed.copyOfRange(mixedOffset + keyWidth + keyHead * c.linearKeyDim, mixedOffset + keyWidth + (keyHead + 1) * c.linearKeyDim)
                var qSquare = 0f; var kSquare = 0f
                for (i in key.indices) { qSquare += query[i] * query[i]; kSquare += key[i] * key[i] }
                val qScale = 1f / sqrt(qSquare + 1e-6f); val kScale = 1f / sqrt(kSquare + 1e-6f)
                for (i in key.indices) { query[i] = query[i] * qScale * scaling; key[i] *= kScale }
                val decay = exp(-exp(decayLog[head]) * softplus(alpha[position * c.linearValueHeads + head] + dt[head]))
                val learning = sigmoid(beta[position * c.linearValueHeads + head])
                val offset = head * c.linearKeyDim * c.linearValueDim
                for (i in 0 until c.linearKeyDim * c.linearValueDim) {
                    if (i and 4095 == 0) check()
                    state[offset + i] *= decay
                }
                val memory = FloatArray(c.linearValueDim)
                // Contiguous V loops preserve the original increasing-K reduction order for every output.
                for (keyDim in key.indices) {
                    if (keyDim and 31 == 0) check()
                    val at = offset + keyDim * c.linearValueDim
                    for (value in memory.indices) memory[value] += state[at + value] * key[keyDim]
                }
                for (value in memory.indices) memory[value] =
                    (mixed[mixedOffset + 2 * keyWidth + head * c.linearValueDim + value] - memory[value]) * learning
                val result = FloatArray(c.linearValueDim)
                for (keyDim in key.indices) {
                    if (keyDim and 31 == 0) check()
                    val at = offset + keyDim * c.linearValueDim
                    for (value in result.indices) {
                        state[at + value] += key[keyDim] * memory[value]
                        result[value] += state[at + value] * query[keyDim]
                    }
                }
                val normalized = rms(result, c.linearValueDim, norm, zeroCentered = false, check = check)
                for (i in normalized.indices) output[position * valueWidth + head * c.linearValueDim + i] =
                    normalized[i] * silu(gates[position * valueWidth + head * c.linearValueDim + i])
            }
        }
        return matrix.linear(weight(name + "out_proj.weight", c.hiddenSize, valueWidth), output, length, check = check)
    }
}
