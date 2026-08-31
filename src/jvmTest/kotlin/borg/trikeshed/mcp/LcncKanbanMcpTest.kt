package borg.trikeshed.mcp

import borg.trikeshed.job.CasStore
import borg.trikeshed.kanban.BoardStoreElement
import borg.trikeshed.kanban.JvmBoardWal
import borg.trikeshed.lcnc.LcncKanbanExperience
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The MCP projection proved against a real board, not a mock: every assertion
 * below runs a JSON-RPC document through [LcncKanbanMcp] into a live
 * [BoardStoreElement] with a WAL on disk.
 *
 * The gate these tests exist for is the audit's ownership rule — MCP is a lens,
 * LCNC owns the composition, the store owns durability. A test that only proved
 * "a card moved" would pass just as well against a store bypass, so the first
 * two tests are about WHICH path the write took.
 */
class LcncKanbanMcpTest {

    // ── harness ───────────────────────────────────────────────────────

    private class Rig(
        val mcp: LcncKanbanMcp,
        val store: BoardStoreElement,
        val receipts: KanbanReceiptLog,
        val calls: MutableList<String>,
    )

    private fun tempDir(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "mcp-kanban-$name-${System.nanoTime()}").apply { mkdirs() }

    /**
     * Runs [block] against a live board: WAL on disk, store open, receipt
     * collector attached. The collector is cancelled on the way out — a
     * `collect` on the store's flow never completes on its own, and an
     * un-cancelled one would hang `runBlocking` forever.
     *
     * [registryOverride] lets a test substitute the LCNC registry so it can
     * observe — or withhold — the only write path.
     */
    private fun withRig(
        name: String,
        registryOverride: ((Map<String, LcncNodeRunner>, MutableList<String>) -> Map<String, LcncNodeRunner>)? = null,
        block: suspend CoroutineScope.(Rig) -> Unit,
    ) = runBlocking {
        val rig = build(name, registryOverride)
        rig.store.open()
        val attached = CompletableDeferred<Unit>()
        val collector = launch {
            rig.store.committed
                .onSubscription { attached.complete(Unit) }
                .collect { rig.receipts.record(it) }
        }
        // The store emits with tryEmit and no replay buffer: a receipt is lost
        // unless the collector is already attached when the command commits, so
        // wait for the subscription rather than racing it.
        withTimeout(5_000) { attached.await() }
        try {
            block(rig)
        } finally {
            collector.cancel()
            rig.store.drain()
        }
    }

    private fun build(
        name: String,
        registryOverride: ((Map<String, LcncNodeRunner>, MutableList<String>) -> Map<String, LcncNodeRunner>)? = null,
    ): Rig {
        val store = BoardStoreElement(JvmBoardWal(tempDir(name)), CasStore.inMemory(), clock = { 42L })
        val experience = LcncKanbanExperience(store)
        val calls = mutableListOf<String>()
        val registry = experience.registry().let { base ->
            registryOverride?.invoke(base, calls) ?: base
        }
        val receipts = KanbanReceiptLog()
        return Rig(
            LcncKanbanMcp(tools = registry, reads = BoardKanbanReadPort(store, experience, receipts)),
            store,
            receipts,
            calls,
        )
    }

    // ── JSON-RPC helpers ──────────────────────────────────────────────

    private var nextId = 0

    private suspend fun call(rig: Rig, method: String, params: Map<String, Any?>? = null): Map<*, *> {
        val id = ++nextId
        val doc = buildMap<String, Any?> {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            params?.let { put("params", it) }
        }
        val reply = rig.mcp.handle(JsonSupport.stringify(doc))
        val parsed = JsonSupport.parse(reply) as? Map<*, *> ?: error("not a JSON object: $reply")
        assertEquals("2.0", parsed["jsonrpc"], reply)
        assertEquals(id.toLong(), num(parsed["id"]), "id must echo as an integer, not a float: $reply")
        return parsed
    }

    private suspend fun ok(rig: Rig, method: String, params: Map<String, Any?>? = null): Map<*, *> {
        val parsed = call(rig, method, params)
        assertNull(parsed["error"], "expected a result from $method, got ${parsed["error"]}")
        return parsed["result"] as? Map<*, *> ?: error("$method result was not an object")
    }

