package borg.trikeshed.narsese

import borg.trikeshed.graal.subvm.CoreNlpRuntime
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.ontology.SumoCorpus
import java.io.File

/**
 * CoreNLP → NAL evidence → eternal promotion → live rete, widened by SUMO subsumption.
 *
 * Each input line is parsed by CoreNLP; [NlpcoreAxiomatics] yields candidate implications.
 * Candidates with the same lemma pair revise one evidence base. A rule whose NAL confidence
 * (horizon k = one observation) reaches `promote` is admitted to [CausalityRete]. Each
 * `ask` term is then projected through its SUMO superclasses and fired against the rete,
 * so a rule learned about `Mammal` fires for `DomesticDog`.
 *
 * Usage: CorenlpReteCli <corpus.txt> <promote 0..1> <askTerm>...
 */
object CorenlpReteCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val lines = File(args[0]).readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val promote = args[1].toFloat()
        val asks = args.drop(2)

        val sumo = SumoCorpus.pinned
        val lexicon = SumoCorpus.nounLemmas
        fun sumoTerm(lemma: String): String = lemma.lowercase().let { l ->
            lexicon[l] ?: lexicon[l.removeSuffix("s")]
        } ?: lemma.replaceFirstChar { it.uppercase() }.takeIf { sumo.isClass(it) } ?: lemma

        val evidence = LinkedHashMap<Pair<String, String>, EvidenceCoord>()
        val t0 = System.nanoTime()
        CoreNlpRuntime().use { nlp ->
            for (line in lines) {
                val doc = nlp.analyze(line)
                val lemmaOf = HashMap<String, String>()
                for (s in doc.sentences.values()) for (t in s.tokens.values()) lemmaOf[t.word] = t.lemma.ifBlank { t.word }
                val axioms = NlpcoreAxiomatics.recognize(doc).values()
                if (axioms.isEmpty()) println("[nlp] no candidate: $line")
                for (a in axioms) {
                    val key = sumoTerm(lemmaOf[a.antecedent] ?: a.antecedent) to sumoTerm(lemmaOf[a.consequent] ?: a.consequent)
                    evidence[key] = revise(evidence[key] ?: EvidenceCoord.EMPTY, a.rule.evidence)
                    val c = Nal.truthOf(evidence[key]!!).confidence
                    println("[nal] ${key.first} ==> ${key.second}  c=${"%.3f".format(c)}  ← $line")
                }
            }
        }
        val nlpMs = (System.nanoTime() - t0) / 1_000_000

        val eternal = evidence.filter { (_, e) -> Nal.truthOf(e).confidence >= promote }
            .map { (k, e) -> EternalRule(k.first, k.second, NalCopula.IMPLICATION, e) }
        println("[rete] promoted ${eternal.size}/${evidence.size} at c>=$promote: " +
            eternal.joinToString { "${it.antecedent}==>${it.consequent}" })
        val rete = CausalityRete(eternal.size j { i: Int -> eternal[i] })

        for (ask in asks) {
            val t1 = System.nanoTime()
            val path = listOf(ask) + sumo.superclassesOf(ask).values().reversed()
            val assertions = path.map { ReteAssertion(it, ask, 0L, EvidenceCoord(Nal.UNIT, 0L), RelationKind.CAUSALITY) }
            val fired = rete.fire(assertions.size j { i: Int -> assertions[i] }).values()
            val us = (System.nanoTime() - t1) / 1_000
            if (fired.isEmpty()) println("[ask] $ask: no rule  (${us}µs, sumo path ${path.take(6)})")
            for (f in fired) println("[ask] $ask ==> ${f.rule.consequent}  via ${f.matched.subject}" +
                "  support c=${"%.3f".format(Nal.truthOf(f.support).confidence)}  (${us}µs, model calls 0)")
        }
        println("[time] corenlp ${nlpMs}ms for ${lines.size} lines")
    }
}
