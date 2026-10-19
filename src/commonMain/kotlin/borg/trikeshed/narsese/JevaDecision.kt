/*
 * Kotlin commonMain adaptation of Jeva e2104e0720100f979f2cdd456a1cbbee96da0abd.
 * Copyright 2026 Qi Li and Xinchao Wang. Upstream licensed under Apache-2.0.
 * Modified for Join/Series tensor oracles and bounded mapped inference.
 * See NOTICE.md and src/commonMain/resources/licenses/apache-2.0.txt.
 */
package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import kotlin.math.*

/** The Float32 matrix_stack head saved in Jeva's head.pt, independently of the backbone dtype. */
data class JevaDecisionConfig(
    val projectionDim: Int = 768,
    val depth: Int = 1,
    val numHeads: Int = 8,
    val ffnRatio: Double = 4.0,
    val normalizeState: Boolean = false,
    val finalNorm: Boolean = false,
    val minTemperature: Float = 1e-4f,
    val eps: Float = 1e-6f,
) {
    val headDim: Int get() = projectionDim / numHeads
    val ffnDim: Int get() = if (ffnRatio > 0) round(projectionDim * ffnRatio).toInt() else 0

    init {
        require(projectionDim > 0 && depth > 0 && numHeads > 0 && projectionDim % numHeads == 0)
        require(ffnRatio.isFinite() && (ffnRatio <= 0 || projectionDim * ffnRatio in 1.0..Int.MAX_VALUE.toDouble()))
        require(minTemperature.isFinite() && minTemperature > 0 && eps.isFinite() && eps > 0)
    }

    companion object {
        fun from(model: Map<*, *>): JevaDecisionConfig {
            require(model["decision_arch"] == null || model["decision_arch"] == "matrix_stack") { "Jeva requires matrix_stack" }
            fun number(name: String, default: Double): Double = model[name]?.let {
                requireNotNull((it as? Number)?.toDouble()?.takeIf(Double::isFinite)) { "Invalid $name" }
            } ?: default
            fun integer(name: String, default: Int): Int = number(name, default.toDouble()).let {
                require(it % 1.0 == 0.0 && it in 1.0..Int.MAX_VALUE.toDouble()) { "Invalid $name" }; it.toInt()
            }
            fun flag(name: String, default: Boolean): Boolean = model[name]?.let {
                requireNotNull(it as? Boolean) { "Invalid $name" }
            } ?: default
            return JevaDecisionConfig(integer("projection_dim", 768), integer("decision_depth", 1),
                integer("decision_num_heads", 8), number("decision_ffn_ratio", 4.0),
                flag("normalize_state", false), flag("decision_final_norm", false),
                number("min_temperature", 1e-4).toFloat())
        }
    }
}

/** Row-major kernels; arrays own their storage and tensor oracles expose only indexed values. */
object JevaMath {
    fun array(tensor: MetaSeries<Tensor, Float>): FloatArray {
        (tensor as? JevaWeight)?.let { reader ->
            return FloatArray(reader.size).also { reader.read(0, it) }
        }
        val shape = tensor.a
        val coordinates = IntArray(shape.size)
        val index = shape.size j { axis: Int -> coordinates[axis] }
        return FloatArray(tensorSize(shape)) {
            val value = tensor.b(index)
            for (axis in coordinates.lastIndex downTo 0) {
                coordinates[axis]++
                if (coordinates[axis] < shape[axis]) break
                coordinates[axis] = 0
            }
            value
        }
    }

    fun dimensions(tensor: MetaSeries<Tensor, Float>, vararg expected: Int) {
        require(tensor.a.size == expected.size && expected.indices.all { tensor.a[it] == expected[it] }) {
            "Expected tensor dimensions ${expected.toList()}"
        }
    }

    fun finite(values: FloatArray): FloatArray = values.also { require(it.all(Float::isFinite)) { "Non-finite Jeva tensor" } }

    fun normalize(values: FloatArray, width: Int): FloatArray {
        require(width > 0 && values.size % width == 0)
        return values.copyOf().also { result ->
            for (offset in values.indices step width) {
                var square = 0f
                for (i in 0 until width) square += values[offset + i] * values[offset + i]
                val denominator = max(sqrt(square.toDouble()).toFloat(), 1e-12f)
                for (i in 0 until width) result[offset + i] /= denominator
            }
        }
    }

