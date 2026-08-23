package borg.trikeshed.graal.subvm

import borg.trikeshed.vm.Teleported

import borg.trikeshed.graal.subvm.LeafTrainer.Phase
import borg.trikeshed.vm.Teleported.Num
import borg.trikeshed.pointcut.VmFacet
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [LeafTrainer] phases, each evidenced: OBSERVED → SELF_CONTAINED → (promote) SHADOWED → DELEGATED,
 * and the refutation path SHADOWED → DEMOTED.
 *
 * Once a root is rebound the guest's own recursion resolves through the rebound global, but only the
 * OUTERMOST frame is shadowed or served (inner self-recursive frames run the original directly), so
 * one top-level call is exactly one receipt: `fib(12)` twice → two SHADOW receipts → DELEGATED; again →
 * one MEMO receipt; `fib(13)` → one HOST receipt.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class LeafTrainerTest {
    private class Rig(facet: VmFacet, trainCalls: Int = 4, shadowCalls: Int = 2) : AutoCloseable {
        val transitions = CopyOnWriteArrayList<Triple<String, Phase, Phase>>()
        val receipts = CopyOnWriteArrayList<DelegationReceipt>()
        lateinit var trainer: LeafTrainer
        val isolate = InProcessIsolate("trainer-${facet.id}", facet) { obs -> trainer.observe(obs) }

        init {
            trainer = LeafTrainer(
                isolate, trainCalls, shadowCalls,
                onTransition = { p, from, to -> transitions += Triple(p.root, from, to) },
                onReceipt = { receipts += it },
            )
        }

        fun phase(root: String): Phase? = trainer.profiles[root]?.phase
        fun receiptsSince(n: Int): List<DelegationReceipt> = receipts.drop(n)
        override fun close() { trainer.close(); isolate.close() }
    }

    private val FIB_JS = "function fib(n){return n<2?n:fib(n-1)+fib(n-2)}"

    @Test fun jsFibTrainsShadowsDelegatesThenServesMemoAndHost() {
        Rig(VmFacet.GRAAL_JS).use { rig ->
            val iso = rig.isolate
            val trainer = rig.trainer
            iso.delegate("tick") { it.first() }
            iso.eval("$FIB_JS\nfunction impure(n){return host.call('tick', n)}", "leaf.js")

            // ── train: 4 top-level calls, all self-contained (self-recursion allowed) ──
            repeat(4) { assertEquals(Num(144), iso.call("fib", Num(12))) }
            val profile = trainer.profiles["fib"] ?: fail("no profile for fib; roots seen: ${trainer.profiles.keys}")
            assertEquals(Phase.SELF_CONTAINED, profile.phase, "$profile")
            assertTrue(rig.transitions.contains(Triple("fib", Phase.OBSERVED, Phase.SELF_CONTAINED)), "${rig.transitions}")
            assertEquals(0L, profile.notSelfContained)
            assertEquals(0L, profile.opaqueReturns)
            assertTrue(profile.calls >= 4)
            assertTrue(profile.characters!!.contains("fib(n-1)"), profile.characters)
            assertTrue(rig.receipts.isEmpty(), "no receipts before promotion")
            assertNull(profile.leafHost)

            // ── promote: rule-driven, installs the shadowing proxy ──
            assertTrue(trainer.promote("fib"))
            assertEquals(Phase.SHADOWED, profile.phase)
            assertNotNull(profile.leafHost, "leaf host materialised from the captured characters")
            assertNotNull(profile.original)
            assertFalse(trainer.promote("fib"), "only SELF_CONTAINED promotes")
            assertTrue(rig.transitions.contains(Triple("fib", Phase.SELF_CONTAINED, Phase.SHADOWED)))

            // ── shadow: guest still computes, leaf host verifies, cids agree ──
            repeat(2) { assertEquals(Num(144), iso.call("fib", Num(12))) }
            assertEquals(Phase.DELEGATED, profile.phase, "$profile")
            assertEquals(listOf(Served.SHADOW, Served.SHADOW), rig.receipts.map { it.served }, "one receipt per top-level call: ${rig.receipts}")
            assertTrue(rig.receipts.none { it.refuted })
            assertEquals(1, rig.transitions.count { it.first == "fib" && it.third == Phase.DELEGATED }, "${rig.transitions}")
            assertTrue(rig.transitions.contains(Triple("fib", Phase.SHADOWED, Phase.DELEGATED)))
            assertEquals(2, profile.consistent); assertEquals(0, profile.inconsistent)
            assertEquals(0L, iso.stats().delegationsMemo)
            assertEquals(0L, iso.stats().refutations)
            assertEquals(0L, iso.stats().delegationsHost)
            assertTrue(rig.receipts.all { it.isolate == iso.id && it.root == "fib" })
            assertTrue(rig.receipts.map { it.seq }.zipWithNext().all { (a, b) -> a < b }, "receipt seq is monotonic")

            // ── delegated, memo hit: one MEMO receipt, guest function not executed ──
            val enters = iso.stats().rootEnters
            val n0 = rig.receipts.size
            assertEquals(Num(144), iso.call("fib", Num(12)))
            assertEquals(listOf(Served.MEMO), rig.receiptsSince(n0).map { it.served })
            assertEquals(1L, iso.stats().delegationsMemo)
            assertEquals(enters, iso.stats().rootEnters, "a memo hit never enters the guest root")

            // ── delegated, memo miss: the warm leaf host computes, one HOST receipt ──
            val n1 = rig.receipts.size
            assertEquals(Num(233), iso.call("fib", Num(13)))
            assertEquals(listOf(Served.HOST), rig.receiptsSince(n1).map { it.served })
            assertEquals(1L, iso.stats().delegationsHost)
            assertEquals(enters, iso.stats().rootEnters, "the leaf host is not instrumented and the guest root is not entered")
            assertEquals(Phase.DELEGATED, profile.phase)
            // now memoised too
            val n2 = rig.receipts.size
            assertEquals(Num(233), iso.call("fib", Num(13)))
            assertEquals(listOf(Served.MEMO), rig.receiptsSince(n2).map { it.served })
            assertEquals(1L, iso.stats().delegationsHost)
            assertEquals(0L, iso.stats().refutations)
            val memoHit = rig.receipts.last()
            assertEquals(Teleported.Arr(listOf(Num(13))).cid, memoHit.argsCid)
            assertEquals(Num(233).cid, memoHit.resultCid)

            // ── impure: a host call inside the window is never self-contained ──
            repeat(6) { assertEquals(Num(it.toLong()), iso.call("impure", Num(it.toLong()))) }
            val impure = trainer.profiles.getValue("impure")
            assertEquals(Phase.OBSERVED, impure.phase, "$impure")
            assertTrue(impure.notSelfContained >= 6)
            assertFalse(trainer.promote("impure"))
            assertFalse(trainer.promote("never-observed"))
        }
    }

    @Test fun jsLeafThatNeedsAGuestGlobalIsRefutedAndDemoted() {
        Rig(VmFacet.GRAAL_JS).use { rig ->
            val iso = rig.isolate
            val trainer = rig.trainer
            iso.eval("var G = 5;\nfunction usesGlobal(n){return n + G}", "global.js")
            repeat(4) { assertEquals(Num(6), iso.call("usesGlobal", Num(1))) }
            val profile = trainer.profiles.getValue("usesGlobal")
            assertEquals(Phase.SELF_CONTAINED, profile.phase, "$profile")

            assertTrue(trainer.promote("usesGlobal"))
            assertEquals(Phase.SHADOWED, profile.phase)
            assertNotNull(profile.leafHost, "the characters parse on their own; G is only missing at call time")

            // the shadow call: guest says 6, the leaf host has no G → refuted → demoted, guest value returned
            assertEquals(Num(6), iso.call("usesGlobal", Num(1)))
            assertEquals(Phase.DEMOTED, profile.phase, "$profile")
            assertTrue(profile.demotedReason!!.startsWith("leaf host failed"), profile.demotedReason)
            assertTrue(profile.demotedReason!!.contains("G is not defined"), profile.demotedReason)
            val refuted = rig.receipts.filter { it.root == "usesGlobal" && it.refuted }
            assertEquals(1, refuted.size, "${rig.receipts}")
            assertEquals(Served.SHADOW, refuted.single().served)
            assertEquals(1, profile.inconsistent)
            assertTrue(rig.transitions.contains(Triple("usesGlobal", Phase.SHADOWED, Phase.DEMOTED)), "${rig.transitions}")
            assertNull(profile.leafHost, "leaf host closed on demotion")

            // original binding restored: plain guest calls, no more receipts, no re-promotion
            val n = rig.receipts.size
            repeat(3) { assertEquals(Num(6), iso.call("usesGlobal", Num(1))) }
            assertEquals(Num(7), iso.eval("usesGlobal(2)"))
            assertEquals(n, rig.receipts.size)
            assertEquals(Phase.DEMOTED, profile.phase)
            assertFalse(trainer.promote("usesGlobal"))
        }
    }

    /**
     * Same fib scenario in Python. GraalPy 25.0.2 reports no function roots to the listener
     * (PythonRootProbeTest), so the trainer learns from binding pointcuts: observations carry no
     * characters, and [LeafTrainer.promote] materialises the leaf host from the isolate's whole
     * [InProcessIsolate.program] instead. Guest recursion resolves through the rebound global exactly
     * as in JS, so the same per-call deltas hold (see the class kdoc).
     */
    @Test fun pythonFibTrainsShadowsAndDelegatesThroughBindingPointcuts() {
        Rig(VmFacet.GRAAL_PYTHON).use { rig ->
            val iso = rig.isolate
            val trainer = rig.trainer
            iso.eval("def fib(n): return n if n<2 else fib(n-1)+fib(n-2)", "leaf.py")
            repeat(4) { assertEquals(Num(144), iso.call("fib", Num(12))) }
            val profile = trainer.profiles["fib"] ?: fail("no profile for fib; roots seen: ${trainer.profiles.keys}")
            assertEquals(Phase.SELF_CONTAINED, profile.phase, "$profile")
            assertNull(profile.characters, "binding pointcuts carry no source section")
            assertEquals(-1, profile.line)
            assertTrue(iso.program.startsWith("def fib"), iso.program)
            assertTrue(rig.receipts.isEmpty())

            assertTrue(trainer.promote("fib"))
            assertEquals(Phase.SHADOWED, profile.phase)
            assertNotNull(profile.leafHost, "leaf host materialised from the program: ${iso.program}")
            assertFalse(trainer.promote("fib"))

            repeat(2) { assertEquals(Num(144), iso.call("fib", Num(12))) }
            assertEquals(Phase.DELEGATED, profile.phase, "$profile receipts=${rig.receipts}")
            assertEquals(listOf(Served.SHADOW, Served.SHADOW), rig.receipts.map { it.served }, "${rig.receipts}")
            assertTrue(rig.receipts.none { it.refuted }, "${rig.receipts}")
            assertTrue(rig.transitions.contains(Triple("fib", Phase.SHADOWED, Phase.DELEGATED)), "${rig.transitions}")

            val n0 = rig.receipts.size
            assertEquals(Num(144), iso.call("fib", Num(12)))
            assertEquals(listOf(Served.MEMO), rig.receiptsSince(n0).map { it.served })
            assertEquals(1L, iso.stats().delegationsMemo)

            val n1 = rig.receipts.size
            assertEquals(Num(233), iso.call("fib", Num(13)))
            assertEquals(listOf(Served.HOST), rig.receiptsSince(n1).map { it.served })
            assertEquals(1L, iso.stats().delegationsHost)
            assertEquals(Phase.DELEGATED, profile.phase, "$profile")
            assertEquals(0L, iso.stats().refutations)

            // demotion restores the observing wrapper, not the bare function: calls are still observed
            val callsBefore = profile.calls
            val n2 = rig.receipts.size
            trainer.demote("fib", "test")
            assertEquals(Phase.DEMOTED, profile.phase)
            assertEquals("test", profile.demotedReason)
            assertNull(profile.leafHost)
            assertEquals(Num(144), iso.call("fib", Num(12)))
            assertTrue(profile.calls > callsBefore, "wrapper restored, observations continue: $profile")
            assertEquals(n2, rig.receipts.size, "no receipts once the proxy is gone")
        }
    }

}
