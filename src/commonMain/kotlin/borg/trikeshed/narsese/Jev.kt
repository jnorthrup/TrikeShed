package borg.trikeshed.narsese

import borg.trikeshed.htx.htxHeaders
import borg.trikeshed.jules.HtxHttpException
import borg.trikeshed.jules.TrikeHtxHttpClient
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyMap
import kotlinx.coroutines.delay

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
        val body = jsonOf(mapOf("model" to MODEL, "state" to state, "questions" to questions))
        val client = TrikeHtxHttpClient(BASE, htxHeaders("Authorization" j "Bearer $key"))
        var wait = 1_000L
        while (true) {
            try { return reifyMap(client.post("/v1/systemone", body)) }
            catch (e: HtxHttpException) { if ((e.status != 429 && e.status != 529) || wait > 2_000L) throw e }
            delay(wait); wait *= 2
        }
    }

    /** The noul of answer [id], or 0. */
    fun noulOf(answers: Map<*, *>, id: String): Double = ((answers[id] as? Map<*, *>)?.get("noul") as? Number)?.toDouble() ?: 0.0
}
