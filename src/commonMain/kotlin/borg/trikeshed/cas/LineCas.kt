package borg.trikeshed.cas

import borg.trikeshed.job.ContentId

enum class LineCasNeighbor {
    NEIGHBOR_PREV,
    NEIGHBOR_NEXT,
    NEIGHBOR_SIBLING,
    NEIGHBOR_PARENT,
    NEIGHBOR_CHILD,
    NEIGHBOR_UNKNOWN
}

data class LineCasEdge(
    val sourceCid: ContentId,
    val targetCid: ContentId,
    val relationship: LineCasNeighbor,
    val confidence: Double = 1.0
)

data class LineCasSpine(
    val contentCid: ContentId,
    val ordinal: Int,
    val linkedKey: String? = null
)