    private suspend fun toolCall(rig: Rig, name: String, args: Map<String, Any?>): Map<*, *> =
        ok(rig, "tools/call", mapOf("name" to name, "arguments" to args))

    private fun structured(result: Map<*, *>): Map<*, *> =
        result["structuredContent"] as? Map<*, *> ?: error("no structuredContent in $result")

    private suspend fun readResource(rig: Rig, uri: String): Map<*, *> {
        val result = ok(rig, "resources/read", mapOf("uri" to uri))
        val contents = result["contents"] as? List<*> ?: error("no contents")
        val first = contents.first() as Map<*, *>
        assertEquals(uri, first["uri"])
        return JsonSupport.parse(first["text"] as String) as Map<*, *>
    }

    private fun num(value: Any?): Long? = (value as? Number)?.toLong()

    private fun text(result: Map<*, *>): String =
        ((result["content"] as List<*>).first() as Map<*, *>)["text"] as String

    /** Submit a card and return its jobId. */
    private suspend fun submit(rig: Rig, title: String, args: Map<String, Any?> = emptyMap()): String {
        val r = toolCall(rig, LcncKanbanMcp.TOOL_SUBMIT, mapOf("title" to title) + args)
        val s = structured(r)
        assertEquals(true, s["accepted"], "submit '$title' refused: ${s["reason"]}")
        return s["jobId"] as String
    }

    // ── the ownership rule ────────────────────────────────────────────

    @Test
    // The registry is wrapped, not replaced: each runner still does the real
    // work, but records that MCP came through it. If a future change gave the
    // handler a store handle and skipped LCNC, the board would still change and
    // this list would be empty.
    fun everyMcpWriteTravelsThroughTheLcncRegistry() = withRig(
        "ownership",
        registryOverride = { base, calls ->
            base.mapValues { (name, runner) ->
                LcncNodeRunner { node, inputs ->
                    calls += name
                    runner.run(node, inputs)
                }
            }
        },
    ) { rig ->
        val jobId = submit(rig, "Ownership holds")
        assertEquals(listOf(LcncKanbanMcp.TOOL_SUBMIT), rig.calls)

        val card = readResource(rig, "${LcncKanbanMcp.URI_CARD_PREFIX}$jobId")
        toolCall(
            rig,
            LcncKanbanMcp.TOOL_MOVE,
            mapOf("jobId" to jobId, "toColumn" to "ready", "expectedRevision" to num(card["revision"])),
        )
        assertEquals(listOf(LcncKanbanMcp.TOOL_SUBMIT, LcncKanbanMcp.TOOL_MOVE), rig.calls)

        // And the write really did land in the durable store, not in the lens.
        assertEquals("ready", rig.store.card(jobId)!!.col.wire)
    }

    @Test
    // Withhold the registry entirely. A handler holding a store reference could
    // still write; this one reports that it cannot, and the board stays empty —
    // the ownership rule is structural, not conventional.
    fun withoutTheLcncRunnerThereIsNoOtherWayIn() = withRig(
        "no-registry",
        registryOverride = { _, _ -> emptyMap() },
    ) { rig ->
        val result = call(
            rig,
            "tools/call",
            mapOf("name" to LcncKanbanMcp.TOOL_SUBMIT, "arguments" to mapOf("title" to "should not land")),
        )
        // A missing runner is a server misconfiguration, not a board verdict, so
        // it surfaces as a protocol error rather than a plausible-looking refusal.
        val error = result["error"] as Map<*, *>
        assertEquals(LcncKanbanMcp.INTERNAL_ERROR.toLong(), num(error["code"]))
        assertTrue(LcncKanbanMcp.TOOL_SUBMIT in (error["message"] as String))
        assertEquals(0, rig.store.cards().size)
    }

    // ── the board's guards, seen through MCP ──────────────────────────

