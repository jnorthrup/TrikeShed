package borg.trikeshed.lcnc

import borg.trikeshed.couch.Couch
import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProductionRegistry
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.subvm.CamelRuntime
import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.view
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.module.ModuleRouteRegistry
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.vm.Teleported
import borg.trikeshed.vm.VmHandle
import borg.trikeshed.vm.VmHost
import borg.trikeshed.vm.VmSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal fun vmRuntimeTestContext(scope: kotlinx.coroutines.CoroutineScope): ModuleContext {
    val cas = CasStore.inMemory()
    val store = CouchStoreFactory.casBacked(cas)
    return ModuleContext(
        couch = Couch("vm-runtime-test", store, cas), rete = ReteNetwork(), productions = ReteProductionRegistry(),
        beliefBag = null, turnReview = null, blackboard = ConfixBlackboard.empty(), casStore = cas,
        attachments = CouchAttachmentGateway(store, cas), routes = ModuleRouteRegistry(), scope = scope,
        clock = { 123L }, stateDir = File(System.getProperty("java.io.tmpdir"), "vm-runtime-test"),
    )
}

class VmRuntimeBindingTest {
    private class Host : VmHost {
        val specs = mutableListOf<VmSpec>()
        val handles = linkedMapOf<String, Handle>()
        val revoked = mutableListOf<String>()
        var error: Throwable? = null
        var revokeError: Throwable? = null
        var closed = false
        override fun get(id: String): VmHandle? = handles[id]
        override fun spawn(spec: VmSpec): VmHandle {
            specs += spec
            return Handle(spec).also { handles[spec.id] = it }
        }
        override fun revoke(id: String, reason: String) {
            revoked += id
            handles[id]?.alive = false
            revokeError?.let { throw it }
        }
        override fun close() { closed = true }

        inner class Handle(val spec: VmSpec) : VmHandle {
            override val id: String get() = spec.id
            override val facet: VmFacet get() = spec.facet
            var alive = true
            override val isAlive: Boolean get() = alive
            val sources = mutableListOf<String>()
            override fun eval(source: String, name: String): Teleported {
                sources += source
                error?.let { throw it }
                return Teleported.Str("guest output")
            }
        }
    }

    private class Routes : CamelRouteRegistry, AutoCloseable {
        val handles = linkedMapOf<String, Handle>()
        var starts = 0
        var stops = 0
        var closed = false
        override fun route(id: String): CamelRouteHandle? = handles[id]
        override fun running(): List<CamelRouteHandle> = handles.values.toList()
        override fun start(id: String, from: String, to: String, module: String, reach: CamelLinkage.Reach, observer: CamelRuntime.Observer): CamelRouteHandle {
            starts++
            return Handle(id, from, to, module, observer).also { handles[id] = it }
        }
        override fun stop(id: String): Boolean { stops++; return handles.remove(id) != null }
        override fun close() { closed = true; handles.clear() }

        class Handle(override val id: String, val from: String, val to: String, val module: String, val observer: CamelRuntime.Observer) : CamelRouteHandle {
            override fun status() = "Started"
            override fun routeIds() = listOf(id)
            override fun identity(): Map<String, Any?> = mapOf("id" to id, "from" to from, "to" to to, "module" to module, "status" to status())
            override fun describe() = identity() + ("exchanges" to 1L)
        }
    }

    @Test
    fun subVmUsesOverrideHostAndReleasesOnlyItsOwnTemporaryGuests() = runTest {
        val default = Host()
        val host = Host()
        val runner = SubVmLegos.graalce(default)
        assertTrue((runner as LcncServiceBinding).requiredKeys.view.any { it === VmHostKey })
        withContext(VmHostKey(host)) {
            val node = LcncNode("guest", SubVmLegos.GRAALCE, mapOf("source" to "ignored"))
            val first = runner.run(node, mapOf("source" to "6*7", "world" to listOf("/one", "/two")))
            val second = runner.run(node, mapOf("source" to "7*9"))
            assertEquals("guest output", first["text"])
            assertEquals(listOf("/one", "/two"), host.specs.first().world)
            assertTrue(first["vmId"] != second["vmId"], "temporary runs must not collide on node id")
            assertEquals(host.specs.map { it.id }, host.revoked)
            assertEquals("6*7", host.handles.getValue(first["vmId"] as String).sources.single())
        }
        assertTrue(default.specs.isEmpty())
        assertFalse(host.closed)
        assertFalse(currentCoroutineContext().job.children.any())
    }

