package borg.trikeshed.memory

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.narsese.BeliefIntake
import borg.trikeshed.narsese.EvidenceCoord
import borg.trikeshed.narsese.Nal
import borg.trikeshed.narsese.RelationKind
import borg.trikeshed.narsese.SemanticSignal
import borg.trikeshed.ontology.zipper.PlaneAdapters
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Phase-6 gate: fixture tree ingest; each backchain surfaces its skill; determinism. */
class SkillSelectTest {

    private fun fixtureTree(): File {
        val root = File.createTempFile("skills", "").let { f -> f.delete(); f.apply { mkdirs() } }
        fun skill(category: String, name: String, description: String, tags: List<String> = emptyList()) {
            val dir = File(root, "$category/$name").apply { mkdirs() }
            File(dir, "SKILL.md").writeText(
                """
                ---
                name: $name
                description: $description
                metadata:
                  hermes:
                    tags: [${tags.joinToString(", ")}]
                ---
                # $name
                Body of $name.
                """.trimIndent(),
            )
        }
        skill("coding", "kotlin-gradle", "build and test kotlin multiplatform projects with gradle", listOf("kotlin", "gradle"))
        skill("coding", "python-pytest", "run and debug python test suites with pytest", listOf("python", "pytest"))
        skill("coding", "rust-cargo", "compile rust crates with cargo", listOf("rust"))
        skill("writing", "tech-prose", "write clear technical documentation", listOf("prose"))
        skill("ops", "daemon-watch", "operate long-running daemons and watch their logs", listOf("daemon"))
        return root
    }

    @Test
    fun registryIngestsTreeAndBuildsLattice() {
        val reg = SkillRegistry(JvmFileOperations())
        val cards = reg.ingest(fixtureTree().absolutePath)
        assertEquals(5, cards.size)
        val kotlinCard = cards.view.first { it.name == "kotlin-gradle" }
        assertEquals("coding", kotlinCard.category)
        assertTrue(kotlinCard.tags.contains("gradle"))
        // skill IS-A category
        assertTrue(reg.lattice().isA(reg.token("kotlin-gradle"), reg.token("coding")))
    }

    @Test
    fun bagChainSurfacesTheBelievedSkill() = runBlocking {
        val reg = SkillRegistry(JvmFileOperations())
        val cards = reg.ingest(fixtureTree().absolutePath)
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        // strong belief: kotlin-gradle helps in kotlin-build contexts (the card's own coordinate)
        val card = cards.view.first { it.name == "kotlin-gradle" }
        bag.intake.send(
            BeliefIntake.Mint(
                SemanticSignal(card.angular, EvidenceCoord(20 * Nal.UNIT, 0), RelationKind.CAUSALITY,
                    ContentId.of(card.name.encodeToByteArray()).value),
                BudgetCoord(0.9f, 0.5f, 0.7f),
            ),
        )
        var quiet = 0; while (quiet < 3) { delay(10); if (bag.intake.isEmpty) quiet++ }
        delay(25)
        val goal = GoalCoord("build and test kotlin multiplatform projects with gradle tooling", "coding")
        val picks = selectSkills(goal, reg, cards, PlaneAdapters(bag = bag, lattice = reg.lattice()))
        assertTrue(picks.size > 0, "backchain must surface something")
        assertEquals("kotlin-gradle", picks[0].card.name, "believed skill must rank first")
        assertTrue(picks[0].trail.size >= 1, "pick must carry its breadcrumb trail")
        bag.drain()
    }

    @Test
    fun taxonomyChainWorksWithoutAnyBeliefs() {
        val reg = SkillRegistry(JvmFileOperations())
        val cards = reg.ingest(fixtureTree().absolutePath)
        val goal = GoalCoord("run and debug python test suites with pytest runners", "coding")
        val picks = selectSkills(goal, reg, cards, PlaneAdapters())
        assertTrue(picks.size > 0, "taxonomy chain alone must still select")
        assertEquals("python-pytest", picks[0].card.name)
    }

    @Test
    fun selectionIsDeterministicAndBounded() = runBlocking {
        val reg = SkillRegistry(JvmFileOperations())
        val cards = reg.ingest(fixtureTree().absolutePath)
        val goal = GoalCoord("compile rust crates with the cargo toolchain", "coding")
        val a = selectSkills(goal, reg, cards, PlaneAdapters(), k = 3)
        val b = selectSkills(goal, reg, cards, PlaneAdapters(), k = 3)
        assertEquals(a.size, b.size)
        for (i in 0 until a.size) assertEquals(a[i].card.name, b[i].card.name)
        assertTrue(a.size <= 3)
    }

    @Test
    fun renderBlockIsBudgeted() {
        val reg = SkillRegistry(JvmFileOperations())
        val cards = reg.ingest(fixtureTree().absolutePath)
        val picks = selectSkills(GoalCoord("write clear technical documentation prose", "writing"), reg, cards, PlaneAdapters(), k = 3)
        val block = renderSkillBlock(picks, focusBody = "x".repeat(20_000), bodyBudget = 8000)
        assertTrue(block.length < 9500, "body budget must bound the block, got ${block.length}")
        assertTrue("## Skills" in block)
    }
}
