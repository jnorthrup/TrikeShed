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
        val ring = rings[name]
        if (ring != null) ring.clear()
    }
}
++
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
    val eigenvalue = v.zip(v).sumOf { (a, b) -> a * b }
    return mapOf("eigenvector" to v, "eigenvalue" to eigenvalue)
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

private fun buildSquareMatrix(series: Series<Map<String, Any?>>, dim: Int): List<List<Double>> {
    val n = series.a
    return (0 until minOf(dim, n)).map { i ->
        (0 until minOf(dim, n)).map { j ->
            val idx = i * dim + j
            if (idx < n) {
                series[idx]["v"]?.let { (it as? Number)?.toDouble() } ?: 0.0
            } else 0.0
        }
    }
}

private fun powerIteration(matrix: List<List<Double>>, iterations: Int = 50): List<Double> {
    val k = matrix.size
    if (k == 0) return emptyList()
    var v = List(k) { 1.0 / k }
    repeat(iterations) {
        val Av = v.mapIndexed { i, _ ->
            matrix[i].zip(v).sumOf { (a, b) -> a * b }
        }
        val norm = kotlin.math.sqrt(Av.sumOf { it * it })
        if (norm > 1e-10) v = Av.map { it / norm }
    }
    return v
}

private fun extractValues(series: Series<Map<String, Any?>>): List<Double> =
    (0 until series.a).map { i ->
        series[i]["v"]?.let { (it as? Number)?.toDouble() } ?: 0.0
    }

private fun kmeansAssign(X: List<Double>, k: Int, maxIter: Int = 20): List<Int> {
    if (X.isEmpty()) return emptyList()
    val lo = X.minOrNull() ?: 0.0
    val hi = X.maxOrNull() ?: 1.0
    var centroids = (0 until k).map { lo + (hi - lo) * it / k }
    var assignments = List(X.size) { 0 }
    repeat(maxIter) {
        assignments = X.map { x ->
            centroids.indices.minByOrNull { kotlin.math.abs(x - centroids[it]) } ?: 0
        }
        centroids = (0 until k).map { c ->
            val members = assignments.zip(X).filter { it.first == c }.map { it.second }
            if (members.isEmpty()) centroids[c] else members.sum() / members.size
        }
    }
    return assignments
}

private fun buildClusterSummary(assignments: List<Int>, values: List<Double>, k: Int): List<Map<String, Any>> =
    (0 until k).map { cid ->
        val members = assignments.zip(values).filter { it.first == cid }.map { it.second }
        val centroid = if (members.isEmpty()) 0.0 else members.sum() / members.size
        mapOf(
            "clusterId" to cid,
            "memberCount" to members.size,
            "centroid" to round(centroid, 6),
        )
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
        // Snapshot: read all events from ring and return them as a flat list
        val ring = RingRegistry.getOrCreate(ringName)
        val events = (0 until ring.size).map { ring[it] }
        return mapOf(
            "ringName" to ringName,
            "captured" to true,
            "size" to ring.size,
            "events" to events,
        )
    }
    fun listRings(): Map<String, Any?> = mapOf("rings" to RingRegistry.names())

    fun clear(ringName: String): Map<String, Any?> {
        RingRegistry.clear(ringName)
        return mapOf("ringName" to ringName, "cleared" to true, "size" to 0)
    }
}