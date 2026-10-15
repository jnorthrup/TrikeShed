package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.kanban.module.KanbanModule
import borg.trikeshed.lcnc.ccek.CcekReactorBinding
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
    source: ModuleContext, curator: DocumentCuratorElement, binding: CcekReactorBinding, program: LcncProgram,
    observations: () -> Map<String, Any?> = { emptyMap() },
) = coroutineScope {
    val owner = SupervisorJob(coroutineContext[Job])
    val state = Files.createTempDirectory("lcnc-retained-simulation-").toFile()
    val context = ModuleContext(source.couch, source.rete, source.productions, null, null,
        source.blackboard, source.casStore, source.attachments, ModuleRouteRegistry(),
        CoroutineScope(coroutineContext + owner + Dispatchers.Default), source.clock, state,
        ccekBinding = binding, lcncRunners = LinkedHashMap(source.lcncRunners))
    context.lcncRunners["display"] = TribunalNodes.registry(
        TribunalDialog { _, _, _ -> error("Retained simulation cannot request a model") }).getValue("display")
    val module = KanbanModule().open(context)
    val blackboard = borg.trikeshed.forge.server.BlackboardWire(context.blackboard, context.scope)
    val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 8)
    val executor = Executors.newFixedThreadPool(2)
    val finished = CountDownLatch(1)
    server.executor = executor
    try {
        server.createContext("/") { exchange ->
            try {
                val path = exchange.requestURI.path
                var status = 200
                var mime = "application/json; charset=utf-8"
                val bytes = when {
                    path == "/_test/finish" -> { finished.countDown(); "{}".encodeToByteArray() }
                    path == "/_test/observations" -> jsonOf(observations()).encodeToByteArray()
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
                        val record = requireNotNull(curator.record(cid))
                        jsonOf(DocumentCurationLegos.sheet(cid, record, query)).encodeToByteArray()
                    }
                    path in setOf("/panels", "/kanban", "/graal") || path.endsWith(".js") || path.endsWith(".css") -> {
                        mime = when {
                            path.endsWith(".js") -> "application/javascript"
                            path.endsWith(".css") -> "text/css"
                            else -> "text/html"
                        }
                        val resource = if (path in setOf("/panels", "/kanban", "/graal")) "web$path.html" else "web$path"
                        requireNotNull(LcncProgram::class.java.classLoader.getResourceAsStream(resource)) { resource }
                            .use { it.readBytes() }
                    }
                    else -> {
                        val response = runBlocking { context.routes.match(path)?.route(exchange.requestMethod,
                            exchange.requestURI.toString(), exchange.requestBody.readBytes().decodeToString(), null) }
                        status = response?.status ?: 404
                        (response?.body ?: "{}").encodeToByteArray()
                    }
                }
                exchange.responseHeaders.set("Content-Type", mime)
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } catch (failure: Exception) {
                val bytes = jsonOf(mapOf("error" to failure.toString())).encodeToByteArray()
                exchange.sendResponseHeaders(500, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally { exchange.close() }
        }
        server.start()
        println("LCNC_SIMULATION_UI=http://127.0.0.1:${server.address.port}/panels?load=retained-check")
        assertTrue(withContext(Dispatchers.IO) { finished.await(10, TimeUnit.MINUTES) }, "Browser check did not close")
    } finally {
        try {
            server.stop(0)
            executor.shutdown()
            assertTrue(withContext(Dispatchers.IO) { executor.awaitTermination(10, TimeUnit.SECONDS) })
        } finally {
            try { module.drain() } finally { try { module.close() } finally { owner.cancelAndJoin() } }
        }
    }
}
