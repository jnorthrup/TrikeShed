package borg.trikeshed.userspace.benchmark

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.platform.loadPlatformHost
import borg.trikeshed.parse.confix.ConfixFormat
import borg.trikeshed.userspace.FunctionalUringFacade
import borg.trikeshed.userspace.UringCompletion
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.UringProbeReport
import borg.trikeshed.userspace.nio.ByteBuffer
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlin.time.TimeSource

@Serializable
data class UringBenchmarkConfig(
    val path: String,
    val entries: Int = 64,
    val batch: Int = 8,
    val bytes: Int = 4096,
    val warmup: Int = 250,
    val iterations: Int = 1000,
) {
    init {
        require(path.isNotBlank())
        require(entries > 0 && batch in 1..entries)
        require(bytes in 1..1048576 && bytes.toLong() * batch <= 67108864)
        require(warmup >= 0 && iterations > 0)
    }
}

@Serializable
data class UringBenchmarkMeasurement(
    val opcode: UringOp,
    val admitted: Long,
    val settled: Long,
    val bytes: Long,
    val elapsedNanos: Long,
    val batchMedianNanos: Long,
    val batchP95Nanos: Long,
    val operationsPerSecond: Double,
)

@Serializable
data class UringBenchmarkResult(
    val runtime: String,
    val os: String?,
    val architecture: String?,
    val config: UringBenchmarkConfig,
    val availability: String,
    val capabilities: Long,
    val nativeCapabilities: Long,
    val probe: UringProbeReport?,
    val measurements: List<UringBenchmarkMeasurement>,
)

/** Identical warmed, cached-file and NOP workloads through the owning common ring. */
object UringBenchmark {
    suspend fun run(config: UringBenchmarkConfig): UringBenchmarkResult = coroutineScope {
        val facade = FunctionalUringFacade.create(this, config.entries)
        var descriptor: Int? = null
        var nextToken = 1L
        suspend fun one(submission: UringSubmission): UringCompletion {
            val result = facade.batchEnqueue(1 j { submission })
            check(result.size == 1 && result[0].userData == submission.userData) { "CQE identity/count mismatch" }
            return result[0]
        }
        try {
            // O_RDWR | O_CREAT | O_EXCL: the fixture must be a new disposable file.
            val opened = one(Submissions.openat(config.path, flags = 2 or 64 or 128, userData = nextToken++))
            check(opened.res >= 0) { "OPENAT failed: ${opened.res}; use a new fixture path" }
            val fd = opened.res
            descriptor = fd
            val buffers = Array(config.batch) { ByteBuffer(config.bytes) }
            val measurements = mutableListOf<UringBenchmarkMeasurement>()
            for (opcode in arrayOf(UringOp.NOP, UringOp.WRITE, UringOp.READ)) {
                val durations = LongArray(config.iterations)
                var settled = 0L
                for (iteration in 0 until config.warmup + config.iterations) {
                    for (index in buffers.indices) {
                        buffers[index].clear()
                        buffers[index].array().fill(if (opcode == UringOp.READ) 0 else (index + 1).toByte())
                    }
                    val firstToken = nextToken
                    nextToken += config.batch
                    val submissions: Series<UringSubmission> = config.batch j { index ->
                        UringSubmission(opcode, if (opcode == UringOp.NOP) -1 else fd, 0,
                            if (opcode == UringOp.NOP) 0 else config.bytes, index.toLong() * config.bytes,
                            userData = firstToken + index,
                            buffer = if (opcode == UringOp.NOP) null else buffers[index])
                    }
                    val start = TimeSource.Monotonic.markNow()
                    val results = facade.batchEnqueue(submissions)
                    val elapsed = start.elapsedNow().inWholeNanoseconds
                    check(results.size == config.batch) { "Admitted ${config.batch}, settled ${results.size}" }
                    val seen = BooleanArray(config.batch)
                    for (index in 0 until results.size) {
                        val cqe = results[index]
                        val slot = cqe.userData - firstToken
                        check(slot in 0 until config.batch.toLong() && !seen[slot.toInt()]) { "CQE identity mismatch" }
                        seen[slot.toInt()] = true
                        check(cqe.res == if (opcode == UringOp.NOP) 0 else config.bytes) {
                            "$opcode completed with ${cqe.res}"
                        }
                    }
                    if (opcode == UringOp.READ) for (index in buffers.indices) {
                        check(buffers[index].position() == config.bytes)
                        check(buffers[index].array().all { it == (index + 1).toByte() }) { "READ payload mismatch" }
                    }
                    if (iteration >= config.warmup) {
                        durations[iteration - config.warmup] = elapsed
                        settled += results.size
                    }
                }
                val elapsed = durations.sum()
                check(elapsed > 0) { "Monotonic clock did not advance" }
                durations.sort()
                val admitted = config.iterations.toLong() * config.batch
                check(settled == admitted)
                measurements += UringBenchmarkMeasurement(opcode, admitted, settled,
                    if (opcode == UringOp.NOP) 0 else settled * config.bytes,
                    elapsed, durations[durations.size / 2], durations[(durations.size - 1) * 95 / 100],
                    settled * 1_000_000_000.0 / elapsed)
                if (opcode == UringOp.WRITE)
                    check(one(Submissions.fsync(fd, nextToken++)).res == 0) { "FSYNC failed" }
            }
            val eof = one(Submissions.read(fd, 0, config.bytes, config.bytes.toLong() * config.batch,
                nextToken++).copy(buffer = ByteBuffer(config.bytes)))
            check(eof.res == 0) { "EOF must be a zero-byte CQE: ${eof.res}" }
            check(one(Submissions.close(fd, nextToken++)).res == 0) { "CLOSE failed" }
            descriptor = null
            val host = loadPlatformHost().descriptor
            UringBenchmarkResult(host.runtime.name, host.os, host.architecture, config, facade.availability,
                facade.capabilities, facade.nativeCapabilities, facade.probeReport, measurements)
        } finally {
            try {
                descriptor?.let { check(one(Submissions.close(it, nextToken)).res == 0) { "CLOSE failed" } }
            } finally {
                facade.drain()
            }
        }
    }
}

/** Arguments: a new fixture path, then optional entries/batch/bytes/warmup/iterations key=value pairs. */
suspend fun uringBenchmarkMain(args: Array<String>) {
    require(args.isNotEmpty()) { "Expected a new fixture path and optional entries=64 batch=8 bytes=4096 warmup=250 iterations=1000" }
    val values = args.drop(1).associate { argument ->
        val pair = argument.split('=', limit = 2)
        require(pair.size == 2 && pair[0] in setOf("entries", "batch", "bytes", "warmup", "iterations")) {
            "Unknown benchmark argument: $argument"
        }
        pair[0] to pair[1].toInt()
    }
    val config = UringBenchmarkConfig(args[0], values["entries"] ?: 64, values["batch"] ?: 8,
        values["bytes"] ?: 4096, values["warmup"] ?: 250, values["iterations"] ?: 1000)
    println(ConfixFormat.encodeToJsonString(UringBenchmarkResult.serializer(), UringBenchmark.run(config)))
}
