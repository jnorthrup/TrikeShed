package borg.trikeshed.couch

import borg.trikeshed.dag.FactId
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.job.CasStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CouchChangesFactElementTest {
    @Test
    fun framesBecomeFactsAndBusEvents() = runTest {
        val cas = CasStore.inMemory()
        val store = CouchStoreFactory.casBacked(cas)
        val db = CouchDatabase("trikeshed", store, cas)
        val rete = ReteNetwork()
        val report = CouchReportReactorElement()
        report.open()
        val tendon = CouchChangesFactElement(db, rete, report)

        db.ensureDesignDoc()
        db.put("a", mapOf("kind" to "x"), null)
        db.put("b", mapOf("kind" to "y"), null)
        tendon.drainFrames() // deterministic: no dispatcher race in the test

        assertEquals(2, tendon.factsApplied.toInt(), "design doc is not admitted; a and b are")
        assertEquals(3, report.reportState.value.commits.toInt(), "bus sees every commit, ddoc included")
        assertEquals(1, rete.workingMemory.facts(FactId("trikeshed", "a")).size)
        val v1 = rete.workingMemory.facts(FactId("trikeshed", "a")).first().versionCid

        db.put("a", mapOf("kind" to "x2"), store.head.getRev("a"))
        tendon.drainFrames()
        val fact = rete.workingMemory.facts(FactId("trikeshed", "a")).first()
        assertTrue(fact.versionCid != v1, "modify carries the new revision's blob cid")
        assertEquals("x2", fact.fields["kind"])
        assertEquals(CouchDatabase.revToCid(store.head.getRev("a")!!), fact.versionCid)

        db.delete("b", store.head.getRev("b"))
        tendon.drainFrames()
        assertEquals(0, rete.workingMemory.facts(FactId("trikeshed", "b")).size, "delete retracts")
        assertEquals(5L, report.reportState.value.lastSeq)
    }
}
