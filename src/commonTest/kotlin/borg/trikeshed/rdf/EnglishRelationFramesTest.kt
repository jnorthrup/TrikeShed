package borg.trikeshed.rdf

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.*
import borg.trikeshed.nlp.*
import kotlin.test.*

/** Hand-authored recorded annotations; these fixtures do not execute Stanford. */
internal fun relationSnapshot(text: String = "Alice pays Bob.", variant: String = "direct"): EnglishObservationGraph.Snapshot {
    val cas = object : CasStore(), BoundedCasReader {
        override fun getBounded(cid: ContentId, maxBytes: Int): ByteArray? = super.get(cid)?.also { require(it.size <= maxBytes) }
    }
    val cid = cas.put(text.encodeToByteArray())
    val revision = DocumentRevision("fixture", "relations.txt", cid.value, "1-fixture", 1)
    val source = DocumentSource(cid, cid, text, revision.id, "text/plain", "hand-authored-relations", project = revision, extraction = revision)
    val sentences = Regex("[^.]+\\.\"?").findAll(text).mapIndexed { ordinal, match ->
        val begin = match.range.first + match.value.indexOfFirst { !it.isWhitespace() }; val end = match.range.last + 1
        val words = Regex("[A-Za-z]+|[^\\sA-Za-z]").findAll(text.substring(begin, end)).mapIndexed { at, token ->
            val word = token.value
            val lemma = when (word.lowercase()) { "pays" -> "pay"; "owes" -> "owe"; "approves" -> "approve"; else -> word.lowercase() }
            val pos = when (word.lowercase()) { "pays", "owes", "approves" -> "VBZ"; "not" -> "RB"; "." -> "."; "\"" -> "``"; else -> "NNP" }
            NlpToken(at + 1, begin + token.range.first, begin + token.range.last + 1, word, lemma, pos, "O")
        }.toList()
        val root = words.first { it.lemma in _s["pay", "owe"] }
        val alice = words.first { it.word in _s["Alice", "Bob"] }
        val bob = words.last { it.word in _s["Alice", "Bob"] }
        val edges = words.map { token ->
            val relation = when {
                token.index == root.index -> "root"
                token.index == alice.index -> if (variant == "passive") "nsubj:pass" else "nsubj"
                token.index == bob.index -> "obj"
                token.lemma == "approve" -> "advcl"
                token.word == "if" -> "mark"
                token.word == "not" -> "neg"
                token.word == "." || token.word == "\"" -> "punct"
                token.word == "and" -> "cc"
                else -> "dep"
            }
            val governor = if (relation == "root") 0 else if (relation == "mark") words.first { it.lemma == "approve" }.index else root.index
            NlpDependency(governor, token.index, relation)
        }.toMutableList()
        if (variant == "copy") edges.add(NlpDependency(root.index, root.index, "dep", 0, 1))
        if (variant == "ambiguous") edges.add(NlpDependency(root.index, alice.index, "obj"))
        NlpSentence(ordinal, begin, end, words.toSeries(), edges.toSeries())
    }.toList().toSeries()
    val runtime = if (variant == "unreported") emptyMap() else _m["dependencyNodeIdentity" j "token-index+copy-count/1"]
    val parsed = NlpDocument(text, sentences, NlpMetadata("hand-authored-fixture", "EnglishRelationFramesTest", runtime))
    val observed = EnglishInspection.inspect(source, parsed, 0, text.length)
    val receipt = observed + _m["source" j (DocumentCuratorCodec.source(source) - "text"), "selection" j revision.toMap()]
    return EnglishObservationGraph.read(cas, cas.put(CanonicalCbor.encodeMap(receipt)))
}

class EnglishRelationFramesTest {
    private fun build(text: String = "Alice pays Bob.", variant: String = "direct") = EnglishRelationFrames.build(relationSnapshot(text, variant))
    private fun reasons(report: EnglishRelationFrames.Report) = (report.value["excluded"] as List<*>).flatMap { (it as Map<*, *>)["reasons"] as List<*> }

