package borg.trikeshed.graal.subvm.harness

import borg.trikeshed.graal.subvm.Budget
import borg.trikeshed.graal.subvm.GuestException
import borg.trikeshed.graal.subvm.GuestFailure
import borg.trikeshed.graal.subvm.Hypervisor
import borg.trikeshed.graal.subvm.InProcessIsolate
import borg.trikeshed.graal.subvm.LeafTrainer
import borg.trikeshed.graal.subvm.ProcessIsolate
import borg.trikeshed.graal.subvm.Served
import borg.trikeshed.vm.Teleported
import borg.trikeshed.pointcut.VmFacet
import java.io.File

/**
 * The capability matrix is MEASURED: every cell is a [Probe] run on a real host and recorded with
 * its evidence. Declaring a capability is not allowed — a row that cannot be probed on a host is
 * [Verdict.ABSENT] with the reason, never blank.
 *
 * Rows are the capabilities the sub-VM depends on; columns are hosts ([Host]). The same probe set
 * runs under the JVM harness, inside a `native-image` binary (macOS / Linux), and — for the JS
 * column — under the GraalJS `node` launcher when one is installed.
 */
enum class Verdict { OK, ABSENT, BOUNDED, FAILED }

data class Cell(val probe: String, val verdict: Verdict, val evidence: String, val micros: Long)

data class Host(val name: String, val os: String, val arch: String, val runtime: String, val vmName: String, val nativeImage: Boolean) {
    override fun toString() = "$name ($os/$arch, $runtime${if (nativeImage) ", native-image" else ""})"

    companion object {
        fun current(name: String? = null): Host {
            val native = System.getProperty("org.graalvm.nativeimage.imagecode") != null
            val os = System.getProperty("os.name").lowercase().let { when { it.contains("mac") -> "macos"; it.contains("linux") -> "linux"; it.contains("win") -> "windows"; else -> it } }
            val arch = System.getProperty("os.arch").let { if (it == "aarch64" || it == "arm64") "arm64" else it }
            val runtime = if (native) "native" else "jvm"
            return Host(name ?: "$runtime-$os-$arch", os, arch, runtime, System.getProperty("java.vm.name") ?: "?", native)
        }
    }
}

/** One measurement. [run] returns the evidence string; throwing = FAILED; returning null = ABSENT with the message. */
class Probe(val id: String, val group: String, val run: () -> Pair<Verdict, String>)

object Capabilities {
    const val FIB_JS = "function fib(n){return n<2?n:fib(n-1)+fib(n-2)}"
    const val FIB_PY = "def fib(n):\n    return n if n < 2 else fib(n-1) + fib(n-2)\n"

    private fun ok(e: String) = Verdict.OK to e
    private fun bounded(e: String) = Verdict.BOUNDED to e
    private fun absent(e: String) = Verdict.ABSENT to e

    private fun hasLanguage(id: String): Boolean = runCatching {
        org.graalvm.polyglot.Engine.create().use { it.languages.containsKey(id) }
    }.getOrDefault(false)

