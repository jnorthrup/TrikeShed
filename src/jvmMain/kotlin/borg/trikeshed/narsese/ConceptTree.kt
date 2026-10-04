package borg.trikeshed.narsese

import borg.trikeshed.collections.bits.RoaringSeries
import borg.trikeshed.lib.toSeries
import borg.trikeshed.ontology.SumoClassId
import borg.trikeshed.ontology.SumoCorpus

/**
 * Two concept trees, one laid over the other. The super tree is SUMO's: each class's self+ancestor closure, one
 * precomputed Roaring set per class ([SumoCorpus.closure]), the same for every text. The local tree is what induction
 * learned of a word where it was read ([Locality]): the class a myelinated production `<(&&,lemma,locality) ==> class>`
 * types it as ([rules], fired through [rete]), else the class a NARS belief in [memory] holds at [holds] expectation.
 * Laid over the super tree, the local class chooses which SUMO closure the word carries in that locality: the
 * productions re-root a word in the shared taxonomy rather than copy the taxonomy per locality.
 *
 * Across localities the productions of one word either agree or not. A word typed as one class in two or more
 * localities is a [Factor]: its locality condition is redundant, and one locality-free production `lemma ==> class`
 * could stand for all of them. A word typed as different classes in different localities is a [Drift]: the sense
 * changed with the time or the English it was read in.
 */
class ConceptTree(val rules: List<EternalRule>, val memory: SenseMemory?, val holds: Double) {

    val rete = CausalityRete(rules.toSeries())

    /** A word's class where it was read, and what typed it: `rete` (a myelinated production) or `nars` (a belief). */
    class Typed(val cls: String, val e: Double, val by: String)

    /** The production typing [lemma] in [at], or null. */
    fun production(lemma: String, at: Locality): EternalRule? {
        val term = condition(lemma, at)
        val fired = rete.fire(listOf(ReteAssertion(term, term, 0L, EvidenceCoord.EMPTY, RelationKind.MATCH)).toSeries())
        return if (fired.a > 0) fired.b(0).rule else null
    }

    /** The class [lemma] denotes in [at]; null when neither tree's overlay types it, and the lexicon's sense stands. */
    fun typed(lemma: String, at: Locality): Typed? {
        production(lemma, at)?.let { return Typed(it.consequent, Nal.truthOf(it.evidence).expectation().toDouble(), "rete") }
        val m = memory ?: return null
        val best = synchronized(m) { m.senses(lemma, at).firstOrNull() } ?: return null
        val e = Nal.truthOf(best.second).expectation()
        return if (best.first != NONE && e >= holds) Typed(best.first, e.toDouble(), "nars") else null
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

    fun factors(): List<Factor> = byLemma().mapNotNull { (lemma, rs) ->
        if (rs.size < 2 || rs.map { it.second.consequent }.distinct().size != 1) null
        else Factor(lemma, rs[0].second.consequent, rs.map { it.first },
            rs.minBy { Nal.truthOf(it.second.evidence).confidence }.second.evidence)
    }

    fun drift(): List<Drift> = byLemma().mapNotNull { (lemma, rs) ->
        if (rs.map { it.second.consequent }.distinct().size < 2) null
        else Drift(lemma, rs.sortedBy { it.first.decade }.map { it.first to it.second.consequent })
    }

    /** The productions grouped by word, each with the locality it holds in. */
    fun byLemma(): Map<String, List<Pair<Locality, EternalRule>>> = rules.mapNotNull { r ->
        conditionOf(r.antecedent)?.let { (lemma, at) -> lemma to (at to r) }
    }.groupBy({ it.first }, { it.second })

    companion object {
        /** The option a sense question offers for a use none of the listed senses names. */
        const val NONE = "none"

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
