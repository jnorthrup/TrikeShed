package borg.trikeshed.lcnc

import borg.trikeshed.couch.*
import borg.trikeshed.dag.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.lib.*
import borg.trikeshed.module.*
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.FileCasStore
import kotlinx.coroutines.*
import java.nio.file.Path
import kotlin.test.*

class CamelDispatchOwnershipTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path

    @Test fun oneShotDispatchDrainsAfterItsWaiterCancels(): Unit = runBlocking {
        val files = JvmFileOperations()
        val cas = FileCasStore(files, directory.resolve("cas").toString())
        val root = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(Dispatchers.IO + root + files)
        val store = CouchStoreFactory.casBacked(cas)
        val ctx = ModuleContext(Couch("camel-dispatch", store, cas), ReteNetwork(), ReteProductionRegistry(),
            null, null, ConfixBlackboard.empty(), cas, CouchAttachmentGateway(store, cas),
            ModuleRouteRegistry(), scope, { 123L }, directory.toFile())
        val entered = CompletableDeferred<CamelRouteElement?>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        ctx.lcncRunners[SubVmLegos.CAMEL] = LcncNodeRunner { _, _ ->
            entered.complete(currentCoroutineContext()[CamelRouteElement])
            release.await()
            finished.complete(Unit)
            _m["routed" j "review", "ok" j true]
        }
        val owner = CamelRouteLegos.register(ctx)
        try {
            val runner = ctx.lcncRunners.getValue(SubVmLegos.CAMEL)
            val node = LcncNode("dispatch", SubVmLegos.CAMEL)
            val waiter = async { runner.run(node, _m.get()) }
            assertSame(owner, withTimeout(5000) { entered.await() })
            waiter.cancelAndJoin()
            val drain = async { owner.drain() }
            yield()
            assertFalse(drain.isCompleted)
            assertFalse(finished.isCompleted)
            release.complete(Unit)
            withTimeout(5000) { drain.await() }
            assertTrue(finished.isCompleted)
            assertTrue(owner.closed.isCompleted)
            assertFailsWith<IllegalStateException> { runner.run(node, _m.get()) }
        } finally {
            release.complete(Unit)
            withContext(NonCancellable) { owner.drain(); root.cancelAndJoin() }
        }
    }
}
