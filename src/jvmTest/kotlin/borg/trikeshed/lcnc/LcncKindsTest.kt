package borg.trikeshed.lcnc

import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CABLES ARE NEVER UNTYPED ([LcncKinds], [LcncTypeCheck], [LcncFacts]). A
 * cable carries exactly its source port's type and the sink must be that
 * type. The case that motivates every test: `beliefs.introspect.field` wired
 * into `beliefs.review.facts`, both called `json`, and the curator reviewed
 * nothing while a gate stayed green. The field is a Confix object; what the
 * review consumes is `List<TurnFact>`; that is not a cable.
 */
class LcncKindsTest {

    private val turnFacts = """[{"verb":"bash","ok":true,"context":"build","object":"gradle"},""" +
        """{"verb":"edit","ok":true,"context":"build","object":"kotlin"}]"""

    private val facts = LcncFacts.of(LcncContracts.all())

    @Test
    fun aCableHasOneTypeAndBothEndsMustBeIt() {
        assertTrue(facts.accepts("List<TurnFact>", "List<TurnFact>"))
        assertFalse(facts.accepts("json", "List<TurnFact>"), "a Confix object is not a list of turn facts")
        assertFalse(facts.accepts("List<TurnFact>", "json"), "no covariance: exact means exact")
        assertFalse(facts.accepts("text", "json"))
        assertTrue(facts.accepts("List<TurnFact>", LcncKinds.CCEK_ANY), "a runner that declares Any is exact for any cable")
        assertTrue(facts.accepts(LcncKinds.UNRESOLVED, "json"), "an unresolved ring port claims nothing")
    }

    @Test
    fun thereIsNoHierarchyAndAcceptanceIsIdentityPlusAny() {
        assertTrue(facts.hierarchy().isEmpty())
        // itself, Any, and the type variables the daemon resolves per node (T on coalesce)
        val variables = facts.kinds().filter { LcncKinds.isTypeVariable(it) }
        assertTrue("T" in variables, "coalesce declares T: $variables")
        assertEquals((listOf("Any", "List<TurnFact>") + variables).sorted(), facts.acceptance()["List<TurnFact>"])
        assertEquals((listOf("Any", "json") + variables).sorted(), facts.acceptance()["json"])
        assertEquals(mapOf("List<TurnFact>" to listOf("verb", "ok", "context", "object")), facts.shapes())
    }

    @Test
    fun theCuratorNoOpWireIsATypedViolation() {
        val program = LcncProgram(
            "curator-bug",
            listOf(LcncNode("n2", "beliefs.introspect"), LcncNode("n3", "beliefs.review")).toSeries(),
            listOf(LcncWire("n2", "field", "n3", "facts")).toSeries(),
        )
        val v = LcncTypeCheck.check(program)
        assertEquals(1, v.size, "$v")
        assertEquals("kind-mismatch", v[0].rule)
        assertTrue("json" in v[0].detail && "List<TurnFact>" in v[0].detail, v[0].detail)
        assertEquals(listOf("json"), LcncTypeCheck.cableTypes(program), "the cable's type is its source's, on the blackboard")
    }

    @Test
    fun anAuthoredTurnFactListIsAListOfTurnFacts() {
        val lit = LcncNode("n0", "json.value", params = mapOf("value" to turnFacts))
        val program = LcncProgram(
            "curator-fixed",
            listOf(lit, LcncNode("n3", "beliefs.review")).toSeries(),
            listOf(LcncWire("n0", "value", "n3", "facts")).toSeries(),
        )
        assertEquals(emptyList(), LcncTypeCheck.check(program))
        assertEquals(listOf("List<TurnFact>"), LcncTypeCheck.cableTypes(program))
        val contracts = LcncContracts.all().associateBy { it.type }
        assertEquals("List<TurnFact>", LcncTypeCheck.portKind(lit, "out", "value", contracts).kind)
        val mates = LcncMating.compatibleTypes(program, "n0", "value")
        assertTrue(mates.any { it.type == "beliefs.review" && it.inputPort == "facts" }, "$mates")
    }

