package narchy.spacegraph.layout

// Upstream test/layout/scope-policy.test.ts at 125b369: all 6 cases ported, expected values copied from upstream.
// Upstream's toBe on floats (250, 30, 400, 60, 300) is exact here too: each product is representable.

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScopePolicyTest {
    private val specs = listOf(
        ScopeSpec(id = "root", nodes = listOf(ScopeNode(id = "focus"), ScopeNode(id = "portal", scope = "child"))),
        ScopeSpec(id = "child", parent = "root", nodes = listOf(ScopeNode(id = "x"), ScopeNode(id = "y"))),
    )

    // 'pins the focus and every scope that owns it'
    @Test
    fun pinsTheFocusAndEveryScopeThatOwnsIt() {
        val policy = scopePolicy(buildScopeTree(specs), ScopePolicyInput(focus = "root/child/y"))

        assertEquals(listOf("root/child/y", "root/child", "root"), policy.pinned.toList())
        assertTrue("root/child" in policy.active)
    }

    // 'gives solvers more room for overlap and poor legibility'
    @Test
    fun givesSolversMoreRoomForOverlapAndPoorLegibility() {
        val tree = buildScopeTree(specs)
        val base = ScopeLayoutOptions(gap = 100.0, nodePadding = 10.0)
        val calm = scopePolicy(tree, ScopePolicyInput(), base)
        val stressed = scopePolicy(tree, ScopePolicyInput(vision = ScopeVisionSignal(overlap = 1.0, legibility = 0.0)), base)

        assertEquals(base, calm.layout)
        assertEquals(250.0, stressed.layout.gap)
        assertEquals(30.0, stressed.layout.nodePadding)
    }

    // 'clamps vision scores to the unit range'
    @Test
    fun clampsVisionScoresToTheUnitRange() {
        val policy = scopePolicy(
            buildScopeTree(specs),
            ScopePolicyInput(vision = ScopeVisionSignal(overlap = 5.0, legibility = -2.0)),
        )

        assertEquals(400.0, policy.layout.gap)
        assertEquals(60.0, policy.layout.nodePadding)
    }

    // 'uses hierarchy and ergonomics scores to widen depth and padding'
    @Test
    fun usesHierarchyAndErgonomicsScoresToWidenDepthAndPadding() {
        val tree = buildScopeTree(specs)
        val base = ScopeLayoutOptions(gap = 100.0, nodePadding = 10.0, depthStep = 200.0)
        val calm = scopePolicy(tree, ScopePolicyInput(vision = ScopeVisionSignal(hierarchy = 1.0, ergonomics = 1.0)), base)
        val stressed = scopePolicy(tree, ScopePolicyInput(vision = ScopeVisionSignal(hierarchy = 0.0, ergonomics = 0.0)), base)

        assertEquals(base, calm.layout)
        assertEquals(300.0, stressed.layout.depthStep)
        assertEquals(30.0, stressed.layout.nodePadding)
    }

    // 'holds the focused body when a policy change retargets the graph'
    @Test
    fun holdsTheFocusedBodyWhenAPolicyChangeRetargetsTheGraph() {
        val sim = ScopeSimulation()
        sim.setSpecs(specs)
        sim.setPolicy(ScopePolicyInput(focus = "root/focus", vision = ScopeVisionSignal(overlap = 0.0)))
        sim.settle()

        sim.setPolicy(ScopePolicyInput(focus = "root/focus", vision = ScopeVisionSignal(overlap = 1.0)))
        val target = sim.positions["root/focus"]!!
        sim.advance(1.0 / 60.0)

        assertEquals(target, sim.positions["root/focus"])
    }

    // 'collapses scopes below the band'
    @Test
    fun collapsesScopesBelowTheBand() {
        val sim = ScopeSimulation()
        sim.setSpecs(
            listOf(
                ScopeSpec(id = "root", nodes = listOf(ScopeNode(id = "p", scope = "a"))),
                ScopeSpec(id = "a", parent = "root", nodes = listOf(ScopeNode(id = "q", scope = "b"))),
                ScopeSpec(id = "b", parent = "a", nodes = listOf(ScopeNode(id = "leaf"))),
            ),
        )
        sim.setPolicy(ScopePolicyInput(band = 0))

        val view = sim.view()
        assertTrue(view.frames["root/a"]!!.active)
        assertEquals(false, view.frames["root/a/b"]!!.active)
    }
}
