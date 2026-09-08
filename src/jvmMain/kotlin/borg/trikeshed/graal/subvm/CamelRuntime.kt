package borg.trikeshed.graal.subvm

import borg.trikeshed.lcnc.CamelLinkage
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * CamelRuntime — a CamelContext that OUTLIVES the call that started it.
 *
 * `vm.camel` starts a context, dispatches one body and stops it inside a single lego run
 * (`SubVmLegos.camel`, and `keep=true` there holds the VM handle open while the context still
 * stops). That shape can prove a route carries a message and can prove nothing else: every
 * consumer Camel has — `timer:`, `file:`, `imaps:` — is a POLLER, and a poller has nowhere to
 * live inside a request. Camel is a daemon-shaped thing, and until now it was mounted in a
 * request-shaped hole. This object is the hole made the right shape.
 *
 * ON THE JVM, NOT IN GRAALJS, and that is the whole reason this is reflection rather than a
 * script. A GraalJS context has thread affinity: it may be entered by one thread at a time, and
 * Camel's consumers call back from their own pool. A `from('timer:…')` whose Processor lives in
 * JS would fault on the second tick. Driving `DefaultCamelContext` over the guest
 * [java.net.URLClassLoader] that [GuestModules.loaderFor] already hands out puts Camel's threads
 * on Java the whole way down, and costs one reflective seam that this file confines.
 *
 * THE GATE IS HERE, not at the caller. [CamelLinkage] is checked before a context is even
 * constructed, because this object is the only thing in the tree that can cause Camel to open a
 * socket, and a policy enforced anywhere but the one place that can violate it is a suggestion.
 * A caller may check first for a better error; it may not skip this one.
 *
 * The route is `from(uri).routeId(id).process(observer).to(uri)` — a tap between the two declared
 * endpoints, so every Exchange that crosses is counted and its body offered to [Observer] before
 * it reaches `to`. That tap is what makes a running route WATCHABLE rather than merely up:
 * a fact per exchange is the difference between "the context reports Started" and "something is
 * moving through it", and only the second is worth a lamp.
 */
object CamelRuntime {

    /** The guest module carrying the EIP engine, when a caller names none. */
    const val MODULE: String = CamelCatalog.MODULE

    /**
     * How much of an Exchange body a fact carries. A route over `file:` can pick up megabytes,
     * and the board is a fact plane, not a store: past this the body is truncated and
     * [Exchange.truncated] says so, rather than the board growing an attachment it cannot name.
     */
    const val MAX_FACT_BODY: Int = 4096

    /** Headers this runtime writes onto the actual Camel Exchange before the tap returns. */
    const val HEADER_ROUTE_ID: String = "TrikeShedCamelRouteId"
    const val HEADER_EXCHANGE_ID: String = "TrikeShedCamelExchangeId"
    const val HEADER_SEQ: String = "TrikeShedCamelSeq"
    const val HEADER_PROCESSED: String = "TrikeShedCamelProcessed"

    /** Exchange property set before rethrowing a failing [PayloadProcessor]. */
    const val PROPERTY_PROCESSOR_ERROR: String = "TrikeShedCamelProcessorError"

    /** One Exchange as it crossed the tap, for the caller to publish however it publishes. */
    data class Exchange(
        val routeId: String,
        val seq: Long,
        val body: String,
        val truncated: Boolean,
        val atMs: Long,
    )

    /** What a caller hands in to see traffic. Called on Camel's own consumer thread. */
    fun interface Observer {
        fun onExchange(exchange: Exchange)
    }

    /** Full body plus route-local identity, for code that participates in the Exchange. */
    data class Payload(
        val routeId: String,
        val exchangeId: String?,
        val seq: Long,
        val body: String,
        val atMs: Long,
    )

    /** Host request for a callable route. */
    data class Request(
        val body: String,
        val headers: Map<String, Any?> = emptyMap(),
    )

    /** Body and headers written back to the actual Exchange. */
    data class Reply(
        val body: String,
        val headers: Map<String, Any?> = emptyMap(),
    )

    /** What a caller hands in to process traffic. Called on Camel's own consumer thread. */
    fun interface PayloadProcessor {
        fun process(payload: Payload): Reply
    }

