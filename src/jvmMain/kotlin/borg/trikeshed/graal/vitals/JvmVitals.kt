package borg.trikeshed.graal.vitals

import jdk.jfr.consumer.RecordedClass
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordedObject
import jdk.jfr.consumer.RecordingStream
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.lang.management.ManagementFactory
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * JvmVitals — the Graal console's instrument cluster.
 *
 * One in-process JFR [RecordingStream] supplies the signals a profile page actually wants:
 * `jdk.Compilation` (the JIT working), `jdk.Deoptimization` (the JIT giving ground),
 * `jdk.GarbageCollection` and `jdk.CPULoad`. JMX beans supply the standing counters
 * (heap, metaspace, classes, threads, total compile time). Everything degrades gracefully:
 * a JVM without JFR still reports the JMX plane with `jfr: false`.
 *
 * Consumers: [snapshot] renders one JSON-shaped map; [events] is the flourish feed the
 * console streams over SSE — one element per compile / deopt / GC, already reduced to the
 * fields worth animating.
 */
class JvmVitals {

    /** One animate-worthy occurrence. `kind` ∈ compile | deopt | gc | cpu. */
    data class VitalEvent(val kind: String, val detail: Map<String, Any?>, val atMs: Long = System.currentTimeMillis())

    private val _events = MutableSharedFlow<VitalEvent>(replay = 64, extraBufferCapacity = 512)
    val events: SharedFlow<VitalEvent> = _events.asSharedFlow()

    // ── rolling counters ─────────────────────────────────────────
    val compilations = AtomicLong()
    val osrCompilations = AtomicLong()
    val compiledBytes = AtomicLong()
    val deoptimizations = AtomicLong()
    val gcCollections = AtomicLong()
    val gcPauseMsTotal = AtomicLong()
    @Volatile var jvmCpuLoad: Double = 0.0; private set
    @Volatile var machineCpuLoad: Double = 0.0; private set
    @Volatile var jfrLive: Boolean = false; private set
    @Volatile var jfrError: String? = null; private set

    private val recentCompiles = ConcurrentLinkedDeque<Map<String, Any?>>()
    private val recentDeopts = ConcurrentLinkedDeque<Map<String, Any?>>()
    private val recentGcs = ConcurrentLinkedDeque<Map<String, Any?>>()
    private var stream: RecordingStream? = null

    // ── R4: real GC occupancy deltas + allocation-attributed continents ──
    // JDK 25 (verified against `jfr metadata` on a live recording):
    //  · jdk.GCHeapSummary      — gcId(int), when("Before GC"/"After GC"), heapSpace(VirtualSpace), heapUsed(long)
    //  · jdk.GCHeapMemoryUsage  — used, committed, max   (whole heap, at each collection)
    //  · jdk.GCHeapMemoryPoolUsage — name, used, committed, max (per pool, at each collection)
    //  · jdk.GCPhasePause       — name, duration(Timespan)
    //  · jdk.ObjectAllocationSample — objectClass, weight(long)
    // The JMX NotificationEmitter does not exist in JDK 25, so JFR is the sole source.
    private val heapBefore = java.util.concurrent.ConcurrentHashMap<Int, Long>()          // gcId → used before
    private val heapAfter = java.util.concurrent.ConcurrentHashMap<Int, Long>()           // gcId → used after
    private val heapCommitted = AtomicLong()                                              // last committed size
    private val heapCommittedSamples = AtomicLong()
    private val freedByGc = ConcurrentLinkedDeque<Long>()                                 // per-collection freed bytes (ring)
    private val poolUsage = java.util.concurrent.ConcurrentHashMap<String, LongArray>()   // pool → [lastUsed, reclaimed, grown]
    private val gcPhases = java.util.concurrent.ConcurrentHashMap<String, LongArray>()    // phase → [count, pauseNanos]
    private val allocByClass = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>() // class → sampled bytes

