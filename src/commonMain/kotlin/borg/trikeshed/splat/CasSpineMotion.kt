package borg.trikeshed.splat

import borg.trikeshed.cas.LineCas
import borg.trikeshed.cas.LineNode
import borg.trikeshed.cas.LineSpine
import borg.trikeshed.cas.MatchGrade
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Tensor
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import borg.trikeshed.lib.α
import kotlin.math.abs

/** One observed content-addressed line displacement between adjacent spine generations. */
data class SpineMotion(
    val contentCid: ContentId,
    val fromOrdinal: Int,
    val toOrdinal: Int,
    val grade: MatchGrade,
) {
    val velocity: Int get() = toOrdinal - fromOrdinal
}

data class CasSpineIteration(
    val previousCid: ContentId,
    val currentCid: ContentId,
    val nextCid: ContentId,
    val motion: Splat<SpineMotion>,
    val eigenSignature: Tensor<Double>,
    val next: LineSpine,
)

/**
 * The recovered n-dimensional splat toy applied to LineCas: infer ordinal velocity by content/link
 * identity, extrapolate one generation, then rebuild neighbor stamps from CAS-resident line bytes.
 */
object CasSpineMotion {
    fun motion(previous: LineSpine, current: LineSpine): Splat<SpineMotion> {
        val previousByContent = index(previous)
        val motions = ArrayList<SpineMotion>(current.size)
        var totalStrength = 0.0
        for (node in current.view) {
            val before = nearest(previousByContent[node.contentCid.hex], node.ordinal) ?: continue
            val grade = LineCas.matchGrade(before, node) ?: MatchGrade.CONTENT_ONLY
            motions += SpineMotion(node.contentCid, before.ordinal, node.ordinal, grade)
            totalStrength += grade.strength.toDouble()
        }
        if (motions.isEmpty()) return 0 j { _: Int -> error("empty motion splat") }
        return motions.size j { i: Int -> motions[i] j (motions[i].grade.strength / totalStrength) }
    }

    fun advance(cas: CasStore, previous: LineSpine, current: LineSpine): LineSpine {
        val previousByContent = index(previous)
        data class Projected(val node: LineNode, val ordinal: Int)
        val projected = Array(current.size) { i: Int ->
            val node = current[i]
            val before = nearest(previousByContent[node.contentCid.hex], node.ordinal)
            val velocity = if (before == null) 0 else node.ordinal - before.ordinal
            Projected(node, node.ordinal + velocity)
        }
        projected.sortWith(compareBy<Projected> { it.ordinal }.thenBy { it.node.ordinal }.thenBy { it.node.contentCid.hex })
        val lines: Series<String> = projected α { projection ->
            cas.get(projection.node.contentCid)?.decodeToString()
                ?: error("CAS line missing for ${projection.node.contentCid.hex}")
        }
        return LineCas.ingestLines(cas, lines)
    }

    fun iterate(cas: CasStore, previous: LineSpine, current: LineSpine): CasSpineIteration {
        val motion = motion(previous, current)
        val next = advance(cas, previous, current)
        return CasSpineIteration(
            previousCid = LineCas.spineCid(previous),
            currentCid = LineCas.spineCid(current),
            nextCid = LineCas.spineCid(next),
            motion = motion,
            eigenSignature = PowerIterationEigenFinder<SpineMotion>().extractSignature(motion),
            next = next,
        )
    }

    /** Dense N-dimensional line features for Gaussian models: position, links, then CID bytes. */
    fun features(node: LineNode, spineSize: Int, dimensions: Int = 8): Series<Double> {
        require(dimensions >= 3)
        val denominator = (spineSize - 1).coerceAtLeast(1).toDouble()
        return dimensions j { axis: Int ->
            when (axis) {
                0 -> node.ordinal / denominator
                1 -> node.prevHex.toInt(16) / 255.0
                2 -> node.nextHex.toInt(16) / 255.0
                else -> {
                    val byteOffset = ((axis - 3) * 2) % node.contentCid.hex.length
                    node.contentCid.hex.substring(byteOffset, byteOffset + 2).toInt(16) / 255.0
                }
            }
        }
    }

    private fun index(spine: LineSpine): Map<String, List<LineNode>> {
        val index = linkedMapOf<String, MutableList<LineNode>>()
        for (node in spine.view) index.getOrPut(node.contentCid.hex) { mutableListOf() } += node
        return index
    }

    private fun nearest(candidates: List<LineNode>?, ordinal: Int): LineNode? {
        var best: LineNode? = null
        var distance = Int.MAX_VALUE
        for (candidate in candidates.orEmpty()) {
            val nextDistance = abs(candidate.ordinal - ordinal)
            if (nextDistance < distance) {
                best = candidate
                distance = nextDistance
            }
        }
        return best
    }
}
