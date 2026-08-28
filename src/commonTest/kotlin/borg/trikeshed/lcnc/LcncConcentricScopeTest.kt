package borg.trikeshed.lcnc

import borg.trikeshed.lib.toSeries
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * The concentric machine's gates (the-concentric-machine contract, lines
 * 1–5). These pin the UNLABELED semantics structurally so no future session
 * can regress them to a trained pattern: rings are blocks (not calls), inner
 * sees outer through the frame chain, only scope.out crosses back, authored
 * order is program order, and the frame is a genuine CoroutineContext
 * element any suspend runner can read.
 */
class LcncConcentricScopeTest {

    private val registry = mapOf(
        "source" to LcncNodeRunner { n, _ -> mapOf("out" to (n.params["v"] ?: "src")) },
        "upper" to LcncNodeRunner { _, inputs -> mapOf("y" to inputs["x"]?.toString()?.uppercase()) },
        "echo" to LcncNodeRunner { _, inputs -> inputs },
    )

    private fun program(name: String, nodes: List<LcncNode>, wires: List<LcncWire> = emptyList()) =
        LcncProgram(name, nodes.toSeries(), wires.toSeries())

    // ── line 1: inner sees outer — a wire crosses TWO ring boundaries inward,
    //    zero re-plumbing; yields climb out explicitly ──────────────────────

    @Test
    fun innerConsumesOuterTwoRingsDeepWithZeroPlumbing() = runBlocking {
        val p = program(
            "deep",
            listOf(
                LcncNode("src", "source", params = mapOf("v" to "warm")),
                LcncNode("r1", LcncContracts.SCOPE, children = listOf(
                    LcncNode("r2", LcncContracts.SCOPE, children = listOf(
                        LcncNode("p", LcncContracts.SCOPE_OUT, params = mapOf("name" to "got")),
                    ).toSeries()),
                    LcncNode("q", LcncContracts.SCOPE_OUT, params = mapOf("name" to "got")),
                ).toSeries()),
            ),
            listOf(
                LcncWire("src", "out", "p", "value"),   // crosses two boundaries INWARD
                LcncWire("r2", "got", "q", "value"),    // yields climb out ring by ring
            ),
        )
        val res = LcncRunner(registry).runProcedure(p)
        assertEquals("warm", (res.nodeOutputs["r1"] as Map<*, *>)["got"],
            "the outer output reached a consumer two rings deep and its yield climbed back out")
    }

    // ── line 1: scope.in resolves the chain outward; nearest ring shadows ──

    @Test
    fun scopeInResolvesOutwardAndNearestRingShadows() = runBlocking {
        val p = program(
            "shadow",
            listOf(
                LcncNode("mk", "source", params = mapOf("v" to "inner-binding")),
                // ring A: binds text explicitly (per-name wire) — its scope.in sees the envelope
                LcncNode("a", LcncContracts.SCOPE, children = listOf(
                    LcncNode("ai", LcncContracts.SCOPE_IN, params = mapOf("name" to "text")),
                    LcncNode("ao", LcncContracts.SCOPE_OUT, params = mapOf("name" to "seen")),
                ).toSeries()),
                // ring B: binds nothing — its scope.in walks to the ROOT frame's binding
                LcncNode("b", LcncContracts.SCOPE, children = listOf(
                    LcncNode("bi", LcncContracts.SCOPE_IN, params = mapOf("name" to "text")),
                    LcncNode("bo", LcncContracts.SCOPE_OUT, params = mapOf("name" to "seen")),
                ).toSeries()),
            ),
            listOf(
                LcncWire("mk", "out", "a", "text"),
                LcncWire("ai", "value", "ao", "value"),
                LcncWire("bi", "value", "bo", "value"),
            ),
        )
        val res = LcncRunner(registry).runProcedure(p, args = mapOf("text" to "root-binding"))
        assertEquals("inner-binding", (res.nodeOutputs["a"] as Map<*, *>)["seen"], "nearest ring shadows")
        assertEquals("root-binding", (res.nodeOutputs["b"] as Map<*, *>)["seen"], "unbound ring walks outward to the root")
    }

    // ── line 1: only scope.out crosses — an outward wire is a loud violation ──

    @Test
    fun outwardWireIsALoudScopeViolation() = runBlocking {
        val p = program(
            "leak",
            listOf(
                LcncNode("r", LcncContracts.SCOPE, children = listOf(
                    LcncNode("local", "source", params = mapOf("v" to "secret")),
                ).toSeries()),
                LcncNode("thief", "echo"),
            ),
            listOf(LcncWire("local", "out", "thief", "x")),
        )
        assertFailsWith<LcncScopeViolation>("an inner local wired outward must throw") {
            LcncRunner(registry).runProcedure(p)
        }
    }

    @Test
    fun cousinWireIsALoudScopeViolation() = runBlocking {
        val p = program(
            "cousins",
            listOf(
                LcncNode("r1", LcncContracts.SCOPE, children = listOf(
                    LcncNode("x1", "source"),
                ).toSeries()),
                LcncNode("r2", LcncContracts.SCOPE, children = listOf(
                    LcncNode("x2", "echo"),
                ).toSeries()),
            ),
            listOf(LcncWire("x1", "out", "x2", "x")),
        )
        assertFailsWith<LcncScopeViolation>("cousin rings share nothing — climb via scope.out, re-enter via binding") {
            LcncRunner(registry).runProcedure(p)
        }
    }

    // ── line 4: authored order is program order — use-before-def is loud ──

    @Test
    fun useBeforeDefIsLoud() = runBlocking {
        val p = program(
            "ubd",
            listOf(
                LcncNode("consumer", "echo"),
                LcncNode("late", "source"),
            ),
            listOf(LcncWire("late", "out", "consumer", "x")),
        )
        assertFailsWith<LcncUseBeforeDef>("consuming a later statement is a data error, like Kotlin") {
            LcncRunner(registry).runProcedure(p)
        }
    }

