package borg.trikeshed.narsese

import borg.trikeshed.collections.bits.RoaringSeries
import borg.trikeshed.graal.subvm.CoreNlpRuntime
import borg.trikeshed.lib.j
import borg.trikeshed.ontology.SumoClassId
import borg.trikeshed.ontology.SumoCorpus
import borg.trikeshed.ontology.SumoMask
import java.io.File

/**
 * CoreNLP → NAL evidence → eternal promotion → rete, with SUMO as the alpha network.
 *
 * Each line is parsed by CoreNLP; [NlpcoreAxiomatics] yields candidate implications. The
 * antecedent lemma resolves to a SUMO preorder class id through the WordNet noun mapping;
 * the consequent keeps its lemma. Candidates with the same (class id, lemma) revise one
 * evidence base; a rule whose NAL confidence reaches `promote` is admitted to [CausalityRete].
 * An `ask` class matches rules by one Roaring AND of its self+ancestor ids against the
 * admitted antecedent ids.
 *
 * Usage: CorenlpReteCli <corpus.txt> <promote 0..1> <askClass>...
 */
object CorenlpReteCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val lines = File(args[0]).readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val promote = args[1].toFloat()
        val asks = args.drop(2)

        val sumo = SumoCorpus.pinned
        val lexicon = SumoCorpus.nounClassIds
        fun classOf(lemma: String): Int? = lemma.lowercase().let { lexicon[it] ?: lexicon[it.removeSuffix("s")] }
        fun name(id: Int) = sumo.className(SumoClassId(id))

        val evidence = LinkedHashMap<Pair<Int, String>, EvidenceCoord>()
        val t0 = System.nanoTime()
        CoreNlpRuntime().use { nlp ->
            for (line in lines) {
                val doc = nlp.analyze(line)
                val lemmaOf = HashMap<String, String>()
                for (s in doc.sentences.values()) for (t in s.tokens.values()) lemmaOf[t.word] = t.lemma.ifBlank { t.word }
                val axioms = NlpcoreAxiomatics.recognize(doc).values()
                if (axioms.isEmpty()) println("[nlp] no candidate: $line")
                for (a in axioms) {
                    val cls = classOf(lemmaOf[a.antecedent] ?: a.antecedent)
                    if (cls == null) { println("[sumo] no class for ${a.antecedent}: $line"); continue }
                    val key = cls to (lemmaOf[a.consequent] ?: a.consequent).lowercase()
                    evidence[key] = revise(evidence[key] ?: EvidenceCoord.EMPTY, a.rule.evidence)
                    println("[nal] ${name(cls)} ==> ${key.second}  c=${"%.3f".format(Nal.truthOf(evidence[key]!!).confidence)}  ← $line")
                }
            }
        }
        val nlpMs = (System.nanoTime() - t0) / 1_000_000

        val eternal = evidence.filter { (_, e) -> Nal.truthOf(e).confidence >= promote }
        val rules = eternal.map { (k, e) -> EternalRule(name(k.first), k.second, NalCopula.IMPLICATION, e) }
        val antecedents = RoaringSeries.of(eternal.keys.map { it.first })
        println("[rete] promoted ${rules.size}/${evidence.size} at c>=$promote: " +
            rules.joinToString { "${it.antecedent}==>${it.consequent}" })
        val rete = CausalityRete(rules.size j { i: Int -> rules[i] })

        for (ask in asks) {
            val self = sumo.classId(ask)?.value
            if (self == null) { println("[ask] $ask: not a SUMO class"); continue }
            val t1 = System.nanoTime()
            val hits = (sumo.mask(ask, SumoMask.ANCESTORS) or RoaringSeries.singleton(self)) and antecedents
            val assertions = hits.toIntArray().map { ReteAssertion(name(it), ask, 0L, EvidenceCoord(Nal.UNIT, 0L), RelationKind.CAUSALITY) }
            val fired = rete.fire(assertions.size j { i: Int -> assertions[i] }).values()
            val us = (System.nanoTime() - t1) / 1_000
            if (fired.isEmpty()) println("[ask] $ask: no rule  (${us}µs)")
            for (f in fired) println("[ask] $ask ==> ${f.rule.consequent}  via ${f.matched.subject}" +
                "  support c=${"%.3f".format(Nal.truthOf(f.support).confidence)}  (${us}µs, model calls 0)")
        }
        println("[time] corenlp ${nlpMs}ms for ${lines.size} lines")
    }
}
