package borg.trikeshed.memory

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.ContentId
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.narsese.BeliefIntake
import borg.trikeshed.narsese.EvidenceCoord
import borg.trikeshed.narsese.Nal
import borg.trikeshed.narsese.RelationKind
import borg.trikeshed.narsese.SemanticSignal
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Phase-5 gate: byte-determinism, caps, contradiction exclusion, user-edit round-trip. */
class BeliefRenderTest {

    private fun signal(angular: Long, positive: Long = 5 * Nal.UNIT, relation: RelationKind = RelationKind.MATCH) =
        SemanticSignal(angular, EvidenceCoord(positive, 0), relation, ContentId.of("s$angular".encodeToByteArray()).value)

    private suspend fun BeliefBagElement.settle() {
        var quiet = 0; var spins = 0
        while (spins++ < 400 && quiet < 3) { delay(10); if (intake.isEmpty) quiet++ else quiet = 0 }
        delay(25)
    }

    @Test
    fun renderIsByteDeterministicAndCapped() = runBlocking {
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        for (i in 1..30L) bag.intake.send(BeliefIntake.Mint(signal(i, positive = i * Nal.UNIT), BudgetCoord(0.7f, 0.4f, 0.5f)))
        bag.settle()
        val gloss: Gloss = { s -> "belief about subject ${s.angular} with some explanatory prose attached to it" }
        val r1 = BeliefRender.render(bag.recallTop(30), gloss, cap = 400)
        val r2 = BeliefRender.render(bag.recallTop(30), gloss, cap = 400)
        assertEquals(r1.cid, r2.cid, "same bag must render byte-identical")
        assertTrue(r1.text.length <= 400, "cap must hold, got ${r1.text.length}")
        assertTrue(BeliefRender.entriesOf(r1.text).isNotEmpty())
        bag.drain()
    }

    @Test
    fun contradictionsAreExcludedFromRender() = runBlocking {
        val bag = BeliefBagElement(capacity = 16)
        bag.open()
        bag.intake.send(BeliefIntake.Mint(signal(1, positive = 50 * Nal.UNIT), BudgetCoord(0.9f, 0.5f, 0.5f)))
        bag.intake.send(
            BeliefIntake.Mint(signal(2, positive = 50 * Nal.UNIT, relation = RelationKind.CONTRADICTION), BudgetCoord(0.99f, 0.5f, 0.5f)),
        )
        bag.settle()
        val r = BeliefRender.render(bag.recallTop(10), { s -> "entry ${s.angular}" })
        assertTrue("entry 1" in r.text)
        assertTrue("entry 2" !in r.text, "CONTRADICTION beliefs must not reach the prompt")
        bag.drain()
    }

    @Test
    fun pinsSurviveCapPressure() = runBlocking {
        val bag = BeliefBagElement(capacity = 32)
        bag.open()
        for (i in 1..10L) bag.intake.send(BeliefIntake.Mint(signal(i, positive = 100 * Nal.UNIT), BudgetCoord(0.9f, 0.4f, 0.5f)))
        bag.intake.send(BeliefIntake.Mint(signal(99, positive = Nal.UNIT), BudgetCoord(0.2f, 1.0f, 0.5f)))
        bag.settle()
        val r = BeliefRender.render(
            bag.recallTop(20),
            { s -> if (s.angular == 99L) "!pinned truth" else "filler entry ${s.angular} padded with prose to consume the cap quickly enough" },
            cap = 200,
        )
        assertTrue("!pinned truth" in r.text, "durability-1 pin must survive cap pressure")
        bag.drain()
    }

    @Test
    fun userEditRoundTrip() = runBlocking {
        val dir = File.createTempFile("hermes-mem", "").let { f -> f.delete(); f.apply { mkdirs() } }
        val bag = BeliefBagElement(capacity = 64)
        bag.open()
        val files = HermesMemoryFiles(bag, dir, ContentId.of("session".encodeToByteArray()))
        // seed one machine belief with a gloss, render
        val a = HermesMemoryFiles.entryAngular("machine learned this")
        files.gloss(a, "machine learned this")
        bag.intake.send(
            BeliefIntake.Mint(
                SemanticSignal(a, EvidenceCoord(5 * Nal.UNIT, 0), RelationKind.MATCH, ContentId.of("m".encodeToByteArray()).value),
                BudgetCoord(0.8f, 0.4f, 0.5f),
            ),
        )
        bag.settle()
        val r1 = files.renderTo()
        assertTrue("machine learned this" in r1.text)
        // user appends an entry by hand
        val memFile = File(dir, "MEMORY.md")
        memFile.writeText(r1.text + BeliefRender.DELIM + "!jim says: never do X")
        val deltas = files.ingestUserEdits()
        bag.settle()
        assertTrue(deltas >= 1, "user addition must mint")
        val userAngular = HermesMemoryFiles.entryAngular("!jim says: never do X")
        val minted = bag.snapshot().entries.first { it.key.a == userAngular }.value
        assertEquals(Nal.USER_UNIT, minted.evidence.positive, "user evidence is 1000-scale")
        assertEquals(1.0f, bag.budgetOf(userAngular)!!.df, 1e-3f, "!-prefix pins durability")
        // re-render reflects the edit
        val r2 = files.renderTo()
        assertTrue("!jim says: never do X" in r2.text)
        bag.drain()
    }
}
