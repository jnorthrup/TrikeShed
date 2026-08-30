package borg.trikeshed.lcnc

import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Patch type-system compliance, with the daemon as the authority.
 *
 * The canvas refuses a kind-mismatched drag, but a stored panel, an import, a
 * Kotlin-authored preset or a raw POST to /api/lcnc/run never passes through the
 * canvas. This gate holds the rule where it decides: every offered preset must
 * type-check, and a mismatched wire must be NAMED, not silently executed.
 */
class LcncTypeCheckTest {

    @Test
    fun everyOfferedPresetTypeChecks() {
        for ((name, json) in LcncPresets.all()) {
            val violations = LcncTypeCheck.check(LcncProgramConfix.fromJson(name, json))
            assertTrue(violations.isEmpty(),
                "$name does not type-check:\n" + violations.joinToString("\n") { "  " + it.render() })
        }
    }

    @Test
    fun aKindMismatchIsNamedNotExecuted() {
        // graal.vitals emits json; mux.chat's prompt wants text.
        val program = LcncProgram(
            name = "bad",
            nodes = listOf(
                LcncNode("a", "graal.vitals"),
                LcncNode("b", "mux.chat"),
            ).toSeries(),
            wires = listOf(LcncWire("a", "json", "b", "prompt?")).toSeries(),
        )
        val violations = LcncTypeCheck.check(program)
        assertEquals(1, violations.size, "one wire, one violation: $violations")
        val v = violations[0]
        assertEquals("kind-mismatch", v.rule)
        assertTrue(v.detail.contains("json") && v.detail.contains("text"), "the detail names both kinds: ${v.detail}")
    }

    @Test
    fun anUndeclaredPortIsCaughtBeforeTheRun() {
        val program = LcncProgram(
            name = "bad-port",
            nodes = listOf(LcncNode("a", "timer"), LcncNode("b", "display")).toSeries(),
            wires = listOf(LcncWire("a", "nope", "b", "x")).toSeries(),
        )
        val violations = LcncTypeCheck.check(program)
        assertEquals(1, violations.size)
        assertEquals("undeclared-port", violations[0].rule)
    }

    @Test
    fun aRingParameterIsGenericUntilItDeclaresAKind() {
        // The shipped council preset wires text -> ring param; forcing "json" on
        // ring ports made that undrawable. Generic accepts it; a DECLARED kind
        // then enforces exactly like a leaf port.
        fun ring(paramKind: String?) = LcncProgram(
            name = "ring",
            nodes = listOf(
                LcncNode("src", "text.value"),
                LcncNode(
                    "r", LcncContracts.SCOPE,
                    children = listOf(
                        LcncNode("in", LcncContracts.SCOPE_IN,
                            params = buildMap {
                                put("name", "brief")
                                paramKind?.let { put("kind", it) }
                            }),
                    ).toSeries(),
                ),
            ).toSeries(),
            wires = listOf(LcncWire("src", "value", "r", "brief")).toSeries(),
        )
        assertTrue(LcncTypeCheck.check(ring(null)).isEmpty(), "an undeclared ring parameter is generic")
        assertTrue(LcncTypeCheck.check(ring("text")).isEmpty(), "a declared matching kind passes")
        val mismatched = LcncTypeCheck.check(ring("id"))
        assertEquals(1, mismatched.size, "a DECLARED ring kind is enforced like any port: $mismatched")
        assertEquals("kind-mismatch", mismatched[0].rule)
    }

    @Test
    fun aMalformedCableIsLoudNotSilentlyDropped() {
        // Found by QA'ing the QA: a wire in the wrong shape used to vanish in
        // fromJson, so a document executed with cables the author wrote and the
        // engine never saw — and the type checker had nothing to check.
        val bad = """{"name":"x","seq":1,
            "nodes":[{"id":"a","type":"timer","params":{},"x":0,"y":0}],
            "wires":[{"fromNode":"a","fromPort":"tick","toNode":"b","toPort":"x"}]}"""
        var threw = false
        try {
            LcncProgramConfix.fromJson("x", bad)
        } catch (e: IllegalArgumentException) {
            threw = true
            assertTrue(e.message!!.contains("wire[0]"), "the message names the cable: ${e.message}")
        }
        assertTrue(threw, "a malformed cable must not be silently dropped")
    }

    @Test
    fun dataFlowsLateralOrInwardOnly() {
        // An inner node yielding straight outward — the runner throws
        // LcncScopeViolation at run time; the checker names it before.
        val program = LcncProgram(
            name = "outward",
            nodes = listOf(
                LcncNode(
                    "r", LcncContracts.SCOPE,
                    children = listOf(LcncNode("inner", "timer")).toSeries(),
                ),
                LcncNode("sink", "display"),
            ).toSeries(),
            wires = listOf(LcncWire("inner", "tick", "sink", "x")).toSeries(),
        )
        val violations = LcncTypeCheck.check(program)
        assertTrue(violations.any { it.rule == "ring-boundary" || it.rule == "kind-mismatch" },
            "an outward wire is refused: $violations")
    }
}
