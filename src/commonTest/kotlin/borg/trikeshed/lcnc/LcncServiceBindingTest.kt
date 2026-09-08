package borg.trikeshed.lcnc

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.kanban.InvokeLowering
import borg.trikeshed.lib.toList
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.relaxfactory.RelaxTransport
import borg.trikeshed.relaxfactory.RequestFactoryProxy
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LcncServiceBindingTest {
    private class Corpus(val text: String) : ProjectCorpus {
        private val source = InMemoryProjectCorpus().apply {
            put("p", "doc.md", text.encodeToByteArray())
            put("p", "doc.md.extract.md", "$text extract".encodeToByteArray())
        }
        var calls = 0

        private suspend fun observe() {
            assertSame(this, ProjectCorpusKey.require())
            calls++
        }

        override suspend fun projects(): List<ProjectRef> {
            observe()
            return source.projects().map { it.copy(path = text) }
        }

        override suspend fun docs(project: String, prefix: String, glob: String, limit: Int): List<ProjectDoc> {
            observe()
            return source.docs(project, prefix, glob, limit)
        }

        override suspend fun read(project: String, id: String, maxChars: Int): ProjectText? {
            observe()
            return source.read(project, id, maxChars)
        }
    }

    @Test
    fun corpusOverrideRedirectsEveryReadAndPreservesTheLedger() = runTest {
        val default = Corpus("default")
        val replacement = Corpus("replacement")
        val registry = ProjectNodes.registry(default)

        suspend fun readAll(expected: String) {
            val ledger = LcncConsumedLedger()
            withContext(ledger) {
                val list = registry.getValue(ProjectNodes.LIST).run(LcncNode("list", ProjectNodes.LIST), emptyMap())
                assertEquals(expected, ((list["projects"] as List<*>).single() as Map<*, *>)["path"])
                val docs = registry.getValue(ProjectNodes.DOCS).run(
                    LcncNode("docs", ProjectNodes.DOCS, params = mapOf("project" to "p")), emptyMap(),
                )
                assertEquals(2, docs["count"])
                val read = registry.getValue(ProjectNodes.READ).run(
                    LcncNode("read", ProjectNodes.READ), mapOf("project" to "p", "id" to "doc.md"),
                )
                assertEquals(expected, read["text"])
                val extract = registry.getValue(ProjectNodes.EXTRACT).run(
                    LcncNode("extract", ProjectNodes.EXTRACT), mapOf("project" to "p", "id" to "doc.md"),
                )
                assertEquals("$expected extract", extract["text"])
                assertTrue(ledger.entries().any { it.id == "p/doc.md" && it.cid == read["cid"] })
                assertTrue(ledger.entries().any { it.id == "p/doc.md.extract.md" && it.cid == extract["cid"] })
                assertEquals(3, ledger.entries().size)
            }
        }

        readAll("default")
        withContext(ProjectCorpusKey(replacement)) { readAll("replacement") }
        assertEquals(4, default.calls)
        assertEquals(4, replacement.calls)
        assertNull(currentCoroutineContext()[ProjectCorpusKey])
        registry.values.forEach { assertBindings(it, ProjectCorpusKey) }
    }

    @Test
    fun promptOverrideRedirectsHeadsAndRecordsTheSelectedVersion() = runTest {
        fun source(text: String): PromptReads {
            val store = InMemoryPromptReads().apply { put(PromptDocument("greet", text)) }
            return object : PromptReads by store {
                override suspend fun get(name: String): PromptDocument? {
                    assertSame(this, PromptReadsKey.require())
                    return store.get(name)
                }
            }
        }
        val default = source("default")
        val replacement = source("replacement text")
        val registry = PromptNodes.registry(default)
        val ledger = LcncConsumedLedger()

        suspend fun read(expected: String) {
            val out = registry.getValue(PromptNodes.GET).run(
                LcncNode("prompt", PromptNodes.GET, params = mapOf("name" to "greet")), emptyMap(),
            )
            assertEquals(expected, out["text"])
            assertTrue(ledger.entries().any { it.kind == LcncConsumedLedger.PROMPT && it.cid == out["cid"] })
            val list = registry.getValue(PromptNodes.LIST).run(LcncNode("list", PromptNodes.LIST), emptyMap())
            val head = (list["prompts"] as List<*>).single() as Map<*, *>
            assertEquals(out["cid"], head["cid"])
        }

        withContext(ledger) { read("default") }
        withContext(ledger + PromptReadsKey(replacement)) { read("replacement text") }
        assertEquals(2, ledger.entries().size)
        assertNull(currentCoroutineContext()[PromptReadsKey])
        assertBindings(registry.getValue(PromptNodes.GET), PromptReadsKey)
        assertBindings(registry.getValue(PromptNodes.LIST), PromptReadsKey)
        assertTrue(registry.getValue(PromptNodes.RENDER) !is LcncServiceBinding)
        val render = registry.getValue(PromptNodes.RENDER).run(
            LcncNode("render", PromptNodes.RENDER), mapOf("template" to "Hello {{name}}", "args" to mapOf("name" to "Jim")),
        )
        assertEquals("Hello Jim", render["text"])
    }

    private class Runs(private val label: String) : AgentRuns {
        val requests = ArrayList<AgentRunRequest>()
        override suspend fun roster(): List<AgentCliInfo> {
            assertSame(this, AgentRunsKey.require())
            return listOf(AgentCliInfo(label, "/test/$label", "test", true))
        }
        override suspend fun run(request: AgentRunRequest): AgentRunResult {
            assertSame(this, AgentRunsKey.require())
            assertNotNull(currentCoroutineContext()[AgentRunIdKey])
            requests.add(request)
            return AgentRunResult(runId = request.runId, agent = request.agent, summary = label, exit = 0)
        }
    }

    @Test
    fun agentProviderAndIdSupplierCanBeOverriddenIndependently() = runTest {
        val default = Runs("default")
        val replacement = Runs("replacement")
        val registry = AgentNodes.registry(default) { "default-id" }
        suspend fun run(): Map<String, Any?> = registry.getValue(AgentNodes.RUN).run(
            LcncNode("agent", AgentNodes.RUN), mapOf("agent" to "test", "brief" to "inspect"),
        )

        assertEquals("default-id", run()["runId"])
        withContext(AgentRunsKey(replacement)) {
            val out = run()
            assertEquals("replacement", out["summary"])
            assertEquals("default-id", out["runId"])
            val list = registry.getValue(AgentNodes.LIST).run(LcncNode("list", AgentNodes.LIST), emptyMap())
            assertEquals("replacement", ((list["agents"] as List<*>).single() as Map<*, *>)["id"])
        }
        withContext(AgentRunIdKey { "caller-id" }) {
            val out = run()
            assertEquals("default", out["summary"])
            assertEquals("caller-id", out["runId"])
        }
        assertEquals(listOf("default-id", "caller-id"), default.requests.map { it.runId })
        assertEquals(listOf("default-id"), replacement.requests.map { it.runId })
        assertBindings(registry.getValue(AgentNodes.LIST), AgentRunsKey)
        assertBindings(registry.getValue(AgentNodes.RUN), AgentRunsKey, AgentRunIdKey)
        assertNull(currentCoroutineContext()[AgentRunsKey])
        assertNull(currentCoroutineContext()[AgentRunIdKey])
    }

    private class Transport(private val label: String) : RelaxTransport {
        val proxy = RequestFactoryProxy(this)
        val operations = ArrayList<Map<*, *>>()
        override suspend fun exchange(envelopeJson: String): String {
            assertSame(proxy, RequestFactoryProxyKey.require())
            val envelope = JsonSupport.parseMap(envelopeJson)
            val ops = checkNotNull(InvokeLowering.listishOf(envelope["operations"])).map { it as Map<*, *> }
            operations.addAll(ops)
            return JsonSupport.stringify(mapOf(
                "ok" to true,
                "receipts" to ops.map { mapOf("ok" to true, "result" to label) },
            ))
        }
    }

    @Test
    fun requestFactoryOverrideRedirectsRpcAndBatchTransport() = runTest {
        val default = Transport("default")
        val replacement = Transport("replacement")
        val registry = RequestFactoryNodes.registry(default.proxy)
        suspend fun exercise(expected: String) {
            val rpc = registry.getValue("rf.rpc").run(
                LcncNode("rpc", "rf.rpc"), mapOf("target" to "session.echo", "args" to mapOf("text" to "hello")),
            )
            assertEquals(expected, rpc["result"])
            val batch = registry.getValue("rf.batch").run(
                LcncNode("batch", "rf.batch"), mapOf("operations" to listOf(mapOf("op" to "get", "id" to "doc"))),
            )
            assertEquals(expected, ((batch["receipts"] as List<*>).single() as Map<*, *>)["result"])
        }
        exercise("default")
        withContext(RequestFactoryProxyKey(replacement.proxy)) { exercise("replacement") }
        assertEquals(default.operations, replacement.operations)
        assertEquals(listOf("rpc", "get"), replacement.operations.map { it["op"] })
        assertNull(currentCoroutineContext()[RequestFactoryProxyKey])
        registry.values.forEach { assertBindings(it, RequestFactoryProxyKey) }
    }

    @Test
    fun everySurfaceRunnerUsesTheVisibleCallerOrDefaultDispatcher() = runTest {
        class Calls(private val label: String) : SurfaceNodes.Call {
            val requests = ArrayList<Triple<String, String, Any?>>()
            override suspend fun invoke(method: String, path: String, body: Any?): Any? {
                assertSame(this, SurfaceCallKey.require())
                val context = currentCoroutineContext()
                val invocations = context.fold(0) { count, e -> count + if (e is LcncNodeElement) 1 else 0 }
                assertEquals(1, invocations)
                requests.add(Triple(method, path, body))
                if (path == "/api/invoke") {
                    val commands = (body as Map<*, *>)["commands"] as List<*>
                    return mapOf("results" to commands.map { mapOf("verdict" to "committed", "provider" to label) })
                }
                if (path == "/api/projects") return mapOf("verdict" to "ok", "name" to label)
                if (method == "DELETE") return mapOf("verdict" to "unmounted", "name" to label)
                return mapOf("provider" to label, "rows" to listOf(label), "routes" to listOf(label),
                    "panels" to listOf(label), "standings" to listOf(label))
            }
        }
        val default = Calls("default")
        val replacement = Calls("replacement")
        val registry = SurfaceNodes.registry(default)
        for ((type, runner) in registry) {
            val node = LcncNode(type, type, params = mapOf("name" to "mounted"))
            val inputs = when (type) {
                "job.command" -> mapOf("verb" to "cancel", "jobId" to "card", "expectedRevision" to 1L)
                "job.batch" -> mapOf("commands" to listOf(mapOf("type" to "cancel", "jobId" to "card")))
                "project.mount" -> mapOf("path" to "/mounted")
                else -> mapOf("body" to mapOf("text" to "payload"))
            }
            val ordinary = runner.run(node, inputs)
            val redirected = withContext(SurfaceCallKey(replacement)) { runner.run(node, inputs) }
            assertNotEquals(ordinary, redirected, type)
            assertBindings(runner, SurfaceCallKey)
        }
        assertEquals(SurfaceNodes.servedTypes().size, default.requests.size)
        assertEquals(default.requests, replacement.requests)
        assertEquals(Triple("POST", "/api/submit", mapOf("text" to "payload")), replacement.requests.first { it.first == "POST" })
        assertNull(currentCoroutineContext()[SurfaceCallKey])
    }

    private class BorrowedElement : AsyncContextElement() {
        companion object Key : CoroutineContext.Key<BorrowedElement>
        override val key: CoroutineContext.Key<*> get() = Key
    }

    @Test
    fun helperBorrowsLifecycleAndExecutesDelegateWithoutASecondInvocation() = runTest {
        val default = BorrowedElement()
        val replacement = BorrowedElement()
        default.open()
        replacement.open()
        try {
            val delegate = object : LcncNodeRunner {
                override suspend fun run(node: LcncNode, inputs: Map<String, Any?>): Map<String, Any?> =
                    error("binding must call execute")

                override suspend fun execute(node: LcncNode, inputs: Map<String, Any?>): Map<String, Any?> {
                    val context = currentCoroutineContext()
                    assertSame(replacement, context[BorrowedElement])
                    assertEquals("caller", context[CoroutineName]?.name)
                    assertEquals(node.id, context[LcncNodeKey.TEXT_VALUE]?.node?.id)
                    return inputs
                }
            }
            val wrapped = boundLcnc(default, delegate)
            val out = withContext(replacement + CoroutineName("caller")) {
                wrapped.run(LcncNode("one", "text.value"), mapOf("value" to "ok"))
            }
            assertEquals(mapOf("value" to "ok"), out)
            val failing = boundLcnc(default) { actual, _, _ ->
                assertSame(default, actual)
                throw IllegalStateException("operation failed")
            }
            assertFailsWith<IllegalStateException> { failing.run(LcncNode("failure", "text.value"), emptyMap()) }
            assertEquals(ElementState.OPEN, default.lifecycleState)
            assertEquals(ElementState.OPEN, replacement.lifecycleState)
            assertTrue(default.supervisor.isActive)
            assertTrue(replacement.supervisor.isActive)
            assertBindings(wrapped, BorrowedElement.Key)
        } finally {
            default.close()
            replacement.close()
        }
    }

    private fun assertBindings(runner: LcncNodeRunner, vararg expected: CoroutineContext.Key<*>) {
        val binding = runner as LcncServiceBinding
        assertEquals(expected.toList(), binding.requiredKeys.toList())
        assertEquals(expected.toList(), binding.providedKeys.toList())
    }
}
