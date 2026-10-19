package borg.trikeshed.lcnc

import borg.trikeshed.couch.*
import borg.trikeshed.dag.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.*
import borg.trikeshed.module.*
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class CouncilCaseContextTest {
    private fun context(scope: CoroutineScope): ModuleContext {
        val cas = CasStore.inMemory()
        val store = CouchStoreFactory.casBacked(cas)
        return ModuleContext(Couch("case-owner", store, cas), ReteNetwork(), ReteProductionRegistry(),
            null, null, ConfixBlackboard.empty(), cas, CouchAttachmentGateway(store, cas),
            ModuleRouteRegistry(), scope, { 0L }, File(System.getProperty("java.io.tmpdir"), "case-owner"))
    }

    @Test fun stoppingAnUnusedContextNeverInitializesItsCaseOwner() = runBlocking<Unit> {
        // Constructing a registry would fail without a parent Job: drain must preserve laziness.
        val ctx = context(object : CoroutineScope { override val coroutineContext = kotlin.coroutines.EmptyCoroutineContext })
        ctx.drainCouncilCases()
        assertContains(assertFailsWith<IllegalStateException> { ctx.councilCases }.message.orEmpty(), "stopped")
        ctx.drainCouncilCases()
    }

    @Test fun concurrentFirstLookupAndStopCannotPublishAnAcceptingOwnerAfterStop() = runBlocking<Unit> {
        val parent = SupervisorJob(coroutineContext[Job])
        val ctx = context(CoroutineScope(coroutineContext + parent))
        val start = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val lookup = threads.submit<CouncilCaseRegistry?> {
                start.await()
                try { ctx.councilCases } catch (_: IllegalStateException) { null }
            }
            val stop = threads.submit<Unit> { start.await(); runBlocking { ctx.drainCouncilCases() } }
            start.countDown()
            stop.get(5, TimeUnit.SECONDS)
            val published = lookup.get(5, TimeUnit.SECONDS)
            assertFailsWith<IllegalStateException> { ctx.councilCases }
            if (published != null) assertFailsWith<IllegalStateException> {
                published.withCase("late", create = true) { error("context published a live owner after stop") }
            }
            assertTrue(parent.children.none())
        } finally {
            ctx.drainCouncilCases()
            threads.shutdownNow()
            parent.complete(); parent.join()
        }
    }

    @Test fun borrowersShareOneOwnerAndRootStopAwaitsItsActualChild() = runBlocking<Unit> {
        val parent = SupervisorJob(coroutineContext[Job])
        val ctx = context(CoroutineScope(coroutineContext + parent))
        try {
            val first = ctx.councilCases
            assertSame(first, ctx.councilCases)
            first.withCase("owned", create = true) { it.seed("seed", "start") }
            val child = parent.children.single().children.single()
            assertTrue(child.isActive)
            ctx.drainCouncilCases()
            assertTrue(child.isCompleted)
            assertTrue(parent.children.none())
            assertFailsWith<IllegalStateException> { ctx.councilCases }
        } finally { ctx.drainCouncilCases(); parent.complete(); parent.join() }
    }
}
