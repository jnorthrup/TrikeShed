package borg.trikeshed.narsese

import borg.trikeshed.kif.KifExpr
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lcnc.LcncNode
import kotlinx.coroutines.runBlocking
import java.io.File

/** Run `skill.curate` once over `args[0]` (a Hermes profile dir), then query the bank it told. */
object SkillCurateCli {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val bank = KifKnowledgeBase()
        val out = SkillCurateNode.runner(File(args[0])) { bank.assertKif(it) }
            .execute(LcncNode("c", SkillCurateNode.TYPE, mapOf("daysAhead" to (args.getOrNull(1) ?: "0"))), emptyMap())
        println("[curate] ${out["count"]} skills, divergent ${out["divergent"]}")
        for (q in listOf("(skillState ?s ?v)", "(skillModelled ?s ?v)", "(skillUses ?s ?n)")) {
            val rows = bank.query(KifExpr.parse(q))
            println("[bank] $q → ${rows.size}: " + rows.joinToString { b -> b.entries.joinToString(" ") { "${it.key}=${it.value}" } })
        }
    }
}
