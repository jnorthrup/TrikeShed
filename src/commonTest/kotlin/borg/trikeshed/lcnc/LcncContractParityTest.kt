package borg.trikeshed.lcnc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Step 4 gates: the contract table IS the vocabulary — the ONLY author.
 *
 * panels.html no longer declares a TYPES table (RouteParityGate enforces
 * that); the page hydrates its palette/ports/params from
 * /api/lcnc/contracts. This test therefore asserts the properties the one
 * vocabulary must hold on its own: completeness of kinds, unique types, and
 * the run-surface parity between RUNNERS (JS execution bodies) and
 * contracts (Kotlin vocabulary).
 */
class LcncContractParityTest {

    /** JS execution-body keys straight out of panels.html's RUNNERS table. */
    private fun jsRunnerNames(): List<String> {
        val url = javaClass.getResource("/web/panels.html")
            ?: fail("panels.html missing from the test classpath — the gate must not silently pass on a missing resource")
        val text = url.readText()
        val start = text.indexOf("const RUNNERS = {")
        assertTrue(start >= 0, "panels.html must carry a RUNNERS table (execution bodies)")
        val end = text.indexOf("/* ── CONTRACTS", start).let { if (it < 0) text.indexOf("syncContracts", start) else it }
        val block = text.substring(start, if (end > start) end else text.length)
        return Regex(""""([a-zA-Z0-9._]+)":\s*\{""").findAll(block)
            .map { it.groupValues[1] }
            .toList()
            .distinct()
    }

    @Test
    fun everyJsRunnerHasAKotlinContract() {
        val js = jsRunnerNames()
        assertTrue(js.isNotEmpty(), "RUNNERS parsed empty — the extraction or the page is broken")
        val kotlin = LcncContracts.all().map { it.type }.toSet()
        val missing = js.filter { it !in kotlin }
        assertTrue(missing.isEmpty(),
            "JS RUNNERS not covered by LcncContracts (dead code — nothing can offer or wire them): $missing")
    }

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
    fun programRefDivesIntoStoredPanelsAndFindsThemFromGraph() {
        assertEquals("program.ref", LcncContracts.find("program.ref")?.type)
    }
}
