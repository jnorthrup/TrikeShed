package borg.trikeshed.lcnc

import borg.trikeshed.graal.subvm.CamelRuntime
import borg.trikeshed.graal.subvm.GuestModules
import borg.trikeshed.graal.subvm.DocumentFeed
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.job.CasStore
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.narsese.DocumentModel
import borg.trikeshed.pointcut.PointcutBlackboardAdapter
import borg.trikeshed.userspace.nio.InMemoryVolume
import borg.trikeshed.userspace.reactor.MuxReactorElement
import kotlinx.coroutines.*
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Installed Camel: failed startup is not evidence that its context was stopped. */
class CamelRuntimeStartupCleanupTest {
    @Test
    fun startupFailurePreservesItsCleanupFailure() {
        StartupFault().use { fault ->
            val error = assertFailsWith<IllegalStateException> { fault.start() }
            fault.assertReached()
            assertTrue(error.causes().any { it === fault.startFailure }, "retain the startup cause")
            assertTrue(error.causes().flatMap { it.suppressed.asSequence() }
                .flatMap { it.causes() }.any { it === fault.stopFailure }, "retain the cleanup failure too")
        }
    }

    @Test
    fun failedStartupCleanupRetainsTheExactContextWithoutAdmittingTrafficOrReuse() {
        StartupFault().use { fault ->
            assertFailsWith<IllegalStateException> { fault.start() }
            fault.assertReached()
            val route = assertNotNull(CamelRuntime.route(fault.id), "retain a handle until actual cleanup")
            assertSame(fault.contexts.single(), route.camelContext)
            assertEquals(true, route.identity()["cleanupPending"])
            assertFalse(CamelRuntime.isRunning(fault.id), "cleanup retention is not a successful start")
            assertTrue(CamelRuntime.running().any { it === route }, "stopAll must still find pending cleanup")
            assertFailsWith<IllegalStateException> { CamelRuntime.send(fault.id, "refused") }
            assertFailsWith<IllegalStateException> { fault.start() }
            assertEquals(1, fault.contexts.size, "refuse a replacement before allocating another context")
            fault.failStop.set(false)
            assertTrue(CamelRuntime.stop(route))
            assertEquals("Stopped", route.status())
            assertNull(CamelRuntime.route(fault.id))
            fault.failStart.set(false)
            val next = fault.start()
            assertFalse(CamelRuntime.stop(route), "a stale cleanup handle must not stop the replacement")
            assertEquals("replacement", CamelRuntime.send(next.id, "replacement"))
        }
    }

    @Test
    fun successfulCleanupOfStartupFailureReleasesTheId() {
        StartupFault().use { fault ->
            fault.failStop.set(false)
            val error = assertFailsWith<IllegalStateException> { fault.start() }
            fault.assertReached()
            assertTrue(error.causes().any { it === fault.startFailure })
            assertNull(CamelRuntime.route(fault.id))
            assertFalse(CamelRuntime.isRunning(fault.id))
            assertEquals("Stopped", fault.contexts.single().javaClass.getMethod("getStatus")
                .invoke(fault.contexts.single()).toString())
            fault.failStart.set(false)
            assertEquals("Started", fault.start().status())
        }
    }

