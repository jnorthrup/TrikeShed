package borg.trikeshed.narsese

import borg.trikeshed.htx.HtxHttpError
import borg.trikeshed.htx.htxHeaders
import borg.trikeshed.htx.TrikeHtxHttpClient
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyMap
import kotlinx.coroutines.delay
import borg.trikeshed.agent.modelmux.Airgap

/**
 * TypeSafe Jev, a System One model: one `POST /v1/systemone` carries a state and a map of typed
 * questions (noul, choice, score), answered in parallel over that state. Questions are maps in the
 * wire shape; their ids are for code and never reach the model. Rides the HTX reactor in context.
 */
object Jev {
    const val BASE = "https://api.typesafe.ai"
    const val MODEL = "jev-latest"

    /** Probability that [instructions] holds; [yes]/[no] say what each end means. */
    fun noul(instructions: Any, yes: String? = null, no: String? = null): Map<String, Any?> =
        if (yes == null && no == null) mapOf("type" to "noul", "instructions" to instructions)
        else mapOf("type" to "noul", "instructions" to instructions, "criteria" to mapOf("true" to yes, "false" to no))

    /** One option of [criteria] (option → description or null), at most 255. */
    fun choice(instructions: Any, criteria: Map<String, Any?>): Map<String, Any?> =
        mapOf("type" to "choice", "instructions" to instructions, "criteria" to criteria)

    /** The answers keyed by question id, plus `model` and `usage`. 429/529 retry twice, 1 s then 2 s apart. */
    suspend fun ask(key: String, state: Any, questions: Map<String, Map<String, Any?>>): Map<String, Any?> {
        Airgap.requireRemote()
        require(key.isNotBlank()) { "JEV_API_KEY is not set" }
        val body = jsonOf(mapOf("model" to MODEL, "state" to state, "questions" to questions))
        val client = TrikeHtxHttpClient(BASE, htxHeaders("Authorization" j "Bearer $key"))
        var wait = 1_000L
        while (true) {
            Airgap.requireRemote()
            try { return reifyMap(client.post("/v1/systemone", body)) }
            catch (e: HtxHttpError) { if ((e.status != 429 && e.status != 529) || wait > 2_000L) throw e }
            delay(wait); wait *= 2
        }
    }

    /** A missing, refused or malformed answer is unknown; it cannot supply negative evidence. */
    fun noulOf(answers: Map<*, *>, id: String): Double =
        requireNotNull(((answers[id] as? Map<*, *>)?.get("noul") as? Number)?.toDouble()
            ?.takeIf { it.isFinite() && it in 0.0..1.0 }) { "No valid noul for question '$id'" }

    /** Validate the normalized answer against the exact question before retaining or using it. */
    fun answerOf(answers: Map<*, *>, id: String, question: Map<String, Any?>): Map<*, *> {
        val answer = requireNotNull(answers[id] as? Map<*, *>) { "No answer for question '$id'" }
        require(answer["type"] == null || answer["type"] == question["type"]) { "Answer type differs for question '$id'" }
        when (question["type"]) {
            "noul" -> noulOf(answers, id)
            "choice" -> {
                val criteria = requireNotNull(question["criteria"] as? Map<*, *>) { "No choice criteria for question '$id'" }
                require(criteria.isNotEmpty() && criteria.keys.all { it is String }) { "Invalid choice criteria for question '$id'" }
                val probabilities = requireNotNull(answer["probabilities"] as? Map<*, *>) { "No probabilities for question '$id'" }
                require(probabilities.keys == criteria.keys) { "Choice probabilities differ from criteria for question '$id'" }
                for (value in probabilities.values) require((value as? Number)?.toDouble()
                    ?.let { it.isFinite() && it in 0.0..1.0 } == true) { "Invalid choice probability for question '$id'" }
                require((answer["confidence"] as? Number)?.toDouble()
                    ?.let { it.isFinite() && it in 0.0..1.0 } == true) { "Invalid choice confidence for question '$id'" }
                require(answer["choice"] is String && answer["choice"] in criteria) { "Invalid choice for question '$id'" }
            }
            else -> error("Unsupported question type '${question["type"]}'")
        }
        return answer
    }
}