    @Test
    fun anUnshapedLiteralIsJustJsonAndDoesNotReachTheReview() {
        val lit = LcncNode("n0", "json.value", params = mapOf("value" to """[{"verb":"bash"}]"""))
        val program = LcncProgram(
            "curator-unshaped",
            listOf(lit, LcncNode("n3", "beliefs.review")).toSeries(),
            listOf(LcncWire("n0", "value", "n3", "facts")).toSeries(),
        )
        val v = LcncTypeCheck.check(program)
        assertEquals(1, v.size, "$v")
        assertEquals("kind-mismatch", v[0].rule)
        assertFalse(LcncMating.compatibleTypes(program, "n0", "value").any { it.type == "beliefs.review" })
    }

    @Test
    fun aCableIntoDisplayIsExactBecauseDisplayDeclaresAny() {
        val program = LcncProgram(
            "to-display",
            listOf(
                LcncNode("n2", "beliefs.introspect"),
                LcncNode("n0", "json.value", params = mapOf("value" to turnFacts)),
                LcncNode("d1", "display"), LcncNode("d2", "display"),
            ).toSeries(),
            listOf(LcncWire("n2", "field", "d1", "x"), LcncWire("n0", "value", "d2", "x")).toSeries(),
        )
        assertEquals(emptyList(), LcncTypeCheck.check(program))
        assertEquals(listOf("json", "List<TurnFact>"), LcncTypeCheck.cableTypes(program))
    }

    @Test
    fun autoWireUsesTheSameRule() {
        val nodes = listOf(LcncNode("from", "beliefs.introspect"), LcncNode("to", "beliefs.review")).toSeries()
        val r = LcncMating.autoWire(LcncProgram("aw", nodes, emptySeriesOf()), "from", "to")
        assertNull(r.wire)
        assertTrue(r.candidates.isEmpty(), "${r.candidates}")
    }

    @Test
    fun aCableFixesTheTypeOfAnUndeclaredRingParameterAndInnerCablesObeyIt() {
        fun program(innerSink: String, innerPort: String) = LcncProgram(
            "infer",
            nodes = listOf(
                LcncNode("src", "text.value", params = mapOf("value" to "hi")),
                LcncNode(
                    "r", LcncContracts.SCOPE,
                    children = listOf(
                        LcncNode("in", LcncContracts.SCOPE_IN, params = mapOf("name" to "brief")),
                        LcncNode("k", innerSink),
                    ).toSeries(),
                ),
            ).toSeries(),
            wires = listOf(LcncWire("src", "value", "r", "brief"), LcncWire("in", "value", "k", innerPort)).toSeries(),
        )
        // The text cable fixes `brief` as text; inside, brief → mux.chat.prompt? (text) obeys.
        val ok = program("mux.chat", "prompt?")
        assertEquals(emptyList(), LcncTypeCheck.check(ok))
        assertEquals(listOf("text", "text"), LcncTypeCheck.cableTypes(ok), "the inner cable carries the inferred type")
        // brief → list.format.x (json): the cable is text, the sink wants json — refused, no wildcard.
        val v = LcncTypeCheck.check(program("list.format", "x"))
        assertEquals(1, v.size, "$v")
        assertEquals("kind-mismatch", v[0].rule)
        assertTrue("text" in v[0].detail && "json" in v[0].detail, v[0].detail)
    }

    @Test
    fun everyOfferedPresetTypeChecksAndEveryCableIsTyped() {
        for ((name, json) in LcncPresets.all()) {
            val p = LcncProgramConfix.fromJson(name, json)
            val v = LcncTypeCheck.check(p)
            assertTrue(v.isEmpty(), "$name:\n" + v.joinToString("\n") { "  " + it.render() })
        }
    }

    // ── the review's confirmed defects, pinned ─────────────────────────

    @Test
    fun aCompositeReferencedByTypeIsUnresolvedNeverUntyped() {
        // comp has no contract (a stored program run by type); its cable into a ring
        // parameter must not leak "untyped" into the ring — display declares Any.
        val program = LcncProgram(
            "composite-into-ring",
            nodes = listOf(
                LcncNode("comp", "my-stored-program"),
                LcncNode(
                    "r", LcncContracts.SCOPE,
                    children = listOf(
                        LcncNode("in", LcncContracts.SCOPE_IN, params = mapOf("name" to "p")),
                        LcncNode("d", "display"),
                    ).toSeries(),
                ),
            ).toSeries(),
            wires = listOf(LcncWire("comp", "y", "r", "p"), LcncWire("in", "value", "d", "x")).toSeries(),
        )
        assertEquals(emptyList(), LcncTypeCheck.check(program, strict = false))
        assertEquals(listOf(null, null), LcncTypeCheck.cableTypes(program))
    }

