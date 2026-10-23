package borg.trikeshed.lib.floatp

/** Monomorphic Float-column kernels for vector columns: `flat` holds `n` rows of `dims` floats. */

fun dot(flat: FloatArray, n: Int, dims: Int, probe: FloatArray, out: FloatArray) {
    for (r in 0 until n) {
        var s = 0f
        val base = r * dims
        for (d in 0 until dims) s += flat[base + d] * probe[d]
        out[r] = s
    }
}

/** Squared L2 distance. */
fun l2Squared(flat: FloatArray, n: Int, dims: Int, probe: FloatArray, out: FloatArray) {
    for (r in 0 until n) {
        var s = 0f
        val base = r * dims
        for (d in 0 until dims) { val x = flat[base + d] - probe[d]; s += x * x }
        out[r] = s
    }
}

/** Cosine similarity; a zero-norm row or probe answers 0. */
fun cosine(flat: FloatArray, n: Int, dims: Int, probe: FloatArray, out: FloatArray) {
    var pn = 0f
    for (d in 0 until dims) pn += probe[d] * probe[d]
    val probeNorm = kotlin.math.sqrt(pn)
    for (r in 0 until n) {
        var dotp = 0f
        var rn = 0f
        val base = r * dims
        for (d in 0 until dims) { val x = flat[base + d]; dotp += x * probe[d]; rn += x * x }
        val norm = kotlin.math.sqrt(rn) * probeNorm
        out[r] = if (norm == 0f) 0f else dotp / norm
    }
}

/** The [k] best rows of [scores], best first, ties by lower ordinal. */
fun topK(scores: FloatArray, n: Int, k: Int, descending: Boolean): IntArray {
    val widened = DoubleArray(n) { scores[it].toDouble() }
    return borg.trikeshed.lib.doublep.topK(widened, n, k, descending)
}
