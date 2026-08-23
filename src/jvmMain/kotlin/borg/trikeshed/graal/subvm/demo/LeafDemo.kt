package borg.trikeshed.graal.subvm.demo

import borg.trikeshed.graal.subvm.Budget
import borg.trikeshed.graal.subvm.DelegationReceipt
import borg.trikeshed.graal.subvm.GuestBounds
import borg.trikeshed.graal.subvm.Hypervisor
import borg.trikeshed.graal.subvm.InProcessIsolate
import borg.trikeshed.graal.subvm.IsolateStats
import borg.trikeshed.graal.subvm.LeafTrainer
import borg.trikeshed.graal.subvm.Served
import borg.trikeshed.graal.subvm.Teleported
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.toList
import borg.trikeshed.pointcut.VmFacet
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.EnvironmentAccess
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.IOAccess
import org.graalvm.polyglot.management.ExecutionListener
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Reusable leaf-promotion harness: a [Hypervisor], one OWN-trust isolate, one guest root, and the
 * trainer's climb OBSERVED → SELF_CONTAINED → SHADOWED → DELEGATED timed against the guest alone.
 * The guest-alone number comes from a twin hypervisor that observes identically but never promotes
 * (`trainCalls = Int.MAX_VALUE`): a recursive root's transition lands inside warm call #1, so the
 * promoting isolate has no "before promotion" window to time.
 *
 * Nothing is read through private hooks. The hypervisor constructs its [LeafTrainer] internally
 * and lands every receipt and phase transition on the blackboard, so the harness reads them back
 * off [Hypervisor.adapter] landings (`propertyName == "delegate"` / `"phase"`) and rule fires off
 * [Hypervisor.fires]: the report is exactly what the blackboard saw.
 *
 * The guest sequence runs on its own daemon thread under a progress watchdog. A guest call that
 * makes no progress for `stallMillis` is declared stalled: the JVM's deadlock detector and the
 * stuck threads' frames go into [Run.notes], the hypervisor close is attempted on a bounded
 * daemon thread (a `Context.close(true)` cannot cancel a guest parked on a Java monitor), and the
 * run is returned instead of hanging the caller.
 */
object LeafDemo {
    data class Run(
        val facet: VmFacet,
        val root: String,
        /** Average nanos per [Hypervisor.delegateTo] of the guest function itself: `measured` calls after `warm` on a twin isolate whose trainer never promotes. -1 = never measured. */
        val guestNanos: Long,
        /** Average nanos per [Hypervisor.delegateTo] once the root is DELEGATED on the promoting isolate (memo hits). -1 = never measured. */
        val delegatedNanos: Long,
        /** Trainer receipts, re-read from the `delegate` landings; see [parseReceipt] for what survives the round trip. */
        val receipts: List<DelegationReceipt>,
        /** Phase transitions as landed: `"<root> <from>→<to>"`. */
        val transitions: List<String>,
        val landings: Int,
        /** Rule names that fired on the hypervisor's Rete. */
        val fires: List<String>,
        val stats: IsolateStats,
        /** Harness observations: phases at each window, the profile, stalls, guest exceptions, listener probes. */
        val notes: List<String> = emptyList(),
    ) {
        /** Average nanos of the receipts served a given way — the per-crossing cost the trainer itself recorded. */
        fun receiptNanos(served: Served): Long? = receipts.filter { it.served == served }.takeIf { it.isNotEmpty() }?.map { it.nanos }?.average()?.toLong()
    }

    /** A guest call with no progress for this long is a stall (a legitimate interpreted fib(20) is well under it). */
    const val STALL_MILLIS = 10_000L

    /** Longest the harness waits for an asynchronous promotion to land between calls. */
    const val PROMOTION_WAIT_MILLIS = 3_000L

    /** Untimed calls the harness is willing to spend driving SHADOWED/OBSERVED towards DELEGATED. */
    const val MAX_NUDGES = 32