    @Test
    fun moveIsCompareAndSetAndAStaleRevisionIsRefused() = withRig("cas") { rig ->
        val jobId = submit(rig, "Compare and set")

        val card = readResource(rig, "${LcncKanbanMcp.URI_CARD_PREFIX}$jobId")
        val revision = num(card["revision"])!!

        val moved = structured(
            toolCall(
                rig,
                LcncKanbanMcp.TOOL_MOVE,
                mapOf("jobId" to jobId, "toColumn" to "ready", "expectedRevision" to revision),
            ),
        )
        assertEquals(true, moved["accepted"])
        assertEquals(revision + 1, num(moved["revision"]))

        // The same revision a second time is stale — the card has moved on.
        val stale = toolCall(
            rig,
            LcncKanbanMcp.TOOL_MOVE,
            mapOf("jobId" to jobId, "toColumn" to "done", "expectedRevision" to revision),
        )
        assertEquals(true, stale["isError"], "a stale move must surface as a tool error")
        val reason = structured(stale)["reason"] as String
        assertTrue("stale expectedRevision" in reason, reason)
        // The refusal is legible without parsing: the text block carries it too.
        assertTrue("refused" in text(stale), text(stale))
        // And the board did NOT move.
        assertEquals("ready", rig.store.card(jobId)!!.col.wire)
    }

    @Test
    fun aDuplicateIdempotencyKeyIsRefusedRatherThanAppliedTwice() = withRig("idempotency") { rig ->

        val first = structured(
            toolCall(rig, LcncKanbanMcp.TOOL_SUBMIT, mapOf("title" to "Once", "idempotencyKey" to "k1")),
        )
        assertEquals(true, first["accepted"])

        val again = toolCall(rig, LcncKanbanMcp.TOOL_SUBMIT, mapOf("title" to "Once", "idempotencyKey" to "k1"))
        assertEquals(true, again["isError"])
        assertTrue("duplicate idempotencyKey" in (structured(again)["reason"] as String))
        assertEquals(1, rig.store.cards().size)
    }

    @Test
    fun theWipLimitRefusesTheFourthRunningCard() = withRig("wip") { rig ->

        val ids = (1..4).map { submit(rig, "Work $it") }
        val accepted = ids.map { jobId ->
            val card = readResource(rig, "${LcncKanbanMcp.URI_CARD_PREFIX}$jobId")
            val r = toolCall(
                rig,
                LcncKanbanMcp.TOOL_MOVE,
                mapOf("jobId" to jobId, "toColumn" to "running", "expectedRevision" to num(card["revision"])),
            )
            structured(r)["accepted"] == true
        }
        assertEquals(listOf(true, true, true, false), accepted, "running holds 3")
        assertEquals(3, rig.store.cards().count { it.col.wire == "running" })
    }

    @Test
    fun aDependencyCycleIsRefusedThroughMcpToo() = withRig("cycle") { rig ->

        val x = submit(rig, "X waits on Y", mapOf("dependencies" to listOf("y-card")))
        assertEquals(listOf("y-card"), rig.store.card(x)!!.dependencies)

        val cyclic = toolCall(
            rig,
            LcncKanbanMcp.TOOL_SUBMIT,
            mapOf("title" to "Y", "jobId" to "y-card", "dependencies" to listOf(x)),
        )
        assertEquals(true, cyclic["isError"])
        assertTrue("cycle" in (structured(cyclic)["reason"] as String))
    }

    // ── the read projection ───────────────────────────────────────────

    @Test
    fun tagsDependenciesAndOwnerSurviveTheWriteAndComeBackOnTheCard() = withRig("full-card") { rig ->
        // The store has always persisted all three, and /api/board's `enrich`
        // already returns them — but the LCNC submit runner dropped them on the
        // way IN, so they could not be set at all through a runner. This is the
        // regression test for that write path, read back through the resource.

        val jobId = submit(
            rig,
            "Fully described",
            mapOf(
                "tags" to listOf("marketability", "mcp"),
                "dependencies" to listOf("some-other-card"),
                "owner" to "jim",
                "priority" to 0,
            ),
        )

        val card = readResource(rig, "${LcncKanbanMcp.URI_CARD_PREFIX}$jobId")
        assertEquals(listOf("marketability", "mcp"), card["tags"])
        assertEquals(listOf("some-other-card"), card["dependencies"])
        assertEquals("jim", card["owner"])
        assertEquals(0L, num(card["priority"]))
        // A submit lands in 'todo' — the lifecycle mapping, not 'triage'.
        assertEquals("todo", card["status"])

        // The row itself carries them — the resource is projecting, not decorating.
        val row = rig.store.card(jobId)!!
        assertEquals(listOf("marketability", "mcp"), row.tags)
        assertEquals("jim", row.owner)
    }

