package borg.trikeshed.lcnc

import borg.trikeshed.collections.associative.*
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.lib.*
import kotlin.test.*

class LcncBindingOriginsTest {
    private fun path(vararg parts: Any): LcncBindingPath = parts α {
        when (it) {
            is String -> Either.left<String, Int>(it)
            is Int -> Either.right<String, Int>(it)
            else -> error("key or index required")
        }
    }

    @Test
    fun manyPresenceDistinguishesNoFeederFromAnOrdinaryEmptyResult() {
        val terms = LcncBindingTerms()
        val edge = terms.binding("edge")
        val ordinary = terms.binding("ordinary")
        val origins = terms.join(s_[
            terms.prefix(terms.guard(terms.any(s_[edge, ordinary])), path("turns")),
            terms.prefix(terms.guard(edge), path("turns", 0)),
            terms.prefix(terms.guard(ordinary), path("turns", 1)),
        ])
        val record = mapOf("value" to 1L)
        val actual = mapOf("turns" to listOf(record, record))
        val projected = terms.mask(actual, origins, setOf("ordinary")) as Map<*, *>
        val turns = projected["turns"] as List<*>
        assertSame(LcncProjection.Absent, turns[0], "Equal-valued occurrences keep their executed positions")
        assertEquals(record, turns[1])
        val exported = LcncProjection.frame(mapOf("fold" to mapOf("turns" to turns)), emptyMap())
        assertEquals(listOf(record), exported.outputs.getValue("fold")["turns"])
        val unfed = terms.mask(actual, origins, emptySet()) as Map<*, *>
        assertSame(LcncProjection.Absent, unfed["turns"], "No surviving feeder must leave the required port absent")
        assertEquals(emptyList<Any?>(), terms.mask(emptyList<Any?>(), emptySeriesOf(), emptySet()),
            "An ordinary scalar feeder can produce an ordinary empty fold result")
    }

    @Test
    fun emptyContainersAndStringKeysRetainTheirOwnBindingOrigins() {
        val terms = LcncBindingTerms()
        val edge = terms.binding("edge")
        val ordinary = terms.binding("ordinary")
        val origins = terms.join(s_[
            terms.prefix(terms.guard(edge), path("bucket", "0")),
            terms.prefix(terms.guard(ordinary), path("rows", 0)),
            terms.prefix(terms.guard(edge), path("empty")),
        ])
        val value = mapOf("bucket" to mapOf("0" to 1L), "rows" to listOf(1L), "empty" to emptyMap<String, Any?>())
        val projected = terms.mask(value, origins, setOf("ordinary")) as Map<*, *>
        assertSame(LcncProjection.Absent, (projected["bucket"] as Map<*, *>)["0"])
        assertEquals(listOf(1L), projected["rows"])
        assertSame(LcncProjection.Absent, projected["empty"], "Container provenance cannot be reconstructed from leaves")
        assertSame(LcncProjection.Absent, terms.mask(emptyMap<String, Any?>(),
            terms.select(origins, path("empty")), setOf("ordinary")))
        assertSame(LcncProjection.Absent, terms.mask(emptyList<Any?>(), terms.guard(edge), setOf("ordinary")))
        val inherited = terms.join(s_[terms.guard(edge), terms.prefix(terms.guard(ordinary), path("child"))])
        assertSame(LcncProjection.Absent, terms.mask(1L, terms.select(inherited, path("child")), setOf("ordinary")),
            "Selecting a field keeps both its container and field requirements")
    }

