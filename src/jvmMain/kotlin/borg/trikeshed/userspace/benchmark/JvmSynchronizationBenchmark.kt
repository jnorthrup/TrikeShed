package borg.trikeshed.userspace.benchmark

import borg.trikeshed.collections.associative.toItem
import borg.trikeshed.context.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.userspace.*
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.channels.spi.JvmReactorOperations
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import com.sun.management.OperatingSystemMXBean
import java.lang.System
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.*

/** Linux measurement driver. Workload effects and completion ownership stay in the common NIO boundary. */
object JvmSynchronizationBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        val arguments = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        val workload = arguments["workload"] ?: "db"
        val mode = Synchronization.valueOf(arguments["mode"] ?: "NONE")
        val workers = arguments["workers"]?.toInt() ?: 1
        require(workers in 1..16)
        check(System.getProperty("os.name").lowercase().contains("linux")) { "This driver requires Linux CPU accounting and native io_uring" }
        System.setProperty("trikeshed.uring.mode", "native")
        val directory = Path.of(arguments["path"] ?: "/tmp/trikeshed-sync-${ProcessHandle.current().pid()}")
        Files.createDirectories(directory)
        val phases = PhaseMeasurements(workers)
        val owners = Executors.newFixedThreadPool(workers)
        val started = cpuSample()
        try {
            val results = (0 until workers).map { shard -> owners.submit<Map<String, Any?>> {
                val counters = BackendMeasurements()
                val path = directory.resolve("shard-$shard")
                Files.createDirectories(path)
                val boundary: (String, Boolean) -> Unit = { phase, begin -> phases.boundary(shard, phase, begin) }
                val measured = when (workload) {
                    "db" -> {
                        val operations = SynchronizationDatabaseBenchmark.nativeOperations(mode, { counters.open(256) })
                        repeat(arguments["warmup_rounds"]?.toInt() ?: 2) { round ->
                            SynchronizationDatabaseBenchmark.run(operations, path.resolve("warm-$round.bin").toString(),
                                arguments["warmup_rows"]?.toInt() ?: 1024, 64, 1, 1024)
                        }
                        SynchronizationDatabaseBenchmark.run(operations, path.resolve("data.bin").toString(),
                            arguments["rows"]?.toInt() ?: 8192, arguments["batch_rows"]?.toInt() ?: 64,
                            arguments["scans"]?.toInt() ?: 2, arguments["point_reads"]?.toInt() ?: 8192,
                            counters::snapshot, boundary)
                    }
                    "batch" -> runBlocking {
                        val batch = arguments["batch"]?.toInt() ?: 8
                        repeat(arguments["warmup_rounds"]?.toInt() ?: 2) { round ->
                            UringBenchmark.run(UringBenchmarkConfig(path.resolve("warm-$round.bin").toString(),
                                batch = batch, warmup = 32, iterations = 128), counters.open(64), nativeRequired = true)
                        }
                        val result = UringBenchmark.run(UringBenchmarkConfig(path.resolve("batch.bin").toString(),
                            batch = batch, warmup = arguments["warmup"]?.toInt() ?: 256,
                            iterations = arguments["iterations"]?.toInt() ?: 2048), counters.open(64),
                            nativeRequired = true, phaseBoundary = boundary)
                        mapOf("result" to ConfixFormat.encodeToItem(UringBenchmarkResult.serializer(), result))
                    }
                    "native_wake", "reactor_wake" -> runBlocking {
                        wake(workload, mode, path, arguments, counters, boundary)
                    }
                    else -> error("Unknown workload: $workload")
                }
                mapOf("shard" to shard, "measurements" to measured, "backend" to counters.snapshot())
            } }.map { it.get(10, TimeUnit.MINUTES) }
            val stopped = cpuSample()
            val output = mapOf(
                "workload" to workload, "synchronization" to if (workload == "batch" || workload == "native_wake") "NOT_APPLICABLE" else mode.name,
                "arguments" to arguments, "workers" to workers, "environment" to environment(directory),
                "total_including_warmup" to delta(started, stopped), "phases" to phases.results,
                "shards" to results,
            )
            println(output.toItem().toJsonString())
        } finally {
            phases.abort()
            owners.shutdownNow()
            check(owners.awaitTermination(30, TimeUnit.SECONDS)) { "Benchmark owners did not finish" }
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private suspend fun wake(workload: String, mode: Synchronization, path: Path, arguments: Map<String, String>,
                             counters: BackendMeasurements, boundary: (String, Boolean) -> Unit): Map<String, Any?> {
        val fifo = path.resolve("events")
        check(ProcessBuilder("mkfifo", fifo.toString()).start().waitFor() == 0)
        val control = FunctionalUringFacade(64, counters.open(64))
        fun one(submission: UringSubmission): Int {
            control.enqueue(submission)
            check(control.submit() == 1)
            return control.wait(1).single().also { check(it.userData == submission.userData && it.res >= 0) }.res
        }
        val fd = one(UringOp.Companion.Submissions.openat(fifo.toString(), 2, 1))
        val writer = FileChannel.open(fifo.toString(), setOf(StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            FunctionalUringFacade(64, counters.open(64))
        }
        val warmup = arguments["warmup"]?.toInt() ?: 256
        val iterations = arguments["iterations"]?.toInt() ?: 2048
        val idle = arguments["producer_idle_ns"]?.toLong() ?: 500_000L
        val writeByte = ByteBuffer.wrap(byteArrayOf(1))
        val producer: suspend (Int) -> Long = {
            LockSupport.parkNanos(idle)
            writeByte.clear()
            val stamp = System.nanoTime()
            check(writer.write(writeByte) == 1)
            stamp
        }
        try {
            val latencies: LongArray
            val pathDescription: String
            if (workload == "native_wake") {
                latencies = uringCompletionWakeBenchmark(counters.open(64), fd, warmup, iterations,
                    producer, System::nanoTime, boundary)
                pathDescription = "kernel io_uring READ CQE observed by common facade"
            } else {
                val reactor = JvmReactorOperations(mode)
                try {
                    latencies = reactorWakeBenchmark(reactor, fd, warmup, iterations, producer, {
                        val bytes = ByteBuffer(1)
                        check(one(UringOp.Companion.Submissions.read(fd, 0, 1, -1, 2).copy(buffer = bytes)) == 1)
                        check(bytes.array()[0] == 1.toByte())
                    }, System::nanoTime, boundary)
                } finally { reactor.drain() }
                pathDescription = "POSIX-emulated POLL_ADD watch under native-selected ring; payload consumed by native READ"
            }
            latencies.sort()
            fun percentile(percent: Int): Long = latencies[((latencies.size - 1).toLong() * percent / 100).toInt()]
            return mapOf("path" to pathDescription, "samples" to latencies.size, "producer_idle_ns" to idle,
                "latency_definition" to "producer native WRITE request timestamp to consumer completion observation; fixture idle excluded",
                "p50_ns" to percentile(50), "p95_ns" to percentile(95), "p99_ns" to percentile(99),
                "min_ns" to latencies.first(), "max_ns" to latencies.last(), "latencies_ns" to latencies.toList())
        } finally {
            writer.close()
            try { check(one(UringOp.Companion.Submissions.close(fd, 3)) == 0) }
            finally { control.closeNow() }
        }
    }

    /** Counters describe backend admission, not kernel enters. Producer and observer may use different threads. */
    private class BackendMeasurements {
        private val values = ConcurrentHashMap<String, LongAdder>()
        fun snapshot(): Map<String, Long> = values.mapValues { it.value.sum() }
        private fun increment(name: String, value: Long = 1) { values.computeIfAbsent(name) { LongAdder() }.add(value) }
        fun open(entries: Int): UserspaceChannelBackend {
            val backend = openUserspaceChannelBackend(entries, UringBackendMode.NATIVE)
            check(backend.nativeCapabilities and UringOp.NOP.mask != 0L) { backend.availability }
            increment("channels")
            return object : UserspaceChannelBackend by backend {
                private fun submitted(submissions: List<UringSubmission>) {
                    increment("backend_batches")
                    increment("backend_requests", submissions.size.toLong())
                    increment("backend_batch_${submissions.size}")
                    for (submission in submissions) increment("opcode_${submission.opcode}")
                }
                override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
                    submitted(submissions)
                    return backend.submitBatch(submissions).also { increment("cqes", it.size.toLong()) }
                }
                override fun submitDeferredBatch(submissions: List<UringSubmission>): List<SelectionResult> {
                    submitted(submissions)
                    return backend.submitDeferredBatch(submissions).also { increment("cqes", it.size.toLong()) }
                }
                override fun reapCompletions(minComplete: Int): List<SelectionResult> {
                    increment("reap_calls")
                    return backend.reapCompletions(minComplete).also { increment("cqes", it.size.toLong()) }
                }
                override suspend fun awaitCompletions() {
                    increment("readiness_waits")
                    backend.awaitCompletions()
                }
            }
        }
    }

    private class PhaseMeasurements(parties: Int) {
        private val barrier = CyclicBarrier(parties)
        private val starts = mutableMapOf<String, CpuSample>()
        val results = ConcurrentHashMap<String, Map<String, Long>>()
        fun boundary(shard: Int, phase: String, begin: Boolean) {
            barrier.await(120, TimeUnit.SECONDS)
            if (shard == 0) {
                if (begin) starts[phase] = cpuSample()
                else results[phase] = delta(starts.getValue(phase), cpuSample())
            }
            barrier.await(120, TimeUnit.SECONDS)
        }
        fun abort() { barrier.reset() }
    }

    private data class CpuSample(val wall: Long, val process: Long, val usage: Long, val user: Long, val system: Long,
                                 val collections: Long, val collectionMillis: Long)

    private fun cpuSample(): CpuSample {
        val cpu = (ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean).processCpuTime
        val cgroup = Files.readAllLines(Path.of("/sys/fs/cgroup/cpu.stat")).associate {
            it.substringBefore(' ') to it.substringAfter(' ').toLong()
        }
        val collectors = ManagementFactory.getGarbageCollectorMXBeans()
        return CpuSample(System.nanoTime(), cpu, cgroup.getValue("usage_usec") * 1000,
            cgroup.getValue("user_usec") * 1000, cgroup.getValue("system_usec") * 1000,
            collectors.sumOf { it.collectionCount.coerceAtLeast(0) }, collectors.sumOf { it.collectionTime.coerceAtLeast(0) })
    }

    private fun delta(before: CpuSample, after: CpuSample): Map<String, Long> = mapOf(
        "wall_ns" to after.wall - before.wall, "process_cpu_ns" to after.process - before.process,
        "cgroup_cpu_ns" to after.usage - before.usage, "cgroup_user_ns" to after.user - before.user,
        "cgroup_system_ns" to after.system - before.system, "gc_collections" to after.collections - before.collections,
        "gc_millis" to after.collectionMillis - before.collectionMillis,
    )

    private fun environment(path: Path): Map<String, Any?> = mapOf(
        "java" to System.getProperty("java.runtime.version"), "vm" to System.getProperty("java.vm.name"),
        "os" to System.getProperty("os.name"), "architecture" to System.getProperty("os.arch"),
        "affinity" to Files.readAllLines(Path.of("/proc/self/status")).first { it.startsWith("Cpus_allowed_list:") }.substringAfter(':').trim(),
        "available_processors" to Runtime.getRuntime().availableProcessors(), "filesystem" to Files.getFileStore(path).type(),
        "cpu_max" to Files.readString(Path.of("/sys/fs/cgroup/cpu.max")).trim(),
        "pid" to ProcessHandle.current().pid(), "kernel" to Files.readString(Path.of("/proc/version")).trim(),
    )
}
