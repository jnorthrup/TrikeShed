package borg.trikeshed.lcnc

import borg.trikeshed.kif.KifExpr
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The "one bank" fix: [LcncFacts.of] and [LcncFacts.parse] can be told INTO
 * the daemon's shared [KifKnowledgeBase] instead of a private one per call,
 * so `(nodeType ?t)` on the live bank stops answering `[]`, and re-resolving
 * the vocabulary into that bank (every `lateBound()` call) leaves it unchanged.
 */
class LcncFactsOneBankTest {

    private val program = LcncProgram(
        "curator-bug",
        listOf(LcncNode("n2", "beliefs.introspect"), LcncNode("n3", "beliefs.review")).toSeries(),
        listOf(LcncWire("n2", "field", "n3", "facts")).toSeries(),
    )

    private fun rows(bank: KifKnowledgeBase, pattern: String, v: String) =
        bank.query(KifExpr.parse(pattern)).map { it.getValue(v) }

    @Test
    fun tellingTheVocabularyIntoTheSameBankTwiceLeavesItUnchanged() {
        val bank = KifKnowledgeBase()
        bank.assertKif("(subclass Dog Mammal)") // the SUMO-spine style tenant already there
        val first = LcncFacts.of(LcncContracts.all(), mapOf("g" to program), into = bank)
        val size = bank.size()
        assertTrue(size > 1000, "a vocabulary this size is thousands of tuples, got $size")

        val second = LcncFacts.of(LcncContracts.all(), mapOf("g" to program), into = bank)
        assertEquals(size, bank.size(), "the exact-string dedupe makes the second telling a no-op")
        assertEquals(first.toKifFile(), second.toKifFile())
        assertEquals(first.contracts(), second.contracts())
    }

    @Test
    fun theSharedBankAnswersTheVocabularyQuestionsDirectly() {
        val bank = KifKnowledgeBase()
        val facts = LcncFacts.of(LcncContracts.all(), mapOf("g" to program), into = bank)

        val types = rows(bank, "(nodeType ?t)", "?t")
        assertTrue(types.isNotEmpty(), "(nodeType ?t) must be non-empty on the shared bank")
        assertEquals(LcncContracts.all().map { it.type }, types)
        assertEquals(facts.types(), types, "the view and the bank agree")

        assertEquals(listOf("beliefs.introspect"), rows(bank, "(node g n2 ?t)", "?t"))
        assertEquals(listOf("n3"), rows(bank, "(feeds g n2 field ?m facts)", "?m"))
        assertTrue(rows(bank, "(kind ?k)", "?k").contains("json"), "confix slots are kinds")
    }

    @Test
    fun parseIntoASharedBankReadsBackAndDedupesTheSameWay() {
        val bank = KifKnowledgeBase()
        val text = LcncFacts.of(LcncContracts.all()).toKifFile()
        val reread = LcncFacts.parse(text, into = bank)
        val size = bank.size()
        assertTrue(size > 1000)
        for (c in LcncContracts.all()) assertEquals(c, reread.contract(c.type), c.type)

        LcncFacts.parse(text, into = bank)
        assertEquals(size, bank.size())
        LcncFacts.of(LcncContracts.all(), into = bank)
        assertEquals(size, bank.size(), "the compiled table and its own .kif text land on the same strings")
    }

    /**
     * The one family here that is NOT monotone. A binding answers "which runner
     * backs this type" against a registry that grows all through a boot, so the
     * same type resolves `unbound` at one moment and `kotlin` at the next. Told
     * by `assert` the two rows would coexist — the bank retracts nothing on its
     * own — and [LcncFacts.bindingOf] reads the first row in telling order, so
     * the stalest answer would win forever. `learn(bindings)` therefore REPLACES.
     */
    @Test
    fun aBindingIsReplacedNotStackedWhenTheRegistryGrows() {
        val bank = KifKnowledgeBase()
        val contracts = LcncContracts.all().take(3)
        val types = contracts.map { it.type }
        fun tellAll(bindings: List<LcncBinding>) = LcncFacts.of(contracts, into = bank).learn(bindings)
        fun how(type: String) = bank.query(KifExpr.parse("(binding $type ?how ?by)")).singleOrNull()?.getValue("?how")

        tellAll(types.map { LcncBinding(it, LcncBindingKind.UNBOUND, "") })
        val size = bank.size()
        assertEquals(types.map { "unbound" }, types.map { how(it) })

        tellAll(types.map { LcncBinding(it, LcncBindingKind.UNBOUND, "") })
        assertEquals(size, bank.size(), "the same resolution twice is still a no-op")

        // A runner arrives for the first type — the registry grew between the two passes.
        val bound = tellAll(
            listOf(LcncBinding(types[0], LcncBindingKind.KOTLIN, "borg.x.Y\$registry\$1")) +
                types.drop(1).map { LcncBinding(it, LcncBindingKind.UNBOUND, "") },
        )
        assertEquals(size, bank.size(), "one row per type, still — the superseded row is retracted, not kept")
        assertEquals(listOf("kotlin", "unbound", "unbound"), types.map { how(it) })
        assertEquals("kotlin" to "borg.x.Y\$registry\$1", bound.bindingOf(types[0]), "and the view reads the LATER answer")
    }

    @Test
    fun theDefaultIsStillAPrivateBankPerCall() {
        val bank = KifKnowledgeBase()
        LcncFacts.of(LcncContracts.all())
        assertEquals(0, bank.size())
        LcncFacts.of(LcncContracts.all(), into = bank)
        assertTrue(bank.size() > 0)
    }
}
