package borg.trikeshed.nlp

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.rdf.*
import kotlin.test.*

/** Exact source/edge excerpts with reconstructed fixture annotations, not the original receipt or a parser execution. */
internal class GuardBaseline(val text: String, sourceHex: String, inspectionHex: String,
    tags: String, val edges: Series<NlpDependency>) {
    val sourceCid = ContentId("sha256:$sourceHex")
    /** External reference to the ORIGINAL receipt; reconstructed annotations never use this as their identity. */
    val originalBaselineInspectionCid = ContentId("sha256:$inspectionHex")
    val fixtureInspectionCid: ContentId
    val source = DocumentSource(sourceCid, sourceCid, text, "baseline.txt", "text/plain", "retained-observation-fixture")
    val parsed: NlpDocument
    init {
        require(ContentId.of(text.encodeToByteArray()) == sourceCid) { "Transcribed baseline source bytes differ" }
        val positions = tags.split(' ')
        val tokens = Regex("[A-Za-z]+|[^\\sA-Za-z]").findAll(text).mapIndexed { i, match ->
            val word = match.value
            val lemma = when (word.lowercase()) { "approves" -> "approve"; "does" -> "do"; "consents" -> "consent"; "acting" -> "act"; else -> word.lowercase() }
            NlpToken(i + 1, match.range.first, match.range.last + 1, word, lemma, positions[i], "O")
        }.toList().toSeries()
        parsed = NlpDocument(text, s_[NlpSentence(0, 0, text.length, tokens, edges)],
            NlpMetadata("retained-observation-fixture", "EnglishGuardAttachmentsTest",
                _m["dependencyNodeIdentity" j "token-index+copy-count/1"]))
        fixtureInspectionCid = ContentId.of(CanonicalCbor.encodeMap(_m["kind" j "transcribed-english-guard-fixture/1",
            "sourceCid" j sourceCid.value, "parsed" j DocumentCuratorCodec.nlp(parsed)]))
        require(fixtureInspectionCid != originalBaselineInspectionCid)
    }
}

internal fun guardBaseline(case: String): GuardBaseline = when (case) {
    "G01" -> GuardBaseline("Tenant must pay rent if Owner approves.",
        "33739ec22bf33c7433aee82d0325108428f02a922a840c664746198b7b8fa4b0",
        "9ee6a4c328fe5bff1dd898019e51ff862032992985f4a3c3270c100f4e4e27c2", "NN MD VB NN IN NN VBZ .",
        s_[NlpDependency(0,3,"root"),NlpDependency(3,1,"nsubj"),NlpDependency(3,2,"aux"),
            NlpDependency(3,4,"obj"),NlpDependency(7,5,"mark"),NlpDependency(7,6,"nsubj"),
            NlpDependency(3,7,"advcl:if"),NlpDependency(3,8,"punct")])
    "G03" -> GuardBaseline("Tenant must not pay rent if Owner approves.",
        "c70abbd6df1ad28bb9a6f3acc58f42ec98a20da7e8a8f9d30d4611b6ac9eb93b",
        "da73b415ed3d41efa0c0fa992797722f8bc0160adb59af3ce35c04b188181f85", "NN MD RB VB NN IN NN VBZ .",
        s_[NlpDependency(0,4,"root"),NlpDependency(4,1,"nsubj"),NlpDependency(4,2,"aux"),
            NlpDependency(4,3,"advmod"),NlpDependency(4,5,"obj"),NlpDependency(8,6,"mark"),
            NlpDependency(8,7,"nsubj"),NlpDependency(4,8,"advcl:if"),NlpDependency(4,9,"punct")])
    "G04" -> GuardBaseline("Tenant must pay rent if Owner does not approve.",
        "873863564944e2609740f52fb69dfcffc55985f6ad107e26b1ae788b0e115ee1",
        "f729da2140392817ddf9e019d249a537aacf85220da221983116b6baf26972ec", "NN MD VB NN IN NN VBZ RB VB .",
        s_[NlpDependency(0,3,"root"),NlpDependency(3,1,"nsubj"),NlpDependency(3,2,"aux"),
            NlpDependency(3,4,"obj"),NlpDependency(9,5,"mark"),NlpDependency(9,6,"nsubj"),
            NlpDependency(9,7,"aux"),NlpDependency(9,8,"advmod"),NlpDependency(3,9,"advcl:if"),NlpDependency(3,10,"punct")])
    "G07" -> GuardBaseline("Tenant must pay rent if Owner approves or Trustee consents.",
        "a01e1f4f42999a26fe8d505a75f35a24c82beb3cf18b82cd1e3d6ab96dc55896",
        "1899ec44c853e7633ef3bd06c65236806d18defeaf5ec6693fef97a2a5b39608", "NN MD VB NN IN NN VBZ CC NN NNS .",
        s_[NlpDependency(0,3,"root"),NlpDependency(3,1,"nsubj"),NlpDependency(3,2,"aux"),
            NlpDependency(3,4,"obj"),NlpDependency(7,5,"mark"),NlpDependency(7,6,"nsubj"),
            NlpDependency(3,7,"advcl:if"),NlpDependency(9,8,"cc"),NlpDependency(3,9,"advcl:if"),
            NlpDependency(7,9,"conj:or"),NlpDependency(7,10,"obj"),NlpDependency(3,11,"punct")])
    "G13" -> GuardBaseline("Tenant must record \"if Owner approves\" without acting on it.",
        "72e4cea248136d862bd637f94849aa82d055d947d9ddc60b2a5a4243fc45e2b4",
        "bb487a93ac29498b4e0d8c88f4377005da45b0065034854bc750ac4567afaa39", "NN MD VB `` IN NN VBZ `` IN VBG IN PRP .",
        s_[NlpDependency(0,3,"root"),NlpDependency(3,1,"nsubj"),NlpDependency(3,2,"aux"),
            NlpDependency(3,4,"punct"),NlpDependency(7,5,"mark"),NlpDependency(7,6,"nsubj"),
            NlpDependency(3,7,"advcl:if"),NlpDependency(7,8,"punct"),NlpDependency(10,9,"case"),
            NlpDependency(7,10,"advcl:without"),NlpDependency(12,11,"case"),NlpDependency(10,12,"obl:on"),NlpDependency(3,13,"punct")])
    else -> error("Unknown materialized baseline case")
}

