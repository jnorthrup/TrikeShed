package borg.trikeshed.hermes.tool

import borg.trikeshed.lib.*
import borg.trikeshed.lib.Series
import borg.trikeshed.mutable.RingSeries

/**
 * og1 RingTool — exposes RingSeries to hermes agent.
 *
 * hermes agent calls `og1_ring` tool with JSON args:
 *   { "op": "read", "ringName": "og1-events", "fromIndex": 0, "limit": 100, "reduce": "kmeans", "reduceK": 3 }
 *   { "op": "append", "ringName": "og1-events", "events": [{"v": 0.5}, {"v": 0.7}] }
 *   { "op": "capture", "ringName": "og1-events" }
 *   { "op": "list" }
 *
 * Returns JSON with ring data + optional reducer result.
 *
 * Flow:
 *   agent → og1_ring(JSON args) → JVM RingSeries → JSON → agent
 *   agent → og1_ring(append, events) → RingSeries.add() → JSON confirmation
 *
 * Reducers: "count", "eigenvalue", "kmeans"
 */

// ── RingSeries registry (shared JVM state, survives across cron ticks) ─────

object RingRegistry {
    private val rings = mutableMapOf<String, RingSeries<Map<String, Any?>>>()

    fun getOrCreate(name: String): RingSeries<Map<String, Any?>> =
        rings.getOrPut(name) {
            RingSeries(1024)  // power of 2 capacity
        }

    fun names(): List<String> = rings.keys.toList()
    fun clear(name: String) {
        rings.remove(name)
    }
}
fun countReduce(series: Series<Map<String, Any?>>): Map<String, Any> =
    mapOf("count" to series.a)

/** Eigenvalue reducer: power iteration on extracted numeric field. */
fun eigenvalueReduce(series: Series<Map<String, Any?>>, k: Int): Map<String, Any> {
    val n = series.a
    if (n < 2) return mapOf("eigenvector" to emptyList<Double>(), "eigenvalue" to 0.0)
    val dim = minOf(k, n)
    val matrix = buildSquareMatrix(series, dim)
    val v = powerIteration(matrix)
    var eigenvalue = 0.0
    for (x in v) eigenvalue += x * x
    return mapOf("eigenvector" to v.asList(), "eigenvalue" to eigenvalue)
}

/** K-means reducer: cluster assignments on "v" field. */
fun kmeansReduce(series: Series<Map<String, Any?>>, k: Int): Map<String, Any> {
    if (series.a == 0) return mapOf("clusters" to emptyList<Map<String, Any>>())
    val values = extractValues(series)
    val assignments = kmeansAssign(values, k)
    val clusters = buildClusterSummary(assignments, values, k)
    return mapOf("clusters" to clusters)
}

// ── Matrix helpers ─────────────────────────────────────────────────────────

/** Dense matrix for power iter — DoubleArray rows (autovec), not List of Lists. */
private fun buildSquareMatrix(series: Series<Map<String, Any?>>, dim: Int): Array<DoubleArray> {
    val n = series.a
    val d = minOf(dim, n)
    return Array(d) { i ->
        DoubleArray(d) { j ->
            val idx = i * dim + j
            if (idx < n) series[idx]["v"]?.let { (it as? Number)?.toDouble() } ?: 0.0 else 0.0
        }
    }
}

private fun powerIteration(matrix: Array<DoubleArray>, iterations: Int = 50): DoubleArray {
    val k = matrix.size
    if (k == 0) return DoubleArray(0)
    var v = DoubleArray(k) { 1.0 / k }
    val Av = DoubleArray(k)
    repeat(iterations) {
        for (i in 0 until k) {
            var s = 0.0
            val row = matrix[i]
            for (j in 0 until k) s += row[j] * v[j]
            Av[i] = s
        }
        var norm = 0.0
        for (i in 0 until k) norm += Av[i] * Av[i]
        norm = kotlin.math.sqrt(norm)
        if (norm > 1e-10) {
            val inv = 1.0 / norm
            for (i in 0 until k) v[i] = Av[i] * inv
        }
    }
    return v
}

/** One DoubleArray freeze from Series field "v". */
private fun extractValues(series: Series<Map<String, Any?>>): DoubleArray {
    val n = series.a
    return DoubleArray(n) { i ->
        series[i]["v"]?.let { (it as? Number)?.toDouble() } ?: 0.0
    }
}

