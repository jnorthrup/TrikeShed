package borg.trikeshed.og1

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.og1.cron.CrmsCron
import borg.trikeshed.og1.shape.Blackboard
import borg.trikeshed.og1.shape.CrmsEigenResult
import borg.trikeshed.og1.shape.CrmsEigensolver
import borg.trikeshed.og1.shape.RingSeries
import borg.trikeshed.og1.shape.RealtimePipeline
import borg.trikeshed.og1.shape.Shape
import borg.trikeshed.og1.shape.ShapeSchema
import borg.trikeshed.og1.state.CrmsPhase
import borg.trikeshed.og1.state.CrmsState
import borg.trikeshed.og1.voter.FacetVoterPanel

/** DataMeta = () -> ColumnMeta — column metadata supplier per index. */
private class DataMeta(private val col: Int) : () -> ColumnMeta {
    override fun invoke(): ColumnMeta = ColumnMeta("col_$col", IOMemento.IoLong, null)
}

/** RowVec = Series<Join<Any?, () -> ColumnMeta>> — built via explicit object. */
@Suppress("UNCHECKED_CAST")
private fun rowVecOf(data: LongArray): RowVec =
    object : Series<Join<Any?, () -> ColumnMeta>> {
        // Series<T> = MetaSeries<Int, T> = Join<Int, (Int) -> T>
        // a = size (Int), b = index oracle (Int) -> T
        override val a: Int get() = data.size
        override val b: (Int) -> Join<Any?, () -> ColumnMeta>
            get() = { col ->
                object : Join<Any?, () -> ColumnMeta> {
                    override val a: Any? get() = data[col]
                    override val b: () -> ColumnMeta get() = DataMeta(col)
                }
            }
    } as RowVec

fun main(args: Array<String>) {
    val iterations = args.find { it.startsWith("--iterations=") }?.substringAfter("=")?.toIntOrNull() ?: 10
    val phaseArg   = args.find { it.startsWith("--phase=") }?.substringAfter("=")?.uppercase()
    val phase      = phaseArg?.let { CrmsPhase.valueOf(it) } ?: CrmsPhase.BRAINSTORM
    val tenant     = args.find { it.startsWith("--tenant=") }?.substringAfter("=") ?: "og1"

    val startMs = System.currentTimeMillis()

    // ── Pipeline bootstrap ────────────────────────────────────────────────

    val cron = CrmsCron()
    val eig  = CrmsEigensolver()
    val board = Blackboard()

    ShapeSchema.Cascade.all.forEach { shape -> board.register(shape) }

    val stateHolder = mutableListOf<CrmsState>()
    val ring = RingSeries<RowVec>(capacity = 4096)
    val pipeline = RealtimePipeline(cron, eig, board, ring) { state ->
        stateHolder.add(state)
    }

    val targetIdx = CrmsPhase.entries.indexOf(phase).coerceAtLeast(0)
    repeat(targetIdx) { pipeline.tick() }

    seedMock(ring, count = 20)

    // ── Tick loop ────────────────────────────────────────────────────────

    println("""{"og1":"start","tenant":"$tenant","phase":"${cron.currentPhase()}","iterations":$iterations}""")

    repeat(iterations) { i ->
        val tickMs = System.currentTimeMillis()

        val state: CrmsState = pipeline.tick()
        val eigenResults: Map<Shape, CrmsEigenResult> = pipeline.eigensolve()

        val clusterMap: Map<Shape, IntArray> = eigenResults.mapValues { (_, r) ->
            r.clusterOf ?: intArrayOf()
        }

        val voterPanel = FacetVoterPanel(board)
        val verdict = voterPanel.vote()

        val bestEntry = eigenResults.maxByOrNull { it.value.eigenvalue }
        val bestShape  = bestEntry?.key
        val bestEigen  = bestEntry?.value

        val clusterCardsJson = buildClusterCards(clusterMap, eigenResults, tenant)
        val elapsed = System.currentTimeMillis() - tickMs

        println(buildString {
            append("""{"tick":$i,""")
            append(""""phase":"${state.phase}",""")
            append(""""ringSize":${ring.size},""")
            append(""""bestShape":${bestShape?.contentToString() ?: "null"},""")
            append(""""eigenvalue":${bestEigen?.eigenvalue ?: 0f},""")
            append(""""gap":${bestEigen?.gap ?: 0f},""")
            append(""""nClusters":${clusterMap.size},""")
            append(""""quorumWinner":${verdict.winner},""")
            append(""""quorumConfidence":${verdict.quorumConfidence},""")
            append(""""elapsedMs":$elapsed,""")
            append(""""clusterCards":$clusterCardsJson}""")
        })

        seedMock(ring, count = 3)
    }

    val totalMs = System.currentTimeMillis() - startMs
    println("""{"og1":"done","totalMs":$totalMs}""")
}

/* ── Cluster cards from k-means output ───────────────────────────────── */

data class ClusterCard(
    val clusterId: Int,
    val eigenvalue: Float,
    val gap: Float,
    val memberCount: Int,
    val shapeKey: String,
    val tenant: String,
) {
    fun title(): String = "og1:cluster-$clusterId @ eigenvalue=$eigenvalue"
    fun body(): String = buildString {
        append("cluster_id: $clusterId\n")
        append("eigenvalue: $eigenvalue  gap: $gap\n")
        append("members: $memberCount\n")
        append("shape_key: $shapeKey\n")
        append("tenant: $tenant\n")
        append("source: k-means-seated voter panel\n")
    }
}

private fun buildClusterCards(
    clusterMap: Map<Shape, IntArray>,
    eigenResults: Map<Shape, CrmsEigenResult>,
    tenant: String,
): String {
    val cards = mutableListOf<ClusterCard>()

    for ((shape, clusters) in clusterMap) {
        if (clusters.isEmpty()) continue
        val eigen = eigenResults[shape] ?: continue
        val shapeKey = shape.contentToString()
        val uniqueIds = clusters.toSet()

        for (clusterId in uniqueIds) {
            val count = clusters.count { it == clusterId }
            cards.add(ClusterCard(
                clusterId   = clusterId,
                eigenvalue  = eigen.eigenvalue,
                gap         = eigen.gap,
                memberCount = count,
                shapeKey    = shapeKey,
                tenant      = tenant,
            ))
        }
    }

    return cards.joinToString(",", "[", "]") { c ->
        buildString {
            append("""{"clusterId":${c.clusterId},""")
            append(""""eigenvalue":${c.eigenvalue},""")
            append(""""gap":${c.gap},""")
            append(""""memberCount":${c.memberCount},""")
            append(""""shapeKey":${c.shapeKey},""")
            append(""""title":${jsonStr(c.title())},""")
            append(""""body":${jsonStr(c.body())}}""")
        }
    }
}

/* ── Mock data seeding ──────────────────────────────────────────────── */

private fun seedMock(ring: RingSeries<RowVec>, count: Int) {
    val tick = (System.currentTimeMillis() / 1000).toInt()
    repeat(count) { sub ->
        val data = longArrayOf(
            (tick * 100 + sub).toLong(),
            1L, 2L, 3L, 4L,
            2026L, 5L, 30L, 12L, 0L,
            (tick * 10 + sub).toLong(),
            (tick * 5  + sub).toLong(),
            (tick * 3  + sub).toLong(),
            (tick * 2  + sub).toLong(),
            (sub * 7).toLong(),
        )
        ring.append(rowVecOf(data))
    }
}

/* ── JSON helpers ───────────────────────────────────────────────────── */

private fun jsonStr(s: String): String = s
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")