package borg.trikeshed.vm

import borg.trikeshed.graal.subvm.GuestBounds
import borg.trikeshed.graal.subvm.SubVmMain
import borg.trikeshed.pointcut.VmFacet
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Real SubVmMain children behind the standalone provider and the common process owner. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ProcessVmHostTest {
    @Test fun standaloneProviderPreservesItsReportAndRunsJavaScript() {
        JvmProcessIsolateProvider.open().use { host ->
            val report = JvmProcessIsolateProvider.report()
            assertTrue(report.available)
            assertEquals(setOf(VmFacet.GRAAL_JS, VmFacet.GRAAL_PYTHON), host.languages)
            assertEquals(VmFacet.entries.map { it.id }, report.languages)
            assertEquals("process", report.sandboxKind)
            assertTrue(report.callSupported)
            assertTrue(report.wallBudgetSupported)
            val guest = host.spawn(VmSpec("standalone-js", VmFacet.GRAAL_JS))
            assertEquals("process", guest.tier)
            assertEquals(Teleported.Num(3), guest.eval("1 + 2"))
            guest.eval("function add(a, b) { return a + b; }")
            assertEquals(Teleported.Num(42), guest.call("add", Teleported.Num(20), Teleported.Num(22)))
            assertEquals(2L, guest.stats().evals)
            assertEquals(1L, guest.stats().calls)
            guest.close()
            guest.close()
            assertFalse(guest.isAlive)
        }
    }

    @Test fun standaloneProviderRunsPythonEvalAndCall() {
        JvmProcessIsolateProvider.open().use { host ->
            val guest = host.spawn(VmSpec("standalone-python", VmFacet.GRAAL_PYTHON))
            assertEquals(Teleported.Num(3), guest.eval("1 + 2"))
            guest.eval("def add(a, b):\n    return a + b")
            assertEquals(Teleported.Str("ab"), guest.call("add", Teleported.Str("a"), Teleported.Str("b")))
            guest.close()
            assertFalse(guest.isAlive)
        }
    }

    @Test fun delegateRegistrationCrossesTheWireAndReturnsHostValuesAndErrors() {
        val guest = isolate("delegation")
        try {
            guest.delegate("double") { args -> Teleported.Num(2 * (args.single() as Teleported.Num).v) }
            assertEquals(Teleported.Num(42), guest.eval("host.call('double', 21)"))
            guest.eval("function delegated(n) { return host.call('double', n); }")
            assertEquals(Teleported.Num(84), guest.call("delegated", Teleported.Num(42)))
            guest.delegate("failure") { error("host failure through standalone protocol") }
            val failure = assertFailsWith<VmGuestException> { guest.eval("host.call('failure')") }
            assertTrue(failure.message.orEmpty().contains("host failure through standalone protocol"))
            assertEquals(Teleported.Num(4), guest.eval("host.call('double', 2)"))
            assertEquals(4L, guest.stats().hostCalls)
            guest.close()
            assertReaped(guest.processId)
        } finally { cleanup(guest) }
    }

    @Test fun closeReapsTheActualChildAndIsIdempotent() {
        val guest = isolate("close")
        try {
            assertTrue(guest.processId > 0)
            assertTrue(ProcessHandle.of(guest.processId).orElseThrow().isAlive)
            assertEquals(Teleported.Num(4), guest.eval("2 + 2"))
            guest.close()
            assertFalse(guest.isAlive)
            assertReaped(guest.processId)
            guest.close()
            assertReaped(guest.processId)
        } finally { cleanup(guest) }
    }

    @Test fun simultaneousCloseFinishesTheOutstandingCallbackAndItsActualGuestResult() {
        val guest = isolate("drain")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var evaluation: Invocation<Teleported>? = null
        var firstClose: Invocation<Unit>? = null
        var secondClose: Invocation<Unit>? = null
        try {
            guest.delegate("held") {
                entered.countDown()
                check(release.await(30, TimeUnit.SECONDS)) { "Test did not release the admitted callback" }
                Teleported.Num(40)
            }
            evaluation = Invocation("process-vm-eval") { guest.eval("host.call('held') + 2") }
            assertTrue(entered.await(20, TimeUnit.SECONDS), "Guest entered the registered host callback")
            firstClose = Invocation("process-vm-close-1") { guest.close() }
            secondClose = Invocation("process-vm-close-2") { guest.close() }
            assertTrue(firstClose.started.await(5, TimeUnit.SECONDS))
            assertTrue(secondClose.started.await(5, TimeUnit.SECONDS))
            assertFalse(firstClose.completed.await(1_500, TimeUnit.MILLISECONDS), "Close must join admitted work")
            assertFalse(secondClose.completed.await(50, TimeUnit.MILLISECONDS), "Concurrent close must join the same drain")
            assertTrue(ProcessHandle.of(guest.processId).orElseThrow().isAlive,
                "Neither closer may kill the child while the admitted callback remains held")
            release.countDown()
            assertEquals(Teleported.Num(42), evaluation.awaitResult().getOrThrow(),
                "Drain must return the real guest result after the host callback, not a sentinel")
            firstClose.awaitResult().getOrThrow()
            secondClose.awaitResult().getOrThrow()
            assertFalse(guest.isAlive)
            assertReaped(guest.processId)
        } finally {
            release.countDown()
            cleanup(guest, evaluation, firstClose, secondClose)
        }
    }

    @Test fun interruptReapsABlockedGuestWithoutWaitingForTheEvaluationRoundTrip() {
        val guest = isolate("interrupt")
        val entered = CountDownLatch(1)
        var evaluation: Invocation<Teleported>? = null
        var interruption: Invocation<Boolean>? = null
        try {
            guest.delegate("entered") { entered.countDown(); Teleported.Null }
            evaluation = Invocation("process-vm-loop") { guest.eval("host.call('entered'); while (true) {}") }
            assertTrue(entered.await(20, TimeUnit.SECONDS))
            assertFalse(evaluation.completed.await(100, TimeUnit.MILLISECONDS))
            interruption = Invocation("process-vm-interrupt") { guest.interrupt() }
            assertTrue(interruption.awaitResult(5).getOrThrow(), "Interrupt must not queue behind the blocked eval")
            assertReaped(guest.processId)
            assertTrue(evaluation.awaitResult(5).isFailure, "The revoked guest cannot produce a successful result")
            assertFalse(guest.isAlive)
            guest.close()
            guest.close()
        } finally { cleanup(guest, evaluation, interruption) }
    }

    @Test fun hostRevocationReapsItsOwnPidAndPreventsFurtherGuestCalls() {
        var pipe: JvmProcessPipe? = null
        val host = ProcessVmHost("jvm", setOf(VmFacet.GRAAL_JS)) { spec ->
            JvmProcessPipe(command(spec.id)).also { pipe = it }
        }
        try {
            val guest = host.spawn(VmSpec("host-revoke", VmFacet.GRAAL_JS))
            val pid = requireNotNull(pipe).pipes.processId
            assertEquals(Teleported.Num(1), guest.eval("1"))
            host.revoke(guest.id, "test revocation")
            assertReaped(pid)
            assertFalse(guest.isAlive)
            assertFails { guest.eval("2") }
            host.revoke(guest.id, "already reaped")
            host.close()
            host.close()
            assertFailsWith<IllegalStateException> { host.spawn(VmSpec("after-close", VmFacet.GRAAL_JS)) }
        } finally {
            pipe?.pipes?.processId?.let(::reap)
            runCatching { host.close() }
        }
    }

    private fun isolate(id: String) = ProcessIsolateHost(id, VmFacet.GRAAL_JS, JvmProcessPipe(command(id)))

    private fun command(id: String): List<String> = listOf(
        File(System.getProperty("java.home"), "bin/java").path,
        "-Xss4m", "-cp", System.getProperty("java.class.path"), SubVmMain::class.java.name,
        GuestBounds.of(VmFacet.GRAAL_JS).languageId, id,
        // No child watchdog or statement budget can stand in for the lifecycle behavior under test.
        "0", "0",
    )

    private class Invocation<T>(name: String, operation: () -> T) {
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        private val result = AtomicReference<Result<T>>()
        val thread = Thread({
            started.countDown()
            try { result.set(runCatching(operation)) }
            finally { completed.countDown() }
        }, name).apply { isDaemon = true; start() }

        fun awaitResult(seconds: Long = 20): Result<T> {
            assertTrue(completed.await(seconds, TimeUnit.SECONDS), "${thread.name} did not finish within ${seconds}s")
            thread.join(1_000)
            assertFalse(thread.isAlive, "${thread.name} must be joined")
            return requireNotNull(result.get())
        }
    }

    private fun assertReaped(pid: Long) =
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "Child $pid remains alive")

    /** Failure cleanup targets only the exact child owned by this test, never other JVMs. */
    private fun reap(pid: Long) {
        ProcessHandle.of(pid).ifPresent { child ->
            if (child.isAlive) {
                child.destroyForcibly()
                child.onExit().get(10, TimeUnit.SECONDS)
            }
        }
    }

    private fun join(vararg pending: Invocation<*>?) {
        pending.filterNotNull().forEach {
            it.thread.join(10_000)
            assertFalse(it.thread.isAlive, "${it.thread.name} remains alive after child cleanup")
        }
    }

    private fun cleanup(guest: ProcessIsolateHost, vararg pending: Invocation<*>?) {
        reap(guest.processId)
        join(*pending)
        runCatching { guest.close() }
    }
}
