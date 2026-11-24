package narchy.spacegraph.layout

// Upstream test/layout/scope-simulation.test.ts at 125b369: all 17 cases ported, expected values copied from upstream.
// Mapping: upstream `targets` is `targetLayout`; upstream `heat` is `heatLevel`; upstream option `spawn: 'target'`
// is ScopeSpawn.TARGET; `toBeCloseTo` (|diff| < 0.005) is an explicit tolerance.

import narchy.spacegraph.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScopeSimulationTest {
    private val close = 0.005

    private fun assertClose(expected: Double, actual: Double) =
        assertTrue(abs(expected - actual) < close, "expected $expected, got $actual")

    private val simple = listOf(
        ScopeSpec(
            id = "root",
            strategy = ScopeStrategy.GRID,
            nodes = listOf(ScopeNode(id = "a", size = 100.0 to 40.0), ScopeNode(id = "b", size = 100.0 to 40.0)),
        ),
    )

    private fun targetOf(specs: List<ScopeSpec>): ScopeLayout = layoutScopes(buildScopeTree(specs))

    private fun advanceFrames(sim: ScopeSimulation, frames: Int) {
        for (i in 0 until frames) sim.advance(1.0 / 60.0)
    }

    // 'converges to the solved target and then sleeps'
    @Test
    fun convergesToTheSolvedTargetAndThenSleeps() {
        val sim = ScopeSimulation()
        sim.setSpecs(simple)
        assertFalse(sim.settled)

        advanceFrames(sim, 180)

        val target = targetOf(simple)
        assertTrue(sim.settled)
        assertEquals(0.0, sim.energy)
        for ((id, position) in target.positions) assertEquals(position, sim.positions[id])
    }

    // 'exposes the solved layout for read-only overlays'
    @Test
    fun exposesTheSolvedLayoutForReadOnlyOverlays() {
        val sim = ScopeSimulation()
        assertNull(sim.targetLayout)
        sim.setSpecs(simple)
        assertEquals(targetOf(simple).frames.size, sim.targetLayout!!.frames.size)
        assertEquals(targetOf(simple).positions["root/a"], sim.targetLayout!!.positions["root/a"])
    }

    // 'is deterministic for the same specs and steps'
    @Test
    fun isDeterministicForTheSameSpecsAndSteps() {
        val a = ScopeSimulation()
        val b = ScopeSimulation()
        a.setSpecs(simple)
        b.setSpecs(simple)
        advanceFrames(a, 12)
        advanceFrames(b, 12)

        assertEquals(a.positions.toList(), b.positions.toList())
        assertEquals(a.steps, b.steps)
    }

    // 'holds pinned bodies at the target throughout the motion'
    @Test
    fun holdsPinnedBodiesAtTheTargetThroughoutTheMotion() {
        val target = targetOf(simple).positions["root/a"]!!
        val sim = ScopeSimulation(ScopeSimulationOptions(pinned = setOf("root/a")))

        sim.setSpecs(simple)
        sim.advance(1.0 / 60.0)
        assertEquals(target, sim.positions["root/a"])

        advanceFrames(sim, 120)
        assertEquals(target, sim.positions["root/a"])
    }

    // 'spawns new bodies at their owning scope origin'
    @Test
    fun spawnsNewBodiesAtTheirOwningScopeOrigin() {
        val sim = ScopeSimulation()
        sim.setSpecs(
            listOf(
                ScopeSpec(id = "root", nodes = listOf(ScopeNode(id = "portal", scope = "child"))),
                ScopeSpec(id = "child", parent = "root", nodes = listOf(ScopeNode(id = "x"))),
            ),
        )
        val view = sim.view()

        assertEquals(Vec3(0.0, 0.0, 0.0), view.positions["root/portal"])
        assertEquals(Vec3(0.0, 0.0, 0.0), view.frames["root/child"]!!.origin)
        assertFalse(sim.settled)
    }

    // 'does not jitter once settled'
    @Test
    fun doesNotJitterOnceSettled() {
        val sim = ScopeSimulation(ScopeSimulationOptions(instant = true))
        sim.setSpecs(simple)
        val before = sim.positions.toList()

        sim.advance(5.0)

        assertEquals(before, sim.positions.toList())
        assertTrue(sim.settled)
    }

    // 'snaps to the target with the instant option'
    @Test
    fun snapsToTheTargetWithTheInstantOption() {
        val sim = ScopeSimulation(ScopeSimulationOptions(instant = true))
        sim.setSpecs(simple)
        val target = targetOf(simple)

        assertTrue(sim.settled)
        for ((id, position) in target.positions) assertEquals(position, sim.positions[id])
    }

    // 'caps catch-up steps to avoid a spiral'
    @Test
    fun capsCatchUpStepsToAvoidASpiral() {
        val sim = ScopeSimulation(ScopeSimulationOptions(maxSteps = 2))
        sim.setSpecs(simple)

        sim.advance(10.0)

        assertEquals(2, sim.steps)
    }

    // 'wakes sleeping bodies when the target changes'
    @Test
    fun wakesSleepingBodiesWhenTheTargetChanges() {
        val sim = ScopeSimulation()
        sim.setSpecs(listOf(ScopeSpec(id = "root", strategy = ScopeStrategy.GRID, nodes = listOf(ScopeNode(id = "a", size = 100.0 to 40.0)))))
        sim.settle()
        val before = sim.positions["root/a"]!!

        sim.setSpecs(listOf(ScopeSpec(id = "root", strategy = ScopeStrategy.FORCE, nodes = listOf(ScopeNode(id = "a", size = 100.0 to 40.0)))))
        assertFalse(sim.settled)
        sim.advance(1.0 / 60.0)
        assertNotEquals(before, sim.positions["root/a"])
    }

    // 'loses energy while converging'
    @Test
    fun losesEnergyWhileConverging() {
        val sim = ScopeSimulation()
        sim.setSpecs(simple)

        sim.advance(1.0 / 60.0)
        val first = sim.energy
        advanceFrames(sim, 120)

        assertTrue(first > sim.energy)
        assertEquals(0.0, sim.energy)
    }

    // 'projects edges from animated endpoints until they settle'
    @Test
    fun projectsEdgesFromAnimatedEndpointsUntilTheySettle() {
        val specs = listOf(
            ScopeSpec(
                id = "root",
                nodes = listOf(ScopeNode(id = "a"), ScopeNode(id = "portal", scope = "child")),
                edges = listOf(ScopeEdge(id = "bridge", source = "a", target = "child/x")),
            ),
            ScopeSpec(id = "child", parent = "root", nodes = listOf(ScopeNode(id = "x"))),
        )
        val sim = ScopeSimulation()
        sim.setSpecs(specs)

        val moving = sim.view().edges.first { it.id == "bridge" }
        sim.settle()
        val resting = sim.view().edges.first { it.id == "bridge" }
        val target = targetOf(specs).edges.first { it.id == "bridge" }

        assertEquals(Vec3(0.0, 0.0, 0.0), moving.from)
        assertEquals(target.from, resting.from)
        assertEquals(target.to, resting.to)
    }

    // 'applies transient inter-body forces when enabled'
    @Test
    fun appliesTransientInterBodyForcesWhenEnabled() {
        val specs = listOf(
            ScopeSpec(
                id = "root",
                strategy = ScopeStrategy.GRID,
                nodes = listOf(ScopeNode(id = "a", size = 100.0 to 40.0), ScopeNode(id = "b", size = 100.0 to 40.0)),
            ),
        )
        val springOnly = ScopeSimulation(ScopeSimulationOptions(layout = ScopeLayoutOptions(gap = 60.0), spawn = ScopeSpawn.TARGET))
        val physical = ScopeSimulation(
            ScopeSimulationOptions(
                layout = ScopeLayoutOptions(gap = 60.0),
                spawn = ScopeSpawn.TARGET,
                forces = ScopeForceOptions(repulsion = 400000.0),
            ),
        )
        springOnly.setSpecs(specs)
        physical.setSpecs(specs)

        advanceFrames(springOnly, 4)
        advanceFrames(physical, 4)

        assertTrue(physical.heatLevel > 0.0)
        assertNotEquals(springOnly.positions["root/a"], physical.positions["root/a"])
    }

    // 'cools forces to rest so the graph still sleeps'
    @Test
    fun coolsForcesToRestSoTheGraphStillSleeps() {
        val sim = ScopeSimulation(ScopeSimulationOptions(spawn = ScopeSpawn.TARGET, forces = ScopeForceOptions(cooling = 0.8)))
        sim.setSpecs(simple)

        advanceFrames(sim, 120)

        assertEquals(0.0, sim.heatLevel)
        assertTrue(sim.settled)
        assertEquals(0.0, sim.energy)
    }

    // 'skips pairwise forces above the body cap'
    @Test
    fun skipsPairwiseForcesAboveTheBodyCap() {
        val plain = ScopeSimulation(ScopeSimulationOptions(layout = ScopeLayoutOptions(gap = 60.0)))
        val capped = ScopeSimulation(
            ScopeSimulationOptions(
                layout = ScopeLayoutOptions(gap = 60.0),
                forces = ScopeForceOptions(repulsion = 4000.0, maxBodies = 1),
            ),
        )
        plain.setSpecs(simple)
        capped.setSpecs(simple)

        for (i in 0 until 4) {
            plain.advance(1.0 / 60.0)
            capped.advance(1.0 / 60.0)
        }

        assertEquals(plain.positions.toList(), capped.positions.toList())
    }

    // 'keeps pinned bodies still under inter-body forces'
    @Test
    fun keepsPinnedBodiesStillUnderInterBodyForces() {
        val sim = ScopeSimulation(ScopeSimulationOptions(pinned = listOf("root/a"), forces = ScopeForceOptions(repulsion = 8000.0)))
        sim.setSpecs(simple)
        sim.advance(1.0 / 60.0)
        val target = sim.positions["root/a"]!!

        advanceFrames(sim, 20)

        assertEquals(target, sim.positions["root/a"])
    }

    // 'derives locals from animated frames'
    @Test
    fun derivesLocalsFromAnimatedFrames() {
        val sim = ScopeSimulation(ScopeSimulationOptions(instant = true))
        sim.setSpecs(listOf(ScopeSpec(id = "root", scale = 2.0, nodes = listOf(ScopeNode(id = "a", size = 80.0 to 40.0)))))
        val view = sim.view()
        val origin = view.frames["root"]!!.origin

        val local = view.locals["root/a"]!!
        val world = view.positions["root/a"]!!
        assertClose(origin.x + 2 * local.x, world.x)
        assertClose(origin.y + 2 * local.y, world.y)
    }

    // 'chases externally supplied layout targets and refits its bounds'
    @Test
    fun chasesExternallySuppliedLayoutTargetsAndRefitsItsBounds() {
        val sim = ScopeSimulation(ScopeSimulationOptions(instant = true))
        sim.setSpecs(simple)

        sim.setLayoutTargets(
            mapOf(
                "root/a" to Vec3(10.0, 20.0, 0.0),
                "root/b" to Vec3(300.0, 400.0, 0.0),
            ),
        )

        assertEquals(Vec3(10.0, 20.0, 0.0), sim.positions["root/a"])
        assertEquals(Vec3(300.0, 400.0, 0.0), sim.positions["root/b"])
        assertEquals(10.0, sim.view().bounds.min.x)
        assertEquals(20.0, sim.view().bounds.min.y)
        assertEquals(300.0, sim.view().bounds.max.x)
        assertEquals(400.0, sim.view().bounds.max.y)

        sim.setLayoutTargets(null)
        assertEquals(targetOf(simple).positions["root/a"], sim.positions["root/a"])
    }
}
