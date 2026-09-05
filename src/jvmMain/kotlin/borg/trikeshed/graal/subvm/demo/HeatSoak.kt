package borg.trikeshed.graal.subvm.demo

import borg.trikeshed.graal.subvm.Budget
import borg.trikeshed.graal.subvm.GuestIsolate
import borg.trikeshed.graal.subvm.Hypervisor
import borg.trikeshed.graal.subvm.LeafTrainer
import borg.trikeshed.vm.Teleported
import borg.trikeshed.pointcut.VmFacet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Heat soak: hold the hypervisor at temperature and watch what drifts.
 *
 * Phase 1 — SOAK: N isolates (JS + Python), each with hot and warm roots, driven at full rate by one
 *   thread per isolate (the sequential-access bound) with VARYING arguments, so memo tables, receipt
 *   logs, landings, Rete facts and leaf-host threads all get exercised the way a long-lived daemon would.
 * Phase 2 — HOT ZONES: read the heatmap, take the top-K zones by heat, and hammer only those for the
 *   remaining time — the "heat soak of the heatmap hot zones".
 *
 * Every second a [Sample] is taken: throughput, p50/p99 per zone, heap, live sub-VM threads, receipt
 * log size, landings, blackboard keys, largest memo. The [Report] compares the first and last thirds of
 * each phase: latency drift, heap drift, thread drift, phase regressions (a DELEGATED root must stay
 * DELEGATED; a DEMOTED root is a finding), and the cap invariants (receipt log ≤ cap, memo ≤ cap).
 */
object HeatSoak {
    data class Zone(val isolate: String, val facet: VmFacet, val root: String, val args: (Long) -> Teleported) {
        val key get() = "$isolate/$root"
    }

    data class ZoneHeat(val zone: String, val calls: Long, val p50us: Double, val p99us: Double, val phase: String, val memo: Int)

    data class Sample(
        val t: Int, val phase: String, val callsPerSec: Long, val heapMb: Long, val subVmThreads: Int,
        val receipts: Int, val landings: Int, val blackboardKeys: Int, val memoMax: Int, val fires: Int,
        val zones: List<ZoneHeat>,
    )

    data class Report(
        val samples: List<Sample>, val hotZones: List<String>, val heatmap: List<ZoneHeat>,
        val findings: List<String>, val refutations: Long, val demoted: List<String>, val text: String,
    )

    private class Lat(cap: Int = 1 shl 14) {
        private val buf = LongArray(cap); private var n = 0; private var i = 0
        @Synchronized fun add(ns: Long) { buf[i] = ns; i = (i + 1) % buf.size; if (n < buf.size) n++ }
        @Synchronized fun pct(p: Double): Double { if (n == 0) return 0.0; val c = buf.copyOf(n); c.sort(); return c[((n - 1) * p).toInt()] / 1000.0 }
        @Synchronized fun reset() { n = 0; i = 0 }
    }

    const val JS_PROGRAM = """
        function fib(n){return n<2?n:fib(n-1)+fib(n-2)}
        function work(n){let s=0;for(let i=0;i<n*200;i++){s=(s*31+i)|0}return s}
        function strs(n){let s='';for(let i=0;i<n;i++){s+=String.fromCharCode(97+(i%26))}return s}
        function arr(n){const a=[];for(let i=0;i<n;i++)a.push(i*i);return a}
        function impure(n){return host.call('tick', n)}
    """
    const val PY_PROGRAM = "def fib(n):\n    return n if n < 2 else fib(n-1) + fib(n-2)\ndef work(n):\n    s = 0\n    for i in range(n*200):\n        s = (s*31 + i) & 0xffffffff\n    return s\ndef strs(n):\n    return ''.join(chr(97 + (i % 26)) for i in range(n))\ndef impure(n):\n    return host.call('tick', n)\n"

