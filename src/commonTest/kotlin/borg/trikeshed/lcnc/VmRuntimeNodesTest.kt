package borg.trikeshed.lcnc

import borg.trikeshed.lib.view
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.vm.Teleported
import borg.trikeshed.vm.VmBudget
import borg.trikeshed.vm.VmCapabilityReport
import borg.trikeshed.vm.VmHandle
import borg.trikeshed.vm.VmHost
import borg.trikeshed.vm.VmSpec
import borg.trikeshed.vm.VmStats
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VmRuntimeNodesTest {
    private class Host : VmHost {
        val specs = mutableListOf<VmSpec>()
        val handles = linkedMapOf<String, Handle>()
        val revoked = mutableListOf<Pair<String, String>>()
        var closed = false
        var result: Teleported = Teleported.obj("answer" to 42L)
        var failure: Throwable? = null
        override fun spawn(spec: VmSpec): VmHandle {
            specs += spec
            return Handle(spec).also { handles[spec.id] = it }
        }
        override fun get(id: String): VmHandle? = handles[id]
        override fun ids(): List<String> = handles.keys.toList()
        override fun revoke(id: String, reason: String) { revoked += id to reason; handles.getValue(id).alive = false }
        override fun close() { closed = true }

        inner class Handle(val spec: VmSpec) : VmHandle {
            override val id: String get() = spec.id
            override val facet: VmFacet get() = spec.facet
            var alive = true
            override val isAlive: Boolean get() = alive
            val evals = mutableListOf<Pair<String, String>>()
            val calls = mutableListOf<Pair<String, List<Teleported>>>()
            override fun eval(source: String, name: String): Teleported {
                evals += source to name
                failure?.let { throw it }
                return result
            }
            override fun call(root: String, vararg args: Teleported): Teleported {
                calls += root to args.toList()
                return result
            }
            override fun stats() = VmStats(evals = evals.size.toLong(), calls = calls.size.toLong())
        }
    }

    @Test
    fun allHandleOperationsResolveTheOverrideAndLeaveTheHostOwned() = runTest {
        val default = Host()
        val host = Host()
        val runners = VmRuntimeNodes.runners(default)
        withContext(VmHostKey(host)) {
            val spawned = runners.getValue(VmRuntimeNodes.SPAWN).run(LcncNode("spawn", VmRuntimeNodes.SPAWN,
                params = mapOf("id" to "shared", "facet" to "js", "statements" to "11", "wallMillis" to "23", "calls" to "7")), emptyMap())
            assertEquals("shared", spawned["vmId"])
            assertEquals(VmBudget(11, 23, 7), host.specs.single().budget)
            assertTrue(host.handles.getValue("shared").isAlive)
            val evaluated = runners.getValue(VmRuntimeNodes.EVAL).run(
                LcncNode("eval", VmRuntimeNodes.EVAL, params = mapOf("source" to "wrong")),
                mapOf("vmId" to "shared", "source?" to "6*7"),
            )
            assertEquals(mapOf("answer" to 42L), evaluated["value"])
            assertEquals(host.result.cid.value, evaluated["cid"])
            runners.getValue(VmRuntimeNodes.CALL).run(LcncNode("call", VmRuntimeNodes.CALL),
                mapOf("vmId" to "shared", "root" to "add", "args" to listOf(1, "2", mapOf("x" to true))))
            val handle = host.handles.getValue("shared")
            assertEquals("6*7" to "eval", handle.evals.single())
            assertEquals("add" to listOf(Teleported.Num(1), Teleported.Str("2"), Teleported.obj("x" to true)), handle.calls.single())
            val stats = runners.getValue(VmRuntimeNodes.STATS).run(LcncNode("stats", VmRuntimeNodes.STATS), mapOf("vmId" to "shared"))
            assertEquals(1L, (stats["stats"] as Map<*, *>)["evals"])
            assertEquals(1L, (stats["stats"] as Map<*, *>)["calls"])
            val revoked = runners.getValue(VmRuntimeNodes.REVOKE).run(
                LcncNode("revoke", VmRuntimeNodes.REVOKE, params = mapOf("reason" to "finished")), mapOf("vmId" to "shared"))
            assertEquals(true, revoked["ok"])
        }
        assertEquals(listOf("shared" to "finished"), host.revoked)
        assertTrue(default.specs.isEmpty())
        assertFalse(default.closed)
        assertFalse(host.closed)
        assertFalse(currentCoroutineContext().job.children.any())
    }

    @Test
    fun bindingMetadataNamesTheActualSingletonProviders() {
        val runners = VmRuntimeNodes.runners(Host())
        for ((type, runner) in runners) {
            val binding = runner as LcncServiceBinding
            val expected = if (type == VmRuntimeNodes.TIERS) VmProviderReportsKey else VmHostKey
            assertTrue(binding.requiredKeys.view.any { it === expected })
            assertTrue(binding.providedKeys.view.any { it === expected })
        }
    }

    @Test
    fun invalidConstructionAndUnknownHandlesFailBeforeHostOperations() = runTest {
        val host = Host()
        val runner = VmRuntimeNodes.spawn(host)
        for (params in listOf(
            mapOf("id" to "../invalid"), mapOf("facet" to "unknown"), mapOf("trust" to "typo"),
            mapOf("statements" to "-1"), mapOf("wallMillis" to "1.5"),
            mapOf("trust" to "UNTRUSTED", "world" to "/workspace"),
        )) {
            assertFailsWith<IllegalArgumentException> { runner.run(LcncNode("bad", VmRuntimeNodes.SPAWN, params), emptyMap()) }
        }
        assertTrue(host.specs.isEmpty())
        assertFailsWith<IllegalStateException> {
            VmRuntimeNodes.eval(host).run(LcncNode("eval", VmRuntimeNodes.EVAL), mapOf("vmId" to "missing", "source" to "1"))
        }
        host.spawn(VmSpec("existing", VmFacet.GRAAL_JS))
        assertFailsWith<IllegalStateException> {
            runner.run(LcncNode("spawn", VmRuntimeNodes.SPAWN, mapOf("id" to "existing")), emptyMap())
        }
        assertEquals(1, host.specs.size)
    }

    @Test
    fun tiersResolveTheReportProviderAndPreserveUnsupportedCapabilities() = runTest {
        val expected = VmCapabilityReport("native", false, emptyList(), "none", false, false, "not provided")
        val runner = VmRuntimeNodes.tiers { error("default provider must not be used") }
        val out = withContext(VmProviderReportsKey { listOf(expected) }) {
            runner.run(LcncNode("tiers", VmRuntimeNodes.TIERS), emptyMap())
        }
        assertEquals(listOf(expected.toMap()), out["tiers"])
    }

    @Test
    fun pytestReturnsOnlyTheGuestResultAndKeepsTheGuestAlive() = runTest {
        val host = Host()
        val vm = host.spawn(VmSpec("py", VmFacet.GRAAL_PYTHON)) as Host.Handle
        host.result = Teleported.Arr(listOf(Teleported.Num(1), Teleported.Str("one failed")))
        val out = VmRuntimeNodes.pytest(host).run(LcncNode("pytest", VmRuntimeNodes.PYTEST,
            mapOf("path" to "/workspace/tests's suite", "flags" to "-k 'one or two'")), mapOf("vmId" to "py"))
        assertEquals(mapOf("exit" to 1L, "tail" to "one failed"), out)
        assertTrue(vm.evals.single().first.contains("pytest.main(_lcnc_args)"))
        assertTrue(vm.evals.single().first.contains("shlex.split"))
        assertTrue(vm.isAlive)
        assertTrue(host.revoked.isEmpty())
    }

    @Test
    fun pytestDoesNotInventSuccessForUnavailableOrMalformedExecution() = runTest {
        val host = Host()
        host.spawn(VmSpec("js", VmFacet.GRAAL_JS))
        val runner = VmRuntimeNodes.pytest(host)
        assertFailsWith<IllegalArgumentException> { runner.run(LcncNode("test", VmRuntimeNodes.PYTEST), mapOf("vmId" to "js")) }
        host.spawn(VmSpec("py", VmFacet.GRAAL_PYTHON))
        assertFailsWith<IllegalStateException> { runner.run(LcncNode("test", VmRuntimeNodes.PYTEST), mapOf("vmId" to "py")) }
        host.failure = UnsupportedOperationException("pytest is not installed")
        val error = assertFailsWith<UnsupportedOperationException> {
            runner.run(LcncNode("test", VmRuntimeNodes.PYTEST), mapOf("vmId" to "py"))
        }
        assertEquals("pytest is not installed", error.message)
        assertTrue(host.revoked.isEmpty())
    }

    @Test
    fun unsupportedHostRemainsUnsupported() = runTest {
        assertFailsWith<NotImplementedError> {
            VmRuntimeNodes.spawn(VmHost.NONE).run(LcncNode("spawn", VmRuntimeNodes.SPAWN, mapOf("facet" to "js")), emptyMap())
        }
    }
}
