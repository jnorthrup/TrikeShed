package borg.trikeshed.graal.vitals

import jdk.jfr.consumer.RecordedEvent
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

    fun start() {
        if (stream != null) return
        try {
            val rs = RecordingStream()
            rs.enable("jdk.Compilation").withoutThreshold()
            rs.enable("jdk.Deoptimization").withoutThreshold()
            rs.enable("jdk.GarbageCollection")
            rs.enable("jdk.CPULoad").withPeriod(Duration.ofSeconds(1))
            rs.onEvent("jdk.Compilation") { e -> onCompilation(e) }
            rs.onEvent("jdk.Deoptimization") { e -> onDeopt(e) }
            rs.onEvent("jdk.GarbageCollection") { e -> onGc(e) }
            rs.onEvent("jdk.CPULoad") { e ->
                jvmCpuLoad = e.getDoubleOr("jvmUser") + e.getDoubleOr("jvmSystem")
                machineCpuLoad = e.getDoubleOr("machineTotal")
                _events.tryEmit(VitalEvent("cpu", mapOf("jvm" to jvmCpuLoad, "machine" to machineCpuLoad)))
            }
            rs.setMaxAge(Duration.ofMinutes(5))
            rs.startAsync()
            stream = rs
            jfrLive = true
        } catch (t: Throwable) {
            jfrLive = false
            jfrError = t.message ?: t.toString()
        }
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

    private fun onGc(e: RecordedEvent) {
        gcCollections.incrementAndGet()
        val pauseMs = runCatching { e.getDuration("sumOfPauses").toMillis() }.getOrDefault(0L)
        gcPauseMsTotal.addAndGet(pauseMs)
        val d = mapOf(
            "name" to (e.getStringOr("name") ?: "?"),
            "cause" to (e.getStringOr("cause") ?: "?"),
            "pauseMs" to pauseMs,
        )
        ring(recentGcs, d)
        _events.tryEmit(VitalEvent("gc", d))
    }

    private fun ring(dq: ConcurrentLinkedDeque<Map<String, Any?>>, v: Map<String, Any?>) {
        dq.addFirst(v)
        while (dq.size > RING) dq.pollLast()
    }

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
    private fun RecordedEvent.getBooleanOr(name: String, default: Boolean = false): Boolean = runCatching { getBoolean(name) }.getOrDefault(default)
    private fun RecordedEvent.getDoubleOr(name: String): Double = runCatching { getDouble(name) }.getOrElse { runCatching { getFloat(name).toDouble() }.getOrDefault(0.0) }

    companion object { private const val RING = 40 }
}
