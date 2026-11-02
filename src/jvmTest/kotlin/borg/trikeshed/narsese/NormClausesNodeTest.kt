package borg.trikeshed.narsese

import borg.trikeshed.kif.KifExpr
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lib.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NormClausesNodeTest {
    private val text = "Tenant must pay rent if funds arrive. Tenant cannot sell assets."
    private val positive = NormClause("tenant", "must", true, "pay", "rent", "to owner", text,
        condition = "if funds arrive")
    private val negative = NormClause("tenant", "cannot", false, "sell", "assets", null, text)

    private fun read(sections: List<String>, generic: Set<String>): List<List<NormClause>> {
        assertEquals(_l[text], sections)
        assertEquals(_s["tenant"], generic)
        return _l[_l[positive, negative]]
    }

    @Test
    fun registeredNodeReturnsUnreviewedCandidatesWithoutPublishing() = runBlocking {
        val bank = KifKnowledgeBase()
        val runners = HashMap<String, LcncNodeRunner>()
        NormClausesNode.register(runners, bank, ::read)
        val node = LcncNode("n", NormClausesNode.TYPE,
            _m["generic" j "tenant", "promote" j "0", "mode" j "legacy"])
        val output = runners.getValue(NormClausesNode.TYPE).execute(node, _m["text" j text, "policy" j "legacy"])
        val candidates = output["candidates"] as List<*>
        assertEquals(2, candidates.size)
        val first = candidates[0] as Map<*, *>
        val second = candidates[1] as Map<*, *>
        assertEquals("tenant", first["subject"])
        assertEquals("must", first["modal"])
        assertEquals(true, first["affirmative"])
        assertEquals("pay", first["verb"])
        assertEquals("rent", first["object"])
        assertEquals("to owner", first["oblique"])
        assertEquals("if funds arrive", first["condition"])
        assertEquals(text, first["sentencePreview"])
        assertEquals(0, first["sectionIndex"])
        assertEquals("cannot", second["modal"])
        assertEquals(false, second["affirmative"])
        assertEquals("sell", second["verb"])
        for (value in candidates) {
            val candidate = value as Map<*, *>
            assertEquals("unreviewed", candidate["reviewStatus"])
            assertEquals(false, candidate["sourceRetained"])
            val source = candidate["sourceEvidence"] as Map<*, *>
            assertEquals("unretained-section-text", source["kind"])
            assertEquals("unavailable", source["offsets"])
        }
        assertEquals(2, output["clauses"])
        assertEquals(1, output["sections"])
        assertEquals("not-established", output["coverage"])
        assertEquals(emptyList<Any>(), output["beliefs"])
        assertEquals(emptyList<Any>(), output["rules"])
        assertTrue(bank.query(KifExpr.parse("(norm ?s ?p ?f ?c)")).isEmpty())
    }

    @Test
    fun explicitLegacyRunnerAloneInvokesPublicationCallback() = runBlocking {
        val published = ArrayList<String>()
        val node = LcncNode("n", NormClausesNode.TYPE, _m["generic" j "tenant", "promote" j "0"])
        val output = NormClausesNode.legacyRunner({ published.add(it) }, ::read).execute(node, _m["text" j text])
        assertEquals(2, (output["beliefs"] as List<*>).size)
        assertEquals(1, (output["rules"] as List<*>).size)
        assertEquals(2, published.size)
        assertTrue(published.all { it.startsWith("(norm ") })
    }

    @Test
    fun candidateReaderMustReturnOneResultPerSection() = runBlocking {
        val runner = NormClausesNode.candidateRunner { _, _ -> emptyList() }
        assertFailsWith<IllegalArgumentException> {
            runner.execute(LcncNode("n", NormClausesNode.TYPE), _m["text" j text])
        }
    }
}
