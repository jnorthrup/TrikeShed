package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.kif.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import borg.trikeshed.rdf.*
import kotlin.test.*

/** Authored certificate/projection fixture through actual exporter/quad engine; not native generation or authenticated review. */
internal fun nativeHornGraphFixture(subject: String = "Tenant", repeat: Boolean = false): Map<String, Any?> {
    fun cid(text: String) = ContentId.of(text.encodeToByteArray()).value
    val read = cid("fixture-read"); val snapshot = cid("fixture-snapshot"); val approval = cid("fixture-approval")
    val texts = _l["(instance $subject Person)", "(subclass Person Agent)", "(subclass Unrelated Other)"] +
        if (repeat) _l["(instance $subject Person)"] else emptyList()
    val full = texts.joinToString("\n", postfix = "\n"); val source = cid(full)
    var begin = 0
    val evidence = linkedMapOf<String, Any?>()
    val assertions = texts.map { text ->
        val q = _m["kind" j "reviewed-kif-native-occurrence/1", "readCid" j read, "snapshotCid" j snapshot, "approvalCid" j approval,
            "selectionId" j "kif:$begin:${begin + text.length}", "originalCid" j source, "textCid" j source,
            "begin" j begin, "end" j (begin + text.length), "quote" j text]
        begin += text.length + 1
        val id = "selection:" + ContentId.of(CanonicalCbor.encodeMap(q)).hex
        evidence[id] = q
        KifSourceAssertion(id, ContentId(source), "fixture-source-v1", KifReview.APPROVED, text)
    } + ReviewedKifHornPlan.rules.mapIndexed { i, text ->
        KifSourceAssertion("host-rule:${i + 1}", ContentId(cid(text)), ReviewedKifHornPlan.POLICY, KifReview.APPROVED, text)
    }
    val export = SumoNixBridge.compile(3, assertions, "(instance $subject Agent)")
    val input = reifyMap(export.inputJson); val provenance = input["provenance"] as Map<*, *>
    val facts = (provenance["facts"] as List<*>).map { it as Map<*, *> }
    val rule = ((provenance["rules"] as List<*>)[1] as Map<*, *>)["source"] as Map<*, *>
    val catalog = linkedMapOf<String, Any?>()
    fun source(value: Any?): String { val map = value as Map<*, *>; val id = map["sourceNodeId"] as String; catalog[id] = map; return id }
    fun constant(token: String) = _m["kind" j "constant", "token" j token]
    fun witnesses(index: Int) = facts.filter { it["fact"] == facts[index]["fact"] }.map { it["source"] }
    fun leaf(index: Int): Map<String, Any?> = _m["fact" j texts[index], "asserted" j true,
        "witnessMode" j "any-one-asserted-source", "sources" j witnesses(index)]
    val steps = texts.take(2).mapIndexed { i, text ->
        val atom = facts[i]["fact"] as List<*>
        _m["path" j _l[i], "normalForm" j text, "predicate" j atom[0], "arguments" j atom.drop(1).map { constant(it as String) },
            "kind" j "asserted", "witnessMode" j "any-one-asserted-source", "sourceNodeIds" j witnesses(i).map(::source)]
    }
    val ruleId = "instance#2"; val ruleSources = _l[source(rule)]; val pattern = (input["rules"] as List<*>)[1] as List<*>
    val body = (pattern[1] as List<*>).drop(1)
    val root = _m["path" j emptyList<Int>(), "normalForm" j "(instance $subject Agent)", "predicate" j "instance",
        "arguments" j _l[constant(subject), constant("Agent")], "kind" j "derived", "rule" j ruleId,
        "ruleSourceNodeIds" j ruleSources, "rulePattern" j pattern,
        "premises" j (0..1).map { _m["ordinal" j it, "path" j _l[it], "pattern" j body[it]] },
        "bindings" j _l["?x" j subject, "?y" j "Person", "?z" j "Agent"].map { (variable, token) ->
            _m["variable" j variable, "rule" j ruleId, "ruleSourceNodeIds" j ruleSources,
                "originalVariableSourceNodeIds" j ruleSources, "term" j constant(token)]
        }]
    val proof = _m["fact" j "(instance $subject Agent)", "rule" j ruleId, "sources" j _l[rule], "from" j _l[leaf(0), leaf(1)]]
    val checked = _m["kind" j "native-horn-checked-proof/1", "inputCid" j export.inputCid.value, "frameId" j export.frameId.value,
        "query" j input["query"], "semantics" j input["semantics"], "sumoNixRevision" j SumoNixBridge.SUMO_REVISION,
        "mlirNixRevision" j SumoNixBridge.MLIR_REVISION, "firstProofOnly" j true,
        "referenceMode" j "normalized-export-atoms-and-rule-scoped-variables-not-source-spans", "steps" j (steps + root), "sources" j catalog,
        "verification" j "supplied-native-certificate-only-not-independent-inference"]
    return _m["kind" j "reviewed-kif-native-result/1", "scope" j "host-explicit-assertions", "inputCid" j export.inputCid.value,
        "frameId" j export.frameId.value, "query" j input["query"], "revision" j 3L, "sumoNixRevision" j SumoNixBridge.SUMO_REVISION,
        "status" j "SUPPORTED", "semantics" j input["semantics"], "firstProofOnly" j true, "ontology" j null,
        "proof" j proof, "checkedProof" j checked, "snapshotCid" j snapshot, "readCid" j read, "decisionCid" j approval,
        "approvalCid" j approval, "reviewer" j "synthetic:projection-fixture", "synthetic" j true, "asOf" j 123L,
        "policy" j ReviewedKifHornPlan.POLICY, "sourceEvidence" j evidence, "hostRules" j ReviewedKifHornPlan.rules,
        "coverage" j "positive-reviewed-selected-snapshot", "selectionCount" j texts.size, "selectionLimit" j ReviewedKifHornPlan.MAX_SELECTIONS,
        "execution" j "pinned-upstream-sumo-nix-datalog", "solverStepBudget" j null,
        "authority" j "reviewed-read-only-not-a-sink-permit", "nativeBinarySha256" j "a".repeat(64),
        "nativeAdapterSha256" j "b".repeat(64)]
}

