package borg.trikeshed.lcnc

import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProductionRegistry
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.subvm.CamelRuntime
import borg.trikeshed.graal.subvm.GuestModules
import borg.trikeshed.job.CasStore
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.module.ModuleRouteRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A camel route that OUTLIVES the call that started it, and says so on the board.
 *
 * The claim under test is one sentence: a `timer:` consumer keeps producing after the gesture that
 * started it has returned. That is the whole difference between [CamelRuntime] and the `vm.camel`
 * lego next to it, and it is the reason a route can be watched instead of re-run — so it is
 * asserted by WAITING for ticks the test never asks for, rather than by reading a status word.
 * A started context with a dead route still reports `Started`; only arrivals prove intake.
 *
 * Real threads, real clock, so [runBlocking] and not `runTest`: Camel's consumers run on their own
 * pool, and virtual time advances none of it.
 */
class CamelRuntimeTest {

    private val started = mutableListOf<String>()

    @AfterTest
    fun stopEverythingThisTestStarted() {
        started.forEach { runCatching { CamelRuntime.stop(it) } }
        started.clear()
    }

    private fun requireModule() {
        assertTrue(
            GuestModules.isInstalled("camel"),
            "guest module 'camel' is not installed — run: ./gradlew -p utils/subvm installCamel",
        )
    }

    private fun track(id: String): String = id.also { started += it }

