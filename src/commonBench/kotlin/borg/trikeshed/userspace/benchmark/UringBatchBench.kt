package borg.trikeshed.userspace.benchmark

import borg.trikeshed.userspace.*
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import kotlinx.benchmark.*

/** One batch of [batch] SQEs per invocation through the backend's synchronous submitBatch. */
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
    fun nop(): Int = backend.submitBatch(nops).settled(0)
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
        fd = backend.submitBatch(listOf(Submissions.openat(path, flags = 2 or 64 or 512, userData = -1))).single().res
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
        backend.submitBatch(writes).settled(bytes)
    }

    @TearDown
    fun close() {
        if (fd >= 0) backend.submitBatch(listOf(Submissions.close(fd, -2)))
        backend.close()
    }

    @Benchmark
    fun write(): Int {
        for (s in writes) s.buffer!!.clear()
        return backend.submitBatch(writes).settled(bytes)
    }

    @Benchmark
    fun read(): Int {
        for (s in reads) s.buffer!!.clear()
        return backend.submitBatch(reads).settled(bytes)
    }
}

/** Every CQE of the batch completed with [expected]; returns the summed results for the harness's sink. */
private fun List<SelectionResult>.settled(expected: Int): Int {
    var sum = 0
    for (r in this) {
        check(r.res == expected) { "CQE ${r.userData} completed with ${r.res}" }
        sum += r.res
    }
    return sum
}
