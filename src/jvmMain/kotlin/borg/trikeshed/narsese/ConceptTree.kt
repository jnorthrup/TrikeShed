package borg.trikeshed.narsese

import borg.trikeshed.collections.bits.RoaringSeries
import borg.trikeshed.ontology.SumoClassId
import borg.trikeshed.ontology.SumoCorpus

/**
 * Two concept trees, one laid over the other. The super tree is SUMO's: each class's self+ancestor closure, one
 * precomputed Roaring set per class ([SumoCorpus.closure]), the same for every text. The local tree is what induction
 * learned of a word where it was read ([Locality]): the class a myelinated production `<(&&,lemma,locality) ==> class>`
 * types it as ([rules]), else the class the word's sense row in [memory] holds ([SenseRow.holds]). Laid over the super
 * tree, the local class chooses which SUMO closure the word carries in that locality: the productions re-root a word in
 * the shared taxonomy rather than copy the taxonomy per locality.
 *
 * Across localities the productions of one word either agree or not. A word typed as one class in two or more
 * localities whose rows cannot be told apart is a [Factor]: its locality condition is redundant, and one locality-free
 * production `lemma ==> class` could stand for all of them. A word typed as different classes in localities whose rows
 * differ is a [Drift]: the sense changed with the time or the English it was read in. Differently typed localities whose
 * rows cannot be told apart are a near tie, not drift.
 */
class ConceptTree(val rules: List<EternalRule>, val memory: SenseMemory?) {

    /** The productions by condition: typing a word where it is read is one lookup. */
    private val byCondition = HashMap<String, EternalRule>(rules.size * 2).also { m -> for (r in rules) m[r.antecedent] = r }

    /** A word's class where it was read, its share there, and what typed it: `rete` (a myelinated production) or `nars` (its row). */
    class Typed(val cls: String, val e: Double, val by: String)

    /** The production typing [lemma] in [at], or null. */
    fun production(lemma: String, at: Locality): EternalRule? = byCondition[condition(lemma, at)]

    /** The class [lemma] denotes in [at]; null when neither tree's overlay types it, and the lexicon's sense stands. */
    fun typed(lemma: String, at: Locality): Typed? {
        val m = memory
        val row = m?.let { synchronized(it) { it.sense(lemma, at) } }
        production(lemma, at)?.let { r ->
            val i = row?.let { s -> (0 until s.width).firstOrNull { m.className(s.classes[it]) == r.consequent } }
            return Typed(r.consequent, if (i != null) row.share[i] else Nal.truthOf(r.evidence).expectation().toDouble(), "rete")
        }
        val s = row ?: return null
        val t = s.top.takeIf { it >= 0 && s.holds(it) } ?: return null
        return Typed(m.className(s.classes[t]), s.share[t], "nars")
    }

    /** The super tree's closure of the class [lemma] carries in [at]: the overlay's class, else the lexicon's preferred sense. */
    fun closure(lemma: String, at: Locality): RoaringSeries {
        val cls = typed(lemma, at)?.let { SumoCorpus.classifier.classId(it.cls)?.value }
            ?: SumoCorpus.nounClassId(lemma).takeIf { it >= 0 } ?: SumoCorpus.nounClassId(lemma.removeSuffix("s"))
        return if (cls >= 0) SumoCorpus.closure(cls) else RoaringSeries.EMPTY
    }

    /**
     * The classes the local tree has established in [at], with every class above them: the overlay as one super-tree
     * bitset. A word not yet typed there takes the sense these support, as a sense is read against what is believed.
     */
    fun believed(at: Locality): RoaringSeries {
        var out = RoaringSeries.EMPTY
        for (r in rules) if (conditionOf(r.antecedent)?.second == at)
            SumoCorpus.classifier.classId(r.consequent)?.value?.let { out = out or SumoCorpus.closure(it) }
        return out
    }

    /** A production holding in every locality its word was read in: [cls] across [localities], the least confident of them as [evidence]. */
    class Factor(val lemma: String, val cls: String, val localities: List<Locality>, val evidence: EvidenceCoord)

    /** A word typed as different classes in different localities: per locality, its class. */
    class Drift(val lemma: String, val classes: List<Pair<Locality, String>>)

    /** True when [lemma]'s rows in [a] and [b] cannot be told apart; true when either is missing or there is no memory. */
    private fun agree(lemma: String, a: Locality, b: Locality): Boolean {
        val m = memory ?: return true
        val local = synchronized(m) { m.contexts(lemma) }
        val ra = local.firstOrNull { it.first == a }?.second ?: return true
        val rb = local.firstOrNull { it.first == b }?.second ?: return true
        return ra.homogeneous(rb)
    }

    fun factors(): List<Factor> = byLemma().mapNotNull { (lemma, rs) ->
        if (rs.size < 2 || rs.map { it.second.consequent }.distinct().size != 1) return@mapNotNull null
        for (x in rs.indices) for (y in x + 1 until rs.size) if (!agree(lemma, rs[x].first, rs[y].first)) return@mapNotNull null
        Factor(lemma, rs[0].second.consequent, rs.map { it.first }, rs.minBy { Nal.truthOf(it.second.evidence).confidence }.second.evidence)
    }

    fun drift(): List<Drift> = byLemma().mapNotNull { (lemma, rs) ->
        var moved = false
        for (x in rs.indices) for (y in x + 1 until rs.size)
            if (rs[x].second.consequent != rs[y].second.consequent && !agree(lemma, rs[x].first, rs[y].first)) moved = true
        if (!moved) null else Drift(lemma, rs.sortedBy { it.first.decade }.map { it.first to it.second.consequent })
    }

    /** The productions grouped by word, each with the locality it holds in. */
    fun byLemma(): Map<String, List<Pair<Locality, EternalRule>>> = rules.mapNotNull { r ->
        conditionOf(r.antecedent)?.let { (lemma, at) -> lemma to (at to r) }
    }.groupBy({ it.first }, { it.second })

    companion object {
        /** The option a sense question offers for a use none of the listed senses names. */
        const val NONE = SenseMemory.NONE

        /** A sense production's condition: the word read in a locality. */
        fun condition(lemma: String, at: Locality) = "(&&,$lemma,${at.term})"

        /** The word and locality of a production's [condition], or null when it is not a sense condition. */
        fun conditionOf(term: String): Pair<String, Locality>? {
            if (!term.startsWith("(&&,") || !term.endsWith(")")) return null
            val body = term.substring(4, term.length - 1)
            val cut = body.lastIndexOf(',').takeIf { it > 0 } ?: return null
            return body.substring(0, cut) to (Locality.parse(body.substring(cut + 1)) ?: return null)
        }

        /** Class [id]'s name. */
        fun name(id: Int): String = SumoCorpus.classifier.className(SumoClassId(id))
    }
}