    /** A started route, and the handle that stops it. */
    class Route internal constructor(
        val id: String,
        val module: String,
        val from: String,
        val to: String,
        val startedAtMs: Long,
        internal val bridge: Bridge,
        internal val camelContext: Any,
    ) {
        internal val counter = AtomicLong(0)

        @Volatile
        internal var lastAtMs: Long = 0

        /** How many Exchanges have crossed the tap since start. */
        fun exchanges(): Long = counter.get()

        /** Epoch millis of the last Exchange, or 0 when none has crossed yet. */
        fun lastExchangeAtMs(): Long = lastAtMs

        /** The context's own status word (`Started`, `Stopped`, …), read live. */
        fun status(): String = runCatching { bridge.statusOf(camelContext) }.getOrElse { "Unknown" }

        /** The route ids the context actually holds — one, unless someone added more. */
        fun routeIds(): List<String> = runCatching { bridge.routeIdsOf(camelContext) }.getOrElse { emptyList() }

        /**
         * What does not move while the route runs: what it is, where it reaches, whether it is up.
         *
         * Split from the counters deliberately. A lifecycle fact written once at start and
         * carrying `exchanges: 0` is false from the first tick onward, and a board that says 0
         * beside a stream delivering one a second is worse than a board that says nothing —
         * the reader has to decide which of two surfaces to disbelieve.
         */
        fun identity(): Map<String, Any?> = mapOf(
            "id" to id,
            "module" to module,
            "from" to from,
            "to" to to,
            "status" to status(),
            "routes" to routeIds(),
            "startedAtMs" to startedAtMs,
        )

        /** [identity] plus the counters, true only at the instant it is read. */
        fun describe(): Map<String, Any?> = identity() + mapOf(
            "exchanges" to exchanges(),
            "lastExchangeAtMs" to lastAtMs,
        )
    }

    private val routes = ConcurrentHashMap<String, Route>()

    /** Every route running right now, in start order by id. */
    fun running(): List<Route> = routes.values.sortedBy { it.id }

    /** The route with this id, or null. */
    fun route(id: String): Route? = routes[id]

    /** Whether a route with this id is up. */
    fun isRunning(id: String): Boolean = routes.containsKey(id)

    /**
     * Start a route and leave it running.
     *
     * Returns the [Route] on success. Throws [IllegalStateException] with a message an operator
     * can act on for every refusal: the module is not installed, the linkage gate declined, or
     * the id is already up. Nothing partial is left behind — a context that fails to start is
     * stopped before the throw.
     */
    fun start(
        id: String,
        from: String,
        to: String,
        module: String = MODULE,
        reach: CamelLinkage.Reach = CamelLinkage.Reach.LOCAL,
        observer: Observer? = null,
    ): Route = startRoute(id, from, to, module, reach, observer, null)

    /**
     * Start a route with a host-side processor that receives the COMPLETE body and may replace it.
     *
     * The processor is Exchange work, unlike [Observer]: a failure remains a Camel failure and
     * the caller of [send] or [request] sees it. The bounded observer still runs first.
     */
    fun start(
        id: String,
        from: String,
        to: String,
        module: String = MODULE,
        reach: CamelLinkage.Reach = CamelLinkage.Reach.LOCAL,
        processor: PayloadProcessor,
        observer: Observer? = null,
    ): Route = startRoute(id, from, to, module, reach, observer, processor)