class NativeHornQueryViewTest {
    private fun map(v: Any?) = v as Map<*, *>
    private fun rows(v: Any?) = (v as List<*>).map(::map)
    private fun execute(report: Map<String, Any?>, bindings: Boolean): Triple<NativeHornQueryView.Starter, Map<String, Any?>, List<Map<*, *>>> {
        val projection = NativeHornProofGraph.project(report); val starter = NativeHornQueryView.starter(report, bindings = bindings)
        val parsed = SparqlGraph.lower(starter.program); assertEquals(8, parsed.graph.patterns.size)
        assertTrue(LcncTypeCheck.check(starter.program, SparqlGraph.contracts().associateBy { it.type }, strict = true).isEmpty())
        assertEquals(starter.query, SparqlGraph.lower(LcncProgramConfix.fromJson("roundtrip", LcncProgramConfix.toJson(starter.program))).text)
        val raw = SparqlSurface(projection.store).answer(parsed, projection.store.scan(), "json", "")
        assertEquals(200, raw.status, raw.body)
        val query = _m["kind" j "reviewed-native-horn-quad-query/1", "schema" j NativeHornProofGraph.SCHEMA,
            "dataset" j NativeHornProofGraph.DATASET, "graph" j projection.graph, "query" j starter.query,
            "results" j reifyStrict(raw.body), "physicalSource" j SparqlSource.STORE.descriptor(false),
            "provenance" j (projection.descriptor + _m["checkedAsOf" j 123L, "sourceRevision" j "fixture-source-v1"]),
            "persistence" j "request-local", "authority" j "authenticated-reviewed-read-not-a-sink-permit"]
        val response = report + _m["quadQuery" j query]
        return Triple(starter, response, NativeHornQueryView.answer(report, response, starter).rows)
    }

    @Test fun integerAndJsonNumberDomainsKeepTheSameGraphAndBothEightPatternPrograms(): Unit {
        val report = nativeHornGraphFixture(); val roundtrip = reifyMap(jsonOf(report))
        val projection = NativeHornProofGraph.project(report)
        assertEquals(projection.projectionCid, NativeHornProofGraph.project(roundtrip).projectionCid)
        assertEquals(projection.projectionCid, NativeHornProofGraph.project(report + _m["asOf" j 999999999L]).projectionCid)
        for (bindings in s_[true, false].view) {
            val (starter, response, answers) = execute(report, bindings)
            assertEquals(starter.query, NativeHornQueryView.starter(roundtrip, bindings = bindings).query)
            assertEquals(answers, NativeHornQueryView.answer(roundtrip, reifyMap(jsonOf(response)), starter).rows)
            assertEquals(if (bindings) 3 else 2, answers.size)
            if (bindings) {
                assertEquals(_l["?x", "?y", "?z"], answers.map { map(it["variable"])["value"] })
                assertEquals(_l["Tenant", "Person", "Agent"], answers.map { map(it["value"])["value"] })
                assertTrue(answers.all { map(it["kind"])["value"] == "constant" && map(it["referenceMode"])["value"] == "host-rule-normalized-form" })
            } else {
                assertEquals(_l["0", "1"], answers.map { map(it["ordinal"])["value"] })
                assertEquals(_l["(instance Tenant Person)", "(subclass Person Agent)"], answers.map { map(it["form"])["value"] })
                assertTrue(answers.all { map(it["ordinal"])["datatype"] == RdfVocab.XSD + "integer" })
            }
        }
        val iriToken = execute(nativeHornGraphFixture("urn:fixture:Tenant"), true).third
        assertEquals("literal", map(iriToken[0]["value"])["type"])
        assertEquals("urn:fixture:Tenant", map(iriToken[0]["value"])["value"])
        assertNotEquals(projection.projectionCid, NativeHornProofGraph.project(nativeHornGraphFixture("Different")).projectionCid)
        val repeated = nativeHornGraphFixture(repeat = true)
        val alternatives = execute(repeated, false).third
        assertEquals(_l["0", "0", "1"], alternatives.map { map(it["ordinal"])["value"] })
        assertEquals(2, alternatives.take(2).map { map(it["source"])["value"] }.distinct().size)
        assertEquals(2, rows(rows(map(repeated["checkedProof"])["steps"]).last()["premises"]).size)
        assertEquals(3, execute(repeated, true).third.size, "Source alternatives are not extra binding/premise votes")
        // Even an unused evidence metadata substitution changes the context hash; it confers no authenticated authority.
        val evidence = map(report["sourceEvidence"]); val unused = map(evidence.values.last())
        val q = unused + _m["selectionId" j "changed-unused-selection"]
        val qid = "selection:" + ContentId.of(CanonicalCbor.encodeMap(q.entries.associate { (it.key as String j it.value).pair })).hex
        val changed = report + _m["sourceEvidence" j (evidence.filterKeys { it != evidence.keys.last() } + _m[qid j q])]
        assertNotEquals(projection.projectionCid, NativeHornProofGraph.project(changed).projectionCid)
    }