    @Test
    fun aCommittedChangeHasAReadableReceiptAnchoredInCas() = withRig("receipts") { rig ->

        val jobId = submit(rig, "Leaves a receipt")
        val card = readResource(rig, "${LcncKanbanMcp.URI_CARD_PREFIX}$jobId")
        val sequence = num(card["lastSequence"])!!

        val receipt = readResource(rig, "${LcncKanbanMcp.URI_RECEIPT_PREFIX}$sequence")
        assertEquals(jobId, receipt["jobId"])
        assertEquals(sequence, num(receipt["sequence"]))
        assertEquals("submit", receipt["command"])
        assertEquals("committed", receipt["source"])
        // The durable anchor, not a synthesized id.
        val cid = receipt["cid"] as? String
        assertNotNull(cid, "a committed receipt must carry the CAS id of the raw command")
        assertTrue(cid.isNotBlank())
    }

    @Test
    fun aReceiptOutsideRetentionIsAbsentRatherThanInvented() = withRig("retention") { rig ->
        submit(rig, "Only one")

        val parsed = call(rig, "resources/read", mapOf("uri" to "${LcncKanbanMcp.URI_RECEIPT_PREFIX}99999"))
        val error = parsed["error"] as Map<*, *>
        assertEquals(LcncKanbanMcp.RESOURCE_NOT_FOUND.toLong(), num(error["code"]))
    }

    @Test
    fun replaySeedingRebuildsReceiptsWithoutFabricatingAContentId() = withRig("seed") { rig ->
        val jobId = submit(rig, "Survives a restart")
        val sequence = rig.store.card(jobId)!!.lastSequence
        rig.store.drain()

        // A fresh process: rows replayed from the WAL, no committed events to catch.
        val cold = KanbanReceiptLog()
        cold.seedFrom(rig.store.cards())
        val seeded = assertNotNull(cold.get(sequence), "a replayed card's receiptResource must resolve")
        assertEquals(jobId, seeded["jobId"])
        assertEquals("replay", seeded["source"])
        assertNull(seeded["cid"], "no cid is recoverable at replay — it must not be invented")
    }

    @Test
    fun theSchemaPublishesTheColumnsWipLimitsAndGuardsThatAreActuallyEnforced() = withRig("schema") { rig ->

        val schema = readResource(rig, LcncKanbanMcp.URI_SCHEMA)
        val columns = schema["columns"] as List<*>
        assertEquals(
            listOf("triage", "todo", "ready", "running", "blocked", "done", "archived"),
            columns.map { (it as Map<*, *>)["id"] },
        )
        // The one WIP limit the store enforces, published as the number it enforces.
        val running = columns.map { it as Map<*, *> }.first { it["id"] == "running" }
        assertEquals(3L, num(running["wipLimit"]))
        assertNull(columns.map { it as Map<*, *> }.first { it["id"] == "todo" }["wipLimit"])

        val guards = (schema["guards"] as List<*>).map { (it as Map<*, *>)["name"] }
        assertEquals(listOf("idempotency", "expectedRevision", "wipLimit", "dependencyCycle"), guards)
        assertEquals("open", (schema["transitionPolicy"] as Map<*, *>)["kind"])
    }

    @Test
    fun sheetsAreProjectedFreshFromTheStore() = withRig("sheets") { rig ->
        submit(rig, "On the board")

        val sheets = readResource(rig, LcncKanbanMcp.URI_SHEETS)
        val boardView = sheets["boardView"] as Map<*, *>
        val items = boardView["items"] as List<*>
        assertEquals(1, items.size)
        assertEquals("On the board", (items.first() as Map<*, *>)["title"])
        assertEquals(num(boardView["sequence"]), num(sheets["watermark"]))
    }

