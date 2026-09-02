package borg.trikeshed.lcnc

import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The vocabulary IS tuples ([LcncFacts]). The compiled contract table is one
 * serialization of them and a `.kif` file is another; every question is a
 * pattern query. These tests pin the round trip field for field, and answer
 * the mating and type-check questions from tuples that were AUTHORED AS
 * TEXT with no Kotlin contract in sight. Typing is exact throughout.
 */
class LcncFactsTest {

    private val curatorBug = LcncProgram(
        "curator-bug",
        listOf(LcncNode("n2", "beliefs.introspect"), LcncNode("n3", "beliefs.review")).toSeries(),
        listOf(LcncWire("n2", "field", "n3", "facts")).toSeries(),
    )

    @Test
    fun everyCompiledContractRoundTripsThroughTheTuplesFieldForField() {
        val facts = LcncFacts.of(LcncContracts.all())
        for (c in LcncContracts.all()) assertEquals(c, facts.contract(c.type), c.type)
        assertEquals(LcncContracts.all().map { it.type }, facts.types())
    }

    @Test
    fun theWholeVocabularyIsAKifFileAndReadsBackWhole() {
        val text = LcncFacts.of(LcncContracts.all()).toKifFile()
        val reread = LcncFacts.parse(text)
        for (c in LcncContracts.all()) assertEquals(c, reread.contract(c.type), c.type)
        assertEquals(LcncContracts.all().size, reread.types().size)
        assertTrue(text.lineSequence().count() > 1000, "a vocabulary this size is thousands of tuples")
    }

    @Test
    fun matingIsAQueryOverTuplesAuthoredAsText() {
        val f = LcncFacts.parse(
            """
            (nodeType a) (label a "A") (output a out) (outKind a out List<TurnFact>)
            (nodeType b) (label b "B") (input b facts) (inKind b facts List<TurnFact>)
            (nodeType c) (label c "C") (input c x?) (inKind c x json)
            (nodeType d) (label d "D") (input d x) (inKind d x Any)
            (kind json) (kind List<TurnFact>) (kind Any)
            """,
        )
        assertEquals(listOf("b" to "facts", "d" to "x"), f.compatibleInputs("List<TurnFact>"))
        assertEquals(listOf("c" to "x?", "d" to "x"), f.compatibleInputs("json"))
        assertEquals(listOf(LcncAutoWireCandidate("out", "facts", "List<TurnFact>")), f.autoWire("a", "b"))
        assertTrue(f.autoWire("a", "c").isEmpty(), "no covariance: a list of turn facts is not json")
        assertFalse(f.accepts("json", "List<TurnFact>"))
        assertTrue(f.hierarchy().isEmpty())
    }

    @Test
    fun theTypeCheckerRunsOnTuplesReadBackFromText() {
        val vocabulary = LcncFacts.parse(LcncFacts.of(LcncContracts.all()).toKifFile()).contracts()
        val v = LcncTypeCheck.check(curatorBug, vocabulary)
        assertEquals(1, v.size, "$v")
        assertEquals("kind-mismatch", v[0].rule)
    }

    @Test
    fun evidenceIsCountedByPatternOverTheCorpusWires() {
        val corpus = LcncPresets.all().mapValues { (n, j) -> LcncProgramConfix.fromJson(n, j) }
        val f = LcncFacts.of(LcncContracts.all(), corpus)
        val counts = f.feedsInto("timer", "tick")
        // preset-curator and preset-hermes-train both wire timer.tick → beliefs.introspect.
        assertTrue((counts["beliefs.introspect"] ?: 0) >= 2, "$counts")
    }

    @Test
    fun bindingsAndEffectsAreTuplesToo() {
        val effect = LcncContracts.all().first { it.isEffect }.type
        val program = LcncProgram("g", listOf(LcncNode("t", "timer"), LcncNode("e", effect)).toSeries(), emptyList<LcncWire>().toSeries())
        val f = LcncFacts.of(LcncContracts.all(), mapOf("g" to program))
            .learn(listOf(LcncBinding("timer", LcncBindingKind.KOTLIN, "borg.x.Y\$registry\$1")))
        assertTrue(f.programHasEffect("g"))
        assertEquals("kotlin" to "borg.x.Y\$registry\$1", f.bindingOf("timer"))
        assertTrue(f.isEffect(effect))
        assertFalse(f.isEffect("timer"))
    }

    @Test
    fun aProgramEntryCarriesTypedCablesAndViolationsAndReadsBack() {
        val contracts = LcncContracts.all().associateBy { it.type }
        val entry = LcncBlackboard.programEntry("curator-bug", curatorBug, contracts)
        val cables = entry["cables"] as List<*>
        assertEquals(1, cables.size)
        assertEquals("json", (cables[0] as Map<*, *>)["type"], "the cable is typed by its source, exactly")
        assertEquals(1, (entry["violations"] as List<*>).size)
        val back = LcncBlackboard.programOf(entry)!!
        assertEquals("curator-bug", back.name)
        assertEquals(1, back.wires.size)
    }

    @Test
    fun aKindWithASpaceRoundTripsThroughTheKifFile() {
        val c = LcncPortContract(
            "probe.map", "probe", listOf("x"), listOf("y"),
            inputKinds = mapOf("x" to "Map<String, Any>"), outputKinds = mapOf("y" to "Map<String, Any>"),
            params = mapOf("opt" to LcncPortContract.LcncParamSpec(opts = listOf("a b", "(c)", ""))),
        )
        val reread = LcncFacts.parse(LcncFacts.of(listOf(c)).toKifFile())
        assertEquals(c, reread.contract("probe.map"))
        assertEquals(listOf("probe.map" to "x"), reread.compatibleInputs("Map<String, Any>"))
    }
}