    @Test fun orderedRoles(): Unit {
        val a = build(); val b = build("Bob pays Alice.")
        assertEquals(1, EnglishRelationFrames.query(a, _m["subject" j "Alice", "object" j "Bob"]).size)
        assertTrue(EnglishRelationFrames.query(b, _m["subject" j "Alice", "object" j "Bob"]).isEmpty())
        val frame = a.frames.single(); val binding = frame["bindings"] as Map<*, *>
        for (name in s_["subject", "object", "predicate"].view) {
            val term = binding[name] as Map<*, *>
            assertEquals("uri", term["type"]); assertEquals(frame[name + "NodeIri"], term["value"])
        }
        assertNotEquals(a.cid, b.cid)
    }
    @Test fun predicateFilter(): Unit {
        val pay = build(); val owe = build("Alice owes Bob.")
        assertEquals(1, EnglishRelationFrames.query(pay, EnglishRelationFrames.request("{\"predicate\":\"pay\"}")).size)
        assertTrue(EnglishRelationFrames.query(owe, _m["predicate" j "pay"]).isEmpty())
        assertTrue(EnglishRelationFrames.query(pay, _m["predicate" j "pays"]).isEmpty())
        assertFailsWith<IllegalArgumentException> { EnglishRelationFrames.request(null) }
        assertFailsWith<IllegalArgumentException> { EnglishRelationFrames.request(_m["predicate" j _l["pay"]]) }
        assertFailsWith<IllegalArgumentException> { EnglishRelationFrames.request(_m["confidence" j "1"]) }
    }
    @Test fun repeatedSourceBasis(): Unit {
        val report = build("Alice pays Bob.\nAlice pays Bob.")
        assertEquals(2, report.frames.size)
        assertEquals(2, EnglishRelationFrames.validateBindings(report.snapshot, report.results).size)
        assertEquals(2, report.frames.map { it["occurrenceCid"] }.toSet().size)
        assertEquals(1, report.frames.map { it["basis"] }.toSet().size)
        assertEquals(_l[report.snapshot.source.extractedTextCid.value], report.value["sourceBasis"])
        assertEquals(false, report.value["countsAreEvidence"]); assertEquals("not-performed", report.value["admission"])
        val passages = EnglishRelationFrames.passages(report, report.frames)
        assertEquals(_l[0, 16], passages.map { it["begin"] })
        assertTrue(passages.all { it["basis"] == report.value["sourceBasis"] })
    }
    @Test fun copiedAndLegacyExcluded(): Unit {
        val copy = build(variant = "copy")
        assertTrue(copy.frames.isEmpty()); assertTrue("copied-nodes-outside-profile" in reasons(copy))
        assertFailsWith<IllegalArgumentException> { build(variant = "unreported") }
        val snapshot = relationSnapshot()
        val legacy = EnglishObservationGraph.Snapshot(snapshot.receiptCid, snapshot.source, snapshot.inventory,
            snapshot.selection, snapshot.sourceSnapshots, true, snapshot.parsed, snapshot.retainedReceipt)
        assertFailsWith<IllegalArgumentException> { EnglishRelationFrames.build(legacy) }
    }
    @Test fun passiveExcluded(): Unit {
        val report = build(variant = "passive")
        assertTrue(report.frames.isEmpty()); assertTrue("passive-outside-profile" in reasons(report))
        assertTrue("no-direct-nsubj-obj-join" in reasons(report))
    }
    @Test fun coordinatedAndAmbiguousExcluded(): Unit {
        val coordinated = build("Alice pays Bob and Carol.")
        assertTrue(coordinated.frames.isEmpty()); assertTrue("coordination-outside-profile" in reasons(coordinated))
        val ambiguous = build(variant = "ambiguous")
        assertTrue(ambiguous.frames.isEmpty()); assertTrue("ambiguous-or-unsupported-role-bindings" in reasons(ambiguous))
        val original = (((ambiguous.results["results"] as Map<*, *>)["bindings"]) as List<*>).map { it as Map<*, *> }
        assertEquals(2, original.size)
        val base = ambiguous.results.entries.associate { (it.key as String j it.value).pair }
        fun result(bindings: List<*>) = base + _m["results" j _m["bindings" j bindings]]
        // Even wholly excluded/ambiguous tuples must be complete, exact and unique.
        assertEquals(original.reversed(), EnglishRelationFrames.validateBindings(ambiguous.snapshot, result(original.reversed())))
        assertFailsWith<IllegalArgumentException> {
            EnglishRelationFrames.validateBindings(ambiguous.snapshot, result(_l[original[0], original[0]]))
        }
        assertFailsWith<IllegalArgumentException> {
            EnglishRelationFrames.validateBindings(ambiguous.snapshot, result(_l[original[0]]))
        }
        for (field in s_["sentence", "predicate", "subject", "object", "subjectEdge", "objectEdge"].view) {
            val forged = original[0] + _m[field j _m["type" j "uri", "value" j "urn:forged:$field"]]
            assertFailsWith<IllegalArgumentException> {
                EnglishRelationFrames.validateBindings(ambiguous.snapshot, result(_l[forged, original[1]]))
            }
            val term = original[0][field] as Map<*, *>
            val wrongKind = original[0] + _m[field j (term + _m["type" j "literal"])]
            assertFailsWith<IllegalArgumentException> {
                EnglishRelationFrames.validateBindings(ambiguous.snapshot, result(_l[wrongKind, original[1]]))
            }
        }
        val extraField = original[0] + _m["unexpected" j _m["type" j "uri", "value" j "urn:extra"]]
        assertFailsWith<IllegalArgumentException> {
            EnglishRelationFrames.validateBindings(ambiguous.snapshot, result(_l[extraField, original[1]]))
        }
        val sourceTerm = original[0]["subject"] as Map<*, *>
        val extraTermField = original[0] + _m["subject" j (sourceTerm + _m["datatype" j "urn:extra"])]
        assertFailsWith<IllegalArgumentException> {
            EnglishRelationFrames.validateBindings(ambiguous.snapshot, result(_l[extraTermField, original[1]]))
        }
        val wrongColumns = base + _m["head" j _m["vars" j _l["subject"]]]
        assertFailsWith<IllegalArgumentException> {
            EnglishRelationFrames.validateBindings(ambiguous.snapshot, wrongColumns)
        }
    }
    @Test fun quotationExcluded(): Unit {
        val report = build("\"Alice pays Bob.\"")
        assertTrue(report.frames.isEmpty()); assertTrue("quotation-outside-profile" in reasons(report))
        assertEquals(1, (report.value["excluded"] as List<*>).size)
    }
    @Test fun guardAndNegationRemainUnresolved(): Unit {
        val report = build("Alice not pays Bob if Carol approves.")
        val frame = report.frames.single()
        assertEquals(8, (frame["dependencies"] as List<*>).size)
        val guard = frame["guardObservations"] as Map<*, *>
        assertEquals(1, (guard["attachments"] as List<*>).size)
        val attachment = (guard["attachments"] as List<*>).single() as Map<*, *>
        assertTrue("guard-and-negation-scope-not-established" in (attachment["unresolved"] as List<*>))
        assertTrue((frame["markers"] as List<*>).size >= 2)
        assertEquals("recorded-parser-roles-only", report.value["authority"])
        val passage = EnglishRelationFrames.passages(report, report.frames).single()
        assertEquals(report.snapshot.source.text, passage["quote"])
    }
}
