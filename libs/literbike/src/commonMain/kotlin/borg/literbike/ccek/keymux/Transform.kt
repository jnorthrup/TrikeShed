package borg.literbike.ccek.keymux

import kotlinx.serialization.json.*

/**
 * Protocol translation logic
 * Ported from cc-switch (MIT License)
 */

/**
 * Anthropic Request -> OpenAI Request
 */
fun anthropicToOpenai(body: JsonElement): JsonElement {
    val obj = body.jsonObject
    val result = mutableMapOf<String, JsonElement>()

    obj["model"]?.let { model -> result["model"] = model }

    val messages = mutableListOf<JsonElement>()

    // Handle system prompt
    obj["system"]?.let { system ->
        system.takeIf { it is JsonPrimitive }?.let {
            messages.add(buildJsonObject {
                put("role", JsonPrimitive("system"))
                put("content", it)
            })
        }
        (system as? JsonArray)?.forEach { msg ->
            (msg as? JsonObject)?.get("text")?.let { text ->
                messages.add(buildJsonObject {
                    put("role", JsonPrimitive("system"))
                    put("content", text)
                })
            }
        }
    }

    // Convert messages
    obj["messages"]?.let { msgs ->
        (msgs as? JsonArray)?.forEach { msg ->
            val msgObj = msg as? JsonObject ?: return@forEach
            val role = (msgObj["role"] as? JsonPrimitive)?.content ?: "user"
            val content = msgObj["content"]

            when (content) {
                is JsonPrimitive -> {
                    messages.add(buildJsonObject {
                        put("role", JsonPrimitive(role))
                        put("content", content)
                    })
                }
                is JsonArray -> {
                    messages.add(buildJsonObject {
                        put("role", JsonPrimitive(role))
                        put("content", content)
                    })
                }
                else -> {}
            }
        }
    }

    result["messages"] = JsonArray(messages)

    // Pass through common parameters
    for (key in listOf("max_tokens", "temperature", "top_p", "stream")) {
        obj[key]?.let { result[key] = it }
    }

    return JsonObject(result)
}

/**
 * OpenAI Response -> Anthropic Response
 */
fun openaiToAnthropic(body: JsonElement): JsonElement {
    val obj = body.jsonObject
    val choices = (obj["choices"] as? JsonArray)
    val choice = choices?.firstOrNull() as? JsonObject
    val message = choice?.get("message") as? JsonObject

    val content = mutableListOf<JsonElement>()
    message?.get("content")?.let { c ->
        (c as? JsonPrimitive)?.content?.let { text ->
            content.add(buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(text))
            })
        }
    }

    val usage = obj["usage"] as? JsonObject
    val inputTokens = (usage?.get("prompt_tokens") as? JsonPrimitive)?.long?.toUInt() ?: 0u
    val outputTokens = (usage?.get("completion_tokens") as? JsonPrimitive)?.long?.toUInt() ?: 0u

    return buildJsonObject {
        put("id", JsonPrimitive((obj["id"] as? JsonPrimitive)?.content ?: ""))
        put("type", JsonPrimitive("message"))
        put("role", JsonPrimitive("assistant"))
        put("content", JsonArray(content))
        put("model", JsonPrimitive((obj["model"] as? JsonPrimitive)?.content ?: ""))
        put("usage", buildJsonObject {
            put("input_tokens", JsonPrimitive(inputTokens))
            put("output_tokens", JsonPrimitive(outputTokens))
        })
    }
}
