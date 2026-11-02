package borg.trikeshed.kif

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyMap
import kotlin.test.*

class SumoNixBridgeTest {
    private fun source(id: String, text: String, review: KifReview = KifReview.APPROVED) =
        KifSourceAssertion(id, ContentId.of("source:$id".encodeToByteArray()), "v1", review, text)

    @Test fun onlyRangeRestrictedPositiveScalarHornIsAdmitted() {
        val sources = _l[source("fact", "(p A)"), source("rule", "(=> (and (p ?x) (r ?x)) (q ?x))"),
            source("unbound", "(=> (p ?x) (q ?y))"), source("negative", "(=> (not (p ?x)) (q ?x))"),
            source("exists", "(=> (exists (?x) (p ?x)) (q A))"),
            source("function", "(=> (p ?x) (q (Fn ?x)))"), source("equal", "(equal A B)"),
            source("quantified", "(forall (?x) (=> (p ?x) (q ?x)))"),
            source("unreviewed", "(p B)", KifReview.UNVERIFIED)]
        val export = SumoNixBridge.compile(1, sources, "(q A)")
        val input = reifyMap(export.inputJson)
        assertEquals(1, (input["facts"] as List<*>).size)
        assertEquals(1, (input["rules"] as List<*>).size)
        assertEquals(7, (input["excluded"] as List<*>).size)
        assertNull(input["ontology"])
        assertEquals("NOT_EXECUTED", input["executionStatus"])
        assertTrue("kif.rule" in export.mlir && "kif.var \"?x\"" in export.mlir)
        assertFailsWith<IllegalStateException> { SumoNixBridge.compile(1, sources, "(not (q A))") }
        assertFailsWith<IllegalArgumentException> { SumoNixBridge.compile(1, sources, "(q ?x)") }
    }

    @Test fun duplicateFactsAndPositionalRulesKeepExactIndependentSources() {
        val one = source("one", "(p A)")
        val sources = _l[one, source("two", "(p A)"), source("r1", "(=> (p ?x) (q ?x))"),
            source("excluded", "(=> (p ?x) (q ?y))"), source("r2", "(=> (p ?x) (q ?x))")]
        val first = SumoNixBridge.compile(1, sources, "(q A)")
        val second = SumoNixBridge.compile(2, sources, "(q A)")
        assertNotEquals(first.frameId, second.frameId)
        val data = reifyMap(first.inputJson)
        val provenance = data["provenance"] as Map<*, *>
        val facts = provenance["facts"] as List<*>
        assertEquals(2, facts.size)
        assertNotEquals((facts[0] as Map<*, *>)["source"], (facts[1] as Map<*, *>)["source"])
        val rules = provenance["rules"] as List<*>
        assertEquals(_l["q#1", "q#2"], rules.map { (it as Map<*, *>)["id"] })
        val changed = SumoNixBridge.compile(1, sources.map { if (it.id == "one") it.copy(sourceRevision = "v2") else it }, "(q A)")
        assertNotEquals(first.inputCid, changed.inputCid)
        assertEquals(first.mlir, changed.mlir)
    }

    @Test fun actualUpstreamAssemblyEscapesBytesAndNeverAcceptsLossyIdentities() {
        val text = "(p \"?notVariable \\\"quoted\\\" λ\uD83D\uDE00\")"
        val source = source("unicode", text)
        val output = SumoNixBridge.compile(0, _l[source], text)
        assertTrue("sumo.holds \"p\"" in output.mlir)
        assertTrue("\\ce\\bb\\f0\\9f\\98\\80" in output.mlir)
        assertFalse("kif.var" in output.mlir)
        assertEquals(text, (((reifyMap(output.inputJson)["provenance"] as Map<*, *>)["facts"] as List<*>)[0]
            as Map<*, *>).let { (it["source"] as Map<*, *>)["text"] })
        assertFailsWith<CharacterCodingException> {
            SumoNixBridge.compile(0, _l[source.copy(id = "\uD800")], text)
        }
        assertFailsWith<CharacterCodingException> { SumoNixBridge.compile(0, _l[source], "(p \uD800)") }
    }
}
