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
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
            val waiter = async { runner.run(node, emptyMap()) }
||||||| 0b5584266
            val waiter = async { runner.run(node, _m[]) }
=======
            val waiter = async { runner.run(node, _m.get()) }
>>>>>>> cede029abc5b92a0f5d50776921dbc2763dc356a
||||||| 74d00f3c1
            val waiter = async { runner.run(node, _m[]) }
=======
            val waiter = async { runner.run(node, _m.get()) }
>>>>>>> 0196eac3e7f3a9ac6898b634335b162a882cd0d6
||||||| 8f5e4ee8f
            val waiter = async { runner.run(node, _m[]) }
=======
            val waiter = async { runner.run(node, _m.get()) }
>>>>>>> 8ddca0dc56cefb051af7f1a112a62b7b5c4a665c
||||||| 71d82262f
            val waiter = async { runner.run(node, _m[]) }
=======
            val waiter = async { runner.run(node, _m.get()) }
>>>>>>> 506ec98ee2f19da7bc227b9747f4ec2751036944
||||||| 71d82262f
            val waiter = async { runner.run(node, _m[]) }
=======
            val waiter = async { runner.run(node, _m.get()) }
>>>>>>> 776fce9e87b8951c3e7f493a87a53503a2209740
||||||| cc0b7feaa
            val waiter = async { runner.run(node, _m[]) }
=======
            val waiter = async { runner.run(node, _m.get()) }
>>>>>>> a89c1ce684dbe650d3b50acd09e9a090a8825171
||||||| 678c7a818
            val waiter = async { runner.run(node, _m[]) }
=======
            val waiter = async { runner.run(node, _m.get()) }
>>>>>>> 41d83038940c6ea5b6f0f510d263a9bfba68ad94
||||||| 678c7a818
            val waiter = async { runner.run(node, _m[]) }
=======
            val waiter = async { runner.run(node, _m.get()) }
>>>>>>> d771ffd01da34e146b9e947cdae575db0d64781e
||||||| cc0b7feaa
            val waiter = async { runner.run(node, _m[]) }
=======
            val waiter = async { runner.run(node, _m.get()) }
>>>>>>> 11d3e572794981ddee9e2d2ae93a1cf3a49d02a4
||||||| a39e91f9e
            val waiter = async { runner.run(node, _m[]) }
=======
            val waiter = async { runner.run(node, _m.get()) }
>>>>>>> d7216e115738f2b8df4295c93c32fbb15c1be3e0
||||||| a39e91f9e
            val waiter = async { runner.run(node, _m[]) }
=======
            val waiter = async { runner.run(node, _m.get()) }
>>>>>>> e4affdace595940b8e99b54ecb6c37e898cf4015
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
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
            assertFailsWith<IllegalStateException> { runner.run(node, emptyMap()) }
||||||| 0b5584266
            assertFailsWith<IllegalStateException> { runner.run(node, _m[]) }
=======
            assertFailsWith<IllegalStateException> { runner.run(node, _m.get()) }
>>>>>>> cede029abc5b92a0f5d50776921dbc2763dc356a
||||||| 74d00f3c1
            assertFailsWith<IllegalStateException> { runner.run(node, _m[]) }
=======
            assertFailsWith<IllegalStateException> { runner.run(node, _m.get()) }
>>>>>>> 0196eac3e7f3a9ac6898b634335b162a882cd0d6
||||||| 8f5e4ee8f
            assertFailsWith<IllegalStateException> { runner.run(node, _m[]) }
=======
            assertFailsWith<IllegalStateException> { runner.run(node, _m.get()) }
>>>>>>> 8ddca0dc56cefb051af7f1a112a62b7b5c4a665c
||||||| 71d82262f
            assertFailsWith<IllegalStateException> { runner.run(node, _m[]) }
=======
            assertFailsWith<IllegalStateException> { runner.run(node, _m.get()) }
>>>>>>> 506ec98ee2f19da7bc227b9747f4ec2751036944
||||||| 71d82262f
            assertFailsWith<IllegalStateException> { runner.run(node, _m[]) }
=======
            assertFailsWith<IllegalStateException> { runner.run(node, _m.get()) }
>>>>>>> 776fce9e87b8951c3e7f493a87a53503a2209740
||||||| cc0b7feaa
            assertFailsWith<IllegalStateException> { runner.run(node, _m[]) }
=======
            assertFailsWith<IllegalStateException> { runner.run(node, _m.get()) }
>>>>>>> a89c1ce684dbe650d3b50acd09e9a090a8825171
||||||| 678c7a818
            assertFailsWith<IllegalStateException> { runner.run(node, _m[]) }
=======
            assertFailsWith<IllegalStateException> { runner.run(node, _m.get()) }
>>>>>>> 41d83038940c6ea5b6f0f510d263a9bfba68ad94
||||||| 678c7a818
            assertFailsWith<IllegalStateException> { runner.run(node, _m[]) }
=======
            assertFailsWith<IllegalStateException> { runner.run(node, _m.get()) }
>>>>>>> d771ffd01da34e146b9e947cdae575db0d64781e
||||||| cc0b7feaa
            assertFailsWith<IllegalStateException> { runner.run(node, _m[]) }
=======
            assertFailsWith<IllegalStateException> { runner.run(node, _m.get()) }
>>>>>>> 11d3e572794981ddee9e2d2ae93a1cf3a49d02a4
||||||| a39e91f9e
            assertFailsWith<IllegalStateException> { runner.run(node, _m[]) }
=======
            assertFailsWith<IllegalStateException> { runner.run(node, _m.get()) }
>>>>>>> d7216e115738f2b8df4295c93c32fbb15c1be3e0
||||||| a39e91f9e
            assertFailsWith<IllegalStateException> { runner.run(node, _m[]) }
=======
            assertFailsWith<IllegalStateException> { runner.run(node, _m.get()) }
>>>>>>> e4affdace595940b8e99b54ecb6c37e898cf4015
        } finally {
            release.complete(Unit)
            withContext(NonCancellable) { owner.drain(); root.cancelAndJoin() }
        }
    }
}
