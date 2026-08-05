package borg.trikeshed.cas

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlin.jvm.JvmInline
import borg.trikeshed.lib.size
import borg.trikeshed.lib.get

enum class MatchGrade {
    CONTENT_ONLY,
    PARTIAL_PREV,
    PARTIAL_NEXT,
    LINKED;

    fun meets(other: MatchGrade): Boolean {
        if (this == LINKED) return true
        if (other == CONTENT_ONLY) return true
        return this == other
    }
}

object LineCas {
    const val NEIGHBOR_HEX_LEN = 2
    const val EDGE_HEX = "00"

    @JvmInline
    value class NeighborStamp(val hex: String) {
        init {
            require(hex.length == 4) { "NeighborStamp must be exactly 4 hex chars" }
            require(hex.all { it.isLowerCase() || it.isDigit() }) { "NeighborStamp must be lowercase hex" }
        }
    }

    data class LineNode(
        val contentCid: ContentId,
        val stamp: NeighborStamp,
        val ordinal: Int
    ) {
        val linkedKey: String get() = "${stamp.hex}:${contentCid.hex}"
    }

    private fun getPrefix(cid: ContentId?): String {
        return cid?.hex?.take(NEIGHBOR_HEX_LEN) ?: EDGE_HEX
    }

    fun spine(text: Series<ContentId>): Series<LineNode> {
        val sz = text.size
        if (sz == 0) return 0 j { _ -> throw IndexOutOfBoundsException() }

        return sz j { index: Int ->
            val prev = if (index > 0) text[index - 1] else null
            val next = if (index < sz - 1) text[index + 1] else null
            val stampHex = getPrefix(prev) + getPrefix(next)
            LineNode(text[index], NeighborStamp(stampHex), index)
        }
    }

    fun matchGrade(a: LineNode, b: LineNode): MatchGrade? {
        if (a.contentCid != b.contentCid) return null
        val prevMatch = a.stamp.hex.substring(0, NEIGHBOR_HEX_LEN) == b.stamp.hex.substring(0, NEIGHBOR_HEX_LEN)
        val nextMatch = a.stamp.hex.substring(NEIGHBOR_HEX_LEN) == b.stamp.hex.substring(NEIGHBOR_HEX_LEN)
        return when {
            prevMatch && nextMatch -> MatchGrade.LINKED
            prevMatch -> MatchGrade.PARTIAL_PREV
            nextMatch -> MatchGrade.PARTIAL_NEXT
            else -> MatchGrade.CONTENT_ONLY
        }
    }
}
