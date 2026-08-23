package borg.trikeshed.graal.subvm

import borg.trikeshed.vm.Teleported

import borg.trikeshed.pointcut.VmFacet
import java.io.File
import kotlin.test.Test

/**
 * Why did Python `strs` (a str-returning generator join) stay OBSERVED in the heat soak while JS `strs`
 * delegated? Answer: GraalPy's main-module bindings expose builtins as executable members, so the
 * binding-pointcut installer had wrapped `chr` and every builtin — `strs → chr` read as a foreign root.
 * Pinned: only program-defined roots are wrapped, and `strs` becomes SELF_CONTAINED.
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
        File("/private/tmp/claude-501/-Users-jim-work-TrikeShed/03af77b2-a0a4-44c2-94c3-5c481620eda5/scratchpad/py-strs-probe.txt").writeText(out.toString())
        println(out)
        kotlin.test.assertEquals(setOf("fib", "work", "strs", "impure"), wrapped, "only program-defined roots are pointcuts: $wrapped")
        kotlin.test.assertTrue(seen.all { it.selfContained }, "strs calls only builtins — self-contained: $out")
        kotlin.test.assertEquals(LeafTrainer.Phase.SELF_CONTAINED, trainer.profiles["strs"]?.phase, out.toString())
    }
}
