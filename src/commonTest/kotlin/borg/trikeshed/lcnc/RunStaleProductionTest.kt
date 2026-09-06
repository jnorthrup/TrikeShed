package borg.trikeshed.lcnc

import borg.trikeshed.couch.CouchChangesFactElement
import borg.trikeshed.couch.CouchDatabase
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.dag.Activation
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.job.CasStore
import borg.trikeshed.lcnc.rules.RunStaleProduction
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * run-stale on the in-memory changes rig: a consumed document whose cid moved fires once, a
 * deletion fires with deleted=true, a file added to the listing fires the index row, an edit
 * does not move the listing, and a retracted run is silent (Forge genesis, Cut S).
 */
class RunStaleProductionTest {

    private class Rig {
        val cas = CasStore.inMemory()
        val store = CouchStoreFactory.casBacked(cas)
        val db = CouchDatabase("notes", store, cas)
        val rete = ReteNetwork()
        val tendon = CouchChangesFactElement(db, rete, admit = { true })
        val facts = LcncRunFacts(rete)
        val fired = ArrayList<Activation>()
        init { rete.register(RunStaleProduction()); rete.productionSink = { fired.add(it) } }
        fun doc(id: String, cid: String) { db.put(id, mapOf("contentId" to cid, "contentType" to "text/markdown", "length" to "7"), store.head.getRev(id)) }
        fun byKind(kind: String) = fired.filter { it.bindings["kind"] == kind }
    }

    private fun receipt(runId: String, index: String, a: String, b: String): Map<String, Any?> = mapOf(
        "status" to "completed", "runId" to runId, "jobId" to "lcnc/run/$runId", "receiptCid" to "sha256:receipt-$runId",
        "programKey" to "lcnc/program/corpus", "programCid" to "sha256:program",
        "consumed" to listOf(
            mapOf("kind" to "project-index", "id" to "notes/", "cid" to index, "prefix" to "", "glob" to "*.md"),
            mapOf("kind" to "project", "id" to "notes/a.md", "cid" to a, "sequence" to 0L, "rev" to "1-x"),
            mapOf("kind" to "project", "id" to "notes/b.md", "cid" to b, "sequence" to 1L, "rev" to "1-y"),
            mapOf("kind" to "prompt", "id" to "summarize", "cid" to "sha256:q"),
        ),
    )

    @Test
    fun aMovedDocumentFiresOnceADeletionSaysSoAndAnAddedFileMovesTheListing() = runTest {
        val rig = Rig()
        rig.doc("a.md", "sha256:a1"); rig.doc("b.md", "sha256:b1"); rig.doc("c.txt", "sha256:c1")
        rig.tendon.drainFrames()
        val index = LcncConsumedLedger.indexFingerprintOf(listOf("a.md", "b.md"))
        assertEquals(3, rig.facts.assertRun(receipt("r1", index, "sha256:a1", "sha256:b1")), "the prompt entry is not a project fact")
        assertEquals(3, rig.rete.workingMemory.query(BlackboardContext("notes"), "kind" to LcncRunFacts.KIND).size)
        assertTrue(rig.fired.isEmpty(), "nothing moved: ${rig.fired}")

        // a.md edited: one document firing, no listing firing; re-evaluation is refracted.
        rig.doc("a.md", "sha256:a2"); rig.tendon.drainFrames()
        assertEquals(1, rig.fired.size, rig.fired.toString())
        val f = rig.fired.single()
        assertEquals(RunStaleProduction.RULE, f.ruleId)
        assertEquals(mapOf("runId" to "r1", "id" to "a.md", "oldCid" to "sha256:a1", "newCid" to "sha256:a2", "deleted" to "false", "project" to "notes", "kind" to "project"),
            f.bindings.filterKeys { it in setOf("runId", "id", "oldCid", "newCid", "deleted", "project", "kind") })
        assertEquals("sha256:receipt-r1", f.bindings["receiptCid"]); assertEquals("lcnc/program/corpus", f.bindings["programKey"])
        rig.rete.evaluateRules("notes")
        assertEquals(1, rig.fired.size, "refraction: one firing per (receipt, new cid)")

        // b.md deleted: the document fires deleted=true AND the listing moved (one file fewer).
        rig.db.delete("b.md", rig.store.head.getRev("b.md")); rig.tendon.drainFrames()
        val deleted = rig.byKind("project").single { it.bindings["id"] == "b.md" }
        assertEquals("true", deleted.bindings["deleted"]); assertEquals("", deleted.bindings["newCid"])
        val listing1 = rig.byKind("project-index").single()
        assertEquals(LcncConsumedLedger.indexFingerprintOf(listOf("a.md")), listing1.bindings["newCid"]); assertEquals("1", listing1.bindings["files"])

        // d.md added: the listing moves again; the .txt never counted.
        rig.doc("d.md", "sha256:d1"); rig.tendon.drainFrames()
        assertEquals(2, rig.byKind("project-index").size)
        assertEquals(LcncConsumedLedger.indexFingerprintOf(listOf("a.md", "d.md")), rig.byKind("project-index").last().bindings["newCid"])

        // The run retracted: further moves are nobody's staleness.
        val before = rig.fired.size
        assertEquals(3, rig.facts.retractRun("r1"))
        rig.doc("a.md", "sha256:a3"); rig.tendon.drainFrames()
        assertEquals(before, rig.fired.size, "a retracted run fires nothing")
        assertEquals(0, rig.rete.workingMemory.query(BlackboardContext("notes"), "kind" to LcncRunFacts.KIND).size)
    }

    @Test
    fun onlyCompletedReceiptsBecomeFactsAndReassertIsAModify() = runTest {
        val rig = Rig()
        rig.doc("a.md", "sha256:a1"); rig.doc("b.md", "sha256:b1"); rig.tendon.drainFrames()
        val index = LcncConsumedLedger.indexFingerprintOf(listOf("a.md", "b.md"))
        assertEquals(0, rig.facts.assertRun(receipt("r0", index, "sha256:a1", "sha256:b1") + ("status" to "running")))
        assertEquals(3, rig.facts.assertRun(receipt("r1", index, "sha256:a1", "sha256:b1")))
        assertEquals(3, rig.facts.assertRun(receipt("r1", index, "sha256:a1", "sha256:b1")), "the same receipt again modifies rather than throwing")
        assertEquals(setOf("r1"), rig.facts.runs())
    }
}
