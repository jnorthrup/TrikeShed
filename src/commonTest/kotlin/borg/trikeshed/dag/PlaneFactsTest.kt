package borg.trikeshed.dag

import borg.trikeshed.job.ContentId
import borg.trikeshed.kif.KifExpr
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.rdf.RdfGraph
import borg.trikeshed.rdf.RdfTerm
import borg.trikeshed.rdf.RdfVocab
import borg.trikeshed.rdf.TurtleRdf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaneFactsTest {

    private fun cableFact() = PlaneFacts.fact(
        PlaneFacts.PANELS,
        "demo panel/cable/3",
        linkedMapOf(
            PlaneFacts.KIND to "cable",
            PlaneFacts.KEY to "demo panel",
            PlaneFacts.ACTOR to "lcnc",
            PlaneFacts.AT_MS to 1_725_000_000_000L,
            "fromNode" to "n1",
            "fromPort" to "out",
            "toNode" to "n2",
            "toPort" to "in?",
            "type" to "List<TurnFact>",
            "tags" to listOf("alpha", "beta", "gamma"),
            "weight" to 0.5,
            "enabled" to true,
            "note" to "she said \"hi\"\nthen \\left",
            "nothing" to null,
            "shape" to mapOf("z" to 1, "a" to listOf(2, 3)),
        ),
    )

    // ── versionOf ─────────────────────────────────────────────────────────

    @Test
    fun versionIsStableUnderTopLevelAndNestedKeyOrder() {
        val a = linkedMapOf<String, Any?>("kind" to "x", "key" to "k", "shape" to linkedMapOf("z" to 1, "a" to 2), "n" to 3)
        val b = linkedMapOf<String, Any?>("n" to 3, "shape" to linkedMapOf("a" to 2, "z" to 1), "key" to "k", "kind" to "x")
        assertEquals(PlaneFacts.versionOf(a), PlaneFacts.versionOf(b))
        // JsonSupport.stringify joins with ", " — deterministic, which is all canonical needs
        assertEquals("""{"key":"k", "kind":"x", "n":3, "shape":{"a":2, "z":1}}""", PlaneFacts.canonicalJson(a))
        assertEquals(PlaneFacts.canonicalJson(a), PlaneFacts.canonicalJson(b))

        val changed = linkedMapOf<String, Any?>("n" to 4, "shape" to linkedMapOf("a" to 2, "z" to 1), "key" to "k", "kind" to "x")
        assertNotEquals(PlaneFacts.versionOf(a), PlaneFacts.versionOf(changed))
        val nestedChanged = a + ("shape" to mapOf("z" to 1, "a" to 3))
        assertNotEquals(PlaneFacts.versionOf(a), PlaneFacts.versionOf(nestedChanged))
        // list order is meaning, not noise
        assertNotEquals(PlaneFacts.versionOf(mapOf("l" to listOf(1, 2))), PlaneFacts.versionOf(mapOf("l" to listOf(2, 1))))
        // a null field is part of the version
        assertNotEquals(PlaneFacts.versionOf(mapOf("a" to 1)), PlaneFacts.versionOf(mapOf("a" to 1, "b" to null)))
    }

    @Test
    fun factDefaultsItsVersionToVersionOfTheFields() {
        val f = cableFact()
        assertEquals(PlaneFacts.versionOf(f.fields), f.versionCid)
        assertEquals(PlaneFacts.PANELS, f.board.id)
        assertEquals(PlaneFacts.PANELS to "demo panel", PlaneFacts.keyOf(f))
        // pre-plane facts (couch, board) have no `key`; identity is the localId
        val legacy = ReteStoredFact(FactId("trikeshed", "panels/x"), mapOf("_id" to "panels/x"), ContentId.of(byteArrayOf()), borg.trikeshed.cursor.BlackboardContext("trikeshed"))
        assertEquals("trikeshed" to "panels/x", PlaneFacts.keyOf(legacy))
    }

    // ── projections ───────────────────────────────────────────────────────

    /** 13 scalars (kind key actor atMs fromNode fromPort toNode toPort type weight enabled note + the map collapsed to one literal; null emits nothing) + list fan-out (3) */
    private val expectedProjectionCount = 13 + 3

    @Test
    fun tripleAndKifCountsAreScalarsPlusListFanOut() {
        val f = cableFact()
        val triples = PlaneFacts.toTriples(f)
        val kif = PlaneFacts.toKif(f)
        assertEquals(expectedProjectionCount, triples.size, triples.joinToString("\n") { it.toTurtle() })
        assertEquals(expectedProjectionCount, kif.size, kif.joinToString("\n") { it.toKifString() })

        val subject = PlaneFacts.factIri(f.factId)
        assertTrue(triples.all { it.s == subject })
        assertEquals(3, triples.count { it.p == PlaneFacts.fieldIri("tags") }, "list fans out per element")
        assertEquals(listOf("alpha", "beta", "gamma"), triples.filter { it.p == PlaneFacts.fieldIri("tags") }.map { (it.o as RdfTerm.Literal).lexical })
        assertTrue(triples.none { it.p == PlaneFacts.fieldIri("nothing") }, "null emits nothing")
        assertEquals(
            RdfTerm.Literal("1725000000000", datatype = RdfVocab.XSD + "integer"),
            triples.single { it.p == PlaneFacts.fieldIri(PlaneFacts.AT_MS) }.o,
        )
        assertEquals(RdfTerm.Literal("true", datatype = RdfVocab.XSD + "boolean"), triples.single { it.p == PlaneFacts.fieldIri("enabled") }.o)
        assertEquals(RdfTerm.Literal("0.5", datatype = RdfVocab.XSD + "double"), triples.single { it.p == PlaneFacts.fieldIri("weight") }.o)
        assertEquals(RdfTerm.Literal("""{"a":[2, 3], "z":1}"""), triples.single { it.p == PlaneFacts.fieldIri("shape") }.o)
    }

    @Test
    fun everyKifTupleHasArityThreeKindComesFirstAndAllReparse() {
        val f = cableFact()
        val kif = PlaneFacts.toKif(f)
        val subject = PlaneFacts.factIri(f.factId).iri
        for (e in kif) {
            val list = e as KifExpr.ListExpr
            assertEquals(3, list.elements.size, e.toKifString())
            assertEquals(KifExpr.Atom(subject), list.elements[1])
            assertEquals(e, KifExpr.parse(e.toKifString()), "re-parses to itself: ${e.toKifString()}")
        }
        assertEquals("(kind $subject cable)", kif.first().toKifString())
        assertEquals(1, kif.count { (it as KifExpr.ListExpr).elements[0] == KifExpr.Atom("kind") })
        // a CCEK type with angle brackets is one bare token; a value with spaces/quotes is quoted
        assertTrue(kif.any { it.toKifString() == "(type $subject List<TurnFact>)" }, kif.joinToString("\n") { it.toKifString() })
        assertTrue(kif.any { it.toKifString() == "(key $subject \"demo panel\")" })
        assertTrue(kif.any { it.toKifString() == "(note $subject \"she said \\\"hi\\\"\\nthen \\\\left\")" })
        assertEquals(3, kif.count { (it as KifExpr.ListExpr).elements[0] == KifExpr.Atom("tags") })
        assertTrue(kif.none { (it as KifExpr.ListExpr).elements[0] == KifExpr.Atom("nothing") })

        // the string path and the expr path land the same tuple in a bank, and unify on a bare atom
        val bank = KifKnowledgeBase()
        kif.forEach(bank::assert)
        kif.forEach { bank.assertKif(it.toKifString()) }
        assertEquals(expectedProjectionCount, bank.asserts().size, "exact-string dedupe")
        val hits = bank.query(KifExpr.parse("(kind ?f cable)"))
        assertEquals(listOf(mapOf("?f" to subject)), hits)
        assertEquals(listOf(mapOf("?t" to "List<TurnFact>")), bank.query(KifExpr.parse("(type $subject ?t)")))
    }

    @Test
    fun triplesRoundTripThroughTurtle() {
        val facts = listOf(
            cableFact(),
            PlaneFacts.fact(PlaneFacts.BLACKBOARD, "lcnc/program/x <y>", mapOf(PlaneFacts.KIND to "blackboard", PlaneFacts.KEY to "lcnc/program/x <y>", "value" to "a\tb%c;d(e)'f?")),
            PlaneFacts.fact(PlaneFacts.GRAAL, "gc/G1 Young", mapOf(PlaneFacts.KIND to "gc", PlaneFacts.KEY to "gc/G1 Young", "collections" to 7, "pauseMsTotal" to 12.25, "lastCause" to "Allocation Failure")),
        )
        val expected = facts.flatMap(PlaneFacts::toTriples)
        val turtle = PlaneFacts.toTurtle(facts)
        val parsed: RdfGraph = TurtleRdf.parse(turtle)
        assertEquals(expected.toSet(), parsed.triples.toSet(), turtle)
        assertEquals(expected.size, parsed.triples.size, "no triple lost or duplicated")
    }

    @Test
    fun factIriIsReversibleAndNeverContainsAnIriOrKifBreaker() {
        val ids = listOf(
            FactId(PlaneFacts.PANELS, "demo panel/cable/3"),
            FactId(PlaneFacts.BLACKBOARD, "lcnc/program/x <y>"),
            FactId(PlaneFacts.GRAAL, "deopt/java.lang.String::hashCode()I"),
            FactId("p", "quote\"semi;paren(q)?var'tick%pct ünï"),
        )
        for (id in ids) {
            val iri = PlaneFacts.factIri(id)
            assertTrue(iri.iri.startsWith(PlaneFacts.FACT_NS + id.partitionId + "/"), iri.iri)
            for (c in "<>\" \t\n;()'?%{}|\\^`") {
                if (c == '%') continue // the escape itself
                assertTrue(c !in iri.iri.removePrefix(PlaneFacts.FACT_NS), "'$c' leaked into ${iri.iri}")
            }
            assertTrue(iri.iri.all { it.code < 128 }, iri.iri)
            assertEquals(id, PlaneFacts.factIdOf(iri))
        }
        assertNull(PlaneFacts.factIdOf(RdfTerm.Iri("https://elsewhere/x")))
        assertEquals("lcnc/program/x <y>", PlaneFacts.decodeIriPart(PlaneFacts.encodeIriPart("lcnc/program/x <y>")))
    }
}