    fun start() {
        if (stream != null) return
        try {
            val rs = RecordingStream()
            rs.enable("jdk.Compilation").withoutThreshold()
            rs.enable("jdk.Deoptimization").withoutThreshold()
            rs.enable("jdk.GarbageCollection")
            rs.enable("jdk.CPULoad").withPeriod(Duration.ofSeconds(1))
            // R4: real occupancy deltas (GCHeapSummary before/after matched by gcId,
            // GCHeapMemoryUsage whole-heap + GCHeapMemoryPoolUsage per-pool at each collection,
            // GCPhasePause phase breakdown) and allocation attribution (ObjectAllocationSample).
            rs.enable("jdk.GCHeapSummary").withoutThreshold()
            rs.enable("jdk.GCHeapMemoryUsage").withoutThreshold()
            rs.enable("jdk.GCHeapMemoryPoolUsage").withoutThreshold()
            rs.enable("jdk.GCPhasePause").withoutThreshold()
            rs.enable("jdk.ObjectAllocationSample").withPeriod(Duration.ofMillis(250))
            rs.onEvent("jdk.Compilation") { e -> onCompilation(e) }
            rs.onEvent("jdk.Deoptimization") { e -> onDeopt(e) }
            rs.onEvent("jdk.GarbageCollection") { e -> onGc(e) }
            rs.onEvent("jdk.CPULoad") { e ->
                jvmCpuLoad = e.getDoubleOr("jvmUser") + e.getDoubleOr("jvmSystem")
                machineCpuLoad = e.getDoubleOr("machineTotal")
                _events.tryEmit(VitalEvent("cpu", mapOf("jvm" to jvmCpuLoad, "machine" to machineCpuLoad)))
            }
            rs.onEvent("jdk.GCHeapSummary") { e -> onGcHeapSummary(e) }
            rs.onEvent("jdk.GCHeapMemoryUsage") { e -> onGcHeapMemoryUsage(e) }
            rs.onEvent("jdk.GCHeapMemoryPoolUsage") { e -> onGcHeapMemoryPoolUsage(e) }
            rs.onEvent("jdk.GCPhasePause") { e -> onGcPhasePause(e) }
            rs.onEvent("jdk.ObjectAllocationSample") { e -> onAllocSample(e) }
            rs.setMaxAge(Duration.ofMinutes(5))
            rs.startAsync()
            stream = rs
            jfrLive = true
        } catch (t: Throwable) {
            jfrLive = false
            jfrError = t.message ?: t.toString()
        }
    }

    private fun onGcHeapSummary(e: RecordedEvent) {
        val gcId = e.getIntOr("gcId", -1)
        if (gcId < 0) return
        val used = e.getLongOr("heapUsed")
        val when_ = e.getStringOr("when")
        val committed = runCatching { (e.getValue<Any?>("heapSpace") as? RecordedObject)?.getLong("committedSize") }.getOrNull()
        recordGcHeapSummary(gcId, when_ ?: "", used, committed)
    }

    private fun onGcHeapMemoryUsage(e: RecordedEvent) {
        recordGcHeapMemoryUsage(e.getLongOr("committed"))
    }

    private fun onGcHeapMemoryPoolUsage(e: RecordedEvent) {
        val pool = e.getStringOr("name") ?: return
        recordGcHeapMemoryPoolUsage(pool, e.getLongOr("used"), e.getLongOr("committed"))
    }

    private fun onGcPhasePause(e: RecordedEvent) {
        val phase = e.getStringOr("name") ?: return
        val pauseNanos = runCatching { e.getDuration("duration").toNanos() }.getOrDefault(0L)
        recordGcPhasePause(phase, pauseNanos)
    }

    private fun onAllocSample(e: RecordedEvent) {
        val className = (e.getValue<Any?>("objectClass") as? RecordedClass)?.name
            ?: e.getStringOr("objectClass") ?: "unknown"
        val weight = e.getLongOr("weight")
        if (weight > 0) recordAllocationSample(className, weight)
    }

    fun stop() {
        runCatching { stream?.close() }
        stream = null
        jfrLive = false
    }