    fun run(
        seconds: Int = 20,
        isolates: Int = 4,
        hotZones: Int = 3,
        promoteAfter: Long = 8,
        out: (String) -> Unit = { println(it) },
    ): Report {
        require(seconds >= 6) { "a soak needs at least 6s (two phases × three thirds)" }
        val hv = Hypervisor(promoteAfter = promoteAfter, trainCalls = promoteAfter.toInt(), shadowCalls = 2)
        val zones = ArrayList<Zone>()
        val lat = ConcurrentHashMap<String, Lat>()
        val calls = ConcurrentHashMap<String, AtomicLong>()
        val ticks = AtomicLong()
        try {
            repeat(isolates) { k ->
                val js = k % 2 == 0
                val id = if (js) "js$k" else "py$k"
                val facet = if (js) VmFacet.GRAAL_JS else VmFacet.GRAAL_PYTHON
                val iso = hv.spawn(id, facet, budget = Budget(statements = 50_000_000, wallMillis = 10_000))
                hv.delegateFrom(id, "tick") { a -> ticks.incrementAndGet(); a.first() }
                iso.eval(if (js) JS_PROGRAM else PY_PROGRAM, "$id.soak")
                zones += Zone(id, facet, "fib") { i -> Teleported.Num(12 + (i % 9)) }          // 12..20: varying args
                zones += Zone(id, facet, "work") { i -> Teleported.Num(1 + (i % 50)) }
                zones += Zone(id, facet, "strs") { i -> Teleported.Num(1 + (i % 64)) }
                if (js) zones += Zone(id, facet, "arr") { i -> Teleported.Num(1 + (i % 32)) }
                zones += Zone(id, facet, "impure") { i -> Teleported.Num(i % 7) }               // never promotable: host call
            }
            zones.forEach { lat[it.key] = Lat(); calls[it.key] = AtomicLong() }

            val samples = ArrayList<Sample>()
            val soakSeconds = seconds / 2
            val hotSeconds = seconds - soakSeconds

            // ── phase 1: soak everything ───────────────────────────────
            val phase1 = drive(hv, zones.groupBy { it.isolate }, lat, calls, soakSeconds, "soak", samples, out)
            val heatmap = heatmapOf(hv, zones, lat, calls)
            val hot = heatmap.filter { !it.zone.endsWith("/impure") }.sortedByDescending { it.calls }.take(hotZones).map { it.zone }
            out("── hot zones (top $hotZones by heat, excluding impure): $hot")
            lat.values.forEach { it.reset() }

            // ── phase 2: hammer the hot zones only ─────────────────────
            val hotOnly = zones.filter { it.key in hot }.groupBy { it.isolate }
            drive(hv, hotOnly, lat, calls, hotSeconds, "hot", samples, out)
            val heatmap2 = heatmapOf(hv, zones, lat, calls)

            // ── findings ───────────────────────────────────────────────
            val findings = ArrayList<String>()
            // drift windows: first vs last third of the STEADY part of a phase — the first seconds are JIT and
            // promotion warm-up (throughput ramps 10k → 500k/s), not temperature
            fun thirds(ph: String): Pair<List<Sample>, List<Sample>> {
                val all = samples.filter { it.phase == ph }
                val s = if (all.size >= 9) all.drop(3) else all
                val k = maxOf(1, s.size / 3); return s.take(k) to s.takeLast(k)
            }
            for (ph in listOf("soak", "hot")) {
                val (a, b) = thirds(ph)
                if (a.isEmpty() || b.isEmpty()) continue
                val heapA = a.map { it.heapMb }.average(); val heapB = b.map { it.heapMb }.average()
                if (heapB > heapA * 1.5 && heapB - heapA > 64) findings += "$ph: heap drift ${heapA.toInt()}MB → ${heapB.toInt()}MB"
                val thA = a.map { it.subVmThreads }.average(); val thB = b.map { it.subVmThreads }.average()
                if (thB > thA + 4) findings += "$ph: sub-VM thread drift ${thA.toInt()} → ${thB.toInt()}"
                val tpA = a.map { it.callsPerSec }.average(); val tpB = b.map { it.callsPerSec }.average()
                if (tpA > 0 && tpB < tpA * 0.5) findings += "$ph: throughput collapse ${tpA.toInt()}/s → ${tpB.toInt()}/s"
                for (z in hot) {
                    val pA = a.mapNotNull { s -> s.zones.find { it.zone == z }?.p99us }.average()
                    val pB = b.mapNotNull { s -> s.zones.find { it.zone == z }?.p99us }.average()
                    if (pA > 0 && pB > pA * 3 && pB - pA > 200) findings += "$ph: p99 drift $z ${"%.0f".format(pA)}µs → ${"%.0f".format(pB)}µs"
                }
            }
            val lastSample = samples.last()
            if (lastSample.receipts > Hypervisor.RECEIPT_LOG_CAP) findings += "receipt log over cap: ${lastSample.receipts}"
            if (lastSample.memoMax > LeafTrainer.MEMO_CAP) findings += "memo over cap: ${lastSample.memoMax}"
            val demoted = zones.mapNotNull { z -> hv.trainer(z.isolate)?.profiles?.get(z.root)?.takeIf { it.phase == LeafTrainer.Phase.DEMOTED }?.let { "${z.key}: ${it.demotedReason}" } }
            demoted.forEach { findings += "demoted: $it" }
            val notDelegated = hot.filter { z -> val (iso, root) = z.split('/'); hv.trainer(iso)?.profiles?.get(root)?.phase != LeafTrainer.Phase.DELEGATED }
            if (notDelegated.isNotEmpty()) findings += "hot zones not DELEGATED at the end: $notDelegated"
            val refutations = hv.receipts.count { it.refuted }.toLong()
            if (refutations > 0) findings += "refuted receipts: $refutations"

            val histo = if (System.getProperty("subvm.soak.histo") == "true" || System.getenv("SUBVM_SOAK_HISTO") == "true") classHistogram(25) else ""
            val text = render(samples, heatmap2, hot, findings, phase1, ticks.get(), hv) + histo
            out(text)
            return Report(samples, hot, heatmap2, findings, refutations, demoted, text)
        } finally {
            hv.close()
        }
    }