class EnglishGuardAttachmentsTest {
    private fun rows(value: Any?) = (value as List<*>).map { it as Map<String, Any?> }
    private fun patterns(f: GuardBaseline) = EnglishLegalPatterns.inspect(f.source, f.parsed, 0, f.text.length)
    private fun guard(result: Map<String, Any?>) = rows((result["guardAttachments"] as Map<*, *>)["sentences"]).single()
    private fun payload(f: GuardBaseline, value: Map<String, Any?> = patterns(f)) = value + _m[
        "inspectionCid" j f.fixtureInspectionCid.value, "parserExecution" j "not-reverified",
        "producerAuthority" j "not-established", "unresolved" j emptyList<String>()]
    private fun project(f: GuardBaseline, value: Map<String, Any?> = payload(f)) =
        EnglishLegalGraph.project(ContentId.of(CanonicalCbor.encodeMap(value)), f.fixtureInspectionCid, value, f.source)

    @Test fun pinnedMatrixAndGuardNegationHaveDifferentDirectHeadsAndExistingDiagnostics() {
        val positive = guard(patterns(guardBaseline("G01")))
        assertNull(positive["normConditionIssue"])
        assertEquals(1, rows(positive["attachments"]).size)
        assertTrue(rows(rows(positive["attachments"]).single()["directNegationCueBindings"]).isEmpty())
        val matrix = guard(patterns(guardBaseline("G03")))
        val subordinate = guard(patterns(guardBaseline("G04")))
        assertNull(matrix["normConditionIssue"])
        assertEquals("NEGATED", subordinate["normConditionIssue"])
        val a = rows(rows(matrix["attachments"]).single()["directNegationCueBindings"]).single()
        val b = rows(rows(subordinate["attachments"]).single()["directNegationCueBindings"]).single()
        assertEquals("governor-head", a["location"]); assertEquals("4:0", a["governorNodeId"])
        assertEquals("subordinate-head", b["location"]); assertEquals("9:0", b["governorNodeId"])
        assertEquals("3:0", a["cueNodeId"]); assertEquals("8:0", b["cueNodeId"])
        assertEquals("advmod", (a["dependency"] as Map<*, *>)["relation"])
        assertEquals("advmod", (b["dependency"] as Map<*, *>)["relation"])
    }

