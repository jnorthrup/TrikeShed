package borg.trikeshed.lcnc.rules

import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.dag.Activation
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProduction
import borg.trikeshed.dag.ReteStoredFact
import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.LcncConsumedLedger
import borg.trikeshed.lcnc.LcncRunFacts
import borg.trikeshed.lcnc.ProjectGlob
import borg.trikeshed.lcnc.ProjectNodes
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * RUN-STALE (Forge genesis, Cut S): in a project's partition, a consumed-document fact whose
 * recorded cid no longer equals the document fact's `contentId` (or whose document is gone)
 * fires once per (receipt, new cid): the support is the consumed fact and the document fact,
 * so refraction re-arms exactly when the document's version moves. A consumed LISTING fact
 * fires when the set of matching file ids moved (a file added or removed); an edited file is
 * its own document row, not a listing change. The module's production sink folds firings into
 * one `lcnc/stale/<runId>` marker.
 */
class RunStaleProduction : ReteProduction {
    companion object {
        const val RULE = "run-stale"
        const val MAX_SUPPORT = 512
        private val VERSION = ContentId.of("rule-run-stale-v1".encodeToByteArray())
    }

    override val ruleId: String = RULE
    override val salience: Int = 30
    override val interests: Series<Join<String, Any?>> = 1 j { _: Int -> "kind" j (LcncRunFacts.KIND as Any?) }

    override fun evaluate(net: ReteNetwork, partitionId: String, fire: (Activation) -> Unit) {
        val consumed = net.workingMemory.query(BlackboardContext(partitionId), "kind" to LcncRunFacts.KIND)
        if (consumed.isEmpty()) return
        // The partition's documents: the tendon's facts carry `_id`; ours never do.
        val docs = net.workingMemory.all().filter { it.factId.partitionId == partitionId && it.fields.containsKey("_id") && !it.factId.localId.startsWith(LcncRunFacts.LOCAL_PREFIX) }
        val byId = HashMap<String, ReteStoredFact>(docs.size)
        for (d in docs) byId[d.factId.localId] = d
        for (c in consumed) {
            val runId = c.fields["runId"] as? String ?: continue
            val common = mapOf(
                "runId" to runId,
                "receiptKey" to c.fields["receiptKey"]?.toString().orEmpty(),
                "receiptCid" to c.fields["receiptCid"]?.toString().orEmpty(),
                "programKey" to c.fields["programKey"]?.toString().orEmpty(),
                "programCid" to c.fields["programCid"]?.toString().orEmpty(),
                "project" to partitionId,
            )
            val oldCid = c.fields["cid"]?.toString().orEmpty()
            when (c.fields["inputKind"]) {
                LcncConsumedLedger.PROJECT -> {
                    val docId = c.fields["docId"] as? String ?: continue
                    val doc = byId[docId]
                    val newCid = doc?.fields?.get("contentId")?.toString()
                    if (newCid == oldCid) continue
                    fire(
                        Activation(
                            activationId = "run-stale-$runId-$docId-${newCid ?: "deleted"}",
                            ruleId = ruleId,
                            ruleVersionCid = VERSION,
                            salience = salience,
                            sequence = (doc?.fields?.get("_seq") as? Number)?.toLong() ?: 0L,
                            supportCids = listOfNotNull(c.versionCid, doc?.versionCid),
                            bindings = common + mapOf(
                                "kind" to LcncConsumedLedger.PROJECT, "id" to docId, "oldCid" to oldCid, "newCid" to newCid.orEmpty(),
                                "sequence" to ((doc?.fields?.get("_seq") as? Number)?.toLong()?.toString() ?: ""), "deleted" to (doc == null).toString(),
                            ),
                        ),
                    )
                }
                LcncConsumedLedger.PROJECT_INDEX -> {
                    val prefix = c.fields["prefix"]?.toString().orEmpty()
                    val glob = c.fields["glob"]?.toString().orEmpty()
                    val listing = docs.filter { d ->
                        val id = d.factId.localId
                        d.fields["contentId"] != null && !id.endsWith(ProjectNodes.EXTRACT_SUFFIX) && id.startsWith(prefix) && ProjectGlob.matches(glob, id)
                    }.sortedBy { it.factId.localId }
                    val fingerprint = LcncConsumedLedger.indexFingerprintOf(listing.map { it.factId.localId })
                    if (fingerprint == oldCid) continue
                    fire(
                        Activation(
                            activationId = "run-stale-$runId-index-${fingerprint.takeLast(12)}",
                            ruleId = ruleId,
                            ruleVersionCid = VERSION,
                            salience = salience,
                            sequence = listing.size.toLong(),
                            supportCids = listOf(c.versionCid) + listing.take(MAX_SUPPORT).map { it.versionCid },
                            bindings = common + mapOf(
                                "kind" to LcncConsumedLedger.PROJECT_INDEX, "id" to "", "oldCid" to oldCid, "newCid" to fingerprint,
                                "sequence" to "", "deleted" to "false", "files" to listing.size.toString(),
                            ),
                        ),
                    )
                }
            }
        }
    }
}
