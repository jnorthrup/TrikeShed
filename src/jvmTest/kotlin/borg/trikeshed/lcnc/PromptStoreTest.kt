package borg.trikeshed.lcnc

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stored set of prompts survives what the couch index does not: a fresh
 * store over the same CAS and ledger restores every head, re-files it, and
 * re-publishes it; seeds install only where a person has not spoken.
 */
class PromptStoreTest {

    private class Rig(val cas: CasStore, val ledger: File) {
        val board = ConfixBlackboard.empty()
        val couch = CouchStoreFactory.casBacked(cas)
        val gateway = CouchAttachmentGateway(couch, cas)
        val publisher = LcncPublisher(board, { emptyMap() }, gateway)
        var now = 1_000L
        val store = PromptStore(gateway, cas, ledger, publisher) { now }
    }

    private fun ledger(): File = File(System.getProperty("java.io.tmpdir"), "prompt-ledger-${System.nanoTime()}").let { File(it, "prompts/ledger.jsonl") }

    @Test
    fun saveChainsLineageAndAByteIdenticalSaveIsANoOp(): Unit = runBlocking {
        val r = Rig(CasStore.inMemory(), ledger())
        val first = r.store.save(PromptDocument("hello", "Say hello."), actor = "test")
        assertTrue(first.changed); assertNull(first.previousCid)
        r.now = 2_000L
        val second = r.store.save(PromptDocument("hello", "Say hi."), actor = "test")
        assertTrue(second.changed); assertEquals(first.cid, second.previousCid)
        val again = r.store.save(PromptDocument("hello", "Say hi."), actor = "test")
        assertTrue(!again.changed); assertEquals(second.cid, again.cid)
        assertEquals("Say hi.", r.store.get("hello")!!.text)
        assertEquals("Say hello.", r.store.byCid(first.cid)!!.text)
        // The bytes are in CAS under the version's own cid, and filed as the head attachment.
        assertNotNull(r.cas.get(ContentId(second.cid)))
        assertEquals(second.cid, r.gateway.getAttachment("prompts/hello")!!.first.contentId.value)
        // The board carries the head with its lineage, through the one writer.
        val entry = r.board.get(LcncBlackboard.promptKey("hello")) as Map<*, *>
        assertEquals(second.cid, entry["cid"]); assertEquals(first.cid, entry["previousCid"]); assertEquals(2_000L, entry["savedAtMs"])
        assertEquals(listOf(first.cid, second.cid), r.store.history("hello").map { it.cid })
    }

    @Test
    fun aFreshStoreOverTheSameLedgerAndCasRestoresEveryHead(): Unit = runBlocking {
        val cas = CasStore.inMemory(); val file = ledger()
        val a = Rig(cas, file)
        a.store.save(PromptDocument("hello", "Say hello."), actor = "test")
        a.store.save(PromptDocument("hello", "Say hi."), actor = "test")
        a.store.save(PromptDocument("greet", "Greet {{who}}.", role = PromptDocument.ROLE_SYSTEM), actor = "test")
        val b = Rig(cas, file)                      // a new couch index, an empty board — a restart
        assertNull(b.store.get("hello"))
        assertEquals(2, b.store.thaw())
        assertEquals("Say hi.", b.store.get("hello")!!.text)
        assertEquals(listOf("who"), b.store.get("greet")!!.variables)
        assertEquals(b.store.get("hello")!!.cid, (b.board.get(LcncBlackboard.promptKey("hello")) as Map<*, *>)["cid"])
        assertEquals("Say hi.", b.gateway.getAttachment("prompts/hello")!!.second.decodeToString().let { PromptDocument.fromJson(it).text })
    }

    @Test
    fun seedsInstallOnlyWhereNoHeadExistsAndTheirBytesAlwaysResolve(): Unit = runBlocking {
        val cas = CasStore.inMemory(); val file = ledger()
        val a = Rig(cas, file)
        a.store.save(PromptDocument(LcncPromptSeeds.HELLO, "A person wrote this."), actor = "test")
        val b = Rig(cas, file)
        b.store.thaw(LcncPromptSeeds.all())
        assertEquals("A person wrote this.", b.store.get(LcncPromptSeeds.HELLO)!!.text, "the person's head wins over the seed")
        val summarize = b.store.get(LcncPromptSeeds.SUMMARIZE)!!
        assertEquals(LcncPromptSeeds.byName(LcncPromptSeeds.SUMMARIZE)!!.text, summarize.text)
        assertNull(summarize.previousCid)
        val seedHello = LcncPromptSeeds.byName(LcncPromptSeeds.HELLO)!!
        assertNotNull(cas.get(ContentId(seedHello.cid)), "a seed's bytes are in CAS even when its head is not the seed")
        assertEquals(seedHello, b.store.byCid(seedHello.cid))
        // Seeds leave no ledger line: only people write history.
        assertEquals(1, b.store.history(LcncPromptSeeds.HELLO).size)
        assertEquals(0, b.store.history(LcncPromptSeeds.SUMMARIZE).size)
    }

    @Test
    fun aStaleBaseIsRefusedNeverOverwritten(): Unit = runBlocking {
        val r = Rig(CasStore.inMemory(), ledger())
        val first = r.store.save(PromptDocument("hello", "one"), actor = "a")
        val second = r.store.save(PromptDocument("hello", "two"), actor = "b")
        val e = assertFailsWith<PromptStore.StaleBase> { r.store.save(PromptDocument("hello", "three"), actor = "a", baseCid = first.cid) }
        assertEquals(second.cid, e.currentCid)
        assertEquals("two", r.store.get("hello")!!.text)
        val ok = r.store.save(PromptDocument("hello", "three"), actor = "a", baseCid = second.cid)
        assertEquals(second.cid, ok.previousCid)
    }

    @Test
    fun theStoreRefusesWhatTheDocumentGrammarRefuses(): Unit = runBlocking {
        val r = Rig(CasStore.inMemory(), ledger())
        assertFailsWith<IllegalArgumentException> { r.store.save(PromptDocument("Bad Name", "x"), actor = "t") }
        assertFailsWith<IllegalArgumentException> { r.store.save(PromptDocument("ok", "x", role = "oracle"), actor = "t") }
        assertFailsWith<IllegalArgumentException> { r.store.save(PromptDocument("ok", "x".repeat(PromptDocument.MAX_CHARS + 1)), actor = "t") }
    }
}
