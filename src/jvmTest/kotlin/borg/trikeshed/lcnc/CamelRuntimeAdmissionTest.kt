package borg.trikeshed.lcnc

import borg.trikeshed.graal.subvm.CamelRuntime
import borg.trikeshed.graal.subvm.GuestModules
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CamelRuntimeAdmissionTest {
    @Test
    fun duplicateStartIsRefusedBeforeASecondContextWhileOtherIdsCanStart() {
        assertTrue(GuestModules.isInstalled(CamelRuntime.MODULE))
        val loader = assertNotNull(GuestModules.loaderFor(CamelRuntime.MODULE))
        val id = "admission-" + UUID.randomUUID()
        val otherId = id + "-other"
        val sequence = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2) { action ->
            Thread(action, "$id-worker-" + sequence.incrementAndGet())
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstThread = AtomicReference<Thread>()
        val held = AtomicBoolean(false)
        val contexts = CopyOnWriteArrayList<Any>()
        val trackerClass = loader.loadClass("org.apache.camel.spi.CamelContextTracker")
        val filterClass = loader.loadClass("org.apache.camel.spi.CamelContextTracker\$Filter")
        val startupClass = loader.loadClass("org.apache.camel.StartupListener")
        val startup = Proxy.newProxyInstance(loader, arrayOf(startupClass)) { proxy, method, args ->
            when (method.name) {
                "onCamelContextStarting" -> {
                    entered.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "First context was not released" }
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "CamelRuntimeAdmissionTest.StartupListener"
                else -> null
            }
        }
        val filter = Proxy.newProxyInstance(loader, arrayOf(filterClass)) { proxy, method, args ->
            when (method.name) {
                "accept", "test" -> {
                    if (Thread.currentThread().name.startsWith("$id-worker-")) {
                        args?.firstOrNull()?.let { context ->
                            if (contexts.none { it === context }) contexts += context
                            if (Thread.currentThread() === firstThread.get() && held.compareAndSet(false, true)) {
                                // Tracker callbacks hold Camel's global lock. Hold startup instead,
                                // so this fixture does not serialize unrelated context construction.
                                context.javaClass.getMethod("addStartupListener", startupClass).invoke(context, startup)
                            }
                        }
                    }
                    false
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "CamelRuntimeAdmissionTest.Filter"
                else -> null
            }
        }
        val tracker = trackerClass.getConstructor(filterClass).newInstance(filter)
        var first: Future<CamelRuntime.Route>? = null
        trackerClass.getMethod("open").invoke(tracker)
        try {
            val construction = executor.submit<CamelRuntime.Route> {
                firstThread.set(Thread.currentThread())
                CamelRuntime.start(id, "direct:$id", "mock:$id")
            }
            first = construction
            assertTrue(entered.await(5, TimeUnit.SECONDS), "The first context must be held before publication")
            assertFalse(CamelRuntime.isRunning(id))
            val duplicate = executor.submit<Throwable?> {
                runCatching { CamelRuntime.start(id, "direct:$id", "mock:$id") }.exceptionOrNull()
            }.get(5, TimeUnit.SECONDS)
            assertTrue(duplicate is IllegalStateException, "Concurrent duplicate must be refused, got $duplicate")
            assertEquals(1, contexts.size, "The refusal must precede a second context allocation")

            val other = executor.submit<CamelRuntime.Route> {
                CamelRuntime.start(otherId, "direct:$otherId", "mock:$otherId")
            }.get(5, TimeUnit.SECONDS)
            assertSame(other, CamelRuntime.route(otherId), "Reservation of one ID must not block another")
            release.countDown()
            val route = construction.get(5, TimeUnit.SECONDS)
            assertSame(route, CamelRuntime.route(id), "The accepted construction must retain its own handle")
            assertEquals("retained", CamelRuntime.send(id, "retained"))
            assertEquals("independent", CamelRuntime.send(otherId, "independent"))
            assertEquals(2, contexts.size)
        } finally {
            release.countDown()
            runCatching { first?.get(10, TimeUnit.SECONDS) }
            executor.shutdown()
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow()
                check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Test construction threads did not join" }
            }
            trackerClass.getMethod("close").invoke(tracker)
            // Keep every test-created context reachable for cleanup even on the buggy baseline.
            for (routeId in listOf(id, otherId)) runCatching { CamelRuntime.stop(routeId) }
            for (context in contexts) runCatching { context.javaClass.getMethod("stop").invoke(context) }
        }
    }

    @Test
    fun failedStartupReleasesTheIdForLaterConstruction() {
        assertTrue(GuestModules.isInstalled(CamelRuntime.MODULE))
        val id = "admission-retry-" + UUID.randomUUID()
        assertFailsWith<IllegalStateException> {
            CamelRuntime.start(id, "timer:$id?period=not-a-number", "mock:$id")
        }
        assertFalse(CamelRuntime.isRunning(id))
        val route = CamelRuntime.start(id, "direct:$id", "mock:$id")
        try {
            assertSame(route, CamelRuntime.route(id))
            assertEquals("after-failure", CamelRuntime.send(id, "after-failure"))
        } finally {
            CamelRuntime.stop(id)
        }
    }
}