    private fun onCompilation(e: RecordedEvent) {
        compilations.incrementAndGet()
        val method = runCatching {
            val m = e.getValue<Any?>("method")
            if (m is jdk.jfr.consumer.RecordedMethod) "${m.type?.name?.substringAfterLast('.')}.${m.name}" else m?.toString()
        }.getOrNull() ?: "?"
        if (e.getBooleanOr("isOsr")) osrCompilations.incrementAndGet()
        e.getLongOr("codeSize").let { if (it > 0) compiledBytes.addAndGet(it) }
        val d = mapOf(
            "method" to method,
            "level" to e.getLongOr("compileLevel"),
            "codeSize" to e.getLongOr("codeSize"),
            "osr" to e.getBooleanOr("isOsr"),
            "durationUs" to runCatching { e.duration.toNanos() / 1_000 }.getOrDefault(0L),
            "ok" to e.getBooleanOr("succeded", true), // JFR's own historical spelling
        )
        ring(recentCompiles, d)
        _events.tryEmit(VitalEvent("compile", d))
    }

    private fun onDeopt(e: RecordedEvent) {
        deoptimizations.incrementAndGet()
        val d = mapOf(
            "reason" to (e.getStringOr("reason") ?: "?"),
            "action" to (e.getStringOr("action") ?: "?"),
            "compileId" to e.getLongOr("compileId"),
            "bci" to e.getLongOr("bci"),
            "line" to e.getLongOr("lineNumber"),
        )
        ring(recentDeopts, d)
        _events.tryEmit(VitalEvent("deopt", d))
    }

    /** jdk.GarbageCollection: collector-level counters + pause decomposition (JDK 25 Timespan fields). */
    private fun onGc(e: RecordedEvent) {
        gcCollections.incrementAndGet()
        val pauseMs = runCatching { e.getDuration("sumOfPauses").toMillis() }.getOrDefault(0L)
        gcPauseMsTotal.addAndGet(pauseMs)
        val d = mapOf(
            "name" to (e.getStringOr("name") ?: "?"),
            "cause" to (e.getStringOr("cause") ?: "?"),
            "pauseMs" to pauseMs,
            // H4: the GC lane's occupancy deltas — previously discarded with the event timestamp
            "atMs" to runCatching { e.startTime.toEpochMilli() }.getOrDefault(System.currentTimeMillis()),
            "longestPauseMs" to runCatching { e.getDuration("longestPause").toMillis() }.getOrDefault(-1L),
        )
        ring(recentGcs, d)
        _events.tryEmit(VitalEvent("gc", d))
    }

    private fun ring(dq: ConcurrentLinkedDeque<Map<String, Any?>>, v: Map<String, Any?>) {
        dq.addFirst(v)
        while (dq.size > RING) dq.pollLast()
    }

    // ── H3: the heap continent ───────────────────────────────────

    /** One row of the heap histogram: per-class live set. */
    data class HeapRow(val className: String, val count: Long, val bytes: Long)

    /**
     * Live-set continent: per-class `(class, count, bytes)` from
     * `jcmd <pid> GC.class_histogram` (parsed — HeatSoak's shell-out returned
     * unparsed text and stayed demo-gated). JMX `MemoryMXBean` scalar heap usage
     * remains in [snapshot]; this adds the per-class shape the treemap renders.
     *
     * The live-set source is a seam (production-test-seams pattern); the gate swaps in a
     * fixture. jcmd GC.class_histogram self-attach stops the ENTIRE target JVM at a
     * safepoint, and if any target thread cannot reach the safepoint the attach never
     * completes: the whole JVM freezes, INCLUDING the daemon watchdog thread that would
     * kill jcmd — unrecoverable from inside. PROVEN LIVE 2026-08-27: one GET
     * /api/graal/heap wedged the daemon at 0% CPU (graal.html fetches it on every boot).
     * Therefore NOBODY self-attaches by default — not gates, not production. The jcmd
     * live-set is explicit opt-in (`-Dtrikeshed.vitals.jcmd=true`, for operators who
     * accept the freeze risk); the default heap answer carries the JFR
     * allocation-attributed continent, which needs no attach.
     */
    internal var liveSetSource: () -> List<HeapRow> = {
        if (System.getProperty("trikeshed.vitals.jcmd") == "true") classHistogram() else emptyList()
    }

