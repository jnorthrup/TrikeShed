package borg.trikeshed.narsese

import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reify

/**
 * A claim about a passage whose outcome is known: whether the passage [states] it, the [confidence] in that outcome,
 * and its [author]: a person, whose outcome is the truth, or a model named by its [model] id, whose outcome is a
 * candidate. A person types a challenge on the page; a model supplies challenges as [rows]. None is written into code.
 *
 * The engine's answer to a challenge is the [IngestStages] board's: the stage that loses the claim, or found at every
 * stage. It agrees with the known outcome when found and stated coincide.
 */
data class Challenge(val author: String, val claim: String, val states: Boolean, val confidence: Double, val model: String = "") {
    init {
        require(author == Expectation.PERSON || author == Expectation.MODEL) { "a challenge's author is ${Expectation.PERSON} or ${Expectation.MODEL}, not '$author'" }
        require(claim.isNotBlank()) { "a challenge states a claim" }
        require(confidence > 0.0 && confidence <= 1.0) { "a challenge's confidence is above 0 and at most 1, not $confidence" }
        require((author == Expectation.MODEL) == model.isNotBlank()) { "a model's challenge names the model by its id, and a person's names none" }
    }

    /** Whose outcome it is: [Expectation.PERSON], or the model's id. */
    val origin: String get() = if (author == Expectation.MODEL) model else author

    companion object {
        /** The value [IngestStages] writes for a variant under which every stage finds the claim. */
        const val FOUND = "none"
        /** The program [program] builds. */
        const val PROGRAM = "ingest-stages-challenges"

        /**
         * Challenges as rows of `{"author": "person" | "model", "model": id, "claim": …, "states": true | false,
         * "confidence": 0 < c ≤ 1}`, a list or its JSON text. A row lacking a field is refused.
         */
        fun rows(value: Any?): List<Challenge> {
            val rows = (if (value is CharSequence) value.takeIf { it.isNotBlank() }?.let { reify(it) } else value) ?: return emptyList()
            require(rows is List<*>) { "challenges are a list of rows" }
            return rows.map { row ->
                require(row is Map<*, *>) { "a challenge is a row with an author, a claim, whether the passage states it and the confidence in that" }
                val states = row["states"] as? Boolean ?: throw IllegalArgumentException("a challenge says whether the passage states its claim: states is true or false")
                val confidence = (row["confidence"] as? Number)?.toDouble() ?: throw IllegalArgumentException("a challenge gives its confidence as a number")
                Challenge(row["author"]?.toString()?.trim().orEmpty(), row["claim"]?.toString()?.trim().orEmpty(), states, confidence,
                    row["model"]?.toString()?.trim().orEmpty())
            }
        }

        /** Each row of a stages [board] with the challenge it answers: the next unanswered challenge of the same author and claim, or none. */
        fun answers(board: List<Map<String, Any?>>, challenges: List<Challenge>): List<Join<Map<String, Any?>, Challenge?>> {
            val open = challenges.toMutableList()
            return board.map { row ->
                val at = open.indexOfFirst { it.author == row["author"] && it.claim == row["claim"] }
                row j (if (at < 0) null else open.removeAt(at))
            }
        }

        /**
         * Per origin, in one [setting]: the confidence of the challenges whose known outcome the engine's answer agrees
         * with, the confidence of those it disagrees with, and the claims it disagrees on. A row no challenge answers is not scored.
         */
        fun score(answers: List<Join<Map<String, Any?>, Challenge?>>, setting: Setting): List<Score> {
            val by = LinkedHashMap<String, MutableList<Join<Challenge, Boolean>>>()
            for ((row, challenge) in answers) if (challenge != null)
                by.getOrPut(challenge.origin) { ArrayList() }.add(challenge j (challenge.states == (setting.blame(row) == null)))
            return by.map { (origin, held) -> Score.of(origin, held) }
        }

        /** Each model's outcomes held against the persons' outcomes on the same claims, weighted by the model's confidence. */
        fun against(challenges: List<Challenge>): List<Score> {
            val by = LinkedHashMap<String, MutableList<Join<Challenge, Boolean>>>()
            for (m in challenges) if (m.author == Expectation.MODEL) for (p in challenges)
                if (p.author == Expectation.PERSON && p.claim == m.claim) by.getOrPut(m.origin) { ArrayList() }.add(m j (m.states == p.states))
            return by.map { (origin, held) -> Score.of(origin, held) }
        }

        /** The program's node that carries the [challenges][program] as rows, so that a run's record holds its known outcomes. */
        const val NODE = "challenges"

        /**
         * [text] read through the stages with [expectations]: a text literal wired into an `ingest.stages` node, and a
         * second text literal, [NODE], holding [challenges] as [rows] for the record's `outputs`.
         */
        fun program(text: String, expectations: List<Expectation>, generic: String = "", parsers: String = IngestStages.PARSERS,
                    challenges: List<Challenge> = emptyList()): LcncProgram =
            LcncProgram(PROGRAM, s_[
                LcncNode("text", "text.value", _m["value" j text], x = 40.0, y = 40.0),
                LcncNode("stages", IngestStages.TYPE, _m["expectations" j jsonOf(expectations.map { _m["author" j it.author, "claim" j it.claim] }),
                    "generic" j generic, "parsers" j parsers], x = 390.0, y = 40.0),
                LcncNode(NODE, "text.value", _m["value" j jsonOf(challenges.map { c ->
                    _m["author" j c.author, "model" j c.model, "claim" j c.claim, "states" j c.states, "confidence" j c.confidence] })], x = 40.0, y = 240.0),
            ], s_[LcncWire("text", "value", "stages", "text")])

        /** The challenges a run [record] carries in its [NODE] node's output; none when its program had no such node. */
        fun carried(record: Any?): List<Challenge> =
            rows((((record as? Map<*, *>)?.get("outputs") as? Map<*, *>)?.get(NODE) as? Map<*, *>)?.get("value"))

        /** The `/api/lcnc/run` request that runs [program] once. */
        fun request(program: LcncProgram): String = jsonOf(_m["name" j program.name, "document" j reify(LcncProgramConfix.toJson(program)),
            "inputs" j emptyMap<String, Any?>(), "timeoutMs" j 120_000])
    }
}

