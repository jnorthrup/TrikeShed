package borg.trikeshed.narsese

import borg.trikeshed.collections.bits.IntAccumulator
import borg.trikeshed.collections.bits.RoaringSeries
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.ontology.SumoClassId
import borg.trikeshed.ontology.SumoCorpus
import java.io.File

/**
 * `skill.overlap` — the consolidation half of Hermes' curator without a model call.
 *
 * Each `SKILL.md` under `<profile>/skills` is a set of SUMO concepts: every word that resolves
 * through the WordNet noun lexicon, together with every concept it inherits (SUMO subclass is
 * transitive, so a skill about `Debugging` is also about its superclasses). The ancestor closure
 * is the dense bitset tree; it only assists — one OR per concept instead of a hierarchy walk.
 * Concepts held by more than [common] of the skills are dropped as vocabulary, not subject.
 *
 * Similarity is NAL evidence over those concepts: each concept both skills hold is positive
 * evidence for ⟨A ↔ B⟩, each held by only one is negative. Pairs whose frequency reaches `min`
 * are consolidation candidates, told to the bank as
 *
 *     (skillOverlap A B "f" "c")
 *
 * with the most specific shared concepts — those no other shared concept specialises — as the
 * reason. Read-only against the profile.
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
                f.parentFile.name to inherited(acc.toRoaring())
            }
            .sortedBy { it.first }
            .toList()

    /** [direct] together with every SUMO ancestor of each of its concepts. */
    fun inherited(direct: RoaringSeries): RoaringSeries {
        val sumo = SumoCorpus.classifier
        var out = direct
        direct.forEach { out = out or sumo.ancestors(SumoClassId(it)) }
        return out
    }

    /** The members of [shared] that no other member of [shared] specialises. */
    fun specific(shared: RoaringSeries): RoaringSeries {
        val sumo = SumoCorpus.classifier
        var general = RoaringSeries.EMPTY
        shared.forEach { general = general or sumo.ancestors(SumoClassId(it)) }
        return shared andNot general
    }

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
            val union = (sa or sb).cardinality.toLong()
            val evidence = EvidenceCoord(shared.cardinality * Nal.UNIT, (union - shared.cardinality) * Nal.UNIT)
            val t = Nal.truthOf(evidence)
            if (t.frequency < min) continue
            into("(skillOverlap $a $b \"${"%.2f".format(t.frequency)}\" \"${"%.2f".format(t.confidence)}\")")
            pairs.add(mapOf(
                "a" to a, "b" to b, "frequency" to t.frequency, "confidence" to t.confidence,
                "specific" to specific(shared).toIntArray().map { id ->
                    val c = SumoCorpus.classifier.className(SumoClassId(id))
                    wordOf[id]?.let { "$it:$c" } ?: c
                },
            ))
        }
        pairs.sortByDescending { it["frequency"] as Float }
        mapOf("pairs" to pairs, "skills" to sets.size, "vocabulary" to vocab.cardinality)
    }
}