    // ── the protocol envelope ─────────────────────────────────────────

    @Test
    fun handshakeNegotiatesAVersionAndAdvertisesOnlyWhatIsImplemented() = withRig("handshake") { rig ->

        val known = ok(rig, "initialize", mapOf("protocolVersion" to "2025-06-18"))
        assertEquals("2025-06-18", known["protocolVersion"], "a recognized client version is echoed")

        // The live failure this guards: Claude Code asks for a revision we do not
        // list, and answering with our newest made it hang up. Negotiate down.
        val newer = ok(rig, "initialize", mapOf("protocolVersion" to "2025-11-25"))
        assertEquals("2025-06-18", newer["protocolVersion"], "an unknown newer ask negotiates down")

        val unknown = ok(rig, "initialize", mapOf("protocolVersion" to "1999-01-01"))
        assertEquals(LcncKanbanMcp.BASELINE_PROTOCOL, unknown["protocolVersion"])

        val caps = unknown["capabilities"] as Map<*, *>
        assertEquals(false, (caps["resources"] as Map<*, *>)["subscribe"], "do not advertise a push we do not do")
        assertEquals(LcncKanbanMcp.SERVER_NAME, (unknown["serverInfo"] as Map<*, *>)["name"])
    }

    @Test
    fun toolsAndResourcesAreDiscoverable() = withRig("discovery") { rig ->

        val tools = (ok(rig, "tools/list")["tools"] as List<*>).map { (it as Map<*, *>)["name"] }
        assertEquals(listOf(LcncKanbanMcp.TOOL_SUBMIT, LcncKanbanMcp.TOOL_MOVE), tools)

        // A move's schema must name the columns, or a client has to guess them.
        val move = (ok(rig, "tools/list")["tools"] as List<*>)
            .map { it as Map<*, *> }.first { it["name"] == LcncKanbanMcp.TOOL_MOVE }
        val schema = move["inputSchema"] as Map<*, *>
        val toColumn = (schema["properties"] as Map<*, *>)["toColumn"] as Map<*, *>
        assertEquals(LcncKanbanMcp.columnWires(), toColumn["enum"])
        assertEquals(listOf("jobId", "toColumn", "expectedRevision"), schema["required"])

        val resources = (ok(rig, "resources/list")["resources"] as List<*>).map { (it as Map<*, *>)["uri"] }
        assertEquals(listOf(LcncKanbanMcp.URI_SCHEMA, LcncKanbanMcp.URI_SHEETS), resources)

        val templates = (ok(rig, "resources/templates/list")["resourceTemplates"] as List<*>)
            .map { (it as Map<*, *>)["uriTemplate"] }
        assertEquals(
            listOf("${LcncKanbanMcp.URI_CARD_PREFIX}{jobId}", "${LcncKanbanMcp.URI_RECEIPT_PREFIX}{sequence}"),
            templates,
        )
    }