    @Test fun typedSourceRowsMissingPremisesAndChangedFramesCannotBecomeAnswers(): Unit {
        val report = nativeHornGraphFixture(); val (starter, response, answers) = execute(report, false)
        val query = map(response["quadQuery"]); val result = map(query["results"])
        fun withRows(rows: List<Map<*, *>>) = response + _m["quadQuery" j (query + _m["results" j (result +
            _m["results" j (map(result["results"]) + _m["bindings" j rows])])])]
        assertEquals(answers, NativeHornQueryView.answer(report, withRows(answers.reversed()), starter).rows)
        assertFails { NativeHornQueryView.answer(report, withRows(answers.take(1)), starter) }
        assertFails { NativeHornQueryView.answer(report, withRows(_l[answers[0], answers[0]]), starter) }
        val bad = answers[0] + _m["source" j (map(answers[0]["source"]) + _m["type" j "literal"])]
        assertFails { NativeHornQueryView.answer(report, withRows(_l[bad, answers[1]]), starter) }
        assertFails { NativeHornQueryView.answer(report, response + _m["synthetic" j false], starter) }
        val checked = map(report["checkedProof"]); val steps = rows(checked["steps"])
        for (badSteps in s_[steps.drop(1), steps + steps[0], steps.dropLast(1) + (steps.last() + _m["path" j _l[7]])].view)
            assertFails { NativeHornProofGraph.project(report + _m["checkedProof" j (checked + _m["steps" j badSteps])]) }
        val first = steps[0] + _m["arguments" j _l[_m["kind" j "literal", "spelling" j "\"Tenant\""], _m["kind" j "constant", "token" j "Person"]]]
        assertFails { NativeHornProofGraph.project(report + _m["checkedProof" j (checked + _m["steps" j (_l[first] + steps.drop(1))])]) }
        val unknown = report + _m["status" j "UNKNOWN", "proof" j null, "checkedProof" j null]
        assertNotNull(NativeHornProofGraph.project(unknown))
        assertFails { NativeHornQueryView.starter(unknown, bindings = true) }
    }

    @Test fun completeSourceContextAndProjectionBoundsRefuseWithoutPartialGraphs(): Unit {
        val report = nativeHornGraphFixture(); val checked = map(report["checkedProof"])
        val evidence = map(report["sourceEvidence"])
        assertFails { NativeHornProofGraph.project(report + _m["sourceEvidence" j evidence.filterKeys { it != evidence.keys.first() }]) }
        val raw = map(evidence.values.first())
        assertFails { NativeHornProofGraph.project(report + _m["sourceEvidence" j (evidence + _m[evidence.keys.first() j (raw + _m["begin" j 1])])]) }
        assertFailsWith<NativeHornProofGraph.ProfileRefused> {
            val unused = map(evidence.values.last()) + _m["quote" j "x".repeat(4097)]
            NativeHornProofGraph.project(report + _m["sourceEvidence" j (evidence + _m[evidence.keys.last() j unused])])
        }
        assertFailsWith<NativeHornProofGraph.ProfileRefused> {
            NativeHornProofGraph.project(report + _m["checkedProof" j (checked + _m["steps" j (0..4096).map { rows(checked["steps"])[0] }])])
        }
    }
}