    @Test
    fun cborRoundTripFoldsRetiredNamesWithoutChoosingAnotherBranch() {
        val terms = LcncBindingTerms()
        val edge = terms.binding("edge")
        val ordinary = terms.binding("ordinary")
        val origins = terms.join(s_[
            terms.prefix(terms.guard(terms.any(s_[edge, ordinary])), path("fold", "turns")),
            terms.prefix(terms.guard(edge), path("fold", "turns", 0)),
            terms.prefix(terms.guard(ordinary), path("fold", "turns", 1)),
            terms.prefix(terms.guard(edge), path("chosen", "value")),
        ])
        val wire = terms.export(origins, setOf("ordinary"))
        assertEquals(listOf("ordinary"), wire["bindings"], "Strict hydration must not require the retired root name")
        val bytes = CanonicalCbor.encodeMap(wire)
        val reader = Cbor.Reader(bytes, 0, maxDepth = 32, maxItems = 65536)
        val decoded = reader.readItem().toAny()
        assertEquals(bytes.size, reader.pos)
        val hydrated = LcncBindingTerms()
        val imported = hydrated.decode(decoded, setOf("ordinary"))
        val record = mapOf("value" to 1L)
        val value = mapOf("fold" to mapOf("turns" to listOf(record, record)), "chosen" to mapOf("value" to 1L))
        hydrated.validate(value, imported)
        val projected = hydrated.mask(value, imported, hydrated.evaluate(setOf("ordinary"))) as Map<*, *>
        assertSame(LcncProjection.Absent, ((projected["fold"] as Map<*, *>)["turns"] as List<*>)[0])
        assertEquals(record, ((projected["fold"] as Map<*, *>)["turns"] as List<*>)[1])
        assertSame(LcncProjection.Absent, (projected["chosen"] as Map<*, *>)["value"],
            "The executed choice expires even though another equal-valued binding survives")
    }

    @Test
    fun longAliasesReuseTermsAndDeepExpressionsEvaluateWithoutRecursion() {
        val aliases = LcncBindingTerms()
        val edge = aliases.binding("edge")
        var origin = aliases.guard(edge)
        repeat(4096) { origin = aliases.select(origin, emptySeriesOf()) }
        assertSame(LcncProjection.Absent, aliases.mask(1L, origin, emptySet()))
        assertEquals(1, (aliases.export(origin, setOf("edge"))["terms"] as List<*>).size)

        val chain = LcncBindingTerms()
        val names = (0..2048).map { "root/$it" }.toSet()
        var presence = chain.binding("root/0")
        for (index in 1..2048) presence = chain.all(s_[presence, chain.binding("root/$index")])
        val wire = chain.export(chain.guard(presence), names)
        val hydrated = LcncBindingTerms()
        val imported = hydrated.decode(wire, names)
        assertSame(LcncProjection.Absent, hydrated.mask(1L, imported, names - "root/0"),
            "A topological term chain must not recurse through aliases or the expression depth")
    }

    @Test
    fun codecRefusesNumericTruncationForwardReferencesAndUnknownOwners() {
        fun document(terms: List<Any?> = listOf(listOf(0, 0)), origins: List<Any?> = listOf(listOf(2))): Map<String, Any?> =
            mapOf("bindings" to listOf("edge"), "terms" to terms, "origins" to origins)
        // Keep Float overflow above the boundary even when JS emits its shorter decimal form.
        val invalid = listOf(-1, 0.5, Double.NaN, Double.POSITIVE_INFINITY, Int.MAX_VALUE.toLong() + 1L,
            4_294_967_296L, 2_147_483_648.0, 4_294_967_296.0f, false, "2")
        for (number in invalid) {
            assertFailsWith<IllegalArgumentException>("term=$number") {
                LcncBindingTerms().decode(document(origins = listOf(listOf(number))), setOf("edge"))
            }
            if (number !is String) assertFailsWith<IllegalArgumentException>("index=$number") {
                LcncBindingTerms().decode(document(origins = listOf(listOf(2, number))), setOf("edge"))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            LcncBindingTerms().decode(document(terms = listOf(listOf(1, 2))), setOf("edge"))
        }
        assertFailsWith<IllegalArgumentException> { LcncBindingTerms().decode(document(), setOf("other")) }
        val checked = LcncBindingTerms()
        val guarded = checked.guard(checked.binding("edge"))
        assertFailsWith<IllegalArgumentException> { checked.validate(listOf(1L), checked.prefix(guarded, path("0"))) }
        assertFailsWith<IllegalArgumentException> { checked.validate(listOf(1L), checked.prefix(guarded, path(1))) }
        assertFailsWith<IllegalArgumentException> { checked.validate(mapOf("other" to 1L), checked.prefix(guarded, path("missing"))) }
        checked.validate(mapOf("present" to null), checked.prefix(guarded, path("present")))
        val bounded = LcncBindingTerms(12)
        val origin = bounded.guard(bounded.binding("edge"))
        assertEquals("work_limit", assertFailsWith<LcncBindingTerms.Limit> {
            bounded.prefix(origin, path("a", "b", "c", "d", "e", "f", "g", "h"))
        }.reason, "Terms and path copies share one finite budget")
    }
}
