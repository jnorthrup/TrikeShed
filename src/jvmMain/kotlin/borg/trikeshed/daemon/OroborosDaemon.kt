/* Copyright (c) 2017 TrikeShed Contributors. AGPLv3 — see LICENSE. */
package borg.trikeshed.daemon

import borg.trikeshed.flywheel.Flywheel
import borg.trikeshed.flywheel.FlywheelElement
import borg.trikeshed.jules.JulesConductor
import borg.trikeshed.jules.JulesRestClient
import borg.trikeshed.utils.kanban.JulesBoardStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * Oroboros daemon — one JVM that owns the live wheel.
 *
 * Cycle:
 *   (1) JulesConductor.pollOnce()        → answer AWAITING, drain COMPLETED
 *   (2) Flywheel.cycle()                  → dispatch WorkQueued, simulate harvest
 *   (3) write trajectory.json             → FlywheelTui renders the loop
 *
 * Working spine only. RGA→Jules smart-loop libs (AcpToolRga, RgaConvergence,
 * RgaTaskPromoter) are not on disk at this HEAD; the convergence-driven
 * dispatch is a separate cut that depends on those libs being recovered or
 * rewritten.
 *
 * Env: JULES_API_KEY (required)
 * Args:
 *   --once                  run a single cycle and exit
 *   --watch                 loop forever (default)
 *   --interval-ms <N>       poll interval (default = FlywheelElement.pollIntervalMs)
 *   --home <path>           forge home (default = ~/.local/forge_home, canonical)
 *   --repo <path>           repo work tree (default = cwd)
 */
object OroborosDaemon {

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val parsed = parseArgs(args)
        val forgeHome = parsed.home
        val repoDir = parsed.repo
        val intervalMs = parsed.intervalMs
        val once = parsed.once
        val apiKey = System.getenv("JULES_API_KEY")

        if (apiKey.isNullOrBlank()) {
            System.err.println("[OROBOROS] JULES_API_KEY not set; the conductor cannot poll Jules. Aborting.")
            exitProcess(1)
        }
        if (!repoDir.resolve(".git").exists()) {
            System.err.println("[OROBOROS] $repoDir is not a git work tree. Aborting.")
            exitProcess(1)
        }

        forgeHome.mkdirs()

        val julesClient = JulesRestClient(apiKey)
        val store = JulesBoardStore(forgeHome)
        val headShaProvider: () -> String = { headSha(repoDir) }

        val element = FlywheelElement()
        val flywheel = Flywheel(store, repoDir, element)
        val conductor = JulesConductor(julesClient, headShaProvider, store)

        System.err.println("[OROBOROS] daemon up. forgeHome=$forgeHome repo=$repoDir maxLive=${element.defaults.maxLive} pollIntervalMs=${element.defaults.pollIntervalMs} simBudgetMs=${element.defaults.sessionSimulationMs}")

        var cycle = 0
        do {
            cycle++
            val t0 = System.currentTimeMillis()
            try {
                conductor.pollOnce()
                val result = flywheel.cycle()
                val elapsed = System.currentTimeMillis() - t0
                System.err.println("[FLY-CYCLE] #$cycle dispatched=${result.dispatched} harvested=${result.harvested} live=${result.liveCount} landed=${result.landedCount} elapsed=${elapsed}ms")
            } catch (t: Throwable) {
                System.err.println("[OROBOROS] cycle error: ${t::class.simpleName}: ${t.message}")
            }
            if (!once) delay(intervalMs)
        } while (!once)
    }

    private data class ParsedArgs(
        val home: File,
        val repo: File,
        val intervalMs: Long,
        val once: Boolean,
    )

    private fun parseArgs(args: Array<String>): ParsedArgs {
        val canonicalHome = File(System.getProperty("user.home"), ".local/forge_home")
        var home: File = canonicalHome
        var repo: File = File(System.getProperty("user.dir"))
        var intervalMs: Long? = null
        var once = false
        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "--once" -> once = true
                "--watch" -> once = false
                "--interval-ms" -> { intervalMs = args.getOrNull(++i)?.toLongOrNull(); if (intervalMs == null) die("--interval-ms requires a positive long") }
                "--home" -> { val p = args.getOrNull(++i) ?: die("--home requires a path"); home = File(p) }
                "--repo" -> { val p = args.getOrNull(++i) ?: die("--repo requires a path"); repo = File(p) }
                "-h", "--help" -> { printUsage(); exitProcess(0) }
                else -> die("unknown arg: $a")
            }
            i++
        }
        val poll = intervalMs ?: run {
            // Default: read from the FlywheelElement defaults so sibling reactors share one source of truth.
            FlywheelElement().defaults.pollIntervalMs
        }
        return ParsedArgs(home, repo, poll, once)
    }

    private fun die(msg: String): Nothing {
        System.err.println("[OROBOROS] $msg")
        printUsage()
        exitProcess(2)
    }

    private fun printUsage() {
        System.err.println(
            """usage: OroborosDaemon [--once | --watch] [--interval-ms N] [--home PATH] [--repo PATH]
              env: JULES_API_KEY (required)"""
        )
    }

    private fun headSha(repoDir: File): String = try {
        val p = ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(repoDir).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().trim().ifBlank { "0000000" }
    } catch (_: Throwable) { "0000000" }
}
