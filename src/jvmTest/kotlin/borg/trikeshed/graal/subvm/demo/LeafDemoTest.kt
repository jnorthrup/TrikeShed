package borg.trikeshed.graal.subvm.demo

import borg.trikeshed.graal.subvm.Served
import borg.trikeshed.pointcut.VmFacet
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LeafDemoTest {
    private fun assertPromoted(run: LeafDemo.Run, report: String) {
        assertTrue(run.transitions.any { it.endsWith("→DELEGATED") }, "no transition reached DELEGATED\n$report")
        val served = run.receipts.map { it.served }.toSet()
        assertTrue(Served.SHADOW in served, "no SHADOW receipt landed\n$report")
        assertTrue(Served.MEMO in served, "no MEMO receipt landed\n$report")
        assertTrue(run.landings > 0, "nothing landed on the blackboard\n$report")
        assertTrue("leaf-promotion" in run.fires, "the leaf-promotion rule never fired\n$report")
    }

    private fun memoBeatsRecursion(run: LeafDemo.Run) = run.delegatedNanos >= 0 && run.delegatedNanos < run.guestNanos

    @Test
    fun jsLeafPromotesToMemo() {
        val run = LeafDemo.run(VmFacet.GRAAL_JS, JsLeafDemo.PROGRAM, JsLeafDemo.ROOT, 20)
        val report = LeafDemo.report(run)
        println(report)
        assertPromoted(run, report)
        assertTrue(memoBeatsRecursion(run), "memo must beat recursion: guest=${run.guestNanos}ns delegated=${run.delegatedNanos}ns\n$report")
    }

    @Test
    fun pythonLeafPromotesToMemo() {
        val run = LeafDemo.run(VmFacet.GRAAL_PYTHON, PyLeafDemo.PROGRAM, PyLeafDemo.ROOT, 16)
        val report = LeafDemo.report(run)
        println(report)
        assertPromoted(run, report)
        // memo vs recursion is asserted for JS; for Python it is reported either way
        if (memoBeatsRecursion(run)) println("python: memo beat recursion guest=${run.guestNanos}ns delegated=${run.delegatedNanos}ns")
        else println("python: memo did NOT beat recursion on this machine guest=${run.guestNanos}ns delegated=${run.delegatedNanos}ns")
    }

    /**
     * Control: a self-contained root with ONE root return per call. It isolates the trainer
     * pipeline (observe → promote → shadow → memo) from what recursion adds — a transition that
     * lands mid-call, with every inner return still needing the profile monitor the promoting
     * Rete thread holds.
     */
    @Test
    fun nonRecursiveControlPromotesToMemo() {
        // ~60k guest statements per call: the isolate's statement budget (5M) is cumulative over the context's life
        val program = "function work(n){let s=0;for(let i=0;i<n*1000;i++){s=(s+i*i)%1000003}return s}\n"
        val run = LeafDemo.run(VmFacet.GRAAL_JS, program, "work", 20)
        val report = LeafDemo.report(run)
        println(report)
        assertPromoted(run, report)
        val shadow = assertNotNull(run.receiptNanos(Served.SHADOW), "shadow receipts carry nanos\n$report")
        val memo = assertNotNull(run.receiptNanos(Served.MEMO), "memo receipts carry nanos\n$report")
        assertTrue(memo < shadow, "a memo hit must beat running work(20) in guest+leaf host: memo=${memo}ns shadow=${shadow}ns\n$report")
    }

    @Test
    fun nodeBranchSkipsCleanlyWithoutGraalJsLauncher() {
        val launcher = LeafDemo.nodeLauncher()
        println("nodeLauncher() = $launcher  (PATH node = ${LeafDemo.systemNode()})")
        val lines = ArrayList<String>()
        val platform = JsLeafDemo.nodeDemo { lines += it; println(it) } // must not throw either way
        assertFalse(lines.isEmpty(), "the node branch must say what it did")
        if (launcher == null) {
            assertNull(platform, "no GraalJS node launcher on this machine: the node branch must be skipped")
            assertTrue(lines.first().startsWith("node demo skipped"), "skip reason expected, got: ${lines.first()}")
        } else {
            assertTrue(!platform.isNullOrBlank(), "GraalJS node launcher present but no platform came back")
        }
    }
}