    private fun startRoute(
        id: String,
        from: String,
        to: String,
        module: String,
        reach: CamelLinkage.Reach,
        observer: Observer?,
        payloadProcessor: PayloadProcessor?,
    ): Route {
        require(id.isNotBlank()) { "route id is blank — a running route has to be nameable to be stoppable" }
        check(!routes.containsKey(id)) { "route '$id' is already running — stop it first, or pick another id" }
        check(GuestModules.isInstalled(module)) {
            "guest module '$module' is not installed — install it: " +
                "./gradlew -p utils/subvm install${gradleTaskName(module)}"
        }

        // THE GATE, before anything is constructed. `from` and `to` are the entire reachable
        // surface of the route: the EIPs between them only move an Exchange inside one context.
        val mounted = GuestModules.chain(module)
        for ((port, uri) in listOf("from" to from, "to" to to)) {
            CamelLinkage.refusal(uri, reach, mounted)?.let { why ->
                throw IllegalStateException("route '$id' refuses $port='$uri': $why")
            }
        }

        val loader = GuestModules.loaderFor(module)
            ?: throw IllegalStateException("guest module '$module' resolved no classpath to mount")
        val bridge = Bridge(loader)
        val startedAtMs = System.currentTimeMillis()

        var camelContext: Any? = null
        try {
            withLoader(loader) {
                val ctx = bridge.newContext()
                camelContext = ctx
                val route = Route(id, module, from, to, startedAtMs, bridge, ctx)
                bridge.addRoute(ctx, from, to, id, bridge.processor { exchange ->
                    val seq = route.counter.incrementAndGet()
                    route.lastAtMs = System.currentTimeMillis()
                    val exchangeId = bridge.exchangeIdOf(exchange)
                    bridge.setHeader(exchange, HEADER_ROUTE_ID, id)
                    exchangeId?.let { bridge.setHeader(exchange, HEADER_EXCHANGE_ID, it) }
                    bridge.setHeader(exchange, HEADER_SEQ, seq)
                    if (observer != null || payloadProcessor != null) {
                        val raw = bridge.bodyOf(exchange)
                        // A broken observer must not kill the route: this runs ON Camel's consumer
                        // thread, and a throw here would fail the Exchange and, on a transacted
                        // route, roll it back. The tap observes; it does not participate.
                        if (observer != null) {
                            runCatching {
                                observer.onExchange(
                                    Exchange(
                                        routeId = id,
                                        seq = seq,
                                        body = raw.take(MAX_FACT_BODY),
                                        truncated = raw.length > MAX_FACT_BODY,
                                        atMs = route.lastAtMs,
                                    ),
                                )
                            }
                        }
                        if (payloadProcessor != null) {
                            val payload = Payload(
                                routeId = id,
                                exchangeId = exchangeId,
                                seq = seq,
                                body = raw,
                                atMs = route.lastAtMs,
                            )
                            try {
                                val reply = payloadProcessor.process(payload)
                                bridge.setBody(exchange, reply.body)
                                reply.headers.forEach { (name, value) -> bridge.setHeader(exchange, name, value) }
                                bridge.setHeader(exchange, HEADER_PROCESSED, true)
                            } catch (t: Throwable) {
                                bridge.setProperty(
                                    exchange,
                                    PROPERTY_PROCESSOR_ERROR,
                                    t.message ?: t::class.java.name,
                                )
                                throw t
                            }
                        }
                    } else {
                        // Still stamp correlation on exchanges that have no observer or processor.
                        bridge.setHeader(exchange, HEADER_PROCESSED, false)
                    }
                })
                bridge.start(ctx)
                routes[id] = route
            }
            return routes.getValue(id)
        } catch (t: Throwable) {
            routes.remove(id)
            camelContext?.let { c -> runCatching { withLoader(loader) { bridge.stop(c) } } }
            val unwrapped = unwrap(t)
            throw if (unwrapped is IllegalStateException) unwrapped
            else IllegalStateException("route '$id' failed to start: ${unwrapped.message ?: unwrapped::class.java.name}", unwrapped)
        }
    }

    /** Stop a route and forget it. Returns false when no route by that id was up. */
    fun stop(id: String): Boolean {
        val route = routes.remove(id) ?: return false
        runCatching { withLoader(route.bridge.loader) { route.bridge.stop(route.camelContext) } }
        return true
    }

    /** Stop every route. Called on daemon shutdown; safe to call twice. */
    fun stopAll(): List<String> = running().map { it.id }.onEach { stop(it) }

    /**
     * Send [body] into a running route's `from` endpoint and return the reply body.
     *
     * Only meaningful for a route whose `from` is callable (`direct:`, `seda:`); a `timer:`
     * route produces its own Exchanges and has nothing to send into.
     */
    fun send(id: String, body: String): String = request(id, Request(body)).body

    /**
     * Send [request] into a running route's `from` endpoint and return the Exchange reply.
     *
     * Request headers are put onto Camel's input message, route identity is stamped by the tap,
     * and the returned headers are read from the message after the route has completed.
     */
    fun request(id: String, request: Request): Reply {
        val route = routes[id] ?: throw IllegalStateException("route '$id' is not running")
        return withLoader(route.bridge.loader) { route.bridge.request(route.camelContext, route.from, request) }
    }

    // ── the one reflective seam ────────────────────────────────────────────────