    @Test
    fun retainedAndBorrowedGuestsSurviveInvocationCompletion() = runTest {
        val host = Host()
        val runner = SubVmLegos.graalce(host)
        val retained = runner.run(LcncNode("retained", SubVmLegos.GRAALCE,
            mapOf("keep" to "true", "source" to "first")), emptyMap())
        val id = retained["vmId"] as String
        runner.run(LcncNode("borrow", SubVmLegos.GRAALCE, mapOf("source" to "second")), mapOf("vmId" to id))
        assertEquals(1, host.specs.size)
        assertTrue(host.handles.getValue(id).isAlive)
        assertEquals(listOf("first", "second"), host.handles.getValue(id).sources)
        assertTrue(host.revoked.isEmpty())
        assertFalse(host.closed)
    }

    @Test
    fun failedCreationIsReleasedAndCleanupDoesNotMaskCancellation() = runTest {
        val host = Host()
        host.error = CancellationException("guest cancelled")
        host.revokeError = IllegalStateException("revoke failed")
        val error = assertFailsWith<CancellationException> {
            SubVmLegos.graalce(host).run(LcncNode("guest", SubVmLegos.GRAALCE,
                mapOf("keep" to "true", "source" to "cancel")), emptyMap())
        }
        assertEquals("guest cancelled", error.message)
        assertEquals(1, host.revoked.size)
        assertTrue(error.suppressedExceptions.any { it.message == "revoke failed" })
        assertFalse(host.closed)
    }

    @Test
    fun camelOverrideOwnsPersistentRoutesAndReceivesEveryOperation() = runTest {
        val ctx = vmRuntimeTestContext(this)
        val default = Routes()
        val registry = Routes()
        val up = CamelRouteLegos.up(ctx, default)
        val down = CamelRouteLegos.down(ctx, default)
        val list = CamelRouteLegos.routes(default)
        assertTrue((up as LcncServiceBinding).requiredKeys.view.any { it === CamelRouteRegistryKey })
        withContext(CamelRouteRegistryKey(registry)) {
            val node = LcncNode("route", CamelRouteLegos.UP,
                mapOf("id" to "persist", "from" to "direct:in", "to" to "log:out"))
            assertEquals(true, up.run(node, emptyMap())["ok"])
            assertEquals(true, up.run(node, emptyMap())["ok"])
            assertEquals(1, registry.starts)
            assertEquals(listOf("persist"), list.run(LcncNode("routes", CamelRouteLegos.ROUTES), emptyMap())["ids"])
        }
        assertFalse(registry.closed)
        assertFalse(currentCoroutineContext().job.children.any())
        val route = assertNotNull(registry.handles["persist"])
        route.observer.onExchange(CamelRuntime.Exchange("persist", 1, "arrived after run", false, 42))
        assertEquals("arrived after run", (ctx.blackboard.get(CamelRouteLegos.exchangeKey("persist")) as Map<*, *>)["body"])
        withContext(CamelRouteRegistryKey(registry)) {
            val stopped = down.run(LcncNode("down", CamelRouteLegos.DOWN), mapOf("id" to "persist"))
            assertEquals(listOf("persist"), stopped["stopped"])
        }
        assertEquals(1, registry.stops)
        assertEquals(123L, (ctx.blackboard.get(CamelRouteLegos.routeKey("persist")) as Map<*, *>)["stoppedAtMs"])
        assertEquals(0, default.starts)
        assertEquals(0, default.stops)
        assertFalse(registry.closed)
    }

    @Test
    fun camelChecksReachAndConflictingRouteConstruction() = runTest {
        val ctx = vmRuntimeTestContext(this)
        val registry = Routes()
        val runner = CamelRouteLegos.up(ctx, registry)
        assertFailsWith<IllegalArgumentException> {
            runner.run(LcncNode("bad", CamelRouteLegos.UP, mapOf("reach" to "typo")), emptyMap())
        }
        assertEquals(0, registry.starts)
        runner.run(LcncNode("existing", CamelRouteLegos.UP, mapOf("from" to "direct:a")), emptyMap())
        assertFailsWith<IllegalArgumentException> {
            runner.run(LcncNode("existing", CamelRouteLegos.UP, mapOf("from" to "direct:b")), emptyMap())
        }
        assertEquals(1, registry.starts)
    }
}
