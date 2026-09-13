package borg.trikeshed.lcnc

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.kif.KifExpr
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.OroborosAttachmentRef
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A published program survives what the couch index does not (AutoTools notion,
 * Cut 0): a fresh publisher, board and attachment gateway over the SAME CAS and
 * the SAME ledger file is a restart, and the thaw puts every head back at the
 * cid it was published at — re-filed as its attachment, republished as
 * `lcnc/program/<name>`, and resolvable by the run seam's own loader.
 */
class ProgramLedgerTest {

    /** One node, one word: the smallest thing that round-trips through the Confix grammar. */
    private companion object {
        fun document(note: String) =
            """{"nodes":[{"id":"n1","type":"note","params":{"text":"$note"},"x":10,"y":10}],"wires":[]}"""
    }

    /** A fresh one of these over the same `cas` and `file` IS the restart. */
    private class Rig(val cas: CasStore, val file: borg.trikeshed.common.Path) {
        val board = ConfixBlackboard.empty()
        val couch = CouchStoreFactory.casBacked(cas)
        val gateway = CouchAttachmentGateway(couch, cas)
        val publisher = LcncPublisher(board, { emptyMap() }, gateway)
        var now = 1_000L
        val ledger = ProgramLedger(gateway, cas, file, publisher) { now }

        /** What `POST /api/panels/<name>` does, in the order it does it. */
        suspend fun publish(name: String, note: String, actor: String = ProgramLedger.ACTOR): String {
            val program = LcncProgramConfix.fromJson(name, document(note))
            val bytes = LcncProgramConfix.toJson(program).encodeToByteArray()
            val cid = ContentId.of(bytes)
            val previousCid = publisher.boardProgramCid(name)
            gateway.putAttachment(
                OroborosAttachmentRef(
                    path = "panels/$name", contentType = "application/json", length = bytes.size.toLong(),
                    contentId = cid, agentId = actor, revision = cid.hex.take(12), sequence = now,
                ),
                bytes,
            )
            ledger.record(name, cid.value, previousCid = previousCid, actor = actor)
            publisher.publishProgram(name, program)
            publisher.publishAll()
            return cid.value
        }

        fun boardCid(name: String): String? = (board.get(LcncBlackboard.programKey(name)) as? Map<*, *>)?.get("programCid")?.toString()
        fun attachmentCid(name: String): String? = gateway.getAttachment("panels/$name")?.first?.contentId?.value
    }

    private fun ledgerFile(): borg.trikeshed.common.Path =
        borg.trikeshed.common.File(System.getProperty("java.io.tmpdir")!!, "program-ledger-${System.nanoTime()}")

    @Test
    fun theLedgerChainsEveryVersionAndAFreshRigReplaysTheLastPerName(): Unit = runBlocking {
        val cas = CasStore.inMemory(); val file = ledgerFile()
        val a = Rig(cas, file)
        val v1 = a.publish("notes", "one")
        a.now = 2_000L
        val v2 = a.publish("notes", "two")
        val d1 = a.publish("digest", "digested")
        assertEquals(listOf(v1, v2), a.ledger.history("notes").map { it.cid }, "oldest first, as recorded")
        assertEquals(listOf(null, v1), a.ledger.history("notes").map { it.previousCid }, "the chain is the reply's own previousCid")
        assertEquals(mapOf("notes" to v2, "digest" to d1), a.ledger.heads())

        // A byte-identical re-publish is not a new version: the ledger does not grow.
        a.publish("notes", "two")
        assertEquals(2, a.ledger.history("notes").size)

        val b = Rig(cas, file)                        // a new couch index, an empty board — a restart
        assertNull(b.gateway.getAttachment("panels/notes"), "the attachment plane starts empty")
        assertNull(b.publisher.boardProgramCid("notes"), "and so does the board — the bug being closed")
        assertEquals(2, b.ledger.thaw())
        assertEquals(v2, b.attachmentCid("notes"), "the head is re-filed at the cid it was published at")
        assertEquals(v2, b.boardCid("notes"), "and republished as lcnc/program/notes at the same cid")
        assertEquals(d1, b.boardCid("digest"))
        assertEquals(mapOf("notes" to v2, "digest" to d1), b.ledger.heads())
        assertEquals("two", noteOf(b, "notes"), "the LAST version per name wins, not the first")
        // `ctx.programLoader` is exactly this call: a run naming the program by name resolves it.
        assertEquals("two", LcncProgramConfix.toJson(b.publisher.load("notes")!!).let { textOf(it) })
        // The chain continues across the boot: the next publish reports v2 as its predecessor.
        b.now = 3_000L
        val v3 = b.publish("notes", "three")
        assertEquals(v2, b.ledger.history("notes").last { it.cid == v3 }.previousCid)
    }

