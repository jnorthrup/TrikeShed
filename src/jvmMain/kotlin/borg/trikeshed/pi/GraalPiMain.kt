package borg.trikeshed.pi

import borg.trikeshed.agent.keymux.EnvVarSource
import borg.trikeshed.agent.keymux.KeyMux
import borg.trikeshed.agent.keymux.LlmAuth
import borg.trikeshed.agent.modelmux.BreadcrumbElement
import borg.trikeshed.agent.modelmux.ModelMux
import borg.trikeshed.forge.server.PiWire
import borg.trikeshed.htx.openHtxElement
import borg.trikeshed.htx.HtxElement
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** Validate the initial unauthenticated endpoint; a local relay can still forward requests elsewhere. */
internal fun noAuthLoopbackBaseUrl(value: String?): String {
    val url = requireNotNull(value?.takeIf { it.isNotBlank() }) { "PI_AUTH=none requires an explicit loopback PI_BASE_URL" }
    val uri = try { URI(url) } catch (failure: URISyntaxException) {
        throw IllegalArgumentException("PI_AUTH=none requires a numeric loopback HTTP(S) base URL", failure)
    }
    val authority = uri.rawAuthority.orEmpty()
    val match = Regex("^(?:127\\.0\\.0\\.1|\\[::1\\])(?::([0-9]{1,5}))?$").matchEntire(authority)
    require(uri.scheme?.lowercase() in _s["http", "https"] && match != null &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        (uri.port == -1 || uri.port in 1..65535)) { "PI_AUTH=none requires a numeric loopback HTTP(S) base URL" }
    return url
}

