package borg.trikeshed.loom

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.*
import borg.trikeshed.litebike.taxonomy.Protocol
import borg.trikeshed.loom.RegistryVectors.RegistryCase
import kotlinx.coroutines.*
import kotlin.test.*

/**
 * registry.rs against what its Rust build did for the same inputs ([RegistryVectors]), through a platform's litebike
 * listener. A load case scripts an [OriginFake] with the replies the Rust harness's fake gave: the requests it records
 * equal Rust's and the result is the same. Over the registry [RegistryVectors.routeLoad] loads, each route answers as
 * axum's router did, and the registry's own listener answers each raw request with the bytes hyper wrote.
 */
abstract class RegistryParity {
    /** Serves [listener] on 127.0.0.1:[port] until cancelled; binds before its first suspension. */
    abstract suspend fun bind(listener: LitebikeListenerElement, port: Int)

    /** A loopback port free when asked. */
    abstract fun port(): Int

    /** [request] on a new connection to 127.0.0.1:[port], then every byte answered until the peer closes. */
    abstract fun exchange(port: Int, request: ByteArray): ByteArray

    /** The platform's HTX reactor, open. */
    abstract suspend fun reactor(): HtxReactorElement

    /** loom-registry.rs serve's reading of [text] (serde_json into RegistryConfig), then `Registry::load_for_loopback_test`. */
    suspend fun load(text: String, routes: HtxRouteService): Registry {
        val config = try {
            RegistryConfig.from_slice(text.encodeToByteArray())
        } catch (refused: IllegalStateException) {
            error("config invalid")
        }
        return Registry.load_for_loopback_test(config, routes)
    }

    /** [rust]'s load case against an origin answering its replies, then [after] over the registry it loaded. */
    suspend fun case(name: String, rust: RegistryCase, after: suspend (Registry, OriginFake) -> Unit = { _, _ -> }) {
        val origin = OriginFake(this).start()
        val reactor = reactor()
        try {
            for (reply in rust.replies.view) origin.replies.add(reply.replace("{base}", origin.base).encodeToByteArray())
            var registry: Registry? = null
            val result = try {
                registry = load(rust.config.replace("{base}", origin.base), reactor)
                "ok:" + registry.defaults().view.joinToString("\n") { (source, default) -> "serving ${source.a}:${default.a} ${default.b} from ${source.b}" }
            } catch (refused: IllegalStateException) {
                "error:${refused.message}"
            }
            assertEquals(rust.result.replace("{base}", origin.base), result, name)
            assertEquals(rust.requests.size, origin.log.size, name)
            for (i in 0 until rust.requests.size) assertSameRequest(rust.requests[i].hexToByteArray(), origin.log[i], origin.base, "$name request $i")
            registry?.let { after(it, origin) }
        } finally {
            reactor.close()
            origin.stop()
        }
    }

    suspend fun load_cases() {
        for ((name, rust) in RegistryVectors.cases.view) case(name, rust)
    }

    /** The routes through [Registry.serve], then the wire through the registry's listener on a platform bind. */
    suspend fun serve_cases() = case("routeLoad", RegistryVectors.routeLoad) { registry, origin ->
        for (route in RegistryVectors.routes.view) {
            val response = registry.serve(route.method, Registry.path(route.uri.encodeToByteArray())!!)
            val name = "${route.method} ${route.uri}"
            assertEquals(route.status, response.status, name)
            assertEquals(route.headers.view.map { it.replace("{base}", origin.base) }, response.headers.view.map { "${it.a}: ${it.b}" }, name)
            assertEquals(route.body, response.body.toArray().decodeToString(), name)
        }
        val port = port()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        registry.open()
        try {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { bind(registry.listener, port) }
            for (wire in RegistryVectors.wire.view) {
                val request = wire.request.hexToByteArray()
                val reply = withContext(Dispatchers.Default) { exchange(port, request) }
                assertEquals(wire.reply.replace("{base}", origin.base), reply.decodeToString(), request.decodeToString().substringBefore("\r\n").take(120))
            }
        } finally {
            registry.drain()
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    /**
     * The request Rust sent equals ours, Rust's fake's base read as [base]: request line, body, and headers once each
     * HTTP stack's own are set aside: `host` (each fake's port) on both, reqwest's default `accept: * / *`, and HTX's
     * `connection: close` plus the `accept-encoding: identity` it adds where the caller set none.
     */
    fun assertSameRequest(rust: ByteArray, ours: ByteArray, base: String, name: String) {
        val rustText = rust.decodeToString()
        val rustHost = rustText.split("\r\n").single { it.startsWith("host: ") }.substringAfter("host: ")
        val (rustLine, rustHeaders, rustBody) = parse(rustText.replace("http://$rustHost/", base).encodeToByteArray())
        val (ourLine, ourHeaders, ourBody) = parse(ours)
        assertEquals(rustLine, ourLine, name)
        assertContentEquals(rustBody, ourBody, name)
        assertEquals("127.0.0.1", ourHeaders.single { it.first == "host" }.second.substringBefore(':'), name)
        val expected = rustHeaders.filterNot { it.first == "host" || it == ("accept" to "*/*") }.toMutableList()
        if (expected.none { it.first == "accept-encoding" }) expected += "accept-encoding" to "identity"
        val actual = ourHeaders.filterNot { it.first == "host" || it == ("connection" to "close") }
        assertEquals(expected.sortedBy { it.first }, actual.sortedBy { it.first }, name)
    }

    /** Request line, headers (names lowercased) and body of one raw HTTP/1.1 request. */
    fun parse(raw: ByteArray): Triple<String, List<Pair<String, String>>, ByteArray> {
        val end = raw.indexOfHeaderBoundary()
        val lines = raw.decodeToString(0, end).split("\r\n")
        val headers = lines.drop(1).map { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        return Triple(lines[0], headers, raw.copyOfRange(end + 4, raw.size))
    }
}

/** A loopback origin on the litebike listener through [parity]'s bind: logs each request's bytes and answers it with the next scripted reply. */
class OriginFake(val parity: RegistryParity) {
    val port = parity.port()
    val base = "http://127.0.0.1:$port/"
    val replies = ArrayDeque<ByteArray>()
    val log = mutableListOf<ByteArray>()
    val listener = LitebikeListenerElement()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun start(): OriginFake {
        listener.open()
        val slot = listener.register(Protocol.Http)
        scope.launch {
            while (true) {
                val message = slot.consume()
                log += message.payload
                val reply = replies.removeFirstOrNull() ?: "HTTP/1.1 500 Internal Server Error\r\ncontent-length: 0\r\nconnection: close\r\n\r\n".encodeToByteArray()
                message.respond?.invoke(reply)
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { parity.bind(listener, port) }
        return this
    }

    suspend fun stop() {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        listener.close()
    }
}
