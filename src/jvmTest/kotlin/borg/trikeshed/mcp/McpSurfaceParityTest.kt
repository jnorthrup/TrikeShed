package borg.trikeshed.mcp

import borg.trikeshed.job.CasStore
import borg.trikeshed.kanban.BoardCol
import borg.trikeshed.kanban.BoardStoreElement
import borg.trikeshed.kanban.JvmBoardWal
import borg.trikeshed.lcnc.LcncKanbanExperience
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * KMFSM-009, the MCP half: the surface must not drift from itself, from the
 * board vocabulary, or from the guide that sells it.
 *
 * The audit is blunt about why this exists — "adding MCP without a parity gate
 * would create another untrusted description". An MCP surface describes itself
 * four times over (the schema resource, `tools/list`, `resources/list`, the
 * templates) and a fifth time in `docs/guide-mcp-kanban.md`. Five descriptions
 * of one thing is five chances to be wrong, and a client that trusts the wrong
 * one gets a confident answer that does not work.
 *
 * The doc assertions are the ones with teeth for marketability: the guide is
 * what a buyer reads before anything runs. A guide that quotes a URI the server
 * does not serve is worse than no guide. Verified by mutation — appending a
 * bogus `oroboros://…` URI to the guide fails
 * [theGuideQuotesOnlyUrisAndToolsTheServerActuallyServes] by name.
 *
 * KNOWN LIMIT, so nobody trusts this further than it goes: `docs/` is not a
 * declared input of the `jvmTest` task, so Gradle considers the task up to date
 * when ONLY the guide changed and skips it. A doc-only edit can therefore land
 * drift until the next source change (or a `--rerun-tasks` / clean CI run)
 * re-runs this. Declaring the doc as a task input in build.gradle.kts would
 * close that, and is the right fix if doc-only drift ever actually bites.
 */
class McpSurfaceParityTest {

    private fun rig(): Pair<LcncKanbanMcp, BoardStoreElement> {
        val dir = File(System.getProperty("java.io.tmpdir"), "mcp-parity-${System.nanoTime()}").apply { mkdirs() }
        val store = BoardStoreElement(JvmBoardWal(dir), CasStore.inMemory(), clock = { 1L })
        val experience = LcncKanbanExperience(store)
        return LcncKanbanMcp(
            tools = experience.registry(),
            reads = BoardKanbanReadPort(store, experience, KanbanReceiptLog()),
        ) to store
    }

    private suspend fun result(mcp: LcncKanbanMcp, method: String, params: Map<String, Any?>? = null): Map<*, *> {
        val doc = buildMap<String, Any?> {
            put("jsonrpc", "2.0"); put("id", 1); put("method", method)
            params?.let { put("params", it) }
        }
        val parsed = JsonSupport.parse(mcp.handle(JsonSupport.stringify(doc))) as Map<*, *>
        return parsed["result"] as? Map<*, *> ?: fail("$method returned ${parsed["error"]}")
    }

    private suspend fun readResource(mcp: LcncKanbanMcp, uri: String): Map<*, *> {
        val contents = result(mcp, "resources/read", mapOf("uri" to uri))["contents"] as List<*>
        return JsonSupport.parse((contents.first() as Map<*, *>)["text"] as String) as Map<*, *>
    }

    private fun guide(): String {
        val root = System.getProperty("user.dir") ?: fail("no user.dir")
        val f = File(root, "docs/guide-mcp-kanban.md")
        if (!f.isFile) fail("docs/guide-mcp-kanban.md is missing — the MCP surface has no guide")
        return f.readText()
    }

    @Test
    fun theSurfaceDescribesTheSameToolsEverywhere() = runBlocking {
        val (mcp, store) = rig()
        store.open()
        val listed = (result(mcp, "tools/list")["tools"] as List<*>).map { (it as Map<*, *>)["name"] as String }
        val constants = listOf(LcncKanbanMcp.TOOL_SUBMIT, LcncKanbanMcp.TOOL_MOVE)
        assertEquals(constants, listed, "tools/list disagrees with the TOOL_ constants")

        val schema = readResource(mcp, LcncKanbanMcp.URI_SCHEMA)
        assertEquals(
            constants,
            (schema["tools"] as List<*>).map { it as String },
            "the schema resource advertises different tools than tools/list — a client reading either is misled",
        )
        store.drain()
    }

    @Test
    fun theColumnVocabularyIsOneVocabulary() = runBlocking {
        val (mcp, store) = rig()
        store.open()
        val boardOrder = BoardCol.entries.sortedBy { it.order }.map { it.wire }

        val schema = readResource(mcp, LcncKanbanMcp.URI_SCHEMA)
        assertEquals(
            boardOrder,
            (schema["columns"] as List<*>).map { (it as Map<*, *>)["id"] as String },
            "the schema resource and BoardCol disagree about the columns",
        )

        // The move tool's enum is what a client validates against BEFORE calling.
        // If it drifts from the board, the client's own validation rejects legal
        // moves — or worse, admits illegal ones.
        val move = (result(mcp, "tools/list")["tools"] as List<*>)
            .map { it as Map<*, *> }.first { it["name"] == LcncKanbanMcp.TOOL_MOVE }
        val enum = ((move["inputSchema"] as Map<*, *>)["properties"] as Map<*, *>)
            .let { (it["toColumn"] as Map<*, *>)["enum"] as List<*> }.map { it as String }
        assertEquals(boardOrder, enum, "kanban.move's toColumn enum drifted from the board vocabulary")

        // And the one WIP limit the store enforces is reported as the number it enforces.
        val running = (schema["columns"] as List<*>).map { it as Map<*, *> }.first { it["id"] == "running" }
        assertEquals(
            BoardCol.RUNNING.wipLimit?.toLong(),
            (running["wipLimit"] as Number).toLong(),
            "the published WIP limit is not the enforced one",
        )
        store.drain()
    }

