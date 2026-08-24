package borg.trikeshed.graal.subvm

import borg.trikeshed.hyperspace.HyperspaceRoutine
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.vm.Teleported
import keymux.KeyMux
import modelmux.acp.AcpCodec
import modelmux.acp.AcpMessage
import modelmux.acp.AcpResponse
import borg.trikeshed.lib.Series
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.htx.HtxKey
import borg.trikeshed.htx.HtxMethod
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.htx.parseHtxRequest
import borg.trikeshed.htx.htxHeaders
import kotlinx.coroutines.currentCoroutineContext

/**
 * JVM actual of [HyperspaceRoutine] — the routine lives **inside the subVM**.
 *
 * Membrane:
 *   host (KeyMux raw-key resolution + Htx) ⇄ subVM (InProcessIsolate) ⇄ routine
 *
 * 1. Host creates an [InProcessIsolate] per model, loads JS routine `hyperspace_chat`
 *    that **is** the embodied hyperspace (no AcpTool param, no eval).
 * 2. Routine is eval'd inside the isolate: it formats messages and calls
 *    `host.call("hyperspace_llm_call", modelId, messagesJson)` back to host.
 * 3. Host delegate `hyperspace_llm_call` resolves **raw** key via `KeyMux.get("llm.<model>.key")`
 *    (never ghosted), builds an OpenAI-compatible `POST /v1/chat/completions` via `Htx`,
 *    with `tools=[]`, and returns the teleported `AcpResponse`.
 * 4. SubVM returns that response to the caller — no synthetic fallback. If no key, it throws;
 *    if Htx missing, it throws. No fake model code.
 */
class JvmHyperspaceRoutine(
    override val modelId: String,
    private val keyMux: KeyMux,
    private val isolate: InProcessIsolate = InProcessIsolate(
        id = "hyperspace-$modelId-${System.nanoTime()}",
        facet = VmFacet.GRAAL_JS,
        budget = Budget(statements = 1_000_000, wallMillis = 30_000),
    )
) : HyperspaceRoutine {

    init {
        // Host delegate — raw-key resolution + membrane transport to real LLM
        isolate.delegate("hyperspace_llm_call") { args ->
            if (args.size < 2) throw IllegalArgumentException("hyperspace_llm_call(modelId, messagesJson)")
            val mid = (args[0] as? Teleported.Str)?.v ?: args[0].toString()
            val messagesJson = (args[1] as? Teleported.Str)?.v ?: args[1].toString()
            val result = kotlinx.coroutines.runBlocking { hostLlmCall(mid, messagesJson) }
            Teleported.Str(result)
        }

        // Routine itself — lives **inside the subVM**, no tools, no eval
        val routineJs = """
            // HyperspaceRoutine — embodied, no eval, no tools. Lives inside the subVM.
            function hyperspace_chat(modelId, messagesJson) {
                return host.call("hyperspace_llm_call", modelId, messagesJson);
            }
            hyperspace_chat;
        """.trimIndent()
        isolate.eval(routineJs, "hyperspace_routine.js")
    }

    override suspend fun chat(messages: Series<AcpMessage>): AcpResponse {
        val messagesJson = buildString {
            append("[")
            for (i in 0 until messages.size) {
                if (i > 0) append(",")
                val msg = messages[i]
                val role = msg.a
                val content = msg.b
                append("{\"role\":${jsonStr(role)},\"content\":${jsonStr(content)}}")
            }
            append("]")
        }
        val teleported = isolate.call("hyperspace_chat", Teleported.Str(modelId), Teleported.Str(messagesJson))
        val raw = (teleported as? Teleported.Str)?.v ?: teleported.toString()
        return AcpCodec.parseResponse(raw)
    }

    private suspend fun hostLlmCall(mid: String, messagesJson: String): String {
        val rawKey = keyMux.get("llm.${mid}.key")
            ?: keyMux.get("llm.default.key")
            ?: keyMux.get("llm.${mid.substringBefore("/")}.key")
            ?: throw IllegalStateException("No raw key for model $mid — KeyMux has no llm.${mid}.key and no llm.default.key (keys are ghosted in logs, but upstream needs raw)")

        val baseUrl = keyMux.get("llm.${mid}.base_url")
            ?: keyMux.get("llm.default.base_url")
            ?: keyMux.get("llm.${mid.substringBefore("/")}.base_url")
            ?: "https://api.openai.com/v1"

        val htx = currentCoroutineContext()[HtxKey]
            ?: throw IllegalStateException("No HtxKey in coroutine context — JvmHyperspaceRoutine requires MuxReactorElement + Htx to membrane to LLM")

        val body = """{"model":"${mid.substringAfterLast("/")}","messages":$messagesJson,"tools":[]}"""
        val headers = htxHeaders("Authorization" j "Bearer $rawKey", "Content-Type" j "application/json")
        val req = parseHtxRequest(url = "$baseUrl/chat/completions", method = HtxMethod.POST, body = ByteSeries(body.encodeToByteArray())).copy(headers = headers)
        val resp = htx.request(req)
        val respBody = resp.body.toArray().decodeToString()
        if (resp.status !in 200..299) throw IllegalStateException("Hyperspace LLM call failed ${resp.status}: ${respBody.take(500)}")
        return respBody
    }

    private fun jsonStr(s: String): String = "\"" + s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n") + "\""

    override fun close() = isolate.close()
}
