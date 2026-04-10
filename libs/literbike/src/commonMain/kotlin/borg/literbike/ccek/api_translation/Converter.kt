package borg.literbike.ccek.api_translation

import kotlinx.serialization.json.*

/**
 * API Format Converter
 *
 * Converts between unified API format and provider-specific formats.
 */
object ApiConverter {

    // ======================== OpenAI ========================

    fun toOpenAi(request: UnifiedChatRequest): JsonObject {
        val messages = buildJsonArray {
            request.messages.forEach { m ->
                add(buildJsonObject {
                    put("role", roleToOpenAi(m.role))
                    put("content", contentToString(m.content))
                    m.toolCalls?.let { tc ->
                        put("tool_calls", buildJsonArray {
                            tc.forEach { add(buildJsonObject {
                                put("id", JsonPrimitive(it.id))
                                put("type", JsonPrimitive("function"))
                                put("function", buildJsonObject {
                                    put("name", JsonPrimitive(it.name))
                                    put("arguments", JsonPrimitive(it.arguments))
                                })
                            })}
                        })
                    }
                    m.toolCallId?.let { put("tool_call_id", JsonPrimitive(it)) }
                })
            }
        }

        return buildJsonObject {
            put("model", JsonPrimitive(request.model))
            put("messages", messages)
            request.temperature?.let { put("temperature", JsonPrimitive(it)) }
            request.maxTokens?.let { put("max_tokens", JsonPrimitive(it.toInt())) }
            request.stream?.let { put("stream", JsonPrimitive(it)) }
        }
    }

    fun fromOpenAi(response: JsonObject): UnifiedChatResponse? {
        val id = response["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val model = response["model"]?.jsonPrimitive?.contentOrNull ?: return null
        val choicesArr = response["choices"]?.jsonArray ?: return null

        val choices = choicesArr.mapNotNull { c ->
            val obj = c.jsonObject
            val idx = obj["index"]?.jsonPrimitive?.contentOrNull?.toUIntOrNull() ?: 0u
            val roleStr = obj["message"]?.jsonObject?.get("role")?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val role = roleFromOpenAi(roleStr)
            val contentStr = obj["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val content = MessageContent.TextContent(contentStr)
            val finish = obj["finish_reason"]?.jsonPrimitive?.contentOrNull
            Choice(index = idx, message = UnifiedMessage(role = role, content = content), finishReason = finish)
        }

        val usage = response["usage"]?.jsonObject?.let { u ->
            Usage(
                promptTokens = u["prompt_tokens"]?.jsonPrimitive?.contentOrNull?.toUIntOrNull() ?: 0u,
                completionTokens = u["completion_tokens"]?.jsonPrimitive?.contentOrNull?.toUIntOrNull() ?: 0u,
                totalTokens = u["total_tokens"]?.jsonPrimitive?.contentOrNull?.toUIntOrNull() ?: 0u
            )
        }

        return UnifiedChatResponse(id = id, model = model, choices = choices, usage = usage)
    }

    // ======================== Anthropic ========================

    fun toAnthropic(request: UnifiedChatRequest): JsonObject {
        val messages = buildJsonArray {
            request.messages.filter { it.role != MessageRole.System }.forEach { m ->
                add(buildJsonObject {
                    put("role", JsonPrimitive(roleToAnthropic(m.role)))
                    put("content", JsonPrimitive(contentToString(m.content)))
                })
            }
        }

        return buildJsonObject {
            put("model", JsonPrimitive(request.model))
            put("messages", messages)
            put("max_tokens", JsonPrimitive(request.maxTokens?.toInt() ?: 1024))
            request.system?.let { put("system", JsonPrimitive(it)) }
        }
    }

    fun fromAnthropic(response: JsonObject): UnifiedChatResponse? {
        val id = response["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val model = response["model"]?.jsonPrimitive?.contentOrNull ?: return null
        val contentArr = response["content"]?.jsonArray ?: return null
        val contentText = contentArr.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: return null
        val finish = response["stop_reason"]?.jsonPrimitive?.contentOrNull

        val usage = response["usage"]?.jsonObject?.let { u ->
            Usage(
                promptTokens = u["input_tokens"]?.jsonPrimitive?.contentOrNull?.toUIntOrNull() ?: 0u,
                completionTokens = u["output_tokens"]?.jsonPrimitive?.contentOrNull?.toUIntOrNull() ?: 0u,
                totalTokens = ((u["input_tokens"]?.jsonPrimitive?.contentOrNull?.toUIntOrNull() ?: 0u) +
                        (u["output_tokens"]?.jsonPrimitive?.contentOrNull?.toUIntOrNull() ?: 0u))
            )
        }

        return UnifiedChatResponse(
            id = id, model = model,
            choices = listOf(Choice(
                index = 0u,
                message = UnifiedMessage(role = MessageRole.Assistant, content = MessageContent.TextContent(contentText)),
                finishReason = finish
            )),
            usage = usage
        )
    }

    // ======================== Gemini ========================

    fun toGemini(request: UnifiedChatRequest): JsonObject {
        val contents = buildJsonArray {
            request.messages.filter { it.role != MessageRole.System }.forEach { m ->
                add(buildJsonObject {
                    put("role", JsonPrimitive(roleToGemini(m.role)))
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("text", JsonPrimitive(contentToString(m.content)))
                        })
                    })
                })
            }
        }

        return buildJsonObject {
            put("contents", contents)
            request.system?.let {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", JsonPrimitive(it)) })
                    })
                })
            }
        }
    }

    fun fromGemini(response: JsonObject): UnifiedChatResponse? {
        val candidates = response["candidates"]?.jsonArray ?: return null
        val firstCandidate = candidates.firstOrNull()?.jsonObject ?: return null
        val contentObj = firstCandidate["content"]?.jsonObject ?: return null
        val parts = contentObj["parts"]?.jsonArray ?: return null
        val text = parts.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: return null

        return UnifiedChatResponse(
            id = "gemini-1", model = "gemini",
            choices = listOf(Choice(
                index = 0u,
                message = UnifiedMessage(role = MessageRole.Assistant, content = MessageContent.TextContent(text)),
                finishReason = null
            )),
            usage = null
        )
    }

    // ======================== Helper functions ========================

    private fun roleToOpenAi(role: MessageRole): String = when (role) {
        MessageRole.System -> "system"
        MessageRole.User -> "user"
        MessageRole.Assistant -> "assistant"
        MessageRole.Tool -> "tool"
    }

    private fun roleFromOpenAi(role: String): MessageRole = when (role) {
        "system" -> MessageRole.System
        "user" -> MessageRole.User
        "assistant" -> MessageRole.Assistant
        "tool" -> MessageRole.Tool
        else -> MessageRole.User
    }

    private fun roleToAnthropic(role: MessageRole): String = when (role) {
        MessageRole.User -> "user"
        MessageRole.Assistant -> "assistant"
        else -> "user"
    }

    private fun roleToGemini(role: MessageRole): String = when (role) {
        MessageRole.User -> "user"
        MessageRole.Assistant -> "model"
        else -> "user"
    }

    private fun contentToString(content: MessageContent): String = when (content) {
        is MessageContent.TextContent -> content.text
        is MessageContent.PartsContent -> content.parts.filterIsInstance<ContentPart.TextPart>().joinToString(" ") { it.text }
    }
}
