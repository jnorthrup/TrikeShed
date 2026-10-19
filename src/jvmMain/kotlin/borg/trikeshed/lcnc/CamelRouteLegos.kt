package borg.trikeshed.lcnc

import borg.trikeshed.graal.subvm.CamelRuntime
import borg.trikeshed.module.ModuleContext
import kotlinx.coroutines.*

/** Borrowed registry: persistent routes belong to the host, outside an LCNC invocation. */
object CamelRouteRegistryKey : LcncServiceKey<CamelRouteRegistry>("CamelRouteRegistryKey")

/** A newly allocated context whose startup failed without completing cleanup. */
class CamelRouteStartFailure(val route: CamelRouteHandle, cause: Throwable) :
    IllegalStateException(cause.message, cause)

interface CamelRouteRegistry {
    fun route(id: String): CamelRouteHandle?
    fun running(): List<CamelRouteHandle>
    fun start(id: String, from: String, to: String, module: String, reach: CamelLinkage.Reach, observer: CamelRuntime.Observer): CamelRouteHandle
    fun stop(id: String): Boolean
    fun stop(handle: CamelRouteHandle): Boolean {
        val current = route(handle.id) ?: return false
        check(current.token === handle.token) { "route '${handle.id}' now belongs to another context" }
        return stop(handle.id)
    }
}

/** Delegates to CamelRuntime's existing live registry; no second route map. */
object HostCamelRouteRegistry : CamelRouteRegistry {
    private class Handle(val route: CamelRuntime.Route) : CamelRouteHandle {
        override val id: String get() = route.id
        override val token: Any get() = route
        override val cleanupPending: Boolean get() = route.cleanupPending
        override fun status() = route.status()
        override fun routeIds() = route.routeIds()
        override fun identity() = route.identity()
        override fun describe() = route.describe()
    }

    override fun route(id: String): CamelRouteHandle? = CamelRuntime.route(id)?.let(::Handle)
    override fun running(): List<CamelRouteHandle> = CamelRuntime.running().map(::Handle)
    override fun start(id: String, from: String, to: String, module: String, reach: CamelLinkage.Reach, observer: CamelRuntime.Observer): CamelRouteHandle =
        try { Handle(CamelRuntime.start(id, from, to, module, reach, observer)) }
        catch (failure: CamelRuntime.StartFailure) {
            throw CamelRouteStartFailure(Handle(failure.route), failure)
        }
    override fun stop(id: String): Boolean = CamelRuntime.stop(id)
    override fun stop(handle: CamelRouteHandle): Boolean = CamelRuntime.stop((handle as Handle).route)
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

    /** The host owns the lanes; first use opens them, and shutdown drains before dependencies. */
    fun register(ctx: ModuleContext): CamelRouteElement {
        check(ctx.camelRoutes == null) { "Camel routes already registered for this host" }
        val owner = CamelRouteElement(ctx.scope.coroutineContext.job)
        ctx.camelRoutes = owner
        ctx.lcncRunners[UP] = owned(ctx, owner, up(ctx))
        ctx.lcncRunners[DOWN] = owned(ctx, owner, down(ctx))
        ctx.lcncRunners[ROUTES] = owned(ctx, owner, routes())
        return owner
    }

    private fun owned(ctx: ModuleContext, owner: CamelRouteElement, runner: LcncNodeRunner): LcncNodeRunner =
        boundLcnc(owner, boundLcnc(CamelRouteRegistryKey(HostCamelRouteRegistry)) { _, node, inputs ->
            val selected = checkNotNull(currentCoroutineContext()[CamelRouteElement])
            withContext(ctx.scope.coroutineContext.minusKey(Job)) { selected.open() }
            selected.command {
                // Blocking Camel/Java library work is the host boundary; admission and joins
                // remain the commonMain owner's userspace channel composition.
                withContext(Dispatchers.IO) { runner.execute(node, inputs) }
            }
        })

    private fun publish(owner: CamelRouteElement?, action: () -> Unit) {
        if (owner == null) action()
        else runBlocking(owner.supervisor) { owner.observe { action() } }
    }

    private suspend fun stop(
        ctx: ModuleContext, registry: CamelRouteRegistry, route: CamelRouteHandle, owner: CamelRouteElement?,
    ): Boolean {
        if (!registry.stop(route)) return false
        val fact = route.describe() + mapOf("status" to "Stopped", "stoppedAtMs" to ctx.clock())
        if (owner == null) ctx.blackboard.put(routeKey(route.id), fact, LANGUAGE)
        else owner.observe { ctx.blackboard.put(routeKey(route.id), fact, LANGUAGE) }
        return true
    }

    /** A failed start with incomplete cleanup has the same stop obligation as a successful one. */
    private suspend fun retain(
        ctx: ModuleContext, registry: CamelRouteRegistry, route: CamelRouteHandle, owner: CamelRouteElement?,
    ) {
        if (owner == null) return
        var stoppedFact: Map<String, Any?>? = null
        owner.retain(route) {
            withContext(Dispatchers.IO) {
                val fact = stoppedFact ?: run {
                    check(registry.stop(route)) { "route '${route.id}' was not stopped by its owner" }
                    (route.describe() + mapOf("status" to "Stopped", "stoppedAtMs" to ctx.clock()))
                        .also { stoppedFact = it }
                }
                owner.observe { ctx.blackboard.put(routeKey(route.id), fact, LANGUAGE) }
            }
        }
    }

