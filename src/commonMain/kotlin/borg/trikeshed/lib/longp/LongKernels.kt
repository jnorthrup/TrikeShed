package borg.trikeshed.lib.longp

import borg.trikeshed.lib.intp.Comparison
import borg.trikeshed.lib.intp.Selection

/** Monomorphic Long-column kernels: selection, aggregation, grouping and hash join over LongArrays. */

fun where(values: LongArray, n: Int, op: Comparison, operand: Long, out: Selection): Int {
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

fun refine(values: LongArray, sel: Selection, m: Int, op: Comparison, operand: Long, out: Selection): Int {
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

fun gather(values: LongArray, sel: Selection, m: Int): LongArray {
    val out = LongArray(m)
    for (j in 0 until m) out[j] = values[sel[j]]
    return out
}

fun sum(values: LongArray, sel: Selection, m: Int): Long {
    var s = 0L
    for (j in 0 until m) s += values[sel[j]]
    return s
}

/** `Long.MAX_VALUE` / `MIN_VALUE` for `m == 0`; callers test the count first. */
fun min(values: LongArray, sel: Selection, m: Int): Long {
    var best = Long.MAX_VALUE
    for (j in 0 until m) { val v = values[sel[j]]; if (v < best) best = v }
    return best
}

fun max(values: LongArray, sel: Selection, m: Int): Long {
    var best = Long.MIN_VALUE
    for (j in 0 until m) { val v = values[sel[j]]; if (v > best) best = v }
    return best
}

/** Fibonacci hash of a key into a power-of-two table of `1 shl bits` slots. */
fun slotOf(key: Long, bits: Int): Int = ((key * -7046029254386353131L) ushr (64 - bits)).toInt()

/**
 * Dense group ids by open addressing with linear probing. `ids[j]` is the group of row `sel[j]`
 * (groups numbered by first occurrence); [firstRow] receives each group's first row ordinal
 * (capacity `m`). Returns the group count. [table] is scratch of a power-of-two size ≥ `2m`,
 * holding group+1 (0 = empty); [tableKeys] is scratch of the same size.
 */
fun groupIds(keys: LongArray, sel: Selection, m: Int, ids: IntArray, firstRow: IntArray, table: IntArray, tableKeys: LongArray): Int {
    val mask = table.size - 1
    val bits = 63 - table.size.toLong().countLeadingZeroBits()
    var groups = 0
    for (j in 0 until m) {
        val key = keys[sel[j]]
        var slot = slotOf(key, bits)
        while (true) {
            val held = table[slot]
            if (held == 0) {
                table[slot] = groups + 1
                tableKeys[slot] = key
                firstRow[groups] = sel[j]
                ids[j] = groups++
                break
            }
            if (tableKeys[slot] == key) { ids[j] = held - 1; break }
            slot = (slot + 1) and mask
        }
    }
    return groups
}

/** Smallest power of two ≥ `2 * m`, at least 16. */
fun tableSizeFor(m: Int): Int {
    var size = 16
    while (size < 2 * m) size = size shl 1
    return size
}

/** `counts[ids[j]]++` for `j < m`. */
fun countByGroup(ids: IntArray, m: Int, counts: LongArray) {
    for (j in 0 until m) counts[ids[j]]++
}

fun sumByGroup(values: LongArray, sel: Selection, ids: IntArray, m: Int, sums: LongArray) {
    for (j in 0 until m) sums[ids[j]] += values[sel[j]]
}

/**
 * Hash-join build over [keys]: chained buckets. Row r heads or links into `head[slotOf(key)]` /
 * `next[r]` (−1 ends a chain). [head] must be a power-of-two table filled with −1.
 */
fun hashBuild(keys: LongArray, n: Int, head: IntArray, next: IntArray) {
    val bits = 63 - head.size.toLong().countLeadingZeroBits()
    for (r in n - 1 downTo 0) {
        val slot = slotOf(keys[r], bits)
        next[r] = head[slot]
        head[slot] = r
    }
}

/**
 * Probe: for each probe row `p < n` with key k, appends every build row b with `buildKeys[b] == k`
 * as the pair (`outProbe[c]`, `outBuild[c]`) in probe-then-build-ascending order; returns the pair
 * count. Stops (returning the count so far negated minus one) when the output buffers fill.
 */
fun probe(probeKeys: LongArray, n: Int, buildKeys: LongArray, head: IntArray, next: IntArray, outProbe: IntArray, outBuild: IntArray): Int {
    val bits = 63 - head.size.toLong().countLeadingZeroBits()
    var c = 0
    for (p in 0 until n) {
        val key = probeKeys[p]
        var b = head[slotOf(key, bits)]
        while (b >= 0) {
            if (buildKeys[b] == key) {
                if (c == outProbe.size) return -c - 1
                outProbe[c] = p
                outBuild[c] = b
                c++
            }
            b = next[b]
        }
    }
    return c
}