    private fun drive(
        hv: Hypervisor, byIsolate: Map<String, List<Zone>>, lat: Map<String, Lat>, calls: Map<String, AtomicLong>,
        seconds: Int, phase: String, samples: MutableList<Sample>, out: (String) -> Unit,
    ): Long {
        val stop = AtomicBoolean(false)
        val total = AtomicLong()
        val drivers = byIsolate.map { (iso, zs) ->
            Thread({
                var i = 0L
                while (!stop.get()) {
                    val z = zs[(i % zs.size).toInt()]
                    val t0 = System.nanoTime()
                    try { hv.delegateTo(iso, z.root, z.args(i)) } catch (t: Throwable) { /* counted as a finding through phases/receipts */ }
                    lat.getValue(z.key).add(System.nanoTime() - t0); calls.getValue(z.key).incrementAndGet(); total.incrementAndGet()
                    i++
                }
            }, "soak-driver-$iso").apply { isDaemon = true }
        }
        drivers.forEach { it.start() }
        var last = 0L
        repeat(seconds) { s ->
            Thread.sleep(1000)
            val now = total.get()
            val sample = sample(hv, s + 1, phase, now - last, lat, calls); last = now
            samples += sample
            out("  [$phase t=${sample.t}s] ${sample.callsPerSec}/s heap=${sample.heapMb}MB threads=${sample.subVmThreads} receipts=${sample.receipts} landings=${sample.landings} bb=${sample.blackboardKeys} memoMax=${sample.memoMax} fires=${sample.fires}")
        }
        stop.set(true); drivers.forEach { it.join(15_000) }
        return total.get()
    }

    /**
     * Live set, not garbage: the heap's usage *after the last collection* summed over the heap pools.
     * `totalMemory - freeMemory` at 500k allocations/s mostly measures how lazy the collector is being.
     */
    fun liveHeapMb(): Long {
        val pools = java.lang.management.ManagementFactory.getMemoryPoolMXBeans().filter { it.type == java.lang.management.MemoryType.HEAP }
        val afterGc = pools.mapNotNull { it.collectionUsage?.used }
        val bytes = if (afterGc.isNotEmpty() && afterGc.sum() > 0) afterGc.sum() else Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        return bytes / (1024 * 1024)
    }

    private fun sample(hv: Hypervisor, t: Int, phase: String, cps: Long, lat: Map<String, Lat>, calls: Map<String, AtomicLong>): Sample {
        val snap = hv.snapshot()
        @Suppress("UNCHECKED_CAST") val profiles = snap["profiles"] as Map<String, List<String>>
        val memoMax = profiles.values.flatten().maxOfOrNull { line -> Regex("memo=(\\d+)").find(line)?.groupValues?.get(1)?.toInt() ?: 0 } ?: 0
        val zones = lat.keys.sorted().map { k ->
            val (iso, root) = k.split('/')
            ZoneHeat(k, calls.getValue(k).get(), lat.getValue(k).pct(0.5), lat.getValue(k).pct(0.99), hv.trainer(iso)?.profiles?.get(root)?.phase?.name ?: "-", hv.trainer(iso)?.profiles?.get(root)?.memo?.size ?: 0)
        }
        return Sample(
            t, phase, cps, liveHeapMb(), subVmThreads(),
            snap["receipts"] as Int, snap["landings"] as Int, hv.blackboard.keys().size, memoMax, hv.fires.size, zones,
        )
    }