    @Test
    fun aNestedScopeInInheritsTheOuterRingsTypeLikeTheRuntimeDoes() {
        val program = LcncProgram(
            "nested",
            nodes = listOf(
                LcncNode("src", "text.value", params = mapOf("value" to "hi")),
                LcncNode(
                    "outer", LcncContracts.SCOPE,
                    children = listOf(
                        LcncNode("in_o", LcncContracts.SCOPE_IN, params = mapOf("name" to "brief")),
                        LcncNode(
                            "inner", LcncContracts.SCOPE,
                            children = listOf(
                                LcncNode("in_i", LcncContracts.SCOPE_IN, params = mapOf("name" to "brief")),
                                LcncNode("k", "list.format"),
                            ).toSeries(),
                        ),
                    ).toSeries(),
                ),
            ).toSeries(),
            wires = listOf(LcncWire("src", "value", "outer", "brief"), LcncWire("in_i", "value", "k", "x")).toSeries(),
        )
        val v = LcncTypeCheck.check(program)
        assertEquals(1, v.size, "$v")
        assertEquals("kind-mismatch", v[0].rule)
        assertEquals(listOf("text", "text"), LcncTypeCheck.cableTypes(program))
    }

    @Test
    fun aCompositesUndeclaredFormalDoesNotFixATypeVariable() {
        val body = LcncProgram(
            "my-stored-program",
            listOf(LcncNode("o", LcncContracts.SCOPE_OUT, params = mapOf("name" to "y"))).toSeries(),
            emptyList<LcncWire>().toSeries(),
        )
        val vocabulary = LcncVocabulary.resolve(mapOf("my-stored-program" to body))
        val program = LcncProgram(
            "coalesce-star",
            nodes = listOf(
                LcncNode("comp", "my-stored-program"),
                LcncNode("c", "coalesce"),
                LcncNode("lit", "json.value", params = mapOf("value" to "[1]")),
                LcncNode("m", "mux.chat"),
            ).toSeries(),
            wires = listOf(
                LcncWire("comp", "y", "c", "a?"), LcncWire("lit", "value", "c", "b"), LcncWire("c", "value", "m", "prompt?"),
            ).toSeries(),
        )
        // T is fixed by the json literal (the `*` formal claims nothing); json into a text prompt is refused.
        val v = LcncTypeCheck.check(program, vocabulary, strict = false)
        assertEquals(1, v.size, "$v")
        assertTrue("json" in v[0].detail && "text" in v[0].detail, v[0].detail)
        assertEquals(listOf(null, "json", "json"), LcncTypeCheck.cableTypes(program, vocabulary))
    }

    @Test
    fun aTextLiteralIsTextHoweverItsValueLooks() {
        val lit = LcncNode("n0", "text.value", params = mapOf("value" to turnFacts))
        val program = LcncProgram(
            "text-literal",
            listOf(lit, LcncNode("m", "mux.chat")).toSeries(),
            listOf(LcncWire("n0", "value", "m", "prompt?")).toSeries(),
        )
        assertEquals(emptyList(), LcncTypeCheck.check(program))
        assertEquals(listOf("text"), LcncTypeCheck.cableTypes(program))
    }

    @Test
    fun theLastTypedCableFixesARingParameterWhateverTheWireOrder() {
        fun program(first: LcncWire, second: LcncWire) = LcncProgram(
            "last-wins",
            nodes = listOf(
                LcncNode("nr", LcncContracts.SCOPE, subprogram = "somewhere"),
                LcncNode("t", "text.value", params = mapOf("value" to "hi")),
                LcncNode(
                    "r", LcncContracts.SCOPE,
                    children = listOf(
                        LcncNode("in", LcncContracts.SCOPE_IN, params = mapOf("name" to "p")),
                        LcncNode("k", "list.format"),
                    ).toSeries(),
                ),
            ).toSeries(),
            wires = listOf(first, second, LcncWire("in", "value", "k", "x")).toSeries(),
        )
        val a = LcncWire("nr", "y", "r", "p")
        val b = LcncWire("t", "value", "r", "p")
        for (p in listOf(program(a, b), program(b, a))) {
            val v = LcncTypeCheck.check(p)
            assertEquals(1, v.size, "text reaches list.format.x either way: $v")
            assertEquals("kind-mismatch", v[0].rule)
        }
    }
}