    // ── line 4: if (cond) { ring } — a falsy guard skips, yields stay absent ──

    @Test
    fun guardedRingSkipsOnFalseAndRunsOnTrue() = runBlocking {
        fun guarded(cond: String) = program(
            "iff",
            listOf(
                LcncNode("c", "source", params = mapOf("v" to cond)),
                LcncNode("r", LcncContracts.SCOPE, children = listOf(
                    LcncNode("s", "source", params = mapOf("v" to "yes")),
                    LcncNode("o", LcncContracts.SCOPE_OUT, params = mapOf("name" to "ran")),
                ).toSeries()),
            ),
            listOf(
                LcncWire("c", "out", "r", "when?"),
                LcncWire("s", "out", "o", "value"),
            ),
        )
        val runner = LcncRunner(registry)
        assertTrue("r" !in runner.runProcedure(guarded("false")).nodeOutputs,
            "false guard: the ring is skipped, its yields absent")
        assertEquals("yes", (runner.runProcedure(guarded("true")).nodeOutputs["r"] as Map<*, *>)["ran"],
            "true guard: the ring runs")
    }

    // ── line 1: inline ≡ named — a named ring is lazy containment and STILL
    //    sees the enclosing environment (the anti-vacuum gate) ──────────────

    @Test
    fun namedRingSeesTheEnclosingEnvironmentAndMatchesInline() = runBlocking {
        val bodyNodes = listOf(
            LcncNode("bi", LcncContracts.SCOPE_IN, params = mapOf("name" to "text")),
            LcncNode("bo", LcncContracts.SCOPE_OUT, params = mapOf("name" to "seen")),
        )
        val bodyWires = listOf(LcncWire("bi", "value", "bo", "value"))

        val inline = program("host-inline",
            listOf(LcncNode("r", LcncContracts.SCOPE, children = bodyNodes.toSeries())), bodyWires)
        val named = program("host-named",
            listOf(LcncNode("r", LcncContracts.SCOPE, subprogram = "body")))
        val bodyDoc = program("body", bodyNodes, bodyWires)

        val runner = LcncRunner(registry).apply { subprogramLoader = { if (it == "body") bodyDoc else null } }
        val env = mapOf("text" to "conferred")
        val ri = runner.runProcedure(inline, env)
        val rn = runner.runProcedure(named, env)
        assertEquals("conferred", (ri.nodeOutputs["r"] as Map<*, *>)["seen"], "inline ring reads the enclosing binding")
        assertEquals("conferred", (rn.nodeOutputs["r"] as Map<*, *>)["seen"],
            "a NAMED ring is lazy containment — it still sees the enclosing environment, never a call into a vacuum")
    }

    // ── the frame is a genuine context element: a runner reads it with zero
    //    plumbing — block compatibility through the machinery, not the grammar ──

    @Test
    fun runnersSeeTheFrameThroughCoroutineContext() = runBlocking {
        val reg = registry + ("ctx.probe" to LcncNodeRunner { _, _ ->
            val frame = currentCoroutineContext()[LcncScopeFrame]
            mapOf(
                "text" to frame?.binding("text"),
                "depth" to frame?.depth,
            )
        })
        val p = program(
            "probe",
            listOf(
                LcncNode("r", LcncContracts.SCOPE, children = listOf(
                    LcncNode("deep", "ctx.probe"),
                    LcncNode("o", LcncContracts.SCOPE_OUT, params = mapOf("name" to "probe")),
                ).toSeries()),
            ),
            listOf(LcncWire("deep", "text", "o", "value")),
        )
        val res = LcncRunner(reg).runProcedure(p, args = mapOf("text" to "through-the-machine"))
        assertEquals("through-the-machine", (res.nodeOutputs["r"] as Map<*, *>)["probe"],
            "a suspend runner reads the enclosing binding via currentCoroutineContext()[LcncScopeFrame]")
    }

    // ── identity: the executor's chain is deterministic and ring-named ──

    @Test
    fun frameChainIsDeterministicOverTheRingPath() = runBlocking {
        val entered = ArrayList<Pair<List<String>, FrameIdChain>>()
        val p = program(
            "chain",
            listOf(
                LcncNode("outer", LcncContracts.SCOPE, children = listOf(
                    LcncNode("innerRing", LcncContracts.SCOPE, children = listOf(
                        LcncNode("leaf", "source"),
                    ).toSeries()),
                ).toSeries()),
            ),
        )
        val runner = LcncRunner(registry).apply { onScopeEnter = { path, chain -> entered.add(path to chain) } }
        runner.runProcedure(p)
        assertEquals(listOf(listOf("outer"), listOf("outer", "innerRing")), entered.map { it.first })
        var expected = FrameIdChain.root(ROOT_SCOPE)
        expected = FrameIdChain.append(expected, "outer")
        assertEquals(expected.cid, entered[0].second.cid, "one ring: identical cid")
        expected = FrameIdChain.append(expected, "innerRing")
        assertEquals(expected.cid, entered[1].second.cid, "two rings: identical cid — the address is a pure function of the ring path")
    }

    // ── the offered preset is the machine's own demo: three rings, one document ──

    @Test
    fun presetScopeRunsAsAThreeRingDocument() = runBlocking {
        val doc = LcncPresets.all().getValue("preset-scope")
        val p = LcncProgramConfix.fromJson("preset-scope", doc)
        val res = LcncRunner(registry).runProcedure(p)
        assertEquals("hello", res.returns["result"],
            "the root scope.in default rode a wire two rings deep and the yield climbed all the way out")
    }
}
