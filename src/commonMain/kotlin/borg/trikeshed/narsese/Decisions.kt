package borg.trikeshed.narsese

import borg.trikeshed.htx.HtxHttpError
import borg.trikeshed.htx.htxHeaders
import borg.trikeshed.htx.TrikeHtxHttpClient
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import kotlinx.coroutines.delay
import borg.trikeshed.agent.modelmux.Airgap

/**
 * OpenAI Decisions, `POST /v1/decisions` (gpt-6-luna): [Jev]'s contract on another wire. [Jev] questions go out
 * as predicate (noul) and choice over one `input`, each named by its id; answers come back in [Jev]'s shape,
 * keyed by id: `noul` for a predicate, `choice`, `probabilities` and `confidence` for a choice.
 * A refused question has no answer, never a `none` choice. Rides the HTX reactor in context.
 */
object Decisions {
    const val BASE = "https://api.openai.com"
    const val MODEL = "gpt-6-luna"
    const val MAX_QUESTIONS = 200
    const val MAX_TEXT_CHARS = 1_048_576

    /** The request: the state as JSON text (a string state as is), the questions named by their ids. */
    fun body(state: Any, questions: Map<String, Map<String, Any?>>): String {
        require(questions.size in 1..MAX_QUESTIONS) { "Decisions requires 1..$MAX_QUESTIONS questions" }
        return jsonOf(mapOf(
            "model" to MODEL,
            "input" to (state as? String ?: jsonOf(state)),
            "questions" to questions.map { (id, q) -> question(id, q) },
        ))
    }

    private fun text(value: Any?, field: String): String {
        require(value is String && value.isNotBlank() && value.length <= MAX_TEXT_CHARS) { "invalid Decisions $field" }
        return value
    }

    private fun question(id: String, q: Map<String, Any?>): Map<String, Any?> {
        text(id, "question name")
        val instruction = requireNotNull(q["instructions"]) { "Decisions instructions are required" }
        val instructions = text(instruction as? String ?: jsonOf(instruction), "instructions")
        val criteria = q["criteria"] as? Map<*, *>
        return when (q["type"]) {
            "noul" -> {
                require(q["criteria"] == null || criteria != null) { "invalid Decisions predicate criteria" }
                require(criteria == null || criteria.keys.all { it == "true" || it == "false" }) { "invalid Decisions predicate criteria" }
                val described = buildString {
                    append(instructions)
                    for ((value, description) in criteria.orEmpty()) if (description != null) {
                        append(if (value == "true") "\nTrue: " else "\nFalse: ")
                        append(text(description, "predicate description"))
                    }
                }
                mapOf("type" to "predicate", "name" to id, "instructions" to text(described, "instructions"))
            }
            "choice" -> {
                require(criteria != null && criteria.size in 2..255) { "Decisions requires 2..255 choices" }
                mapOf("type" to "choice", "name" to id, "instructions" to instructions,
                    "choices" to criteria.map { (value, description) ->
                        val choice = text(value, "choice value")
                        if (description == null) mapOf("value" to choice)
                        else mapOf("value" to choice, "description" to text(description, "choice description"))
                    })
            }
            else -> error("Decisions maps noul and choice questions, not '${q["type"]}'")
        }
    }

    private fun probability(value: Any?, field: String): Double {
        val probability = (value as? Number)?.toDouble()
        require(probability != null && probability.isFinite() && probability in 0.0..1.0) { "invalid Decisions $field" }
        return probability
    }

    /** Validate before normalization. Refusals are retained separately and never become a false predicate or a choice. */
    fun answers(reply: Map<*, *>, questions: Map<String, Map<String, Any?>>? = null): Map<String, Any?> {
        val model = text(reply["model"], "model")
        val answers = reply["answers"] as? List<*> ?: error("Decisions returned no answers array")
        require(answers.size in 1..MAX_QUESTIONS) { "invalid Decisions answer count" }
        val out = LinkedHashMap<String, Any?>()
        val names = LinkedHashSet<String>()
        val refusals = mutableListOf<String>()
        for (answer in answers) {
            val m = answer as? Map<*, *> ?: error("invalid Decisions answer")
            val id = text(m["name"], "answer name")
            require(names.add(id)) { "duplicate Decisions answer name" }
            val requested = questions?.let { requireNotNull(it[id]) { "unrequested Decisions answer name" } }
            when (m["type"]) {
                "predicate" -> {
                    require(requested == null || requested["type"] == "noul") { "Decisions answer type differs from question" }
                    out[id] = mapOf("noul" to probability(m["probability"], "predicate probability"))
                }
                "choice" -> {
                    require(requested == null || requested["type"] == "choice") { "Decisions answer type differs from question" }
                    val distribution = m["probabilities"] as? List<*> ?: error("invalid Decisions choice probabilities")
                    require(distribution.size in 2..255) { "invalid Decisions choice count" }
                    val probabilities = LinkedHashMap<String, Double>()
                    for (entry in distribution) {
                        val p = entry as? Map<*, *> ?: error("invalid Decisions choice probability")
                        val value = text(p["value"], "choice value")
                        require(value !in probabilities) { "duplicate Decisions choice value" }
                        probabilities[value] = probability(p["probability"], "choice probability")
                    }
                    if (requested != null) require(probabilities.keys == (requested["criteria"] as? Map<*, *>)?.keys) {
                        "Decisions choices differ from question"
                    }
                    val choice = text(m["choice"], "choice")
                    require(choice in probabilities) { "Decisions choice is absent from probabilities" }
                    out[id] = mapOf("choice" to choice, "probabilities" to probabilities,
                        "confidence" to probability(m["confidence"], "choice confidence"))
                }
                "refusal" -> refusals.add(id)
                else -> error("unsupported Decisions answer type")
            }
        }
        if (questions != null) require(names == questions.keys) { "Decisions omitted requested answers" }
        return mapOf("model" to model, "usage" to reply["usage"], "answers" to out, "refusals" to refusals)
    }

    /** [Jev.ask]'s signature and reply shape. 429/503 retry twice, 1 s then 2 s apart. */
    suspend fun ask(key: String, state: Any, questions: Map<String, Map<String, Any?>>): Map<String, Any?> {
        Airgap.requireRemote()
        require(key.isNotBlank()) { "OPENAI_API_KEY is not set" }
        val payload = body(state, questions)
        val client = TrikeHtxHttpClient(BASE, htxHeaders("Authorization" j "Bearer $key"))
        var wait = 1_000L
        while (true) {
            Airgap.requireRemote()
            try {
                val reply = reifyStrict(client.post("/v1/decisions", payload)) as? Map<*, *>
                    ?: error("Decisions returned no object")
                Airgap.requireRemote()
                return answers(reply, questions)
            }
            catch (e: HtxHttpError) { if ((e.status != 429 && e.status != 503) || wait > 2_000L) throw e }
            delay(wait); wait *= 2
        }
    }
}
