package borg.trikeshed.lib.doublep

import borg.trikeshed.lib.intp.Comparison
import borg.trikeshed.lib.intp.Selection

/** Monomorphic Double-column kernels. NaN compares false under every operator but NE, as IEEE. */

fun where(values: DoubleArray, n: Int, op: Comparison, operand: Double, out: Selection): Int {
    var m = 0
    when (op) {
        Comparison.LT -> for (i in 0 until n) { out[m] = i; m += if (values[i] < operand) 1 else 0 }
        Comparison.LE -> for (i in 0 until n) { out[m] = i; m += if (values[i] <= operand) 1 else 0 }
        Comparison.GT -> for (i in 0 until n) { out[m] = i; m += if (values[i] > operand) 1 else 0 }
        Comparison.GE -> for (i in 0 until n) { out[m] = i; m += if (values[i] >= operand) 1 else 0 }
        Comparison.EQ -> for (i in 0 until n) { out[m] = i; m += if (values[i] == operand) 1 else 0 }
        Comparison.NE -> for (i in 0 until n) { out[m] = i; m += if (values[i] != operand) 1 else 0 }
    }
    return m
}

fun refine(values: DoubleArray, sel: Selection, m: Int, op: Comparison, operand: Double, out: Selection): Int {
    var k = 0
    when (op) {
        Comparison.LT -> for (j in 0 until m) { val r = sel[j]; out[k] = r; k += if (values[r] < operand) 1 else 0 }
        Comparison.LE -> for (j in 0 until m) { val r = sel[j]; out[k] = r; k += if (values[r] <= operand) 1 else 0 }
        Comparison.GT -> for (j in 0 until m) { val r = sel[j]; out[k] = r; k += if (values[r] > operand) 1 else 0 }
        Comparison.GE -> for (j in 0 until m) { val r = sel[j]; out[k] = r; k += if (values[r] >= operand) 1 else 0 }
        Comparison.EQ -> for (j in 0 until m) { val r = sel[j]; out[k] = r; k += if (values[r] == operand) 1 else 0 }
        Comparison.NE -> for (j in 0 until m) { val r = sel[j]; out[k] = r; k += if (values[r] != operand) 1 else 0 }
    }
    return k
}

fun gather(values: DoubleArray, sel: Selection, m: Int): DoubleArray {
    val out = DoubleArray(m)
    for (j in 0 until m) out[j] = values[sel[j]]
    return out
}

fun sum(values: DoubleArray, sel: Selection, m: Int): Double {
    var s = 0.0
    for (j in 0 until m) s += values[sel[j]]
    return s
}

fun min(values: DoubleArray, sel: Selection, m: Int): Double {
    var best = Double.POSITIVE_INFINITY
    for (j in 0 until m) { val v = values[sel[j]]; if (v < best) best = v }
    return best
}

fun max(values: DoubleArray, sel: Selection, m: Int): Double {
    var best = Double.NEGATIVE_INFINITY
    for (j in 0 until m) { val v = values[sel[j]]; if (v > best) best = v }
    return best
}

/** `out[j] = a[sel[j]] * b[sel[j]]`, dense in `j`. */
fun mul(a: DoubleArray, b: DoubleArray, sel: Selection, m: Int, out: DoubleArray) {
    for (j in 0 until m) { val r = sel[j]; out[j] = a[r] * b[r] }
}

/** `out[j] = a[sel[j]] * scale + shift` — the `(1 - discount)` and `(1 + tax)` factors. */
fun affine(a: DoubleArray, scale: Double, shift: Double, sel: Selection, m: Int, out: DoubleArray) {
    for (j in 0 until m) out[j] = a[sel[j]] * scale + shift
}

/** `out[j] *= factor[j]` over dense arrays. */
fun mulInPlace(out: DoubleArray, factor: DoubleArray, m: Int) {
    for (j in 0 until m) out[j] *= factor[j]
}

fun sumByGroup(values: DoubleArray, sel: Selection, ids: IntArray, m: Int, sums: DoubleArray) {
    for (j in 0 until m) sums[ids[j]] += values[sel[j]]
}

/** Same as [sumByGroup] for an already dense (selection-ordered) value array. */
fun sumDenseByGroup(dense: DoubleArray, ids: IntArray, m: Int, sums: DoubleArray) {
    for (j in 0 until m) sums[ids[j]] += dense[j]
}

fun minByGroup(values: DoubleArray, sel: Selection, ids: IntArray, m: Int, mins: DoubleArray) {
    for (j in 0 until m) { val v = values[sel[j]]; val g = ids[j]; if (v < mins[g]) mins[g] = v }
}

fun maxByGroup(values: DoubleArray, sel: Selection, ids: IntArray, m: Int, maxs: DoubleArray) {
    for (j in 0 until m) { val v = values[sel[j]]; val g = ids[j]; if (v > maxs[g]) maxs[g] = v }
}

/**
 * The [k] best rows of [scores] among `0 until n`, best first, ties broken by lower ordinal.
 * A bounded binary heap of size k: O(n log k), no sort of the full column.
 */
fun topK(scores: DoubleArray, n: Int, k: Int, descending: Boolean): IntArray {
    val size = minOf(k, n)
    if (size <= 0) return IntArray(0)
    val heap = IntArray(size)
    var count = 0
    // "worse" ordering: the heap root is the worst kept row, so a candidate replaces it when better.
    fun worse(a: Int, b: Int): Boolean {
        val sa = scores[a]; val sb = scores[b]
        return if (sa == sb) a > b else if (descending) sa < sb else sa > sb
    }
    fun siftDown(from: Int) {
        var i = from
        while (true) {
            val l = 2 * i + 1
            if (l >= count) return
            val r = l + 1
            val c = if (r < count && worse(heap[r], heap[l])) r else l
            if (worse(heap[c], heap[i])) { val t = heap[i]; heap[i] = heap[c]; heap[c] = t; i = c } else return
        }
    }
    for (row in 0 until n) {
        if (count < size) {
            var i = count++
            heap[i] = row
            while (i > 0) {
                val parent = (i - 1) / 2
                if (worse(heap[i], heap[parent])) { val t = heap[i]; heap[i] = heap[parent]; heap[parent] = t; i = parent } else break
            }
        } else if (worse(heap[0], row)) {
            heap[0] = row
            siftDown(0)
        }
    }
    val out = IntArray(count)
    for (i in count - 1 downTo 0) {
        out[i] = heap[0]
        heap[0] = heap[count - 1]
        count--
        siftDown(0)
    }
    return out
}

/** Dense elementwise `out[i] = a[i] op b[i]` for `+ - /` (`*` is [mul] with the identity selection). */
fun add(a: DoubleArray, b: DoubleArray, n: Int, out: DoubleArray) { for (i in 0 until n) out[i] = a[i] + b[i] }
fun sub(a: DoubleArray, b: DoubleArray, n: Int, out: DoubleArray) { for (i in 0 until n) out[i] = a[i] - b[i] }
fun div(a: DoubleArray, b: DoubleArray, n: Int, out: DoubleArray) { for (i in 0 until n) out[i] = a[i] / b[i] }
