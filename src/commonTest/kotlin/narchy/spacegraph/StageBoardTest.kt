package narchy.spacegraph

import borg.trikeshed.lcnc.Expectation
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.Challenge
import borg.trikeshed.narsese.IngestStages
import narchy.spacegraph.graphics.spi.DrawItem
import narchy.spacegraph.graphics.spi.SvgGraphicsProvider
import kotlin.test.*

/** Rows written by hand: one claim every stage finds, one the words stage loses for the word "rent". */
class StageBoardTest {
    private val stages = listOf(IngestStages.TOKEN, IngestStages.STATEMENT)
    private fun row(claim: String, blame: String?, words: List<String>, lacking: List<String>) = mapOf("author" to "person", "claim" to claim, "blame" to blame,
        "variants" to mapOf("parser: b" to (blame ?: Challenge.FOUND)),
        "stages" to linkedMapOf(IngestStages.TOKEN to mapOf("pass" to lacking.isEmpty(), "wanted" to words, "lacking" to lacking),
            IngestStages.STATEMENT to mapOf("pass" to (blame == null), "claim" to listOf("must|bailee|return|goods|"), "lacking" to emptyList<String>(), "opposed" to emptyList<String>())))
    private val returns = "The bailee must return the goods."
    private val rent = "The bailee must pay rent."
    private val output = mapOf("board" to listOf(row(returns, null, listOf("bailee", "return", "goods"), emptyList()), row(rent, IngestStages.TOKEN, listOf("bailee", "pay", "rent"), listOf("rent"))))
    private val challenges = listOf(Challenge(Expectation.PERSON, returns, true, .9), Challenge(Expectation.PERSON, rent, true, .6))
    private fun drop() = StageBoard.Drop(output, challenges, "The bailee must return the goods. The bailee holds them.")
    private val viewport = Viewport(1500, 900)

    @Test fun aBallRestsInThePocketOfTheStageThatLosesItAndNeverBelowIt() {
        val drop = drop()
        assertEquals(stages, drop.stages, "the stages are the row's own, in its order")
        val (found, caught) = drop.balls
        assertEquals(drop.rest(caught), drop.at(caught, drop.end + 5))
        assertTrue(drop.rest(caught).y < drop.gateY(0) + 30 && drop.rest(caught).y > drop.gateY(0), "in the words pocket")
        assertTrue(drop.rest(found).y > drop.gateY(1) + 40, "the found ball is in the tray, below the last pocket")
        val touch = drop.contacts.first { it.ball == caught.index }.at
        var t = touch
        while (t < drop.end + 2) { assertTrue(drop.at(caught, t).y <= drop.rest(caught).y + 1e-9, "at $t the caught ball is below its pocket"); t += .05 }
    }

    @Test fun theGapIsTheThingThePassageLacksAndTheContactSaysSo() {
        val drop = drop()
        val caught = drop.balls[1]
        assertEquals(listOf("bailee" to true, "pay" to true, "rent" to false), drop.pins(caught, 0).map { it.word to it.held })
        val contacts = drop.contacts.filter { it.ball == 1 }
        assertEquals(listOf(0, -1), contacts.map { it.stage }, "it meets the words stage and no other")
        assertFalse(contacts[0].passed)
        assertEquals("The passage never uses the word “rent”.", contacts[0].sentence)
        assertEquals(listOf(0, 1, -1), drop.contacts.filter { it.ball == 0 }.map { it.stage })
        assertEquals("The passage has every word of the claim: bailee, return and goods.", drop.contacts[0].sentence)
    }

    @Test fun aPersonsOutcomeIsTheTruthAndThePayoutIsItsConfidence() {
        val drop = drop()
        assertEquals(.9, drop.balls[0].paid); assertEquals(-.6, drop.balls[1].paid)
        val tally = drop.tallies(drop.end).single()
        assertEquals(Expectation.PERSON, tally.origin); assertEquals(1, tally.agreed); assertEquals(1, tally.lost)
        assertEquals(.3, tally.net, 1e-9)
        assertEquals(emptyList(), drop.tallies(drop.balls[0].end - .1), "a ball in play is not yet scored")
        assertEquals("The ball is caught at words. A person said the passage states this, and a person's outcome is the truth: the machine is wrong at words, and loses 0.60.", drop.result(drop.balls[1]))
        val texts = StageBoard.frame(drop, viewport, drop.end + 1).items.view.filterIsInstance<DrawItem.Text>().map { it.text }
        assertTrue("The machine lost 1 of 2 to people: 1 at words." in texts.joinToString(" "), "the headline names the stage the machine fails at: $texts")
    }

    @Test fun theSameRunDrawsTheSameFramesAndNoWordIsSmallOrAnInternalKey() {
        val a = drop(); val b = drop()
        var t = 0.0
        while (t < a.end + 1) {
            val frame = StageBoard.frame(a, viewport, t, true)
            assertEquals(SvgGraphicsProvider.encode(frame).a, SvgGraphicsProvider.encode(StageBoard.frame(b, viewport, t, true)).a)
            for (text in frame.items.view.filterIsInstance<DrawItem.Text>()) {
                assertTrue(text.size >= StageBoard.SMALL, "'${text.text}' is ${text.size} px at $t")
                assertTrue('|' !in text.text && '…' !in text.text && "nsubj" !in text.text, "'${text.text}' at $t")
            }
            t += .7
        }
        assertTrue(StageBoard.frame(null, viewport, 0.0).items.view.filterIsInstance<DrawItem.Text>().any { it.text == "The machine is empty." })
    }

    @Test fun theControlsStandWhereTheFrameDrawsThem() {
        val places = StageBoard.controls(viewport).associate { it.a to it.b }
        assertEquals(setOf("back", "play", "next", "again", "skip", "1", "0.5", "0.1", "new", "strip"), places.keys)
        assertTrue(places.values.all { it.x >= 844 && it.right <= 1480.0 + 1e-9 && it.bottom <= 900 })
    }
}
