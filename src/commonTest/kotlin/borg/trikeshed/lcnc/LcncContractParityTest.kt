package borg.trikeshed.lcnc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contract table IS the vocabulary — the ONLY author. (The browser
 * editor and its JS RUNNERS table were rooted out 2026-08-27; the parity
 * that table needed is now structural — there is no second author left to
 * drift.) This test asserts the properties the one vocabulary must hold on
 * its own: completeness of kinds, unique types, honest source/sink flags.
 */
class LcncContractParityTest {

    @Test
    fun contractTypesAreUnique() {
        val all = LcncContracts.all()
        val dupes = all.groupBy { it.type }.filterValues { it.size > 1 }.keys
        assertTrue(dupes.isEmpty(), "duplicate contract types: $dupes")
    }

    @Test
    fun inputsAndOutputsDeclareKindsForMating() {
        for (c in LcncContracts.all()) {
            for (input in c.inputs) {
                val clean = input.removeSuffix("?")
                assertTrue(c.inputKinds.containsKey(clean),
                    "${c.type}.$clean: declared input has no inputKind — invisible to compatibleTypes()")
            }
            for (output in c.outputs) {
                val clean = output.removeSuffix("?")
                assertTrue(c.outputKinds.containsKey(clean),
                    "${c.type}.$clean: declared output has no outputKind — cannot mate anywhere")
            }
        }
    }

    @Test
    fun portKindsUseCanonicalVocabulary() {
        // A kind is a Confix slot, `Any` where the runner declares it, or the exact
        // CCEK type name (`List<TurnFact>`) — capitalised, as Kotlin spells it.
        // Nothing else mates with anything (LcncKinds): cables are never untyped.
        val slots = LcncKinds.CONFIX_SLOTS.toSet() + LcncKinds.CCEK_ANY
        for (c in LcncContracts.all()) {
            for ((port, kind) in c.inputKinds + c.outputKinds) {
                assertTrue(kind in slots || LcncKinds.isCcekType(kind), "${c.type}.$port uses non-canonical kind '$kind'")
            }
            for (kind in c.kindShapes.keys) {
                assertTrue(LcncKinds.isCcekType(kind), "${c.type} declares a shape for '$kind' — a shape says what a CCEK type parses from")
            }
        }
    }

    @Test
    fun sourcesAndSinksAreHonest() {
        // source = auto-firing (re-fires on its own clock); sink = chain
        // terminator; anything else is a manual/action node (vm.spawn fires
        // when the sweep reaches it) — both flags may legitimately be false.
        val declaredSources = LcncContracts.all().filter { it.isSource }.map { it.type }.toSet()
        assertEquals(setOf("timer", "graal.events", "vm.events"), declaredSources,
            "the auto-firing set must stay explicit, not accrete")
        for (c in LcncContracts.all()) {
            if (c.outputs.isEmpty()) {
                assertTrue(c.isSink, "${c.type}: no outputs must be declared a sink")
            }
        }
    }

    @Test
    fun programRefContractStaysInTheVocabulary() {
        assertEquals("program.ref", LcncContracts.find("program.ref")?.type)
    }

    @Test
    fun concentricScopeVocabularyIsDeclared() {
        // Spec §4: the call, the binding, the return — one author.
        assertEquals(LcncContracts.SCOPE, LcncContracts.find(LcncContracts.SCOPE)?.type)
        assertEquals(LcncContracts.SCOPE_IN, LcncContracts.find(LcncContracts.SCOPE_IN)?.type)
        assertEquals(LcncContracts.SCOPE_OUT, LcncContracts.find(LcncContracts.SCOPE_OUT)?.type)
    }
}
