package borg.trikeshed.forge.server

import borg.trikeshed.kanban.ForgeKanbanIngest
import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.NuidFanoutElement
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.TraitSpace
import borg.trikeshed.context.nuid.Workgroup
import borg.trikeshed.lib.j
import borg.trikeshed.htx.HtxRequest
import borg.trikeshed.htx.HtxResponse
import borg.trikeshed.htx.HtxFlowStage
import borg.trikeshed.htx.HtxExchangeState
import borg.trikeshed.htx.HtxExchangeResult
import borg.trikeshed.htx.HtxRouteService
import borg.trikeshed.htx.HtxReactorElement
import borg.trikeshed.htx.HtxBody
import borg.trikeshed.htx.emptyHtxBody
import borg.trikeshed.htx.htxFrames
import borg.trikeshed.htx.HtxFrame
import borg.trikeshed.htx.HtxExchangeLifecycle
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import java.nio.file.Files as NioFiles
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

object KanbanServerMain {
    
    @JvmStatic
    fun main(args: Array<String>) {
        var port = 8888
        var donor: String? = null
        val i = args.iterator()
        while (i.hasNext()) {
            when (val a = i.next()) {
                "--port"  -> if (i.hasNext()) port = i.next().toIntOrNull() ?: 8888
                "--donor" -> if (i.hasNext()) donor = i.next()
                "-h", "--help" -> {
                    System.err.println("Usage: KanbanServerMain [--port N] [--donor path]")
                    exitProcess(2)
                }
            }
        }
        runBlocking { run(port, donor) }
    }

    suspend fun run(port: Int, donorPath: String?) {
        val serverJob = SupervisorJob()
        val scope = CoroutineScope(serverJob + Dispatchers.Default)
        
        val fanout = NuidFanoutElement(parentJob = serverJob).also { it.open() }

        val processWorkgroup = Workgroup(
            name = "kanban-process-local",
            scope = Subnet.local,
            traits = traitSpaceOf(Capability.ProcessAll),
        )
        val casWorkgroup = Workgroup(
            name = "kanban-cas-local",
            scope = Subnet.local,
            traits = traitSpaceOf(Capability.CasAll),
        )
        val wireprotoWorkgroup = Workgroup(
            name = "kanban-wireproto-lan",
            scope = Subnet.lanLocalhost,
            traits = traitSpaceOf(Capability.WireprotoAll),
        )
        fanout.register(processWorkgroup)
        fanout.register(casWorkgroup)
        fanout.register(wireprotoWorkgroup)
        fanout.activate()
        
        listOf(processWorkgroup, casWorkgroup, wireprotoWorkgroup).forEach { workgroup ->
            val slot = requireNotNull(fanout.slotOf(workgroup.name))
            scope.launch {
                try {
                    while (true) {
                        slot.consume()
                    }
                } catch (_: ClosedReceiveChannelException) {
                }
            }
        }
        
        if (donorPath != null && NioFiles.exists(Paths.get(donorPath))) {
            try {
                ForgeKanbanIngest.persistMarkdown("jim", donorPath)
                System.err.println("donor replayed: $donorPath")
            } catch (t: Throwable) {
                System.err.println("donor replay failed: ${t.message}")
            }
        }
        
        val routeService = object : HtxRouteService {
            override val key get() = HtxRouteService.Key
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                val path = request.target.requestPath
                val method = request.method
                val text = if (request.body.toArray().isNotEmpty()) String(request.body.toArray(), StandardCharsets.UTF_8) else ""
                
                val response = routeHttp(method.name, path, text)
                
                return HtxExchangeResult(
                    state.copy(
                        lifecycle = HtxExchangeLifecycle.RESPONDED,
                        request = request,
                        response = response,
                    ),
                    htxFrames(
                        HtxFrame(
                            exchangeOrdinal = state.exchangeOrdinal,
                            stage = HtxFlowStage.REQUEST,
                            request = request,
                        ),
                        HtxFrame(
                            exchangeOrdinal = state.exchangeOrdinal,
                            stage = HtxFlowStage.RESPONSE,
                            request = request,
                            response = response,
                        ),
                    ),
                )
            }
        }
        
        val nioSupervisor = NioSupervisor()
        nioSupervisor.open()
        val channelOperations = nioSupervisor.service<ChannelOperations>() ?: error("Missing ChannelOperations")
        
        val reactor = HtxReactorElement(
            channelOperations = channelOperations,
            parentJob = serverJob,
            ownedSupervisor = nioSupervisor
        )
        reactor.open()

        System.err.println("trikeshed-kanban: listening on :$port  donor=${donorPath ?: "<none>"}")
        System.err.println("Endpoints (CCEK): GET /api/board POST /api/submit")

        CompletableDeferred<Unit>().await()
    }
    
    private fun routeHttp(method: String, path: String, text: String): HtxResponse {
        return when (path) {
            "/api/board"  -> HtxResponse(200, HtxBody(boardJson().toByteArray(StandardCharsets.UTF_8)))
            "/api/submit" -> if (method == "POST") submit(text) else HtxResponse(405, HtxBody("""{"error":"method_not_allowed"}""".toByteArray(StandardCharsets.UTF_8)))
            else           -> HtxResponse(404, HtxBody("""{"error":"not_found","path":"$path"}""".toByteArray(StandardCharsets.UTF_8)))
        }
    }

    private fun boardJson(): String = runCatching {
        val reduction = ForgeKanbanIngest.load("jim")
        JsonSupport.stringify(
            linkedMapOf(
                "title" to reduction.source.title,
                "userId" to reduction.source.userId,
                "items" to reduction.board.cards.sortedBy { it.order }.map { card ->
                    linkedMapOf(
                        "id" to card.id.value,
                        "title" to card.title,
                        "status" to card.columnId.value,
                    )
                },
                "correlations" to reduction.correlations.size,
            )
        )
    }.getOrElse { """{"error":"load_failed","reason":"${it.message}"}""" }

    private fun submit(body: String): HtxResponse {
        val payload = body.substringAfter("\r\n\r\n", "").ifEmpty {
            body.substringAfter("\n\n", "")
        }
        if (payload.isBlank()) return HtxResponse(400, HtxBody("""{"error":"empty_body"}""".toByteArray(StandardCharsets.UTF_8)))
        return runCatching {
            val tmp = "/tmp/hi"
            writeStringJvm(tmp, payload)
            val reduction = ForgeKanbanIngest.persistMarkdown("jim", tmp)
            HtxResponse(
                201,
                HtxBody(JsonSupport.stringify(
                    linkedMapOf(
                        "ok" to true,
                        "correlations" to reduction.correlations.size,
                        "firstCausalKey" to (reduction.correlations.firstOrNull()?.causalKey ?: ""),
                    )
                ).toByteArray(StandardCharsets.UTF_8)),
            )
        }.getOrElse { HtxResponse(500, HtxBody("""{"error":"submit_failed","reason":"${it.message}"}""".toByteArray(StandardCharsets.UTF_8))) }
    }
}

private fun traitSpaceOf(vararg capabilities: Capability): TraitSpace = TraitSpace {
    capabilities.size j { index -> capabilities[index] }
}

private fun writeStringJvm(path: String, text: String) {
    val p = Paths.get(path)
    if (p.parent != null) NioFiles.createDirectories(p.parent)
    NioFiles.writeString(
        p, text,
        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
    )
}