    @Test fun coordinatedUnmarkedTargetAndQuotedNestedAttachmentAreNeverChosenOrRewritten() {
        val f = guardBaseline("G07"); val result = patterns(f); val g = guard(result)
        val attachments = rows(g["attachments"])
        assertEquals("COORDINATED", g["normConditionIssue"])
        assertEquals(_l["7:0", "9:0"], attachments.map { it["subordinateNodeId"] })
        assertEquals(_l[1], attachments[0]["markerBindingOrdinals"])
        assertEquals(emptyList<Int>(), attachments[1]["markerBindingOrdinals"])
        assertTrue("no-direct-guard-marker-binding" in attachments[1]["unresolved"] as List<*>)
        assertTrue(attachments.all { rows(it["coordinationDependencies"]).size == 2 })
        assertEquals("NNS", rows(rows(result["sentences"]).single()["nodes"]).single { it["id"] == "10:0" }["pos"])
        val quoted = guard(patterns(guardBaseline("G13")))
        assertEquals("NEGATED", quoted["normConditionIssue"], "Existing diagnostic is not a quote refusal")
        assertEquals(_l["4:0", "8:0"], quoted["quotationIndicatorNodeIds"])
        assertEquals(_l["advcl:if", "advcl:without"], rows(quoted["attachments"]).map { (it["dependency"] as Map<*, *>)["relation"] })
        assertTrue(rows(quoted["attachments"]).all { rows(it["directNegationCueBindings"]).isEmpty() }, "Without has no fabricated negation-marker/neg edge")
        assertEquals(emptyList<Int>(), rows(quoted["attachments"])[1]["modalActionOrdinals"])
    }

    @Test fun quadEngineReturnsBothTypedAttachmentRowsWithFixtureInspectionProvenance() {
        val f = guardBaseline("G07"); val value = payload(f); val store = project(f, value)
        val v = EnglishLegalGraph.VOCAB
        val text = "SELECT ?link ?governor ?subordinate ?relation ?issue ?sentence WHERE { " +
            "?link <${v}kind> \"adverbial-attachment-observation\" . ?link <${v}governorNode> ?g . " +
            "?g <${v}word> ?governor . ?link <${v}subordinateNode> ?s . ?s <${v}word> ?subordinate . " +
            "?link <${v}attachmentRelation> ?relation . ?link <${v}normConditionIssue> ?issue . ?link <${v}sentence> ?sentence . }"
        val reply = SparqlSurface(store).answer(text, emptyList(), emptyList(), "json", "")
        assertEquals(200, reply.status)
        val bindings = rows((reifyStrict(reply.body) as Map<*, *>)["results"].let { (it as Map<*, *>)["bindings"] })
        fun term(row: Map<String, Any?>, name: String) = row[name] as Map<*, *>
        assertEquals(_s["approves", "Trustee"], bindings.map { term(it, "subordinate")["value"] }.toSet())
        assertTrue(bindings.all { term(it, "link")["type"] == "uri" && term(it, "issue")["value"] == "COORDINATED" })
        val cid = ContentId.of(CanonicalCbor.encodeMap(value)); val graph = "<${EnglishLegalGraph.graph(cid)}>"
        for (row in bindings) assertTrue(store.contains("<${term(row,"link")["value"]}>", "<${v}inspectionReceipt>", "<urn:sha256:${f.fixtureInspectionCid.hex}>", graph))
    }

    @Test fun legacyAbsenceProjectsWhilePresentUnknownMissingDuplicatedAndSubstitutedBindingsRefuse() {
        val f = guardBaseline("G07"); val value = payload(f)
        val legacy = value - "guardAttachments"
        val old = project(f, legacy)
        assertTrue(old.size < project(f, value).size)
        val section = value["guardAttachments"] as Map<String, Any?>
        val g = rows(section["sentences"]).single(); val attachments = rows(g["attachments"])
        fun changed(row: Map<String, Any?>) = value + _m["guardAttachments" j (section + _m["sentences" j _l[row]])]
        val bad = s_[
            value + _m["guardAttachments" j null], value + _m["guardAttachments" j (section + _m["profile" j "unknown/2"])],
            changed(g + _m["attachments" j attachments.drop(1)]),
            changed(g + _m["attachments" j (attachments + attachments[0])]),
            changed(g + _m["attachments" j _l[attachments[0] + _m["subordinateNodeId" j "7:1"], attachments[1]]]),
            changed(g + _m["attachments" j _l[attachments[0], attachments[1] + _m["markerBindingOrdinals" j _l[1]]]])
        ]
        for (tampered in bad.view) assertFailsWith<IllegalArgumentException> { project(f, tampered) }
        val altered = attachments[0] + _m["dependency" j _m["governorNode" j "7:0", "dependentNode" j "3:0", "relation" j "advcl:if"]]
        assertFailsWith<IllegalArgumentException> { project(f, changed(g + _m["attachments" j _l[altered, attachments[1]]])) }
        val marker = rows(g["markerBindings"]).single()
        val wrongQuote = marker + _m["marker" j ((marker["marker"] as Map<String, Any?>) + _m["quote" j "unless"])]
        assertFailsWith<IllegalArgumentException> { project(f, changed(g + _m["markerBindings" j _l[wrongQuote]])) }
    }

