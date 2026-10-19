package borg.trikeshed.lcnc

import borg.trikeshed.graal.subvm.CamelRuntime
import kotlinx.coroutines.*
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class CamelRouteOwnerBindingTest {
    @Test
    fun realConsumerStopFailureKeepsItsPublicationLaneLiveForRetry() = runBlocking {
        val parent = SupervisorJob(coroutineContext.job)
        val ctx = vmRuntimeTestContext(CoroutineScope(parent + Dispatchers.Default))
        val owner = CamelRouteLegos.register(ctx)
        val id = "owner-retry-" + UUID.randomUUID()
        val fail = AtomicBoolean(true)
        var detach: () -> Unit = {}
        try {
            val out = ctx.lcncRunners.getValue(CamelRouteLegos.UP).run(
                LcncNode(id, CamelRouteLegos.UP, mapOf("from" to "direct:$id", "to" to "mock:$id")), emptyMap())
            assertEquals(true, out["ok"])
            val route = assertNotNull(CamelRuntime.route(id))
            val lifecycle = route.bridge.loader.loadClass("org.apache.camel.spi.LifecycleStrategy")
            val hook = Proxy.newProxyInstance(route.bridge.loader, arrayOf(lifecycle)) { proxy, method, args ->
                when (method.name) {
                    "onContextStopping" -> {
                        if (fail.get()) throw LinkageError("owned stop refused")
                        route.bridge.request(route.camelContext, route.from, CamelRuntime.Request("last"))
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "CamelRouteOwnerBindingTest.LifecycleStrategy"
                    else -> null
                }
            }
            route.camelContext.javaClass.getMethod("addLifecycleStrategy", lifecycle).invoke(route.camelContext, hook)
            detach = {
                @Suppress("UNCHECKED_CAST")
                val strategies = route.camelContext.javaClass.getMethod("getLifecycleStrategies")
                    .invoke(route.camelContext) as MutableList<Any>
                strategies.remove(hook)
                Unit
            }
            assertFailsWith<IllegalStateException> { owner.drain() }
            assertSame(route, CamelRuntime.route(id))
            assertTrue(owner.supervisor.isActive)
            assertEquals("still-live", CamelRuntime.send(id, "still-live"))
            assertEquals("still-live", (ctx.blackboard.get(CamelRouteLegos.exchangeKey(id)) as Map<*, *>)["body"])
            fail.set(false)
            owner.drain()
            assertNull(CamelRuntime.route(id))
            assertEquals("last", (ctx.blackboard.get(CamelRouteLegos.exchangeKey(id)) as Map<*, *>)["body"])
            val fact = ctx.blackboard.get(CamelRouteLegos.routeKey(id)) as Map<*, *>
            assertEquals("Stopped", fact["status"])
            assertEquals(2L, fact["exchanges"])
            assertTrue(owner.supervisor.isCompleted)
        } finally {
            fail.set(false)
            detach()
            owner.drain()
            CamelRuntime.stop(id)
            parent.cancelAndJoin()
        }
    }

    @Test
    fun retainedBackendHandleCannotStopAReplacementContext() {
        val id = "owner-replaced-" + UUID.randomUUID()
        val first = CamelRuntime.start(id, "direct:$id", "mock:$id")
        assertTrue(CamelRuntime.stop(first))
        val next = CamelRuntime.start(id, "direct:$id", "mock:$id")
        try {
            assertFalse(CamelRuntime.stop(first))
            assertSame(next, CamelRuntime.route(id))
            assertEquals("replacement", CamelRuntime.send(id, "replacement"))
        } finally { CamelRuntime.stop(next) }
    }

    /** The registered host boundary, including blocking provider callbacks and caller binding. */
    private class Registry : CamelRouteRegistry {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val handles = ConcurrentHashMap<String, Handle>()
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        override fun route(id: String) = handles[id]
        override fun running(): List<CamelRouteHandle> = handles.values.toList()
        override fun start(id: String, from: String, to: String, module: String,
                           reach: CamelLinkage.Reach, observer: CamelRuntime.Observer): CamelRouteHandle {
            starts.incrementAndGet()
            entered.countDown()
            check(release.await(15, TimeUnit.SECONDS)) { "startup fixture was not released" }
            return Handle(id, from, to, module, reach, observer).also {
                check(handles.putIfAbsent(id, it) == null)
                it.exchange("during-start")
            }
        }
        override fun stop(id: String): Boolean {
            val route = handles[id] ?: return false
            route.exchange("during-stop")
            stops.incrementAndGet()
            return handles.remove(id, route)
        }
        class Handle(override val id: String, val from: String, val to: String, val module: String,
                     val reach: CamelLinkage.Reach, val observer: CamelRuntime.Observer) : CamelRouteHandle {
            var seq = 0L
            fun exchange(body: String) { observer.onExchange(CamelRuntime.Exchange(id, ++seq, body, false, seq)) }
            override fun status() = "Started"
            override fun routeIds() = listOf(id)
            override fun identity(): Map<String, Any?> = mapOf("id" to id, "from" to from, "to" to to,
                "module" to module, "reach" to reach.name, "status" to status())
            override fun describe() = identity() + ("exchanges" to seq)
        }
    }

    @Test
    fun callerCancellationStillDrainsTheAcceptedStartAndItsLastExchange() = runBlocking {
        val parent = SupervisorJob(coroutineContext.job)
        val ctx = vmRuntimeTestContext(CoroutineScope(parent + Dispatchers.Default))
        val owner = CamelRouteLegos.register(ctx)
        val registry = Registry()
        val start = launch(Dispatchers.Default + CamelRouteRegistryKey(registry)) {
            ctx.lcncRunners.getValue(CamelRouteLegos.UP).run(
                LcncNode("owned", CamelRouteLegos.UP, mapOf("from" to "direct:owned", "to" to "mock:owned")), emptyMap())
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { registry.entered.await(5, TimeUnit.SECONDS) })
            start.cancelAndJoin()
            val drain = async(start = CoroutineStart.UNDISPATCHED) { owner.drain() }
            assertFalse(drain.isCompleted, "the caller cannot cancel the accepted library startup")
            registry.release.countDown()
            withTimeout(10_000) { drain.await() }
            assertEquals(1, registry.starts.get(), "the caller's registry override must survive channel dispatch")
            assertEquals(1, registry.stops.get())
            assertTrue(registry.handles.isEmpty())
            val last = ctx.blackboard.get(CamelRouteLegos.exchangeKey("owned")) as Map<*, *>
            assertEquals("during-stop", last["body"])
            assertEquals(2L, last["seq"])
            val stopped = ctx.blackboard.get(CamelRouteLegos.routeKey("owned")) as Map<*, *>
            assertEquals("Stopped", stopped["status"])
            assertEquals(2L, stopped["exchanges"])
            assertTrue(owner.supervisor.isCompleted)
        } finally {
            registry.release.countDown()
            start.cancelAndJoin()
            owner.drain()
            parent.cancelAndJoin()
        }
    }

    @Test
    fun simultaneousExactReplaysShareOneOwnedConstruction() = runBlocking {
        val parent = SupervisorJob(coroutineContext.job)
        val ctx = vmRuntimeTestContext(CoroutineScope(parent + Dispatchers.Default))
        val owner = CamelRouteLegos.register(ctx)
        val registry = Registry()
        try {
            val first = async(Dispatchers.Default + CamelRouteRegistryKey(registry)) {
                ctx.lcncRunners.getValue(CamelRouteLegos.UP).run(
                    LcncNode("same", CamelRouteLegos.UP, mapOf("from" to "direct:same", "to" to "mock:same")), emptyMap())
            }
            assertTrue(withContext(Dispatchers.IO) { registry.entered.await(5, TimeUnit.SECONDS) })
            val second = async(Dispatchers.Default + CamelRouteRegistryKey(registry)) {
                ctx.lcncRunners.getValue(CamelRouteLegos.UP).run(
                    LcncNode("same", CamelRouteLegos.UP, mapOf("from" to "direct:same", "to" to "mock:same")), emptyMap())
            }
            registry.release.countDown()
            assertEquals(true, first.await()["ok"])
            assertEquals(true, second.await()["ok"])
            assertEquals(1, registry.starts.get())
            owner.drain()
            assertEquals(1, registry.stops.get())
        } finally { registry.release.countDown(); owner.drain(); parent.cancelAndJoin() }
    }
}
