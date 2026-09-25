package borg.trikeshed.narsese

import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lcnc.LcncNodeRunner

/**
 * `nl.rules` — the describe-it lane as an LCNC node. Text lines in; [ClassRuleLane] observes them
 * (evidence accumulates across runs in one lane), promotes at `promote`, and answers each `ask`
 * class. Promoted rules and answers are told to the bank:
 *
 *     (ruleLearned AntecedentClass consequent)
 *     (ruleAnswer AskClass consequent ViaClass "f" "c")
 */
object NlRulesNode {
    const val TYPE = "nl.rules"

    fun register(runners: MutableMap<String, LcncNodeRunner>, bank: KifKnowledgeBase) {
        runners[TYPE] = runner(ClassRuleLane()) { kif -> runCatching { bank.assertKif(kif) } }
    }

    /** Run the node over `args[0]` in two halves (evidence accumulates across runs), asking `args[1..]`; query the bank. */
    @JvmStatic
    fun main(args: Array<String>) = kotlinx.coroutines.runBlocking {
        val bank = KifKnowledgeBase()
        val lines = java.io.File(args[0]).readLines().filter { it.isNotBlank() }
        val node = borg.trikeshed.lcnc.LcncNode("n", TYPE, mapOf("promote" to "0.7"))
        ClassRuleLane().use { lane ->
            val run = runner(lane) { bank.assertKif(it) }
            val asks = args.drop(1).joinToString(" ")
            for ((i, half) in listOf(lines.take(lines.size / 2), lines.drop(lines.size / 2)).withIndex()) {
                val out = run.execute(node, mapOf("text" to half.joinToString("\n"), "ask" to asks))
                println("[run ${i + 1}] ${half.size} lines → rules ${out["rules"]}")
                println("[run ${i + 1}] answers ${out["answers"]}")
            }
        }
        for (q in listOf("(ruleLearned ?a ?c)", "(ruleAnswer ?s ?c ?via ?f ?conf)"))
            println("[bank] $q → " + bank.query(borg.trikeshed.kif.KifExpr.parse(q)).joinToString { b -> b.values.joinToString(" ") })
    }

    fun runner(lane: ClassRuleLane, into: (String) -> Unit): LcncNodeRunner = LcncNodeRunner { node, inputs ->
        val text = (inputs["text"] ?: node.params["text"]) as? String ?: ""
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val promote = node.params["promote"]?.toFloatOrNull() ?: 0.7f
        val asks = ((inputs["ask"] ?: node.params["ask"]) as? String ?: "").split(',', ' ', '\n').filter { it.isNotBlank() }
        synchronized(lane) {
            val observed = lane.observe(lines)
            val rete = lane.promote(promote)
            val rules = (0 until rete.size).map { r ->
                val a = lane.className(rete.antecedent(r)); val c = lane.term(rete.consequent(r))
                into("(ruleLearned $a $c)")
                val t = Nal.truthOf(rete.evidence(r))
                mapOf("antecedent" to a, "consequent" to c, "frequency" to t.frequency, "confidence" to t.confidence)
            }
            val answers = asks.flatMap { ask ->
                (lane.ask(ask) ?: emptyList()).map { ans ->
                    val c = lane.term(rete.consequent(ans.row)); val via = lane.className(rete.antecedent(ans.row))
                    val t = Nal.truthOf(ans.belief)
                    into("(ruleAnswer $ask $c $via \"${"%.2f".format(t.frequency)}\" \"${"%.2f".format(t.confidence)}\")")
                    mapOf("ask" to ask, "consequent" to c, "via" to via, "frequency" to t.frequency, "confidence" to t.confidence,
                        "observed" to (ans.observed != null))
                }
            }
            mapOf(
                "rules" to rules,
                "answers" to answers,
                "unparsed" to observed.filter { it.miss != null }.map { mapOf("line" to it.line, "reason" to it.miss) },
            )
        }
    }
}