    @Test fun fullCopiedIdentityAndPartialUnicodeRangeRemainSourceObservationsOnly() {
        val f = guardBaseline("G04"); val sentence = f.parsed.sentences[0]
        val extra = NlpDependency(3,9,"advcl:if",0,1)
        val copied = f.parsed.copy(sentences = s_[sentence.copy(dependencies = (f.edges.size + 1) j { i: Int -> if (i < f.edges.size) f.edges[i] else extra })])
        val result = EnglishLegalPatterns.inspect(f.source, copied, 0, f.text.length)
        val g = guard(result)
        assertEquals("COPIED_NODE", g["normConditionIssue"])
        assertEquals(_l["9:0", "9:1"], rows(g["attachments"]).map { it["subordinateNodeId"] })
        assertEquals(emptyList<Int>(), rows(g["attachments"])[1]["markerBindingOrdinals"])
        // The genuine corpus has zero copies; this is explicitly an additional synthetic graph test.
        val prefix = "🧾\r\n"; val text = prefix + f.text + "\r\n"
        val sourceCid = ContentId.of(text.encodeToByteArray())
        val source = f.source.copy(originalCid = sourceCid, extractedTextCid = sourceCid, text = text)
        val ranged = EnglishLegalPatterns.inspect(source, f.parsed, prefix.length, prefix.length + f.text.length)
        val marker = rows(guard(ranged)["markerBindings"]).single()["marker"] as Map<*, *>
        assertEquals("if", source.text.substring(marker["begin"] as Int, marker["end"] as Int))
        assertEquals(21 + prefix.length, marker["begin"])
        assertEquals(false, (ranged["range"] as Map<*, *>)["complete"])
        val unreported = f.parsed.copy(metadata = NlpMetadata("edu.stanford.nlp.pipeline.StanfordCoreNLP", "historical"))
        assertEquals("UNREPORTED_IDENTITY", guard(EnglishLegalPatterns.inspect(f.source, unreported, 0, f.text.length))["normConditionIssue"])
    }

    @Test fun overlappingUnlessMarkerLabelsKeepBothOrdinalsAndOneObservedAttachment() {
        val f = guardBaseline("G01")
        val text = f.text.replace("if", "unless")
        val cid = ContentId.of(text.encodeToByteArray())
        val tokens = f.parsed.sentences[0].tokens α { token -> when {
            token.word == "if" -> token.copy(word = "unless", lemma = "unless", end = token.end + 4)
            token.begin > 23 -> token.copy(begin = token.begin + 4, end = token.end + 4)
            else -> token
        } }
        val parsed = f.parsed.copy(text = text, sentences = s_[f.parsed.sentences[0].copy(end = text.length, tokens = tokens)])
        val g = guard(EnglishLegalPatterns.inspect(f.source.copy(originalCid = cid, extractedTextCid = cid, text = text), parsed, 0, text.length))
        assertNull(g["normConditionIssue"])
        val markers = rows(g["markerBindings"])
        assertEquals(_s["condition", "exception"], markers.map { (it["marker"] as Map<*, *>)["kind"] }.toSet())
        assertEquals(1, rows(g["attachments"]).size)
        assertEquals(markers.map { it["markerOrdinal"] }, rows(g["attachments"]).single()["markerBindingOrdinals"])
    }

    @Test fun completeAttachmentBudgetRefusesBeforeReturningAnIncompleteSection() {
        fun inspect(count: Int): Map<String, Any?> {
            val text = (0..count).joinToString(" ") { "word$it" }
            val tokens = Regex("\\S+").findAll(text).mapIndexed { i, match -> NlpToken(i+1, match.range.first,
                match.range.last+1, match.value, match.value, "NN", "O") }.toList().toSeries()
            val edges = tokens α { NlpDependency(if (it.index == 1) 0 else 1, it.index, if (it.index == 1) "root" else "advcl") }
            val cid = ContentId.of(text.encodeToByteArray())
            return EnglishLegalPatterns.inspect(DocumentSource(cid,cid,text,"budget","text/plain","fixture"),
                NlpDocument(text,s_[NlpSentence(0,0,text.length,tokens,edges)]),0,text.length)
        }
        assertEquals(1024, rows(guard(inspect(1024))["attachments"]).size)
        val failure = assertFailsWith<IllegalArgumentException> { inspect(1025) }
        assertTrue(failure.message.orEmpty().contains("attachment limit"))
    }
}