    @Test
    fun registeredOwnerRetainsFailedStartupCleanupAndRefusesSuccessfulReplay() = runBlocking {
        val parent = SupervisorJob(coroutineContext.job)
        val ctx = vmRuntimeTestContext(CoroutineScope(parent + Dispatchers.Default))
        val owner = CamelRouteLegos.register(ctx)
        StartupFault(fullyStarted = true).use { fault ->
            val node = LcncNode(fault.id, CamelRouteLegos.UP,
                mapOf("from" to "direct:${fault.id}", "to" to "mock:${fault.id}"))
            try {
                val out = fault.captureSuspending {
                    ctx.lcncRunners.getValue(CamelRouteLegos.UP).run(node, emptyMap())
                }
                fault.assertReached()
                assertEquals(false, out["ok"])
                val route = assertNotNull(CamelRuntime.route(fault.id))
                val handle = assertNotNull(HostCamelRouteRegistry.route(fault.id))
                assertTrue(owner.command { owner.owns(handle) }, "the accepted startup owns its remaining cleanup")
                val fact = ctx.blackboard.get(CamelRouteLegos.routeKey(fault.id)) as Map<*, *>
                assertEquals(true, fact["cleanupPending"])
                val replay = ctx.lcncRunners.getValue(CamelRouteLegos.UP).run(node, emptyMap())
                assertEquals(false, replay["ok"], "same identity cannot turn failed startup into success")
                assertEquals(1, fault.contexts.size)
                assertFailsWith<IllegalStateException> { owner.drain() }
                assertTrue(owner.supervisor.isActive, "observation dependencies must survive failed cleanup")
                assertSame(route, CamelRuntime.route(fault.id))
                route.bridge.request(route.camelContext, route.from, CamelRuntime.Request("still-live"))
                assertEquals("still-live", (ctx.blackboard.get(CamelRouteLegos.exchangeKey(fault.id)) as Map<*, *>)["body"])
                fault.failStop.set(false)
                owner.drain()
                assertTrue(owner.supervisor.isCompleted)
                assertNull(CamelRuntime.route(fault.id))
                val stopped = ctx.blackboard.get(CamelRouteLegos.routeKey(fault.id)) as Map<*, *>
                assertEquals("Stopped", stopped["status"])
                assertEquals(false, stopped["cleanupPending"])
                assertEquals(1L, stopped["exchanges"])
            } finally {
                fault.failStop.set(false)
                owner.drain()
                parent.cancelAndJoin()
            }
        }
    }

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

    @Test
    fun failedDocumentStartupKeepsItsDependencyOwnerUntilContextCleanup() = runBlocking {
        val parent = SupervisorJob(coroutineContext.job)
        val scope = CoroutineScope(parent + Dispatchers.Default)
        val reactor = MuxReactorElement(coroutineContext.job)
        val bag = BeliefBagElement(parentJob = coroutineContext.job)
        reactor.open()
        bag.open()
        try {
            StartupFault().use { fault ->
                try {
                    val error = assertFailsWith<IllegalStateException> {
                        fault.captureSuspending {
                            DocumentFeed.create(scope, InMemoryVolume(512, 16), CasStore.inMemory(), EmptyLog,
                                bag, PointcutBlackboardAdapter(ConfixBlackboard.empty()),
                                DocumentModel.through(reactor), "unused", routeId = fault.id)
                        }
                    }
                    fault.assertReached()
                    assertTrue(error.causes().any { it === fault.startFailure })
                    val dependencies = assertNotNull(parent.children.singleOrNull(),
                        "failed context cleanup must retain the feed's owner")
                    assertTrue(dependencies.isActive)
                    assertTrue(dependencies.children.any { it.isActive }, "input and curator must remain alive")
                    val retained = assertIs<DocumentFeed.StartFailure>(error).feed
                    assertSame(dependencies, retained.job)
                    assertFailsWith<IllegalStateException> { retained.submit(byteArrayOf(1), "refused.txt") }
                    assertFailsWith<IllegalStateException> { retained.drain() }
                    assertTrue(retained.job.isActive)
                    fault.failStop.set(false)
                    retained.drain()
                    assertTrue(retained.job.isCompleted)
                    assertNull(CamelRuntime.route(fault.id))
                } finally {
                    fault.failStop.set(false)
                    CamelRuntime.stop(fault.id)
                }
            }
        } finally {
            parent.cancelAndJoin()
            try { bag.drain() } finally { reactor.drain() }
        }
    }

    @Test
    fun successfulDocumentStartupCleanupJoinsItsDependencyOwner() = runBlocking {
        val parent = SupervisorJob(coroutineContext.job)
        val scope = CoroutineScope(parent + Dispatchers.Default)
        val reactor = MuxReactorElement(coroutineContext.job)
        val bag = BeliefBagElement(parentJob = coroutineContext.job)
        reactor.open()
        bag.open()
        try {
            StartupFault().use { fault ->
                fault.failStop.set(false)
                val error = assertFailsWith<IllegalStateException> {
                    fault.captureSuspending {
                        DocumentFeed.create(scope, InMemoryVolume(512, 16), CasStore.inMemory(), EmptyLog,
                            bag, PointcutBlackboardAdapter(ConfixBlackboard.empty()),
                            DocumentModel.through(reactor), "unused", routeId = fault.id)
                    }
                }
                fault.assertReached()
                assertTrue(error.causes().any { it === fault.startFailure })
                assertNull(CamelRuntime.route(fault.id))
                assertFalse(parent.children.any(), "successful cleanup must join, not retain, the feed owner")
            }
        } finally {
            parent.cancelAndJoin()
            try { bag.drain() } finally { reactor.drain() }
        }
    }


