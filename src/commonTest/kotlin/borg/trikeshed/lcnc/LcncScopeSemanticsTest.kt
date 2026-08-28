package borg.trikeshed.lcnc

import borg.trikeshed.lib.toSeries
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec gates (docs/concentric-lcnc-ccek-spec.md §7): arguments bind through
 * `scope.in`, only `scope.out` names cross back, a reference cycle throws
 * [LcncRunner.LcncScopeDepthExceeded] with the offending path, and the
 * executor's frame chain is bit-identical to [ProgramNavigator]'s for the
 * same dive path — the four coincident views of §2 agreeing on the address.
 */
class LcncScopeSemanticsTest {

    private val registry = mapOf(
        "source" to LcncNodeRunner { n, _ -> mapOf("out" to (n.params["v"] ?: "src")) },
        "upper" to LcncNodeRunner { _, inputs -> mapOf("y" to inputs["x"]?.toString()?.uppercase()) },
        "mapSource" to LcncNodeRunner { _, _ -> mapOf("out" to mapOf("text" to "from-args", "extra" to "e")) },
        "display" to LcncNodeRunner { _, inputs -> inputs },
    )

    private fun program(name: String, nodes: List<LcncNode>, wires: List<LcncWire> = emptyList()) =
        LcncProgram(name, nodes.toSeries(), wires.toSeries())

    /** The workhorse child: text → uppercase → result, with one local that must not leak. */
    private fun shout() = program(
        "shout",
        listOf(
            LcncNode("p1", LcncContracts.SCOPE_IN, params = mapOf("name" to "text")),
            LcncNode("p2", "upper"),
            LcncNode("p3", LcncContracts.SCOPE_OUT, params = mapOf("name" to "result")),
            LcncNode("p4", "source", params = mapOf("v" to "local-noise")),
        ),
        listOf(
            LcncWire("p1", "value", "p2", "x"),
            LcncWire("p2", "y", "p3", "value"),
        ),
    )

    // ── gate: arguments bind — caller input k reaches scope.in name=k consumers ──

    @Test
    fun argumentsBindThroughScopeIn() = runBlocking {
        val runner = LcncRunner(registry).apply {
            subprogramLoader = { name -> if (name == "shout") shout() else null }
        }
        val outer = program(
            "outer",
            listOf(
                LcncNode("n1", "source", params = mapOf("v" to "hi")),
                LcncNode("n2", LcncContracts.SCOPE, subprogram = "shout"),
                LcncNode("n3", "display"),
            ),
            listOf(
                LcncWire("n1", "out", "n2", "text"),
                LcncWire("n2", "result", "n3", "x"),
            ),
        )
        val out = runner.runAll(outer)
        assertEquals("HI", out["n3"]?.get("x"), "the caller's input reached the scope.in consumer and returned")
    }

    // ── gate: returns gather — ONLY scope.out names cross; locals do not ──

    @Test
    fun returnsGatherOnlyScopeOutNames() = runBlocking {
        val runner = LcncRunner(registry).apply {
            subprogramLoader = { name -> if (name == "shout") shout() else null }
        }
        val outer = program(
            "outer",
            listOf(
                LcncNode("n1", "source", params = mapOf("v" to "hi")),
                LcncNode("n2", LcncContracts.SCOPE, subprogram = "shout"),
            ),
            listOf(LcncWire("n1", "out", "n2", "text")),
        )
        val scopeOut = runner.runAll(outer)["n2"] ?: throw AssertionError("scope never ran")
        assertEquals(setOf("result", "returns"), scopeOut.keys,
            "exactly the scope.out names plus the composed returns map cross — inner locals (p2/p4) are invisible")
        assertEquals(mapOf("result" to "HI"), scopeOut["returns"])
    }

    // ── §4: the generic args? map merges UNDER per-name wires (per-name wins) ──

    @Test
    fun argsMapMergesUnderPerNameInputs() = runBlocking {
        val child = program(
            "pair",
            listOf(
                LcncNode("p1", LcncContracts.SCOPE_IN, params = mapOf("name" to "text")),
                LcncNode("p2", LcncContracts.SCOPE_IN, params = mapOf("name" to "extra?")),
                LcncNode("r1", LcncContracts.SCOPE_OUT, params = mapOf("name" to "text")),
                LcncNode("r2", LcncContracts.SCOPE_OUT, params = mapOf("name" to "extra")),
            ),
            listOf(
                LcncWire("p1", "value", "r1", "value"),
                LcncWire("p2", "value", "r2", "value"),
            ),
        )
        val runner = LcncRunner(registry).apply {
            subprogramLoader = { name -> if (name == "pair") child else null }
        }
        val outer = program(
            "outer",
            listOf(
                LcncNode("m1", "mapSource"),
                LcncNode("n1", "source", params = mapOf("v" to "per-name")),
                LcncNode("n2", LcncContracts.SCOPE, subprogram = "pair"),
            ),
            listOf(
                LcncWire("m1", "out", "n2", "args?"),
                LcncWire("n1", "out", "n2", "text"),
            ),
        )
        val returns = runner.runAll(outer)["n2"]?.get("returns") as? Map<*, *>
            ?: throw AssertionError("scope never returned")
        assertEquals("per-name", returns["text"], "the per-name wire wins over the args? map entry")
        assertEquals("e", returns["extra"], "args? entries with no per-name wire still bind")
    }

