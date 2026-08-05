package borg.trikeshed.cas

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.toSeries
import borg.trikeshed.lib.emptySeries
import borg.trikeshed.lib.forEach
import borg.trikeshed.collections.associative.FunnelHashIndex

data class LineLocation(val documentId: Int, val lineIndex: Int)

class LineCasIndex {
    private val hexToLocations = mutableMapOf<String, MutableList<LineLocation>>()
    private var docCount = 0
    private var funnel: FunnelHashIndex<String>? = null
    private var funnelSize = 0

    val documentCount: Int get() = docCount

    fun ingest(text: String) {
        val lines = text.split("\n")
        val docId = docCount++
        for ((lineIndex, line) in lines.withIndex()) {
            val cid = ContentId.of(line.encodeToByteArray())
            hexToLocations.getOrPut(cid.hex) { mutableListOf() }.add(LineLocation(docId, lineIndex))
        }
    }

    fun ingestSpine(spine: Series<ContentId>) {
        val docId = docCount++
        var lineIndex = 0
        spine.forEach { cid ->
            hexToLocations.getOrPut(cid.hex) { mutableListOf() }.add(LineLocation(docId, lineIndex))
            lineIndex++
        }
    }

    fun ingestSpine(spine: CasManifest) {
        val docId = docCount++
        for ((lineIndex, cid) in spine.cids.withIndex()) {
            hexToLocations.getOrPut(cid.hex) { mutableListOf() }.add(LineLocation(docId, lineIndex))
        }
    }

    fun linkMatch(probe: String, minGrade: Double): Series<LineLocation> {
        val probeCid = ContentId.of(probe.encodeToByteArray())
        val hex = probeCid.hex

        if (funnel == null || funnelSize != hexToLocations.size) {
            funnel = FunnelHashIndex.build(hexToLocations.keys.toList(), 0L)
            funnelSize = hexToLocations.size
        }

        if (funnel?.contains(hex) == false) {
            return emptySeries()
        }

        val matches = hexToLocations[hex] ?: return emptySeries()
        return matches.toSeries()
    }
}
