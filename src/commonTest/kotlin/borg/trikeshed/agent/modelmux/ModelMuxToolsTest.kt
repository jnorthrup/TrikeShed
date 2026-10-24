package borg.trikeshed.agent.modelmux

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.mcp.McpToolServer
import borg.trikeshed.mcp.ToolFailure
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val SCOPE = "team/a"
private const val NOTES = "The manifest must be verified before caching.\nfiller one\nfiller two\nCargo manifest lists forty crates of oranges.\n"

private suspend fun McpToolServer.rpc(id: Int, tool: String, args: Map<String, Any?> = emptyMap()): Map<*, *> =
    reifyStrict(handle(jsonOf(mapOf("jsonrpc" to "2.0", "id" to id, "method" to "tools/call", "params" to mapOf("name" to tool, "arguments" to args))))) as Map<*, *>

private fun Map<*, *>.result() = this["result"] as Map<*, *>
private fun Map<*, *>.data() = result()["structuredContent"] as Map<*, *>
private fun Map<*, *>.failed() = result()["isError"] == true
private fun Map<*, *>.protocolCode() = ((this["error"] as? Map<*, *>)?.get("code") as? Number)?.toInt()
private fun Map<*, *>.message() = (data()["error"] as? String).orEmpty()
private fun Map<*, *>.int(key: String) = (this[key] as Number).toInt()

private class FakePort : ModelPort {
    val seen = mutableListOf<Triple<String, List<ChatMessage>, Int?>>()
    override suspend fun models() = listOf(ModelInfo("m1", "fixture", setOf("tools", "chat")))
    override suspend fun chat(model: String, messages: List<ChatMessage>, maxTokens: Int?): ChatReply {
        seen += Triple(model, messages, maxTokens)
        return when (model) {
            "boom" -> throw RuntimeException("HTTP 429 Too Many Requests; Authorization: Bearer sk-SECRET123")
            "refuse" -> throw ToolFailure("model is not configured on this server")
            else -> ChatReply("hello", 3, 5)
        }
    }
}

/** Records the order of puts and syncs, so "durable before named" can be asserted rather than hoped for. */
private class RecordingCas : CasStore() {
    val events = mutableListOf<String>()
    override fun put(bytes: ByteArray): ContentId = super.put(bytes).also { events += "put" }
    override fun sync() { events += "sync" }
}

private class CountingJudge(private val notRelevantContaining: String? = null) : BreadcrumbOversight {
    var calls = 0
    override suspend fun review(question: String, projection: Projection): OversightReceipt {
        calls++
        val n = projection.excerpts.size
        val verdicts = List<Verdict?>(n) { i -> if (notRelevantContaining != null && notRelevantContaining in projection.excerpts[i].text) Verdict.NOT_RELEVANT else Verdict.RELEVANT }
        return OversightReceipt(OversightStatus.REVIEWED, "fake-system1", verdicts, List<Double?>(n) { i -> if (verdicts[i] == Verdict.NOT_RELEVANT) 0.1 else 0.9 }, null)
    }
}

class ModelMuxToolsTest {
    private fun context(vararg scopes: String) = SharedContext(CasStore.inMemory(), null, ContextReadPolicy(allowedScopes = scopes.toSet().ifEmpty { setOf(SCOPE) }))
    private fun serverOf(tools: ModelMuxTools, maxRequestChars: Int = 1_048_576) = McpToolServer("fixture", "1", tools.tools(), maxRequestChars = maxRequestChars)

    private suspend fun McpToolServer.put(text: String = NOTES, parents: List<String> = emptyList(), scope: String = SCOPE, name: String = "notes.md"): String {
        val r = rpc(1, "modelmux_context_put", buildMap {
            put("scope", scope); put("author", "alice")
            put("documents", listOf(mapOf("name" to name, "text" to text), mapOf("name" to "other.txt", "text" to "unrelated words only\n")))
            if (parents.isNotEmpty()) put("parents", parents)
        })
        assertFalse(r.failed(), r.toString())
        return r.data()["manifest"] as String
    }