    fun run(
        facet: VmFacet,
        program: String,
        root: String,
        arg: Long,
        warm: Int = 8,
        measured: Int = 20,
        promoteAfter: Long = 8,
        stallMillis: Long = STALL_MILLIS,
    ): Run {
        // the twin observes exactly like [hv] (same listener, trainer, heat facts, delegate-to landings) but can never
        // reach SELF_CONTAINED, so its window is the guest function itself — for a recursive root the promoting
        // hypervisor's transition lands inside warm call #1 and leaves no "before promotion" window of its own
        val twin = Hypervisor(promoteAfter = promoteAfter, trainCalls = Int.MAX_VALUE, shadowCalls = 2)
        val hv = Hypervisor(promoteAfter = promoteAfter, trainCalls = warm, shadowCalls = 2)
        val id = "leaf-${facet.id}"
        val s = Session(twin, hv, id, root, arg, warm, measured)
        s.notes += "root=$root arg=$arg warm=$warm measured=$measured promoteAfter=$promoteAfter shadowCalls=2"

        val worker = Thread({ try { s.drive(program, facet) } catch (t: Throwable) { s.failure = t } }, "leaf-demo-$id").apply { isDaemon = true }
        val stallNanos = TimeUnit.MILLISECONDS.toNanos(stallMillis)
        s.lastProgress = System.nanoTime()
        worker.start()
        var stalled = false
        while (worker.isAlive) {
            worker.join(50)
            if (worker.isAlive && System.nanoTime() - s.lastProgress > stallNanos) {
                stalled = true
                s.notes += "STALL: no guest progress for ${stallMillis}ms in step '${s.step}' phase=${s.phase}"
                s.notes += stuckThreads(worker)
                break
            }
        }
        s.failure?.let { s.notes += "guest thread failed: ${it::class.simpleName}: ${it.message?.lineSequence()?.firstOrNull()}" }

        val landed = hv.adapter.landings.toList()
        val receipts = landed.filter { it.propertyName == "delegate" }.mapNotNull { parseReceipt(id, it.value.toString()) }
        val transitions = landed.filter { it.propertyName == "phase" }.map { "${it.coordinate.methodName} ${it.value}" }
        val fires = hv.fires.map { it.ruleName }
        val stats = runCatching { hv[id].stats() }.getOrDefault(IsolateStats(0, 0, 0, 0, 0, 0, 0, 0))
        val profile = hv.trainer(id)?.profiles?.get(root)
        s.notes += "profile: ${profile?.toString() ?: "<none>"}"
        if (profile != null && profile.phase == LeafTrainer.Phase.DEMOTED) {
            s.notes += "demoted: reason='${profile.demotedReason}' captured characters (${profile.sourceName}:${profile.line}:${profile.column}) = ${profile.characters?.let { "«${it.replace("\n", "\\n")}»" } ?: "<null>"}"
            profile.characters?.let { chars ->
                val rebuilt = runCatching { LeafTrainer.LeafHost(GuestBounds.of(facet), "$id-rebuild", Budget()).use { h -> h.materialize(root, chars); h.call(root, Teleported.Arr(listOf(Teleported.Num(arg)))) } }
                s.notes += "LeafHost rebuilt from those characters: " + rebuilt.fold({ "ok, $root($arg) = $it" }, { "FAILED ${it::class.simpleName}: ${it.message?.lineSequence()?.firstOrNull()}" })
            }
        }
        s.notes += "landings by property: " + landed.groupingBy { it.propertyName }.eachCount().entries
            .sortedByDescending { it.value }.joinToString(" ") { "${it.key}=${it.value}" }
        if (!stalled && stats.rootEnters == 0L && stats.calls > 0) {
            s.notes += "the isolate's listener saw NO roots (rootEnters=0); unfiltered ExecutionListeners on a fresh ${facet.id} context: " +
                listenerProbe(facet, program, root, arg)
            s.notes += "isolate-shaped listener (roots+collectReturnValue+rootNameFilter+onEnter/onReturn), root enters seen: " +
                listOf(false to true, true to true, false to false, true to false).joinToString(" ") { (before, restricted) ->
                    "${if (before) "attach-before-build" else "attach-after-build"}+${if (restricted) "restricted" else "permissive"}=${isolateShapeProbe(facet, program, root, arg, before, restricted)}"
                } + "  (InProcessIsolate = attach-after-build+restricted)"
        }

        s.notes += "twin profile: ${twin.trainer(id)?.profiles?.get(root)?.toString() ?: "<none>"} fires=${twin.fires.size}"

        // a stalled guest is parked on a Java monitor, where Context.close(true) cannot reach it: close on a bounded daemon thread
        val closer = Thread({ runCatching { twin.close() }; runCatching { hv.close() } }, "leaf-demo-close-$id").apply { isDaemon = true }
        closer.start()
        closer.join(TimeUnit.SECONDS.toMillis(if (stalled) 3 else 30))
        if (closer.isAlive) s.notes += "hypervisor.close() did not return within ${if (stalled) 3 else 30}s (Context.close(true) waits for a guest thread that is blocked on a Java monitor); left to the daemon thread"
        return Run(facet, root, s.guestNanos, s.delegatedNanos, receipts, transitions, landed.size, fires, stats, s.notes.toList())
    }

