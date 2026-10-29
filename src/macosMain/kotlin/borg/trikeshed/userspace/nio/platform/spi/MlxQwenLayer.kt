package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.lib.*
import kotlin.math.sqrt

/** A packed affine-quantized matrix [out, in * bits / 32] with its scales and biases. */
class MlxQuantized(val weight: MlxTensor, val scales: MlxTensor, val biases: MlxTensor, val groupSize: Int, val bits: Int)

fun MlxMatrixSession.linear(input: MlxTensor, matrix: MlxQuantized): MlxTensor =
    quantizedLinear(input, matrix.weight, matrix.scales, matrix.biases, matrix.groupSize, matrix.bits)

/**
 * Qwen3.5 full-attention layer over one unpadded causal sequence [tokens, hidden]: the query projection carries an output
 * gate, q and k are normed per head then rotated, grouped-query causal attention, gate, output projection.
 * Norm weights are used as stored.
 */
fun MlxMatrixSession.fullAttention(
    input: MlxTensor, query: MlxQuantized, key: MlxQuantized, value: MlxQuantized, output: MlxQuantized,
    queryNorm: MlxTensor, keyNorm: MlxTensor,
    heads: Int, keyValueHeads: Int, headDim: Int, ropeDimensions: Int, ropeBase: Float, epsilon: Float,
): MlxTensor {
    val tokens = input.a[0]
    val (queries, gate) = split(reshape(linear(input, query), 1, tokens, heads, 2 * headDim), 2, 3)
    val keys = reshape(linear(input, key), 1, tokens, keyValueHeads, headDim)
    val values = reshape(linear(input, value), 1, tokens, keyValueHeads, headDim)
    val rotatedQueries = rope(swapAxes(rmsNorm(queries, queryNorm, epsilon), 1, 2), ropeDimensions, ropeBase)
    val rotatedKeys = rope(swapAxes(rmsNorm(keys, keyNorm, epsilon), 1, 2), ropeDimensions, ropeBase)
    val attended = attention(rotatedQueries, rotatedKeys, swapAxes(values, 1, 2), 1f / sqrt(headDim.toFloat()))
    val merged = reshape(swapAxes(attended, 1, 2), tokens, heads * headDim)
    return linear(times(merged, sigmoid(reshape(gate, tokens, heads * headDim))), output)
}

/**
 * Qwen3.5 linear-attention (gated delta) layer over one unpadded sequence [tokens, hidden], zero initial state.
 * The causal depthwise convolution sees three zero positions of history; the recurrence advances one token at a time
 * on the device: decay the state, read it along the key, write the beta-weighted correction, read along the query.
 * Norm weights are used as stored; [stateA] and [stateBias] are the A_log and dt_bias vectors.
 */
fun MlxMatrixSession.linearAttention(
    input: MlxTensor, qkv: MlxQuantized, gate: MlxQuantized, beta: MlxTensor, decay: MlxTensor, output: MlxQuantized,
    convolution: MlxTensor, stateA: MlxTensor, stateBias: MlxTensor, norm: MlxTensor,
    keyHeads: Int, valueHeads: Int, keyDim: Int, valueDim: Int, kernel: Int, epsilon: Float,
): MlxTensor {
    val tokens = input.a[0]
    val keyWidth = keyHeads * keyDim
    val valueWidth = valueHeads * valueDim
    val channels = 2 * keyWidth + valueWidth
    val gates = reshape(linear(input, gate), tokens, valueHeads, valueDim)
    val learning = sigmoid(dense(input, beta))
    val history = concatenate(listOf(zeros(kernel - 1, channels), linear(input, qkv)), 0)
    val mixed = silu(conv1d(reshape(history, 1, tokens + kernel - 1, channels), convolution, channels))
    val queries = repeat(scale(rmsNorm(reshape(slice(mixed, 2, 0, keyWidth), tokens, keyHeads, keyDim), null, 1e-6f), 1f / keyDim), valueHeads / keyHeads, 1)
    val keys = repeat(scale(rmsNorm(reshape(slice(mixed, 2, keyWidth, 2 * keyWidth), tokens, keyHeads, keyDim), null, 1e-6f), 1f / kotlin.math.sqrt(keyDim.toFloat())), valueHeads / keyHeads, 1)
    val values = reshape(slice(mixed, 2, 2 * keyWidth, channels), tokens, valueHeads, valueDim)
    val retention = exp(negate(times(exp(stateA), softplus(add(dense(input, decay), stateBias)))))
    var state = zeros(valueHeads, valueDim, keyDim)
    val rows = ArrayList<MlxTensor>(tokens)
    for (t in 0 until tokens) {
        fun at(of: MlxTensor, vararg extents: Int) = reshape(slice(of, 0, t, t + 1), *extents)
        val key = at(keys, valueHeads, 1, keyDim)
        state = times(state, at(retention, valueHeads, 1, 1))
        val memory = sum(times(state, key), 2)
        val correction = times(minus(at(values, valueHeads, valueDim), memory), at(learning, valueHeads, 1))
        state = add(state, times(key, reshape(correction, valueHeads, valueDim, 1)))
        rows += reshape(sum(times(state, at(queries, valueHeads, 1, keyDim)), 2), 1, valueHeads, valueDim)
    }
    val normed = times(rmsNorm(concatenate(rows, 0), norm, epsilon), silu(gates))
    return linear(reshape(normed, tokens, valueWidth), output)
}
