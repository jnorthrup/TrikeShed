package borg.trikeshed.forge.server

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.jules.BrainClient
import borg.trikeshed.lcnc.LcncBlackboard
import borg.trikeshed.lcnc.LcncPublisher
import borg.trikeshed.lcnc.ProgramLedger
import borg.trikeshed.lcnc.WorkspaceSnapshot
import borg.trikeshed.memory.CouchIndexBridge
import borg.trikeshed.memory.MemoryIndexLayer
import borg.trikeshed.memory.MemoryStore
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import keymux.KeyMux
import modelmux.ModelMux
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Publishing over a moved board is refused (Forge genesis, Cut C); snapshots name the workspace as one cid. */
class PatchWirePublishTest {

    /**
     * A second Rig over the same [cas], [ledger] and [programLedger] is a RESTART: a fresh
     * couch index, a fresh attachment gateway, an empty board — everything that is per boot —
     * over the two planes that are on disk.
     */
    private class Rig(
        val cas: CasStore = CasStore.inMemory(),
        val ledger: File = File(System.getProperty("java.io.tmpdir"), "patchwire-snapshots-${System.nanoTime()}/snapshots/ledger.jsonl"),
        val programLedger: File = File(System.getProperty("java.io.tmpdir"), "patchwire-programs-${System.nanoTime()}/programs/ledger.jsonl"),
    ) {
        val couch = CouchStoreFactory.casBacked(cas)
        val gateway = CouchAttachmentGateway(couch, cas)
        val board = ConfixBlackboard.empty()
        val publisher = LcncPublisher(board, { emptyMap() }, gateway)
        val scopes = ProjectScopes(JvmFileOperations(), gateway, CouchIndexBridge(gateway, MemoryIndexLayer(MemoryStore(cas, couch))), cas, null)
        var now = 5_000L
        val snapshots = WorkspaceSnapshotService(board, cas, gateway, null, null, publisher, ledger) { now }
        val programs = ProgramLedger(gateway, cas, programLedger, publisher) { now }
        fun wire(): PatchWire {
            val keyMux = KeyMux {}
            val brain = BrainClient(apiKey = "test-key", keyMux = keyMux, modelMux = ModelMux(keyMux) {})
            return PatchWire(brain, scopes, attachments = gateway, publisher = publisher, snapshots = snapshots, programs = programs)
        }
    }

    private fun program(note: String) = """{"nodes":[{"id":"n1","type":"note","params":{"text":"$note"},"x":10,"y":10}],"wires":[]}"""

    private suspend fun post(wire: PatchWire, path: String, body: String) =
        wire.route("POST", path, "POST $path HTTP/1.1\r\nContent-Type: application/json\r\n\r\n$body", null)!!
    private suspend fun get(wire: PatchWire, path: String) = wire.route("GET", path, "GET $path HTTP/1.1\r\n\r\n", null)!!

    @Suppress("UNCHECKED_CAST")
    private fun json(r: borg.trikeshed.litebike.JvmKanbanServer.HttpResponse) = JsonSupport.parse(r.body) as Map<String, Any?>

    @Test
    fun aStaleBaseIsRefusedAMatchingBaseSucceedsAndNoBaseKeepsLastWriterWins(): Unit = runBlocking {
        val rig = Rig(); val wire = rig.wire()
        val first = json(post(wire, "/api/panels/notes", program("one")))
        assertEquals("ok", first["verdict"]); assertNull(first["previousCid"], "the first version has no predecessor")
        val v1 = first["cid"] as String
        assertEquals(v1, rig.publisher.boardProgramCid("notes"))

        // Editor B publishes over v1 with the matching base: fine, and the reply names what it replaced.
        val second = json(post(wire, "/api/panels/notes?baseCid=$v1", program("two")))
        assertEquals("ok", second["verdict"]); assertEquals(v1, second["previousCid"])
        val v2 = second["cid"] as String
        assertNotEquals(v1, v2)

        // Editor A, still holding v1, publishes: refused, nothing written, the refusal is a board receipt.
        val refused = post(wire, "/api/panels/notes?baseCid=$v1", program("three"))
        assertEquals(409, refused.status, refused.body)
        val body = json(refused)
        assertEquals("stale_base", body["error"]); assertEquals(v1, body["baseCid"]); assertEquals(v2, body["currentCid"])
        assertEquals(v2, rig.publisher.boardProgramCid("notes"), "the board did not move")
        assertEquals("two", ((JsonSupport.parse(get(wire, "/api/panels/notes").body) as Map<*, *>)["nodes"] as List<*>).let { ((it[0] as Map<*, *>)["params"] as Map<*, *>)["text"] })
        val outcome = rig.board.get(LcncBlackboard.publishKey("notes")) as Map<*, *>
        assertEquals("refused", outcome["verdict"]); assertEquals("stale_base", outcome["reason"]); assertEquals(v2, outcome["currentCid"])
        assertEquals("lcnc", rig.board.getProvenance(LcncBlackboard.publishKey("notes"))?.language)

        // No baseCid: the legacy path, last writer wins, and the outcome says so.
        val third = json(post(wire, "/api/panels/notes", program("three")))
        assertEquals("ok", third["verdict"]); assertEquals(v2, third["previousCid"])
        assertEquals("ok", (rig.board.get(LcncBlackboard.publishKey("notes")) as Map<*, *>)["verdict"])
    }

