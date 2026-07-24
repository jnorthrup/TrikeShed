package borg.trikeshed.daemon

import borg.trikeshed.jules.FlywheelDriver
import borg.trikeshed.jules.FlywheelDriver.FlywheelEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * Oroboros daemon — thin entry-point over [FlywheelDriver].
 *
 * The reactor (poll → drain → dispatch → harvest) lives in
 * [borg.trikeshed.jules.FlywheelDriver]. This object constructs it directly
 * so it can defer to [ForgeHome.defaultHome] (`~/.local/forge`) and parse
 * the daemon's full flag set.
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
 
    data class CycleReport(
        val cycleMs: Long,
        val drained: Int,
        val dispatched: Int,
        val alive: Int,
        val available: Int
    )

    @Volatile
    var lastCycleReport: CycleReport? = null

    @Volatile
    var daemonStartTime = 0L


    private const val DEFAULT_INTERVAL_MS = 30_000L
    private const val DEFAULT_MAX_SLOTS = 15

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val apiKey = System.getenv("JULES_API_KEY") ?: System.getProperty("JULES_API_KEY")
        if (apiKey.isNullOrBlank()) {
            System.err.println("[OROBOROS] JULES_API_KEY not set; the conductor cannot poll Jules. Aborting.")
            exitProcess(1)
        } 
    const val DEFAULT_INTERVAL_MS = 30_000L
    const val DEFAULT_MAX_SLOTS = 15

    data class DaemonConfig(
        val watch: Boolean,
        val intervalMs: Long,
        val maxSlots: Int,
        val positional: List<String>
    ) 

    fun parseConfig(args: Array<String>): DaemonConfig {
        var watch = true
        var intervalMs = DEFAULT_INTERVAL_MS
        var maxSlots = DEFAULT_MAX_SLOTS
        val positional = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "--once" -> { watch = false; i++ }
                "--watch" -> { watch = true; i++ }
                "--interval-ms" -> {
                    val v = args.getOrNull(++i) ?: die("--interval-ms requires a positive long")
                    intervalMs = v.toLongOrNull() ?: die("--interval-ms requires a positive long")
                    i++
                }
                "--max-slots" -> {
                    val v = args.getOrNull(++i) ?: die("--max-slots requires a positive int")
                    maxSlots = v.toIntOrNull() ?: die("--max-slots requires a positive int")
                    i++
                }
                "-h", "--help" -> { usage(); exitProcess(0) }
                else -> { positional.add(a); i++ }
            }
        }
        return DaemonConfig(watch, intervalMs, maxSlots, positional)
    }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val apiKey = System.getenv("JULES_API_KEY")
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
        // Defer to ForgeHome.defaultHome via the canonical Kotlin/JVM runtime path.
        // The TrikeShed canonical root is $HOME/.local/forge (see ForgeHome.defaultHome).
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
                } catch (e: Exception) {
                    // Ignore stream closed on exit
                }
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

        suspend fun doCycle() {
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

        doCycle()
        if (!watch) {
            traceWriter?.flush()
            traceWriter?.close()
            driver.close()
            return@runBlocking

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
                } catch (e: Exception) {
                    // Ignore closed channel exceptions during shutdown
                } finally {
                    try { client?.close() } catch (_: Exception) {}
                }
            }
        }

        try {
            while (true) {
                delay(intervalMs)
                doCycle()
            suspend fun runCycle() {
                val start = System.currentTimeMillis()
                val result = driver.cycle()
                val cycleMs = System.currentTimeMillis() - start

                val drainedMatch = Regex("drained=([-0-9]+)").find(result)
                val dispatchedMatch = Regex("dispatched=([-0-9]+)").find(result)
                val aliveMatch = Regex("alive=([0-9]+)").find(result)
                val availableMatch = Regex("available=([0-9]+)").find(result)

                val drained = drainedMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val dispatched = dispatchedMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val alive = aliveMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val available = availableMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

                lastCycleReport = CycleReport(cycleMs, drained, dispatched, alive, available)
                println("[FLYWHEEL] $result")
            }

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
