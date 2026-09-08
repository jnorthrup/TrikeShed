package borg.trikeshed.lcnc

import borg.trikeshed.graal.subvm.CamelRuntime
import borg.trikeshed.module.ModuleContext
import kotlinx.coroutines.CancellationException

/** Borrowed registry: persistent routes belong to the host, outside an LCNC invocation. */
object CamelRouteRegistryKey : LcncServiceKey<CamelRouteRegistry>("CamelRouteRegistryKey")

interface CamelRouteHandle {
    val id: String
    fun status(): String
    fun routeIds(): List<String>
    fun identity(): Map<String, Any?>
    fun describe(): Map<String, Any?>
}

interface CamelRouteRegistry {
    fun route(id: String): CamelRouteHandle?
    fun running(): List<CamelRouteHandle>
    fun start(id: String, from: String, to: String, module: String, reach: CamelLinkage.Reach, observer: CamelRuntime.Observer): CamelRouteHandle
    fun stop(id: String): Boolean
}

/** Delegates to CamelRuntime's existing live registry; no second route map. */
object HostCamelRouteRegistry : CamelRouteRegistry {
    private class Handle(private val route: CamelRuntime.Route) : CamelRouteHandle {
        override val id: String get() = route.id
        override fun status() = route.status()
        override fun routeIds() = route.routeIds()
        override fun identity() = route.identity()
        override fun describe() = route.describe()
    }

    override fun route(id: String): CamelRouteHandle? = CamelRuntime.route(id)?.let(::Handle)
    override fun running(): List<CamelRouteHandle> = CamelRuntime.running().map(::Handle)
    override fun start(id: String, from: String, to: String, module: String, reach: CamelLinkage.Reach, observer: CamelRuntime.Observer): CamelRouteHandle =
        Handle(CamelRuntime.start(id, from, to, module, reach, observer))
    override fun stop(id: String): Boolean = CamelRuntime.stop(id)
}

/**
 * `vm.camel.up` / `vm.camel.down` / `vm.camel.routes` — the legos that own a route's LIFETIME,
 * as against `vm.camel`, which owns one dispatch.
 *
 * The split is the point. `vm.camel` answers "does this route carry a message", start to stop
 * inside one run, and that is the right shape for a request. It cannot answer "what has arrived
 * since Tuesday", because every consumer Camel has is a poller and a poller outlives the gesture
 * that started it. These three put the route on the daemon's clock instead of the caller's, and
 * publish what crosses it as facts — so a channel becomes something an operator WATCHES rather
 * than something they re-run to sample.
 *
 * ONE WRITER, the way [LcncPublisher] is one writer: the observer installed here is the only
 * thing that writes `camel/route/…`, so the board's account of what a route has carried has a
 * single provenance and cannot be raced by a second publisher with a different idea of the shape.
 *
 * The facts are two keys per route and no more:
 *
 *  - `camel/route/<id>` — lifecycle: what it is, where it reaches, whether it is up. Written on
 *    start and on stop.
 *  - `camel/route/<id>/exchange` — traffic: the last Exchange that crossed the tap, with its
 *    sequence number. Written once per Exchange, latest-wins.
 *
 * Latest-wins on one key rather than a key per Exchange because the board is a fact plane and a
 * `timer:` route would otherwise mint a key a second forever. Nothing is lost to a watcher: every
 * put is a revision, and `/blackboard/facts` carries revisions, not the snapshot.
 */
object CamelRouteLegos {

    const val UP = "vm.camel.up"
    const val DOWN = "vm.camel.down"
    const val ROUTES = "vm.camel.routes"

    /** The board key holding a route's lifecycle entry. */
    fun routeKey(id: String): String = "camel/route/$id"

    /** The board key holding the last Exchange that crossed a route's tap. */
    fun exchangeKey(id: String): String = "camel/route/$id/exchange"

    private const val LANGUAGE = "camel"

    @Volatile
    private var shutdownHookInstalled = false

    /**
     * Register the three lifetime legos. Called from the daemon beside
     * [SubVmLegos.register]; no other site registers them.
     */
    fun register(ctx: ModuleContext) {
        ctx.lcncRunners[UP] = up(ctx)
        ctx.lcncRunners[DOWN] = down(ctx)
        ctx.lcncRunners[ROUTES] = routes()
        installShutdownHook()
    }

    /**
     * A started route survives the run that started it — including, without this, the JVM's own
     * exit, where Camel's non-daemon consumer threads would hold the process up. Registered once.
     */
    private fun installShutdownHook() {
        if (shutdownHookInstalled) return
        synchronized(this) {
            if (shutdownHookInstalled) return
            runCatching {
                Runtime.getRuntime().addShutdownHook(Thread({ CamelRuntime.stopAll() }, "camel-routes-stop"))
            }
            shutdownHookInstalled = true
        }
    }

    // ── up: start a route and leave it running ─────────────────────────────────

