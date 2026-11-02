package borg.trikeshed.narsese

import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lcnc.LcncNodeRunner

/**
 * `nl.rules` parses unreviewed source-linked candidates. It neither admits rules nor
 * answers class queries. The old class-rule experiment is available only through an
 * explicit host-side CLI flag, never an LCNC node parameter.
 */
object NlRulesNode {
    const val TYPE = "nl.rules"

    @Suppress("UNUSED_PARAMETER")
    fun register(runners: MutableMap<String, LcncNodeRunner>, bank: KifKnowledgeBase, lane: ClassRuleLane = ClassRuleLane()) {
        runners[TYPE] = runner(lane)
    }

    /** Candidate readings use UTF-16 offsets into the exact supplied text; its digest is not a CAS receipt. */
    fun runner(lane: ClassRuleLane): LcncNodeRunner = LcncNodeRunner { node, inputs ->
        val text = (inputs["text"] ?: inputs["text?"] ?: node.params["text"]) as? String ?: ""
        synchronized(lane) {
            // Parse applies the lane's source-size limit before UTF-8 allocation and hashing.
            val readings = lane.candidates(text)
            val sourceDigest = borg.trikeshed.job.ContentId.of(text.encodeToByteArray(throwOnInvalidSequence = true)).value
            val candidates = readings.map { a ->
                mapOf<String, Any?>("sourceDigest" to sourceDigest, "sourceRetained" to false,
                    // Recognized by the direct document-rule admission fence. This metadata
                    // can be stripped by a caller and is not an authority token.
                    "sourceEvidence" to mapOf("kind" to "unretained-supplied-text", "offsetUnit" to "utf16-code-units"),
                    "sentenceIndex" to a.sentenceIndex, "begin" to a.begin, "end" to a.end,
                    "quote" to text.substring(a.begin, a.end),
                    "antecedent" to a.antecedent, "predicate" to a.predicate, "consequent" to a.consequent,
                    "polarity" to (a.rule.evidence.positive > 0), "structuralWeight" to a.confidence,
                    "weightKind" to "pattern-structural-not-parser-confidence", "reviewStatus" to "unreviewed")
            }
            mapOf<String, Any?>("candidates" to candidates, "rules" to emptyList<Any>(), "answers" to emptyList<Any>(),
                "answerStatus" to "deferred-unreviewed", "coverage" to "not-established", "unparsed" to
                    if (text.isBlank()) emptyList<Any>() else listOf(mapOf("reason" to
                        if (candidates.isEmpty()) "no candidate reading; sentence coverage not established"
                        else "sentence coverage not established; unsupported text may remain")))
        }
    }

    /** Run the node over `args[0]` in two halves (evidence accumulates across runs), asking `args[1..]`; query the bank. */
    @JvmStatic
    fun main(args: Array<String>) = kotlinx.coroutines.runBlocking {
        val legacy = "--legacy-promote" in args
        val positional = args.filterNot { it == "--legacy-promote" }
        require(positional.isNotEmpty()) { "Usage: NlRulesNode [--legacy-promote] <text-file> [class ...]" }
        val bank = KifKnowledgeBase()
        val lines = java.io.File(positional[0]).readLines().filter { it.isNotBlank() }
        val node = borg.trikeshed.lcnc.LcncNode("n", TYPE, mapOf("promote" to "0.7"))
        ClassRuleLane().use { lane ->
            val run = if (legacy) legacyRunner(lane) { bank.assertKif(it) } else runner(lane)
            val asks = positional.drop(1).joinToString(" ")
            for ((i, half) in listOf(lines.take(lines.size / 2), lines.drop(lines.size / 2)).withIndex()) {
                val out = run.execute(node, mapOf("text" to half.joinToString("\n"), "ask" to asks))
                println("[run ${i + 1}] ${half.size} lines → candidates ${out["candidates"] ?: emptyList<Any>()}")
                println("[run ${i + 1}] rules ${out["rules"]}")
                println("[run ${i + 1}] answers ${out["answers"]}")
            }
        }
        for (q in listOf("(ruleLearned ?a ?c)", "(ruleAnswer ?s ?c ?via ?f ?conf)"))
            println("[bank] $q → " + bank.query(borg.trikeshed.kif.KifExpr.parse(q)).joinToString { b -> b.values.joinToString(" ") })
    }

    /** Explicit host-only legacy experiment: promotes and writes KIF without review. */
    internal fun legacyRunner(lane: ClassRuleLane, into: (String) -> Unit): LcncNodeRunner = LcncNodeRunner { node, inputs ->
        val text = (inputs["text"] ?: inputs["text?"] ?: node.params["text"]) as? String ?: ""
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val promote = node.params["promote"]?.toFloatOrNull() ?: 0.7f
        val asks = ((inputs["ask"] ?: inputs["ask?"] ?: node.params["ask"]) as? String ?: "").split(',', ' ', '\n').filter { it.isNotBlank() }
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
                "candidates" to rules.map { it + ("status" to "legacy-promoted-unreviewed") },
                "answerStatus" to "legacy-promoted-unreviewed; see answers",
                "coverage" to "not-established",
                "rules" to rules,
                "answers" to answers,
                "unparsed" to observed.filter { it.miss != null }.map { mapOf("line" to it.line, "reason" to it.miss) },
            )
        }
    }
}
