@file:Suppress("unused")

package borg.trikeshed.og1.shape

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.og1.state.CrmsPhase
import borg.trikeshed.og1.fanout.poolInit

/* ── Shape = IntArray dimensions ─────────────────────────────────────
 *
 * Shape encodes the lens over a readings tensor:
 *   Shape[0..k)       = key columns (groupBy axis)
 *   Shape[k..k+2)     = date axes (reading_date, interval) — always LAST 2
 *   Shape[k+2..n)     = metric columns (reduce targets) — always before date
 *
 * Shape is the STABLE HANDLE. Cursor is the VOLATILE BODY.
 * Watermark tracks version for Confix re-join.
 */
typealias Shape = IntArray

/* ── ShapeCursor — versioned handle-body volatile ──────────────────── */
data class ShapeCursor(
    val shape: Shape,             // stable handle
    val cursor: Series<RowVec>,   // volatile body
    val version: Long,             // monotonic watermark
)

/* ── ShapeCursorBox ─────────────────────────────────────────────────── */
class ShapeCursorBox(
    val handle: Shape,
    private val body: ArrayList<RowVec> = ArrayList(),
    private var version: Long = 0L,
) {
    fun reify(): ShapeCursor = ShapeCursor(
        shape = handle,
        cursor = body.size j { i -> body[i] },
        version = version,
    )

    fun ingest(source: Series<RowVec>) {
        for (i in 0 until source.a) body.add(source.b(i))
        version++
    }

    fun advanceWatermark(minVersion: Long) { version = maxOf(version, minVersion) }

    val currentVersion: Long get() = version
    val rowCount: Int get() = body.size
}

/* ── ShapeToCursor — the fundamental projection operator ────────────── */
typealias ShapeToCursor = (Shape) -> Series<RowVec>

/* ── ShapeSchema — dimension contract ──────────────────────────────── */
object ShapeSchema {
    /** Validate: dimensions must be distinct and >= 2. */
    fun validate(shape: Shape): Boolean =
        shape.distinct().size == shape.size && shape.size >= 2

    /** Key column count (excluding date axes and metrics).
     *  Date axes are always last 2 elements; metrics are the remaining columns.
     */
    fun keyCount(shape: Shape): Int = shape.size - 9  // 7 metrics + 2 date = 9 non-key cols

    /** Date axis ordinals given shape.
     *  Date axes are [reading_date, interval] — always the LAST 2 elements.
     */
    fun dateAxes(shape: Shape): IntArray = intArrayOf(
        shape[shape.size - 2],  // reading_date (COL 6)
        shape[shape.size - 1],  // interval (COL 7)
    )

    /** Canonical cascade shapes — one per eigenspace level.
     *  These are the 5 eigenvector bases for CRMS spectral decomposition.
     *  Ordinals from Readings.kt column layout.
     */
    object Cascade {
        // Level 1: byEntity   — [entity, metric_0..metric_6]   ordinal [4,8,9,10,11,12,13,14]
        val byEntity   = intArrayOf(4, 8, 9, 10, 11, 12, 13, 14)
        // Level 2: byGroup3   — [group_3, entity, metric_0..metric_6]  ordinal [3,4,8,9,10,11,12,13,14]
        val byGroup3   = intArrayOf(3, 4, 8, 9, 10, 11, 12, 13, 14)
        // Level 3: byGroup2   — [group_2, entity, metric_0..metric_6]  ordinal [2,4,8,9,10,11,12,13,14]
        val byGroup2   = intArrayOf(2, 4, 8, 9, 10, 11, 12, 13, 14)
        // Level 4: byGroup1   — [group_1, entity, metric_0..metric_6]  ordinal [1,4,8,9,10,11,12,13,14]
        val byGroup1   = intArrayOf(1, 4, 8, 9, 10, 11, 12, 13, 14)
        // Level 5: byGroup0   — [group_0, entity, metric_0..metric_6]  ordinal [0,4,8,9,10,11,12,13,14]
        val byGroup0   = intArrayOf(0, 4, 8, 9, 10, 11, 12, 13, 14)

        val all = listOf(byGroup0, byGroup1, byGroup2, byGroup3, byEntity)
    }
}

/* ── Blackboard ─────────────────────────────────────────────────────── */
open class Blackboard {
    private val boxes = mutableMapOf<String, ShapeCursorBox>()

    fun register(shape: Shape): ShapeCursorBox {
        val key = shape.contentToString()
        val box = ShapeCursorBox(handle = shape)
        boxes[key] = box
        return box
    }

    fun fetch(shape: Shape): ShapeCursor? =
        boxes[shape.contentToString()]?.reify()

    fun ingest(shape: Shape, rows: Series<RowVec>) {
        boxes[shape.contentToString()]?.ingest(rows)
    }

    val shapes: List<Shape> get() = boxes.values.map { it.handle }

    fun allShapes(): List<Shape> = shapes

    /** Eigensolve across all registered shapes for a given CRMS phase.
     *  Uses wireproto Payloads for Python-based eigenvector computations.
     */
    fun eigensolve(phase: CrmsPhase): Map<String, CrmsEigenResult> {
        poolInit
        // System.err.println("[blackboard.eigensolve] phase=$phase boxes=${boxes.size}")
        return boxes.entries.associate { (key, box) ->
            val sc = box.reify()
            // System.err.println("[blackboard.eigensolve] key=$key shape=${sc.shape.contentToString()} cursor.a=${sc.cursor.a}")
            val eigensolver = CrmsEigensolver()
            val result = when (phase) {
                CrmsPhase.GAP     -> eigensolver.eigensolve(sc, CrmsPhase.GAP)
                CrmsPhase.KMEANS  -> eigensolver.eigensolve(sc, CrmsPhase.KMEANS)
                CrmsPhase.QUORUM  -> eigensolver.eigensolve(sc, CrmsPhase.QUORUM)
                else              -> emptyResult()
            }
            key to result
        }
    }

    fun isRegistered(shape: Shape): Boolean =
        boxes[shape.contentToString()] != null
}

/* ── EigenResult — eigenvector decomposition result ─────────────────── */
data class EigenResult(
    val eigenvalues: List<Double> = emptyList(),
    val eigenvectors: List<DoubleArray> = emptyList(),
    val dominant: Int = -1,                      // index of dominant eigenvector
    val gap: Double = 0.0,                      // spectral gap
    val clusters: Map<Int, List<Int>> = emptyMap(),   // clusterId → row indices
    val centroid: Map<Int, DoubleArray> = emptyMap(),  // clusterId → centroid vector
)

/* ── Stub eigensolvers — wired to PyEngine via Payloads ────────────── */
fun eigenvectorCorrelation(cursor: Series<RowVec>): CrmsEigenResult = CrmsEigenResult(0f, 0f, floatArrayOf(), -1)
fun kMeansCluster(cursor: Series<RowVec>, shape: Shape): CrmsEigenResult = CrmsEigenResult(0f, 0f, floatArrayOf(), -1)
fun dominantEigenvector(cursor: Series<RowVec>): CrmsEigenResult = CrmsEigenResult(0f, 0f, floatArrayOf(), -1)
fun gapEigen(cursor: Series<RowVec>, shape: Shape): CrmsEigenResult = CrmsEigenResult(0f, 0f, floatArrayOf(), -1)
private fun emptyResult(): CrmsEigenResult = CrmsEigenResult(0f, 0f, floatArrayOf(), -1)
