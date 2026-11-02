package borg.trikeshed.lcnc

import borg.trikeshed.forge.server.*
import borg.trikeshed.lcnc.ccek.*
import borg.trikeshed.lib.*
import borg.trikeshed.memory.*
import borg.trikeshed.modelmux.*
import borg.trikeshed.parse.*
import borg.trikeshed.rdf.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Opt-in loopback browser proof: the page and SQL endpoint are production implementations. */
class VisualSqlBrowserTest {
    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun visualSqlUsesTheQuadSnapshotAndKeepsTheStarterEphemeral() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_SQL_VISUAL_UI") == "1")
        val binding = ccekReactorBinding(coroutineContext)
        val gate = CompletableDeferred<Unit>()
        val held = CompletableDeferred<Unit>()
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val bodies = CopyOnWriteArrayList<Map<String, Any?>>()
        val failures = CopyOnWriteArrayList<Map<String, Any?>>()
        val reports = CopyOnWriteArrayList<Map<String, Any?>>()
        val queryCount = AtomicInteger()
        val store = QuadStore().add("<urn:a>", "<urn:p>", "\"<img src=x onerror=alert(1)>\"")
            .add("<urn:b>", "<urn:p>", "\"second\"")
        val wire = SparqlWire(SparqlSurface(store))
        val initial = SqlGraph.parse("SELECT S, S, O FROM DB.DBA.RDF_QUAD")
        try {
            binding.reactor.open()
            val context = vmRuntimeTestContext(this)
            context.lcncRunners.putAll(PureNodes.registry { 1L })
            val memory = MemoryIndexLayer(MemoryStore(context.casStore, context.couch.store))
            val scopes = ProjectScopes(JvmFileOperations(), context.attachments,
                CouchIndexBridge(context.attachments, memory), context.casStore, null)
            val patch = PatchWire(BrainClient(apiKey = "fixture", base = "https://fixture.invalid/v1", model = "fixture"),
                scopes, attachments = context.attachments)
            retainedSimulationBrowser(context, null, binding, initial, executorThreads = 8,
                requests = { method, path -> requests += Pair(method, path) },
                testRoute = { method, path, body -> when {
                    method == "GET" && path in _s["/api/panels/presets", "/api/panels"] ->
                        patch.route(method, path, "", null)
                    method == "POST" && path == "/sql/visual" -> {
                        bodies += reifyMap(body)
                        val ordinal = queryCount.incrementAndGet()
                        val actual = requireNotNull(wire.route(method, path,
                            "POST /sql/visual HTTP/1.1\r\nContent-Type: application/json\r\n\r\n$body", null))
                        if (ordinal == 2) { held.complete(Unit); gate.await() }
                        actual
                    }
                    method == "GET" && path == "/_test/sql/state" ->
                        borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(200, jsonOf(_m["held" j held.isCompleted, "queries" j queryCount.get()]))
                    method == "POST" && path == "/_test/sql/release" -> {
                        gate.complete(Unit)
                        borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(200, "{}")
                    }
                    method == "POST" && path == "/_test/sql/report" -> {
                        reports += reifyMap(body)
                        borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(200, "{}")
                    }
                    method == "POST" && path == "/_test/sql/failure" -> {
                        failures += reifyMap(body)
                        borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(200, "{}")
                    }
                    else -> null
                } }, cleanup = { gate.complete(Unit) })
            assertTrue(failures.isEmpty(), "Browser driver reported: $failures")
            assertEquals(1, reports.size, "A browser proof must report its observed checkpoints")
            assertEquals(true, reports.single()["verified"])
            assertEquals(2, queryCount.get(), "Only the explicitly requested SQL queries may run")
            assertEquals(2, store.size, "Read-only query must not change the store")
            assertTrue(store.contains("<urn:a>", "<urn:p>", "\"<img src=x onerror=alert(1)>\""))
            assertTrue(bodies.all { body ->
                body["dataset"] == "quad-store" &&
                    ((body["program"] as? Map<*, *>)?.get("controls") as? Map<*, *>)?.get("inspectionOnly") == true
            })
            assertTrue(requests.none { (method, path) -> method == "POST" &&
                (path == "/api/lcnc/run" || path == "/pg/query" || path == "/sparql") },
                "Visual SQL must not dispatch a program or mutation: $requests")
            println("VISUAL_SQL_BROWSER_PROOF=" + jsonOf(_m["reports" j reports.toList(), "queries" j queryCount.get()]))
        } finally { withContext(NonCancellable) { gate.complete(Unit); binding.reactor.drain() } }
    }
}
