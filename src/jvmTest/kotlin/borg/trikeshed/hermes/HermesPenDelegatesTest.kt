package borg.trikeshed.hermes

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.subvm.Budget
import borg.trikeshed.graal.subvm.GraalBtrfsSupervisor
import borg.trikeshed.lib.size
import borg.trikeshed.memory.SkillRegistry
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.ontology.zipper.PlaneAdapters
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.vm.Teleported
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase-7 gate: the pen's verbs exercised through a REAL GraalPy guest —
 * refusals as data, double landings (blackboard fact + tool-use belief),
 * evidence clamped, scribe confined, no delete verb.
 */
class HermesPenDelegatesTest {

    private fun guest(): GraalBtrfsSupervisor = GraalBtrfsSupervisor(
        id = "pen-test-${System.nanoTime()}",
        facet = VmFacet.GRAAL_PYTHON,
        budget = Budget(statements = 0, wallMillis = 30_000, calls = 0),
    )

    private fun skillsFixture(): File {
        val root = File.createTempFile("pen-skills", "").let { f -> f.delete(); f.apply { mkdirs() } }
        val dir = File(root, "coding/kotlin-gradle").apply { mkdirs() }
        File(dir, "SKILL.md").writeText("---\nname: kotlin-gradle\ndescription: build kotlin with gradle\n---\nbody")
        return root
    }

    private suspend fun BeliefBagElement.settle() {
        var quiet = 0; var spins = 0
        while (spins++ < 400 && quiet < 3) { delay(10); if (intake.isEmpty) quiet++ else quiet = 0 }
        delay(25)
    }

    @Test
    fun refusalsAreVocabularyNotExceptions() = runBlocking {
        val blackboard = ConfixBlackboard.empty()
        val pen = HermesPen(blackboard) // no mux, no bag, no skills leased
        val g = guest()
        pen.install(g)
        val out = g.eval(
            "import json\n" +
                "r1 = json.loads(host.call('mux_converse', 'some-model', 'hello'))\n" +
                "r2 = json.loads(host.call('bag_recall', 'anything'))\n" +
                "r3 = json.loads(host.call('skill_scribe', 'delete', 'a', 'b'))\n" +
                "(r1['verdict'], r2['verdict'], r3['verdict'])",
            "pen-refusals.py",
        )
        val arr = out as Teleported.Arr
        assertEquals("no-mux", (arr.v[0] as Teleported.Str).v)
        assertEquals("no-bag", (arr.v[1] as Teleported.Str).v)
        assertEquals("no-scribe", (arr.v[2] as Teleported.Str).v)
        g.close()
    }

    @Test
    fun everyCrossingLandsTwice() = runBlocking {
        val blackboard = ConfixBlackboard.empty()
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        val pen = HermesPen(blackboard, bag = bag)
        val g = guest()
        pen.install(g)
        g.eval("host.call('bag_assert', 'context kotlin build', 'kotlin-gradle', True)", "pen-assert.py")
        bag.settle()
        // landing 1: the blackboard fact (Rete-visible)
        val landings = blackboard.keys().filter { k -> k.startsWith("hermes/python/pen/bag_assert/") }
        assertTrue(landings.isNotEmpty(), "pen crossing must land on the blackboard")
        // landing 2: the asserted belief AND the tool-use belief about the assertion itself
        assertTrue(bag.size >= 2, "expected asserted belief + tool-use belief, got ${bag.size}")
        // guest evidence clamp: every guest-minted belief carries at most ONE unit
        for ((_, s) in bag.snapshot()) {
            assertTrue(s.evidence.total <= borg.trikeshed.narsese.Nal.UNIT, "guest evidence must be clamped to unit, got ${s.evidence.total}")
        }
        bag.drain()
        g.close()
    }

    @Test
    fun crumbWalkReturnsPicksWithTrails() = runBlocking {
        val blackboard = ConfixBlackboard.empty()
        val registry = SkillRegistry(JvmFileOperations())
        val cards = registry.ingest(skillsFixture().absolutePath)
        assertEquals(1, cards.size)
        val pen = HermesPen(blackboard, registry = registry, cards = cards, planes = PlaneAdapters(lattice = registry.lattice()))
        val g = guest()
        pen.install(g)
        val out = g.eval(
            "import json\n" +
                "r = json.loads(host.call('crumb_walk', 'build kotlin with gradle tooling', 'coding', 3))\n" +
                "(r['verdict'], len(r['picks']), r['picks'][0]['name'] if r['picks'] else '')",
            "pen-walk.py",
        )
        val arr = out as Teleported.Arr
        assertEquals("ok", (arr.v[0] as Teleported.Str).v)
        assertTrue((arr.v[1] as Teleported.Num).v.toInt() >= 1)
        assertEquals("kotlin-gradle", (arr.v[2] as Teleported.Str).v)
        g.close()
    }

    @Test
    fun scribeIsConfinedAndHasNoDelete() = runBlocking {
        val blackboard = ConfixBlackboard.empty()
        val root = skillsFixture()
        val pen = HermesPen(blackboard, skillsRoot = root)
        val g = guest()
        pen.install(g)
        val out = g.eval(
            "import json\n" +
                "ok = json.loads(host.call('skill_scribe', 'create', 'coding', 'new-skill', '---\\nname: new-skill\\ndescription: d\\n---\\nbody'))\n" +
                "esc = json.loads(host.call('skill_scribe', 'create', '..', 'escape', 'x'))\n" +
                "de = json.loads(host.call('skill_scribe', 'delete', 'coding', 'new-skill'))\n" +
                "(ok['verdict'], esc['verdict'], de['verdict'])",
            "pen-scribe.py",
        )
        val arr = out as Teleported.Arr
        assertEquals("ok", (arr.v[0] as Teleported.Str).v)
        assertTrue((arr.v[1] as Teleported.Str).v in setOf("bad-name", "confined"), "escape must be refused")
        assertEquals("no-such-action", (arr.v[2] as Teleported.Str).v, "there is no delete verb")
        assertTrue(File(root, "coding/new-skill/SKILL.md").isFile)
        g.close()
    }
}