    @Test
    fun curationFactoryTransfersFailedStartupWithoutRegisteringARunner() = runBlocking {
        val parent = SupervisorJob(coroutineContext.job)
        val scope = CoroutineScope(parent + Dispatchers.Default)
        val reactor = MuxReactorElement(coroutineContext.job)
        val bag = BeliefBagElement(parentJob = coroutineContext.job)
        reactor.open()
        bag.open()
        try {
            StartupFault().use { fault ->
                val runners = linkedMapOf<String, LcncNodeRunner>()
                var feed: DocumentFeed? = null
                try {
                    val error = assertFailsWith<DocumentFeed.StartFailure> {
                        fault.captureSuspending {
                            DocumentCurationLegos.create(scope, InMemoryVolume(512, 16), CasStore.inMemory(),
                                EmptyLog, bag, PointcutBlackboardAdapter(ConfixBlackboard.empty()),
                                DocumentModel.through(reactor), "unused", runners)
                        }
                    }
                    val retained = error.feed
                    feed = retained
                    fault.assertReached()
                    assertTrue(error.causes().any { it === fault.startFailure })
                    assertFalse(DocumentCurationLegos.CURATE in runners)
                    assertTrue(retained.job.isActive)
                    assertEquals(true, assertNotNull(CamelRuntime.route(retained.routeId)).identity()["cleanupPending"])
                    fault.failStop.set(false)
                    retained.drain()
                    assertTrue(retained.job.isCompleted)
                    assertNull(CamelRuntime.route(retained.routeId))
                } finally {
                    fault.failStop.set(false)
                    feed?.drain()
                }
            }
        } finally {
            parent.cancelAndJoin()
            try { bag.drain() } finally { reactor.drain() }
        }
    }

    @Test
    fun curationRegistrationFailureTransfersItsCleanupObligation() = runBlocking {
        val parent = SupervisorJob(coroutineContext.job)
        val scope = CoroutineScope(parent + Dispatchers.Default)
        val reactor = MuxReactorElement(coroutineContext.job)
        val bag = BeliefBagElement(parentJob = coroutineContext.job)
        reactor.open()
        bag.open()
        try {
            StartupFault().use { fault ->
                fault.failStart.set(false)
                val registration = IllegalStateException("runner registration failed")
                val runners = object : MutableMap<String, LcncNodeRunner> by linkedMapOf() {
                    override fun put(key: String, value: LcncNodeRunner): LcncNodeRunner? = throw registration
                }
                var feed: DocumentFeed? = null
                try {
                    val error = assertFailsWith<DocumentFeed.StartFailure> {
                        fault.captureSuspending {
                            DocumentCurationLegos.create(scope, InMemoryVolume(512, 16), CasStore.inMemory(),
                                EmptyLog, bag, PointcutBlackboardAdapter(ConfixBlackboard.empty()),
                                DocumentModel.through(reactor), "unused", runners)
                        }
                    }
                    val retained = error.feed
                    feed = retained
                    fault.assertReached()
                    assertTrue(error.causes().any { it === registration })
                    assertTrue(error.causes().flatMap { it.suppressed.asSequence() }
                        .flatMap { it.causes() }.any { it === fault.stopFailure })
                    assertFalse(DocumentCurationLegos.CURATE in runners)
                    assertTrue(retained.job.isActive)
                    assertNotNull(CamelRuntime.route(retained.routeId))
                    assertFailsWith<IllegalStateException> { retained.submit(byteArrayOf(1), "refused.txt") }
                    fault.failStop.set(false)
                    retained.drain()
                    assertTrue(retained.job.isCompleted)
                    assertNull(CamelRuntime.route(retained.routeId))
                } finally {
                    fault.failStop.set(false)
                    feed?.drain()
                }
            }
        } finally {
            parent.cancelAndJoin()
            try { bag.drain() } finally { reactor.drain() }
        }
    }