    /** One line per phase transition, receipt counts by [Served], µs per call before/after, fires, landings, notes. */
    fun report(run: Run): String = buildString {
        appendLine("== LeafDemo ${run.facet.id} ${run.root} ==")
        if (run.transitions.isEmpty()) appendLine("  phase     <no transition landed>")
        val grouped = ArrayList<Pair<String, Int>>()
        for (t in run.transitions) {
            if (grouped.isNotEmpty() && grouped.last().first == t) grouped[grouped.lastIndex] = t to grouped.last().second + 1 else grouped += t to 1
        }
        grouped.forEach { (t, n) -> appendLine("  phase     $t${if (n > 1) " ×$n" else ""}") }
        val byServed = Served.entries.joinToString(" ") { s -> "$s=${run.receipts.count { it.served == s }}" }
        appendLine("  receipts  ${run.receipts.size} ($byServed refuted=${run.receipts.count { it.refuted }})")
        val speedup = if (run.guestNanos > 0 && run.delegatedNanos > 0) "×%.1f".format(run.guestNanos.toDouble() / run.delegatedNanos) else "n/a"
        appendLine("  µs/call   guest=${micros(run.guestNanos)} delegated=${micros(run.delegatedNanos)} speedup=$speedup")
        val perServed = Served.entries.mapNotNull { s -> run.receiptNanos(s)?.let { "$s=${micros(it)}" } }
        appendLine("  µs/receipt ${if (perServed.isEmpty()) "<none>" else perServed.joinToString(" ")}")
        appendLine("  fires     ${run.fires.groupingBy { it }.eachCount().entries.joinToString(" ") { "${it.key}×${it.value}" }.ifEmpty { "<none>" }}")
        appendLine("  landings  ${run.landings}")
        appendLine("  stats     ${run.stats}")
        run.notes.forEach { appendLine("  note      $it") }
    }

    private fun micros(nanos: Long) = if (nanos < 0) "unmeasured" else "%.1f".format(nanos / 1000.0)

    // ── the guest-side sequence, one thread, one isolate ───────────────────
    private class Session(val twin: Hypervisor, val hv: Hypervisor, val id: String, val root: String, val arg: Long, val warm: Int, val measured: Int) {
        @Volatile var guestNanos = -1L
        @Volatile var delegatedNanos = -1L
        @Volatile var lastProgress = 0L
        @Volatile var step = "start"
        @Volatile var failure: Throwable? = null
        val notes = CopyOnWriteArrayList<String>()
        var settled = 0

        fun phaseIn(h: Hypervisor): LeafTrainer.Phase? = h.trainer(id)?.profiles?.get(root)?.phase
        val phase: LeafTrainer.Phase? get() = phaseIn(hv)

        private fun at(step: String) { this.step = step; lastProgress = System.nanoTime() }

        fun call(h: Hypervisor): Teleported {
            val r = h.delegateTo(id, root, Teleported.Num(arg))
            lastProgress = System.nanoTime()
            return r
        }

        /** A promotion/demotion the Rete queued for the guest thread lands at the next safe point; idle, that is [InProcessIsolate.settle]. */
        fun settle(): Boolean {
            val iso = runCatching { hv[id] }.getOrNull() as? InProcessIsolate ?: return false
            if (iso.pendingGuestActions == 0) return false
            iso.settle(); lastProgress = System.nanoTime(); return true
        }

