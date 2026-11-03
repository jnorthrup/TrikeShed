@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package borg.trikeshed.narsese

import borg.trikeshed.mcp.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.userspace.nio.platform.spi.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import mlxc.trikeshed_mlx_footprint
import platform.posix.*

/** The caller owns this process and can terminate it even while a native MLX call is blocked. */
fun jevaWorkerMain(args: Array<String>) {
    require(args.isEmpty())
    signal(SIGPIPE, SIG_IGN)
    val protocol = dup(STDOUT_FILENO)
    check(protocol >= 0 && fcntl(protocol, F_SETFD, FD_CLOEXEC) == 0)
    check(dup2(STDERR_FILENO, STDOUT_FILENO) >= 0)
    fun readFd(bytes: ByteArray): Int {
        while (true) {
            val count = bytes.usePinned { read(STDIN_FILENO, it.addressOf(0), bytes.size.convert()) }
            if (count >= 0) return if (count == 0L) -1 else count.toInt()
            check(errno == EINTR) { "Jeva worker input failed: $errno" }
        }
    }
    fun writeFd(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = bytes.usePinned { write(protocol, it.addressOf(offset), (bytes.size - offset).convert()) }
            if (count < 0) { check(errno == EINTR) { "Jeva worker output failed: $errno" }; continue }
            check(count > 0) { "Jeva worker output closed" }
            offset += count.toInt()
        }
    }
    val limitMarker = (JevaWorker.FOOTPRINT_MARKER + "\n").encodeToByteArray()
    val sampleMarker = (JevaWorker.FOOTPRINT_SAMPLE_MARKER + "\n").encodeToByteArray()
    fun terminate(bytes: ByteArray): Nothing {
        // The diagnostic is best effort: a full stderr pipe must never block revocation.
        val flags = fcntl(STDERR_FILENO, F_GETFL)
        if (flags >= 0 && fcntl(STDERR_FILENO, F_SETFL, flags or O_NONBLOCK) == 0) {
            bytes.usePinned { write(STDERR_FILENO, it.addressOf(0), bytes.size.convert()) }
        }
        kill(getpid(), SIGKILL)
        _exit(137)
        error("Worker termination unexpectedly returned")
    }
    fun enforceFootprint(limit: Long) {
        try {
            memScoped {
                val bytes = alloc<ULongVar>()
                if (trikeshed_mlx_footprint(bytes.ptr) != 0) terminate(sampleMarker)
                if (bytes.value > limit.toULong()) terminate(limitMarker)
            }
        } catch (_: Throwable) { terminate(sampleMarker) }
    }
    val monitorOwner = SupervisorJob()
    val monitorScope = CoroutineScope(monitorOwner + Dispatchers.Default)
    val worker = JevaWorker { config ->
        require(config.device == "mlx") { "The native Jeva worker requires device=mlx" }
        config.mlxMaxFootprintBytes?.let { limit ->
            // Check before model loading, then keep sampling while native calls run.
            enforceFootprint(limit)
            monitorScope.launch(start = CoroutineStart.UNDISPATCHED) {
                monitorFootprint(monitorOwner, { enforceFootprint(limit) }, { terminate(sampleMarker) })
            }
        }
        val defaults = MlxMatrixOperations()
        val matrices = MlxMatrixOperations(checkpointLimitBytes = config.mlxCheckpointLimitBytes ?: defaults.checkpointLimitBytes,
            maxTokens = config.mlxMaxTokens)
        JevaInference.loadDevice(config, MlxEncoderOperations(matrices))
    }
    try {
        runBlocking {
            val source = ChunkedLineSource({ readFd(it) })
            val sink = FramedLineSink { writeFd(it) }
            while (true) when (val line = source.read(8_388_608)) {
                LineRead.Eof -> break
                LineRead.TooLong -> error("Jeva worker request exceeds frame bound")
                is LineRead.Line -> {
                    val response = try { worker.exchange(line.bytes.decodeToString(throwOnInvalidSequence = true)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        sink.write(jsonOf(_m["error" j (failure.message ?: "Jeva worker failed").take(2048)]).encodeToByteArray())
                        break
                    }
                    sink.write(response.encodeToByteArray())
                }
            }
        }
    } finally {
        runBlocking { monitorOwner.cancelAndJoin() }
        try { worker.close() } finally { close(protocol) }
    }
}