    /** Poll until [predicate] holds or [timeoutMs] elapses; returns whether it held. */
    private fun awaitUntil(timeoutMs: Long = 6_000, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(25)
        }
        return predicate()
    }

    private fun messages(t: Throwable): String =
        generateSequence(t) { it.cause }.joinToString(" | ") { it.message ?: it::class.java.name }

    private fun newContext(blackboard: ConfixBlackboard): ModuleContext {
        val cas = CasStore.inMemory()
        val couchStore = CouchStoreFactory.casBacked(cas)
        return ModuleContext(
            couch = Couch("camel-runtime-test", couchStore, cas),
            rete = ReteNetwork(),
            productions = ReteProductionRegistry(),
            beliefBag = null,
            turnReview = null,
            blackboard = blackboard,
            casStore = cas,
            attachments = CouchAttachmentGateway(couchStore, cas),
            routes = ModuleRouteRegistry(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            clock = { System.currentTimeMillis() },
            stateDir = File(System.getProperty("java.io.tmpdir"), "camel-runtime-test"),
        )
    }

    // ── the claim ──────────────────────────────────────────────────────────────

    @Test
    fun aTimerRouteKeepsFiringAfterTheCallThatStartedItReturned() {
        requireModule()
        val ticks = AtomicInteger(0)
        val id = track("probe-timer")

        // The gesture: one call, which returns.
        val route = CamelRuntime.start(
            id = id,
            from = "timer:lcnc?period=200",
            to = "log:lcnc",
        ) { ticks.incrementAndGet() }

        assertEquals("Started", route.status(), "the context should be up when start() returns")
        assertEquals(listOf(id), route.routeIds(), "exactly the one declared route should exist")

        // Nothing below asks for a tick. If the context died with the call, this never passes.
        assertTrue(
            awaitUntil { ticks.get() >= 3 },
            "a timer route must keep producing after its starting call returned; saw ${ticks.get()} ticks",
        )
        assertTrue(CamelRuntime.isRunning(id), "the route should still be up")
        assertTrue(route.exchanges() >= 3, "the route's own counter should agree: ${route.exchanges()}")
        assertTrue(route.lastExchangeAtMs() > 0, "the last-exchange stamp should have moved")
    }

    @Test
    fun everyExchangeLandsOnTheBoardWithAnIncreasingSequence() = runBlocking {
        requireModule()
        val blackboard = ConfixBlackboard.empty()
        val ctx = newContext(blackboard)
        val id = track("probe-facts")

        // Every revision, not only the snapshot — a latest-wins key would otherwise look like
        // one fact no matter how many crossed, which is precisely the confusion the tap exists
        // to end.
        val seen = ConcurrentLinkedQueue<Long>()
        @Suppress("DEPRECATION")
        val unsubscribe = blackboard.subscribe {
            val entry = blackboard.get(CamelRouteLegos.exchangeKey(id)) as? Map<*, *>
            (entry?.get("seq") as? Long)?.let { seen += it }
        }
        try {
            val out = CamelRouteLegos.up(ctx).run(
                LcncNode("n1", CamelRouteLegos.UP, params = mapOf(
                    "id" to id, "from" to "timer:lcnc?period=200", "to" to "log:lcnc",
                )),
                emptyMap(),
            )
            assertEquals(true, out["ok"], "up should succeed: ${out["error"]}")

            assertTrue(
                awaitUntil { seen.distinct().size >= 3 },
                "at least three exchange facts should reach the board; saw ${seen.toList()}",
            )
            val distinct = seen.distinct()
            assertEquals(
                distinct.sorted(), distinct,
                "sequence numbers must arrive in order, not be re-ordered by the tap: $distinct",
            )

            val lifecycle = blackboard.get(CamelRouteLegos.routeKey(id)) as? Map<*, *>
            assertTrue(lifecycle != null, "the lifecycle key should exist after up")
            assertEquals("Started", lifecycle["status"], "lifecycle should record it started")
            assertEquals("timer:lcnc?period=200", lifecycle["from"], "lifecycle should record where it reaches")
        } finally {
            unsubscribe()
        }
    }

    @Test
    fun downStopsItAndRewritesTheLifecycleRatherThanErasingIt() = runBlocking {
        requireModule()
        val blackboard = ConfixBlackboard.empty()
        val ctx = newContext(blackboard)
        val id = track("probe-down")

        CamelRouteLegos.up(ctx).run(
            LcncNode("n1", CamelRouteLegos.UP, params = mapOf(
                "id" to id, "from" to "timer:lcnc?period=200", "to" to "log:lcnc",
            )),
            emptyMap(),
        )
        assertTrue(CamelRuntime.isRunning(id))

        // The start-time entry must NOT carry a tally. A lifecycle fact that says `exchanges: 0`
        // while the stream beside it delivers one a second forces the reader to decide which
        // surface to disbelieve; the live count is the exchange key's `seq`.
        val atStart = blackboard.get(CamelRouteLegos.routeKey(id)) as? Map<*, *>
        assertTrue(atStart != null, "the lifecycle key should exist after up")
        assertFalse("exchanges" in atStart.keys, "no stale tally at start: $atStart")
        assertEquals("Started", atStart["status"])

        // Let some actually cross, so the final tally has something true to report.
        assertTrue(
            awaitUntil { (CamelRuntime.route(id)?.exchanges() ?: 0L) >= 2L },
            "the route should have carried something before it is stopped",
        )

        val out = CamelRouteLegos.down(ctx).run(
            LcncNode("n2", CamelRouteLegos.DOWN, params = mapOf("id" to id)), emptyMap(),
        )
        assertEquals(listOf(id), out["stopped"])
        assertFalse(CamelRuntime.isRunning(id), "the route should be gone from the runtime")

        // "ran and stopped" is a different fact from "was never here", and a watcher that saw
        // the start is owed the stop rather than a key that quietly vanishes.
        val lifecycle = blackboard.get(CamelRouteLegos.routeKey(id)) as? Map<*, *>
        assertTrue(lifecycle != null, "the lifecycle key must survive the stop")
        assertEquals("Stopped", lifecycle["status"])
        assertEquals(id, lifecycle["id"], "the prior entry's fields should be carried, not dropped")
        assertTrue((lifecycle["stoppedAtMs"] as? Long ?: 0L) > 0L, "the stop should be stamped")
        // The tally lands HERE, where it is final and therefore true — and it is read before
        // stop() forgets the route, which is the only moment it can still be recovered.
        assertTrue(
            (lifecycle["exchanges"] as? Long ?: 0L) >= 2L,
            "the stop should carry the final tally, not a zero: $lifecycle",
        )
    }

    @Test
    fun aBodySentIntoARunningRouteComesBackOutAndTheTapSawIt() {
        requireModule()
        val id = track("probe-direct")
        val bodies = ConcurrentLinkedQueue<String>()
        CamelRuntime.start(id, from = "direct:probe", to = "log:probe") { bodies += it.body }

        // Distinctive enough that an echo of the SOURCE rather than the routed message shows.
        val reply = CamelRuntime.send(id, "smb-signal-7f3a")
        assertEquals("smb-signal-7f3a", reply, "the body must come back out of the route")
        assertTrue(
            awaitUntil { bodies.contains("smb-signal-7f3a") },
            "the tap between from and to must have seen it: ${bodies.toList()}",
        )
    }

    @Test
    fun payloadProcessorGetsTheFullBodyWhileObserverStaysBounded() {
        requireModule()
        val id = track("probe-full-payload")
        val observed = ConcurrentLinkedQueue<CamelRuntime.Exchange>()
        val payloads = ConcurrentLinkedQueue<CamelRuntime.Payload>()
        val body = "x".repeat(CamelRuntime.MAX_FACT_BODY + 512) + "tail"

        CamelRuntime.start(
            id = id,
            from = "direct:full-payload",
            to = "log:full-payload",
            processor = CamelRuntime.PayloadProcessor { payload ->
                payloads += payload
                CamelRuntime.Reply(
                    body = "processed:${payload.body.length}:${payload.body.takeLast(4)}",
                    headers = mapOf(
                        "curator.length" to payload.body.length,
                        "curator.exchange" to (payload.exchangeId ?: ""),
                    ),
                )
            },
            observer = CamelRuntime.Observer { observed += it },
        )

        val reply = CamelRuntime.request(
            id,
            CamelRuntime.Request(body, mapOf("curator.source" to "llp-test")),
        )

        assertEquals("processed:${body.length}:tail", reply.body)
        assertEquals(body.length, reply.headers["curator.length"])
        assertEquals("llp-test", reply.headers["curator.source"])
        assertEquals(true, reply.headers[CamelRuntime.HEADER_PROCESSED])
        assertEquals(1L, reply.headers[CamelRuntime.HEADER_SEQ])
        assertFalse(
            reply.headers.keys.any { it.startsWith("TrikeShedCamelRetry") },
            "correlation is exchangeId plus route-local seq; the runtime must not mint retry evidence",
        )

        val payload = payloads.toList().single()
        assertEquals(id, payload.routeId)
        assertEquals(1L, payload.seq)
        assertEquals(body, payload.body)
        assertTrue(!payload.exchangeId.isNullOrBlank(), "Camel should supply an exchange id")
        assertEquals(payload.exchangeId, reply.headers[CamelRuntime.HEADER_EXCHANGE_ID])
        assertEquals(payload.exchangeId, reply.headers["curator.exchange"])

        val exchange = observed.toList().single()
        assertEquals(id, exchange.routeId)
        assertEquals(1L, exchange.seq)
        assertEquals(CamelRuntime.MAX_FACT_BODY, exchange.body.length)
        assertTrue(exchange.truncated, "observer facts stay bounded")
        assertFalse(exchange.body.endsWith("tail"), "the observer should not receive the hidden tail")
    }

    @Test
    fun payloadProcessorFailureFailsTheExchange() {
        requireModule()
        val id = track("probe-full-payload-failure")
        val observed = ConcurrentLinkedQueue<CamelRuntime.Exchange>()

        CamelRuntime.start(
            id = id,
            from = "direct:full-payload-failure",
            to = "log:full-payload-failure",
            processor = CamelRuntime.PayloadProcessor { payload -> error("curator failed seq=${payload.seq}") },
            observer = CamelRuntime.Observer { observed += it },
        )

        val failure = runCatching { CamelRuntime.send(id, "bad-payload") }.exceptionOrNull()
        assertTrue(failure != null, "processor failure must reach the sender")
        assertTrue(
            "curator failed seq=1" in messages(failure),
            "the original processor error must remain in the causal chain: ${messages(failure)}",
        )
        assertEquals(1L, CamelRuntime.route(id)?.exchanges())

        val exchange = observed.toList().single()
        assertEquals("bad-payload", exchange.body, "observer still sees the bounded tap before processing")
        assertFalse(exchange.truncated)
    }

    @Test
    fun payloadCorrelationUsesCamelExchangeIdAndRouteSequenceOnly() {
        requireModule()
        val id = track("probe-payload-correlation")
        val payloads = ConcurrentLinkedQueue<CamelRuntime.Payload>()

        CamelRuntime.start(
            id = id,
            from = "direct:payload-correlation",
            to = "log:payload-correlation",
            processor = CamelRuntime.PayloadProcessor { payload ->
                payloads += payload
                CamelRuntime.Reply(
                    body = "seq=${payload.seq}",
                    headers = mapOf("seen.exchange" to (payload.exchangeId ?: "")),
                )
            },
        )

        val first = CamelRuntime.request(id, CamelRuntime.Request("one"))
        val second = CamelRuntime.request(id, CamelRuntime.Request("two"))
        val seen = payloads.toList()

        assertEquals(listOf(1L, 2L), seen.map { it.seq })
        assertEquals("seq=1", first.body)
        assertEquals("seq=2", second.body)
        assertEquals(id, first.headers[CamelRuntime.HEADER_ROUTE_ID])
        assertEquals(id, second.headers[CamelRuntime.HEADER_ROUTE_ID])
        assertEquals(1L, first.headers[CamelRuntime.HEADER_SEQ])
        assertEquals(2L, second.headers[CamelRuntime.HEADER_SEQ])
        assertTrue(seen.all { !it.exchangeId.isNullOrBlank() }, "Camel should name each Exchange")
        assertEquals(seen[0].exchangeId, first.headers[CamelRuntime.HEADER_EXCHANGE_ID])
        assertEquals(seen[1].exchangeId, second.headers[CamelRuntime.HEADER_EXCHANGE_ID])
        assertEquals(seen[0].exchangeId, first.headers["seen.exchange"])
        assertEquals(seen[1].exchangeId, second.headers["seen.exchange"])
        assertTrue(
            seen[0].exchangeId != seen[1].exchangeId,
            "new sends get new Camel Exchange ids; seq is route-local delivery bookkeeping",
        )
        assertFalse(
            (first.headers.keys + second.headers.keys).any {
                it.startsWith("TrikeShedCamelRetry") || it.startsWith("TrikeShedCamelEvidence")
            },
            "the runtime must not convert correlation into retry or evidence identifiers",
        )
    }

    @Test
    fun stoppingATimerRouteStopsProcessorCallbacks() {
        requireModule()
        val id = track("probe-processor-stop")
        val calls = AtomicInteger(0)
        val threadNames = ConcurrentLinkedQueue<String>()
        val testThread = Thread.currentThread().name

        CamelRuntime.start(
            id = id,
            from = "timer:processor-stop?period=75&delay=0",
            to = "log:processor-stop",
            processor = CamelRuntime.PayloadProcessor { payload ->
                calls.incrementAndGet()
                threadNames += Thread.currentThread().name
                CamelRuntime.Reply("tick:${payload.seq}")
            },
        )

        assertTrue(awaitUntil { calls.get() >= 3 }, "timer processor should fire before stop")
        assertTrue(
            threadNames.any { it != testThread },
            "timer route must invoke the host processor from Camel's consumer thread: ${threadNames.toList()}",
        )
        assertTrue(CamelRuntime.stop(id), "the running processor route should stop")
        started.remove(id)

        val stoppedAt = calls.get()
        Thread.sleep(350)
        assertEquals(stoppedAt, calls.get(), "no processor callbacks should arrive after stop")
        assertFalse(CamelRuntime.isRunning(id), "the route should stay out of the registry after stop")
    }

    @Test
    fun startupFailureAfterContextAllocationLeavesNoRouteOrCallbacks() {
        requireModule()
        val id = "probe-startup-cleanup"
        val calls = AtomicInteger(0)

        val failure = runCatching {
            CamelRuntime.start(
                id = id,
                from = "timer:startup-cleanup?period=not-a-number",
                to = "log:startup-cleanup",
                processor = CamelRuntime.PayloadProcessor { payload ->
                    calls.incrementAndGet()
                    CamelRuntime.Reply(payload.body)
                },
            )
        }.exceptionOrNull()

        assertTrue(failure != null, "the installed Camel module should reject the malformed timer period")
        assertFalse(CamelRuntime.isRunning(id), "a failed start must not enter the runtime registry")
        Thread.sleep(200)
        assertEquals(0, calls.get(), "a route that failed during start must not keep a timer callback alive")
    }

    // ── the gate ───────────────────────────────────────────────────────────────

    /**
     * The FIRST gate, and the one departmentalizing gives for free: with only the spine mounted
     * there is no MailComponent to resolve, so `smtp:` is refused by PROVISION before any policy
     * is consulted. A deployment that never bought mail cannot name an smtp endpoint at all.
     */
    @Test
    fun anUnmountedSchemeIsRefusedByProvisionBeforeReachIsEvenConsidered() {
        requireModule()
        val failure = runCatching {
            CamelRuntime.start(
                id = track("probe-unprovisioned"),
                from = "timer:lcnc?period=1000",
                to = "smtp://mail.example.com?to=someone",
                module = "camel",                       // the spine alone
                reach = CamelLinkage.Reach.DEPARTMENT,  // permissive, and still refused
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException, "a refusal is an IllegalStateException, got $failure")
        val message = failure.message.orEmpty()
        assertTrue("smtp" in message, "the refusal must name the scheme: $message")
        assertTrue("camel-mail" in message, "and the module that would ship it: $message")
        // A refusal without the remedy sends an operator to read source that tells them nothing
        // they could not have guessed.
        assertTrue("installCamelMail" in message, "and a Gradle task that exists: $message")
        assertFalse(CamelRuntime.isRunning("probe-unprovisioned"), "nothing may be left running behind a refusal")
    }

    /**
     * The SECOND gate, which exists because provision alone is too coarse once a department is
     * mounted: the operator who wanted an IMAP poller has, by the same act, made SMTP nameable
     * from every other route on the box. Reach is declared on the node so touching the wire is
     * visible in the graph rather than implied by what someone installed months ago.
     */
    @Test
    fun aMountedNetworkSchemeIsStillRefusedUnderLocalReachAndNamesTheRemedy() {
        requireModule()
        assertTrue(
            GuestModules.isInstalled("camel-mail"),
            "guest module 'camel-mail' is not installed — run: ./gradlew -p utils/subvm installCamelMail",
        )
        val failure = runCatching {
            CamelRuntime.start(
                id = track("probe-reach-refused"),
                from = "timer:lcnc?period=1000",
                to = "smtp://mail.example.com?to=someone",
                module = "camel-mail",                 // provisioned, so provision passes
                reach = CamelLinkage.Reach.LOCAL,      // and reach declines
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException, "a refusal is an IllegalStateException, got $failure")
        val message = failure.message.orEmpty()
        assertTrue("smtp" in message, "the refusal must name the scheme: $message")
        assertTrue("DEPARTMENT" in message, "the refusal must name what would change it: $message")
        assertFalse(CamelRuntime.isRunning("probe-reach-refused"), "nothing may be left running behind a refusal")
    }

    @Test
    fun theGateIsInTheRuntimeSoALegoCannotRouteAroundIt() = runBlocking {
        requireModule()
        val ctx = newContext(ConfixBlackboard.empty())
        val out = CamelRouteLegos.up(ctx).run(
            LcncNode("n1", CamelRouteLegos.UP, params = mapOf(
                "id" to track("probe-lego-refused"),
                "from" to "timer:lcnc?period=1000",
                "to" to "smtp://mail.example.com?to=someone",
                "reach" to "LOCAL",
            )),
            emptyMap(),
        )
        assertEquals(false, out["ok"], "the lego must report the refusal, not a blank success")
        assertEquals("Refused", out["status"])
        assertTrue("smtp" in (out["error"] as String), "the refusal must reach the surface: ${out["error"]}")
    }

    @Test
    fun startingAnIdThatIsAlreadyUpIsIdempotentRatherThanAnError() = runBlocking {
        requireModule()
        val ctx = newContext(ConfixBlackboard.empty())
        val id = track("probe-idempotent")
        val node = LcncNode("n1", CamelRouteLegos.UP, params = mapOf(
            "id" to id, "from" to "timer:lcnc?period=500", "to" to "log:lcnc",
        ))
        val first = CamelRouteLegos.up(ctx).run(node, emptyMap())
        // A run block that rebuilds re-runs its nodes; a route that refused for having succeeded
        // earlier would make a rebuild fail for the wrong reason.
        val second = CamelRouteLegos.up(ctx).run(node, emptyMap())
        assertEquals(true, first["ok"])
        assertEquals(true, second["ok"], "re-running up on a live route must not be an error")
        assertEquals(1, CamelRuntime.running().count { it.id == id }, "and must not start a second context")
    }

    @Test
    fun routesReportsWhatIsUpAndTakesNoContextToDoIt() = runBlocking {
        requireModule()
        val id = track("probe-listed")
        CamelRuntime.start(id, from = "timer:lcnc?period=1000", to = "log:lcnc")

        val out = CamelRouteLegos.routes().run(LcncNode("n1", CamelRouteLegos.ROUTES), emptyMap())
        @Suppress("UNCHECKED_CAST")
        val ids = out["ids"] as List<String>
        assertTrue(id in ids, "the running route should be listed for the picklist: $ids")
        assertEquals(ids.size, out["count"])
    }
}