        fun drive(program: String, facet: VmFacet) {
            // 1. the guest alone: same instrumentation and landings, trainer observing but never promoting
            at("twin-spawn"); twin.spawn(id, facet); twin[id].eval(program, "leaf-demo")
            at("twin-warm"); repeat(warm) { call(twin) }
            at("guest-window")
            guestNanos = time(twin, measured)
            notes += "guest window ($measured calls on the never-promoting twin): phase=${phaseIn(twin)}"

            // 2. the promoting hypervisor: warm calls train it, the Rete promotes it, memo serves it
            at("spawn"); hv.spawn(id, facet); hv[id].eval(program, "leaf-demo")
            at("warm")
            var waitedForPromotion = false
            repeat(warm) {
                call(hv)
                // a promotion fires on the Rete thread and is queued for the guest thread's next safe point; give it
                // one now (settle) instead of letting it land inside the next call
                if (!waitedForPromotion && phase == LeafTrainer.Phase.SELF_CONTAINED) {
                    waitedForPromotion = true
                    val landed = awaitPhase(PROMOTION_WAIT_MILLIS) { it != LeafTrainer.Phase.SELF_CONTAINED }
                    notes += "warm call ${it + 1}: SELF_CONTAINED at a call boundary; promotion ${if (landed) "landed → $phase" else "did not land within ${PROMOTION_WAIT_MILLIS}ms"}${if (settled > 0) " (settled $settled queued guest action(s))" else ""}"
                }
            }
            notes += "after $warm warm calls: phase=$phase"
            at("await-delegated")
            awaitDelegated(PROMOTION_WAIT_MILLIS)
            notes += "await DELEGATED: phase=$phase"
            at("delegated-window")
            delegatedNanos = time(hv, measured)
            notes += "delegated window ($measured calls): phase=$phase"
            at("done")
        }

        private fun time(h: Hypervisor, n: Int): Long { val t0 = System.nanoTime(); repeat(n) { call(h) }; return (System.nanoTime() - t0) / n }

        private fun awaitPhase(millis: Long, until: (LeafTrainer.Phase?) -> Boolean): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
            while (System.nanoTime() < deadline) { if (settle()) settled++; if (until(phase)) return true; Thread.sleep(5) }
            return until(phase)
        }

