package borg.trikeshed.lcnc

import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.couch.CouchDatabase
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

    private fun newContext(blackboard: ConfixBlackboard): ModuleContext {
        val cas = CasStore.inMemory()
        val couchStore = CouchStoreFactory.casBacked(cas)
        return ModuleContext(
            couchDb = CouchDatabase("camel-runtime-test", couchStore, cas),
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
