package borg.trikeshed.daemon

import borg.trikeshed.jules.FlywheelDriver
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

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

    private const val DEFAULT_INTERVAL_MS = 30_000L
    private const val DEFAULT_MAX_SLOTS = 15

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val apiKey = System.getenv("JULES_API_KEY")
        if (apiKey.isNullOrBlank()) {
            System.err.println("[OROBOROS] JULES_API_KEY not set; the conductor cannot poll Jules. Aborting.")
            exitProcess(1)
        }

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

        val consecutivePollErrors = java.util.concurrent.atomic.AtomicInteger(0)
        var pollErrOccurred = false

        // Stdout observer so cycles are visible without a TUI.
        driver.subscribe { ev ->
            println("[FLY-EVENT] $ev")
            if (ev is FlywheelDriver.FlywheelEvent.PollError) {
                pollErrOccurred = true
            }
        }
        System.err.println(
            "[OROBOROS] daemon up. forgeHome=$forgeHome repo=$repoDir " +
                "intervalMs=$intervalMs maxSlots=$maxSlots mode=${if (watch) "watch" else "once"}"
        )

        pollErrOccurred = false
        println("[FLYWHEEL] " + driver.cycle())
        if (!watch) {
            driver.close()
            return@runBlocking
        }
        try {
            while (true) {
                val errors = consecutivePollErrors.get()
                val backoffMs = kotlin.math.min(intervalMs * (1L shl kotlin.math.min(errors, 30)), intervalMs * 5)
                System.err.println("[OROBOROS] backoff=${backoffMs}ms consecutiveErrors=$errors")
                delay(backoffMs)

                pollErrOccurred = false
                println("[FLYWHEEL] " + driver.cycle())

                if (pollErrOccurred) {
                    consecutivePollErrors.incrementAndGet()
                } else {
                    consecutivePollErrors.set(0)
                }
            }
        } finally {
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