    /** Lifecycle-only: no model calls or fabricated curation receipts. */
    private object EmptyLog : DurableAppendLog {
        override fun append(sequence: Long, payload: ByteArray): Long = error("unexpected curation")
        override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long = 0L
        override fun flush() = Unit
        override fun injectCorruptionAfter(sequence: Long) = error("no retained frames")
    }

    private class StartupFault(private val fullyStarted: Boolean = false) : AutoCloseable {
        val id = "startup-cleanup-" + UUID.randomUUID()
        val startFailure = LinkageError("startup hook failed")
        val stopFailure = LinkageError("startup cleanup hook failed")
        val failStart = AtomicBoolean(true)
        val failStop = AtomicBoolean(true)
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val contexts = CopyOnWriteArrayList<Any>()
        private val selected = ThreadLocal<Boolean>()
        private val loader = assertNotNull(GuestModules.loaderFor(CamelRuntime.MODULE))
        private val trackerClass = loader.loadClass("org.apache.camel.spi.CamelContextTracker")
        private val filterClass = loader.loadClass("org.apache.camel.spi.CamelContextTracker\$Filter")
        private val startupClass = loader.loadClass("org.apache.camel.StartupListener")
        private val strategyClass = loader.loadClass("org.apache.camel.spi.LifecycleStrategy")
        private val startup = proxy(startupClass, "startup") { name, _ ->
            if (name == (if (fullyStarted) "onCamelContextFullyStarted" else "onCamelContextStarted")) {
                starts.incrementAndGet()
                if (failStart.get()) throw startFailure
            }
            null
        }
        private val strategy = proxy(strategyClass, "stop") { name, _ ->
            if (name == "onContextStopping") {
                stops.incrementAndGet()
                if (failStop.get()) throw stopFailure
            }
            null
        }
        private val filter = proxy(filterClass, "filter") { name, args ->
            if (name == "accept" || name == "test") {
                if (selected.get() == true) args?.firstOrNull()?.let { context ->
                    if (contexts.none { it === context }) {
                        contexts += context
                        // Tracker holds a global lock: attach hooks here, never wait inside it.
                        context.javaClass.getMethod("addStartupListener", startupClass).invoke(context, startup)
                        context.javaClass.getMethod("addLifecycleStrategy", strategyClass).invoke(context, strategy)
                    }
                }
                false
            } else null
        }
        private val tracker = trackerClass.getConstructor(filterClass).newInstance(filter)

        init { trackerClass.getMethod("open").invoke(tracker) }

        private fun proxy(type: Class<*>, label: String, action: (String, Array<out Any?>?) -> Any?): Any =
            Proxy.newProxyInstance(loader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "CamelRuntimeStartupCleanupTest.$label"
                    else -> action(method.name, args)
                }
            }

        fun start(): CamelRuntime.Route {
            val previous = selected.get()
            selected.set(true)
            return try { CamelRuntime.start(id, "direct:$id", "mock:$id") }
            finally { selected.set(previous) }
        }

        suspend fun <T> captureSuspending(action: suspend () -> T): T =
            withContext(selected.asContextElement(true)) { action() }

        fun assertReached() {
            assertTrue(starts.get() > 0, "fixture must reach the selected installed Camel startup callback")
            assertTrue(stops.get() > 0, "fixture must exercise actual cleanup")
        }

        override fun close() {
            failStart.set(false)
            failStop.set(false)
            trackerClass.getMethod("close").invoke(tracker)
            try {
                for (route in CamelRuntime.running().filter { route -> contexts.any { it === route.camelContext } })
                    CamelRuntime.stop(route)
            } finally {
                // Independent handles clean up even when the failing baseline forgot its map entry.
                val thread = Thread.currentThread()
                val previous = thread.contextClassLoader
                thread.contextClassLoader = loader
                try { for (context in contexts) context.javaClass.getMethod("stop").invoke(context) }
                finally { thread.contextClassLoader = previous }
            }
        }
    }
}
