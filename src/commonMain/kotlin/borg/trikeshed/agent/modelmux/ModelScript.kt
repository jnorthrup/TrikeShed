package borg.trikeshed.agent.modelmux

import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** Endpoint identity of an in-process scripted provider: `script:<name>`. It opens no socket. */
typealias ModelScriptUrl = String

/**
 * One scripted speaker. [match] is a substring of the request's system message (the user
 * message when the request has no system message); [replies] answer its successive matching
 * calls in order, and the last reply repeats once they are spent.
 */
data class ModelScriptCue(val match: String, val replies: Series<String>)

/**
 * Scripted chat-completions provider. A ModelMux card whose base URL is this script's [url]
 * is answered in process from the operator-authored cues: ModelMux routing, activity and
 * receipts still run, the airgap admits it because no request leaves the process, and every
 * answer reports zero tokens. The first cue whose [ModelScriptCue.match] occurs answers;
 * [fallback] answers a request no cue matches. [delayMs] holds each answer, varied ±40% at
 * random the way a model's latency varies.
 */
class ModelScript(val name: String, val cues: Series<ModelScriptCue>, val fallback: String, val delayMs: Long = 0) {
    val url: ModelScriptUrl get() = SCHEME + name
    val turns = IntArray(cues.size)
    val gate = Mutex()
    var calls = 0

    /** Answer one OpenAI-shaped chat request body with an OpenAI-shaped chat-completion body. */
    suspend fun answer(request: String, wireModel: String): String {
        val messages = (reifyMap(request)["messages"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
        fun content(role: String): String = messages.filter { it["role"] == role }.joinToString("\n") { it["content"]?.toString().orEmpty() }
        val probe = content("system").ifEmpty { content("user") }
        val (call, reply) = gate.withLock {
            val index = cues.view.indexOfFirst { it.match in probe }
            ++calls j if (index < 0) fallback else cues[index].replies.let { it[minOf(turns[index]++, it.size - 1)] }
        }
        if (delayMs > 0) delay(Random.nextLong(delayMs * 3 / 5, delayMs * 7 / 5 + 1))
        return jsonOf(_m["id" j "$name-$call", "object" j "chat.completion", "model" j wireModel,
            "choices" j _l[_m["index" j 0, "message" j _m["role" j "assistant", "content" j reply], "finish_reason" j "stop"]],
            "usage" j _m["prompt_tokens" j 0, "completion_tokens" j 0, "total_tokens" j 0]])
    }

    companion object {
        const val SCHEME = "script:"

        /** Scripts registered for this process, by endpoint identity. */
        var registry: Map<ModelScriptUrl, ModelScript> = emptyMap()

        fun owns(url: String?): Boolean = url?.startsWith(SCHEME) == true
        fun register(script: ModelScript): ModelScriptUrl = script.url.also { registry = registry + _m[it j script] }
        fun registered(url: String): ModelScript? = registry[url]

        /** `{"name", "delayMs"?, "fallback"?, "cues": [{"match", "replies": [...]}, ...]}` */
        fun parse(json: String): ModelScript {
            val doc = reifyMap(json)
            val name = requireNotNull(doc["name"] as? String) { "Model script requires a name" }
            require(name.isNotBlank() && name.none { it.isWhitespace() }) { "Model script name must be one token" }
            val cues = (doc["cues"] as? List<*>).orEmpty().map { cue ->
                val fields = requireNotNull(cue as? Map<*, *>) { "Model script cue must be an object" }
                val match = requireNotNull(fields["match"] as? String) { "Model script cue requires a match" }
                val replies = (fields["replies"] as? List<*>).orEmpty().map { it.toString() }
                require(replies.isNotEmpty()) { "Model script cue '$match' has no replies" }
                ModelScriptCue(match, replies.toSeries())
            }
            return ModelScript(name, cues.toSeries(), doc["fallback"] as? String ?: "", (doc["delayMs"] as? Number)?.toLong() ?: 0)
        }
    }
}