    fun heapHistogram(): Map<String, Any?> {
        val rows = runCatching { liveSetSource() }.getOrDefault(emptyList())
        val totalInstances = rows.sumOf { it.count }
        val totalBytes = rows.sumOf { it.bytes }
        return mapOf(
            "atMs" to System.currentTimeMillis(),
            "classes" to rows.size,
            "instances" to totalInstances,
            "bytes" to totalBytes,
            "rows" to rows.take(256).map { mapOf("class" to it.className, "count" to it.count, "bytes" to it.bytes) },
            // R4: the allocation-attributed continent (JFR ObjectAllocationSample) — the
            // second terrain source beside the live-set rows, same treemap component.
            "allocation" to allocationByClass(),
        )
    }

    /**
     * jcmd GC.class_histogram parsed into rows; empty when jcmd is unavailable.
     *
     * The main (calling) thread does EXACTLY ONE bounded join and never touches the process.
     * A daemon worker spawns jcmd, a second daemon drains its stdout, and the worker — not
     * the caller — kills the child on the timeout. This matters because jcmd can wedge in
     * attach (uninterruptible kernel state) and a `readText()`/`waitFor()` on the main thread
     * would hang /api/graal/heap — and the whole Gradle test worker — for minutes. The join
     * always returns after [JCMD_TIMEOUT_MS]; the live-set continent degrades to the
     * allocation-attributed one and the daemon thread leaks at worst (a JVM-exit cleanup, not
     * a request-thread stall).
     */
    private fun classHistogram(): List<HeapRow> {
        val result = java.util.concurrent.atomic.AtomicReference<List<HeapRow>>(emptyList())
        val worker = Thread {
            try {
                val pid = ProcessHandle.current().pid()
                val javaExe = ProcessHandle.current().info().command().orElse("java")
                val dir = javaExe.substringBeforeLast('/', "")
                val jcmd = if (dir.isNotEmpty()) "$dir/jcmd" else "jcmd"
                val p = ProcessBuilder(jcmd, pid.toString(), "GC.class_histogram")
                    .redirectErrorStream(true).start()
                val out = java.util.concurrent.atomic.AtomicReference("")
                val reader = Thread { runCatching { out.set(p.inputStream.bufferedReader().readText()) } }
                reader.isDaemon = true
                reader.name = "jvmvitals-class-histogram-reader"
                reader.start()
                reader.join(JCMD_TIMEOUT_MS)
                // The worker kills the child on timeout — the main thread never calls waitFor/destroy.
                if (reader.isAlive) {
                    p.destroy()
                    runCatching { p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }
                    if (runCatching { p.isAlive }.getOrDefault(false)) p.destroyForcibly()
                    runCatching { p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }
                } else {
                    runCatching { p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }
                }
                result.set(parseClassHistogram(out.get()))
            } catch (_: Throwable) { /* degraded: empty live-set */ }
        }
        worker.isDaemon = true
        worker.name = "jvmvitals-class-histogram"
        worker.start()
        worker.join(JCMD_TIMEOUT_MS + 5_000)
        return runCatching { result.get() }.getOrDefault(emptyList())
    }

