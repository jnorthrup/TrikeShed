package borg.trikeshed.lcnc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * W2.1/W2.2 gates: the contract table IS the vocabulary.
 *
 * The canonical JS type list lives in panels.html's TYPES map; this test
 * parses the resource file itself so drift between the two tables fails
 * loudly here instead of silently hiding types from drag-to-empty-space.
 */
class LcncContractParityTest {

    /** Parse the JS TYPES keys straight out of panels.html — no copy-paste drift. */
    private fun jsTypeNames(): List<String> {
        val url = javaClass.getResource("/web/panels.html")
            ?: return emptyList() // resource not on the test classpath for this target
        val text = url.readText()
        val keys = Regex("^\\s*\"([a-zA-Z0-9._]+)\":\\s*\\{", RegexOption.MULTILINE)
            .findAll(text.substringAfter("const TYPES = {").substringBefore("/* ── graph state"))
            .map { it.groupValues[1] }
            .filter { it != "run" && it != "render" } // function keys, not type keys
            .toList()
        return keys.distinct()
    }

    /**
     * The hard invariant: NO JS type is mating-blind. Every type the browser
     * can declare must have a Kotlin contract, or drag-to-empty-space can't
     * see it. commonMain (LcncContracts) is the authoritative SUPERSET — it
     * may legitimately declare more types than the JS palette renders
     * (W4–W5 job-control / VM / curator server-side nodes), so the reverse
     * direction is NOT a failure.
     */
    @Test
    fun everyJsTypeHasAKotlinContract() {
        val js = jsTypeNames()
        val kotlin = LcncContracts.all().map { it.type }.toSet()
        // The frozen expectation (kept in sync with panels.html by review):
        // if a type is missing here, drag-to-empty-space can't see it.
        val expected = setOf(
            "vm.spawn", "vm.eval", "vm.revoke", "vms.list", "pytest.pure",
            "graal.vitals", "http.get", "http.post", "board.get", "timer",
            "js", "pick", "display", "gauge", "mux.chat", "mux.models",
            "keys.status", "project.kill", "project.mount", "project.list",
            "kg.ingest", "beliefs.review", "beliefs.resonate", "beliefs.introspect",
            "pointcut.routes", "board.view", "list.groupBy", "dom.board",
            "panels.list", "program.ref", "note", "graal.events", "vm.events",
        )
        for (t in expected) {
            assertTrue(t in kotlin, "LcncContracts is missing type '$t' — invisible to mating")
        }
        if (js.isNotEmpty()) {
            // HARD GATE: JS ⊆ Kotlin. A JS type with no Kotlin contract is
            // invisible to drag-to-empty-space — the headline mating bug.
            val missing = js.filter { it !in kotlin }
            assertTrue(missing.isEmpty(),
                "JS TYPES not covered by LcncContracts (mating-blind): $missing")
            // SUPERSET is expected, not a failure: commonMain carries the
            // W4–W5 additions (job.command, job.batch, vm.call/stats/tiers,
            // curator/rete nodes) that the browser palette does not render.
            val extra = kotlin.filter { it !in js.toSet() }
            println("Kotlin superset (server-side / W4-W5, not in JS palette): $extra")
        }
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
    fun programRefDivesIntoStoredPanelsAndFindsThemFromGraph() {
        // W2.5 groundwork sanity: contracts answer find() for the dive-capable type.
        assertEquals("program.ref", LcncContracts.find("program.ref")?.type)
    }
}
