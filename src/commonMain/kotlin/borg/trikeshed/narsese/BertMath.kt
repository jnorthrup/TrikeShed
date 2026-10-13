package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import kotlin.math.*

/** Row/column extents joined with borrowed row-major F32 coordinates. */
typealias BertMatrix = Join<Twin<Int>, Series<Float>>

operator fun BertMatrix.get(row: Int, column: Int): Float {
    require(row in 0 until a.a && column in 0 until a.b) { "BERT matrix coordinate exceeds its shape" }
    return b[row * a.b + column]
}

val BertMatrix.tensor: Tensor<Float> get() = shapeOf(a.a, a.b) j { index: Shape ->
    require(index.size == 2) { "BERT matrix indices must have rank two" }
    this[index[0], index[1]]
}

/** Common F32 BERT kernels. Weight matrices follow Torch's [output, input] storage order. */
object BertMath {
    fun matrix(values: FloatArray, rows: Int, columns: Int): BertMatrix {
        require(rows >= 0 && columns >= 0 && rows.toLong() * columns == values.size.toLong()) { "BERT matrix shape mismatch" }
        return (rows j columns) j values.toSeries()
    }

    fun linear(input: FloatArray, rows: Int, inDim: Int, weight: FloatArray, outDim: Int, bias: FloatArray): FloatArray {
        require(rows >= 0 && inDim > 0 && outDim > 0)
        require(input.size.toLong() == rows.toLong() * inDim && weight.size.toLong() == outDim.toLong() * inDim && bias.size == outDim) {
            "BERT linear shape mismatch"
        }
        require(rows.toLong() * outDim <= Int.MAX_VALUE)
        val output = FloatArray(rows * outDim)
        linear(input, rows, inDim, weight, outDim, bias, output, 0 j rows)
        return output
    }

    /** Writes the half-open row range into a borrowed output matrix; disjoint ranges share no destinations. */
    fun linear(input: FloatArray, rows: Int, inDim: Int, weight: FloatArray, outDim: Int, bias: FloatArray,
               output: FloatArray, range: Twin<Int>) {
        require(rows >= 0 && inDim > 0 && outDim > 0)
        require(input.size.toLong() == rows.toLong() * inDim && weight.size.toLong() == outDim.toLong() * inDim &&
            bias.size == outDim && output.size.toLong() == rows.toLong() * outDim) { "BERT linear shape mismatch" }
        require(range.a >= 0 && range.a <= range.b && range.b <= rows) { "BERT linear row range exceeds its shape" }
        val end = inDim - inDim % 8
        for (row in range.a until range.b) {
            val at = row * inDim
            val dst = row * outDim
            for (out in 0 until outDim) {
                val w = out * inDim
                var s0 = 0f; var s1 = 0f; var s2 = 0f; var s3 = 0f
                var s4 = 0f; var s5 = 0f; var s6 = 0f; var s7 = 0f
                var column = 0
                // Independent F32 reductions let the CPU overlap products without a serial sum dependency.
                while (column < end) {
                    s0 += input[at + column] * weight[w + column]
                    s1 += input[at + column + 1] * weight[w + column + 1]
                    s2 += input[at + column + 2] * weight[w + column + 2]
                    s3 += input[at + column + 3] * weight[w + column + 3]
                    s4 += input[at + column + 4] * weight[w + column + 4]
                    s5 += input[at + column + 5] * weight[w + column + 5]
                    s6 += input[at + column + 6] * weight[w + column + 6]
                    s7 += input[at + column + 7] * weight[w + column + 7]
                    column += 8
                }
                var sum = ((s0 + s1) + (s2 + s3)) + ((s4 + s5) + (s6 + s7))
                while (column < inDim) {
                    sum += input[at + column] * weight[w + column]
                    column++
                }
                output[dst + out] = sum + bias[out]
            }
        }
    }

    /** Population variance, over input plus the optional residual, with F32 accumulation. */
    fun layerNorm(input: FloatArray, rows: Int, dim: Int, weight: FloatArray, bias: FloatArray,
                  eps: Float, residual: FloatArray? = null): FloatArray {
        require(rows >= 0 && dim > 0 && eps.isFinite() && eps > 0f)
        require(input.size.toLong() == rows.toLong() * dim && weight.size == dim && bias.size == dim &&
            (residual == null || residual.size == input.size)) { "BERT layer norm shape mismatch" }
        val output = FloatArray(input.size)
        for (row in 0 until rows) {
            val at = row * dim
            var mean = 0f
            for (column in 0 until dim) mean += input[at + column] + (residual?.get(at + column) ?: 0f)
            mean /= dim
            var variance = 0f
            for (column in 0 until dim) {
                val centered = input[at + column] + (residual?.get(at + column) ?: 0f) - mean
                variance += centered * centered
            }
            val scale = 1f / sqrt(variance / dim + eps)
            for (column in 0 until dim) {
                val centered = input[at + column] + (residual?.get(at + column) ?: 0f) - mean
                output[at + column] = centered * scale * weight[column] + bias[column]
            }
        }
        return output
    }

    /** Erf-form GELU, rather than its tanh form; A&S 7.1.26 approximates erf within 1.5e-7. */
    fun gelu(value: Float): Float {
        if (value == 0f) return value
        val x = abs(value.toDouble()) / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * x)
        val tail = (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x)
        val erf = if (value < 0f) tail - 1.0 else 1.0 - tail
        return (0.5 * value.toDouble() * (1.0 + erf)).toFloat()
    }

    fun gelu(input: FloatArray): FloatArray = FloatArray(input.size) { gelu(input[it]) }

    /** Bidirectional attention; the optional mask excludes keys, and heads concatenate in hidden order. */
    fun attention(q: FloatArray, k: FloatArray, v: FloatArray, rows: Int, hidden: Int, heads: Int,
                  mask: BooleanArray? = null): FloatArray {
        require(rows in 0..512 && hidden > 0 && heads > 0 && hidden % heads == 0)
        require(q.size.toLong() == rows.toLong() * hidden && k.size == q.size && v.size == q.size &&
            (mask == null || mask.size == rows)) { "BERT attention shape mismatch" }
        require(rows == 0 || mask == null || mask.any { it }) { "BERT attention has no admitted keys" }
        val output = FloatArray(q.size)
        val scores = FloatArray(rows)
        val width = hidden / heads
        val scale = 1f / sqrt(width.toFloat())
        for (head in 0 until heads) {
            val from = head * width
            for (row in 0 until rows) {
                val at = row * hidden + from
                var greatest = Float.NEGATIVE_INFINITY
                for (key in 0 until rows) {
                    var score = Float.NEGATIVE_INFINITY
                    if (mask == null || mask[key]) {
                        val other = key * hidden + from
                        var sum = 0f
                        for (column in 0 until width) sum += q[at + column] * k[other + column]
                        score = sum * scale
                    }
                    scores[key] = score
                    greatest = maxOf(greatest, score)
                }
                var mass = 0f
                for (key in 0 until rows) {
                    val probability = exp(scores[key] - greatest)
                    scores[key] = probability
                    mass += probability
                }
                for (key in 0 until rows) scores[key] /= mass
                for (column in 0 until width) {
                    var sum = 0f
                    for (key in 0 until rows) sum += scores[key] * v[key * hidden + from + column]
                    output[at + column] = sum
                }
            }
        }
        return output
    }
}
