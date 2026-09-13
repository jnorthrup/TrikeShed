package borg.trikeshed.forge.server

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.jules.BrainClient
import borg.trikeshed.lcnc.LcncBlackboard
import borg.trikeshed.lcnc.LcncPublisher
import borg.trikeshed.lcnc.PromptDocument
import borg.trikeshed.lcnc.PromptStore
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
import kotlin.test.assertTrue

/** `/api/prompts`: the stored set of prompts on the wire — list, read, save, history, refusal. */
class PatchWirePromptsTest {

    private class Rig {
        val cas = CasStore.inMemory()
        val couch = CouchStoreFactory.casBacked(cas)
        val gateway = CouchAttachmentGateway(couch, cas)
        val board = ConfixBlackboard.empty()
        val publisher = LcncPublisher(board, { emptyMap() }, gateway)
        val ledger = borg.trikeshed.common.File(System.getProperty("java.io.tmpdir"), "patchwire-prompts-${System.nanoTime()}/prompts/ledger.jsonl")
        val store = PromptStore(gateway, cas, ledger, publisher) { 7L }
        val scopes = ProjectScopes(JvmFileOperations(), gateway, CouchIndexBridge(gateway, MemoryIndexLayer(MemoryStore(cas, couch))), cas, null)
        fun wire(withStore: Boolean = true): PatchWire {
            val keyMux = KeyMux {}
            val brain = BrainClient(apiKey = "test-key", keyMux = keyMux, modelMux = ModelMux(keyMux) {})
            return PatchWire(brain, scopes, attachments = gateway, publisher = publisher, prompts = if (withStore) store else null)
        }
    }

    private suspend fun get(wire: PatchWire, path: String) = wire.route("GET", path, "GET $path HTTP/1.1\r\n\r\n", null)!!
    private suspend fun postText(wire: PatchWire, path: String, body: String) =
        wire.route("POST", path, "POST $path HTTP/1.1\r\nContent-Type: text/plain\r\n\r\n$body", null)!!
    private suspend fun postJson(wire: PatchWire, path: String, body: String) =
        wire.route("POST", path, "POST $path HTTP/1.1\r\nContent-Type: application/json\r\n\r\n$body", null)!!
    @Suppress("UNCHECKED_CAST")
    private fun json(r: borg.trikeshed.litebike.JvmKanbanServer.HttpResponse) = JsonSupport.parse(r.body) as Map<String, Any?>

    @Test
    fun saveListReadHistoryAndTheBoardEntryAgree(): Unit = runBlocking {
        val rig = Rig(); val wire = rig.wire()
        assertEquals(emptyList<Any?>(), json(get(wire, "/api/prompts"))["prompts"])
        val first = json(postText(wire, "/api/prompts/hello", "Say hello in one sentence."))
        assertEquals("ok", first["verdict"]); assertEquals(true, first["changed"]); assertEquals(null, first["previousCid"])
        val second = json(postJson(wire, "/api/prompts/hello", """{"text":"Greet {{who}} warmly.","role":"system","tags":["a","b"]}"""))
        assertEquals(first["cid"], second["previousCid"]); assertEquals(listOf("who"), second["variables"])
        val heads = json(get(wire, "/api/prompts"))["prompts"] as List<*>
        assertEquals(1, heads.size)
        assertEquals(second["cid"], (heads[0] as Map<*, *>)["cid"]); assertEquals("system", (heads[0] as Map<*, *>)["role"])
        val doc = PromptDocument.fromJson(get(wire, "/api/prompts/hello").body)
        assertEquals("Greet {{who}} warmly.", doc.text); assertEquals(listOf("a", "b"), doc.tags); assertEquals(second["cid"], doc.cid)
        val history = json(get(wire, "/api/prompts/hello?history=1"))["versions"] as List<*>
        assertEquals(listOf(first["cid"], second["cid"]), history.map { (it as Map<*, *>)["cid"] })
        assertEquals(second["cid"], (rig.board.get(LcncBlackboard.promptKey("hello")) as Map<*, *>)["cid"])
        val same = json(postJson(wire, "/api/prompts/hello", """{"text":"Greet {{who}} warmly.","role":"system","tags":["a","b"]}"""))
        assertEquals(false, same["changed"]); assertEquals(second["cid"], same["cid"])
    }

    @Test
    fun aStaleBaseIsRefusedWith409NamingTheCurrentHead(): Unit = runBlocking {
        val rig = Rig(); val wire = rig.wire()
        val first = json(postText(wire, "/api/prompts/p", "one"))
        val second = json(postText(wire, "/api/prompts/p", "two"))
        val refused = postJson(wire, "/api/prompts/p", """{"text":"three","baseCid":"${first["cid"]}"}""")
        assertEquals(409, refused.status)
        val body = json(refused)
        assertEquals("stale_base", body["error"]); assertEquals(second["cid"], body["currentCid"])
        assertEquals("two", PromptDocument.fromJson(get(wire, "/api/prompts/p").body).text)
        assertEquals(200, postJson(wire, "/api/prompts/p", """{"text":"three","baseCid":"${second["cid"]}"}""").status)
    }

    @Test
    fun theRouteRefusesWhatItMust(): Unit = runBlocking {
        val rig = Rig(); val wire = rig.wire()
        assertEquals(400, postText(wire, "/api/prompts/Bad%20Name", "x").status)
        assertEquals(404, get(wire, "/api/prompts/nosuch").status)
        assertEquals(413, postText(wire, "/api/prompts/big", "x".repeat(PromptDocument.MAX_CHARS + 1)).status)
        assertEquals(400, postJson(wire, "/api/prompts/r", """{"text":"x","role":"oracle"}""").status)
        assertEquals(400, postJson(wire, "/api/prompts/r", """{"role":"user"}""").status)
        val unwired = rig.wire(withStore = false)
        assertEquals(503, get(unwired, "/api/prompts").status)
        assertTrue(get(unwired, "/api/prompts").body.contains("not wired"))
    }
}