    private fun heatmapOf(hv: Hypervisor, zones: List<Zone>, lat: Map<String, Lat>, calls: Map<String, AtomicLong>): List<ZoneHeat> =
        zones.map { z -> ZoneHeat(z.key, calls.getValue(z.key).get(), lat.getValue(z.key).pct(0.5), lat.getValue(z.key).pct(0.99), hv.trainer(z.isolate)?.profiles?.get(z.root)?.phase?.name ?: "-", hv.trainer(z.isolate)?.profiles?.get(z.root)?.memo?.size ?: 0) }
            .sortedByDescending { it.calls }

    /** `jcmd <pid> GC.class_histogram` top [n] lines — the evidence behind any heap claim (-Dsubvm.soak.histo=true). */
    fun classHistogram(n: Int): String = runCatching {
        val jcmd = java.io.File(System.getProperty("java.home"), "bin/jcmd").path
        val p = ProcessBuilder(jcmd, ProcessHandle.current().pid().toString(), "GC.class_histogram").redirectErrorStream(true).start()
        val future = java.util.concurrent.CompletableFuture.supplyAsync {
            p.inputStream.bufferedReader().readLines()
        }
        if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly()
        }
        val lines = runCatching { future.get(1, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList())
        "\n── class histogram (live, top $n) ──\n" + lines.take(n + 3).joinToString("\n") { it.take(140) }
    }.getOrElse { "\n── class histogram unavailable: $it" }

    /** Per-isolate threads only: leaf hosts, process readers, drivers. `subvm-watchdog` is a JVM-wide idle singleton and is excluded. */
    fun subVmThreads(): Int = Thread.getAllStackTraces().keys.count { it.name.startsWith("leaf-host:") || it.name.startsWith("subvm-reader-") || it.name.startsWith("soak-driver-") }

    private fun render(samples: List<Sample>, heatmap: List<ZoneHeat>, hot: List<String>, findings: List<String>, soakCalls: Long, ticks: Long, hv: Hypervisor): String = buildString {
        appendLine("══ heat soak report ══")
        val max = heatmap.maxOfOrNull { it.calls }?.coerceAtLeast(1) ?: 1
        appendLine("heatmap (calls, p50/p99 µs, phase, memo) — ▮ = heat, ★ = hot zone")
        for (z in heatmap) {
            val bar = "▮".repeat((z.calls * 30 / max).toInt().coerceAtLeast(if (z.calls > 0) 1 else 0)).padEnd(30, '·')
            appendLine("  ${if (z.zone in hot) "★" else " "} ${z.zone.padEnd(12)} $bar ${z.calls.toString().padStart(7)}  ${"%7.1f".format(z.p50us)}/${"%8.1f".format(z.p99us)}  ${z.phase.padEnd(14)} memo=${z.memo}")
        }
        val first = samples.first(); val last = samples.last()
        appendLine("soak calls=$soakCalls  host ticks=$ticks  fires=${last.fires}  receipts=${last.receipts}  landings=${last.landings}  blackboard keys=${last.blackboardKeys}")
        appendLine("live heap (post-GC) ${first.heapMb}MB → ${last.heapMb}MB   sub-VM threads ${first.subVmThreads} → ${last.subVmThreads}   memoMax=${last.memoMax} (cap ${LeafTrainer.MEMO_CAP})   receipt cap ${Hypervisor.RECEIPT_LOG_CAP}")
        appendLine("throughput by second: " + samples.joinToString(" ") { "${it.phase.first()}${it.callsPerSec}" })
        appendLine(if (findings.isEmpty()) "findings: none — steady at temperature" else "findings:\n" + findings.joinToString("\n") { "  ! $it" })
    }
}

/** `main` for a long soak: args = seconds [isolates] [hotZones]. */
object HeatSoakMain {
    @JvmStatic fun main(args: Array<String>) {
        HeatSoak.run(seconds = args.getOrNull(0)?.toIntOrNull() ?: 120, isolates = args.getOrNull(1)?.toIntOrNull() ?: 4, hotZones = args.getOrNull(2)?.toIntOrNull() ?: 3)
    }
}
