package borg.trikeshed.narsese

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.s_
import borg.trikeshed.lib.size
import borg.trikeshed.memory.MemoryStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** I1 gate — corpus evidence → deterministic design docs → CAS + MemoryStore + Line CAS. */
class HermesDesignDistillerTest {
    @Test
    fun designFeaturesLandAsDeterministicCorpusDocuments() = runTest {
        val impulses = s_[
            CuratorImpulse(CuratorImpulseKind.CREATE, "tooling", "record after success", proposalCid = "session-1"),
            CuratorImpulse(CuratorImpulseKind.PATCH, "retry", "record after retry", proposalCid = "session-2"),
        ]
        val scenarios = s_[
            ReplayScenario(
                "session-1", "tooling", s_[
                    ReplayTurn("user", "describe the tool schema and invoke the tool_call"),
                    ReplayTurn("assistant", "tool call completed [pass]"),
                ],
            ),
            ReplayScenario(
                "session-2", "retry", s_[
                    ReplayTurn("user", "continue after timeout"),
                    ReplayTurn("assistant", "retry then resume the session [pass]"),
                ],
            ),
        ]

        val a = HermesDesignDistiller.project(impulses, scenarios)
        val b = HermesDesignDistiller.project(impulses, scenarios)
        assertEquals(HermesDesignDistiller.Feature.entries.size + 1, a.size, "five features + mapping table")
        for (i in 0 until a.size) {
            assertEquals(a[i].path, b[i].path)
            assertEquals(ContentId.of(a[i].bytes), ContentId.of(b[i].bytes), "same snapshot reproduces same cid")
        }
        var retry: HermesDesignDistiller.Document? = null
        var table: HermesDesignDistiller.Document? = null
        for (i in 0 until a.size) {
            if (a[i].path.endsWith("retry-continuation.md")) retry = a[i]
            if (a[i].path.endsWith("feature-shape.md")) table = a[i]
        }
        val retryDoc = assertNotNull(retry)
        assertTrue("Evidence occurrences: 4" in retryDoc.bytes.decodeToString())
        val tableDoc = assertNotNull(table)
        assertTrue("mux channel" in tableDoc.bytes.decodeToString())
        assertTrue("CCEK seat" in tableDoc.bytes.decodeToString())

        val store = MemoryStore(CasStore.inMemory(), CouchStoreFactory.inMemory())
        val landed = HermesDesignDistiller.land(a, store)
        assertEquals(a.size, landed.size)
        for (i in 0 until landed.size) {
            assertNotNull(store.get(landed[i].path), "distillate is a MemoryStore document")
            assertNotNull(store.cas.get(landed[i].cid), "distillate bytes are in CAS")
            assertNotNull(store.spineCidOf(landed[i].path), "distillate is Line-CAS indexed")
        }
        assertEquals(a.size, store.lineIndex.documentCount, "all distillates are terrain citizens")
    }
}