    @Test
    fun theRegisteredToolsAreExactlyThePermissionSet() = runTest {
        assertEquals(emptySet(), ModelMuxTools().tools().map { it.name }.toSet())
        val readers = setOf("modelmux_context_acquire", "modelmux_context_read", "modelmux_breadcrumb_project")
        assertEquals(readers, ModelMuxTools(context()).tools().map { it.name }.toSet())
        assertEquals(readers + "modelmux_context_put", ModelMuxTools(context(), allowWrite = true).tools().map { it.name }.toSet())
        assertEquals(setOf("modelmux_models", "modelmux_chat"), ModelMuxTools(models = FakePort()).tools().map { it.name }.toSet())
        assertEquals(setOf("modelmux_models", "modelmux_chat"), ModelMuxTools(null, null, true, FakePort()).tools().map { it.name }.toSet(), "write needs a context to write to")

        val everything = serverOf(ModelMuxTools(context(), allowWrite = true, models = FakePort()))
        val listed = (reifyStrict(everything.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")) as Map<*, *>).result()["tools"] as List<*>
        val readOnly = listed.associate { (it as Map<*, *>)["name"] to ((it["annotations"] as Map<*, *>)["readOnlyHint"] as Boolean) }
        assertEquals(setOf("modelmux_context_put", "modelmux_chat"), readOnly.filterValues { !it }.keys, "only the tools that mutate or spend are not read-only")
    }

    @Test
    fun putAcquireReadAndProjectRoundTrip() = runTest {
        val server = serverOf(ModelMuxTools(context(), allowWrite = true))
        val manifest = server.put()
        assertEquals(71, manifest.length)

        val acquired = server.rpc(2, "modelmux_context_acquire", mapOf("manifest" to manifest, "scope" to SCOPE))
        assertFalse(acquired.failed(), acquired.toString())
        val documents = acquired.data()["documents"] as List<*>
        assertEquals(listOf("notes.md", "other.txt"), documents.map { (it as Map<*, *>)["name"] })
        assertEquals(NOTES.encodeToByteArray().size, (documents[0] as Map<*, *>).int("bytes"))
        assertEquals(manifest, acquired.data()["manifest"])
        assertEquals("alice", acquired.data()["author"])

        val read = server.rpc(3, "modelmux_context_read", mapOf("manifest" to manifest, "scope" to SCOPE, "document" to "notes.md", "startLine" to 1, "endLine" to 2))
        assertEquals("The manifest must be verified before caching.\nfiller one", read.data()["text"])
        assertEquals(1, read.data().int("firstLine")); assertEquals(2, read.data().int("lastLine"))
        assertEquals(false, read.data()["truncated"])
        assertEquals(NOTES.lines().size, read.data().int("totalLines"))

        val projected = server.rpc(4, "modelmux_breadcrumb_project", mapOf("manifest" to manifest, "scope" to SCOPE, "question" to "manifest verified caching"))
        assertFalse(projected.failed(), projected.toString())
        val text = projected.data()["text"] as String
        assertTrue(text.startsWith("@ notes.md:"), text)
        assertTrue("The manifest must be verified before caching." in text, text)
        assertTrue((projected.data()["charsUsed"] as Number).toInt() <= 2_000)
        val excerpt = (projected.data()["excerpts"] as List<*>)[0] as Map<*, *>
        assertEquals("notes.md", excerpt["document"])
        assertEquals((documents[0] as Map<*, *>)["cid"], excerpt["documentCid"])
        val range = excerpt["range"] as Map<*, *>
        assertTrue("manifest" in NOTES.substring((range["start"] as Number).toInt(), (range["end"] as Number).toInt()), "the range points at the cited source text")
        assertEquals("NOT_REQUESTED", (projected.data()["oversight"] as Map<*, *>)["status"])
    }

    @Test
    fun scopeIsEnforcedOnEveryToolAndMismatchesAreNamed() = runTest {
        val server = serverOf(ModelMuxTools(context(SCOPE, "team/b"), allowWrite = true))
        val manifest = server.put()

        val notServed = server.rpc(2, "modelmux_context_put", mapOf("scope" to "team/z", "author" to "a", "documents" to emptyList<Any>()))
        assertTrue(notServed.failed()); assertEquals("scope 'team/z' is not served here", notServed.message())

        for ((id, tool) in listOf(2 to "modelmux_context_acquire", 3 to "modelmux_context_read", 4 to "modelmux_breadcrumb_project")) {
            val args = mapOf("manifest" to manifest, "scope" to "team/z", "document" to "notes.md", "question" to "manifest")
            val r = server.rpc(id, tool, args.filterKeys { it in setOf("manifest", "scope") + (if (tool.endsWith("read")) setOf("document") else emptySet()) + (if (tool.endsWith("project")) setOf("question") else emptySet()) })
            assertTrue(r.failed(), "$tool: $r")
            assertEquals("FORBIDDEN_SCOPE $manifest", r.message(), tool)
        }
        val mismatch = server.rpc(5, "modelmux_context_acquire", mapOf("manifest" to manifest, "scope" to "team/b"))
        assertTrue(mismatch.failed()); assertEquals("SCOPE_MISMATCH $manifest", mismatch.message())
    }

    @Test
    fun identitiesAreCheckedBeforeAnyWorkIsDone() = runTest {
        val server = serverOf(ModelMuxTools(context(), allowWrite = true))
        assertEquals(-32602, server.rpc(1, "modelmux_context_acquire", mapOf("manifest" to "sha256:abc", "scope" to SCOPE)).protocolCode())
        val garbage = server.rpc(2, "modelmux_context_acquire", mapOf("manifest" to "sha256:" + "z".repeat(64), "scope" to SCOPE))
        assertTrue(garbage.failed()); assertEquals("manifest is not a sha256 identity", garbage.message())
        val absent = "sha256:" + "0".repeat(64)
        val missing = server.rpc(3, "modelmux_context_acquire", mapOf("manifest" to absent, "scope" to SCOPE))
        assertTrue(missing.failed()); assertTrue(absent in missing.message(), missing.message())
        val badParent = server.rpc(4, "modelmux_context_put", mapOf("scope" to SCOPE, "author" to "a", "documents" to emptyList<Any>(), "parents" to listOf("sha256:" + "q".repeat(64))))
        assertTrue(badParent.failed()); assertEquals("parent is not a sha256 identity", badParent.message())
    }

    @Test
    fun readReturnsWholeLinesAndSaysWhenItStopped() = runTest {
        val server = serverOf(ModelMuxTools(context(), allowWrite = true))
        val manifest = server.put()
        fun args(vararg extra: Pair<String, Any?>) = mapOf("manifest" to manifest, "scope" to SCOPE, "document" to "notes.md") + extra

        val short = server.rpc(2, "modelmux_context_read", args("maxChars" to 60))
        assertEquals("The manifest must be verified before caching.\nfiller one", short.data()["text"], "two whole lines fit in 60 characters; a third would not")
        assertEquals(true, short.data()["truncated"]); assertEquals(2, short.data().int("lastLine"))

        val tiny = server.rpc(3, "modelmux_context_read", args("maxChars" to 10))
        assertEquals("", tiny.data()["text"], "a first line that exceeds the budget is never clipped mid-line")
        assertEquals(true, tiny.data()["truncated"])

        val backwards = server.rpc(4, "modelmux_context_read", args("startLine" to 3, "endLine" to 2))
        assertTrue(backwards.failed()); assertEquals("endLine is before startLine", backwards.message())
        val past = server.rpc(5, "modelmux_context_read", args("startLine" to 9_999))
        assertTrue(past.failed()); assertTrue("past the end" in past.message(), past.message())
        val unknown = server.rpc(6, "modelmux_context_read", mapOf("manifest" to manifest, "scope" to SCOPE, "document" to "nope.md"))
        assertTrue(unknown.failed()); assertEquals("no such document in this revision: nope.md", unknown.message())
    }

    @Test
    fun oversightIsRequestedPerCallAndUnavailabilityIsNeverApproval() = runTest {
        val bare = serverOf(ModelMuxTools(context(), allowWrite = true))
        val manifest = bare.put()
        val q = mapOf("manifest" to manifest, "scope" to SCOPE, "question" to "manifest")

        val required = bare.rpc(2, "modelmux_breadcrumb_project", q + ("oversight" to "required"))
        assertTrue(required.failed()); assertEquals("oversight is required but the judge is UNAVAILABLE", required.message())

        val advisory = bare.rpc(3, "modelmux_breadcrumb_project", q + ("oversight" to "advisory"))
        assertFalse(advisory.failed())
        assertEquals("UNAVAILABLE", (advisory.data()["oversight"] as Map<*, *>)["status"])
        assertTrue((advisory.data()["excerpts"] as List<*>).all { (it as Map<*, *>)["shown"] == true }, "nothing is pruned on the strength of a judge that did not run")
        assertTrue("oranges" in (advisory.data()["text"] as String))

        val judge = CountingJudge(notRelevantContaining = "oranges")
        val judged = serverOf(ModelMuxTools(context(), judge, allowWrite = true))
        val m2 = judged.put()
        val q2 = mapOf("manifest" to m2, "scope" to SCOPE, "question" to "manifest")

        val off = judged.rpc(2, "modelmux_breadcrumb_project", q2)
        assertEquals(0, judge.calls, "oversight is opt-in per call: the judge is not consulted by default")
        assertEquals("NOT_REQUESTED", (off.data()["oversight"] as Map<*, *>)["status"])

        val reviewed = judged.rpc(3, "modelmux_breadcrumb_project", q2 + ("oversight" to "required"))
        assertEquals(1, judge.calls)
        assertEquals("REVIEWED", (reviewed.data()["oversight"] as Map<*, *>)["status"])
        assertEquals("fake-system1", (reviewed.data()["oversight"] as Map<*, *>)["model"])
        val shown = (reviewed.data()["excerpts"] as List<*>).map { it as Map<*, *> }
        assertTrue(shown.any { it["shown"] == false }, "the judge pruned the irrelevant excerpt from the view")
        assertFalse("oranges" in (reviewed.data()["text"] as String))
        val still = judged.rpc(4, "modelmux_context_read", mapOf("manifest" to m2, "scope" to SCOPE, "document" to "notes.md", "startLine" to 4, "endLine" to 4))
        assertEquals("Cargo manifest lists forty crates of oranges.", still.data()["text"], "pruning is a view; the source is untouched")
    }

    @Test
    fun modelsSitBehindAPortAndNoCredentialOrProviderTextEverReachesTheClient() = runTest {
        val port = FakePort()
        val server = serverOf(ModelMuxTools(models = port))
        val models = server.rpc(1, "modelmux_models")
        assertEquals(listOf(mapOf("id" to "m1", "provider" to "fixture", "capabilities" to listOf("chat", "tools"))), models.data()["models"])

        val ok = server.rpc(2, "modelmux_chat", mapOf("model" to "m1", "maxTokens" to 16, "messages" to listOf(mapOf("role" to "user", "content" to "hi"))))
        assertEquals("hello", ok.data()["text"]); assertEquals(3, ok.data().int("inputTokens")); assertEquals(5, ok.data().int("outputTokens"))
        assertEquals(Triple("m1", listOf(ChatMessage("user", "hi")), 16), port.seen.single())

        val boom = server.rpc(3, "modelmux_chat", mapOf("model" to "boom", "messages" to listOf(mapOf("role" to "user", "content" to "hi"))))
        assertTrue(boom.failed()); assertEquals("chat failed: RuntimeException (HTTP 429)", boom.message())
        val raw = jsonOf(boom)
        assertFalse("sk-SECRET123" in raw || "Bearer" in raw || "Authorization" in raw, "provider text must not be relayed: $raw")

        val refused = server.rpc(4, "modelmux_chat", mapOf("model" to "refuse", "messages" to listOf(mapOf("role" to "user", "content" to "x"))))
        assertEquals("model is not configured on this server", refused.message(), "a ToolFailure's own text is the safe, intended message")

        val empty = server.rpc(5, "modelmux_chat", mapOf("model" to "m1", "messages" to emptyList<Any>()))
        assertTrue(empty.failed()); assertEquals("messages must not be empty", empty.message())
        assertEquals(-32602, server.rpc(6, "modelmux_chat", mapOf("model" to "m1", "messages" to listOf(mapOf("role" to "tool", "content" to "x")))).protocolCode())
        assertEquals(3, port.seen.size, "refused calls never reached the port")
    }

    @Test
    fun putIsBoundedAndNeverOverwrites() = runTest {
        val server = serverOf(ModelMuxTools(context(), allowWrite = true))
        fun put(docs: List<Map<String, Any?>>) = mapOf("scope" to SCOPE, "author" to "a", "documents" to docs)

        assertEquals(-32602, server.rpc(1, "modelmux_context_put", put((1..257).map { mapOf("name" to "d$it", "text" to "x") })).protocolCode())
        assertEquals(-32602, server.rpc(2, "modelmux_context_put", put(listOf(mapOf("name" to "big", "text" to "x".repeat(262_145))))).protocolCode())
        val traversal = server.rpc(3, "modelmux_context_put", put(listOf(mapOf("name" to "../escape", "text" to "x"))))
        assertTrue(traversal.failed()); assertTrue("not a plain relative label" in traversal.message(), traversal.message())
        val duplicate = server.rpc(4, "modelmux_context_put", put(listOf(mapOf("name" to "a", "text" to "1"), mapOf("name" to "a", "text" to "2"))))
        assertTrue(duplicate.failed()); assertTrue("duplicate document name" in duplicate.message(), duplicate.message())
        // With the default request cap this is refused earlier, as an oversized request.
        val capped = server.rpc(5, "modelmux_context_put", put((1..17).map { mapOf("name" to "d$it", "text" to "y".repeat(250_000)) }))
        assertEquals(-32600, capped.protocolCode())
        // The operator's explicit raise of the cap is what makes the 4 MiB total reachable, and it still holds.
        val roomy = serverOf(ModelMuxTools(context(), allowWrite = true), maxRequestChars = 8_388_608)
        val tooMuch = roomy.rpc(5, "modelmux_context_put", put((1..17).map { mapOf("name" to "d$it", "text" to "y".repeat(250_000)) }))
        assertTrue(tooMuch.failed()); assertTrue("exceed" in tooMuch.message(), tooMuch.message())

        val a = server.rpc(6, "modelmux_context_put", put(listOf(mapOf("name" to "a", "text" to "same\n"))))
        val b = server.rpc(7, "modelmux_context_put", put(listOf(mapOf("name" to "a", "text" to "same\n"))))
        assertEquals(a.data()["manifest"], b.data()["manifest"], "the same revision has one identity")
    }

    @Test
    fun aRevisionIsNamedOnlyAfterTheStoreSaysItsBlocksAreDurable() = runTest {
        val cas = RecordingCas()
        val server = serverOf(ModelMuxTools(SharedContext(cas, null, ContextReadPolicy(allowedScopes = setOf(SCOPE))), allowWrite = true))
        val manifest = server.put()
        assertEquals(71, manifest.length)
        assertEquals(listOf("put", "put", "put", "sync"), cas.events, "two documents and the manifest, then one sync, and only then a reply")
        val refused = server.rpc(2, "modelmux_context_put", mapOf("scope" to SCOPE, "author" to "a", "documents" to listOf(mapOf("name" to "../x", "text" to "y"))))
        assertTrue(refused.failed())
        assertEquals(4, cas.events.size, "a refused revision stored nothing and promised nothing")
    }

    @Test
    fun aSuccessorIsANewRevisionAndTheOldOneStaysReadable() = runTest {
        val server = serverOf(ModelMuxTools(context(), allowWrite = true))
        val v1 = server.put(text = "alpha token\n")
        val v2 = server.put(text = "alpha changed token\n", parents = listOf(v1))
        assertTrue(v1 != v2)
        suspend fun read(m: String) = server.rpc(9, "modelmux_context_read", mapOf("manifest" to m, "scope" to SCOPE, "document" to "notes.md")).data()["text"]
        assertEquals("alpha token\n".trimEnd('\n') + "\n", (read(v1) as String).let { if (it.endsWith("\n")) it else it + "\n" })
        assertTrue("changed" in (read(v2) as String))
        assertFalse("changed" in (read(v1) as String), "a child pinned to v1 never sees v2's text")
        val acquired = server.rpc(10, "modelmux_context_acquire", mapOf("manifest" to v2, "scope" to SCOPE))
        assertEquals(listOf(v1), acquired.data()["parents"])
    }

    @Test
    fun theProjectionBudgetIsExactAndDegenerateBudgetsAreHandled() = runTest {
        val server = serverOf(ModelMuxTools(context(), allowWrite = true))
        val manifest = server.put()
        suspend fun project(vararg extra: Pair<String, Any?>) = server.rpc(2, "modelmux_breadcrumb_project", mapOf("manifest" to manifest, "scope" to SCOPE, "question" to "manifest verified") + extra)

        val tight = project("maxChars" to 100)
        assertTrue((tight.data()["text"] as String).length <= 100, "text is ${(tight.data()["text"] as String).length} chars")
        assertTrue((tight.data()["charsUsed"] as Number).toInt() <= 100)

        val zero = project("maxChars" to 0)
        assertFalse(zero.failed() && zero.message().startsWith("tool failed:"), "a budget the schema allows must not surface as an internal failure: ${zero.message()}")
        if (!zero.failed()) assertEquals("", zero.data()["text"])

        val noLines = project("maxLines" to 200, "neighborLines" to 5)
        assertFalse(noLines.failed(), noLines.toString())
        assertNotNull(noLines.data()["excerpts"])
        assertEquals(-32602, project("maxLines" to 0).protocolCode())
        assertEquals(-32602, project("oversight" to "maybe").protocolCode())
    }
}