    /**
     * Every class and method this file needs out of the guest loader, resolved once.
     *
     * Held per route rather than cached globally on purpose: a re-resolve of the module hands
     * out a NEW URLClassLoader, and a cached Method from the old one would be invoked against
     * instances from the new — the failure mode that reads as an unrelated ClassCastException
     * hours later. A route pins the loader it started under, and stops under the same one.
     */
    internal class Bridge(val loader: ClassLoader) {
        private val contextClass: Class<*> = loader.loadClass("org.apache.camel.CamelContext")
        private val defaultContextClass: Class<*> = loader.loadClass("org.apache.camel.impl.DefaultCamelContext")
        private val routeBuilderClass: Class<*> = loader.loadClass("org.apache.camel.builder.RouteBuilder")
        private val lambdaRouteBuilderClass: Class<*> = loader.loadClass("org.apache.camel.builder.LambdaRouteBuilder")
        private val processorClass: Class<*> = loader.loadClass("org.apache.camel.Processor")
        private val exchangeClass: Class<*> = loader.loadClass("org.apache.camel.Exchange")
        private val messageClass: Class<*> = loader.loadClass("org.apache.camel.Message")
        private val routeClass: Class<*> = loader.loadClass("org.apache.camel.Route")
        private val routeDefinitionClass: Class<*> = loader.loadClass("org.apache.camel.model.RouteDefinition")
        private val processorDefinitionClass: Class<*> = loader.loadClass("org.apache.camel.model.ProcessorDefinition")
        private val producerTemplateClass: Class<*> = loader.loadClass("org.apache.camel.ProducerTemplate")

        // Camel 4 exposes the static seam an abstract RouteBuilder otherwise denies a non-subclass:
        // addRoutes(CamelContext, LambdaRouteBuilder), whose second parameter is a functional
        // interface — which is what lets a java.lang.reflect.Proxy stand in for a lambda here.
        private val addRoutes: Method =
            routeBuilderClass.getMethod("addRoutes", contextClass, lambdaRouteBuilderClass)
        private val fromMethod: Method = routeBuilderClass.getMethod("from", String::class.java)
        private val routeIdMethod: Method = routeDefinitionClass.getMethod("routeId", String::class.java)
        private val processMethod: Method = processorDefinitionClass.getMethod("process", processorClass)
        private val toMethod: Method = processorDefinitionClass.getMethod("to", String::class.java)
        private val getMessage: Method = exchangeClass.getMethod("getMessage")
        private val getExchangeId: Method = exchangeClass.getMethod("getExchangeId")
        private val setPropertyMethod: Method = exchangeClass.getMethod("setProperty", String::class.java, Any::class.java)
        private val getException: Method = exchangeClass.getMethod("getException")
        private val getBody: Method = messageClass.getMethod("getBody", Class::class.java)
        private val getHeaders: Method = messageClass.getMethod("getHeaders")
        private val setBodyMethod: Method = messageClass.getMethod("setBody", Any::class.java)
        private val setHeaderMethod: Method = messageClass.getMethod("setHeader", String::class.java, Any::class.java)
        private val startMethod: Method = defaultContextClass.getMethod("start")
        private val stopMethod: Method = defaultContextClass.getMethod("stop")
        private val getStatus: Method = defaultContextClass.getMethod("getStatus")
        private val getRoutes: Method = defaultContextClass.getMethod("getRoutes")
        private val routeGetId: Method = routeClass.getMethod("getId")
        private val createProducerTemplate: Method = defaultContextClass.getMethod("createProducerTemplate")
        private val requestMethod: Method = producerTemplateClass.getMethod("request", String::class.java, processorClass)
        private val templateStop: Method = producerTemplateClass.getMethod("stop")

        fun newContext(): Any {
            val ctx = defaultContextClass.getConstructor().newInstance()
            // Camel resolves components, data formats and languages through the application
            // context classloader. Without this it would look on the daemon's own classpath,
            // where none of the 58 guest jars are, and report every scheme as unknown.
            runCatching {
                defaultContextClass.getMethod("setApplicationContextClassLoader", ClassLoader::class.java)
                    .invoke(ctx, loader)
            }
            return ctx
        }

        /** A `Processor` that hands each Exchange to [tap]; [tap] may mutate or fail it. */
        fun processor(tap: (Any) -> Unit): Any =
            Proxy.newProxyInstance(loader, arrayOf(processorClass)) { proxy, method, args ->
                if (method.name == "process" && args != null && args.size == 1) {
                    tap(args[0]!!)
                    null
                } else objectMethod(proxy, method, args)
            }

