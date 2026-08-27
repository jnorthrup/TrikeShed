package borg.trikeshed.lcnc

import borg.trikeshed.lib.size
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.toSeries
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plan step 3 gates: `runAll` recurses into `LcncNode.subprogram` (was a flat
 * sweep), and scope entry pushes a frame — the navigator's frame chain IS the
 * cid chain (scope = obstack frame = cache prefix).
 */
class LcncSubprogramRecursionTest {

    private val registry = mapOf(
        "source" to LcncNodeRunner { n, _ -> mapOf("out" to "src-${n.id}") },
        "display" to LcncNodeRunner { _, inputs -> inputs },
    )

    private fun program(vararg nodes: LcncNode, wires: List<LcncWire> = emptyList()) =
        LcncProgram("prog", nodes.toList().toSeries(), wires.toSeries())

    @Test
    fun subprogramNodeRunsItsInnerProgram() = runBlocking {
        val inner = program(LcncNode("inner-1", "source"))
        val runner = LcncRunner(registry).apply {
            subprogramLoader = { name -> if (name == "panels/inner") inner else null }
        }
        val outer = program(LcncNode("scope", "scope-box", subprogram = "panels/inner"))

        val out = runner.runAll(outer)
        val scopeOut = out["scope"] ?: throw AssertionError("subprogram node never ran")
        assertEquals("src-inner-1", scopeOut["inner-1"]?.let { (it as Map<*, *>)["out"] },
            "node output IS the inner program's outputs map")
    }

    @Test
    fun subprogramRecursesThroughNesting() = runBlocking {
        val innermost = program(LcncNode("leaf", "source"))
        val middle = program(LcncNode("mid-scope", "scope-box", subprogram = "panels/innermost"))
        val runner = LcncRunner(registry).apply {
            subprogramLoader = { name ->
                when (name) {
                    "panels/innermost" -> innermost
                    "panels/middle" -> middle
                    else -> null
                }
            }
        }
        val outer = program(LcncNode("top", "scope-box", subprogram = "panels/middle"))

        val out = runner.runAll(outer)
        val top = out["top"] ?: throw AssertionError("outer scope never ran")
        // top → middle-scope outputs → innermost leaf's map
        val midOut = top["mid-scope"] as? Map<*, *> ?: throw AssertionError("middle scope absent: $top")
        val leafOut = midOut["leaf"] as? Map<*, *> ?: throw AssertionError("innermost leaf absent: $midOut")
        assertEquals("src-leaf", leafOut["out"], "three-deep nesting resolves")
    }

    @Test
    fun noLoaderKeepsFlatSweepBehaviour() = runBlocking {
        val runner = LcncRunner(registry) // subprogramLoader unset
        val outer = program(LcncNode("scope", "scope-box", subprogram = "panels/unloaded"))
        // Without a loader the subprogram is invisible: the node is judged by
        // its type alone — here an unregistered type, which the flat sweep
        // rejects loudly, exactly as before step 3.
        try {
            runner.runAll(outer)
            throw AssertionError("unregistered type must be rejected as before")
        } catch (e: LcncUnknownNodeType) {
            assertTrue("scope-box" in e.message!!, "flat-sweep rejection names the node type: ${e.message}")
        }
    }

    @Test
    fun noLoaderWithRegisteredTypeRunsAsPlainLeaf() = runBlocking {
        val runner = LcncRunner(registry).apply {
            // register the carrier type so the node CAN run as a leaf
            registry as Map<String, LcncNodeRunner>
        }
        val leafRunner = LcncRunner(registry + ("scope-box" to LcncNodeRunner { _, inputs -> inputs }))
        val outer = program(LcncNode("scope", "scope-box", subprogram = "panels/unloaded"))
        val out = leafRunner.runAll(outer)
        assertTrue("scope" in out, "registered type + no loader → plain leaf run, subprogram ignored")
    }

    @Test
    fun missingSubprogramIsALoudError() = runBlocking {
        val runner = LcncRunner(registry).apply { subprogramLoader = { null } }
        val outer = program(LcncNode("scope", "scope-box", subprogram = "panels/vanished"))
        try {
            runner.runAll(outer)
            throw AssertionError("a subprogram that fails to load must not pass silently")
        } catch (e: LcncUnknownNodeType) {
            assertTrue("panels/vanished" in e.message!!, "error names the missing subprogram: ${e.message}")
        }
    }
}

/**
 * The address grammar's scope half: a navigator's frame chain is a cid chain —
 * nested scopes share the parent prefix, sibling scopes diverge, pops rewind.
 */
class ScopeFrameChainTest {

    private fun nav(): ProgramNavigator {
        val programs = mutableMapOf<String, LcncProgram>()
        return ProgramNavigator(LcncProgram.EMPTY) { name ->
            programs.getOrPut(name) { LcncProgram(name, borg.trikeshed.lib.emptySeriesOf(), borg.trikeshed.lib.emptySeriesOf()) }
        }
    }

    @Test
    fun divePushesFrameChain() = runBlocking {
        val n = nav()
        val rootCid = n.frameChain.cid
        n.diveInto("advisory")
        assertTrue(n.frameChain.cid != rootCid, "scope entry advances the chain")
        assertTrue(n.frameChain.parent == rootCid, "child frame links to the root cid")
        assertEquals(listOf("advisory"), n.breadcrumb.toList())
    }

    @Test
    fun nestedScopesSharePrefixes() = runBlocking {
        val n = nav()
        n.diveInto("legal")
        val legal = n.frameChain
        n.diveInto("researcher")
        val researcher = n.frameChain
        assertEquals(legal.cid, researcher.parent, "nested scope's parent IS the enclosing scope's cid")
    }

    @Test
    fun siblingScopesDiverge() = runBlocking {
        val n = nav()
        n.diveInto("legal")
        val legalChain = n.frameChain
        n.pop()
        n.diveInto("opposing")
        val opposing = n.frameChain
        assertTrue(legalChain.cid != opposing.cid, "siblings diverge at the fork point")
        assertEquals(legalChain.parent, opposing.parent, "but share the parent prefix")
    }

    @Test
    fun popRewindsTheChain() = runBlocking {
        val n = nav()
        val rootCid = n.frameChain.cid
        n.diveInto("a")
        val aCid = n.frameChain.cid
        n.diveInto("b")
        n.popTo(0)
        assertEquals(rootCid, n.frameChain.cid, "scope exit pops the frame — chain rewinds to root")
        n.diveInto("a")
        assertEquals(aCid, n.frameChain.cid, "re-entering the same scope reproduces the same frame cid (deterministic)")
    }
}
