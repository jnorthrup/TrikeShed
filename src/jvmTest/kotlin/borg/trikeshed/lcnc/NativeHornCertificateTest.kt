package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.kif.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyMap
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path
import kotlin.test.*

/** Supplied certificate verification only; native generation is covered by NativeHornQueryTest. */
class NativeHornCertificateTest {
    private val export = SumoNixBridge.compile(1, s_["(p A)", "(q A)", "(q B)",
        "(=> (and (p ?x) (q ?x)) (r ?x))"].view.mapIndexed { i, text ->
        KifSourceAssertion("source-$i", ContentId.of(text.encodeToByteArray()), "fixture", KifReview.APPROVED, text)
    }, "(r A)", "review-record-observations")
    private val input = reifyMap(export.inputJson)
    private val provenance = input["provenance"] as Map<*, *>
    private val facts = provenance["facts"] as List<*>
    private val ruleSource = ((provenance["rules"] as List<*>).single() as Map<*, *>)["source"]
    private fun leaf(index: Int, text: String): Map<String, Any?> = _m["fact" j text, "asserted" j true,
        "witnessMode" j "any-one-asserted-source", "sources" j _l[(facts[index] as Map<*, *>)["source"]]]
    private fun proof(): Map<String, Any?> = _m["fact" j "(r A)", "rule" j "r#1", "sources" j _l[ruleSource],
        "from" j _l[leaf(0, "(p A)"), leaf(1, "(q A)")]]
    private fun verify(proof: Any?, data: Map<String, Any?> = input) {
        val query = NativeHornQuery(NativeHornProfile(Path.of("/operator/mlnix"), "a".repeat(64),
            Path.of("/operator/run.nix"), Path.of("/operator/source")))
        val method = NativeHornQuery::class.java.getDeclaredMethod("checkProof", Any::class.java, Map::class.java)
        method.isAccessible = true
        try { method.invoke(query, proof, data) }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }
    @Test fun validCertificateAndUnknown() { verify(proof()); verify(null) }
    @Test fun wrongLeafSourceRefuses() {
        assertFailsWith<IllegalArgumentException> {
            verify(proof() + _m["from" j _l[leaf(1, "(p A)"), leaf(1, "(q A)")]])
        }
    }
    @Test fun differentQueryRefuses() {
        assertFailsWith<IllegalArgumentException> { verify(proof(), input + _m["query" j _l["r", "B"]]) }
    }
    @Test fun sharedVariableAndOrderedPremisesRefuse() {
        assertFailsWith<IllegalArgumentException> {
            verify(proof() + _m["from" j _l[leaf(0, "(p A)"), leaf(2, "(q B)")]])
        }
        assertFailsWith<IllegalArgumentException> {
            verify(proof() + _m["from" j _l[leaf(1, "(q A)"), leaf(0, "(p A)")]])
        }
        assertFailsWith<IllegalArgumentException> {
            verify(proof() + _m["fact" j "(r B)"], input + _m["query" j _l["r", "B"]])
        }
    }
    @Test fun incorrectRuleOrRuleSourceRefuses() {
        assertFails { verify(proof() + _m["rule" j "r#2"]) }
        assertFailsWith<IllegalArgumentException> { verify(proof() + _m["sources" j _l[(facts[0] as Map<*, *>)["source"]]]) }
    }
}
