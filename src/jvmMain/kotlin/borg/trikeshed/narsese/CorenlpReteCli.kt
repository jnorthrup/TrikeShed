package borg.trikeshed.narsese

import java.io.File

/**
 * [ClassRuleLane] over a corpus file, printed.
 *
 * Usage: CorenlpReteCli <corpus.txt> <promote 0..1> <askClass>...
 */
object CorenlpReteCli {
    @JvmStatic
    fun main(args: Array<String>) {
        val lines = File(args[0]).readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val promote = args[1].toFloat()
        val asks = args.drop(2)

        val tS = System.nanoTime()
        ClassRuleLane().use { lane ->
            println("[sumo] ${lane.sumo.classCount} classes, ${lane.sumo.termCount} terms: ${(System.nanoTime() - tS) / 1_000_000}ms")
            val t0 = System.nanoTime()
            for (o in lane.observe(lines)) {
                if (o.miss != null) { println("[nlp] ${o.miss}: ${o.line}"); continue }
                val k = lane.ledger.key(o.slot)
                println("[nal] ${lane.className((k ushr 32).toInt())} ==> ${lane.term(k.toInt())}  " +
                    "c=${"%.3f".format(Nal.truthOf(lane.ledger.evidence(o.slot)).confidence)}  ← #${o.source} ${o.line}")
            }
            val nlpMs = (System.nanoTime() - t0) / 1_000_000
            val rete = lane.promote(promote)
            println("[rete] promoted ${rete.size}/${lane.ledger.size} at c>=$promote, f>0.5: " +
                (0 until rete.size).joinToString { "${lane.className(rete.antecedent(it))}==>${lane.term(rete.consequent(it))}" })
            fun show(e: EvidenceCoord) = Nal.truthOf(e).let { "f=${"%.2f".format(it.frequency)} c=${"%.2f".format(it.confidence)}" }
            for (ask in asks) {
                val t1 = System.nanoTime()
                val answers = lane.ask(ask)
                val us = (System.nanoTime() - t1) / 1_000
                when {
                    answers == null -> println("[ask] $ask: not a SUMO class")
                    answers.isEmpty() -> println("[ask] $ask: no rule  (${us}µs)")
                    else -> for (a in answers) println(
                        "[ask] $ask ==> ${lane.term(rete.consequent(a.row))}  via ${lane.className(rete.antecedent(a.row))}  deduced ${show(a.deduced)}" +
                            (a.observed?.let { "  observed ${show(it)}" } ?: "") + "  belief ${show(a.belief)}  (${us}µs, model calls 0)")
                }
            }
            println("[time] corenlp ${nlpMs}ms for ${lines.size} lines")
        }
    }
}
