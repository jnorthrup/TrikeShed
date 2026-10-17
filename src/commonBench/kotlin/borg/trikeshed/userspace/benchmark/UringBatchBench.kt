package borg.trikeshed.userspace.benchmark

import borg.trikeshed.userspace.*
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import kotlinx.benchmark.*

/** One batch of [batch] SQEs per invocation through the backend's submitBatch, plus a reap for CQEs still in flight. */
@State(Scope.Benchmark)
open class UringNopBench {
    @Param("emulated") var mode = "emulated"
    @Param("8") var batch = 8

    private lateinit var backend: UserspaceChannelBackend
    private lateinit var nops: List<UringSubmission>

    @Setup
    fun open() {
        backend = openUserspaceChannelBackend(64, UringBackendMode.parse(mode))
        nops = List(batch) { UringSubmission(UringOp.NOP, -1, 0, 0, 0, userData = it.toLong()) }
    }

    @TearDown
    fun close() = backend.close()

    @Benchmark
    fun nop(): Int = backend.round(nops, 0)
}

/** [batch] WRITE or READ SQEs of [bytes] at consecutive offsets of one fixture file per invocation. */
@State(Scope.Benchmark)
open class UringFileBench {
    @Param("emulated") var mode = "emulated"
    @Param("8") var batch = 8
    @Param("4096") var bytes = 4096
    @Param("/tmp/trikeshed-uring-bench.bin") var path = "/tmp/trikeshed-uring-bench.bin"

    private lateinit var backend: UserspaceChannelBackend
    private lateinit var writes: List<UringSubmission>
    private lateinit var reads: List<UringSubmission>
    private var fd = -1

    @Setup
    fun open() {
        backend = openUserspaceChannelBackend(64, UringBackendMode.parse(mode))
        // O_RDWR | O_CREAT | O_TRUNC
        fd = backend.round1(Submissions.openat(path, flags = 2 or 64 or 512, userData = -1))
        if (fd < 0) println("OPENAT $path failed: $fd (${backend.availability})")
        check(fd >= 0) { "OPENAT $path failed: $fd (${backend.availability})" }
        writes = List(batch) { index ->
            UringSubmission(UringOp.WRITE, fd, 0, bytes, index.toLong() * bytes, userData = index.toLong(),
                buffer = ByteBuffer(bytes).also { it.array().fill((index + 1).toByte()) })
        }
        reads = List(batch) { index ->
            UringSubmission(UringOp.READ, fd, 0, bytes, index.toLong() * bytes, userData = index.toLong(), buffer = ByteBuffer(bytes))
        }
        writes.forEach { it.buffer!!.clear() }
        backend.round(writes, bytes)
    }

    @TearDown
    fun close() {
        if (fd >= 0) backend.round1(Submissions.close(fd, -2))
        backend.close()
    }

    @Benchmark
    fun write(): Int {
        for (s in writes) s.buffer!!.clear()
        return backend.round(writes, bytes)
    }

    @Benchmark
    fun read(): Int {
        for (s in reads) s.buffer!!.clear()
        return backend.round(reads, bytes)
    }
}

/**
 * Submits [batch] and collects every CQE: a kernel ring may complete SQEs after submitBatch returns, and those arrive
 * through reapCompletions. Each must complete with [expected]; returns the summed results for the harness's sink.
 */
private fun UserspaceChannelBackend.round(batch: List<UringSubmission>, expected: Int): Int {
    var sum = 0
    var settled = 0
    for (r in submitBatch(batch)) {
        check(r.res == expected) { "CQE ${r.userData} completed with ${r.res}" }
        sum += r.res
        settled++
    }
    while (settled < batch.size) for (r in reapCompletions(batch.size - settled)) {
        check(r.res == expected) { "CQE ${r.userData} completed with ${r.res}" }
        sum += r.res
        settled++
    }
    return sum
}

/** One SQE's result, reaped when it completes after submitBatch returns. */
private fun UserspaceChannelBackend.round1(sub: UringSubmission): Int =
    (submitBatch(listOf(sub)).firstOrNull() ?: reapCompletions(1).first()).res
