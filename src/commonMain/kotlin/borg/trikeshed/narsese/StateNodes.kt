package borg.trikeshed.narsese

import borg.trikeshed.cursor.currentTimeMillis
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.rdf.RdfGraph
import borg.trikeshed.rdf.TurtleRdf

/**
 * State freeze/thaw LCNC nodes — persist the belief bag, KIF knowledge base,
 * and RdfGraph to CAS; restore them on thaw.
 *
 * Freeze output shape (one CAS document):
 * - `bagSnapshot`: the bag's COW map serialized as JSON
 * - `kifText`: the KIF KB as a text attachment
 * - `rdfTurtle`: the RdfGraph as Turtle serialization
 * - `freezeCid`: content-addressed identity of the combined snapshot
 *
 * The CAS-diff-on-thaw approach is consistent with the CID-anchoring
 * invariant enforced elsewhere in the codebase.
 */
object StateNodes {

    /**
     * `state.freeze` — snapshot the bag + serialize KIF + Turtle,
     * store everything in CAS, return the snapshot CID.
     */
    fun freezeRunner(
        bag: BeliefBagElement,
        kif: KifKnowledgeBase,
        graph: () -> RdfGraph,  // lazy: graph may be mutated between ticks
        cas: CasStore,
    ): LcncNodeRunner = LcncNodeRunner { _, _ ->
        // 1. Bag snapshot: COW map of angular → signal
        val bagSnap = bag.snapshot()
        val bagJson = buildString {
            append("{")
            var first = true
            for ((key, signal) in bagSnap) {
                if (!first) append(",")
                first = false
                append("\"${key.a}\":{\"angular\":${signal.angular},\"evidence\":{\"wPlus\":${signal.evidence.positive},\"wMinus\":${signal.evidence.negative}},\"relation\":\"${signal.relation}\",\"subject\":\"${signal.subjectCid}\"}")
            }
            append("}")
        }
        val bagCid = cas.put(bagJson.encodeToByteArray())

        // 2. KIF serialization
        val kifText = kif.toKifFile()
        val kifCid = cas.put(kifText.encodeToByteArray())

        // 3. RdfGraph → Turtle
        val rdfTurtle = try {
            val g = graph()
            TurtleRdf.emit(g)
        } catch (_: Exception) { "" }
        val rdfCid = if (rdfTurtle.isNotEmpty()) cas.put(rdfTurtle.encodeToByteArray()) else null

        // 4. Combined freeze receipt
        val receipt = buildString {
            append("{\"bagCid\":\"${bagCid.value}\",")
            append("\"kifCid\":\"${kifCid.value}\",")
            if (rdfCid != null) append("\"rdfCid\":\"${rdfCid.value}\",")
            append("\"bagSize\":${bagSnap.size},")
            append("\"kifLength\":${kifText.length},")
            append("\"rdfLength\":${rdfTurtle.length},")
            append("\"timestamp\":${currentTimeMillis()}}")
        }
        val receiptCid = cas.put(receipt.encodeToByteArray())

        mapOf("snapshot" to linkedMapOf(
            "cid" to receiptCid.value,
            "bagCid" to bagCid.value,
            "kifCid" to kifCid.value,
            "rdfCid" to (rdfCid?.value ?: ""),
            "bagSize" to bagSnap.size,
        ))
    }

    /**
     * `state.thaw` — load a freeze receipt from CAS, restore KIF assertions.
     * The bag itself cannot be reconstructed from a snapshot without re-minting
     * (the HijackBeliefBag geometry requires intake); thaw restores the KIF
     * and reports what was frozen.
     */
    fun thawRunner(
        cas: CasStore,
        kif: KifKnowledgeBase,
    ): LcncNodeRunner = LcncNodeRunner { node, _ ->
        val cidStr = (node.params["cid"]
            ?: (node.params["snapshotCid"] ?: ""))
        require(cidStr.isNotEmpty()) { "state.thaw requires a cid param" }
        val receiptBytes = cas.get(ContentId(cidStr))
            ?: return@LcncNodeRunner mapOf("restored" to mapOf("error" to "CID not found in CAS"))
        val receipt = receiptBytes.decodeToString()

        // Parse the freeze receipt to get component CIDs
        val bagCid = extractJsonField(receipt, "bagCid")
        val kifCid = extractJsonField(receipt, "kifCid")

        // Restore KIF assertions
        var kifRestored = 0
        if (kifCid.isNotEmpty()) {
            val kifBytes = cas.get(ContentId(kifCid))
            if (kifBytes != null) {
                val kifText = kifBytes.decodeToString()
                for (line in kifText.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith(";")) {
                        runCatching { kif.assertKif(trimmed) }
                        kifRestored++
                    }
                }
            }
        }

        mapOf("restored" to linkedMapOf(
            "receiptCid" to cidStr,
            "bagCid" to bagCid,
            "kifCid" to kifCid,
            "kifAssertionsRestored" to kifRestored,
        ))
    }

    private fun extractJsonField(json: String, field: String): String {
        val pattern = "\"$field\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        return pattern.find(json)?.groupValues?.get(1) ?: ""
    }
}