    @Test
    fun theRestoredProgramCidIsTheCidTheRoundTripMints(): Unit = runBlocking {
        val cas = CasStore.inMemory(); val file = ledgerFile()
        val a = Rig(cas, file)
        val v1 = a.publish("notes", "one")
        val b = Rig(cas, file)
        assertEquals(1, b.ledger.thaw())
        // `toJson ∘ fromJson` is a fixpoint on stored bytes, so the cid the board recomputes for
        // the restored program is the cid the ledger recorded — the equality every receipt's
        // version match (landscape.js) depends on after a boot.
        val restored = LcncProgramConfix.fromJson("notes", b.gateway.getAttachment("panels/notes")!!.second.decodeToString())
        assertEquals(v1, LcncBlackboard.cidOf(restored))
        assertEquals(v1, b.boardCid("notes"))
        assertEquals(v1, (b.board.get(LcncBlackboard.programKey("notes")) as Map<*, *>)["sourceCid"])
    }

    @Test
    fun aHeadWhoseBytesAreGoneIsSkippedAndTheOtherHeadsStillRestore(): Unit = runBlocking {
        val cas = CasStore.inMemory(); val file = ledgerFile()
        val a = Rig(cas, file)
        val kept = a.publish("notes", "one")
        // A line whose blob was never put: a home whose cas/ was pruned, or bytes from another root.
        val absent = ContentId.of("never put into this cas".encodeToByteArray()).value
        a.ledger.record("ghost", absent, previousCid = null, actor = "test")
        assertNull(cas.get(ContentId(absent)))

        val b = Rig(cas, file)
        assertEquals(1, b.ledger.thaw(), "the ghost is skipped, the boot is not")
        assertEquals(kept, b.boardCid("notes"))
        assertNull(b.boardCid("ghost"))
        assertNull(b.gateway.getAttachment("panels/ghost"))
    }

    @Test
    fun aNameThatHasSinceBecomeAPresetIsNotResurrected(): Unit = runBlocking {
        val cas = CasStore.inMemory(); val file = ledgerFile()
        val presetName = LcncPresets.all().keys.first()
        val a = Rig(cas, file)
        // Written before the release that added the preset — the route refuses this name today.
        val bytes = LcncProgramConfix.toJson(LcncProgramConfix.fromJson(presetName, document("shadowed"))).encodeToByteArray()
        val cid = ContentId.of(bytes)
        a.gateway.putAttachment(
            OroborosAttachmentRef(
                path = "panels/$presetName", contentType = "application/json", length = bytes.size.toLong(),
                contentId = cid, agentId = "test", revision = cid.hex.take(12), sequence = 1_000L,
            ),
            bytes,
        )
        a.ledger.record(presetName, cid.value, previousCid = null, actor = "test")
        val alsoKept = a.publish("notes", "one")

        val b = Rig(cas, file)
        assertEquals(1, b.ledger.thaw(), "only the user program is restored")
        assertEquals(alsoKept, b.boardCid("notes"))
        assertNull(b.gateway.getAttachment("panels/$presetName"), "the preset's name is not re-filed under it")
        // LcncPublisher's precedence is never contradicted: the preset owns its name.
        assertEquals(
            LcncBlackboard.cidOf(LcncProgramConfix.fromJson(presetName, LcncPresets.all().getValue(presetName))),
            b.boardCid(presetName),
        )
    }

