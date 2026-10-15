package borg.trikeshed.forge.server

import borg.trikeshed.job.ContentId
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.parse.jsonOf
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/** Opt-in loopback adapter for the production document routes and Kotlin/JS page. No daemon restart. */
internal suspend fun retainedDocumentBrowser(context: ModuleContext, cid: ContentId, observations: () -> Map<String, Any?>) {
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
                    path == "/api/projects" -> "{\"scopes\":[]}".encodeToByteArray()
                    path == "/documents" || path.endsWith(".js") || path.endsWith(".css") -> {
                        mime = when {
                            path.endsWith(".js") -> "application/javascript"
                            path.endsWith(".css") -> "text/css"
                            else -> "text/html"
                        }
                        val resource = if (path == "/documents") "web/documents.html" else "web$path"
                        requireNotNull(DocumentCurationWire::class.java.classLoader.getResourceAsStream(resource)) { resource }
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
        println("TRIKESHED_CURATION_UI=http://127.0.0.1:${server.address.port}/documents#curation/${cid.value}")
        assertTrue(withContext(Dispatchers.IO) { finished.await(3, TimeUnit.MINUTES) }, "Browser check did not close")
    } finally {
        server.stop(0)
        executor.shutdown()
        assertTrue(withContext(Dispatchers.IO) { executor.awaitTermination(10, TimeUnit.SECONDS) })
    }
}
