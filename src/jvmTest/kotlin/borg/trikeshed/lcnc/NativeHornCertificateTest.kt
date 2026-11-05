package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.kif.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyMap
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path
import kotlin.test.*

/** Authored manual KIF controls; no parsed-English or authenticated review claim. */
internal const val manualRoleRule = "(=> (and (assignedTo ?operator ?resource) (authorized ?operator Access)) (mayRead ?operator ?resource))"
internal val manualRoleS5 = "$manualRoleRule\n(assignedTo Harbor Lark)\n(authorized Lark Access)\n"
internal val manualRoleS6 = "$manualRoleRule\n(assignedTo Lark Harbor)\n"
internal val manualRolePositive = manualRoleS6 + "(authorized Lark Access)\n"
internal fun manualRoleSource(text: String) = KifSourceAssertion("host-manual-fixture",
    ContentId.of(text.encodeToByteArray(throwOnInvalidSequence = true)), "manual-fixture/1", KifReview.APPROVED, text)

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
    private fun verify(proof: Any?, data: Map<String, Any?> = input): Map<*, *>? {
        val query = NativeHornQuery(NativeHornProfile(Path.of("/operator/mlnix"), "a".repeat(64),
            Path.of("/operator/run.nix"), Path.of("/operator/source")))
        val method = NativeHornQuery::class.java.getDeclaredMethod("checkProof", Any::class.java, Map::class.java)
        method.isAccessible = true
        try { return method.invoke(query, proof, data) as Map<*, *>? }
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

    private fun roleData(text: String = manualRolePositive): Map<String, Any?> = reifyMap(
        NativeHornQuery.manualInput(1, manualRoleSource(text), "(mayRead Lark Harbor)")["inputJson"] as String)

    /** A supplied certificate fixture, not native generation or a second inference implementation. */
    private fun roleProof(data: Map<String, Any?>): Map<String, Any?> {
        val provenance = data["provenance"] as Map<*, *>
        val facts = provenance["facts"] as List<*>
        fun leaf(fact: List<String>): Map<String, Any?> = _m["fact" j "(${fact.joinToString(" ")})",
            "asserted" j true, "witnessMode" j "any-one-asserted-source",
            "sources" j facts.map { it as Map<*, *> }.filter { it["fact"] == fact }.map { it["source"] }]
        return _m["fact" j "(mayRead Lark Harbor)", "rule" j "mayRead#1",
            "sources" j (provenance["rules"] as List<*>).map { (it as Map<*, *>)["source"] },
            "from" j _l[leaf(_l["assignedTo", "Lark", "Harbor"]), leaf(_l["authorized", "Lark", "Access"])]]
    }

    @Test fun roleBindingsAndOrderedUsesComeFromTheExistingCertificateCheck(): Unit {
        val data = roleData()
        val raw = roleProof(data)
        val before = borg.trikeshed.parse.jsonOf(raw)
        val checked = assertNotNull(verify(raw, data))
        assertEquals(before, borg.trikeshed.parse.jsonOf(raw), "Raw certificate must not be rewritten")
        assertEquals("native-horn-checked-proof/1", checked["kind"])
        assertEquals(data["frameId"], checked["frameId"])
        assertEquals(data["query"], checked["query"])
        val steps = (checked["steps"] as List<*>).map { it as Map<*, *> }
        assertEquals(_l[_l[0], _l[1], emptyList<Int>()], steps.map { it["path"] })
        val root = steps.last()
        assertEquals("mayRead#1", root["rule"])
        val uses = (root["premises"] as List<*>).map { it as Map<*, *> }
        assertEquals(_l[0, 1], uses.map { it["ordinal"] })
        assertEquals(_l[_l[0], _l[1]], uses.map { it["path"] })
        val bindings = (root["bindings"] as List<*>).map { it as Map<*, *> }.associateBy { it["variable"] }
        assertEquals(_s["?operator", "?resource"], bindings.keys)
        assertEquals(_m["kind" j "constant", "token" j "Lark"], bindings.getValue("?operator")["term"])
        assertEquals(_m["kind" j "constant", "token" j "Harbor"], bindings.getValue("?resource")["term"])
        val ruleIds = root["ruleSourceNodeIds"] as List<*>
        for (binding in bindings.values) {
            assertEquals("mayRead#1", binding["rule"])
            assertEquals(ruleIds, binding["ruleSourceNodeIds"])
            assertEquals(ruleIds, binding["originalVariableSourceNodeIds"])
        }
        val catalog = checked["sources"] as Map<*, *>
        assertEquals(3, catalog.size)
        assertTrue(catalog.values.all { (it as Map<*, *>)["sourceCid"] == manualRoleSource(manualRolePositive).sourceCid.value })
        assertTrue("not-source-spans" in (checked["referenceMode"] as String))
        val children = raw["from"] as List<*>
        assertFailsWith<IllegalArgumentException> { verify(raw + _m["from" j children.reversed()], data) }
        val changed = roleData(manualRolePositive.replace("(authorized Lark Access)", "(authorized Harbor Access)"))
        assertFailsWith<IllegalArgumentException> { verify(raw, changed) }
        assertNull(verify(null, data), "No certificate supplies no bindings; it does not prove a negative")
    }

    @Test fun repeatedOccurrencesStayAlternativeWitnessesAndLiteralBindingsStayTyped(): Unit {
        val data = roleData(manualRolePositive + "(authorized Lark Access)\n")
        val checked = assertNotNull(verify(roleProof(data), data))
        val steps = (checked["steps"] as List<*>).map { it as Map<*, *> }
        val authorized = steps.single { it["normalForm"] == "(authorized Lark Access)" }
        assertEquals("any-one-asserted-source", authorized["witnessMode"])
        assertEquals(2, (authorized["sourceNodeIds"] as List<*>).distinct().size)
        assertEquals(2, (steps.last()["premises"] as List<*>).size, "A repeat cannot become another required premise")
        assertEquals(2, (steps.last()["bindings"] as List<*>).size)
        for (token in s_["\"Lark\"", "A".repeat(65_532)].view) {
            val scalarSources = _l["(p $token)", "(=> (p ?x) (q ?x))"].mapIndexed { i, text ->
                KifSourceAssertion("scalar-$i", ContentId.of(text.encodeToByteArray()), "fixture", KifReview.APPROVED, text)
            }
            val scalarData = reifyMap(SumoNixBridge.compile(1, scalarSources, "(q $token)").inputJson)
            val provenance = scalarData["provenance"] as Map<*, *>
            val scalarProof = _m["fact" j "(q $token)", "rule" j "q#1",
                "sources" j _l[((provenance["rules"] as List<*>).single() as Map<*, *>)["source"]],
                "from" j _l[_m["fact" j "(p $token)", "asserted" j true, "witnessMode" j "any-one-asserted-source",
                    "sources" j _l[((provenance["facts"] as List<*>).single() as Map<*, *>)["source"]]]]]
            val scalarSteps = (assertNotNull(verify(scalarProof, scalarData))["steps"] as List<*>).map { it as Map<*, *> }
            val binding = (scalarSteps.last()["bindings"] as List<*>).single() as Map<*, *>
            assertEquals(if (token.startsWith('"')) _m["kind" j "literal", "spelling" j token]
                else _m["kind" j "constant", "token" j token], binding["term"])
            assertFailsWith<IllegalArgumentException> { verify(scalarProof + _m["fact" j "(q Lark)"], scalarData) }
        }
    }

    @Test fun wholeManualPreparationKeepsAllFormsAndImmutableSuppliedControls(): Unit {
        assertEquals("sha256:0a1ab3d15d9e8e453e7467ba5ae170defded7244bdca7b4047d414773706bf9f", manualRoleSource(manualRoleS5).sourceCid.value)
        assertEquals("sha256:5427aad52ecd1e911128a54c00021bbaa7bce382d240e83698815a48501180e3", manualRoleSource(manualRoleS6).sourceCid.value)
        val positive = NativeHornQuery.manualInput(1, manualRoleSource(manualRolePositive), "(mayRead Lark Harbor)")
        val s6 = NativeHornQuery.manualInput(1, manualRoleSource(manualRoleS6), "(mayRead Lark Harbor)")
        assertNotEquals(positive["frameId"], s6["frameId"])
        assertNotEquals(positive["inputCid"], s6["inputCid"])
        val repeated = NativeHornQuery.manualInput(1, manualRoleSource(manualRolePositive + "(authorized Lark Access)\n"), "(mayRead Lark Harbor)")
        val forms = (repeated["sourceForms"] as List<*>).map { it as Map<*, *> }
        assertEquals(_l[0, 1, 2, 3], forms.map { it["ordinal"] })
        assertEquals(4, forms.map { it["id"] }.distinct().size)
        assertEquals(forms[2]["normalForm"], forms[3]["normalForm"])
        assertTrue(forms.none { "begin" in it || "end" in it || "quote" in it })
        val data = reifyMap(repeated["inputJson"] as String)
        assertTrue((data["excluded"] as List<*>).isEmpty())
        assertEquals(3, (data["facts"] as List<*>).size)
        assertEquals(1, (data["rules"] as List<*>).size)
        val normalized = NativeHornQuery.manualInput(1, manualRoleSource("; documentary comment\n  $manualRoleRule\n (assignedTo Lark Harbor)\n"), "(mayRead Lark Harbor)")
        assertEquals(2, (normalized["sourceForms"] as List<*>).size)
    }

    @Test fun oneUnsupportedOrUnverifiedFormRefusesTheWholeManualTheory(): Unit {
        for (extra in s_["(not (authorized Lark Access))", "(exists (?x) (assignedTo ?x Harbor))",
            "(p (Fn Lark))", "(holdsDuring Now (authorized Lark Access))", "(believes Lark Harbor)",
            "(=> (assignedTo ?x Harbor) (mayRead ?y Harbor))", "(broken"].view) {
            assertFails { NativeHornQuery.manualInput(1, manualRoleSource(manualRolePositive + extra), "(mayRead Lark Harbor)") }
        }
        assertFailsWith<IllegalArgumentException> {
            NativeHornQuery.manualInput(1, manualRoleSource(manualRolePositive).copy(review = KifReview.UNVERIFIED), "(mayRead Lark Harbor)")
        }
        assertFailsWith<IllegalArgumentException> {
            NativeHornQuery.manualInput(1, manualRoleSource(manualRolePositive).copy(sourceCid = manualRoleSource(manualRoleS6).sourceCid), "(mayRead Lark Harbor)")
        }
        assertFailsWith<IllegalArgumentException> {
            NativeHornQuery.manualInput(1, manualRoleSource("(p A)\n".repeat(33)), "(p A)")
        }
        assertFailsWith<IllegalArgumentException> {
            NativeHornQuery.manualInput(1, manualRoleSource("(p A)" + " ".repeat(65_536)), "(p A)")
        }
        assertFails { NativeHornQuery.manualInput(1, manualRoleSource(manualRolePositive), "(mayRead ?operator Harbor)") }
        // The general exporter still reports exclusions; this wrapper must not change that policy.
        val partial = SumoNixBridge.compile(1, _l[manualRoleSource("(p A)"), manualRoleSource("(not (p B))").copy(id = "other")], "(p A)")
        assertEquals(1, (reifyMap(partial.inputJson)["excluded"] as List<*>).size)
    }
}
