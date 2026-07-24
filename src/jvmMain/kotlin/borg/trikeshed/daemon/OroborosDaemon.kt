package borg.trikeshed.daemon

import borg.trikeshed.jules.FlywheelDriver
import borg.trikeshed.jules.FlywheelDriver.FlywheelEvent
import borg.trikeshed.util.io.ForgeCliArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlin.system.exitProcess

/**
 * Oroboros daemon — thin entry-point over [FlywheelDriver].
 *
 * Env: JULES_API_KEY (required)
 * Flags:
 *   --once                  single cycle then exit (default: --watch loops)
 *   --watch                 loop forever
 *   --interval-ms <N>       poll cadence (default = 30000)
 *   --max-slots <N>         live session cap (default = 15)
 * Positional args (must come last):
 *   forgeHome               default = ~/.local/forge (ForgeHome.defaultHome)
 *   repoDir                 default = cwd
 */
object OroborosDaemon {

    const val DEFAULT_INTERVAL_MS = 30_000L
    const val DEFAULT_MAX_SLOTS = 15

    data class CycleReport(
        val cycleMs: Long,
        val drained: Int,
        val dispatched: Int,
        val alive: Int,
        val available: Int
    )

    data class DaemonConfig(
        val watch: Boolean,
        val intervalMs: Long,
        val maxSlots: Int,
        val positional: List<String>
    )

    @Volatile
    var lastCycleReport: CycleReport? = null

    @Volatile
    var daemonStartTime = 0L

    fun parseConfig(args: Array<String>): DaemonConfig {
        var watch = true
        var intervalMs = DEFAULT_INTERVAL_MS
        var maxSlots = DEFAULT_MAX_SLOTS
        val positional = mutableListOf<String>()

        val flags = listOf(
            ForgeCliArgs.Flag(name = "--once") { _, i -> watch = false; i + 1 },
            ForgeCliArgs.Flag(name = "--watch") { _, i -> watch = true; i + 1 },
            ForgeCliArgs.Flag(name = "--interval-ms", withValue = true) { a, i ->
                val v = a[i].toLongOrNull() ?: die("--interval-ms requires a positive long")
                intervalMs = v
                i + 1
            },
            ForgeCliArgs.Flag(name = "--max-slots", withValue = true) { a, i ->
                val v = a[i].toIntOrNull() ?: die("--max-slots requires a positive int")
                maxSlots = v
                i + 1
            },
        )

        when (val r = ForgeCliArgs.parse(args.toList(), flags)) {
            is ForgeCliArgs.Result.Parsed -> positional.addAll(r.remaining)
            ForgeCliArgs.Result.Help -> { usage(); exitProcess(0) }
            is ForgeCliArgs.Result.Error -> die(r.message)
        }
        return DaemonConfig(watch, intervalMs, maxSlots, positional)
    }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val apiKey = System.getenv("JULES_API_KEY") ?: System.getProperty("JULES_API_KEY")
        if (apiKey.isNullOrBlank()) {
            System.err.println("[OROBOROS] JULES_API_KEY not set; the conductor cannot poll Jules. Aborting.")
            exitProcess(1)
        }

        val config = parseConfig(args)
        val watch = config.watch
        val intervalMs = config.intervalMs
        val maxSlots = config.maxSlots
        val positional = config.positional

        val home = System.getProperty("user.home")
            ?: die("System property user.home not set")
        val canonicalForge = File(home, ".local/forge")
        val forgeHome = File(positional.getOrNull(0) ?: canonicalForge.absolutePath)
        val repoDir = File(positional.getOrNull(1) ?: System.getProperty("user.dir"))
        if (!repoDir.resolve(".git").exists()) {
            System.err.println("[OROBOROS] $repoDir is not a git work tree. Aborting.")
            exitProcess(1)
        }
        forgeHome.mkdirs()

        val driver = FlywheelDriver(
            apiKey = apiKey,
            repoDir = repoDir,
            forgeDir = forgeHome,
            intervalMs = intervalMs,
            maxSlots = maxSlots,
        )

        val traceFile = File(forgeHome, "oroboros-cycles.jsonl")
        var traceLineCount = if (traceFile.exists()) traceFile.readLines().size else 0
        var traceWriter: BufferedWriter? = null
        try {
            traceWriter = FileOutputStream(traceFile, true).bufferedWriter()
            Runtime.getRuntime().addShutdownHook(Thread {
                try {
                    traceWriter?.flush()
                    traceWriter?.close()
                } catch (e: Exception) { /* ignore */ }
            })
        } catch (e: Exception) {
            System.err.println("[OROBOROS] warning: failed to open trace file: ${e.message}")
        }

