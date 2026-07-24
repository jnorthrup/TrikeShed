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
 * The RGA→Jules smart loop is a follow-on cut; this daemon keeps the working
 * spine running first.
 *
 * Env: JULES_API_KEY (required)
 * Args: [forgeHome] [repoDir] — both default to ~/.local/forge_home and cwd
 */
object OroborosDaemon {

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val forgeHome = File(args.getOrNull(0) ?: System.getProperty("user.home") + "/.local/forge_home")
        val repoDir = File(args.getOrNull(1) ?: System.getProperty("user.dir"))
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
        while (true) {
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
            delay(element.defaults.pollIntervalMs)
        }
    }

    private fun headSha(repoDir: File): String = try {
        val p = ProcessBuilder("git", "rev-parse", "HEAD")
            .directory(repoDir).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().trim().ifBlank { "0000000" }
    } catch (_: Throwable) { "0000000" }
}
