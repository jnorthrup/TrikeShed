package borg.trikeshed.og1.repl

import borg.trikeshed.hermes.tool.Og1RingTool
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpExchange
import java.io.OutputStreamWriter
import java.net.InetSocketAddress

/**
 * Og1RingServer — lightweight HTTP server exposing Og1RingTool as REST.
 * hermes cron Python script calls these endpoints to access JVM RingSeries.
 * No external deps — uses com.sun.net.httpserver (built into JDK).
 *
 * Endpoints:
 *   GET  /ring/read?ringName=og1-events&fromIndex=0&limit=100&reduce=kmeans&reduceK=3
 *   POST /ring/append { "ringName": "og1-events", "events": [...] }
 *   POST /ring/capture { "ringName": "og1-events" }
 *   GET  /ring/list
 *   POST /ring/clear { "ringName": "og1-events" }
 *   GET  /health
 */
object Og1RingServer {
    private var server: HttpServer? = null

    @JvmStatic
    fun start(port: Int = 18743): InetSocketAddress {
        stop()
        server = HttpServer.create(InetSocketAddress("localhost", port), 0)

        server?.apply {
            createContext("/ring/read", RingReadHandler())
            createContext("/ring/append", RingAppendHandler())
            createContext("/ring/capture", RingCaptureHandler())
            createContext("/ring/list", RingListHandler())
            createContext("/ring/clear", RingClearHandler())
            createContext("/health", HealthHandler())
            executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            start()
        }

        return InetSocketAddress("localhost", port)
    }

    @JvmStatic
    fun stop() {
        server?.stop(0)
        server = null
    }

    // ── Request handlers ───────────────────────────────────────────────────

    abstract class JsonHandler : HttpHandler {
        protected fun sendJson(exchange: HttpExchange, body: String, status: Int = 200) {
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, body.length.toLong())
            OutputStreamWriter(exchange.responseBody).use { it.write(body) }
        }

        protected fun parseBody(exchange: HttpExchange): Map<String, Any?> {
            return exchange.requestBody.bufferedReader().use { reader ->
                parseQuery(reader.readText())
            }
        }

        protected fun parseQuery(qs: String): Map<String, Any?> {
            val result = mutableMapOf<String, Any?>()
            for (pair in qs.split('&')) {
                val kv = pair.split('=', limit = 2)
                if (kv.size == 2) {
                    result[kv[0]] = kv[1]
                }
            }
            return result
        }

        protected fun toJsonString(map: Map<String, Any?>): String {
            val sb = StringBuilder("{")
            map.entries.forEachIndexed { idx, (k, v) ->
                if (idx > 0) sb.append(", ")
                sb.append("\"$k\":")
                when (v) {
                    is String -> sb.append("\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
                    is Number -> sb.append(v.toString())
                    is Boolean -> sb.append(v.toString())
                    null -> sb.append("null")
                    is List<*> -> {
                        sb.append("[")
                        v.forEachIndexed { i, item ->
                            if (i > 0) sb.append(", ")
                            when (item) {
                                is String -> sb.append("\"${item.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
                                is Number -> sb.append(item.toString())
                                is Map<*, *> -> sb.append(toJsonString(item as Map<String, Any?>))
                                else -> sb.append("null")
                            }
                        }
                        sb.append("]")
                    }
                    is Map<*, *> -> sb.append(toJsonString(v as Map<String, Any?>))
                    else -> sb.append("null")
                }
            }
            sb.append("}")
            return sb.toString()
        }
    }

    class HealthHandler : JsonHandler() {
        override fun handle(exchange: HttpExchange) {
            sendJson(exchange, "{\"status\":\"ok\"}")
        }
    }

    class RingReadHandler : JsonHandler() {
        override fun handle(exchange: HttpExchange) {
            try {
                val params = exchange.requestURI.rawQuery?.let { parseQuery(it) } ?: emptyMap()
                val ringName = params["ringName"] as? String ?: "default"
                val fromIndex = params["fromIndex"]?.toString()?.toIntOrNull() ?: 0
                val limit = params["limit"]?.toString()?.toIntOrNull() ?: 100
                val reduce = params["reduce"] as? String
                val reduceK = params["reduceK"]?.toString()?.toIntOrNull()

                val result = Og1RingTool.read(ringName, fromIndex, limit, reduce, reduceK)
                sendJson(exchange, toJsonString(result))
            } catch (e: Exception) {
                sendJson(exchange, "{\"error\":\"${e.message}\"}", 500)
            }
        }
    }

    class RingAppendHandler : JsonHandler() {
        override fun handle(exchange: HttpExchange) {
            try {
                val body = parseBody(exchange)
                val ringName = body["ringName"] as? String ?: "default"
                @Suppress("UNCHECKED_CAST")
                val events = body["events"] as? List<Map<String, Any?>> ?: emptyList()
                val result = Og1RingTool.append(ringName, events)
                sendJson(exchange, toJsonString(result))
            } catch (e: Exception) {
                sendJson(exchange, "{\"error\":\"${e.message}\"}", 500)
            }
        }
    }

    class RingCaptureHandler : JsonHandler() {
        override fun handle(exchange: HttpExchange) {
            try {
                val body = parseBody(exchange)
                val ringName = body["ringName"] as? String ?: "default"
                val result = Og1RingTool.capture(ringName)
                sendJson(exchange, toJsonString(result))
            } catch (e: Exception) {
                sendJson(exchange, "{\"error\":\"${e.message}\"}", 500)
            }
        }
    }

    class RingListHandler : JsonHandler() {
        override fun handle(exchange: HttpExchange) {
            try {
                val result = Og1RingTool.listRings()
                sendJson(exchange, toJsonString(result))
            } catch (e: Exception) {
                sendJson(exchange, "{\"error\":\"${e.message}\"}", 500)
            }
        }
    }

    class RingClearHandler : JsonHandler() {
        override fun handle(exchange: HttpExchange) {
            try {
                val body = parseBody(exchange)
                val ringName = body["ringName"] as? String ?: "default"
                val result = Og1RingTool.clear(ringName)
                sendJson(exchange, toJsonString(result))
            } catch (e: Exception) {
                sendJson(exchange, "{\"error\":\"${e.message}\"}", 500)
            }
        }
    }
}