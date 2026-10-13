package borg.trikeshed.graal.subvm

import borg.trikeshed.vm.Teleported

import borg.trikeshed.pointcut.VmFacet
import kotlin.test.Test

/**
 * A generator join calling only builtins remains self-contained under native instrumentation.
 * No observation wrappers are installed around the function or the builtins it calls.
 */
class PythonStrsProbeTest {
    @Test fun probe() {
        val out = StringBuilder()
        val seen = ArrayList<RootObservation>()
        lateinit var trainer: LeafTrainer
        val iso = InProcessIsolate("py", VmFacet.GRAAL_PYTHON) { o ->
            if (o.root == "strs") seen += o
            trainer.observe(o)
        }
        trainer = LeafTrainer(iso, trainCalls = 4, shadowCalls = 2)
        lateinit var wrapped: Set<String>
        iso.use {
            iso.eval(borg.trikeshed.graal.subvm.demo.HeatSoak.PY_PROGRAM)
            repeat(6) { i -> out.append("strs(${3 + i}) = ").append(iso.call("strs", Teleported.Num((3 + i).toLong()))).append('\n') }
            out.append("observations: ").append(seen.size).append('\n')
            seen.take(6).forEach { o -> out.append("  selfContained=${o.selfContained} nanos=${o.nanos} chars=${o.characters != null}\n") }
            out.append("profile: ").append(trainer.profiles["strs"]).append('\n')
            out.append("program defs: ").append(iso.program.lines().filter { it.startsWith("def ") }).append('\n')
            wrapped = iso.wrappedRoots
            out.append("wrapped roots (${wrapped.size}): ").append(wrapped.sorted().take(40)).append('\n')
            val raw = iso.eval("strs(5)")
            out.append("raw eval strs(5) = ").append(raw).append(" opaque=").append(raw.isOpaque).append('\n')
        }
        println(out)
        kotlin.test.assertTrue(wrapped.isEmpty(), "native pointcuts must not replace bindings: $wrapped")
        kotlin.test.assertTrue(seen.all { it.selfContained }, "strs calls only builtins — self-contained: $out")
        kotlin.test.assertEquals(LeafTrainer.Phase.SELF_CONTAINED, trainer.profiles["strs"]?.phase, out.toString())
    }
}
