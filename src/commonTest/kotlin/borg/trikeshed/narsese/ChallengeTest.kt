package borg.trikeshed.narsese

import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChallengeTest {
    val returns = "The bailee must return the goods."
    val delivery = "A bailment is a delivery of goods in trust."
    val restored = "The goods shall be restored by the bailee."

    /** A board written by hand: the first claim found at every stage, the second lost at token, the third at clause; one variant. */
    val output = _m["board" j _l[
        _m["author" j "person", "claim" j returns, "blame" j null, "variants" j _m["parser: b" j "token"]],
        _m["author" j "model", "claim" j delivery, "blame" j "token", "variants" j _m["parser: b" j "none"]],
        _m["author" j "model", "claim" j restored, "blame" j "clause", "variants" j _m["parser: b" j "clause"]],
    ]]

    /** The challenges as a model or a person supplies them: rows with an outcome and the confidence in it. */
    val challenges = Challenge.rows("""[
        {"author": "person", "claim": "$returns", "states": true, "confidence": 1.0},
        {"author": "model", "model": "m", "claim": "$delivery", "states": true, "confidence": 0.5},
        {"author": "model", "model": "m", "claim": "$restored", "states": false, "confidence": 0.25},
        {"author": "person", "claim": "$delivery", "states": true, "confidence": 0.75},
        {"author": "person", "claim": "$restored", "states": true, "confidence": 1.0}]""")

    @Suppress("UNCHECKED_CAST")
    val board = output["board"] as List<Map<String, Any?>>

    @Test fun theScoreWeighsTheEngineAnswerAgainstEachKnownOutcomeByItsConfidence() {
        val settings = Setting.of(output)
        assertEquals(_l["as read", "parser: b"], settings.map { it.name })
        val answers = Challenge.answers(board, challenges)
        assertEquals(_l[challenges[0], challenges[1], challenges[2]], answers.map { it.b }, "a row answers the first challenge of its author and claim")

        // As read: the person's claim is found and stated (agrees, 1.0); the model's second claim is lost but stated
        // (disagrees, 0.5); its third is lost and not stated (agrees, 0.25).
        assertEquals(_l[Score("person", 1.0, 0.0, emptyList()), Score("m", 0.25, 0.5, _l[delivery])], Challenge.score(answers, settings[0]))
        // Under the variant the person's claim is lost at token and the model's second claim is found.
        assertEquals(_l[Score("person", 0.0, 1.0, _l[returns]), Score("m", 0.75, 0.0, emptyList())], Challenge.score(answers, settings[1]))
    }

    /** A run carries its challenges: the program's text literal holds them, and the record read back gives the same ones. */
    @Test fun aRunCarriesItsChallengesInItsProgram() {
        val program = Challenge.program("A passage.", challenges.map { Expectation(it.author, it.claim) }, challenges = challenges)
        // the daemon reads the request's document back into a program, and a text literal's output is its value
        val document = (reify(Challenge.request(program)) as Map<*, *>)["document"]
        val node = LcncProgramConfix.fromJson(program.name, jsonOf(document)).nodes.view.first { it.id == Challenge.NODE }
        assertEquals(challenges, Challenge.carried(_m["outputs" j _m[Challenge.NODE j _m["value" j node.params["value"]]]]))
        assertEquals(emptyList(), Challenge.carried(_m["outputs" j _m["text" j _m["value" j "A passage."]]]), "a record of a program without the node carries none")
    }

    @Test fun aModelsOutcomeIsHeldAgainstAPersonsOnTheSameClaim() {
        // The model says the passage states the delivery (as the person does) and does not state the restoring (the person says it does).
        assertEquals(_l[Score("m", 0.5, 0.25, _l[restored])], Challenge.against(challenges))
        assertFailsWith<IllegalArgumentException> { Challenge.rows("""[{"author": "model", "claim": "$returns", "states": true, "confidence": 0.5}]""") }
    }
}