        var pollErrors = 0
        // Stdout observer so cycles are visible without a TUI.
        driver.subscribe { ev ->
            println("[FLY-EVENT] $ev")
            if (ev is FlywheelEvent.PollError) pollErrors++
        }
        System.err.println(
            "[OROBOROS] daemon up. forgeHome=$forgeHome repo=$repoDir " +
                "intervalMs=$intervalMs maxSlots=$maxSlots mode=${if (watch) "watch" else "once"}"
        )

        daemonStartTime = System.currentTimeMillis()
        lastCycleReport = null

        val oroborosDir = File(forgeHome, ".oroboros")
        oroborosDir.mkdirs()
        val healthSock = File(oroborosDir, "health.sock")
        if (healthSock.exists()) healthSock.delete()

        val serverSocket = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        serverSocket.bind(UnixDomainSocketAddress.of(healthSock.toPath()))

        val healthJob = launch(Dispatchers.IO) {
            while (isActive) {
                var client: SocketChannel? = null
                try {
                    client = serverSocket.accept()
                    val report = lastCycleReport
                    val uptimeMs = System.currentTimeMillis() - daemonStartTime
                    val msg = if (report != null) {
                        "ALIVE $uptimeMs ${report.cycleMs} ${report.drained} ${report.dispatched} ${report.alive} ${report.available}\n"
                    } else {
                        "ALIVE $uptimeMs -1 -1 -1 -1 -1\n"
                    }
                    val buf = ByteBuffer.wrap(msg.toByteArray())
                    while (buf.hasRemaining()) {
                        client.write(buf)
                    }
                } catch (e: Exception) { /* ignore */ }
                finally {
                    try { client?.close() } catch (_: Exception) {}
                }
            }
        }

        suspend fun runCycle() {
            val t0 = System.currentTimeMillis()
            val startPollErrors = pollErrors
            val summary = driver.cycle()
            val cycleMs = System.currentTimeMillis() - t0
            val cyclePollErrors = pollErrors - startPollErrors
            println("[FLYWHEEL] $summary")

            var d = 0; var p = 0; var a = 0; var v = 0
            Regex("drained=(\\d+)").find(summary)?.let { d = it.groupValues[1].toInt() }
            Regex("dispatched=(\\d+)").find(summary)?.let { p = it.groupValues[1].toInt() }
            Regex("alive=(\\d+)").find(summary)?.let { a = it.groupValues[1].toInt() }
            Regex("available=(\\d+)").find(summary)?.let { v = it.groupValues[1].toInt() }

            lastCycleReport = CycleReport(cycleMs, d, p, a, v)
            val json = "{\"t\":$t0,\"c\":$cycleMs,\"d\":$d,\"p\":$p,\"a\":$a,\"v\":$v,\"e\":$cyclePollErrors}"
            try {
                if (traceLineCount >= 10000) {
                    traceWriter?.close()
                    val backup = File(traceFile.parentFile, traceFile.name + ".1")
                    traceFile.renameTo(backup)
                    traceWriter = FileOutputStream(traceFile, false).bufferedWriter()
                    traceLineCount = 0
                }
                traceWriter?.let {
                    it.write(json)
                    it.write("\n")
                    traceLineCount++
                }
            } catch (e: Exception) {
                System.err.println("[OROBOROS] warning: failed to write trace file: ${e.message}")
            }
        }

        try {
            runCycle()
            if (watch) {
                while (true) {
                    delay(intervalMs)
                    runCycle()
                }
            }
        } finally {
            healthJob.cancel()
            try { serverSocket.close() } catch (_: Exception) {}
            if (healthSock.exists()) healthSock.delete()
            try { traceWriter?.flush(); traceWriter?.close() } catch (_: Exception) {}
            driver.close()
        }
    }

    private fun die(msg: String): Nothing {
        System.err.println("[OROBOROS] $msg")
        usage()
        exitProcess(2)
    }

    private fun usage() {
        System.err.println(
            """usage: OroborosDaemon [--once | --watch] [--interval-ms N] [--max-slots N] [forgeHome] [repoDir]
              env: JULES_API_KEY (required)
              forgeHome default: ~/.local/forge (ForgeHome.defaultHome)
              repoDir  default: cwd"""
        )
    }
}
