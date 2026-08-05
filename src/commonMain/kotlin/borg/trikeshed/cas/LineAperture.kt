package borg.trikeshed.cas

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.size
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j

/**
 * RTS mapping: coarser aperture = zoomed out.
 * Defines zoom levels mapping to CID hex prefix lengths.
 */
enum class Aperture(val prefixHex: Int) {
    L0(2),
    L1(4),
    L2(8),
    L3(64)
}

fun bucketKey(cid: ContentId, aperture: Aperture): String {
    return cid.hex.take(aperture.prefixHex)
}

fun regionDensities(spine: Series<ContentId>, aperture: Aperture): Series<Join<String, Int>> {
    val counts = mutableMapOf<String, Int>()
    for (i in 0 until spine.size) {
        val key = bucketKey(spine[i], aperture)
        counts[key] = (counts[key] ?: 0) + 1
    }
    val list = counts.entries.toList()
    return list.size j { i -> list[i].key j list[i].value }
}

fun topK(regions: Series<Join<String, Int>>, k: Int): Series<Join<String, Int>> {
    val list = mutableListOf<Join<String, Int>>()
    for (i in 0 until regions.size) {
        list.add(regions[i])
    }
    list.sortByDescending { it.b }
    val resultList = list.take(k)
    return resultList.size j { i -> resultList[i] }
}
