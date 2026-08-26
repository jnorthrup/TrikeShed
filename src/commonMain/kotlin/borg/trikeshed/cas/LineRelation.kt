package borg.trikeshed.cas

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.get
import borg.trikeshed.collections.associative.LinearHashMap

enum class RelationKind {
    NEIGHBOR_PREV,
    NEIGHBOR_NEXT,
    SAME_CONTENT,
    LINKED_CONTEXT,
    ATTRACTION,
    CAUSALITY,
    CUSTOM
}

data class LineEdge(
    val fromOrdinal: Int,
    val toOrdinal: Int,
    val kind: RelationKind,
    val confidence: Double? = null
)

val <T> Series<T>.size get() = a

fun neighborEdges(spine: Series<*>): Series<LineEdge> {
    val count = maxOf(0, spine.size - 1)
    return count j { idx: Int ->
        LineEdge(
            fromOrdinal = idx,
            toOrdinal = idx + 1,
            kind = RelationKind.NEIGHBOR_NEXT
        )
    }
}

fun groupByContent(spine: Series<ContentId>): Series<Join<ContentId, Series<Int>>> {
    val map = LinearHashMap<ContentId, MutableList<Int>>()
    for (i in 0 until spine.size) {
        val cid = spine[i]
        var list = map[cid]
        if (list == null) {
            list = ArrayList()
            map[cid] = list
        }
        list.add(i)
    }

    val entriesList = map.entries()
    return entriesList.size j { i: Int ->
        val entry = entriesList[i]
        val list = entry.b
        entry.a j (list.size j { idx -> list[idx] })
    }
}

fun edgesOf(spine: Series<*>, kind: RelationKind, pairs: Series<Join<Int, Int>>): Series<LineEdge> {
    return pairs.size j { i: Int ->
        val pair = pairs[i]
        LineEdge(
            fromOrdinal = pair.a,
            toOrdinal = pair.b,
            kind = kind
        )
    }
}
