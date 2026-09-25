package borg.trikeshed.narsese

import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import java.io.File

/**
 * `skill.curate` — Hermes' curation step as an LCNC node. Reads `<profile>/skills/.usage.json`
 * (the Python curator's evidence), models each skill's lifecycle with [AttentionEconomy], and
 * tells both verdicts to the bank as tuples, so the curation and what it concludes are queryable
 * beside SUMO and admitted rules:
 *
 *     (skillState S active|stale|archived)      ; the Python clock's recorded verdict
 *     (skillModelled S active|stale|archived)   ; the economy's verdict on the same evidence
 *     (skillUses S n) (skillPatches S n) (skillPinned S)
 *
 * Read-only against the profile: no skill is moved, patched or archived here.
 */
object SkillCurateNode {
    const val TYPE = "skill.curate"

    /** Register against `$HERMES_PROFILE` (else `~/.hermes`), telling tuples into [bank]. */
    fun register(runners: MutableMap<String, LcncNodeRunner>, bank: KifKnowledgeBase) {
        val profile = System.getenv("HERMES_PROFILE")?.let(::File) ?: File(System.getProperty("user.home"), ".hermes")
        runners[TYPE] = runner(profile) { kif -> runCatching { bank.assertKif(kif) } }
    }

    fun runner(profileDir: File, into: (String) -> Unit): LcncNodeRunner = LcncNodeRunner { node, _ ->
        val daysAhead = node.params["daysAhead"]?.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis() + daysAhead * 86_400_000L
        val records = SkillUsageFeeder(profileDir).load()
        val rows = ArrayList<Map<String, Any?>>(records.size)
        val diverged = ArrayList<String>()
        for (i in 0 until records.size) {
            val r = records[i]
            val modelled = SkillUsageLedger.modelled(r, now)
            val b = AttentionEconomy.budgetOf(r.usage, now)
            val s = r.name
            into("(skillState $s ${r.recorded.name.lowercase()})")
            into("(skillModelled $s ${modelled.name.lowercase()})")
            into("(skillUses $s ${r.usage.useCount})")
            into("(skillPatches $s ${r.usage.patchCount})")
            if (r.usage.pinned) into("(skillPinned $s)")
            if (modelled != r.recorded) diverged.add(s)
            rows.add(mapOf(
                "skill" to s, "recorded" to r.recorded.name, "modelled" to modelled.name,
                "priority" to b.pf, "durability" to b.df, "uses" to r.usage.useCount,
                "patches" to r.usage.patchCount, "pinned" to r.usage.pinned, "neverUsed" to r.neverUsed,
            ))
        }
        mapOf("skills" to rows, "divergent" to diverged, "count" to rows.size)
    }
}