    // ── §3.3 default / §3 required: a default feeds an omitted parameter;
    //    an unfed required parameter skips the call silently ──

    @Test
    fun defaultsFeedOmittedParametersAndRequiredGatesTheCall() = runBlocking {
        val defaulted = program(
            "defaulted",
            listOf(
                LcncNode("p1", LcncContracts.SCOPE_IN, params = mapOf("name" to "text", "default" to "hello")),
                LcncNode("r1", LcncContracts.SCOPE_OUT, params = mapOf("name" to "result")),
            ),
            listOf(LcncWire("p1", "value", "r1", "value")),
        )
        val strict = program(
            "strict",
            listOf(
                LcncNode("p1", LcncContracts.SCOPE_IN, params = mapOf("name" to "must")),
                LcncNode("r1", LcncContracts.SCOPE_OUT, params = mapOf("name" to "result")),
            ),
            listOf(LcncWire("p1", "value", "r1", "value")),
        )
        val runner = LcncRunner(registry).apply {
            subprogramLoader = { name ->
                when (name) {
                    "defaulted" -> defaulted
                    "strict" -> strict
                    else -> null
                }
            }
        }
        val outer = program(
            "outer",
            listOf(
                LcncNode("d", LcncContracts.SCOPE, subprogram = "defaulted"),
                LcncNode("s", LcncContracts.SCOPE, subprogram = "strict"),
            ),
        )
        val out = runner.runAll(outer)
        assertEquals("hello", out["d"]?.get("result"), "the scope.in default fed the omitted parameter")
        assertTrue("s" !in out, "an unfed required scope.in skips the call silently — the general readiness rule")
    }

    // ── gate: depth — a self-referencing scope throws, listing the path ──

    @Test
    fun selfReferencingScopeThrowsDepthExceededWithThePath() = runBlocking {
        val loop = program("loop", listOf(LcncNode("n1", LcncContracts.SCOPE, subprogram = "loop")))
        val runner = LcncRunner(registry).apply {
            maxScopeDepth = 4
            subprogramLoader = { name -> if (name == "loop") loop else null }
        }
        try {
            runner.runAll(loop)
            throw AssertionError("a reference cycle must surface as data, not run forever")
        } catch (e: LcncRunner.LcncScopeDepthExceeded) {
            assertEquals(5, e.path.size, "the path carries every entered scope plus the refused one")
            assertTrue(e.path.all { it == "loop" }, "the path names the cycle: ${e.path}")
        }
    }

    // ── gate: chain determinism — executor frame chain ≡ ProgramNavigator chain ──

    @Test
    fun executorFrameChainMatchesNavigatorBitForBit() = runBlocking {
        val leaf = program("leaf", listOf(LcncNode("l1", "source")))
        val mid = program("mid", listOf(LcncNode("m1", LcncContracts.SCOPE, subprogram = "leaf")))
        val outer = program("outer", listOf(LcncNode("o1", LcncContracts.SCOPE, subprogram = "mid")))
        val load: suspend (String) -> LcncProgram? = { name ->
            when (name) {
                "leaf" -> leaf
                "mid" -> mid
                "outer" -> outer
                else -> null
            }
        }
        val entered = ArrayList<Pair<List<String>, FrameIdChain>>()
        val runner = LcncRunner(registry).apply {
            subprogramLoader = load
            onScopeEnter = { path, chain -> entered.add(path to chain) }
        }
        runner.runAll(outer)
        assertEquals(listOf(listOf("mid"), listOf("mid", "leaf")), entered.map { it.first })

        val nav = ProgramNavigator(outer, load)
        nav.diveInto("mid")
        assertEquals(nav.frameChain.cid, entered[0].second.cid, "one dive: identical cid")
        nav.diveInto("leaf")
        assertEquals(nav.frameChain.cid, entered[1].second.cid, "two dives: identical cid — §2's four views agree")
        assertEquals(nav.frameChain.parent, entered[1].second.parent, "and the parent links agree")
    }
}