    /** The histogram text's `num: #instances #bytes class` table → rows. */
    internal fun parseClassHistogram(text: String): List<HeapRow> {
        val rows = ArrayList<HeapRow>()
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || !t[0].isDigit()) continue
            // "num:" (jcmd prints the index with a colon), then instances, bytes, class name
            val m = Regex("^(\\d+):\\s+(\\d+)\\s+(\\d+)\\s+(.+)$").find(t) ?: continue
            // jcmd annotates module-owned classes: `java.lang.String (java.base@25.0.4)` —
            // the treemap wants the bare class name.
            val className = m.groupValues[4].trim().substringBeforeLast(" (")
            if (className == "Total") continue
            rows += HeapRow(className, m.groupValues[2].toLongOrNull() ?: 0L, m.groupValues[3].toLongOrNull() ?: 0L)
        }
        return rows.sortedByDescending { it.bytes }
    }

    // ── R4 recorders — the accumulators the GC lane + allocation continent read ──

    /** R4: record a GCHeapSummary boundary. `when_` is "Before GC" or "After GC". */
    internal fun recordGcHeapSummary(gcId: Int, when_: String, heapUsed: Long, committed: Long?) {
        if (committed != null) {
            heapCommitted.set(committed)
            heapCommittedSamples.incrementAndGet()
        }
        if (when_ == "Before GC") {
            heapBefore[gcId] = heapUsed
        } else if (when_ == "After GC") {
            val before = heapBefore.remove(gcId) ?: return // unmatched After (evicted) → no delta
            heapAfter[gcId] = heapUsed
            val freed = before - heapUsed
            freedByGc.addFirst(freed)
            while (freedByGc.size > RING) freedByGc.pollLast()
        }
    }

    /** R4: record a GCHeapMemoryUsage whole-heap boundary. */
    internal fun recordGcHeapMemoryUsage(committed: Long) {
        heapCommitted.set(committed)
        heapCommittedSamples.incrementAndGet()
    }

    /** R4: record a GCHeapMemoryPoolUsage per-pool boundary. */
    internal fun recordGcHeapMemoryPoolUsage(pool: String, used: Long, committed: Long) {
        val acc = poolUsage[pool] ?: run { poolUsage.putIfAbsent(pool, longArrayOf(0L, 0L, 0L, 0L, 0L)); poolUsage[pool]!! }
        // [lastUsed, reclaimed, grown, lastCommitted, samples]
        if (acc[4] > 0) {
            val d = used - acc[0]
            if (d < 0) acc[1] -= d else if (d > 0) acc[2] += d
        }
        acc[0] = used
        acc[3] = committed
        acc[4] += 1
    }

    internal fun recordGcPhasePause(phase: String, pauseNanos: Long) {
        val acc = gcPhases[phase] ?: run { gcPhases.putIfAbsent(phase, longArrayOf(0L, 0L)); gcPhases[phase]!! }
        acc[0] += 1
        acc[1] += pauseNanos
    }

    internal fun recordAllocationSample(className: String, weight: Long) {
        allocByClass.getOrPut(className) { AtomicLong() }.addAndGet(weight)
    }

    /** R4: whole-heap occupancy — committed size + the per-collection freed-bytes ring. */
    internal fun gcHeapOccupancy(): Map<String, Any?> {
        val freed = freedByGc.toList()
        val totalFreed = freed.sum()
        return mapOf(
            "committedBytes" to heapCommitted.get(),
            "committedSamples" to heapCommittedSamples.get(),
            "collectionsMatched" to heapAfter.size,
            "totalFreedBytes" to totalFreed,
            "avgFreedBytes" to (if (freedByGc.isNotEmpty()) totalFreed / freed.size else 0L),
            "recentFreed" to freed.takeLast(RING),
        )
    }

    /** R4: per-pool occupancy deltas from JFR GCHeapMemoryPoolUsage. */
    internal fun gcPoolUsage(): List<Map<String, Any?>> =
        poolUsage.entries.map { (pool, acc) ->
            mapOf(
                "pool" to pool,
                "lastUsedBytes" to acc[0],
                "lastCommittedBytes" to acc[3],
                "reclaimedBytes" to acc[1],
                "grownBytes" to acc[2],
                "samples" to acc[4].toInt(),
            )
        }.sortedByDescending { (it["lastUsedBytes"] as? Long) ?: 0L }

    /** R4: the pause decomposed into phases (JFR GCPhasePause). */
    internal fun gcPhases(): List<Map<String, Any?>> =
        gcPhases.entries.map { (phase, acc) ->
            mapOf(
                "phase" to phase,
                "count" to acc[0].toInt(),
                "pauseMsTotal" to (acc[1] / 1_000_000L),
            )
        }.sortedByDescending { (it["pauseMsTotal"] as? Long) ?: 0L }

    /** R4: allocation-attributed continent (JFR ObjectAllocationSample), top classes by sampled bytes. */
    internal fun allocationByClass(): List<Map<String, Any?>> =
        allocByClass.entries.map { (name, acc) -> mapOf("class" to name, "bytes" to acc.get()) }
            .sortedByDescending { (it["bytes"] as? Long) ?: 0L }
            .take(256)

    /** R4: the whole GC-lane snapshot — the fields the GC lane widget + spec-parity route expose. */
    internal fun gcLane(): Map<String, Any?> = mapOf(
        "heapOccupancy" to gcHeapOccupancy(),
        "pools" to gcPoolUsage(),
        "phases" to gcPhases(),
        "allocation" to allocationByClass(),
        "atMs" to System.currentTimeMillis(),
    )

    /** The whole instrument cluster as one JSON-shaped map. */
    fun snapshot(): Map<String, Any?> {
        val rt = Runtime.getRuntime()
        val mem = ManagementFactory.getMemoryMXBean()
        val heap = mem.heapMemoryUsage
        val meta = ManagementFactory.getMemoryPoolMXBeans().firstOrNull { it.name.contains("Metaspace") && !it.name.contains("Compressed") }?.usage
        val cls = ManagementFactory.getClassLoadingMXBean()
        val threads = ManagementFactory.getThreadMXBean()
        val comp = runCatching { ManagementFactory.getCompilationMXBean() }.getOrNull()
        val gcBeans = ManagementFactory.getGarbageCollectorMXBeans().map {
            mapOf("name" to it.name, "count" to it.collectionCount, "timeMs" to it.collectionTime)
        }
        return mapOf(
            "graal" to mapOf(
                "vmName" to System.getProperty("java.vm.name"),
                "vmVersion" to System.getProperty("java.vm.version"),
                "vendor" to System.getProperty("java.vm.vendor"),
                "javaVersion" to System.getProperty("java.version"),
                "jvmci" to (System.getProperty("jvmci.Compiler") ?: if ((System.getProperty("java.vm.name") ?: "").contains("GraalVM")) "graal" else null),
                "jitName" to comp?.name,
                "uptimeMs" to ManagementFactory.getRuntimeMXBean().uptime,
                "pid" to ProcessHandle.current().pid(),
            ),
            "jfr" to mapOf("live" to jfrLive, "error" to jfrError),
            "jit" to mapOf(
                "compilations" to compilations.get(),
                "osr" to osrCompilations.get(),
                "compiledBytes" to compiledBytes.get(),
                "totalCompileTimeMs" to (runCatching { comp?.totalCompilationTime }.getOrNull() ?: -1L),
                "recent" to recentCompiles.toList(),
            ),
            "deopt" to mapOf(
                "count" to deoptimizations.get(),
                "recent" to recentDeopts.toList(),
            ),
            "gc" to mapOf(
                "collections" to gcCollections.get(),
                "pauseMsTotal" to gcPauseMsTotal.get(),
                "beans" to gcBeans,
                "recent" to recentGcs.toList(),
                "lane" to gcLane(),
            ),
            "memory" to mapOf(
                "heapUsed" to heap.used, "heapCommitted" to heap.committed, "heapMax" to heap.max,
                "metaspaceUsed" to (meta?.used ?: -1L),
                "processors" to rt.availableProcessors(),
            ),
            "classes" to mapOf("loaded" to cls.loadedClassCount, "totalLoaded" to cls.totalLoadedClassCount, "unloaded" to cls.unloadedClassCount),
            "threads" to mapOf("live" to threads.threadCount, "daemon" to threads.daemonThreadCount, "peak" to threads.peakThreadCount),
            "cpu" to mapOf("jvm" to jvmCpuLoad, "machine" to machineCpuLoad),
        )
    }

    // ── defensive JFR field access (event shapes drift across JDKs) ──
    private fun RecordedEvent.getStringOr(name: String): String? = runCatching { getString(name) }.getOrNull()
    private fun RecordedEvent.getLongOr(name: String): Long = runCatching { getLong(name) }.getOrElse { runCatching { getInt(name).toLong() }.getOrDefault(0L) }
    private fun RecordedEvent.getIntOr(name: String, default: Int = 0): Int = runCatching { getInt(name) }.getOrElse { runCatching { getLong(name).toInt() }.getOrDefault(default) }
    private fun RecordedEvent.getBooleanOr(name: String, default: Boolean = false): Boolean = runCatching { getBoolean(name) }.getOrDefault(default)
    private fun RecordedEvent.getDoubleOr(name: String): Double = runCatching { getDouble(name) }.getOrElse { runCatching { getFloat(name).toDouble() }.getOrDefault(0.0) }

    companion object {
        private const val RING = 40
        /** Max wait for a jcmd class-histogram read; a wedged jcmd degrades the live-set, never the caller. */
        private const val JCMD_TIMEOUT_MS = 8_000L
    }
}
