package borg.trikeshed.narsese

import borg.trikeshed.kif.KifExpr
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lcnc.LcncPresets
import borg.trikeshed.lcnc.LcncProgramConfix
import borg.trikeshed.lcnc.LcncRunner
import borg.trikeshed.lcnc.LcncTypeCheck
import borg.trikeshed.lcnc.PureNodes
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `preset-describe`, headless: the stored program is parsed, type-checked against the palette
 * contracts and run by [LcncRunner] with the real `nl.rules` and `skill.curate` runners over one
 * bank, which is then queried. `args[0]` is the Hermes profile the curation reads.
 */
object DescribePresetCli {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val program = LcncProgramConfix.fromJson("preset-describe", LcncPresets.all().getValue("preset-describe"))
        val violations = LcncTypeCheck.check(program)
        println("[check] ${violations.size} violations" + violations.joinToString("") { "\n  $it" })
        val bank = KifKnowledgeBase()
        val runners = HashMap(PureNodes.registry { System.currentTimeMillis() })
        runners["display"] = LcncNodeRunner { _, inputs -> mapOf("x" to (inputs["x"] ?: "")) }
        runners["note"] = LcncNodeRunner { _, _ -> emptyMap() }
        runners[SkillCurateNode.TYPE] = SkillCurateNode.runner(File(args[0])) { bank.assertKif(it) }
        runners[SkillOverlapNode.TYPE] = SkillOverlapNode.runner(File(args[0])) { bank.assertKif(it) }
        ClassRuleLane().use { lane ->
            runners[NlRulesNode.TYPE] = NlRulesNode.runner(lane) { bank.assertKif(it) }
            val out = LcncRunner(runners).runAll(program)
            for (id in listOf("n4", "n5", "n10")) println("[$id display] ${out[id]?.get("x")}")
        }
        for (q in listOf("(ruleLearned ?a ?c)", "(ruleAnswer ?s ?c ?via ?f ?conf)", "(skillModelled ?s ?v)", "(skillOverlap ?a ?b ?f ?c)"))
            println("[bank] $q → " + bank.query(KifExpr.parse(q)).joinToString { b -> b.values.joinToString(" ") })
    }
}
