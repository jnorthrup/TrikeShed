package borg.trikeshed.memory.ace

import borg.trikeshed.lib.get
import borg.trikeshed.lib.s_
import borg.trikeshed.lib.size
import borg.trikeshed.memory.MemoryStore
import borg.trikeshed.job.CasStore
import borg.trikeshed.couch.CouchStoreFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AceContextProgramTest {
    private val config = AceContextProgram.Config("model-x", s_["search", "read", "write"], "high")

    @Test
    fun presetIsAuditableByteStableAndConfigSalted() {
        val p = AceContextProgram.preset("tools".encodeToByteArray(), "base".encodeToByteArray(), "env".encodeToByteArray(), "tail".encodeToByteArray())
        val docA = p.canonicalBytes()
        val docB = AceContextProgram.preset("tools".encodeToByteArray(), "base".encodeToByteArray(), "env".encodeToByteArray(), "tail".encodeToByteArray()).canonicalBytes()
        assertTrue(docA.contentEquals(docB))
        val text = docA.decodeToString()
        assertTrue("PREWARM" in text && "|0|" in text, "max_tokens=0 prewarm is in the program")
        assertTrue("FANOUT_STAGGER" in text && "|1|" in text, "first-token stagger is in the program")

        val reordered = AceContextProgram.Config("model-x", s_["write", "search", "read"], "high")
        assertEquals(config.salt(), reordered.salt(), "tool set is deterministically sorted")
        assertNotEquals(config.salt(), AceContextProgram.Config("model-y", s_["search", "read", "write"], "high").salt())

        val store = MemoryStore(CasStore.inMemory(), CouchStoreFactory.inMemory())
        val cid = AceContextProgram.land(p, store)
        val path = "/programs/context/${cid.hex}.ace"
        assertTrue(store.get(path) != null, "program document is auditable in MemoryStore")
        assertTrue(store.spineCidOf(path) != null, "program chunks are Line-CAS groupable")
    }

    @Test
    fun oneByteEditRepricesExactlyFromEditedFrameOnward() {
        val a = AceContextProgram.assemble(
            AceContextProgram.preset("tools".encodeToByteArray(), "base".encodeToByteArray(), "env".encodeToByteArray(), "tail".encodeToByteArray()), config,
        )
        val b = AceContextProgram.assemble(
            AceContextProgram.preset("tools".encodeToByteArray(), "base".encodeToByteArray(), "enw".encodeToByteArray(), "tail".encodeToByteArray()), config,
        )
        assertEquals(2, AceContextProgram.firstChanged(a, b), "one byte in frame k forks at k")
        assertEquals(2, AceContextProgram.firstChanged(b, a), "repricing boundary is symmetric")
        for (i in 0 until 2) assertEquals(a[i].cid, b[i].cid, "prefix before k remains byte-identical")
        for (i in 2 until a.size) assertNotEquals(a[i].cid, b[i].cid, "everything after k is repriced")

        val receipts = s_[
            AceContextProgram.ChunkReceipt(a[0].cid, cacheRead = 100, cacheWrite = 0),
            AceContextProgram.ChunkReceipt(a[1].cid, cacheRead = 80, cacheWrite = 0),
        ]
        assertTrue(AceContextProgram.auditCacheRead(a, receipts, 2), "identical prefix has cache_read coverage")
        assertTrue(!AceContextProgram.auditCacheRead(a, receipts, 3), "missing receipt cannot claim cache coverage")
    }

    @Test
    fun moreThanFourBreakpointsIsRejected() {
        val nodes = s_[
            AceContextProgram.Node("a", AceContextProgram.FrameKind.TOOLS_SYSTEM, byteArrayOf(), true),
            AceContextProgram.Node("b", AceContextProgram.FrameKind.TOOLS_SYSTEM, byteArrayOf(), true),
            AceContextProgram.Node("c", AceContextProgram.FrameKind.TOOLS_SYSTEM, byteArrayOf(), true),
            AceContextProgram.Node("d", AceContextProgram.FrameKind.TOOLS_SYSTEM, byteArrayOf(), true),
            AceContextProgram.Node("e", AceContextProgram.FrameKind.TOOLS_SYSTEM, byteArrayOf(), true),
        ]
        assertFailsWith<IllegalArgumentException> { AceContextProgram.Program("bad", nodes) }
    }
}