        /** Reach DELEGATED: SHADOWED/OBSERVED need calls (untimed nudges, capped); SELF_CONTAINED needs a safe point. */
        private fun awaitDelegated(millis: Long) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
            var nudges = 0
            while (System.nanoTime() < deadline) {
                when (phase) {
                    null -> { notes += "await DELEGATED: '$root' has no profile — the listener never observed it; nothing to train"; break }
                    LeafTrainer.Phase.DELEGATED, LeafTrainer.Phase.DEMOTED -> break
                    LeafTrainer.Phase.SELF_CONTAINED -> { if (settle()) settled++ else Thread.sleep(5) }
                    else -> { if (nudges >= MAX_NUDGES) break; call(hv); nudges++ }
                }
            }
            if (nudges > 0) notes += "await DELEGATED: $nudges untimed nudge call(s)"
        }
    }

    // ── receipts come back off the blackboard as their toString ─────────────
    private val RECEIPT = Regex("""^delegate\[(\w+)] (\S+) args=([0-9a-f]+) → ([0-9a-f]+) (-?\d+)µs seq=(\d+)( REFUTED)?$""")

    /**
     * Inverse of [DelegationReceipt.toString], which is what the hypervisor lands. Served, root,
     * seq and refuted survive exactly; nanos survive at µs resolution; the cids survive as their
     * 12-hex prefix, zero-padded back to a well-formed [ContentId].
     */
    fun parseReceipt(isolate: String, landed: String): DelegationReceipt? {
        val m = RECEIPT.matchEntire(landed) ?: return null
        val (served, root, argsHex, resultHex, micros, seq, refuted) = m.destructured
        return DelegationReceipt(
            isolate = isolate, root = root,
            argsCid = cidOfPrefix(argsHex), resultCid = cidOfPrefix(resultHex),
            served = Served.valueOf(served), nanos = micros.toLong() * 1_000, seq = seq.toInt(), refuted = refuted.isNotEmpty(),
        )
    }

    private fun cidOfPrefix(hex: String) = ContentId("sha256:" + hex.take(64).padEnd(64, '0'))

    // ── diagnostics ──────────────────────────────────────────────────────────

    /**
     * What UNFILTERED [ExecutionListener]s (roots / statements / expressions) on a fresh context of
     * this facet report for `program` + one `root(arg)` call. Explains an isolate whose listener saw
     * nothing: no root events at all is an instrumentation bound of the language, root events with
     * names the facet's `rootNameNoise` drops is a filter bound.
     */
    fun listenerProbe(facet: VmFacet, program: String, root: String, arg: Long): String {
        val bounds = GuestBounds.of(facet)
        val roots = LinkedHashMap<String, Int>()
        val statements = LinkedHashMap<String, Int>()
        var expressions = 0L
        // the isolate's own listener shape, split into its two extra options so the blind one shows
        val filterAsked = LinkedHashMap<String, Int>()
        val filteredEnters = LinkedHashMap<String, Int>()
        val returnsWithValue = LinkedHashMap<String, Int>()
        val engine = Engine.newBuilder().option("engine.WarnInterpreterOnly", "false").build()
        val listeners = listOf(
            ExecutionListener.newBuilder().roots(true).onEnter { ev -> roots.merge(ev.rootName ?: "<null>", 1, Int::plus) }.attach(engine),
            ExecutionListener.newBuilder().statements(true).onEnter { ev -> statements.merge(ev.rootName ?: "<null>", 1, Int::plus) }.attach(engine),
            ExecutionListener.newBuilder().expressions(true).onEnter { expressions++ }.attach(engine),
            ExecutionListener.newBuilder().roots(true)
                .rootNameFilter { name -> filterAsked.merge(name ?: "<null>", 1, Int::plus); !bounds.rootNameNoise(name) }
                .onEnter { ev -> filteredEnters.merge(ev.rootName ?: "<null>", 1, Int::plus) }.attach(engine),
            ExecutionListener.newBuilder().roots(true).collectReturnValue(true)
                .onReturn { ev -> returnsWithValue.merge(ev.rootName ?: "<null>", 1, Int::plus) }.attach(engine),
        )
        var failure: String? = null
        try {
            Context.newBuilder(bounds.languageId).engine(engine).allowHostAccess(HostAccess.NONE).build().use { ctx ->
                ctx.eval(bounds.languageId, program)
                ctx.getBindings(bounds.languageId).getMember(root).execute(arg)
            }
        } catch (t: Throwable) {
            failure = t.message?.lineSequence()?.firstOrNull() ?: t.toString()
        } finally {
            listeners.forEach { runCatching { it.close() } }
            runCatching { engine.close(true) }
        }
        fun fmt(m: Map<String, Int>) = if (m.isEmpty()) "none" else "${m.values.sum()} events in ${m.size} root(s) " +
            m.entries.take(12).joinToString(" ") { "${it.key}×${it.value}" }
        return "roots=${fmt(roots)}; statements=${fmt(statements)}; expressions=$expressions; " +
            "rootNameNoise keeps ${roots.keys.filterNot(bounds.rootNameNoise).take(12)}; " +
            "with the isolate's rootNameFilter: asked about ${fmt(filterAsked)} → enters ${fmt(filteredEnters)}; " +
            "with collectReturnValue(true): returns ${fmt(returnsWithValue)}" + (failure?.let { "; probe failed: $it" } ?: "")
    }

    /**
     * The isolate's exact listener shape against a context built the way [InProcessIsolate] builds
     * it; returns "enters/returns" that passed the facet's noise filter, or "failed: …".
     * [attachBeforeBuild] flips the one ordering InProcessIsolate fixes (context first, listener
     * second); [restricted] toggles its allow-nothing context options.
     */
    fun isolateShapeProbe(facet: VmFacet, program: String, root: String, arg: Long, attachBeforeBuild: Boolean, restricted: Boolean): String {
        val bounds = GuestBounds.of(facet)
        var enters = 0; var returns = 0; var nullNames = 0
        val engine = Engine.newBuilder().option("engine.WarnInterpreterOnly", "false").build()
        fun attach(): ExecutionListener = ExecutionListener.newBuilder().roots(true).collectReturnValue(true)
            .rootNameFilter { !bounds.rootNameNoise(it) }
            .onEnter { ev -> if (ev.rootName == null) nullNames++ else enters++ }
            .onReturn { returns++ }
            .attach(engine)
        fun build(): Context {
            val b = Context.newBuilder(bounds.languageId).engine(engine).allowHostAccess(HostAccess.NONE)
            if (restricted) b.allowHostClassLookup { false }.allowIO(IOAccess.NONE).allowCreateThread(false).allowNativeAccess(false)
                .allowCreateProcess(false).allowEnvironmentAccess(EnvironmentAccess.NONE).allowPolyglotAccess(PolyglotAccess.NONE)
            return b.build()
        }
        var listener: ExecutionListener? = null
        var ctx: Context? = null
        try {
            if (attachBeforeBuild) { listener = attach(); ctx = build() } else { ctx = build(); listener = attach() }
            ctx.eval(Source.newBuilder(bounds.languageId, program, "leaf-demo").buildLiteral())
            ctx.getBindings(bounds.languageId).getMember(root).execute(arg)
        } catch (t: Throwable) {
            return "failed: ${t.message?.lineSequence()?.firstOrNull()}"
        } finally {
            runCatching { ctx?.close(true) }; runCatching { listener?.close() }; runCatching { engine.close(true) }
        }
        return "$enters/$returns${if (nullNames > 0) " (+$nullNames unnamed)" else ""}"
    }

    private fun stuckThreads(worker: Thread): List<String> {
        val out = ArrayList<String>()
        val mx = ManagementFactory.getThreadMXBean()
        val dead = mx.findDeadlockedThreads()
        if (dead != null) {
            out += "JVM deadlock detector: ${dead.size} deadlocked threads"
            for (ti in mx.getThreadInfo(dead, true, true)) {
                out += "  ${ti.threadName} [${ti.threadState}] waits for ${ti.lockName} held by ${ti.lockOwnerName}: ${frames(ti.stackTrace)}"
            }
        }
        out += "guest thread ${worker.name} [${worker.state}]: ${frames(worker.stackTrace)}"
        for ((t, st) in Thread.getAllStackTraces()) {
            if (t !== worker && dead?.contains(t.threadId()) != true && st.any { it.className.startsWith("borg.trikeshed.graal.subvm") }) {
                out += "thread ${t.name} [${t.state}]: ${frames(st)}"
            }
        }
        return out
    }

    private fun frames(st: Array<StackTraceElement>) = st
        .filter { it.className.startsWith("borg.trikeshed") }
        .take(6).joinToString(" ← ") { "${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})" }

    // ── GraalJS node launcher discovery ─────────────────────────────────────

    /**
     * `$GRAALVM_HOME/bin/node`, else the `node` on PATH — but only when it is the GraalJS launcher:
     * its `--version` mentions graal, or it answers `--version:graalvm` (a stock node rejects that
     * flag with a non-zero exit). Anything else is a plain Node.js and returns null.
     */
    fun nodeLauncher(): File? {
        val candidates = ArrayList<File>()
        System.getenv("GRAALVM_HOME")?.takeIf { it.isNotBlank() }?.let { candidates += File(it, "bin/node") }
        candidates += File(System.getProperty("java.home"), "bin/node")
        systemNode()?.let { candidates += it }
        return candidates.firstOrNull { it.isFile && it.canExecute() && isGraalNode(it) }
    }

    /** The `node` on PATH, GraalJS or not. */
    fun systemNode(): File? = System.getenv("PATH")?.split(File.pathSeparator)
        ?.map { File(it, "node") }?.firstOrNull { it.isFile && it.canExecute() }

    fun nodeVersion(launcher: File): String? = exec(launcher.path, "--version")

    fun nodeSkipReason(): String {
        val sys = systemNode()
        val version = sys?.let { nodeVersion(it) }
        return "node demo skipped: Node.js APIs (require, process, the event loop) need the GraalJS `node` launcher " +
            "(\$GRAALVM_HOME/bin/node, or a `node` on PATH whose --version says graal); the in-process JS engine is ECMAScript only. " +
            "GRAALVM_HOME=${System.getenv("GRAALVM_HOME")?.takeIf { it.isNotBlank() } ?: "<unset>"}, " +
            "PATH node=${sys?.path ?: "<none>"}${if (version != null) " ($version, not GraalJS)" else ""}"
    }

    private fun isGraalNode(launcher: File): Boolean {
        val v = exec(launcher.path, "--version") ?: return false
        if (v.contains("graal", ignoreCase = true)) return true
        return exec(launcher.path, "--version:graalvm")?.contains("graal", ignoreCase = true) == true
    }

    /** stdout+stderr of a short command on exit 0, null on failure/timeout. */
    private fun exec(vararg cmd: String, timeoutMillis: Long = 5_000): String? = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val buf = ByteArrayOutputStream()
        val pump = Thread({ runCatching { p.inputStream.copyTo(buf) } }, "leaf-demo-exec").apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); return@runCatching null }
        pump.join(1_000)
        if (p.exitValue() == 0) buf.toString(Charsets.UTF_8).trim() else null
    }.getOrNull()
}
