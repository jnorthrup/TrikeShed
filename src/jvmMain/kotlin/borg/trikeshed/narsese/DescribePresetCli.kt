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
        val name = args.getOrNull(1) ?: "preset-describe"
        val program = LcncProgramConfix.fromJson(name, LcncPresets.all().getValue(name))
        val violations = LcncTypeCheck.check(program)
        println("[check] ${violations.size} violations" + violations.joinToString("") { "\n  $it" })
        val bank = KifKnowledgeBase()
        val runners = HashMap(PureNodes.registry { System.currentTimeMillis() })
        runners["display"] = LcncNodeRunner { _, inputs -> mapOf("x" to (inputs["x"] ?: "")) }
        runners["note"] = LcncNodeRunner { _, _ -> emptyMap() }
        // No model dialog headless: the proposer reports the summary it was handed instead of calling one.
        runners[borg.trikeshed.lcnc.LcncContracts.WIKI_PROPOSE] = LcncNodeRunner { _, inputs ->
            mapOf("report" to mapOf("dialog" to "none (describePreset)", "summaryChars" to ((inputs["summary"] ?: inputs["summary?"]) as? String)?.length))
        }
        runners[SkillCurateNode.TYPE] = SkillCurateNode.runner(File(args[0])) { bank.assertKif(it) }
        runners[SkillOverlapNode.TYPE] = SkillOverlapNode.runner(File(args[0])) { bank.assertKif(it) }
        runners[NormClausesNode.TYPE] = NormClausesNode.runner { bank.assertKif(it) }
        // args[2]: a forge home whose wiki `wiki.read` reads; constellations always go to a fresh scratch dir.
        val state = File(System.getProperty("java.io.tmpdir"), "describe-preset").apply { deleteRecursively(); mkdirs() }
        args.getOrNull(2)?.let { home -> File(home, "wiki").takeIf { it.isDirectory }?.copyRecursively(File(state, "wiki")) }
        ConstellationNodes(state,
            borg.trikeshed.graal.ConfixBlackboard.empty(), bank).register(runners)
        val board = borg.trikeshed.kanban.BoardStoreElement(null, borg.trikeshed.job.CasStore.inMemory(), clock = { System.currentTimeMillis() })
        board.open()
        runners.putAll(borg.trikeshed.lcnc.LcncKanbanExperience(board).registry())
        ClassRuleLane().use { lane ->
            runners[NlRulesNode.TYPE] = NlRulesNode.runner(lane) { bank.assertKif(it) }
            val out = kotlinx.coroutines.withContext(board) { LcncRunner(runners).runAll(program) }
            for ((id, o) in out) println("[$id] " + o.entries.joinToString { (k, v) -> "$k=${v.toString().take(900)}" })
        }
        for (card in board.cards()) println("[board] ${card.jobId} ${card.col.wire} p${card.priority} ${card.title}")
        for (q in listOf("(ruleLearned ?a ?c)", "(ruleAnswer ?s ?c ?via ?f ?conf)", "(skillModelled ?s ?v)", "(skillOverlap ?a ?b ?f ?c)", "(norm ?s ?p ?f ?c)",
            "(norm ?id ?m ?class ?p ?f ?c)", "(normSource ?id ?book ?section)",
            "(normHeld ?class ?p ?via ?f ?c)"))
            println("[bank] $q → " + bank.query(KifExpr.parse(q)).joinToString { b -> b.values.joinToString(" ") })
    }
}
