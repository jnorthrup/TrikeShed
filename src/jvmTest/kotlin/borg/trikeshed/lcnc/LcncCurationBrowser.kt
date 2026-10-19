package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.module.KanbanModule
import borg.trikeshed.lcnc.ccek.CcekReactorBinding
import borg.trikeshed.litebike.JvmKanbanServer.HttpResponse
import borg.trikeshed.module.*
import borg.trikeshed.narsese.DocumentCuratorElement
import borg.trikeshed.parse.*
import borg.trikeshed.relaxfactory.CouchHttpSurface
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/** Optional loopback transport over the tested curator and production module; never the user's daemon. */
internal suspend fun retainedSimulationBrowser(
    source: ModuleContext, curator: DocumentCuratorElement?, binding: CcekReactorBinding, program: LcncProgram,
    stateDirectory: java.io.File? = null,
    requests: (String, String) -> Unit = { _, _ -> },
    requestBodies: (String, String, String) -> Unit = { _, _, _ -> },
    observations: () -> Map<String, Any?> = { emptyMap() },
    opened: suspend (ModuleContext) -> Unit = {},
    testRoute: suspend (String, String, String) -> HttpResponse? = { _, _, _ -> null },
    cleanup: () -> Unit = {},
    executorThreads: Int = 2,
    blackboardEvents: Boolean = false,
    programLoader: suspend (String) -> LcncProgram? = source.programLoader,
    pageSuffix: String = "",
    configure: (ModuleContext) -> Unit = {},
) = coroutineScope {
    val owner = SupervisorJob(coroutineContext[Job])
    val state = stateDirectory ?: Files.createTempDirectory("lcnc-retained-simulation-").toFile()
    val context = ModuleContext(source.couch, source.rete, source.productions, null, null,
        source.blackboard, source.casStore, source.attachments, ModuleRouteRegistry(),
        CoroutineScope(coroutineContext + owner + Dispatchers.Default), source.clock, state,
        ccekBinding = binding, lcncRunners = LinkedHashMap(source.lcncRunners), confixIsam = source.confixIsam,
        programLoader = programLoader)
    context.lcncRunners["display"] = TribunalNodes.registry(
        TribunalDialog { _, _, _ -> error("Retained simulation cannot request a model") }).getValue("display")
    configure(context)
    val module = KanbanModule().open(context)
    val blackboard = borg.trikeshed.forge.server.BlackboardWire(context.blackboard, context.scope)
    val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8)
    val executor = Executors.newFixedThreadPool(executorThreads)
    val streams = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    val streamsClosing = java.util.concurrent.atomic.AtomicBoolean(false)
    val finished = CountDownLatch(1)
    server.executor = executor
    try {
        opened(context)
        server.createContext("/") { exchange ->
            var streaming = false
            try {
                val path = exchange.requestURI.path
                val body = exchange.requestBody.readBytes().decodeToString()
                requests(exchange.requestMethod, path)
                if (blackboardEvents && exchange.requestMethod == "GET" && path == "/blackboard/facts") {
                    streaming = true
                    runBlocking {
                        val stream = currentCoroutineContext().job
                        streams.add(stream)
                        if (streamsClosing.get()) stream.cancel()
                        try { streamBlackboard(exchange, blackboard) }
                        finally { streams.remove(stream) }
                    }
                    return@createContext
                }
                var status = 200
                var mime = "application/json; charset=utf-8"
                val intercepted = runBlocking { testRoute(exchange.requestMethod, path, body) }
                val bytes = when {
                    intercepted != null -> { status = intercepted.status; intercepted.body.encodeToByteArray() }
                    path == "/_test/finish" -> { finished.countDown(); "{}".encodeToByteArray() }
                    path == "/_test/observations" -> {
                        val board = runBlocking {
                            context.routes.match("/api/board")?.route("GET", "/api/board", "", null)
                        }
                        jsonOf(observations() + ("board" to board?.body?.let(::reify))).encodeToByteArray()
                    }
                    path == "/blackboard/board" -> runBlocking {
                        requireNotNull(blackboard.route("GET", path, "")).body.encodeToByteArray()
                    }
                    path == "/api/panels/retained-check" -> LcncProgramConfix.toJson(program).encodeToByteArray()
                    path == "/api/panels/presets" -> jsonOf(mapOf("presets" to LcncWorkspacePresets.catalog.map { info ->
                        mapOf("name" to info.name, "title" to info.title, "does" to info.does, "needs" to info.needs,
                            "see" to info.see, "tweakFirst" to info.tweakFirst,
                            "document" to reify(LcncWorkspacePresets.documents().getValue(info.name)))
                    })).encodeToByteArray()
                    path == "/api/documents" -> runBlocking {
                        val query = CouchHttpSurface.parseQuery(exchange.requestURI.rawQuery.orEmpty())
                        val cid = ContentId(requireNotNull(query["cid"]))
                        val record = requireNotNull(curator?.record(cid))
                        jsonOf(DocumentCurationLegos.sheet(cid, record, query)).encodeToByteArray()
                    }
                    path in setOf("/panels", "/kanban", "/graal", "/curator") || path.endsWith(".js") || path.endsWith(".css") -> {
                        mime = when {
                            path.endsWith(".js") -> "application/javascript"
                            path.endsWith(".css") -> "text/css"
                            else -> "text/html"
                        }
                        val resource = if (path in setOf("/panels", "/kanban", "/graal", "/curator")) "web$path.html" else "web$path"
                        requireNotNull(LcncProgram::class.java.classLoader.getResourceAsStream(resource)) { resource }
                            .use { input ->
                                val content = input.readBytes()
                                if (path == "/panels" && pageSuffix.isNotEmpty()) content + pageSuffix.encodeToByteArray() else content
                            }
                    }
                    else -> {
                        requestBodies(exchange.requestMethod, path, body)
                        val response = runBlocking { context.routes.match(path)?.route(exchange.requestMethod,
                            exchange.requestURI.toString(), body, null) }
                        status = response?.status ?: 404
                        (response?.body ?: "{}").encodeToByteArray()
                    }
                }
                exchange.responseHeaders.set("Content-Type", mime)
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } catch (failure: Exception) {
                if (!streaming) {
                    val bytes = jsonOf(mapOf("error" to failure.toString())).encodeToByteArray()
                    exchange.sendResponseHeaders(500, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            } finally { exchange.close() }
        }
        server.start()
        println("LCNC_SIMULATION_UI=http://127.0.0.1:${server.address.port}/panels?load=retained-check")
        assertTrue(withContext(Dispatchers.IO) { finished.await(10, TimeUnit.MINUTES) }, "Browser check did not close")
    } finally {
        try {
            try { cleanup() } finally {
                streamsClosing.set(true)
                server.stop(0)
                streams.toList().forEach { it.cancel() }
                executor.shutdown()
                assertTrue(withContext(Dispatchers.IO) { executor.awaitTermination(10, TimeUnit.SECONDS) })
            }
        } finally {
            withContext(NonCancellable) {
                try { module.drain() } finally {
                    try { module.close() } finally {
                        try { context.drainCouncilCases() } finally { owner.cancelAndJoin() }
                    }
                }
            }
        }
    }
}

/** Adapt only the transport framing; BlackboardWire supplies the actual headers, replay and live data. */
private suspend fun streamBlackboard(
    exchange: com.sun.net.httpserver.HttpExchange,
    blackboard: borg.trikeshed.forge.server.BlackboardWire,
) {
    var opened = false
    blackboard.route(exchange.requestMethod, exchange.requestURI.toString(), "") { bytes ->
        if (!opened) {
            val initial = bytes.decodeToString().split("\r\n\r\n", limit = 2)
            check(initial.size == 2) { "Production stream did not supply its HTTP headers" }
            val headers = initial[0].split("\r\n")
            val status = headers.first().split(' ')[1].toInt()
            for (line in headers.drop(1)) {
                val field = line.split(':', limit = 2)
                check(field.size == 2)
                exchange.responseHeaders.set(field[0], field[1].trim())
            }
            exchange.sendResponseHeaders(status, 0L)
            opened = true
            if (initial[1].isNotEmpty()) exchange.responseBody.write(initial[1].encodeToByteArray())
        } else exchange.responseBody.write(bytes)
        exchange.responseBody.flush()
    }
}
