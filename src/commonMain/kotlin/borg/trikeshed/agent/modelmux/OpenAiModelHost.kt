package borg.trikeshed.agent.modelmux

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.parse.confix.*
import kotlinx.coroutines.CancellationException
import borg.trikeshed.agent.modelmux.acp.*

/** OpenAI wire routes; transport and admitted exchanges remain owned by the existing HTX runtime. */
class OpenAiModelHost(
    val mux: ModelMux,
    val completion: suspend (String, ConfixDoc) -> Result<ConfixDoc> = { model, request ->
        mux.completion(model, request)
    },
    val routes: suspend (HtxRequest) -> HtxResponse? = { null },
) {
    suspend fun handle(request: HtxRequest): HtxResponse? {
        routes(request)?.let { return it }
        return when (request.target.requestPath.substringBefore('?').trimEnd('/')) {
            "/v1/models" -> if (request.method != HtxMethod.GET) error(405, "method_not_allowed", "Use GET") else {
                json(200, mapOf(
                    "object" to "list",
                    "data" to (mux.listModels("chat") α { card ->
                        mapOf("id" to card.id, "object" to "model", "created" to 0,
                            "owned_by" to (card.providerTag ?: "modelmux"))
                    }).view,
                ))
            }
            "/v1/chat/completions" -> if (request.method != HtxMethod.POST)
                error(405, "method_not_allowed", "Use POST") else complete(request)
            else -> null
        }
    }

    suspend fun complete(request: HtxRequest): HtxResponse {
        val doc = try {
            val text = request.body.toArray().decodeToString(throwOnInvalidSequence = true)
            reifyStrict(text)
            confixDoc(text)
        } catch (_: Exception) {
            return error(400, "invalid_json", "Request must contain one valid JSON object")
        }
        val model = doc.value("model") as? String
        if (model.isNullOrBlank()) return error(400, "model_required", "model is required")
        if (mux.listModels("chat").view.none { it.id == model })
            return error(404, "model_not_found", "Model is not in the hosted catalog")
        if (doc.value("stream") == true)
            return error(501, "streaming_unsupported", "This host supports non-streaming completions")
        try {
            AcpCodec.encodeCompletion(doc, model)
        } catch (invalid: Exception) {
            return error(400, "invalid_request", invalid.message ?: "Invalid completion request")
        }
        val result = try {
            completion(model, doc)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: IllegalArgumentException) {
            return error(400, "invalid_request", invalid.message ?: "Invalid completion request")
        } catch (_: Exception) {
            return error(502, "upstream_error", "Model completion failed")
        }
        val failure = result.exceptionOrNull()
        if (failure is CancellationException) throw failure
        if (failure is ModelHttpFailure && failure.status == 202)
            return error(202, "upstream_pending", "Upstream model has not returned a completion")
        if (failure is ModelHttpFailure && failure.status in 400..599)
            return error(failure.status, "upstream_http_${failure.status}", "Upstream model returned HTTP ${failure.status}")
        if (failure != null) return error(502, "upstream_error", "Model completion failed")
        return HtxResponse(200, ByteSeries(result.getOrThrow().src.toArray()), jsonHeaders())
    }

    fun json(status: Int, value: Any?): HtxResponse =
        HtxResponse(status, ByteSeries(jsonOf(value).encodeToByteArray()), jsonHeaders())

    fun error(status: Int, code: String, message: String): HtxResponse = json(status,
        mapOf("error" to mapOf("message" to message,
            "type" to when {
                status == 202 -> "model_pending"
                status == 429 -> "rate_limit_error"
                status < 500 -> "invalid_request_error"
                else -> "server_error"
            }, "code" to code)))

    fun jsonHeaders(): HtxHeaders = htxHeaders("Content-Type" j "application/json; charset=utf-8")
}