private fun kmeansAssign(X: DoubleArray, k: Int, maxIter: Int = 20): IntArray {
    if (X.isEmpty()) return IntArray(0)
    var lo = X[0]; var hi = X[0]
    for (x in X) { if (x < lo) lo = x; if (x > hi) hi = x }
    val centroids = DoubleArray(k) { c -> lo + (hi - lo) * c / k }
    val assignments = IntArray(X.size)
    val sums = DoubleArray(k)
    val counts = IntArray(k)
    repeat(maxIter) {
        for (i in X.indices) {
            val x = X[i]
            var best = 0
            var bestDist = kotlin.math.abs(x - centroids[0])
            for (c in 1 until k) {
                val d = kotlin.math.abs(x - centroids[c])
                if (d < bestDist) { bestDist = d; best = c }
            }
            assignments[i] = best
        }
        for (c in 0 until k) { sums[c] = 0.0; counts[c] = 0 }
        for (i in X.indices) {
            val c = assignments[i]
            sums[c] += X[i]
            counts[c]++
        }
        for (c in 0 until k) {
            if (counts[c] > 0) centroids[c] = sums[c] / counts[c]
        }
    }
    return assignments
}

private fun buildClusterSummary(assignments: IntArray, values: DoubleArray, k: Int): List<Map<String, Any>> {
    val sums = DoubleArray(k)
    val counts = IntArray(k)
    for (i in assignments.indices) {
        val c = assignments[i]
        sums[c] += values[i]
        counts[c]++
    }
    return List(k) { cid ->
        val centroid = if (counts[cid] == 0) 0.0 else sums[cid] / counts[cid]
        mapOf(
            "clusterId" to cid,
            "memberCount" to counts[cid],
            "centroid" to round(centroid, 6),
        )
    }
}

private fun round(v: Double, decimals: Int): Double {
    val factor = Math.pow(10.0, decimals.toDouble())
    return kotlin.math.round(v * factor) / factor
}

// ── Tool entry points ──────────────────────────────────────────────────────

object Og1RingTool {

    /** Main entry: parse op, dispatch to read/append/capture/list. */
    fun call(op: String, args: Map<String, Any?>): Map<String, Any?> =
        when (op) {
            "read" -> read(
                args["ringName"] as? String ?: "default",
                (args["fromIndex"] as? Number)?.toInt() ?: 0,
                (args["limit"] as? Number)?.toInt() ?: 100,
                args["reduce"] as? String,
                (args["reduceK"] as? Number)?.toInt(),
            )
            "append" -> append(
                args["ringName"] as? String ?: "default",
                args["events"] as? List<Map<String, Any?>> ?: emptyList(),
            )
            "capture" -> capture(args["ringName"] as? String ?: "default")
            "list" -> listRings()
            "clear" -> clear(args["ringName"] as? String ?: "default")
            else -> mapOf("error" to "unknown op: $op")
        }

    fun read(ringName: String, fromIndex: Int = 0, limit: Int = 100,
             reduce: String? = null, reduceK: Int? = null): Map<String, Any?> {
        val ring = RingRegistry.getOrCreate(ringName)
        val from = fromIndex.coerceAtLeast(0)
        val lim = limit.coerceIn(1, 1000)
        val count = ring.a

        if (count == 0 || from >= count) {
            return mapOf(
                "ringName" to ringName,
                "size" to 0,
                "events" to emptyList<Map<String, Any?>>(),
            )
        }

        val end = minOf(from + lim, count)
        val events = (from until end).map { ring[it] }

        val result = mutableMapOf<String, Any?>(
            "ringName" to ringName,
            "size" to count,
            "fromIndex" to from,
            "limit" to lim,
            "events" to events,
        )

        reduce?.let { r ->
            result["reduced"] = when (r) {
                "count" -> countReduce(ring.view)
                "eigenvalue" -> eigenvalueReduce(ring.view, reduceK ?: 5)
                "kmeans" -> kmeansReduce(ring.view, reduceK ?: 3)
                else -> mapOf("error" to "unknown reducer: $r")
            }
        }

        return result
    }

    fun append(ringName: String, events: List<Map<String, Any?>>): Map<String, Any?> {
        val ring = RingRegistry.getOrCreate(ringName)
        val before = ring.size
        events.forEach { ring.add(it) }
        return mapOf(
            "ringName" to ringName,
            "appended" to events.size,
            "sizeBefore" to before,
            "sizeAfter" to ring.size,
        )
    }

    fun capture(ringName: String): Map<String, Any?> {
        // Ring mutates — freeze once into array-backed Series (cap is power-of-2, small).
        // Series.toList() is AbstractList over that array, not Iterable.view.toList copy.
        val ring = RingRegistry.getOrCreate(ringName)
        val n = ring.size
        val frozen: Series<Map<String, Any?>> = Array(n) { ring[it] }.toSeries()
        return mapOf(
            "ringName" to ringName,
            "captured" to true,
            "size" to n,
            "events" to frozen.toList(),
        )
    }
    fun listRings(): Map<String, Any?> = mapOf("rings" to RingRegistry.names())

    fun clear(ringName: String): Map<String, Any?> {
        RingRegistry.clear(ringName)
        return mapOf("ringName" to ringName, "cleared" to true, "size" to 0)
    }
}