package borg.trikeshed.narsese

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * One-shot CLI: model Hermes' curation clock as [AttentionEconomy] floor
 * crossings and report where the two disagree. Read-only — proposes nothing,
 * writes nothing, touches no live skill.
 *
 * Hermes marks a skill stale at 30 days idle and archives at 90. The economy
 * says the same thing as one decaying priority, but it also reads *use* —
 * which the clock ignores entirely. Divergence is where that extra evidence
 * changes the verdict.
 *
 * Usage: CuratorStateModelCli [profileDir] [daysAhead]
 *
 * [daysAhead] projects the clock forward without touching anything. A young
 * corpus reads identically under both models because recency has not moved
 * yet; the two only part once idleness accumulates.
 */
object CuratorStateModelCli {

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val profileDir = args.firstOrNull()?.let { File(it) }
            ?: File(System.getProperty("user.home"), ".hermes")
        val daysAhead = args.getOrNull(1)?.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis() + daysAhead * 24L * 3600 * 1000
        if (daysAhead != 0L) println("[MODEL] projecting $daysAhead days ahead")

        val records = SkillUsageFeeder(profileDir).load()
        val src = File(profileDir, "skills/.usage.json")
        println("[MODEL] ${records.size} usage records from $src")
        if (records.size == 0) {
            println("[MODEL] nothing to model — no .usage.json under $profileDir")
            return@runBlocking
        }

        var neverUsed = 0
        var pinned = 0
        val byRecorded = HashMap<CurationState, Int>()
        val byModelled = HashMap<CurationState, Int>()
        for (i in 0 until records.size) {
            val r = records[i]
            if (r.neverUsed) neverUsed++
            if (r.usage.pinned) pinned++
            byRecorded[r.recorded] = (byRecorded[r.recorded] ?: 0) + 1
            val m = SkillUsageLedger.modelled(r, now)
            byModelled[m] = (byModelled[m] ?: 0) + 1
        }
        println("[MODEL] never used: $neverUsed   pinned: $pinned")
        println("[MODEL] hermes clock says: ${render(byRecorded)}")
        println("[MODEL] economy says:      ${render(byModelled)}")

        val diverged = SkillUsageLedger.divergent(records, now)
        println("[MODEL] divergences: ${diverged.size}/${records.size}")
        for (i in 0 until diverged.size) {
            val r = diverged[i]
            val b = AttentionEconomy.budgetOf(r.usage, now)
            val exempt = if (r.neverUsed) " [hermes exempts: never used]" else ""
            println(
                "[MODEL]   ${r.name}: ${r.recorded} -> ${SkillUsageLedger.modelled(r, now)}" +
                    "  p=${fmt(b.pf)} d=${fmt(b.df)} q=${fmt(b.qf)}" +
                    "  use=${r.usage.useCount} view=${r.usage.viewCount} patch=${r.usage.patchCount}$exempt"
            )
        }
    }

    private fun render(counts: Map<CurationState, Int>): String =
        CurationState.entries.joinToString("  ") { "${it.name.lowercase()}=${counts[it] ?: 0}" }

    private fun fmt(f: Float): String {
        val scaled = ((f * 1000f) + 0.5f).toInt()
        return "${scaled / 1000}.${(scaled % 1000).toString().padStart(3, '0')}"
    }
}
