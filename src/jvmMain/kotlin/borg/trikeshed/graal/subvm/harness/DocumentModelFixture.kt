package borg.trikeshed.graal.subvm.harness

import borg.trikeshed.htx.HtxElement
import borg.trikeshed.htx.HtxExchangeLifecycle
import borg.trikeshed.htx.HtxExchangeResult
import borg.trikeshed.htx.HtxExchangeState
import borg.trikeshed.htx.HtxRequest
import borg.trikeshed.htx.HtxResponse
import borg.trikeshed.htx.HtxRouteService
import borg.trikeshed.htx.openHtxElement
import borg.trikeshed.lib.ByteSeries
import borg.trikeshed.lib.j
import borg.trikeshed.modelmux.ModelResponse
import borg.trikeshed.modelmux.Prompt
import borg.trikeshed.modelmux.PromptMessage
import borg.trikeshed.narsese.DocumentModel
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.userspace.reactor.MuxReactorElement
import keymux.KeyMux
import keymux.TestKeySource
import kotlin.coroutines.coroutineContext
import modelmux.ModelMux

/**
 * The ONLY HTX provider fixture in the harness tree. Its route service decodes
 * the captured chat/completions request body into a [Prompt] for the caller's
 * deterministic responder, then re-encodes the reply as ordinary
 * choices+usage JSON — so every harness model call still crosses
 * [ModelMux.chat] against a real, OPEN, ACTIVE [MuxReactorElement] and mints a
 * real receipt. No fixture logic runs inside [DocumentModel]/the curator.
 */
object DocumentModelFixture {
    const val MODEL_ID = "fixture"

    /** Owns the fixture's HTX and reactor elements; close after the caller's feed(s) drain. */
    class Session(
        val htx: HtxElement,
        val reactor: MuxReactorElement,
        val model: DocumentModel,
    ) {
        suspend fun close() {
            try { htx.close() } finally { reactor.close() }
        }
    }

    /** Opens the fixture transport bound to [responder]. Ambient [coroutineContext] carries htx+reactor for callers. */
    suspend fun open(modelId: String = MODEL_ID, responder: suspend (Prompt) -> ModelResponse): Session {
        val keyMux = KeyMux { bind("llm.$modelId.key", TestKeySource()) }
        val routeService = object : HtxRouteService {
            override suspend fun exchange(state: HtxExchangeState, request: HtxRequest): HtxExchangeResult {
                val body = JsonSupport.parse(request.body.toArray().decodeToString()) as Map<*, *>
                val rawMessages = body["messages"] as List<*>
                val messages = rawMessages.map {
                    val entry = it as Map<*, *>
                    val content = entry["content"] as String
                    when (entry["role"] as String) {
                        "system" -> PromptMessage.System(content)
                        "assistant" -> PromptMessage.Assistant(content)
                        else -> PromptMessage.User(content)
                    }
                }
                val prompt = Prompt(
                    messages = messages.size j { i -> messages[i] },
                    modelId = body["model"] as String,
                    temperature = (body["temperature"] as? Number)?.toDouble() ?: 0.7,
                    maxTokens = (body["max_tokens"] as? Number)?.toInt() ?: 1024,
                )
                val response = responder(prompt)
                val json = JsonSupport.stringify(mapOf(
                    "choices" to listOf(mapOf("message" to mapOf("content" to response.content))),
                    "usage" to mapOf(
                        "prompt_tokens" to response.usage.promptTokens,
                        "completion_tokens" to response.usage.completionTokens,
                    ),
                ))
                return HtxExchangeResult(state.copy(
                    lifecycle = HtxExchangeLifecycle.RESPONDED,
                    request = request,
                    response = HtxResponse(status = 200, body = ByteSeries(json.encodeToByteArray())),
                ))
            }
        }
        val htx = openHtxElement(routeService = routeService)
        val reactor = MuxReactorElement(keyMux = keyMux, modelMuxProvider = {
            ModelMux(keyMux) { model(modelId, caps = setOf("chat")) }
        })
        reactor.open()
        val model = DocumentModel.through(coroutineContext + htx + reactor)
        return Session(htx, reactor, model)
    }
}
