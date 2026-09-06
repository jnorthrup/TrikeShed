package borg.trikeshed.lcnc

import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.dag.FactId
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.job.ContentId

/**
 * WHAT A RUN READ, AS FACTS (Forge genesis, Cut S): every project document a completed run
 * consumed becomes one fact in that PROJECT'S partition, beside the document facts the
 * project's changes tendon keeps there, so `run-stale` can compare the cid the run read with
 * the cid the document has now inside one partition (a production only sees the partition
 * it is evaluated for). The listing a run read becomes one fact too, so an added or removed
 * file is a staleness of its own. Local ids live under `lcnc/consumed/` and never collide
 * with a document id; the assert-once, modify-after discipline is the tendon's.
 */
class LcncRunFacts(private val rete: ReteNetwork) {
    private val known = HashMap<String, LinkedHashSet<FactId>>()

    companion object {
        const val KIND = "lcnc-consumed"
        const val LOCAL_PREFIX = "lcnc/consumed/"
        const val INDEX_DOC = "_index"

        fun localId(runId: String, docId: String): String = LOCAL_PREFIX + runId + "/" + docId.ifEmpty { INDEX_DOC }
    }

    /** Asserts (or re-asserts) the facts for a completed receipt; anything else is ignored. */
    suspend fun assertRun(receipt: Map<String, Any?>): Int {
        if (receipt["status"]?.toString() != "completed") return 0
        val runId = receipt["runId"]?.toString() ?: return 0
        val consumed = receipt["consumed"] as? List<*> ?: return 0
        val receiptKey = receipt["jobId"]?.toString() ?: ("lcnc/run/" + runId)
        val receiptCid = receipt["receiptCid"]?.toString().orEmpty()
        val programKey = receipt["programKey"]?.toString().orEmpty()
        val programCid = receipt["programCid"]?.toString().orEmpty()
        val ids = known.getOrPut(runId) { LinkedHashSet() }
        var n = 0
        for (row in consumed) {
            val m = row as? Map<*, *> ?: continue
            val kind = m["kind"]?.toString()
            if (kind != LcncConsumedLedger.PROJECT && kind != LcncConsumedLedger.PROJECT_INDEX) continue
            val id = m["id"]?.toString() ?: continue
            val project = id.substringBefore('/')
            val docId = id.substringAfter('/', "")
            if (project.isEmpty()) continue
            val cid = m["cid"]?.toString().orEmpty()
            val factId = FactId(project, localId(runId, docId))
            val fields = linkedMapOf<String, Any?>(
                "kind" to KIND, "inputKind" to kind, "runId" to runId, "receiptKey" to receiptKey, "receiptCid" to receiptCid,
                "programKey" to programKey, "programCid" to programCid, "project" to project, "docId" to docId, "cid" to cid,
                "sequence" to (m["sequence"] as? Number)?.toLong(), "prefix" to m["prefix"]?.toString().orEmpty(), "glob" to m["glob"]?.toString().orEmpty(),
            )
            val version = ContentId.of((receiptCid + "|" + factId.localId + "|" + cid).encodeToByteArray())
            if (ids.add(factId)) rete.assert(factId, fields, version, BlackboardContext(project)) else rete.modify(factId, fields, version)
            n++
        }
        return n
    }

    /** A rebuilt (or forgotten) run takes its facts out of working memory with proper retraction. */
    suspend fun retractRun(runId: String): Int {
        val ids = known.remove(runId) ?: return 0
        for (id in ids) rete.retract(id)
        return ids.size
    }

    suspend fun retractAll() {
        for (runId in known.keys.toList()) retractRun(runId)
    }

    fun runs(): Set<String> = known.keys.toSet()
}
