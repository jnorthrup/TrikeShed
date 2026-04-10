package borg.literbike.ccek.keymux

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * API Data Models for Model Facade
 * Ported from cc-switch
 */

// ============================================================================
// OpenAI API Models
// ============================================================================

@Serializable
data class OpenAIRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val maxTokens: UInt? = null,
    val temperature: Float? = null,
    val stream: Boolean? = null,
    val tools: List<OpenAITool>? = null,
    val toolChoice: JsonElement? = null
)

@Serializable
data class OpenAIMessage(
    val role: String,
    val content: JsonElement? = null,
    val toolCalls: List<OpenAIToolCall>? = null,
    val toolCallId: String? = null
)

@Serializable
data class OpenAIToolCall(
    val id: String,
    val type: String,
    val function: OpenAIFunction
)

@Serializable
data class OpenAIFunction(
    val name: String,
    val arguments: String
)

@Serializable
data class OpenAITool(
    val type: String,
    val function: OpenAIFunctionDef
)

@Serializable
data class OpenAIFunctionDef(
    val name: String,
    val description: String? = null,
    val parameters: JsonElement
)

@Serializable
data class OpenAIResponse(
    val id: String,
    val object: String,
    val created: ULong,
    val model: String,
    val choices: List<OpenAIChoice>,
    val usage: OpenAIUsage? = null
)

@Serializable
data class OpenAIChoice(
    val index: UInt,
    val message: OpenAIMessage,
    val finishReason: String? = null
)

@Serializable
data class OpenAIUsage(
    val promptTokens: UInt,
    val completionTokens: UInt,
    val totalTokens: UInt
)

// ============================================================================
// Anthropic API Models
// ============================================================================

@Serializable
data class AnthropicRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    val maxTokens: UInt,
    val system: JsonElement? = null,
    val temperature: Float? = null,
    val stream: Boolean? = null,
    val tools: List<AnthropicTool>? = null,
    val toolChoice: JsonElement? = null
)

@Serializable
data class AnthropicMessage(
    val role: String,
    val content: JsonElement
)

@Serializable
data class AnthropicTool(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonElement
)

@Serializable
data class AnthropicResponse(
    val id: String,
    val type: String,
    val role: String,
    val content: List<AnthropicResponseContent>,
    val model: String,
    val stopReason: String? = null,
    val usage: AnthropicUsage? = null
)

@Serializable
sealed class AnthropicResponseContent {
    @Serializable
    data class Text(val text: String) : AnthropicResponseContent()

    @Serializable
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonElement
    ) : AnthropicResponseContent()
}

@Serializable
data class AnthropicUsage(
    val inputTokens: UInt,
    val outputTokens: UInt
)