        fun addRoute(ctx: Any, from: String, to: String, routeId: String, processor: Any) {
            val lambda = Proxy.newProxyInstance(loader, arrayOf(lambdaRouteBuilderClass)) { proxy, method, args ->
                if (method.name == "accept" && args != null && args.size == 1) {
                    val rb = args[0]
                    var def = fromMethod.invoke(rb, from)
                    def = routeIdMethod.invoke(def, routeId)
                    def = processMethod.invoke(def, processor)
                    toMethod.invoke(def, to)
                    null
                } else objectMethod(proxy, method, args)
            }
            addRoutes.invoke(null, ctx, lambda)
        }

        fun start(ctx: Any) { startMethod.invoke(ctx) }

        fun stop(ctx: Any) { stopMethod.invoke(ctx) }

        fun statusOf(ctx: Any): String = getStatus.invoke(ctx)?.toString() ?: "Unknown"

        fun routeIdsOf(ctx: Any): List<String> =
            (getRoutes.invoke(ctx) as? List<*>).orEmpty().mapNotNull { r ->
                r?.let { routeGetId.invoke(it)?.toString() }
            }

        fun bodyOf(exchange: Any): String {
            val message = getMessage.invoke(exchange) ?: return ""
            return getBody.invoke(message, String::class.java)?.toString() ?: ""
        }

        fun exchangeIdOf(exchange: Any): String? = getExchangeId.invoke(exchange)?.toString()

        fun setBody(exchange: Any, body: String) {
            val message = getMessage.invoke(exchange) ?: return
            setBodyMethod.invoke(message, body)
        }

        fun setHeader(exchange: Any, name: String, value: Any?) {
            val message = getMessage.invoke(exchange) ?: return
            setHeaderMethod.invoke(message, name, value)
        }

        fun setProperty(exchange: Any, name: String, value: Any?) {
            setPropertyMethod.invoke(exchange, name, value)
        }

        fun headersOf(exchange: Any): Map<String, Any?> {
            val message = getMessage.invoke(exchange) ?: return emptyMap()
            val headers = getHeaders.invoke(message) as? Map<*, *> ?: return emptyMap()
            return headers.entries.mapNotNull { entry ->
                (entry.key as? String)?.let { it to entry.value }
            }.toMap()
        }

        fun request(ctx: Any, endpoint: String, request: Request): Reply {
            var template: Any? = null
            try {
                template = createProducerTemplate.invoke(ctx)
                val reply = requestMethod.invoke(
                    template,
                    endpoint,
                    processor { exchange ->
                        setBody(exchange, request.body)
                        request.headers.forEach { (name, value) -> setHeader(exchange, name, value) }
                    },
                ) ?: return Reply("")
                val failure = getException.invoke(reply) as? Throwable
                if (failure != null) throw failure
                return Reply(bodyOf(reply), headersOf(reply))
            } catch (t: Throwable) {
                throw unwrap(t)
            } finally {
                template?.let { runCatching { templateStop.invoke(it) } }
            }
        }

        /**
         * A Proxy receives `hashCode`/`equals`/`toString` too, and answering them with null —
         * the shape a naive handler falls into — makes `hashCode` throw a NullPointerException
         * the moment Camel puts the Processor in a map, which it does.
         */
        private fun objectMethod(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.getOrNull(0)
            "toString" -> "CamelRuntime.Proxy@" + Integer.toHexString(System.identityHashCode(proxy))
            else -> null
        }
    }

    /**
     * Run [block] with the guest loader as the thread context classloader.
     *
     * Camel reads the TCCL in several places during start (component resolution, type
     * converter discovery, the JAXB model loader). Setting the application context classloader
     * covers most of it; this covers the rest, and is scoped to the call so the daemon's own
     * threads are handed back exactly what they had.
     */
    private fun <T> withLoader(loader: ClassLoader, block: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = loader
        try {
            return block()
        } finally {
            thread.contextClassLoader = previous
        }
    }

    private tailrec fun unwrap(t: Throwable): Throwable =
        if (t is InvocationTargetException) unwrap(t.targetException) else t

    /** `camel-mail` -> `CamelMail`, so a refusal names a Gradle task that exists. */
    private fun gradleTaskName(module: String): String =
        module.split('-').filter { it.isNotEmpty() }.joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
}