    @Test
    fun snapshotsNameTheWorkspaceAndChainAndServeTheirBytes(): Unit = runBlocking {
        val rig = Rig(); val wire = rig.wire()
        val v1 = json(post(wire, "/api/panels/notes", program("one")))["cid"] as String
        val taken = post(wire, "/api/snapshots", """{"note":"after one"}""")
        assertEquals(201, taken.status, taken.body)
        val t1 = json(taken)
        val cid1 = t1["cid"] as String
        assertNull(t1["previousCid"])
        val head = rig.board.get(WorkspaceSnapshot.HEAD_KEY) as Map<*, *>
        assertEquals(cid1, head["cid"]); assertEquals("after one", head["note"])
        val doc = WorkspaceSnapshot.fromJson(get(wire, "/api/snapshots/$cid1").body)!!
        assertEquals(v1, doc.programs.getValue("notes")["programCid"]); assertEquals("after one", doc.note)
        assertEquals(cid1, doc.cid, "the served bytes hash to the cid asked for")

        rig.now = 6_000L
        val t2 = json(post(wire, "/api/snapshots", """{"note":"again"}"""))
        assertEquals(cid1, t2["previousCid"], "the lineage chains")
        val listing = json(get(wire, "/api/snapshots"))
        assertEquals(listOf(t2["cid"], cid1), (listing["snapshots"] as List<Map<*, *>>).map { it["cid"] }, "newest first")
        assertEquals(t2["cid"], listing["head"])
        assertEquals(404, get(wire, "/api/snapshots/sha256:0000000000000000000000000000000000000000000000000000000000000000").status)

        // A fresh service over the same ledger and CAS restores the head after a restart.
        val reopened = WorkspaceSnapshotService(rig.board, rig.cas, rig.gateway, null, null, rig.publisher, rig.ledger) { 7_000L }
        assertEquals(2, reopened.restore())
        assertEquals(t2["cid"], reopened.head?.cid); assertEquals(cid1, reopened.head?.previousCid)
        assertTrue(rig.gateway.listAttachments("snapshots/").map { it.path }.containsAll(listOf("snapshots/$cid1", "snapshots/${t2["cid"]}")))
    }

    /**
     * AutoTools notion, Cut 0. The bytes were always on disk; the name → head cid mapping was
     * not, so a restart lost every published program. `<forgeHome>/programs/ledger.jsonl` is
     * that mapping, and the thaw is what a boot does with it.
     */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun aRestartOnTheSameHomeServesTheSameProgramCid(): Unit = runBlocking {
        val rig = Rig(); val wire = rig.wire()
        val v1 = json(post(wire, "/api/panels/notes", program("one")))["cid"] as String
        val second = json(post(wire, "/api/panels/notes?baseCid=$v1", program("two")))
        val v2 = second["cid"] as String
        assertEquals(v1, second["previousCid"])
        assertEquals(listOf(v1, v2), (json(get(wire, "/api/panels/notes?history=1"))["versions"] as List<Map<*, *>>).map { it["cid"] })

        // A restart: the same CAS and the same two ledger files, everything else new.
        val rebooted = Rig(rig.cas, rig.ledger, rig.programLedger)
        val after = rebooted.wire()

        // BEFORE the thaw, the failure this cut closes: the head projection is empty.
        assertEquals(404, get(after, "/api/panels/notes").status, "the couch index is rebuilt per boot")
        assertNull(rebooted.publisher.boardProgramCid("notes"), "and the board starts empty")

        assertEquals(1, rebooted.programs.thaw())
        assertEquals(v2, rebooted.publisher.boardProgramCid("notes"), "the head is back at the cid it was published at")
        assertEquals(v2, (rebooted.board.get(LcncBlackboard.programKey("notes")) as Map<*, *>)["programCid"])
        val listed = json(get(after, "/api/panels"))["panels"] as List<Map<*, *>>
        assertEquals(listOf("notes" to v2), listed.map { it["name"] to it["cid"] }, "the harness's program list, from the attachments")
        assertEquals("two", ((JsonSupport.parse(get(after, "/api/panels/notes").body) as Map<*, *>)["nodes"] as List<*>)
            .let { ((it[0] as Map<*, *>)["params"] as Map<*, *>)["text"] }, "and it serves the LAST version, not the first")

        // The lineage continues across the boot: today this reply would say null.
        val third = json(post(after, "/api/panels/notes?baseCid=$v2", program("three")))
        assertEquals("ok", third["verdict"]); assertEquals(v2, third["previousCid"])
        assertEquals(listOf(v1, v2, third["cid"]), (json(get(after, "/api/panels/notes?history=1"))["versions"] as List<Map<*, *>>).map { it["cid"] })
    }

    /** Without the ledger the route behaves exactly as it did, and `?history=1` says so plainly. */
    @Test
    fun aWireWithNoLedgerPublishesAsBeforeAndRefusesTheHistoryRead(): Unit = runBlocking {
        val rig = Rig()
        val keyMux = KeyMux {}
        val brain = BrainClient(apiKey = "test-key", keyMux = keyMux, modelMux = ModelMux(keyMux) {})
        val wire = PatchWire(brain, rig.scopes, attachments = rig.gateway, publisher = rig.publisher)
        val first = json(post(wire, "/api/panels/notes", program("one")))
        assertEquals("ok", first["verdict"])
        assertEquals(first["cid"], rig.publisher.boardProgramCid("notes"))
        val history = get(wire, "/api/panels/notes?history=1")
        assertEquals(503, history.status)
        assertEquals("program ledger not wired", json(history)["error"])
    }
}