    fun up(
        ctx: ModuleContext,
        registry: CamelRouteRegistry = HostCamelRouteRegistry,
    ) = boundLcnc(CamelRouteRegistryKey(registry)) { service, node, inputs ->
        val routes = service.value
        val id = VmRuntimeNodes.string(node, inputs, "id", node.id).trim()
        val from = VmRuntimeNodes.string(node, inputs, "from", "timer:lcnc?period=1000")
        val to = VmRuntimeNodes.string(node, inputs, "to", "log:lcnc")
        val module = VmRuntimeNodes.string(node, inputs, "module", CamelRuntime.MODULE)
        val reachName = VmRuntimeNodes.string(node, inputs, "reach", "LOCAL").trim().uppercase()
        val reach = requireNotNull(CamelLinkage.Reach.entries.firstOrNull { it.name == reachName }) { "unknown Camel reach '$reachName'" }

        // Starting an id that is already up is idempotent rather than an error: a run block that
        // rebuilds re-runs its nodes, and a route that answered "already running" with a refusal
        // would make a rebuild fail for having succeeded earlier.
        routes.route(id)?.let { existing ->
            val identity = existing.identity()
            require(identity["from"] == from && identity["to"] == to && identity["module"] == module) {
                "route '$id' already exists with different endpoints or module"
            }
            return@boundLcnc mapOf(
                "id" to id,
                "status" to existing.status(),
                "routes" to existing.routeIds(),
                "ok" to true,
                "error" to "",
            )
        }

        val started = runCatching {
            routes.start(id, from, to, module, reach) { exchange ->
                ctx.blackboard.put(
                    exchangeKey(id),
                    mapOf(
                        "route" to id,
                        "seq" to exchange.seq,
                        "body" to exchange.body,
                        "truncated" to exchange.truncated,
                        "from" to from,
                        "to" to to,
                        "atMs" to exchange.atMs,
                    ),
                    LANGUAGE,
                )
            }
        }.getOrElse { t ->
            if (t is CancellationException) throw t
            // The gate's refusal IS the product here: it names the scheme and the remedy, and a
            // caller that swallowed it into a blank output would leave an operator reading source.
            return@boundLcnc mapOf(
                "id" to id,
                "status" to "Refused",
                "routes" to emptyList<String>(),
                "ok" to false,
                "error" to (t.message ?: t::class.java.name),
            )
        }

        // identity(), not describe(): the counters belong to the exchange key, which is live.
        // A lifecycle fact carrying exchanges=0 forever would contradict the stream beside it.
        ctx.blackboard.put(routeKey(id), started.identity(), LANGUAGE)
        mapOf(
            "id" to id,
            "status" to started.status(),
            "routes" to started.routeIds(),
            "ok" to true,
            "error" to "",
        )
    }

    // ── down: stop a route ─────────────────────────────────────────────────────

    fun down(
        ctx: ModuleContext,
        registry: CamelRouteRegistry = HostCamelRouteRegistry,
    ) = boundLcnc(CamelRouteRegistryKey(registry)) { service, node, inputs ->
        val routes = service.value
        val allValue = VmRuntimeNodes.value(node, inputs, "all") ?: false
        val all = when (allValue) {
            is Boolean -> allValue
            is String -> requireNotNull(allValue.toBooleanStrictOrNull()) { "all must be true or false" }
            else -> throw IllegalArgumentException("all must be true or false")
        }
        val id = VmRuntimeNodes.string(node, inputs, "id", "", allowBlank = true).trim()
        val targets: List<String> = when {
            all -> routes.running().map { it.id }
            id.isEmpty() -> emptyList()
            routes.route(id) != null -> listOf(id)
            else -> emptyList()
        }
        // Read the counters BEFORE stopping: stop() forgets the route, and a final tally taken
        // afterwards would be the one number nobody can recover.
        val finals = targets.associateWith { routes.route(it)?.describe().orEmpty() }
        val stopped = targets.filter { routes.stop(it) }
        // The lifecycle key is REWRITTEN, not removed: "this route ran and is now stopped" is a
        // different fact from "no such route was ever here", and a watcher that saw the start
        // deserves to see the stop rather than a key that quietly vanishes. The counters land
        // here, where they are final and therefore true.
        for (each in stopped) {
            ctx.blackboard.put(
                routeKey(each),
                finals[each].orEmpty() +
                    mapOf("status" to "Stopped", "stoppedAtMs" to ctx.clock()),
                LANGUAGE,
            )
        }
        mapOf(
            "stopped" to stopped,
            "count" to stopped.size,
            "ok" to (all || id.isEmpty() || stopped.isNotEmpty()),
            "error" to if (!all && id.isNotEmpty() && stopped.isEmpty()) "route '$id' is not running" else "",
        )
    }

    // ── routes: what is up right now ───────────────────────────────────────────

    /**
     * Read-only, and takes no [ModuleContext] for that reason: it reports what the runtime holds.
     * The same argument `vm.camel.catalog` makes about itself — a lego with no port through which
     * it could start, stop or reach anything is safe to call from a picklist that fills on open.
     */
    fun routes(
        registry: CamelRouteRegistry = HostCamelRouteRegistry,
    ) = boundLcnc(CamelRouteRegistryKey(registry)) { service, _, _ ->
        val running = service.value.running()
        mapOf(
            // A real List<String>, not a serialized array: the picklist resolver walks
            // outputs.ids[] in the browser, exactly as camel.catalog's endpoints[] is walked.
            "ids" to running.map { it.id },
            "routes" to running.map { it.describe() },
            "count" to running.size,
        )
    }
}