    fun rms(values: FloatArray, weight: FloatArray, eps: Float): FloatArray {
        require(weight.isNotEmpty() && values.size % weight.size == 0)
        return FloatArray(values.size).also { result ->
            for (offset in values.indices step weight.size) {
                var square = 0f
                for (i in weight.indices) square += values[offset + i] * values[offset + i]
                val scale = (1.0 / sqrt((square / weight.size + eps).toDouble())).toFloat()
                for (i in weight.indices) result[offset + i] = values[offset + i] * scale * weight[i]
            }
        }
    }

    fun sigmoid(value: Float): Float = if (value >= 0) (1.0 / (1.0 + exp(-value.toDouble()))).toFloat()
        else exp(value.toDouble()).let { (it / (1.0 + it)).toFloat() }

    fun positive(raw: Float, minimum: Float): Float = minimum +
        (if (raw > 20f) raw else ln1p(exp(raw.toDouble())).toFloat())
}

/** The weight oracle remains borrowed, so a mapped checkpoint is never expanded into a second weight array. */
class JevaLinear(val weight: MetaSeries<Tensor, Float>, val matrix: JevaMatrix = JevaMatrix.reference) {
    val output: Int
    val input: Int
    init {
        require(weight.a.size == 2) { "Linear weight must be [output,input]" }
        output = weight.a[0]; input = weight.a[1]
        require(output > 0 && input > 0)
    }

    operator fun invoke(x: FloatArray, check: () -> Unit = {}): FloatArray {
        check()
        require(x.size % input == 0)
        val rows = x.size / input
        return JevaMath.finite(matrix.linear(weight, x, rows, check = check))
    }
}

data class JevaOptionMemory(
    val optionRead: MetaSeries<Tensor, Float>,
    val layerKeys: Series<MetaSeries<Tensor, Float>>,
    val layerValues: Series<MetaSeries<Tensor, Float>>,
) {
    val nbytes: Long get() = 4L * (tensorSize(optionRead.a).toLong() +
        layerKeys.view.sumOf { tensorSize(it.a).toLong() } + layerValues.view.sumOf { tensorSize(it.a).toLong() })
}

data class JevaOptionCache(
    val optionRead: MetaSeries<Tensor, Float>,
    val optionMask: MetaSeries<Tensor, Float>,
    val layerKeys: Series<MetaSeries<Tensor, Float>>,
    val layerValues: Series<MetaSeries<Tensor, Float>>,
) {
    val batchSize: Int get() = optionMask.a[0]
    val numOptions: Int get() = optionMask.a[1]
    /** Float32 payload accounting includes this port's Float mask, rather than upstream's bool mask. */
    val nbytes: Long get() = 4L * (tensorSize(optionRead.a).toLong() + tensorSize(optionMask.a) +
        layerKeys.view.sumOf { tensorSize(it.a).toLong() } + layerValues.view.sumOf { tensorSize(it.a).toLong() })
}

/** Entry and payload-byte bounded LRU. Keys must have structural equality (token Series closures do not). */
class JevaCache<K, V : Any>(val maxEntries: Int, val maxBytes: Long, val size: (V) -> Long) {
    private val entries = LinkedHashMap<K, V>()
    val count: Int get() = entries.size
    var nbytes = 0L
        private set
    var hits = 0L
        private set
    var misses = 0L
        private set
    init { require(maxEntries >= 0 && maxBytes >= 0) }
    operator fun get(key: K): V? {
        val value = entries.remove(key)
        if (value == null) misses++ else { entries[key] = value; hits++ }
        return value
    }
    fun put(key: K, value: V) {
        val bytes = size(value).also { require(it >= 0) }
        entries.remove(key)?.let { nbytes -= size(it) }
        if (maxEntries == 0 || maxBytes == 0L || bytes > maxBytes) return
        while (entries.size >= maxEntries || nbytes > maxBytes - bytes) {
            val first = entries.keys.first()
            nbytes -= size(entries.remove(first)!!)
        }
        entries[key] = value; nbytes += bytes
    }
    fun clear() { entries.clear(); nbytes = 0; hits = 0; misses = 0 }
}

data class JevaDecisionOutput(
    val q: MetaSeries<Tensor, Float>,
    val coverage: MetaSeries<Tensor, Float>,
    val pNull: MetaSeries<Tensor, Float>,
    val pCandidates: MetaSeries<Tensor, Float>,
    val scores: MetaSeries<Tensor, Float>,
    val z: MetaSeries<Tensor, Float>,
    val zRead: MetaSeries<Tensor, Float>,
    val decisionLogits: MetaSeries<Tensor, Float>,
    val logQ: MetaSeries<Tensor, Float>,
    val coverageLogit: MetaSeries<Tensor, Float>,
    val layerAttentions: Series<MetaSeries<Tensor, Float>>,
    val layerAlphas: Series<Float>,
)