    @Test
    fun aSecondThawMovesNeitherTheBoardNorTheAttachment(): Unit = runBlocking {
        val cas = CasStore.inMemory(); val file = ledgerFile()
        val a = Rig(cas, file)
        val v1 = a.publish("notes", "one")
        val b = Rig(cas, file)
        assertEquals(1, b.ledger.thaw())
        val revisionAfterFirst = b.board.snapshot().revision
        val attachmentAfterFirst = b.gateway.getAttachment("panels/notes")!!.first

        assertEquals(1, b.ledger.thaw(), "the ledger is replayed, not consumed")
        assertEquals(revisionAfterFirst, b.board.snapshot().revision, "putIfChanged: no delta, no board revision")
        val attachmentAfterSecond = b.gateway.getAttachment("panels/notes")!!.first
        assertEquals(attachmentAfterFirst.contentId, attachmentAfterSecond.contentId)
        assertEquals(attachmentAfterFirst.revision, attachmentAfterSecond.revision)
        assertEquals(attachmentAfterFirst.sequence, attachmentAfterSecond.sequence, "the recorded instant, not the boot's clock")
        assertEquals(v1, b.boardCid("notes"))
    }

    @Test
    fun aLedgerWithNoFileAndAFileWithGarbageAreBothSurvivable(): Unit = runBlocking {
        val cas = CasStore.inMemory()
        val unwired = ProgramLedger(null, cas, null, null) { 1_000L }
        assertEquals(0, unwired.thaw())
        assertEquals(emptyList(), unwired.history("notes"))
        assertNotNull(unwired.record("notes", ContentId.of("x".encodeToByteArray()).value, null, "test"))

        val file = ledgerFile()
        val a = Rig(cas, file)
        val v1 = a.publish("notes", "one")
        file.appendText("this is not json\n\n{\"name\":\"halfline\"}\n")
        val b = Rig(cas, file)
        assertEquals(1, b.ledger.thaw(), "an unreadable line is dropped, the readable head is not")
        assertEquals(v1, b.boardCid("notes"))
        assertTrue(b.ledger.history("notes").isNotEmpty())
    }

    /**
     * A ROTTED BLOB IS NOT A MISSING BLOB, and the CAS does not report it the same
     * way: `CasStore.get` and the daemon's `FileCasStore.get` re-hash what they read
     * and THROW `digest mismatch` when a blob is present and no longer its own id;
     * only an ABSENT blob is null. The thaw runs sequentially in `mainImpl` before
     * `kanbanServer.run(...)` binds and the daemon's only catch is
     * CancellationException, so an unguarded read here would turn "one program does
     * not come back" into "the daemon has no HTTP surface" — for a home whose `cas/`
     * was copied, partially synced or repaired by an external tool.
     */
    @Test
    fun aRottedHeadBlobIsSkippedLikeAMissingOneAndTheBootKeepsItsOtherPrograms(): Unit = runBlocking {
        val cas = CasStore.inMemory(); val file = ledgerFile()
        val a = Rig(cas, file)
        val rotted = a.publish("rotted", "one")
        val kept = a.publish("notes", "two")
        cas.corrupt(ContentId(rotted))
        assertFailsWith<IllegalStateException>("the store THROWS for this, it does not answer null") {
            cas.get(ContentId(rotted))
        }

        val b = Rig(cas, file)
        assertEquals(1, b.ledger.thaw(), "the rotted head costs one program, not the boot")
        assertEquals(kept, b.boardCid("notes"), "the readable head is restored and republished")
        assertNull(b.boardCid("rotted"))
        assertNull(b.gateway.getAttachment("panels/rotted"), "and nothing is re-filed under it")
    }

    /**
     * The same rot, one seam further out: the thaw's closing `publishAll()` reads
     * EVERY `panels/` attachment through `storedCorpus()`, ledger line or not. One
     * unreadable panel there used to throw out of the corpus — taking the presets,
     * the restored heads' board entries and (unguarded at the call site) the boot
     * with it. It must cost that one panel.
     */
    @Test
    fun aRottedPanelTheLedgerNeverNamedDoesNotCostTheThawItsRepublish(): Unit = runBlocking {
        val cas = CasStore.inMemory(); val file = ledgerFile()
        val kept = Rig(cas, file).publish("notes", "one")

        val b = Rig(cas, file)
        // A panel filed by another lane, whose blob has rotted under it: present in the
        // attachment plane this boot reads, named by no ledger line.
        val orphan = LcncProgramConfix.toJson(LcncProgramConfix.fromJson("orphan", document("orphan"))).encodeToByteArray()
        val orphanCid = ContentId.of(orphan)
        b.gateway.putAttachment(
            OroborosAttachmentRef(
                path = "panels/orphan", contentType = "application/json", length = orphan.size.toLong(),
                contentId = orphanCid, agentId = "other-lane", revision = orphanCid.hex.take(12), sequence = 1_000L,
            ),
            orphan,
        )
        cas.corrupt(orphanCid)

        assertEquals(1, b.ledger.thaw(), "the ledger's own head is still restored")
        assertEquals(kept, b.boardCid("notes"), "and REPUBLISHED — the corpus survived the unreadable panel")
        assertNotNull(b.boardCid(LcncPresets.all().keys.first()), "so did the presets, which shared its corpus")
        assertNull(b.boardCid("orphan"), "the panel that cannot be read is the only thing lost")
    }

