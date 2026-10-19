package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import kotlin.math.abs

/** The immutable option order accompanies each request through Jeva's index-based predict API. */
data class JevaQuestion(val type: String, val instructions: String, val labels: Series<String>,
                        val options: Series<String>, val nullLabel: String?) {
    fun fields(): Map<String, Any?> = mapOf("decision_type" to type, "question" to instructions,
        "options" to options.toList(), "allow_null" to (nullLabel != null), "positive_index" to 1)
}

typealias JevaQuestions = Map<String, JevaQuestion>

/** Jev's finite judgments on Jeva's local Python API. Native null is the existing `none` outcome. */
object Jeva {
    const val MODEL = "LIQIIIII/Jeva-4B"
    const val MAX_QUESTIONS = 200
    const val MAX_TEXT_CHARS = 1_048_576
    const val NONE = "none"

    fun text(value: Any?, field: String): String {
        require(value is String && value.isNotBlank() && value.length <= MAX_TEXT_CHARS) { "Invalid Jeva $field" }
        return value
    }

    fun questions(questions: Map<String, Map<String, Any?>>): JevaQuestions {
        require(questions.size in 1..MAX_QUESTIONS) { "Jeva requires 1..$MAX_QUESTIONS questions" }
        return questions.mapValues { (id, q) ->
            text(id, "question name")
            val instruction = requireNotNull(q["instructions"]) { "Jeva instructions are required" }
            val instructions = text(instruction as? String ?: jsonOf(instruction), "instructions")
            // Validate and snapshot descriptions before lazy option projection or channel admission.
            val criteria = (q["criteria"] as? Map<*, *>)?.mapValues { (_, description) ->
                description?.let { text(it, "criterion") }
            }
            when (q["type"]) {
                "noul" -> {
                    require(q["criteria"] == null || criteria != null) { "Invalid Jeva noul criteria" }
                    require(criteria == null || criteria.keys.all { it == "false" || it == "true" }) { "Invalid Jeva noul criteria" }
                    val labels = s_["false", "true"]
                    val options = labels.α { label -> criteria?.get(label)?.let { "$label: $it" } ?: label }
                    JevaQuestion("noul", instructions, labels, options, null)
                }
                "choice" -> {
                    require(criteria != null && criteria.size in 2..255) { "Jeva ingest requires 2..255 choices" }
                    val labels = criteria.keys.map { text(it, "choice label") }.toSeries()
                    val candidates = labels.filter { it != NONE }
                    val options = candidates.α { label -> criteria[label]?.let { "$label: $it" } ?: label }
                    require(candidates.size > 0) { "Jeva choice requires a non-null candidate" }
                    JevaQuestion("choice", instructions, candidates, options, if (NONE in criteria) NONE else null)
                }
                else -> error("Jeva ingest maps noul and choice, not '${q["type"]}'")
            }
        }
    }

    fun body(state: Any, questions: JevaQuestions): String = jsonOf(mapOf(
        "op" to "infer", "state" to (state as? String ?: jsonOf(state)),
        "questions" to questions.mapValues { it.value.fields() },
    ))

    fun probability(value: Any?, field: String): Double = requireNotNull((value as? Number)?.toDouble()
        ?.takeIf { it.isFinite() && it in 0.0..1.0 }) { "Invalid Jeva $field" }

    /** Validate every answer before returning any evidence; null mass is never renormalized away. */
    fun answers(reply: Map<*, *>, questions: JevaQuestions): Map<String, Any?> {
        reply["error"]?.let { error("Jeva inference failed: $it") }
        val model = text(reply["model"], "model")
        val answers = requireNotNull(reply["answers"] as? Map<*, *>) { "Jeva returned no answers" }
        require(answers.keys == questions.keys) { "Jeva answers differ from requested questions" }
        val normalized = questions.mapValues { (id, question) ->
            val answer = requireNotNull(answers[id] as? Map<*, *>) { "Invalid Jeva answer '$id'" }
            require(answer["kind"] == question.type) { "Jeva answer type differs for '$id'" }
            val candidate = requireNotNull(answer["candidate_probabilities"] as? List<*>) { "Missing Jeva conditional distribution '$id'" }
            require(candidate.size == question.labels.size) { "Jeva candidate count differs for '$id'" }
            val q = candidate.map { probability(it, "candidate probability '$id'") }
            require(abs(q.sum() - 1.0) <= 1e-5) { "Jeva conditional probabilities do not sum to one for '$id'" }
            val pNull = probability(answer["null_probability"], "null probability '$id'")
            require(question.nullLabel != null || pNull == 0.0) { "Unexpected Jeva null mass for '$id'" }
            if (question.type == "noul") {
                val noul = probability(answer["noul"], "noul '$id'")
                require(abs(noul - q[1]) <= 1e-5) { "Jeva noul differs from conditional probability '$id'" }
                mapOf("noul" to noul)
            } else {
                val joint = LinkedHashMap<String, Double>()
                for (i in 0 until question.labels.size) joint[question.labels[i]] = (1.0 - pNull) * q[i]
                question.nullLabel?.let { joint[it] = pNull }
                val best = q.indices.maxBy { q[it] }
                val choice = if (question.nullLabel != null && pNull >= joint.getValue(question.labels[best])) question.nullLabel
                    else question.labels[best]
                mapOf("choice" to choice, "confidence" to joint.getValue(choice), "probabilities" to joint,
                    "null_probability" to pNull,
                    "conditional_probabilities" to (0 until question.labels.size).associate { question.labels[it] to q[it] })
            }
        }
        return mapOf("model" to model, "usage" to reply["usage"], "answers" to normalized)
    }
}