    private fun guestProbe(lang: String, facet: VmFacet, program: String, expect: Long): List<Probe> {
        val L = lang.uppercase()
        return listOf(
            Probe("$lang.eval", "guest:$lang") {
                if (!hasLanguage(lang)) return@Probe absent("language '$lang' not in this engine")
                InProcessIsolate("cap-$lang", facet).use { iso -> iso.eval(program); ok("fib(15)=${iso.call("fib", Teleported.Num(15))}") }
            },
            Probe("$lang.host-delegate", "guest:$lang") {
                if (!hasLanguage(lang)) return@Probe absent("no $lang")
                InProcessIsolate("cap-$lang", facet).use { iso ->
                    iso.delegate("double") { a -> Teleported.Num(2 * (a.first() as Teleported.Num).v) }
                    ok("host.call('double',21)=" + iso.eval(if (lang == "js") "host.call('double', 21)" else "host.call('double', 21)"))
                }
            },
            Probe("$lang.statement-limit", "guest:$lang") {
                if (!hasLanguage(lang)) return@Probe absent("no $lang")
                val b = borg.trikeshed.graal.subvm.GuestBounds.ofLanguage(lang)
                if (!b.statementLimitSafe) return@Probe bounded("statementLimit unsafe on $L (GIL assert) → stop=${b.stop}")
                InProcessIsolate("cap-$lang", facet, Budget(statements = 20_000)).use { iso ->
                    try { iso.eval(if (lang == "js") "while(true){}" else "while True:\n    pass"); Verdict.FAILED to "limit not enforced" }
                    catch (e: GuestException) { if (e.kind == GuestFailure.EXHAUSTED) ok("EXHAUSTED after 20k statements; alive=${iso.isAlive}") else Verdict.FAILED to e.toString() }
                }
            },
            Probe("$lang.wall-interrupt", "guest:$lang") {
                if (!hasLanguage(lang)) return@Probe absent("no $lang")
                InProcessIsolate("cap-$lang", facet, Budget(statements = 0, wallMillis = 400)).use { iso ->
                    val t0 = System.nanoTime()
                    try { iso.eval(if (lang == "js") "while(true){}" else "while True:\n    pass"); Verdict.FAILED to "not interrupted" }
                    catch (e: GuestException) {
                        val ms = (System.nanoTime() - t0) / 1_000_000
                        if (e.kind == GuestFailure.INTERRUPTED || e.kind == GuestFailure.EXHAUSTED) ok("${e.kind} after ${ms}ms; alive=${iso.isAlive}") else Verdict.FAILED to e.toString()
                    }
                }
            },
            Probe("$lang.root-observation", "guest:$lang") {
                if (!hasLanguage(lang)) return@Probe absent("no $lang")
                var roots = 0; var self = 0
                InProcessIsolate("cap-$lang", facet) { o -> if (o.root == "fib") { roots++; if (o.selfContained) self++ } }.use { iso ->
                    iso.eval(program); iso.call("fib", Teleported.Num(10))
                }
                val how = if (borg.trikeshed.graal.subvm.GuestBounds.ofLanguage(lang).rootEventsObservable) "listener" else "binding pointcuts"
                if (roots > 0) ok("$roots fib frames via $how, $self self-contained") else Verdict.FAILED to "no observations via $how"
            },
            Probe("$lang.leaf-delegation", "guest:$lang") {
                if (!hasLanguage(lang)) return@Probe absent("no $lang")
                Hypervisor(promoteAfter = 4, trainCalls = 4, shadowCalls = 2).use { hv ->
                    val iso = hv.spawn("cap-$lang", facet)
                    iso.eval(program)
                    repeat(12) { hv.delegateTo("cap-$lang", "fib", Teleported.Num(14)) }
                    val deadline = System.nanoTime() + 3_000_000_000L
                    while (System.nanoTime() < deadline && hv.trainer("cap-$lang")?.profiles?.get("fib")?.phase != LeafTrainer.Phase.DELEGATED) { hv.delegateTo("cap-$lang", "fib", Teleported.Num(14)); Thread.sleep(20) }
                    val p = hv.trainer("cap-$lang")!!.profiles["fib"]!!
                    val served = hv.receipts.filter { it.root == "fib" }.groupingBy { it.served }.eachCount()
                    if (p.phase == LeafTrainer.Phase.DELEGATED && (served[Served.MEMO] ?: 0) > 0) ok("phase=${p.phase} served=$served fires=${hv.fires.size}")
                    else Verdict.FAILED to "phase=${p.phase} served=$served reason=${p.demotedReason}"
                }
            },
            Probe("$lang.process-wall", "guest:$lang") {
                if (!hasLanguage(lang)) return@Probe absent("no $lang")
                if (Host.current().nativeImage) return@Probe absent("child JVM spawn needs a JDK; native-image host has none on the classpath")
                ProcessIsolate("cap-$lang-p", facet).use { iso ->
                    iso.eval(program)
                    iso.delegate("double") { a -> Teleported.Num(2 * (a.first() as Teleported.Num).v) }
                    ok("child pid alive=${iso.isAlive}; fib(10)=${iso.call("fib", Teleported.Num(10))}; host.call across wall=" + iso.eval("host.call('double', 4)"))
                }
            },
        )
    }

    val all: List<Probe> by lazy {
        listOf(
            Probe("host.runtime", "host") { ok(Host.current().toString() + " vm=" + System.getProperty("java.vm.name") + " jit=" + (System.getProperty("jvmci.Compiler") ?: "default")) },
            Probe("host.truffle-engine", "host") {
                runCatching { org.graalvm.polyglot.Engine.create().use { e -> ok("engine ${e.version} languages=${e.languages.keys.sorted()} instruments=${e.instruments.keys.sorted().take(6)}") } }
                    .getOrElse { Verdict.FAILED to it.toString() }
            },
            Probe("host.execution-listener", "host") {
                runCatching {
                    org.graalvm.polyglot.Engine.create().use { e -> if (e.instruments.containsKey("execution-listener")) ok("instrument present") else absent("no execution-listener instrument (native-image without -H:+IncludeInstruments?)") }
                }.getOrElse { Verdict.FAILED to it.toString() }
            },
            Probe("host.native-image-tool", "host") {
                val ni = File(System.getProperty("java.home"), "bin/native-image")
                if (ni.exists()) ok(ni.path) else absent("no native-image in \${java.home}/bin")
            },
            Probe("host.graaljs-node", "host") {
                val node = sequenceOf(System.getenv("GRAALVM_HOME")?.let { File(it, "bin/node") }, File(System.getProperty("java.home"), "bin/node"))
                    .filterNotNull().firstOrNull { it.exists() }
                if (node != null) ok(node.path) else absent("no GraalJS node launcher (PATH node is stock Node.js, no Truffle); Node APIs cell = absent")
            },
            Probe("host.llvm-language", "host") { if (hasLanguage("llvm")) ok("llvm (Sulong) available") else absent("llvm-community not on this engine") },
        ) + guestProbe("js", VmFacet.GRAAL_JS, FIB_JS, 610) + guestProbe("python", VmFacet.GRAAL_PYTHON, FIB_PY, 610)
    }
}