    /**
     * THE THAW'S REPUBLISH IS A `lateBound()`, and lateBound resolves every
     * contract against the runner registry AS IT STANDS, telling the answer into
     * the daemon's ONE KIF bank — the bank `/api/lcnc/facts` serves and
     * `/api/beliefs/query` answers on. A boot's registry keeps growing (node
     * families register in blocks, a module adds its own on attach), so a pass
     * that runs early is entitled to say `unbound` about a lego that is about to
     * be bound. What it may NOT do is leave that answer standing beside the true
     * one: the bank retracts nothing on its own and `bindingOf` reads the FIRST
     * row in telling order, so a stacked second row would make the lie permanent
     * and `/api/lcnc/contracts` would call a bound lego unbound forever.
     */
    @Test
    fun aThawResolvedBeforeTheRunnersArriveLeavesOneBindingRowPerTypeAndItIsTheLaterOne(): Unit = runBlocking {
        val cas = CasStore.inMemory(); val file = ledgerFile()
        val v1 = Rig(cas, file).publish("notes", "one")

        // The daemon's own shape: ONE bank, a registry read LATE, and a restart whose
        // ledger republishes the restored corpus into that bank.
        val bank = KifKnowledgeBase()
        val registry = linkedMapOf<String, LcncNodeRunner>()
        val gateway = CouchAttachmentGateway(CouchStoreFactory.casBacked(cas), cas)
        val board = ConfixBlackboard.empty()
        val publisher = LcncPublisher(board, { registry }, gateway, null, bank)
        assertEquals(1, ProgramLedger(gateway, cas, file, publisher) { 5_000L }.thaw())
        assertEquals(v1, (board.get(LcncBlackboard.programKey("notes")) as Map<*, *>)["programCid"])
        assertEquals("unbound", bindingHow(bank, "project.list"), "nothing is registered yet — honest for that moment")

        // …then the node families register and the module attaches, and the corpus is
        // published again over the SAME bank.
        registry["project.list"] = LcncNodeRunner { _, _ -> emptyMap() }
        registry["pick"] = LcncNodeRunner { _, _ -> emptyMap() }
        publisher.publishAll()

        val stacked = bank.query(KifExpr.parse("(binding ?t ?how ?by)")).groupBy { it.getValue("?t") }.filterValues { it.size > 1 }
        assertTrue(stacked.isEmpty(), "a type's binding is REPLACED, never stacked; doubled: ${stacked.keys}")
        assertEquals("kotlin", bindingHow(bank, "project.list"), "the bound lego reads bound, not the first answer told")
        assertEquals("kotlin", bindingHow(bank, "pick"))
        assertEquals("unbound", bindingHow(bank, "timer"), "and a type nothing registered keeps its one honest row")
    }

    /** The bank's single answer for a type, or null when it holds none or more than one. */
    private fun bindingHow(bank: KifKnowledgeBase, type: String): String? =
        bank.query(KifExpr.parse("(binding $type ?how ?by)")).singleOrNull()?.getValue("?how")

    private fun noteOf(rig: Rig, name: String): String? =
        rig.gateway.getAttachment("panels/$name")?.second?.decodeToString()?.let { textOf(it) }

    @Suppress("UNCHECKED_CAST")
    private fun textOf(json: String): String? {
        val m = borg.trikeshed.parse.json.JsonSupport.parse(json) as Map<String, Any?>
        val nodes = m["nodes"] as List<Map<String, Any?>>
        return (nodes[0]["params"] as Map<String, Any?>)["text"]?.toString()
    }
}
