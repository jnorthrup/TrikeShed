package borg.trikeshed.lcnc

import borg.trikeshed.graal.subvm.CamelRuntime
import borg.trikeshed.graal.subvm.DocumentFeed
import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.narsese.DocumentModel
import borg.trikeshed.pointcut.PointcutBlackboardAdapter
import borg.trikeshed.userspace.nio.InMemoryVolume
import borg.trikeshed.userspace.reactor.MuxReactorElement
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.test.*

/** Installed Camel contexts: stop failure must not become a successful lifecycle receipt. */
class CamelRuntimeShutdownTest {
    @Test
    fun failedStopRetainsTheLiveHandleAndAllowsCleanupRetry() {
        val id = "shutdown-" + UUID.randomUUID()
        val route = CamelRuntime.start(id, "direct:$id", "mock:$id")
        val fault = LinkageError("injected stop linkage failure")
        val detach = onStopping(route) { throw fault }
        try {
            val error = assertFailsWith<IllegalStateException> { CamelRuntime.stop(id) }
            assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it === fault })
            assertSame(route, CamelRuntime.route(id), "failed cleanup must retain the only cleanup handle")
            assertEquals("Started", route.status())
            assertEquals("still-live", CamelRuntime.send(id, "still-live"))
            assertFailsWith<IllegalStateException> { CamelRuntime.start(id, "direct:$id", "mock:$id") }
            detach()
            assertTrue(CamelRuntime.stop(id), "cleanup must remain retryable")
            assertNull(CamelRuntime.route(id))
            assertEquals("Stopped", route.status())
        } finally {
            detach()
            release(route)
        }
    }

    @Test
    fun stopAllAttemptsEveryRouteAndRetainsOnlyFailedCleanup() {
        assertTrue(CamelRuntime.running().isEmpty(), "this test must own every route it stops")
        val suffix = UUID.randomUUID().toString()
        val failed = CamelRuntime.start("a-failed-$suffix", "direct:failed-$suffix", "mock:failed-$suffix")
        val healthy = CamelRuntime.start("z-healthy-$suffix", "direct:healthy-$suffix", "mock:healthy-$suffix")
        val fault = LinkageError("injected stop-all linkage failure")
        val detach = onStopping(failed) { throw fault }
        try {
            val error = assertFailsWith<IllegalStateException> { CamelRuntime.stopAll() }
            assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it === fault })
            assertSame(failed, CamelRuntime.route(failed.id))
            assertNull(CamelRuntime.route(healthy.id), "one failed stop must not abandon unrelated cleanup")
            assertEquals("Stopped", healthy.status())
            detach()
            assertEquals(listOf(failed.id), CamelRuntime.stopAll())
        } finally {
            detach()
            release(failed)
            release(healthy)
        }
    }

    @Test
    fun downReportsPartialFailureAndCountsExchangesThroughConsumerShutdown() = runBlocking {
        assertTrue(CamelRuntime.running().isEmpty(), "this test must own every route it stops")
        val ctx = vmRuntimeTestContext(this)
        val suffix = UUID.randomUUID().toString()
        val failedId = "a-down-failed-$suffix"
        val healthyId = "z-down-healthy-$suffix"
        val up = CamelRouteLegos.up(ctx)
        for (id in listOf(failedId, healthyId)) {
            val out = up.run(LcncNode(id, CamelRouteLegos.UP,
                mapOf("id" to id, "from" to "direct:$id", "to" to "mock:$id")), emptyMap())
            assertEquals(true, out["ok"], out["error"].toString())
        }
        val failed = assertNotNull(CamelRuntime.route(failedId))
        val healthy = assertNotNull(CamelRuntime.route(healthyId))
        val initialFact = ctx.blackboard.get(CamelRouteLegos.routeKey(failedId))
        val detachFailed = onStopping(failed) { throw LinkageError("injected down linkage failure") }
        val detachHealthy = onStopping(healthy) {
            // This exchange crosses the real context before its consumer stops, but after down
            // has begun. Its counter belongs in the final receipt, not an earlier snapshot.
            healthy.bridge.request(healthy.camelContext, healthy.from, CamelRuntime.Request("during-stop"))
        }
        try {
            assertEquals("before-stop", CamelRuntime.send(healthyId, "before-stop"))
            val out = CamelRouteLegos.down(ctx).run(
                LcncNode("down-all", CamelRouteLegos.DOWN, mapOf("all" to "true")), emptyMap())
            assertEquals(false, out["ok"], "a retained live route makes all=true only a partial stop")
            assertEquals(listOf(healthyId), out["stopped"])
            assertEquals(1, out["count"])
            assertTrue(out["error"].toString().contains(failedId))
            assertSame(failed, CamelRuntime.route(failedId))
            assertEquals(initialFact, ctx.blackboard.get(CamelRouteLegos.routeKey(failedId)),
                "a failed stop must not publish Stopped")
            val finalFact = ctx.blackboard.get(CamelRouteLegos.routeKey(healthyId)) as Map<*, *>
            assertEquals("Stopped", finalFact["status"])
            assertEquals(2L, finalFact["exchanges"], "the receipt must include the last exchange before shutdown")
            assertEquals(123L, finalFact["stoppedAtMs"])
            detachFailed()
            val retry = CamelRouteLegos.down(ctx).run(
                LcncNode("retry", CamelRouteLegos.DOWN, mapOf("id" to failedId)), emptyMap())
            assertEquals(true, retry["ok"])
            assertEquals(listOf(failedId), retry["stopped"])
        } finally {
            detachFailed()
            detachHealthy()
            release(failed)
            release(healthy)
        }
    }

    @Test
    fun successfulDownPublishesCountersOnlyAfterConsumerShutdown() = runBlocking {
        val ctx = vmRuntimeTestContext(this)
        val id = "shutdown-counter-" + UUID.randomUUID()
        val up = CamelRouteLegos.up(ctx).run(LcncNode(id, CamelRouteLegos.UP,
            mapOf("id" to id, "from" to "direct:$id", "to" to "mock:$id")), emptyMap())
        assertEquals(true, up["ok"], up["error"].toString())
        val route = assertNotNull(CamelRuntime.route(id))
        val detach = onStopping(route) {
            route.bridge.request(route.camelContext, route.from, CamelRuntime.Request("during-stop"))
        }
        try {
            assertEquals("before-stop", CamelRuntime.send(id, "before-stop"))
            val down = CamelRouteLegos.down(ctx).run(
                LcncNode("down", CamelRouteLegos.DOWN, mapOf("id" to id)), emptyMap())
            assertEquals(true, down["ok"])
            val fact = ctx.blackboard.get(CamelRouteLegos.routeKey(id)) as Map<*, *>
            assertEquals(2L, route.exchanges(), "the real context must accept the callback's last exchange")
            assertEquals(2L, fact["exchanges"], "final counters must follow the consumer's stop")
            assertEquals("Stopped", fact["status"])
        } finally {
            detach()
            release(route)
        }
    }

    @Test
    fun documentFeedRetainsDependenciesUntilItsRouteActuallyStops() = runBlocking {
        val reactor = MuxReactorElement(coroutineContext[Job])
        val bag = BeliefBagElement(parentJob = coroutineContext[Job])
        reactor.open()
        bag.open()
        var feed: DocumentFeed? = null
        var route: CamelRuntime.Route? = null
        var detach: () -> Unit = {}
        try {
            val owner = DocumentFeed.create(this, InMemoryVolume(512, 16), CasStore.inMemory(), EmptyLog,
                bag, PointcutBlackboardAdapter(ConfixBlackboard.empty()), DocumentModel.through(reactor), "unused",
                routeId = "shutdown-document-" + UUID.randomUUID())
            feed = owner
            val retained = assertNotNull(CamelRuntime.route(owner.routeId))
            route = retained
            val dependencies = owner.job.children.toList()
            assertTrue(dependencies.isNotEmpty(), "the existing input and curator workers must be owned")
            detach = onStopping(retained) { throw LinkageError("injected document route stop failure") }
            assertFailsWith<IllegalStateException> { owner.drain() }
            assertSame(retained, CamelRuntime.route(owner.routeId))
            assertTrue(owner.job.isActive, "failed route cleanup must not close its dependency owner")
            assertTrue(dependencies.all { it.isActive }, "workers must remain live until the route is stopped")
            assertFailsWith<IllegalStateException> { owner.submit(byteArrayOf(1), "refused.txt") }
            detach()
            owner.drain()
            assertNull(CamelRuntime.route(owner.routeId))
            assertTrue(owner.job.isCompleted)
            assertTrue(dependencies.all { it.isCompleted })
        } finally {
            detach()
            try { feed?.drain() } finally {
                try { route?.let(::release) } finally {
                    try { bag.drain() } finally { reactor.drain() }
                }
            }
        }
    }

    /** Lifecycle fixture: an empty ledger; any attempted curation is an error, not a fake result. */
    private object EmptyLog : DurableAppendLog {
        override fun append(sequence: Long, payload: ByteArray): Long = error("unexpected curation")
        override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long = 0L
        override fun flush() = Unit
        override fun injectCorruptionAfter(sequence: Long) = error("no retained frames")
    }

    private fun onStopping(route: CamelRuntime.Route, action: () -> Unit): () -> Unit {
        val loader = route.bridge.loader
        val strategyClass = loader.loadClass("org.apache.camel.spi.LifecycleStrategy")
        val strategy = Proxy.newProxyInstance(loader, arrayOf(strategyClass)) { proxy, method, args ->
            when (method.name) {
                "onContextStopping" -> { action(); null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "CamelRuntimeShutdownTest.LifecycleStrategy"
                else -> null
            }
        }
        val context = route.camelContext
        context.javaClass.getMethod("addLifecycleStrategy", strategyClass).invoke(context, strategy)
        return {
            @Suppress("UNCHECKED_CAST")
            val strategies = context.javaClass.getMethod("getLifecycleStrategies").invoke(context) as MutableList<Any>
            strategies.remove(strategy)
            Unit
        }
    }

    private fun release(route: CamelRuntime.Route) {
        try {
            CamelRuntime.stop(route.id)
        } finally {
            // The failing baseline forgets the context, so retain it independently for cleanup.
            route.bridge.stop(route.camelContext)
        }
    }
}
