package borg.trikeshed.graal.subvm.demo

import borg.trikeshed.graal.subvm.Budget
import borg.trikeshed.graal.subvm.GuestException
import borg.trikeshed.graal.subvm.GuestFailure
import borg.trikeshed.graal.subvm.Hypervisor
import borg.trikeshed.vm.Teleported
import borg.trikeshed.graal.subvm.Trust
import borg.trikeshed.pointcut.VmFacet

/**
 * GraalPy leaf demo: `fib` trained and teleported, the KNOWN BOUND that Python is stopped from
 * outside by the wall budget (statement limits are not safe on GraalPy), and an UNTRUSTED guest
 * behind the process wall.
 */
object PyLeafDemo {
    const val PROGRAM = "def fib(n):\n    return n if n < 2 else fib(n-1) + fib(n-2)\n"
    const val ROOT = "fib"
    const val ARG = 18L

    fun leafRun(arg: Long = ARG, measured: Int = 20): LeafDemo.Run =
        LeafDemo.run(VmFacet.GRAAL_PYTHON, PROGRAM, ROOT, arg, measured = measured)

    /** KNOWN BOUND: a 500ms wall budget interrupts an endless Python loop from the watchdog; the isolate survives it. */
    fun wallBudgetDemo(out: (String) -> Unit = ::println): GuestFailure? = Hypervisor().use { hv ->
        val iso = hv.spawn("py-loop", VmFacet.GRAAL_PYTHON, budget = Budget(wallMillis = 500))
        val t0 = System.nanoTime()
        val kind = try {
            iso.eval("i=0\nwhile True:\n    i+=1", "loop")
            out("unexpected: the endless loop returned"); null
        } catch (e: GuestException) {
            out("wall budget 500ms: GuestException kind=${e.kind} after ${(System.nanoTime() - t0) / 1_000_000}ms; isolate alive=${iso.isAlive}; ${e.message?.lineSequence()?.first()}")
            e.kind
        }
        val again = runCatching { iso.eval("1+1", "after") }.fold({ "= $it" }, { "threw ${it::class.simpleName}: ${it.message?.lineSequence()?.first()}" })
        out("after interrupt: eval(\"1+1\") $again  stats=${iso.stats()}")
        kind
    }

    /** UNTRUSTED trust puts the guest in a child JVM; the same eval crosses the line protocol. */
    fun untrustedDemo(out: (String) -> Unit = ::println): Teleported = Hypervisor().use { hv ->
        val iso = hv.spawn("py-wall", VmFacet.GRAAL_PYTHON, Trust.UNTRUSTED)
        val r = iso.eval("sum(range(100))", "wall")
        out("untrusted ${iso::class.simpleName} py-wall: sum(range(100)) = $r  (== 4950: ${r == Teleported.Num(4950)})  alive=${iso.isAlive}")
        r
    }
}

fun main() {
    println(LeafDemo.report(PyLeafDemo.leafRun()))
    PyLeafDemo.wallBudgetDemo()
    PyLeafDemo.untrustedDemo()
}
