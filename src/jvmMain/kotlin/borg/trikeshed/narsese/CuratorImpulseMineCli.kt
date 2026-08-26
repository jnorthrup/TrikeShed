package borg.trikeshed.narsese

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * One-shot CLI: mine the REAL hermes curator ground truth (ledger + state.db
 * transcripts) through [CuratorImpulseFeeder] and print the landed signals.
 * No daemon, no network, no bag — pure read-side proof the pipeline sees the
 * live corpus. Usage: CuratorImpulseMineCli <profileDir>
 */
object CuratorImpulseMineCli {

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val profileDir = args.firstOrNull()?.let { File(it) }
            ?: File(System.getProperty("user.home"), ".hermes/profiles/src-trikeshed")
        val feeder = CuratorImpulseFeeder(profileDir)
        val impulses = feeder.loadImpulses()
        println("[MINE] ledger: ${impulses.size} impulses from ${File(profileDir, "skills/.curator_ledger.jsonl")}")
        for (i in 0 until minOf(5, impulses.size)) {
            val imp = impulses[i]
            println("[MINE]   ${imp.kind} ${imp.subject} (evidence session: ${imp.proposalCid ?: "none"})")
        }
        val scenarios = feeder.loadScenarios(impulses)
        println("[MINE] transcripts: ${scenarios.size} replayable scenarios from ${File(profileDir, "state.db")}")
        var supported = 0
        var refuted = 0
        for (i in 0 until scenarios.size) {
            when (ScenarioTranscripts.verdictOfReplay(scenarios[i].turns)) {
                HindsightVerdict.SUPPORTED -> supported++
                HindsightVerdict.REFUTED -> refuted++
                HindsightVerdict.NEUTRAL -> {}
            }
        }
        println("[MINE] verdicts: $supported SUPPORTED / $refuted REFUTED / ${scenarios.size - supported - refuted} NEUTRAL")
        for (i in 0 until scenarios.size) {
            val s = scenarios[i]
            println("[MINE]   ${s.scenarioId} (${s.turns.size} turns) → ${ScenarioTranscripts.verdictOfReplay(s.turns)}")
        }
    }
}
