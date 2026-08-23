package borg.trikeshed.graal.subvm

import borg.trikeshed.vm.Teleported

import borg.trikeshed.pointcut.VmFacet
import java.io.File
import kotlin.test.Test

/** Why does the Python leaf host refute? Writes its findings to a scratch file (test-results get clobbered by concurrent runs). */
class PythonLeafHostProbeTest {
    @Test fun probePythonLeafHost() {
        val out = StringBuilder()
        val program = "def fib(n):\n    return n if n < 2 else fib(n-1) + fib(n-2)\n"
        // 1. can a LeafHost materialize fib from the whole program and agree with a fresh guest?
        runCatching {
            LeafTrainer.LeafHost(GuestBounds.PYTHON, "probe", Budget()).use { h ->
                h.materialize("fib", program)
                out.append("leafhost fib(10) = ").append(h.call("fib", Teleported.Arr(listOf(Teleported.Num(10))))).append('\n')
            }
        }.onFailure { out.append("leafhost FAILED: ").append(it.toString()).append('\n').append(it.stackTrace.take(8).joinToString("\n")).append('\n') }
        // 2. drive the trainer and read the demotion reason + receipts
        val transitions = ArrayList<String>(); val receipts = ArrayList<DelegationReceipt>()
        lateinit var trainer: LeafTrainer
        val iso = InProcessIsolate("py", VmFacet.GRAAL_PYTHON) { trainer.observe(it) }
        trainer = LeafTrainer(iso, trainCalls = 4, shadowCalls = 2, onTransition = { p, f, t -> transitions += "${p.root} $f→$t reason=${p.demotedReason}" }, onReceipt = { receipts += it })
        iso.use {
            iso.eval(program)
            repeat(4) { out.append("guest fib(10)=").append(iso.call("fib", Teleported.Num(10))).append('\n') }
            val p = trainer.profiles["fib"]
            out.append("profile after 4: ").append(p.toString()).append('\n')
            out.append("promote → ").append(trainer.promote("fib")).append('\n')
            iso.settle()
            out.append("profile after promote: ").append(p.toString()).append(" leafHost=").append(p?.leafHost != null).append('\n')
            repeat(3) { runCatching { out.append("shadow call = ").append(iso.call("fib", Teleported.Num(10))).append('\n') }.onFailure { out.append("call FAILED ").append(it.toString()).append('\n') } }
            out.append("profile end: ").append(p.toString()).append(" reason=").append(p?.demotedReason).append('\n')
        }
        out.append("transitions: ").append(transitions).append('\n')
        out.append("receipts: ").append(receipts).append('\n')
        File("/private/tmp/claude-501/-Users-jim-work-TrikeShed/03af77b2-a0a4-44c2-94c3-5c481620eda5/scratchpad/py-leafhost-probe.txt").writeText(out.toString())
        println(out)
    }
}
