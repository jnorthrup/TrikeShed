package borg.trikeshed.util.oroboros

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * Daemon shell shared by both production entrypoints:
 *  - [OroborosMain] — file-watch-driven ingest of a source tree
 *  - [borg.trikeshed.daemon.OroborosDaemon] — poll-driven flywheel over Jules REST
 *
 * Each daemon owns its own cycle body and its own `Map<String, Long>` report shape
 * (the daemon uses `t, c, d, p, a, v, e`; the file daemon uses `t, c, files, bytes`).
 * This shell owns the surfaces both share:
 *
 *  1. JSONL cycle trace at `<forgeHome>/oroboros-cycles.jsonl`. Ring-backup at 10_000
 *     lines (rotate to `.jsonl.1`, restart). A shutdown hook flushes the writer so the
 *     final lines always land.
 *  2. Health socket at `<forgeHome>/.oroboros/health.sock`. Per-accept reply
 *     `ALIVE <uptimeMs> <fields...>` where `fields` is supplied by the daemon's
 *     cycle body via [ReportSupplier].
 *  3. Shutdown hook that closes the writer + socket on JVM exit.
 *
 * Construction: [start] opens the trace, binds the socket, registers the shutdown
 * hook, and returns a [Shell]. The daemon's main loop calls [Shell.writeCycle] per
 * tick and [Shell.close] on shutdown. The Socket listener is launched on [scope]
 * with a dedicated child Job so the listener can be cancelled without tearing down
 * the daemon's own coroutine scope.
 */
object OroborosShell {

    private const val RING_LIMIT = 10_000

    /** Per-cycle field values. Rendered as a space-separated list after `ALIVE uptime`. */
    fun interface ReportSupplier {
        fun fields(): List<String>
    }

    /**
     * The wrapper around the underlying writers / socket. The trace writer is held
     * as `var` (NOT `val`) so the ring-rotate path can swap it.
     */
    class Shell internal constructor(
        private val forgeHome: File,
        private val traceFile: File,
        private var traceLineCount: Int,
        private var writer: BufferedWriter?,
        private val serverSocket: ServerSocketChannel?,
        private val healthSock: File,
        private val healthJob: Job,
        private val reportSupplier: ReportSupplier,
        private val startMs: Long,
    ) {
        /**
         * Write one cycle line. The `t` field is the timestamp; `fields` is the
         * daemon-specific body. Keys must be unique. The `t` field is preserved
         * (overridden if [fields] also contains it) so the cycle trace is stable.
         */
        fun writeCycle(now: Long, fields: Map<String, Long>) {
            val sb = StringBuilder()
            sb.append('{').append("\"t\":").append(now)
            for ((k, v) in fields) {
                sb.append(',').append('"').append(k).append("\":").append(v)
            }
            sb.append('}')
            val json = sb.toString()
            try {
                if (traceLineCount >= RING_LIMIT) {
                    writer?.close()
                    val backup = File(traceFile.parentFile, traceFile.name + ".1")
                    traceFile.renameTo(backup)
                    writer = FileOutputStream(traceFile, false).bufferedWriter()
                    traceLineCount = 0
                }
                writer?.let {
                    it.write(json)
                    it.write("\n")
                    it.flush()
                    traceLineCount++
                }
            } catch (e: Exception) {
                System.err.println("[OROBOROS] warning: failed to write trace file: ${e.message}")
            }
        }

        /** Snapshot of fields for the test seam. */
        fun reportFields(): List<String> = reportSupplier.fields()

        fun close() {
            healthJob.cancel()
            try { serverSocket?.close() } catch (_: Exception) {}
            if (healthSock.exists()) healthSock.delete()
            try { writer?.flush(); writer?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Start the daemon shell. Returns a [Shell] handle that the daemon uses for
     * per-cycle trace writes and whose [Shell.close] shuts down health + trace.
     *
     * Listener is a child of [scope] so cancelling it does not tear down the daemon.
     */
    fun start(forgeHome: File, reportSupplier: ReportSupplier, scope: CoroutineScope): Shell {
        forgeHome.mkdirs()

        val traceFile = File(forgeHome, "oroboros-cycles.jsonl")
        val traceLineCount = if (traceFile.exists()) traceFile.readLines().size else 0
        val writer: BufferedWriter? = try {
            FileOutputStream(traceFile, true).bufferedWriter()
        } catch (e: Exception) {
            System.err.println("[OROBOROS] warning: failed to open trace file: ${e.message}")
            null
        }
        Runtime.getRuntime().addShutdownHook(Thread {
            try { writer?.flush(); writer?.close() } catch (_: Exception) {}
        })

        val oroborosDir = File(forgeHome, ".oroboros")
        oroborosDir.mkdirs()
        val healthSock = File(oroborosDir, "health.sock")
        if (healthSock.exists()) healthSock.delete()
        val serverSocket = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        serverSocket.bind(UnixDomainSocketAddress.of(healthSock.toPath()))

        val startMs = System.currentTimeMillis()
        val healthJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                var client: SocketChannel? = null
                try {
                    client = serverSocket.accept()
                    val sb = StringBuilder()
                    sb.append("ALIVE ").append(System.currentTimeMillis() - startMs)
                    for (v in reportSupplier.fields()) sb.append(' ').append(v)
                    sb.append('\n')
                    val buf = ByteBuffer.wrap(sb.toString().toByteArray())
                    while (buf.hasRemaining()) client.write(buf)
                } catch (e: Exception) { /* ignore */ }
                finally { try { client?.close() } catch (_: Exception) {} }
            }
        }

        return Shell(
            forgeHome = forgeHome,
            traceFile = traceFile,
            traceLineCount = traceLineCount,
            writer = writer,
            serverSocket = serverSocket,
            healthSock = healthSock,
            healthJob = healthJob,
            reportSupplier = reportSupplier,
            startMs = startMs,
        )
    }

    /** Convenience constructor used when only the daemon-side report is needed. */
    fun daemonReportSupplier(block: () -> List<String>): ReportSupplier = ReportSupplier(block)
}
