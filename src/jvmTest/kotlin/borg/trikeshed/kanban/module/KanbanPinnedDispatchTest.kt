package borg.trikeshed.kanban.module

import borg.trikeshed.context.nuid.*
import borg.trikeshed.couch.*
import borg.trikeshed.dag.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.module.*
import borg.trikeshed.narsese.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.FileCasStore
import kotlinx.coroutines.*
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class KanbanPinnedDispatchTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path

    @Test fun queuedRunUsesItsPinnedWorkgroupDespiteNearerWildcard(): Unit = runBlocking {
        val files = JvmFileOperations()
        val cas = FileCasStore(files, directory.resolve("cas").toString())
        val owner = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(Dispatchers.IO + owner + files)
        val store = CouchStoreFactory.casBacked(cas)
        val ctx = ModuleContext(Couch("pinned-dispatch", store, cas), ReteNetwork(), ReteProductionRegistry(),
            null, null, ConfixBlackboard.empty(), cas, CouchAttachmentGateway(store, cas),
            ModuleRouteRegistry(), scope, { 123L }, directory.toFile())
        val wildcardCalls = AtomicInteger()
        val pinnedCalls = AtomicInteger()
        ctx.lcncRunners["json.value"] = LcncNodeRunner { node, _ ->
            if (node.params["value"] == "register") {
                val fanout = checkNotNull(currentCoroutineContext()[NuidFanoutElement])
                fanout.register(Workgroup("nearer-wildcard", Subnet.parse("local.inner"),
                    traitSpaceOf(Capability.CustomAll))) { wildcardCalls.incrementAndGet() }
            } else pinnedCalls.incrementAndGet()
            mapOf("value" to node.params["value"])
        }
        var handle: ModuleHandle? = null
        try {
            handle = KanbanModule().open(ctx)
            suspend fun run(name: String, value: String) {
                val program = LcncProgram(name, s_[LcncNode("value", "json.value", mapOf("value" to value))], emptySeriesOf())
                val body = jsonOf(mapOf("name" to name, "subnet" to "local.inner",
                    "document" to reify(LcncProgramConfix.toJson(program))))
                val path = "/api/lcnc/run"
                val response = withTimeout(30_000) {
                    requireNotNull(ctx.routes.match(path)?.route("POST", path, "POST $path HTTP/1.1\r\n\r\n$body", null))
                }
                assertEquals(200, response.status, response.body)
            }
            run("first", "register")
            run("second", "selected")
            assertEquals(1, pinnedCalls.get())
            assertEquals(0, wildcardCalls.get())
        } finally {
            withContext(NonCancellable) {
                handle?.drain()
                owner.cancelAndJoin()
            }
        }
    }
}
