package borg.trikeshed.cas

import borg.trikeshed.collections.associative.FunnelHashIndex
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.get
import borg.trikeshed.lib.α
import kotlin.jvm.JvmInline

/**
 * FunnelMergeReceipt captures the kept/dropped Series + counts
 * generated from merging a clustered topology of residuals.
 */
data class MergeReceipt(
    val kept: Series<GradedCluster>,
    val dropped: Series<GradedCluster>,
    val keptCount: Int,
    val droppedCount: Int
)

@JvmInline
value class Mini64(val value: Long)

data class CopyAddress(
    val sourceIdx: Int,
    val ordinal: Int,
    val neighborPrefix: String
)

data class Cluster(
    val mini64: Mini64,
    val contentCid: ContentId,
    val copies: Series<CopyAddress>
)

enum class Grade {
    INHERITED,
    NOVEL,
    INHERITED_CROSS,
    RELOCATED
}

data class GradedCluster(
    val cluster: Cluster,
    val grade: Grade
)

typealias ResidualSpine = Join<Int, Series<LineNode>>

/**
 * The 57-way funnel residual merge pipeline.
 *
 * Implements the full six-stage pipeline composable over LineSpines:
 * Stage 1: LineCas.spine
 * Stage 2: FunnelHashIndex.build
 * Stage 3: [LineCasIndex.residualsOf], [LineCasIndex.linkMatch], [LineCasIndex.residualDensity]
 * Stage 4: topologyOf
 * Stage 5: gradeClusters
 * Stage 6: mergeResiduals
 */
object FunnelResidualMerge {

    fun mini64Of(cid: ContentId): Mini64 {
        var h = -0x349b101655b38cbL // FNV offset basis
        val hex = cid.hex
        for (i in 0 until hex.length) {
            h = h xor hex[i].code.toLong()
            h = h * 0x100000001b3L // FNV prime
        }
        return Mini64(h)
    }

    /**
     * Stage 4: topologyOf
     */
    fun topologyOf(residuals: Series<ResidualSpine>): Series<Cluster> {
        val map = linkedMapOf<Long, MutableList<Pair<ContentId, CopyAddress>>>()

        for (i in 0 until residuals.size) {
            val sourceIdx = residuals[i].a
            val nodes = residuals[i].b
            for (j in 0 until nodes.size) {
                val node = nodes[j]
                val m64 = mini64Of(node.contentCid).value
                val copy = CopyAddress(
                    sourceIdx = sourceIdx,
                    ordinal = node.ordinal,
                    neighborPrefix = node.stamp.hex
                )
                map.getOrPut(m64) { mutableListOf() }.add(Pair(node.contentCid, copy))
            }
        }

        val entries = map.entries.toList()
        return entries.size j { i: Int ->
            val entry = entries[i]
            val copiesList = entry.value
            val firstCid = copiesList.first().first
            val copiesSeries = copiesList.size j { j: Int -> copiesList[j].second }
            Cluster(Mini64(entry.key), firstCid, copiesSeries)
        }
    }

    /**
     * Stage 5: gradeClusters
     */
    fun gradeClusters(topology: Series<Cluster>, masterFunnel: FunnelHashIndex<String>): Series<GradedCluster> {
        return topology.size j { i: Int ->
            val cluster = topology[i]
            val cidHex = cluster.contentCid.hex

            val grade = if (masterFunnel.contains(cidHex)) {
                Grade.INHERITED
            } else if (cluster.copies.size <= 1) {
                Grade.NOVEL
            } else {
                val firstPrefix = cluster.copies[0].neighborPrefix
                var allSame = true
                for (j in 1 until cluster.copies.size) {
                    if (cluster.copies[j].neighborPrefix != firstPrefix) {
                        allSame = false
                        break
                    }
                }

                if (allSame) {
                    Grade.INHERITED_CROSS
                } else {
                    Grade.RELOCATED
                }
            }

            GradedCluster(cluster, grade)
        }
    }

    /**
     * Stage 6: mergeResiduals
     */
    fun mergeResiduals(graded: Series<GradedCluster>): MergeReceipt {
        val keptList = mutableListOf<GradedCluster>()
        val droppedList = mutableListOf<GradedCluster>()

        for (i in 0 until graded.size) {
            val g = graded[i]
            if (g.grade == Grade.NOVEL || g.grade == Grade.RELOCATED) {
                keptList.add(g)
            } else {
                droppedList.add(g)
            }
        }

        val keptSeries = keptList.size j { i: Int -> keptList[i] }
        val droppedSeries = droppedList.size j { i: Int -> droppedList[i] }

        return MergeReceipt(keptSeries, droppedSeries, keptList.size, droppedList.size)
    }

    /**
     * The full six-stage pipeline composable.
     * Stage 1: LineCas.spine
     * Stage 2: FunnelHashIndex.build
     * Stage 3: [LineCasIndex.residualsOf], [LineCasIndex.linkMatch], [LineCasIndex.residualDensity]
     * Stage 4: [topologyOf]
     * Stage 5: [gradeClusters]
     * Stage 6: [mergeResiduals]
     */
    fun merge(sources: Series<LineSpine>, masterFunnel: FunnelHashIndex<String>): MergeReceipt {
        val index = LineCasIndex()
        index.funnel = masterFunnel

        val residuals = sources.size j { i: Int ->
            val spine = sources[i]
            val resNodes = index.residualsOf(spine)
            i j resNodes
        }

        val topology = topologyOf(residuals)
        val graded = gradeClusters(topology, masterFunnel)
        return mergeResiduals(graded)
    }

    fun buildMasterFunnel(keys: Series<String>, seed: Long): FunnelHashIndex<String> {
        return FunnelHashIndex.build(keys, seed)
    }

    fun buildMasterFunnelFromTexts(texts: Series<String>, seed: Long): FunnelHashIndex<String> {
        val flatKeysList = mutableListOf<String>()
        for (i in 0 until texts.size) {
            val spine = LineCas.spine(texts[i])
            for (j in 0 until spine.size) {
                flatKeysList.add(spine[j].contentCid.hex)
            }
        }
        val flatKeys = flatKeysList.size j { i: Int -> flatKeysList[i] }
        return FunnelHashIndex.build(flatKeys, seed)
    }
}