/** Standalone loopback host for the production Pi page, wire, ModelMux and restricted Graal child. */
object GraalPiMain {
    private const val REQUEST_LIMIT = 160 * 1024
    private val usage = """
        Usage: GraalPiMain --workspace DIRECTORY [--allow RELATIVE_PATH]... [--port PORT]

        Environment: PI_MODEL and PI_ACCESS_TOKEN or PI_ACCESS_TOKEN_SHA256.
        PI_AUTH=required (default) uses OPENAI_API_KEY; PI_BASE_URL defaults to https://api.openai.com/v1.
        PI_AUTH=none requires an explicit PI_BASE_URL on numeric loopback 127.0.0.1 or [::1].
        The token is entered in the Pi page.
        --allow grants a specific existing workspace .ts or .js file to Pi; repeat for each file.
        With no --allow entries workspace editing is disabled. The listener binds 127.0.0.1 only.
    """.trimIndent()

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        if (args.contentEquals(_a["--help"])) { println(usage); return@runBlocking }
        var workspacePath: String? = null
        var port = 0
        val allowed = linkedSetOf<String>()
        var index = 0
        while (index < args.size) {
            val flag = args[index++]
            require(index < args.size) { "Missing value for $flag\n$usage" }
            val value = args[index++]
            when (flag) {
                "--workspace" -> { require(workspacePath == null); workspacePath = value }
                "--allow" -> { require(allowed.add(value)) { "Duplicate --allow path" } }
                "--port" -> { port = value.toInt(); require(port in 0..65535) }
                else -> error("Unknown option: $flag\n$usage")
            }
        }
        val root = Path.of(requireNotNull(workspacePath) { "--workspace is required\n$usage" }).toRealPath()
        require(Files.isDirectory(root)) { "Workspace must be an existing directory" }
        val workspace = if (allowed.isEmpty()) null else PiWorkspace(root, allowed)
        val modelId = requireNotNull(System.getenv("PI_MODEL")?.takeIf { it.isNotBlank() }) { "PI_MODEL is required" }
        require(modelId.length <= 128)
        val auth = when (System.getenv("PI_AUTH") ?: "required") {
            "required" -> LlmAuth.REQUIRED
            "none" -> LlmAuth.NONE
            else -> error("PI_AUTH must be required or none")
        }
        val rawToken = System.getenv("PI_ACCESS_TOKEN")?.takeIf { it.isNotBlank() }
        val suppliedHash = System.getenv("PI_ACCESS_TOKEN_SHA256")?.takeIf { it.isNotBlank() }
        require((rawToken == null) != (suppliedHash == null)) { "Set exactly one Pi access token or token hash" }
        val tokenHash = suppliedHash ?: ContentId.of(requireNotNull(rawToken).also {
            require(it.length in 17..2041 && '\r' !in it && '\n' !in it) { "Pi access token must contain 17–2041 characters" }
        }.encodeToByteArray()).hex
        require(tokenHash.matches(Regex("[0-9a-f]{64}"))) { "PI_ACCESS_TOKEN_SHA256 must be lowercase SHA-256 hex" }
        val configuredBase = System.getenv("PI_BASE_URL")?.takeIf { it.isNotBlank() }
        val baseUrl = if (auth == LlmAuth.NONE) {
            noAuthLoopbackBaseUrl(configuredBase)
        } else {
            require(!System.getenv("OPENAI_API_KEY").isNullOrBlank()) { "OPENAI_API_KEY is required" }
            configuredBase ?: "https://api.openai.com/v1"
        }
        val keyMux = if (auth == LlmAuth.NONE) KeyMux { }
            else KeyMux { bind("llm.openai.key", EnvVarSource("OPENAI_API_KEY")) }
        val provider = if (auth == LlmAuth.NONE) "development-relay" else "openai"
        val mux = ModelMux(keyMux) { model(modelId, caps = _s["chat", "tools"], provider = provider, baseUrl = baseUrl, auth = auth) }
        val pagesJs = requireNotNull(GraalPiMain::class.java.classLoader.getResourceAsStream("web/kotlin/pages/pages.js")) {
            "Missing production pages.js bundle; package the Kotlin pages browser bundle"
        }.use { it.readBytes() }
        val navCss = requireNotNull(GraalPiMain::class.java.classLoader.getResourceAsStream("web/application-nav.css")) {
            "Missing production application navigation stylesheet"
        }.use { it.readBytes() }
        val owner = SupervisorJob()
        var wire: PiWire? = null
        var server: HttpServer? = null
        val pool = Executors.newFixedThreadPool(4)
        val stop = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var shutdown: Thread? = null
        var transport: HtxElement? = null
        try {
            val htx = openHtxElement()
            transport = htx
            val scope = CoroutineScope(owner + Dispatchers.IO)
            val crumbs = BreadcrumbElement()
            val actualWire = PiWire(scope, htx, crumbs, { mux }, tokenHash, workspace = workspace)
            wire = actualWire
            val listener = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16)
            server = listener
            listener.executor = pool
            listener.createContext("/") { exchange ->
                try { serve(exchange, actualWire, pagesJs, navCss) }
                catch (_: Exception) { runCatching { respond(exchange, 500, "text/plain; charset=utf-8", "Request failed".encodeToByteArray()) } }
                finally { exchange.close() }
            }
            listener.start()
            val hook = Thread { stop.countDown(); finished.await() }
            shutdown = hook
            Runtime.getRuntime().addShutdownHook(hook)
            println("Pi listening at http://127.0.0.1:${listener.address.port}/pi")
            withContext(Dispatchers.IO) { stop.await() }
        } finally {
            try {
                withContext(NonCancellable) {
                    try { server?.stop(0) } finally {
                        try { pool.shutdownNow() } finally {
                            try { wire?.drain() } finally { try { transport?.close() } finally { owner.cancelAndJoin() } }
                        }
                    }
                }
            } finally {
                finished.countDown()
                shutdown?.let { runCatching { Runtime.getRuntime().removeShutdownHook(it) } }
            }
        }
    }

    private fun serve(exchange: HttpExchange, wire: PiWire, pagesJs: ByteArray, navCss: ByteArray) {
        val path = exchange.requestURI.toASCIIString()
        if (exchange.requestMethod == "GET" && exchange.requestURI.path == "/kotlin/pages/pages.js") {
            respond(exchange, 200, "text/javascript; charset=utf-8", pagesJs)
            return
        }
        if (exchange.requestMethod == "GET" && exchange.requestURI.path == "/application-nav.css") {
            respond(exchange, 200, "text/css; charset=utf-8", navCss)
            return
        }
        if (exchange.requestMethod == "GET" && exchange.requestURI.path == "/") {
            exchange.responseHeaders.set("Location", "/pi")
            respond(exchange, 302, "text/plain; charset=utf-8", ByteArray(0))
            return
        }
        val body = exchange.requestBody.use { it.readNBytes(REQUEST_LIMIT + 1) }
        if (body.size > REQUEST_LIMIT) { respond(exchange, 413, "text/plain; charset=utf-8", ByteArray(0)); return }
        val text = try { body.decodeToString(throwOnInvalidSequence = true) }
        catch (_: Exception) { respond(exchange, 400, "text/plain; charset=utf-8", ByteArray(0)); return }
        val request = buildString {
            append(exchange.requestMethod).append(' ').append(path).append(" HTTP/1.1\r\n")
            for ((name, values) in exchange.requestHeaders) for (value in values) {
                require('\r' !in name && '\n' !in name && '\r' !in value && '\n' !in value)
                append(name).append(": ").append(value).append("\r\n")
            }
            append("\r\n").append(text)
        }
        val result = runBlocking { wire.route(exchange.requestMethod, path, request, null) }
        if (result == null) respond(exchange, 404, "text/plain; charset=utf-8", ByteArray(0))
        else {
            result.headers.forEach { (name, value) -> exchange.responseHeaders.set(name, value) }
            respond(exchange, result.status, result.contentType, result.payloadBytes)
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
}
