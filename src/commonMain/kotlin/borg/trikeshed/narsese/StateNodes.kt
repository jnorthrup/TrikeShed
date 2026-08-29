package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
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
 * - `bagSnapshot`: the bag's COW map serialized as JSON (budget rides each row)
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
                append("\"${key.a}\":{\"angular\":${signal.angular},\"evidence\":{\"wPlus\":${signal.evidence.positive},\"wMinus\":${signal.evidence.negative}},\"relation\":\"${signal.relation}\",\"subject\":\"${signal.subjectCid}\",\"budget\":${key.b}}")
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
     * `state.thaw` — load a freeze receipt from CAS, restore KIF assertions,
     * and repopulate the bag by re-minting every snapshot row through intake
     * (the HijackBeliefBag geometry requires intake; direct map assignment
     * would bypass revision and the WAL).
     */
    fun thawRunner(
        bag: BeliefBagElement,
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

        // Restore the bag: decode each frozen row, re-mint at its frozen budget
        var bagRestored = 0
        if (bagCid.isNotEmpty()) {
            val bagBytes = cas.get(ContentId(bagCid))
            if (bagBytes != null) {
                for (row in BAG_ROW.findAll(bagBytes.decodeToString())) {
                    val gv = row.groupValues
                    val angular = gv[1].toLongOrNull() ?: continue
                    val positive = gv[2].toLongOrNull() ?: continue
                    val negative = gv[3].toLongOrNull() ?: continue
                    val relation = RelationKind.entries.firstOrNull { it.name == gv[4] } ?: continue
                    val budget = gv[6].toLongOrNull()?.let { BudgetCoord(it) }
                        ?: BudgetCoord(0.5f, 0.5f, 0.5f) // pre-budget snapshots
                    val signal = SemanticSignal(
                        angular = angular,
                        evidence = EvidenceCoord(positive, negative),
                        relation = relation,
                        subjectCid = gv[5],
                    )
                    bag.intake.send(BeliefIntake.Mint(signal, budget))
                    bagRestored++
                }
            }
        }

        val kifRestored = restoreKif(cas, kif, kifCid)

        mapOf("restored" to linkedMapOf(
            "receiptCid" to cidStr,
            "bagCid" to bagCid,
            "kifCid" to kifCid,
            "bagRestored" to bagRestored,
            "kifAssertionsRestored" to kifRestored,
        ))
    }

    /** Compile-compat shim for the pre-bag registration; the daemon migrates off it. */
    @Deprecated(
        "restores KIF only — the bag stays empty; pass the bag",
        ReplaceWith("thawRunner(bag, cas, kif)"),
    )
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
        val bagCid = extractJsonField(receipt, "bagCid")
        val kifCid = extractJsonField(receipt, "kifCid")
        mapOf("restored" to linkedMapOf(
            "receiptCid" to cidStr,
            "bagCid" to bagCid,
            "kifCid" to kifCid,
            "bagRestored" to 0,
            "kifAssertionsRestored" to restoreKif(cas, kif, kifCid),
        ))
    }

    private fun restoreKif(cas: CasStore, kif: KifKnowledgeBase, kifCid: String): Int {
        if (kifCid.isEmpty()) return 0
        val kifBytes = cas.get(ContentId(kifCid)) ?: return 0
        var kifRestored = 0
        for (line in kifBytes.decodeToString().lines()) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith(";")) {
                runCatching { kif.assertKif(trimmed) }.onSuccess { kifRestored++ }
            }
        }
        return kifRestored
    }

    /** One freeze row as written above; `budget` optional for pre-budget snapshots. */
    private val BAG_ROW = (
        "\"angular\":(-?\\d+),\"evidence\":\\{\"wPlus\":(\\d+),\"wMinus\":(\\d+)\\}," +
            "\"relation\":\"([^\"]*)\",\"subject\":\"([^\"]*)\"(?:,\"budget\":(-?\\d+))?"
        ).toRegex()

    private fun extractJsonField(json: String, field: String): String {
        val pattern = "\"$field\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        return pattern.find(json)?.groupValues?.get(1) ?: ""
    }
}
