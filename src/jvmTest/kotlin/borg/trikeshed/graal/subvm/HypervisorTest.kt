package borg.trikeshed.graal.subvm

import borg.trikeshed.graal.subvm.LeafTrainer.Phase
import borg.trikeshed.graal.subvm.Teleported.Num
import borg.trikeshed.graal.subvm.Teleported.Str
import borg.trikeshed.lib.toList
import borg.trikeshed.pointcut.PointcutBlackboardAdapter.PointcutLanding
import borg.trikeshed.pointcut.VmFacet
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The [Hypervisor] wiring: isolates are blackboard nodes, crossings are receipts, and the
 * promote / revoke decisions are Rete rules over [borg.trikeshed.dag.ReteFact.SiteHeat].
 *
 * The Rete agent runs on `Dispatchers.Default`, so `leaf-promotion` fires — and [LeafTrainer.promote]
 * runs — while the guest is still executing the call that produced the fact; the rebind is queued to
 * the guest thread and lands at the next safe point ([InProcessIsolate.onGuestThread]). Guest-driving
 * work is done on a bounded helper thread anyway: a wedge between the trainer and the isolate lock
 * surfaces as a failed assertion with a thread diagnosis, never as a hung build.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class HypervisorTest {
    private val FIB_JS = "function fib(n){return n<2?n:fib(n-1)+fib(n-2)}"

    private fun await(timeoutMs: Long, what: String, diagnosis: () -> String = { "" }, cond: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!cond()) {
            if (System.nanoTime() > deadline) fail("timed out after ${timeoutMs}ms waiting for $what\n${diagnosis()}")
            Thread.sleep(20)
        }
    }

    /** Stacks of every thread currently inside the sub-VM package (the Rete agent, a wedged guest thread, a watchdog). */
    private fun subVmThreads(): String = buildString {
        for ((th, frames) in Thread.getAllStackTraces()) {
            if (frames.any { it.className.startsWith("borg.trikeshed.graal.subvm") }) {
                append("  ${th.name} [${th.state}]\n")
                frames.take(12).forEach { append("    at $it\n") }
            }
        }
    }

    private fun Hypervisor.landingsOf(isolate: String): List<PointcutLanding> =
        adapter.landings.toList().filter { it.key.startsWith("pointcut/$isolate") }

    /**
     * Run [block] on a daemon thread. Returns null when it completed within [timeoutMs] (rethrowing
     * its failure, if any); otherwise a diagnosis of the stuck thread and every thread inside [LeafTrainer].
     */
    private fun driveBounded(name: String, timeoutMs: Long, block: () -> Unit): String? {
        val failure = AtomicReference<Throwable?>()
        val t = Thread({ try { block() } catch (e: Throwable) { failure.set(e) } }, name).apply { isDaemon = true }
        t.start(); t.join(timeoutMs)
        if (!t.isAlive) { failure.get()?.let { throw it }; return null }
        val sb = StringBuilder("thread '$name' still running after ${timeoutMs}ms\n")
        for ((th, frames) in Thread.getAllStackTraces()) {
            if (th === t || frames.any { it.className.startsWith("borg.trikeshed.graal.subvm.LeafTrainer") }) {
                sb.append("  ${th.name} [${th.state}]\n")
                frames.take(10).forEach { sb.append("    at $it\n") }
            }
        }
        return sb.toString()
    }

    @Test fun leafPromotionRuleFiresAndEveryCrossingLandsOnTheBlackboard() {
        val uncaught = java.util.concurrent.CopyOnWriteArrayList<String>()
        val priorHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> uncaught += "${t.name}: $e\n" + e.stackTrace.take(6).joinToString("") { "      at $it\n" } }
        val hv = Hypervisor(promoteAfter = 4, trainCalls = 4, shadowCalls = 2)
        var wedged = false   // on a deadlock the isolate is still entered by the stuck thread; close(true) would wait on it forever
        try {
            val a = hv.spawn("a", VmFacet.GRAAL_JS)
            assertIs<InProcessIsolate>(a)
            assertEquals(Trust.OWN, a.trust)
            assertEquals(a, hv["a"])
            assertTrue(hv.landingsOf("a").any { it.propertyName == "spawn" })
            a.eval(FIB_JS, "fib.js")

            // fib(16) = 987 and 3193 root returns per call: the 4th observation's fact reaches the Rete
            // agent while this first call is still running, which is the production shape of the rule.
            driveBounded("guest-driver", 30_000) {
                repeat(8) { assertEquals(Num(987), hv.delegateTo("a", "fib", Num(16))) }
            }?.let { diagnosis ->
                wedged = true
                fail("guest driver wedged (promotion must never block the guest thread):\n$diagnosis")
            }

            await(5_000, "rule leaf-promotion to fire") { hv.fires.any { it.ruleName == "leaf-promotion" && it.nodeId == "a/fib" } }
            val fire = hv.fires.first { it.ruleName == "leaf-promotion" }
            assertEquals("fib", fire.payload)
            assertEquals("hypervisor", fire.agentId)
            await(5_000, "fib to be promoted by the rule", diagnosis = {
                "profile=${hv.trainer("a")!!.profiles["fib"]} fires=${hv.fires.map { it.ruleName + ":" + it.nodeId }}\n" +
                    (if (uncaught.isEmpty()) "" else "UNCAUGHT on a Rete/dispatcher thread (the rule agent is dead):\n  ${uncaught.joinToString("  ")}") +
                    "threads inside subvm:\n" + subVmThreads()
            }) {
                hv.trainer("a")!!.profiles["fib"]?.phase in setOf(Phase.SHADOWED, Phase.DELEGATED)
            }
            assertTrue(uncaught.isEmpty(), "uncaught exception on a background thread:\n${uncaught.joinToString()}")
            Thread.sleep(300)
            assertEquals(1, hv.fires.count { it.ruleName == "leaf-promotion" }, "the predicate is false once the promotion is queued or done: ${hv.fires}")
            assertEquals(0, (a as InProcessIsolate).pendingGuestActions, "a queued promotion lands at the next call")

            // drive the rebound root so delegation receipts land
            driveBounded("guest-driver-2", 30_000) {
                repeat(3) { assertEquals(Num(987), hv.delegateTo("a", "fib", Num(16))) }
            }?.let { wedged = true; fail("guest driver wedged after promotion:\n$it") }
            assertEquals(Phase.DELEGATED, hv.trainer("a")!!.profiles["fib"]!!.phase)

            // receipts are logged at full rate (the audit trail) and LANDED at the sampled cadence the Rete heat
            // facts use — first promoteAfter, powers of two, every 256 — per isolate/root; transitions always land
            val fibReceipts = hv.receipts.filter { it.isolate == "a" && it.root == "fib" }
            assertEquals(11, fibReceipts.count { it.served == Served.GUEST }, "one delegate-to receipt per call: $fibReceipts")
            assertTrue(fibReceipts.any { it.served == Served.SHADOW }, "$fibReceipts")
            assertTrue(fibReceipts.any { it.served == Served.MEMO }, "$fibReceipts")
            assertTrue(fibReceipts.none { it.refuted })
            assertTrue(fibReceipts.map { it.seq }.zipWithNext().all { (x, y) -> x < y }, "receipt seq is monotonic")
            assertEquals(Teleported.Arr(listOf(Num(16))).cid, fibReceipts.first().argsCid)
            assertEquals(Num(987).cid, fibReceipts.first().resultCid)

            val landings = hv.landingsOf("a")
            assertTrue(landings.isNotEmpty())
            assertTrue(landings.all { it.facet == VmFacet.GRAAL_JS })
            val byProperty = landings.groupBy { it.propertyName }
            assertTrue(byProperty.containsKey("phase"), "phase landings: ${byProperty.keys}")
            assertTrue(byProperty.containsKey("delegate"), "delegate (receipt) landings: ${byProperty.keys}")
            assertTrue(byProperty.containsKey("delegate-to"), "delegate-to landings: ${byProperty.keys}")
            val phases = byProperty.getValue("phase").map { "${it.value}" }
            assertTrue(phases.any { it.contains("SELF_CONTAINED→SHADOWED") }, "$phases")
            assertTrue(phases.any { it.contains("SHADOWED→DELEGATED") }, "$phases")
            assertTrue(byProperty.getValue("delegate").all { "${it.value}".startsWith("delegate[") }, "${byProperty["delegate"]?.map { it.value }}")
            val landedDelegateTo = byProperty.getValue("delegate-to").size
            assertTrue(landedDelegateTo in 1 until 11, "per-call receipts are sampled onto the board, not landed one by one: $landedDelegateTo")
            assertTrue(landings.filter { it.propertyName == "phase" }.all { it.key.startsWith("pointcut/a/fib/") })

            // guest → host through the hypervisor is a receipt too (its first crossing always lands)
            hv.delegateFrom("a", "echo") { it.first() }
            assertEquals(Str("x"), a.eval("host.call('echo', 'x')"))
            val echo = hv.receipts.filter { it.isolate == "a" && it.root == "host.echo" }
            assertEquals(1, echo.size, "${hv.receipts}")
            assertEquals(Served.HOST, echo.single().served)
            assertEquals(Str("x").cid, echo.single().resultCid)
            val from = hv.landingsOf("a").filter { it.propertyName == "delegate-from" }
            assertEquals(1, from.size, "${hv.landingsOf("a").map { it.propertyName }}")
            assertTrue("${from.single().value}".contains("delegate[HOST] host.echo"), "${from.single().value}")
            assertTrue(from.single().key.startsWith("pointcut/a.host/echo/"), from.single().key)

            val snap = hv.snapshot()
            assertEquals(listOf("a"), snap["isolates"])
            assertEquals(listOf("a:live"), snap["leases"])
            assertTrue((snap["fires"] as List<*>).contains("leaf-promotion:a/fib"))
            assertEquals(hv.adapter.size, snap["landings"])
            assertEquals(hv.receipts.size, snap["receipts"])
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(priorHandler)
            if (!wedged) hv.close()
        }
    }

    @Test fun leaseBudgetRuleRevokesTheLeaseAndSnapshotListsIsolates() {
        Hypervisor(promoteAfter = 4, trainCalls = 4, shadowCalls = 2).use { hv ->
            hv.spawn("a", VmFacet.GRAAL_JS)
            val b = hv.spawn("b", VmFacet.GRAAL_JS, budget = Budget(calls = 3))
            assertEquals(Budget(calls = 3), hv.lease("b")!!.budget)
            assertFalse(hv.lease("b")!!.revoked)
            // a root with a host call in its window is never self-contained, so only the lease rule can fire for b
            hv.delegateFrom("b", "tick") { it.first() }
            b.eval("function ping(n){return host.call('tick', n)}", "ping.js")

            // the revoke's interrupt may land while a call is in flight; that is the lease doing its job
            var interruptedCalls = 0
            repeat(6) {
                try { assertEquals(Num(5), hv.delegateTo("b", "ping", Num(5))) }
                catch (e: GuestException) { assertEquals(GuestFailure.INTERRUPTED, e.kind, e.message); interruptedCalls++ }
            }
            await(5_000, "rule lease-budget to fire") { hv.fires.any { it.ruleName == "lease-budget" && it.nodeId == "b" } }
            await(5_000, "lease b to be revoked") { hv.lease("b")!!.revoked }
            assertTrue(hv.lease("b")!!.revoked)
            assertFalse(hv.lease("a")!!.revoked)
            assertTrue(hv.fires.none { it.ruleName == "leaf-promotion" }, "${hv.fires}")
            assertTrue(hv.fires.none { it.ruleName == "lease-budget" && it.nodeId == "a" })
            assertEquals(Phase.OBSERVED, hv.trainer("b")!!.profiles["ping"]?.phase)
            assertTrue(hv.landingsOf("b").any { it.propertyName == "revoke" && "${it.value}".startsWith("budget:") },
                "${hv.landingsOf("b").map { it.propertyName to it.value }}")
            assertTrue(hv.landingsOf("b").any { it.propertyName == "delegate-from" })
            assertTrue(interruptedCalls <= 6)
            assertEquals(6 - interruptedCalls, hv.receipts.count { it.isolate == "b" && it.root == "ping" && it.served == Served.GUEST })
            assertTrue(hv.receipts.any { it.isolate == "b" && it.root == "host.tick" && it.served == Served.HOST })

            // revoke is idempotent and the isolate survives an idle interrupt
            hv.revoke("b", "again")
            assertEquals(1, hv.landingsOf("b").count { it.propertyName == "revoke" })
            assertTrue(b.isAlive)
            assertEquals(Num(9), b.eval("host.call('tick', 9)"))

            val snap = hv.snapshot()
            assertEquals(listOf("a", "b"), snap["isolates"])
            assertEquals(setOf("a:live", "b:revoked"), (snap["leases"] as List<*>).toSet())
            assertTrue((snap["fires"] as List<*>).contains("lease-budget:b"))
            assertFailsWithIllegalArgument { hv["nope"] }
            assertFailsWithIllegalArgument { hv.spawn("a", VmFacet.GRAAL_JS) }
        }
    }

    private fun assertFailsWithIllegalArgument(block: () -> Unit) {
        try { block(); fail("expected IllegalArgumentException") } catch (_: IllegalArgumentException) { }
    }
}
