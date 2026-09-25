package borg.trikeshed.narsese

import borg.trikeshed.collections.bits.IntAccumulator
import borg.trikeshed.collections.bits.RoaringSeries
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.ontology.SumoCorpus
import java.io.File

/**
 * `skill.overlap` — the consolidation half of Hermes' curator without a model call. Each
 * `SKILL.md` under `<profile>/skills` becomes one Roaring set of SUMO class ids: every word that
 * resolves through the WordNet noun lexicon. Classes carried by more than [common] of the skills
 * are dropped as vocabulary, not subject. Pairs whose Jaccard over the remaining classes reaches
 * `min` are consolidation candidates; each is told to the bank as
 *
 *     (skillOverlap A B "jaccard")
 *
 * with the shared classes returned for the reason. Read-only against the profile.
 */
object SkillOverlapNode {
    const val TYPE = "skill.overlap"
    private val WORD = Regex("[a-z]{3,}")

    /** First word seen for each class id, across every skill read by [classSets]; the reason text for a shared class. */
    val wordOf = HashMap<Int, String>()

    /** Skill name → its SUMO class set, for every `SKILL.md` under [skillsDir] outside `.archive`. */
    fun classSets(skillsDir: File): List<Pair<String, RoaringSeries>> =
        skillsDir.walkTopDown()
            .onEnter { !it.name.startsWith(".") }
            .filter { it.name == "SKILL.md" }
            .map { f ->
                val acc = IntAccumulator(256)
                for (m in WORD.findAll(f.readText().lowercase())) {
                    val w = m.value
                    val id = SumoCorpus.nounClassId(w).takeIf { it >= 0 } ?: SumoCorpus.nounClassId(w.removeSuffix("s"))
                    if (id >= 0) { acc.add(id); wordOf.getOrPut(id) { w } }
                }
                f.parentFile.name to acc.toRoaring()
            }
            .sortedBy { it.first }
            .toList()

    /** Classes present in more than [common] of [sets]. */
    fun vocabulary(sets: List<Pair<String, RoaringSeries>>, common: Float): RoaringSeries {
        val df = HashMap<Int, Int>()
        for ((_, s) in sets) s.forEach { df[it] = (df[it] ?: 0) + 1 }
        val cut = (sets.size * common).toInt()
        return RoaringSeries.of(df.filterValues { it > cut }.keys)
    }

    fun register(runners: MutableMap<String, LcncNodeRunner>, bank: borg.trikeshed.kif.KifKnowledgeBase) {
        val profile = System.getenv("HERMES_PROFILE")?.let(::File) ?: File(System.getProperty("user.home"), ".hermes")
        runners[TYPE] = runner(profile) { kif -> runCatching { bank.assertKif(kif) } }
    }

    fun runner(profileDir: File, into: (String) -> Unit): LcncNodeRunner = LcncNodeRunner { node, _ ->
        val min = node.params["min"]?.toFloatOrNull() ?: 0.2f
        val common = node.params["common"]?.toFloatOrNull() ?: 0.25f
        val raw = classSets(File(profileDir, "skills"))
        val vocab = vocabulary(raw, common)
        val sets = raw.map { (n, s) -> n to (s andNot vocab) }
        val pairs = ArrayList<Map<String, Any?>>()
        for (i in sets.indices) for (j in i + 1 until sets.size) {
            val (a, sa) = sets[i]; val (b, sb) = sets[j]
            if (sa.isEmpty() || sb.isEmpty()) continue
            val shared = sa and sb
            val jaccard = shared.cardinality.toFloat() / (sa or sb).cardinality
            if (jaccard < min) continue
            into("(skillOverlap $a $b \"${"%.2f".format(jaccard)}\")")
            pairs.add(mapOf(
                "a" to a, "b" to b, "jaccard" to jaccard,
                "shared" to shared.toIntArray().take(12).map { "${wordOf[it]}:${SumoCorpus.classifier.className(borg.trikeshed.ontology.SumoClassId(it))}" },
            ))
        }
        pairs.sortByDescending { it["jaccard"] as Float }
        mapOf("pairs" to pairs, "skills" to sets.size, "vocabulary" to vocab.cardinality)
    }
}