    @Test
    fun malformedTrafficGetsTheRightJsonRpcCode() = withRig("envelope") { rig ->

        fun errorCode(reply: String): Long {
            val parsed = JsonSupport.parse(reply) as Map<*, *>
            return num((parsed["error"] as Map<*, *>)["code"])!!
        }

        assertEquals(LcncKanbanMcp.PARSE_ERROR.toLong(), errorCode(rig.mcp.handle("{ not json")))
        assertEquals(
            LcncKanbanMcp.INVALID_REQUEST.toLong(),
            errorCode(rig.mcp.handle("""[{"jsonrpc":"2.0","id":1,"method":"ping"}]""")),
            "batching was removed from MCP and must be refused, not half-honoured",
        )
        assertEquals(
            LcncKanbanMcp.METHOD_NOT_FOUND.toLong(),
            num((call(rig, "nonsense/method")["error"] as Map<*, *>)["code"]),
        )
        assertEquals(
            LcncKanbanMcp.RESOURCE_NOT_FOUND.toLong(),
            num((call(rig, "resources/read", mapOf("uri" to "oroboros://nope"))["error"] as Map<*, *>)["code"]),
        )

        // A notification gets no response document at all.
        assertEquals("", rig.mcp.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test
    fun badToolArgumentsAreNamedBeforeTheyReachTheBoard() = withRig("args") { rig ->

        val noGesture = call(rig, "tools/call", mapOf("name" to LcncKanbanMcp.TOOL_SUBMIT, "arguments" to emptyMap<String, Any?>()))
        val message = ((noGesture["error"] as Map<*, *>)["message"] as String)
        assertTrue("title" in message && "jobId" in message, message)

        val badColumn = call(
            rig,
            "tools/call",
            mapOf(
                "name" to LcncKanbanMcp.TOOL_MOVE,
                "arguments" to mapOf("jobId" to "x", "toColumn" to "elsewhere", "expectedRevision" to 1),
            ),
        )
        val columnMessage = ((badColumn["error"] as Map<*, *>)["message"] as String)
        assertTrue("elsewhere" in columnMessage && "triage" in columnMessage, columnMessage)

        // A move without a revision must say where to get one.
        val noRevision = call(
            rig,
            "tools/call",
            mapOf("name" to LcncKanbanMcp.TOOL_MOVE, "arguments" to mapOf("jobId" to "x", "toColumn" to "done")),
        )
        val revisionMessage = ((noRevision["error"] as Map<*, *>)["message"] as String)
        assertTrue(LcncKanbanMcp.URI_CARD_PREFIX in revisionMessage, revisionMessage)

        assertEquals(0, rig.store.cards().size, "no malformed call reached the board")
    }

    @Test
    fun aRepeatedMoveDedupesWithoutTheClientInventingAKey() = withRig("default-key") { rig ->
        val jobId = submit(rig, "Retry me")
        val revision = rig.store.card(jobId)!!.revision

        val args = mapOf("jobId" to jobId, "toColumn" to "ready", "expectedRevision" to revision)
        assertEquals(true, structured(toolCall(rig, LcncKanbanMcp.TOOL_MOVE, args))["accepted"])

        // The identical document again — a network retry, say. The default key is
        // derived from the move itself, so it dedupes instead of double-moving.
        val retry = toolCall(rig, LcncKanbanMcp.TOOL_MOVE, args)
        assertEquals(true, retry["isError"])
        val reason = structured(retry)["reason"] as String
        assertTrue("duplicate idempotencyKey" in reason || "stale expectedRevision" in reason, reason)
        assertEquals("ready", rig.store.card(jobId)!!.col.wire)
    }

    @Test
    fun acceptedWritesHandBackTheReferencesNeededForTheNextCall() = withRig("references") { rig ->
        // Audit import gate 4: every mutation returns idempotency key, revision,
        // sequence and a durable receipt reference.

        val s = structured(toolCall(rig, LcncKanbanMcp.TOOL_SUBMIT, mapOf("title" to "Traceable")))
        val jobId = s["jobId"] as String
        assertNotNull(num(s["revision"]))
        assertNotNull(num(s["sequence"]))
        assertEquals("submit#$jobId", s["idempotencyKey"])
        assertTrue((s["cid"] as String).isNotBlank())
        assertEquals("${LcncKanbanMcp.URI_CARD_PREFIX}$jobId", s["cardResource"])
        assertEquals("${LcncKanbanMcp.URI_RECEIPT_PREFIX}${num(s["sequence"])}", s["receiptResource"])
        assertEquals(LcncKanbanMcp.URI_SHEETS, s["sheetsResource"])

        // Both references resolve.
        readResource(rig, s["cardResource"] as String)
        readResource(rig, s["receiptResource"] as String)
    }

    @Test
    fun theSameTitleAlwaysMintsTheSameCardId() = withRig("stable-a") { a ->
        // "Stable ids survive unchanged" — the minted id is a content hash, so a
        // client that forgets an id can recompute the same card rather than
        // creating a twin. Proved across two boards that share nothing: separate
        // WALs, separate CAS, no common state but the title.
        val first = submit(a, "Same title")
        val b = build("stable-b")
        b.store.open()
        try {
            assertEquals(first, submit(b, "Same title"))
        } finally {
            b.store.drain()
        }
    }
}
