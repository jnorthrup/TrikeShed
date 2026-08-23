package borg.trikeshed.graal.subvm.demo

import borg.trikeshed.graal.subvm.Hypervisor
import borg.trikeshed.graal.subvm.ProcessIsolate
import borg.trikeshed.vm.Teleported
import borg.trikeshed.lib.toList
import borg.trikeshed.pointcut.VmFacet

/**
 * GraalJS leaf demo: `fib` trained and teleported to a leaf host, a guest→host clock, and the
 * Node.js branch that only exists behind the GraalJS `node` launcher.
 */
object JsLeafDemo {
    const val PROGRAM = "function fib(n){return n<2?n:fib(n-1)+fib(n-2)}\nfunction hot(n){return fib(n)}\n"
    const val ROOT = "fib"
    const val ARG = 20L

    fun leafRun(arg: Long = ARG, measured: Int = 20): LeafDemo.Run =
        LeafDemo.run(VmFacet.GRAAL_JS, PROGRAM, ROOT, arg, measured = measured)

    /** guest → host: a host clock the guest reaches as `host.call("now")`; returns the guest's result and the landed receipt(s). */
    fun hostDelegationDemo(): Pair<Teleported, List<String>> = Hypervisor().use { hv ->
        val id = "js-host"
        hv.spawn(id, VmFacet.GRAAL_JS)
        hv.delegateFrom(id, "now") { Teleported.Num(System.nanoTime()) }
        val result = hv[id].eval("host.call(\"now\")", "host-demo")
        val landed = hv.adapter.landings.toList().filter { it.propertyName == "delegate-from" }.map { "${it.key} = ${it.value}" }
        result to landed
    }

    /**
     * Node.js APIs need the GraalJS node launcher ([ProcessIsolate] with `nodeLauncher`); the in-process
     * engine is ECMAScript only. Returns the platform string, or null — with the exact reason printed — when skipped.
     */
    fun nodeDemo(out: (String) -> Unit = ::println): String? {
        val node = LeafDemo.nodeLauncher()
        if (node == null) { out(LeafDemo.nodeSkipReason()); return null }
        return ProcessIsolate("node", VmFacet.GRAAL_JS, nodeLauncher = node).use { iso ->
            val platform = iso.eval("require('os').platform()", "node-demo")
            out("node launcher $node: require('os').platform() = $platform  alive=${iso.isAlive}")
            (platform as? Teleported.Str)?.v ?: platform.toString()
        }
    }
}

fun main() {
    println(LeafDemo.report(JsLeafDemo.leafRun()))
    val (now, landed) = JsLeafDemo.hostDelegationDemo()
    println("guest→host  host.call(\"now\") = $now")
    landed.forEach { println("  landing   $it") }
    JsLeafDemo.nodeDemo()
}