/** An origin's challenges held against answers: the confidence [agreed] with, the confidence [disagreed] with, and the claims disagreed on. */
data class Score(val origin: String, val agreed: Double, val disagreed: Double, val disagreements: List<String>) {
    companion object {
        /** The score of [origin]'s challenges, each with whether it was agreed with. */
        fun of(origin: String, held: List<Join<Challenge, Boolean>>) = Score(origin,
            held.filter { it.b }.sumOf { it.a.confidence }, held.filterNot { it.b }.sumOf { it.a.confidence }, held.filterNot { it.b }.map { it.a.claim })
    }
}

/**
 * A setting the stages read every claim under: the node's own, or one of the variants it re-reads under. [blame] is a
 * board row's blamed stage in this setting, null when every stage found the claim.
 */
class Setting(val name: String, val blame: (Map<String, Any?>) -> String?) {
    companion object {
        /** The settings of a stages output: its own reading, named by its parser and generic subjects, then each variant its rows name. */
        fun of(output: Map<String, Any?>): List<Setting> {
            val board = (output["board"] as? List<*>).orEmpty().map { it as Map<*, *> }
            val passage = output["passage"] as? Map<*, *>
            val generic = (passage?.get("generic") as? List<*>).orEmpty()
            val own = passage?.get("parser")?.let { "parser: $it" + (if (generic.isEmpty()) "" else "; generic: " + generic.joinToString(", ")) } ?: "as read"
            val variants = LinkedHashSet<String>().also { names -> for (row in board) (row["variants"] as? Map<*, *>)?.keys?.forEach { names.add(it.toString()) } }
            return _l[Setting(own) { it["blame"]?.toString() }] + variants.map { name ->
                Setting(name) { row ->
                    val blamed = (row["variants"] as? Map<*, *>)?.get(name) ?: error("$name: no reading of '${row["claim"]}'")
                    blamed.toString().takeIf { it != Challenge.FOUND }
                }
            }
        }
    }
}