class JevaDecision(val config: JevaDecisionConfig, val weights: (String) -> MetaSeries<Tensor, Float>,
                   val matrix: JevaMatrix = JevaMatrix.reference) {
    val dim = config.projectionDim
    val depthScale = (1.0 / sqrt(config.depth.toDouble())).toFloat()
    fun scalar(name: String): Float = weightsScalar(weights, name)
    val tauDec = JevaMath.positive(scalar("core.tau_dec.raw"), config.minTemperature)
    val tauCov = JevaMath.positive(scalar("core.tau_cov.raw"), config.minTemperature)
    val coverageBias = scalar("core.coverage_bias")
    val finalNorm = if (config.finalNorm) vector(weights, "core.final_norm.weight", dim) else null

    inner class Block(index: Int) {
        val prefix = "core.blocks.$index."
        fun linear(name: String, output: Int, input: Int): JevaLinear = weights(prefix + name + ".weight").let {
            JevaMath.dimensions(it, output, input); JevaLinear(it, matrix)
        }
        val q = linear("q_proj", dim, dim)
        val k = linear("k_proj", dim, dim)
        val v = linear("v_proj", dim, dim)
        val o = linear("o_proj", dim, dim)
        val tau = JevaMath.positive(scalar(prefix + "tau_set.raw"), config.minTemperature)
        val alpha = tau * JevaMath.sigmoid(scalar(prefix + "gamma")) * depthScale
        val norm = if (config.ffnRatio > 0) vector(weights, prefix + "ffn_norm.weight", dim) else null
        val gate = if (norm != null) linear("ffn.gate_proj", config.ffnDim, dim) else null
        val up = if (norm != null) linear("ffn.up_proj", config.ffnDim, dim) else null
        val down = if (norm != null) linear("ffn.down_proj", dim, config.ffnDim) else null

        fun forward(state: FloatArray, keys: FloatArray, values: FloatArray, mask: FloatArray,
                    batch: Int, options: Int, check: () -> Unit): Join<FloatArray, FloatArray> {
            val queries = JevaMath.normalize(q(state, check), config.headDim)
            val attention = FloatArray(batch * config.numHeads * options)
            val message = FloatArray(batch * dim)
            for (b in 0 until batch) for (h in 0 until config.numHeads) {
                check()
                val base = (b * config.numHeads + h) * options
                var maximum = Float.NEGATIVE_INFINITY
                for (option in 0 until options) {
                    var dot = 0f
                    for (d in 0 until config.headDim) dot += queries[b * dim + h * config.headDim + d] *
                        keys[(b * options + option) * dim + h * config.headDim + d]
                    val value = if (mask[b * options + option] == 0f) Float.NEGATIVE_INFINITY else dot / tau
                    attention[base + option] = value; maximum = max(maximum, value)
                }
                var total = 0f
                for (option in 0 until options) {
                    val value = exp((attention[base + option] - maximum).toDouble()).toFloat()
                    attention[base + option] = value; total += value
                }
                for (option in 0 until options) attention[base + option] /= total
                for (d in 0 until config.headDim) {
                    var value = 0f
                    for (option in 0 until options) value += attention[base + option] *
                        values[(b * options + option) * dim + h * config.headDim + d]
                    message[b * dim + h * config.headDim + d] = value
                }
            }
            val projected = o(message, check)
            var result = FloatArray(state.size) { state[it] + alpha * projected[it] }
            if (norm != null) {
                val normalized = JevaMath.rms(result, norm, config.eps)
                val gates = gate!!(normalized, check)
                val valuesUp = up!!(normalized, check)
                for (i in gates.indices) gates[i] = gates[i] * JevaMath.sigmoid(gates[i]) * valuesUp[i]
                val delta = down!!(gates, check)
                result = FloatArray(result.size) { result[it] + depthScale * delta[it] }
            }
            return result j attention
        }
    }

    val blocks: Series<Block> = Array(config.depth) { Block(it) }.toSeries()

    fun prepare(options: MetaSeries<Tensor, Float>, mask: MetaSeries<Tensor, Float>, check: () -> Unit = {}): JevaOptionCache {
        check()
        require(options.a.size == 3 && options.a[2] == dim)
        val batch = options.a[0]; val count = options.a[1]
        require(batch > 0 && count > 0)
        JevaMath.dimensions(mask, batch, count)
        val accepted = JevaMath.array(mask)
        require(accepted.all { it == 0f || it == 1f }) { "Option mask must contain only zero or one" }
        require((0 until batch).all { b -> (0 until count).any { accepted[b * count + it] == 1f } }) { "Every example needs a valid option" }
        val read = JevaMath.normalize(JevaMath.finite(JevaMath.array(options)), dim)
        val shape = shapeOf(batch, count, config.numHeads, config.headDim)
        val keys = Array(config.depth) { tensorOf(shape, JevaMath.normalize(blocks[it].k(read, check), config.headDim)) }.toSeries()
        val values = Array(config.depth) { tensorOf(shape, blocks[it].v(read, check)) }.toSeries()
        return JevaOptionCache(tensorOf(shapeOf(batch, count, dim), read), tensorOf(shapeOf(batch, count), accepted), keys, values)
    }

    /** Each result owns D*(1+2*depth) floats; retaining one option cannot retain the whole encoded batch. */
    fun compile(options: MetaSeries<Tensor, Float>, check: () -> Unit = {}): Series<JevaOptionMemory> {
        check()
        require(options.a.size == 2 && options.a[1] == dim && options.a[0] > 0)
        val count = options.a[0]
        val read = JevaMath.normalize(JevaMath.finite(JevaMath.array(options)), dim)
        val keys = Array(config.depth) { JevaMath.normalize(blocks[it].k(read, check), config.headDim) }
        val values = Array(config.depth) { blocks[it].v(read, check) }
        return Array(count) { option ->
            check()
            JevaOptionMemory(tensorOf(shapeOf(dim), read.copyOfRange(option * dim, (option + 1) * dim)),
                Array(config.depth) { tensorOf(shapeOf(config.numHeads, config.headDim), keys[it].copyOfRange(option * dim, (option + 1) * dim)) }.toSeries(),
                Array(config.depth) { tensorOf(shapeOf(config.numHeads, config.headDim), values[it].copyOfRange(option * dim, (option + 1) * dim)) }.toSeries())
        }.toSeries()
    }

    fun assemble(memories: Series<JevaOptionMemory>, check: () -> Unit = {}): JevaOptionCache {
        check()
        require(memories.size > 0)
        val read = FloatArray(tensorSize(shapeOf(memories.size, dim)))
        val keys = Array(config.depth) { FloatArray(read.size) }
        val values = Array(config.depth) { FloatArray(read.size) }
        for (i in 0 until memories.size) {
            check()
            val memory = memories[i]
            JevaMath.dimensions(memory.optionRead, dim)
            require(memory.layerKeys.size == config.depth && memory.layerValues.size == config.depth)
            JevaMath.array(memory.optionRead).copyInto(read, i * dim)
            for (layer in 0 until config.depth) {
                JevaMath.dimensions(memory.layerKeys[layer], config.numHeads, config.headDim)
                JevaMath.dimensions(memory.layerValues[layer], config.numHeads, config.headDim)
                JevaMath.array(memory.layerKeys[layer]).copyInto(keys[layer], i * dim)
                JevaMath.array(memory.layerValues[layer]).copyInto(values[layer], i * dim)
            }
        }
        val shape = shapeOf(1, memories.size, config.numHeads, config.headDim)
        return JevaOptionCache(tensorOf(shapeOf(1, memories.size, dim), read),
            tensorOf(shapeOf(1, memories.size), FloatArray(memories.size) { 1f }),
            Array(config.depth) { tensorOf(shape, keys[it]) }.toSeries(),
            Array(config.depth) { tensorOf(shape, values[it]) }.toSeries())
    }

    fun forward(context: MetaSeries<Tensor, Float>, options: MetaSeries<Tensor, Float>, mask: MetaSeries<Tensor, Float>,
                returnLayerAttentions: Boolean = false, check: () -> Unit = {}): JevaDecisionOutput =
        forward(context, prepare(options, mask, check), returnLayerAttentions, check)

    fun forward(context: MetaSeries<Tensor, Float>, cache: JevaOptionCache,
                returnLayerAttentions: Boolean = false, check: () -> Unit = {}): JevaDecisionOutput {
        check()
        require(cache.optionMask.a.size == 2)
        val batch = cache.batchSize; val count = cache.numOptions
        require(batch > 0 && count > 0)
        JevaMath.dimensions(context, batch, dim)
        JevaMath.dimensions(cache.optionRead, batch, count, dim)
        require(cache.layerKeys.size == config.depth && cache.layerValues.size == config.depth)
        val mask = JevaMath.array(cache.optionMask)
        require(mask.all { it == 0f || it == 1f })
        require((0 until batch).all { b -> (0 until count).any { mask[b * count + it] == 1f } })
        var state = JevaMath.finite(JevaMath.array(context))
        if (config.normalizeState) state = JevaMath.normalize(state, dim)
        val attentions = mutableListOf<MetaSeries<Tensor, Float>>()
        for (layer in 0 until config.depth) {
            check()
            JevaMath.dimensions(cache.layerKeys[layer], batch, count, config.numHeads, config.headDim)
            JevaMath.dimensions(cache.layerValues[layer], batch, count, config.numHeads, config.headDim)
            val result = blocks[layer].forward(state, JevaMath.finite(JevaMath.array(cache.layerKeys[layer])),
                JevaMath.finite(JevaMath.array(cache.layerValues[layer])), mask, batch, count, check)
            state = result.a
            if (returnLayerAttentions) attentions.add(tensorOf(shapeOf(batch, config.numHeads, count), result.b))
        }
        val zRead = finalNorm?.let { JevaMath.rms(state, it, config.eps) } ?: state
        val read = JevaMath.finite(JevaMath.array(cache.optionRead))
        val scores = FloatArray(batch * count)
        val logits = FloatArray(scores.size)
        val logQ = FloatArray(scores.size)
        val q = FloatArray(scores.size)
        val joint = FloatArray(scores.size)
        val coverage = FloatArray(batch)
        val nulls = FloatArray(batch)
        val coverageLogit = FloatArray(batch)
        for (b in 0 until batch) {
            check()
            var maximum = Float.NEGATIVE_INFINITY
            var maxLogit = Float.NEGATIVE_INFINITY
            for (option in 0 until count) {
                check()
                val i = b * count + option
                var score = 0f
                for (d in 0 until dim) score += zRead[b * dim + d] * read[i * dim + d]
                require(score.isFinite()) { "Non-finite Jeva score" }
                scores[i] = score
                logits[i] = if (mask[i] == 0f) Float.NEGATIVE_INFINITY else score / tauDec
                if (mask[i] != 0f) maximum = max(maximum, score)
                maxLogit = max(maxLogit, logits[i])
            }
            var denominator = 0f
            for (option in 0 until count) denominator += exp((logits[b * count + option] - maxLogit).toDouble()).toFloat()
            val logDenominator = ln(denominator.toDouble()).toFloat()
            val covLogit = (maximum - coverageBias) / tauCov
            coverageLogit[b] = covLogit
            coverage[b] = JevaMath.sigmoid(covLogit); nulls[b] = JevaMath.sigmoid(-covLogit)
            for (option in 0 until count) {
                val i = b * count + option
                logQ[i] = logits[i] - maxLogit - logDenominator
                q[i] = if (mask[i] == 0f) 0f else exp(logQ[i].toDouble()).toFloat()
                joint[i] = coverage[b] * q[i]
            }
        }
        val choices = shapeOf(batch, count); val states = shapeOf(batch, dim); val batches = shapeOf(batch)
        return JevaDecisionOutput(tensorOf(choices, q), tensorOf(batches, coverage), tensorOf(batches, nulls),
            tensorOf(choices, joint), tensorOf(choices, scores), tensorOf(states, state), tensorOf(states, zRead),
            tensorOf(choices, logits), tensorOf(choices, logQ), tensorOf(batches, coverageLogit), attentions.toSeries(),
            if (returnLayerAttentions) blocks α { it.alpha } else emptySeriesOf())
    }

    companion object {
        fun weightsScalar(weights: (String) -> MetaSeries<Tensor, Float>, name: String): Float = weights(name).let {
            JevaMath.dimensions(it); JevaMath.finite(JevaMath.array(it)).single()
        }
        fun vector(weights: (String) -> MetaSeries<Tensor, Float>, name: String, width: Int): FloatArray = weights(name).let {
            JevaMath.dimensions(it, width); JevaMath.finite(JevaMath.array(it))
        }
    }
}