    @Test
    fun everyAdvertisedResourceIsReadableAndEveryTemplateIsBuiltFromItsConstant() = runBlocking {
        val (mcp, store) = rig()
        store.open()

        val concrete = (result(mcp, "resources/list")["resources"] as List<*>)
            .map { (it as Map<*, *>)["uri"] as String }
        assertEquals(listOf(LcncKanbanMcp.URI_SCHEMA, LcncKanbanMcp.URI_SHEETS), concrete)
        // Advertised means readable. A resource listed but not served is the exact
        // "untrusted description" the audit warns about.
        for (uri in concrete) readResource(mcp, uri)

        val templates = (result(mcp, "resources/templates/list")["resourceTemplates"] as List<*>)
            .map { (it as Map<*, *>)["uriTemplate"] as String }
        assertEquals(
            listOf("${LcncKanbanMcp.URI_CARD_PREFIX}{jobId}", "${LcncKanbanMcp.URI_RECEIPT_PREFIX}{sequence}"),
            templates,
            "the templates must be built from the same constants the reader resolves",
        )
        store.drain()
    }

    @Test
    fun theGuideQuotesOnlyUrisAndToolsTheServerActuallyServes() = runBlocking {
        val (mcp, store) = rig()
        store.open()
        val text = guide()

        // Everything the surface advertises must appear in the guide...
        for (uri in listOf(LcncKanbanMcp.URI_SCHEMA, LcncKanbanMcp.URI_SHEETS)) {
            assertTrue(uri in text, "the guide never mentions $uri")
        }
        for (prefix in listOf(LcncKanbanMcp.URI_CARD_PREFIX, LcncKanbanMcp.URI_RECEIPT_PREFIX)) {
            assertTrue(prefix in text, "the guide never mentions $prefix")
        }
        for (tool in listOf(LcncKanbanMcp.TOOL_SUBMIT, LcncKanbanMcp.TOOL_MOVE)) {
            assertTrue(tool in text, "the guide never documents the $tool tool")
        }

        // ...and the reverse: an `oroboros://` URI the guide quotes must resolve to
        // something the server knows about, so a reader can paste it and have it work.
        val advertised = listOf(
            LcncKanbanMcp.URI_SCHEMA,
            LcncKanbanMcp.URI_SHEETS,
            LcncKanbanMcp.URI_CARD_PREFIX,
            LcncKanbanMcp.URI_RECEIPT_PREFIX,
        )
        val quoted = Regex("""oroboros://[A-Za-z0-9/_{}.-]+""").findAll(text).map { it.value }.toSet()
        val unknown = quoted.filterNot { q -> advertised.any { q.startsWith(it) } }
        assertTrue(unknown.isEmpty(), "the guide quotes URIs the server does not serve: $unknown")

        // The guide states the WIP limit as a number a reader will rely on.
        val limit = BoardCol.RUNNING.wipLimit
        assertTrue(
            Regex("""[Oo]nly .?running.? is limited \($limit\)""").containsMatchIn(text) ||
                "limited ($limit)" in text,
            "the guide's stated WIP limit does not match the enforced limit of $limit",
        )
        store.drain()
    }

    @Test
    fun theGuidesProtocolVersionsAreTheOnesNegotiated() = runBlocking {
        val (mcp, store) = rig()
        store.open()
        val text = guide()
        for (version in LcncKanbanMcp.SUPPORTED_PROTOCOLS) {
            assertTrue(version in text, "the guide omits supported protocol version $version")
        }
        // A revision we do not speak negotiates DOWN to the newest we do that is
        // no newer than the ask. This is the case that took the server off the
        // air for every Claude Code client: it asks 2025-11-25, and answering
        // with our own newest (2026-07-28) is a version from beyond its horizon.
        val fromTheFuture = result(mcp, "initialize", mapOf("protocolVersion" to "2025-11-25"))["protocolVersion"]
        assertEquals("2025-06-18", fromTheFuture, "an unknown NEWER ask must negotiate down, not up")

        // Older than anything we speak: nothing to negotiate down to, so the
        // widely-implemented baseline is the best answer available.
        val ancient = result(mcp, "initialize", mapOf("protocolVersion" to "1999-01-01"))["protocolVersion"]
        assertEquals(LcncKanbanMcp.BASELINE_PROTOCOL, ancient)

        assertTrue(
            LcncKanbanMcp.BASELINE_PROTOCOL in text,
            "the guide does not name the version the server falls back to",
        )
        store.drain()
    }
}