    // ── up: start a route and leave it running ─────────────────────────────────

    internal fun up(
        ctx: ModuleContext,
        registry: CamelRouteRegistry = HostCamelRouteRegistry,
    ) = boundLcnc(CamelRouteRegistryKey(registry)) { service, node, inputs ->
        val routes = service.value
        val owner = currentCoroutineContext()[CamelRouteElement]
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
            require(owner == null || owner.owns(existing)) { "route '$id' belongs to another owner" }
            val identity = existing.identity()
            require(identity["from"] == from && identity["to"] == to && identity["module"] == module &&
                identity["reach"] == reach.name) {
                "route '$id' already exists with different endpoints, module or reach"
            }
            if (existing.cleanupPending) return@boundLcnc mapOf(
                "id" to id, "status" to existing.status(), "routes" to existing.routeIds(),
                "ok" to false, "cleanupPending" to true,
                "error" to "route '$id' startup failed; cleanup is still pending",
            )
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
                publish(owner) { ctx.blackboard.put(
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
                ) }
            }
        }.getOrElse { t ->
            if (t is CancellationException) throw t
            if (t is CamelRouteStartFailure) {
                // The exception carries this construction's exact token; never adopt by ID.
                retain(ctx, routes, t.route, owner)
                val identity = t.route.identity()
                if (owner == null) ctx.blackboard.put(routeKey(id), identity, LANGUAGE)
                else owner.observe { ctx.blackboard.put(routeKey(id), identity, LANGUAGE) }
                return@boundLcnc mapOf(
                    "id" to id, "status" to t.route.status(), "routes" to t.route.routeIds(),
                    "ok" to false, "cleanupPending" to true, "error" to t.message,
                )
            }
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

        retain(ctx, routes, started, owner)

        // identity(), not describe(): the counters belong to the exchange key, which is live.
        // A lifecycle fact carrying exchanges=0 forever would contradict the stream beside it.
        val identity = started.identity()
        if (owner == null) ctx.blackboard.put(routeKey(id), identity, LANGUAGE)
        else owner.observe { ctx.blackboard.put(routeKey(id), identity, LANGUAGE) }
        mapOf(
            "id" to id,
            "status" to started.status(),
            "routes" to started.routeIds(),
            "ok" to true,
            "error" to "",
        )
    }

    // ── down: stop a route ─────────────────────────────────────────────────────

    internal fun down(
        ctx: ModuleContext,
        registry: CamelRouteRegistry = HostCamelRouteRegistry,
    ) = boundLcnc(CamelRouteRegistryKey(registry)) { service, node, inputs ->
        val routes = service.value
        val owner = currentCoroutineContext()[CamelRouteElement]
        val allValue = VmRuntimeNodes.value(node, inputs, "all") ?: false
        val all = when (allValue) {
            is Boolean -> allValue
            is String -> requireNotNull(allValue.toBooleanStrictOrNull()) { "all must be true or false" }
            else -> throw IllegalArgumentException("all must be true or false")
        }
        val id = VmRuntimeNodes.string(node, inputs, "id", "", allowBlank = true).trim()
        val targets: List<CamelRouteHandle> = when {
            all -> routes.running().filter { owner == null || owner.owns(it) }
            id.isEmpty() -> emptyList()
            else -> listOfNotNull(routes.route(id)).filter { owner == null || owner.owns(it) }
        }
        val stopped = mutableListOf<String>()
        val errors = mutableListOf<String>()
        if (!all && id.isNotEmpty() && targets.isEmpty()) errors += "route '$id' is not running"
        // The lifecycle key is REWRITTEN, not removed: "this route ran and is now stopped" is a
        // different fact from "no such route was ever here", and a watcher that saw the start
        // deserves to see the stop rather than a key that quietly vanishes. The counters land
        // here after cleanup, read from the retained handle so the last exchange is included.
        for (route in targets) {
            val didStop = try {
                (owner?.stop(route) ?: stop(ctx, routes, route, null))
                    .also { if (!it) errors += "route '${route.id}' is not running" }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                errors += "route '${route.id}' failed to stop: ${t.message ?: t::class.java.name}"
                false
            }
            if (!didStop) continue
            stopped += route.id
        }
        mapOf(
            "stopped" to stopped,
            "count" to stopped.size,
            "ok" to errors.isEmpty(),
            "error" to errors.joinToString("; "),
        )
    }

    // ── routes: what is up right now ───────────────────────────────────────────

    /**
     * Read-only, and takes no [ModuleContext] for that reason: it reports what the runtime holds.
     * The same argument `vm.camel.catalog` makes about itself — a lego with no port through which
     * it could start, stop or reach anything is safe to call from a picklist that fills on open.
     */
    internal fun routes(
        registry: CamelRouteRegistry = HostCamelRouteRegistry,
    ) = boundLcnc(CamelRouteRegistryKey(registry)) { service, _, _ ->
        val owner = currentCoroutineContext()[CamelRouteElement]
        val running = service.value.running().filter { owner == null || owner.owns(it) }
        mapOf(
            // A real List<String>, not a serialized array: the picklist resolver walks
            // outputs.ids[] in the browser, exactly as camel.catalog's endpoints[] is walked.
            "ids" to running.map { it.id },
            "routes" to running.map { it.describe() },
            "count" to running.size,
        )
    }
}
