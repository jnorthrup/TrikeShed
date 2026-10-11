package borg.trikeshed.loom

import borg.trikeshed.lib.*
import borg.trikeshed.platform.*
import borg.trikeshed.userspace.*
import borg.trikeshed.userspace.UringOp.Companion.Submissions
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.ByteBuffer
import kotlinx.coroutines.*
import kotlin.random.Random
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds

/** cocaine-rats crates/loom-mesh/src/uring.rs `policy`: the completion backend an I/O mode names. */
fun policy(name: String): UringBackendMode = when (name) {
    "auto" -> UringBackendMode.AUTO
    "native" -> UringBackendMode.NATIVE
    "emulated" -> UringBackendMode.EMULATED
    else -> error("I/O mode must be auto, native or emulated")
}

/**
 * uring.rs `self_test`: a synthetic bidirectional transport check; no keys, stores, billing or GPU. The peer writes
 * 262144 bytes in 997-byte chunks and reads each one back; the mesh end echoes them through a ring of [policy]'s
 * backend, each of its reads and writes one completion. The fixture is a connected UNIX stream pair made by ring
 * operations (liburing has no socketpair); a stream end waits for readiness before each transfer, and every wait
 * ends at the 5 s fixture deadline. The report as serde_json's object.
 */
suspend fun self_test(policy: UringBackendMode): Map<String, Any> {
    val fixture = FunctionalUringFacade(256, openUserspaceChannelBackend(256))
    val (mesh, peer) = try {
        val path = (HostSystem.getenv("TMPDIR") ?: "/tmp").trimEnd('/') + "/loom-uring-" + Random.nextLong().toULong().toString(16)
        // sockaddr_un in the facade's Linux layout: sun_family AF_UNIX, then the NUL-terminated path.
        val address = byteArrayOf(1, 0) + path.encodeToByteArray() + 0
        val listener = fixture.execute(UringSubmission(UringOp.SOCKET, 1, 0, 0, (1 or UringOp.O_CLOEXEC).toLong(), userData = 1)).also { check(it >= 0) }
        try {
            check(fixture.execute(UringSubmission(UringOp.BIND, listener, 0, address.size, 0, userData = 2, buffer = ByteBuffer(address))) == 0)
            try {
                check(fixture.execute(UringSubmission(UringOp.LISTEN, listener, 0, 1, 0, userData = 3)) == 0)
                val peer = fixture.execute(UringSubmission(UringOp.SOCKET, 1, 0, 0, (1 or UringOp.O_CLOEXEC).toLong(), userData = 4)).also { check(it >= 0) }
                check(fixture.execute(UringSubmission(UringOp.CONNECT, peer, 0, address.size, 0, userData = 5, buffer = ByteBuffer(address))) == 0)
                fixture.execute(UringSubmission(UringOp.ACCEPT, listener, 0, 0, 0, userData = 6, operationFlags = UringOp.O_CLOEXEC))
                    .also { check(it >= 0) } j peer
            } finally {
                fixture.execute(Submissions.unlinkat(UringOp.AT_FDCWD, path, 0, 7))
            }
        } finally {
            fixture.execute(Submissions.close(listener, 8))
        }
    } catch (failed: Exception) {
        error("fixture sockets unavailable")
    }
    val backend = openUserspaceChannelBackend(256)
    val native = backend.nativeCapabilities != 0L
    val fallback: Int? = when {
        native && policy == UringBackendMode.EMULATED ->
            TODO("an emulated ring beside an available kernel ring waits on openUserspaceChannelBackend taking a mode (TRIKESHED_URING_MODE selects one per process)")
        native || policy == UringBackendMode.EMULATED -> null
        policy == UringBackendMode.NATIVE -> error("requested completion backend unavailable")
        // Rust has no ring off Linux and falls back on libc::ENOSYS, Darwin's and the BSDs' 78.
        PlatformHost.default.descriptor.os != "linux" -> 78
        else -> (backend.probeReport?.errno ?: TODO("the setup errno of a posix backend that fell back to emulation"))
            .also { if (it !in setOf(38, 1, 13, 95, 22)) error("requested completion backend unavailable") }
    }
    val ring = FunctionalUringFacade(256, backend)
    val expected = ByteArray(262144) { (it % 251).toByte() }
    val (read, written, completions) = coroutineScope {
        val echo = async(Dispatchers.Default) {
            runCatching {
                val echoed = ByteArray(997)
                for (at in expected.indices step 997) {
                    val chunk = minOf(997, expected.size - at)
                    var sent = 0
                    while (sent < chunk) {
                        val result = fixture.stream(peer, ByteBuffer.wrap(expected, at + sent, chunk - sent), false, TimeSource.Monotonic.markNow())
                        check(result > 0)
                        sent += result
                    }
                    var received = 0
                    while (received < chunk) {
                        val result = fixture.stream(peer, ByteBuffer.wrap(echoed, received, chunk - received), true, TimeSource.Monotonic.markNow())
                        check(result > 0)
                        received += result
                    }
                    check(echoed.copyOf(chunk).contentEquals(expected.copyOfRange(at, at + chunk)))
                }
                check(fixture.execute(UringSubmission(UringOp.SHUTDOWN, peer, 0, 1, 0, userData = 9)) == 0)
            }
        }
        val buffer = ByteArray(65536)
        var read = 0
        var written = 0
        var offset = 0
        var length = 0
        var completions = 0
        val deadline = TimeSource.Monotonic.markNow()
        while (true) {
            val reading = offset >= length
            val result = ring.stream(mesh, if (reading) ByteBuffer(buffer) else ByteBuffer.wrap(buffer, offset, length - offset), reading, deadline)
            completions++
            if (result < 0) error("fixture operation failed")
            if (reading) {
                if (result == 0) break
                length = result
                offset = 0
                read += length
            } else {
                if (result == 0) error("fixture write made no progress")
                offset += result
                written += result
            }
        }
        echo.await().getOrElse { error("fixture bytes changed") }
        Triple(read, written, completions)
    }
    ring.execute(Submissions.close(mesh, 10))
    ring.closeNow()
    fixture.execute(Submissions.close(peer, 10))
    fixture.closeNow()
    if (read != 262144 || written != read) error("fixture byte accounting mismatch")
    return mapOf(
        "mode" to if (native) "liburing" else "emulated", "fallback_errno" to (fallback?.toLong() ?: Null),
        "read_bytes" to read.toLong(), "written_bytes" to written.toLong(), "completions" to completions.toLong(),
        "payload_equal" to true, "gpu_used" to false,
    )
}

/**
 * One RECV ([read]) or SEND of [buffer]'s window on the stream [fd] once it polls ready: the transfer's result, a
 * would-block retried. A wait that outlasts 5 s from [deadline] fails the fixture.
 */
suspend fun FunctionalUringFacade.stream(fd: Int, buffer: ByteBuffer, read: Boolean, deadline: TimeMark): Int {
    while (true) {
        val ready = settle(UringSubmission(UringOp.POLL_ADD, fd, 0, 0, 0, userData = 11, operationFlags = if (read) 1 else 4), deadline)
        if (ready < 0) error("fixture completion failed")
        val result = settle(UringSubmission(if (read) UringOp.RECV else UringOp.SEND, fd, 0, buffer.remaining(), 0, userData = 12, buffer = buffer), deadline)
        if (result != -11 && result != -4) return result
    }
}

/** [submission]'s completion result, polled without blocking until the 5 s from [deadline] pass. */
suspend fun FunctionalUringFacade.settle(submission: UringSubmission, deadline: TimeMark): Int {
    enqueue(submission)
    submit()
    while (true) {
        wait(0).firstOrNull()?.let { return it.res }
        if (deadline.elapsedNow() >= 5.seconds) error("fixture completion deadline exceeded")
        delay(1)
    }
}
