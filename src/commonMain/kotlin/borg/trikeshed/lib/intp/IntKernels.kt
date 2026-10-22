package borg.trikeshed.lib.intp

/** Monomorphic Int-column kernels: no boxing, no lambdas, no virtual calls in any loop body. */

/** Ordinals `i < n` with `values[i] op operand` into [out]; returns the count. Branch-free writes. */
fun where(values: IntArray, n: Int, op: Comparison, operand: Int, out: Selection): Int {
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

/** Keeps the entries of [sel]`[0 until m]` whose row passes; compacts in place into [out] (may alias [sel]). */
fun refine(values: IntArray, sel: Selection, m: Int, op: Comparison, operand: Int, out: Selection): Int {
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

/** `values[sel[j]]` for `j < m` into a fresh array. */
fun gather(values: IntArray, sel: Selection, m: Int): IntArray {
    val out = IntArray(m)
    for (j in 0 until m) out[j] = values[sel[j]]
    return out
}

/** Sum over the selection as Long. */
fun sum(values: IntArray, sel: Selection, m: Int): Long {
    var s = 0L
    for (j in 0 until m) s += values[sel[j]]
    return s
}
